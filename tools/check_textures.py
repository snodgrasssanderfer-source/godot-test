#!/usr/bin/env python3
"""Acceptance check for a texture delivery (rules: docs/texture_delivery.md).

  tools/check_textures.py <dir>                    full check of a folder
  tools/check_textures.py --ref <git-ref> <path>   names, coverage and exact
                                                   duplicates only, read from
                                                   git without downloading files
Options:
  --sheet <prefix>   contact sheets of every colour map with its file name
                     (prefix-1.jpg, prefix-2.jpg, ...): the quickest way for a
                     person to see whether names and pictures agree
  --report <file>    write the result as Markdown
  --missing          also list the P0 items of the list that have no file
  --list <file>      the texture list (default docs/texture_list.md)

Exit code 1 if there is any error. Needs Pillow and numpy for a folder check.
"""
import argparse
import collections
import csv
import hashlib
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NAME = re.compile(r"^([A-R]\d\d)([a-z]?)_([A-Za-z0-9]+)_(Color|Normal|ORM|Emission|Mask|Height|Panorama|Cookie|Curve|Flipbook\d+x\d+)\.(png|jpg|jpeg|exr|hdr)$")
# what a set of each list type has to contain
NEEDS = {
    "平铺": ["Color", "Normal", "ORM"], "饰条": ["Color", "Normal", "ORM"], "物件": ["Color", "Normal", "ORM"],
    "发光": ["Color", "Emission"], "贴花": ["Color"], "图集": ["Color"], "卡片": ["Color"], "全景": ["Panorama"],
}
ALPHA_TYPES = ("贴花", "卡片")
OK_LICENSES = ("cc0", "original", "self-shot", "n/a")


def read_list(path):
    items = {}
    for line in open(path, encoding="utf-8"):
        # id | name | notes | type | priority | source, then (since the list gained one) an asset column
        m = re.match(r"^\| ([A-R]\d\d) \| (.+?) \| .* \| (\S+) \| (P[012]) \| (\S) \|(?: [^|]* \|)?$", line.strip())
        if m:
            items[m.group(1)] = {"name": m.group(2), "type": m.group(3), "prio": m.group(4), "src": m.group(5)}
    return items


class Report:
    def __init__(self):
        self.errors, self.warnings, self.info = [], [], []

    def err(self, s):
        self.errors.append(s)

    def warn(self, s):
        self.warnings.append(s)


def collect_dir(root):
    files = []
    for base, _dirs, names in os.walk(root):
        for n in sorted(names):
            if n.startswith(".") or n.endswith(".import") or n in ("sources.csv", "README.md"):
                continue
            p = os.path.join(base, n)
            h = hashlib.sha1(open(p, "rb").read()).hexdigest()
            files.append((os.path.relpath(p, root), h, p))
    return files


def collect_ref(ref, path):
    out = subprocess.run(["git", "-C", ROOT, "ls-tree", "-r", ref, "--", path], capture_output=True, text=True, check=True).stdout
    files = []
    for line in out.splitlines():
        meta, p = line.split("\t", 1)
        n = os.path.basename(p)
        if n.endswith(".import") or n in ("sources.csv", "README.md"):
            continue
        files.append((os.path.relpath(p, path), meta.split()[2], None))
    return files


def image_checks(sets, items, rep, sheet):
    import numpy as np
    from PIL import Image, ImageDraw
    Image.MAX_IMAGE_PIXELS = None
    thumbs = []
    looks = collections.defaultdict(list)
    for key in sorted(sets):
        lid = key[:3]
        typ = items.get(lid, {}).get("type", "")
        for ch, (rel, _h, path) in sorted(sets[key].items()):
            try:
                im = Image.open(path)
                im.load()
            except Exception as e:                       # noqa: BLE001
                rep.err("%s: cannot be opened (%s)" % (rel, e))
                continue
            w, h = im.size
            pot = lambda v: v & (v - 1) == 0
            if ch == "Curve":
                pass
            elif ch == "Panorama":
                if w != 2 * h:
                    rep.err("%s: a panorama must be 2:1, this is %dx%d" % (rel, w, h))
            elif not (pot(w) and pot(h)):
                rep.err("%s: %dx%d is not a power of two" % (rel, w, h))
            elif typ in ("平铺", "物件", "饰条") and ch in ("Color", "Normal", "ORM") and max(w, h) < 2048 and lid[0] != "R":
                rep.warn("%s: %dx%d, the list asks for 2K for this type" % (rel, w, h))
            small = np.asarray(im.convert("RGBA").resize((64, 64)), dtype=np.float64)
            rgb, alpha = small[..., :3], small[..., 3]
            grey = float(np.abs(rgb[..., 0] - rgb[..., 1]).mean() + np.abs(rgb[..., 1] - rgb[..., 2]).mean()) < 1.0
            flat = float(rgb.std(axis=(0, 1)).max()) < 2.5
            if ch == "Color":
                if typ in ALPHA_TYPES or (typ == "图集" and im.mode == "RGBA"):
                    if im.mode != "RGBA":
                        rep.err("%s: a %s needs transparency (RGBA PNG), this is %s" % (rel, typ, im.mode))
                    elif alpha.min() > 250:
                        rep.err("%s: the alpha channel is fully opaque" % rel)
                    elif alpha.max() < 5:
                        rep.err("%s: the alpha channel is fully transparent" % rel)
                elif grey and typ in ("平铺", "物件", "饰条") and lid[0] != "R":
                    rep.warn("%s: the colour map is greyscale; is this a mask?" % rel)
                if flat and alpha.max() > 250 and alpha.min() > 250:
                    rep.warn("%s: the colour map is one flat colour" % rel)
                looks[tuple((rgb.reshape(8, 8, 8, 8, 3).mean(axis=(1, 3)).mean(axis=2) > rgb.mean()).ravel())].append(rel)
            elif ch == "Normal" or (ch.startswith("Flipbook") and "Ripple" in rel):
                mean = rgb.mean(axis=(0, 1))
                if not (mean[2] > 180 and abs(mean[0] - 128) < 30 and abs(mean[1] - 128) < 30):
                    rep.err("%s: does not look like a tangent-space normal map (mean colour %d %d %d)" % ((rel,) + tuple(int(v) for v in mean)))
            elif ch == "Emission":
                if rgb.max() < 8:
                    rep.err("%s: the emission map is black" % rel)
            elif ch == "ORM":
                if grey:
                    rep.warn("%s: all three channels are the same; an ORM map packs occlusion / roughness / metal" % rel)
            elif ch in ("Mask", "Height", "Cookie"):
                if flat:
                    rep.err("%s: the map is blank" % rel)
            if sheet and ch in ("Color", "Panorama", "Mask", "Cookie", "Height", "Normal", "Emission", "Curve") or (sheet and ch.startswith("Flipbook")):
                if ch in ("Color", "Panorama") or "Color" not in sets[key]:
                    t = im.convert("RGBA")
                    t.thumbnail((224, 224))
                    thumbs.append((rel, t))
    for group in looks.values():
        if len(group) > 1 and len(set(g[:3] for g in group)) > 1:
            rep.warn("these colour maps look alike (re-encoded copies?): " + ", ".join(sorted(group)[:6]))
    if sheet and thumbs:
        cols, cell, label = 8, 228, 26
        per = cols * 5
        for page in range(0, len(thumbs), per):
            chunk = thumbs[page:page + per]
            rows = (len(chunk) + cols - 1) // cols
            img = Image.new("RGB", (cols * cell, rows * (cell + label)), (70, 0, 70))
            d = ImageDraw.Draw(img)
            for i, (rel, t) in enumerate(chunk):
                x, y = (i % cols) * cell + 2, (i // cols) * (cell + label) + 2
                chk = Image.new("RGB", t.size, (46, 46, 46))
                chk.paste(t, (0, 0), t)
                img.paste(chk, (x, y))
                nm = os.path.basename(rel)
                d.text((x, y + cell - 2), nm[:36], fill=(255, 255, 255))
                d.text((x, y + cell + 9), nm[36:72], fill=(255, 255, 255))
            out = "%s-%d.jpg" % (sheet, page // per + 1)
            img.save(out, quality=82)
            rep.info.append("contact sheet: " + out)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("path")
    ap.add_argument("--ref")
    ap.add_argument("--sheet")
    ap.add_argument("--report")
    ap.add_argument("--missing", action="store_true")
    ap.add_argument("--list", default=os.path.join(ROOT, "docs", "texture_list.md"))
    a = ap.parse_args()
    items = read_list(a.list)
    rep = Report()
    files = collect_ref(a.ref, a.path) if a.ref else collect_dir(a.path)
    if not files:
        print("no texture files found")
        return 1

    # names -> sets
    sets = collections.defaultdict(dict)
    for rel, h, path in files:
        m = NAME.match(os.path.basename(rel))
        if not m:
            rep.err("%s: name does not follow {id}{variant}_{Name}_{Channel}.ext" % rel)
            continue
        lid, var, name, ch = m.group(1), m.group(2), m.group(3), m.group(4)
        if lid not in items:
            rep.err("%s: %s is not an id in the texture list" % (rel, lid))
            continue
        key = "%s%s_%s" % (lid, var, name)
        ch = "Flipbook" if ch.startswith("Flipbook") and False else ch
        if ch in sets[key]:
            rep.err("%s: the set %s has two %s files" % (rel, key, ch))
        sets[key][ch] = (rel, h, path)

    # completeness per list type
    for key in sorted(sets):
        lid = key[:3]
        typ = items[lid]["type"]
        have = set(sets[key])
        need = NEEDS.get(typ)
        if need and lid[0] != "R" and not any(c.startswith("Flipbook") for c in have):
            lacking = [c for c in need if c not in have]
            if lacking and not (typ in ("物件", "发光") and have & {"Mask", "Cookie", "Normal"}):
                rep.err("%s (%s, %s): missing %s" % (key, items[lid]["name"], typ, ", ".join(lacking)))

    # exact duplicates
    by_hash = collections.defaultdict(list)
    for rel, h, _p in files:
        by_hash[h].append(rel)
    copies = 0
    for h, rels in sorted(by_hash.items(), key=lambda kv: -len(kv[1])):
        owners = sorted(set(os.path.basename(r)[:3] for r in rels))
        if len(rels) > 1 and len(owners) > 1:
            copies += len(rels) - 1
            rep.err("the same file is delivered for %d different items: %s" % (len(owners), ", ".join(sorted(os.path.basename(r) for r in rels)[:5])))

    # source records
    manifest = {}
    if not a.ref:
        mp = os.path.join(a.path, "sources.csv")
        if os.path.exists(mp):
            for row in csv.DictReader(open(mp, encoding="utf-8")):
                manifest[row.get("set", "").strip()] = row
        else:
            rep.err("sources.csv is missing: every set needs a row saying where it came from")
        if manifest:
            for key in sorted(sets):
                row = manifest.get(key)
                if row is None:
                    rep.err("%s: no row in sources.csv" % key)
                    continue
                lic = row.get("license", "").strip().lower()
                if lic not in OK_LICENSES:
                    rep.err("%s: license '%s' is not one of %s" % (key, row.get("license", ""), ", ".join(OK_LICENSES)))
                if lic == "cc0" and not (row.get("asset", "").strip() and row.get("url", "").strip()):
                    rep.err("%s: a CC0 set needs the original asset name and its URL" % key)
        image_checks(sets, items, rep, a.sheet)

    # coverage
    covered = set(k[:3] for k in sets)
    if manifest:
        covered |= set(k[:3] for k, row in manifest.items() if row.get("source") == "engine" and k[:3] in items)
    groups = collections.OrderedDict()
    prios = collections.OrderedDict((p, [0, 0]) for p in ("P0", "P1", "P2"))
    for lid, it in sorted(items.items()):
        g = groups.setdefault(lid[0], [0, 0])
        g[1] += 1
        prios[it["prio"]][1] += 1
        if lid in covered:
            g[0] += 1
            prios[it["prio"]][0] += 1
    distinct = len(set(v["Color"][1] for v in sets.values() if "Color" in v))

    out = []
    out.append("# Texture delivery check: %s" % (("%s:%s" % (a.ref, a.path)) if a.ref else a.path))
    out.append("")
    out.append("%d files in %d sets; %d distinct colour maps." % (len(files), len(sets), distinct))
    out.append("List coverage: %d of %d items (%s)." % (len(covered), len(items), ", ".join("%s %d/%d" % (p, v[0], v[1]) for p, v in prios.items())))
    out.append("By group: " + "  ".join("%s %d/%d" % (g, v[0], v[1]) for g, v in groups.items()))
    if a.ref:
        out.append("(read from git: image content, sizes and sources.csv were NOT checked)")
    out.append("")
    out.append("## Errors (%d)" % len(rep.errors))
    out += ["- " + e for e in rep.errors] or ["none"]
    out.append("")
    out.append("## Warnings (%d)" % len(rep.warnings))
    out += ["- " + w for w in rep.warnings] or ["none"]
    if a.missing:
        out.append("")
        out.append("## P0 items without a file")
        out += ["- %s %s (%s, %s)" % (lid, it["name"], it["type"], it["src"]) for lid, it in sorted(items.items()) if it["prio"] == "P0" and lid not in covered]
    if rep.info:
        out.append("")
        out += rep.info
    text = "\n".join(out)
    if a.report:
        open(a.report, "w", encoding="utf-8").write(text + "\n")
    limit = 60
    lines = text.splitlines()
    print("\n".join(lines[:limit] + (["... (%d more lines; use --report)" % (len(lines) - limit)] if len(lines) > limit and not a.report else lines[limit:] if a.report is None else [])))
    print("\nRESULT: %s" % ("FAIL" if rep.errors else "PASS"))
    return 1 if rep.errors else 0


if __name__ == "__main__":
    sys.exit(main())
