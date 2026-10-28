"""Build the BlackForgeMC resource pack zip from resourcepack/ and print its SHA-1.

Usage:  python tools/build_pack.py --format <pack format>

The pack format must match the Minecraft version the servers run (check the Minecraft wiki's
"Pack format" page for 26.3). It's passed in rather than stored so a wrong guess never ships.
This script only builds the zip; it does not upload it or touch any server.
"""
import argparse
import hashlib
import json
import pathlib
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "resourcepack"
OUT = ROOT / "build" / "blackforge-pack.zip"


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--format", type=int, required=True, help="resource pack format for the server's Minecraft version")
    args = ap.parse_args()

    # 1.21.9+ packs declare the formats they support; the old single pack_format is only read by older clients
    meta = {"pack": {"description": "BlackForgeMC", "min_format": args.format, "max_format": args.format}}
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("pack.mcmeta", json.dumps(meta, indent=2))
        for f in sorted(SRC.rglob("*")):
            if f.is_file() and f.name != "README.md":
                z.write(f, f.relative_to(SRC).as_posix())

    sha1 = hashlib.sha1(OUT.read_bytes()).hexdigest()
    print(f"Built {OUT} ({OUT.stat().st_size} bytes)")
    print(f"resource-pack-sha1={sha1}")


if __name__ == "__main__":
    main()
