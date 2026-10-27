"""AlloyMC network control.

  python tools/netctl.py start            start proxy + all servers in the background (no windows)
  python tools/netctl.py stop             save and stop everything cleanly
  python tools/netctl.py status           which servers are up
  python tools/netctl.py restart <server>  stop it, deploy the latest AlloyCore to it, start it again
  python tools/netctl.py cmd <server> <command...>   run a console command (e.g. cmd hub op Steve)
"""
import socket, struct, subprocess, sys, time
sys.stdout.reconfigure(encoding="utf-8", errors="replace")
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAVA = str(next((ROOT / "tools").glob("jdk-25*")) / "bin" / "java.exe")
PROXY_PORT = 25577
SERVERS = {"hub": (30001, "1536M"), "skyblock": (30002, "2G"), "survival": (30003, "2560M"),
           "factions": (30004, "2G"), "arcade": (30005, "1536M")}
FLAGS = ["-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled", "-XX:MaxGCPauseMillis=200", "-XX:+UnlockExperimentalVMOptions",
         "-XX:+DisableExplicitGC", "-XX:G1NewSizePercent=30", "-XX:G1MaxNewSizePercent=40", "-XX:G1HeapRegionSize=8M",
         "-XX:G1ReservePercent=20", "-XX:InitiatingHeapOccupancyPercent=15"]
NO_WINDOW = 0x08000000 | 0x00000200 | 0x00000008   # CREATE_NO_WINDOW | NEW_PROCESS_GROUP | DETACHED


def rcon(port, cmd, timeout=10):
    pw = (ROOT / "tools" / "rcon.secret").read_text().strip()
    s = socket.create_connection(("127.0.0.1", port + 1000), timeout=timeout)
    def send(i, t, body):
        data = struct.pack("<ii", i, t) + body.encode("utf-8") + b"\x00\x00"
        s.sendall(struct.pack("<i", len(data)) + data)
    def recv():
        n = struct.unpack("<i", s.recv(4))[0]
        buf = b""
        while len(buf) < n: buf += s.recv(n - len(buf))
        return struct.unpack("<ii", buf[:8])[0], buf[8:-2].decode("utf-8", "replace")
    send(1, 3, pw)
    if recv()[0] == -1: raise RuntimeError("rcon auth failed")
    send(2, 2, cmd)
    try: out = recv()[1]
    except Exception: out = ""
    s.close()
    return out


def up(port):
    try: socket.create_connection(("127.0.0.1", port), timeout=1).close(); return True
    except OSError: return False


def start_server(name):
    port, xmx = SERVERS[name]
    if up(port): print(f"{name}: already running"); return
    subprocess.Popen([JAVA, "-Xms1G", f"-Xmx{xmx}", *FLAGS, "-jar", "paper.jar", "--nogui"], cwd=ROOT / name,
                     stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=NO_WINDOW)
    print(f"{name}: starting")


def start():
    for name, (port, xmx) in SERVERS.items():
        if up(port): print(f"{name}: already running"); continue
        subprocess.Popen([JAVA, "-Xms1G", f"-Xmx{xmx}", *FLAGS, "-jar", "paper.jar", "--nogui"], cwd=ROOT / name,
                         stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, creationflags=NO_WINDOW)
        print(f"{name}: starting")
    if not up(PROXY_PORT):
        subprocess.Popen([JAVA, "-Xms256M", "-Xmx512M", "-jar", "velocity.jar"], cwd=ROOT / "proxy",
                         stdin=subprocess.PIPE, stdout=open(ROOT / "proxy" / "console.log", "w"), stderr=subprocess.STDOUT,
                         creationflags=NO_WINDOW)
        print("proxy: starting")


def stop(names=None):
    """No names: the whole network (and the proxy). With names: only those backends."""
    for name, (port, _) in SERVERS.items():
        if names and name not in names: continue
        if not up(port): continue
        try: rcon(port, "stop"); print(f"{name}: stopping")
        except Exception as e: print(f"{name}: rcon failed ({e})")
    if names: return
    subprocess.run(["powershell", "-NoProfile", "-Command",
                    "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | Where-Object { $_.CommandLine -like '*velocity.jar*' -and $_.CommandLine -like '*MinecraftNetwork*' } | ForEach-Object { Stop-Process -Id $_.ProcessId }"],
                   capture_output=True)
    print("proxy: stopped")


def restart(name):
    port = SERVERS[name][0]
    if up(port):
        rcon(port, "stop")
        for _ in range(60):
            time.sleep(1)
            if not up(port): break
        time.sleep(3)   # let java release the jar
    subprocess.run([sys.executable, str(ROOT / "tools" / "deploy.py"), name], check=True)
    # only this server: starting "everything that's down" here would launch a second copy of servers still booting
    start_server(name)


def status():
    print(f"proxy  :{PROXY_PORT}  {'UP' if up(PROXY_PORT) else 'down'}")
    for name, (port, _) in SERVERS.items():
        print(f"{name:9}:{port}  {'UP' if up(port) else 'down'}")


if __name__ == "__main__":
    a = sys.argv[1:] or ["status"]
    if a[0] == "start": start()
    elif a[0] == "stop":
        bad = [n for n in a[1:] if n not in SERVERS]
        if bad: sys.exit(f"unknown server(s): {bad}")
        stop(a[1:])
    elif a[0] == "status": status()
    elif a[0] == "restart": [restart(n) for n in a[1:]]
    elif a[0] == "cmd": print(rcon(SERVERS[a[1]][0], " ".join(a[2:])))
