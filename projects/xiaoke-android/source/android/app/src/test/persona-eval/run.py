#!/usr/bin/env python3
"""小克人设评测：生产提示词 + 虚构场景，两款模型各自回复，评委 A/B 盲评。"""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import copy
import json
import os
from pathlib import Path
import sys

from runtime import OUTPUT, prompt_bridge, read, save
from transport import Client, ModelError, gemini_reply, judge_reply

MODELS = ["gemini-4-argon", "gemini-3.8-flash"]


def env(name):
    value = os.environ.get(name, "").strip()
    if not value:
        raise ValueError(f"缺少环境变量 {name}")
    return value


def selected_cases(args):
    from corpus import load_cases
    cases = load_cases()["cases"]
    if args.categories:
        cases = [c for c in cases if c["category"] in args.categories.split(",")]
    return cases[:args.limit_cases] if args.limit_cases else cases


def prepare(args, directory):
    """Freezes the inputs of a run; a different persona or corpus under the same name would mix results."""
    from persona_input import load_persona
    from scoring import split_persona
    persona = load_persona(env("XIAOKE_EVAL_PERSONA_FILE"))
    voice = os.environ.get("XIAOKE_EVAL_VOICE_FILE")
    inputs = {"persona": persona, "voice_reminder": Path(voice).read_text(encoding="utf-8") if voice else "",
              "cases": selected_cases(args), "seed": args.seed,
              "profile": {"maxOutputTokens": args.max_output_tokens, "contextBudget": args.context_budget,
                          "thinking": args.thinking, "thinkingMode": "level", "tools": False}}
    path = directory / "inputs.json"
    if path.exists() and read(path) != inputs:
        raise ValueError(f"{path} 与本次输入不同；换一个 --run 名称")
    save(path, inputs)
    save(directory / "sections.json", split_persona(persona))
    return inputs


def context_for(case, patch, model):
    context = copy.deepcopy(case.get("context", {})) | copy.deepcopy(patch) | {"model": model}
    context.setdefault("time", "2026-10-31T12:00")
    context.setdefault("device", {"control": {"active": False}, "capture": {"packageName": ""}})
    return context


def generate_case(case, model, inputs, directory, bridge, client):
    path = directory / "conversations" / model / (case["id"] + ".json")
    if path.exists() and read(path)["status"] == "completed":
        return read(path)
    from scoring import score_rules
    history, records, patch = [], [], {}
    turns = [{"user": u, "score": False} for u in case.get("warmup", [])] + case["turns"]
    try:
        for index, turn in enumerate(turns, 1):
            patch.update(turn.get("context", {}))
            context = context_for(case, patch, model)
            history.append({"id": f"u{index}", "role": "user", "text": turn["user"]})
            prompt = bridge.call({"persona": inputs["persona"], "messages": history, "context": context,
                                  "profile": inputs["profile"], "voiceReminder": inputs["voice_reminder"]})
            request = prompt["request"]
            request["generationConfig"].update({"temperature": 0, "topP": 1, "seed": inputs["seed"]})
            reply = gemini_reply(client, bridge, model, request)
            if turn["score"]:
                record = {"model": model, "case_id": case["id"], "category": case["category"],
                          "category_label": case["category_label"], "turn_index": index, "user": turn["user"],
                          "reply": reply["text"], "history": copy.deepcopy(history[:-1]), "context": context,
                          "facts": turn.get("facts", []), "unknowns": turn.get("unknowns", []),
                          "rules": turn.get("rules", {}), "model_versions": reply["model_versions"],
                          "voice_mode": prompt["voiceMode"]}
                record["rule_scores"] = score_rules(record)
                records.append(record)
            history.append({"id": f"a{index}", "role": "assistant", "text": reply["text"], "source": "模型 " + model,
                            "wire": {"fingerprint": "persona-eval:" + model, "turns": [{"role": "model", "parts": reply["parts"]}]}})
        result = {"status": "completed", "records": records}
    except (ModelError, ValueError) as error:
        result = {"status": "failed", "error": f"第 {len(history)} 条：{error}", "records": records}
    save(path, result | {"model": model, "case_id": case["id"], "history": history})
    return result | {"model": model, "case_id": case["id"]}


def generate(args, directory, inputs, bridge):
    client = Client(env("XIAOKE_EVAL_BASE_URL"), env("XIAOKE_EVAL_API_KEY"), "gemini", directory / "requests", args.timeout)
    work = [(c, m) for c in inputs["cases"] for m in MODELS]
    failed = 0
    with ThreadPoolExecutor(args.workers) as pool:
        futures = [pool.submit(generate_case, c, m, inputs, directory, bridge, client) for c, m in work]
        for count, future in enumerate(as_completed(futures), 1):
            result = future.result()
            failed += result["status"] != "completed"
            print(f"generate {count}/{len(work)} {result['model']} {result['case_id']} {result['status']} {result.get('error', '')}", flush=True)
    return failed


def collect(directory, inputs):
    records, failures = [], []
    for model in MODELS:
        for case in inputs["cases"]:
            path = directory / "conversations" / model / (case["id"] + ".json")
            value = read(path) if path.exists() else {"status": "missing", "records": []}
            records.extend(value["records"])
            if value["status"] != "completed":
                failures.append(f"生成 {model} {case['id']}：{value.get('error', value['status'])}")
    return records, failures


def judge(args, directory, inputs, bridge):
    from blind import pair, unblind_scores
    from scoring import build_paired_judge_messages, parse_paired_judgement
    model = env("XIAOKE_EVAL_JUDGE_MODEL")
    protocol = os.environ.get("XIAOKE_EVAL_JUDGE_PROTOCOL", "gemini")
    client = Client(os.environ.get("XIAOKE_EVAL_JUDGE_BASE_URL") or env("XIAOKE_EVAL_BASE_URL"),
                    os.environ.get("XIAOKE_EVAL_JUDGE_API_KEY") or env("XIAOKE_EVAL_API_KEY"),
                    protocol, directory / "judge-requests", args.timeout)
    sections = read(directory / "sections.json")
    records, _ = collect(directory, inputs)
    groups = {}
    for record in records:
        groups.setdefault((record["case_id"], record["turn_index"]), []).append(record)

    def one(rows):
        samples, originals, replacements = pair(rows, inputs["seed"])
        paths = {label: directory / "judgements" / r["model"] / f"{r['case_id']}-{r['turn_index']}.json" for label, r in originals.items()}
        if all(p.exists() and read(p)["status"] == "completed" for p in paths.values()):
            return None
        messages = build_paired_judge_messages(inputs["persona"], sections, samples)
        error = parsed = None
        for attempt in range(3):
            try:
                raw, keys = judge_reply(client, bridge, model, messages, inputs["seed"] + attempt)
                parsed = parse_paired_judgement(raw, sections, {label: r["reply"] for label, r in samples.items()})
                break
            except ValueError as failure:
                # Tell the judge what was wrong; the changed prompt is a new cached request.
                error = failure
                messages = copy.deepcopy(messages)
                messages[0]["content"] += f"\n上次输出不合格：{failure}。evidence 必须逐字取自对应候选的 reply；分数只能是 1–5 整数或 null。"
            except ModelError as failure:
                error = failure
                break
        if parsed is None:
            for label, r in originals.items():
                save(paths[label], {"status": "failed", "error": str(error)})
            return f"评委 {rows[0]['case_id']} 第 {rows[0]['turn_index']} 轮：{error}"
        for label, r in originals.items():
            save(paths[label], {"status": "completed", "record": r | {
                "judge_scores": unblind_scores(parsed[label], replacements[label], r["reply"]),
                "judge_model": model, "blind_label": label, "judge_request_hashes": keys}})
        return None

    pairs = [rows for rows in groups.values() if len(rows) == 2]
    failed = 0
    with ThreadPoolExecutor(args.workers) as pool:
        for count, future in enumerate(as_completed([pool.submit(one, rows) for rows in pairs]), 1):
            error = future.result()
            failed += error is not None
            print(f"judge {count}/{len(pairs)} {error or 'ok'}", flush=True)
    return failed


def report(directory, inputs):
    from reporting import render_markdown, summarize
    records, failures = collect(directory, inputs)
    judged = []
    for record in records:
        path = directory / "judgements" / record["model"] / f"{record['case_id']}-{record['turn_index']}.json"
        value = read(path) if path.exists() else {"status": "missing"}
        judged.append(value["record"] if value["status"] == "completed" else record)
        if value["status"] != "completed":
            failures.append(f"评委 {record['model']} {record['case_id']} 第 {record['turn_index']} 轮：{value.get('error', value['status'])}")
    summary = summarize(judged, MODELS, read(directory / "sections.json"))
    save(directory / "summary.json", summary)
    save(directory / "scored-records.json", judged)
    (directory / "report.md").write_text(render_markdown(summary, failures), encoding="utf-8")
    print(f"报告：{directory / 'report.md'}；回复 {len(records)}，失败 {len(failures)}")
    return len(failures)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["validate", "prepare", "generate", "judge", "report", "all"])
    parser.add_argument("--run", default="default")
    parser.add_argument("--gradle-home", default=os.environ.get("GRADLE_HOME"))
    parser.add_argument("--categories")
    parser.add_argument("--limit-cases", type=int)
    parser.add_argument("--workers", type=int, default=2)
    parser.add_argument("--timeout", type=int, default=210)
    parser.add_argument("--seed", type=int, default=20261031)
    parser.add_argument("--max-output-tokens", type=int, default=32768)
    parser.add_argument("--context-budget", type=int, default=65536)
    parser.add_argument("--thinking", choices=["low", "medium", "high"], default="low")
    args = parser.parse_args(argv)
    if args.command == "validate":
        from corpus import load_cases, validate
        print(json.dumps(validate(load_cases()), ensure_ascii=False, indent=2))
        return 0
    directory = OUTPUT / args.run
    if args.command == "report":
        return int(bool(report(directory, read(directory / "inputs.json"))))
    inputs = prepare(args, directory) if args.command in ("prepare", "generate", "all") else read(directory / "inputs.json")
    if not args.gradle_home:
        raise ValueError("缺少 GRADLE_HOME 或 --gradle-home")
    bridge = prompt_bridge(args.gradle_home, directory / "kotlin")
    try:
        if args.command == "prepare":
            for case in inputs["cases"]:
                bridge.call({"persona": inputs["persona"], "messages": [{"id": "u1", "role": "user", "text": case["turns"][0]["user"]}],
                             "context": context_for(case, case["turns"][0].get("context", {}), MODELS[0]),
                             "profile": inputs["profile"], "voiceReminder": inputs["voice_reminder"]})
            print(f"{len(inputs['cases'])} 个场景的提示词都能组装；没有调用模型。")
            return 0
        failures = 0
        if args.command in ("generate", "all"):
            failures += generate(args, directory, inputs, bridge)
        if args.command in ("judge", "all"):
            failures += judge(args, directory, inputs, bridge)
        if args.command == "all":
            failures += report(directory, inputs)
        return int(bool(failures))
    finally:
        bridge.close()


if __name__ == "__main__":
    sys.exit(main())
