#!/usr/bin/env python3
"""小克回复延迟基准：编译指定版本的生产提示词和 GeminiClient，经真实中转跑固定虚构场景。"""
import argparse
import json
import os
from pathlib import Path
import statistics
import subprocess
import sys
import tempfile
import urllib.request

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "persona-eval"))
from runtime import PROJECT, SOURCE, Bridge, digest, save  # noqa: E402
from persona_input import load_persona  # noqa: E402

OUTPUT = PROJECT / "latency-output"
PRODUCTION = ["CompanionContext.kt", "CompanionPrompt.kt", "PersonaVoicePrompt.kt", "GeminiWire.kt", "GeminiClient.kt",
              "CompanionPolicy.kt", "chat/QqFaces.kt", "OneBotProtocol.kt", "social/OneBotSegments.kt", "iteration/ModuleContract.kt"]
# Pure extraction with a baseline-equivalence test (CompanionToolsTest); older refs take it from the worktree.
EXTRACTED = ["CompanionTools.kt"]
JARS = {"okhttp-4.12.0.jar": ("com/squareup/okhttp3/okhttp/4.12.0", "b1050081b14bb7a3a7e55a4d3ef01b5dcfabc453b4573a4fc019767191d5f4e0"),
        "okio-jvm-3.6.0.jar": ("com/squareup/okio/okio-jvm/3.6.0", "67543f0736fc422ae927ed0e504b98bc5e269fda0d3500579337cb713da28412")}


def jar(directory, name):
    path, sha = JARS[name]
    target = directory / name
    if not target.exists():
        directory.mkdir(parents=True, exist_ok=True)
        urllib.request.urlretrieve(f"https://repo.maven.apache.org/maven2/{path}/{name}", target)
    if digest(target.read_bytes()) != sha:
        raise ValueError(f"{target} 的 SHA-256 不符")
    return target


def sources(ref, directory):
    """Production files as of [ref]; the harness itself always comes from the worktree."""
    if ref == "worktree":
        return [SOURCE / f for f in PRODUCTION + EXTRACTED]
    root = Path(subprocess.check_output(["git", "-C", str(HERE), "rev-parse", "--show-toplevel"], text=True).strip())
    out = []
    for name in PRODUCTION + EXTRACTED:
        shown = subprocess.run(["git", "-C", str(root), "show", f"{ref}:{(SOURCE / name).relative_to(root).as_posix()}"], capture_output=True)
        if shown.returncode and name not in EXTRACTED:
            raise ValueError(f"{ref} 里没有 {name}：{shown.stderr.decode().strip()}")
        target = directory / ref / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(shown.stdout if shown.returncode == 0 else (SOURCE / name).read_bytes())
        out.append(target)
    return out


def percentile(values, q):
    values = sorted(values)
    return values[min(len(values) - 1, round(q * (len(values) - 1)))] if values else None


def summarize(records):
    rows = {}
    for r in records:
        if r.get("status") != "completed":
            continue
        group = rows.setdefault((r["scenario"], r["model"]), {"首字": [], "总时长": [], "首个请求→响应头": [], "本地组装": [], "探测": []})
        replies = [q for q in r["requests"] if q["purpose"] == "reply"]
        if "firstTextMs" in r["metrics"]:
            group["首字"].append(r["metrics"]["firstTextMs"])
        group["总时长"].append(r["metrics"]["totalMs"])
        if replies and "headersMs" in replies[0]:
            group["首个请求→响应头"].append(replies[0]["headersMs"])
        group["本地组装"].append(r["metrics"]["assemblyMs"])
        group["探测"].extend(q["elapsedMs"] for q in r["requests"] if q["purpose"] == "web_probe")
    return {f"{s} / {m}": {k: {"n": len(v), "p50": statistics.median(v), "p90": percentile(v, 0.9)} for k, v in g.items() if v}
            for (s, m), g in sorted(rows.items())}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", required=True)
    parser.add_argument("--ref", default="worktree", help="要测的生产代码版本（git 引用），默认当前工作区")
    parser.add_argument("--models", default="gemini-4-argon,gemini-3.8-flash")
    parser.add_argument("--cases", help="逗号分隔的案例 id，默认全部")
    parser.add_argument("--repeat", type=int, default=3)
    parser.add_argument("--thinking", default="low")
    parser.add_argument("--probe", action="store_true", help="每轮先发一次联网能力探测（旧版行为）")
    parser.add_argument("--fresh-connection", action="store_true", help="每次请求新建连接（旧版行为）")
    parser.add_argument("--gradle-home", default=os.environ.get("GRADLE_HOME"))
    args = parser.parse_args()
    for name in ("XIAOKE_EVAL_PERSONA_FILE", "XIAOKE_EVAL_BASE_URL", "XIAOKE_EVAL_API_KEY"):
        if not os.environ.get(name):
            raise SystemExit(f"缺少环境变量 {name}")
    directory = OUTPUT / args.run
    cases = json.loads((HERE / "cases.json").read_text(encoding="utf-8"))["cases"]
    if args.cases:
        cases = [c for c in cases if c["id"] in args.cases.split(",")]
    persona = load_persona(os.environ["XIAOKE_EVAL_PERSONA_FILE"])
    build = directory / "build"
    harness = [HERE / "LatencyBridge.kt", HERE / "BenchPlatform.kt", HERE.parent / "persona-eval/PersonaPromptBridge.kt"]
    with tempfile.TemporaryDirectory() as temporary:
        bridge = Bridge(args.gradle_home, build, sources(args.ref, Path(temporary)) + harness,
                        "io.deepseekharness.mobile.companion.eval.LatencyBridgeKt", [jar(build, n) for n in JARS])
    records = []
    try:
        for repeat in range(args.repeat):
            for case in cases:
                for model in args.models.split(","):
                    result = bridge.call({"case": case, "model": model, "persona": persona, "probe": args.probe,
                                          "freshConnection": args.fresh_connection,
                                          "profile": {"maxOutputTokens": 32768, "contextBudget": 65536, "thinking": args.thinking, "timeoutSeconds": 180}})
                    records.append(result | {"repeat": repeat})
                    save(directory / "records.json", records)
                    print(f"{repeat + 1}/{args.repeat} {case['id']} {model} {result.get('status')} {result.get('failure', '')}", flush=True)
    finally:
        bridge.close()
    summary = {"ref": args.ref, "probe": args.probe, "freshConnection": args.fresh_connection, "groups": summarize(records)}
    save(directory / "summary.json", summary)
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
