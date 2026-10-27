package net.alloymc.core.arcade.core;

import net.alloymc.core.build.Canvas;

import java.util.Random;

/** Drawing helpers for built-in arcade maps. Coordinates are relative to the map origin. */
public class MapKit {
    public final Canvas c = new Canvas();
    public final Random rnd;

    public MapKit(long seed) { this.rnd = new Random(seed); }

    public static String b(String id) { return id.contains(":") ? id : "minecraft:" + id; }

    public void set(int x, int y, int z, String block) { c.set(x, y, z, b(block)); }
    public void late(int x, int y, int z, String block) { c.setLate(x, y, z, b(block)); }
    public boolean isSet(int x, int y, int z) { return c.isSet(x, y, z); }
    public String get(int x, int y, int z) { return c.get(x, y, z); }

    public void fill(int x1, int y1, int z1, int x2, int y2, int z2, String block) { c.fill(x1, y1, z1, x2, y2, z2, b(block)); }
    public void disc(int cx, int y, int cz, double r, String block) { c.disc(cx, y, cz, r, b(block)); }
    public void ring(int cx, int y, int cz, double in, double out, String block) { c.ring(cx, y, cz, in, out, b(block)); }

    /** Hollow box walls (no floor/ceiling). */
    public void walls(int x1, int y1, int z1, int x2, int y2, int z2, String block) {
        for (int y = y1; y <= y2; y++) {
            for (int x = x1; x <= x2; x++) { set(x, y, z1, block); set(x, y, z2, block); }
            for (int z = z1; z <= z2; z++) { set(x1, y, z, block); set(x2, y, z, block); }
        }
    }

    /** Pick one of several blocks, weighted by repetition ("stone","stone","andesite"). */
    public String pick(String... blocks) { return blocks[rnd.nextInt(blocks.length)]; }

    /**
     * Floating island: a flat top of {@code top} with a few blocks of {@code mid} under it and a tapered
     * rocky underside of {@code bottom}, like a sky island.
     */
    public void island(int cx, int y, int cz, double r, String top, String mid, String bottom) {
        int ri = (int) Math.ceil(r);
        for (int x = -ri; x <= ri; x++)
            for (int z = -ri; z <= ri; z++) {
                double d = Math.sqrt(x * x + z * z);
                double edge = r + (rnd.nextDouble() - 0.5) * 0.9;
                if (d > edge) continue;
                set(cx + x, y, cz + z, top);
                int depth = (int) Math.round((edge - d) * 1.1 + 1 + rnd.nextInt(2));
                for (int dy = 1; dy <= depth; dy++) set(cx + x, y - dy, cz + z, dy <= 2 ? mid : bottom);
            }
    }

    /** Simple broadleaf/conifer tree. */
    public void tree(int x, int y, int z, String log, String leaves, int height) {
        for (int i = 0; i < height; i++) set(x, y + i, z, log);
        int top = y + height;
        for (int dy = -2; dy <= 1; dy++) {
            int r = dy >= 0 ? 1 : 2;
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
                if (Math.abs(dx) == r && Math.abs(dz) == r && rnd.nextBoolean()) continue;
                if (!isSet(x + dx, top + dy, z + dz)) set(x + dx, top + dy, z + dz, leaves);
            }
        }
        set(x, top + 1, z, leaves);
    }

    public void pine(int x, int y, int z, String log, String leaves, int height) {
        for (int i = 0; i < height; i++) set(x, y + i, z, log);
        for (int i = 2; i <= height; i++) {
            int r = Math.max(0, (height - i) / 2 - (i % 2));
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++)
                if (Math.abs(dx) + Math.abs(dz) <= r + 1 && !isSet(x + dx, y + i, z + dz)) set(x + dx, y + i, z + dz, leaves);
        }
        set(x, y + height, z, leaves);
        set(x, y + height + 1, z, leaves);
    }

    /** A glass spawn cage (player stands at x, y, z); removed by the game at start. */
    public void cage(int x, int y, int z, String glass) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            set(x + dx, y - 1, z + dz, glass);
            set(x + dx, y + 3, z + dz, glass);
        }
        for (int dy = 0; dy <= 2; dy++)
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++)
                if (dx != 0 || dz != 0) set(x + dx, y + dy, z + dz, glass);
    }

    /** Smooth 2D value noise in [0, 1]. */
    public static double noise(double x, double z, long seed) {
        int xi = (int) Math.floor(x), zi = (int) Math.floor(z);
        double fx = x - xi, fz = z - zi;
        double a = hash(xi, zi, seed), b = hash(xi + 1, zi, seed), c = hash(xi, zi + 1, seed), d = hash(xi + 1, zi + 1, seed);
        double u = fx * fx * (3 - 2 * fx), v = fz * fz * (3 - 2 * fz);
        return a * (1 - u) * (1 - v) + b * u * (1 - v) + c * (1 - u) * v + d * u * v;
    }

    public static double fbm(double x, double z, long seed) {
        return noise(x / 24, z / 24, seed) * 0.6 + noise(x / 10, z / 10, seed + 7) * 0.3 + noise(x / 4, z / 4, seed + 13) * 0.1;
    }

    private static double hash(int x, int z, long seed) {
        long h = seed * 6364136223846793005L + x * 374761393L + z * 668265263L;
        h = (h ^ (h >>> 13)) * 1274126177L;
        return ((h ^ (h >>> 16)) & 0xFFFFFF) / (double) 0xFFFFFF;
    }

    /** A small room/building shell with a door gap, windows and a flat roof. */
    public void building(int x1, int y, int z1, int x2, int z2, int h, String wall, String floor, String roof, String window) {
        fill(x1, y, z1, x2, y, z2, floor);
        for (int yy = y + 1; yy <= y + h; yy++) {
            for (int x = x1; x <= x2; x++) for (int z : new int[]{z1, z2}) set(x, yy, z, yy == y + 2 && (x - x1) % 3 == 1 && x != x1 && x != x2 ? window : wall);
            for (int z = z1; z <= z2; z++) for (int x : new int[]{x1, x2}) set(x, yy, z, yy == y + 2 && (z - z1) % 3 == 1 && z != z1 && z != z2 ? window : wall);
        }
        fill(x1, y + h + 1, z1, x2, y + h + 1, z2, roof);
    }

    /** Carve air (by setting cave_air, which the paster writes as air). */
    public void clear(int x1, int y1, int z1, int x2, int y2, int z2) { fill(x1, y1, z1, x2, y2, z2, "air"); }
}
