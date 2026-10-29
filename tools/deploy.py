"""Deploy AlloyCore + per-server settings to BlackForgeMC backends. Re-runnable.

Only deploy to STOPPED servers (replacing a jar under a running server breaks it).
Usage: deploy.py [server ...]   (default: all)  - or use `netctl.py restart <server>`.
"""
import os, json, secrets, shutil, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAR = ROOT / "plugins-src" / "AlloyCore" / "build" / "libs" / "AlloyCore.jar"
OWNER = {"uuid": "f2aef66f-fb87-4635-8c61-4b116baaa5b5", "name": "GavVFX", "level": 4, "bypassesPlayerLimit": True}
# permanent ops: re-added on every deploy, so a rebuilt or wiped server never loses them
PERMANENT_OPS = [OWNER, {"uuid": "8c67e820-3611-42b8-9f5f-d355d31f9ca9", "name": "Causee", "level": 4, "bypassesPlayerLimit": True}]
ADDRESS = "blackforgemc.com"
# served straight from GitHub (public repo, main branch): a merged pack is downloadable at once, no website re-upload.
# The file name carries the SHA-1, so a new pack is a new file and clients never get a stale cached copy.
PACK_URL = "https://raw.githubusercontent.com/crayosog-star/BlackforgeMC/main/website/pack/blackforge-pack-4b53675c.zip"
PACK_SHA1 = "4b53675cfef56f532637e1dd38cab1f61898143b"

SERVERS = {
    "hub":      {"port": 30001, "mode": "hub", "display": "Hub", "join": ""},
    "skyblock": {"port": 30002, "mode": "skyblock", "display": "Skyblock", "join": ""},
    "survival": {"port": 30003, "mode": "survival", "display": "Survival", "join": ""},
    "factions": {"port": 30004, "mode": "factions", "display": "Factions", "join": ""},
    "arcade":   {"port": 30005, "mode": "arcade", "display": "Arcade", "join": ""},
}

secret_file = ROOT / "tools" / "rcon.secret"
if not secret_file.exists():
    secret_file.write_text(secrets.token_urlsafe(18))
RCON_PASS = secret_file.read_text().strip()


def set_props(path, values):
    lines = path.read_text(encoding="utf-8").splitlines()
    seen = set()
    for i, l in enumerate(lines):
        k = l.split("=", 1)[0]
        if k in values:
            lines[i] = f"{k}={values[k]}"; seen.add(k)
    lines += [f"{k}={v}" for k, v in values.items() if k not in seen]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


targets = sys.argv[1:] or list(SERVERS)
for name, s in SERVERS.items():
    if name not in targets:
        continue
    d = ROOT / name
    plugins = d / "plugins"
    for old in plugins.glob("TAB*.jar"):
        old.unlink()
    shutil.copy(JAR, plugins / "AlloyCore.jar")
    cfg = plugins / "AlloyCore"
    cfg.mkdir(exist_ok=True)
    koth = [
        "koth:", "  interval-minutes: 120", "  first-after-minutes: 30",
        "  capture-seconds: 180", "  max-minutes: 20", "  reward-money: 5000",
        "pvp:", "  drop-interval-minutes: 300", "  first-drop-after-minutes: 60", "  crates-per-drop: 15",
        "rtp:", "  border-size: 100000", "  range: 100000   # wild teleports land anywhere inside the border",
        "votes:", "  party-every: 50", "  money: 250", "  sites: []",
        "store-url: 'Store coming soon!'",
        "gale-bellows:", "  enabled: true", "  custom-model: true   # the resource pack below is live, so the bellows can use its texture",
    ] if s["mode"] == "survival" else [
        "rtp:", "  border-size: 12000",
        "season:", "  months: 3   # the factions map resets every this many months",
    ] if s["mode"] == "factions" else [
        "oneblock:",
        f"  fragment-chance-block: {os.environ.get('BF_FRAGMENT_CHANCE', '0.00333')}   # Spawner Fragment per OneBlock break (~1 in 300)",
        f"  fragment-chance-chest: {os.environ.get('BF_FRAGMENT_CHANCE', '0.0833')}   # per OneBlock chest opened (~1 in 12)",
    ] if s["mode"] == "skyblock" else []
    (cfg / "config.yml").write_text("\n".join([
        "# hub | arcade | survival | skyblock | factions | backend", f"mode: {s['mode']}", f"display-name: {s['display']}",
        f"join-command: '{s['join']}'", f"address: '{ADDRESS}'", "resource-pack-live: true", *koth, ""]), encoding="utf-8")
    set_props(d / "server.properties", {
        "enable-rcon": "true", "rcon.port": str(s["port"] + 1000), "rcon.password": RCON_PASS,
        "broadcast-rcon-to-ops": "false", "motd": f"BlackForgeMC {s['display']}",
    })
    # one network-wide pack for every custom item texture. The hub asks for it on join; the other servers send the same
    # URL + SHA-1, so the client reuses its cached copy instead of downloading again. Rebuild with tools/build_pack.py
    # and update PACK_URL/PACK_SHA1 whenever the pack changes.
    set_props(d / "server.properties", {
        "resource-pack": PACK_URL, "resource-pack-sha1": PACK_SHA1, "require-resource-pack": "true",
        "resource-pack-prompt": '{"text":"BlackForgeMC needs a small texture pack for its custom items.","color":"gold"}',
    })
    ops = d / "ops.json"
    cur = json.loads(ops.read_text()) if ops.exists() and ops.read_text().strip() else []
    for op in PERMANENT_OPS:
        if not any(o["uuid"] == op["uuid"] for o in cur):
            cur.append(op)
    ops.write_text(json.dumps(cur, indent=2))
    if s["mode"] in ("hub", "factions"):   # blackforge:inferno (hub) and blackforge:ember_night (factions spawn) biomes
        dp = d / "world" / "datapacks" / "blackforge"
        if dp.exists():
            shutil.rmtree(dp)
        shutil.copytree(ROOT / "datapacks" / "blackforge", dp)
    icon = ROOT / "branding" / "server-icon.png"
    if icon.exists():
        shutil.copy(icon, d / "server-icon.png")
    print("deployed", name)
