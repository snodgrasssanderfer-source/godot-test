#!/usr/bin/env python3
"""Offline strict paired reports; exclude mismatches and isolate unknown version metadata."""
import argparse
from collections import Counter
from itertools import combinations
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

import audit_metadata
from audit_metadata import audit_run, digest, read, write_audit


def reporting_module(directory, source=None):
    source = Path(source) if source else Path(directory).resolve().parents[1] / "source/android/app/src/test/persona-eval"
    if not (source / "reporting.py").is_file():
        raise ValueError("reporting_source_not_found")
    sys.path.insert(0, str(source))
    import reporting
    return reporting


def problem(source, model, reason, **details):
    return {"source": source, "model": model, "reason": reason, **details}


def classify_references(keys, request_rows, expected_alias, source, model, **context):
    issues = []
    if not keys:
        return [problem(source, model, "missing_request_references", **context)]
    for key in sorted(set(keys)):
        row = request_rows.get(key)
        details = {**context, "request_hash": key}
        if row is None:
            issues.append(problem(source, model, "missing_request_cache", **details))
            continue
        details.update(requested_alias=row["requested_alias"], reported_versions=row["reported_versions"],
                       missing_version=row["identity_status"] == "unknown_metadata")
        if (not row["response_sha256_verified"] or not row["cache_filename_matches_request_hash"]
                or row["requested_alias"] != expected_alias):
            issues.append(problem(source, model, "cache_or_request_integrity", **details))
        if row["identity_status"] == "reported_mismatch":
            issues.append(problem(source, model, "metadata_mismatch", **details))
        elif row["identity_status"] == "unknown_metadata":
            issues.append(problem(source, model, "metadata_unknown", **details))
    return issues


def classify_cases(directory, audit):
    directory = Path(directory)
    manifest, inputs = read(directory / "manifest.json"), read(directory / "inputs.json")
    judge_path = directory / "judge-config.json"
    judge_model = read(judge_path).get("model") if judge_path.exists() else None
    fixtures = {case["id"]: case for case in inputs["cases"]}
    rows, records = [], []
    for cid in manifest["case_ids"]:
        fixture = fixtures[cid]
        warmup = len(fixture.get("warmup", []))
        expected_steps = warmup + len(fixture["turns"])
        expected_scored = [i for i, turn in enumerate(fixture["turns"], warmup + 1) if turn.get("score", True)]
        issues, members, case_records = [], [], []
        judge_refs = set()
        for model in manifest["models"]:
            member_issues = []
            path = directory / "conversations" / model / (cid + ".json")
            conversation = read(path) if path.exists() else {"status": "missing"}
            if conversation.get("status") != "completed":
                member_issues.append(problem("target", model, "incomplete_conversation"))
            steps = conversation.get("steps", [])
            if sorted(s.get("turn_index") for s in steps) != list(range(1, expected_steps + 1)):
                member_issues.append(problem("target", model, "incomplete_turn_sequence"))
            for step in steps:
                member_issues += classify_references(
                    step.get("request_hashes", []), audit["target"]["requests"], model, "target", model,
                    turn_index=step["turn_index"], phase="warmup" if step["turn_index"] <= warmup else "probe")
            originals = {(r.get("case_id"), r.get("model"), r.get("turn_index")): r for r in conversation.get("records", [])}
            scored_count = 0
            for index in expected_scored:
                original = originals.get((cid, model, index))
                score_path = directory / "judgements" / model / f"{cid}-{index}.json"
                score = read(score_path) if score_path.exists() else {"status": "missing"}
                record = score.get("record")
                if (score.get("status") != "completed" or not isinstance(record, dict)
                        or not isinstance(record.get("judge_scores"), dict) or not record["judge_scores"]):
                    member_issues.append(problem("judge", model, "missing_or_incomplete_scores", turn_index=index))
                    continue
                if (not original or record.get("case_id") != cid or record.get("model") != model
                        or record.get("turn_index") != index or record.get("reply") != original.get("reply")
                        or record.get("rule_scores") != original.get("rule_scores")):
                    member_issues.append(problem("judge", model, "score_record_integrity", turn_index=index))
                    continue
                if record.get("judge_model") != judge_model or not judge_model:
                    member_issues.append(problem("judge", model, "judge_configuration_mismatch", turn_index=index))
                keys = record.get("judge_request_hashes", [])
                judge_refs.update(keys)
                member_issues += classify_references(keys, audit["judge"]["requests"], judge_model,
                                                    "judge", model, turn_index=index)
                case_records.append(record)
                scored_count += 1
            issues.extend(member_issues)
            members.append({"model": model, "conversation_status": conversation.get("status"),
                            "expected_scored_replies": len(expected_scored), "available_scored_replies": scored_count,
                            "issues": member_issues})
        # The pair file also retains schema-repair attempts; all referenced calls are checked.
        for index in expected_scored:
            pair_path = directory / "blind-pairs" / f"{cid}-{index}.json"
            if pair_path.exists():
                pair = read(pair_path)
                keys = pair.get("request_hashes", [])
                judge_refs.update(keys)
                issues += classify_references(keys, audit["judge"]["requests"], judge_model,
                                               "judge", "paired", turn_index=index)
        issues = list({digest(issue): issue for issue in issues}.values())
        reasons = {issue["reason"] for issue in issues}
        group = "excluded" if reasons - {"metadata_unknown"} else "unknown" if reasons else "clean"
        rows.append({"case_id": cid, "category": fixture["category"],
                     "category_label": fixture.get("category_label", fixture["category"]),
                     "group": group, "paired_exclusion": group == "excluded", "members": members,
                     "issues": issues, "reason_codes": sorted(reasons),
                     "referenced_judge_requests": len(judge_refs),
                     "expected_scored_replies_per_model": len(expected_scored)})
        records.extend(case_records)
    counts = Counter(row["group"] for row in rows)
    by_category = []
    for category in sorted({row["category"] for row in rows}):
        cases = [row for row in rows if row["category"] == category]
        groups = Counter(row["group"] for row in cases)
        by_category.append({"category": category, "category_label": cases[0]["category_label"],
                            "original_cases": len(cases), **{name: groups[name] for name in ("clean", "unknown", "excluded")},
                            "clean_below_minimum_15": groups["clean"] < 15})
    by_model = []
    for model in manifest["models"]:
        problems = Counter(issue["reason"] for row in rows for member in row["members"]
                           if member["model"] == model for issue in member["issues"])
        by_model.append({"model": model, "original_cases": len(rows),
                         **{name: counts[name] for name in ("clean", "unknown", "excluded")},
                         "own_issue_reference_counts": dict(problems)})
    return {"schema_version": 1, "policy": "strict_paired_case_exclusion_with_unknown_separate",
            "judge_policy": "conservative_all_referenced_calls_including_schema_repairs",
            "original_cases": len(rows), **{name: counts[name] for name in ("clean", "unknown", "excluded")},
            "original_model_conversations": len(rows) * len(manifest["models"]),
            "model_conversations": {name: counts[name] * len(manifest["models"]) for name in ("clean", "unknown", "excluded")},
            "by_category": by_category, "by_model": by_model, "cases": rows,
            "audit_manifest_sha256": audit["manifest_sha256"]}, records


def complete_dimensions(summary, dimensions, labels, categories):
    """Add explicit unobserved rows; excluded cases are never fabricated as failures."""
    dimensions = sorted(dimensions)
    summary["dimensions"] = dimensions
    summary["dimension_labels"].update(labels)
    existing = {(r["model"], r["category"], r["dimension"]) for r in summary["by_category"]}
    overall = {(r["model"], r["dimension"]) for r in summary["overall"]}
    comparisons = {(r["model_a"], r["model_b"], r["dimension"]) for r in summary["comparison"]}
    zero = {"mean": None, "n": 0, "na": 0, "missing": 0, "cases": 0}
    for model in summary["models"]:
        for dimension in dimensions:
            for category, label in categories.items():
                if (model, category, dimension) not in existing:
                    summary["by_category"].append({"model": model, "category": category, "category_label": label,
                                                   "dimension": dimension, **zero})
            if (model, dimension) not in overall:
                summary["overall"].append({"model": model, "dimension": dimension, "categories": 0, **zero})
            summary["worst"][model].setdefault(dimension, [])
    for a, b in combinations(summary["models"], 2):
        for dimension in dimensions:
            if (a, b, dimension) not in comparisons:
                summary["comparison"].append({"model_a": a, "model_b": b, "dimension": dimension,
                    "mean_a": None, "mean_b": None, "delta_b_minus_a": None,
                    "paired_cases": 0, "paired_categories": 0, "paired_turns": 0})
    return summary


def group_summary(group, records, exclusions, models, sections, reporter):
    ids = {row["case_id"] for row in exclusions["cases"] if row["group"] == group}
    selected = [record for record in records if record["case_id"] in ids]
    summary = reporter.summarize(selected, models=models, sections=sections)
    dimensions = set(reporter.DIMENSION_LABELS) | {"persona:" + section["id"] for section in sections}
    dimensions |= {key for record in records for key in (*record.get("rule_scores", {}), *record.get("judge_scores", {}))}
    labels = reporter.DIMENSION_LABELS | {"persona:" + section["id"]: section["title"] for section in sections}
    categories = {row["category"]: row["category_label"] for row in exclusions["by_category"]}
    complete_dimensions(summary, dimensions, labels, categories)
    summary["strict_coverage"] = {
        "group": group, "policy": exclusions["policy"], "original_cases_per_model": exclusions["original_cases"],
        "eligible_paired_cases": len(ids), "eligible_scored_replies": len(selected),
        "clean_cases": exclusions["clean"], "unknown_cases": exclusions["unknown"], "excluded_cases": exclusions["excluded"],
        "by_category": exclusions["by_category"], "by_model": exclusions["by_model"],
        "all_categories_have_at_least_15_eligible_cases": all(row[group] >= 15 for row in exclusions["by_category"]),
        "excluded_cases_are_not_failures": True,
    }
    return summary


def coverage_markdown(exclusions, group):
    escape = lambda value: str(value).replace("|", "\\|").replace("\n", " ")
    title = "严格主报告：返回版本名一致的配对会话" if group == "clean" else "版本缺失组：身份未知，独立列示"
    lines = ["# " + title, "", "按用户要求严格排除返回版本名不一致的整段会话；任一模型出现问题，整例两边均退出主比较。检查包含暖场、探针、续传及评委全部被引用请求（包括格式修复）。缺失版本的会话单列，不进入主表。", "",
             "返回字符串一致仅是中转元数据一致，不证明真实上游身份。所有分数沿用原始评委结果，没有重评或改分。", "",
             f"原始案例每模型 {exclusions['original_cases']}；严格一致组 {exclusions['clean']}；版本缺失组 {exclusions['unknown']}；排除组 {exclusions['excluded']}。下面的评分完成情况只以当前纳入组为分母，排除不冒充调用失败。", "",
             "## 全部情境覆盖", "", "| 情境 | 原始案例 | 一致组 | 缺失组 | 排除组 | 当前组达到 15 例 |", "|---|---:|---:|---:|---:|---|"]
    for row in exclusions["by_category"]:
        lines.append(f"| {escape(row['category_label'])} | {row['original_cases']} | {row['clean']} | {row['unknown']} | {row['excluded']} | {'是' if row[group] >= 15 else '否'} |")
    lines += ["", "当前组不足 15 个合格案例的情境仍显示均分和实际覆盖数；空情境显示未覆盖，不声称完成原定覆盖。", "",
              "## 案例分组与排除原因", "", "| 案例 | 情境 | 分组 | 问题来源与原因 |", "|---|---|---|---|"]
    for row in exclusions["cases"]:
        details = sorted({f"{issue['source']}/{issue['model']}:{issue['reason']}" for issue in row["issues"]})
        lines.append(f"| {escape(row['case_id'])} | {escape(row['category_label'])} | {row['group']} | {escape('；'.join(details) or '全部引用请求返回名一致')} |")
    lines += ["", "具体请求哈希、返回版本、缺失标记和每模型原因见 `exclusions.json`；原始未筛选统计只作审计，不作为严格结论。", ""]
    return "\n".join(lines)


def save_private(directory, name, text):
    target = directory / name
    root = subprocess.check_output(["git", "-C", str(directory), "rev-parse", "--show-toplevel"], text=True).strip()
    ignored = subprocess.run(["git", "-C", root, "check-ignore", "-q", str(target)])
    tracked = subprocess.check_output(["git", "-C", root, "ls-files", "--", str(target)], text=True)
    if ignored.returncode or tracked.strip() or target.is_symlink():
        raise ValueError("strict_output_must_be_untracked_and_ignored")
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=directory, prefix=".strict-", delete=False) as file:
        file.write(text)
        temporary = Path(file.name)
    temporary.chmod(0o600)
    temporary.replace(target)


def provenance(directory, reporter):
    files = [directory / name for name in ("manifest.json", "inputs.json", "sections.json", "judge-config.json", "scored-records.json")]
    private_sources = (sorted((directory / "conversations").rglob("*.json"))
                       + sorted((directory / "judgements").rglob("*.json"))
                       + sorted((directory / "blind-pairs").glob("*.json")))
    sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    manifest = read(directory / "manifest.json")
    return {
        "policy": "strict_paired_case_exclusion_with_unknown_separate",
        "record_source": "completed_conversations_and_judgements",
        "generation_checkout_commit": manifest.get("checkout_commit"),
        "generation_manifest_sha256": digest(manifest),
        "postprocessing_code_sha256": {path.name: sha(path) for path in
            (Path(__file__), Path(audit_metadata.__file__), Path(reporter.__file__))},
        "input_files_sha256": {path.name: sha(path) for path in files if path.exists()},
        "record_sources_sha256": {str(path.relative_to(directory)): sha(path) for path in private_sources},
    }


def build_reports(directory, source=None, write=True):
    directory = Path(directory).resolve()
    reporter = reporting_module(directory, source)
    audit = audit_run(directory)
    exclusions, records = classify_cases(directory, audit)
    manifest, sections = read(directory / "manifest.json"), read(directory / "sections.json")
    lineage = provenance(directory, reporter)
    exclusions["provenance"] = lineage
    result = {group: group_summary(group, records, exclusions, manifest["models"], sections, reporter)
              for group in ("clean", "unknown")}
    for summary in result.values():
        summary["provenance"] = lineage
    if write:
        write_audit(directory, audit)
        save_private(directory, "exclusions.json", json.dumps(exclusions, ensure_ascii=False, indent=2) + "\n")
        for group, prefix in (("clean", "strict"), ("unknown", "unknown-version")):
            summary = result[group]
            save_private(directory, prefix + "-summary.json", json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
            save_private(directory, prefix + "-summary.csv", reporter.render_csv(summary))
            save_private(directory, prefix + "-report.md", coverage_markdown(exclusions, group) + reporter.render_markdown(summary))
        original = directory / "unfiltered-report.md"
        if not original.exists():
            original = directory / "report.md"
        if original.exists() and (original.name == "unfiltered-report.md" or not (directory / "raw-report-audit.md").exists()):
            save_private(directory, "raw-report-audit.md", "# 未筛选原始结果：仅供审计\n\n此文件包含未按返回版本筛选的原始统计，不作为严格评测结论。请使用 strict-report.md；版本缺失另见 unknown-version-report.md。\n\n" + original.read_text(encoding="utf-8"))
    return {"audit": audit, "exclusions": exclusions, **result}


def write_strict_reports(directory, source=None):
    """Write private strict artifacts and return small counts/path metadata for the runner."""
    result = build_reports(directory, source, write=True)
    exclusions = result["exclusions"]
    return {
        "counts": {key: exclusions[key] for key in ("original_cases", "clean", "unknown", "excluded",
                                                    "original_model_conversations", "model_conversations")},
        "reports": {"clean": "strict-report.md", "unknown": "unknown-version-report.md", "exclusions": "exclusions.json"},
        "provenance": {key: result["clean"]["provenance"][key] for key in
                       ("generation_checkout_commit", "generation_manifest_sha256", "postprocessing_code_sha256")},
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("run_directory", type=Path)
    parser.add_argument("--eval-source", type=Path)
    args = parser.parse_args()
    os.umask(0o077)
    result = build_reports(args.run_directory, args.eval_source)
    print(json.dumps({key: result["exclusions"][key] for key in ("original_cases", "clean", "unknown", "excluded")}, sort_keys=True))


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError):
        print("strict_report_failed_check_private_run_files", file=sys.stderr)
        sys.exit(2)
