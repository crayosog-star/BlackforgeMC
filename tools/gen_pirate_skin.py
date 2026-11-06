"""Paint Captain Salt-Eye's skin (64x64, classic arms) into the resource pack: textures/entity/pirate.png.

Used by the Mannequin NPC in AbyssPirate.java through a skin patch (body = blackforge:entity/pirate).
Run: python tools/gen_pirate_skin.py   then rebuild the pack (tools/build_pack.py).
"""
import pathlib
import random
import struct
import zlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "resourcepack" / "assets" / "blackforge" / "textures" / "entity" / "pirate.png"

W = H = 64
px = [[(0, 0, 0, 0) for _ in range(W)] for _ in range(H)]
rnd = random.Random(7)

SKIN = (201, 150, 112)
SKIN_D = (176, 126, 92)
COAT = (30, 92, 98)         # teal sea-coat
COAT_D = (21, 66, 72)
COAT_L = (45, 122, 128)
TRIM = (214, 168, 64)       # gold
SHIRT = (214, 206, 186)
BELT = (58, 36, 22)
PANTS = (44, 38, 52)
BOOT = (34, 24, 18)
BOOT_L = (60, 42, 30)
HAT = (22, 22, 30)
HAT_L = (40, 40, 52)
BEARD = (92, 92, 96)
BEARD_L = (140, 140, 146)
EYE = (120, 220, 215)
PATCH = (14, 14, 18)


def put(x, y, c):
    if 0 <= x < W and 0 <= y < H:
        px[y][x] = c + (255,) if len(c) == 3 else c


def rect(x, y, w, h, c):
    for j in range(h):
        for i in range(w):
            put(x + i, y + j, c)


def shade(c, d):
    return tuple(max(0, min(255, v + d)) for v in c)


def noisy(x, y, w, h, c, amt=8):
    for j in range(h):
        for i in range(w):
            put(x + i, y + j, shade(c, rnd.randint(-amt, amt)))


def box_faces(ox, oy, w, h, d):
    """The six face rectangles of a UV-unwrapped box: top, bottom, right, front, left, back."""
    return {
        "top": (ox + d, oy, w, d), "bottom": (ox + d + w, oy, w, d),
        "right": (ox, oy + d, d, h), "front": (ox + d, oy + d, w, h),
        "left": (ox + d + w, oy + d, d, h), "back": (ox + d + w + d, oy + d, w, h)}


def fill_box(ox, oy, w, h, d, c, amt=7):
    for f in box_faces(ox, oy, w, h, d).values():
        noisy(*f, c, amt)


# ---- head (0,0) 8x8x8 ----
head = box_faces(0, 0, 8, 8, 8)
fill_box(0, 0, 8, 8, 8, SKIN, 6)
x, y, w, h = head["front"]
rect(x + 1, y + 4, 6, 4, BEARD)                          # grey beard
rect(x + 2, y + 3, 4, 1, BEARD_L)
rect(x + 3, y + 5, 2, 1, SKIN_D)                         # mouth
rect(x + 2, y + 2, 1, 1, EYE)                            # good eye
rect(x + 5, y + 2, 1, 1, PATCH)                          # eyepatch eye
rect(x + 4, y + 1, 3, 1, PATCH)
rect(x + 7, y, 1, 3, PATCH)                              # strap goes round
rect(x, y + 2, 5, 1, PATCH)
put(x + 2, y + 2, EYE)
rect(x + 1, y + 1, 2, 1, BEARD_L)                        # eyebrow
for k in ("right", "left"):
    fx, fy, fw, fh = head[k]
    rect(fx, fy + 4, fw, 4, BEARD)
    rect(fx, fy + 2, fw, 1, PATCH)                       # strap
fx, fy, fw, fh = head["back"]
rect(fx, fy + 5, fw, 3, BEARD_L)

# ---- hat overlay (32,0): a black tricorn with a gold band ----
hat = box_faces(32, 0, 8, 8, 8)
for name, (fx, fy, fw, fh) in hat.items():
    if name == "bottom":
        continue
    noisy(fx, fy, fw, fh, HAT, 5)
rect(hat["front"][0], hat["front"][1] + 3, 8, 1, TRIM)
rect(hat["right"][0], hat["right"][1] + 3, 8, 1, TRIM)
rect(hat["left"][0], hat["left"][1] + 3, 8, 1, TRIM)
rect(hat["back"][0], hat["back"][1] + 3, 8, 1, TRIM)
for name in ("front", "right", "left", "back"):
    fx, fy, fw, fh = hat[name]
    rect(fx, fy + 4, fw, 4, (0, 0, 0, 0))                # hat only covers the top half of the head
rect(*hat["top"], HAT_L)
rect(hat["top"][0] + 2, hat["top"][1] + 2, 4, 4, HAT)
put(hat["front"][0] + 4, hat["front"][1] + 3, (230, 230, 230))   # skull-and-bones dot

# ---- body (16,16) 8x12x4 ----
body = box_faces(16, 16, 8, 12, 4)
fill_box(16, 16, 8, 12, 4, SHIRT, 6)
# jacket overlay (16,32)
jack = box_faces(16, 32, 8, 12, 4)
for f in jack.values():
    noisy(*f, COAT, 7)
x, y, w, h = jack["front"]
rect(x + 3, y, 2, h, SHIRT)                              # open front shows the shirt
rect(x + 2, y, 1, h, TRIM)
rect(x + 5, y, 1, h, TRIM)
rect(x, y + 8, w, 1, BELT)                               # belt
rect(x + 3, y + 8, 2, 1, TRIM)
for k in range(0, 12, 3):
    put(x + 1, y + 1 + k, TRIM)
    put(x + 6, y + 1 + k, TRIM)
rect(x, y + 9, w, 3, COAT_D)                             # coat tails
rect(jack["back"][0], jack["back"][1] + 8, 8, 1, BELT)
rect(jack["back"][0], jack["back"][1] + 9, 8, 3, COAT_D)
rect(jack["top"][0], jack["top"][1], 8, 4, COAT_L)

# ---- arms: right (40,16) / left (32,48), 4x12x4, and sleeves (40,32) / (48,48) ----
for ox, oy, sx, sy in ((40, 16, 40, 32), (32, 48, 48, 48)):
    arm = box_faces(ox, oy, 4, 12, 4)
    fill_box(ox, oy, 4, 12, 4, SKIN, 6)
    rect(arm["front"][0], arm["front"][1], 4, 3, SHIRT)
    sl = box_faces(sx, sy, 4, 12, 4)
    for f in sl.values():
        noisy(*f, COAT, 7)
    for f in (sl["front"], sl["back"], sl["left"], sl["right"]):
        rect(f[0], f[1] + 8, f[2], 1, TRIM)              # gold cuff
        rect(f[0], f[1] + 9, f[2], 3, (0, 0, 0, 0))      # forearm bare
    rect(*sl["top"], COAT_L)

# ---- legs: right (0,16) / left (16,48), 4x12x4, and trousers (0,32) / (0,48) ----
for ox, oy, tx, ty in ((0, 16, 0, 32), (16, 48, 0, 48)):
    leg = box_faces(ox, oy, 4, 12, 4)
    fill_box(ox, oy, 4, 12, 4, PANTS, 5)
    for f in (leg["front"], leg["back"], leg["left"], leg["right"]):
        rect(f[0], f[1] + 6, f[2], 6, BOOT)              # tall boots
        rect(f[0], f[1] + 6, f[2], 1, BOOT_L)            # folded top
    rect(leg["front"][0], leg["front"][1] + 10, 4, 1, TRIM) # buckle
    rect(*leg["bottom"], BOOT)
    ov = box_faces(tx, ty, 4, 12, 4)
    for f in ov.values():
        rect(*f, (0, 0, 0, 0))


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
