"""Auto-merge Colin's pull requests (GitHub: colinosbo) into BlackForgeMC main, then install them.

Gav's standing instruction (2026-10-27): anything from colinosbo is merged automatically and put on the servers.
Run every 5 minutes, hidden, by Task Scheduler (install_automerge.ps1). Log: tools/autoupdate.log (shared).

For each open, non-draft PR into main by colinosbo, oldest first:
  1. merge its head locally (--no-ff) on top of the current origin/main
  2. if the plugin changed: full build WITH tests + walkcheck on the merged code
     - conflict, failed build, failed test or failed walkcheck: the local merge is undone, nothing is pushed or
       deployed, the PR stays open and the reason is logged. It is retried when Colin pushes a new commit.
  3. push main - GitHub marks the PR as merged
Then, if anything merged touched the plugin/datapacks: install like autoupdate.py does (empty servers restart
now, servers with players after a 5-minute in-game countdown).
Every run starts with autoupdate.main(), so anything merged into main on GitHub by anyone else is installed too.
Reading PRs uses GitHub's public API (no login); merging is a normal git push with this PC's git credentials.
"""
import json
import urllib.request
from pathlib import Path

import autoupdate as au

REPO = "crayosog-star/BlackforgeMC"
AUTHOR = "colinosbo"
STATE = au.TOOLS / "automerge_state.json"   # PR number -> head sha that failed (don't retry the same code)
GIT_ID = ["-c", "user.name=GavVFX", "-c", "user.email=crayosog@gmail.com"]


def open_prs():
    req = urllib.request.Request(f"https://api.github.com/repos/{REPO}/pulls?state=open&base=main&sort=created&direction=asc",
                                 headers={"User-Agent": "blackforge-automerge", "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return [p for p in json.load(r) if p["user"]["login"].lower() == AUTHOR and not p.get("draft")]


def full_check():
    """Build with tests, then walkcheck. Returns (ok, output)."""
    env = dict(au.os.environ, JAVA_HOME=str(next(au.TOOLS.glob("jdk-*"))))
    gradle = next(au.TOOLS.glob("gradle-*")) / "bin" / "gradle.bat"
    code, out = au.run([str(gradle), "-q", "build", "walkcheck"], cwd=au.ROOT / "plugins-src" / "AlloyCore", env=env, timeout=1200)
    return code == 0, out


def main():
    # First catch up with main itself: PRs merged on GitHub (not Colin's) are pulled, built and installed here,
    # and /alloy restart requests are handled. Nothing else runs autoupdate.py on a schedule.
    au.main()
    try:
        prs = open_prs()
    except Exception as e:  # network / rate limit: try again next run
        au.log(f"automerge: could not read pull requests: {e}")
        return
    if not prs:
        return
    if au.LOCK.exists() and au.time.time() - au.LOCK.stat().st_mtime < 3600:
        return   # an update is in progress
    au.LOCK.write_text(str(au.os.getpid()))
    failed = json.loads(STATE.read_text()) if STATE.exists() else {}
    merged_any, changed_all = False, set()
    try:
        if au.git("status", "--porcelain")[1].strip():
            au.log("automerge: local changes in C:\\MinecraftNetwork - not merging until they're committed or removed")
            return
        code, out = au.git("fetch", "--quiet", "origin", "main")
        if code != 0:
            au.log("automerge: fetch failed: " + out.strip()[:300]); return
        if au.git("merge-base", "--is-ancestor", "HEAD", "origin/main")[0] != 0:
            au.log("automerge: local commits aren't on GitHub yet - push or reconcile first"); return
        au.git("merge", "--ff-only", "--quiet", "origin/main")   # catch up with anything already on main

        for pr in prs:
            n, head, title, branch = pr["number"], pr["head"]["sha"], pr["title"], pr["head"]["ref"]
            if failed.get(str(n)) == head:
                continue   # this exact code already failed; wait for a new commit
            before = au.git("rev-parse", "HEAD")[1].strip()
            code, out = au.git("fetch", "--quiet", "origin", f"pull/{n}/head")
            if code != 0:
                au.log(f"automerge #{n}: fetch failed: {out.strip()[:300]}"); continue
            msg = f"Merge pull request #{n} from {AUTHOR}/{branch}\n\n{title}\n\nAuto-merged for Gav (Colin's PRs merge automatically)."
            code, out = au.run(["git", *GIT_ID, "merge", "--no-ff", "-m", msg, head])
            if code != 0:
                au.git("merge", "--abort")
                au.log(f"automerge #{n} '{title}': merge conflict with main - left open:\n{out[-1500:]}")
                failed[str(n)] = head; continue
            changed = au.git("diff", "--name-only", before, "HEAD")[1].split()
            if any(f.startswith("plugins-src/") for f in changed):
                ok, out = full_check()
                if not ok:
                    au.git("reset", "--hard", before)   # undo only the merge just made (tree was clean before it)
                    au.log(f"automerge #{n} '{title}': build/tests/walkcheck FAILED - not merged, left open:\n{out[-3000:]}")
                    failed[str(n)] = head; continue
            code, out = au.git("push", "origin", "main")
            if code != 0:
                au.git("reset", "--hard", before)
                au.log(f"automerge #{n}: push failed (will retry next run): {out.strip()[:500]}"); continue
            failed.pop(str(n), None)
            merged_any = True
            changed_all.update(changed)
            au.log(f"automerge #{n} '{title}': MERGED and pushed ({len(changed)} file(s))")
    finally:
        STATE.write_text(json.dumps(failed, indent=1))
        au.LOCK.unlink(missing_ok=True)

    if merged_any and any(f.startswith(au.NEEDS_DEPLOY) for f in changed_all):
        short = au.git("rev-parse", "--short", "HEAD")[1].strip()
        au.LOCK.write_text(str(au.os.getpid()))
        try:
            au.install(short)   # jar was already built (with tests) above
            au.log(f"automerge: installed {short} on the servers")
        finally:
            au.LOCK.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
