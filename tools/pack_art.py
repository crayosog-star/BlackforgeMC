"""Draw BlackForgeMC's custom item textures (16x16 pixel art) into the resource pack, with their item and model files.

Usage:  python tools/pack_art.py      then  python tools/build_pack.py --format <pack format>

Each sprite is 16 rows of 16 characters; every character is a palette colour ('.' is transparent). Edit the art here
and re-run. Items whose textures someone drew by hand (gale_bellows, surge, gaia_potion) aren't touched.
"""
import json
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent / "resourcepack" / "assets" / "blackforge"

BOTTLE = [
    "................",
    "......oooo......",
    "......occo......",
    "......occo......",
    ".......oo.......",
    "......ogGo......",
    ".....ogGGGo.....",
    "....oLLLLLLo....",
    "...oLhLLLLLLo...",
    "...oLhLLLLLLo...",
    "...oLLLLLLLlo...",
    "...oLLLLLLLlo...",
    "...olLLLLLllo...",
    "....ollllllo....",
    ".....oooooo.....",
    "................",
]

SPRITES = {
    "darkness_potion": (BOTTLE, {"o": "1a1424", "c": "8a5a2b", "g": "cfd8e6", "G": "9aa6b8",
                                 "L": "3b1d5c", "l": "1e0b33", "h": "a77fe0"}),
    "gods_eye": ([
        "................",
        ".......Y........",
        "..Y....Y....Y...",
        "...Y.......Y....",
        ".....oooooo.....",
        "...oowwwwwwoo...",
        "..owwwcccccwwo..",
        ".owwwcCkkCcwwwo.",
        ".owwwcCkkCcwwwo.",
        "..owwwcccccwwo..",
        "...oowwwwwwoo...",
        ".....oooooo.....",
        "...Y.......Y....",
        "..Y....Y....Y...",
        ".......Y........",
        "................",
    ], {"o": "8a6514", "w": "f5f0e0", "c": "3fd6e0", "C": "1a9aa6", "k": "0a0a12", "Y": "ffd23f"}),
    "ashen_chain": ([
        "..............EE",
        ".............EeE",
        "............oEE.",
        "...........o.o..",
        "..........ooo...",
        ".........o.o....",
        "........ooo.....",
        ".......o.o......",
        "......ooo.......",
        ".....o.o........",
        "....ooo.........",
        "...o.o..........",
        "..ooo...........",
        ".o.o............",
        ".oo.............",
        "................",
    ], {"o": "7a7a88", "E": "ff7a1a", "e": "ffd27a"}),
    "ashen_bonfire": ([
        "................",
        ".......a........",
        "......aA........",
        "......aAa.a.....",
        ".....aAAa.Aa....",
        "....aAAAAaAa....",
        "....aAWWAAAa....",
        "...aAAWWWAAAa...",
        "...aAWWWWWAAa...",
        "....aAWWWAAa....",
        ".bb..eEEEe..bb..",
        ".bBbbbeeebbbBb..",
        "..bBBbbbbbBBb...",
        "...bbBBBBBbb....",
        "....bbbbbbb.....",
        "................",
    ], {"a": "5a5a64", "A": "9a9aa6", "W": "eeeef4", "e": "ff7a1a", "E": "ffd27a", "b": "3d2a17", "B": "6b4a2a"}),
    "last_rite": ([
        "................",
        "..pppppppppppp..",
        ".pPPPPPPPPPPPPp.",
        "..pqqqqqqqqqqp..",
        "..pqkkqkkkqqqp..",
        "..pqqqqqqqqqqp..",
        "..pqkkkqkkqkqp..",
        "..pqqqqqqqqqqp..",
        "..pqkqkkkkqqqp..",
        "..pqqqqqqRRqqp..",
        "..pqqqqqRrrRqp..",
        "..pqqqqqRrrRqp..",
        "..pqqqqqqRRqqp..",
        ".pPPPPPPPPPPPPp.",
        "..pppppppppppp..",
        "................",
    ], {"p": "7a5a34", "P": "c9a46a", "q": "efe0bf", "k": "5a4632", "R": "a3121e", "r": "e03a3a"}),
    "rimelock": ([
        "................",
        "......oooo......",
        ".....oiiiio.....",
        "....oio..oio....",
        "....oio..oio....",
        "....oio..oio....",
        "..oooooooooooo..",
        "..oIIIIIIIIIIo..",
        "..oIwIIIIIIIIo..",
        "..oIwIIkkIIIIo..",
        "..oIIIIkkIIIIo..",
        "..oIIIIIkIIIIo..",
        "..oIIIIIIIIIBo..",
        "..oBBIIIIIIBBo..",
        "..oooooooooooo..",
        "................",
    ], {"o": "1d4f73", "i": "9fe6ff", "I": "6cc8ef", "w": "ffffff", "k": "0d2a40", "B": "3a95c8"}),
    "frost_shard": ([
        "................",
        "..........w.....",
        ".........wIw....",
        "........wIIo....",
        ".......wIiIo....",
        "......wIiIIo....",
        ".....wIiIIBo....",
        "....wIiIIBBo....",
        "...wIiIIBBo.....",
        "...oIIIBBo......",
        "....oIBBo..w....",
        ".....oBo..wIw...",
        "......o....o....",
        "..w.............",
        ".wIw............",
        "..o.............",
    ], {"w": "ffffff", "I": "9fe6ff", "i": "e8fbff", "B": "4fa3ff", "o": "1d4f73"}),
    "soul_candy": ([
        "................",
        "................",
        "................",
        "................",
        ".PP..........QQ.",
        ".PpP..oooo..QqQ.",
        "..PpPoOOOOoQqQ..",
        "...PPoOyOOOoQQ..",
        "...PPoOOOyOoQQ..",
        "..PpPoOOOOoQqQ..",
        ".PpP..oooo..QqQ.",
        ".PP..........QQ.",
        "................",
        "................",
        "................",
        "................",
    ], {"P": "9b4dff", "p": "c9a0ff", "Q": "9b4dff", "q": "c9a0ff", "o": "a8460a", "O": "ff9a1f", "y": "ffe08a"}),
}


def png(rows, palette):
    raw = b""
    for r in rows:
        r = (r + "." * 16)[:16]
        raw += b"\x00"
        for ch in r:
            raw += b"\x00\x00\x00\x00" if ch == "." else bytes.fromhex(palette[ch]) + b"\xff"

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xFFFFFFFF)

    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 16, 16, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")


def main():
    for name, (rows, palette) in SPRITES.items():
        assert len(rows) == 16, name
        (ROOT / "textures" / "item").mkdir(parents=True, exist_ok=True)
        (ROOT / "models" / "item").mkdir(parents=True, exist_ok=True)
        (ROOT / "items").mkdir(parents=True, exist_ok=True)
        (ROOT / "textures" / "item" / f"{name}.png").write_bytes(png(rows, palette))
        (ROOT / "models" / "item" / f"{name}.json").write_text(json.dumps(
            {"parent": "minecraft:item/generated", "textures": {"layer0": f"blackforge:item/{name}"}}, indent=2) + "\n")
        (ROOT / "items" / f"{name}.json").write_text(json.dumps(
            {"model": {"type": "minecraft:model", "model": f"blackforge:item/{name}"}}, indent=2) + "\n")
        print("drew", name)


if __name__ == "__main__":
    main()
