"""Read the private persona from a local path; character cards go through the app's own importer."""
import json
import os
from pathlib import Path
import re
import subprocess

HERE = Path(__file__).resolve().parent
FIELD_MARKER = re.compile(r"^【[^】\r\n]+】(system_prompt|post_history_instructions|mes_example)[ \t]*(?:\r?\n|$)", re.MULTILINE)
SEPARATOR = re.compile(r"^[ \t]*-{3,}[ \t]*$")


def _strip_separators(body):
    lines = body.splitlines(keepends=True)
    while lines and (not lines[0].strip() or SEPARATOR.match(lines[0].rstrip("\r\n"))):
        lines.pop(0)
    while lines and (not lines[-1].strip() or SEPARATOR.match(lines[-1].rstrip("\r\n"))):
        lines.pop()
    return "".join(lines)


def marked_card(text, name):
    """A document with 【…】system_prompt / post_history_instructions / mes_example headings becomes a card."""
    matches = list(FIELD_MARKER.finditer(text))
    if not matches:
        return None
    card = {"name": name}
    for index, match in enumerate(matches):
        end = matches[index + 1].start() if index + 1 < len(matches) else len(text)
        card[match.group(1)] = _strip_separators(text[match.end():end])
    return card


def import_card(card):
    result = subprocess.run(["node", "--experimental-strip-types", str(HERE / "import-card.mjs")],
                            input=json.dumps(card, ensure_ascii=False), capture_output=True,
                            text=True, encoding="utf-8", timeout=30)
    if result.returncode:
        raise ValueError("角色卡导入失败：" + result.stderr.strip()[-500:])
    return json.loads(result.stdout)


def load_persona(path):
    path = Path(path)
    text = path.read_text(encoding="utf-8-sig")
    if path.suffix.lower() == ".json" or text.lstrip().startswith("{"):
        value = json.loads(text)
        persona = value if isinstance(value.get("text"), str) else import_card(value)
    else:
        name = os.environ.get("XIAOKE_EVAL_PERSONA_NAME", "小克")
        card = marked_card(text, name)
        persona = import_card(card) if card else {"name": name, "text": text}
    if not persona.get("text", "").strip():
        raise ValueError(f"{path} 读出的人设 text 为空")
    return persona
