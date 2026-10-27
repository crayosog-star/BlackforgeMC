"""BlackForgeMC network setup: lays out proxy + backend servers, first-boots them to
generate configs, then patches the configs. Safe to re-run (it only overwrites
the settings it owns)."""
import os, re, shutil, subprocess, sys, time, secrets, threading
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DL = ROOT / "_downloads"
JAVA = str(next((ROOT / "tools").glob("jdk-25*")) / "bin" / "java.exe")
NAME = "BlackForgeMC"

PROXY_PORT = 25577
SERVERS = {
    # name: (port, Xmx, plugins, extra server.properties)
    "hub": (30001, "1536M", ["LuckPerms-Bukkit", "GrimAC"], {
        "gamemode": "adventure", "force-gamemode": "true", "pvp": "false", "difficulty": "peaceful",
        "spawn-monsters": "false", "spawn-animals": "false", "spawn-npcs": "false",
        "level-type": "minecraft\\:flat", "generate-structures": "false", "spawn-protection": "0",
        "generator-settings": '{"layers"\\:[{"block"\\:"minecraft\\:air","height"\\:1}],"biome"\\:"minecraft\\:the_void"}',
        "view-distance": "8", "allow-nether": "false"}),
    "skyblock": (30002, "2G", ["LuckPerms-Bukkit", "VaultUnlocked", "EssentialsX", "BentoBox"], {
        "difficulty": "normal", "spawn-protection": "0", "allow-nether": "true"}),
    "survival": (30003, "2560M", ["LuckPerms-Bukkit", "VaultUnlocked", "EssentialsX", "EssentialsXSpawn", "GriefPrevention"], {
        "difficulty": "normal", "spawn-protection": "16", "pvp": "false"}),
    "factions": (30004, "2G", ["LuckPerms-Bukkit", "VaultUnlocked", "EssentialsX", "EssentialsXSpawn", "PvPIndex-Factions"], {
        "difficulty": "hard", "spawn-protection": "16", "pvp": "true"}),
    "arcade": (30005, "1536M", ["LuckPerms-Bukkit"], {
        "gamemode": "adventure", "force-gamemode": "true", "difficulty": "peaceful",
        "spawn-monsters": "false", "spawn-animals": "false", "spawn-npcs": "false",
        "level-type": "minecraft\\:flat", "generate-structures": "false", "spawn-protection": "0",
        "generator-settings": '{"layers"\\:[{"block"\\:"minecraft\\:air","height"\\:1}],"biome"\\:"minecraft\\:the_void"}',
        "view-distance": "8", "allow-nether": "false"}),
}
BENTO_ADDONS = ["BSkyBlock", "Level", "Challenges", "Warps"]
PROXY_PLUGINS = ["ViaVersion", "ViaBackwards"]   # no LuckPerms on the proxy: it would overwrite shared rank data


def set_props(path, values):
    lines = path.read_text(encoding="utf-8").splitlines() if path.exists() else []
    seen = set()
    for i, l in enumerate(lines):
        k = l.split("=", 1)[0]
        if k in values:
            lines[i] = f"{k}={values[k]}"; seen.add(k)
    lines += [f"{k}={v}" for k, v in values.items() if k not in seen]
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def first_boot(cwd, cmd, done_pat, stop_cmd, timeout=600):
    """Run a server until its startup log matches done_pat, then stop it."""
    p = subprocess.Popen(cmd, cwd=cwd, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                         stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace",
                         creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    log = []
    def reader():
        for line in p.stdout:
            log.append(line)
    t = threading.Thread(target=reader, daemon=True); t.start()
    start = time.time()
    while time.time() - start < timeout:
        if any(re.search(done_pat, l) for l in log[-50:]) or p.poll() is not None:
            break
        time.sleep(1)
    if p.poll() is None:
        p.stdin.write(stop_cmd + "\n"); p.stdin.flush()
        try: p.wait(120)
        except subprocess.TimeoutExpired: p.kill()
    t.join(5)
    (Path(cwd) / "first-boot.log").write_text("".join(log), encoding="utf-8")
    errs = [l.rstrip() for l in log if re.search(r"ERROR|Could not load|Exception", l)]
    return errs


def main():
    secret = None
    proxy = ROOT / "proxy"
    shutil.copy(DL / "velocity.jar", proxy / "velocity.jar")
    (proxy / "plugins").mkdir(exist_ok=True)
    for pl in PROXY_PLUGINS:
        shutil.copy(DL / "plugins" / f"{pl}.jar", proxy / "plugins" / f"{pl}.jar")
    if not (proxy / "velocity.toml").exists():
        print("proxy first boot:", first_boot(proxy, [JAVA, "-Xmx512M", "-jar", "velocity.jar"], r"Done \(", "end"))
    secret = (proxy / "forwarding.secret").read_text().strip()

    toml = (proxy / "velocity.toml").read_text(encoding="utf-8")
    toml = re.sub(r'^bind = .*$', f'bind = "0.0.0.0:{PROXY_PORT}"', toml, flags=re.M)
    toml = re.sub(r'^motd = .*$', 'motd = "<gradient:#ffb347:#ff5a1a><bold>BLACKFORGE</bold></gradient><gray><bold>MC</bold> <dark_gray>| <white>Skyblock <gray>• <white>OneBlock <gray>• <white>Survival <gray>• <white>Factions <gray>• <white>Arcade<newline><gray>blackforgemc.com"', toml, flags=re.M)
    toml = re.sub(r'^show-max-players = .*$', 'show-max-players = 100', toml, flags=re.M)
    toml = re.sub(r'^online-mode = .*$', 'online-mode = true', toml, flags=re.M)
    toml = re.sub(r'^player-info-forwarding-mode = .*$', 'player-info-forwarding-mode = "modern"', toml, flags=re.M)
    servers_block = "[servers]\n" + "".join(f'{n} = "127.0.0.1:{SERVERS[n][0]}"\n' for n in SERVERS) + 'try = [\n    "hub"\n]\n'
    toml = re.sub(r'^\[servers\].*?(?=^\[forced-hosts\])', servers_block + "\n", toml, flags=re.M | re.S)
    toml = re.sub(r'^\[forced-hosts\].*?(?=^\[advanced\])', "[forced-hosts]\n\n", toml, flags=re.M | re.S)
    (proxy / "velocity.toml").write_text(toml, encoding="utf-8")

    for name, (port, xmx, plugins, props) in SERVERS.items():
        d = ROOT / name
        (d / "plugins").mkdir(parents=True, exist_ok=True)
        shutil.copy(DL / "paper.jar", d / "paper.jar")
        for pl in plugins:
            shutil.copy(DL / "plugins" / f"{pl}.jar", d / "plugins" / f"{pl}.jar")
        if name == "skyblock":
            (d / "plugins" / "BentoBox" / "addons").mkdir(parents=True, exist_ok=True)
            for a in BENTO_ADDONS:
                shutil.copy(DL / "plugins" / f"{a}.jar", d / "plugins" / "BentoBox" / "addons" / f"{a}.jar")
        (d / "eula.txt").write_text("eula=true\n")
        set_props(d / "server.properties", {
            "server-ip": "127.0.0.1", "server-port": str(port), "online-mode": "false",
            "motd": f"{NAME} {name}", "max-players": "100", "enable-command-block": "false",
            "white-list": "false", "level-name": "world", **props})
        if not (d / "config" / "paper-global.yml").exists():
            errs = first_boot(d, [JAVA, "-Xms512M", f"-Xmx{xmx}", "-jar", "paper.jar", "--nogui"], r"Done \(", "stop")
            print(f"{name} first boot errors: {len(errs)}"); [print("   ", e) for e in errs[:15]]
        g = d / "config" / "paper-global.yml"
        y = g.read_text(encoding="utf-8")
        y = re.sub(r"(velocity:\s*\n\s*enabled: )false", r"\1true", y)
        y = re.sub(r"(velocity:(?:\s*\n\s+.*)*?\n\s*secret: ).*", lambda m: m.group(1) + f"'{secret}'", y, count=1)
        g.write_text(y, encoding="utf-8")
        sp = d / "spigot.yml"
        if sp.exists():
            s = sp.read_text(encoding="utf-8")
            s = re.sub(r"(\n\s*bungeecord: )true", r"\1false", s)
            sp.write_text(s, encoding="utf-8")
        set_props(d / "server.properties", {"online-mode": "false"})
    print("done")


if __name__ == "__main__":
    main()
