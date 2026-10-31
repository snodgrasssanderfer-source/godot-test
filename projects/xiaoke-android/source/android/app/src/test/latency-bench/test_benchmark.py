import copy
import unittest

from reporting import classify, identity, metric_values, paired_deltas, summary, tool_summary
from run import cases, observed_probe, schedules


def sample(**changes):
    result = {"status": "completed", "variant": "baseline", "model": "gemini-3.8-flash", "scenario": "tool",
              "case_id": "tool-recall-code", "repeat": 0, "webCache": "warm", "text": "编号是杉-27。",
              "metrics": {"firstTextMs": 200, "totalMs": 500, "probeMs": 0},
              "requests": [{"purpose": "answer", "timing": {"firstTextMs": 100}, "usage": {"promptTokenCount": 34}, "modelVersions": ["gemini-3.8-flash"]}],
              "tools": [{"name": "recall"}], "text_events": [{"atMs": 200, "text": "我查一下。"}, {"atMs": 400, "text": "编号是杉-27。"}]}
    result.update(changes)
    return result


class MeasurementBoundaryTest(unittest.TestCase):
    def test_real_web_requires_explicit_case_selection(self):
        self.assertEqual(8, len(cases()))
        self.assertFalse(any(c.get("real_web") for c in cases()))
        self.assertTrue(cases(selected="real-web-android-docs")[0]["real_web"])

    def test_failures_and_missing_usage_are_not_zero_latency(self):
        good = sample()
        bad = sample(status="failed", metrics={"totalMs": 30000}, requests=[])
        rows = summary([good, bad])
        self.assertEqual(1, sum(r["failed"] for r in rows))
        matched = next(r for r in rows if r["version_status"] == "matched")
        self.assertEqual(1, matched["metrics"]["turn_complete_ms"]["n"])
        self.assertEqual(500, matched["metrics"]["turn_complete_ms"]["mean"])
        self.assertIsNone(metric_values(sample(requests=[]))["server_prompt_tokens_all_requests"])

    def test_probe_is_excluded_from_transport_first_text(self):
        record = sample()
        record["requests"].insert(0, {"purpose": "web_probe", "timing": {"firstTextMs": 5}, "usage": {"promptTokenCount": 12}})
        record["requests"].insert(1, {"purpose": "web_research", "timing": {"firstTextMs": 1}})
        self.assertEqual(100, metric_values(record)["transport_first_visible_ms"])
        self.assertEqual(46, metric_values(record)["server_prompt_tokens_all_requests"])

    def test_acknowledgement_does_not_become_answer_start(self):
        case = next(c for c in cases() if c["id"] == "tool-recall-code")
        checked = classify(sample(), case)
        self.assertEqual("fixture_answer", checked["response_kind"])
        self.assertEqual(400, checked["answer_started_ms"])
        self.assertFalse(classify(sample(tools=[]), case)["answer_verified"])
        self.assertEqual("acknowledgement_only", classify(sample(text="我查一下。"), case)["response_kind"])

    def test_anchor_substring_is_explicitly_not_semantic_verification(self):
        case = next(c for c in cases() if c["id"] == "tool-recall-code")
        checked = classify(sample(text="没查到编号，不能确认是不是杉-27。"), case)
        self.assertTrue(checked["mechanical_check_passed"])
        self.assertIn("semantic truth not scored", checked["answer_check"])
        self.assertEqual(1, checked["anchor_count"])

    def test_casual_text_is_not_reported_as_an_anchor_timestamp(self):
        record = sample(answer_started_ms=200, answer_check="nonempty non-acknowledgement; semantic quality not scored")
        self.assertIsNone(metric_values(record)["answer_started_ms"])
        record["answer_check"] = "fixture anchors and required tool"
        self.assertEqual(200, metric_values(record)["answer_started_ms"])

    def test_completion_goal_does_not_claim_semantic_correctness(self):
        row = summary([sample(answer_verified=False)])[0]
        self.assertEqual(1, row["under_3s"]["complete_answer"]["reached"])

    def test_pairing_does_not_mix_cold_and_warm(self):
        fast = sample(variant="optimized", metrics={"firstTextMs": 100, "totalMs": 300})
        delta = paired_deltas([sample(), fast])[0]
        self.assertEqual(-200, delta["metrics"]["turn_complete_ms"]["mean_delta"])
        self.assertEqual(-40, delta["metrics"]["turn_complete_ms"]["mean_delta_percent"])
        cold = sample(variant="optimized", webCache="cold")
        self.assertEqual(0, paired_deltas([sample(), cold])[0]["successful_pairs"])

    def test_one_mismatched_probe_excludes_whole_chain(self):
        record = sample()
        record["requests"].insert(0, {"purpose": "web_probe", "modelVersions": ["some-other-model"]})
        self.assertEqual("mismatch", identity(record))
        row = summary([record])[0]
        self.assertEqual(1, row["excluded_chains"])
        self.assertEqual(0, row["metrics"]["turn_complete_ms"]["n"])

    def test_missing_versions_separate_from_matches_and_no_speedup_claim(self):
        missing = sample(variant="optimized", requests=[{"purpose": "reply", "timing": {"firstTextMs": 1}}])
        self.assertEqual("missing", identity(missing))
        self.assertEqual(0, paired_deltas([sample(), missing])[0]["successful_pairs"])

    def test_host_ack_never_counts_as_model_answer_under_three_seconds(self):
        record = sample(metrics={"hostAckMs": 2, "firstTextMs": 5000, "totalMs": 7000}, answer_started_ms=6000)
        rates = summary([record])[0]["under_3s"]
        self.assertEqual(1, rates["host_ack"]["reached"])
        self.assertEqual(0, rates["backend_first_text"]["reached"])
        self.assertEqual(0, rates["answer_start"]["reached"])
        self.assertEqual(0, rates["complete_answer"]["reached"])

    def test_warm_seed_requires_real_probe_with_matching_model_metadata(self):
        record = sample(webMode="split", webModeSource="real_probe")
        record["requests"][0]["purpose"] = "web_probe"
        self.assertEqual("split", observed_probe(record, "fixture")['mode'])
        record["requests"][0]["modelVersions"] = ["different-model"]
        self.assertIsNone(observed_probe(record, "fixture"))
        record["requests"][0]["modelVersions"] = []
        self.assertIsNone(observed_probe(record, "fixture"))

    def test_tool_time_is_separate_from_network_continuation(self):
        record = sample(tools=[{"name": "recall", "timing": {"callReceivedMs": 100, "fixtureFinishedMs": 102,
            "nextRequestStartedMs": 104, "nextTextMs": 230, "nextFinishedMs": 300}}])
        result = tool_summary([record])[0]["metrics"]
        self.assertEqual(2, result["tool_execution_ms"]["p50"])
        self.assertEqual(128, result["result_to_next_visible_ms"]["p50"])
        self.assertEqual(200, result["call_to_completed_answer_ms"]["p50"])

    def test_schedule_rotates_variants_and_excludes_background_probe(self):
        manifest = {"variants": [["baseline", "old"], ["optimized", "new"]], "repeat": 1,
                    "models": ["gemini-3.8-flash"], "web_cache_states": ["cold", "warm"]}
        selected = cases(smoke=True)
        plan = list(schedules(manifest, selected))
        self.assertEqual(["baseline", "optimized", "optimized", "baseline"], [j["variant"] for j in plan[:4]])
        background = [j for j in plan if j["case"]["scenario"] == "background"]
        self.assertEqual(2, len(background))
        self.assertTrue(all(j["webCache"] == "not_applicable" for j in background))


if __name__ == "__main__":
    unittest.main()
