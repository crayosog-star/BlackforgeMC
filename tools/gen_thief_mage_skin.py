"""Paint the Night Thief Mage's skin (64x64, classic arms) into the resource pack: textures/entity/thief_mage.png.

Used by the mage's Mannequin in survival/ThiefAmbush.java through a skin patch (body = blackforge:entity/thief_mage).
A deep violet hooded robe with dull gold trim, the face lost in shadow except two hex-purple eyes, a cloth mask, and
sleeves that hang past the hands. Palette matches the Hex Bolt's particles (150,60,230 / 60,15,110).
Run: python tools/gen_thief_mage_skin.py   then rebuild the pack (tools/build_pack.py).
"""
import pathlib
import random
import struct
import zlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "resourcepack" / "assets" / "blackforge" / "textures" / "entity" / "thief_mage.png"

W = H = 64
px = [[(0, 0, 0, 0) for _ in range(W)] for _ in range(H)]
rnd = random.Random(11)

SKIN = (150, 120, 108)      # pale, mostly in shadow
SHADOW = (28, 20, 38)       # inside the hood
ROBE = (58, 30, 92)         # deep violet
ROBE_D = (40, 20, 66)
ROBE_L = (84, 46, 128)
HOOD = (46, 24, 74)
HOOD_L = (70, 38, 110)
TRIM = (176, 140, 70)       # dull gold
TRIM_D = (128, 98, 46)
MASK = (34, 30, 40)         # cloth over nose and mouth
EYE = (196, 120, 255)       # hex purple glow
EYE_L = (236, 200, 255)
SASH = (24, 20, 30)
GEM = (150, 60, 230)
BOOT = (30, 24, 34)
CLEAR = (0, 0, 0, 0)


def put(x, y, c):
    if 0 <= x < W and 0 <= y < H:
        px[y][x] = c + (255,) if len(c) == 3 else c


def rect(x, y, w, h, c):
    for j in range(h):
        for i in range(w):
            put(x + i, y + j, c)


def shade(c, d):
    return tuple(max(0, min(255, v + d)) for v in c)


def noisy(x, y, w, h, c, amt=6):
    for j in range(h):
        for i in range(w):
            put(x + i, y + j, shade(c, rnd.randint(-amt, amt)))


def box_faces(ox, oy, w, h, d):
    """The six face rectangles of a UV-unwrapped box: top, bottom, right, front, left, back."""
    return {
        "top": (ox + d, oy, w, d), "bottom": (ox + d + w, oy, w, d),
        "right": (ox, oy + d, d, h), "front": (ox + d, oy + d, w, h),
        "left": (ox + d + w, oy + d, d, h), "back": (ox + d + w + d, oy + d, w, h)}


def fill_box(ox, oy, w, h, d, c, amt=6):
    for f in box_faces(ox, oy, w, h, d).values():
        noisy(*f, c, amt)


def folds(x, y, w, h, c):
    """Vertical cloth folds: a darker line every other column, lighter beside it."""
    for i in range(1, w, 3):
        for j in range(h):
            put(x + i, y + j, shade(c, -14))
            if i + 1 < w:
                put(x + i + 1, y + j, shade(c, 8))


# ---- head (0,0) 8x8x8: a face sunk in shadow ----
head = box_faces(0, 0, 8, 8, 8)
fill_box(0, 0, 8, 8, 8, SHADOW, 4)
x, y, w, h = head["front"]
rect(x + 1, y + 2, 6, 2, shade(SKIN, -60))                # brow, barely lit
rect(x + 1, y + 3, 2, 1, EYE)                              # eyes
rect(x + 5, y + 3, 2, 1, EYE)
put(x + 1, y + 3, EYE_L)
put(x + 5, y + 3, EYE_L)
rect(x, y + 4, 8, 4, MASK)                                 # cloth mask
rect(x, y + 4, 8, 1, shade(MASK, 14))
put(x + 3, y + 6, shade(MASK, -10))
put(x + 4, y + 6, shade(MASK, -10))
for k in ("right", "left"):
    fx, fy, fw, fh = head[k]
    rect(fx, fy + 4, fw, 4, MASK)

# ---- hood overlay (32,0): covers the head, opens around the face ----
hood = box_faces(32, 0, 8, 8, 8)
for name, f in hood.items():
    if name == "bottom":
        continue
    noisy(*f, HOOD, 5)
fx, fy, fw, fh = hood["front"]
rect(fx + 1, fy + 2, 6, 6, CLEAR)                          # the opening
rect(fx, fy, 8, 1, HOOD_L)                                 # light catches the brim
rect(fx, fy + 1, 8, 1, TRIM)
for j in range(2, 8):                                      # hood edges fold in, dark cloth
    put(fx, fy + j, shade(HOOD, -12))
    put(fx + 7, fy + j, shade(HOOD, -12))
rect(*hood["top"], HOOD_L)
tx, ty, tw, th = hood["top"]
rect(tx + 1, ty + 1, 6, 6, HOOD)
for k in ("right", "left", "back"):
    sx, sy, sw, sh = hood[k]
    folds(sx, sy, sw, sh, HOOD)
    rect(sx, sy, sw, 1, HOOD_L)
bx, by, bw, bh = hood["back"]
rect(bx + 3, by + 5, 2, 3, TRIM_D)                         # hood point hangs down the back

# ---- body (16,16) 8x12x4: robe ----
body = box_faces(16, 16, 8, 12, 4)
fill_box(16, 16, 8, 12, 4, ROBE, 5)
for f in (body["front"], body["back"]):
    folds(*f, ROBE)
# robe overlay (16,32): trim, sash and a hex gem
over = box_faces(16, 32, 8, 12, 4)
for f in over.values():
    rect(*f, CLEAR)
x, y, w, h = over["front"]
rect(x + 3, y, 2, h, TRIM)                                 # gold placket down the front
rect(x + 3, y, 1, h, TRIM_D)
rect(x, y + 6, w, 2, SASH)                                 # sash
rect(x + 3, y + 6, 2, 2, GEM)
put(x + 3, y + 6, EYE_L)
rect(x, y, w, 1, ROBE_L)                                   # collar
rect(x + 1, y + 1, 1, 1, TRIM)
rect(x + 6, y + 1, 1, 1, TRIM)
bx, by, bw, bh = over["back"]
rect(bx, by + 6, bw, 2, SASH)
for k in ("right", "left"):
    sx, sy, sw, sh = over[k]
    rect(sx, sy + 6, sw, 2, SASH)
rect(*over["top"], ROBE_L)

# ---- arms: right (40,16) / left (32,48), 4x12x4, sleeves (40,32) / (48,48) ----
for ox, oy, sx, sy in ((40, 16, 40, 32), (32, 48, 48, 48)):
    arm = box_faces(ox, oy, 4, 12, 4)
    fill_box(ox, oy, 4, 12, 4, ROBE_D, 5)
    for f in (arm["front"], arm["back"], arm["left"], arm["right"]):
        rect(f[0], f[1] + 10, f[2], 2, shade(SKIN, -40))   # fingertips just showing
    rect(*arm["bottom"], shade(SKIN, -40))
    sl = box_faces(sx, sy, 4, 12, 4)
    for f in sl.values():
        noisy(*f, ROBE, 5)
    for f in (sl["front"], sl["back"], sl["left"], sl["right"]):
        rect(f[0], f[1] + 8, f[2], 1, TRIM)                 # wide gold cuff
        rect(f[0], f[1] + 9, f[2], 1, ROBE_D)
        rect(f[0], f[1] + 10, f[2], 2, CLEAR)
    rect(*sl["top"], ROBE_L)
    rect(*sl["bottom"], CLEAR)

# ---- legs: right (0,16) / left (16,48), 4x12x4, robe skirt overlay (0,32) / (0,48) ----
for ox, oy, tx, ty in ((0, 16, 0, 32), (16, 48, 0, 48)):
    leg = box_faces(ox, oy, 4, 12, 4)
    fill_box(ox, oy, 4, 12, 4, ROBE_D, 5)
    for f in (leg["front"], leg["back"], leg["left"], leg["right"]):
        rect(f[0], f[1] + 10, f[2], 2, BOOT)
    rect(*leg["bottom"], BOOT)
    ov = box_faces(tx, ty, 4, 12, 4)
    for f in ov.values():
        rect(*f, CLEAR)
    for f in (ov["front"], ov["back"], ov["left"], ov["right"]):
        noisy(f[0], f[1], f[2], 9, ROBE, 5)                 # the robe falls to the ankles
        folds(f[0], f[1], f[2], 9, ROBE)
        rect(f[0], f[1] + 8, f[2], 1, TRIM)                 # gold hem


def write_png(path):
    raw = b"".join(b"\x00" + b"".join(bytes(px[y][x]) for x in range(W)) for y in range(H))

    def chunk(t, d):
        c = struct.pack(">I", len(d)) + t + d
        return c + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 6, 0, 0, 0))
                     + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


if __name__ == "__main__":
    write_png(OUT)
    print("wrote", OUT)
