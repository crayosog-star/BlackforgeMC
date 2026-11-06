import sys, time
from rc import R
start = int(sys.argv[1]); lava_only = len(sys.argv) > 2 and sys.argv[2] == "lava"
def log(m): print(time.strftime("%H:%M:%S"), m, flush=True)
def conn():
    while True:
        try: return R()
        except Exception as e: log("reconnect wait " + str(e)[:60]); time.sleep(5)
r = conn()
lines = open("out/commands.txt").read().split("\n"); total = len(lines)
i = start
if lava_only: i = lines.index("#LAVA") + 1
while i < total:
    ln = lines[i]
    if ln and ln != "#LAVA":
        try: out = r.cmd(ln)
        except Exception as e:
            log(f"conn lost at {i}: {str(e)[:50]}"); time.sleep(10); r = conn(); i = max(start, i - 20000); log(f"resume from {i}"); continue
    i += 1
    if i % 50000 == 0: log(f"{i}/{total}"); open("out/progress.txt", "w").write(str(i))
log("DONE build")
