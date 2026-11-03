"""Turn any gameplay recording (or screenshots) into a TikTok / Shorts / Reels clip: 1080x1920, a hook caption on top,
the server IP across the top of the frame the whole time, and short punchy caption lines over the footage.

  python tools/make_clip.py out.mp4 --src gameplay.mp4 --hook "This Minecraft server has pets that level up" \
        --caption "They gain abilities" --caption "Only at max level" --ip blackforgemc.com [--start 12 --length 15] [--music track.mp3]
  python tools/make_clip.py out.mp4 --src shot1.png --src shot2.png --hook "..."     (screenshots: 3 s each, slow zoom)

Captions are spread evenly over the clip after the hook. Needs ffmpeg on PATH.
"""
import argparse
import subprocess
import sys
import tempfile
from pathlib import Path

FONT = "C\:/Windows/Fonts/impact.ttf"
W, H = 1080, 1920


def esc(t):
    return t.replace("\\", "\\\\").replace(":", "\:").replace("'", "’").replace("%", "\%").replace(",", "\,")


def wrap(t, width=22):
    words, lines, cur = t.split(), [], ""
    for w in words:
        if len(cur) + len(w) + 1 > width and cur:
            lines.append(cur)
            cur = w
        else:
            cur = (cur + " " + w).strip()
    return lines + [cur]


def text(t, y, size, start, end, color="white"):
    out = []
    lines = wrap(t.upper())
    for i, ln in enumerate(lines):
        out.append(f"drawtext=fontfile='{FONT}':text='{esc(ln)}':fontsize={size}:fontcolor={color}:borderw=8:bordercolor=black@0.95:x=(w-text_w)/2:y={y}+{i * int(size * 1.15)}:enable='between(t,{start},{end})'")
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--src", action="append", required=True)
    ap.add_argument("--hook", required=True)
    ap.add_argument("--caption", action="append", default=[])
    ap.add_argument("--ip", default="blackforgemc.com")
    ap.add_argument("--start", type=float, default=0)
    ap.add_argument("--length", type=float, default=15)
    ap.add_argument("--music")
    a = ap.parse_args()

    is_img = Path(a.src[0]).suffix.lower() in (".png", ".jpg", ".jpeg")
    cmd = ["ffmpeg", "-y", "-loglevel", "error"]
    if is_img:
        length = 3.0 * len(a.src)
        for s in a.src:
            cmd += ["-loop", "1", "-t", "3", "-i", s]
        scale = "".join(f"[{i}:v]scale=-2:{H}*1.15,zoompan=z='1+0.0008*on':d=75:s={W}x{H}:fps=30[v{i}];" for i in range(len(a.src)))
        base = scale + "".join(f"[v{i}]" for i in range(len(a.src))) + f"concat=n={len(a.src)}:v=1:a=0[bg]"
    else:
        length = a.length
        cmd += ["-ss", str(a.start), "-t", str(length), "-i", a.src[0]]
        base = f"[0:v]scale=-2:{H},crop={W}:{H},setsar=1[bg]"
    caps = a.caption
    step = (length - 2.5) / max(1, len(caps)) if caps else 0
    vf = []
    vf += text(a.hook, 360, 96, 0, 2.5, "yellow")
    for i, c in enumerate(caps):
        vf += text(c, 1250, 92, 2.5 + i * step, 2.5 + (i + 1) * step)
    vf.append(f"drawtext=fontfile='{FONT}':text='{esc(a.ip)}':fontsize=70:fontcolor=white:box=1:boxcolor=black@0.55:boxborderw=18:x=(w-text_w)/2:y=110")
    flt = base + ";[bg]" + ",".join(vf) + "[v]"
    cmd += ["-filter_complex", flt, "-map", "[v]"]
    if a.music:
        cmd += ["-i", a.music, "-map", f"{len(a.src) if is_img else 1}:a", "-shortest", "-af", f"afade=t=out:st={length - 1}:d=1"]
    elif not is_img:
        cmd += ["-map", "0:a?"]
    cmd += ["-t", str(length), "-c:v", "libx264", "-preset", "medium", "-crf", "20", "-pix_fmt", "yuv420p", "-movflags", "+faststart", a.out]
    subprocess.run(cmd, check=True)
    print("wrote", a.out)


if __name__ == "__main__":
    sys.exit(main())
