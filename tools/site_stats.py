"""Build website/data/stats.json for the website's Stats page from the servers' saved stats.

Usage:  python tools/site_stats.py

Reads shared/stats (kills, arcade wins/games/kills), hub parkour times, survival bounties and arcade ranked ratings,
and writes the leaderboards plus a per-player summary. The site reads the file; redeploy the website afterwards
(npx wrangler pages deploy website --project-name blackforgemc --branch main).
"""
import json
import time
from pathlib import Path

try:
    import yaml  # PyYAML, if installed
except ImportError:
    yaml = None

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "website" / "data" / "stats.json"


def load(p: Path) -> dict:
    """A tiny YAML reader for the flat/nested key: value files the plugin writes (PyYAML when available)."""
    text = p.read_text(encoding="utf-8", errors="replace")
    if yaml:
        return yaml.safe_load(text) or {}
    root, stack = {}, [(-1, {})]
    stack[0] = (-1, root)
    for raw in text.splitlines():
        if not raw.strip() or raw.lstrip().startswith("#") or ":" not in raw:
            continue
        indent = len(raw) - len(raw.lstrip())
        key, _, val = raw.strip().partition(":")
        key, val = key.strip().strip("'\""), val.strip().strip("'\"")
        while stack and stack[-1][0] >= indent:
            stack.pop()
        parent = stack[-1][1]
        if val == "":
            parent[key] = {}
            stack.append((indent, parent[key]))
        else:
            try:
                parent[key] = int(val)
            except ValueError:
                try:
                    parent[key] = float(val)
                except ValueError:
                    parent[key] = val
    return root


def top(rows, n=10, reverse=True):
    rows = [r for r in rows if r[1]]
    rows.sort(key=lambda r: r[1], reverse=reverse)
    return [{"name": a, "value": b} for a, b in rows[:n]]


def main():
    players = {}

    def player(name):
        return players.setdefault(name, {"name": name})

    shared = [load(f) for f in (ROOT / "shared" / "stats").glob("*.yml")]
    for s in shared:
        if str(s.get("name", "")).startswith("AlloyTester"):
            continue
        p = player(s.get("name", "?"))
        for k in ("kills", "arcade_wins", "arcade_games", "arcade_kills"):
            p[k] = s.get(k, 0)

    parkour = []
    pf = ROOT / "hub" / "plugins" / "AlloyCore" / "hub-parkour.yml"
    if pf.exists():
        for uid, v in load(pf).items():
            if isinstance(v, dict) and v.get("best"):
                parkour.append((v.get("name", "?"), v["best"]))
                player(v.get("name", "?"))["parkour_ms"] = v["best"]

    bounties = []
    bf = ROOT / "survival" / "plugins" / "AlloyCore" / "bounties.yml"
    if bf.exists():
        for uid, v in load(bf).items():
            if isinstance(v, dict) and v.get("amount"):
                bounties.append((v.get("name", "?"), v["amount"]))

    ranked = {}
    for f in (ROOT / "arcade" / "plugins" / "AlloyCore" / "arcade" / "stats").glob("*.yml"):
        s = load(f)
        name = s.get("name")
        duels = s.get("duels", {}) if isinstance(s.get("duels"), dict) else {}
        if not name or str(name).startswith("AlloyTester"):
            continue
        # the Arcade's own per-game stats go back further than the network-wide totals: use whichever is bigger
        games = [v for k, v in s.items() if isinstance(v, dict) and not k.startswith("_")]
        p = player(name)
        for key, stat in (("arcade_wins", "wins"), ("arcade_games", "played"), ("arcade_kills", "kills")):
            p[key] = max(p.get(key, 0), sum(int(g.get(stat, 0) or 0) for g in games))
        for k, v in duels.items():
            if k.startswith("rating_"):
                ranked.setdefault(k[len("rating_"):], []).append((name, v))
                player(name).setdefault("ratings", {})[k[len("rating_"):]] = v

    data = {
        "updated": int(time.time()),
        "boards": {
            "kills": top([(p["name"], p.get("kills", 0)) for p in players.values()]),
            "arcade_wins": top([(p["name"], p.get("arcade_wins", 0)) for p in players.values()]),
            "arcade_games": top([(p["name"], p.get("arcade_games", 0)) for p in players.values()]),
            "parkour": top(parkour, reverse=False),
            "bounties": top(bounties),
        },
        "ranked": {k: top(v) for k, v in ranked.items()},
        "players": sorted(players.values(), key=lambda p: p["name"].lower()),
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(data, indent=1), encoding="utf-8")
    print(f"wrote {OUT} - {len(players)} players")


if __name__ == "__main__":
    main()
