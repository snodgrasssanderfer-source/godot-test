import json
import unittest

from scoring import (RUBRIC, build_judge_messages, build_paired_judge_messages, parse_judgement,
                     parse_paired_judgement, score_rules, split_persona)


class PersonaScoringTest(unittest.TestCase):
    def test_sections_keep_original_preamble_headings_and_fenced_hashes(self):
        text = "虚构序言\n\n# 语气\n短句。\n```\n# 代码里的井号\n```\n## 边界\n只说知道的。\n"
        sections = split_persona(text)
        self.assertEqual([s["title"] for s in sections], ["正文", "语气", "边界"])
        self.assertEqual("".join(s["text"] for s in sections), text)
        self.assertEqual([s["id"] for s in sections], ["section_001", "section_002", "section_003"])

    def test_plain_persona_and_card_extras_are_not_lost(self):
        self.assertEqual(len(split_persona("完全虚构的无标题设定。")), 1)
        sections = split_persona({"text": "测试角色。", "extras": {"postHistory": "虚构后置指令",
            "depthPrompt": {"prompt": "虚构深度提示"}, "characterBook": {"entries": [
                {"content": "虚构启用条目"}, {"content": "虚构停用条目", "enabled": False}]}}})
        self.assertEqual(len(sections), 4)
        self.assertEqual(sections[-1]["source"], "extras.characterBook.entries.0.content")
        with self.assertRaises(ValueError):
            split_persona(" ")

    def test_chinese_headings_and_import_labels_keep_all_original_text(self):
        text = "系统提示\n\n【虚构语气】\n说话简短。\n【虚构边界】\n说明未知。\n对话示例\n用户：你好。\n角色：早。\n"
        sections = split_persona(text)
        self.assertEqual([s["title"] for s in sections], ["系统提示", "虚构语气", "虚构边界", "对话示例"])
        self.assertEqual("".join(s["text"] for s in sections), text)
        self.assertEqual(len(split_persona("正文中包含【虚构标签】不是独立标题。")), 1)
        self.assertEqual(len(split_persona("```text\n【代码中的标签】\n```\n")), 1)

    def judge(self, score=5):
        sections = split_persona("# 虚构\n用短句。")
        item = {"score": score, "reason": "回复满足测试约束。", "evidence": "知道了" if score is not None else ""}
        value = {"sections": [dict(item, id=sections[0]["id"])],
                 "dimensions": {name: item.copy() for name in RUBRIC["dimensions"]}}
        return sections, value

    def test_strict_judge_and_no_automatic_full_score_for_uncovered_section(self):
        sections, value = self.judge(None)
        result = parse_judgement(json.dumps(value), sections)
        self.assertIsNone(result["persona:section_001"]["score"])
        for bad in [True, 0, 6, 4.5, "5"]:
            sections, value = self.judge(bad)
            with self.assertRaises(ValueError):
                parse_judgement(json.dumps(value), sections)
        sections, value = self.judge()
        self.assertEqual(parse_judgement(json.dumps(value), sections, "知道了。")["judge:grounding"]["score"], 5)
        with self.assertRaises(ValueError):
            parse_judgement(json.dumps(value), sections, "这不是原文。")
        value["sections"].append(value["sections"][0])
        with self.assertRaises(ValueError):
            parse_judgement(json.dumps(value), sections)
        with self.assertRaises(ValueError):
            parse_judgement('{"sections": [], "sections": []}', sections)

    def test_judge_payload_retains_facts_but_not_rule_results_or_model_label(self):
        persona = {"text": "完全虚构的角色。"}
        messages = build_judge_messages(persona, split_persona(persona), {
            "user": "只看前十秒怎么评价？", "reply": "只看了开头。", "facts": ["只看前十秒"],
            "unknowns": ["后半段"], "model": "blind-target", "rule_scores": {"x": 1}})
        payload = json.loads(messages[1]["content"])
        self.assertEqual(payload["sample"]["facts"], ["只看前十秒"])
        self.assertNotIn("model", payload["sample"])
        self.assertNotIn("rule_scores", payload["sample"])

    def test_paired_judge_uses_blind_same_case_independent_scores(self):
        persona = {"text": "完全虚构的角色。"}
        sections = split_persona(persona)
        pair = {label: {"case_id": "fiction-01", "turn_index": 2, "model": "hidden-source",
                       "user": "测量问题", "reply": reply, "context": {"model": "[来源已隐藏]"}}
                for label, reply in (("A", "知道了"), ("B", "好呀"))}
        messages = build_paired_judge_messages(persona, sections, pair)
        payload = json.loads(messages[1]["content"])
        self.assertEqual(set(payload["sample"]), {"A", "B"})
        self.assertNotIn("hidden-source", messages[1]["content"])
        self.assertIn("允许同分", messages[0]["content"])
        pair["B"]["context"]["model"] = "gemini-3.8-flash"
        with self.assertRaises(ValueError):
            build_paired_judge_messages(persona, sections, pair)
        pair["B"]["context"]["model"] = "匿名"
        pair["B"]["case_id"] = "another-case"
        with self.assertRaises(ValueError):
            build_paired_judge_messages(persona, sections, pair)

    def test_paired_judge_rejects_missing_candidate_and_swapped_evidence(self):
        sections, value = self.judge()
        second = json.loads(json.dumps(value, ensure_ascii=False).replace("知道了", "好呀"))
        pair = {"A": value, "B": second}
        parsed = parse_paired_judgement(json.dumps(pair), sections, {"A": "知道了。", "B": "好呀。"})
        self.assertEqual(parsed["A"]["persona:section_001"]["score"], 5)
        with self.assertRaises(ValueError):
            parse_paired_judgement(json.dumps({"A": value}), sections, {"A": "知道了。", "B": "好呀。"})
        with self.assertRaises(ValueError):
            parse_paired_judgement(json.dumps(pair), sections, {"A": "好呀。", "B": "知道了。"})

    def score(self, reply, rules=None, user="随口一句"):
        return score_rules({"reply": reply, "user": user, "rules": rules or {}})

    def test_prose_detectors_exclude_code_math_and_required_quote(self):
        scores = self.score("用 f(x + 1)，命令是 `print('😀')`。")
        self.assertEqual(scores["rule:parenthetical_aside"]["score"], 5)
        self.assertEqual(scores["rule:emoji"]["score"], 5)
        scores = self.score("（笑）我轻轻抱住你。😀 [表情:微笑]")
        self.assertEqual(scores["rule:parenthetical_aside"]["score"], 1)
        self.assertEqual(scores["rule:appearance_action"]["score"], 1)
        self.assertEqual(scores["rule:emoji"]["score"], 1)
        quote = "（笑）😀 你呢？"
        scores = self.score(quote, {"exact_quote": quote})
        for key in ["emoji", "parenthetical_aside", "ending_question", "exact_quote"]:
            self.assertEqual(scores["rule:" + key]["score"], 5)
        self.assertEqual(self.score(quote + "😀", {"exact_quote": quote})["rule:emoji"]["score"], 1)

    def test_question_observation_is_separate_from_case_violation(self):
        denied = self.score("要继续吗？")
        allowed = self.score("是我点错了还是有别的事？", {"allow_question": True})
        self.assertEqual(denied["rule:ending_question"]["score"], 1)
        self.assertEqual(allowed["rule:ending_question"]["score"], 5)
        self.assertTrue(allowed["rule:ending_question"]["metrics"]["ending_question"])
        self.assertEqual(self.score("我刚检查了日志，没有错误。 ")["rule:appearance_action"]["score"], 5)

    def test_claim_heuristic_handles_negation_and_later_positive_claim(self):
        rule = {"forbidden_claims": ["看完了整段视频", "已经通关"]}
        self.assertEqual(self.score("我没看完了整段视频，也不能说已经通关。", rule)["rule:unseen_claim"]["score"], 5)
        self.assertEqual(self.score("这段我不确定。不过我已经通关。", rule)["rule:unseen_claim"]["score"], 1)
        self.assertEqual(self.score("如果已经通关，就能拿到奖励。", rule)["rule:unseen_claim"]["score"], 5)
        self.assertIsNone(self.score("未知情况。 ")["rule:unseen_claim"]["score"])

    def test_length_and_exact_quote_constraints_are_explicit(self):
        self.assertEqual(self.score("你好", {"min_chars": 1, "max_chars": 2})["rule:length"]["score"], 5)
        self.assertEqual(self.score("你好呀", {"max_chars": 2})["rule:length"]["score"], 1)
        self.assertIsNone(self.score("很长的回复也不凭空设限。")["rule:length"]["score"])
        self.assertEqual(self.score(" 原文", {"exact_quote": "原文"})["rule:exact_quote"]["score"], 1)
        with self.assertRaises(ValueError):
            self.score("", {})
        with self.assertRaises(ValueError):
            self.score("测试", {"min_chars": 3, "max_chars": 1})


if __name__ == "__main__":
    unittest.main()
