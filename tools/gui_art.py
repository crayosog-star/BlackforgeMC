"""Draw BlackForgeMC's custom menu art and fonts into the resource pack.

Usage:  python tools/gui_art.py      then  python tools/build_pack.py --format <pack format>

Each screen covers a chest menu's top panel (176 wide), and its wells sit on the vanilla slot grid so items line up.
Glyphs must match survival/StationUi.java; shift characters must match survival/StationText.java. Needs Pillow.
"""
import json
import random
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent / "resourcepack" / "assets" / "blackforge"
STONE = [(30, 25, 27), (35, 29, 31), (26, 21, 23), (40, 33, 34)]
MORTAR, HIGHLIGHT, IRON, IRON_LIGHT = (13, 10, 12), (58, 48, 47), (52, 50, 54), (108, 104, 106)
GOLD, GOLD_DARK = (214, 170, 92), (122, 86, 44)
WELL, WELL_DARK, WELL_LIGHT = (14, 11, 13), (5, 4, 5), (72, 58, 54)
LAVA = [(92, 20, 10), (170, 44, 12), (255, 110, 20), (255, 196, 70)]

# The Forge's text sits at these heights: font name -> y from the panel's top.
LINES = {"gui_l1": 39, "gui_l2": 49, "gui_l3": 59, "gui_l4": 77}
PIP_Y, COUNT_Y = 67, 5
CARD_COLS = [0, 2, 4, 6, 8]


def slot_xy(slot):
    return 7 + 18 * (slot % 9), 17 + 18 * (slot // 9)


def stone(img, x0, y0, x1, y1, seed):
    """Dark stone bricks, offset every other course, with a lit top edge."""
    rnd = random.Random(seed)
    px = img.load()
    for y in range(y0, y1):
        course = (y - y0) // 8
        for x in range(x0, x1):
            ox = (x + (6 if course % 2 else 0)) % 13
            if (y - y0) % 8 == 7 or ox == 12:
                px[x, y] = MORTAR + (255,)
            elif (y - y0) % 8 == 0 or ox == 0:
                px[x, y] = HIGHLIGHT + (255,)
            else:
                px[x, y] = rnd.choice(STONE) + (255,)


def crack(d, rnd, x, y, length, vertical=True):
    """A molten seam: hot centre, cooling at the ends."""
    for i in range(length):
        t = abs(i / max(1, length - 1) - 0.5) * 2
        c = LAVA[3] if t < 0.25 else LAVA[2] if t < 0.6 else LAVA[1]
        d.point((x, y), fill=c)
        if t < 0.6:
            d.point((x + (1 if vertical else 0), y + (0 if vertical else 1)), fill=LAVA[0])
        if vertical:
            y += 1
            x += rnd.choice((-1, 0, 0, 1)) if i % 2 else 0
        else:
            x += 1
            y += rnd.choice((-1, 0, 0, 1)) if i % 2 else 0


def frame(d, w, h):
    d.rectangle([0, 0, w - 1, h - 1], outline=MORTAR)
    d.rectangle([1, 1, w - 2, h - 2], outline=IRON)
    d.line([1, 1, w - 2, 1], fill=IRON_LIGHT)
    d.line([1, 1, 1, h - 2], fill=IRON_LIGHT)


def header(d, w):
    d.rectangle([2, 2, w - 3, 15], fill=(20, 16, 18))
    d.line([2, 15, w - 3, 15], fill=GOLD_DARK)
    d.line([2, 16, w - 3, 16], fill=LAVA[1])
    for x in (4, w - 6):
        d.rectangle([x, 4, x + 1, 5], fill=GOLD)
        d.rectangle([x, 12, x + 1, 13], fill=GOLD_DARK)


def well(d, x, y, ring=False):
    d.rectangle([x, y, x + 17, y + 17], fill=WELL)
    d.line([x, y, x + 16, y], fill=WELL_DARK)
    d.line([x, y, x, y + 16], fill=WELL_DARK)
    d.line([x + 1, y + 17, x + 17, y + 17], fill=WELL_LIGHT)
    d.line([x + 17, y + 1, x + 17, y + 17], fill=WELL_LIGHT)
    if ring:
        d.rectangle([x - 1, y - 1, x + 18, y + 18], outline=GOLD_DARK)
        for cx, cy in ((x - 1, y - 1), (x + 18, y - 1), (x - 1, y + 18), (x + 18, y + 18)):
            d.point((cx, cy), fill=GOLD)


def forge():
    w, h = 176, 89
    img = Image.new("RGBA", (w, h))
    stone(img, 0, 0, w, h, 7)
    d = ImageDraw.Draw(img)
    rnd = random.Random(11)
    frame(d, w, h)
    header(d, w)
    for col in CARD_COLS:
        x, y = slot_xy(col)
        # the card: a dark iron plate under the well
        left, right = max(3, x - 8), min(w - 4, x + 25)
        d.rectangle([left, y - 1, right, 70], fill=(17, 13, 15), outline=GOLD_DARK)
        well(d, x, y, ring=True)
    # molten seams between the cards and along the foot of the wall
    for col in CARD_COLS[:-1]:
        x, _ = slot_xy(col)
        crack(d, rnd, x + 27, 20, 48)
    d.rectangle([2, 71, w - 3, h - 3], fill=(22, 12, 10))
    d.line([2, 71, w - 3, 71], fill=LAVA[2])
    d.line([2, 72, w - 3, 72], fill=LAVA[0])
    # heat rising from the foot
    for x in range(3, w - 3):
        if rnd.random() < 0.5:
            d.point((x, 73 + rnd.randrange(2)), fill=LAVA[1])
    for s in (31, 32, 33):
        x, y = slot_xy(s)
        well(d, x, y, ring=s != 31)
    return img


def plain(rows, wells, rings):
    w, h = 176, 17 + 18 * rows
    img = Image.new("RGBA", (w, h))
    stone(img, 0, 0, w, h, rows * 31 + len(wells))
    d = ImageDraw.Draw(img)
    frame(d, w, h)
    header(d, w)
    for s in wells:
        x, y = slot_xy(s)
        well(d, x, y, ring=s in rings)
    return img


def pip(size, on):
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, size - 1, size - 1], fill=LAVA[0] if on else (10, 8, 9), outline=MORTAR)
    if size > 3:
        d.rectangle([1, 1, size - 2, size - 2], fill=LAVA[2] if on else (40, 33, 34))
        if on:
            d.rectangle([2, 2, size - 3, size - 3], fill=LAVA[3])
    elif on:
        d.rectangle([0, 0, size - 1, size - 1], fill=LAVA[2])
        d.point((0, 0), fill=LAVA[3])
    return img


def ascii_font(y):
    """The vanilla text font, drawn at height y instead of on the title line."""
    rows = ["\u0000" * 16] * 2 + ["".join(chr(c) for c in range(r, r + 16)) for r in range(0x20, 0x80, 16)] + ["\u0000" * 16] * 8
    rows[7] = rows[7][:-1] + "\u0000"
    return {"providers": [{"type": "bitmap", "file": "minecraft:font/ascii.png", "height": 8, "ascent": 13 - y, "chars": rows}]}


def main():
    tex = ROOT / "textures" / "gui"
    tex.mkdir(parents=True, exist_ok=True)
    for old in tex.glob("*.png"):
        old.unlink()
    (ROOT / "font").mkdir(parents=True, exist_ok=True)
    advances = {chr(0xF100 + i): 1 << i for i in range(8)}
    advances.update({chr(0xF110 + i): -(1 << i) for i in range(8)})
    providers = [{"type": "space", "advances": advances}]

    def add(name, img, glyph, ascent):
        img.save(tex / f"{name}.png")
        providers.append({"type": "bitmap", "file": f"blackforge:gui/{name}.png", "height": img.height, "ascent": ascent, "chars": [glyph]})

    add("forge", forge(), "", 13)
    add("storage", plain(3, list(range(27)), []), "", 13)
    add("agent", plain(6, [1, 3, 5, 7, 9, 10, 11, 13, 15, 16, 17] + list(range(18, 54)), [1, 3, 5, 7, 15]), "", 13)
    add("workshop", plain(4, [4, 13, 19, 21, 23, 25, 27, 31], [13, 31]), "", 13)
    add("home", plain(6, [4, 10, 11, 12, 13, 14, 15, 19, 20, 21, 22, 28, 29, 30, 31, 32, 33, 37, 38, 39, 46, 49, 52], []), "\ue00a", 13)
    add("pip_on", pip(3, True), "", 13 - PIP_Y)
    add("pip_off", pip(3, False), "", 13 - PIP_Y)
    add("count_on", pip(7, True), "", 13 - COUNT_Y)
    add("count_off", pip(7, False), "", 13 - COUNT_Y)
    (ROOT / "font" / "gui.json").write_text(json.dumps({"providers": providers}, indent=2) + "\n", encoding="utf-8")
    for name, y in LINES.items():
        (ROOT / "font" / f"{name}.json").write_text(json.dumps(ascii_font(y), indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
