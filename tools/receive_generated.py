#!/usr/bin/env python3
"""Takes the accepted images of a generated-texture delivery into the library.

  tools/receive_generated.py <textures_incoming dir>            everything marked 合格 or 我来修
  tools/receive_generated.py <dir> --only D20,K06               just these ids
  tools/receive_generated.py <dir> --verdicts <csv>             another verdict table

The delivery is colour only (1024 x 1024 PNG, one list entry per image). The
verdict table (docs/texture_check/xiaoyu_gpt_verdicts.csv) says what each image
is and whether it passed. What is written, under textures/generated/<set>/:

  tiles, trim strips   <set>_Color.jpg, _Normal.jpg (OpenGL), _ORM.jpg, the last two
                       worked out from the colour (see derive())
  decals               <set>_Color.png with its alpha; a sheet of four variants is
                       cut into <id>a.. <id>d, 512 x 512 each (a Godot Decal takes
                       a whole texture, not a region of one)
  decal sheets         (贴花图集) several decals on one image, in a loose grid
                       (two by two unless GRID says otherwise): cut apart into
                       <id>a, <id>b, ...
  masks                <set>_Mask.png, greyscale; four-up sheets cut the same way
  emission             <set>_Emission.jpg
  faces, print sheets,
  reference sheets     <set>_Color.jpg as delivered

and textures/generated/sources.csv, one line per set. Everything here was made
by an image generator: that has to be declared when the game is published.

Three images are repaired on the way in (the audit's "我来修"): the two trim
strips are mirrored so they tile left to right, and the input glyphs are
turned into clean white-on-transparent icons.

Needs Pillow and numpy.
"""
import argparse
import csv
import glob
import os
import sys
import time

import numpy as np
from PIL import Image, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "textures", "generated")
VERDICTS = os.path.join(ROOT, "docs", "texture_check", "xiaoyu_gpt_verdicts.csv")
TOOL = "gpt-image-2.6-sunburst"
QUALITY = {"Color": 90, "Normal": 92, "ORM": 86, "Emission": 90}

# How each tile's maps are derived: bump strength (how much of the brightness
# is taken for relief: low for printed patterns, which are flat), base
# roughness, metalness. Anything not listed uses DEFAULT.
DEFAULT = (1.0, 0.82, 0.0)
SURFACE = {
    "C08": (0.8, 0.25, 0.0),    # glass block: smooth
    "C14": (1.6, 0.85, 0.0),    # peeling clapboard: real relief
    "C20": (1.2, 0.88, 0.0),
    "C62": (0.8, 0.45, 0.9),    # aluminium window frame
    "C68": (1.3, 0.55, 0.3),    # aluminium blinds under dust
    "D01": (0.25, 0.86, 0.0), "D02": (0.25, 0.86, 0.0), "D03": (0.25, 0.86, 0.0),
    "D04": (0.25, 0.84, 0.0), "D05": (0.25, 0.86, 0.0),            # wallpaper: the print is not relief
    "D10": (0.6, 0.8, 0.0),
    "D15": (1.5, 0.92, 0.0),    # popcorn ceiling
    "D16": (1.4, 0.7, 0.0),     # pressed tin under paint
    "D20": (0.3, 0.55, 0.0), "D21": (0.4, 0.6, 0.0), "D22": (0.3, 0.55, 0.0), "D24": (0.4, 0.6, 0.0),   # vinyl floors
    "D43": (1.2, 0.5, 0.0),     # varnished wood
    "D55": (1.3, 0.5, 0.3),
    "F34": (1.2, 0.95, 0.0), "F49": (0.9, 0.95, 0.0),              # felt
    "K04": (0.5, 0.5, 0.0),     # ABS plastic
    "K06": (0.5, 0.35, 1.0),    # brushed stainless steel
    "L05": (0.9, 0.6, 0.0),
    # third round
    "C19": (1.0, 0.9, 0.0), "C48": (0.6, 0.9, 0.0), "D06": (0.25, 0.86, 0.0), "D27": (0.5, 0.97, 0.0),
    "E61": (0.5, 0.6, 0.0), "G45": (0.6, 0.85, 0.0), "H29": (1.0, 0.6, 0.0),
    "J19": (0.3, 0.5, 0.0), "J20": (0.3, 0.45, 0.0), "J21": (0.2, 0.4, 0.0),                # vinyl cloth, laminates
    "L01": (1.2, 0.3, 0.0), "L02": (1.3, 0.85, 0.0), "L03": (1.2, 0.45, 0.0), "L04": (1.0, 0.4, 0.0),
    "L33": (1.4, 0.5, 0.0), "L34": (1.4, 0.5, 0.0),                                         # flesh: wet is smooth
}
# Decal sheets (贴花图集) that are not two by two: id -> (columns, rows).
GRID = {"A09": (1, 2), "P05": (3, 1), "P06": (3, 1), "P07": (3, 1), "P09": (3, 1), "M33a": (4, 4), "M33b": (4, 4)}
# Parts of an accepted sheet that must not be used, blanked on the way in:
# id -> boxes (x0, y0, x1, y1) in the 1024 image. See the audit for why.
BLANK = {
    "N10": [(261, 445, 514, 653), (514, 445, 787, 653)],    # two payment-card stickers too close to real card marks
}


def find(src, name):
    hits = glob.glob(os.path.join(src, "*", name))
    if not hits:
        raise FileNotFoundError(name)
    return hits[0]


def blur(a, sigma):
    """Gaussian blur that wraps round the edges, so derived maps tile like their source."""
    h, w = a.shape
    fy = np.fft.fftfreq(h)[:, None]
    fx = np.fft.fftfreq(w)[None, :]
    return np.real(np.fft.ifft2(np.fft.fft2(a) * np.exp(-2.0 * (np.pi * sigma) ** 2 * (fx * fx + fy * fy))))


def derive(color, strength, rough, metal):
    """Normal (OpenGL: green up) and ORM from a colour image, taking brightness
    for height. It is a guess, good enough for surfaces that are nearly flat;
    it tiles because every filter wraps."""
    rgb = np.asarray(color.convert("RGB")).astype(np.float64) / 255.0
    lum = rgb @ np.array([0.299, 0.587, 0.114])
    nx = np.zeros_like(lum)
    ny = np.zeros_like(lum)
    for sigma, weight in ((0.8, 1.0), (3.0, 2.0), (8.0, 2.0)):       # fine grain, then broader forms
        h = blur(lum, sigma)
        nx -= weight * (np.roll(h, -1, 1) - np.roll(h, 1, 1)) * 0.5
        ny += weight * (np.roll(h, -1, 0) - np.roll(h, 1, 0)) * 0.5   # rows run down, green points up
    k = 9.0 * strength
    n = np.dstack([nx * k, ny * k, np.ones_like(lum)])
    n /= np.linalg.norm(n, axis=2, keepdims=True)
    normal = Image.fromarray(np.uint8(np.clip(n * 0.5 + 0.5, 0, 1) * 255 + 0.5), "RGB")
    soft = blur(lum, 2.0)
    occlusion = np.clip(1.0 - 2.2 * strength * np.maximum(blur(lum, 10.0) - soft, 0.0), 0.45, 1.0)
    # dirt (darker) is duller; much less so where the darkness is a printed pattern
    roughness = np.clip(rough + 0.35 * min(strength, 1.0) * (soft.mean() - soft), 0.05, 1.0)
    metalness = np.full_like(lum, metal)
    if metal > 0.0:                                                   # rust and grime are not metal
        sat = rgb.max(2) - rgb.min(2)
        metalness = np.clip(metal - 3.0 * np.maximum(sat - 0.12, 0.0), 0.0, 1.0)
    orm = Image.fromarray(np.uint8(np.dstack([occlusion, roughness, metalness]) * 255 + 0.5), "RGB")
    return normal, orm


def mirror_tile(im):
    """Makes a strip tile left to right: the image followed by its mirror, squeezed back to width."""
    w, h = im.size
    wide = Image.new(im.mode, (w * 2, h))
    wide.paste(im, (0, 0))
    wide.paste(im.transpose(Image.FLIP_LEFT_RIGHT), (w, 0))
    return wide.resize((w, h), Image.LANCZOS)


def clean_glyphs(im):
    """White icons on transparency from white-ish icons on a dark, textured ground."""
    solid = im.convert("L").filter(ImageFilter.GaussianBlur(1.0)).point(lambda v: 255 if v > 92 else 0)
    # close the cracks and specks the generator painted into the icons, keep their outlines
    solid = solid.filter(ImageFilter.MaxFilter(9)).filter(ImageFilter.MinFilter(9))
    solid = solid.filter(ImageFilter.MinFilter(5)).filter(ImageFilter.MaxFilter(5)).filter(ImageFilter.GaussianBlur(0.8))   # and drop loose specks
    alpha = np.asarray(solid).astype(np.float64) / 255.0
    edge = 22                                            # the ragged rim of the painted background
    alpha[:edge] = alpha[-edge:] = 0.0
    alpha[:, :edge] = alpha[:, -edge:] = 0.0
    out = np.dstack([np.full_like(alpha, 255.0)] * 3 + [alpha * 255.0])
    return Image.fromarray(np.uint8(out + 0.5), "RGBA")


def quarters(im):
    w, h = im.size
    for k, (x, y) in enumerate(((0, 0), (w // 2, 0), (0, h // 2), (w // 2, h // 2))):
        yield "abcd"[k], im.crop((x, y, x + w // 2, y + h // 2))


def pieces(im, cols, rows):
    """Cuts a sheet of several decals on transparency into its items. The sheet
    is a loose grid (GRID says how many columns and rows); each cut is put on
    the emptiest line near where an even grid would have it, rows first and
    then the columns of each row on their own, since the items are not lined
    up. Returns the items cropped tight with a small margin, row by row."""
    alpha = (np.asarray(im)[..., 3] > 16)

    def cuts(profile, n):
        size = len(profile)
        marks = [0]
        for k in range(1, n):
            nominal = size * k // n
            reach = int(size * 0.18)
            lo, hi = max(nominal - reach, 1), min(nominal + reach, size - 1)
            window = profile[lo:hi]
            least = window.min()
            # the middle of the widest run of emptiest lines
            best_len, best_mid, start = 0, nominal, None
            for i, v in enumerate(list(window <= least) + [False]):
                if v and start is None:
                    start = i
                elif not v and start is not None:
                    if i - start > best_len:
                        best_len, best_mid = i - start, lo + (start + i) // 2
                    start = None
            marks.append(best_mid)
        return marks + [size]

    out = []
    ys = cuts(alpha.sum(axis=1), rows)
    for r in range(rows):
        band = alpha[ys[r]:ys[r + 1]]
        xs = cuts(band.sum(axis=0), cols)
        for c in range(cols):
            cell = band[:, xs[c]:xs[c + 1]]
            yy = np.flatnonzero(cell.any(axis=1))
            xx = np.flatnonzero(cell.any(axis=0))
            if yy.size < 24 or xx.size < 24:
                continue
            m = 6
            box = (max(xs[c] + xx[0] - m, 0), max(ys[r] + yy[0] - m, 0),
                   min(xs[c] + xx[-1] + 1 + m, im.size[0]), min(ys[r] + yy[-1] + 1 + m, im.size[1]))
            out.append(im.crop(box))
    return out


def save_jpg(im, path, channel):
    im.convert("RGB").save(path, quality=QUALITY[channel], optimize=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("src", help="the delivery's textures_incoming directory")
    ap.add_argument("--verdicts", default=VERDICTS)
    ap.add_argument("--only", default="")
    ap.add_argument("--origin", default="xiaoyu/textures-20261112", help="branch the delivery came from (recorded)")
    args = ap.parse_args()
    only = {x for x in args.only.split(",") if x}

    record_path = os.path.join(OUT, "sources.csv")
    records = {}
    if os.path.exists(record_path):
        for row in csv.DictReader(open(record_path, encoding="utf-8-sig")):
            records[row["set"]] = row
    done = 0
    for row in csv.DictReader(open(args.verdicts, encoding="utf-8-sig")):
        lid, name, layout, verdict = row["编号"], row["文件"], row["版式"], row["结论"]
        if verdict not in ("合格", "我来修") or (only and lid not in only):
            continue
        stem = name.rsplit("_", 1)[0]                       # D20_CheckerVinylFloor
        title = stem.split("_", 1)[1]
        im = Image.open(find(args.src, name))
        im.load()
        if lid in BLANK:
            im = im.convert("RGBA")
            for box in BLANK[lid]:
                im.paste((0, 0, 0, 0), box)
        folder = os.path.join(OUT, stem)
        os.makedirs(folder, exist_ok=True)
        for old in os.listdir(folder):
            os.remove(os.path.join(folder, old))
        files, note = [], ""
        if layout in ("平铺", "饰条"):
            color = im.convert("RGB")
            if layout == "饰条":
                color = mirror_tile(color)
                note = "mirrored so it tiles left to right"
            strength, rough, metal = SURFACE.get(lid, DEFAULT)
            normal, orm = derive(color, strength, rough, metal)
            save_jpg(color, os.path.join(folder, stem + "_Color.jpg"), "Color")
            save_jpg(normal, os.path.join(folder, stem + "_Normal.jpg"), "Normal")
            save_jpg(orm, os.path.join(folder, stem + "_ORM.jpg"), "ORM")
            files = [stem + "_Color.jpg", stem + "_Normal.jpg", stem + "_ORM.jpg"]
            note = (note + "; " if note else "") + "normal and ORM derived from the colour (bump %.2f, roughness %.2f, metal %.1f)" % (strength, rough, metal)
        elif layout == "贴花":
            im.convert("RGBA").save(os.path.join(folder, stem + "_Color.png"), optimize=True)
            files = [stem + "_Color.png"]
        elif layout == "贴花四变体":
            for letter, part in quarters(im.convert("RGBA")):
                f = "%s%s_%s_Color.png" % (lid, letter, title)
                part.save(os.path.join(folder, f), optimize=True)
                files.append(f)
            note = "cut into four single decals"
        elif layout == "贴花图集":
            cols, rows = GRID.get(lid, (2, 2))
            for k, part in enumerate(pieces(im.convert("RGBA"), cols, rows)):
                f = "%s%s_%s_Color.png" % (lid, "abcdefghijklmnopqrstuvwxyz"[k], title)
                part.save(os.path.join(folder, f), optimize=True)
                files.append(f)
            note = "cut into %d single decals" % len(files)
        elif layout == "遮罩单张":
            im.convert("L").save(os.path.join(folder, stem + "_Mask.png"), optimize=True)
            files = [stem + "_Mask.png"]
        elif layout == "遮罩":
            for letter, part in quarters(im.convert("L")):
                f = "%s%s_%s_Mask.png" % (lid, letter, title)
                part.save(os.path.join(folder, f), optimize=True)
                files.append(f)
            note = "cut into four single masks"
        elif layout == "发光":
            save_jpg(im, os.path.join(folder, stem + "_Emission.jpg"), "Emission")
            files = [stem + "_Emission.jpg"]
        elif lid == "Q06":
            clean_glyphs(im).save(os.path.join(folder, stem + "_Color.png"), optimize=True)
            files = [stem + "_Color.png"]
            note = "thresholded into clean white icons on transparency"
        elif im.mode == "RGBA" and (np.asarray(im)[..., 3] < 128).mean() > 0.02:
            im.save(os.path.join(folder, stem + "_Color.png"), optimize=True)      # an atlas on transparency
            files = [stem + "_Color.png"]
        else:                                               # faces, print sheets, reference sheets
            save_jpg(im, os.path.join(folder, stem + "_Color.jpg"), "Color")
            files = [stem + "_Color.jpg"]
            if layout == "多视图":
                note = "modelling reference, not a texture"
        if lid in BLANK:
            note = (note + "; " if note else "") + "%d parts blanked (see the audit)" % len(BLANK[lid])
        records[stem] = {"set": stem, "id": lid, "layout": layout, "files": " ".join(files), "made_with": TOOL,
                         "origin": args.origin, "licence": "generated", "received": time.strftime("%Y-%m-%d"), "note": note}
        done += 1
    os.makedirs(OUT, exist_ok=True)
    cols = ["set", "id", "layout", "files", "made_with", "origin", "licence", "received", "note"]
    with open(record_path, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=cols)
        w.writeheader()
        for key in sorted(records):
            w.writerow({c: records[key].get(c, "") for c in cols})
    print("%d sets received, %d on record" % (done, len(records)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
