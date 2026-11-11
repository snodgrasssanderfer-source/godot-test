#!/usr/bin/env python3
"""Generates the texture-list items marked "程" (procedural) that are image files.

Usage:  python3 art/textures/gen_procedural.py          (needs numpy + Pillow)
Output: fps/assets/textures/proc/*.png and sources.csv   (deterministic)

File names follow docs/texture_delivery.md: {id}{variant}_{Name}_{Channel}.png
Everything tileable is built in the frequency domain, so it wraps seamlessly.
Items of the list that are shaders, real-time reflections or meshes are not
produced here; they are listed at the bottom of sources.csv as "engine".
"""
import csv
import math
import os

import numpy as np
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "fps", "assets", "textures", "proc")
os.makedirs(OUT, exist_ok=True)
MADE = []


def rng(seed):
    return np.random.default_rng(seed)


def norm01(a):
    a = a - a.min()
    return a / (a.max() + 1e-12)


def fnoise(n, beta, seed, aniso=(1.0, 1.0), m=None):
    """Tileable 1/f^beta noise, 0..1. aniso stretches it along x / y."""
    m = m or n
    fy = np.fft.fftfreq(m)[:, None] * aniso[1]
    fx = np.fft.fftfreq(n)[None, :] * aniso[0]
    f = np.sqrt(fx * fx + fy * fy)
    f[0, 0] = 1.0
    spec = np.fft.fft2(rng(seed).standard_normal((m, n))) / f ** (beta / 2.0)
    spec[0, 0] = 0.0
    return norm01(np.real(np.fft.ifft2(spec)))


def blur(a, sigma):
    """Gaussian blur that wraps (keeps tileable things tileable)."""
    if sigma <= 0:
        return a
    fy = np.fft.fftfreq(a.shape[0])[:, None]
    fx = np.fft.fftfreq(a.shape[1])[None, :]
    k = np.exp(-2.0 * (math.pi * sigma) ** 2 * (fx * fx + fy * fy))
    return np.real(np.fft.ifft2(np.fft.fft2(a) * k))


def worley(n, count, seed, second=False):
    """Tileable distance to the nearest (or second nearest minus nearest) feature point."""
    r = rng(seed)
    pts = r.random((count, 2)) * n
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32)
    d1 = np.full((n, n), 1e9, np.float32)
    d2 = np.full((n, n), 1e9, np.float32)
    for px, py in pts:
        dx = np.abs(xx - px)
        dx = np.minimum(dx, n - dx)
        dy = np.abs(yy - py)
        dy = np.minimum(dy, n - dy)
        d = np.sqrt(dx * dx + dy * dy)
        d2 = np.where(d < d1, d1, np.minimum(d2, d))
        d1 = np.minimum(d1, d)
    return norm01(d2 - d1) if second else norm01(d1)


def smooth(a, lo, hi):
    t = np.clip((a - lo) / (hi - lo), 0, 1)
    return t * t * (3 - 2 * t)


def radial(n, cx=0.5, cy=0.5, m=None):
    m = m or n
    yy, xx = np.mgrid[0:m, 0:n]
    return np.hypot((xx + 0.5) / n - cx, (yy + 0.5) / m - cy) * 2.0


def angle(n):
    yy, xx = np.mgrid[0:n, 0:n]
    return np.arctan2((yy + 0.5) / n - 0.5, (xx + 0.5) / n - 0.5)


def normal_map(h, strength):
    """OpenGL-style tangent normal map (green = up) from a tileable height field."""
    dx = (np.roll(h, -1, 1) - np.roll(h, 1, 1)) * strength
    dy = (np.roll(h, -1, 0) - np.roll(h, 1, 0)) * strength
    nz = np.ones_like(h)
    l = np.sqrt(dx * dx + dy * dy + nz * nz)
    return np.dstack([(-dx / l) * 0.5 + 0.5, (dy / l) * 0.5 + 0.5, (nz / l) * 0.5 + 0.5])


def save(name, a, note):
    a = np.clip(np.asarray(a, dtype=np.float64), 0, 1)
    arr = (a * 255.0 + 0.5).astype(np.uint8)
    mode = "L" if arr.ndim == 2 else {3: "RGB", 4: "RGBA"}[arr.shape[2]]
    Image.fromarray(arr, mode).save(os.path.join(OUT, name + ".png"), optimize=True)
    MADE.append((name, note))


def rgba(intensity, color, alpha=None):
    c = np.zeros(intensity.shape + (4,))
    for i in range(3):
        c[..., i] = np.clip(color[i] * (0.6 + 0.4 * intensity), 0, 1) if alpha is None else color[i]
    c[..., 3] = intensity if alpha is None else alpha
    return c


def hot(i):
    """Intensity -> orange-white emissive colour with alpha."""
    c = np.zeros(i.shape + (4,))
    c[..., 0] = np.clip(i * 2.2, 0, 1)
    c[..., 1] = np.clip(i * 1.5 - 0.15, 0, 1)
    c[..., 2] = np.clip(i * 1.6 - 0.75, 0, 1)
    c[..., 3] = np.clip(i * 1.4, 0, 1)
    return c


def book(frames, cols):
    rows = (len(frames) + cols - 1) // cols
    h, w = frames[0].shape[:2]
    shape = (rows * h, cols * w) + frames[0].shape[2:]
    out = np.zeros(shape)
    for i, f in enumerate(frames):
        out[(i // cols) * h:(i // cols + 1) * h, (i % cols) * w:(i % cols + 1) * w] = f
    return out


def draw(n, fn, sigma=0.0, m=None):
    """Draws with PIL (values 0..255) and returns a 0..1 array."""
    img = Image.new("L", (n, m or n), 0)
    fn(ImageDraw.Draw(img))
    a = np.asarray(img, dtype=np.float64) / 255.0
    return blur(a, sigma) if sigma else a


def bolt(d, r, a, b, width, depth=0, jitter=0.18):
    """Jagged line from a to b with a few forks (lightning, electric arcs, cracks)."""
    pts = [a]
    steps = 9
    for i in range(1, steps):
        t = i / steps
        ln = math.hypot(b[0] - a[0], b[1] - a[1])
        pts.append((a[0] + (b[0] - a[0]) * t + r.normal(0, ln * jitter / 3), a[1] + (b[1] - a[1]) * t + r.normal(0, ln * jitter / 3)))
    pts.append(b)
    d.line(pts, fill=255, width=max(1, int(width)), joint="curve")
    if depth < 2:
        for i in r.choice(len(pts) - 2, size=2, replace=False):
            p = pts[i + 1]
            ang = math.atan2(b[1] - a[1], b[0] - a[0]) + r.uniform(-1.1, 1.1)
            ln = math.hypot(b[0] - a[0], b[1] - a[1]) * r.uniform(0.2, 0.45)
            bolt(d, r, p, (p[0] + math.cos(ang) * ln, p[1] + math.sin(ang) * ln), width * 0.6, depth + 1, jitter)


# --- masks and overlays --------------------------------------------------------------
def masks():
    n = 512
    save("A35_PuddleMask_Mask", blur(smooth(fnoise(n, 2.6, 1), 0.56, 0.63), 2.0), "low-frequency noise, thresholded")
    v = np.linspace(0, 1, n)[:, None]
    cond = np.clip(fnoise(n, 2.0, 2) * 0.6 + v * 0.7, 0, 1)
    wiped = draw(n, lambda d: [d.line([(x, 0), (x + rr, n)], fill=255, width=w) for x, rr, w in
                               [(int(rng(3 + i).uniform(0, n)), int(rng(9 + i).uniform(-12, 12)), int(rng(5 + i).uniform(2, 9))) for i in range(14)]], 1.5)
    save("C64_WindowCondensation_Mask", np.clip(cond - wiped * 0.9, 0, 1), "fog denser towards the sill, with clear drip tracks")
    save("D63_SettledDust_Mask", norm01(fnoise(n, 1.6, 4) * 0.7 + fnoise(n, 0.3, 5) * 0.3), "broad drifts plus fine grain")

    r = rng(6)
    def cracks(d):
        cx, cy = 0.38 * n, 0.44 * n
        for i in range(11):
            a = i / 11 * math.tau + r.uniform(-0.2, 0.2)
            bolt(d, r, (cx, cy), (cx + math.cos(a) * n * 0.9, cy + math.sin(a) * n * 0.9), 2, depth=1, jitter=0.05)
        for rad in (0.08, 0.17, 0.3):
            pts = [(cx + math.cos(i / 9 * math.tau) * n * rad * r.uniform(0.8, 1.2), cy + math.sin(i / 9 * math.tau) * n * rad * r.uniform(0.8, 1.2)) for i in range(10)]
            d.line(pts + pts[:1], fill=255, width=1)
    save("E60_MirrorCracks_Mask", draw(n, cracks, 0.4), "impact point with radial and ring cracks")

    r = rng(7)
    def mud(d):
        for i in range(520):
            y = n * (1 - r.random() ** 2.2)
            x = r.random() * n
            s = r.uniform(0.6, 5.0) * (0.4 + y / n)
            d.ellipse([x - s, y - s * r.uniform(0.7, 1.6), x + s, y + s], fill=int(r.uniform(120, 255)))
    streak = blur(smooth(fnoise(n, 1.2, 8, aniso=(0.02, 1.0)), 0.6, 0.85), 0.8) * 0.5
    save("F03_CarMudAndRunoff_Mask", np.clip(draw(n, mud, 0.8) + streak, 0, 1), "splatter thickest low on the body, plus vertical run-off")

    r = rng(10)
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float64)
    drops = np.zeros((n, n))
    for i in range(170):
        px, py, rad = r.random() * n, r.random() * n, r.uniform(2.5, 11)
        dx = np.minimum(np.abs(xx - px), n - np.abs(xx - px))
        dy = np.minimum(np.abs(yy - py), n - np.abs(yy - py))
        q = 1 - (dx * dx + dy * dy * r.uniform(0.6, 1.0)) / (rad * rad)
        drops = np.maximum(drops, np.sqrt(np.clip(q, 0, 1)))
    save("F20a_WindshieldDroplets_Mask", smooth(drops, 0.0, 0.25), "where the drops are")
    save("F20a_WindshieldDroplets_Normal", normal_map(blur(drops, 0.6), 6.0), "lens-shaped drops")
    wip = np.zeros((n, n))
    for cx in (0.30, 0.78):
        rr = radial(n, cx, 1.02) / 2
        ang = np.arctan2(1.02 - (yy + 0.5) / n, (xx + 0.5) / n - cx)
        wip = np.maximum(wip, smooth(rr, 0.16, 0.19) * (1 - smooth(rr, 0.62, 0.65)) * smooth(ang, 0.35, 0.42) * (1 - smooth(ang, 2.7, 2.78)))
    save("F20b_WiperArc_Mask", np.clip(wip * (0.85 + 0.15 * fnoise(n, 1.0, 11, aniso=(0.1, 1.0))), 0, 1), "the two swept fans; white = wiped clean")

    v = np.linspace(1, 0, n)[:, None]
    a = np.clip(v ** 1.6 * (0.55 + 0.6 * fnoise(n, 2.2, 12)), 0, 1)
    save("H28_NicotineCeilingStain_Color", rgba(a, (0.55, 0.42, 0.18), a * 0.85), "yellow-brown film, heaviest at the top")

    veins = 1 - smooth(worley(n, 46, 13, second=True), 0.0, 0.09)
    save("L38_PulseVeins_Mask", np.clip(blur(veins, 1.2) * 1.3 * (0.6 + 0.4 * fnoise(n, 2, 14)), 0, 1), "vein network; animate brightness along it")

    m = 1024
    edge = 0.55 + (fnoise(256, 3.6, 15, m=1)[0][None, :] - 0.5) * 0.3
    v = np.linspace(0, 1, m)[:, None]
    wet = smooth(v, edge - 0.12, edge + 0.1) * (0.85 + 0.15 * fnoise(256, 1.5, 16, m=m))
    save("M40_WallBaseDamp_Mask", wet, "white = wet; ragged upper edge where the damp has climbed")
    v = np.linspace(0, 1, 512)[:, None]
    save("P03_SleeveRainWet_Mask", np.clip((1 - v) * 0.5 + fnoise(256, 2.0, 17, m=512) * 0.7 - 0.1, 0, 1) ** 1.2, "shoulders and upper arm soak first")


# --- television, arcs, sparks, fire -----------------------------------------------------
def emissive():
    n = 256
    r = rng(20)
    frames = []
    scan = (0.82 + 0.18 * (np.arange(n) % 3 == 0))[:, None]
    for i in range(8):                                    # snow
        s = r.random((n, n)) * scan
        frames.append(np.dstack([s, s, s]))
    bars = np.array([(.75, .75, .75), (.75, .75, 0), (0, .75, .75), (0, .75, 0), (.75, 0, .75), (.75, 0, 0), (0, 0, .75)])
    for i in range(4):                                    # colour bars with a tear
        f = bars[(np.arange(n) * 7 // n)][None, :, :].repeat(n, 0).astype(np.float64)
        y = int(r.uniform(20, n - 40))
        f[y:y + 14] = np.roll(f[y:y + 14], int(r.uniform(-60, 60)), 1)
        frames.append(np.clip(f * 0.8 + r.random((n, n, 1)) * 0.3, 0, 1))
    rr = radial(n, 0.5, 0.46)
    yy, xx = np.mgrid[0:n, 0:n] / n
    face = smooth(1 - rr * np.sqrt(1 + (xx - 0.5) ** 2 * 6), 0.0, 0.35)
    for ex in (0.4, 0.6):                                 # a face that is not quite there
        face -= smooth(1 - np.hypot((xx - ex) * 1.2, yy - 0.42) * 9, 0, 1) * 0.9
    face -= smooth(1 - np.hypot((xx - 0.5) * 2.6, (yy - 0.7) * 1.2) * 8, 0, 1) * 0.9
    for i in range(4):
        f = np.clip(face, 0, 1)
        f = np.stack([np.roll(f[y], int(r.normal(0, 3 + i * 3))) for y in range(n)])
        s = np.clip(f * (0.42 - i * 0.07) + r.random((n, n)) * (0.5 + i * 0.1), 0, 1) * scan
        frames.append(np.dstack([s * 0.9, s, s * 1.05]))
    save("K07_TelevisionScreen_Flipbook4x4", book(frames, 4), "frames 0-7 snow, 8-11 colour bars, 12-15 a face dissolving into static")

    for name, seed, note in (("K29_ElectricArc_Flipbook4x4", 21, "arc between two contacts"), ("P37_StunGunArc_Flipbook4x4", 22, "short tight arc between the probes")):
        r = rng(seed)
        frames = []
        for i in range(16):
            short = name.startswith("P37")
            a, b = ((n * 0.3, n * 0.5), (n * 0.7, n * 0.5)) if short else ((n * 0.06, n * 0.5), (n * 0.94, n * 0.5))
            core = draw(n, lambda d: bolt(d, r, a, b, 2, depth=0 if not short else 1, jitter=0.3))
            glow = np.clip(blur(core, 1.0) * 2.5 + blur(core, 6.0) * 3.5, 0, 1)
            c = np.zeros((n, n, 4))
            c[..., 0] = np.clip(0.55 * glow + core, 0, 1)
            c[..., 1] = np.clip(0.75 * glow + core, 0, 1)
            c[..., 2] = np.clip(glow + core, 0, 1)
            c[..., 3] = np.clip(glow * 1.3, 0, 1)
            frames.append(c)
        save(name, book(frames, 4), note)

    r = rng(23)
    parts = [(r.uniform(0, math.tau), r.uniform(0.5, 1.5), r.uniform(0.5, 1.0)) for _ in range(46)]
    frames = []
    for i in range(16):
        t = (i + 1) / 16
        def sp(d):
            for ang, speed, life in parts:
                if t > life:
                    continue
                x0 = n / 2 + math.cos(ang) * speed * t * n * 0.36
                y0 = n / 2 + math.sin(ang) * speed * t * n * 0.36 + t * t * n * 0.3
                x1 = x0 - math.cos(ang) * speed * n * 0.05
                y1 = y0 - math.sin(ang) * speed * n * 0.05 - t * n * 0.03
                d.line([(x0, y0), (x1, y1)], fill=int(255 * (1 - t / life) ** 0.5), width=2)
        core = draw(n, sp)
        flash = np.clip(1 - radial(n) * 3.5, 0, 1) * max(0.0, 1 - t * 5)
        frames.append(hot(np.clip(blur(core, 0.7) * 1.6 + blur(core, 3) * 1.2 + flash, 0, 1)))
    save("O23_Sparks_Flipbook4x4", book(frames, 4), "a burst that arcs down under gravity")

    n = 512
    ang = angle(n)
    rr = radial(n)
    def star(spikes, seed, reach, core, stretch=1.0):
        r = rng(seed)
        out = np.clip(1 - rr / core, 0, 1) ** 1.5
        for i in range(spikes):
            a0 = i / spikes * math.tau + r.uniform(-0.25, 0.25)
            da = np.abs(np.angle(np.exp(1j * (ang - a0))))
            ln = reach * r.uniform(0.55, 1.0) * (stretch if abs(math.cos(a0)) > 0.7 else 1.0)
            out = np.maximum(out, np.clip(1 - da / r.uniform(0.08, 0.2), 0, 1) * np.clip(1 - rr / ln, 0, 1))
        return np.clip(blur(out, 2.0) * (0.75 + 0.5 * fnoise(n, 1.5, seed + 50)), 0, 1)
    puff = np.clip((1 - rr * 1.25) * (0.45 + 0.9 * fnoise(n, 2.2, 30)), 0, 1)
    sup = np.zeros((n, n, 4))
    sup[..., :3] = (0.55 + 0.3 * puff)[..., None]
    sup[..., 0] += np.clip(1 - rr * 5, 0, 1) * 0.4
    sup[..., 3] = smooth(puff, 0.05, 0.5) * 0.8
    save("O24_MuzzleFlashes_Flipbook2x2", book([hot(star(5, 24, 0.95, 0.3)), hot(star(11, 25, 1.0, 0.5)), hot(star(4, 26, 0.8, 0.26, 1.6)), sup], 2),
         "pistol, shotgun, submachine gun, oil-filter suppressor (smoke, hardly any flame)")

    r = rng(27)
    def bolts(d):
        for q in range(4):
            ox, oy = (q % 2) * n / 2, (q // 2) * n / 2
            bolt(d, r, (ox + n * r.uniform(0.15, 0.35), oy + 4), (ox + n * r.uniform(0.15, 0.35), oy + n / 2 - 6), 3, jitter=0.25)
    core = draw(n, bolts)
    glow = np.clip(blur(core, 1.0) * 2.0 + blur(core, 7.0) * 3.0, 0, 1)
    c = np.zeros((n, n, 4))
    c[..., 0] = np.clip(0.8 * glow + core, 0, 1)
    c[..., 1] = np.clip(0.85 * glow + core, 0, 1)
    c[..., 2] = np.clip(glow + core, 0, 1)
    c[..., 3] = np.clip(glow * 1.3, 0, 1)
    save("O03_LightningBolts_Flipbook2x2", c, "four different bolts")


# --- smoke, steam, mist, rain ---------------------------------------------------------------
def soft():
    n = 256
    def cloud(seed, frames, rise, grow, wispy, fade=1.0):
        a, b = fnoise(n, 2.4, seed), fnoise(n, 2.4, seed + 1)
        out = []
        for i in range(frames):
            t = i / (frames - 1)
            f = np.roll(a, -int(t * rise * n), 0) * (1 - t) + np.roll(b, -int(t * rise * n * 0.6), 0) * t
            rad = radial(n, 0.5, 0.5 + 0.18 * (1 - t) * (1 if rise else 0)) / (0.45 + grow * t)
            al = np.clip((1 - rad) * 1.5, 0, 1) * smooth(f, 0.5 - 0.25 * wispy, 0.8) * (1 - t ** 1.5 * fade) * min(1.0, t * 6 + 0.25)
            out.append(np.dstack([np.ones((n, n)) * (0.75 + 0.25 * f)] * 3 + [np.clip(al * 1.6, 0, 1)]))
        return out
    save("O12_SteamAndBreath_Flipbook4x4", book(cloud(40, 16, 0.45, 0.5, 1.0), 4), "a puff that rises and thins; for manholes, the coffee pot, breath (also K44, L39)")
    save("O25_GunSmoke_Flipbook4x4", book(cloud(42, 16, 0.12, 0.7, 0.4), 4), "denser puff that hangs; for muzzles and shorted appliances")
    r = rng(44)
    pts = [(r.normal(0, 0.16), r.normal(0, 0.16), r.uniform(0.6, 2.2)) for _ in range(260)]
    frames = []
    for i in range(16):
        t = (i + 1) / 16
        def dots(d):
            for x, y, s in pts:
                px = n / 2 + x * n * (0.5 + 2.2 * t)
                py = n / 2 + y * n * (0.5 + 2.2 * t) + t * t * n * 0.12
                d.ellipse([px - s, py - s, px + s, py + s], fill=255)
        fine = draw(n, dots, 0.6)
        haze = np.clip(1 - radial(n) / (0.3 + 0.9 * t), 0, 1) * fnoise(n, 2.2, 45) * (1 - t)
        al = np.clip((fine * 1.5 + haze * 1.2) * (1 - t ** 2), 0, 1)
        frames.append(np.dstack([np.ones((n, n))] * 3 + [al]))
    save("O27_FluidMist_Flipbook4x4", book(frames, 4), "white, tint it red for blood or near-black for oil")

    r = rng(46)
    frames = []
    for i in range(16):
        t = (i + 1) / 16
        def crown(d):
            for k in range(26):
                a = k / 26 * math.tau
                reach = n * (0.08 + 0.3 * t) * (0.8 + 0.4 * math.sin(k * 7.3))
                x, y = n / 2 + math.cos(a) * reach, n * 0.62 + math.sin(a) * reach * 0.28 - math.sin(t * math.pi) * n * 0.2 * (0.6 + 0.4 * math.cos(k * 3.1))
                s = 4.5 * (1 - t) + 1.0
                d.ellipse([x - s, y - s, x + s, y + s], fill=int(255 * (1 - t)))
            d.ellipse([n / 2 - n * 0.3 * t, n * 0.62 - n * 0.08 * t, n / 2 + n * 0.3 * t, n * 0.62 + n * 0.08 * t], outline=int(200 * (1 - t)), width=2)
        al = np.clip(draw(n, crown, 0.8) * 2.4, 0, 1)
        frames.append(np.dstack([np.ones((n, n))] * 3 + [al]))
    save("O07_RainSplash_Flipbook4x4", book(frames, 4), "crown and ring of one raindrop hitting a wet surface")

    yy, xx = np.mgrid[0:n, 0:n].astype(np.float64)
    r = rng(47)
    rings = [(r.random() * n, r.random() * n, r.random()) for _ in range(9)]
    frames = []
    for i in range(16):
        h = np.zeros((n, n))
        for px, py, ph in rings:
            t = (i / 16 + ph) % 1.0
            dx = np.minimum(np.abs(xx - px), n - np.abs(xx - px))
            dy = np.minimum(np.abs(yy - py), n - np.abs(yy - py))
            d = np.sqrt(dx * dx + dy * dy)
            h += np.sin((d - t * 70) * 0.55) * np.exp(-((d - t * 70) / 9.0) ** 2) * (1 - t) ** 1.5
        frames.append(normal_map(h, 2.2))
    save("O06_RainRipples_Flipbook4x4", book(frames, 4), "normal maps; each frame tiles, and the sequence loops")

    def streaks(m, w, count, seed, slant, length):
        r = rng(seed)
        def f(d):
            for _ in range(count):
                x, y, ln = r.random() * w, r.random() * m, m * r.uniform(*length)
                d.line([(x, y), (x + slant * ln, y + ln)], fill=int(r.uniform(90, 255)), width=1)
        return draw(w, f, 0.5, m=m)
    for nm, cnt, seed, note in (("O04a_RainStreaksNear_Color", 14, 48, "few long streaks, close to the camera"), ("O04b_RainStreaksFar_Color", 60, 49, "many fine streaks for the distant layer")):
        a = streaks(512, 128, cnt, seed, 0.0, (0.25, 0.6) if cnt < 20 else (0.06, 0.16))
        save(nm, np.dstack([np.full_like(a, 0.8)] * 3 + [np.clip(a * 1.2, 0, 1)]), note)
    frames = []
    for i in range(4):
        a = streaks(256, 256, 90, 50 + i, 0.22, (0.06, 0.14))
        frames.append(np.dstack([np.ones_like(a)] * 3 + [np.clip(a * 1.3, 0, 1)]))
    save("O05_RainInLightBeam_Flipbook2x2", book(frames, 2), "slanted streaks; show only inside light cones")

    n = 512
    a = np.clip(1 - radial(n, m=n) * np.array(1.0), 0, 1)
    rr = np.hypot((np.mgrid[0:n, 0:n][1] / n - 0.5) * 1.1, (np.mgrid[0:n, 0:n][0] / n - 0.55) * 2.6) * 2
    a = np.clip(1 - rr, 0, 1) ** 1.3 * (0.4 + 0.9 * fnoise(n, 2.6, 52))
    save("O10_GroundFog_Color", np.dstack([np.full((n, n), 0.82)] * 3 + [np.clip(a, 0, 1) * 0.7]), "soft low bank for fog cards")

    rr = radial(256)
    ang = angle(256)
    tiles = []
    for col, streak in (((1.0, 0.62, 0.22), 0.5), ((1.0, 0.25, 0.6), 0.25), ((0.85, 0.9, 1.0), 0.9), ((0.7, 1.0, 0.85), 0.35)):
        yy = np.abs(np.mgrid[0:256, 0:256][0] / 256 - 0.5) * 2
        i = np.clip((1 - rr) ** 3 * 0.9 + np.clip(1 - rr * 4, 0, 1) + np.exp(-(yy * 26) ** 2) * np.clip(1 - rr, 0, 1) * streak
                    + np.clip(np.cos(ang * 6) ** 40, 0, 1) * np.clip(1 - rr * 1.6, 0, 1) * 0.25, 0, 1)
        tiles.append(np.dstack([np.clip(col[k] + i * 0.5, 0, 1) * np.ones_like(i) for k in range(3)] + [i]))
    save("O14_LightGlares_Flipbook2x2", book(tiles, 2), "sodium lamp, neon, cold white (tint for red / blue police lights), mercury-vapour green")

    m, n = 1024, 2048
    az = np.linspace(0, math.tau, n, endpoint=False)[None, :]
    el = np.linspace(math.pi / 2, -math.pi / 2, m)[:, None]
    cloudn = fnoise(n, 2.4, 53, m=m)
    low = np.exp(-(np.clip(el, 0, None) / 0.22) ** 2) * (el > -0.02)
    glow = low * (0.55 * np.exp(-((np.angle(np.exp(1j * (az - 1.1)))) / 0.7) ** 2) + 0.3 * np.exp(-((np.angle(np.exp(1j * (az - 4.3)))) / 0.5) ** 2) + 0.08)
    under = np.clip(el / 1.2, 0, 1)
    sky = np.dstack([0.012 + glow * 0.55 * (0.6 + 0.6 * cloudn) + under * 0.004 * cloudn,
                     0.014 + glow * 0.30 * (0.6 + 0.6 * cloudn) + under * 0.006 * cloudn,
                     0.020 + glow * 0.12 * (0.6 + 0.6 * cloudn) + under * 0.012 * cloudn])
    sky[el[:, 0] < -0.02] = (0.006, 0.006, 0.007)
    save("O02_HorizonLightPollution_Panorama", np.clip(sky * 2.2, 0, 1), "overcast night with the orange glow of two distant towns on the horizon; dark, meant to be scaled up in engine")


# --- light cookies and curves ---------------------------------------------------------------
def cookies():
    n = 512
    rr = radial(n)
    ang = angle(n)
    yy, xx = np.mgrid[0:n, 0:n] / n
    disc = 1 - smooth(rr, 0.82, 1.0)
    save("O15_StreetLampGlobe_Cookie", disc * (0.72 + 0.2 * np.cos(ang * 24) * smooth(rr, 0.3, 0.8)) * (1 - 0.35 * np.exp(-(rr / 0.16) ** 2)) * (0.9 + 0.1 * fnoise(n, 2, 60)),
         "prismatic refractor ribs, dim spot under the bulb, a little dirt")
    slat = smooth(np.abs(((yy * 13) % 1.0) - 0.5), 0.14, 0.24)
    save("O16a_VenetianBlinds_Cookie", blur(slat * (1 - smooth(rr, 0.9, 1.25)), 1.5), "striped light through bent slats")
    pane = np.zeros((n, n))
    for gx in range(2):
        for gy in range(3):
            pane = np.maximum(pane, ((xx > 0.14 + gx * 0.37) & (xx < 0.47 + gx * 0.37) & (yy > 0.08 + gy * 0.29) & (yy < 0.33 + gy * 0.29)).astype(float))
    save("O16b_WindowPanes_Cookie", blur(pane, 4.0) * (0.85 + 0.15 * fnoise(n, 2.2, 61)), "six panes with the shadow of the muntins, dirty glass")
    blades = np.clip(np.cos(ang * 2) ** 2 * 3 - 1.6, 0, 1) * smooth(rr, 0.1, 0.16) * (1 - smooth(rr, 0.8, 0.86))
    save("O17_CeilingFan_Cookie", blur(disc * (1 - blades * 0.9), 2.0), "four blades; rotate the light to animate")
    lobe = np.exp(-(np.angle(np.exp(1j * ang)) / 0.5) ** 2) + np.exp(-(np.angle(np.exp(1j * (ang - math.pi))) / 0.5) ** 2)
    save("O18_PoliceBeacon_Cookie", np.clip(lobe * (1 - smooth(rr, 0.7, 1.0)) + np.exp(-(rr / 0.12) ** 2), 0, 1), "two opposite beams; rotate to sweep")
    low = np.exp(-((xx - 0.5) / 0.36) ** 2) * smooth(yy, 0.44, 0.52) * np.exp(-((yy - 0.6) / 0.2) ** 2) + np.exp(-((xx - 0.6) / 0.14) ** 2 - ((yy - 0.56) / 0.07) ** 2) * 0.8
    save("O19a_HeadlightLowBeam_Cookie", np.clip(blur(low, 2.0), 0, 1), "wide, flat cut-off on top, kick-up to the right")
    high = np.exp(-((xx - 0.5) / 0.3) ** 2 - ((yy - 0.5) / 0.2) ** 2) * 0.6 + np.exp(-((xx - 0.5) / 0.09) ** 2 - ((yy - 0.48) / 0.07) ** 2)
    save("O19b_HeadlightHighBeam_Cookie", np.clip(high, 0, 1), "central hot spot")
    torch = np.exp(-(rr / 0.3) ** 2) + 0.3 * (1 - smooth(rr, 0.78, 0.95)) - 0.14 * np.exp(-((rr - 0.5) / 0.05) ** 2) + 0.1 * np.exp(-((rr - 0.86) / 0.03) ** 2)
    r = rng(62)
    for _ in range(18):
        torch -= 0.3 * np.exp(-(np.hypot(xx - r.uniform(0.2, 0.8), yy - r.uniform(0.2, 0.8)) / r.uniform(0.004, 0.012)) ** 2)
    save("O20a_PoliceFlashlight_Cookie", np.clip(torch, 0, 1), "hot spot, corona, reflector rings, dust on the lens")
    iris = (0.55 + 0.45 * fnoise(n, 1.4, 63, aniso=(1.0, 1.0))) * (0.7 + 0.3 * np.cos(ang * 46 + fnoise(n, 2, 64) * 6))
    eye = smooth(rr, 0.12, 0.2) * (1 - smooth(rr, 0.8, 0.98)) * iris + 0.12 * (1 - smooth(rr, 0.9, 1.0))
    save("O20b_LampEyeLight_Cookie", np.clip(eye, 0, 1), "the creature-eye attachment: iris fibres around a dark pupil")
    r = rng(65)
    steps = np.repeat(r.uniform(0.25, 1.0, 64), 4)
    col = np.dstack([steps * np.repeat(r.uniform(0.7, 1.0, 64), 4), steps * np.repeat(r.uniform(0.8, 1.0, 64), 4), steps])[0][None, :, :].repeat(4, 0)
    save("O21_TelevisionFlicker_Curve", col, "read left to right over about 4 s: cold, jumpy light of a TV on a wall")
    t = np.ones(256)
    for s in r.choice(240, 9, replace=False):
        t[s:s + int(r.uniform(1, 5))] = r.uniform(0.0, 0.5)
    t[:10] = [0, 1, 0, 0, 1, 0.3, 1, 0, 1, 1]
    save("O22_FluorescentFlicker_Curve", t[None, :].repeat(4, 0), "starter strikes, then steady light with dropouts")


# --- technical maps ---------------------------------------------------------------------------
def technical():
    n = 512
    weave = np.sin(np.mgrid[0:n, 0:n][1] / n * math.tau * 64) * np.sin(np.mgrid[0:n, 0:n][0] / n * math.tau * 64)
    details = [
        ("R01a_DetailConcrete_Normal", fnoise(n, 0.9, 70), 5.0, "fine aggregate"),
        ("R01b_DetailBrushedMetal_Normal", fnoise(n, 1.0, 71, aniso=(1.0, 0.02)), 4.0, "scratches along x"),
        ("R01c_DetailFabric_Normal", weave * 0.5 + 0.5 + fnoise(n, 1.0, 72) * 0.2, 2.5, "plain weave"),
        ("R01d_DetailPlasticGrain_Normal", 1 - worley(n, 900, 73), 4.0, "leather-grain plastic"),
        ("R01e_DetailWood_Normal", fnoise(n, 1.6, 74, aniso=(0.06, 1.0)), 5.0, "grain along y"),
        ("R01f_DetailRubber_Normal", worley(n, 1600, 75) + fnoise(n, 0.5, 76) * 0.3, 3.0, "fine pebbling"),
    ]
    for name, h, s, note in details:
        save(name, normal_map(norm01(h), s), note)
    save("R02a_NoiseFractal_Mask", fnoise(n, 2.0, 77), "fractal (Perlin-like), tileable")
    save("R02b_NoiseCellular_Mask", worley(n, 64, 78), "cellular (Worley), tileable")
    white = rng(79).standard_normal((256, 256))
    f = np.hypot(np.fft.fftfreq(256)[:, None], np.fft.fftfreq(256)[None, :])
    hp = np.real(np.fft.ifft2(np.fft.fft2(white) * smooth(f, 0.05, 0.35)))
    save("R02c_NoiseBlue_Mask", np.argsort(np.argsort(hp.ravel())).reshape(256, 256) / 65535.0, "high-pass noise with a flat histogram, for dithering")
    v = np.linspace(0, 1, n)[:, None]
    scratch = smooth(fnoise(n, 0.8, 80, aniso=(0.04, 1.0)), 0.62, 0.8) * 0.6 + smooth(fnoise(n, 0.4, 81), 0.72, 0.9)
    drip = lambda seed, wide: blur(smooth(fnoise(n, 1.4, seed, aniso=(0.015, 1.0)), 0.55, 0.85), wide) * (1 - v * 0.6)
    save("R03a_DirtEdgeWear_Mask", np.clip(scratch, 0, 1), "multiply with a curvature / edge mask")
    save("R03b_DirtCavityDust_Mask", smooth(fnoise(n, 2.4, 82), 0.3, 0.75), "multiply with an occlusion mask")
    save("R03c_DirtRainStreaksFine_Mask", np.clip(drip(83, 0.6) * 1.2, 0, 1), "thin vertical run-off, strongest at the top")
    save("R03d_DirtRainStreaksBroad_Mask", np.clip(drip(84, 2.5) * 1.3, 0, 1), "wide stains under sills and gutters")
    save("R03e_DirtLeaks_Mask", np.clip(blur(smooth(fnoise(n, 1.8, 85, aniso=(0.1, 1.0)), 0.6, 0.8), 2.0) * (1 - v * 0.4), 0, 1), "blotchy leaks")
    save("R03f_DirtSplotches_Mask", smooth(fnoise(n, 2.8, 86), 0.5, 0.62), "big soft patches")
    save("R04_Wetness_Mask", np.clip(fnoise(n, 2.2, 87) * 0.8 + drip(88, 1.5) * 0.4, 0, 1), "where surfaces stay wet longest")
    blocks = np.kron(rng(89).random((16, 8)), np.ones((n // 16, n // 8)))
    save("R05a_BlendBrickToPlaster_Height", norm01(blocks * 0.5 + fnoise(n, 2.0, 90)), "plaster breaks off brick by brick")
    save("R05b_BlendAsphaltToMud_Height", norm01(fnoise(n, 2.2, 91) + (1 - smooth(worley(n, 40, 92, second=True), 0.0, 0.06)) * 0.25), "mud fills the cracks first")
    save("R06_StillWater_Normal", normal_map(fnoise(n, 2.4, 93), 3.0), "barely moving water; scroll two copies against each other")
    bubbles = sum(np.exp(-(np.hypot(np.mgrid[0:n, 0:n][1] - x, np.mgrid[0:n, 0:n][0] - y) / 3.0) ** 2) for x, y in rng(94).random((14, 2)) * n)
    save("R07_OldGlass_Normal", normal_map(fnoise(n, 3.2, 95) * 4.0 + bubbles * 0.05, 5.0), "the wobble of drawn glass, a few seeds")
    lut = np.zeros((16, 256, 3))
    for b in range(16):
        for g in range(16):
            lut[g, b * 16:(b + 1) * 16, 0] = np.arange(16) / 15.0
            lut[g, b * 16:(b + 1) * 16, 1] = g / 15.0
            lut[g, b * 16:(b + 1) * 16, 2] = b / 15.0
    save("R08_NeutralLut_Color", lut, "identity 16x16x16 strip; grade a screenshot together with it to make a preset's LUT")
    r = rng(96)
    save("R09_FilmGrain_Flipbook2x2", book([np.clip(0.5 + blur(r.standard_normal((256, 256)), 0.6) * 0.22, 0, 1) for _ in range(4)], 2), "four frames, mid-grey = no change")
    v3 = rng(97).standard_normal((64, 64, 64))
    fz, fy, fx = np.meshgrid(np.fft.fftfreq(64), np.fft.fftfreq(64), np.fft.fftfreq(64), indexing="ij")
    f = np.sqrt(fx * fx + fy * fy + fz * fz)
    f[0, 0, 0] = 1
    vol = norm01(np.real(np.fft.ifftn(np.fft.fftn(v3) / f ** 1.4)))
    save("O09_FogNoiseVolume_Flipbook8x8", book([vol[i] for i in range(64)], 8), "64 slices of a 64-cubed volume that tiles in all three directions")


def chips():
    """O26: 8 rows (brick, concrete, wood, metal, enamel, glass, plaster, plastic) x 4 debris pieces."""
    kinds = [((0.55, 0.26, 0.2), "chunk"), ((0.5, 0.5, 0.48), "chunk"), ((0.5, 0.36, 0.2), "splinter"), ((0.6, 0.62, 0.64), "curl"),
             ((0.9, 0.9, 0.86), "flake"), ((0.75, 0.85, 0.88), "shard"), ((0.85, 0.83, 0.78), "dust"), ((0.78, 0.72, 0.55), "shard")]
    r = rng(98)
    img = Image.new("RGBA", (512, 1024), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for row, (col, kind) in enumerate(kinds):
        for i in range(4):
            cx, cy = i * 128 + 64, row * 128 + 64
            shade = r.uniform(0.75, 1.1)
            fill = tuple(int(min(1.0, c * shade) * 255) for c in col) + (255 if kind != "shard" or row == 7 else 170,)
            if kind == "dust":
                for _ in range(40):
                    x, y, q = cx + r.normal(0, 22), cy + r.normal(0, 22), r.uniform(1, 4)
                    d.ellipse([x - q, y - q, x + q, y + q], fill=fill[:3] + (int(r.uniform(60, 200)),))
                continue
            n_pts = {"chunk": 7, "splinter": 4, "curl": 6, "flake": 6, "shard": 3}[kind]
            sx, sy = {"chunk": (38, 34), "splinter": (10, 52), "curl": (46, 14), "flake": (34, 26), "shard": (24, 44)}[kind]
            rot = r.uniform(0, math.tau)
            pts = []
            for k in range(n_pts):
                a = k / n_pts * math.tau + r.uniform(-0.3, 0.3)
                x, y = math.cos(a) * sx * r.uniform(0.6, 1.0), math.sin(a) * sy * r.uniform(0.6, 1.0)
                pts.append((cx + x * math.cos(rot) - y * math.sin(rot), cy + x * math.sin(rot) + y * math.cos(rot)))
            d.polygon(pts, fill=fill)
            d.line(pts[:n_pts // 2 + 1], fill=tuple(int(c * 0.6) for c in fill[:3]) + (255,), width=2)
    arr = np.asarray(img, dtype=np.float64) / 255.0
    arr[..., :3] *= (0.85 + 0.3 * fnoise(512, 1.2, 99, m=1024))[..., None]
    save("O26_ImpactDebris_Flipbook4x8", arr, "rows: brick, concrete, wood, metal, enamel, glass, plaster, plastic; four pieces each")


ENGINE = [
    ("A37", "curb water flow", "shader"), ("B06", "overhead wires", "mesh"), ("C71", "TV light in windows", "uses K07 + O21"),
    ("E50", "CCTV monitor", "uses K07"), ("F22", "wing mirror", "real-time reflection"), ("G35", "oil surface in the pit", "shader"),
    ("H09", "back-bar mirror", "real-time reflection + E60 mask"), ("I07", "washer window: water and suds", "shader"),
    ("I09", "washer window: bloody water", "shader"), ("K43", "mimic tell: faint glow", "emission parameter"),
    ("K44", "mimic tell: dry lid and steam", "uses O12"), ("L15", "black oil", "shader"), ("L23", "blood in water", "shader"),
    ("L39", "breath", "uses O12"), ("O08", "water running down glass", "shader"), ("O11", "black fog wall", "shader"),
    ("Q11", "store screenshots", "captured from the game"),
]


def main():
    masks()
    emissive()
    soft()
    cookies()
    technical()
    chips()
    with open(os.path.join(OUT, "sources.csv"), "w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(["set", "source", "asset", "url", "license", "note"])
        for name, note in MADE:
            w.writerow([name.rsplit("_", 1)[0], "procedural", "art/textures/gen_procedural.py", "", "original", note])
        for lid, what, how in ENGINE:
            w.writerow([lid, "engine", how, "", "n/a", "%s: no image file" % what])
    size = sum(os.path.getsize(os.path.join(OUT, f)) for f in os.listdir(OUT))
    print("%d files, %.1f MB -> %s" % (len(MADE), size / 1e6, OUT))


if __name__ == "__main__":
    main()
