package net.alloymc.core.survival;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Random;
import java.util.function.Supplier;

/**
 * Picks a landing column for the Drop Zone shafts that is open ground: flat for a few blocks around and
 * solid underneath (a roof has air below it), so nobody lands on a building and then takes fall damage
 * jumping off it.
 */
final class SafeDrop {
    private SafeDrop() {}

    /** A cached set of landing spots (checking columns loads chunks, so it isn't redone for every drop). */
    static final class Pool {
        private final java.util.List<Location> spots = new java.util.ArrayList<>();
        private long builtAt;
        private final Supplier<Location> finder;

        Pool(Supplier<Location> finder) { this.finder = finder; }

        Location next(Random rnd) {
            long now = System.currentTimeMillis();
            if (spots.isEmpty() || now - builtAt > 10 * 60_000L) {
                spots.clear();
                for (int i = 0; i < 24; i++) { Location l = finder.get(); if (l != null) spots.add(l); }
                builtAt = now;
            }
            Location l = spots.get(rnd.nextInt(spots.size())).clone();
            l.setYaw(rnd.nextFloat() * 360);
            return l;
        }
    }

    /**
     * @param candidate random (x, z) in the zone (only x/z are used)
     * @param top       highest y to scan down from
     * @param maxGround highest y open ground can be at
     * @param height    blocks above the ground to drop players from
     */
    static Location find(World w, Supplier<Location> candidate, int top, int maxGround, int height, Random rnd) {
        Location fallback = null;
        for (int i = 0; i < 80; i++) {
            Location c = candidate.get();
            int x = c.getBlockX(), z = c.getBlockZ();
            int g = ground(w, x, z, top);
            if (g == Integer.MIN_VALUE || g > maxGround) continue;
            if (fallback == null) { fallback = c.clone(); fallback.setX(x + 0.5); fallback.setZ(z + 0.5); fallback.setY(g + height); }
            if (!safeColumn(w, x, z, g)) continue;
            boolean flat = true;
            for (int dx = -2; dx <= 2 && flat; dx++)
                for (int dz = -2; dz <= 2 && flat; dz++) {
                    int n = ground(w, x + dx, z + dz, top);
                    if (n == Integer.MIN_VALUE || Math.abs(n - g) > 1) flat = false;
                }
            if (!flat) continue;
            Location l = c.clone();
            l.setX(x + 0.5);
            l.setZ(z + 0.5);
            l.setY(g + height);
            return l;
        }
        if (fallback != null) return fallback;   // the zones are built so this practically never happens
        Location c = candidate.get();
        c.setY(top);
        return c;
    }

    /** Highest solid block (barrier roofs ignored), or MIN_VALUE if none. */
    static int ground(World w, int x, int z, int top) {
        for (int y = top; y > top - 120; y--) {
            Block b = w.getBlockAt(x, y, z);
            Material m = b.getType();
            if (m == Material.BARRIER || m.isAir()) continue;
            if (m == Material.LAVA || m == Material.WATER) return y;
            if (m.isSolid()) return y;
        }
        return Integer.MIN_VALUE;
    }

    /** Ground block is safe to stand on and has solid ground under it (not a roof or a ledge). */
    private static boolean safeColumn(World w, int x, int z, int g) {
        Material topM = w.getBlockAt(x, g, z).getType();
        if (topM == Material.LAVA || topM == Material.WATER || topM == Material.MAGMA_BLOCK || topM == Material.CACTUS
                || topM == Material.CAMPFIRE || topM == Material.SOUL_CAMPFIRE || topM.name().endsWith("_SLAB") || topM.name().contains("FENCE")
                || topM.name().contains("WALL") || topM == Material.CHEST || topM == Material.BARREL) return false;
        for (int d = 1; d <= 3; d++) if (!w.getBlockAt(x, g - d, z).getType().isSolid()) return false;
        return true;
    }
}
