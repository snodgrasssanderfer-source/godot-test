"""Checks on cases.json: facts/unknowns/rules/private_memories are for the judge and never reach the model."""
from collections import Counter
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCORING_ONLY = {"facts", "unknowns", "rules", "private_memories"}


def require(ok, message):
    if not ok:
        raise ValueError(message)


def check_context(context, where):
    require(isinstance(context, dict), f"{where}: context 必须是对象")
    require(not SCORING_ONLY & context.keys(), f"{where}: 评分资料混进了模型上下文")
    scope = context.get("scope", "owner:local")
    if scope.startswith("qq:group:"):
        require(all(m.get("scope") == scope for m in context.get("memories", [])), f"{where}: 群场景里混入了主人私聊记忆")


def validate(data):
    cases = data["cases"]
    ids = [c["id"] for c in cases]
    require(len(ids) == len(set(ids)), "案例 id 重复")
    counts, scored = Counter(), Counter()
    for c in cases:
        cid, category = c["id"], c["category"]
        require(category in data["categories"], f"{cid}: 未知类别 {category}")
        check_context(c["context"], cid)
        require(all(isinstance(u, str) for u in c["warmup"]), f"{cid}: warmup 只能是用户消息，不能预写回复")
        merged = dict(c["context"])
        for index, turn in enumerate(c["turns"]):
            where = f"{cid}.turns[{index}]"
            require(isinstance(turn["user"], str) and turn["user"].strip(), f"{where}: 空消息")
            require(not {"assistant", "response"} & turn.keys(), f"{where}: 不能预写回复")
            quote = turn["rules"].get("exact_quote")
            require(quote is None or quote in turn["user"], f"{where}: exact_quote 不在用户消息里")
            if "context" in turn:
                merged.update(turn["context"])
                check_context(merged, where)
            scored[category] += turn["score"]
        require(any(t["score"] for t in c["turns"]), f"{cid}: 没有评分轮")
        counts[category] += 1
    return {"cases": len(cases), "categories": {k: {"cases": counts[k], "scored_turns": scored[k]} for k in data["categories"]}}


def load_cases(path=HERE / "cases.json"):
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    validate(data)
    return data
