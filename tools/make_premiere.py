"""Score and encode the BlackForgeMC premiere (frames from `gradle premiere`, see Premiere.java).

    python tools/make_premiere.py main|network|hero|short|all [--audio-only]

Reads   plugins-src/AlloyCore/previews/premiere/<product>/f00000.png ...
Writes  website/assets/video/blackforge-trailer.mp4     (the whole network, ~93 s)
        website/assets/video/blackforge-colosseum.mp4   (the Colosseum film, ~105 s)  - each also as -720.mp4
        website/assets/video/blackforge-hero.mp4        (1920x1080, 20 s seamless loop, no audio)
        website/assets/video/blackforge-short.mp4       (1080x1920, ~30 s, H.264 + AAC)

All audio is synthesized here from scratch (no samples, nothing licensed): a dark orchestral bed (low drone, string pads,
brass swells, timpani and war drums), forge hammers and sword clashes locked to the picture, crowd roar, embers and an
impact on every cut. Event times are computed from the same clip tables as Premiere.java.
"""
import math
import random
import struct
import os
import subprocess
import sys
import wave
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FRAMES = ROOT / "plugins-src" / "AlloyCore" / "previews" / "premiere"
VIDEO = ROOT / "website" / "assets" / "video"
MASTERS = ROOT / "build" / "masters"   # full-quality encodes, kept off the website (Cloudflare Pages allows 25 MB per file)
BUILD = ROOT / "build"
FPS, RATE = 30, 44100
TWO_PI = 2 * math.pi
NOTE = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}


def freq(semis_from_a4):
    return 440.0 * 2 ** (semis_from_a4 / 12)


def chord(root, kind, octave=0):
    r = NOTE[root] - 9 - 12 + 12 * octave
    third = 3 if kind == "m" else 4
    return [freq(r + o) for o in (0, third, 7, 12, 12 + third)]


class Mix:
    def __init__(self, seconds):
        self.n = int(seconds * RATE)
        self.L = [0.0] * self.n
        self.R = [0.0] * self.n
        self.rnd = random.Random(11)

    def add(self, i, l, r=None):
        if 0 <= i < self.n:
            self.L[i] += l
            self.R[i] += l if r is None else r

    # ---- instruments -------------------------------------------------------------------------------------------

    def drone(self, t0, t1, gain=0.16, f=55.0):
        a, b = int(t0 * RATE), min(self.n, int(t1 * RATE))
        for i in range(a, b):
            x = i / RATE
            env = min(1, (x - t0) / 3.0) * min(1, (t1 - x) / 2.5)
            v = env * gain * (math.sin(TWO_PI * f * x) + 0.6 * math.sin(TWO_PI * f * 1.5 * x + 0.3) + 0.25 * math.sin(TWO_PI * f * 2 * x) * (0.5 + 0.5 * math.sin(TWO_PI * 0.11 * x)))
            self.add(i, v)

    def pad(self, t0, t1, root, kind, gain=0.07, octave=0, bright=1.0, fade=1.2):
        """Soft sawtooth strings: a few harmonics, slow attack, slight stereo detune."""
        fs = chord(root, kind, octave)
        a, b = int(max(0, t0 - fade / 2) * RATE), min(self.n, int((t1 + fade / 2) * RATE))
        for i in range(a, b):
            x = i / RATE
            env = min(1, (x - (t0 - fade / 2)) / fade) * min(1, ((t1 + fade / 2) - x) / fade)
            l = r = 0.0
            for j, f in enumerate(fs):
                for h in (1, 2, 3):
                    amp = 1.0 / h ** (1.3 / bright)
                    l += amp * math.sin(TWO_PI * f * h * 1.002 * x + j) / (1 + 0.1 * j)
                    r += amp * math.sin(TWO_PI * f * h * 0.998 * x + j * 1.3) / (1 + 0.1 * j)
            self.add(i, env * gain * l * 0.35, env * gain * r * 0.35)

    def brass(self, t, dur, root, kind, gain=0.09, octave=0):
        fs = chord(root, kind, octave)[:3]
        a, b = int(t * RATE), min(self.n, int((t + dur + 1.2) * RATE))
        for i in range(a, b):
            x = i / RATE - t
            env = min(1, x / 0.35) * (1 if x < dur else max(0, 1 - (x - dur) / 1.2))
            v = 0.0
            for f in fs:
                ph = (f * x) % 1.0
                saw = 2 * ph - 1
                v += saw * 0.6 + math.sin(TWO_PI * f * x) * 0.4 + 0.2 * math.sin(TWO_PI * f * 2 * x)
            self.add(i + a - a, env * gain * v / 3)

    def timpani(self, t, gain=0.5, f=55.0):
        a = int(t * RATE)
        for k in range(int(1.6 * RATE)):
            x = k / RATE
            env = math.exp(-x * 4.2)
            fr = f * (1 + 0.5 * math.exp(-x * 30))
            v = env * (math.sin(TWO_PI * fr * x) + 0.4 * math.sin(TWO_PI * fr * 1.58 * x) * math.exp(-x * 8))
            n = (self.rnd.random() - 0.5) * math.exp(-x * 60) * 0.7
            self.add(a + k, gain * (v + n))

    def impact(self, t, gain=0.9, long=True):
        """A cinematic hit: sub drop, noise crash, metallic ring."""
        a = int(t * RATE)
        dur = 3.8 if long else 1.2
        lp = 0.0
        for k in range(int(dur * RATE)):
            x = k / RATE
            sub = math.sin(TWO_PI * (38 + 90 * math.exp(-x * 7)) * x) * math.exp(-x * 1.5)
            n = self.rnd.random() * 2 - 1
            lp += (n - lp) * (0.35 if x < 0.3 else 0.08)
            crash = lp * math.exp(-x * 3.0) * 0.8
            ring = (math.sin(TWO_PI * 220 * x) + 0.7 * math.sin(TWO_PI * 331.5 * x) + 0.5 * math.sin(TWO_PI * 523 * x)) * math.exp(-x * 2.4) * 0.14
            self.add(a + k, gain * (sub * 0.9 + crash + ring))

    def clang(self, t, gain=0.5, base=620.0):
        """Metal on metal: inharmonic partials, fast decay, a click."""
        a = int(t * RATE)
        parts = [(1.0, 1.0), (2.76, 0.7), (5.4, 0.5), (8.93, 0.3), (1.51, 0.4)]
        for k in range(int(1.5 * RATE)):
            x = k / RATE
            v = 0.0
            for m, amp in parts:
                v += amp * math.sin(TWO_PI * base * m * x) * math.exp(-x * (6 + 3 * m))
            click = (self.rnd.random() - 0.5) * math.exp(-x * 220)
            pan = 0.5 + 0.4 * math.sin(t * 7.1)
            self.add(a + k, gain * 0.4 * (v + click * 1.5) * (1 - pan * 0.5), gain * 0.4 * (v + click * 1.5) * (0.5 + pan * 0.5))

    def whoosh(self, t0, t1, gain=0.25, rising=True):
        a, b = int(t0 * RATE), min(self.n, int(t1 * RATE))
        lp = 0.0
        for i in range(a, b):
            u = (i - a) / max(1, b - a)
            env = math.sin(math.pi * u) ** 2 if rising else (1 - u) ** 2
            c = 0.02 + 0.45 * (u if rising else 1 - u) ** 2
            lp += ((self.rnd.random() * 2 - 1) - lp) * c
            self.add(i, gain * env * lp)

    def riser(self, t0, t1, gain=0.2):
        a, b = int(t0 * RATE), min(self.n, int(t1 * RATE))
        for i in range(a, b):
            u = (i - a) / max(1, b - a)
            x = i / RATE
            f = 110 * 2 ** (u * 3.2)
            v = (math.sin(TWO_PI * f * x) + 0.5 * math.sin(TWO_PI * f * 1.5 * x)) * u ** 2
            n = (self.rnd.random() - 0.5) * u ** 3
            self.add(i, gain * (v * 0.5 + n * 0.5))

    def crowd(self, t0, t1, gain=0.18, peak=None):
        a, b = int(t0 * RATE), min(self.n, int(t1 * RATE))
        lp1 = lp2 = 0.0
        for i in range(a, b):
            u = (i - a) / max(1, b - a)
            env = math.sin(math.pi * u) ** 1.2
            if peak is not None:
                env = max(0.2, min(1, 1 - abs(u - peak) * 2.2))
            n = self.rnd.random() * 2 - 1
            lp1 += (n - lp1) * 0.05
            lp2 += (lp1 - lp2) * 0.05
            self.add(i, gain * env * (lp1 - lp2) * 5 * (0.7 + 0.3 * math.sin(i / RATE * 3.3)))

    def crackle(self, t0, t1, density=60, gain=0.22):
        for _ in range(int((t1 - t0) * density)):
            t = t0 + self.rnd.random() * (t1 - t0)
            a = int(t * RATE)
            g = gain * self.rnd.random() ** 2
            for k in range(int(0.012 * RATE)):
                self.add(a + k, g * (self.rnd.random() - 0.5) * math.exp(-k / (0.003 * RATE)))

    def pulse(self, t0, t1, root, kind, step=0.25, gain=0.05, octave=1):
        """A driving string ostinato on the chord."""
        fs = chord(root, kind, octave)
        n = int((t1 - t0) / step)
        for s in range(n):
            f = fs[(s * 3) % 3 if s % 4 else 0]
            a = int((t0 + s * step) * RATE)
            lenS = int(step * 0.9 * RATE)
            g = gain * (1 + 0.5 * (s % 4 == 0)) * min(1, 0.4 + (s / max(1, n)))
            for k in range(lenS):
                x = k / RATE
                env = math.exp(-x * 9) * min(1, x / 0.004)
                self.add(a + k, g * env * (math.sin(TWO_PI * f * x) + 0.5 * math.sin(TWO_PI * f * 2 * x)))

    # ---- finishing ---------------------------------------------------------------------------------------------

    def finish(self, fade_in=0.0, fade_out=1.6):
        # a cheap stereo reverb: four combs per side
        for chan, delays in ((self.L, (0.0297, 0.0371, 0.0411, 0.0437)), (self.R, (0.0311, 0.0353, 0.0397, 0.0453))):
            out = list(chan)
            for d in delays:
                n = int(d * RATE)
                fb = 0.62
                buf = [0.0] * n
                for i in range(self.n):
                    j = i % n
                    y = chan[i] + buf[j] * fb
                    buf[j] = y
                    out[i] += y * 0.16
            chan[:] = out
        peak = max(max(abs(v) for v in self.L), max(abs(v) for v in self.R)) or 1
        g = 0.92 / peak
        fo = int(fade_out * RATE)
        fi = int(fade_in * RATE)
        for i in range(self.n):
            e = 1.0
            if fade_out and i > self.n - fo:
                e = (self.n - i) / fo
            if fi and i < fi:
                e = min(e, i / fi)
            self.L[i] = max(-1, min(1, math.tanh(self.L[i] * g * 1.1) * e))
            self.R[i] = max(-1, min(1, math.tanh(self.R[i] * g * 1.1) * e))

    def write(self, path):
        path.parent.mkdir(parents=True, exist_ok=True)
        with wave.open(str(path), "wb") as w:
            w.setnchannels(2)
            w.setsampwidth(2)
            w.setframerate(RATE)
            frames = bytearray()
            for i in range(self.n):
                frames += struct.pack("<hh", int(self.L[i] * 32000), int(self.R[i] * 32000))
            w.writeframes(bytes(frames))


# ------------------------------------------------------------------------------------------------ the film's clip tables
# (shot, duration, sim0, sim1) in the order of Premiere.mainFilm(); the black beat is trimmed from 3.0 s to 1.6 s on encode

MAIN = [("forge", 12), ("kingdom", 6.5), ("market", 5.5), ("hall", 6), ("colosseum", 5.5), ("pit_in", 4.6, 0, 3.6), ("pit_orbit", 5.2, 5.0, 11.2),
        ("pit_close", 3.6, 12.2, 15.2), ("pit_win", 3.6, 16.0, 19.6), ("colosseum_hi", 4.0), ("wilds_air", 4.5), ("wilds_ground", 4.2), ("scrapyard", 4.2), ("koth", 4.2),
        ("plaza", 6.3), ("hub_close", 6.5), ("colosseum_hi", 1.3), ("kingdom", 1.1), ("pit_orbit", 1.3, 6.5, 8.0), ("hub", 1.1), ("pit_close", 1.1, 13.4, 14.5),
        ("wilds_air", 1.0), ("scrapyard", 1.0), ("pit_win", 1.2, 16.6, 17.8), ("colosseum", 1.3), ("black", 1.6), ("end", 6.5)]
BLACK_TRIM = (2952, 2994)   # frames dropped from the 3.0 s black beat on encode (main film): 1.6 s of black stays


def starts(table):
    t, out = 0.0, []
    for c in table:
        out.append(t)
        t += c[1]
    return out, t


def duel_contacts(sim0, sim1):
    """Sword-clash sim times of Premiere.duel(): every 0.85 s from 5.578 in the exchange phase, plus the finishing blow."""
    out = []
    t = 5.578
    while t < 13.0:
        out.append(t)
        t += 0.85
    out.append(13.9)
    return [x for x in out if sim0 <= x <= sim1]


def score_main():
    st, total = starts(MAIN)
    m = Mix(total + 1.0)
    cuts = {c[0] + str(i): st[i] for i, c in enumerate(MAIN)}
    # drone under everything, a pad per scene
    m.drone(0, total + 0.8, 0.15)
    chords = [("A", "m"), ("F", ""), ("C", ""), ("G", ""), ("A", "m"), ("F", ""), ("D", "m"), ("E", ""), ("A", "m"), ("F", ""), ("C", ""), ("G", ""), ("D", "m"), ("E", ""),
              ("F", ""), ("A", "m")]
    for i in range(16):
        m.pad(st[i], st[i] + MAIN[i][1], chords[i][0], chords[i][1], 0.06 + 0.002 * i, 0 if i < 4 else 0)
    # 0-12  the forge
    m.crackle(0.5, 11.5, 55, 0.2)
    m.riser(4.5, 8.15, 0.28)
    m.impact(8.2, 1.0)
    m.clang(8.2, 0.7, 480)
    m.brass(8.2, 3.6, "A", "m", 0.1, -1)
    m.timpani(8.2, 0.9, 49)
    # 12-30  the world: a wide warm lift
    m.whoosh(st[1] - 0.6, st[1] + 0.2, 0.18)
    m.brass(st[1], 5.0, "F", "", 0.05, 0)
    m.whoosh(st[3] - 0.4, st[3] + 0.4, 0.14)
    # 30-56  the Colosseum: war drums, crowd, clashes
    m.whoosh(st[4] - 0.7, st[4] + 0.1, 0.22)
    t0, t1 = st[4], st[9] + MAIN[9][1]
    beat = 0.6
    k = 0
    t = t0
    while t < t1:
        m.timpani(t, 0.42 if k % 4 else 0.62, 55 if k % 2 == 0 else 49)
        k += 1
        t += beat
    m.pulse(st[6], st[7] + MAIN[7][1], "D", "m", 0.2, 0.045)
    m.brass(st[5], 4.0, "D", "m", 0.07, -1)
    m.brass(st[8], 3.4, "A", "m", 0.1, 0)                       # the victor's fanfare
    m.timpani(st[8], 0.8, 49)
    # 56-73  adventure
    for i in (10, 11, 12, 13):
        m.whoosh(st[i] - 0.25, st[i] + 0.2, 0.16)
        m.timpani(st[i], 0.5, 55)
    m.crackle(st[11], st[11] + 4.2, 20, 0.1)
    m.pulse(st[10], st[13] + MAIN[13][1], "A", "m", 0.25, 0.04)
    # 73-86  community: warm, held
    m.brass(st[14], 5.8, "F", "", 0.07, 0)
    m.brass(st[15], 5.8, "C", "", 0.08, 0)
    # 86-97  the rapid sequence: pulse + a hit on every cut
    m.riser(st[16] - 1.5, st[16] + 0.05, 0.22)
    m.pulse(st[16], st[25], "A", "m", 0.15, 0.07)
    for i in range(16, 25):
        m.impact(st[i], 0.55, False)
        m.timpani(st[i], 0.7, 55)
    m.riser(st[25] - 1.0, st[25], 0.3)
    # the black beat: held breath, then the slam at the end card
    end = st[26] + 0.35
    m.riser(st[25] + 0.2, end, 0.16)
    m.impact(end, 1.0)
    m.clang(end, 0.7, 470)
    m.timpani(end, 1.0, 45)
    m.brass(end, 4.2, "A", "", 0.11, -1)
    m.pad(end, total + 0.8, "A", "", 0.09, 0, 1.4, 2.0)
    m.crackle(end, total, 40, 0.16)
    m.finish(0.0, 1.8)
    return m


NETWORK = [("forge", 4.5), ("hub", 6.0), ("skyhub_air", 4.5), ("sky_aerial", 4.6), ("oneblock", 4.6), ("kingdom", 5.0), ("market", 3.0), ("wilds_air", 3.8), ("wilds_ground", 3.2),
           ("koth", 3.8), ("scrapyard", 3.8), ("colosseum", 3.4), ("pit_orbit", 3.6, 5.6, 9.2), ("factions", 4.6), ("factions_low", 3.0), ("arcade", 4.4), ("plaza", 4.8),
           ("hub_close", 4.0), ("r1", 1.0), ("r2", 1.0), ("r3", 1.0), ("r4", 1.0), ("r5", 1.0), ("r6", 1.0), ("r7", 1.0), ("r8", 1.0), ("r9", 1.0), ("r10", 1.2),
           ("black", 1.6), ("end", 6.5)]


def score_network():
    st, total = starts(NETWORK)
    names = [c[0] for c in NETWORK]
    m = Mix(total + 1.0)
    m.drone(0, total + 0.8, 0.15)
    prog = [("A", "m"), ("F", ""), ("C", ""), ("G", ""), ("D", "m"), ("A", "m"), ("F", ""), ("C", ""), ("G", ""), ("E", "")]
    for i in range(18):
        m.pad(st[i], st[i] + NETWORK[i][1], prog[i % len(prog)][0], prog[i % len(prog)][1], 0.06 + 0.0015 * i)
    # the forge: hammer blows, a riser and the strike that reveals the logo at 1.2 s
    m.crackle(0.0, 4.4, 50, 0.2)
    m.clang(0.0, 0.3, 560)
    m.riser(0.0, 1.15, 0.22)
    m.impact(1.2, 1.0)
    m.clang(1.2, 0.7, 480)
    m.timpani(1.2, 0.9, 49)
    m.brass(1.2, 3.4, "A", "m", 0.1, -1)
    # every world gets a whoosh in and a drum on the cut
    for i in range(1, 18):
        m.whoosh(st[i] - 0.4, st[i] + 0.15, 0.15)
        m.timpani(st[i], 0.45, 55 if i % 2 else 49)
    m.brass(st[1], 5.4, "A", "m", 0.06, 0)
    m.brass(st[5], 4.8, "F", "", 0.06, 0)
    m.brass(st[13], 4.2, "D", "m", 0.07, -1)
    m.crackle(st[10], st[10] + 3.8, 25, 0.1)
    # a beat under Survival onwards, clashes at the Colosseum duel
    t, k = st[5], 0
    while t < st[18]:
        m.timpani(t, 0.34 if k % 4 else 0.5, 55 if k % 2 == 0 else 49)
        k += 1
        t += 0.6
    m.pulse(st[9], st[18], "D", "m", 0.2, 0.045)
    idx = names.index("pit_orbit")
    c = NETWORK[idx]
    m.brass(st[idx], 3.6, "A", "m", 0.09, 0)
    # the rapid sequence: a hit on every cut
    m.riser(st[18] - 1.6, st[18], 0.22)
    m.pulse(st[18], st[28], "A", "m", 0.15, 0.07)
    for i in range(18, 28):
        m.impact(st[i], 0.55, False)
        m.timpani(st[i], 0.7, 55)
    m.riser(st[28] - 0.9, st[28], 0.3)
    end = st[29] + 0.35
    m.riser(st[28] + 0.2, end, 0.16)
    m.impact(end, 1.0)
    m.clang(end, 0.7, 470)
    m.timpani(end, 1.0, 45)
    m.brass(end, 4.2, "A", "", 0.11, -1)
    m.pad(end, total + 0.8, "A", "", 0.09, 0, 1.4, 2.0)
    m.crackle(end, total, 40, 0.16)
    m.finish(0.0, 1.8)
    return m


def score_short():
    table = [("forge", 4.5), ("kingdom", 3.5), ("colosseum", 3.5), ("pit_orbit", 4.5, 5.5, 10.0), ("pit_close", 3.0, 12.4, 15.0), ("pit_win", 3.0), ("wilds_air", 2.6), ("scrapyard", 2.8), ("end", 4.6)]
    st, total = starts(table)
    m = Mix(total + 0.5)
    m.drone(0, total + 0.4, 0.14)
    m.crackle(0, 4, 50, 0.18)
    m.clang(0.3, 0.4, 540)
    m.riser(0.0, 1.2, 0.25)
    m.impact(1.2, 1.0)
    m.brass(1.2, 3.0, "A", "m", 0.1, -1)
    m.timpani(1.2, 0.9, 49)
    for i, (c, ch) in enumerate((("A", "m"), ("F", ""), ("D", "m"), ("D", "m"), ("E", ""), ("A", "m"), ("C", ""), ("G", ""), ("A", ""))):
        m.pad(st[i], st[i] + table[i][1], c, ch, 0.07)
    t = st[2]
    k = 0
    while t < st[5] + 3:
        m.timpani(t, 0.5 if k % 4 else 0.7, 55 if k % 2 == 0 else 49)
        k += 1
        t += 0.5
    m.pulse(st[3], st[7] + 2.8, "D", "m", 0.18, 0.05)
    m.brass(st[5], 3.0, "A", "m", 0.1, 0)
    for i in (1, 2, 6, 7):
        m.whoosh(st[i] - 0.3, st[i] + 0.2, 0.16)
        m.timpani(st[i], 0.5, 55)
    end = st[8] + 0.35
    m.riser(st[8] - 1.0, end, 0.25)
    m.impact(end, 1.0)
    m.clang(end, 0.7, 470)
    m.timpani(end, 1.0, 45)
    m.brass(end, 4.0, "A", "", 0.11, -1)
    m.pad(end, total + 0.4, "A", "", 0.09, 0, 1.4, 2.0)
    m.finish(0.0, 1.6)
    return m


def small(src, dst, crf):
    """A 720p copy for phones and slow connections."""
    run(["ffmpeg", "-y", "-loglevel", "error", "-i", src, "-vf", "scale=-2:720", "-c:v", "libx264", "-preset", "slow", "-crf", crf, "-profile:v", "main",
         "-movflags", "+faststart", "-c:a", "copy", dst])


LIMIT = 25 * 1024 * 1024   # Cloudflare Pages: 25 MiB per file


def web(master, dst, vkbps, height=None, audio=True):
    """A website copy of a master at a fixed video bitrate (two passes), so it is certain to stay under the host's file limit."""
    MASTERS.mkdir(parents=True, exist_ok=True)
    VIDEO.mkdir(parents=True, exist_ok=True)
    vf = ["-vf", f"scale=-2:{height}"] if height else []
    log = BUILD / ("pass-" + Path(str(dst)).stem)
    base = ["ffmpeg", "-y", "-loglevel", "error", "-i", master, *vf, "-c:v", "libx264", "-preset", "slow", "-b:v", f"{vkbps}k", "-maxrate", f"{int(vkbps * 1.5)}k",
            "-bufsize", f"{vkbps * 2}k", "-profile:v", "high", "-pix_fmt", "yuv420p", "-passlogfile", log]
    run([*base, "-pass", "1", "-an", "-f", "null", "NUL" if os.name == "nt" else "/dev/null"])
    run([*base, "-pass", "2", *(["-c:a", "aac", "-b:a", "96k"] if audio else ["-an"]), "-movflags", "+faststart", dst])
    size = Path(str(dst)).stat().st_size
    print(f"{Path(str(dst)).name}: {size / 1048576:.1f} MB")
    if size > LIMIT:
        raise SystemExit(f"{dst} is over the 25 MB host limit - lower its bitrate")


def publish(name, full_kbps, small_kbps, audio=True):
    """blackforge-<name>.mp4 (1080p) and blackforge-<name>-720.mp4 for the site, from the master."""
    web(MASTERS / f"blackforge-{name}.mp4", VIDEO / f"blackforge-{name}.mp4", full_kbps, None, audio)
    web(MASTERS / f"blackforge-{name}.mp4", VIDEO / f"blackforge-{name}-720.mp4", small_kbps, 720, audio)


def run(cmd):
    print(" ".join(str(c) for c in cmd))
    subprocess.run([str(c) for c in cmd], check=True)


def encode_main(audio_only):
    wav = BUILD / "premiere-score.wav"
    if not wav.exists() or audio_only:
        score_main().write(wav)
    if audio_only:
        return
    src = FRAMES / "main"
    a, b = BLACK_TRIM
    run(["ffmpeg", "-y", "-loglevel", "error", "-framerate", FPS, "-i", src / "f%05d.png", "-i", wav,
         "-vf", f"select='not(between(n,{a},{b - 1}))',setpts=N/{FPS}/TB,format=yuv420p", "-r", FPS,
         "-c:v", "libx264", "-preset", "slow", "-crf", "17", "-profile:v", "high", "-movflags", "+faststart",
         "-af", "loudnorm=I=-16:TP=-1.5:LRA=11", "-c:a", "aac", "-b:a", "256k", "-shortest", MASTERS / "blackforge-colosseum.mp4"])
    publish("colosseum", 1500, 900)


def encode_network(audio_only):
    wav = BUILD / "premiere-network.wav"
    if not wav.exists() or audio_only:
        score_network().write(wav)
    if audio_only:
        return
    src = FRAMES / "network"
    run(["ffmpeg", "-y", "-loglevel", "error", "-framerate", FPS, "-i", src / "f%05d.png", "-i", wav, "-vf", "format=yuv420p", "-r", FPS,
         "-af", "loudnorm=I=-16:TP=-1.5:LRA=11", "-c:v", "libx264", "-preset", "slow", "-crf", "17", "-profile:v", "high", "-movflags", "+faststart",
         "-c:a", "aac", "-b:a", "256k", "-shortest", MASTERS / "blackforge-trailer.mp4"])
    publish("trailer", 1700, 1000)


def encode_hero():
    src = FRAMES / "hero"
    run(["ffmpeg", "-y", "-loglevel", "error", "-framerate", FPS, "-i", src / "f%05d.png", "-vf", "format=yuv420p", "-r", FPS,
         "-c:v", "libx264", "-preset", "slow", "-crf", "22", "-profile:v", "high", "-movflags", "+faststart", "-an", MASTERS / "blackforge-hero.mp4"])
    publish("hero", 5000, 2500, audio=False)


def encode_short(audio_only):
    wav = BUILD / "premiere-short.wav"
    if not wav.exists() or audio_only:
        score_short().write(wav)
    if audio_only:
        return
    src = FRAMES / "short"
    run(["ffmpeg", "-y", "-loglevel", "error", "-framerate", FPS, "-i", src / "f%05d.png", "-i", wav, "-vf", "format=yuv420p", "-r", FPS,
         "-af", "loudnorm=I=-14:TP=-1.5:LRA=11", "-c:v", "libx264", "-preset", "slow", "-crf", "18", "-profile:v", "high", "-movflags", "+faststart", "-c:a", "aac", "-b:a", "256k", "-shortest",
         MASTERS / "blackforge-short.mp4"])
    web(MASTERS / "blackforge-short.mp4", VIDEO / "blackforge-short.mp4", 2800, 1280)


if __name__ == "__main__":
    what = sys.argv[1] if len(sys.argv) > 1 else "all"
    audio_only = "--audio-only" in sys.argv
    VIDEO.mkdir(parents=True, exist_ok=True)
    if what in ("main", "all"):
        encode_main(audio_only)
    if what in ("network", "all"):
        encode_network(audio_only)
    if what in ("hero", "all") and not audio_only:
        encode_hero()
    if what in ("short", "all"):
        encode_short(audio_only)
