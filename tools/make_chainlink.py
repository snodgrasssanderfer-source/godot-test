#!/usr/bin/env python3
"""Draws the chain-link fence texture (one tile = 4 x 4 diamonds, see-through
between the wires) and writes its material. The library has no fence with holes.

  tools/make_chainlink.py        then: godot --headless --path fps --import
"""
import math
import os

from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "fps", "assets", "textures", "ChainLink")
SIZE = 512
CELLS = 4

MATERIAL = """[gd_resource type="StandardMaterial3D" load_steps=2 format=3]

[ext_resource type="Texture2D" path="res://assets/textures/ChainLink/ChainLink_Color.png" id="1"]

[resource]
resource_name = "ChainLink"
transparency = 2
alpha_scissor_threshold = 0.45
cull_mode = 2
albedo_texture = ExtResource("1")
metallic = 0.7
roughness = 0.45
texture_filter = 5
"""

IMPORT = """[remap]

importer="texture"
type="CompressedTexture2D"

[deps]

source_file="res://assets/textures/ChainLink/ChainLink_Color.png"

[params]

compress/mode=2
compress/high_quality=false
compress/lossy_quality=0.7
compress/hdr_compression=1
compress/normal_map=0
compress/channel_pack=0
mipmaps/generate=true
mipmaps/limit=-1
roughness/mode=0
roughness/src_normal=""
process/fix_alpha_border=true
process/premult_alpha=false
process/normal_map_invert_y=false
process/hdr_as_srgb=false
process/hdr_clamp_exposure=false
process/size_limit=0
detect_3d/compress_to=0
"""


def main():
    os.makedirs(OUT, exist_ok=True)
    big = SIZE * 2                                    # draw at twice the size, then shrink: smooth wires
    alpha = Image.new("L", (big, big), 0)
    shade = Image.new("L", (big, big), 0)
    da = ImageDraw.Draw(alpha)
    ds = ImageDraw.Draw(shade)
    step = big // CELLS
    wire = big // 46
    for k in range(-CELLS, CELLS * 2 + 1):
        # one family of wires leans right, the other left; the second is drawn
        # brighter so the weave reads as two layers
        da.line([(k * step, 0), (k * step + big, big)], fill=255, width=wire)
        ds.line([(k * step, 0), (k * step + big, big)], fill=150, width=wire)
        da.line([(k * step + big, 0), (k * step, big)], fill=255, width=wire)
        ds.line([(k * step + big, 0), (k * step, big)], fill=215, width=wire)
    alpha = alpha.resize((SIZE, SIZE), Image.LANCZOS)
    shade = shade.resize((SIZE, SIZE), Image.LANCZOS).filter(ImageFilter.GaussianBlur(0.6))
    grey = Image.new("RGB", (SIZE, SIZE))
    px = grey.load()
    sh = shade.load()
    for y in range(SIZE):
        for x in range(SIZE):
            v = sh[x, y]
            rust = 0.5 + 0.5 * math.sin(x * 0.045 + math.sin(y * 0.03) * 2.0) * math.sin(y * 0.021 + 1.3)
            px[x, y] = (int(v * (0.62 + 0.10 * rust)), int(v * (0.63 - 0.05 * rust)), int(v * (0.64 - 0.14 * rust)))
    grey.putalpha(alpha)
    path = os.path.join(OUT, "ChainLink_Color.png")
    grey.save(path)
    if not os.path.exists(path + ".import"):
        with open(path + ".import", "w", newline="\n") as fh:
            fh.write(IMPORT)
    with open(os.path.join(ROOT, "fps", "assets", "materials", "ChainLink.tres"), "w", newline="\n") as fh:
        fh.write(MATERIAL)
    print("wrote", os.path.relpath(path, ROOT))


if __name__ == "__main__":
    main()
