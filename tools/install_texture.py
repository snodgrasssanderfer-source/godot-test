#!/usr/bin/env python3
"""Puts material sets from the library (textures/) into the game (fps/).

  tools/install_texture.py red_brick Asphalt015 C14_PeelingClapboard ...
  tools/install_texture.py --list            what is installed, and its import settings

A set with a single image (a sign, a lightbox, a printed face: just <name>_Color
or <name>_Emission) becomes a plain StandardMaterial3D; an _Emission image also
glows, and so does a _Color image asked for as <name>+glow (a lit machine front).
<name>+alpha makes the material see-through where the image is: that is how
stains, cracks and painted lines are laid on (as flat panels a few millimetres
off the surface). A single decal out of a set of variants is asked for by its
own file name without the channel (M07a_RustStreaks, A39_OilDripSpots).
For every full set (a folder under textures/materials or
textures/generated holding <name>_Color, <name>_Normal and <name>_ORM) it

  * copies the three maps to fps/assets/textures/<name>/
  * writes their .import files so that Godot stores them VRAM-compressed with
    mipmaps (a headless import otherwise leaves them lossless and without
    mipmaps: several times the video memory, and shimmer in the distance);
    the normal map is flagged as one
  * writes fps/assets/materials/<name>.tres, an ORMMaterial3D (its ORM texture
    is occlusion / roughness / metal in R / G / B, the way the library packs it;
    normals are OpenGL-style, which is what Godot wants)

Afterwards run:  godot --headless --path fps --import
and commit the .import and .uid files it writes. Only install what the game
uses: the library stays outside fps/ on purpose.
"""
import os
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LIBRARY = [os.path.join(ROOT, "textures", "materials"), os.path.join(ROOT, "textures", "generated")]
TEX_OUT = os.path.join(ROOT, "fps", "assets", "textures")
MAT_OUT = os.path.join(ROOT, "fps", "assets", "materials")

IMPORT = """[remap]

importer="texture"
type="CompressedTexture2D"

[deps]

source_file="{res}"

[params]

compress/mode=2
compress/high_quality=false
compress/lossy_quality=0.7
compress/hdr_compression=1
compress/normal_map={normal}
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

MATERIAL = """[gd_resource type="ORMMaterial3D" load_steps=4 format=3]

[ext_resource type="Texture2D" path="{color}" id="1"]
[ext_resource type="Texture2D" path="{normal}" id="2"]
[ext_resource type="Texture2D" path="{orm}" id="3"]

[resource]
resource_name = "{name}"
albedo_texture = ExtResource("1")
orm_texture = ExtResource("3")
normal_enabled = true
normal_texture = ExtResource("2")
texture_filter = 5
{extra}"""


SINGLE = """[gd_resource type="StandardMaterial3D" load_steps=2 format=3]

[ext_resource type="Texture2D" path="{image}" id="1"]

[resource]
resource_name = "{name}"
albedo_texture = ExtResource("1")
roughness = 0.6
texture_filter = 5
{glow}"""

GLOW = """emission_enabled = true
emission_texture = ExtResource("1")
emission_energy_multiplier = 1.8
"""


ALPHA = """transparency = 1
"""


def find(name):
    for lib in LIBRARY:
        d = os.path.join(lib, name)
        if os.path.isdir(d):
            return d
    # one image of a set of variants (generated/M07_RustStreaks/M07a_RustStreaks_Color.png),
    # or one of the script-made decals (procedural/A39_OilDripSpots_Color.png)
    for lib in LIBRARY + [os.path.join(ROOT, "textures", "procedural")]:
        for d, _, files in os.walk(lib):
            if name + "_Color.png" in files:
                return d
    return None


def channel(folder, name, ch):
    for ext in (".jpg", ".png", ".jpeg"):
        p = os.path.join(folder, "%s_%s%s" % (name, ch, ext))
        if os.path.exists(p):
            return p
    return None


def install(name, force_glow=False, alpha=False):
    src = find(name)
    if src is None:
        print("%s: not in the library" % name)
        return False
    maps = {ch: channel(src, name, ch) for ch in ("Color", "Normal", "ORM")}
    missing = [ch for ch, p in maps.items() if p is None]
    out = os.path.join(TEX_OUT, name)
    if missing:
        single = channel(src, name, "Emission") or maps["Color"]
        if single is None or len(missing) < 2:
            print("%s: no %s map, and not a single-image set either" % (name, ", ".join(missing)))
            return False
        os.makedirs(out, exist_ok=True)
        os.makedirs(MAT_OUT, exist_ok=True)
        dst = os.path.join(out, os.path.basename(single))
        shutil.copyfile(single, dst)
        res_path = "res://assets/textures/%s/%s" % (name, os.path.basename(single))
        if not os.path.exists(dst + ".import"):
            with open(dst + ".import", "w", newline="\n") as fh:
                fh.write(IMPORT.format(res=res_path, normal=0))
        with open(os.path.join(MAT_OUT, name + ".tres"), "w", newline="\n") as fh:
            glows = force_glow or "_Emission" in single
            fh.write(SINGLE.format(name=name, image=res_path, glow=(GLOW if glows else "") + (ALPHA if alpha else "")))
        print("%s: installed as a single-image material%s%s" % (name, " (glowing)" if glows else "", " (see-through)" if alpha else ""))
        return True
    os.makedirs(out, exist_ok=True)
    os.makedirs(MAT_OUT, exist_ok=True)
    res = {}
    for ch, p in maps.items():
        dst = os.path.join(out, os.path.basename(p))
        shutil.copyfile(p, dst)
        res[ch] = "res://assets/textures/%s/%s" % (name, os.path.basename(p))
        imp = dst + ".import"
        if not os.path.exists(imp):          # keep what Godot has already filled in
            with open(imp, "w", newline="\n") as fh:
                fh.write(IMPORT.format(res=res[ch], normal=1 if ch == "Normal" else 0))
    with open(os.path.join(MAT_OUT, name + ".tres"), "w", newline="\n") as fh:
        fh.write(MATERIAL.format(name=name, color=res["Color"], normal=res["Normal"], orm=res["ORM"], extra=ALPHA if alpha else ""))
    print("%s: installed from %s" % (name, os.path.relpath(src, ROOT)))
    return True


def listing():
    if not os.path.isdir(TEX_OUT):
        return
    for name in sorted(os.listdir(TEX_OUT)):
        d = os.path.join(TEX_OUT, name)
        if not os.path.isdir(d) or name == "proc":
            continue
        notes = []
        for f in sorted(os.listdir(d)):
            if not f.endswith(".import"):
                continue
            text = open(os.path.join(d, f)).read()
            ok = "compress/mode=2" in text and "mipmaps/generate=true" in text
            imported = "path." in text or 'path="' in text
            notes.append("%s %s%s" % (f.split("_")[-1].split(".")[0], "ok" if ok else "NOT compressed/mipmapped", "" if imported else " (not imported yet)"))
        print("%-28s %s" % (name, "; ".join(notes)))


def main():
    args = sys.argv[1:]
    if not args or args[0] in ("-h", "--help"):
        print(__doc__)
        return 0
    if args[0] == "--list":
        listing()
        return 0
    ok = all([install(a.split("+")[0], "+glow" in a, "+alpha" in a) for a in args])
    print("now run: godot --headless --path fps --import")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
