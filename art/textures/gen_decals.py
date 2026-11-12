#!/usr/bin/env python3
"""Generates the stain and damage decals that no CC0 library carries.

These are list entries marked "拍" / "库" / "画" for which nothing suitable
exists on ambientCG or Poly Haven but which are simple enough to synthesise:
oil, skid marks, glass, scorch, spills, droppings, cracks, litter, seeping
black oil, blood drops, dents and two transition masks.

Usage:  python3 art/textures/gen_decals.py            (needs numpy + Pillow)
Output: textures/procedural/*.png and sources.csv     (deterministic)

Every image is 1024 x 1024. Colour decals are RGBA with the shape in alpha;
most hold four variants in a 2 x 2 grid. Names follow docs/texture_delivery.md.
First versions: none has been seen in the engine yet.
"""
import csv
import math
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "textures", "procedural")
N = 1024
H = N // 2
MADE = []


def rng(seed):
    return np.random.default_rng(seed)


def norm01(a):
    a = a - a.min()
    return a / (a.max() + 1e-12)


def fnoise(n, beta, seed):
    """1/f^beta noise, 0..1."""
    f = np.sqrt(np.fft.fftfreq(n)[:, None] ** 2 + np.fft.fftfreq(n)[None, :] ** 2)
    f[0, 0] = 1.0
    spec = (rng(seed).standard_normal((n, n)) + 1j * rng(seed + 1).standard_normal((n, n))) / f ** (beta / 2.0)
    spec[0, 0] = 0.0
    return norm01(np.fft.ifft2(spec).real)


def blur(a, sigma):
    img = Image.fromarray((np.clip(a, 0, 1) * 255).astype(np.uint8))
    return np.asarray(img.filter(ImageFilter.GaussianBlur(sigma)), dtype=np.float32) / 255.0


def smooth(a, lo, hi):
    t = np.clip((a - lo) / (hi - lo + 1e-9), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def grid(n):
    y, x = np.mgrid[0:n, 0:n].astype(np.float32) / n
    return x, y


def radial(n, cx=0.5, cy=0.5, sx=1.0, sy=1.0):
    x, y = grid(n)
    return np.sqrt(((x - cx) / sx) ** 2 + ((y - cy) / sy) ** 2)


def blob(n, seed, size=0.30, ragged=0.5, beta=2.6, cx=0.5, cy=0.5, sx=1.0, sy=1.0):
    """An irregular patch: 1 inside, 0 outside, soft noisy edge."""
    d = radial(n, cx, cy, sx, sy) / size
    return smooth(1.0 - d + (fnoise(n, beta, seed) - 0.5) * 2.0 * ragged, 0.0, 0.25)


def canvas(n, scale=2):
    img = Image.new("L", (n * scale, n * scale), 0)
    return img, ImageDraw.Draw(img)


def field(img, n):
    return np.asarray(img.resize((n, n), Image.LANCZOS), dtype=np.float32) / 255.0


def quad(fn):
    """Four variants of a 512 px decal, tiled 2 x 2. fn(seed) -> (rgb, alpha) arrays of shape (512, 512, 3) / (512, 512)."""
    rgb = np.zeros((N, N, 3), dtype=np.float32)
    alpha = np.zeros((N, N), dtype=np.float32)
    for k in range(4):
        c, a = fn(k)
        r0, c0 = (k // 2) * H, (k % 2) * H
        edge = np.minimum(np.minimum(*grid(H)), np.minimum(1 - grid(H)[0], 1 - grid(H)[1]))
        a = a * smooth(edge, 0.0, 0.04)                      # nothing may touch the cell border
        rgb[r0:r0 + H, c0:c0 + H] = c
        alpha[r0:r0 + H, c0:c0 + H] = a
    return rgb, alpha


def flat(color, n=H):
    return np.ones((n, n, 3), dtype=np.float32) * np.array(color, dtype=np.float32)


def save(name, rgb=None, alpha=None, grey=None, note=""):
    os.makedirs(OUT, exist_ok=True)
    if grey is not None:
        img = Image.fromarray((np.clip(grey, 0, 1) * 255 + 0.5).astype(np.uint8), "L")
    elif alpha is None:
        img = Image.fromarray((np.clip(rgb, 0, 1) * 255 + 0.5).astype(np.uint8), "RGB")
    else:
        img = Image.fromarray((np.clip(np.dstack([rgb, alpha]), 0, 1) * 255 + 0.5).astype(np.uint8), "RGBA")
    img.save(os.path.join(OUT, name + ".png"), optimize=True)
    MADE.append((name.rsplit("_", 1)[0], note))


def normal_from_height(h, strength):
    gy, gx = np.gradient(h)
    nx, ny, nz = -gx * strength, gy * strength, np.ones_like(h)
    l = np.sqrt(nx * nx + ny * ny + nz * nz)
    return np.dstack([nx / l * 0.5 + 0.5, ny / l * 0.5 + 0.5, nz / l * 0.5 + 0.5])


def drops(n, seed, count, cx, cy, spread, rmin, rmax):
    """Round droplets scattered around a point (drawn at 2x, so they are smooth)."""
    r = rng(seed)
    img, d = canvas(n)
    for _ in range(count):
        a, dist = r.uniform(0, math.tau), abs(r.normal(0, spread))
        x, y = (cx + math.cos(a) * dist) * n * 2, (cy + math.sin(a) * dist) * n * 2
        rad = r.uniform(rmin, rmax) * n * 2 * (1.0 - min(dist / (spread * 3), 0.8))
        d.ellipse((x - rad, y - rad, x + rad, y + rad), fill=255)
    return field(img, n)


# --- ground -------------------------------------------------------------------------------
def ground():
    # A04 tar sealant: black snakes over cracks
    img, d = canvas(N)
    r = rng(40)
    for k in range(7):
        x, y = r.uniform(0.05, 0.95) * N * 2, -20
        heading = r.uniform(1.2, 1.9)
        pts = [(x, y)]
        while y < N * 2 + 20:
            heading += r.normal(0, 0.22)
            heading = min(max(heading, 0.6), 2.5)
            x += math.cos(heading) * 46
            y += math.sin(heading) * 46
            pts.append((x, y))
        d.line(pts, fill=255, width=int(r.uniform(14, 30)), joint="curve")
    a = field(img, N)
    a = smooth(blur(a, 2.0) + (fnoise(N, 1.6, 41) - 0.5) * 0.35, 0.35, 0.6)
    tone = 0.035 + 0.03 * fnoise(N, 1.2, 42)
    save("A04_TarCrackSealant_Color", np.dstack([tone, tone, tone * 1.05]), a, note="meandering tar lines; alpha is the tar")

    # A36 dried mud around puddles: the rim of a patch
    def mud(k):
        b = blob(H, 100 + k * 7, 0.33, 0.55)
        rim = smooth(b, 0.05, 0.5) * (1.0 - smooth(b, 0.55, 0.98) * 0.85)
        grain = fnoise(H, 1.4, 105 + k)
        c = flat((0.23, 0.17, 0.11)) * (0.75 + 0.5 * grain[..., None])
        return c, rim * (0.55 + 0.45 * grain)
    save("A36_PuddleMudRim_Color", *quad(mud), note="4 variants; mud left where a puddle dried from the edge inwards")

    # A38 oil sheen: thin-film colours on a patch
    def sheen(k):
        b = blob(H, 120 + k * 5, 0.30, 0.5)
        phase = blur(b, 6.0) * 1.6 + blur(fnoise(H, 3.4, 125 + k), 5.0) * 0.9       # bands follow the thickness of the film
        c = np.dstack([0.5 + 0.5 * np.sin(phase * 6.283 + o) for o in (0.0, 2.1, 4.2)])
        c = 0.42 + 0.30 * c                                                          # pale, like a real sheen
        return c, smooth(b, 0.02, 0.6) * 0.45
    save("A38_OilRainbowSheen_Color", *quad(sheen), note="4 variants; iridescent film, meant for wet asphalt, keep it glossy")

    # A39 / M27 engine oil: drips and patches, smears
    def oil_drips(k):
        a = np.maximum(drops(H, 140 + k, 55, 0.5, 0.5, 0.11, 0.004, 0.03), blob(H, 145 + k, 0.10, 0.6) * 0.9)
        a = smooth(blur(a, 1.2) + (fnoise(H, 1.5, 147 + k) - 0.5) * 0.25, 0.3, 0.62)
        c = flat((0.035, 0.028, 0.02)) * (0.7 + 0.6 * fnoise(H, 1.2, 149 + k)[..., None])
        return c, a * 0.92
    save("A39_OilDripSpots_Color", *quad(oil_drips), note="4 variants; a cluster of drips under a parked engine")

    def oil_stain(k):
        if k < 2:        # a wide soaked patch
            a = blob(H, 160 + k * 3, 0.34, 0.6) * (0.6 + 0.4 * fnoise(H, 2.0, 163 + k))
        elif k == 2:     # splatter
            a = np.maximum(drops(H, 166, 130, 0.5, 0.5, 0.16, 0.002, 0.02), blob(H, 167, 0.07, 0.5))
            a = smooth(blur(a, 1.0), 0.3, 0.6)
        else:            # wiped by a hand: streaks
            x, y = grid(H)
            streak = norm01(blur(fnoise(H, 1.0, 168) * (np.sin((y + 0.25 * np.sin(x * 5)) * 80) * 0.5 + 0.5), 1.5))
            a = blob(H, 169, 0.32, 0.3, sx=1.3, sy=0.55) * smooth(streak, 0.25, 0.6)
        c = flat((0.03, 0.025, 0.02)) * (0.7 + 0.6 * fnoise(H, 1.3, 170 + k)[..., None])
        return c, a * 0.9
    save("M27_EngineOilStains_Color", *quad(oil_stain), note="4 variants: two soaked patches, a splatter, a hand-wiped smear")

    # A40 skid marks
    x, y = grid(N)
    rgb = np.ones((N, N, 3), dtype=np.float32) * 0.03
    alpha = np.zeros((N, N), dtype=np.float32)
    for row, (cy, curve, seed) in enumerate(((0.17, 0.0, 180), (0.5, 0.05, 183), (0.83, -0.04, 186))):
        for off in (-0.045, 0.045):
            centre = cy + off + curve * (x - 0.5) ** 2 * 4
            band = smooth(1.0 - np.abs(y - centre) / 0.024, 0.0, 0.5)
            tread = 0.6 + 0.4 * np.sin((y - centre) * 900)
            fade = smooth(x, 0.03, 0.25) * (1.0 - smooth(x, 0.55 + 0.1 * row, 0.97))
            wear = 0.45 + 0.55 * fnoise(N, 1.3, seed + (1 if off > 0 else 0))
            alpha = np.maximum(alpha, band * tread * fade * wear)
    save("A40_SkidMarks_Color", rgb, alpha * 0.85, note="3 pairs of tyre streaks, left to right, fading out")

    # A42 broken glass
    def glass(k):
        r = rng(200 + k)
        img, d = canvas(H)
        edge, de = canvas(H)
        for _ in range(150):
            a, dist = r.uniform(0, math.tau), abs(r.normal(0, 0.16))
            cx, cy = (0.5 + math.cos(a) * dist) * H * 2, (0.5 + math.sin(a) * dist) * H * 2
            size = r.uniform(8, 60) * (1.0 - min(dist / 0.45, 0.8))
            pts = []
            base = r.uniform(0, math.tau)
            for j in range(r.integers(3, 6)):
                ang = base + j * math.tau / 4 + r.uniform(-0.5, 0.5)
                pts.append((cx + math.cos(ang) * size * r.uniform(0.4, 1.0), cy + math.sin(ang) * size * r.uniform(0.4, 1.0)))
            d.polygon(pts, fill=int(r.uniform(90, 200)))
            de.line(pts + [pts[0]], fill=255, width=3)
        body, rim = field(img, H), field(edge, H)
        c = flat((0.72, 0.84, 0.82)) * (0.55 + 0.45 * body[..., None]) + rim[..., None] * 0.5
        return c, np.clip(smooth(body, 0.05, 0.3) * 0.5 + rim * 0.9, 0, 1)
    save("A42_BrokenGlass_Color", *quad(glass), note="4 scatters of shards; bright edges, greenish body; give it low roughness")

    # A43 loose grit
    def grit(k):
        r = rng(220 + k)
        img, d = canvas(H)
        for _ in range(1400):
            a, dist = r.uniform(0, math.tau), abs(r.normal(0, 0.17))
            cx, cy = (0.5 + math.cos(a) * dist) * H * 2, (0.5 + math.sin(a) * dist) * H * 2
            rad = r.uniform(0.8, 4.5)
            d.ellipse((cx - rad, cy - rad * r.uniform(0.6, 1.0), cx + rad, cy + rad), fill=int(r.uniform(120, 255)))
        stones = field(img, H)
        tint = fnoise(H, 0.6, 225 + k)
        c = flat((0.42, 0.38, 0.33)) * (0.45 + 0.75 * tint[..., None]) * (0.5 + 0.5 * stones[..., None])
        return c, smooth(stones, 0.08, 0.3)
    save("A43_LooseGrit_Color", *quad(grit), note="4 scatters of small stones")

    # C31 roofing felt patches and seams
    x, y = grid(N)
    r = rng(240)
    img, d = canvas(N)
    for _ in range(7):
        w, h = r.uniform(0.12, 0.3) * N * 2, r.uniform(0.1, 0.24) * N * 2
        cx, cy = r.uniform(0.15, 0.85) * N * 2, r.uniform(0.15, 0.85) * N * 2
        d.rectangle((cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), fill=150, outline=255, width=14)
    for sy in (0.33, 0.66):
        d.line((0, sy * N * 2 + r.uniform(-20, 20), N * 2, sy * N * 2 + r.uniform(-20, 20)), fill=255, width=18)
    shape = field(img, N)
    a = smooth(shape + (fnoise(N, 1.4, 241) - 0.5) * 0.3, 0.2, 0.5)
    tone = 0.05 + 0.05 * fnoise(N, 1.0, 242) + 0.04 * smooth(shape, 0.7, 0.95)      # tar along the edges is glossier and darker
    save("C31_RoofFeltPatches_Color", np.dstack([tone, tone, tone]), a * 0.9, note="patches with tarred edges and two long seams")


# --- interiors -----------------------------------------------------------------------------
def interiors():
    # D28 carpet stains and cigarette burns
    def carpet(k):
        if k < 2:
            b = blob(H, 300 + k * 5, 0.30, 0.6)
            ring = smooth(b, 0.05, 0.4) * (1.0 - 0.5 * smooth(b, 0.5, 0.95))        # darker at the tide line
            c = flat(((0.27, 0.17, 0.08), (0.16, 0.10, 0.09))[k])
            return c * (0.8 + 0.4 * fnoise(H, 1.5, 303 + k)[..., None]), ring * 0.7
        holes = drops(H, 310 + k, 7, 0.5, 0.5, 0.2, 0.012, 0.028)
        core = smooth(blur(holes, 1.5), 0.45, 0.7)
        halo = smooth(blur(holes, 6.0), 0.08, 0.45)
        c = flat((0.20, 0.13, 0.07)) * (1.0 - core[..., None] * 0.9)
        return c, np.clip(halo * 0.8 + core, 0, 1)
    save("D28_CarpetStainsAndBurns_Color", *quad(carpet), note="2 spill stains, 2 groups of cigarette burns")

    # E39 limescale rings under a leaking cooler
    def scale(k):
        b = blob(H, 320 + k * 4, 0.33, 0.45)
        rings = np.zeros((H, H), dtype=np.float32)
        for level, w in ((0.15, 0.9), (0.38, 0.6), (0.6, 0.45), (0.8, 0.3)):
            rings = np.maximum(rings, (1.0 - np.clip(np.abs(b - level) / 0.06, 0, 1)) * w)
        c = flat((0.80, 0.78, 0.70))
        haze = smooth(b, 0.1, 0.9) * 0.18
        return c, np.clip(rings * (0.6 + 0.4 * fnoise(H, 1.4, 323 + k)) * 1.3 + haze, 0, 1)
    save("E39_CoolerDripScale_Color", *quad(scale), note="4 variants; pale mineral tide lines where water kept drying")

    # I25 lint
    def lint(k):
        r = rng(340 + k)
        img, d = canvas(H)
        for _ in range(900):
            a, dist = r.uniform(0, math.tau), abs(r.normal(0, 0.12))
            x, y = (0.5 + math.cos(a) * dist) * H * 2, (0.5 + math.sin(a) * dist * 0.6) * H * 2
            ang, ln = r.uniform(0, math.tau), r.uniform(8, 40)
            d.arc((x - ln, y - ln, x + ln, y + ln), math.degrees(ang), math.degrees(ang) + r.uniform(20, 110), fill=int(r.uniform(90, 230)), width=1)
        fibres = field(img, H)
        body = blob(H, 345 + k, 0.2, 0.7, sy=0.6) * (0.4 + 0.6 * fnoise(H, 1.8, 347 + k))
        c = flat((0.55, 0.55, 0.58)) * (0.7 + 0.5 * fnoise(H, 0.8, 349 + k)[..., None])
        return c, np.clip(body * 0.75 + fibres * 0.8, 0, 1)
    save("I25_LintClumps_Color", *quad(lint), note="4 grey clumps with stray fibres")

    # M17 nail holes and torn paper corners
    def pins(k):
        r = rng(360 + k)
        c = flat((0.80, 0.76, 0.66))
        a = np.zeros((H, H), dtype=np.float32)
        img, d = canvas(H)
        hole, dh = canvas(H)
        for _ in range(4 if k < 2 else 9):
            x, y = r.uniform(0.15, 0.85) * H * 2, r.uniform(0.15, 0.85) * H * 2
            if k < 2:        # a corner of paper left under a pin or tape
                s = r.uniform(40, 110)
                ang = r.uniform(0, math.tau)
                pts = [(x, y), (x + math.cos(ang) * s, y + math.sin(ang) * s),
                       (x + math.cos(ang + 1.2 + r.uniform(-0.3, 0.3)) * s * r.uniform(0.5, 1.0), y + math.sin(ang + 1.2) * s * r.uniform(0.5, 1.0))]
                d.polygon(pts, fill=255)
            rad = r.uniform(3, 6)
            dh.ellipse((x - rad, y - rad, x + rad, y + rad), fill=255)
        paper, holes = field(img, H), field(hole, H)
        paper = smooth(paper + (fnoise(H, 1.5, 365 + k) - 0.5) * 0.25, 0.4, 0.6)
        c = c * (0.85 + 0.25 * fnoise(H, 1.0, 367 + k)[..., None]) * (1.0 - holes[..., None] * 0.95)
        return c, np.clip(paper * 0.95 + holes + blur(holes, 3) * 0.5, 0, 1)
    save("M17_PinHolesAndPaperCorners_Color", *quad(pins), note="2 cells of torn paper corners with pin holes, 2 of bare holes")

    # D12 hole in a wall: lath edge, insulation, wires
    x, y = grid(N)
    hole = blob(N, 380, 0.30, 0.45, beta=2.2)
    inner = smooth(hole, 0.45, 0.6)
    edge = smooth(hole, 0.05, 0.3) * (1.0 - inner)
    wool = fnoise(N, 1.1, 381)
    c = np.dstack([0.78 * (0.6 + 0.5 * wool), 0.55 * (0.55 + 0.5 * wool), 0.42 * (0.5 + 0.5 * wool)])     # pink-yellow fibreglass
    c = c * (0.35 + 0.65 * smooth(radial(N), 0.0, 0.3))[..., None] * inner[..., None]
    c += np.array([0.62, 0.60, 0.55]) * edge[..., None] * (0.7 + 0.5 * fnoise(N, 1.6, 382)[..., None])    # broken plaster rim
    img, d = canvas(N)
    for sx, sway in ((0.42, 60), (0.55, -90)):
        pts = [((sx + 0.04 * math.sin(t * 5 + sway)) * N * 2, t * N * 2) for t in np.linspace(0.15, 0.85, 30)]
        d.line(pts, fill=255, width=14, joint="curve")
    wires = field(img, N) * inner
    c = c * (1.0 - wires[..., None]) + np.array([0.08, 0.08, 0.07]) * wires[..., None]
    save("D12_WallHoleInsulation_Color", c, np.clip(inner + edge, 0, 1), note="ragged hole: plaster rim, fibreglass, two cables")

    # D18 collapsed ceiling: dark void, grid runners
    hole = blob(N, 390, 0.33, 0.35, beta=2.0)
    inner = smooth(hole, 0.4, 0.55)
    edge = smooth(hole, 0.05, 0.35) * (1.0 - inner)
    c = np.ones((N, N, 3), dtype=np.float32) * 0.02 * inner[..., None]
    runner = np.maximum(1.0 - np.clip(np.abs((x * 2.0) % 1.0 - 0.5) / 0.02, 0, 1), 1.0 - np.clip(np.abs((y * 2.0) % 1.0 - 0.5) / 0.02, 0, 1))
    c += np.array([0.42, 0.42, 0.40]) * (runner * inner)[..., None]
    c += np.array([0.70, 0.68, 0.62]) * edge[..., None] * (0.6 + 0.6 * fnoise(N, 1.7, 391)[..., None])
    save("D18_CeilingCollapse_Color", c, np.clip(inner + edge, 0, 1), note="broken tile edge, dark void, bare grid runners")


# --- walls ---------------------------------------------------------------------------------
def walls():
    # M15 stepped settlement crack along brick courses
    img, d = canvas(N)
    r = rng(400)
    for start in ((0.12, 0.05), (0.55, 0.0)):
        x, y = start[0] * N * 2, start[1] * N * 2
        pts = [(x, y)]
        while y < N * 2:
            y += 128 * r.uniform(0.9, 1.1)                    # down one course...
            pts.append((x + r.uniform(-6, 6), y))
            x += 190 * r.uniform(0.7, 1.2)                    # ...along the bed joint
            pts.append((x, y + r.uniform(-6, 6)))
        for a, b in zip(pts, pts[1:]):
            d.line((a, b), fill=255, width=int(r.uniform(4, 13)))
    crack = field(img, N)
    a = smooth(blur(crack, 0.8) + (fnoise(N, 1.2, 401) - 0.5) * 0.2, 0.25, 0.5)
    save("M15_SteppedBrickCrack_Color", np.ones((N, N, 3), dtype=np.float32) * 0.03, np.clip(a + blur(a, 5) * 0.35, 0, 1),
         note="2 cracks stepping down the mortar joints; scale one step to one brick course")

    # M25 soot and scorch: rising plumes
    def soot(k):
        x, y = grid(H)
        base = 0.86 if k % 2 == 0 else 0.6
        width = 0.10 + 0.22 * np.clip(base - y, 0, 1)        # widens as it rises
        centre = 0.5 + (fnoise(H, 3.0, 410 + k) - 0.5) * 0.25
        plume = smooth(1.0 - np.abs(x - centre) / width, 0.0, 0.8) * smooth(base + 0.05 - y, 0.0, 0.12) * (1.0 - smooth(base - y, 0.45, 0.9))
        plume *= 0.5 + 0.5 * fnoise(H, 2.2, 413 + k)
        char = blob(H, 415 + k, 0.09, 0.5, cy=base - 0.02)
        c = flat((0.03, 0.028, 0.025))
        return c, np.clip(plume * 0.8 + char * 0.95, 0, 1)
    save("M25_SootAndScorch_Color", *quad(soot), note="4 variants; charred base with soot climbing the wall")

    # M26 arc scorch around a socket
    def arc(k):
        x, y = grid(H)
        d = radial(H)
        ang = np.arctan2(y - 0.5, x - 0.5)
        rays = 0.5 + 0.5 * np.sin(ang * (9 + k * 2) + fnoise(H, 2.0, 420 + k) * 6)
        burn = smooth(1.0 - d / (0.2 + 0.16 * rays), 0.0, 0.7) * (0.6 + 0.4 * fnoise(H, 1.4, 423 + k))
        plate = np.maximum(np.abs(x - 0.5) / 0.06, np.abs(y - 0.5) / 0.09)                    # where the cover plate sat
        burn *= smooth(plate, 0.9, 1.15)
        c = flat((0.035, 0.03, 0.028)) + flat((0.12, 0.07, 0.03)) * (burn < 0.35)[..., None]
        return c, burn * 0.92
    save("M26_SocketArcScorch_Color", *quad(arc), note="4 variants; starburst of soot with a clean rectangle where the plate was")

    # M28 drink spills, M29 bird droppings
    def spill(k):
        body = blob(H, 430 + k * 3, 0.20, 0.5, cy=0.42)
        splash = drops(H, 433 + k, 60, 0.5, 0.42, 0.17, 0.003, 0.016)
        x, y = grid(H)
        runs = np.zeros((H, H), dtype=np.float32)
        r = rng(435 + k)
        for _ in range(4):
            rx, ln = r.uniform(0.38, 0.62), r.uniform(0.15, 0.4)
            runs = np.maximum(runs, smooth(1.0 - np.abs(x - rx) / 0.012, 0, 0.6) * smooth(y, 0.42, 0.5) * (1.0 - smooth(y, 0.45 + ln * 0.8, 0.5 + ln)))
        a = np.clip(body + smooth(blur(splash, 1.0), 0.3, 0.6) + runs, 0, 1)
        c = flat(((0.30, 0.16, 0.06), (0.35, 0.05, 0.05), (0.22, 0.14, 0.04), (0.45, 0.36, 0.15))[k])
        return c, a * (0.45 + 0.25 * smooth(body, 0.2, 0.9))
    save("M28_DrinkSpills_Color", *quad(spill), note="4 variants (cola, cherry, coffee, beer): splash, droplets and runs; for walls, runs point down")

    def droppings(k):
        body = blob(H, 440 + k * 3, 0.10 + 0.03 * k, 0.6)
        splash = smooth(blur(drops(H, 443 + k, 40, 0.5, 0.5, 0.11, 0.003, 0.014), 0.8), 0.3, 0.6)
        a = np.clip(body + splash, 0, 1)
        dark = smooth(fnoise(H, 1.6, 445 + k), 0.62, 0.75) * body
        c = flat((0.86, 0.86, 0.80)) * (1.0 - dark[..., None] * 0.75)
        return c, a * 0.95
    save("M29_BirdDroppings_Color", *quad(droppings), note="4 splats, white with dark flecks")

    # M31 litter atlas: cigarette butts, bottle caps, paper scraps (4 x 4 cells)
    img = Image.new("RGBA", (N * 2, N * 2), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    r = rng(460)
    cell = N * 2 // 4
    for i in range(16):
        cx, cy = (i % 4 + 0.5) * cell, (i // 4 + 0.5) * cell
        if i < 6:                                              # butts
            tile = Image.new("RGBA", (cell, cell), (0, 0, 0, 0))
            t = ImageDraw.Draw(tile)
            ln = r.uniform(120, 200)
            t.rounded_rectangle((cell / 2 - ln / 2, cell / 2 - 22, cell / 2 + ln / 2, cell / 2 + 22), 10, fill=(222, 214, 196, 255))
            t.rounded_rectangle((cell / 2 - ln / 2, cell / 2 - 22, cell / 2 - ln / 2 + 78, cell / 2 + 22), 10, fill=(196, 132, 64, 255))
            t.ellipse((cell / 2 + ln / 2 - 14, cell / 2 - 22, cell / 2 + ln / 2 + 16, cell / 2 + 22), fill=(40, 36, 32, 255))
            tile = tile.rotate(r.uniform(0, 360), resample=Image.BICUBIC)
            img.alpha_composite(tile, (int(cx - cell / 2), int(cy - cell / 2)))
        elif i < 11:                                           # crown caps, top or underside
            rad = 78
            pts = [(cx + math.cos(a) * rad * (1.0 + 0.07 * math.cos(a * 21)), cy + math.sin(a) * rad * (1.0 + 0.07 * math.cos(a * 21)))
                   for a in np.linspace(0, math.tau, 126)]
            base = ((150, 150, 148), (120, 30, 28), (40, 70, 110), (190, 160, 60), (92, 92, 90))[i - 6]
            d.polygon(pts, fill=base + (255,))
            d.ellipse((cx - 58, cy - 58, cx + 58, cy + 58), fill=tuple(int(v * 0.82) for v in base) + (255,))
        else:                                                  # torn scraps
            pts = []
            for a in np.linspace(0, math.tau, 9, endpoint=False):
                rad = r.uniform(50, 110)
                pts.append((cx + math.cos(a) * rad, cy + math.sin(a) * rad * 0.7))
            tone = int(r.uniform(170, 225))
            d.polygon(pts, fill=(tone, tone - 6, tone - 22, 255))
    img = img.resize((N, N), Image.LANCZOS)
    arr = np.asarray(img, dtype=np.float32) / 255.0
    dirt = 0.7 + 0.45 * fnoise(N, 1.3, 461)
    save("M31_LitterAtlas_Color", arr[..., :3] * dirt[..., None], arr[..., 3], note="4 x 4 cells: 6 cigarette butts, 5 bottle caps (no brands), 5 paper scraps")


# --- the wrong kind of leak ----------------------------------------------------------------
def creature():
    # L17 black oil drag trail: something heavy was pulled through it
    x, y = grid(N)
    centre = 0.5 + (fnoise(N, 3.2, 500)[:, :1] - 0.5) * 0.25 + 0.0 * x
    width = 0.13 + 0.07 * fnoise(N, 2.4, 501)[:, :1]
    band = smooth(1.0 - np.abs(x - centre) / width, 0.0, 0.5)
    profile = fnoise(N, 1.3, 502)[0]                                     # how much oil each strand across the trail carries
    across = np.clip(((x - centre) * 1.6 + 0.5) * (N - 1), 0, N - 1).astype(np.int32)
    streaks = norm01(blur(profile[across] * 0.6 + fnoise(N, 1.6, 503) * 0.4, 1.5))
    a = band * smooth(streaks, 0.3, 0.65) * (0.55 + 0.45 * smooth(fnoise(N, 2.6, 506), 0.25, 0.7))
    a = np.maximum(a, smooth(blur(drops(N, 504, 90, 0.5, 0.5, 0.22, 0.002, 0.012), 1.0), 0.3, 0.6) * 0.9)
    tone = 0.02 + 0.03 * fnoise(N, 1.4, 505)
    save("L17_BlackOilDragTrail_Color", np.dstack([tone * 1.1, tone, tone * 0.9]), a * 0.95, note="runs top to bottom and tiles vertically in feel (not exactly); glossy black")

    # L21 blood drops
    def blood(k):
        if k == 0:       # single falling drops with crown splashes
            a = smooth(blur(drops(H, 510, 9, 0.5, 0.5, 0.2, 0.012, 0.03) + drops(H, 511, 70, 0.5, 0.5, 0.22, 0.001, 0.006), 0.8), 0.3, 0.6)
        elif k == 1:     # a walking trail
            a = np.zeros((H, H), dtype=np.float32)
            for j in range(6):
                a = np.maximum(a, drops(H, 520 + j, 4, 0.2 + j * 0.12, 0.5 + 0.08 * math.sin(j * 1.7), 0.02, 0.006, 0.02))
            a = smooth(blur(a, 0.8), 0.3, 0.6)
        elif k == 2:     # a pool
            a = blob(H, 530, 0.26, 0.35)
        else:            # cast-off: elongated drops in one direction
            a = np.zeros((H, H), dtype=np.float32)
            r = rng(540)
            for _ in range(26):
                cx, cy, ln = r.uniform(0.15, 0.85), r.uniform(0.2, 0.8), r.uniform(0.02, 0.09)
                a = np.maximum(a, smooth(1.0 - radial(H, cx, cy, ln, ln * 0.18), 0.0, 0.3))
        dry = 0.6 + 0.4 * fnoise(H, 1.5, 545 + k)
        c = flat((0.30, 0.012, 0.012)) * dry[..., None] * (0.55 + 0.45 * (1.0 - smooth(a, 0.5, 1.0))[..., None])      # darker where thick
        return c, a * 0.95
    save("L21_BloodDrops_Color", *quad(blood), note="4 variants: drops, a walking trail, a pool, cast-off")

    # L37 black oil seeping out of a wall
    x, y = grid(N)
    r = rng(560)
    a = np.zeros((N, N), dtype=np.float32)
    seam = 0.12 + (fnoise(N, 3.0, 561)[:1, :] - 0.5) * 0.06
    a = np.maximum(a, smooth(1.0 - np.abs(y - seam) / 0.022, 0.0, 0.6))
    for _ in range(26):
        rx, ln, w = r.uniform(0.03, 0.97), r.uniform(0.1, 0.8), r.uniform(0.004, 0.014)
        wob = rx + (fnoise(N, 3.0, 562 + _)[:, :1] - 0.5) * 0.03
        run = smooth(1.0 - np.abs(x - wob) / w, 0.0, 0.6) * smooth(y, 0.10, 0.14) * (1.0 - smooth(y, 0.12 + ln * 0.85, 0.13 + ln))
        bead = smooth(1.0 - radial(N, rx, 0.125 + ln, w * 1.9, w * 2.6), 0.0, 0.4)
        a = np.maximum(a, np.maximum(run, bead))
    tone = 0.02 + 0.025 * fnoise(N, 1.2, 590)
    save("L37_BlackOilSeep_Color", np.dstack([tone * 1.1, tone, tone * 0.9]), a * 0.96, note="a seam near the top bleeding runs of different lengths; glossy black")

    # M24 cable drag marks: paired thin scuffs
    a = np.zeros((N, N), dtype=np.float32)
    for k in range(5):
        cy = 0.1 + k * 0.2 + (fnoise(N, 3.0, 600 + k)[:1, :] - 0.5) * 0.12
        for off in (-0.006, 0.006):
            line = smooth(1.0 - np.abs(y - cy - off) / 0.0035, 0.0, 0.7)
            a = np.maximum(a, line * (0.35 + 0.65 * smooth(fnoise(N, 1.3, 610 + k), 0.3, 0.7)))
    save("M24_CableDragMarks_Color", np.ones((N, N, 3), dtype=np.float32) * 0.06, a * 0.8, note="5 wandering double scuffs, left to right")

    # K40 dents in sheet metal: normal map + mask
    r = rng(620)
    h = np.zeros((N, N), dtype=np.float32)
    for _ in range(11):
        cx, cy, s = r.uniform(0.1, 0.9), r.uniform(0.1, 0.9), r.uniform(0.03, 0.11)
        e = r.uniform(0.6, 1.6)
        h -= np.exp(-(radial(N, cx, cy, s * e, s / e) ** 2) * 2.5) * r.uniform(0.5, 1.0)
    for _ in range(4):                                         # creases
        cx, cy = r.uniform(0.2, 0.8), r.uniform(0.2, 0.8)
        h -= np.exp(-(radial(N, cx, cy, 0.16, 0.012) ** 2) * 2.0) * 0.6
    save("K40_CasingDents_Normal", normal_from_height(blur(norm01(h), 1.5), 120.0), note="11 dents and 4 creases; OpenGL normal")
    save("K40_CasingDents_Mask", grey=1.0 - norm01(h), note="depth of the dents, white = deepest (for chipped paint and dirt)")

    # L06 / L35 transition masks: where flesh takes over
    x, y = grid(N)
    organic = fnoise(N, 2.4, 640) * 0.6 + fnoise(N, 1.2, 641) * 0.4
    veins = 1.0 - np.clip(np.abs(fnoise(N, 2.0, 642) - 0.5) / 0.03, 0, 1)
    save("L06_SkinMetalTransition_Mask", grey=np.clip(smooth(x + (organic - 0.5) * 0.9, 0.35, 0.65) + veins * smooth(x, 0.25, 0.5) * 0.5, 0, 1),
         note="left = metal, right = skin; fingers of skin reach along thin veins")
    d = radial(N)
    save("L35_GrowthEdgeFalloff_Mask", grey=smooth(1.0 - d / 0.5 + (organic - 0.5) * 0.7 + veins * 0.15, 0.0, 0.5),
         note="white core, ragged tendrils fading out; multiply over the growth material")


def main():
    for part in (ground, interiors, walls, creature):
        part()
    seen = {}
    for name, note in MADE:
        seen.setdefault(name, note)
    with open(os.path.join(OUT, "sources.csv"), "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["set", "source", "asset", "url", "license", "note"])
        for name in sorted(seen):
            w.writerow([name, "procedural", "art/textures/gen_decals.py", "", "original", seen[name]])
    print(f"{len(MADE)} images, {len(seen)} sets -> {OUT}")


if __name__ == "__main__":
    main()
