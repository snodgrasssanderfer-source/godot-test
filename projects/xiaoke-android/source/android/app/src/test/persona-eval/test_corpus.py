import copy
import unittest

from corpus import load_cases, validate


class CorpusTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.data = load_cases()

    def test_coverage(self):
        stats = validate(self.data)
        self.assertEqual(256, stats["cases"])
        long = [c for c in self.data["cases"] if c["category"] == "long_dialogue"]
        self.assertTrue(all(len(c["warmup"]) == 50 for c in long))

    def test_owner_memory_never_in_group_prompt(self):
        data = copy.deepcopy(self.data)
        group = next(c for c in data["cases"] if c["category"] == "group_privacy")
        group["context"]["memories"] = group["private_memories"]
        with self.assertRaisesRegex(ValueError, "主人私聊记忆"):
            validate(data)

    def test_judge_material_never_in_model_context(self):
        data = copy.deepcopy(self.data)
        data["cases"][0]["context"]["facts"] = ["标准答案"]
        with self.assertRaisesRegex(ValueError, "评分资料"):
            validate(data)


if __name__ == "__main__":
    unittest.main()
