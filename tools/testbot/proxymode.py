"""Switch the proxy between online (real players) and offline (test bots) and restart it. Refuses if anyone is online."""
import re, subprocess, sys, time, socket, pathlib
ROOT = pathlib.Path(r"C:\MinecraftNetwork")
mode = sys.argv[1]  # online | offline
if mode == "offline":
    for s in ["hub", "skyblock", "survival", "factions", "arcade"]:
        out = subprocess.run([sys.executable, str(ROOT / "tools" / "netctl.py"), "cmd", s, "list"], capture_output=True, text=True).stdout
        m = re.search(r"(\d+)\D+(?:of|out of)", re.sub("§.", "", out))
        if not m or m.group(1) != "0": sys.exit(f"refusing: {s} has players or no reply: {out.strip()[:80]}")
flag = ROOT / "shared" / "test-mode.flag"
# backends refuse everyone except AlloyTester bots while this flag exists (AlloyCore TestGuard)
if mode == "offline": flag.write_text("bot test in progress")
t = ROOT / "proxy" / "velocity.toml"
t.write_text(re.sub(r"(?m)^online-mode = .*$", f"online-mode = {'true' if mode == 'online' else 'false'}", t.read_text()))
subprocess.run(["powershell", "-NoProfile", "-Command", "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -like '*velocity.jar*' -and $_.CommandLine -like '*MinecraftNetwork*' } | ForEach-Object { Stop-Process -Id $_.ProcessId }"], capture_output=True)
time.sleep(3)
subprocess.run([sys.executable, str(ROOT / "tools" / "netctl.py"), "start"], capture_output=True)
for _ in range(40):
    try: socket.create_connection(("127.0.0.1", 25577), timeout=1).close(); break
    except OSError: time.sleep(1)
time.sleep(3)
if mode == "online" and flag.exists(): flag.unlink()
print("proxy now", mode)
