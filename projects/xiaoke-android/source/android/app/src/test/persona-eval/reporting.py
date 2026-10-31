"""Aggregate frozen responses without allowing long cases or failures to inflate scores."""
from collections import defaultdict
import csv
import io
from itertools import combinations
import math
import re
from statistics import mean


DIMENSION_LABELS = {
    "rule:emoji": "规则：表情",
    "rule:parenthetical_aside": "规则：括号旁白",
    "rule:appearance_action": "规则：外貌动作描写",
    "rule:ending_question": "规则：结尾询问",
    "rule:service_phrase": "规则：客服套话",
    "rule:length": "规则：回复长度",
    "rule:unseen_claim": "规则：未见内容的断言",
    "rule:exact_quote": "规则：原文复述",
    "judge:grounding": "评委：事实与观察边界",
    "judge:length_following": "评委：长度随对方和任务调整",
    "judge:dialogue_style": "评委：情境中的说话方式",
}


def _mean(values):
    values = list(values)
    return mean(values) if values else None


def _failed(record):
    return record.get("status") in {"error", "failed"} or bool(record.get("error_type"))


def _scores(record):
    rules, judges = record.get("rule_scores", {}), record.get("judge_scores", {})
    if rules.keys() & judges.keys():
        raise ValueError("Rule and judge dimensions must have distinct names")
    return {**rules, **judges}


def _value(result):
    value = result.get("score")
    if value is not None and (isinstance(value, bool) or not isinstance(value, (int, float))
                              or not math.isfinite(value) or not 1 <= value <= 5):
        raise ValueError("Scores must be numbers from 1 to 5 or null")
    return value


def _key(record):
    return record["case_id"], record.get("turn_index", -1)


def _statistics(records, dimension):
    by_case = defaultdict(list)
    n = na = missing = 0
    for record in records:
        scores = _scores(record)
        if dimension not in scores:
            missing += 1
        elif (value := _value(scores[dimension])) is None:
            na += 1
        else:
            by_case[record["case_id"]].append(value)
            n += 1
    return {"mean": _mean(_mean(values) for values in by_case.values()),
            "n": n, "na": na, "missing": missing, "cases": len(by_case)}


def summarize(records, models=None, sections=None):
    """Return JSON-serializable aggregates; null means unobserved, never a zero score.

    Supply only scored probe records (warmups may instead carry ``score=False``).
    Error records carry the attempted case and turn identity. Each identity occurs once.
    Both complete and partial runs are supported and their coverage remains explicit.
    """
    records = [r for r in records if r.get("score", True) is not False]
    requested = list(dict.fromkeys(models or []))
    models = requested + sorted({r["model"] for r in records} - set(requested))
    section_labels = {"persona:" + section["id"]: section["title"] for section in sections or []}
    dimensions = sorted({d for r in records for d in _scores(r)} | section_labels.keys())
    category_labels = {r["category"]: r.get("category_label", r["category"]) for r in records}
    categories = sorted(category_labels)
    case_categories = {}
    seen = set()
    for record in records:
        identity = (record["model"], *_key(record))
        if identity in seen:
            raise ValueError("Duplicate model/case/turn record would bias the report")
        seen.add(identity)
        previous = case_categories.setdefault(record["case_id"], record["category"])
        if previous != record["category"]:
            raise ValueError("A case cannot change category")
        for result in _scores(record).values():
            _value(result)
    good = [r for r in records if not _failed(r) and isinstance(r.get("reply"), str) and r["reply"].strip()]
    summary = {"schema_version": 1, "models": models, "dimensions": dimensions,
               "dimension_labels": {**DIMENSION_LABELS, **section_labels},
               "aggregation": "mean(turns within case), then mean(cases within category), then mean(categories); failures excluded",
               "coverage": {}, "by_category": [], "overall": [], "comparison": [],
               "worst": {}, "question_rates": {}}
    for model in models:
        attempted = [r for r in records if r["model"] == model]
        successful = [r for r in good if r["model"] == model]
        errors = [r for r in attempted if _failed(r)]
        failed_cases = {r["case_id"] for r in errors}
        scored = [r for r in successful if _scores(r)]
        scored_keys = {_key(r) for r in scored}
        complete_cases = {r["case_id"] for r in scored}
        complete_cases.difference_update(r["case_id"] for r in attempted if _key(r) not in scored_keys)
        summary["coverage"][model] = {
            "attempted_cases": len({r["case_id"] for r in attempted}),
            "successful_cases": len(complete_cases),
            "failed_cases": len(failed_cases),
            "attempted_turns": len(attempted), "generated_turns": len(successful),
            "scored_turns": len(scored), "failed_turns": len(errors),
            "unscored_turns": len(attempted) - len(errors) - len(scored),
            "failure_rate": len(errors) / len(attempted) if attempted else None,
        }
        summary["worst"][model] = {}
        for category in categories:
            rows = [r for r in successful if r["category"] == category]
            for dimension in dimensions:
                summary["by_category"].append({"model": model, "category": category,
                    "category_label": category_labels[category], "dimension": dimension,
                    **_statistics(rows, dimension)})
        for dimension in dimensions:
            category_rows = [r for r in summary["by_category"] if r["model"] == model and r["dimension"] == dimension]
            measured = [r for r in category_rows if r["mean"] is not None]
            summary["overall"].append({"model": model, "dimension": dimension,
                "mean": _mean(r["mean"] for r in measured), "categories": len(measured),
                **{key: sum(r[key] for r in category_rows) for key in ("n", "na", "missing", "cases")}})
            candidates = [r for r in successful if dimension in _scores(r) and _value(_scores(r)[dimension]) is not None]
            candidates.sort(key=lambda r: (_value(_scores(r)[dimension]), str(r["case_id"]), r.get("turn_index", -1)))
            summary["worst"][model][dimension] = [
                {"case_id": r["case_id"], "category": r["category"], "category_label": r.get("category_label", r["category"]),
                 "turn_index": r.get("turn_index", -1), "user": r.get("user", ""), "reply": r["reply"],
                 "score": _value(_scores(r)[dimension]), "reason": _scores(r)[dimension].get("reason", ""),
                 "evidence": _scores(r)[dimension].get("evidence", [])} for r in candidates[:5]
            ]
        question_rows = [r["rule_scores"]["rule:ending_question"] for r in successful
                         if "rule:ending_question" in r.get("rule_scores", {})]
        question_metrics = [r.get("metrics", {}) for r in question_rows]
        known = [m for m in question_metrics if "ending_question" in m]
        ending = sum(bool(m["ending_question"]) for m in known)
        summary["question_rates"][model] = {"n": len(known), "ending_questions": ending,
            "rate": ending / len(known) if known else None,
            "disallowed_ending_questions": sum(_value(r) == 1 for r in question_rows)}
    for model_a, model_b in combinations(models, 2):
        left = {_key(r): r for r in good if r["model"] == model_a}
        right = {_key(r): r for r in good if r["model"] == model_b}
        for dimension in dimensions:
            paired = defaultdict(list)
            for key in sorted(left.keys() & right.keys()):
                a, b = _scores(left[key]).get(dimension), _scores(right[key]).get(dimension)
                if a is not None and b is not None and _value(a) is not None and _value(b) is not None:
                    paired[key[0]].append((_value(a), _value(b)))
            per_category = defaultdict(list)
            for case_id, scores in paired.items():
                per_category[case_categories[case_id]].append((_mean(s[0] for s in scores), _mean(s[1] for s in scores)))
            a_mean = _mean(_mean(s[0] for s in values) for values in per_category.values())
            b_mean = _mean(_mean(s[1] for s in values) for values in per_category.values())
            summary["comparison"].append({"model_a": model_a, "model_b": model_b, "dimension": dimension,
                "mean_a": a_mean, "mean_b": b_mean,
                "delta_b_minus_a": b_mean - a_mean if a_mean is not None else None,
                "paired_cases": len(paired), "paired_categories": len(per_category),
                "paired_turns": sum(map(len, paired.values()))})
    return summary


def _number(value):
    return "未覆盖" if value is None else f"{value:.3f}"


def _escape(value):
    return str(value).replace("&", "&amp;").replace("<", "&lt;").replace("|", "\\|").replace("\n", "<br>")


def _literal(value):
    value = str(value)
    fence = "`" * max(3, max((len(m.group()) + 1 for m in re.finditer(r"`+", value)), default=3))
    return f"{fence}text\n{value}\n{fence}"


def render_markdown(summary):
    def dimension_label(dimension):
        label = summary.get("dimension_labels", {}).get(dimension)
        return _escape(f"{label}（{dimension}）" if label else dimension)

    lines = ["# 人设一致性评测", "", "各维度均为 1–5 分，高分表示更符合约束；规则分与评委分分别展示。先在案例内平均，再在情境内平均，最后按情境等权平均。未覆盖项不计入均分，调用或评分失败单列。评分轮次指该维度有数值的轮次；不适用指明确返回 null，缺失指有回复但缺少该维度评分。", "",
             "## 完成情况", "", "| 模型 | 尝试案例 | 完整成功案例 | 失败案例 | 已评分轮次 | 失败轮次 | 未评分轮次 |", "|---|---:|---:|---:|---:|---:|---:|"]
    for model, row in summary["coverage"].items():
        lines.append(f"| {_escape(model)} | {row['attempted_cases']} | {row['successful_cases']} | {row['failed_cases']} | {row['scored_turns']} | {row['failed_turns']} | {row['unscored_turns']} |")
    lines.extend(["", "## 两模型分数总表", "", "| 维度 | 模型 | 均分 | 覆盖情境 | 评分轮次 | 不适用轮次 | 缺失轮次 |", "|---|---|---:|---:|---:|---:|---:|"])
    for row in sorted(summary["overall"], key=lambda r: (r["dimension"], r["model"])):
        lines.append(f"| {dimension_label(row['dimension'])} | {_escape(row['model'])} | {_number(row['mean'])} | {row['categories']} | {row['n']} | {row['na']} | {row['missing']} |")
    lines.extend(["", "## 同案例对比", "", "只比较两模型在同一案例、同一探针上均成功且该维度适用的回复；差值为后一个模型减前一个模型。", "", "| 维度 | 前一个模型 | 后一个模型 | 前者均分 | 后者均分 | 差值 | 配对案例 | 配对情境 | 配对轮次 |", "|---|---|---|---:|---:|---:|---:|---:|---:|"])
    for row in summary["comparison"]:
        lines.append(f"| {dimension_label(row['dimension'])} | {_escape(row['model_a'])} | {_escape(row['model_b'])} | {_number(row['mean_a'])} | {_number(row['mean_b'])} | {_number(row['delta_b_minus_a'])} | {row['paired_cases']} | {row['paired_categories']} | {row['paired_turns']} |")
    lines.extend(["", "## 每类情境、每个维度", "", "| 情境 | 维度 | 模型 | 均分 | 适用案例 | 评分轮次 | 不适用轮次 | 缺失轮次 |", "|---|---|---|---:|---:|---:|---:|---:|"])
    for row in sorted(summary["by_category"], key=lambda r: (r["category"], r["dimension"], r["model"])):
        lines.append(f"| {_escape(row['category_label'])} | {dimension_label(row['dimension'])} | {_escape(row['model'])} | {_number(row['mean'])} | {row['cases']} | {row['n']} | {row['na']} | {row['missing']} |")
    lines.extend(["", "## 结尾反问观测", "", "单次问句不直接代表习惯性反问；下表同时列出所有结尾问句与案例不允许的结尾问句。", "", "| 模型 | 检测轮次 | 结尾问句 | 比例 | 案例不允许的结尾问句 |", "|---|---:|---:|---:|---:|"])
    for model, row in summary["question_rates"].items():
        lines.append(f"| {_escape(model)} | {row['n']} | {row['ending_questions']} | {_number(row['rate'])} | {row['disallowed_ending_questions']} |")
    lines.extend(["", "## 每个维度最低的五条实际回复", "", "每个模型分别排序，按分数、案例 ID、轮次稳定排序；少于五条时展示全部。", ""])
    for model, dimensions in summary["worst"].items():
        for dimension, samples in dimensions.items():
            lines.extend([f"### {_escape(model)} / {dimension_label(dimension)}", ""])
            if not samples:
                lines.extend(["未覆盖。", ""])
            for sample in samples:
                lines.extend([f"**{_escape(sample['case_id'])} · {_escape(sample['category_label'])} · 第 {sample['turn_index']} 轮 · {sample['score']} 分**", "",
                              "用户：", "", _literal(sample["user"]), "", "实际回复：", "", _literal(sample["reply"]), "", "评分理由：", "", _literal(sample["reason"]), ""])
    return "\n".join(lines).rstrip() + "\n"


def render_csv(summary):
    """CSV contains numerical aggregates only; full private samples stay in JSON/Markdown."""
    output = io.StringIO(newline="")
    fields = ["level", "model", "category", "category_label", "dimension", "dimension_label", "mean", "n", "na", "missing", "cases", "categories"]
    writer = csv.DictWriter(output, fieldnames=fields, lineterminator="\n")
    writer.writeheader()
    for level, rows in (("category", summary["by_category"]), ("overall", summary["overall"])):
        for row in rows:
            safe = {key: value for key, value in row.items() if key in fields}
            safe["dimension_label"] = summary.get("dimension_labels", {}).get(row["dimension"], row["dimension"])
            for key, value in safe.items():
                if isinstance(value, str) and value.startswith(("=", "+", "-", "@", "\t", "\r")):
                    safe[key] = "'" + value
            writer.writerow({"level": level, **safe})
    return output.getvalue()
