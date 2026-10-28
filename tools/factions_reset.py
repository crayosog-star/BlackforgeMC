"""Factions season reset - started by AlloyCore when a season ends (or by hand).

  python tools/factions_reset.py <ending season number> [months]

1. waits for the factions server to finish shutting down
2. archives the old season into factions/seasons/season-<n>/  (worlds, factions plugin data, Essentials
   homes) - nothing is deleted
3. picks a new random world seed
4. starts season n+1 (season.yml) lasting `months` (default 3) and starts the server again; AlloyCore
   rebuilds the spawn citadel on the new map automatically

Network-wide things are untouched: money, Scrap and ranks are shared and carry over.
"""
import calendar, datetime, os, random, re, shutil, socket, subprocess, sys, time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
# overridable for a dry run against a copy: BF_FACTIONS_DIR, BF_FACTIONS_PORT, BF_NO_START=1
FAC = Path(os.environ.get("BF_FACTIONS_DIR", ROOT / "factions"))
PORT = int(os.environ.get("BF_FACTIONS_PORT", 30004))


def up():
    try:
        socket.create_connection(("127.0.0.1", PORT), timeout=1).close()
        return True
    except OSError:
        return False


def add_months(d, months):
    m = d.month - 1 + months
    y, m = d.year + m // 12, m % 12 + 1
    return d.replace(year=y, month=m, day=min(d.day, calendar.monthrange(y, m)[1]))


def main():
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 0
    months = int(sys.argv[2]) if len(sys.argv) > 2 else 3
    print(f"[{datetime.datetime.now():%Y-%m-%d %H:%M}] season {n} reset starting", flush=True)
    for _ in range(180):
        if not up():
            break
        time.sleep(1)
    else:
        print("factions is still running after 3 minutes - aborting, nothing changed", flush=True)
        return 1
    time.sleep(0 if os.environ.get("BF_NO_START") == "1" else 8)   # let java release the world files

    archive = FAC / "seasons" / f"season-{n}"
    archive.mkdir(parents=True, exist_ok=True)
    for name in ("world", "world_nether", "world_the_end"):
        src = FAC / name
        if src.exists():
            shutil.move(str(src), str(archive / name))
    data = FAC / "plugins" / "PvPIndexFactions" / "data"
    if data.exists():
        shutil.move(str(data), str(archive / "factions-data"))
    homes = FAC / "plugins" / "Essentials" / "userdata"
    if homes.exists():
        shutil.move(str(homes), str(archive / "essentials-userdata"))
    print(f"archived season {n} to {archive}", flush=True)
    # the fresh world needs the blackforge datapack for the spawn's golden Erdtree biome
    pack = ROOT / "datapacks" / "blackforge"
    if pack.exists():
        shutil.copytree(pack, FAC / "world" / "datapacks" / "blackforge", dirs_exist_ok=True)

    props = FAC / "server.properties"
    text = props.read_text(encoding="utf-8")
    seed = random.randint(-2**62, 2**62)
    text = re.sub(r"(?m)^level-seed=.*$", f"level-seed={seed}", text) if re.search(r"(?m)^level-seed=", text) else text + f"\nlevel-seed={seed}\n"
    props.write_text(text, encoding="utf-8")

    now = datetime.datetime.now()
    end = add_months(now, months)
    season = FAC / "plugins" / "AlloyCore" / "season.yml"
    season.write_text(f"number: {n + 1}\nstarted: {int(now.timestamp() * 1000)}\nends: {int(end.timestamp() * 1000)}\n", encoding="utf-8")
    print(f"season {n + 1}: {now:%Y-%m-%d} -> {end:%Y-%m-%d}, seed {seed}", flush=True)

    if os.environ.get("BF_NO_START") != "1":
        subprocess.run([sys.executable, str(ROOT / "tools" / "netctl.py"), "start"], check=False)
        print("factions restarted", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
