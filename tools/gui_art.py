"""Draw BlackForgeMC's custom menu backgrounds and their font into the resource pack.

Usage:  python tools/gui_art.py      then  python tools/build_pack.py --format <pack format>

Each screen is a background that covers a chest menu's top panel (176 wide). Slot wells sit on the vanilla grid, so
items line up with them. The glyphs and shifts must match survival/StationUi.java. Needs Pillow.
"""
import json
import random
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent / "resourcepack" / "assets" / "blackforge"
BASE = ["#1b1518", "#201a1d", "#171215"]
EDGE_DARK, EDGE_LIGHT, TRIM, EMBER, GOLD = "#0b0809", "#3a2f2e", "#6e3a1c", "#ff7a1a", "#c6a15b"
WELL, WELL_DARK, WELL_LIGHT = "#0f0c0e", "#060405", "#433634"

# name: (glyph, rows, slots with wells, slots that get a gold ring, runs of slots joined by an ember line)
SCREENS = {
    "station_home": ("", 3, [11, 13, 15], [11, 13, 15], []),
    "station_storage": ("", 3, list(range(27)), [], []),
    "station_hoppers": ("", 3, [10, 11, 12, 15, 16, 22], [15, 16], [(10, 12)]),
    "station_agents": ("", 3, [4, 10, 11, 12, 13, 14, 15, 16, 22], [], []),
    "agent": ("", 6, [1, 3, 5, 7, 9, 10, 11, 13, 15, 16, 17] + list(range(18, 54)), [1, 3, 5, 7, 15], []),
    "workshop": ("", 4, [4, 13, 19, 21, 23, 25, 27, 31], [13, 31], []),
}


def slot_xy(slot):
    return 7 + 18 * (slot % 9), 17 + 18 * (slot // 9)


def draw_screen(rows, wells, rings, runs):
    w, h = 176, 17 + 18 * rows
    rnd = random.Random(rows * 31 + len(wells))
    img = Image.new("RGBA", (w, h), BASE[0])
    px = img.load()
    for y in range(h):
        for x in range(w):
            px[x, y] = tuple(int(BASE[rnd.randrange(3)][i:i + 2], 16) for i in (1, 3, 5)) + (255,)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, w - 1, h - 1], outline=EDGE_DARK)
    d.line([1, 1, w - 2, 1], fill=EDGE_LIGHT)
    d.line([1, 1, 1, h - 1], fill=EDGE_LIGHT)
    d.line([w - 2, 2, w - 2, h - 1], fill=EDGE_DARK)
    d.line([4, 15, w - 5, 15], fill=TRIM)
    d.line([4, 16, w - 5, 16], fill=EDGE_DARK)
    d.line([4, 15, 12, 15], fill=EMBER)
    d.line([w - 13, 15, w - 5, 15], fill=EMBER)
    for a, b in runs:
        ax, ay = slot_xy(a)
        bx, _ = slot_xy(b)
        d.rectangle([ax + 9, ay + 7, bx + 9, ay + 10], fill=EMBER)
    for s in wells:
        x, y = slot_xy(s)
        d.rectangle([x, y, x + 17, y + 17], fill=WELL)
        d.line([x, y, x + 16, y], fill=WELL_DARK)
        d.line([x, y, x, y + 16], fill=WELL_DARK)
        d.line([x + 1, y + 17, x + 17, y + 17], fill=WELL_LIGHT)
        d.line([x + 17, y + 1, x + 17, y + 17], fill=WELL_LIGHT)
        if s in rings:
            d.rectangle([x - 1, y - 1, x + 18, y + 18], outline=GOLD)
    return img


def main():
    tex = ROOT / "textures" / "gui"
    tex.mkdir(parents=True, exist_ok=True)
    (ROOT / "font").mkdir(parents=True, exist_ok=True)
    providers = [{"type": "space", "advances": {"": -8, "": -169}}]
    for name, (glyph, rows, wells, rings, runs) in SCREENS.items():
        img = draw_screen(rows, wells, rings, runs)
        img.save(tex / f"{name}.png")
        providers.append({"type": "bitmap", "file": f"blackforge:gui/{name}.png", "height": img.height, "ascent": 13, "chars": [glyph]})
    (ROOT / "font" / "gui.json").write_text(json.dumps({"providers": providers}, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
