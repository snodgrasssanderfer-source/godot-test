#!/usr/bin/env python3
"""Finds the separate items on a printed sheet from the library (an atlas on
a flat grey or transparent ground) and prints each one's rectangle the way
the modelling scripts want it: (x0, y0, x1, y1), 0..1, top-left origin, in
reading order.

  tools/sheet_cells.py G12_HandToolPegPacks E35_BatteriesAndHardwarePegs ...

Needs Pillow and numpy.
"""
import glob
import os
import sys

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def cells(path, min_share=0.004):
    im = Image.open(path).convert("RGBA")
    a = np.asarray(im).astype(int)
    h, w = a.shape[:2]
    if (a[..., 3] < 128).mean() > 0.02:
        mask = a[..., 3] > 40
    else:
        ground = np.median(a[:6, :, :3].reshape(-1, 3), axis=0)
        mask = np.abs(a[..., :3] - ground).max(-1) > 26
    small = np.asarray(Image.fromarray((mask * 255).astype(np.uint8)).resize((w // 4, h // 4), Image.BOX)) > 60
    seen = np.zeros_like(small, dtype=bool)
    out = []
    for y0 in range(small.shape[0]):
        for x0 in range(small.shape[1]):
            if not small[y0, x0] or seen[y0, x0]:
                continue
            stack, xs, ys = [(y0, x0)], [], []
            seen[y0, x0] = True
            while stack:
                y, x = stack.pop()
                xs.append(x)
                ys.append(y)
                for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    yy, xx = y + dy, x + dx
                    if 0 <= yy < small.shape[0] and 0 <= xx < small.shape[1] and small[yy, xx] and not seen[yy, xx]:
                        seen[yy, xx] = True
                        stack.append((yy, xx))
            if len(xs) >= min_share * small.size:
                out.append((min(xs) * 4 / w, min(ys) * 4 / h, (max(xs) + 1) * 4 / w, (max(ys) + 1) * 4 / h))
    out.sort(key=lambda r: (round((r[1] + r[3]) / 2 / 0.12), r[0]))        # rows first, then left to right
    return out


def main():
    for name in sys.argv[1:]:
        hits = (glob.glob(os.path.join(ROOT, "textures", "generated", name, name + "_Color.*"))
                or glob.glob(os.path.join(ROOT, "textures", "generated", name, name + "_Emission.*")))      # lit signs: on black
        if not hits:
            print(name, "not found")
            continue
        found = cells(hits[0])
        print("%s = [%s]   # %d" % (name.split("_")[0], ", ".join("(%.3f, %.3f, %.3f, %.3f)" % r for r in found), len(found)))


if __name__ == "__main__":
    main()
