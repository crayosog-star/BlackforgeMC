"""Safe, reversible performance tuning for the BlackForgeMC backends. Backs every file up first (build/config-backup-<date>/),
edits only keys that exist, and prints what changed. Takes effect at each server's next restart."""
import datetime, os, re, shutil

ROOT = r'C:\MinecraftNetwork'
STAMP = datetime.date.today().isoformat()
BACKUP = os.path.join(ROOT, 'build', f'config-backup-{STAMP}')
SERVERS = ['hub', 'skyblock', 'survival', 'factions', 'arcade']
SIM = {'skyblock': 8, 'survival': 8, 'factions': 8, 'arcade': 8}   # hub stays at 4
changed = []


def edit(server, rel, rules):
    path = os.path.join(ROOT, server, rel)
    if not os.path.exists(path):
        return
    s = open(path, encoding='utf-8', newline='').read()
    new = s
    for pat, repl, label in rules:
        n, c = re.subn(pat, repl, new, count=1, flags=re.M)
        if c and n != new:
            changed.append(f'{server}/{rel}: {label}')
            new = n
    if new != s:
        dst = os.path.join(BACKUP, server, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        if not os.path.exists(dst):
            shutil.copy2(path, dst)
        open(path, 'w', encoding='utf-8', newline='').write(new)


for s in SERVERS:
    props = [(r'^network-compression-threshold=.*$', 'network-compression-threshold=-1', 'no compression between proxy and backend (Velocity compresses for the client)')]
    if s in SIM:
        props.append((r'^simulation-distance=.*$', f'simulation-distance={SIM[s]}', f'simulation distance {SIM[s]}'))
    edit(s, 'server.properties', props)
    edit(s, os.path.join('config', 'paper-world-defaults.yml'), [
        (r'^(\s*)max-entity-collisions: \d+', r'\g<1>max-entity-collisions: 2', 'max entity collisions 2'),
        (r'^(\s*)optimize-explosions: false', r'\g<1>optimize-explosions: true', 'optimize explosions'),
        (r'^(\s*)disable-chest-cat-detection: false', r'\g<1>disable-chest-cat-detection: true', 'no chest cat detection'),
    ])
    edit(s, 'spigot.yml', [
        (r'^(\s*)animals: 32$', r'\g<1>animals: 16', 'activation range animals 16'),
        (r'^(\s*)monsters: 32$', r'\g<1>monsters: 24', 'activation range monsters 24'),
        (r'^(\s*)raiders: 64$', r'\g<1>raiders: 48', 'activation range raiders 48'),
        (r'^(\s*)misc: 16$', r'\g<1>misc: 8', 'activation range misc 8'),
        (r'^(\s*)water: 16$', r'\g<1>water: 8', 'activation range water 8'),
        (r'^(\s*)villagers: 32$', r'\g<1>villagers: 16', 'activation range villagers 16'),
        (r'^(\s*)flying-monsters: 32$', r'\g<1>flying-monsters: 24', 'activation range flying monsters 24'),
    ])
    edit(s, 'bukkit.yml', [
        (r'^(\s*)monsters: 70$', r'\g<1>monsters: 50', 'monster spawn limit 50'),
        (r'^(\s*)ambient: 15$', r'\g<1>ambient: 1', 'ambient (bat) spawn limit 1'),
    ])

# JVM: fixed heap (no resize pauses) - the host has 32 GB and every server uses well under its cap
nc = os.path.join(ROOT, 'tools', 'netctl.py')
t = open(nc, encoding='utf-8', newline='').read()
if '"-Xms1G", f"-Xmx{xmx}"' in t:
    shutil.copy2(nc, os.path.join(BACKUP, 'netctl.py')) if os.path.isdir(BACKUP) or os.makedirs(BACKUP, exist_ok=True) is None else None
    t = t.replace('"-Xms1G", f"-Xmx{xmx}"', 'f"-Xms{xmx}", f"-Xmx{xmx}"')
    open(nc, 'w', encoding='utf-8', newline='').write(t)
    changed.append('tools/netctl.py: -Xms = -Xmx for every backend')

print('\n'.join(changed) if changed else 'nothing to change (already applied)')
print('backup:', BACKUP)
