D = r'C:\MinecraftNetwork\plugins-src\BlackForgeCore\src\main\java\net\blackforge\core\survival' + '\\'
def edit(p, pairs):
    s = open(D + p, encoding='utf-8', newline='').read()
    crlf = '\r\n' in s
    s = s.replace('\r\n', '\n')
    for a, b in pairs:
        assert a in s, a[:50]
        s = s.replace(a, b, 1)
    if crlf: s = s.replace('\n', '\r\n')
    open(D + p, 'w', encoding='utf-8', newline='').write(s)

edit('SunderingArena.java', [
    ('    private World world;', '    private World world;\n    private Containment contain;'),
    ('        ready = true;\n        plugin.getLogger().info("The Sundering: arena ready");', '        contain = new Containment(plugin, world);\n        ready = true;\n        plugin.getLogger().info("The Sundering: arena ready");'),
    ('    void chain(Sleeper s, boolean forming) {\n        clear(s);', '    void chain(Sleeper s, boolean forming) {\n        clear(s);\n        if (up && contain != null) contain.restore(s.pit);'),
    ('    void snap(Sleeper s, int chain) {\n', '    void snap(Sleeper s, int chain) {\n        if (contain != null) contain.snap(s.pit, chain);\n'),
    ('    /** The woken boss is out of its pit', "    /** The Frost Titan's iceberg melts as his wake-up runs. */\n    void melt(Sleeper s, double f) { if (contain != null) contain.melt(s.pit, f); }\n\n    /** The cage / iceberg goes when the boss bursts out. */\n    void breakOut(Sleeper s) { if (contain != null) contain.breakOut(s.pit); }\n\n    /** The woken boss is out of its pit"),
])
edit('WorldBoss.java', [
    ('        int t = ++wakeTick;\n', '        int t = ++wakeTick;\n        if (chosen != null) arena.melt(chosen, Math.min(1, t / (double) SunderingMath.BREAK_OUT));\n'),
    ('        if (t == SunderingMath.BREAK_OUT) {\n            w.strikeLightningEffect(at);', '        if (t == SunderingMath.BREAK_OUT) {\n            arena.breakOut(chosen);\n            w.strikeLightningEffect(at);'),
])
print("patched")
