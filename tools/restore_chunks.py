"""Put the chunks of a box of the world back to how a backup zip had them, leaving every other chunk as it is.

Used to take the Survival spawn back to an earlier build without rolling the rest of the world back.
Run it ONLY while the server is stopped (it rewrites region files).

  python tools/restore_chunks.py <backup.zip> <server dir> <min chunk x> <min chunk z> <max chunk x> <max chunk z> [--dry]

Handles the overworld's region, entities and poi folders. Chunks inside the box that the backup did not have are
removed (the world generates them again). Chunk data stays in its stored (compressed) form, so nothing is re-encoded.
"""
import io, struct, sys, zipfile
from pathlib import Path

SECTOR = 4096
DIMS = "world/dimensions/minecraft/overworld"
FOLDERS = ("region", "entities", "poi")


def parse(buf):
    """-> {index: (record bytes, timestamp)} for a region file's bytes (empty file -> {})."""
    out = {}
    if len(buf) < 2 * SECTOR:
        return out
    for i in range(1024):
        loc = struct.unpack(">I", buf[i * 4:i * 4 + 4])[0]
        off, cnt = loc >> 8, loc & 0xFF
        if off == 0 or cnt == 0:
            continue
        ts = struct.unpack(">I", buf[SECTOR + i * 4:SECTOR + i * 4 + 4])[0]
        length = struct.unpack(">I", buf[off * SECTOR:off * SECTOR + 4])[0]
        rec = buf[off * SECTOR:off * SECTOR + 4 + length]
        if rec[4] & 0x80:
            raise SystemExit("a chunk uses an external .mcc file; not handled")
        out[i] = (rec, ts)
    return out


def build(chunks):
    """{index: (record, ts)} -> region file bytes."""
    locs, tss, body = [0] * 1024, [0] * 1024, bytearray()
    sector = 2
    for i in sorted(chunks):
        rec, ts = chunks[i]
        n = -(-len(rec) // SECTOR)
        locs[i] = (sector << 8) | n
        tss[i] = ts
        body += rec + b"\0" * (n * SECTOR - len(rec))
        sector += n
    head = b"".join(struct.pack(">I", v) for v in locs) + b"".join(struct.pack(">I", v) for v in tss)
    return head + bytes(body)


def main():
    a = [x for x in sys.argv[1:] if not x.startswith("--")]
    dry = "--dry" in sys.argv
    zpath, server = Path(a[0]), Path(a[1])
    x0, z0, x1, z1 = map(int, a[2:6])
    zf = zipfile.ZipFile(zpath)
    names = set(zf.namelist())
    tot_restored = tot_removed = 0
    for folder in FOLDERS:
        regions = {(cx >> 5, cz >> 5) for cx in range(x0, x1 + 1) for cz in range(z0, z1 + 1)}
        for rx, rz in sorted(regions):
            rel = f"{DIMS}/{folder}/r.{rx}.{rz}.mca"
            live_path = server / rel
            live = parse(live_path.read_bytes()) if live_path.exists() else {}
            old = parse(zf.read(rel)) if rel in names else {}
            new = dict(live)
            restored = removed = 0
            for cz in range(z0, z1 + 1):
                for cx in range(x0, x1 + 1):
                    if cx >> 5 != rx or cz >> 5 != rz:
                        continue
                    i = (cx & 31) + (cz & 31) * 32
                    if i in old:
                        new[i] = old[i]; restored += 1
                    elif i in new:
                        del new[i]; removed += 1
            tot_restored += restored; tot_removed += removed
            print(f"{folder}/r.{rx}.{rz}: {restored} chunks restored, {removed} removed")
            if not dry and (restored or removed):
                tmp = live_path.with_suffix(".mca.new")
                tmp.write_bytes(build(new))
                if live_path.exists():
                    live_path.replace(live_path.with_suffix(".mca.pre-restore"))
                tmp.replace(live_path)
    print(f"done{' (dry run)' if dry else ''}: {tot_restored} restored, {tot_removed} removed")


if __name__ == "__main__":
    main()
