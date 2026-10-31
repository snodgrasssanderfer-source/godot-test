import csv
import io
import unittest

from reporting import render_csv, render_markdown, summarize


def row(model, case, value, category="daily", turn=1, dimension="persona:section_001", **kwargs):
    return {"model": model, "case_id": case, "category": category, "turn_index": turn,
            "user": "虚构问题", "reply": "虚构实际返回", "rule_scores": {},
            "judge_scores": {dimension: {"score": value, "reason": "虚构测试理由"}}, **kwargs}


class ReportTest(unittest.TestCase):
    def test_hierarchical_average_does_not_overweight_long_cases(self):
        records = [row("a", "long", 1, turn=i) for i in range(50)]
        records += [row("a", "short", 5), row("a", "other", 5, category="technical")]
        report = summarize(records)
        self.assertEqual(report["by_category"][0]["mean"], 3)
        self.assertEqual(report["overall"][0]["mean"], 4)
        self.assertEqual(report["overall"][0]["n"], 52)

    def test_na_and_missing_do_not_become_perfect_or_zero_scores(self):
        records = [row("a", "one", 2), row("a", "na", None),
                   row("a", "missing", 3, dimension="judge:grounding")]
        report = summarize(records)
        score = next(r for r in report["overall"] if r["dimension"] == "persona:section_001")
        self.assertEqual((score["mean"], score["n"], score["na"], score["missing"]), (2, 1, 1, 1))

    def test_failures_excluded_and_only_matched_probes_compared(self):
        records = [row("a", "both", 1), row("b", "both", 3), row("a", "only", 5),
                   row("b", "only", 5, status="error"), row("a", "both", 5, turn=2)]
        report = summarize(records, ["a", "b"])
        pair = report["comparison"][0]
        self.assertEqual((pair["mean_a"], pair["mean_b"], pair["delta_b_minus_a"]), (1, 3, 2))
        self.assertEqual((pair["paired_cases"], pair["paired_turns"]), (1, 1))
        self.assertEqual(report["coverage"]["b"]["failed_cases"], 1)
        self.assertEqual(report["coverage"]["b"]["failure_rate"], .5)

    def test_worst_is_stable_full_and_ignores_errors_and_warmups(self):
        records = [row("a", f"c{i}", 2, reply=f"完整回复 {i}\n第二行") for i in reversed(range(7))]
        records += [row("a", "error", 1, status="failed"), row("a", "warmup", 1, score=False)]
        report = summarize(records)
        worst = report["worst"]["a"]["persona:section_001"]
        self.assertEqual([r["case_id"] for r in worst], ["c0", "c1", "c2", "c3", "c4"])
        self.assertEqual(worst[0]["reply"], "完整回复 0\n第二行")
        self.assertIn("完整回复 0\n第二行", render_markdown(report))

    def test_csv_parseable_and_no_formula_execution(self):
        report = summarize([row("=1+1", "one", 4)])
        rows = list(csv.DictReader(io.StringIO(render_csv(report))))
        self.assertEqual(rows[0]["model"], "'=1+1")
        self.assertEqual(rows[0]["mean"], "4")

    def test_duplicate_or_invalid_scores_are_rejected(self):
        with self.assertRaises(ValueError):
            summarize([row("a", "one", 1), row("a", "one", 5)])
        with self.assertRaises(ValueError):
            summarize([row("a", "one", True)])
        with self.assertRaises(ValueError):
            summarize([row("a", "one", float("nan"))])

    def test_absent_model_and_question_metrics_are_explicit(self):
        record = row("a", "one", 4, rule_scores={"rule:ending_question": {
            "score": 5, "reason": "必要澄清", "metrics": {"ending_question": True}}})
        report = summarize([record], ["a", "b"])
        self.assertEqual(report["question_rates"]["a"]["rate"], 1)
        self.assertEqual(report["question_rates"]["a"]["disallowed_ending_questions"], 0)
        self.assertIsNone(report["question_rates"]["b"]["rate"])
        self.assertTrue(all(r["mean"] is None for r in report["overall"] if r["model"] == "b"))

    def test_private_section_labels_are_runtime_only_and_ids_remain_stable(self):
        sections = [{"id": "section_001", "title": "虚构节标题"}, {"id": "section_002", "title": "未测节"}]
        report = summarize([row("a", "one", 4)], sections=sections)
        self.assertEqual(report["dimension_labels"]["persona:section_001"], "虚构节标题")
        self.assertIn("虚构节标题（persona:section_001）", render_markdown(report))
        self.assertEqual(report["overall"][0]["dimension"], "persona:section_001")
        self.assertIsNone(report["overall"][1]["mean"])
        csv_rows = list(csv.DictReader(io.StringIO(render_csv(report))))
        self.assertEqual(csv_rows[0]["dimension_label"], "虚构节标题")

    def test_scoreless_reply_is_not_a_complete_case(self):
        records = [row("a", "partly", 3), row("a", "partly", 3, turn=2, judge_scores={}),
                   row("a", "unscored", 3, judge_scores={}), row("a", "complete", None)]
        coverage = summarize(records)["coverage"]["a"]
        self.assertEqual(coverage["successful_cases"], 1)
        self.assertEqual(coverage["scored_turns"], 2)
        self.assertEqual(coverage["unscored_turns"], 2)


if __name__ == "__main__":
    unittest.main()
