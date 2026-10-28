"""Nightly backup of every BlackForgeMC server: worlds, plugin data and the shared folder.

Usage:  python tools/backup.py            back everything up now
        python tools/backup.py --list     show the backups that exist

Each running server is told to stop autosaving and flush everything to disk (save-off, save-all flush) while its files
are zipped, then autosave goes back on - players notice nothing. Servers that are down are zipped as they are.
Backups go to F:\\Backups\\BlackForgeMC\\<date>\\<server>.zip and the newest KEEP days are kept.
Scheduled every night at 04:30 by tools/install_backup.ps1. Log: tools/backup.log
"""
import datetime
import os
import shutil
import sys
import time
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import netctl  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
DEST = Path(os.environ.get("BF_BACKUP_DIR", r"F:\Backups\BlackForgeMC"))
KEEP = 7
LOG = ROOT / "tools" / "backup.log"
SKIP_SUFFIX = (".jar", ".lock", ".log.gz")
SKIP_DIRS = {"logs", "cache", "libraries", "versions", ".paper-remapped", "crash-reports", "debug"}


def log(msg):
    line = f"[{datetime.datetime.now():%Y-%m-%d %H:%M:%S}] {msg}"
    print(line)
    with open(LOG, "a", encoding="utf-8") as f:
        f.write(line + "\n")


def wanted(server_dir: Path):
    """Worlds (any folder holding a level.dat), the plugins' data and the server's config files."""
    for p in server_dir.iterdir():
        if p.is_dir() and (p / "level.dat").exists():
            yield p
    if (server_dir / "plugins").is_dir():
        yield server_dir / "plugins"
    for name in ("server.properties", "ops.json", "whitelist.json", "banned-players.json", "banned-ips.json",
                 "bukkit.yml", "spigot.yml", "velocity.toml", "forwarding.secret"):
        if (server_dir / name).exists():
            yield server_dir / name


def zip_tree(zf: zipfile.ZipFile, path: Path, base: Path):
    if path.is_file():
        zf.write(path, path.relative_to(base).as_posix())
        return
    for root, dirs, files in os.walk(path):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for f in files:
            if f.endswith(SKIP_SUFFIX) or f == "session.lock":
                continue
            full = Path(root) / f
            try:
                zf.write(full, full.relative_to(base).as_posix())
            except (PermissionError, FileNotFoundError, OSError) as e:
                log(f"  skipped {full.relative_to(base)}: {e.__class__.__name__}")


def backup_server(name, out_dir):
    port = netctl.SERVERS[name][0] if name in netctl.SERVERS else None
    live = port is not None and netctl.up(port)
    if live:
        netctl.rcon(port, "save-off")
        netctl.rcon(port, "save-all flush", timeout=60)
        time.sleep(2)
    t = time.time()
    target = out_dir / f"{name}.zip"
    try:
        with zipfile.ZipFile(target, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
            base = ROOT / name
            for p in wanted(base):
                zip_tree(zf, p, base)
    finally:
        if live:
            netctl.rcon(port, "save-on")
    log(f"  {name}: {target.stat().st_size / 1e6:.0f} MB in {time.time() - t:.0f}s{'' if live or port is None else ' (server was down)'}")


def main():
    if "--list" in sys.argv:
        for d in sorted(DEST.glob("*")):
            size = sum(f.stat().st_size for f in d.glob("*.zip"))
            print(f"{d.name}  {size / 1e6:.0f} MB")
        return
    stamp = datetime.datetime.now().strftime("%Y-%m-%d_%H%M")
    out = DEST / stamp
    out.mkdir(parents=True, exist_ok=True)
    log(f"backup -> {out}")
    for name in ["hub", "survival", "skyblock", "factions", "arcade", "proxy"]:
        try:
            backup_server(name, out)
        except Exception as e:  # one bad server must not stop the others
            log(f"  {name}: FAILED {e!r}")
    with zipfile.ZipFile(out / "shared.zip", "w", zipfile.ZIP_DEFLATED) as zf:
        zip_tree(zf, ROOT / "shared", ROOT)
    old = sorted(d for d in DEST.iterdir() if d.is_dir())[:-KEEP]
    for d in old:
        shutil.rmtree(d, ignore_errors=True)
        log(f"  removed old backup {d.name}")
    log("backup done")


if __name__ == "__main__":
    main()
