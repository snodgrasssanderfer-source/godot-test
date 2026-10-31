#!/usr/bin/env python3
"""Offline metadata audit; never changes scores or guesses a relay's upstream identity."""
import argparse
from collections import Counter, defaultdict
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile


def digest(value):
    data = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(data).hexdigest()


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def versions(response, protocol):
    events = response if isinstance(response, list) else [response]
    field = "model" if protocol == "openai" else "modelVersion"
    return sorted({event[field] for event in events if isinstance(event, dict)
                   and isinstance(event.get(field), str) and event[field].strip()})


def inspect_cache(path, key):
    value = read(path)
    returned = versions(value.get("response"), value.get("protocol"))
    requested = value.get("model")
    attempts = value.get("attempts", [])
    verified = value.get("response_hash") == digest(value.get("response"))
    return {
        "request_hash": key, "requested_alias": requested,
        "reported_versions": returned,
        "identity_status": ("unknown_metadata" if not returned else
                            "reported_mismatch" if any(v != requested for v in returned) else "reported_equal"),
        "mixed_versions_in_response": len(returned) > 1,
        "response_sha256_verified": verified,
        "cache_filename_matches_request_hash": value.get("request_hash") == key,
        "request_body_sha256": digest(value.get("request")),
        "request_hash_binding": "not_verified_endpoint_not_stored",
        "recorded_attempts": len(attempts),
        "recorded_failed_attempts": sum("error" in attempt for attempt in attempts),
        "metadata_date": value.get("metadata", {}).get("date"),
    }


def cache_stage(directory):
    rows, failed = {}, []
    for path in sorted(directory.glob("*.json")):
        if path.name.endswith(".failure.json"):
            key = path.name.removesuffix(".failure.json")
            if not (directory / (key + ".json")).exists():
                value = read(path)
                failed.append({"request_hash": key, "recorded_attempts": len(value.get("attempts", []))})
            continue
        rows[path.stem] = inspect_cache(path, path.stem)
    by_alias = defaultdict(list)
    for row in rows.values():
        by_alias[row["requested_alias"]].append(row)
    aliases = []
    for alias, requests in sorted(by_alias.items(), key=lambda item: str(item[0])):
        counts = Counter(v for row in requests for v in row["reported_versions"])
        aliases.append({
            "requested_alias": alias, "unique_cached_requests": len(requests),
            "reported_version_request_counts": dict(sorted(counts.items())),
            "unknown_metadata_requests": sum(r["identity_status"] == "unknown_metadata" for r in requests),
            "reported_mismatch_requests": sum(r["identity_status"] == "reported_mismatch" for r in requests),
            "mixed_version_requests": sum(r["mixed_versions_in_response"] for r in requests),
        })
    return {
        "by_requested_alias": aliases, "requests": rows,
        "unique_cached_requests": len(rows), "unresolved_failure_requests": failed,
        "recorded_attempts_lower_bound": sum(r["recorded_attempts"] for r in rows.values())
                                         + sum(r["recorded_attempts"] for r in failed),
        "recorded_failed_attempts_in_successful_requests": sum(r["recorded_failed_attempts"] for r in rows.values()),
        "invalid_response_hashes": sum(not r["response_sha256_verified"] for r in rows.values()),
        "invalid_request_hash_labels": sum(not r["cache_filename_matches_request_hash"] for r in rows.values()),
    }


def completed_pairs(history):
    count = 0
    for offset in range(0, len(history) - 1, 2):
        user, assistant = history[offset:offset + 2]
        index = offset // 2 + 1
        if (user.get("role"), user.get("id"), assistant.get("role"), assistant.get("id")) != (
                "user", f"u{index}", "assistant", f"a{index}"):
            break
        count += 1
    return count


def audit_run(directory):
    started = datetime.now(timezone.utc).isoformat()
    directory = Path(directory)
    manifest, inputs = read(directory / "manifest.json"), read(directory / "inputs.json")
    cases = {case["id"]: case for case in inputs["cases"]}
    target, judge = cache_stage(directory / "requests"), cache_stage(directory / "judge-requests")
    conversations, referenced = [], set()
    for model in manifest["models"]:
        for cid in manifest["case_ids"]:
            fixture = cases[cid]
            path = directory / "conversations" / model / (cid + ".json")
            value = read(path) if path.exists() else {"status": "missing"}
            warmup = len(fixture.get("warmup", []))
            pairs = completed_pairs(value.get("history", []))
            contexts, scopes, times = {}, set(), set()
            context = dict(fixture.get("context", {}))
            for index in range(1, warmup + len(fixture["turns"]) + 1):
                if index > warmup:
                    context.update(fixture["turns"][index - warmup - 1].get("context", {}))
                contexts[index] = dict(context)
            mismatches, unknowns, missing, invalid, scored = [], [], [], [], []
            cached_keys = set()
            for step in value.get("steps", []):
                index = step["turn_index"]
                scopes.add(contexts.get(index, {}).get("scope", "owner:local"))
                times.add(contexts.get(index, {}).get("time", "unspecified_fixture_time"))
                phase = "warmup" if index <= warmup else "probe"
                keys = step.get("request_hashes", [])
                if not keys:
                    missing.append({"turn_index": index, "phase": phase, "request_hash": None})
                for key in keys:
                    referenced.add(key)
                    cached_keys.add(key)
                    ref = {"turn_index": index, "phase": phase, "request_hash": key}
                    row = target["requests"].get(key)
                    if row is None:
                        missing.append(ref)
                        continue
                    if row["requested_alias"] != model or not row["response_sha256_verified"] or not row["cache_filename_matches_request_hash"]:
                        invalid.append(ref)
                    if row["identity_status"] == "reported_mismatch":
                        mismatches.append(ref)
                    elif row["identity_status"] == "unknown_metadata":
                        unknowns.append(ref)
                turn = fixture["turns"][index - warmup - 1] if index > warmup and index <= warmup + len(fixture["turns"]) else None
                if turn and turn.get("score", True):
                    selected = set(step.get("selected_message_ids", []))
                    warmup_pairs = sum({f"u{i}", f"a{i}"} <= selected for i in range(1, warmup + 1))
                    expected = {f"u{i}" for i in range(1, index + 1)} | {f"a{i}" for i in range(1, index)}
                    scored.append({"turn_index": index, "selected_message_count": len(selected),
                                   "expected_message_count": len(expected), "retained_prior_pairs": sum(
                                       {f"u{i}", f"a{i}"} <= selected for i in range(1, index)),
                                   "warmup_pairs_retained": warmup_pairs,
                                   "all_warmup_pairs_retained": warmup_pairs == warmup,
                                   "all_available_messages_retained": expected <= selected})
            conversations.append({
                "model": model, "case_id": cid, "category": fixture.get("category"), "status": value["status"],
                "completed_steps": len(value.get("steps", [])), "completed_history_pairs": pairs,
                "planned_warmup_pairs": warmup, "completed_warmup_pairs": min(warmup, pairs),
                "warmup_complete": pairs >= warmup, "scored_probe_retention": scored,
                "observed_scopes": sorted(scopes), "fixture_times": sorted(times),
                "unique_referenced_requests": len(cached_keys),
                "metadata_mismatch": bool(mismatches), "metadata_unknown": bool(unknowns or missing),
                "mismatch_references": mismatches, "unknown_references": unknowns,
                "missing_cache_references": missing, "invalid_cache_references": invalid,
            })
    target["referenced_unique_requests"] = len(referenced & target["requests"].keys())
    target["unreferenced_cached_requests"] = len(target["requests"].keys() - referenced)
    judge_refs = set()
    for path in (directory / "blind-pairs").glob("*.json"):
        judge_refs.update(read(path).get("request_hashes", []))
    judge["referenced_unique_requests"] = len(judge_refs & judge["requests"].keys())
    judge["unreferenced_cached_requests"] = len(judge["requests"].keys() - judge_refs)
    return {
        "schema_version": 1, "audit_kind": "observed_relay_metadata_only",
        "audit_started_at_utc": started, "audit_finished_at_utc": datetime.now(timezone.utc).isoformat(),
        "manifest_sha256": digest(manifest), "primary_scores_unchanged": True,
        "notes": [
            "Files are read sequentially; rerun after generation and judging stop for a final consistent snapshot.",
            "Returned version strings are relay observations, not verified upstream identities; equality is exact string equality.",
            "Missing version metadata means unknown identity, not a match or a mismatch.",
            "Requests are counted once per unique cache hash; a stream with multiple versions counts once in each observed version column.",
            "Recorded attempts are a lower bound: earlier exhausted calls may be overwritten on rerun; failure sidecars of successful requests are not added twice.",
            "Response SHA is verified; full request-hash binding cannot be reconstructed because the cleartext endpoint is not stored.",
        ],
        "target": target, "judge": judge, "conversations": conversations,
        "summary": {
            "planned_conversations": len(conversations),
            "completed_conversations": sum(c["status"] == "completed" for c in conversations),
            "metadata_mismatch_conversations": sum(c["metadata_mismatch"] for c in conversations),
            "metadata_unknown_conversations": sum(c["metadata_unknown"] for c in conversations),
            "completed_50_pair_warmups": sum(c["planned_warmup_pairs"] == 50 and c["warmup_complete"] for c in conversations),
            "long_scored_probes_retaining_all_50_pairs": sum(p["all_warmup_pairs_retained"] for c in conversations
                if c["planned_warmup_pairs"] == 50 for p in c["scored_probe_retention"]),
            "long_scored_probes": sum(len(c["scored_probe_retention"]) for c in conversations if c["planned_warmup_pairs"] == 50),
        },
    }


def excluded_case_ids(audit, require_known=False):
    """Optional paired exclusion; caller must explicitly choose this alternative policy."""
    return {case["case_id"] for case in audit["conversations"] if case["metadata_mismatch"]
            or require_known and case["metadata_unknown"]}


def filter_records(records, excluded):
    return [record for record in records if record["case_id"] not in excluded]


def markdown(audit):
    lines = ["# 中转元数据审计", "", "读取期间：" + audit["audit_started_at_utc"] + " 至 " + audit["audit_finished_at_utc"] + "；正式汇总应在生成与评分停止后重跑审计。", "", "本表不改变主评分。返回版本名仅为中转提供的元数据，不能证明真实上游模型身份；缺失表示未知。", ""]
    for stage, title in (("target", "被测请求"), ("judge", "评委请求")):
        data = audit[stage]
        lines += [f"## {title}", "", "| 请求别名 | 唯一缓存请求 | 返回名不一致 | 未返回版本 | 返回名及请求数 |",
                  "|---|---:|---:|---:|---|"]
        for row in data["by_requested_alias"]:
            escape = lambda text: str(text).replace("|", "\\|").replace("\n", " ").replace("\r", " ")
            names = "、".join(f"{escape(name)}: {count}" for name, count in row["reported_version_request_counts"].items()) or "无"
            lines.append(f"| {escape(row['requested_alias'])} | {row['unique_cached_requests']} | {row['reported_mismatch_requests']} | {row['unknown_metadata_requests']} | {names} |")
        lines += ["", f"已保存尝试次数下界：{data['recorded_attempts_lower_bound']}；响应 SHA 校验失败：{data['invalid_response_hashes']}。", ""]
    summary = audit["summary"]
    lines += [f"完整会话：{summary['completed_conversations']}/{summary['planned_conversations']}；存在返回名不一致：{summary['metadata_mismatch_conversations']}；存在未知元数据：{summary['metadata_unknown_conversations']}。",
              "", f"已完成 50 个历史往返的长聊：{summary['completed_50_pair_warmups']}；保留全部 50 轮的长聊评分探针：{summary['long_scored_probes_retaining_all_50_pairs']}/{summary['long_scored_probes']}。",
              "", "整段会话的标记包括暖场、评分探针及续传请求。缓存响应正文 SHA 已核对；完整请求哈希含未保存的明文地址，其绑定不能仅凭现有缓存复算。",
              "", "同一缓存请求的重试旁文件不重复累加。重跑可能覆盖较早的失败尝试，因此保存的尝试次数是下界；返回多个版本的单个请求在各版本列各记一次。", ""]
    return "\n".join(lines)


def write_audit(directory, audit):
    directory = Path(directory).resolve()
    root = subprocess.check_output(["git", "-C", str(directory), "rev-parse", "--show-toplevel"], text=True).strip()
    for name, text in (("audit.json", json.dumps(audit, ensure_ascii=False, indent=2) + "\n"), ("audit.md", markdown(audit))):
        target = directory / name
        ignored = subprocess.run(["git", "-C", root, "check-ignore", "-q", str(target)])
        tracked = subprocess.check_output(["git", "-C", root, "ls-files", "--", str(target)], text=True)
        if ignored.returncode or tracked.strip() or target.is_symlink():
            raise ValueError("audit_output_must_be_untracked_and_ignored")
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=directory,
                                         prefix=".audit-", delete=False) as file:
            temporary = Path(file.name)
            file.write(text)
        temporary.chmod(0o600)
        temporary.replace(target)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run_directory", type=Path)
    args = parser.parse_args()
    os.umask(0o077)
    audit = audit_run(args.run_directory)
    write_audit(args.run_directory, audit)
    # Console stays aggregate-only; no returned strings, account scopes or content.
    print(json.dumps(audit["summary"], sort_keys=True))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
        print("audit_failed_check_private_run_files", file=sys.stderr)
        sys.exit(2)
