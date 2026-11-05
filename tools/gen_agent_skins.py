#!/usr/bin/env python3
"""Agent skins, one per tier: writes the textures, models and item definitions into resourcepack/.

Each tier has a body model and an arm model, so the plugin can swing the arm on its own. Tier I is weathered green copper,
II is bright copper, III is glimmering orange like a Courier allay, IV is ember gold and V is a flowing rainbow, like a
Steward allay. Run from the repo root, then build the pack as blackforge-skin-item describes. Output is stable.
"""
import colorsys
import json
from pathlib import Path

from PIL import Image

ROOT = Path("resourcepack/assets/blackforge")
SIZE = 32
FRAMES = 16
# base, dark, light, eye: the eye colour lights the face so the Agent reads at a distance
PALETTES = {
    "oxidized": ((78, 160, 132), (52, 116, 98), (118, 196, 168), (214, 250, 232)),
    "copper": ((198, 116, 58), (150, 82, 40), (234, 156, 96), (255, 232, 170)),
    "ember": ((242, 140, 44), (206, 96, 24), (255, 186, 92), (255, 246, 190)),
    "gold": ((252, 196, 72), (176, 70, 28), (255, 232, 150), (255, 90, 30)),
}


def eyes(x, y):
    """Two eyes on the head's front face, which fills (0,0)-(12,10)."""
    return y in (3, 4) and x in (2, 3, 8, 9)


def metal(name, x, y):
    base, dark, light, eye = PALETTES[name]
    if x < 12 and y < 10 and eyes(x, y):
        return eye + (255,)
    if x < 12 and y == 7 and 3 <= x <= 8:   # a seam for a mouth
        return dark + (255,)
    if (x * 7 + y * 13) % 19 == 0:
        return light + (255,)
    return (dark if y % 8 == 7 else base) + (255,)


def rainbow(x, y, frame):
    if x < 12 and y < 10 and eyes(x, y):
        return (24, 16, 40, 255)
    hue = (y / SIZE + x / (SIZE * 2) + frame / FRAMES) % 1
    r, g, b = colorsys.hsv_to_rgb(hue, 0.72, 1.0)
    return (round(r * 255), round(g * 255), round(b * 255), 255)


def face(u0, v0, u1, v1):
    return {"uv": [u0, v0, u1, v1], "texture": "#skin"}


def box(frm, to, front=None, plain=(6, 0, 10, 4)):
    faces = {d: face(*plain) for d in ("south", "east", "west", "up", "down")}
    faces["north"] = face(*(front or plain))
    return {"from": frm, "to": to, "faces": faces}


def body(name):
    return {
        "textures": {"skin": f"blackforge:item/agent/{name}", "particle": f"blackforge:item/agent/{name}"},
        "elements": [
            box([4.5, 8, 4.5], [11.5, 13, 11.5], front=(0, 0, 6, 5)),     # head
            box([7.5, 13, 7.5], [8.5, 15, 8.5]),                          # its lightning rod
            box([7, 15, 7], [9, 16, 9]),
            box([7.25, 8.5, 3.75], [8.75, 10.5, 4.5]),                    # nose
            box([5, 3.5, 5.5], [11, 8, 10.5]),                            # body
            box([5.5, 0, 6.5], [7.5, 3.5, 9.5]),                          # legs
            box([8.5, 0, 6.5], [10.5, 3.5, 9.5]),
            box([11, 3, 7], [12.5, 8, 9]),                                # the arm that doesn't swing
        ],
    }


def arm(name):
    """Its top is on the model's centre, so turning the model swings it from the shoulder."""
    return {
        "textures": {"skin": f"blackforge:item/agent/{name}", "particle": f"blackforge:item/agent/{name}"},
        "elements": [box([7.25, 3, 7], [8.75, 8.2, 9])],
    }


def write(name):
    for part, data in (("body", body(name)), ("arm", arm(name))):
        (ROOT / "models/item/agent").mkdir(parents=True, exist_ok=True)
        (ROOT / "items/agent").mkdir(parents=True, exist_ok=True)
        (ROOT / f"models/item/agent/{name}_{part}.json").write_text(json.dumps(data, indent=2) + "\n")
        (ROOT / f"items/agent/{name}_{part}.json").write_text(json.dumps(
            {"model": {"type": "minecraft:model", "model": f"blackforge:item/agent/{name}_{part}"}}, indent=2) + "\n")


def hopper(skin, side):
    """A shell round a real hopper: rim, funnel and spout. It uses the Agent's texture, so the two match."""
    plain = (6, 0, 10, 4)
    spout = box([6, 4, 0], [10, 8, 4]) if side else box([6, 0, 6], [10, 4, 10])
    return {
        "textures": {"skin": f"blackforge:item/agent/{skin}", "particle": f"blackforge:item/agent/{skin}"},
        "elements": [
            box([0, 10, 0], [16, 16, 2], plain=plain), box([0, 10, 14], [16, 16, 16], plain=plain),
            box([0, 10, 2], [2, 16, 14], plain=plain), box([14, 10, 2], [16, 16, 14], plain=plain),
            box([2, 10, 2], [14, 11, 14], plain=plain),
            box([4, 4, 4], [12, 10, 12], plain=plain),
            spout,
        ],
    }


def write_hoppers():
    (ROOT / "models/item/hopper").mkdir(parents=True, exist_ok=True)
    (ROOT / "items/hopper").mkdir(parents=True, exist_ok=True)
    for skin in ("ember", "prism"):
        for shape, side in (("down", False), ("side", True)):
            (ROOT / f"models/item/hopper/{skin}_{shape}.json").write_text(json.dumps(hopper(skin, side), indent=2) + "\n")
            (ROOT / f"items/hopper/{skin}_{shape}.json").write_text(json.dumps(
                {"model": {"type": "minecraft:model", "model": f"blackforge:item/hopper/{skin}_{shape}"}}, indent=2) + "\n")


def main():
    write_hoppers()
    tex = ROOT / "textures/item/agent"
    tex.mkdir(parents=True, exist_ok=True)
    for name in PALETTES:
        img = Image.new("RGBA", (SIZE, SIZE))
        img.putdata([metal(name, x, y) for y in range(SIZE) for x in range(SIZE)])
        img.save(tex / f"{name}.png")
        write(name)
    strip = Image.new("RGBA", (SIZE, SIZE * FRAMES))
    strip.putdata([rainbow(x, y, f) for f in range(FRAMES) for y in range(SIZE) for x in range(SIZE)])
    strip.save(tex / "prism.png")
    (tex / "prism.png.mcmeta").write_text(json.dumps({"animation": {"frametime": 3, "interpolate": True}}, indent=2) + "\n")
    write("prism")


if __name__ == "__main__":
    main()
