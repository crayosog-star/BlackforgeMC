package net.alloymc.core.hub;

import net.alloymc.core.build.Canvas;

import java.util.Random;

import static net.alloymc.core.hub.HubLayout.Y;

/**
 * A dark medieval curtain wall ringing the whole hub, far out past the island: scenery only, nobody can reach it.
 * Sixteen round towers with slate cone roofs, battlements, arrow slits glowing faintly from inside, all standing on a
 * jagged floating foundation. The ring between the island and the wall is the dragons' sky (DragonFlight keeps them
 * within about 143 blocks of the centre, the wall starts at {@link #INNER}).
 *
 * Built hollow (just the faces) to keep the block count down - from the island it reads as solid stone.
 */
public final class CastleBuilder {
    public static final int INNER = 150, OUTER = 158;
    static final int BASE = Y - 38, TOP = Y + 30, TOWER_R = 7, TOWER_TOP = Y + 44, TOWERS = 16;
    /** How far out the castle reaches, for the wipe, the biome paint and the chunks to load. */
    public static final int EXTENT = OUTER + TOWER_R + 6;

    private static final String[] STONE = {"minecraft:polished_blackstone_bricks", "minecraft:polished_blackstone_bricks",
            "minecraft:cracked_polished_blackstone_bricks", "minecraft:deepslate_bricks", "minecraft:deepslate_bricks",
            "minecraft:cracked_deepslate_bricks", "minecraft:blackstone"};
    private static final String TRIM = "minecraft:polished_deepslate", ROOF = "minecraft:deepslate_tiles",
            SLIT = "minecraft:black_stained_glass", GLOW = "minecraft:magma_block", ROCK = "minecraft:blackstone";

    private final Canvas c;
    private final Random rnd = new Random(0xCA57L);

    private CastleBuilder(Canvas c) { this.c = c; }

    /** Just the castle, for pasting into a hub built before it existed. */
    public static Canvas build() {
        Canvas c = new Canvas();
        addTo(c);
        return c;
    }

    static void addTo(Canvas c) {
        CastleBuilder b = new CastleBuilder(c);
        b.wall();
        for (int i = 0; i < TOWERS; i++) b.tower(i * Math.PI * 2 / TOWERS);
    }

    private String stone(int x, int y, int z) {
        long h = (x * 73856093L) ^ (y * 19349663L) ^ (z * 83492791L);
        return STONE[(int) Math.floorMod(h, (long) STONE.length)];
    }

    private void put(int x, int y, int z, String b) { if (!c.isSet(x, y, z)) c.set(x, y, z, b); }

    // ------------------------------------------------------------------ curtain wall

    private void wall() {
        int e = OUTER + 1;
        for (int x = -e; x <= e; x++)
            for (int z = -e; z <= e; z++) {
                double r = Math.hypot(x + 0.5, z + 0.5);
                if (r < INNER - 0.5 || r > OUTER + 0.5) continue;
                boolean inner = r < INNER + 1.0, outer = r > OUTER - 1.0;
                double ang = Math.atan2(z + 0.5, x + 0.5);
                // the underside drops away in a jagged taper, like the wall was torn out of a mountain
                int drop = (int) Math.round(10 + 8 * Math.sin(ang * 23) + 6 * Math.sin(ang * 61 + 1) + 4 * Math.cos(r * 0.9));
                int bottom = BASE - Math.max(2, drop);
                for (int y = bottom; y <= TOP; y++) {
                    boolean face = inner || outer || y == TOP || y == BASE;
                    if (y < BASE) { if (inner || outer || y == bottom) put(x, y, z, ROCK); continue; }
                    if (!face) continue;
                    String b = stone(x, y, z);
                    if ((y - BASE) % 12 == 0) b = TRIM;                                       // string courses
                    // arrow slits on the side facing the hub, faintly lit from inside
                    if (inner && y > Y - 10 && y < TOP - 4 && (y - Y) % 9 >= 6 && slit(ang)) b = (y - Y) % 9 == 7 ? GLOW : SLIT;
                    put(x, y, z, b);
                }
                // battlements on both edges of the wall walk
                if ((inner || outer) && Math.floorMod((int) Math.round(ang * INNER / 2.0), 2) == 0) {
                    put(x, TOP + 1, z, stone(x, TOP + 1, z));
                    put(x, TOP + 2, z, TRIM);
                } else if (inner || outer) put(x, TOP + 1, z, TRIM);
            }
    }

    /** Arrow slits every few blocks along the wall, never right next to a tower. */
    private boolean slit(double ang) {
        double step = Math.PI * 2 / TOWERS;
        double fromTower = Math.abs(((ang % step) + step) % step - step / 2);
        if (fromTower > step / 2 - 0.06) return false;
        return Math.floorMod((int) Math.round(ang * INNER / 7.0), 2) == 0 && Math.abs(Math.sin(ang * INNER / 7.0 * Math.PI)) < 0.35;
    }

    // ------------------------------------------------------------------ towers

    private void tower(double ang) {
        double cr = (INNER + OUTER) / 2.0;
        int cx = (int) Math.round(Math.cos(ang) * cr), cz = (int) Math.round(Math.sin(ang) * cr);
        int bottom = BASE - 22 - rnd.nextInt(8);
        for (int x = -TOWER_R - 2; x <= TOWER_R + 2; x++)
            for (int z = -TOWER_R - 2; z <= TOWER_R + 2; z++) {
                double r = Math.hypot(x, z);
                // the foundation narrows to a point below the tower
                for (int y = bottom; y < BASE; y++) {
                    double t = (double) (y - bottom) / (BASE - bottom), rr = TOWER_R * Math.pow(t, 0.7);
                    if (r <= rr && r > rr - 1.6) put(cx + x, y, cz + z, ROCK);
                }
                if (r > TOWER_R + 0.5) continue;
                boolean shell = r > TOWER_R - 1.0;
                for (int y = BASE; y <= TOWER_TOP; y++) {
                    if (!shell && y != BASE && y != TOWER_TOP) continue;
                    String b = stone(cx + x, y, cz + z);
                    if ((y - BASE) % 14 == 0) b = TRIM;
                    if (shell && y > TOP + 3 && y < TOWER_TOP - 3 && (y - TOP) % 6 == 3 && Math.abs(x) <= 0 && Math.abs(z) > 0) b = GLOW;
                    if (shell && y > TOP + 3 && y < TOWER_TOP - 3 && (y - TOP) % 6 == 3 && Math.abs(z) <= 0 && Math.abs(x) > 0) b = GLOW;
                    put(cx + x, y, cz + z, b);
                }
                // corbelled parapet a block wider than the tower
            }
        for (int x = -TOWER_R - 1; x <= TOWER_R + 1; x++)
            for (int z = -TOWER_R - 1; z <= TOWER_R + 1; z++) {
                double r = Math.hypot(x, z);
                if (r > TOWER_R + 1.5 || r < TOWER_R - 0.5) continue;
                put(cx + x, TOWER_TOP, cz + z, TRIM);
                if (Math.floorMod((int) Math.round(Math.atan2(z, x) * TOWER_R), 2) == 0) put(cx + x, TOWER_TOP + 1, cz + z, stone(cx + x, TOWER_TOP + 1, cz + z));
            }
        // a steep slate cone roof with an iron spire
        int roofH = 14;
        for (int k = 0; k <= roofH; k++) {
            double rr = (TOWER_R + 0.5) * (1 - (double) k / roofH);
            int y = TOWER_TOP + 2 + k;
            for (int x = -TOWER_R - 1; x <= TOWER_R + 1; x++)
                for (int z = -TOWER_R - 1; z <= TOWER_R + 1; z++) {
                    double r = Math.hypot(x, z);
                    if (r <= rr && r > rr - 1.5) put(cx + x, y, cz + z, ROOF);
                }
        }
        for (int y = TOWER_TOP + 2 + roofH; y <= TOWER_TOP + roofH + 6; y++) put(cx, y, cz, "minecraft:iron_chain[axis=y]");
        put(cx, TOWER_TOP + roofH + 7, cz, "minecraft:lightning_rod");
    }
}
