import json
import unittest

from scoring import RUBRIC, build_paired_judge_messages, parse_judgement, parse_paired_judgement, score_rules, split_persona


class PersonaScoringTest(unittest.TestCase):
    def test_sections_keep_all_original_text(self):
        text = "虚构序言\n\n# 语气\n短句。\n```\n# 代码里的井号\n```\n【虚构边界】\n只说知道的。\n"
        sections = split_persona(text)
        self.assertEqual([s["title"] for s in sections], ["正文", "语气", "虚构边界"])
        self.assertEqual("".join(s["text"] for s in sections), text)
        card = split_persona({"text": "测试角色。", "extras": {"postHistory": "虚构后置指令",
                                                           "characterBook": {"entries": [{"content": "启用"}, {"content": "停用", "enabled": False}]}}})
        self.assertEqual([s["source"] for s in card], ["text", "extras.postHistory", "extras.characterBook.entries.0.content"])

    def judge(self, score=5, evidence="知道了"):
        sections = split_persona("# 虚构\n用短句。")
        item = {"score": score, "reason": "测试。", "evidence": evidence if score is not None else ""}
        return sections, {"sections": [dict(item, id=sections[0]["id"])],
                          "dimensions": {name: dict(item) for name in RUBRIC["dimensions"]}}

    def test_judge_scores_need_quoted_evidence_and_valid_range(self):
        sections, value = self.judge(None)
        self.assertIsNone(parse_judgement(json.dumps(value), sections, "知道了。")["persona:section_001"]["score"])
        for bad in (True, 0, 6, 4.5, "5"):
            sections, value = self.judge(bad)
            with self.assertRaises(ValueError):
                parse_judgement(json.dumps(value), sections, "知道了。")
        sections, value = self.judge()
        with self.assertRaisesRegex(ValueError, "原文片段"):
            parse_judgement(json.dumps(value), sections, "这不是原文。")
        value["sections"] = []
        with self.assertRaisesRegex(ValueError, "章节"):
            parse_judgement(json.dumps(value), sections, "知道了。")

    def test_paired_judging_is_blind_and_per_candidate(self):
        persona = {"text": "完全虚构的角色。"}
        sections = split_persona(persona)
        pair = {label: {"case_id": "fiction-01", "turn_index": 2, "user": "问题", "reply": reply, "context": {}}
                for label, reply in (("A", "知道了"), ("B", "好呀"))}
        self.assertEqual(set(json.loads(build_paired_judge_messages(persona, sections, pair)[1]["content"])["sample"]), {"A", "B"})
        pair["B"]["context"]["model"] = "gemini-3.8-flash"
        with self.assertRaises(ValueError):
            build_paired_judge_messages(persona, sections, pair)
        _, a = self.judge()
        _, b = self.judge(evidence="好呀")
        parsed = parse_paired_judgement(json.dumps({"A": a, "B": b}), sections, {"A": "知道了。", "B": "好呀。"})
        self.assertEqual(parsed["B"]["judge:grounding"]["evidence"], "好呀")
        with self.assertRaises(ValueError):
            parse_paired_judgement(json.dumps({"A": a, "B": b}), sections, {"A": "好呀。", "B": "知道了。"})

    def score(self, reply, rules=None):
        return score_rules({"reply": reply, "user": "随口一句", "rules": rules or {}})

    def test_style_markers_are_observed_not_penalized(self):
        result = self.score("（笑）我轻轻抱住你。😀 要不要一起去？")
        self.assertEqual(result["observed"]["parenthetical_aside"], ["（笑）"])
        self.assertTrue(result["observed"]["emoji"])
        self.assertTrue(result["observed"]["ending_question"])
        self.assertEqual(result["rule:service_phrase"]["score"], 5)
        self.assertEqual({k for k in result if k.startswith("rule:")}, {"rule:service_phrase"})
        self.assertFalse(self.score("用 f(x + 1)，命令是 `print('😀')`。")["observed"]["emoji"])

    def test_scored_rules(self):
        self.assertEqual(self.score("作为一个AI，我很乐意。")["rule:service_phrase"]["score"], 1)
        rule = {"forbidden_claims": ["看完了整段视频", "已经通关"]}
        self.assertEqual(self.score("我没看完了整段视频，也不能说已经通关。", rule)["rule:unseen_claim"]["score"], 5)
        self.assertEqual(self.score("这段我不确定。不过我已经通关。", rule)["rule:unseen_claim"]["score"], 1)
        self.assertEqual(self.score("你好呀", {"max_chars": 2})["rule:length"]["score"], 1)
        self.assertEqual(self.score("原文\n", {"exact_quote": "原文"})["rule:exact_quote"]["score"], 5)
        self.assertEqual(self.score("原文。", {"exact_quote": "原文"})["rule:exact_quote"]["score"], 1)


if __name__ == "__main__":
    unittest.main()
