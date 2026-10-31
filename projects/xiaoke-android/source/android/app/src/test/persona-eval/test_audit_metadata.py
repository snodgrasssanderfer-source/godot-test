import json
from pathlib import Path
import tempfile
import unittest

from audit_metadata import audit_run, cache_stage, digest, excluded_case_ids, filter_records, markdown


class AuditMetadataTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.models = ["target-a", "target-b"]
        self.fixture = {"id": "long-01", "category": "long_dialogue", "warmup": ["虚构暖场"] * 50,
                        "turns": [{"score": True}], "context": {"scope": "owner:local", "time": "2030-01-02T12:00"}}
        self.save("manifest.json", {"models": self.models, "case_ids": ["long-01"]})
        self.save("inputs.json", {"cases": [self.fixture]})

    def save(self, name, value):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
        return path

    def cache(self, key, model="target-a", versions=None, stage="requests", attempts=None, response=None):
        if response is None:
            response = ([{"modelVersion": version} for version in versions] if versions is not None else [{}])
        return self.save(f"{stage}/{key}.json", {
            "request_hash": key, "request": {"private_text": "fictional-private-canary"},
            "model": model, "protocol": "gemini", "response": response, "response_hash": digest(response),
            "attempts": attempts if attempts is not None else [{"status": 200, "seconds": 1}],
            "metadata": {"date": "Sat, 02 Feb 2030 12:00:00 GMT"},
        })

    def conversation(self, model, steps, pairs=51):
        history = []
        for index in range(1, pairs + 1):
            history += [{"id": f"u{index}", "role": "user", "text": "fictional-private-canary"},
                        {"id": f"a{index}", "role": "assistant", "text": "fictional-private-canary"}]
        self.save(f"conversations/{model}/long-01.json", {"status": "completed", "history": history, "steps": steps})

    def step(self, index, keys, retained=50):
        selected = [f"u{i}" for i in range(51 - retained, 52)] + [f"a{i}" for i in range(51 - retained, 51)]
        return {"turn_index": index, "request_hashes": keys, "selected_message_ids": selected}

    def test_stream_versions_count_once_per_cached_request(self):
        self.cache("same", versions=["target-a", "target-a"])
        self.cache("mixed", versions=["target-a", "reported-other", "reported-other"])
        result = cache_stage(self.root / "requests")
        stats = result["by_requested_alias"][0]
        self.assertEqual(stats["unique_cached_requests"], 2)
        self.assertEqual(stats["reported_version_request_counts"], {"reported-other": 1, "target-a": 2})
        self.assertEqual(stats["reported_mismatch_requests"], 1)
        self.assertEqual(stats["mixed_version_requests"], 1)

    def test_unknown_is_separate_from_equal_and_mismatch(self):
        self.cache("unknown")
        self.cache("event-final", response=[{}, {"modelVersion": "target-a"}])
        self.cache("invalid-version", response=[{"modelVersion": None}])
        result = cache_stage(self.root / "requests")
        stats = result["by_requested_alias"][0]
        self.assertEqual(stats["unknown_metadata_requests"], 2)
        self.assertEqual(stats["reported_mismatch_requests"], 0)
        self.assertEqual(result["requests"]["event-final"]["identity_status"], "reported_equal")

    def test_continuation_mismatch_and_warmup_mismatch_flag_whole_case(self):
        self.cache("warm", versions=["reported-other"])
        self.cache("first-part", versions=["target-a"])
        self.cache("continuation", versions=["reported-other"])
        self.cache("other-target", model="target-b", versions=["target-b"])
        self.conversation("target-a", [self.step(1, ["warm"]), self.step(51, ["first-part", "continuation"])])
        self.conversation("target-b", [self.step(51, ["other-target"])])
        audit = audit_run(self.root)
        row = audit["conversations"][0]
        self.assertTrue(row["metadata_mismatch"])
        self.assertEqual([ref["phase"] for ref in row["mismatch_references"]], ["warmup", "probe"])
        self.assertEqual(excluded_case_ids(audit), {"long-01"})
        records = [{"case_id": "long-01", "model": "target-a"}, {"case_id": "long-01", "model": "target-b"}, {"case_id": "other"}]
        self.assertEqual(filter_records(records, excluded_case_ids(audit)), [{"case_id": "other"}])
        self.assertEqual(len(records), 3)
        self.assertTrue(audit["primary_scores_unchanged"])

    def test_warmup_only_mismatch_affects_final_case(self):
        self.cache("warm", versions=["reported-other"])
        self.cache("probe", versions=["target-a"])
        self.conversation("target-a", [self.step(1, ["warm"]), self.step(51, ["probe"])])
        result = audit_run(self.root)
        self.assertEqual(result["summary"]["metadata_mismatch_conversations"], 1)
        self.assertEqual(result["conversations"][0]["mismatch_references"][0]["turn_index"], 1)

    def test_unknown_exclusion_requires_explicit_option(self):
        self.cache("unknown")
        self.conversation("target-a", [self.step(51, ["unknown"])])
        result = audit_run(self.root)
        self.assertEqual(excluded_case_ids(result), set())
        self.assertEqual(excluded_case_ids(result, require_known=True), {"long-01"})

    def test_retries_are_not_double_counted_with_failure_sidecar(self):
        attempts = [{"error": "http_429"}, {"error": "network_or_timeout"}, {"status": 200}]
        self.cache("retried", versions=["target-a"], attempts=attempts)
        self.save("requests/retried.failure.json", {"request_hash": "retried", "attempts": attempts[:2]})
        self.save("requests/unresolved.failure.json", {"request_hash": "unresolved", "attempts": [{"error": "http_503"}] * 3})
        result = cache_stage(self.root / "requests")
        self.assertEqual(result["unique_cached_requests"], 1)
        self.assertEqual(result["recorded_attempts_lower_bound"], 6)
        self.assertEqual(result["recorded_failed_attempts_in_successful_requests"], 2)
        self.assertEqual(len(result["unresolved_failure_requests"]), 1)

    def test_cache_corruption_and_missing_reference_are_visible(self):
        path = self.cache("corrupt", versions=["target-a"])
        value = json.loads(path.read_text())
        value["response"][0]["modelVersion"] = "changed"
        self.save("requests/corrupt.json", value)
        self.conversation("target-a", [self.step(51, ["corrupt", "missing"])])
        audit = audit_run(self.root)
        self.assertEqual(audit["target"]["invalid_response_hashes"], 1)
        self.assertEqual(len(audit["conversations"][0]["invalid_cache_references"]), 1)
        self.assertEqual(len(audit["conversations"][0]["missing_cache_references"]), 1)
        self.assertEqual(audit["target"]["requests"]["corrupt"]["request_hash_binding"], "not_verified_endpoint_not_stored")

    def test_50_pair_completion_and_retention_are_distinct(self):
        self.cache("long", versions=["target-a"])
        self.conversation("target-a", [self.step(51, ["long"], retained=49)])
        result = audit_run(self.root)
        row = result["conversations"][0]
        self.assertEqual(row["completed_warmup_pairs"], 50)
        self.assertEqual(row["completed_history_pairs"], 51)
        self.assertEqual(row["scored_probe_retention"][0]["warmup_pairs_retained"], 49)
        self.assertFalse(row["scored_probe_retention"][0]["all_warmup_pairs_retained"])
        self.assertEqual(result["summary"]["completed_50_pair_warmups"], 1)
        self.assertEqual(result["summary"]["long_scored_probes_retaining_all_50_pairs"], 0)

    def test_duplicate_request_references_do_not_inflate_totals(self):
        self.cache("shared", versions=["target-a"])
        self.cache("orphan", versions=["target-a"])
        self.conversation("target-a", [self.step(1, ["shared"]), self.step(51, ["shared", "shared"])])
        result = audit_run(self.root)
        self.assertEqual(result["target"]["unique_cached_requests"], 2)
        self.assertEqual(result["target"]["referenced_unique_requests"], 1)
        self.assertEqual(result["target"]["unreferenced_cached_requests"], 1)
        self.assertEqual(result["conversations"][0]["unique_referenced_requests"], 1)

    def test_judge_metadata_and_no_persona_text_in_audit(self):
        self.cache("judge-1", model="judge-alias", versions=["judge-version"], stage="judge-requests")
        self.save("blind-pairs/long-01-51.json", {"request_hashes": ["judge-1"]})
        result = audit_run(self.root)
        self.assertEqual(result["judge"]["referenced_unique_requests"], 1)
        self.assertEqual(result["judge"]["by_requested_alias"][0]["reported_mismatch_requests"], 1)
        self.assertNotIn("fictional-private-canary", json.dumps(result, ensure_ascii=False))
        self.assertNotIn("fictional-private-canary", markdown(result))


if __name__ == "__main__":
    unittest.main()
