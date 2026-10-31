"""Aggregate real streamed runs; absent metrics and failed calls never become zero."""
from collections import defaultdict
import math
from pathlib import Path
import re
from statistics import mean

from runtime import digest, save

ACK = re.compile(r"^(?:好[的呀]?[，,。！! ]*|收到[，,。！! ]*|(?:我)?(?:先)?(?:查|看|找|确认)(?:一下|一眼)?[，,。！! ]*|稍等[，,。！! ]*|等我一下[，,。！! ]*)+$")
LABELS = {"private_chat": "私聊", "tool": "工具", "qq_group": "QQ群后台可交付", "background": "后台任务"}


def number(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) and value >= 0


def quantile(values, p):
    if not values:
        return None
    ordered = sorted(values)
    offset = (len(ordered) - 1) * p
    lo, hi = math.floor(offset), math.ceil(offset)
    return ordered[lo] + (ordered[hi] - ordered[lo]) * (offset - lo)


def metric_values(record):
    metrics = record.get("metrics", {})
    requests = record.get("requests", [])
    answer = [r for r in requests if r.get("purpose") in ("reply", "answer")]
    transport = [r.get("timing", {}).get("firstTextMs") for r in answer]
    observed = [v for v in transport if number(v)]
    server_tokens = [r.get("usage", {}).get("promptTokenCount") for r in requests]
    seen_tokens = [v for v in server_tokens if number(v)]
    # Transport TTFT is the first answer request that produced visible text, not a probe or a loading callback.
    out = {
        "transport_first_visible_ms": observed[0] if observed else None,
        "turn_first_visible_ms": metrics.get("firstTextMs"),
        "turn_complete_ms": metrics.get("totalMs"),
        "prompt_build_ms": metrics.get("promptBuildMs"),
        "web_probe_ms": metrics.get("probeMs"),
        "host_ack_ms": metrics.get("hostAckMs"),
        "estimated_input_tokens": metrics.get("estimatedInputTokens"),
        "server_prompt_tokens_all_requests": sum(seen_tokens) if seen_tokens else None,
        "server_usage_request_coverage": len(seen_tokens),
        "output_chars": len(record.get("text", "")),
    }
    # The legacy key is retained in JSON, but the report only treats it as an
    # anchor appearance timestamp. Nonempty casual text is not an answer check.
    if "answer_started_ms" in record:
        has_anchors = record.get("anchor_count", int("anchor" in record.get("answer_check", ""))) > 0
        out["answer_started_ms"] = record["answer_started_ms"] if has_anchors else None
    return out


def identity(record):
    """Exact relay metadata equality, without claiming to verify the upstream model."""
    requested = record["model"]
    relevant = [r for r in record.get("requests", []) if r.get("transmitted") is not False]
    observed = [r.get("modelVersions", []) for r in relevant]
    versions = sorted({v for values in observed for v in values if isinstance(v, str) and v})
    if any(v != requested for v in versions):
        return "mismatch"
    if not relevant or any(not v for v in observed):
        return "missing"
    return "matched"


def classify(record, case):
    """Mechanical fixture matching only; negation and semantic truth are not judged.

    answer_verified/answer_started_ms are compatibility fields, not claims of
    verified correctness. The report explicitly labels the anchor measurement.
    """
    expected = case["expected"]
    text = record.get("text", "").strip()
    if record.get("status") != "completed":
        return {"response_kind": "failed", "answer_verified": False, "answer_started_ms": None}
    if not text:
        return {"response_kind": "empty", "answer_verified": False, "answer_started_ms": None}
    if ACK.fullmatch(text):
        return {"response_kind": "acknowledgement_only", "answer_verified": False, "answer_started_ms": None}
    if len(text) > expected.get("max_chars", 10000):
        return {"response_kind": "overlong_unverified", "answer_verified": False, "answer_started_ms": None}
    anchors = expected.get("answer_anchors", [])
    required_tool = expected.get("required_tool")
    used_tools = {t.get("name") for t in record.get("tools", [])}
    verified = all(a in text for a in anchors) and (not required_tool or required_tool in used_tools)
    if case.get("real_web"):
        verified = verified and any(
            tool.get("name") == "web_research" and tool.get("status") == "real_web"
            and tool.get("response", {}).get("answer") and not tool.get("response", {}).get("error")
            and (tool.get("response", {}).get("sources") or tool.get("response", {}).get("pages"))
            for tool in record.get("tools", [])
        )
    kind = expected["kind"] if verified else "unverified_answer"
    if case.get("real_web") and verified:
        kind = "real_web_answer"
    events = record.get("text_events", [])
    answer_at = None
    if verified:
        for event in events:
            segment = event.get("text", "").strip()
            if segment and not ACK.fullmatch(segment) and all(a in segment for a in anchors) and number(event.get("atMs")):
                answer_at = event["atMs"]
                break
    return {"response_kind": kind, "answer_verified": verified, "mechanical_check_passed": verified,
            "anchor_count": len(anchors), "answer_started_ms": answer_at,
            "answer_check": ("mechanical real web result/source/page presence and anchor substring; semantic truth not scored" if case.get("real_web") else
                             "mechanical fixture anchor substrings and required tool; semantic truth not scored" if anchors else
                             "nonempty non-acknowledgement; semantic quality not scored")}


METRICS = (
    "transport_first_visible_ms", "turn_first_visible_ms", "answer_started_ms", "turn_complete_ms",
    "prompt_build_ms", "web_probe_ms", "host_ack_ms", "estimated_input_tokens", "server_prompt_tokens_all_requests", "output_chars",
)


def summary(records):
    groups = defaultdict(list)
    for record in records:
        groups[(record["variant"], record["model"], record["scenario"], record["webCache"], identity(record))].append(record)
    rows = []
    for key, members in sorted(groups.items()):
        completed = [r for r in members if r.get("status") == "completed"]
        eligible = completed if key[-1] != "mismatch" else []
        values = [metric_values(r) for r in eligible]
        row = dict(zip(("variant", "model", "scenario", "web_cache", "version_status"), key))
        row.update(attempted=len(members), completed=len(completed), failed=len(members) - len(completed),
                   excluded_chains=len(members) if key[-1] == "mismatch" else 0,
                   verified_answers=sum(bool(r.get("answer_verified")) for r in eligible), metrics={})
        row["response_kinds"] = {kind: sum(r.get("response_kind", "unclassified") == kind for r in eligible)
                                 for kind in sorted({r.get("response_kind", "unclassified") for r in eligible})}
        row["under_3s"] = {}
        for label, metric in (("host_ack", "host_ack_ms"), ("backend_first_text", "turn_first_visible_ms"),
                              ("answer_start", "answer_started_ms"), ("complete_answer", "turn_complete_ms")):
            goal_records = [r for r in eligible if label != "answer_start" or r.get("answer_verified")]
            goal_values = [metric_values(r) for r in goal_records]
            observed = [v[metric] for v in goal_values if number(v.get(metric))]
            reached = sum(value <= 3000 for value in observed)
            denominator = len(members) if key[-1] == "matched" else 0
            row["under_3s"][label] = {"reached": reached if denominator else 0, "eligible_chains": denominator,
                                      "observed": len(observed), "rate": reached / denominator if denominator else None}
        for metric in METRICS:
            samples = [v[metric] for v in values if number(v.get(metric))]
            row["metrics"][metric] = {"n": len(samples), "mean": mean(samples) if samples else None,
                                      "p50": quantile(samples, .5), "p95": quantile(samples, .95)}
        rows.append(row)
    return rows


def paired_deltas(records, baseline="baseline"):
    paired = defaultdict(dict)
    for record in records:
        key = (record["model"], record["case_id"], record["repeat"], record["webCache"])
        if record["variant"] in paired[key]:
            raise ValueError("同一配对有重复 variant 结果；请不要合并同一次测量的副本")
        paired[key][record["variant"]] = record
    deltas = defaultdict(list)
    attempted = defaultdict(int)
    for key, variants in paired.items():
        base = variants.get(baseline)
        for variant, record in variants.items():
            if variant == baseline:
                continue
            group = (variant, record["model"], record["scenario"], record["webCache"])
            attempted[group] += 1
            if (not base or base.get("status") != "completed" or record.get("status") != "completed"
                    or identity(base) != "matched" or identity(record) != "matched"):
                continue
            before, after = metric_values(base), metric_values(record)
            delta = {}
            for metric in METRICS:
                a, b = before.get(metric), after.get(metric)
                if number(a) and number(b):
                    delta[metric] = {"absolute": b-a, "percent": (b-a)/a*100 if a else None}
            deltas[group].append(delta)
    result = []
    for key, count in sorted(attempted.items()):
        pairs = deltas[key]
        row = dict(zip(("variant", "model", "scenario", "web_cache"), key))
        row.update(candidate_count=count, successful_pairs=len(pairs), baseline=baseline, metrics={})
        for metric in METRICS:
            samples = [p[metric] for p in pairs if metric in p]
            percentages = [p["percent"] for p in samples if p["percent"] is not None]
            row["metrics"][metric] = {"n": len(samples),
                "mean_delta": mean(p["absolute"] for p in samples) if samples else None,
                "mean_delta_percent": mean(percentages) if percentages else None}
        result.append(row)
    return result


def tool_summary(records):
    groups = defaultdict(list)
    for record in records:
        if identity(record) == "mismatch":
            continue
        for tool in record.get("tools", []):
            timing = tool.get("timing", {})
            mode = "real_web" if tool.get("status") == "real_web" else "fixture" if tool.get("status") == "fixture" else tool.get("status", "unknown")
            key = (record["variant"], record["model"], record["case_id"], record["webCache"], tool.get("name"), identity(record), mode)
            values = {}
            for metric, start, end in (
                ("tool_execution_ms", "callReceivedMs", "fixtureFinishedMs"),
                ("result_to_next_visible_ms", "fixtureFinishedMs", "nextTextMs"),
                ("call_to_completed_answer_ms", "callReceivedMs", "nextFinishedMs"),
            ):
                if number(timing.get(start)) and number(timing.get(end)) and timing[end] >= timing[start]:
                    values[metric] = timing[end] - timing[start]
            groups[key].append(values)
    rows = []
    for key, values in sorted(groups.items()):
        row = dict(zip(("variant", "model", "case_id", "web_cache", "tool", "version_status", "execution_mode"), key))
        row["calls"] = len(values)
        row["metrics"] = {}
        for metric in ("tool_execution_ms", "result_to_next_visible_ms", "call_to_completed_answer_ms"):
            samples = [v[metric] for v in values if metric in v]
            row["metrics"][metric] = {"n": len(samples), "p50": quantile(samples, .5), "p95": quantile(samples, .95)}
        rows.append(row)
    return rows


def show(value):
    return "—" if value is None else f"{value:.1f}"


def write_report(directory, records, manifests, baseline="baseline"):
    directory = Path(directory)
    rows = summary(records)
    comparisons = paired_deltas(records, baseline)
    tools = tool_summary(records)
    result = {"schema_version": 1, "scope": "production_prompt_and_streaming_client_chain",
              "reporter_sha256": digest(Path(__file__).read_bytes()),
              "manifests": manifests, "summary": rows, "paired_deltas": comparisons, "tool_round_trips": tools}
    save(directory / "summary.json", result)
    lines = [
        "# 小克延迟基准", "",
        "范围：真实生产提示词、工具声明及 GeminiClient 流式读取；不覆盖完整 Android Engine 调度、SQLite/向量检索或 QQ 收件端网络。QQ群计时止于后台可交付文本，不执行外发。",
        "",
        "首字只计生产 onText 中可见的主对话模型文本，thought、usage、progress/loading、工具内部模型文本和 host ack 不计入。轮次首字包含组装及冷 Web 能力探测；transport 首字从对应答复请求开始计。host ack 单独计时，不代表已完成。基础工具使用本地只读虚构结果，不能视为手机或第三方执行耗时；显式 real_web 场景另计真实公开资料查询往返。",
        "",
        "机械检查区分非空短回应、固定确认格式，以及锚点子串/指定工具是否出现；不能识别否定、编造或语义答对。旧 JSON 字段 answer_verified 仅为兼容名称，不表示正确性核验。有锚点的场景填写锚点出现时间，无锚点场景留空；该时间不是语义答案起点。成功样本时间与全部失败数并列，小样本 P95 仅描述本次测量。估算 token 只计初始主请求；服务端 token 汇总全部请求（含探测/工具）。",
        "",
        "版本校验按每个实际请求返回的 modelVersion 原文严格相等检查，包括探测和工具续答；任一请求不一致则整条链路不进入延迟统计。缺失元数据单列为 missing，仅供诊断，不进入配对提速结论。matched 仍只是中转返回的元数据一致，不是上游身份认证。",
        "",
        "|版本|模型|场景|Web缓存|型号元数据|完成/总数|机械检查通过|Transport首字 P50 ms|轮次首字 P50 ms|锚点出现 P50 ms|完成 P50 ms|完成 P95 ms|探测 P50 ms|估算输入token均值|服务端全部请求输入token均值|",
        "|---|---|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for r in rows:
        m = r["metrics"]
        cells = [r["variant"], r["model"], LABELS.get(r["scenario"], r["scenario"]), r["web_cache"], r["version_status"],
                 f'{r["completed"]}/{r["attempted"]}', str(r["verified_answers"])]
        cells += [show(m[k]["p50"]) for k in ("transport_first_visible_ms", "turn_first_visible_ms", "answer_started_ms", "turn_complete_ms")]
        cells += [show(m["turn_complete_ms"]["p95"]), show(m["web_probe_ms"]["p50"]),
                  show(m["estimated_input_tokens"]["mean"]), show(m["server_prompt_tokens_all_requests"]["mean"])]
        lines.append("|" + "|".join(cells) + "|")
    if comparisons:
        lines += ["", "配对变化（负数表示更快；仅相同场景、模型、重复编号和 Web 缓存状态配对）：", "",
                  "|版本|模型|场景|Web缓存|成功配对/候选|轮次首字均值变化 ms|完成均值变化 ms|完成均值变化 %|",
                  "|---|---|---|---|---:|---:|---:|---:|"]
        for r in comparisons:
            m = r["metrics"]
            lines.append("|" + "|".join([r["variant"], r["model"], LABELS.get(r["scenario"], r["scenario"]), r["web_cache"],
                f'{r["successful_pairs"]}/{r["candidate_count"]}', show(m["turn_first_visible_ms"]["mean_delta"]),
                show(m["turn_complete_ms"]["mean_delta"]), show(m["turn_complete_ms"]["mean_delta_percent"])]) + "|")
    matched = [r for r in rows if r["version_status"] == "matched"]
    if matched:
        lines += ["", "3 秒目标（仅型号元数据一致链路；分母包含该组失败，缺失计时不算达成。host ack 对未触发的轮次记未达成，不混入模型指标）：", "",
                  "|版本|模型|场景|Web缓存|host ack≤3s|后台首字≤3s|锚点出现≤3s|链路结束≤3s|",
                  "|---|---|---|---|---:|---:|---:|---:|"]
        for r in matched:
            counts = [f'{r["under_3s"][name]["reached"]}/{r["under_3s"][name]["eligible_chains"]}'
                      for name in ("host_ack", "backend_first_text", "answer_start", "complete_answer")]
            lines.append("|" + "|".join([r["variant"], r["model"], LABELS.get(r["scenario"], r["scenario"]), r["web_cache"], *counts]) + "|")
    if tools:
        lines += ["", "工具往返（fixture 与真实公开资料查询分开，执行和模型续答分列）：", "",
                  "|版本|模型|场景|Web缓存|型号元数据|工具|执行模式|调用数|工具执行 P50 ms|结果→续答首字 P50 ms|调用→续答完成 P50 ms|",
                  "|---|---|---|---|---|---|---|---:|---:|---:|---:|"]
        for r in tools:
            lines.append("|" + "|".join([r["variant"], r["model"], r["case_id"], r["web_cache"], r["version_status"], str(r["tool"]), r["execution_mode"], str(r["calls"]),
                *[show(r["metrics"][m]["p50"]) for m in ("tool_execution_ms", "result_to_next_visible_ms", "call_to_completed_answer_ms")]]) + "|")
    acks = [r for r in rows if r["metrics"]["host_ack_ms"]["n"]]
    if acks:
        lines += ["", "本机短确认（host ack；不计入模型首字或完成时间）：", "",
                  "|版本|模型|场景|Web缓存|型号元数据|触发数|P50 ms|",
                  "|---|---|---|---|---|---:|---:|"]
        for r in acks:
            values = r["metrics"]["host_ack_ms"]
            lines.append("|" + "|".join([r["variant"], r["model"], LABELS.get(r["scenario"], r["scenario"]), r["web_cache"],
                r["version_status"], str(values["n"]), show(values["p50"])]) + "|")
    lines += ["", "逐请求 usage、计时、工具往返和完整真实回复见本目录 records/；这些文件包含私人输入或生成内容，只保存在忽略目录。", ""]
    (directory / "report.md").write_text("\n".join(lines), encoding="utf-8")
    return result
