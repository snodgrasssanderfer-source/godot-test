#!/usr/bin/env python3
"""Generates every texture used by RUSTNIGHT (low-res, grimy, nearest-filtered).

Usage: python3 art/rustnight/gen_textures.py
Output: rustnight/tex/*.png   (deterministic: fixed seeds)
"""
import os
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "rustnight", "tex")
FONT = os.path.join(ROOT, "rustnight", "fonts", "pixel.ttf")
os.makedirs(OUT, exist_ok=True)


def rng(seed):
    return np.random.default_rng(seed)


def vnoise(n, cells, seed):
    """Tileable value noise, n x n, 'cells' lattice cells per side."""
    r = rng(seed)
    g = r.random((cells, cells))
    xs = np.arange(n) * cells / n
    x0 = np.floor(xs).astype(int)
    t = xs - x0
    t = t * t * (3 - 2 * t)
    x1 = (x0 + 1) % cells
    a = g[np.ix_(x0, x0)]
    b = g[np.ix_(x0, x1)]
    c = g[np.ix_(x1, x0)]
    d = g[np.ix_(x1, x1)]
    tx = t[None, :]
    ty = t[:, None]
    return (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty


def fbm(n, seed, octaves=4, base=4):
    out = np.zeros((n, n))
    amp, tot = 1.0, 0.0
    for o in range(octaves):
        out += vnoise(n, min(n, base * 2 ** o), seed + o * 17) * amp
        tot += amp
        amp *= 0.5
    return out / tot


def grain(n, seed, amt):
    return (rng(seed).random((n, n)) - 0.5) * amt


def colorize(v, lo, hi):
    """Map a 0..1 field to a colour ramp between two RGB tuples."""
    v = np.clip(v, 0, 1)[..., None]
    return np.array(lo)[None, None, :] * (1 - v) + np.array(hi)[None, None, :] * v


def stains(n, seed, strength=0.35):
    """Dark blotchy grime multiplier (1 = clean)."""
    f = fbm(n, seed, 3, 2)
    return 1 - np.clip((f - 0.45) * 3, 0, 1) * strength


def drips(n, seed, count=6, strength=0.3):
    r = rng(seed)
    m = np.ones((n, n))
    for _ in range(count):
        x = r.integers(0, n)
        y0 = r.integers(0, n // 2)
        ln = r.integers(n // 6, n // 2)
        for i in range(ln):
            m[(y0 + i) % n, x] *= 1 - strength * (1 - i / ln)
    return m


def save(name, rgb, alpha=None):
    rgb = np.clip(rgb, 0, 255).astype(np.uint8)
    if alpha is not None:
        a = np.clip(alpha, 0, 255).astype(np.uint8)
        img = Image.fromarray(np.dstack([rgb, a]), "RGBA")
    else:
        img = Image.fromarray(rgb, "RGB")
    img.save(os.path.join(OUT, name + ".png"))


def quant(rgb, levels=24):
    """Posterise a little so everything shares the same crunchy look."""
    step = 255.0 / levels
    return np.round(rgb / step) * step


# --- ground ----------------------------------------------------------------
def asphalt():
    n = 64
    v = fbm(n, 1, 4, 4) * 0.5 + rng(2).random((n, n)) * 0.5
    c = colorize(v, (34, 34, 36), (70, 69, 68))
    r = rng(3)
    for _ in range(3):  # cracks
        x, y = r.integers(0, n, 2)
        for _ in range(40):
            c[y % n, x % n] *= 0.45
            x += r.integers(-1, 2)
            y += 1 if r.random() < 0.7 else 0
    save("asphalt", quant(c * stains(n, 4, 0.3)[..., None]))


def sidewalk():
    n = 64
    v = fbm(n, 10, 3, 8) * 0.6 + rng(11).random((n, n)) * 0.4
    c = colorize(v, (88, 86, 80), (128, 125, 116))
    c[::32, :] *= 0.5
    c[:, ::32] *= 0.5
    c[1::32, :] *= 0.8
    save("sidewalk", quant(c * stains(n, 12, 0.4)[..., None]))


def grass():
    n = 64
    v = fbm(n, 20, 4, 4)
    c = colorize(v, (30, 40, 24), (64, 72, 40))
    d = fbm(n, 21, 2, 2)
    dirt = colorize(v, (52, 44, 32), (78, 66, 48))
    m = np.clip((d - 0.58) * 6, 0, 1)[..., None]
    c = c * (1 - m) + dirt * m
    c += grain(n, 22, 26)[..., None]
    save("grass", quant(c))


def dirt():
    n = 64
    v = fbm(n, 30, 4, 4) * 0.7 + rng(31).random((n, n)) * 0.3
    save("dirt", quant(colorize(v, (48, 40, 30), (92, 78, 58))))


# --- walls -------------------------------------------------------------------
def brick(name, seed, lo, hi, mortar):
    n = 64
    c = np.zeros((n, n, 3))
    r = rng(seed)
    bh, bw = 8, 16
    for row in range(n // bh):
        off = (bw // 2) if row % 2 else 0
        for col in range(n // bw + 1):
            tone = r.random()
            col_rgb = np.array(lo) * (1 - tone) + np.array(hi) * tone
            x0 = col * bw - off
            for y in range(row * bh, row * bh + bh - 1):
                for x in range(x0, x0 + bw - 1):
                    c[y, x % n] = col_rgb
    mask = c.sum(axis=2) == 0
    c[mask] = mortar
    c += grain(n, seed + 1, 22)[..., None]
    c *= (stains(n, seed + 2, 0.45) * drips(n, seed + 3, 5, 0.25))[..., None]
    save(name, quant(c))


def siding():
    n = 64
    v = fbm(n, 50, 3, 8)
    c = colorize(v, (150, 150, 142), (188, 186, 176))
    for y in range(n):
        k = y % 8
        if k == 0:
            c[y] *= 0.5
        elif k == 1:
            c[y] *= 0.8
        elif k == 7:
            c[y] *= 1.06
    c += grain(n, 51, 14)[..., None]
    c *= (stains(n, 52, 0.4) * drips(n, 53, 8, 0.3))[..., None]
    save("siding", quant(c))


def concrete(name, seed, lo, hi, panel=False):
    n = 64
    v = fbm(n, seed, 4, 4) * 0.7 + rng(seed + 1).random((n, n)) * 0.3
    c = colorize(v, lo, hi)
    if panel:
        c[:, ::32] *= 0.6
        c[::64, :] *= 0.6
    c *= (stains(n, seed + 2, 0.45) * drips(n, seed + 3, 7, 0.3))[..., None]
    save(name, quant(c))


def plaster():
    n = 64
    v = fbm(n, 70, 3, 4)
    c = colorize(v, (164, 160, 146), (198, 194, 178))
    c += grain(n, 71, 10)[..., None]
    c *= (stains(n, 72, 0.5) * drips(n, 73, 4, 0.2))[..., None]
    c[-6:, :] *= 0.55   # skirting board
    c[-7, :] *= 0.75
    save("plaster", quant(c))


def wallpaper():
    n = 64
    c = np.zeros((n, n, 3))
    for x in range(n):
        k = x % 16
        base = (132, 120, 92) if k < 8 else (116, 104, 80)
        c[:, x] = base
    r = rng(80)
    for y in range(4, n, 16):
        for x in range(4, n, 16):
            c[y, x] = (90, 60, 52)
            c[y - 1, x] = c[y + 1, x] = c[y, x - 1] = c[y, x + 1] = (104, 76, 60)
    c += grain(n, 81, 12)[..., None]
    c *= (stains(n, 82, 0.55) * drips(n, 83, 6, 0.3))[..., None]
    c[-6:, :] = (58, 44, 34)
    c[-6:, :] += grain(n, 84, 10)[-6:, :, None]
    save("wallpaper", quant(c))


def tiles_wall():
    n = 64
    v = fbm(n, 90, 2, 4)
    c = colorize(v, (150, 162, 158), (190, 198, 192))
    c[::8, :] *= 0.6
    c[:, ::8] *= 0.6
    c *= (stains(n, 91, 0.5) * drips(n, 92, 8, 0.35))[..., None]
    save("tiles_wall", quant(c))


# --- floors / ceilings ---------------------------------------------------------
def wood_floor(name, seed, lo, hi, plank=8):
    n = 64
    c = np.zeros((n, n, 3))
    r = rng(seed)
    fib = fbm(n, seed + 1, 3, 16)
    for p in range(n // plank):
        tone = r.random()
        off = r.integers(0, n)
        for x in range(p * plank, (p + 1) * plank):
            col = np.array(lo) * (1 - tone) + np.array(hi) * tone
            c[:, x] = col
            if x % plank == 0:
                c[:, x] *= 0.55
        c[off % n, p * plank:(p + 1) * plank] *= 0.55
        c[(off + 37) % n, p * plank:(p + 1) * plank] *= 0.55
    c *= (0.85 + fib * 0.3)[..., None]
    c += grain(n, seed + 2, 12)[..., None]
    c *= stains(n, seed + 3, 0.4)[..., None]
    save(name, quant(c))


def tile_floor():
    n = 64
    c = np.zeros((n, n, 3))
    for y in range(n):
        for x in range(n):
            a = ((x // 16) + (y // 16)) % 2
            c[y, x] = (136, 134, 124) if a else (72, 76, 74)
    c[::16, :] *= 0.6
    c[:, ::16] *= 0.6
    c += grain(n, 100, 14)[..., None]
    c *= stains(n, 101, 0.5)[..., None]
    save("tile_floor", quant(c))


def carpet():
    n = 64
    v = rng(110).random((n, n)) * 0.6 + fbm(n, 111, 3, 4) * 0.4
    c = colorize(v, (56, 40, 40), (88, 62, 58))
    c *= stains(n, 112, 0.5)[..., None]
    save("carpet", quant(c))


def ceiling():
    n = 64
    v = fbm(n, 120, 3, 8)
    c = colorize(v, (128, 126, 116), (160, 158, 146))
    c[::32, :] *= 0.7
    c[:, ::32] *= 0.7
    c += grain(n, 121, 12)[..., None]
    c *= stains(n, 122, 0.6)[..., None]
    save("ceiling", quant(c))


def roof():
    n = 64
    c = np.zeros((n, n, 3))
    r = rng(130)
    for row in range(8):
        off = 4 if row % 2 else 0
        for col in range(9):
            tone = r.random()
            col_rgb = np.array((44, 40, 40)) * (1 - tone) + np.array((70, 62, 58)) * tone
            for y in range(row * 8, row * 8 + 8):
                for x in range(col * 8 - off, col * 8 - off + 8):
                    sh = 0.6 if y % 8 == 0 else (0.75 if (x + off) % 8 == 0 else 1)
                    c[y, x % n] = col_rgb * sh
    c += grain(n, 131, 14)[..., None]
    c *= stains(n, 132, 0.35)[..., None]
    save("roof", quant(c))


def metal(name, seed, lo, hi, rust=0.5, ribs=0):
    n = 64
    v = fbm(n, seed, 3, 8) * 0.5 + rng(seed + 1).random((n, n)) * 0.2 + 0.2
    c = colorize(v, lo, hi)
    if ribs:
        for x in range(n):
            k = x % ribs
            c[:, x] *= 0.7 if k == 0 else (1.1 if k == ribs // 2 else 1)
    f = fbm(n, seed + 2, 3, 4)
    m = np.clip((f - (1 - rust * 0.6)) * 6, 0, 1)[..., None]
    rustc = colorize(fbm(n, seed + 3, 3, 8), (70, 36, 20), (122, 66, 34))
    c = c * (1 - m) + rustc * m
    c *= drips(n, seed + 4, 6, 0.25)[..., None]
    save(name, quant(c))


def fabric(name, seed, lo, hi):
    n = 32
    v = rng(seed).random((n, n)) * 0.5 + fbm(n, seed + 1, 2, 4) * 0.5
    c = colorize(v, lo, hi)
    c[::4, :] *= 0.92
    c *= stains(n, seed + 2, 0.5)[..., None]
    save(name, quant(c))


def appliance():
    n = 32
    v = fbm(n, 150, 2, 4)
    c = colorize(v, (168, 168, 160), (204, 204, 194))
    c *= (stains(n, 151, 0.4) * drips(n, 152, 4, 0.3))[..., None]
    c[14:16, 2:-2] *= 0.6     # door seam
    c[4:12, -6:-4] *= 0.5     # handle
    save("appliance", quant(c))


def door():
    w, h = 32, 64
    v = fbm(64, 160, 3, 8)[:h, :w]
    c = colorize(v, (70, 50, 34), (102, 74, 48))
    for (x0, y0, x1, y1) in ((4, 4, 28, 28), (4, 34, 28, 60)):
        c[y0:y1, x0:x1] *= 0.82
        c[y0, x0:x1] *= 0.6
        c[y0:y1, x0] *= 0.6
        c[y1 - 1, x0:x1] *= 1.15
    c[30:33, 25:28] = (150, 140, 90)  # knob
    c += grain(64, 161, 12)[:h, :w, None]
    c *= stains(64, 162, 0.4)[:h, :w, None]
    save("door", quant(c))


def metal_door():
    w, h = 32, 64
    v = fbm(64, 165, 3, 8)[:h, :w]
    c = colorize(v, (62, 70, 72), (92, 100, 100))
    c[2:-2, 2] *= 0.6
    c[2:-2, -3] *= 0.6
    c[10:22, 8:24] = (30, 34, 36)   # little wired window
    c[10:22:3, 8:24] = (60, 66, 66)
    c[30:34, 24:28] = (150, 150, 140)
    c *= (stains(64, 166, 0.4) * drips(64, 167, 6, 0.3))[:h, :w, None]
    save("metal_door", quant(c))


def glass():
    n = 32
    v = fbm(n, 170, 3, 4)
    c = colorize(v, (96, 112, 112), (150, 164, 160))
    a = 60 + fbm(n, 171, 3, 4) * 70 + (1 - drips(n, 172, 8, 0.5)) * 160
    a[:1, :] = a[-1:, :] = 255
    a[:, :1] = a[:, -1:] = 255
    a[:, n // 2] = 255
    c[:, n // 2] = (60, 52, 44)
    c[:1, :] = c[-1:, :] = (60, 52, 44)
    c[:, :1] = c[:, -1:] = (60, 52, 44)
    save("glass", quant(c), a)


def glass_broken():
    n = 32
    r = rng(175)
    c = np.zeros((n, n, 3)) + (130, 146, 144)
    a = np.zeros((n, n))
    for x in range(n):   # jagged shards left in the frame
        top = r.integers(0, 7)
        bot = r.integers(0, 9)
        a[:top, x] = 150
        a[n - bot:, x] = 150
    for y in range(n):
        le = r.integers(0, 5)
        ri = r.integers(0, 5)
        a[y, :le] = 150
        a[y, n - ri:] = 150
    a[:1, :] = a[-1:, :] = 255
    a[:, :1] = a[:, -1:] = 255
    c[:1, :] = c[-1:, :] = (60, 52, 44)
    c[:, :1] = c[:, -1:] = (60, 52, 44)
    save("glass_broken", c, a)


def planks():
    n = 64
    c = np.zeros((n, n, 3))
    a = np.zeros((n, n))
    r = rng(180)
    fib = fbm(n, 181, 3, 16)
    for i, y0 in enumerate((4, 24, 44)):
        tone = r.random()
        col = np.array((84, 62, 40)) * (1 - tone) + np.array((120, 92, 60)) * tone
        sl = r.integers(-2, 3)
        for x in range(n):
            yy = y0 + int(sl * x / n)
            c[yy:yy + 13, x] = col * (0.8 + fib[yy, x] * 0.4)
            a[yy:yy + 13, x] = 255
            c[yy, x] *= 0.6
        for nx in (3, n - 5):
            c[y0 + 5:y0 + 7, nx:nx + 2] = (40, 40, 44)
    save("planks", quant(c), a)


def shelf_goods():
    n = 64
    r = rng(190)
    c = np.zeros((n, n, 3)) + (50, 46, 42)
    pal = [(120, 44, 40), (52, 84, 110), (134, 120, 56), (70, 100, 60), (140, 132, 120), (94, 60, 100), (150, 90, 40)]
    for row in range(4):
        y0 = row * 16
        c[y0 + 14:y0 + 16, :] = (88, 82, 72)   # shelf board
        x = 1
        while x < n - 3:
            w = r.integers(3, 7)
            h = r.integers(6, 13)
            if r.random() < 0.3:   # looted gap
                x += w + 1
                continue
            col = np.array(pal[r.integers(0, len(pal))]) * (0.7 + r.random() * 0.4)
            c[y0 + 14 - h:y0 + 14, x:x + w] = col
            c[y0 + 14 - h + 2:y0 + 14 - h + 4, x:x + w] = col * 1.4
            x += w + 1
    c += grain(n, 191, 12)[..., None]
    c *= stains(n, 192, 0.35)[..., None]
    save("shelf_goods", quant(c))


def shelf_empty():
    n = 64
    c = np.zeros((n, n, 3)) + (44, 40, 36)
    for row in range(4):
        c[row * 16 + 14:row * 16 + 16, :] = (88, 82, 72)
    c += grain(n, 195, 12)[..., None]
    c *= stains(n, 196, 0.45)[..., None]
    save("shelf_empty", quant(c))


def paint(name, col, seed):
    n = 32
    v = fbm(n, seed, 3, 4)
    c = colorize(v, [k * 0.7 for k in col], col)
    wear = rng(seed + 1).random((n, n))
    a = np.where(wear < 0.18, 0, 255)
    save(name, quant(c), a)


# --- decals / fx --------------------------------------------------------------
def blood(i):
    n = 64
    r = rng(200 + i)
    yy, xx = np.mgrid[0:n, 0:n]
    a = np.zeros((n, n))
    for _ in range(r.integers(7, 14)):
        cx, cy = r.normal(n / 2, n / 7, 2)
        rad = r.uniform(3, 11)
        d = np.hypot(xx - cx, yy - cy)
        a = np.maximum(a, np.clip((rad - d) / 2, 0, 1))
    for _ in range(26):
        cx, cy = r.normal(n / 2, n / 4, 2)
        d = np.hypot(xx - cx, yy - cy)
        a = np.maximum(a, np.clip(r.uniform(0.6, 2.2) - d, 0, 1))
    v = fbm(n, 210 + i, 3, 8)
    c = colorize(v, (52, 4, 4), (112, 12, 10))
    save("blood%d" % i, quant(c), np.where(a > 0.4, 235, 0))


def muzzle():
    n = 64
    yy, xx = np.mgrid[0:n, 0:n] - n / 2 + 0.5
    ang = np.arctan2(yy, xx)
    d = np.hypot(xx, yy)
    r = rng(220)
    spikes = np.zeros_like(d)
    for k in range(7):
        a0 = r.uniform(0, 2 * np.pi)
        ln = r.uniform(14, 30)
        w = r.uniform(0.12, 0.3)
        da = np.abs(np.angle(np.exp(1j * (ang - a0))))
        spikes = np.maximum(spikes, np.clip(1 - da / w, 0, 1) * np.clip(1 - d / ln, 0, 1))
    core = np.clip(1 - d / 10, 0, 1)
    v = np.clip(spikes + core * 1.5, 0, 1)
    c = colorize(v, (255, 120, 20), (255, 248, 200))
    save("muzzle", c, np.where(v > 0.18, 255, 0))


def puff():
    n = 32
    yy, xx = np.mgrid[0:n, 0:n] - n / 2 + 0.5
    d = np.hypot(xx, yy) / (n / 2)
    v = fbm(n, 230, 3, 4)
    a = np.clip((1 - d) * 1.6 - v * 0.7, 0, 1)
    c = colorize(v, (120, 116, 108), (190, 186, 176))
    save("puff", quant(c), np.where(a > 0.3, 200, 0))


def bullet_hole():
    n = 16
    yy, xx = np.mgrid[0:n, 0:n] - n / 2 + 0.5
    d = np.hypot(xx, yy)
    c = np.zeros((n, n, 3)) + 12
    a = np.where(d < 3.2 + rng(240).random((n, n)) * 2.4, 230, 0)
    save("bullet_hole", c, a)


def rain():
    c = np.zeros((16, 4, 3)) + (170, 180, 190)
    a = np.zeros((16, 4))
    a[:, 1:3] = 110
    save("rain", c, a)


def figure():
    """Tall dark silhouette used by the hallucination billboards."""
    w, h = 32, 64
    a = np.zeros((h, w))
    c = np.zeros((h, w, 3)) + (6, 6, 8)
    yy, xx = np.mgrid[0:h, 0:w]
    a[np.hypot(xx - 15.5, (yy - 7) * 0.9) < 4.6] = 255       # head
    a[(yy > 11) & (yy < 40) & (np.abs(xx - 15.5) < 6 - (yy - 11) * 0.03)] = 255   # torso
    a[(yy > 12) & (yy < 44) & (np.abs(np.abs(xx - 15.5) - 7.5) < 1.4)] = 255      # long arms
    a[(yy >= 40) & (np.abs(np.abs(xx - 15.5) - 3) < 2.2)] = 255                   # legs
    c[6:8, 13:15] = c[6:8, 17:19] = (190, 190, 170)   # eyes
    save("figure", c, a)


# --- signs (text rendered with the bundled pixel font) -------------------------
def sign(name, text, fg, bg, seed, w=128, h=32):
    img = Image.new("RGB", (w, h), bg)
    d = ImageDraw.Draw(img)
    font = ImageFont.truetype(FONT, 24)
    bb = d.textbbox((0, 0), text, font=font)
    d.text(((w - (bb[2] - bb[0])) // 2 - bb[0], (h - (bb[3] - bb[1])) // 2 - bb[1]), text, font=font, fill=fg)
    d.rectangle((0, 0, w - 1, h - 1), outline=tuple(int(k * 0.5) for k in bg))
    c = np.array(img).astype(float)
    big = max(w, h)
    c *= (stains(big, seed, 0.5) * drips(big, seed + 1, 10, 0.35))[:h, :w, None]
    c += grain(big, seed + 2, 16)[:h, :w, None]
    save(name, quant(c))


# --- atlases for the Blender-built models (8x8 cells of 16 px) -----------------
def cell_fill(c, col, row, lo, hi, seed, fn=None):
    v = fbm(16, seed, 2, 4) * 0.6 + rng(seed + 1).random((16, 16)) * 0.4
    blk = colorize(v, lo, hi)
    if fn:
        fn(blk)
    c[row * 16:(row + 1) * 16, col * 16:(col + 1) * 16] = blk


def zombie_atlas(i, skin, shirt, pants, hair):
    """Cells: (0,0) skin (1,0) face (2,0) hair (3,0) shirt (4,0) pants (5,0) shoes
    (6,0) blood/wound (7,0) shirt-bloody (0,1) face-back/skull"""
    c = np.zeros((128, 128, 3))
    s = 300 + i * 40
    lo = lambda col, k=0.62: tuple(x * k for x in col)
    cell_fill(c, 0, 0, lo(skin), skin, s)

    def face(b):
        b[5:7, 3:6] = (14, 10, 10)
        b[5:7, 10:13] = (14, 10, 10)
        b[5, 4] = b[5, 11] = (200, 196, 170) if i != 2 else (190, 40, 30)
        b[8:10, 7:9] *= 0.7
        b[11:14, 4:12] = (40, 8, 8)
        b[12, 5:11:2] = (190, 180, 150)
        b[13:16, 6:8] = (78, 10, 8)
        b[2:4, 2:7] *= 0.75
        b[2:4, 9:14] *= 0.75
    cell_fill(c, 1, 0, lo(skin), skin, s + 2, face)
    cell_fill(c, 2, 0, lo(hair), hair, s + 4)
    cell_fill(c, 3, 0, lo(shirt), shirt, s + 6)
    cell_fill(c, 4, 0, lo(pants), pants, s + 8)
    cell_fill(c, 5, 0, (16, 14, 12), (40, 36, 32), s + 10)
    cell_fill(c, 6, 0, (54, 4, 4), (120, 14, 10), s + 12)

    def bloody(b):
        m = fbm(16, s + 15, 2, 4) > 0.5
        b[m] = b[m] * 0.25 + np.array((92, 8, 6)) * 0.75
    cell_fill(c, 7, 0, lo(shirt), shirt, s + 14, bloody)
    cell_fill(c, 0, 1, lo(skin, 0.5), lo(skin, 0.85), s + 16)
    save("zombie%d" % i, quant(c))


def weapons_atlas():
    """Cells: (0,0) gunmetal (1,0) black polymer (2,0) wood (3,0) worn steel
    (4,0) sleeve (5,0) glove (6,0) brass (7,0) red paint (0,1) rust iron (1,1) skin
    (2,1) dark tape (3,1) sight-white"""
    c = np.zeros((128, 128, 3))
    cell_fill(c, 0, 0, (30, 32, 36), (64, 66, 72), 400)
    cell_fill(c, 1, 0, (14, 14, 16), (34, 34, 38), 402)

    def woodgrain(b):
        b[:, ::3] *= 0.86
    cell_fill(c, 2, 0, (64, 38, 20), (112, 70, 38), 404, woodgrain)
    cell_fill(c, 3, 0, (72, 74, 76), (128, 130, 130), 406)
    cell_fill(c, 4, 0, (30, 34, 28), (58, 64, 50), 408)
    cell_fill(c, 5, 0, (18, 18, 18), (42, 40, 38), 410)
    cell_fill(c, 6, 0, (120, 92, 30), (190, 150, 60), 412)
    cell_fill(c, 7, 0, (92, 16, 12), (150, 30, 22), 414)
    cell_fill(c, 0, 1, (56, 34, 22), (104, 70, 48), 416)
    cell_fill(c, 1, 1, (120, 92, 76), (170, 134, 110), 418)
    cell_fill(c, 2, 1, (20, 20, 24), (30, 30, 34), 420)
    cell_fill(c, 3, 1, (200, 200, 190), (235, 235, 220), 422)
    save("weapons", quant(c))


def props_atlas(i, paintc):
    """Cells: (0,0) car paint (1,0) car glass (2,0) tyre (3,0) chrome/steel
    (4,0) bark (5,0) pine needles (6,0) lamp glass (7,0) rust (0,1) stone
    (1,1) dead leaves (2,1) headlight (3,1) taillight (4,1) dark metal (5,1) green paint
    (6,1) sandbag (7,1) olive drab"""
    c = np.zeros((128, 128, 3))
    s = 500 + i * 30
    lo = lambda col, k=0.6: tuple(x * k for x in col)

    def rusty(b):
        m = fbm(16, s + 1, 2, 4) > 0.62
        b[m] = (86, 46, 26)
    cell_fill(c, 0, 0, lo(paintc), paintc, s, rusty)
    cell_fill(c, 1, 0, (16, 22, 26), (40, 50, 54), s + 2)
    cell_fill(c, 2, 0, (10, 10, 10), (30, 30, 30), s + 4)
    cell_fill(c, 3, 0, (80, 84, 86), (140, 144, 144), s + 6)

    def bark(b):
        b[:, ::4] *= 0.7
    cell_fill(c, 4, 0, (34, 26, 18), (66, 52, 36), s + 8, bark)
    cell_fill(c, 5, 0, (14, 28, 18), (38, 60, 34), s + 10)
    cell_fill(c, 6, 0, (200, 180, 120), (255, 236, 170), s + 12)
    cell_fill(c, 7, 0, (60, 32, 18), (118, 66, 34), s + 14)
    cell_fill(c, 0, 1, (70, 72, 70), (120, 120, 112), s + 16)
    cell_fill(c, 1, 1, (50, 40, 22), (90, 70, 36), s + 18)
    cell_fill(c, 2, 1, (170, 170, 150), (230, 230, 200), s + 20)
    cell_fill(c, 3, 1, (90, 10, 8), (160, 24, 16), s + 22)
    cell_fill(c, 4, 1, (20, 22, 24), (48, 50, 52), s + 24)
    cell_fill(c, 5, 1, (26, 50, 34), (50, 86, 58), s + 26)
    cell_fill(c, 6, 1, (92, 84, 60), (140, 128, 92), s + 28)
    cell_fill(c, 7, 1, (44, 50, 30), (76, 84, 52), s + 30)
    save("props%d" % i, quant(c))


def main():
    asphalt(); sidewalk(); grass(); dirt()
    brick("brick", 40, (84, 40, 32), (132, 66, 50), (112, 106, 96))
    brick("brick_dark", 44, (52, 46, 44), (90, 80, 74), (70, 68, 64))
    siding()
    concrete("concrete", 60, (86, 86, 82), (132, 130, 122), panel=True)
    concrete("concrete_floor", 64, (64, 64, 62), (100, 98, 94))
    plaster(); wallpaper(); tiles_wall()
    wood_floor("wood_floor", 94, (74, 50, 30), (112, 80, 48))
    wood_floor("wood", 97, (50, 34, 22), (84, 58, 36), plank=16)
    tile_floor(); carpet(); ceiling(); roof()
    metal("metal", 140, (70, 74, 76), (116, 120, 120), rust=0.5)
    metal("metal_ribbed", 145, (76, 82, 84), (120, 126, 124), rust=0.7, ribs=8)
    metal("metal_green", 148, (36, 58, 44), (60, 88, 66), rust=0.6)
    metal("metal_dark", 149, (24, 26, 28), (52, 54, 56), rust=0.3)
    fabric("fabric", 154, (60, 70, 54), (96, 106, 82))
    fabric("fabric_red", 156, (84, 36, 34), (128, 58, 52))
    fabric("mattress", 158, (132, 128, 110), (176, 170, 150))
    appliance(); door(); metal_door(); glass(); glass_broken(); planks()
    shelf_goods(); shelf_empty()
    paint("paint_yellow", (170, 140, 40), 185)
    paint("paint_white", (170, 170, 160), 187)
    for i in range(3):
        blood(i)
    muzzle(); puff(); bullet_hole(); rain(); figure()
    sign("sign_store", "便利店 24H", (230, 220, 190), (120, 30, 26), 250)
    sign("sign_diner", "老乔酒馆", (240, 200, 110), (40, 36, 60), 254)
    sign("sign_hardware", "五金 工具", (30, 30, 30), (170, 140, 40), 258)
    sign("sign_police", "警察局", (220, 220, 230), (26, 40, 80), 262)
    sign("sign_clinic", "镇卫生所", (180, 30, 30), (200, 200, 190), 266)
    sign("sign_gas", "加油站", (240, 240, 230), (150, 40, 20), 270)
    sign("sign_garage", "汽修厂", (230, 230, 220), (50, 60, 70), 274)
    sign("sign_exit", "道路封闭 禁止通行", (20, 20, 20), (190, 130, 30), 278, w=256)
    zombie_atlas(0, (124, 132, 104), (86, 90, 100), (50, 54, 70), (38, 30, 24))
    zombie_atlas(1, (134, 126, 108), (110, 60, 52), (60, 52, 40), (20, 18, 18))
    zombie_atlas(2, (150, 150, 140), (150, 146, 136), (44, 44, 48), (60, 54, 46))   # screamer: pale
    zombie_atlas(3, (112, 124, 100), (70, 92, 70), (38, 40, 44), (84, 70, 48))
    weapons_atlas()
    props_atlas(0, (96, 44, 38))
    props_atlas(1, (52, 70, 92))
    props_atlas(2, (120, 118, 108))
    print("textures written to", OUT, len(os.listdir(OUT)))


if __name__ == "__main__":
    main()
