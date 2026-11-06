"""Backs up the live Survival world and its plugin data to F:\\Backups\\BlackForgeMC before any spawn rebuild.
Flushes and pauses autosave while zipping so the world files are consistent, and always turns saving back on."""
import os, subprocess, sys, time, zipfile, datetime

ROOT = r'C:\MinecraftNetwork'
OUT = r'F:\Backups\BlackForgeMC'
STAMP = datetime.datetime.now().strftime('%Y-%m-%d_%H%M')
DEST = os.path.join(OUT, f'survival-{os.environ.get("BF_BACKUP_TAG", "pre-infernal-kingdom")}-{STAMP}.zip')
KEEP = ['world', 'plugins\\BlackForgeCore', 'plugins\\LuckPerms', 'plugins\\Essentials', 'plugins\\GriefPreventionData', 'plugins\\TAB',
        'plugins\\Vault', 'plugins\\Tebex', 'server.properties', 'bukkit.yml', 'spigot.yml', 'commands.yml', 'ops.json', 'whitelist.json']


def cmd(c):
    return subprocess.run([sys.executable, os.path.join(ROOT, 'tools', 'netctl.py'), 'cmd', 'survival', c], capture_output=True, text=True).stdout


def main():
    os.makedirs(OUT, exist_ok=True)
    cmd('save-all flush')
    time.sleep(5)
    cmd('save-off')
    try:
        base = os.path.join(ROOT, 'survival')
        n = 0
        with zipfile.ZipFile(DEST, 'w', zipfile.ZIP_DEFLATED, compresslevel=1) as z:
            for item in KEEP:
                p = os.path.join(base, item)
                if os.path.isfile(p):
                    z.write(p, item); n += 1
                elif os.path.isdir(p):
                    for d, _, fs in os.walk(p):
                        for f in fs:
                            if f == 'session.lock':
                                continue
                            fp = os.path.join(d, f)
                            try:
                                z.write(fp, os.path.relpath(fp, base)); n += 1
                            except Exception as e:
                                print('skip', fp, e)
        print('files', n, 'size MB', os.path.getsize(DEST) >> 20)
    finally:
        print(cmd('save-on'))
    with zipfile.ZipFile(DEST) as z:
        bad = z.testzip()
        print('zip test:', 'OK' if bad is None else 'BAD ' + bad)
    print('DONE', DEST)


main()
