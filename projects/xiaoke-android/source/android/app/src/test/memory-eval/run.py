#!/usr/bin/env python3
"""Compile and evaluate the production Kotlin retrieval code, optionally at a git ref."""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request

HERE = Path(__file__).resolve().parent
REPO = Path(subprocess.check_output(["git", "-C", str(HERE), "rev-parse", "--show-toplevel"], text=True).strip())
SOURCE = HERE.parents[1] / "main/java/io/deepseekharness/mobile/companion"
FILES = [
    "MemoryRanking.kt", "memory/MemoryView.kt", "memory/TimeAnchor.kt",
    "life/LifePolicy.kt", "CompanionPolicy.kt", "iteration/ModuleContract.kt",
    "OneBotProtocol.kt", "social/OneBotSegments.kt", "chat/QqFaces.kt",
]
JSON_SHA256 = "3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed"


def download_json(cache):
    target = cache / "json-20240303.jar"
    if not target.exists():
        urllib.request.urlretrieve("https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar", target)
    if hashlib.sha256(target.read_bytes()).hexdigest() != JSON_SHA256:
        raise ValueError(f"org.json checksum mismatch: {target}")
    return target


def validate(data, scores):
    records = {m["id"]: m for m in data["memories"]}
    messages = {m["id"]: m for m in data["messages"]}
    queries = {q["id"]: q for q in data["queries"]}
    assert len(records) == len(data["memories"]), "duplicate memory id"
    assert len(messages) == len(data["messages"]), "duplicate message id"
    assert len(queries) == len(data["queries"]), "duplicate query id"
    for memory in records.values():
        assert memory["validUntil"] == 0 or memory["validUntil"] >= memory["validFrom"], memory["id"]
        for ref in memory.get("sourceRefs", []):
            if ref["kind"] == "message":
                assert ref["id"] in messages, (memory["id"], ref)
                assert ref["scope"] == messages[ref["id"]]["scope"], (memory["id"], ref)
        for target in memory.get("links", []):
            assert target in records, (memory["id"], target)
    for query in queries.values():
        assert query["split"] in ("dev", "test"), query["id"]
        expected = set(query["expectedIds"])
        assert not expected.intersection(query.get("expiredIds", [])), query["id"]
        assert not expected.intersection(query.get("forbiddenIds", [])), query["id"]
        for record_id in expected | set(query.get("expiredIds", [])) | set(query.get("forbiddenIds", [])):
            assert record_id in records, (query["id"], record_id)
        for record_id in expected:
            assert records[record_id]["createdAt"] <= query["asOf"], (query["id"], "future gold", record_id)
        if query.get("paraphraseOf"):
            original = queries[query["paraphraseOf"]]
            assert set(original["expectedIds"]) == expected, (query["id"], "paraphrase gold differs")
            assert original["split"] == query["split"], (query["id"], "pair split differs")
    for record_id, gold in data["memoryGold"].items():
        assert record_id in records, record_id
        assert gold["holder"] in ("owner", "her", "member", "other", "unknown"), record_id
        assert set(gold["sourceIds"]) <= messages.keys(), record_id
    if scores:
        payload = {"memories": [[m["id"], m["text"]] for m in data["memories"]],
                   "queries": [[q["id"], q["text"]] for q in data["queries"]]}
        digest = hashlib.sha256(json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode()).hexdigest()
        assert scores["corpusSha256"] == digest, "semantic cache does not match corpus text"
        assert set(scores["scores"]) == queries.keys(), "semantic query coverage differs"
        for query_id, similarities in scores["scores"].items():
            assert set(similarities) == records.keys(), (query_id, "semantic memory coverage differs")
            assert all(-1.001 <= float(v) <= 1.001 for v in similarities.values()), query_id


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ref", help="Compile production sources from this git ref instead of the worktree")
    parser.add_argument("--data", type=Path, default=HERE.parent / "resources/memory-eval/zh_companion_memory.json")
    parser.add_argument("--scores", type=Path, default=HERE.parent / "resources/memory-eval/bge_scores.json")
    parser.add_argument("--mode", choices=("lexical", "semantic", "both"), default="both")
    parser.add_argument("--split", choices=("dev", "test", "all"), default="all")
    parser.add_argument("--retrieval", choices=("tool", "automatic"), default="tool")
    parser.add_argument("--details", action="store_true", help="Include retrieved, missed and extra IDs for each query")
    parser.add_argument("--benchmark", action="store_true", help="Measure the same Kotlin ranking calls at 100, 1000 and 5000 memories")
    parser.add_argument("--gradle-home", type=Path, default=os.environ.get("GRADLE_HOME"), help="Gradle 8.11.1 distribution (contains Kotlin compiler)")
    parser.add_argument("--cache", type=Path, default=Path(os.environ.get("XDG_CACHE_HOME", Path.home() / ".cache")) / "xiaoke-memory-eval")
    args = parser.parse_args()
    data = json.loads(args.data.read_text())
    scores = json.loads(args.scores.read_text()) if args.mode != "lexical" else None
    validate(data, scores)
    gradle = args.gradle_home
    if gradle is None:
        executable = shutil.which("gradle")
        if executable:
            gradle = Path(executable).resolve().parents[1]
    compilers = sorted((gradle / "lib").glob("kotlin-compiler-embeddable-2.*.jar")) if gradle else []
    if not compilers:
        parser.error("Pass --gradle-home /path/to/a Gradle 8.x distribution (it bundles the Kotlin compiler)")
    args.cache.mkdir(parents=True, exist_ok=True)
    json_jar = download_json(args.cache)
    stdlib = sorted((gradle / "lib").glob("kotlin-stdlib-2.*.jar"))[-1]
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if os.environ.get("JAVA_HOME") else "java"
    engine_path = SOURCE / "CompanionEngine.kt"
    engine = subprocess.check_output(["git", "-C", str(REPO), "show", f"{args.ref}:{engine_path.relative_to(REPO)}"], text=True) if args.ref else engine_path.read_text()
    recall = engine[engine.index("private fun recallMemories("):engine.index("private fun searchMessages(")]
    call = r"MemoryRanking\.(rank|relevant|scored)\((?:records|list), query, (\d+), semantic(?:, relative = ([0-9.]+))?\)"
    match = re.search((r"else\s+" if args.retrieval == "automatic" else r"if \(forTool\) ") + call, recall)
    if not match:
        raise ValueError("Unrecognized Engine recall selection; update runner to call the new production entry point")
    selection, limit, cut = match.groups()
    with tempfile.TemporaryDirectory(prefix="memory-eval-") as temporary:
        temporary = Path(temporary)
        sources = []
        for relative in FILES:
            path = SOURCE / relative
            target = temporary / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            content = subprocess.check_output(["git", "-C", str(REPO), "show", f"{args.ref}:{path.relative_to(REPO)}"]) if args.ref else path.read_bytes()
            target.write_bytes(content)
            sources.append(target)
        jar = temporary / "eval.jar"
        classpath = os.pathsep.join(map(str, (json_jar, stdlib)))
        compile_command = [java, "-cp", str(gradle / "lib/*"), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                           "-no-stdlib", "-no-reflect", "-jvm-target", "21", "-classpath", classpath,
                           "-d", str(jar), *map(str, sources), str(HERE / "MemoryEvaluation.kt")]
        subprocess.run(compile_command, check=True)
        command = [java, "-Xms128m", "-Xmx1g", "-Duser.timezone=" + data.get("timezone", "Asia/Shanghai"), "-cp", str(jar) + os.pathsep + classpath,
                   "io.deepseekharness.mobile.companion.eval.MemoryEvaluationKt", str(args.data), str(args.scores),
                   args.mode, args.split, str(args.details).lower(), str(args.benchmark).lower(), args.ref or "worktree", selection, limit, args.retrieval, cut or "0.75"]
        subprocess.run(command, check=True)


if __name__ == "__main__":
    main()
