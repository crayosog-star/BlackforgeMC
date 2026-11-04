p = r'C:\MinecraftNetwork\plugins-src\BlackForgeCore\src\main\java\net\blackforge\core\survival\Containment.java'
s = open(p, encoding='utf-8').read().replace('\r\n', '\n')

def rep(a, b):
    global s
    assert a in s, a[:70]
    s = s.replace(a, b, 1)

rep('    private static boolean isIce(Material m) { return m == Material.ICE || m == Material.PACKED_ICE || m == Material.BLUE_ICE; }',
'''    private static boolean isIce(Material m) { return m == Material.ICE || m == Material.PACKED_ICE || m == Material.BLUE_ICE; }

    /** The blocks that make up each boss's prison: magma crust, ribcage, void shards, iceberg, storm cage. */
    private static boolean mine(int pit, Material m) {
        return switch (pit) {
            case 0 -> m == Material.MAGMA_BLOCK || m == Material.BASALT || m == Material.BLACKSTONE;
            case 1 -> m == Material.BONE_BLOCK || m == Material.CALCITE;
            case 2 -> m == Material.OBSIDIAN || m == Material.CRYING_OBSIDIAN || m == Material.PURPLE_STAINED_GLASS_PANE;
            case 3 -> isIce(m);
            default -> m == Material.IRON_BARS || m == Material.LIGHTNING_ROD || m == Material.COPPER_BLOCK;
        };
    }

    private void themeFx(int pit, Location l, boolean big) {
        int n = big ? 12 : 2;
        switch (pit) {
            case 0 -> { world.spawnParticle(Particle.FLAME, l, n, 0.4, 0.4, 0.4, 0.03); world.spawnParticle(Particle.LAVA, l, big ? 4 : 1, 0.3, 0.3, 0.3, 0); }
            case 1 -> world.spawnParticle(Particle.WHITE_ASH, l, n * 3, 0.5, 0.5, 0.5, 0.02);
            case 2 -> world.spawnParticle(Particle.REVERSE_PORTAL, l, n * 3, 0.5, 0.5, 0.5, 0.1);
            case 3 -> world.spawnParticle(Particle.SNOWFLAKE, l, n, 0.4, 0.4, 0.4, 0.03);
            default -> world.spawnParticle(Particle.ELECTRIC_SPARK, l, n * 2, 0.4, 0.4, 0.4, 0.2);
        }
    }

    private void themeSound(int pit, Location l, boolean big) {
        float v = big ? 8f : 6f;
        switch (pit) {
            case 0 -> { world.playSound(l, Sound.BLOCK_STONE_BREAK, v, 0.5f); world.playSound(l, Sound.BLOCK_LAVA_EXTINGUISH, v, 0.6f); }
            case 1 -> { world.playSound(l, Sound.BLOCK_BONE_BLOCK_BREAK, v, 0.5f); world.playSound(l, Sound.ENTITY_SKELETON_DEATH, v, 0.5f); }
            case 2 -> { world.playSound(l, Sound.BLOCK_END_PORTAL_FRAME_FILL, v, 0.5f); world.playSound(l, Sound.ENTITY_ENDERMAN_SCREAM, v * 0.5f, 0.5f); }
            case 3 -> world.playSound(l, Sound.BLOCK_GLASS_BREAK, v, 0.5f);
            default -> { world.strikeLightningEffect(l); world.playSound(l, Sound.ITEM_TRIDENT_THUNDER, v, 0.8f); }
        }
    }''')
rep('for (int x = c[0] - 14; x <= c[0] + 14; x++) for (int z = c[1] - 14; z <= c[1] + 14; z++) {\n                    if (Math.hypot(x - c[0], z - c[1]) > 14) continue;\n                    for (int y = Y; y <= Y + 22; y++) {',
    'for (int x = c[0] - 13; x <= c[0] + 13; x++) for (int z = c[1] - 13; z <= c[1] + 13; z++) {\n                    if (Math.hypot(x - c[0], z - c[1]) > 12.9) continue;\n                    for (int y = Y + 1; y <= Y + 22; y++) {')
rep('if (i == ICE_PIT ? isIce(m) : m == Material.IRON_BARS) {', 'if (mine(i, m)) {')
# restore: clear the melt puddle first
rep('        melted[pit] = 0;\n        for (Snap s : byPit.get(pit)) {',
'''        melted[pit] = 0;
        if (pit == ICE_PIT) puddle(pit, false);
        for (Snap s : byPit.get(pit)) {''')
# remove() with theme
rep('    private void remove(Snap s, boolean fx) {\n        Block b = world.getBlockAt(s.x, s.y, s.z);\n        if (b.getType() == Material.AIR) return;\n        if (fx) world.spawnParticle(Particle.BLOCK, b.getLocation().add(0.5, 0.5, 0.5), 5, 0.3, 0.3, 0.3, s.data);',
'''    /** The melted Frost Titan leaves a puddle in his pit; it is drained again when the iceberg re-forms. */
    private void puddle(int pit, boolean on) {
        int[] c = pit(pit);
        for (int x = c[0] - 6; x <= c[0] + 6; x++) for (int z = c[1] - 6; z <= c[1] + 6; z++) {
            if (Math.hypot(x - c[0], z - c[1]) > 6.1) continue;
            Block b = world.getBlockAt(x, Y, z);
            if (on && b.getType() == Material.AIR) b.setType(Material.WATER, false);
            else if (!on && b.getType() == Material.WATER) b.setType(Material.AIR, false);
        }
    }

    private void remove(int pit, Snap s, boolean fx) {
        Block b = world.getBlockAt(s.x, s.y, s.z);
        if (b.getType() == Material.AIR) return;
        if (fx) { Location at = b.getLocation().add(0.5, 0.5, 0.5); world.spawnParticle(Particle.BLOCK, at, 5, 0.3, 0.3, 0.3, s.data); themeFx(pit, at, false); }''')
rep('''        if (pit == ICE_PIT) {   // the iceberg only cracks here; the real work is the melt
            world.playSound(l, Sound.BLOCK_GLASS_BREAK, v, 0.5f);''', '''XX''') if False else None
rep('''        if (pit == ICE_PIT) {   // the iceberg only cracks here; the real work is the melt
            world.playSound(l, Sound.BLOCK_GLASS_BREAK, 6f, 0.5f);
            world.spawnParticle(Particle.SNOWFLAKE, l, 60, 5, 5, 5, 0.05);
            return;
        }''', '''        if (pit == ICE_PIT) {   // the iceberg only cracks here; the real work is the melt
            themeSound(pit, l, false);
            world.spawnParticle(Particle.SNOWFLAKE, l, 60, 5, 5, 5, 0.05);
            return;
        }''')
rep('        stagger(out, 12, true);\n        world.playSound(l, Sound.BLOCK_IRON_TRAPDOOR_OPEN, 6f, 0.5f);',
    '        stagger(pit, out, 12, true);\n        themeSound(pit, l, false);')
rep('        stagger(new ArrayList<>(byPit.get(pit)), 6, true);\n        int[] c = pit(pit);\n        Location l = new Location(world, c[0] + 0.5, Y + 6, c[1] + 0.5);\n        world.playSound(l, pit == ICE_PIT ? Sound.BLOCK_GLASS_BREAK : Sound.ENTITY_IRON_GOLEM_DEATH, 8f, 0.6f);',
    '        stagger(pit, new ArrayList<>(byPit.get(pit)), 6, true);\n        int[] c = pit(pit);\n        Location l = new Location(world, c[0] + 0.5, Y + 6, c[1] + 0.5);\n        themeSound(pit, l, true);\n        themeFx(pit, l, true);\n        if (pit == ICE_PIT) Bukkit.getScheduler().runTaskLater(plugin, () -> puddle(pit, true), 10L);')
rep('            remove(s, false);\n            if (i % 7 == 0) {', '            remove(pit, s, false);\n            if (i % 7 == 0) {')
rep('    private void stagger(List<Snap> list, int ticks, boolean fx) {', '    private void stagger(int pit, List<Snap> list, int ticks, boolean fx) {')
rep('remove(list.get(i[0]), fx && i[0] % 3 == 0);', 'remove(pit, list.get(i[0]), fx && i[0] % 3 == 0);')
open(p, 'w', encoding='utf-8').write(s)
print('ok')
