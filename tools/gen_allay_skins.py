#!/usr/bin/env python3
"""Forge Allay skins for Tier II and III: writes the textures, models and item definitions into resourcepack/.

Tier II (courier): orange and copper, with a slow, faint shimmer and sparkles that twinkle (an animated texture). Tier III (steward): a flowing rainbow gradient, an
animated texture. Each has three models, wings closed to swept back, which the plugin steps through to flap. Wings are
pale, see-through and smaller than the body, so from behind it still reads as one allay with its head showing. Run it from the repo root,
then build the pack as blackforge-skin-item describes. Output is stable, so re-running changes nothing.
"""
import colorsys
import json
import math
from pathlib import Path

from PIL import Image

ROOT = Path("resourcepack/assets/blackforge")
SIZE = 32          # texture pixels; model UVs run 0-16, so one UV unit is 2 pixels
FRAMES = 16        # animation frames for both skins
COURIER_FRAMETIME = 5   # ticks per frame, so the shimmer loops every 4 seconds; the rainbow's is 3
WING_TOP = 16      # pixels at y >= this and x >= this are the wing area


def eyes(x, y):
    """The two dark eyes on the head's front face, which fills (0,0)-(10,10)."""
    return y in (4, 5) and x in (2, 3, 6, 7)


def wing_shape(x, y):
    """A rounded wing filling (16,16)-(32,32), with notches so it reads as feathers. False is see-through."""
    u, v = x - WING_TOP, y - WING_TOP
    if (u - 7.5) ** 2 + (v - 7.5) ** 2 > 60:
        return False
    return not (v > 10 and u % 4 == 3)


def shade(base, light, amount):
    return tuple(round(a + (b - a) * amount) for a, b in zip(base, light))


def twinkle(x, y, frame):
    """0 to 1, with each sparkle on its own beat. Whole cycles only, so the loop has no seam."""
    return 0.5 + 0.5 * math.cos(2 * math.pi * (frame / FRAMES - (x * 3 + y * 5) % FRAMES / FRAMES))


def sheen(x, y, frame):
    """A soft light drifting across the skin, kept faint on purpose: at most 18% towards the light colour."""
    return 0.18 * (0.5 + 0.5 * math.cos(2 * math.pi * (frame / FRAMES - (x + y) / SIZE)))


def courier(x, y, frame):
    if x >= WING_TOP and y >= WING_TOP:
        if not wing_shape(x, y):
            return (0, 0, 0, 0)
        if (x + y) % 5 == 0:
            return shade((255, 226, 178), (255, 244, 216), twinkle(x, y, frame)) + (190,)
        return (255, 214, 150, 150)
    if eyes(x, y) and x < 10 and y < 10:
        return (58, 24, 8, 255)
    base = (255, 168, 62) if y % 16 < 8 else (242, 140, 44)
    # the same scatter of brighter pixels as before, now fading in and out for the glimmer
    if (x * 7 + y * 13) % 19 == 0:
        return shade(shade(base, (255, 224, 150), 0.45), (255, 236, 176), twinkle(x, y, frame)) + (255,)
    return shade(base, (255, 196, 110), sheen(x, y, frame)) + (255,)


def steward(x, y, frame):
    wing_part = x >= WING_TOP and y >= WING_TOP
    if wing_part and not wing_shape(x, y):
        return (0, 0, 0, 0)
    if x < 10 and y < 10 and eyes(x, y):
        return (24, 16, 40, 255)
    hue = (y / SIZE + x / (SIZE * 2) + frame / FRAMES) % 1
    r, g, b = colorsys.hsv_to_rgb(hue, 0.4 if wing_part else 0.72, 1.0)
    return (round(r * 255), round(g * 255), round(b * 255), 150 if wing_part else 255)


def face(u0, v0, u1, v1):
    return {"uv": [u0, v0, u1, v1], "texture": "#skin"}


def box(frm, to, front=None, rotation=None):
    """A cuboid. `front` is the UV of the -Z side, the way it faces; the rest share the plain part of the skin."""
    side = (5, 0, 10, 5)
    faces = {d: face(*side) for d in ("south", "east", "west", "up", "down")}
    faces["north"] = face(*(front or side))
    out = {"from": frm, "to": to, "faces": faces}
    if rotation:
        out["rotation"] = rotation
    return out


def wing(left, angle):
    """A thin plate behind the body, hinged on its inner edge. The frames differ only by this angle, and it only sweeps back."""
    hinge = 7.0 if left else 9.0
    frm, to = ([2.5, 5.5, 9.0], [7.0, 10.5, 9.1]) if left else ([9.0, 5.5, 9.0], [13.5, 10.5, 9.1])
    uv = (8, 8, 16, 16) if left else (16, 8, 8, 16)
    return {"from": frm, "to": to,
            "rotation": {"origin": [hinge, 8.0, 9.0], "axis": "y", "angle": angle if left else -angle},
            "faces": {"north": face(*uv), "south": face(*uv)}}


def model(name, wing_angle):
    return {
        "textures": {"skin": f"blackforge:item/allay/{name}", "particle": f"blackforge:item/allay/{name}"},
        "elements": [
            box([5.5, 8, 5.5], [10.5, 13, 10.5], front=(0, 0, 5, 5)),   # head
            box([6.5, 4, 7], [9.5, 8, 9]),                              # body
            box([5.5, 5, 7.5], [6.5, 8, 8.5]),                          # arms
            box([9.5, 5, 7.5], [10.5, 8, 8.5]),
            wing(True, wing_angle),
            wing(False, wing_angle),
        ],
    }


def write_models(name):
    for frame, angle in (("a", 0.0), ("b", 22.5), ("c", 45.0)):
        (ROOT / "models/item/allay").mkdir(parents=True, exist_ok=True)
        (ROOT / "items/allay").mkdir(parents=True, exist_ok=True)
        (ROOT / f"models/item/allay/{name}_{frame}.json").write_text(json.dumps(model(name, angle), indent=2) + "\n")
        (ROOT / f"items/allay/{name}_{frame}.json").write_text(json.dumps(
            {"model": {"type": "minecraft:model", "model": f"blackforge:item/allay/{name}_{frame}"}}, indent=2) + "\n")


def main():
    tex = ROOT / "textures/item/allay"
    tex.mkdir(parents=True, exist_ok=True)
    glow = Image.new("RGBA", (SIZE, SIZE * FRAMES))
    glow.putdata([courier(x, y, f) for f in range(FRAMES) for y in range(SIZE) for x in range(SIZE)])
    glow.save(tex / "courier.png")
    (tex / "courier.png.mcmeta").write_text(json.dumps({"animation": {"frametime": COURIER_FRAMETIME, "interpolate": True}}, indent=2) + "\n")
    strip = Image.new("RGBA", (SIZE, SIZE * FRAMES))
    strip.putdata([steward(x, y, f) for f in range(FRAMES) for y in range(SIZE) for x in range(SIZE)])
    strip.save(tex / "steward.png")
    (tex / "steward.png.mcmeta").write_text(json.dumps({"animation": {"frametime": 3, "interpolate": True}}, indent=2) + "\n")
    write_models("courier")
    write_models("steward")


if __name__ == "__main__":
    main()
