import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from persona_input import import_hashes, load_persona, marked_card


class PersonaInputTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def write(self, name, text):
        path = self.root / name
        path.write_bytes(text.encode("utf-8"))
        return path

    def test_direct_persona_preserves_all_fields(self):
        value = {"name": "测试角色", "text": "\n  虚构正文。\r\n", "extras": {"postHistory": " 保留空白 \n"}, "version": 7}
        path = self.write("persona.json", json.dumps(value, ensure_ascii=False))
        self.assertEqual(load_persona(path), value)

    def test_plain_text_preserves_crlf_and_whitespace(self):
        text = "\r\n  【虚构标题】\r\n随便聊两句。\r\n"
        path = self.write("persona.txt", text)
        with patch.dict(os.environ, {"XIAOKE_EVAL_PERSONA_NAME": "虚构名字"}):
            self.assertEqual(load_persona(path), {"name": "虚构名字", "text": text})
        self.assertEqual(path.read_bytes(), text.encode("utf-8"))

    def test_marked_fields_preserve_bodies_and_use_production_importer(self):
        system = "\r\n  【虚构语气】\r\n你好，测试。\r\n---\r\n内部横线保留。\r\n"
        post = " 保留尾部指令空格。 \r\n"
        example = "\r\n用户：示例。\r\n角色：测试。\r\n"
        document = ("字段导入说明，不是人设。\r\n"
                    "【第一部分】system_prompt\r\n---\r\n" + system +
                    "【第二部分】post_history_instructions\r\n-----\r\n" + post +
                    "【第三部分】mes_example\r\n---\r\n" + example)
        fields = marked_card(document, "虚构角色")
        self.assertEqual(fields, {"name": "虚构角色", "system_prompt": system,
                                  "post_history_instructions": post, "mes_example": example})
        path = self.write("persona.txt", document)
        with patch.dict(os.environ, {"XIAOKE_EVAL_PERSONA_NAME": "虚构角色"}):
            persona = load_persona(path)
        self.assertEqual(persona["text"], "系统提示\n" + system + "\n\n对话示例\n" + example)
        self.assertEqual(persona["extras"]["postHistory"], post)
        self.assertEqual(path.read_bytes(), document.encode("utf-8"))
        self.assertNotIn("字段导入说明", persona["text"])

    def test_full_character_card_uses_production_extras(self):
        card = {"spec": "chara_card_v2", "data": {
            "name": "虚构角色", "description": "测试描述。", "post_history_instructions": "测试尾注。",
            "extensions": {"depth_prompt": {"prompt": "测试深度。", "depth": 2}},
        }}
        path = self.write("card.json", json.dumps(card, ensure_ascii=False))
        persona = load_persona(path)
        self.assertEqual(persona["text"], "角色描述\n测试描述。")
        self.assertEqual(persona["extras"]["postHistory"], "测试尾注。")
        self.assertEqual(persona["extras"]["depthPrompt"], {"prompt": "测试深度。", "depth": 2, "role": "system"})

    def test_incomplete_or_duplicate_field_wrappers_fail(self):
        for text in ("【一】system_prompt\n正文\n", "【一】system_prompt\nA\n【二】system_prompt\nB\n"):
            with self.assertRaisesRegex(ValueError, "须且仅须"):
                marked_card(text, "测试")

    def test_outer_separator_removal_keeps_blank_lines(self):
        text = "【一】system_prompt\n\n---\n甲\n---\n\n【二】post_history_instructions\n乙\n【三】mes_example\n丙"
        self.assertEqual(marked_card(text, "测试")["system_prompt"], "\n甲\n\n")

    def test_tracked_file_and_symlink_are_rejected(self):
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        path = self.write("tracked.txt", "虚构测试")
        subprocess.run(["git", "-C", str(self.root), "add", "tracked.txt"], check=True)
        link = self.root / "link.txt"
        link.symlink_to(path)
        for source in (path, link):
            with self.assertRaisesRegex(ValueError, "Git 跟踪"):
                load_persona(source)

    def test_invalid_card_errors_do_not_echo_input(self):
        path = self.write("bad.json", '{"description":"fictional-private-canary"}')
        with self.assertRaises(ValueError) as caught:
            load_persona(path)
        self.assertNotIn("fictional-private-canary", str(caught.exception))

    def test_import_provenance_hashes_are_complete(self):
        hashes = import_hashes()
        self.assertEqual(set(hashes), {"characterCard.ts", "import-card.mjs", "persona_input.py"})
        self.assertTrue(all(len(value) == 64 for value in hashes.values()))


if __name__ == "__main__":
    unittest.main()
