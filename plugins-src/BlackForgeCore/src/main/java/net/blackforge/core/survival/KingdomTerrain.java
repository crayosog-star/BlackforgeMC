package net.blackforge.core.survival;

import static net.blackforge.core.survival.InfernalLayout.*;

/**
 * The land of the Infernal Kingdom: a floating mountain caldera. A flat valley (the plaza and its roads), district terraces
 * either side, the citadel terrace in the north, and mountain walls closing it all in (tallest behind the citadel), with a
 * pass cut through the south for the Dragon Gate. Built hollow: a thin skin on top, underneath and on every exposed cliff
 * face, so the block count stays sane while it reads as solid mountain.
 */
final class KingdomTerrain {
    private KingdomTerrain() {}

    static final int RX = 215, RZ = 240, CZ = -10, NONE = Integer.MIN_VALUE;

    static double d(int x, int z) { return Math.hypot(x / (double) RX, (z - CZ) / (double) RZ); }

    private static double smooth(double t) { t = Math.max(0, Math.min(1, t)); return t * t * (3 - 2 * t); }

    /** Height of the ground surface above the plaza floor, or NONE outside the island. */
    static int height(int x, int z) {
        double d = d(x, z);
        if (d > 1.0) return NONE;
        double mt = smooth((d - 0.62) / 0.38);
        double north = Math.max(0, Math.min(1, (CZ - z) / (double) RZ * 0.5 + 0.5));
        double ridge = SkyHubBuilder.fbm(x * 0.025, z * 0.025, 11);
        int h = (int) Math.round(mt * (24 + 86 * north * north + 36 * ridge));
        if (z <= TERRACE_FRONT && Math.abs(x) <= TERRACE_HALF + 2 && z >= TERRACE_BACK - 2) return TERRACE_Y;
        if (z < TERRACE_BACK - 2) return Math.max(h, TERRACE_Y);
        if (x >= MERCH_X1 && x <= MERCH_X2 && z >= DISTRICT_Z1 && z <= DISTRICT_Z2) return DISTRICT_Y;
        if (x >= BLACK_X1 && x <= BLACK_X2 && z >= DISTRICT_Z1 && z <= DISTRICT_Z2) return DISTRICT_Y;
        if (Math.abs(x) <= 34 && z >= GATE_Z - 40) return Math.min(h, 0);
        return Math.max(0, h);
    }

    /** y of the underside. */
    static int bottom(int x, int z, int h) {
        double d = Math.min(1, d(x, z));
        double wob = SkyHubBuilder.fbm(x * 0.05, z * 0.05, 12);
        return h - 12 - (int) Math.round(78 * Math.pow(1 - d, 0.85) * (0.75 + 0.5 * wob));
    }

    private static String top(KBuild k, int x, int z, int h) {
        double n = SkyHubBuilder.fbm(x * 0.07, z * 0.07, 13);
        int r = Math.floorMod((int) (n * 100) + x * 7 + z * 13, 100);
        if (h > 14 && h > TERRACE_Y) return r < 55 ? "basalt[axis=y]" : r < 75 ? "blackstone" : r < 90 ? "smooth_basalt" : "tuff";
        if (n > 0.62 && h < 12) return "moss_block";
        return r < 38 ? "blackstone" : r < 58 ? "basalt[axis=y]" : r < 74 ? "tuff" : r < 90 ? "deepslate" : r < 96 ? "smooth_basalt" : "magma_block";
    }

    private static String face(int x, int y, int z) {
        long hh = (x * 31L) ^ (y * 131L) ^ (z * 17L);
        int r = (int) Math.floorMod(hh >> 2, 100L);
        return r < 44 ? "blackstone" : r < 66 ? "basalt[axis=y]" : r < 80 ? "deepslate" : r < 90 ? "tuff" : r < 98 ? "smooth_basalt" : "magma_block";
    }

    static void build(KBuild k) {
        int x0 = -RX - 2, x1 = RX + 2, z0 = CZ - RZ - 2, z1 = CZ + RZ + 2;
        int[][] hs = new int[x1 - x0 + 1][z1 - z0 + 1];
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) hs[x - x0][z - z0] = height(x, z);
        int[][] dx = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int x = x0 + 1; x < x1; x++)
            for (int z = z0 + 1; z < z1; z++) {
                int h = hs[x - x0][z - z0];
                if (h == NONE) continue;
                int b = bottom(x, z, h);
                int[] nh = new int[4], nb = new int[4];
                for (int i = 0; i < 4; i++) {
                    nh[i] = hs[x + dx[i][0] - x0][z + dx[i][1] - z0];
                    nb[i] = nh[i] == NONE ? Integer.MAX_VALUE : bottom(x + dx[i][0], z + dx[i][1], nh[i]);
                }
                for (int y = b; y <= h; y++) {
                    boolean need = y >= h - 1 || y <= b + 1;
                    for (int i = 0; i < 4 && !need; i++) need = nh[i] == NONE || y > nh[i] || y < nb[i];
                    if (!need) continue;
                    k.set(x, y, z, y == h ? top(k, x, z, h) : face(x, y, z));
                }
            }
        // glowing veins of lava in the cliffs: molten falls pouring off the high ground
        for (int i = 0; i < 9; i++) {
            double a = Math.PI * (1.12 + i * 0.095);
            int x = (int) Math.round(Math.cos(a) * RX * 0.74), z = (int) Math.round(CZ + Math.sin(a) * RZ * 0.74);
            lavafall(k, x, z, hs, x0, z0);
        }
    }

    private static void lavafall(KBuild k, int x, int z, int[][] hs, int x0, int z0) {
        // walk outwards from (x, z) to the rim and drop a ribbon of lava down the cliff face
        double a = Math.atan2((z - CZ) / (double) RZ, x / (double) RX);
        int px = x, pz = z;
        for (int i = 0; i < 60; i++) {
            int nx = (int) Math.round(x + Math.cos(a) * i * 1.4), nz = (int) Math.round(z + Math.sin(a) * i * 1.4);
            int h = height(nx, nz);
            if (h == NONE) break;
            px = nx; pz = nz;
        }
        int h = height(px, pz);
        if (h == NONE || h < 6) return;
        for (int y = h; y >= bottom(px, pz, h) + 4; y--) {
            k.late(px, y, pz, "lava");
            for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) { if (!k.has(px + o[0], y, pz + o[1]) && height(px + o[0], pz + o[1]) != NONE && y <= height(px + o[0], pz + o[1])) k.set(px + o[0], y, pz + o[1], "blackstone"); }
        }
    }

    /** Dark pines on the mountain slopes and in the valley corners, only where nothing else is built. */
    static void trees(KBuild k) {
        for (int i = 0; i < 700; i++) {
            int x = k.rnd.nextInt(2 * RX) - RX, z = CZ + k.rnd.nextInt(2 * RZ) - RZ;
            int h = height(x, z);
            if (h == NONE || h < 1 || d(x, z) > 0.97) continue;
            if (k.has(x, h + 1, z) || k.has(x, h + 2, z)) continue;
            if (Math.abs(x) <= 160 && z <= -30 && z >= TERRACE_BACK) continue;       // the citadel terrace
            if (Math.hypot(x - PLAZA_X, z - PLAZA_Z) < PLAZA_R + 4) continue;
            if (Math.abs(x) < 16 && z > 0) continue;                                       // the road
            if (Math.abs(x) <= 150 && z >= DISTRICT_Z1 - 4 && z <= DISTRICT_Z2 + 4) continue;
            int th = 7 + k.rnd.nextInt(7);
            for (int y = 1; y <= th; y++) k.set(x, h + y, z, "dark_oak_log");
            for (int dx = -3; dx <= 3; dx++) for (int dy = -2; dy <= 5; dy++) for (int dz = -3; dz <= 3; dz++) {
                double w = 3.2 - Math.max(0, dy) * 0.6;
                if (dx * dx + dz * dz > w * w || (dx == 0 && dz == 0 && dy < 0)) continue;
                k.put(x + dx, h + th + dy - 1, z + dz, "spruce_leaves[persistent=true]");
            }
        }
    }
}
