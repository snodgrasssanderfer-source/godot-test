"""Film-style finishing pass for the concept renders: bloom, split-tone grade,
slight chromatic aberration, vignette, grain. These are the same post effects
the game would run, so the stills show the intended final look.

  python3 art/concept/post.py <raw_dir> <out_dir>
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

# name: saturation, shadow tint, highlight tint, contrast, (bloom strength, threshold), vignette, grain, gain
GRADES = {
    "A_cold_fog":     (0.72, (0.94, 1.00, 1.07), (1.00, 1.00, 0.98), 1.10, (0.15, 0.78), 0.35, 0.022, 1.00),
    "B_rust_dusk":    (0.88, (0.84, 0.98, 1.10), (1.04, 1.00, 0.93), 1.14, (0.30, 0.70), 0.40, 0.022, 0.96),
    "C_sodium_night": (0.95, (0.94, 0.97, 1.10), (1.03, 1.00, 0.94), 1.06, (0.50, 0.55), 0.42, 0.026, 1.30),
    "D_blackout":     (0.80, (0.90, 1.00, 1.10), (1.00, 1.00, 1.00), 1.08, (0.30, 0.60), 0.48, 0.030, 1.20),
    "E_safehouse":    (0.86, (0.90, 1.00, 1.08), (1.06, 1.00, 0.92), 1.08, (0.45, 0.62), 0.45, 0.026, 1.12),
}
LUMA = np.array([0.2126, 0.7152, 0.0722], dtype=np.float32)


def blur(arr, radius):
    img = Image.fromarray((np.clip(arr, 0, 1) * 255).astype(np.uint8))
    return np.asarray(img.filter(ImageFilter.GaussianBlur(radius)), dtype=np.float32) / 255.0


def zoom_channel(ch, factor):
    h, w = ch.shape
    img = Image.fromarray((np.clip(ch, 0, 1) * 255).astype(np.uint8))
    nw, nh = round(w * factor), round(h * factor)
    img = img.resize((nw, nh), Image.BICUBIC)
    left, top = (nw - w) // 2, (nh - h) // 2
    return np.asarray(img.crop((left, top, left + w, top + h)), dtype=np.float32) / 255.0


def finish(src, dst, grade, seed=3):
    sat, sh_tint, hi_tint, contrast, (bloom, thr), vig, grain, gain = grade
    rgb = np.asarray(Image.open(src).convert("RGB"), dtype=np.float32) / 255.0
    h, w, _ = rgb.shape

    # bloom: wide + tight glow from the brightest areas, screen-blended
    lum = rgb @ LUMA
    hot = rgb * np.clip((lum - thr) / (1 - thr), 0, 1)[..., None]
    glow = 0.6 * blur(hot, w / 60) + 0.4 * blur(hot, w / 220)
    rgb = 1 - (1 - rgb) * (1 - np.clip(glow * bloom * 2.2, 0, 1))

    # grade
    lum = (rgb @ LUMA)[..., None]
    rgb = lum + (rgb - lum) * sat
    t = np.clip(lum / 0.6, 0, 1)
    t = t * t * (3 - 2 * t)
    rgb = rgb * (np.array(sh_tint, dtype=np.float32) * (1 - t) + np.array(hi_tint, dtype=np.float32) * t)
    rgb = (rgb * gain - 0.36) * contrast + 0.36

    # lens: chromatic aberration + vignette
    rgb = np.clip(rgb, 0, 1)
    rgb = np.stack([zoom_channel(rgb[..., 0], 1.0036), zoom_channel(rgb[..., 1], 1.0018), rgb[..., 2]], axis=-1)
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    r2 = ((xx - w / 2) / (w / 2)) ** 2 + ((yy - h / 2) / (h / 2)) ** 2
    rgb = rgb * (1 - vig * np.clip(r2 / 2, 0, 1) ** 1.3)[..., None]

    # grain, stronger in the mid-tones
    rng = np.random.default_rng(seed)
    n = rng.normal(0, 1, (h, w)).astype(np.float32)
    n = 0.7 * n + 0.3 * np.asarray(
        Image.fromarray(((n * 40) + 128).clip(0, 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(0.8)),
        dtype=np.float32) / 40 - 0.3 * 3.2
    lum = (rgb @ LUMA)
    rgb = rgb + (n * grain * (0.35 + 0.65 * np.sin(np.clip(lum, 0, 1) * np.pi)))[..., None]

    Image.fromarray((np.clip(rgb, 0, 1) * 255 + 0.5).astype(np.uint8)).save(dst, quality=92)


if __name__ == "__main__":
    raw, out = Path(sys.argv[1]), Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    for name, grade in GRADES.items():
        src = raw / f"{name}.png"
        if src.exists():
            finish(src, out / f"{name}.jpg", grade)
            print("finished", out / f"{name}.jpg")
