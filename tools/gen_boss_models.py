"""Generate the world bosses' custom models and textures for the BlackForgeMC resource pack.

  python tools/gen_boss_models.py     then     python tools/build_pack.py --format 97

Each boss is a rig of groups (head, arms, legs...) and each group is one item model made of textured boxes, drawn by the
plugin as an item display (scale SCALE) that it poses and animates. This file is the source of truth for the models, the
textures (painted here, no image files to edit) and the rig (group names, parents, pivots) that the plugin reads.

Units: boss space is in blocks, +y up, +z the boss's front, origin between its feet. One model unit is SCALE/16 block.
Item models can only reach -16..32 units, so every box must stay within ~4.5 blocks of its group's pivot.
"""
import json
import math
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / "resourcepack" / "assets" / "blackforge"
RIG = ROOT / "plugins-src" / "BlackForgeCore" / "src" / "main" / "resources" / "bossrig"

SCALE = 3.0
U = 16.0 / SCALE          # model units per block (= texels per block)
ATLAS = 256


# ---------------------------------------------------------------------------------------------- noise + png

def h(x, y, s):
    n = (x * 374761393 + y * 668265263 + s * 2147483647) & 0xFFFFFFFF
    n = ((n ^ (n >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((n ^ (n >> 16)) & 0xFFFF) / 65535.0


def vnoise(x, y, s, cell):
    gx, gy = x / cell, y / cell
    x0, y0 = int(math.floor(gx)), int(math.floor(gy))
    fx, fy = gx - x0, gy - y0
    fx, fy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
    a, b, c, d = h(x0, y0, s), h(x0 + 1, y0, s), h(x0, y0 + 1, s), h(x0 + 1, y0 + 1, s)
    return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy


def fbm(x, y, s, cell=6):
    return 0.6 * vnoise(x, y, s, cell) + 0.3 * vnoise(x, y, s + 7, cell / 2) + 0.1 * vnoise(x, y, s + 13, max(1.5, cell / 4))


def cracks(x, y, s, cell=7):
    """Distance-to-edge style cracks: small where two cells meet."""
    cx, cy = int(x // cell), int(y // cell)
    best = (9e9, 9e9)
    for ox in (-1, 0, 1):
        for oy in (-1, 0, 1):
            px = (cx + ox + h(cx + ox, cy + oy, s)) * cell
            py = (cy + oy + h(cx + ox, cy + oy, s + 5)) * cell
            d = math.hypot(x - px, y - py)
            if d < best[0]:
                best = (d, best[0])
            elif d < best[1]:
                best = (best[0], d)
    return best[1] - best[0]


def png(path, w, hgt, px):
    raw = b"".join(b"\x00" + b"".join(bytes(px[y][x]) for x in range(w)) for y in range(hgt))

    def chunk(t, d):
        c = struct.pack(">I", len(d)) + t + d
        return c + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, hgt, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def mix(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def shade(c, k):
    return tuple(max(0, min(255, int(v * k))) for v in c)


# ---------------------------------------------------------------------------------------------- materials

def painter(key, face, w, hgt, seed):
    """Returns f(px, py) -> (r, g, b, a) for one face of w x hgt texels."""
    s = seed
    base = key.split(":")[0]

    def stone(px, py, dark=(30, 28, 36), light=(62, 58, 70)):
        n = fbm(px, py, s, 5)
        c = mix(dark, light, n)
        if (py % 6 == 0) or ((px + (py // 6 % 2) * 4) % 8 == 0 and py % 6 != 0):
            c = shade(c, 0.78)   # brick lines
        return c

    def lava(px, py, dark=(34, 12, 10), hot=(255, 120, 18), white=(255, 214, 90)):
        e = cracks(px, py, s, 6)
        n = fbm(px, py, s + 3, 4)
        if e < 0.9:
            return mix(hot, white, (0.9 - e) + n * 0.3)
        return mix(dark, (90, 30, 14), n * 0.9)

    def bone(px, py):
        n = fbm(px, py, s, 4)
        c = mix((196, 188, 164), (238, 232, 212), n)
        if cracks(px, py, s + 2, 9) < 0.5:
            c = shade(c, 0.72)
        return c

    def ice(px, py, lo=(110, 170, 235), hi=(205, 238, 255)):
        n = fbm(px, py, s, 6)
        c = mix(lo, hi, n)
        streak = vnoise(px + py * 0.6, py * 0.3, s + 4, 3)
        if streak > 0.72:
            c = mix(c, (255, 255, 255), 0.6)
        if cracks(px, py, s + 9, 8) < 0.45:
            c = shade(c, 0.82)
        return c

    def void(px, py):
        n = fbm(px, py, s, 5)
        c = mix((8, 4, 14), (26, 12, 40), n)
        if cracks(px, py, s + 1, 9) < 0.55:
            c = mix(c, (150, 70, 235), 0.85)
        return c

    def cloth(px, py, dark=(12, 10, 18), light=(30, 24, 42)):
        n = fbm(px, py, s, 3)
        c = mix(dark, light, n)
        if (px + py) % 5 == 0:
            c = shade(c, 1.2)
        return c

    def metal(px, py, lo, hi):
        n = fbm(px, py, s, 5)
        c = mix(lo, hi, n)
        if py % 8 == 1:
            c = shade(c, 1.18)
        if h(px, py, s + 6) > 0.97:
            c = shade(c, 1.3)
        return c

    def flat(c):
        return lambda px, py: c

    def scales(px, py, dark=(14, 40, 44), mid=(26, 66, 62), light=(70, 130, 112)):
        # overlapping rows of scales, each a little different, with a bright lip along the lower edge
        row = py // 3
        off = (row % 2) * 2
        cell = (px + off) // 4
        lx, ly = (px + off) % 4, py % 3
        n = h(cell, row, s + 11)
        c = mix(dark, mid, 0.25 + 0.75 * n)
        if ly == 2:
            c = mix(c, light, 0.35 + 0.35 * h(cell, row, s + 12))
        if lx == 0:
            c = shade(c, 0.72)
        return mix(c, shade(c, 1.12), fbm(px, py, s, 3) * 0.6)

    def belly(px, py):
        c = mix((120, 150, 128), (178, 198, 168), fbm(px, py, s, 4))
        if py % 3 == 0:
            c = shade(c, 0.8)
        return c

    table = {
        "blackstone": stone,
        "obsidian": lambda px, py: mix((10, 6, 20), (44, 22, 78), fbm(px, py, s, 4)),
        "magma": lava,
        "lavaglow": lambda px, py: lava(px, py, (120, 40, 12), (255, 150, 30), (255, 230, 120)),
        "bone": bone,
        "ice": ice,
        "icepacked": lambda px, py: ice(px, py, (70, 120, 200), (150, 205, 245)),
        "iceclear": lambda px, py: ice(px, py, (150, 215, 255), (235, 250, 255)),
        "void": void,
        "robe": cloth,
        "hood": lambda px, py: cloth(px, py, (6, 4, 10), (20, 14, 30)),
        "cloak": lambda px, py: cloth(px, py, (20, 18, 24), (46, 42, 54)),
        "copper": lambda px, py: metal(px, py, (150, 80, 52), (214, 128, 84)) if h(px // 3, py // 3, s + 8) < 0.82 else mix((70, 150, 130), (110, 190, 160), fbm(px, py, s, 3)),
        "gold": lambda px, py: metal(px, py, (214, 160, 30), (255, 226, 90)),
        "rod": lambda px, py: metal(px, py, (130, 90, 70), (190, 130, 100)),
        "steel": lambda px, py: metal(px, py, (40, 36, 52), (92, 84, 110)),
        "wood": lambda px, py: mix((72, 48, 26), (118, 82, 44), vnoise(px * 0.4, py * 3, s, 3)),
        "string": flat((226, 226, 226)),
        "crystal": lambda px, py: mix((120, 50, 200), (220, 150, 255), fbm(px, py, s, 3)),
        "soul": lambda px, py: mix((40, 190, 200), (170, 255, 255), fbm(px, py, s, 3)),
        "core": lambda px, py: mix((60, 200, 210), (230, 255, 255), fbm(px, py, s, 3)),
        "g_orange": lambda px, py: mix((255, 140, 20), (255, 235, 120), fbm(px, py, s, 3)),
        "g_cyan": lambda px, py: mix((80, 230, 240), (230, 255, 255), fbm(px, py, s, 3)),
        "g_purple": lambda px, py: mix((170, 80, 255), (240, 200, 255), fbm(px, py, s, 3)),
        "g_yellow": lambda px, py: mix((255, 220, 40), (255, 255, 190), fbm(px, py, s, 3)),
        "g_green": lambda px, py: mix((90, 255, 150), (230, 255, 200), fbm(px, py, s, 3)),
        "scales": scales,
        "scalesdark": lambda px, py: scales(px, py, (6, 18, 22), (14, 36, 38), (40, 82, 76)),
        "scaleslight": lambda px, py: scales(px, py, (40, 84, 78), (70, 128, 112), (150, 196, 170)),
        "belly": belly,
    }
    fn = table[base]
    decal = key.split(":")[1] if ":" in key else None

    def f(px, py):
        c = fn(px, py)
        a = 255
        if decal and face == "south":
            c = decorate(decal, c, px, py, w, hgt)
        if px == 0 or py == 0 or px == w - 1 or py == hgt - 1:
            c = shade(c, 0.78)
        return (c[0], c[1], c[2], a)
    return f


def decorate(kind, c, px, py, w, hgt):
    """Faces: dark eye hollows, noses, teeth. Coordinates are in the face's own texels; the face is symmetric."""
    mx = w / 2.0
    if kind == "skull":
        for sx in (-1, 1):
            ex, ey = mx + sx * w * 0.24, hgt * 0.42
            if abs(px + 0.5 - ex) < w * 0.14 and abs(py + 0.5 - ey) < hgt * 0.14:
                return (6, 6, 10)
        if abs(px + 0.5 - mx) < w * 0.05 and abs(py + 0.5 - hgt * 0.68) < hgt * 0.08:
            return (20, 18, 20)
        if py > hgt * 0.86 and (px % 3 != 0):
            return (250, 248, 238)
    elif kind == "ravhead":
        for sx in (-1, 1):
            ex, ey = mx + sx * w * 0.27, hgt * 0.4
            if abs(px + 0.5 - ex) < w * 0.15 and abs(py + 0.5 - ey) < hgt * 0.12:
                return (12, 4, 4)
        if py < hgt * 0.22:
            return shade(c, 0.6)
    elif kind == "hoodface":
        if abs(px + 0.5 - mx) < w * 0.34 and abs(py + 0.5 - hgt * 0.55) < hgt * 0.3:
            return mix((2, 1, 6), (10, 4, 20), 0.5)
    elif kind == "titanhead":
        for sx in (-1, 1):
            ex, ey = mx + sx * w * 0.24, hgt * 0.42
            if abs(px + 0.5 - ex) < w * 0.14 and abs(py + 0.5 - ey) < hgt * 0.1:
                return (30, 70, 120)
        if abs(px + 0.5 - mx) < w * 0.2 and py > hgt * 0.75:
            return shade(c, 0.7)
    elif kind == "brutehead":
        for sx in (-1, 1):
            ex, ey = mx + sx * w * 0.25, hgt * 0.42
            if abs(px + 0.5 - ex) < w * 0.15 and abs(py + 0.5 - ey) < hgt * 0.11:
                return (30, 20, 14)
        if py < hgt * 0.28:
            return shade(c, 0.55)
    elif kind == "serpenthead":
        for sx in (-1, 1):
            ex, ey = mx + sx * w * 0.16, hgt * 0.55
            if abs(px + 0.5 - ex) < w * 0.07 and abs(py + 0.5 - ey) < hgt * 0.1:
                return (4, 12, 12)
        if py < hgt * 0.2:
            return shade(c, 0.6)
    elif kind == "core":
        d = math.hypot(px + 0.5 - mx, py + 0.5 - hgt / 2) / (min(w, hgt) / 2)
        if d < 0.95:
            return mix((255, 255, 255), (120, 240, 245), d)
    return c


# ---------------------------------------------------------------------------------------------- box models

FACES = ("north", "east", "south", "west", "up", "down")


class Box:
    def __init__(self, group, key, c, s, rot=None):
        self.group, self.key, self.c, self.s, self.rot = group, key, c, s, rot   # rot = (axis, degrees)


class Boss:
    def __init__(self, bid):
        self.id = bid
        self.groups = []    # (name, parent, pivot)
        self.boxes = []
        self.seed = sum(ord(ch) for ch in bid)
        self.scale = SCALE      # one display of this model is this many blocks per 16 model units
        self.atlas = ATLAS

    def group(self, name, parent, pivot):
        self.groups.append((name, parent, pivot))

    def box(self, group, key, c, s, rot=None):
        self.boxes.append(Box(group, key, c, s, rot))

    def pivot(self, name):
        for n, _, p in self.groups:
            if n == name:
                return p
        base = name[:-5] if name.endswith("_glow") else name
        for n, _, p in self.groups:
            if n == base:
                return p
        raise KeyError(name)


def clamp_units(v):
    return max(-16.0, min(32.0, v))


def build(b):
    """Pack every face into the atlas, paint it, and write the models, item definitions, rig and texture."""
    U = 16.0 / b.scale
    A = b.atlas
    px = [[(0, 0, 0, 0)] * A for _ in range(A)]
    cx = cy = rowh = 0

    def alloc(w, hh):
        nonlocal cx, cy, rowh
        if cx + w + 1 > A:
            cx, cy, rowh = 0, cy + rowh + 1, 0
        if cy + hh + 1 > A:
            raise SystemExit(f"{b.id}: atlas full")
        x, y = cx, cy
        cx += w + 1
        rowh = max(rowh, hh)
        return x, y

    by_group = {}
    for bx in b.boxes:
        by_group.setdefault(bx.group, []).append(bx)
    models = {}
    for gname, boxes in by_group.items():
        pv = b.pivot(gname)
        elements = []
        for i, bx in enumerate(boxes):
            sx, sy, sz = bx.s
            lo = [8 + (bx.c[k] - bx.s[k] / 2 - pv[k]) * U for k in range(3)]
            hi = [8 + (bx.c[k] + bx.s[k] / 2 - pv[k]) * U for k in range(3)]
            for k in range(3):
                if lo[k] < -16 or hi[k] > 32:
                    raise SystemExit(f"{b.id}/{gname}: a box is out of the model range on axis {k} ({lo[k]:.1f}..{hi[k]:.1f})")
            dims = {"x": max(1, round(sx * U)), "y": max(1, round(sy * U)), "z": max(1, round(sz * U))}
            fsize = {"north": (dims["x"], dims["y"]), "south": (dims["x"], dims["y"]), "east": (dims["z"], dims["y"]), "west": (dims["z"], dims["y"]),
                     "up": (dims["x"], dims["z"]), "down": (dims["x"], dims["z"])}
            faces = {}
            for fn in FACES:
                w, hh = fsize[fn]
                ox, oy = alloc(w, hh)
                f = painter(bx.key, fn, w, hh, b.seed + i * 17 + FACES.index(fn) * 31 + len(gname))
                for yy in range(hh):
                    for xx in range(w):
                        px[oy + yy][ox + xx] = f(xx, yy)
                k = 16.0 / A
                faces[fn] = {"uv": [ox * k, oy * k, (ox + w) * k, (oy + hh) * k], "texture": "#0"}
            el = {"from": [round(v, 3) for v in lo], "to": [round(v, 3) for v in hi], "faces": faces}
            if bx.rot:
                axis, deg = bx.rot
                el["rotation"] = {"angle": deg, "axis": axis, "origin": [round(8 + (bx.c[k] - pv[k]) * U, 3) for k in range(3)]}
            elements.append(el)
        models[gname] = {"textures": {"0": f"blackforge:item/boss/{b.id}", "particle": f"blackforge:item/boss/{b.id}"}, "elements": elements}
    png(ASSETS / "textures" / "item" / "boss" / f"{b.id}.png", A, A, px)
    for gname, m in models.items():
        out = ASSETS / "models" / "boss" / f"{b.id}_{gname.lower()}.json"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(m, separators=(",", ":")))
        item = ASSETS / "items" / "boss" / f"{b.id}_{gname.lower()}.json"
        item.parent.mkdir(parents=True, exist_ok=True)
        item.write_text(json.dumps({"model": {"type": "minecraft:model", "model": f"blackforge:boss/{b.id}_{gname.lower()}"}}))
    RIG.mkdir(parents=True, exist_ok=True)
    rig = {"scale": b.scale,
           "groups": [{"name": n, "parent": p, "pivot": list(pv)} for n, p, pv in b.groups],
           "models": sorted(models)}
    (RIG / f"{b.id}.json").write_text(json.dumps(rig, indent=1))
    print(f"{b.id}: {len(b.boxes)} boxes, {len(models)} groups")


# ---------------------------------------------------------------------------------------------- the bosses

def ravager():
    b = Boss("ravager")
    b.group("body", None, (0, 3, 0)); b.group("head", "body", (0, 3.5, 1.8)); b.group("jaw", "head", (0, 3.0, 2.4))
    b.group("tail", "body", (0, 3.2, -2.1))
    b.group("legFL", None, (1.25, 2, 1.4)); b.group("legBR", None, (-1.25, 2, -1.4)); b.group("legFR", None, (-1.25, 2, 1.4)); b.group("legBL", None, (1.25, 2, -1.4))
    b.group("head_glow", "head", (0, 3.5, 1.8)); b.group("tail_glow", "tail", (0, 3.2, -2.1)); b.group("body_glow", "body", (0, 3, 0))
    bx = b.box
    bx("body", "magma", (0, 2.9, 0), (3.2, 2.0, 4.2))
    for z in (-1.5, 0, 1.5):
        bx("body", "blackstone", (0, 4.0, z), (3.4, 0.45, 1.1))
    for sgn in (-1, 1):
        bx("body", "blackstone", (sgn * 1.75, 3.3, 0.5), (0.5, 1.5, 2.4))
        bx("body", "blackstone", (sgn * 1.75, 3.1, -1.5), (0.5, 1.2, 1.2))
    for i in range(5):
        bx("body", "obsidian", (0, 4.55, -1.8 + i * 0.9), (0.5, 0.95, 0.5), ("y", 45))
    bx("body_glow", "lavaglow", (0, 2.0, 0), (2.4, 0.25, 3.2))
    bx("head", "blackstone:ravhead", (0, 3.7, 2.6), (2.1, 1.7, 2.0))
    bx("head", "magma", (0, 3.3, 3.8), (1.5, 0.9, 1.3))
    for sgn in (-1, 1):
        bx("head", "obsidian", (sgn * 1.0, 4.75, 2.3), (0.4, 1.8, 0.4), ("z", -22.5 * sgn))
        bx("head_glow", "g_orange", (sgn * 1.55, 5.65, 2.3), (0.25, 0.4, 0.25), ("z", -22.5 * sgn))
        bx("head_glow", "g_orange", (sgn * 0.6, 4.0, 3.62), (0.45, 0.26, 0.12))
    bx("jaw", "blackstone", (0, 2.75, 3.6), (1.4, 0.35, 1.4))
    for sgn in (-1, 1):
        bx("jaw", "bone", (sgn * 0.55, 3.0, 4.15), (0.22, 0.55, 0.22))
    for lg, x, z in (("legFL", 1.25, 1.4), ("legFR", -1.25, 1.4), ("legBL", 1.25, -1.4), ("legBR", -1.25, -1.4)):
        bx(lg, "blackstone", (x, 1.25, z), (1.0, 1.7, 1.0))
        bx(lg, "magma", (x, 0.22, z + 0.1), (1.25, 0.45, 1.35))
    bx("tail", "magma", (0, 3.0, -2.9), (0.8, 0.8, 1.6))
    bx("tail", "blackstone", (0, 3.0, -4.1), (0.55, 0.55, 1.2))
    bx("tail_glow", "g_orange", (0, 3.0, -4.95), (0.7, 0.7, 0.7))
    return b


def colossus():
    b = Boss("colossus")
    b.group("body", None, (0, 2.8, 0)); b.group("head", "body", (0, 5.0, 0)); b.group("jaw", "head", (0, 5.0, 0.4))
    b.group("armL", "body", (1.55, 4.6, 0)); b.group("armR", "body", (-1.55, 4.6, 0))
    b.group("legL", None, (0.6, 2.6, 0)); b.group("legR", None, (-0.6, 2.6, 0))
    b.group("body_glow", "body", (0, 2.8, 0)); b.group("head_glow", "head", (0, 5.0, 0))
    bx = b.box
    bx("body", "bone", (0, 2.75, 0), (1.9, 0.5, 1.0))
    bx("body", "bone", (0, 3.75, -0.15), (0.3, 1.8, 0.3))
    for i in range(4):
        bx("body", "bone", (0, 3.1 + i * 0.4, 0.3), (2.3 - i * 0.2, 0.17, 1.0))
    bx("body", "hood", (0, 3.7, -0.05), (1.7, 1.4, 0.3))
    bx("body", "bone", (0, 4.75, 0), (3.3, 0.35, 0.55))
    for sgn in (-1, 1):
        bx("body", "bone", (sgn * 1.55, 4.65, 0), (0.85, 0.7, 0.85))
    bx("body", "cloak", (0, 3.4, -0.7), (2.6, 2.1, 0.12))
    bx("body", "cloak", (0, 2.2, -0.7), (1.7, 0.9, 0.12))
    bx("body_glow", "soul", (0, 3.4, 0.2), (1.0, 1.0, 0.5))
    bx("head", "bone:skull", (0, 5.65, 0.05), (1.5, 1.4, 1.5))
    for sgn in (-1, 1):
        bx("head", "bone", (sgn * 0.85, 6.5, 0.05), (0.3, 1.1, 0.3), ("z", -22.5 * sgn))
        bx("head_glow", "g_cyan", (sgn * 0.38, 5.75, 0.8), (0.2, 0.2, 0.08))
    bx("jaw", "bone:skull", (0, 4.95, 0.35), (1.1, 0.4, 1.0))
    for ag, x in (("armL", 1.55), ("armR", -1.55)):
        bx(ag, "bone", (x, 3.8, 0), (0.5, 1.6, 0.5))
        bx(ag, "bone", (x, 2.45, 0.1), (0.45, 1.3, 0.45))
        bx(ag, "bone", (x, 1.7, 0.2), (0.8, 0.55, 0.8))
    # a bow in the left hand
    bx("armL", "wood", (1.55, 2.2, 1.3), (0.18, 0.55, 0.18))
    bx("armL", "wood", (1.55, 2.95, 1.2), (0.14, 0.95, 0.14), ("x", 22.5))
    bx("armL", "wood", (1.55, 1.45, 1.2), (0.14, 0.95, 0.14), ("x", -22.5))
    bx("armL", "string", (1.55, 2.2, 1.0), (0.04, 1.95, 0.04))
    for lg, x in (("legL", 0.6), ("legR", -0.6)):
        bx(lg, "bone", (x, 1.95, 0), (0.55, 1.5, 0.55))
        bx(lg, "bone", (x, 0.75, 0.05), (0.5, 1.3, 0.5))
        bx(lg, "bone", (x, 0.1, 0.3), (0.8, 0.22, 1.1))
    return b


def revenant():
    b = Boss("revenant")
    b.group("body", None, (0, 3, 0)); b.group("head", "body", (0, 5, 0))
    b.group("armR", "body", (-1.0, 4.5, 0)); b.group("armL", "body", (1.0, 4.5, 0))
    b.group("hem", "body", (0, 1.4, 0)); b.group("orbs", "body", (0, 3.6, 0))
    b.group("body_glow", "body", (0, 3, 0)); b.group("head_glow", "head", (0, 5, 0)); b.group("orbs_glow", "orbs", (0, 3.6, 0))
    b.group("armR_glow", "armR", (-1.0, 4.5, 0)); b.group("armL_glow", "armL", (1.0, 4.5, 0))
    bx = b.box
    bx("body", "robe", (0, 1.9, 0), (2.5, 0.7, 2.5))
    bx("body", "robe", (0, 2.55, 0), (2.2, 0.8, 2.1))
    bx("body", "void", (0, 3.2, 0), (1.9, 0.7, 1.6))
    bx("body", "robe", (0, 3.85, 0), (1.9, 0.9, 1.3))
    bx("body", "obsidian", (0, 4.55, 0), (2.5, 0.35, 1.3))
    bx("body_glow", "g_purple", (0, 3.0, 0.82), (0.3, 2.3, 0.1))
    for i in range(8):
        a = i * math.pi / 4
        rot = ("z", 22.5 * (1 if math.cos(a) > 0 else -1)) if abs(math.cos(a)) > 0.5 else ("x", 22.5 * (1 if math.sin(a) < 0 else -1))
        bx("hem", "robe", (math.cos(a) * 1.25, 1.2, math.sin(a) * 1.25), (0.5, 1.1 + (i % 3) * 0.2, 0.5), rot)
    bx("head", "hood:hoodface", (0, 5.4, 0), (1.6, 1.5, 1.6))
    bx("head", "hood", (0, 6.35, -0.25), (0.9, 0.9, 0.9), ("x", 22.5))
    for sgn in (-1, 1):
        bx("head_glow", "g_purple", (sgn * 0.28, 5.45, 0.84), (0.24, 0.14, 0.08))
    bx("armR", "robe", (-1.35, 3.9, 0.55), (0.55, 1.5, 0.55), ("x", -45))
    bx("armR_glow", "crystal", (-1.35, 3.4, 1.35), (0.45, 0.45, 0.45))
    bx("armL", "robe", (1.35, 3.9, 0.5), (0.55, 1.5, 0.55), ("x", -22.5))
    bx("armL_glow", "crystal", (1.35, 3.5, 1.2), (0.45, 0.45, 0.45))
    # a dark blade held forward
    bx("armR", "wood", (-1.35, 3.4, 1.6), (0.16, 0.16, 0.8))
    bx("armR", "steel", (-1.35, 3.4, 1.95), (0.16, 0.55, 0.16))
    bx("armR", "steel", (-1.35, 3.4, 3.0), (0.14, 0.4, 2.0))
    for k, (x, y, z) in enumerate(((2.2, 4.6, 0), (-1.1, 3.6, 1.9), (-1.1, 5.4, -1.9))):
        bx("orbs_glow", "crystal", (x, y, z), (0.6, 0.6, 0.6), ("y", 45))
    return b


def titan():
    b = Boss("titan")
    b.group("body", None, (0, 3.5, 0)); b.group("head", "body", (0, 6.2, 0))
    b.group("armL", "body", (2.05, 5.7, 0)); b.group("armR", "body", (-2.05, 5.7, 0))
    b.group("legL", None, (0.9, 2.5, 0)); b.group("legR", None, (-0.9, 2.5, 0))
    b.group("body_glow", "body", (0, 3.5, 0)); b.group("head_glow", "head", (0, 6.2, 0))
    bx = b.box
    bx("body", "ice", (0, 4.6, 0), (3.1, 2.6, 1.9))
    bx("body", "icepacked", (0, 3.0, 0), (2.3, 1.2, 1.5))
    bx("body", "icepacked", (0, 4.7, 0.9), (1.6, 1.6, 0.12))
    for sgn in (-1, 1):
        bx("body", "ice", (sgn * 2.0, 5.8, 0), (1.4, 1.2, 1.5))
        bx("body", "iceclear", (sgn * 2.35, 6.7, 0), (0.45, 1.5, 0.45), ("z", -22.5 * sgn))
        bx("body", "iceclear", (sgn * 1.6, 6.5, -0.3), (0.35, 1.1, 0.35), ("z", -22.5 * sgn))
    for i in range(5):
        bx("body", "iceclear", (-1.0 + i * 0.5, 5.7 + (i % 2) * 0.5, -1.15), (0.35, 1.3, 0.35), ("x", -45))
    bx("body_glow", "core:core", (0, 4.7, 0.97), (1.0, 1.0, 0.1))
    bx("head", "icepacked:titanhead", (0, 6.6, 0.15), (1.4, 1.3, 1.4))
    bx("head", "ice", (0, 6.2, 0.9), (0.7, 0.4, 0.3))
    for i in range(5):
        a = i * math.pi * 2 / 5
        bx("head", "iceclear", (math.cos(a) * 0.65, 7.55, math.sin(a) * 0.65 + 0.15), (0.25, 1.0 + (i % 2) * 0.4, 0.25), ("z", 22.5))
    for sgn in (-1, 1):
        bx("head_glow", "g_cyan", (sgn * 0.38, 6.7, 0.88), (0.28, 0.2, 0.1))
    for ag, x in (("armL", 2.05), ("armR", -2.05)):
        bx(ag, "ice", (x, 4.5, 0), (1.15, 2.5, 1.15))
        bx(ag, "icepacked", (x, 2.85, 0.25), (1.6, 1.5, 1.6))
        for k in (-1, 0, 1):
            bx(ag, "iceclear", (x + k * 0.45, 2.3, 1.0), (0.3, 0.6, 0.3), ("x", -22.5))
    for lg, x in (("legL", 0.9), ("legR", -0.9)):
        bx(lg, "ice", (x, 1.35, 0), (1.25, 2.3, 1.25))
        bx(lg, "icepacked", (x, 0.22, 0.2), (1.7, 0.45, 1.8))
    return b


def brute():
    b = Boss("brute")
    b.group("body", None, (0, 3, 0)); b.group("head", "body", (0, 4.4, 0.2))
    b.group("armL", "body", (1.95, 4.1, 0)); b.group("armR", "body", (-1.95, 4.1, 0))
    b.group("legL", None, (0.7, 1.9, 0)); b.group("legR", None, (-0.7, 1.9, 0))
    b.group("body_glow", "body", (0, 3, 0)); b.group("head_glow", "head", (0, 4.4, 0.2))
    bx = b.box
    bx("body", "copper", (0, 3.2, 0), (2.8, 2.0, 1.7))
    bx("body", "gold", (0, 3.45, 0.9), (2.1, 1.2, 0.3))
    bx("body", "blackstone", (0, 2.2, 0), (2.5, 0.5, 1.5))
    bx("body", "gold", (0, 2.2, 0.8), (0.7, 0.55, 0.15))
    for sgn in (-1, 1):
        bx("body", "gold", (sgn * 1.95, 4.15, 0), (1.2, 0.9, 1.4))
        bx("body", "rod", (sgn * 1.95, 5.2, 0), (0.18, 1.9, 0.18))
        bx("body", "copper", (sgn * 1.95, 6.2, 0), (0.3, 0.3, 0.3))
    for i in range(4):
        bx("body", "rod", (-1.0 + i * 0.67, 4.9, -1.05), (0.16, 1.8, 0.16), ("x", -22.5))
    bx("body_glow", "g_yellow", (0, 3.45, 1.07), (0.7, 0.7, 0.1))
    bx("head", "blackstone:brutehead", (0, 4.8, 0.2), (1.5, 1.2, 1.4))
    bx("head", "gold", (0, 4.55, 1.0), (1.0, 0.5, 0.4))
    for sgn in (-1, 1):
        bx("head", "bone", (sgn * 0.4, 4.3, 1.1), (0.2, 0.6, 0.2), ("x", 22.5))
        bx("head", "gold", (sgn * 0.85, 5.7, 0.2), (0.35, 1.1, 0.35), ("z", -45 * sgn))
        bx("head_glow", "g_yellow", (sgn * 0.38, 5.0, 0.93), (0.3, 0.22, 0.1))
    for ag, x in (("armL", 1.95), ("armR", -1.95)):
        bx(ag, "copper", (x, 3.1, 0), (0.95, 1.7, 0.95))
        bx(ag, "gold", (x, 1.9, 0.35), (1.1, 1.0, 1.1))
    # a double-bitted axe in the right hand
    bx("armR", "wood", (-1.95, 2.7, 1.3), (0.2, 3.0, 0.2))
    bx("armR", "gold", (-1.95, 4.0, 1.3), (0.3, 1.0, 1.7))
    bx("armR", "gold", (-1.95, 4.0, 1.3), (0.5, 0.35, 2.3))
    for lg, x in (("legL", 0.7), ("legR", -0.7)):
        bx(lg, "blackstone", (x, 1.0, 0), (1.0, 1.8, 1.0))
        bx(lg, "gold", (x, 0.2, 0.15), (1.2, 0.4, 1.4))
    return b


def serpent():
    """Jormungandr: a head with a hinged jaw, two body segments to alternate, and a tail tip. Models are in blocks at scale 8
    (one display = 16 model units = 8 blocks); the plugin draws and bends them along the serpent's trail."""
    b = Boss("serpent")
    b.scale = 8.0
    b.atlas = 256
    b.group("head", None, (0, 0, 0)); b.group("jaw", "head", (0, -3, -1)); b.group("head_glow", "head", (0, 0, 0))
    b.group("seg_a", None, (0, 0, 0)); b.group("seg_b", None, (0, 0, 0)); b.group("tail", None, (0, 0, 0))
    bx = b.box
    # skull, snout, brow and crest
    bx("head", "scalesdark:serpenthead", (0, 0, 0.5), (11, 7.5, 13))
    bx("head", "scales:serpenthead", (0, -0.8, 9), (8, 4.6, 6))
    bx("head", "scaleslight", (0, 2.6, 8.5), (6, 1.2, 7))
    for sgn in (-1, 1):
        bx("head", "scalesdark", (sgn * 3.6, 4.1, 4.5), (3.4, 1.6, 7), ("z", -sgn * 12))
        bx("head", "scaleslight", (sgn * 6.0, -0.5, -1), (1.4, 2.2, 5))
        bx("head", "scales", (sgn * 6.3, 1.2, -3.2), (0.8, 5, 5))
        bx("head", "bone", (sgn * 3.6, 4.8, -4.2), (1.8, 1.8, 6), ("x", -25))
        bx("head", "bone", (sgn * 3.9, 7.4, -8.4), (1.2, 1.2, 5), ("x", -45))
    for i in range(4):
        bx("head", "scaleslight", (0, 4.4 + 0.2 * i, -1.5 - 2.0 * i), (1.0, 2.8, 1.2), ("x", -20))
    for i in range(5):                                  # upper fangs and teeth
        for sgn in (-1, 1):
            bx("head", "bone", (sgn * 3.0, -4.2, 7.0 + i * 1.0), (0.7, 2.2 if i in (0, 4) else 1.6, 0.7))
    # lower jaw, hinged near the back of the skull
    bx("jaw", "belly", (0, -3.3, 5.5), (7.4, 1.8, 11))
    bx("jaw", "scales", (0, -3.0, 5.5), (7.8, 1.0, 10))
    bx("jaw", "bone", (0, -4.9, 9.8), (1.0, 1.4, 2.0))
    for i in range(5):
        for sgn in (-1, 1):
            bx("jaw", "bone", (sgn * 2.9, -1.9, 4.6 + i * 1.4), (0.6, 2.0, 0.6))
    # eyes and nostrils burn green
    for sgn in (-1, 1):
        bx("head_glow", "g_green", (sgn * 5.55, 1.4, 3.2), (0.5, 2.2, 3.0))
        bx("head_glow", "g_green", (sgn * 1.2, -0.2, 11.8), (0.8, 0.8, 0.4))
    # two body segments to alternate: an octagonal coil of scales, a pale belly, flank plates, a dorsal fin
    for name, fin in (("seg_a", 1), ("seg_b", 2)):
        bx(name, "scales", (0, 0, 0), (9, 7.2, 5.4))
        bx(name, "scales", (0, 0, 0), (7.2, 9, 5.4))
        bx(name, "scales", (0, 0, 0), (6.6, 6.6, 5.4), ("z", 45))
        bx(name, "belly", (0, -4.6, 0), (5.4, 0.4, 5.4))
        for sgn in (-1, 1):
            bx(name, "scaleslight", (sgn * 4.7, 0.6, 0), (0.5, 3.8, 4.6))
            bx(name, "g_green", (sgn * 4.98, 1.4, 0), (0.12, 0.5, 2.2))
        if fin == 1:
            bx(name, "scalesdark", (0, 5.3, 0), (1.0, 3.6, 3.8))
        else:
            bx(name, "scalesdark", (0, 5.0, 1.2), (1.0, 3.0, 2.0))
            bx(name, "scalesdark", (0, 5.6, -1.4), (1.0, 4.2, 2.0))
            for sgn in (-1, 1):
                bx(name, "scaleslight", (sgn * 3.6, 3.9, 0), (0.7, 2.6, 0.7), ("z", sgn * 32))
    # the tail's tip
    bx("tail", "scales", (0, 0, 0), (5.5, 5.0, 5.4))
    bx("tail", "scalesdark", (0, 0, -4.0), (0.8, 7.0, 6.0))
    bx("tail", "scaleslight", (0, 0, -3.0), (0.6, 3.0, 3.0))
    return b


def main():
    for make in (ravager, colossus, revenant, titan, brute, serpent):
        build(make())


if __name__ == "__main__":
    main()
