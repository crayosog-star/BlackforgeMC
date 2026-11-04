c = r'C:\MinecraftNetwork\plugins-src\BlackForgeCore\src\main\java\net\blackforge\core\survival\Containment.java'
s = open(c, encoding='utf-8').read().replace('\r\n', '\n')

def rep(a, b):
    global s
    assert a in s, a[:70]
    s = s.replace(a, b, 1)

rep('''        for (int x = c[0] - 6; x <= c[0] + 6; x++) for (int z = c[1] - 6; z <= c[1] + 6; z++) {
            if (Math.hypot(x - c[0], z - c[1]) > 6.1) continue;
            Block b = world.getBlockAt(x, Y, z);
            if (on && b.getType() == Material.AIR) b.setType(Material.WATER, false);
            else if (!on && b.getType() == Material.WATER) b.setType(Material.AIR, false);
        }''', '''        for (int x = c[0] - 12; x <= c[0] + 12; x++) for (int z = c[1] - 12; z <= c[1] + 12; z++) {
            double r = Math.hypot(x - c[0], z - c[1]);
            if (r > 12) continue;
            Block b = world.getBlockAt(x, Y, z);
            if (on && r <= 11.2 && b.getType() == Material.AIR) b.setType(Material.WATER, false);
            else if (!on && b.getType() == Material.WATER) b.setType(Material.AIR, false);
        }''')
rep('''        melted[pit] = target + 1;
        if (target > done''', '''        melted[pit] = target + 1;
        // steam and meltwater all over the berg while it goes
        int[] c = pit(pit);
        for (int k = 0; k < 3; k++) {
            double a = rnd.nextDouble() * Math.PI * 2, r = rnd.nextDouble() * 10;
            Location l = new Location(world, c[0] + 0.5 + Math.cos(a) * r, Y + 1 + rnd.nextDouble() * 12 * (1 - f * 0.6), c[1] + 0.5 + Math.sin(a) * r);
            world.spawnParticle(Particle.CLOUD, l, 4, 0.5, 0.4, 0.5, 0.02);
            world.spawnParticle(Particle.FALLING_WATER, l, 6, 0.6, 0.2, 0.6, 0);
        }
        if (target > done''')
open(c, 'w', encoding='utf-8').write(s)

g = r'C:\MinecraftNetwork\tools\graveyard\Graveyard.java'
t = open(g, encoding='utf-8').read()
a = 'switch (i) { case 0 -> magmaCocoon(cx, cz); case 1 -> ribcage(cx, cz); case 2 -> voidShards(cx, cz); case 3 -> iceberg(cx, cz); default -> cage(cx, cz, i); }'
assert a in t
t = t.replace(a, a + '\n            if (i == 3) for (int x = cx - 18; x <= cx + 18; x++) for (int z = cz - 18; z <= cz + 18; z++) { double r = hypot(x - cx, z - cz); if (r > 12.4 && r <= 17.5 && hn(x, 9, z) < 1.0 - (r - 12.4) / 6.0) set(x, 0, z, pick(x, 2, z, "packed_ice", "blue_ice", "packed_ice", "snow_block", "ice")); }', 1)
open(g, 'w', encoding='utf-8').write(t)
print('ok')
