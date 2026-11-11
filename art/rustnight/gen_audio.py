#!/usr/bin/env python3
"""Synthesises every sound effect used by RUSTNIGHT (no samples, numpy + scipy only).

Usage: python3 art/rustnight/gen_audio.py      (needs: pip install numpy scipy)
Output: rustnight/sfx/*.wav  (22.05 kHz, 16-bit mono, deterministic)
"""
import os
import wave
import numpy as np
from scipy import signal

SR = 22050
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "rustnight", "sfx")
os.makedirs(OUT, exist_ok=True)
_seed = [1000]


def R():
    _seed[0] += 1
    return np.random.default_rng(_seed[0])


def t_(dur):
    return np.arange(int(SR * dur)) / SR


def noise(dur):
    return R().uniform(-1, 1, int(SR * dur))


def lp(x, f, order=2):
    b, a = signal.butter(order, min(f, SR / 2 - 100) / (SR / 2), "low")
    return signal.lfilter(b, a, x)


def hp(x, f, order=2):
    b, a = signal.butter(order, f / (SR / 2), "high")
    return signal.lfilter(b, a, x)


def bp(x, f0, f1, order=2):
    b, a = signal.butter(order, [f0 / (SR / 2), min(f1, SR / 2 - 100) / (SR / 2)], "band")
    return signal.lfilter(b, a, x)


def env(dur, a=0.002, d=0.2, curve=4.0):
    t = t_(dur)
    e = np.exp(-t / d * curve)
    att = np.clip(t / max(a, 1e-5), 0, 1)
    return e * att


def fade(x, fin=0.005, fout=0.02):
    n = len(x)
    a = int(SR * fin)
    b = int(SR * fout)
    if a:
        x[:a] *= np.linspace(0, 1, a)
    if b:
        x[n - b:] *= np.linspace(1, 0, b)
    return x


def mix(*parts):
    n = max(len(p) for p in parts)
    out = np.zeros(n)
    for p in parts:
        out[:len(p)] += p
    return out


def at(x, delay, total=None):
    pad = np.zeros(int(SR * delay))
    out = np.concatenate([pad, x])
    if total:
        out = np.concatenate([out, np.zeros(max(0, int(SR * total) - len(out)))])
    return out


def crush(x, bits=8):
    q = 2 ** (bits - 1)
    return np.round(x * q) / q


def save(name, x, vol=0.9, norm=True):
    x = np.nan_to_num(x)
    if norm:
        x = x / (np.max(np.abs(x)) + 1e-9)
    x = np.clip(x * vol, -1, 1)
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes((x * 32767).astype("<i2").tobytes())


def loopify(x, xf=0.6):
    """Crossfade the tail into the head so the clip loops seamlessly."""
    n = int(SR * xf)
    head, tail = x[:n], x[-n:]
    k = np.linspace(0, 1, n)
    body = x[n:-n]
    return np.concatenate([tail * (1 - k) + head * k, body])


def sweep(f0, f1, dur, shape=1.0):
    t = t_(dur)
    k = (t / dur) ** shape
    f = f0 + (f1 - f0) * k
    return np.sin(2 * np.pi * np.cumsum(f) / SR)


# --- guns ------------------------------------------------------------------------
def gunshot(dur, crack_f, body_f, tail, boom=0.6, crackv=1.0):
    crack = hp(noise(dur), crack_f) * env(dur, 0.0005, 0.03) * crackv
    body = bp(noise(dur), 200, body_f) * env(dur, 0.001, tail)
    thump = sweep(160, 40, dur, 0.4) * env(dur, 0.001, 0.09) * boom
    echo = at(lp(noise(dur), 1400) * env(dur, 0.02, tail * 1.6) * 0.25, 0.06)
    x = mix(crack, body, thump, echo)
    return fade(np.tanh(x * 2.2), 0, 0.05)


def guns():
    save("shot_pistol", gunshot(0.55, 2500, 3200, 0.16, 0.8))
    save("shot_smg", gunshot(0.42, 3000, 4200, 0.11, 0.5), 0.85)
    save("shot_rifle", gunshot(0.75, 2200, 2800, 0.22, 0.9, 1.3))
    save("shot_shotgun", gunshot(1.0, 1500, 2000, 0.32, 1.4), 0.95)
    # distant gunfire for ambience
    d = lp(gunshot(0.9, 2200, 2800, 0.25), 700)
    save("shot_far", mix(d, at(d * 0.4, 0.21)), 0.5)
    save("dry", mix(hp(noise(0.05), 3000) * env(0.05, 0.0005, 0.012), sweep(900, 600, 0.05) * env(0.05, 0.0005, 0.01) * 0.4), 0.5)

    def click(f, d=0.05, v=1.0):
        return (bp(noise(d), f * 0.6, f * 1.6) * env(d, 0.0005, d * 0.3) + sweep(f, f * 0.6, d) * env(d, 0.0005, d * 0.2) * 0.4) * v

    save("mag_out", mix(click(1400, 0.07), at(click(700, 0.09, 0.7), 0.06), at(lp(noise(0.15), 2500) * env(0.15, 0.02, 0.08) * 0.3, 0.02)), 0.6)
    save("mag_in", mix(lp(noise(0.12), 2000) * env(0.12, 0.03, 0.07) * 0.3, at(click(900, 0.08), 0.1), at(click(1800, 0.05, 0.8), 0.16)), 0.65)
    save("slide", mix(click(1500, 0.06), at(bp(noise(0.1), 800, 3000) * env(0.1, 0.01, 0.06) * 0.6, 0.03), at(click(2000, 0.06, 1.2), 0.13)), 0.65)
    save("pump", mix(click(600, 0.1), at(bp(noise(0.12), 500, 2500) * env(0.12, 0.01, 0.08) * 0.7, 0.02), at(click(800, 0.1, 1.2), 0.2), at(bp(noise(0.12), 500, 2500) * env(0.12, 0.01, 0.08) * 0.6, 0.2)), 0.7)
    save("shell_in", mix(click(1100, 0.06, 0.8), at(click(500, 0.08), 0.05)), 0.55)
    save("shell_drop", mix(*[at(sweep(4200 + i * 300, 3800, 0.06) * env(0.06, 0.0005, 0.02) * (0.8 ** i), 0.07 * i * (1 + i * 0.3)) for i in range(4)]), 0.25)
    save("swing", bp(noise(0.3), 300, 1800) * np.sin(np.pi * t_(0.3) / 0.3) ** 2, 0.5)
    save("equip", mix(lp(noise(0.2), 1800) * env(0.2, 0.05, 0.1) * 0.5, at(click(1200, 0.05, 0.6), 0.14)), 0.45)


# --- impacts ---------------------------------------------------------------------
def impacts():
    save("hit_flesh", mix(lp(noise(0.16), 900) * env(0.16, 0.001, 0.05), sweep(140, 60, 0.16) * env(0.16, 0.001, 0.05) * 0.8, at(bp(noise(0.12), 1500, 4000) * env(0.12, 0.005, 0.05) * 0.25, 0.01)), 0.75)
    save("hit_head", mix(lp(noise(0.2), 1400) * env(0.2, 0.001, 0.05), sweep(260, 70, 0.2) * env(0.2, 0.001, 0.06), at(bp(noise(0.2), 900, 5000) * env(0.2, 0.01, 0.1) * 0.5, 0.015)), 0.85)
    save("hit_wall", mix(hp(noise(0.14), 1800) * env(0.14, 0.0005, 0.03), lp(noise(0.14), 700) * env(0.14, 0.001, 0.05) * 0.7), 0.5)
    save("hit_metal", mix(*[sweep(f, f * 0.98, 0.3) * env(0.3, 0.0005, 0.12) * a for f, a in ((1830, 1), (2710, 0.6), (4120, 0.4))], hp(noise(0.05), 3000) * env(0.05, 0.0005, 0.01)), 0.45)
    save("hitmarker", mix(sweep(2400, 2000, 0.05) * env(0.05, 0.0005, 0.02), hp(noise(0.03), 5000) * env(0.03, 0.0005, 0.008) * 0.5), 0.35)
    save("headshot", mix(sweep(3100, 2600, 0.09) * env(0.09, 0.0005, 0.04), sweep(1500, 1200, 0.09) * env(0.09, 0.0005, 0.03) * 0.6, hp(noise(0.03), 5000) * env(0.03, 0.0005, 0.008) * 0.5), 0.4)
    save("kill", mix(sweep(1700, 900, 0.14) * env(0.14, 0.0005, 0.06), lp(noise(0.14), 1200) * env(0.14, 0.001, 0.06) * 0.6), 0.4)
    g = hp(noise(0.9), 2500) * env(0.9, 0.001, 0.3)
    tink = mix(*[at(sweep(R().uniform(3000, 7000), 2500, 0.08) * env(0.08, 0.0005, 0.03) * R().uniform(0.2, 0.7), R().uniform(0.02, 0.7)) for _ in range(22)])
    save("glass_break", mix(g, tink, lp(noise(0.1), 500) * env(0.1, 0.001, 0.04)), 0.8)
    save("door_bang", mix(lp(noise(0.3), 500) * env(0.3, 0.001, 0.09), sweep(110, 50, 0.3) * env(0.3, 0.001, 0.1) * 1.2, at(bp(noise(0.2), 400, 1600) * env(0.2, 0.002, 0.06) * 0.3, 0.01)), 0.9)
    crackle = mix(*[at(hp(noise(0.04), 1000) * env(0.04, 0.0005, 0.012) * R().uniform(0.4, 1), R().uniform(0, 0.4)) for _ in range(14)])
    save("wood_break", mix(crackle, lp(noise(0.5), 700) * env(0.5, 0.001, 0.15), sweep(180, 60, 0.3) * env(0.3, 0.001, 0.1)), 0.9)
    creak = sweep(300, 520, 0.5, 0.7) * (0.5 + 0.5 * np.sign(np.sin(2 * np.pi * 37 * t_(0.5)))) * np.sin(np.pi * t_(0.5) / 0.5)
    save("door_open", mix(bp(creak, 250, 2500) * 0.6, at(lp(noise(0.08), 1500) * env(0.08, 0.001, 0.03), 0.0)), 0.4)
    save("door_close", mix(lp(noise(0.2), 700) * env(0.2, 0.001, 0.06), sweep(150, 70, 0.2) * env(0.2, 0.001, 0.06), at(hp(noise(0.04), 2000) * env(0.04, 0.0005, 0.01) * 0.5, 0.02)), 0.6)
    save("hammer", mix(*[at(mix(lp(noise(0.12), 1200) * env(0.12, 0.0005, 0.03), sweep(900, 500, 0.08) * env(0.08, 0.0005, 0.02) * 0.6), i * 0.26) for i in range(3)]), 0.75)


# --- footsteps -----------------------------------------------------------------
def steps():
    for i in range(4):
        d = 0.16
        heel = lp(noise(d), 700 + i * 90) * env(d, 0.002, 0.035)
        toe = at(bp(noise(0.1), 800, 3200) * env(0.1, 0.002, 0.03) * 0.5, 0.045 + i * 0.004)
        save("step_hard%d" % i, mix(heel, toe), 0.4)
        thud = sweep(130 + i * 12, 70, 0.2) * env(0.2, 0.002, 0.05)
        knock = lp(noise(0.2), 1100) * env(0.2, 0.002, 0.04) * 0.7
        save("step_wood%d" % i, mix(thud, knock, at(bp(sweep(400, 480, 0.12), 300, 900) * env(0.12, 0.02, 0.06) * 0.08 * (i % 2), 0.03)), 0.45)
        sw = bp(noise(0.24), 1200 + i * 150, 6000) * (np.sin(np.pi * t_(0.24) / 0.24) ** 1.5)
        save("step_grass%d" % i, mix(sw * 0.7, lp(noise(0.12), 300) * env(0.12, 0.004, 0.04)), 0.3)
    save("land", mix(lp(noise(0.25), 500) * env(0.25, 0.002, 0.07), sweep(120, 50, 0.25) * env(0.25, 0.002, 0.08)), 0.6)
    save("slide_move", bp(noise(0.7), 500, 3500) * env(0.7, 0.03, 0.3), 0.4)


# --- voices (formant-ish synthesis) ------------------------------------------------
def voice(dur, f0, f1, formants, rasp=0.5, wob=7.0, wobd=0.06, seed_env=None):
    t = t_(dur)
    f = np.linspace(f0, f1, len(t)) * (1 + wobd * np.sin(2 * np.pi * wob * t + R().uniform(0, 6)) + 0.03 * lp(noise(dur), 12) * 8)
    ph = np.cumsum(f) / SR
    src = 2 * (ph % 1) - 1                                # saw
    src = src * (1 - rasp) + src * (0.5 + 0.5 * np.sign(np.sin(2 * np.pi * ph * 0.5))) * rasp   # sub-harmonic rasp
    src += noise(dur) * 0.25 * rasp
    out = np.zeros(len(t))
    for fc, bw, g in formants:
        out += bp(src, max(60, fc - bw), fc + bw) * g
    e = seed_env if seed_env is not None else np.sin(np.pi * t / dur) ** 0.7
    return out * e


def voices():
    A = ((650, 120, 1.0), (1100, 160, 0.6), (2500, 300, 0.2))
    O = ((420, 100, 1.0), (800, 140, 0.5), (2300, 300, 0.15))
    U = ((320, 90, 1.0), (700, 120, 0.4), (2200, 300, 0.1))
    E = ((520, 110, 1.0), (1800, 200, 0.5), (2600, 300, 0.25))
    save("z_moan0", voice(1.6, 95, 78, O, 0.6), 0.6)
    save("z_moan1", voice(1.2, 110, 90, U, 0.7, 5), 0.6)
    save("z_moan2", voice(2.0, 82, 70, A, 0.8, 9, 0.1), 0.6)
    save("z_moan3", voice(1.0, 130, 100, O, 0.5, 11, 0.12), 0.6)
    save("z_alert0", voice(0.8, 120, 190, A, 0.9, 14, 0.1), 0.8)
    save("z_alert1", voice(0.7, 150, 210, E, 0.9, 17, 0.12), 0.8)
    save("z_attack0", voice(0.45, 170, 120, A, 1.0, 22, 0.15), 0.85)
    save("z_attack1", voice(0.4, 200, 130, E, 1.0, 25, 0.15), 0.85)
    save("z_die0", voice(0.9, 130, 60, O, 0.8, 6, 0.08, env(0.9, 0.01, 0.4, 2.5)), 0.7)
    save("z_die1", voice(0.7, 150, 70, U, 0.9, 8, 0.08, env(0.7, 0.01, 0.3, 2.5)), 0.7)
    # runner: fast wet chattering
    ch = voice(0.9, 240, 200, E, 1.0, 31, 0.25) * (0.55 + 0.45 * np.sign(np.sin(2 * np.pi * 13 * t_(0.9))))
    save("z_runner", ch, 0.8)
    # screamer: long rising shriek with a second detuned voice
    s1 = voice(2.6, 420, 980, ((1100, 300, 1.0), (2600, 500, 0.8), (3900, 600, 0.5)), 0.7, 23, 0.06)
    s2 = voice(2.6, 433, 1010, ((1300, 300, 1.0), (2900, 500, 0.7)), 0.9, 29, 0.08)
    save("z_scream", np.tanh(mix(s1, s2) * 1.5), 0.95)
    save("z_scream_far", lp(np.tanh(mix(s1, s2) * 1.5), 900), 0.4)
    # sobbing heard before you see the screamer
    sob = np.concatenate([np.concatenate([voice(0.22, 330 - i * 12, 250, ((700, 150, 1.0), (1500, 200, 0.4)), 0.2, 9, 0.05), np.zeros(int(SR * (0.1 + 0.05 * (i % 3))))]) for i in range(7)])
    save("z_sob", lp(sob, 1800), 0.5)
    # player
    save("hurt0", mix(voice(0.28, 210, 150, A, 0.4, 5, 0.03, env(0.28, 0.005, 0.1, 2.5)), lp(noise(0.1), 800) * env(0.1, 0.001, 0.03) * 0.6), 0.6)
    save("hurt1", mix(voice(0.34, 190, 130, O, 0.4, 5, 0.03, env(0.34, 0.005, 0.12, 2.5)), lp(noise(0.1), 800) * env(0.1, 0.001, 0.03) * 0.6), 0.6)
    br = bp(noise(1.4), 400, 2200) * (np.sin(np.pi * t_(1.4) / 0.7) ** 2) * np.where(t_(1.4) < 0.7, 0.6, 1.0)
    save("breath", br, 0.25)
    hb = mix(sweep(70, 40, 0.14) * env(0.14, 0.004, 0.05), at(sweep(60, 36, 0.16) * env(0.16, 0.004, 0.06) * 0.8, 0.22, 0.9))
    save("heartbeat", lp(hb, 200), 0.9)
    # whispers for hallucinations
    wh = np.zeros(int(SR * 3.0))
    for i in range(14):
        d = R().uniform(0.08, 0.22)
        s = bp(noise(d), R().uniform(900, 1800), R().uniform(2800, 5200)) * np.sin(np.pi * t_(d) / d)
        p = int(R().uniform(0, 2.7) * SR)
        wh[p:p + len(s)] += s[:len(wh) - p] * R().uniform(0.3, 1)
    save("whisper", wh, 0.35)


# --- item / ui -------------------------------------------------------------------
def ui():
    rustle = bp(noise(0.28), 900, 5000) * (0.4 + 0.6 * np.abs(lp(noise(0.28), 30) * 6)) * np.sin(np.pi * t_(0.28) / 0.28)
    save("pickup", rustle, 0.35)
    save("search", np.concatenate([bp(noise(0.2), 700, 4000) * np.sin(np.pi * t_(0.2) / 0.2) * R().uniform(0.5, 1) for _ in range(4)]), 0.3)
    crunch = np.concatenate([mix(bp(noise(0.09), 600, 3500) * env(0.09, 0.002, 0.03), np.zeros(int(SR * 0.19))) for _ in range(4)])
    save("eat", crunch, 0.45)
    gulp = np.concatenate([mix(sweep(240, 520, 0.11, 2) * env(0.11, 0.01, 0.06, 2), np.zeros(int(SR * 0.3))) for _ in range(3)])
    save("drink", lp(gulp, 1200), 0.45)
    save("bandage", np.concatenate([bp(noise(0.3), 1500, 6000) * np.sin(np.pi * t_(0.3) / 0.3) ** 0.5 * 0.8, np.zeros(2000), hp(noise(0.12), 2500) * env(0.12, 0.001, 0.04)]), 0.35)
    save("pills", mix(*[at(sweep(R().uniform(2200, 3600), 2000, 0.03) * env(0.03, 0.0005, 0.01), R().uniform(0, 0.25)) for _ in range(10)]), 0.3)
    save("click", mix(sweep(1300, 900, 0.03) * env(0.03, 0.0005, 0.01), hp(noise(0.02), 3000) * env(0.02, 0.0005, 0.005) * 0.5), 0.3)
    save("flashlight", mix(sweep(1900, 1400, 0.025) * env(0.025, 0.0005, 0.008), at(sweep(900, 700, 0.03) * env(0.03, 0.0005, 0.01), 0.03)), 0.35)
    save("deny", mix(sweep(220, 180, 0.12) * env(0.12, 0.002, 0.06, 2), at(sweep(180, 150, 0.14) * env(0.14, 0.002, 0.07, 2), 0.13)), 0.3)
    stingt = t_(2.4)
    sting = sum(np.sin(2 * np.pi * f * stingt * (1 + 0.004 * np.sin(2 * np.pi * 5 * stingt))) for f in (110, 116.5, 164.8, 233.1, 246.9)) * env(2.4, 0.005, 1.0, 2.0)
    save("sting", mix(sting, hp(noise(2.4), 3000) * env(2.4, 0.3, 0.9, 2) * 0.6), 0.6)
    eng = sweep(40, 90, 2.5, 2) * 0.6 + lp(noise(2.5), 300) * (0.5 + 0.5 * np.sign(np.sin(2 * np.pi * np.cumsum(np.linspace(8, 30, int(SR * 2.5))) / SR)))
    save("engine", fade(np.tanh(eng * 2), 0.05, 0.4), 0.7)
    cough = mix(*[at(bp(noise(0.16), 300, 2500) * env(0.16, 0.003, 0.05) * a, d) for d, a in ((0, 1), (0.22, 0.8), (0.4, 0.5))])
    save("cough", cough, 0.5)


# --- loops -------------------------------------------------------------------------
def loops():
    d = 9.0
    t = t_(d)
    gust = 0.45 + 0.55 * np.clip(lp(noise(d), 0.35) * 14 + 0.5, 0, 1)
    w = bp(noise(d), 180, 900) * gust + bp(noise(d), 500, 1600) * gust ** 2 * 0.4
    wl = sum(np.sin(2 * np.pi * f * t * (1 + 0.01 * np.sin(2 * np.pi * 0.21 * t + i))) * 0.03 for i, f in enumerate((310, 466)))
    save("amb_wind", loopify(w + wl * gust), 0.5)

    d = 12.0
    t = t_(d)
    dr = np.zeros(len(t))
    for i, f in enumerate((55, 55.6, 82.4, 110.3, 164)):
        dr += np.sin(2 * np.pi * f * t + i) * (0.5 + 0.5 * np.sin(2 * np.pi * (0.05 + 0.031 * i) * t + i * 2)) / (1 + i * 0.5)
    hi = bp(noise(d), 1800, 2600) * (0.5 + 0.5 * np.sin(2 * np.pi * 0.083 * t)) ** 4 * 0.08
    metal = sum(np.sin(2 * np.pi * f * t) * np.clip(np.sin(2 * np.pi * 0.0833 * t + p), 0, 1) ** 6 * 0.05 for f, p in ((623, 0), (881, 2), (1319, 4)))
    save("amb_drone", loopify(lp(dr, 500) + hi + metal, 1.0), 0.55)

    d = 6.0
    rn = hp(noise(d), 1500) * 0.5 + bp(noise(d), 300, 1500) * 0.5
    drops = np.zeros(int(SR * d))
    for _ in range(260):
        p = int(R().uniform(0, d - 0.05) * SR)
        s = sweep(R().uniform(1800, 5200), 1500, 0.02) * env(0.02, 0.0005, 0.006) * R().uniform(0.1, 0.5)
        drops[p:p + len(s)] += s
    save("amb_rain", loopify(rn * 0.6 + drops, 0.5), 0.5)

    d = 6.0
    t = t_(d)
    cr = np.zeros(len(t))
    for f, rate, ph in ((4300, 23, 0), (4700, 19, 1.3), (3900, 27, 2.1)):
        chirp = np.clip(np.sin(2 * np.pi * rate * t + ph), 0, 1) ** 3 * np.clip(np.sin(2 * np.pi * (rate / 16) * t + ph), 0, 1) ** 2
        cr += np.sin(2 * np.pi * f * t) * chirp
    save("amb_night", loopify(cr * 0.5 + lp(noise(d), 200) * 0.3, 0.5), 0.2)

    th = lp(noise(4.5), 180) * env(4.5, 0.05, 2.0, 2.5) + at(lp(noise(2.0), 900) * env(2.0, 0.002, 0.3) * 0.7, 0.0, 4.5)[:int(SR * 4.5)]
    save("thunder", th, 0.9)


def main():
    guns(); impacts(); steps(); voices(); ui(); loops()
    print("sfx written to", OUT, len(os.listdir(OUT)))


if __name__ == "__main__":
    main()
