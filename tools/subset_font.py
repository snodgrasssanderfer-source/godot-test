"""Builds the game's pixel font: a subset of Fusion Pixel Font (12px, zh_hans,
SIL OFL 1.1) containing only the characters used in pixel-town/.

Run after changing any in-game text:  python3 tools/subset_font.py
Needs: pip install fonttools. Downloads the full font (~50 MB zip) once into
~/.cache/pixel-town-fonts.
"""
import io
import os
import pathlib
import urllib.request
import zipfile

from fontTools import subset

VERSION = "2026.10.16"
ZIP_NAME = f"fusion-pixel-font-12px-proportional-ttf-v{VERSION}.zip"
URL = f"https://github.com/TakWolf/fusion-pixel-font/releases/download/{VERSION}/{ZIP_NAME}"
FONT_IN_ZIP = "fusion-pixel-12px-proportional-zh_hans.ttf"

ROOT = pathlib.Path(__file__).resolve().parent.parent
GAME = ROOT / "pixel-town"
OUT_DIR = GAME / "assets" / "fonts"
CACHE = pathlib.Path.home() / ".cache" / "pixel-town-fonts"
# Always include ASCII and common full-width punctuation, even if unused yet.
EXTRA = "".join(chr(c) for c in range(0x20, 0x7F)) + "，。！？…～·×：；、“”‘’（）《》—▼☀☾♪"


def main():
    CACHE.mkdir(parents=True, exist_ok=True)
    zip_path = CACHE / ZIP_NAME
    if not zip_path.exists():
        print("Downloading", URL)
        with urllib.request.urlopen(URL) as r:
            zip_path.write_bytes(r.read())
    with zipfile.ZipFile(zip_path) as z:
        font_bytes = z.read(FONT_IN_ZIP)
        license_text = z.read("OFL.txt")

    chars = set(EXTRA)
    for path in list(GAME.rglob("*.gd")) + list(GAME.rglob("*.tscn")) + [GAME / "project.godot"]:
        chars.update(path.read_text(encoding="utf-8"))
    chars = {c for c in chars if c.isprintable() or c == " "}

    options = subset.Options()
    options.layout_features = ["*"]
    options.name_IDs = ["*"]
    options.notdef_outline = True
    font = subset.load_font(io.BytesIO(font_bytes), options)
    subsetter = subset.Subsetter(options)
    subsetter.populate(text="".join(sorted(chars)))
    subsetter.subset(font)
    missing = sorted(c for c in chars if ord(c) not in font.getBestCmap())

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    out = OUT_DIR / "fusion-pixel-12px.ttf"
    font.save(out)
    (OUT_DIR / "OFL.txt").write_bytes(license_text)
    print(f"{out.relative_to(ROOT)}: {len(chars)} characters, {out.stat().st_size // 1024} KB")
    if missing:
        print("Not in the font (will not render):", "".join(missing))


if __name__ == "__main__":
    main()
