#!/usr/bin/env python3
"""小克人设评测：真实生产提示词、虚构场景、冻结回复、独立评委。"""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import time

from runtime import HERE, PROJECT, Bridge, canonical, code_hashes, digest, private_run, save
from transport import Client, ModelError, endpoint, gemini_reply, judge_reply

MODELS = ["gemini-4-argon", "gemini-3.8-flash"]


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def read_persona():
    path = os.environ.get("XIAOKE_EVAL_PERSONA_FILE")
    if not path:
        raise ValueError("缺少 XIAOKE_EVAL_PERSONA_FILE：设为本地人设 JSON 或 UTF-8 文本的绝对路径")
    path = Path(path)
    if not path.is_absolute() or not path.is_file():
        raise ValueError("XIAOKE_EVAL_PERSONA_FILE 必须是可读取的绝对文件路径")
    tracked = subprocess.run(["git", "-C", str(path.parent), "ls-files", "--error-unmatch", "--", path.name],
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if tracked.returncode == 0:
        raise ValueError("人设文件已被 Git 跟踪，拒绝读取")
    from persona_input import load_persona
    persona = load_persona(path)
    if not isinstance(persona, dict) or not isinstance(persona.get("text"), str) or not persona["text"].strip():
        raise ValueError("人设必须有非空 text 字段")
    return persona


def selected_cases(args):
    from corpus import validate
    data = read_json(HERE / "cases.json")
    validate(data)
    cases = data["cases"]
    if args.categories:
        names = set(args.categories.split(","))
        if names - {c["category"] for c in cases}:
            raise ValueError("未知 category；使用 validate 查看分类")
        cases = [c for c in cases if c["category"] in names]
    if args.limit_cases:
        cases = cases[:args.limit_cases]
    return cases


def frozen(path, data):
    if path.exists() and read_json(path) != data:
        raise ValueError("运行输入已变更；请使用新 --run 名称，避免混合不同版本结果")
    save(path, data)


def prepare(args, directory):
    from scoring import split_persona
    persona = read_persona()
    cases = selected_cases(args)
    profile = {"maxOutputTokens": args.max_output_tokens, "contextBudget": args.context_budget,
               "thinking": args.thinking, "thinkingMode": "level", "tools": False}
    if profile["contextBudget"] - profile["maxOutputTokens"] < 4096:
        raise ValueError("上下文预算必须比输出预算至少多 4096")
    voice_path = os.environ.get("XIAOKE_EVAL_VOICE_FILE")
    reminder = Path(voice_path).read_text(encoding="utf-8") if voice_path else ""
    manifest = {"schema_version": 1, "base_commit": "3733a136aced096ae7d745c8cd0e99e08ac4a58d",
                "checkout_commit": subprocess.check_output(["git", "-C", str(PROJECT), "rev-parse", "HEAD"], text=True).strip(),
                "code_sha256": code_hashes(), "corpus_sha256": digest((HERE / "cases.json").read_bytes()),
                "persona_sha256": digest(persona), "reminder_sha256": digest(reminder), "models": MODELS,
                "profile": profile, "seed": args.seed, "temperature": 0, "top_p": 1,
                "case_ids": [c["id"] for c in cases], "mode": "reply_only_fixtures",
                "planned_target_calls_min": 2 * sum(len(c.get("warmup", [])) + len(c["turns"]) for c in cases),
                "planned_scored_replies": 2 * sum(sum(t.get("score", True) for t in c["turns"]) for c in cases),
                "planned_judge_pairs": sum(sum(t.get("score", True) for t in c["turns"]) for c in cases),
                "full_corpus": len(cases) == len(read_json(HERE / "cases.json")["cases"])}
    if (directory / "manifest.json").exists():
        # Committing identical evaluated files changes HEAD, not the measured implementation.
        manifest["checkout_commit"] = read_json(directory / "manifest.json")["checkout_commit"]
    frozen(directory / "manifest.json", manifest)
    frozen(directory / "inputs.json", {"persona": persona, "voice_reminder": reminder, "cases": cases})
    frozen(directory / "sections.json", split_persona(persona))
    return manifest


def bridge_for(args, directory):
    if not args.gradle_home:
        raise ValueError("缺少 GRADLE_HOME 或 --gradle-home（Gradle 8.11.1）")
    return Bridge(args.gradle_home, directory / "kotlin")


def normalized_context(case, patch, model):
    context = copy.deepcopy(case.get("context", {}))
    context.update(copy.deepcopy(patch))
    context["model"] = model
    context.setdefault("time", "2026-10-31T12:00")
    context.setdefault("scope", "owner:local")
    context.setdefault("device", {"control": {"active": False}, "capture": {"packageName": ""}})
    return context


def generate_case(case, model, manifest, inputs, directory, bridge, client):
    path = directory / "conversations" / model / (case["id"] + ".json")
    if path.exists() and read_json(path).get("status") == "completed":
        return read_json(path)
    from scoring import score_rules
    history, records, steps = [], [], []
    warmup = case.get("warmup", [])
    prompts = [{"user": u, "score": False} for u in warmup] + case["turns"]
    context_patch = {}
    try:
        for index, turn in enumerate(prompts):
            context_patch.update(turn.get("context", {}))
            context = normalized_context(case, context_patch, model)
            user = turn["user"]
            history.append({"id": f"u{index + 1}", "role": "user", "text": user})
            prompt = bridge.call({"persona": inputs["persona"], "messages": history, "context": context,
                                  "profile": manifest["profile"], "voiceReminder": inputs["voice_reminder"]})
            request = prompt["request"]
            request["generationConfig"].update({"temperature": manifest["temperature"], "topP": manifest["top_p"], "seed": manifest["seed"]})
            reply = gemini_reply(client, bridge, model, request)
            step = {"turn_index": index + 1, "request_hashes": reply["request_hashes"], "voice_mode": prompt["voiceMode"],
                    "selected_message_ids": prompt["selectedMessageIds"], "visible_trimmed": reply["trimmed"],
                    "usage": reply["usage"], "finish": reply["finish"]}
            steps.append(step)
            if turn.get("score", True):
                record = {"model": model, "case_id": case["id"], "category": case["category"],
                          "category_label": case["category_label"], "turn_index": index + 1,
                          "user": user, "reply": reply["text"], "history": copy.deepcopy(history[:-1]),
                          "context": context, "facts": turn.get("facts", []), "unknowns": turn.get("unknowns", []),
                          "rules": turn.get("rules", {}), "warmup_completed": min(index, len(warmup)),
                          "prompt_metadata": step}
                record["rule_scores"] = score_rules(record)
                records.append(record)
            history.append({"id": f"a{index + 1}", "role": "assistant", "text": reply["text"], "source": "模型 " + model,
                            "wire": {"fingerprint": "persona-eval:" + model,
                                     "turns": [{"role": "model", "parts": reply["parts"]}]}})
            # Checkpoints include actual generated turns; replay reuses their exact cached requests.
            save(path, {"status": "running", "model": model, "case_id": case["id"], "history": history, "steps": steps, "records": records})
        result = {"status": "completed", "model": model, "case_id": case["id"], "history": history, "steps": steps, "records": records}
    except (ModelError, ValueError) as error:
        result = {"status": "failed", "model": model, "case_id": case["id"], "history": history,
                  "steps": steps, "records": records, "error": str(error) if isinstance(error, ModelError) else "prompt_or_input_error"}
    save(path, result)
    return result


def generate(args, directory, bridge):
    manifest = read_json(directory / "manifest.json")
    inputs = read_json(directory / "inputs.json")
    base, key = os.environ.get("XIAOKE_EVAL_BASE_URL", ""), os.environ.get("XIAOKE_EVAL_API_KEY", "")
    client = Client(base, key, "gemini", directory / "requests", timeout=args.timeout)
    frozen(directory / "target-config.json", {"endpoint_sha256": digest(client.base), "protocol": "gemini",
                                             "timeout": args.timeout, "retries": 2})
    work = [(case, model) for case in inputs["cases"] for model in MODELS]
    failed = 0
    with ThreadPoolExecutor(max_workers=args.workers) as executor:
        futures = {executor.submit(generate_case, c, m, manifest, inputs, directory, bridge, client): (c["id"], m) for c, m in work}
        for count, future in enumerate(as_completed(futures), 1):
            result = future.result()
            failed += result["status"] != "completed"
            print(f"generate {count}/{len(work)} {result['model']} {result['case_id']} {result['status']}", flush=True)
    return failed


def collect(directory):
    manifest = read_json(directory / "manifest.json")
    records, failures = [], []
    for model in manifest["models"]:
        for case_id in manifest["case_ids"]:
            path = directory / "conversations" / model / (case_id + ".json")
            value = read_json(path) if path.exists() else {"status": "missing"}
            if value["status"] == "completed":
                records.extend(value["records"])
            else:
                failures.append({"model": model, "case_id": case_id, "stage": "generation", "error": value.get("error", value["status"])})
    return records, failures


def judge(args, directory, bridge):
    from scoring import build_paired_judge_messages, parse_paired_judgement
    from blind import pair, unblind_scores
    model = os.environ.get("XIAOKE_EVAL_JUDGE_MODEL", "").strip()
    if not model:
        raise ValueError("请配置 XIAOKE_EVAL_JUDGE_MODEL")
    protocol = os.environ.get("XIAOKE_EVAL_JUDGE_PROTOCOL", "openai")
    if protocol not in ("openai", "gemini"):
        raise ValueError("评委协议只能是 openai 或 gemini")
    base = os.environ.get("XIAOKE_EVAL_JUDGE_BASE_URL", "")
    key = os.environ.get("XIAOKE_EVAL_JUDGE_API_KEY", "")
    client = Client(base, key, protocol, directory / "judge-requests", timeout=args.timeout)
    sections = read_json(directory / "sections.json")
    inputs, manifest = read_json(directory / "inputs.json"), read_json(directory / "manifest.json")
    config = {"model": model, "protocol": protocol, "endpoint_sha256": digest(client.base),
              "temperature": 0, "top_p": 1, "seed": manifest["seed"], "rubric_sha256": digest((HERE / "rubric.json").read_bytes()),
              "scoring_sha256": digest((HERE / "scoring.py").read_bytes()), "mode": "paired_blind_absolute_scores",
              "blind_sha256": digest((HERE / "blind.py").read_bytes())}
    frozen(directory / "judge-config.json", config)
    records, failures = collect(directory)

    groups = {}
    for record in records:
        groups.setdefault((record["case_id"], record["turn_index"]), []).append(record)
    pairs = [rows for rows in groups.values() if len(rows) == 2]

    def one(rows):
        samples, originals, replacements = pair(rows, manifest["seed"])
        sample = rows[0]
        pair_path = directory / "blind-pairs" / f"{sample['case_id']}-{sample['turn_index']}.json"
        paths = {label: directory / "judgements" / rec["model"] / f"{rec['case_id']}-{rec['turn_index']}.json"
                 for label, rec in originals.items()}
        if all(path.exists() and read_json(path).get("status") == "completed" for path in paths.values()):
            return True
        try:
            messages = build_paired_judge_messages(inputs["persona"], sections, samples)
            requests, scores = [], None
            for attempt in range(3):
                raw, keys = judge_reply(client, bridge, model, messages, manifest["seed"])
                requests.extend(keys)
                try:
                    scores = parse_paired_judgement(raw, sections, {label: rec["reply"] for label, rec in samples.items()})
                    break
                except (ValueError, KeyError, TypeError):
                    # A different explicit repair request is audited and cached as its own call.
                    messages = copy.deepcopy(messages)
                    messages[0]["content"] += (f"\n格式校验重试 {attempt + 1}：上次输出未通过 JSON 结构或原文证据校验。"
                        "重新检查所有字段与章节，evidence 必须逐字取自对应 A 或 B 的 reply，不能取自历史、人设或另一条回复。"
                        "分数只能是 1 到 5 的整数或 null；无法适用就给 null 和空 evidence。")
            if scores is None:
                raise ModelError("invalid_judge_schema")
            save(pair_path, {"status": "completed", "labels": {label: rec["model"] for label, rec in originals.items()},
                             "request_hashes": requests, "scores": scores})
            for label, rec in originals.items():
                save(paths[label], {"status": "completed", "record": rec | {"judge_scores": unblind_scores(scores[label], replacements[label], rec["reply"]),
                                     "judge_model": model, "blind_label": label, "judge_request_hashes": requests}})
            return True
        except (ModelError, ValueError) as error:
            for label, rec in originals.items():
                save(paths[label], {"status": "failed", "model": rec["model"], "case_id": rec["case_id"],
                                    "turn_index": rec["turn_index"], "error": str(error) if isinstance(error, ModelError) else "judge_input_error"})
            return False

    failed = len(failures)
    with ThreadPoolExecutor(max_workers=args.workers) as executor:
        futures = [executor.submit(one, rows) for rows in pairs]
        for count, future in enumerate(as_completed(futures), 1):
            ok = future.result()
            failed += not ok
            print(f"judge pairs {count}/{len(pairs)} {'completed' if ok else 'failed'}", flush=True)
    return failed


def report(directory):
    from reporting import summarize, render_markdown, render_csv
    manifest = read_json(directory / "manifest.json")
    records, failures = collect(directory)
    combined = []
    for record in records:
        path = directory / "judgements" / record["model"] / f"{record['case_id']}-{record['turn_index']}.json"
        judgement = read_json(path) if path.exists() else {"status": "missing"}
        if judgement["status"] == "completed":
            combined.append(judgement["record"])
        else:
            combined.append(record)
            failures.append({"model": record["model"], "case_id": record["case_id"], "turn_index": record["turn_index"],
                             "stage": "judge", "error": judgement.get("error", judgement["status"])})
    inputs = read_json(directory / "inputs.json")
    cases = {case["id"]: case for case in inputs["cases"]}
    failed_records = []
    for failure in failures:
        if failure["stage"] != "generation":
            continue
        case = cases[failure["case_id"]]
        for index, turn in enumerate(case["turns"], len(case.get("warmup", [])) + 1):
            if turn.get("score", True):
                failed_records.append({"model": failure["model"], "case_id": case["id"], "category": case["category"],
                                       "category_label": case["category_label"], "turn_index": index, "status": "failed", "error_type": failure["error"]})
    summary = summarize(combined + failed_records, MODELS, sections=read_json(directory / "sections.json"))
    summary["run"] = {"mode": manifest["mode"], "full_corpus": manifest["full_corpus"],
                      "planned_scored_replies": manifest["planned_scored_replies"],
                      "generated_scored_replies": len(records), "judged_scored_replies": sum("judge_scores" in r for r in combined),
                      "failures": failures, "manifest_sha256": digest(manifest)}
    save(directory / "summary.json", summary)
    save(directory / "scored-records.json", combined)
    header = (f"# 小克人设一致性评测\n\n范围：{'完整语料' if manifest['full_corpus'] else '选取子集'}；"
              f"已生成 {len(records)}/{manifest['planned_scored_replies']} 个评分回复；"
              f"评委完成 {sum('judge_scores' in r for r in combined)}；失败/缺失 {len(failures)}。\n\n"
              "采用生产提示词与结果展示处理，工具和记忆结果使用虚构固定资料；没有实际控制设备或发送 QQ。\n\n"
              "评委按固定表对同一探针 A/B 盲评，温度 0；模型名已匿名化，标签按固定 seed 分配。两条回复分别绝对打分，允许同分。\n\n")
    (directory / "report.md").write_text(header + render_markdown(summary), encoding="utf-8")
    (directory / "summary.csv").write_text(render_csv(summary), encoding="utf-8")
    print(f"报告：{directory / 'report.md'}；真实回复 {len(records)}；失败/缺失 {len(failures)}")
    return len(failures)


def main(argv=None):
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["validate", "prepare", "generate", "judge", "report", "all"])
    parser.add_argument("--run", default="20261031")
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
    if not 1 <= args.workers <= 8 or args.limit_cases is not None and args.limit_cases < 1:
        parser.error("workers 必须在 1..8，limit-cases 必须为正数")
    if args.command == "validate":
        from corpus import validate
        stats = validate(read_json(HERE / "cases.json"))
        print(json.dumps(stats, ensure_ascii=False, indent=2))
        return 0
    directory = private_run(args.run)
    if args.command == "report":
        return int(bool(report(directory)))
    if args.command in ("prepare", "generate", "all"):
        manifest = prepare(args, directory)
        print(f"案例 {len(manifest['case_ids'])}；预计至少 {manifest['planned_target_calls_min']} 次被测调用、{manifest['planned_judge_pairs']} 次成对盲评", flush=True)
    bridge = bridge_for(args, directory)
    try:
        if args.command == "prepare":
            inputs = read_json(directory / "inputs.json")
            # Compile and exercise every scenario without contacting any model.
            for case in inputs["cases"]:
                bridge.call({"persona": inputs["persona"], "messages": [{"id": "u1", "role": "user", "text": case["turns"][0]["user"]}],
                             "context": normalized_context(case, case["turns"][0].get("context", {}), MODELS[0]),
                             "profile": manifest["profile"], "voiceReminder": inputs["voice_reminder"]})
            print("生产提示词编译与全部选定场景组装通过；未调用模型。")
            return 0
        failures = 0
        if args.command in ("generate", "all"):
            failures += generate(args, directory, bridge)
        if args.command in ("judge", "all"):
            failures += judge(args, directory, bridge)
        if args.command == "all":
            failures += report(directory)
        return int(bool(failures))
    finally:
        bridge.close()


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, ModelError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(2)
    except (OSError, subprocess.SubprocessError, KeyError, TypeError):
        print("评测失败：请检查本地配置、文件和私有运行日志；未将模型正文输出到终端。", file=sys.stderr)
        sys.exit(2)
