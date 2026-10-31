"""Read private persona files; character cards use the app's existing importer."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

HERE = Path(__file__).resolve().parent
CHARACTER_CARD = HERE.parents[4] / "src/companion/characterCard.ts"
FIELD_MARKER = re.compile(r"^【[^】\r\n]+】(system_prompt|post_history_instructions|mes_example)[ \t]*(?:\r?\n|$)", re.MULTILINE)
SEPARATOR = re.compile(r"^[ \t]*-{3,}[ \t]*(?:\r?\n)?$")
FIELDS = {"system_prompt", "post_history_instructions", "mes_example"}


def import_hashes():
    """These hashes identify the actual importer, without exposing the private input."""
    return {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
            for path in (CHARACTER_CARD, HERE / "import-card.mjs", Path(__file__))}


def _without_outer_separators(body):
    lines = body.splitlines(keepends=True)
    nonempty = [i for i, line in enumerate(lines) if line.strip()]
    remove = set()
    for indices in (nonempty, list(reversed(nonempty))):
        for i in indices:
            if not SEPARATOR.fullmatch(lines[i]):
                break
            remove.add(i)
    return "".join(line for i, line in enumerate(lines) if i not in remove)


def marked_card(text, name):
    """Map explicit standard field wrappers, preserving every body character."""
    matches = list(FIELD_MARKER.finditer(text))
    if not matches:
        return None
    if len(matches) != len(FIELDS) or {m.group(1) for m in matches} != FIELDS:
        raise ValueError("人设字段文档须且仅须各含一个 system_prompt、post_history_instructions、mes_example 标记")
    card = {"name": name}
    for index, match in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        card[match.group(1)] = _without_outer_separators(text[match.end():end])
    return card


def _import_card(card):
    try:
        result = subprocess.run(
            ["node", "--experimental-strip-types", str(HERE / "import-card.mjs")],
            input=json.dumps(card, ensure_ascii=False), capture_output=True,
            text=True, encoding="utf-8", timeout=30, check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        raise ValueError("角色卡导入需要可运行的 Node.js 24") from None
    if result.returncode != 0:
        raise ValueError("生产角色卡导入失败；请检查角色卡结构及 Node.js 24")
    try:
        return json.loads(result.stdout)
    except ValueError:
        raise ValueError("生产角色卡导入返回了无效结果") from None


def load_persona(path):
    path = Path(path)
    if not path.is_absolute() or not path.is_file():
        raise ValueError("XIAOKE_EVAL_PERSONA_FILE 必须是可读取的绝对文件路径")
    # Resolve the source too: a symlink to a tracked file is still a tracked source.
    source = path.resolve()
    tracked = subprocess.run(
        ["git", "-C", str(source.parent), "ls-files", "--error-unmatch", "--", source.name],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    if tracked.returncode == 0:
        raise ValueError("人设文件已被 Git 跟踪，拒绝读取")
    raw = path.read_bytes()
    if len(raw) > 8 * 1024 * 1024:
        raise ValueError("角色文件不能超过 8 MB")
    try:
        text = raw.decode("utf-8-sig")
    except UnicodeDecodeError:
        raise ValueError("人设文件必须是 UTF-8 JSON 或文本") from None
    if path.suffix.lower() == ".json" or text.lstrip().startswith("{"):
        try:
            value = json.loads(text)
        except ValueError:
            raise ValueError("人设 JSON 无效") from None
        if not isinstance(value, dict):
            raise ValueError("人设 JSON 必须是对象")
        persona = value if isinstance(value.get("text"), str) else _import_card(value)
    else:
        name = os.environ.get("XIAOKE_EVAL_PERSONA_NAME", "小克")
        card = marked_card(text, name)
        persona = _import_card(card) if card is not None else {"name": name, "text": text}
    if not isinstance(persona, dict) or not isinstance(persona.get("text"), str) or not persona["text"].strip():
        raise ValueError("人设必须有非空 text 字段")
    name = persona.get("name")
    if not isinstance(name, str) or not name.strip() or len(name) > 40:
        raise ValueError("人设 name 必须为 1–40 字的字符串")
    if len(persona["text"]) > 64000:
        raise ValueError("人设正文不能超过 64000 字")
    if "extras" in persona and not isinstance(persona["extras"], dict):
        raise ValueError("人设 extras 必须是对象")
    return persona
