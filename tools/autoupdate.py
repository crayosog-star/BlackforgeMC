"""Auto-update BlackForgeMC from GitHub (run every few minutes by Windows Task Scheduler).

When origin/main has new commits:
  1. fast-forward pull (never merges or overwrites local edits - if it can't fast-forward it logs and stops)
  2. if the plugin or datapack changed: build AlloyCore; a failed build is logged and nothing is deployed
  3. restart each backend to install it - empty servers right away, servers with players after a
     5-minute in-game countdown
Website/docs/tools-only changes are just pulled. Everything is logged to tools/autoupdate.log.
"""
import os, re, subprocess, sys, time
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOOLS = ROOT / "tools"
LOG = TOOLS / "autoupdate.log"
LOCK = TOOLS / "autoupdate.lock"
SERVERS = ["hub", "skyblock", "survival", "factions", "arcade"]
NEEDS_DEPLOY = ("plugins-src/", "datapacks/", "branding/server-icon.png", "tools/deploy.py")
COUNTDOWN = [300, 60, 30, 10]   # seconds-left warnings for servers with players
NO_WINDOW = 0x08000000


def log(msg):
    line = f"[{datetime.now():%Y-%m-%d %H:%M:%S}] {msg}"
    with open(LOG, "a", encoding="utf-8") as f: f.write(line + "\n")
    print(line)


def run(args, cwd=ROOT, env=None, timeout=900):
    r = subprocess.run(args, cwd=cwd, env=env, capture_output=True, text=True, timeout=timeout, creationflags=NO_WINDOW)
    return r.returncode, (r.stdout or "") + (r.stderr or "")


def git(*a): return run(["git", *a])


def netctl(*a):
    return run([sys.executable, str(TOOLS / "netctl.py"), *a], timeout=600)[1]


def players(server):
    out = re.sub("§.", "", netctl("cmd", server, "minecraft:list"))
    m = re.search(r"There are (\d+)", out)
    return int(m.group(1)) if m else -1   # -1: server down / no reply


def say(server, text):
    netctl("cmd", server, 'minecraft:tellraw @a {"text":"' + text + '","color":"gold"}')


def build():
    env = dict(os.environ, JAVA_HOME=str(next(TOOLS.glob("jdk-*"))))
    gradle = next(TOOLS.glob("gradle-*")) / "bin" / "gradle.bat"
    code, out = run([str(gradle), "-q", "build", "-x", "test"], cwd=ROOT / "plugins-src" / "AlloyCore", env=env)
    if code != 0: log("BUILD FAILED - not deploying:\n" + out[-3000:])
    return code == 0


def install(short):
    pending = []
    for s in SERVERS:
        n = players(s)
        if n < 0: log(f"{s}: not responding - skipped (it will pick up the new jar at its next restart via deploy)"); continue
        if n == 0: log(f"{s}: empty - restarting now"); netctl("restart", s)
        else: pending.append(s)
    if not pending: return
    log(f"players online on {pending} - {COUNTDOWN[0] // 60}-minute countdown")
    left = COUNTDOWN[0]
    for i, warn in enumerate(COUNTDOWN):
        if left > warn: time.sleep(left - warn); left = warn
        when = f"{warn // 60} minutes" if warn >= 60 else f"{warn} seconds"
        for s in pending:
            if players(s) > 0: say(s, f"Server update ({short}) installs in {when} - this server will restart for about a minute.")
    time.sleep(left)
    for s in pending: log(f"{s}: restarting"); netctl("restart", s)


def main():
    if LOCK.exists() and time.time() - LOCK.stat().st_mtime < 3600: return   # another run is mid-update
    LOCK.write_text(str(os.getpid()))
    try:
        code, out = git("fetch", "--quiet", "origin", "main")
        if code != 0: log("fetch failed: " + out.strip()[:300]); return
        head, remote = git("rev-parse", "HEAD")[1].strip(), git("rev-parse", "origin/main")[1].strip()
        if head == remote: return
        if git("merge-base", "--is-ancestor", "HEAD", "origin/main")[0] != 0:
            log("local commits aren't on GitHub yet (or histories diverged) - not pulling; push or reconcile first"); return
        changed = git("diff", "--name-only", head, remote)[1].split()
        short = remote[:7]
        code, out = git("merge", "--ff-only", "--quiet", "origin/main")
        if code != 0: log("pull failed (local edits in the way?): " + out.strip()[:500]); return
        log(f"pulled {head[:7]} -> {short}: {len(changed)} file(s) changed")
        if not any(f.startswith(NEEDS_DEPLOY) for f in changed): log("no plugin/datapack changes - nothing to restart"); return
        if build(): install(short); log("update installed")
    finally:
        LOCK.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
