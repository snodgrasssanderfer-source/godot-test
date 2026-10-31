import json
import unittest

from blind import anonymize, pair, unblind_scores


class BlindTests(unittest.TestCase):
    def record(self, model, text):
        return {"model": model, "case_id": "identity-01", "turn_index": 1, "reply": text,
                "context": {"model": model}, "history": [{"role": "assistant", "text": "上次是 flash。",
                                                         "wire": {"private_unneeded": "DO NOT SEND"}, "source": model}]}

    def test_nested_names_metadata_and_spacing_are_hidden(self):
        record = self.record("gemini-4-argon", "我是 Gemini 4 Argon，上一条用了 GEMINI-3.8-FLASH。")
        sample, mapping = anonymize(record)
        text = json.dumps(sample, ensure_ascii=False).lower()
        self.assertNotIn("gemini", text)
        self.assertNotIn("argon", text)
        self.assertNotIn("flash", text)
        self.assertNotIn("wire", text)
        self.assertNotIn("source", text)
        scores = {"identity": {"score": 5, "reason": "如实说明当前模型。", "evidence": "我是 [当前被测模型]"}}
        actual = unblind_scores(scores, mapping, record["reply"])
        self.assertEqual(actual["identity"]["evidence"], "我是 Gemini 4 Argon")
        self.assertIn(actual["identity"]["evidence"], record["reply"])

    def test_pair_order_reproducible_and_identity_checked(self):
        rows = [self.record("gemini-4-argon", "一"), self.record("gemini-3.8-flash", "二")]
        a = pair(rows, 12)
        b = pair(list(reversed(rows)), 12)
        self.assertEqual(a, b)
        rows[1]["turn_index"] = 2
        with self.assertRaises(ValueError):
            pair(rows, 12)


if __name__ == "__main__":
    unittest.main()
