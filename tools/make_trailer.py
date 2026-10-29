"""Finish the BlackForgeMC trailer: synthesize its music and encode the frames into an MP4.

    gradle trailer -Pjar=<26.3 client jar>      (renders plugins-src/AlloyCore/previews/trailer/f00000.png ...)
    python tools/make_trailer.py                (writes website/assets/video/blackforge-trailer.mp4)

The score is generated here from scratch (no samples, nothing licensed): a low drone, one chord per scene, impact
hits on every cut, a pulse that builds through the middle and a swell into the end card. Scene lengths must match
Trailer.scenes() in the plugin source.
"""
import math
import random
import struct
import subprocess
import wave
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FRAMES = ROOT / "plugins-src" / "AlloyCore" / "previews" / "trailer"
OUT = ROOT / "website" / "assets" / "video" / "blackforge-trailer.mp4"
WAV = ROOT / "build" / "trailer-score.wav"
FPS, RATE = 30, 44100
SCENES = [6.5, 5, 5, 4.5, 4.5, 5, 4.5, 4.5, 7]                       # seconds, as in Trailer.java
CHORDS = [("A", "m"), ("F", ""), ("C", ""), ("G", ""), ("A", "m"), ("F", ""), ("D", "m"), ("E", ""), ("A", "m")]
NOTE = {"C": 0, "D": 2, "E": 4, "F": 5, "G": 7, "A": 9, "B": 11}


def freq(semis_from_a4):
    return 440.0 * 2 ** (semis_from_a4 / 12)


def chord_freqs(root, kind):
    r = NOTE[root] - 9 - 12   # around A3
    third = 3 if kind == "m" else 4
    return [freq(r + o) for o in (0, third, 7, 12, 12 + third)]


def score():
    total = sum(SCENES)
    n = int(total * RATE) + RATE // 2
    out = [0.0] * n
    cuts, t = [], 0.0
    for s in SCENES:
        cuts.append(t)
        t += s
    rnd = random.Random(7)
    two_pi = 2 * math.pi

    # drone: A1 + E2 with a slow swell
    for i in range(n):
        x = i / RATE
        env = min(1, x / 4) * (1 - max(0, (x - total + 1.5) / 2))
        out[i] += env * 0.16 * (math.sin(two_pi * 55 * x) + 0.6 * math.sin(two_pi * 82.41 * x + 0.3) + 0.25 * math.sin(two_pi * 110 * x) * (0.5 + 0.5 * math.sin(two_pi * 0.12 * x)))

    # pads: one chord per scene, crossfaded, soft saw (a few harmonics)
    for k, (start, length) in enumerate(zip(cuts, SCENES)):
        fs = chord_freqs(*CHORDS[k])
        a, b = int(max(0, start - 0.6) * RATE), int(min(total, start + length + 0.6) * RATE)
        for i in range(a, b):
            x = i / RATE
            env = min(1, (x - start + 0.6) / 1.2) * min(1, (start + length + 0.6 - x) / 1.2)
            if k == len(SCENES) - 1:
                env *= min(1, max(0, (total + 0.4 - x) / 2.5))
            v = 0.0
            for j, f in enumerate(fs):
                det = 1 + 0.0015 * (j - 2)
                v += math.sin(two_pi * f * det * x) + 0.35 * math.sin(two_pi * 2 * f * det * x) + 0.12 * math.sin(two_pi * 3 * f * det * x)
            out[i] += env * 0.032 * v

    # impacts on every cut after the opening title, and when the logo lands
    def boom(at, gain):
        a = int(at * RATE)
        for i in range(a, min(n, a + int(2.2 * RATE))):
            x = (i - a) / RATE
            f = 38 + 90 * math.exp(-x * 9)
            ph = two_pi * (38 * x + 90 * (1 - math.exp(-x * 9)) / 9)
            out[i] += gain * math.exp(-x * 2.2) * (0.9 * math.sin(ph) + 0.25 * (rnd.random() * 2 - 1) * math.exp(-x * 14))
    boom(2.0, 0.55)
    for c in cuts[1:]:
        boom(c, 0.42)
    boom(cuts[-1] + 2.0, 0.7)   # end card

    # pulse: a low heartbeat at 90 bpm through the middle, getting stronger
    beat = 60 / 90
    x = cuts[1]
    while x < cuts[-1]:
        g = 0.12 + 0.2 * (x - cuts[1]) / (cuts[-1] - cuts[1])
        for off, gg in ((0, 1), (0.16, 0.6)):
            a = int((x + off) * RATE)
            for i in range(a, min(n, a + int(0.35 * RATE))):
                y = (i - a) / RATE
                out[i] += g * gg * math.exp(-y * 14) * math.sin(two_pi * (60 * y + 40 * (1 - math.exp(-y * 30)) / 30))
        x += beat

    # rising whoosh into the opening title and into the end card
    for s0, s1, gain in ((0.0, 2.0, 0.18), (cuts[-1] - 3.0, cuts[-1] + 2.0, 0.22)):
        lp = 0.0
        for i in range(int(s0 * RATE), min(n, int(s1 * RATE))):
            y = (i / RATE - s0) / (s1 - s0)
            lp += (0.02 + 0.25 * y) * ((rnd.random() * 2 - 1) - lp)
            out[i] += gain * y * y * lp

    peak = max(abs(v) for v in out) or 1
    return [math.tanh(1.4 * v / peak) * 0.85 for v in out]


def write_wav(samples):
    WAV.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(WAV), "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<hh", int(s * 32000), int(s * 32000)) for s in samples))


def main():
    frames = sorted(FRAMES.glob("f*.png"))
    if not frames:
        raise SystemExit("No frames - run: gradle trailer -Pjar=<26.3 client jar>")
    print(f"{len(frames)} frames ({len(frames) / FPS:.1f} s); writing the score...")
    write_wav(score())
    OUT.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-framerate", str(FPS), "-i", str(FRAMES / "f%05d.png"), "-i", str(WAV),
                    "-c:v", "libx264", "-preset", "slow", "-crf", "24", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k",
                    "-shortest", "-movflags", "+faststart", str(OUT)], check=True)
    print("wrote", OUT, f"{OUT.stat().st_size / 1e6:.1f} MB")


if __name__ == "__main__":
    main()
