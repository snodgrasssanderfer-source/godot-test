"""Average within a case, then within a category, then across categories, so long cases weigh the same as short ones."""
from collections import Counter, defaultdict
from statistics import mean

LABELS = {
    "rule:service_phrase": "规则：客服套话", "rule:length": "规则：长度", "rule:unseen_claim": "规则：没依据的断言",
    "rule:exact_quote": "规则：原文复述", "judge:grounding": "评委：事实与观察边界",
    "judge:length_following": "评委：长度随对方调整", "judge:dialogue_style": "评委：说话方式",
}
OBSERVED = {"emoji": "表情", "parenthetical_aside": "括号旁白", "appearance_action": "动作描写", "ending_question": "结尾问句"}


def scores(record):
    return {k: v["score"] for k, v in {**record.get("rule_scores", {}), **record.get("judge_scores", {})}.items()
            if k != "observed" and v.get("score") is not None}


def macro(records, dimension):
    by_case, category_of = defaultdict(list), {}
    for r in records:
        if dimension in scores(r):
            by_case[r["case_id"]].append(scores(r)[dimension])
            category_of[r["case_id"]] = r["category"]
    by_category = defaultdict(list)
    for case, values in by_case.items():
        by_category[category_of[case]].append(mean(values))
    return {c: mean(v) for c, v in by_category.items()}


def summarize(records, models, sections):
    labels = LABELS | {"persona:" + s["id"]: "人设：" + s["title"] for s in sections}
    dimensions = sorted({d for r in records for d in scores(r)})
    out = {"labels": labels, "dimensions": dimensions, "models": {}}
    for model in models:
        rows = [r for r in records if r["model"] == model]
        per_dim = {d: macro(rows, d) for d in dimensions}
        overall = [mean(v.values()) for v in per_dim.values() if v]
        judged = [r for r in rows if "judge_scores" in r]
        observed = Counter()
        for r in rows:
            for key, value in r.get("rule_scores", {}).get("observed", {}).items():
                observed[key] += bool(value)
        worst = sorted((r for r in judged if scores(r)), key=lambda r: (mean(scores(r).values()), r["case_id"], r["turn_index"]))[:5]
        out["models"][model] = {
            "replies": len(rows), "judged": len(judged),
            "overall": mean(overall) if overall else None,
            "dimensions": {d: {"mean": mean(v.values()) if v else None, "categories": v} for d, v in per_dim.items()},
            "observed": {k: observed[k] / len(rows) if rows else None for k in OBSERVED},
            "model_versions": dict(Counter(v for r in rows for v in r.get("model_versions", [])) or {"未返回": len(rows)}),
            "worst": [{"case_id": r["case_id"], "category": r["category_label"], "turn": r["turn_index"], "user": r["user"],
                       "reply": r["reply"], "mean": mean(scores(r).values()),
                       "deductions": [f"{labels.get(k, k)} {v['score']} 分：{v.get('reason', '')}"
                                      for k, v in {**r.get("rule_scores", {}), **r.get("judge_scores", {})}.items()
                                      if k != "observed" and v.get("score") is not None and v["score"] < 5]} for r in worst],
        }
    return out


def fmt(value):
    return "—" if value is None else f"{value:.2f}"


def render_markdown(summary, failures):
    models = list(summary["models"])
    lines = ["# 人设一致性评测", "", "| 维度 | " + " | ".join(models) + " |", "|---|" + "---:|" * len(models)]
    lines.append("| **总分** | " + " | ".join(fmt(summary["models"][m]["overall"]) for m in models) + " |")
    for d in summary["dimensions"]:
        lines.append(f"| {summary['labels'].get(d, d)} | " + " | ".join(fmt(summary["models"][m]["dimensions"][d]["mean"]) for m in models) + " |")
    lines += ["", "出现率（只计数不打分）：", ""]
    for m in models:
        observed = summary["models"][m]["observed"]
        lines.append(f"- {m}：" + "，".join(f"{OBSERVED[k]} {fmt(v)}" for k, v in observed.items()) +
                     f"；回复 {summary['models'][m]['replies']}，已评 {summary['models'][m]['judged']}；返回版本 {summary['models'][m]['model_versions']}")
    if failures:
        lines += ["", f"失败 {len(failures)} 项（不计入分数）：", ""] + [f"- {f}" for f in failures[:50]]
    for m in models:
        lines += ["", f"## {m} 得分最低的 5 条", ""]
        for w in summary["models"][m]["worst"]:
            lines += [f"**{w['case_id']}（{w['category']}，第 {w['turn']} 轮，均分 {w['mean']:.2f}）**", "",
                      "输入：" + w["user"], "", "回复：", "", "> " + w["reply"].replace("\n", "\n> "), ""]
            lines += [f"- {d}" for d in w["deductions"]] + [""]
    return "\n".join(lines).rstrip() + "\n"
