import copy
import unittest

from corpus import load_cases, validate


class CorpusBoundaryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data = load_cases()

    def test_requested_coverage_and_real_long_dialogue_inputs(self):
        stats = validate(self.data)
        self.assertEqual(256, stats["cases"])
        for counts in stats["categories"].values():
            self.assertEqual(8, counts["single_probe"])
            self.assertEqual(8, counts["multi_probe"])
        long = [c for c in self.data["cases"] if c["category"] == "long_dialogue"]
        self.assertTrue(all(len(c["warmup"]) == 50 and all(isinstance(t, str) for t in c["warmup"]) for c in long))
        self.assertEqual(16, len({tuple(c["warmup"]) for c in long}))
        self.assertEqual(16, len({c["warmup"][0] for c in long}))

    def test_reject_reused_long_dialogue_prefix(self):
        data = copy.deepcopy(self.data)
        long = [c for c in data["cases"] if c["category"] == "long_dialogue"]
        long[1]["warmup"] = list(long[0]["warmup"])
        with self.assertRaisesRegex(ValueError, "duplicate long-dialogue warmup sequence"):
            validate(data)

    def test_reject_owner_memory_in_group_prompt(self):
        data = copy.deepcopy(self.data)
        group = next(c for c in data["cases"] if c["category"] == "group_privacy")
        group["context"]["memories"] = group["private_memories"]
        with self.assertRaisesRegex(ValueError, "private memory in group"):
            validate(data)

    def test_reject_scoring_metadata_in_model_context(self):
        data = copy.deepcopy(self.data)
        data["cases"][0]["context"]["facts"] = ["This is the gold answer"]
        with self.assertRaisesRegex(ValueError, "judge metadata"):
            validate(data)

    def test_reject_scripted_assistant_warmup(self):
        data = copy.deepcopy(self.data)
        long = next(c for c in data["cases"] if c["category"] == "long_dialogue")
        long["warmup"][0] = {"user": "hello", "assistant": "scripted reply"}
        with self.assertRaisesRegex(ValueError, "invalid text"):
            validate(data)


if __name__ == "__main__":
    unittest.main()
