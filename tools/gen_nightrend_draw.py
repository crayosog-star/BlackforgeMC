"""Writes the Nightrend pull-out animation into the resource pack.

When a Nightrend is drawn, the plugin puts it on a short item cooldown (Nightrend.draw). The pack's
items/nightrend.json reads that cooldown (minecraft:cooldown, 1 -> 0) and picks one of the frame models,
so the held dagger itself flips up from pointing down, in first and third person, with one dagger or
two. It's the player's own client that animates it, so it shows on their own screen too. Other
players don't get the cooldown, so the plugin shows them display entities instead.

Each frame is the handheld model with its hand transforms turned: the blade holds low and upside down
for a moment, then spins in its own plane to upright (with a small settle) while it rises out of the
low "drawn from the hip" position. The curve is NightrendMath.drawOffset / drawDrop.

The client re-reads an item's cooldown only once a game tick, so the pose changes at most 20 times a second
however many frame models there are. The curve (HOLD, FLIP) sets how far each tick turns: a slow 14-tick flip
(about 21 degrees a tick) still looked stepped, so it's a quick 6-tick snap (about 47 a tick). The frame models are still spaced by motion: a new one whenever the blade has turned
MAX_STEP_DEGREES (or dropped MAX_STEP_PIXELS), so no tick lands far from a frame.

Run from the repo root after changing a number here:  python3 tools/gen_nightrend_draw.py
"""
import json
import math
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent / "resourcepack/assets/blackforge"
MAX_STEP_DEGREES = 4.0   # most the blade turns between two frame models
MAX_STEP_PIXELS = 0.15   # most it rises between two frame models

# vanilla minecraft:item/handheld hand transforms, which the plain nightrend model inherits
BASE = {
    "firstperson_righthand": ([0, -90, 25], [1.13, 3.2, 1.13], 0.68),
    "firstperson_lefthand": ([0, 90, -25], [1.13, 3.2, 1.13], 0.68),
    "thirdperson_righthand": ([0, -90, 55], [0, 4.0, 0.5], 0.85),
    "thirdperson_lefthand": ([0, 90, -55], [0, 4.0, 0.5], 0.85),
}
DROP = {"firstperson": 3.5, "thirdperson": 2.0}   # how far below its normal place the blade starts, in pixels


# same as NightrendMath.DRAW_HOLD / DRAW_FLIP: the client re-reads the cooldown once a game tick, so the motion is
# paced per tick (hold low while the game raises the item, then a quick 0.3 s flip of at most ~47 degrees a tick)
HOLD, FLIP = 5, 6


def flip_progress(x: float) -> float:
    h = HOLD / (HOLD + FLIP)
    return min(max((x - h) / (1 - h), 0.0), 1.0)


def flip_offset(x: float) -> float:
    """Degrees from upright at progress x (0..1): -180 through the hold, ease-in-out to 0, a little past near
    the end (NightrendMath.drawOffset)."""
    u = flip_progress(x)
    ease = 0.5 - 0.5 * math.cos(math.pi * u)
    settle = 8 * math.sin(math.pi * min(max((u - 0.8) / 0.2, 0.0), 1.0))
    return -180 * (1 - ease) + settle


def drop(x: float) -> float:
    """1 (lowest) through the hold, then rises over the first 60% of the flip (NightrendMath.drawDrop)."""
    r = min(max(flip_progress(x) / 0.6, 0.0), 1.0)
    return 1 - r * r * (3 - 2 * r)


def frame_model(x: float) -> dict:
    o, d = flip_offset(x), drop(x)
    display = {}
    for slot, (rot, tr, s) in BASE.items():
        person = slot.split("_")[0]
        left = slot.endswith("lefthand")
        r = [rot[0], rot[1], round(rot[2] + (-o if left else o), 2)]   # left hand mirrors the spin
        t = [tr[0], round(tr[1] - DROP[person] * d, 3), tr[2]]
        display[slot] = {"rotation": r, "translation": t, "scale": [s, s, s]}
    return {"parent": "blackforge:item/nightrend", "display": display}


def slices() -> list:
    """Progress ranges (x0, x1) covering 0..1, each short enough that the blade moves at most one step in it."""
    fine = 4000
    out, start = [], 0.0
    for i in range(1, fine + 1):
        x = i / fine
        turned = abs(flip_offset(x) - flip_offset(start))
        rose = max(DROP.values()) * abs(drop(x) - drop(start))
        if turned >= MAX_STEP_DEGREES or rose >= MAX_STEP_PIXELS or i == fine:
            out.append((start, x))
            start = x
    return out


def main() -> None:
    root = ROOT / "models/item"
    for old in root.glob("nightrend_draw_*.json"):
        old.unlink()   # the frame count changes with the step sizes, so no stale frames stay in the pack
    entries = []
    parts = slices()
    for k, (x0, x1) in enumerate(parts):
        name = f"nightrend_draw_{k}"
        path = root / f"{name}.json"
        path.write_text(json.dumps(frame_model((x0 + x1) / 2), indent=2) + "\n")
        # cooldown runs 1 -> 0 while progress runs 0 -> 1, so this frame shows while the cooldown is above 1 - x1;
        # the last one stops just above 0, so a dagger with no cooldown falls through to the plain model
        threshold = round(1 - x1, 4) if k < len(parts) - 1 else 0.001
        entries.append({"threshold": threshold, "model": {"type": "minecraft:model", "model": f"blackforge:item/{name}"}})
    item = {
        "model": {
            "type": "minecraft:range_dispatch",
            "property": "minecraft:cooldown",
            "entries": entries,
            "fallback": {"type": "minecraft:model", "model": "blackforge:item/nightrend"},
        },
        # no down-and-up dip when the plugin changes the dagger's swing length (Nightrend.setQuick); the draw flip
        # above is the pull-out animation instead
        "hand_animation_on_swap": False,
    }
    (ROOT / "items/nightrend.json").write_text(json.dumps(item, indent=2) + "\n")
    print(f"wrote {len(parts)} frames and items/nightrend.json")


if __name__ == "__main__":
    main()
