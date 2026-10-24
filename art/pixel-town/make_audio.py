"""Synthesizes the soft sound effects and ambient loops for 像素小镇.

Run from the repo root:  python3 art/pixel-town/make_audio.py
Writes 16-bit mono WAV files to pixel-town/assets/audio/.
Everything is generated from sine waves and filtered noise with a fixed seed,
so the output is reproducible and needs no third-party assets.
"""
import array
import math
import os
import random
import wave

RATE = 22050
OUT = os.path.join(os.path.dirname(__file__), "..", "..", "pixel-town", "assets", "audio")
rng = random.Random(7)


def silence(seconds):
    return [0.0] * int(seconds * RATE)


def env(i, n, attack=0.005, release=None):
    """Linear attack, exponential-ish decay envelope."""
    t = i / RATE
    a = min(1.0, t / attack) if attack > 0 else 1.0
    rel = release if release is not None else n / RATE
    return a * math.exp(-4.0 * t / rel)


def tone(freq, seconds, volume=0.5, attack=0.005, release=None, freq_end=None, harmonics=(1.0,)):
    n = int(seconds * RATE)
    out, phase = [], 0.0
    for i in range(n):
        f = freq if freq_end is None else freq + (freq_end - freq) * i / n
        phase += 2 * math.pi * f / RATE
        s = sum(h * math.sin(phase * (k + 1)) for k, h in enumerate(harmonics))
        out.append(volume * s * env(i, n, attack, release))
    return out


def noise(seconds, volume=0.5, smooth=0.0, attack=0.005, release=None):
    """White noise through a one-pole low-pass (smooth 0..1, higher = darker)."""
    n = int(seconds * RATE)
    out, y = [], 0.0
    for i in range(n):
        y = smooth * y + (1 - smooth) * rng.uniform(-1, 1)
        out.append(volume * y * env(i, n, attack, release))
    return out


def mix(*tracks, offsets=None):
    offsets = offsets or [0.0] * len(tracks)
    n = max(int(o * RATE) + len(t) for t, o in zip(tracks, offsets))
    out = [0.0] * n
    for t, o in zip(tracks, offsets):
        start = int(o * RATE)
        for i, s in enumerate(t):
            out[start + i] += s
    return out


def save(name, samples, peak=0.8):
    m = max(1e-6, max(abs(s) for s in samples))
    scale = peak / m if m > peak else 1.0
    data = array.array("h", (int(max(-1, min(1, s * scale)) * 32767) for s in samples))
    os.makedirs(OUT, exist_ok=True)
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(data.tobytes())
    print(f"{name}.wav  {len(samples) / RATE:.2f}s")


def make_loop(samples, fade_seconds=1.0):
    """Crossfades the tail into the head so the clip loops without a click."""
    f = int(fade_seconds * RATE)
    head, body, tail = samples[:f], samples[f:-f], samples[-f:]
    blended = [tail[i] * (1 - i / f) + head[i] * (i / f) for i in range(f)]
    return body + blended


def chirp(start, seconds, f0, f1, volume):
    n = int(seconds * RATE)
    out, phase = [], 0.0
    for i in range(n):
        x = i / n
        f = f0 + (f1 - f0) * math.sin(x * math.pi)          # up and back down
        phase += 2 * math.pi * f / RATE
        out.append(volume * math.sin(phase) * math.sin(math.pi * x) ** 2)
    return out


def wind(seconds, volume):
    n = int(seconds * RATE)
    out, y, y2 = [], 0.0, 0.0
    for i in range(n):
        y = 0.995 * y + 0.005 * rng.uniform(-1, 1)        # brown-ish noise
        y2 = 0.9 * y2 + 0.1 * y
        swell = 0.6 + 0.4 * math.sin(2 * math.pi * i / RATE / 6.0 + 1.3 * math.sin(i / RATE / 2.7))
        out.append(volume * 12 * y2 * swell)
    return out


def sfx():
    save("hoe", mix(tone(110, 0.16, 0.7, release=0.08, freq_end=70),
                    noise(0.18, 0.5, smooth=0.6, release=0.07)))
    save("seed", mix(*[tone(f, 0.07, 0.35, release=0.04) for f in (660, 880, 1100)],
                     offsets=[0.0, 0.07, 0.14]))
    drops = [tone(rng.uniform(900, 1500), 0.06, 0.12, release=0.03, freq_end=rng.uniform(1600, 2200))
             for _ in range(7)]
    save("water", mix(noise(0.55, 0.35, smooth=0.3, attack=0.08, release=0.3), *drops,
                      offsets=[0.0] + [0.05 + 0.06 * k for k in range(7)]))
    save("harvest", mix(tone(220, 0.08, 0.5, release=0.04, freq_end=330),
                        tone(784, 0.35, 0.3, release=0.2, harmonics=(1.0, 0.3)),
                        tone(1175, 0.4, 0.3, release=0.25, harmonics=(1.0, 0.3)),
                        offsets=[0.0, 0.06, 0.14]))
    save("ship", mix(tone(140, 0.12, 0.6, release=0.06, freq_end=90),
                     noise(0.1, 0.3, smooth=0.7, release=0.05),
                     tone(988, 0.3, 0.25, release=0.2), offsets=[0, 0, 0.1]))
    save("coin", mix(tone(1976, 0.12, 0.3, release=0.08, harmonics=(1.0, 0.2)),
                     tone(2637, 0.45, 0.3, release=0.3, harmonics=(1.0, 0.2)),
                     offsets=[0.0, 0.09]))
    save("talk", tone(620, 0.07, 0.35, release=0.04, harmonics=(1.0, 0.25)))
    save("step", noise(0.09, 0.4, smooth=0.75, attack=0.004, release=0.04))
    save("ui", tone(880, 0.05, 0.3, release=0.03))
    save("sleep", mix(*[tone(f, 0.9, 0.22, attack=0.02, release=0.6, harmonics=(1.0, 0.3))
                        for f in (784, 659, 523, 392)], offsets=[0.0, 0.25, 0.5, 0.75]))
    save("wake", mix(*[tone(f, 0.7, 0.2, attack=0.02, release=0.5, harmonics=(1.0, 0.3))
                       for f in (523, 659, 784)], offsets=[0.0, 0.18, 0.36]))


def ambience():
    length = 16.0
    day = wind(length + 1.0, 0.35)
    for _ in range(14):                                  # little bird phrases
        t = rng.uniform(0.3, length - 0.5)
        base = rng.uniform(2600, 3600)
        for k in range(rng.randint(2, 4)):
            c = chirp(0, rng.uniform(0.07, 0.12), base, base * rng.uniform(1.2, 1.45), 0.12)
            start = int((t + k * 0.13) * RATE)
            for i, s in enumerate(c):
                if start + i < len(day):
                    day[start + i] += s
    save("amb_day", make_loop(day), peak=0.5)

    night = wind(length + 1.0, 0.18)
    for cricket, rate_hz, pitch in ((0, 3.1, 4300), (1, 2.3, 3900)):
        n = len(night)
        for i in range(n):
            t = i / RATE
            pulse_train = max(0.0, math.sin(2 * math.pi * 30 * t)) ** 4      # fast trill
            group = max(0.0, math.sin(2 * math.pi * rate_hz * t + cricket)) ** 6
            night[i] += 0.07 * pulse_train * group * math.sin(2 * math.pi * pitch * t)
    save("amb_night", make_loop(night), peak=0.45)


if __name__ == "__main__":
    sfx()
    ambience()
