"""Create the store supporter LuckPerms groups (Ember/Blaze/Inferno/Forgemaster). Re-runnable.

Cosmetic perks only (Mojang server rules): badge (alloy.supporter.<id>), name colour (alloy.tag.<id>),
trails (AlloyCore, by tier) and /hat. LuckPerms data is shared, so running it on one server is enough.
Usage: setup_supporters.py [server]   (default: hub)
"""
import subprocess, sys
SERVER = sys.argv[1] if len(sys.argv) > 1 else "hub"
def lp(cmd):
    r = subprocess.run([sys.executable, r"C:\MinecraftNetwork\tools\netctl.py", "cmd", SERVER, "lp " + cmd], capture_output=True, text=True)
    return r.stdout.strip()
ladder = [
    ("ember", 5, None, ["essentials.hat"]),
    ("blaze", 6, "ember", []),
    ("inferno", 7, "blaze", []),
    ("forgemaster", 8, "inferno", []),
]
for tier, weight, parent, extra in ladder:
    g = "supporter_" + tier
    lp(f"creategroup {g}")
    lp(f"group {g} setweight {weight}")
    lp(f"group {g} permission set alloy.supporter.{tier} true")
    lp(f"group {g} permission set alloy.tag.{tier} true")
    for p in extra:
        lp(f"group {g} permission set {p} true")
    if parent:
        lp(f"group {g} parent add supporter_{parent}")
    print("supporter group", g, "ok")
