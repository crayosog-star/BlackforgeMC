"""Create the Copper/Iron/Gold/Diamond/Netherite LuckPerms groups on survival (re-runnable)."""
import subprocess, sys
SERVER = sys.argv[1] if len(sys.argv) > 1 else "survival"
def lp(cmd):
    subprocess.run([sys.executable, r"C:\MinecraftNetwork\tools\netctl.py", "cmd", SERVER, "lp " + cmd], capture_output=True)
ladder = [
    ("copper", 10, None, ["essentials.hat", "essentials.workbench"]),
    ("iron", 20, "copper", ["essentials.enderchest", "essentials.feed"]),
    ("gold", 30, "iron", ["essentials.anvil"]),
    ("diamond", 40, "gold", []),
    ("netherite", 50, "diamond", []),
]
# /repair is replaced by AlloyCore's /repairhand (Gold+, every 2 hours)
lp("group diamond permission unset essentials.repair")
lp("group netherite permission unset essentials.repair.all")
for name, weight, parent, perms in ladder:
    lp(f"creategroup {name}")
    lp(f"group {name} setweight {weight}")
    lp(f"group {name} permission set alloy.rank.{name} true")
    lp(f"group {name} permission set essentials.sethome.multiple.{name} true")
    for p in perms:
        lp(f"group {name} permission set {p} true")
    if parent:
        for old in ("copper", "iron", "gold", "diamond"):
            if old != parent: lp(f"group {name} parent remove {old}")
        lp(f"group {name} parent add {parent}")
    print("rank", name, "ok")
