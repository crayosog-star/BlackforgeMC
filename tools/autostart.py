"""Starts the BlackForgeMC proxy and all servers (the ones already running are left alone).

Run by the Windows scheduled task "BlackForgeMC Start" a short while after you log in, with no console window, so its output goes
to tools/autostart.log instead.
"""
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
log = open(HERE / "autostart.log", "a", encoding="utf-8", buffering=1)
sys.stdout = sys.stderr = log
print(f"--- {time.strftime('%Y-%m-%d %H:%M:%S')} autostart")
sys.path.insert(0, str(HERE))
try:
    import netctl
    netctl.start()
    time.sleep(20)
    netctl.status()
except Exception as e:   # never leave the task failing silently
    print("autostart failed:", repr(e))
    raise
