#!/usr/bin/env python3
"""Synthesises placeholder sound effects (no recordings, no licences involved).

They exist so shooting, reloading and moving have audible feedback while the
feel is being tuned; every one is meant to be replaced by real, licensed audio.

  python3 tools/make_placeholder_sfx.py fps/assets/sfx
"""
import sys
import wave
from pathlib import Path

import numpy as np

SR = 44100
rng = np.random.default_rng(7)


def t(seconds):
    return np.arange(int(SR * seconds)) / SR


def noise(seconds):
    return rng.standard_normal(int(SR * seconds))


def band(x, lo=0.0, hi=SR / 2, soft=0.25):
    """FFT band-pass with soft shoulders (soft = shoulder width as a fraction of the edge)."""
    spec = np.fft.rfft(x)
    f = np.fft.rfftfreq(len(x), 1 / SR)
    gain = np.ones_like(f)
    if lo > 0:
        gain *= np.clip((f - lo * (1 - soft)) / (lo * soft), 0, 1)
    if hi < SR / 2:
        gain *= np.clip((hi * (1 + soft) - f) / (hi * soft), 0, 1)
    return np.fft.irfft(spec * gain, len(x))


def decay(seconds, tau):
    return np.exp(-t(seconds) / tau)


def ring(seconds, freqs, tau):
    tt = t(seconds)
    return sum(np.sin(2 * np.pi * f * tt + rng.uniform(0, 6.28)) * np.exp(-tt / (tau * a)) * a
               for f, a in freqs)


def sweep(seconds, f0, f1, tau):
    tt = t(seconds)
    freq = f1 + (f0 - f1) * np.exp(-tt / (tau * 0.6))
    return np.sin(2 * np.pi * np.cumsum(freq) / SR) * np.exp(-tt / tau)


def mix(seconds, *layers):
    out = np.zeros(int(SR * seconds))
    for start, sig in layers:
        i = int(start * SR)
        n = min(len(sig), len(out) - i)
        out[i:i + n] += sig[:n]
    return out


def click(freqs, tau=0.012, bright=3000, length=0.09, level=1.0):
    """A short mechanical clack: filtered noise snap plus a few damped partials."""
    return level * (band(noise(length), bright * 0.5, bright * 3) * decay(length, tau * 0.6) * 0.8
                    + ring(length, freqs, tau * 2.2) * 0.5)


def gunshot(length, crack_tau, body_tau, thump, tail_tau, tail_level, drive):
    crack = band(noise(length), 1800, 12000) * decay(length, crack_tau)
    body = band(noise(length), 180, 2400) * decay(length, body_tau)
    low = sweep(length, thump[0], thump[1], body_tau * 1.2)
    tail = band(noise(length), 250, 3200) * decay(length, tail_tau) * tail_level
    # a few discrete slap-back echoes sell "outdoors, between houses"
    echo = mix(length, (0.085, body[: int(SR * 0.25)] * 0.22), (0.19, body[: int(SR * 0.25)] * 0.12))
    return np.tanh((crack * 1.4 + body * 1.6 + low * 1.8) * drive) + tail + echo


def step(seed):
    r = np.random.default_rng(seed)
    heel = band(r.standard_normal(int(SR * 0.16)), 60, 700) * decay(0.16, 0.022)
    toe = band(r.standard_normal(int(SR * 0.16)), 300, 2600) * decay(0.16, 0.016) * 0.5
    grit = band(r.standard_normal(int(SR * 0.2)), 2500, 9000) * decay(0.2, 0.045) * 0.10
    return mix(0.26, (0.0, heel), (0.035 + r.uniform(0, 0.02), toe), (0.02, grit))


def build():
    s = {}
    s["pistol_shot"] = gunshot(0.75, 0.010, 0.050, (210, 70), 0.22, 0.20, 3.0)
    s["shotgun_shot"] = gunshot(1.25, 0.016, 0.105, (140, 42), 0.42, 0.30, 4.2)
    s["dry_fire"] = click([(2100, 1.0), (3900, 0.5)], 0.006, 4200, 0.06, 0.55)
    s["mag_out"] = mix(0.3, (0.0, click([(900, 1.0), (1700, 0.6)], 0.012, 2200, 0.1, 0.8)),
                       (0.05, band(noise(0.2), 600, 3000) * decay(0.2, 0.05) * 0.18))
    s["mag_in"] = mix(0.3, (0.0, band(noise(0.1), 500, 2500) * decay(0.1, 0.03) * 0.2),
                      (0.07, click([(700, 1.0), (1350, 0.7), (2600, 0.4)], 0.016, 2000, 0.14, 1.0)))
    s["slide_rack"] = mix(0.34, (0.0, click([(1500, 1.0), (2900, 0.5)], 0.010, 3200, 0.09, 0.8)),
                          (0.02, band(noise(0.1), 1500, 6000) * decay(0.1, 0.04) * 0.2),
                          (0.13, click([(1100, 1.0), (2300, 0.7)], 0.014, 2600, 0.12, 1.0)))
    s["pump_back"] = mix(0.24, (0.0, band(noise(0.12), 900, 5000) * np.linspace(0.3, 1, int(SR * 0.12)) * 0.25),
                         (0.085, click([(620, 1.0), (1240, 0.7), (2500, 0.3)], 0.018, 1800, 0.14, 1.0)))
    s["pump_forward"] = mix(0.24, (0.0, band(noise(0.1), 900, 5000) * np.linspace(0.3, 1, int(SR * 0.1)) * 0.22),
                            (0.07, click([(780, 1.0), (1560, 0.7), (3100, 0.3)], 0.016, 2400, 0.14, 1.0)))
    s["shell_insert"] = mix(0.26, (0.0, band(noise(0.08), 800, 4000) * decay(0.08, 0.03) * 0.25),
                            (0.05, click([(950, 1.0), (1900, 0.5)], 0.012, 2000, 0.12, 0.8)))
    s["equip"] = mix(0.3, (0.0, band(noise(0.22), 400, 3500) * np.sin(np.pi * t(0.22) / 0.22) * 0.22),
                     (0.15, click([(1300, 1.0), (2700, 0.4)], 0.008, 2600, 0.08, 0.5)))
    whoosh = band(noise(0.36), 250, 2600) * np.sin(np.pi * t(0.36) / 0.36) ** 2.5
    s["pipe_swing"] = whoosh * 0.55 + band(noise(0.36), 2000, 7000) * np.sin(np.pi * t(0.36) / 0.36) ** 6 * 0.12
    s["pipe_hit"] = np.tanh(mix(0.6, (0.0, band(noise(0.12), 80, 1500) * decay(0.12, 0.02) * 2.2),
                                (0.0, ring(0.6, [(430, 1.0), (1130, 0.7), (2290, 0.45), (3610, 0.25)], 0.11) * 0.55)) * 1.6)
    s["hit_flesh"] = np.tanh(mix(0.3, (0.0, band(noise(0.2), 60, 900) * decay(0.2, 0.03) * 2.4),
                                 (0.0, sweep(0.2, 130, 55, 0.05) * 1.2),
                                 (0.01, band(noise(0.12), 1200, 5000) * decay(0.12, 0.02) * 0.25)) * 1.5)
    s["impact"] = mix(0.3, (0.0, band(noise(0.06), 1500, 10000) * decay(0.06, 0.006) * 1.2),
                      (0.0, band(noise(0.25), 300, 3000) * decay(0.25, 0.04) * 0.5))
    s["shell_tink"] = ring(0.35, [(3300, 1.0), (5200, 0.6), (7900, 0.3)], 0.05) * 0.45
    s["hitmarker"] = ring(0.07, [(1900, 1.0), (2850, 0.5)], 0.012) * 0.5
    for i in range(4):
        s[f"footstep_{i + 1}"] = step(20 + i)
    s["land"] = np.tanh(mix(0.4, (0.0, band(noise(0.3), 40, 500) * decay(0.3, 0.05) * 2.2),
                            (0.0, sweep(0.25, 110, 45, 0.06) * 1.0),
                            (0.02, band(noise(0.25), 1500, 7000) * decay(0.25, 0.05) * 0.18)) * 1.3)
    scrape = band(noise(1.0), 300, 5000) * (0.35 + 0.65 * decay(1.0, 0.35))
    s["slide"] = scrape * np.minimum(t(1.0) / 0.04, 1) * np.minimum((1.0 - t(1.0)) / 0.25, 1) * 0.45
    s["mantle"] = mix(0.5, (0.0, band(noise(0.3), 300, 3000) * np.sin(np.pi * t(0.3) / 0.3) * 0.25),
                      (0.22, band(noise(0.2), 60, 600) * decay(0.2, 0.03) * 1.2))
    return s


def main():
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "fps/assets/sfx")
    out.mkdir(parents=True, exist_ok=True)
    for name, sig in build().items():
        sig = np.asarray(sig, dtype=np.float64)
        fade = min(len(sig), int(SR * 0.004))
        sig[-fade:] *= np.linspace(1, 0, fade)
        sig = sig / max(np.abs(sig).max(), 1e-9) * 0.89
        with wave.open(str(out / f"{name}.wav"), "wb") as w:
            w.setnchannels(1)
            w.setsampwidth(2)
            w.setframerate(SR)
            w.writeframes((sig * 32767).astype("<i2").tobytes())
    print("wrote", len(build()), "files to", out)


if __name__ == "__main__":
    main()
