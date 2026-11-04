p = r'C:\MinecraftNetwork\tools\graveyard\Graveyard.java'
s = open(p, encoding='utf-8').read()

def rep(a, b):
    global s
    assert a in s, a[:70]
    s = s.replace(a, b, 1)

rep('String band = i == 0 ? "magma_block" : i == 1 ? "bone_block[axis=y]" : i == 2 ? "crying_obsidian" : "gold_block";',
    'String band = "copper_block";')
rep('if (r >= 11.3 && r <= 12.7 && ((x * 7 + z * 3) % 5 == 0)) { set(x, 12, z, "iron_bars"); set(x, 13, z, "iron_bars"); }',
    'if (r >= 11.3 && r <= 12.7 && ((x * 7 + z * 3) % 5 == 0)) { set(x, 12, z, "iron_bars"); set(x, 13, z, ((x + z) % 3 == 0) ? "lightning_rod[facing=up]" : "iron_bars"); if ((x + z) % 3 == 0) { set(x, 14, z, "lightning_rod[facing=up]"); } }')
rep('                for (int y = 1; y <= 16; y++) set(x, y, z, "air");',
    '                for (int y = 1; y <= 22; y++) set(x, y, z, "air");')
rep('            if (i == 3) iceberg(cx, cz); else cage(cx, cz, i);',
    '            for (int y = 1; y <= 22; y++) for (int x = cx - 14; x <= cx + 14; x++) for (int z = cz - 14; z <= cz + 14; z++) if (hypot(x - cx, z - cz) <= 14.2) set(x, y, z, "air");\n            switch (i) { case 0 -> magmaCocoon(cx, cz); case 1 -> ribcage(cx, cz); case 2 -> voidShards(cx, cz); case 3 -> iceberg(cx, cz); default -> cage(cx, cz, i); }')
rep('    /** Iron cage around a pit', '''    /** The Molten Ravager: a lattice of cooling magma crust. */
    static void magmaCocoon(int cx, int cz) {
        for (int y = 1; y <= 15; y++) for (int x = cx - 13; x <= cx + 13; x++) for (int z = cz - 13; z <= cz + 13; z++) {
            double d = sqrt((x - cx) * (x - cx) + y * y * 0.9 + (z - cz) * (z - cz));
            if (d <= 9.8 || d > 12.4) continue;
            if (hn(x, y, z) < .3 || (x + y * 2 + z) % 5 == 0) continue;
            double q = hn(x + 3, y, z); set(x, y, z, q < .38 ? "basalt[axis=y]" : q < .72 ? "magma_block" : "blackstone");
        }
        for (int k = 0; k < 6; k++) { double a = k * PI / 3 + .4; int sx = cx + (int) round(cos(a) * 6), sz = cz + (int) round(sin(a) * 6); capsule(sx, 11, sz, sx - cos(a) * 1.5, 17 + (k % 2) * 2, sz - sin(a) * 1.5, 1.8, .3, (x, y, z) -> pick(x, y, z, "basalt[axis=y]", "magma_block", "blackstone")); }
    }
    /** The Bone Colossus: a giant ribcage closing over its pit. */
    static void ribcage(int cx, int cz) {
        int n = 9;
        for (int k = 0; k < n; k++) {
            double a = k * 2 * PI / n + .2; double c = cos(a), sn = sin(a);
            double[][] rb = {{cx + 12.2 * c, 1, cz + 12.2 * sn}, {cx + 11.6 * c, 7, cz + 11.6 * sn}, {cx + 7.5 * c, 14, cz + 7.5 * sn}, {cx + 1.8 * c, 18, cz + 1.8 * sn}};
            tube(rb, t -> 1.7 - 0.8 * t, (x, y, z) -> bone(x, y, z).equals("diorite") || bone(x, y, z).equals("white_terracotta") ? "calcite" : bone(x, y, z));
        }
        ell(cx, 18, cz, 2.6, 1.7, 2.6, (x, y, z) -> "bone_block[axis=y]");
        for (int k = 0; k < n; k++) { double a = k * 2 * PI / n + .2 + PI / n; int sx = cx + (int) round(cos(a) * 12), sz = cz + (int) round(sin(a) * 12); box(sx, 1, sz, sx, 3 + (k % 3), sz, "bone_block[axis=y]"); }
    }
    /** The Void Revenant: obsidian shards leaning in around purple glass. */
    static void voidShards(int cx, int cz) {
        int n = 8;
        for (int k = 0; k < n; k++) {
            double a = k * 2 * PI / n + PI / 8; double c = cos(a), sn = sin(a);
            double[][] sh = {{cx + 12.3 * c, 1, cz + 12.3 * sn}, {cx + 10.8 * c, 9, cz + 10.8 * sn}, {cx + 4.5 * c, 19, cz + 4.5 * sn}};
            tube(sh, t -> 2.0 - 1.5 * t, (x, y, z) -> hn(x, y, z) < .72 ? "obsidian" : "crying_obsidian");
        }
        for (int x = cx - 14; x <= cx + 14; x++) for (int z = cz - 14; z <= cz + 14; z++) {
            double r = hypot(x - cx, z - cz);
            if (r >= 11.3 && r <= 12.7) for (int y = 1; y <= 8; y++) if (get(x, y, z).equals("air") && hn(x, y, z) < .9) set(x, y, z, "purple_stained_glass_pane");
        }
        for (int k = 0; k < 5; k++) ell(cx + cos(k * 1.3) * 3, 15 + k * .6, cz + sin(k * 1.3) * 3, 1.1, 1.6, 1.1, (x, y, z) -> "crying_obsidian");
    }
    /** Iron cage around a pit''')
# emit: clear the old pit cylinders first
rep('boolean[] done = new boolean[g.length]; List<String> cmds = new ArrayList<>(), lava = new ArrayList<>();',
    'boolean[] done = new boolean[g.length]; List<String> cmds = new ArrayList<>(), lava = new ArrayList<>();\n        if (onlyPits) for (int pi = 0; pi < 5; pi++) { double pa = -PI / 2 + pi * 2 * PI / 5; int pcx = (int) round(cos(pa) * 70), pcz = (int) round(sin(pa) * 70); for (int zz = -14; zz <= 14; zz++) { int sp = (int) floor(sqrt(14.2 * 14.2 - zz * zz)); cmds.add("execute in minecraft:sundering run fill " + (3000 + pcx - sp) + " 101 " + (3000 + pcz + zz) + " " + (3000 + pcx + sp) + " 122 " + (3000 + pcz + zz) + " minecraft:air"); } }')
# colours
rep('        if (b.startsWith("iron_bars")) return', '        if (b.contains("copper") || b.startsWith("lightning_rod")) return new int[]{190, 112, 72};\n        if (b.contains("obsidian")) return new int[]{40, 18, 64};\n        if (b.contains("purple")) return new int[]{150, 60, 200};\n        if (b.startsWith("iron_bars")) return')
# previews per pit
rep('        preview(new File(dir, "view_ice.png")', '        for (int pi = 0; pi < 5; pi++) { double pa = -PI / 2 + pi * 2 * PI / 5; double pcx = round(cos(pa) * 70), pcz = round(sin(pa) * 70); preview(new File(dir, "view_pit" + pi + ".png"), pcx * .45, 24, pcz * .45, pcx, 8, pcz, 55, 960, 540); }\n        preview(new File(dir, "view_ice.png")')
open(p, 'w', encoding='utf-8').write(s)
print('ok')
