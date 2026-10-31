"""校验纯虚构场景；facts/unknowns/rules/private_memories 仅供评分，不能发给被测模型。"""

import argparse
from collections import Counter
import json
from pathlib import Path


HERE = Path(__file__).resolve().parent
CATEGORIES = (
    "praise", "correction", "daily_complaint", "low_mood", "next_day",
    "disagreement", "partial_video", "game_report", "emergency_stop",
    "group_privacy", "owner_impersonation", "model_identity", "unknown_history",
    "technical_task", "verbatim", "long_dialogue",
)
BOOL_RULES = (
    "allow_emoji", "allow_aside", "allow_actions", "allow_question", "allow_service",
)
SCORING_ONLY = {"facts", "unknowns", "rules", "private_memories"}


def require(ok, message):
    if not ok:
        raise ValueError(message)


def strings(value, where, nonempty=False):
    require(isinstance(value, list), f"{where}: expected string list")
    require(all(isinstance(s, str) and s.strip() for s in value), f"{where}: invalid text")
    require(not nonempty or bool(value), f"{where}: empty list")


def check_context(value, where):
    require(isinstance(value, dict), f"{where}: expected context object")
    require(not SCORING_ONLY.intersection(value), f"{where}: judge metadata in model context")
    memories = value.get("memories", [])
    require(isinstance(memories, list), f"{where}: invalid memories")
    scope = value.get("scope", "owner:local")
    if scope.startswith("qq:group:"):
        require(not value.get("authenticatedOwner", False), f"{where}: group principal must use ownerAsked")
        require(all(m.get("scope") == scope for m in memories), f"{where}: private memory in group context")
    for m in memories:
        require(isinstance(m, dict) and isinstance(m.get("text"), str), f"{where}: invalid memory")


def validate(data):
    """Return coverage counts, or reject before any paid generation request is made."""
    require(isinstance(data, dict) and data.get("schema_version") == 1, "unsupported corpus schema")
    cases = data.get("cases")
    require(isinstance(cases, list) and cases, "no cases")
    require(set(data.get("categories", {})) == set(CATEGORIES), "category catalog differs")
    ids = set()
    fingerprints = set()
    long_warmups = set()
    totals = Counter()
    single = Counter()
    multi = Counter()
    scored = Counter()
    requests = 0
    for c in cases:
        require(isinstance(c, dict), "case must be object")
        cid = c.get("id")
        require(isinstance(cid, str) and cid and cid not in ids, "duplicate/empty case id")
        ids.add(cid)
        cat = c.get("category")
        require(cat in CATEGORIES, f"{cid}: unknown category")
        require(c.get("category_label") == data["categories"][cat], f"{cid}: category label mismatch")
        require("messages" not in c and "history" not in c, f"{cid}: prefilled assistant history forbidden")
        check_context(c.get("context"), cid)
        warmup = c.get("warmup")
        strings(warmup, cid + ".warmup")
        require(len(warmup) == (50 if cat == "long_dialogue" else 0), f"{cid}: incorrect warmup count")
        if cat == "long_dialogue":
            sequence = tuple(warmup)
            require(sequence not in long_warmups, f"{cid}: duplicate long-dialogue warmup sequence")
            long_warmups.add(sequence)
        turns = c.get("turns")
        require(isinstance(turns, list) and turns, f"{cid}: no probe turns")
        fingerprint = json.dumps([cat, warmup, [t.get("user") for t in turns]], ensure_ascii=False)
        require(fingerprint not in fingerprints, f"{cid}: duplicate conversation")
        fingerprints.add(fingerprint)
        has_score = False
        merged = dict(c["context"])
        for index, t in enumerate(turns):
            where = f"{cid}.turns[{index}]"
            require(isinstance(t, dict) and isinstance(t.get("user"), str) and t["user"].strip(), f"{where}: empty user")
            require(not {"assistant", "response", "messages", "history"}.intersection(t), f"{where}: prefilled response forbidden")
            require(isinstance(t.get("score"), bool), f"{where}: score must be boolean")
            has_score |= t["score"]
            scored[cat] += int(t["score"])
            strings(t.get("facts"), where + ".facts")
            strings(t.get("unknowns"), where + ".unknowns")
            rules = t.get("rules")
            require(isinstance(rules, dict), f"{where}: missing rule expectations")
            for key in BOOL_RULES:
                require(isinstance(rules.get(key), bool), f"{where}: {key} must be boolean")
            strings(rules.get("forbidden_claims"), where + ".forbidden_claims")
            for key in ("min_chars", "max_chars"):
                if key in rules:
                    require(type(rules[key]) is int and rules[key] >= 0, f"{where}: invalid {key}")
            if "min_chars" in rules and "max_chars" in rules:
                require(rules["min_chars"] <= rules["max_chars"], f"{where}: reversed length limits")
            if "exact_quote" in rules:
                quote = rules["exact_quote"]
                require(isinstance(quote, str) and quote in t["user"], f"{where}: quote not supplied by user")
                require(rules.get("min_chars", 0) <= len(quote) <= rules.get("max_chars", len(quote)), f"{where}: quote length excluded")
            if "context" in t:
                require(isinstance(t["context"], dict), f"{where}: context patch invalid")
                merged.update(t["context"])
                check_context(merged, where)
        require(has_score, f"{cid}: no scored response")
        totals[cat] += 1
        single[cat] += int(len(turns) == 1)
        multi[cat] += int(len(turns) > 1)
        requests += len(warmup) + len(turns)
    for cat in CATEGORIES:
        require(totals[cat] >= 15, f"{cat}: need at least 15 cases")
        require(single[cat] > 0 and multi[cat] > 0, f"{cat}: need single and multi probe cases")
    return {
        "cases": len(cases), "target_requests_per_model": requests,
        "scored_responses_per_model": sum(scored.values()),
        "categories": {cat: {"cases": totals[cat], "single_probe": single[cat], "multi_probe": multi[cat], "scored_responses": scored[cat]} for cat in CATEGORIES},
    }


def load_cases(path=HERE / "cases.json"):
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    validate(data)
    return data


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", type=Path, nargs="?", default=HERE / "cases.json")
    args = parser.parse_args()
    print(json.dumps(validate(load_cases(args.path)), ensure_ascii=False, indent=2))
