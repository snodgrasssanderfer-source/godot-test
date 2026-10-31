from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import subprocess
import tempfile
import threading
import unittest
from unittest.mock import patch

import runtime


class PrivateRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.project = Path(self.temp.name) / "project"
        self.project.mkdir()
        subprocess.run(["git", "init", "-q", str(self.project)], check=True)
        self.scope = patch.object(runtime, "PROJECT", self.project)
        self.scope.start()
        self.addCleanup(self.scope.stop)

    def ignore_outputs(self):
        (self.project / ".gitignore").write_text("persona-eval-output/\n")

    def test_private_run_requires_ignored_untracked_real_directory(self):
        with self.assertRaisesRegex(ValueError, "Git 忽略"):
            runtime.private_run("fiction")
        self.ignore_outputs()
        output = runtime.private_run("fiction")
        self.assertEqual(output, self.project / "persona-eval-output/fiction")
        self.assertEqual(output.stat().st_mode & 0o777, 0o700)
        self.assertEqual(output.parent.stat().st_mode & 0o777, 0o700)
        tracked = output / "fiction.json"
        tracked.write_text("{}")
        subprocess.run(["git", "-C", str(self.project), "add", "-f", str(tracked)], check=True)
        with self.assertRaisesRegex(ValueError, "已跟踪"):
            runtime.private_run("another")

    def test_private_run_rejects_traversal_and_symlink_destinations(self):
        self.ignore_outputs()
        for name in ("../outside", "..", "/tmp/outside", "fiction/slash", "fiction name"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                runtime.private_run(name)
        outside = Path(self.temp.name) / "outside"
        outside.mkdir()
        root = self.project / "persona-eval-output"
        root.symlink_to(outside, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "符号链接"):
            runtime.private_run("fiction")
        root.unlink()
        root.mkdir()
        (root / "fiction").symlink_to(outside, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, "符号链接"):
            runtime.private_run("fiction")
        self.assertEqual(list(outside.iterdir()), [])

    def test_save_is_owner_only_and_atomic_for_concurrent_same_cache_key(self):
        self.ignore_outputs()
        target = runtime.private_run("fiction") / "request.json"
        barrier = threading.Barrier(2)
        original = Path.write_text

        def after_write(path, *args, **kwargs):
            result = original(path, *args, **kwargs)
            # Force both old fixed-name temporary writes to exist before either replace.
            if path.name.endswith(".tmp"):
                barrier.wait(timeout=5)
            return result

        with patch.object(Path, "write_text", after_write):
            with ThreadPoolExecutor(max_workers=2) as executor:
                futures = [executor.submit(runtime.save, target, {"fiction": value}) for value in ("alpha", "beta")]
                for future in futures:
                    future.result(timeout=10)
        self.assertIn(json.loads(target.read_text())["fiction"], ("alpha", "beta"))
        self.assertEqual(target.stat().st_mode & 0o777, 0o600)
        self.assertEqual([p.name for p in target.parent.iterdir()], ["request.json"])

    def test_digest_is_stable_for_reordered_json_and_unicode(self):
        self.assertEqual(runtime.digest({"b": 2, "a": "虚构"}), runtime.digest({"a": "虚构", "b": 2}))
        self.assertNotEqual(runtime.digest({"a": "虚构"}), runtime.digest({"a": "不同虚构"}))
        self.assertNotEqual(runtime.digest(b'"same"'), runtime.digest("\"same\""))


if __name__ == "__main__":
    unittest.main()
