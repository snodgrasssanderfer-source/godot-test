"""Deterministic A/B assignment; model identities never enter the judge sample."""
import copy
import re
from runtime import digest

MODELS = ("gemini-4-argon", "gemini-3.8-flash")
ALIASES = {
    MODELS[0]: r"(?:gemini[\s_-]*4(?:\.0)?[\s_-]*)?argon",
    MODELS[1]: r"(?:gemini[\s_-]*3\.8[\s_-]*)?flash",
}


def transform(value, replacements):
    if isinstance(value, str):
        for old, new in sorted(replacements.items(), key=lambda item: -len(item[0])):
            value = re.sub(re.escape(old), lambda _: new, value, flags=re.IGNORECASE)
        return value
    if isinstance(value, list):
        return [transform(v, replacements) for v in value]
    if isinstance(value, dict):
        return {k: transform(v, replacements) for k, v in value.items()}
    return value


def anonymize(record):
    model = record["model"]
    other = next(m for m in MODELS if m != model)
    replacement = {model: "[当前被测模型]", other: "[另一被测模型]"}
    # Short names can occur in a natural answer even when the host supplies the full ID.
    replacement["argon"] = replacement[MODELS[0]]
    replacement["flash"] = replacement[MODELS[1]]
    allowed = ("case_id", "category", "category_label", "turn_index", "user", "reply", "context", "facts", "unknowns", "rules")
    sample = {key: copy.deepcopy(record[key]) for key in allowed if key in record}
    sample["history"] = [{"role": item["role"], "text": item["text"]} for item in record.get("history", [])]
    def mask(value):
        if isinstance(value, str):
            for identity, pattern in ALIASES.items():
                value = re.sub(pattern, lambda _: replacement[identity], value, flags=re.IGNORECASE)
            return value
        if isinstance(value, list):
            return [mask(v) for v in value]
        if isinstance(value, dict):
            return {k: mask(v) for k, v in value.items()}
        return value
    return mask(sample), {"[当前被测模型]": model, "[另一被测模型]": other}


def unblind_scores(scores, mapping, original_reply):
    result = transform(scores, mapping)
    for key, score in scores.items():
        evidence = score.get("evidence", "")
        pattern = re.escape(evidence)
        for placeholder, identity in mapping.items():
            pattern = pattern.replace(re.escape(placeholder), "(?:" + ALIASES[identity] + ")")
        match = re.search(pattern, original_reply, re.IGNORECASE) if evidence else None
        if match:
            result[key]["evidence"] = match.group(0)
        else:
            result[key]["evidence"] = evidence
            result[key]["evidence_is_blinded"] = bool(evidence)
    return result


def pair(records, seed):
    if len(records) != 2 or {r["model"] for r in records} != set(MODELS):
        raise ValueError("盲评需要两款被测模型的配对回复")
    if len({(r["case_id"], r["turn_index"]) for r in records}) != 1:
        raise ValueError("盲评双方必须是同一案例的同一探针")
    ordered = sorted(records, key=lambda r: r["model"])
    key = [seed, ordered[0]["case_id"], ordered[0]["turn_index"]]
    if int(digest(key)[:8], 16) % 2:
        ordered.reverse()
    samples, originals, replacements = {}, {}, {}
    for label, record in zip(("A", "B"), ordered):
        samples[label], replacements[label] = anonymize(record)
        originals[label] = record
    return samples, originals, replacements
