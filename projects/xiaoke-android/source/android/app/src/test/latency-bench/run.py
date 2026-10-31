#!/usr/bin/env python3
"""小克生产客户端延迟基准：固定虚构输入，真实流式调用，逐请求保留计时。"""
import argparse
import copy
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time

from runtime import HERE, PROJECT, PERSONA_EVAL, LatencyBridge, digest, private_run, resolve_ref, save
from reporting import classify, write_report

MODELS = ("gemini-4-argon", "gemini-3.8-flash")
SCENARIOS = {"private_chat", "tool", "qq_group", "background"}


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def cases(smoke=False, selected=None, include_optional=False):
    data = read_json(HERE / "cases.json")
    if data.get("schema_version") != 1:
        raise ValueError("场景版本不支持")
    records = data["cases"]
    if len({c["id"] for c in records}) != len(records):
        raise ValueError("场景 ID 重复")
    if {c["scenario"] for c in records} != SCENARIOS:
        raise ValueError("场景必须覆盖私聊、工具、QQ群、后台任务")
    for case in records:
        if not case["user"].strip() or not isinstance(case["tool_results"], dict):
            raise ValueError("场景用户输入或工具 fixture 无效")
        if case["scenario"] == "qq_group" and (case["context"]["authenticatedOwner"] or case["context"]["memories"]):
            raise ValueError("群聊 fixture 不能携带主人私有记忆或伪造身份")
        if (case["scenario"] == "tool" and case["expected"]["required_tool"] not in case["tool_results"]
                and not (case.get("real_web") is True and case["expected"]["required_tool"] == "web_research")):
            raise ValueError("工具场景缺少确定的只读结果")
        if case["scenario"] == "tool" and not case.get("real_web"):
            for anchor in case["expected"].get("answer_anchors", []):
                if anchor in case["user"] or anchor in json.dumps(case["context"], ensure_ascii=False):
                    raise ValueError("工具答案提前泄漏进用户提示或上下文")
    if selected:
        names = set(selected.split(","))
        if names - {c["id"] for c in records}:
            raise ValueError("未知场景 ID")
        records = [c for c in records if c["id"] in names]
    elif smoke:
        records = [c for c in records if c.get("smoke")]
    elif not include_optional:
        records = [c for c in records if not c.get("policy_ablation") and not c.get("real_web")]
    return records


def variants(items):
    parsed = []
    for item in items or ["baseline=8e8ee82"]:
        label, separator, ref = item.partition("=")
        if not separator or not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9_-]{0,39}", label):
            raise ValueError("--variant 使用 标签=Git引用，例如 baseline=8e8ee82")
        if label in {p[0] for p in parsed}:
            raise ValueError("variant 标签重复")
        parsed.append([label, resolve_ref(ref)])
    return parsed


def read_persona():
    source = os.environ.get("XIAOKE_EVAL_PERSONA_FILE")
    if not source:
        raise ValueError("缺少 XIAOKE_EVAL_PERSONA_FILE")
    # Import the same privacy-aware production character-card importer as persona-eval.
    sys.path.insert(0, str(PERSONA_EVAL))
    try:
        from persona_input import load_persona
        return load_persona(source)
    finally:
        sys.path.pop(0)


def recipe(args, selected, sources, persona, voice):
    profile = {"maxOutputTokens": args.max_output_tokens, "contextBudget": args.context_budget,
               "thinking": args.thinking, "thinkingMode": "level", "maxHops": 4, "timeoutSeconds": args.timeout}
    if profile["contextBudget"] - profile["maxOutputTokens"] < 4096:
        raise ValueError("上下文预算至少比输出预算多 4096")
    return {
        "schema_version": 1, "baseline_commit": "8e8ee82ab392d3eb9a788ab87a3ca583a56a72d9",
        "scope": "production_prompt_and_streaming_client_chain", "cases_sha256": digest((HERE / "cases.json").read_bytes()),
        "case_ids": [c["id"] for c in selected], "models": args.models.split(","), "variants": sources,
        "repeat": args.repeat, "web_cache_states": args.web_cache.split(","), "profile": profile,
        "warm_from": args.warm_from,
        "persona_sha256": digest(persona), "voice_reminder_sha256": digest(voice),
        "endpoint_sha256": digest(os.environ.get("XIAOKE_EVAL_BASE_URL", "")),
        "order": "serial; variant order rotates by model/case/repeat/cache index",
        "runner_sha256": digest(Path(__file__).read_bytes()),
        "reporting_sha256": digest((HERE / "reporting.py").read_bytes()),
    }


def schedules(manifest, selected):
    sources = manifest["variants"]
    index = 0
    for repeat in range(manifest["repeat"]):
        for model in manifest["models"]:
            for case in selected:
                states = ["not_applicable"] if case["scenario"] == "background" else manifest["web_cache_states"]
                for state in states:
                    offset = (index + repeat) % len(sources)
                    order = sources[offset:] + sources[:offset]
                    for label, ref in order:
                        yield {"variant": label, "source_ref": ref, "model": model, "case": case,
                               "repeat": repeat, "webCache": state, "schedule_index": index}
                    index += 1


def sample_name(job):
    return f'{job["variant"]}__{job["model"]}__{job["case"]["id"]}__r{job["repeat"]:02}__{job["webCache"]}.json'


def observed_probe(record, source):
    probes = [q for q in record.get("requests", []) if q.get("purpose") == "web_probe"]
    if (record.get("webModeSource") != "real_probe" or record.get("webMode") not in {"split", "none", "unknown"}
            or not probes or any(not q.get("modelVersions") for q in probes)
            or any(v != record["model"] for q in probes for v in q["modelVersions"])):
        return None
    return {"mode": record["webMode"], "source": source, "model_versions": [record["model"]]}


def generate(args):
    if not args.gradle_home:
        raise ValueError("缺少 GRADLE_HOME 或 --gradle-home")
    for name in ("XIAOKE_EVAL_BASE_URL", "XIAOKE_EVAL_API_KEY"):
        if not os.environ.get(name):
            raise ValueError("缺少 " + name)
    selected = cases(args.smoke, args.cases)
    source_variants = variants(args.variant)
    persona = read_persona()
    voice_path = os.environ.get("XIAOKE_EVAL_VOICE_FILE")
    voice = Path(voice_path).read_text(encoding="utf-8") if voice_path else ""
    manifest = recipe(args, selected, source_variants, persona, voice)
    if any(m not in MODELS for m in manifest["models"]):
        raise ValueError("被测模型必须是指定的 argon/flash")
    if args.repeat < 1 or args.repeat > 30:
        raise ValueError("repeat 必须在 1–30")
    if args.max_chains is not None and args.max_chains < 1:
        raise ValueError("max-chains 必须大于零")
    if not set(manifest["web_cache_states"]) <= {"cold", "warm"}:
        raise ValueError("web-cache 只能是 cold,warm")
    warm_cache = {}
    for name in (args.warm_from or "").split(","):
        if not name:
            continue
        previous = private_run(name)
        previous_recipe = read_json(previous / "manifest.json")["recipe"]
        if previous_recipe["endpoint_sha256"] != manifest["endpoint_sha256"]:
            raise ValueError("warm-from 与当前中转不同，拒绝借用能力缓存")
        for path in sorted((previous / "records").glob("*.json")):
            record = read_json(path)
            observed = observed_probe(record, name + "/" + path.name)
            if observed:
                warm_cache[record["model"]] = observed
    if "warm" in manifest["web_cache_states"] and not (
        "cold" in manifest["web_cache_states"] and any(v[0] in ("baseline", "streaming") for v in source_variants)
    ) and any(model not in warm_cache for model in manifest["models"]):
        raise ValueError("warm 需要同批真实 cold probe，或 --warm-from 指向已有真实探测运行")
    directory = private_run(args.run)
    if (directory / "manifest.json").exists():
        if not args.resume:
            raise ValueError("运行目录已有测量，换一个 --run 或显式 --resume；不会重复计入旧响应")
        old = read_json(directory / "manifest.json")
        if old["recipe"] != manifest:
            raise ValueError("恢复运行的输入/代码已变化，请使用新 run 名称")
    else:
        save(directory / "manifest.json", {"recipe": manifest, "bridges": {}})
        save(directory / "inputs.json", {"persona": persona, "voiceReminder": voice, "cases": selected})
    bridges = {}
    stop_requested = False
    def stop_after_request(signum, frame):
        nonlocal stop_requested
        stop_requested = True
        print("将在当前真实生成链路结束后暂停，不中断在途请求。", flush=True)
    old_signal = signal.signal(signal.SIGINT, stop_after_request)
    try:
        for label, ref in source_variants:
            bridges[label] = LatencyBridge(args.gradle_home, directory / "kotlin" / label, ref)
        frozen = read_json(directory / "manifest.json")
        metadata = {label: bridge.metadata for label, bridge in bridges.items()}
        if frozen["bridges"] and frozen["bridges"] != metadata:
            raise ValueError("恢复运行的生产源码/依赖已变化，请使用新 run 名称")
        frozen["bridges"] = metadata
        save(directory / "manifest.json", frozen)
        planned = list(schedules(manifest, selected))
        print(f"计划 {len(planned)} 条真实生成链路；工具和冷探测会产生额外请求。", flush=True)
        dispatched = 0
        for number, job in enumerate(planned, 1):
            if stop_requested or (directory / "STOP").exists() or (args.max_chains and dispatched >= args.max_chains):
                print("已暂停后续派发；保留当前全部样本。", flush=True)
                break
            output = directory / "records" / sample_name(job)
            if output.exists():
                prior = read_json(output)
                observed = observed_probe(prior, args.run + "/" + output.name)
                if observed:
                    warm_cache[job["model"]] = observed
                print(f"保留已有样本 {number}/{len(planned)}", flush=True)
                continue
            payload = {"op": "benchmark", "persona": persona, "case": copy.deepcopy(job["case"]),
                       "profile": copy.deepcopy(manifest["profile"]), "model": job["model"],
                       "webCache": job["webCache"], "variant": job["variant"], "voiceReminder": voice}
            if job["webCache"] == "warm":
                cached = warm_cache.get(job["model"])
                if cached is None:
                    raise ValueError("前序真实探测没有得到可记录的模式，不能伪造 warm 样本")
                payload.update(warmWebMode=cached["mode"], warmWebModeSource=cached["source"])
            started = time.monotonic()
            try:
                record = bridges[job["variant"]].call(payload)
            except (ValueError, OSError, subprocess.SubprocessError) as exc:
                # Keep errors content-free: provider error messages may echo the private prompt.
                record = {"status": "bridge_failed", "failure_type": type(exc).__name__, "metrics": {}}
            record.update(case_id=job["case"]["id"], scenario=job["case"]["scenario"], model=job["model"],
                          variant=job["variant"], source_ref=job["source_ref"], repeat=job["repeat"],
                          webCache=job["webCache"], schedule_index=job["schedule_index"],
                          harness_elapsed_ms=(time.monotonic()-started)*1000)
            record.update(classify(record, job["case"]))
            if job["webCache"] == "warm":
                record["warm_probe_evidence"] = copy.deepcopy(warm_cache[job["model"]])
            save(output, record)
            dispatched += 1
            observed = observed_probe(record, args.run + "/" + output.name)
            if observed:
                warm_cache[job["model"]] = observed
            status = "完成" if record.get("status") == "completed" else "失败（已保留）"
            print(f'{number}/{len(planned)} {job["variant"]} {job["model"]} {job["case"]["id"]} {job["webCache"]}：{status}', flush=True)
        report(args.run)
    finally:
        signal.signal(signal.SIGINT, old_signal)
        for bridge in bridges.values():
            bridge.close()


def report(name):
    directory = private_run(name)
    manifest = read_json(directory / "manifest.json")
    records = [read_json(p) for p in sorted((directory / "records").glob("*.json"))]
    result = write_report(directory, records, [manifest])
    print(f'报告已写入 latency-output/{name}/report.md；{len(records)} 个保留样本。', flush=True)
    return result


def compare(args):
    manifests = []
    records = []
    reference = None
    implementations = {}
    for name in args.inputs.split(","):
        source = private_run(name)
        manifest = read_json(source / "manifest.json")
        current = {key: manifest["recipe"][key] for key in ("cases_sha256", "persona_sha256", "voice_reminder_sha256", "profile", "endpoint_sha256")}
        if reference is not None and current != reference:
            raise ValueError("被比较运行的人设、语料、输出/思考配置或中转不同，不能作配对消融")
        reference = current
        for label, metadata in manifest.get("bridges", {}).items():
            if label in implementations and implementations[label] != metadata:
                raise ValueError("同一 variant 标签对应不同生产或桥源码，请分开报告")
            implementations[label] = metadata
        manifests.append(manifest)
        records += [read_json(p) for p in sorted((source / "records").glob("*.json"))]
    target = private_run(args.run)
    write_report(target, records, manifests, args.baseline)
    print(f"配对报告已写入 latency-output/{args.run}/report.md", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("validate")
    run = sub.add_parser("run")
    run.add_argument("--run", required=True)
    run.add_argument("--variant", action="append", help="标签=Git引用；多项在同一批里轮换配对")
    run.add_argument("--models", default=",".join(MODELS))
    run.add_argument("--repeat", type=int, default=1)
    run.add_argument("--web-cache", default="cold,warm")
    run.add_argument("--warm-from", help="复用已有运行的真实Web能力探测结果，逗号分隔run名称；不复用回复")
    run.add_argument("--smoke", action="store_true")
    run.add_argument("--cases", help="逗号分隔的场景 ID")
    run.add_argument("--resume", action="store_true")
    run.add_argument("--max-chains", type=int, help="本次进程最多派发多少条链路；之后可用同一run恢复")
    run.add_argument("--gradle-home", default=os.environ.get("GRADLE_HOME"))
    run.add_argument("--max-output-tokens", type=int, default=32768)
    run.add_argument("--context-budget", type=int, default=65536)
    run.add_argument("--thinking", choices=("low", "medium", "high"), default="low")
    run.add_argument("--timeout", type=int, default=180)
    rep = sub.add_parser("report")
    rep.add_argument("--run", required=True)
    comp = sub.add_parser("compare")
    comp.add_argument("--run", required=True)
    comp.add_argument("--inputs", required=True)
    comp.add_argument("--baseline", default="baseline")
    args = parser.parse_args()
    if args.command == "validate":
        selected = cases(include_optional=True)
        print(json.dumps({"cases": len(selected), "default_cases": len(cases()), "scenarios": sorted(SCENARIOS), "smoke_cases": sum(c.get("smoke", False) for c in selected)}, ensure_ascii=False))
    elif args.command == "run":
        generate(args)
    elif args.command == "report":
        report(args.run)
    else:
        compare(args)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.SubprocessError) as failure:
        # These errors are local validation messages, except OS/process failures that may include a private path.
        message = str(failure) if type(failure) is ValueError else type(failure).__name__
        print("延迟基准停止：" + message, file=sys.stderr)
        raise SystemExit(2)
