"""Compile frozen production Kotlin, then call its real Gemini streaming client."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import threading
import urllib.request

HERE = Path(__file__).resolve().parent
PROJECT = HERE.parents[5]
REPO = Path(subprocess.check_output(["git", "-C", str(PROJECT), "rev-parse", "--show-toplevel"], text=True).strip())
SOURCE = HERE.parents[1] / "main/java/io/deepseekharness/mobile/companion"
PERSONA_EVAL = HERE.parent / "persona-eval"
PRODUCTION_FILES = [
    "CompanionContext.kt", "CompanionPrompt.kt", "PersonaVoicePrompt.kt", "GeminiWire.kt",
    "GeminiClient.kt", "CompanionPolicy.kt", "chat/QqFaces.kt",
    "OneBotProtocol.kt", "social/OneBotSegments.kt", "iteration/ModuleContract.kt",
]
# These extractions have byte-equivalence tests against Engine/WebResearch at the baseline.
EXTRACTED_FILES = ["CompanionTools.kt", "CompanionRequestPrompts.kt"]
NEW_POLICY_FILES = ["LatencyPolicy.kt"]
DEPENDENCIES = {
    "json-20240303.jar": ("org/json/json/20240303", "3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed"),
    "okhttp-4.12.0.jar": ("com/squareup/okhttp3/okhttp/4.12.0", "b1050081b14bb7a3a7e55a4d3ef01b5dcfabc453b4573a4fc019767191d5f4e0"),
    "okio-jvm-3.6.0.jar": ("com/squareup/okio/okio-jvm/3.6.0", "67543f0736fc422ae927ed0e504b98bc5e269fda0d3500579337cb713da28412"),
}


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def digest(value):
    return hashlib.sha256(value if isinstance(value, bytes) else canonical(value).encode()).hexdigest()


def save(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    descriptor, name = tempfile.mkstemp(prefix=".write-", dir=path.parent)
    temporary = Path(name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as output:
            output.write(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


def private_run(name):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,79}", name):
        raise ValueError("run 名称只能包含字母、数字、点、横线和下划线")
    root = PROJECT / "latency-output"
    if root.resolve() != root.absolute() or (root / name).resolve() != (root / name).absolute():
        raise ValueError("私有输出目录不能是符号链接")
    check = subprocess.run(["git", "-C", str(PROJECT), "check-ignore", "-q", "latency-output/privacy-check.json"])
    tracked = subprocess.check_output(["git", "-C", str(PROJECT), "ls-files", "--", str(root)], text=True)
    if check.returncode != 0 or tracked.strip():
        raise ValueError("latency-output 必须已被 Git 忽略且没有已跟踪文件")
    target = root / name
    target.mkdir(parents=True, exist_ok=True, mode=0o700)
    root.chmod(0o700)
    target.chmod(0o700)
    return target


def resolve_ref(ref):
    if ref in (None, "worktree"):
        return "worktree"
    return subprocess.check_output(
        ["git", "-C", str(REPO), "rev-parse", "--verify", "--end-of-options", ref + "^{commit}"],
        text=True, stderr=subprocess.DEVNULL,
    ).strip()


def source_bytes(relative, ref, extraction=False):
    path = SOURCE / relative
    if ref == "worktree":
        return path.read_bytes(), "worktree"
    result = subprocess.run(["git", "-C", str(REPO), "show", ref + ":" + path.relative_to(REPO).as_posix()], capture_output=True)
    if result.returncode == 0:
        return result.stdout, ref
    if extraction and path.is_file():
        return path.read_bytes(), "worktree_behavior_preserving_extraction"
    raise ValueError("指定版本缺少生产文件：" + relative)


def dependency(directory, filename, relative, sha):
    path = directory / filename
    if not path.exists():
        caches = [Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))]
        configured = os.environ.get("XIAOKE_LATENCY_GRADLE_CACHE")
        if configured:
            caches.insert(0, Path(configured))
        group_path, artifact, version = relative.rsplit("/", 2)
        group = group_path.replace("/", ".")
        candidates = [p for cache in caches for p in (cache / "caches/modules-2/files-2.1" / group / artifact / version).glob("*/" + filename)]
        for candidate in candidates:
            if digest(candidate.read_bytes()) == sha:
                shutil.copyfile(candidate, path)
                break
        else:
            urllib.request.urlretrieve("https://repo.maven.apache.org/maven2/" + relative + "/" + filename, path)
    if digest(path.read_bytes()) != sha:
        raise ValueError("依赖 SHA-256 不符：" + filename)
    return path


class LatencyBridge:
    def __init__(self, gradle, directory, source_ref="worktree"):
        gradle = Path(gradle)
        libs = gradle / "lib"
        if not list(libs.glob("kotlin-compiler-embeddable-2.*.jar")):
            raise ValueError("请设置 GRADLE_HOME 为 Gradle 8.11.1 目录")
        directory = Path(directory)
        directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        ref = resolve_ref(source_ref)
        selected = {}
        origins = {}
        for name in PRODUCTION_FILES + EXTRACTED_FILES:
            content, origin = source_bytes(name, ref, name in EXTRACTED_FILES)
            selected[name] = content
            origins[name] = origin
        for name in NEW_POLICY_FILES:
            content, origin = source_bytes(name, ref, extraction=True)
            selected[name] = content
            origins[name] = "new_policy_unused_by_baseline" if origin == "worktree_behavior_preserving_extraction" else origin
        # Harness code is versioned separately from the production source under measurement.
        harness = [HERE / "LatencyBridge.kt", HERE / "BenchPlatform.kt", PERSONA_EVAL / "PersonaPromptBridge.kt"]
        for path in harness:
            selected["harness/" + path.name] = path.read_bytes()
            origins["harness/" + path.name] = "benchmark_harness"
        self.metadata = {
            "source_ref": ref,
            "production_sources": {name: {"sha256": digest(content), "origin": origins[name]} for name, content in selected.items()},
            "dependencies_sha256": {name: pair[1] for name, pair in DEPENDENCIES.items()},
            "harness_runtime_sha256": digest(Path(__file__).read_bytes()),
        }
        stdlib = sorted(libs.glob("kotlin-stdlib-2.*.jar"))[-1]
        self.metadata["kotlin_stdlib_sha256"] = digest(stdlib.read_bytes())
        signature = digest(self.metadata)
        build = directory / signature
        build.mkdir(parents=True, exist_ok=True, mode=0o700)
        jars = [dependency(directory, name, *pair) for name, pair in DEPENDENCIES.items()]
        jars.append(stdlib)
        classpath = os.pathsep.join(map(str, jars))
        sources = []
        for name, content in selected.items():
            target = build / name
            target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            target.write_bytes(content)
            sources.append(target)
        jar = build / "benchmark.jar"
        java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
        self.log = (directory / "bridge.log").open("a", encoding="utf-8")
        if not jar.exists():
            subprocess.run([java, "-cp", str(libs / "*"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                            "-no-stdlib", "-no-reflect", "-jvm-target", "21", "-classpath", classpath,
                            "-d", str(jar), *map(str, sources)], stdout=self.log, stderr=self.log, check=True)
        self.process = subprocess.Popen(
            [java, "-Xmx512m", "-Dfile.encoding=UTF-8", "-Duser.timezone=UTC", "-cp", str(jar) + os.pathsep + classpath,
             "io.deepseekharness.mobile.companion.eval.LatencyBridgeKt"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.log,
            text=True, encoding="utf-8", bufsize=1,
        )
        self.lock = threading.Lock()

    def call(self, value):
        with self.lock:
            self.process.stdin.write(canonical(value) + "\n")
            self.process.stdin.flush()
            line = self.process.stdout.readline()
            if not line:
                raise ValueError("生产客户端桥退出；检查私有 bridge.log")
            result = json.loads(line)
            if "error" in result and "status" not in result:
                raise ValueError("生产客户端桥拒绝请求：" + result.get("type", "unknown"))
            return result

    def close(self):
        if self.process.poll() is None:
            self.process.stdin.close()
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()
        self.log.close()
