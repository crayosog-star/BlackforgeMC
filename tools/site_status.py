"""Build website/data/status.json for the website's live Status page.

Usage:  python tools/site_status.py        (run it every minute or so, e.g. from Task Scheduler)

Asks each server how many players are online (through netctl), reads the Colosseum's Gladiator rankings from the
Survival server's saved data, and writes the result for status.html. Redeploy the website afterwards if the site is
hosted separately (npx wrangler pages deploy website --project-name blackforgemc --branch main).
"""
import json
import re
import subprocess
import sys
import time
from pathlib import Path

try:
    import yaml  # PyYAML, if installed
except ImportError:
    yaml = None

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "website" / "data" / "status.json"
SERVERS = [("hub", "Hub"), ("skyblock", "Skyblock"), ("survival", "Survival"), ("factions", "Factions"), ("arcade", "Arcade")]
NO_WINDOW = 0x08000000 if sys.platform == "win32" else 0


def online(server):
    """Players online on a server, or None when it doesn't answer (down or restarting)."""
    try:
        r = subprocess.run([sys.executable, str(ROOT / "tools" / "netctl.py"), "cmd", server, "minecraft:list"],
                           capture_output=True, text=True, timeout=20, creationflags=NO_WINDOW)
    except Exception:
        return None
    out = re.sub("§.", "", (r.stdout or "") + (r.stderr or ""))
    m = re.search(r"There are (\d+)", out)
    return int(m.group(1)) if m else None


def gladiators():
    f = ROOT / "survival" / "plugins" / "AlloyCore" / "gladiator" / "gladiators.yml"
    if not f.exists() or yaml is None:
        return []
    data = (yaml.safe_load(f.read_text(encoding="utf-8")) or {}).get("players", {}) or {}
    rows = []
    for u, p in data.items():
        wins, losses = int(p.get("wins", 0)), int(p.get("losses", 0))
        if wins + losses < 3:
            continue
        rows.append({"name": p.get("name", "?"), "rating": round(float(p.get("rating", 1000))), "wins": wins, "losses": losses,
                     "winrate": round(100 * wins / (wins + losses)) if wins + losses else 0})
    rows.sort(key=lambda r: -r["rating"])
    for i, r in enumerate(rows[:10]):
        r["rank"] = i + 1
    return rows[:10]


def main():
    servers, total = [], 0
    for sid, label in SERVERS:
        n = online(sid)
        servers.append({"id": sid, "name": label, "online": n, "up": n is not None})
        total += n or 0
    status = {"updated": int(time.time()), "total": total, "servers": servers, "gladiators": gladiators()}
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(status, indent=2), encoding="utf-8")
    print(f"status.json: {total} online, {len(status['gladiators'])} ranked gladiators")


if __name__ == "__main__":
    main()
