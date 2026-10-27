"""Remove every trace of the AlloyTester bots (run with servers stopped)."""
import glob, json, os
root = r"C:\MinecraftNetwork"
servers = ["hub", "skyblock", "survival", "factions", "arcade"]
testers = set()
for s in servers:
    uc = os.path.join(root, s, "usercache.json")
    if os.path.exists(uc):
        data = json.load(open(uc))
        testers |= {e["uuid"] for e in data if e["name"].startswith("Alloy")}
        json.dump([e for e in data if not e["name"].startswith("Alloy")], open(uc, "w"))
removed = 0
for s in servers:
    pats = [os.path.join(root, s, "plugins", "Essentials", "userdata", "*.yml")] + \
           [os.path.join(root, s, w, sub, "*") for w in ("world", "world_nether", "world_the_end") for sub in ("playerdata", "stats", "advancements", "players/data", "players/stats", "players/advancements")]
    for pat in pats:
        for f in glob.glob(pat):
            if any(t in os.path.basename(f) for t in testers):
                os.remove(f); removed += 1
    for fn in ["claims.yml", "auctions.yml", "stats.yml"]:
        p = os.path.join(root, s, "plugins", "AlloyCore", fn)
        if os.path.exists(p):
            lines = open(p, encoding="utf-8").read().splitlines()
            open(p, "w", encoding="utf-8").write("\n".join(l for l in lines if not any(t in l for t in testers)) + "\n")
    ops = os.path.join(root, s, "ops.json")
    if os.path.exists(ops):
        json.dump([o for o in json.load(open(ops)) if not o["name"].startswith("Alloy")], open(ops, "w"), indent=2)
vp = os.path.join(root, "survival", "plugins", "AlloyCore", "votes.yml")
if os.path.exists(vp):
    import re as _re
    open(vp, "w", encoding="utf-8").write(_re.sub(r"(?m)^party: \d+", "party: 0", open(vp, encoding="utf-8").read()))
sh = os.path.join(root, "shared", "moderation.yml")
if os.path.exists(sh):
    out, skip = [], None
    for l in open(sh, encoding="utf-8").read().splitlines():
        ind = len(l) - len(l.lstrip())
        if skip is not None and l.strip() and ind > skip: continue
        skip = None
        if any(t in l for t in testers): skip = ind; continue
        out.append(l)
    open(sh, "w", encoding="utf-8").write(chr(10).join(out) + chr(10))
for s in servers:   # robots owned by testers (robots.yml: one "  <id>:" block per robot)
    rp = os.path.join(root, s, "plugins", "AlloyCore", "robots.yml")
    if os.path.exists(rp):
        import re as _r
        parts = _r.split(r"(?m)^(?=  \S)", open(rp, encoding="utf-8").read())
        open(rp, "w", encoding="utf-8").write("".join(p for p in parts if not any(t in p for t in testers)))
sd = os.path.join(root, "arcade", "plugins", "AlloyCore", "arcade", "stats")   # arcade per-player stats
if os.path.isdir(sd):
    for fn in os.listdir(sd):
        if any(t in fn for t in testers): os.remove(os.path.join(sd, fn)); removed += 1
for sub in ("economy", "luckperms/users"):
    d = os.path.join(root, "shared", *sub.split("/"))
    if os.path.isdir(d):
        for fn in os.listdir(d):
            fp = os.path.join(d, fn)
            txt = open(fp, encoding="utf-8", errors="ignore").read()
            if any(t in fn for t in testers) or "AlloyTester" in txt:
                os.remove(fp); removed += 1
print(f"testers: {len(testers)}, files removed: {removed}")
