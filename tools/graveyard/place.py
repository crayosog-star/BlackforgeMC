import math, sys, time
from rc import R
r = R(); WX, WZ = 8000, 8000
def log(m): print(time.strftime("%H:%M:%S"), m, flush=True)
stage = sys.argv[1] if len(sys.argv) > 1 else "all"
if stage in ("all", "clear"):
    for z0 in range(-224, 184, 128):
        log("forceload " + r.cmd(f"forceload add {WX-192} {WZ+z0} {WX+192} {WZ+z0+127}"))
    time.sleep(20)
    log("loaded? " + r.cmd(f"execute if loaded {WX-185} 100 {WZ-215}") + " / " + r.cmd(f"execute if loaded {WX+185} 100 {WZ+175}"))
    log("clearing")
    for zz in range(-190, 190):
        span = int(math.sqrt(max(0, 190 * 190 - zz * zz)))
        for y1, y2 in ((21, 106), (107, 192), (193, 278), (279, 319)):
            r.cmd(f"fill {WX-span} {y1} {WZ+zz} {WX+span} {y2} {WZ+zz} minecraft:air")
        if zz % 40 == 0: log(f"clear z={zz}")
if stage in ("all", "build"):
    n = 0; lines = open("out/commands.txt").read().split("\n"); total = len(lines); log(f"{total} commands")
    for ln in lines:
        if not ln or ln == "#LAVA": continue
        r.cmd(ln); n += 1
        if n % 50000 == 0: log(f"{n}/{total}")
    log("DONE build")
