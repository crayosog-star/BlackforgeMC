"""Paint the 16x16 item icons of the Raft game mode into the resource pack (textures/item/raft_<id>.png),
plus their models (models/item/raft_<id>.json) and item definitions (items/raft_<id>.json).

Used by RaftItems.java through setItemModel(blackforge:raft_<id>).
Run: python tools/gen_raft_items.py   then rebuild the pack (tools/build_pack.py).
"""
import json
import math
import pathlib
import struct
import zlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
PACK = ROOT / "resourcepack" / "assets" / "blackforge"
N = 16


class Img:
    def __init__(self):
        self.p = [[None] * N for _ in range(N)]

    def put(self, x, y, c):
        if 0 <= x < N and 0 <= y < N and c is not None:
            self.p[y][x] = c

    def rect(self, x0, y0, x1, y1, c):
        for y in range(y0, y1 + 1):
            for x in range(x0, x1 + 1):
                self.put(x, y, c)

    def line(self, x0, y0, x1, y1, c):
        n = max(abs(x1 - x0), abs(y1 - y0), 1)
        for i in range(n + 1):
            self.put(round(x0 + (x1 - x0) * i / n), round(y0 + (y1 - y0) * i / n), c)

    def disc(self, cx, cy, r, c):
        for y in range(N):
            for x in range(N):
                if (x - cx) ** 2 + (y - cy) ** 2 <= r * r:
                    self.put(x, y, c)

    def ring(self, cx, cy, r0, r1, c):
        for y in range(N):
            for x in range(N):
                d = (x - cx) ** 2 + (y - cy) ** 2
                if r0 * r0 <= d <= r1 * r1:
                    self.put(x, y, c)

    def outline(self, c):
        """A 1px dark border around everything drawn, inside the canvas where possible."""
        edge = []
        for y in range(N):
            for x in range(N):
                if self.p[y][x] is None:
                    continue
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < N and 0 <= ny < N and self.p[ny][nx] is None:
                        edge.append((nx, ny))
        for x, y in edge:
            self.p[y][x] = c

    def save(self, path):
        raw = b"".join(b"\0" + b"".join(bytes(c + (255,)) if c else b"\0\0\0\0" for c in row) for row in self.p)
        def chunk(t, d):
            return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", N, N, 8, 6, 0, 0, 0))
                         + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


OUT = (24, 24, 32)
WOOD, WOOD_L, WOOD_D = (190, 145, 90), (214, 172, 113), (136, 98, 58)
METAL, METAL_L, METAL_D = (150, 158, 168), (196, 204, 212), (92, 98, 108)
WHITE, WHITE_D = (240, 244, 248), (190, 200, 212)
SEA, SEA_L, SEA_D = (60, 110, 160), (110, 165, 210), (36, 70, 110)
FRESH, FRESH_L = (110, 214, 255), (200, 242, 255)
GLASS, GLASS_L = (190, 225, 240), (235, 250, 255)
THATCH, THATCH_L, THATCH_D = (217, 194, 122), (240, 222, 150), (168, 144, 80)
ROPE = (120, 90, 56)
ORANGE, ORANGE_D = (255, 159, 67), (200, 100, 30)
GREEN, GREEN_L = (126, 220, 140), (200, 255, 200)
DIRT, DIRT_D, GRASS = (122, 84, 52), (88, 58, 36), (110, 190, 80)


def plastic():
    i = Img()
    i.rect(3, 4, 12, 11, WHITE_D)
    i.rect(4, 5, 11, 10, WHITE)
    i.rect(5, 5, 7, 6, (255, 255, 255))
    i.line(4, 10, 11, 10, WHITE_D)
    i.line(10, 5, 10, 9, (214, 224, 236))
    i.put(11, 3, WHITE); i.put(12, 3, WHITE_D)   # a curled corner
    return i


def scrap():
    i = Img()
    i.rect(4, 6, 12, 10, METAL)
    i.rect(5, 5, 11, 5, METAL_L)
    i.rect(3, 8, 3, 9, METAL_D); i.rect(13, 7, 13, 8, METAL_D)
    i.rect(4, 10, 12, 11, METAL_D)
    i.line(5, 6, 9, 6, METAL_L)
    i.put(8, 8, (130, 70, 40)); i.put(9, 9, (150, 84, 48)); i.put(6, 9, (130, 70, 40))   # rust
    i.put(11, 7, OUT)                                                                # a bolt hole
    return i


def thatch():
    i = Img()
    for k in range(5):
        i.line(3 + k * 2, 12, 5 + k * 2, 3, THATCH if k % 2 else THATCH_L)
        i.line(4 + k * 2, 12, 6 + k * 2, 3, THATCH_D if k % 2 else THATCH)
    i.rect(3, 8, 13, 9, ROPE)
    i.line(3, 8, 13, 8, (150, 116, 74))
    return i


def foundation():
    i = Img()
    i.rect(1, 5, 14, 12, WOOD)
    i.rect(1, 5, 14, 6, WOOD_L)
    for x in (1, 14):
        i.rect(x, 5, x, 12, WOOD_D)
    for y in (8, 11):
        i.line(1, y, 14, y, WOOD_D)
    i.rect(1, 12, 14, 12, WOOD_D)
    i.put(3, 9, WOOD_D); i.put(12, 7, WOOD_D); i.put(8, 10, WOOD_D)
    i.put(6, 6, WOOD_L); i.put(5, 6, WOOD_L)
    i.line(2, 4, 13, 4, (90, 160, 210)); i.line(3, 3, 12, 3, (130, 190, 235))   # a rim of water
    return i


def _glass(i, fill, fill_l, level):
    i.line(4, 3, 5, 13, GLASS); i.line(11, 3, 10, 13, GLASS)
    i.line(5, 13, 10, 13, GLASS); i.line(4, 3, 11, 3, GLASS_L)
    i.put(4, 2, GLASS_L); i.put(11, 2, GLASS_L)
    for y in range(level, 13):
        w = 1 + (12 - y) // 12
        i.line(5 + w - 1, y, 10 - w + 1, y, fill)
    if fill:
        i.line(6, level, 9, level, fill_l)
    i.put(5, 5, GLASS_L); i.put(5, 6, GLASS_L)


def cup():
    i = Img()
    _glass(i, None, None, 13)
    return i


def salt():
    i = Img()
    _glass(i, SEA, SEA_L, 6)
    i.put(8, 9, SEA_D); i.put(7, 11, SEA_D)
    i.put(9, 4, WHITE); i.put(6, 4, WHITE)   # salt crust on the rim
    return i


def fresh():
    i = Img()
    _glass(i, FRESH, FRESH_L, 6)
    i.put(8, 3, FRESH_L)
    return i


def hook(adv=False):
    i = Img()
    m, ml, md = ((255, 208, 110), (255, 240, 170), (190, 140, 50)) if adv else (METAL, METAL_L, METAL_D)
    i.line(8, 1, 8, 9, m)
    i.line(7, 1, 7, 9, ml)
    i.put(8, 1, md)
    i.ring(8, 10, 2, 3, m)
    i.rect(9, 8, 12, 8, OUT)   # clear the right side of the ring to open the hook
    i.put(11, 7, m); i.put(12, 6, m); i.put(12, 5, ml)   # barb
    i.line(6, 2, 10, 2, WOOD_D); i.line(6, 3, 10, 3, WOOD)   # rope loop at the top
    if adv:
        i.put(6, 6, (255, 255, 255)); i.put(10, 11, (255, 255, 255))
    for y in range(N):
        for x in range(N):
            if i.p[y][x] == OUT:
                i.p[y][x] = None
    return i


def adv_hook():
    return hook(True)


def workbench():
    i = Img()
    i.rect(1, 3, 14, 6, WOOD_L)
    i.rect(1, 6, 14, 7, WOOD)
    i.line(1, 3, 14, 3, (232, 196, 140))
    i.rect(2, 7, 4, 14, WOOD_D); i.rect(11, 7, 13, 14, WOOD_D)
    i.rect(5, 8, 10, 8, WOOD_D)
    # tools on top
    i.line(4, 2, 7, 2, METAL_L); i.put(3, 2, WOOD_D)
    i.rect(10, 1, 12, 2, METAL); i.put(11, 0, WOOD_D)
    i.line(5, 4, 11, 4, WOOD); i.put(8, 5, WOOD_D)
    return i


def purifier():
    i = Img()
    i.rect(3, 6, 12, 13, METAL_D)
    i.rect(4, 6, 11, 13, METAL)
    i.rect(4, 6, 5, 13, METAL_L)
    i.rect(2, 5, 13, 6, METAL_L)
    i.rect(4, 6, 11, 7, FRESH)           # water inside
    i.line(5, 6, 10, 6, FRESH_L)
    i.rect(6, 10, 9, 12, ORANGE_D); i.rect(7, 10, 8, 11, ORANGE)   # the fire below
    i.put(8, 9, (255, 220, 120))
    i.line(11, 5, 13, 2, GLASS); i.put(13, 2, GLASS_L)   # steam pipe
    i.put(3, 12, METAL_D); i.put(12, 12, METAL_D)
    return i


def plot():
    i = Img()
    i.rect(1, 6, 14, 13, DIRT)
    i.rect(1, 12, 14, 13, DIRT_D)
    i.rect(1, 6, 14, 7, (150, 104, 66))
    i.line(1, 6, 14, 6, (170, 122, 80))
    for x in (4, 8, 12):
        i.line(x, 6, x, 3, GRASS); i.put(x - 1, 4, GREEN); i.put(x + 1, 3, GREEN_L)
    i.put(3, 9, DIRT_D); i.put(9, 10, DIRT_D); i.put(12, 9, DIRT_D)
    i.rect(1, 8, 14, 8, (60, 110, 170))   # the water that keeps it moist
    for x in range(2, 14, 3):
        i.put(x, 8, (120, 175, 225))
    return i


def sail():
    i = Img()
    i.line(3, 1, 3, 14, WOOD_D); i.line(4, 1, 4, 14, WOOD)
    i.put(3, 0, WOOD_D); i.put(4, 0, WOOD)
    for y in range(2, 12):
        edge = 13 - (y - 2) // 4
        i.line(5, y, edge, y, WHITE)
        i.put(edge, y, WHITE_D)
    i.line(5, 11, 12, 11, WHITE_D)
    i.line(5, 5, 11, 5, WHITE_D); i.line(5, 8, 11, 8, WHITE_D)
    i.put(6, 3, (255, 255, 255)); i.put(7, 3, (255, 255, 255))
    i.rect(5, 12, 12, 12, ROPE)
    return i


def wheel():
    i = Img()
    i.ring(8, 8, 4, 6, WOOD)
    i.ring(8, 8, 5, 6, WOOD_L)
    i.disc(8, 8, 1, METAL)
    for k in range(8):
        a = k * math.pi / 4
        i.line(8, 8, round(8 + 7 * math.cos(a)), round(8 + 7 * math.sin(a)), WOOD_D if k % 2 else WOOD)
    i.disc(8, 8, 1, METAL_L)
    return i


def motor():
    i = Img()
    i.rect(2, 5, 12, 12, METAL_D)
    i.rect(3, 5, 11, 11, METAL)
    i.rect(3, 5, 11, 6, METAL_L)
    for x in (4, 6, 8, 10):
        i.line(x, 7, x, 10, METAL_D)           # cooling fins
    i.rect(12, 7, 14, 10, METAL_D)             # the shaft housing
    i.line(14, 5, 14, 12, ORANGE); i.line(13, 6, 15, 6, ORANGE_D); i.line(13, 11, 15, 11, ORANGE_D)   # propeller
    i.rect(5, 2, 7, 4, METAL_D); i.put(6, 1, (90, 90, 96)); i.put(6, 0, (160, 160, 170))   # exhaust
    i.rect(8, 12, 10, 13, ORANGE_D); i.put(9, 12, ORANGE)
    return i


def receiver():
    i = Img()
    i.rect(3, 6, 12, 14, METAL_D)
    i.rect(4, 7, 11, 13, (54, 62, 70))
    i.rect(4, 7, 11, 8, (70, 80, 90))
    i.disc(6, 11, 1, GREEN); i.put(6, 11, GREEN_L)    # the signal light
    i.line(9, 10, 11, 10, METAL); i.line(9, 12, 11, 12, METAL)
    i.line(11, 6, 13, 1, METAL_L); i.put(13, 0, GREEN)   # antenna
    i.put(10, 3, GREEN); i.put(14, 3, GREEN); i.put(15, 4, (60, 150, 80))   # signal waves
    return i


ITEMS = dict(plastic=plastic, scrap=scrap, thatch=thatch, foundation=foundation, cup=cup, salt=salt, fresh=fresh, hook=hook,
             adv_hook=adv_hook, workbench=workbench, purifier=purifier, plot=plot, sail=sail, wheel=wheel, motor=motor, receiver=receiver)


def main():
    for id_, fn in ITEMS.items():
        img = fn()
        img.outline(OUT)
        img.save(PACK / "textures" / "item" / f"raft_{id_}.png")
        name = f"raft_{id_}"
        (PACK / "models" / "item").mkdir(parents=True, exist_ok=True)
        (PACK / "models" / "item" / f"{name}.json").write_text(json.dumps({"parent": "minecraft:item/generated", "textures": {"layer0": f"blackforge:item/{name}"}}, indent=2) + "\n")
        (PACK / "items").mkdir(parents=True, exist_ok=True)
        (PACK / "items" / f"{name}.json").write_text(json.dumps({"model": {"type": "minecraft:model", "model": f"blackforge:item/{name}"}}, indent=2) + "\n")
    print(f"wrote {len(ITEMS)} raft item textures")


if __name__ == "__main__":
    main()
