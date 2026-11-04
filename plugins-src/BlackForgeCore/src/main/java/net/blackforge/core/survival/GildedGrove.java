package net.blackforge.core.survival;

import net.blackforge.core.build.Canvas;

import java.util.Random;

/**
 * The Gilded Grove: a luxurious forest island where /warp shop lands, floating just above the caldera's south-west ridge
 * and joined to the Merchant District by a rising bridge. Spawn upgrade phase 4: {@link SpawnUpgrade} only places these
 * blocks where the world has air, and undo rebuilds this same canvas, so everything here must stay deterministic (fixed
 * seeds only). No fire, lava or campfires among the trees, every leaf is persistent, and nothing that keeps growing.
 */
public final class GildedGrove {
    private GildedGrove() {}

    static final int CX = KingdomLayout.GROVE_X, CZ = KingdomLayout.GROVE_Z, R = KingdomLayout.GROVE_R, G = KingdomLayout.GROVE_Y;

    private static final String LEAF = "azalea_leaves[persistent=true]", BLOOM = "flowering_azalea_leaves[persistent=true]";
    private static final String BRICK = "polished_blackstone_bricks", GOLD = "gold_block", QUARTZ = "smooth_quartz";

    // where the zones sit, relative to the island's centre (+z is south)
    private static final int PAV_R = 10, POOL_X = CX + 22, GARDEN_X = CX - 22, TREE_Z = CZ + 22;

    private static String mc(String s) { return "minecraft:" + s; }
    private static void set(Canvas c, int x, int y, int z, String b) { c.set(x, y, z, mc(b)); }
    private static void late(Canvas c, int x, int y, int z, String b) { c.setLate(x, y, z, mc(b)); }

    public static Canvas build() {
        Canvas c = new Canvas();
        ground(c);
        bridge(c);
        avenue(c);
        spokes(c);
        pavilion(c);
        pool(c);
        garden(c);
        greatTree(c);
        grove(c);
        plants(c);
        return c;
    }

    private static double edge(double angle) { return R + 1.2 * Math.sin(angle * 5 + 1) + 0.6 * Math.sin(angle * 11); }

    private static double dist(int x, int z) { return Math.hypot(x - CX, z - CZ); }

    /** On the island's top, not on the rim. */
    private static boolean inside(int x, int z, double margin) {
        return dist(x, z) <= edge(Math.atan2(z - CZ, x - CX)) - margin;
    }

    /** Ground kept clear of trees and plants: the paths, the pavilion, the pool, the garden and the great tree's roots. */
    private static boolean reserved(int x, int z, int pad) {
        return Math.abs(x - CX) <= 3 + pad && z < CZ + 18 + pad                 // avenue and the south path
                || Math.abs(z - CZ) <= 2 + pad && Math.abs(x - CX) <= 24        // east and west paths
                || dist(x, z) <= PAV_R + 2 + pad
                || Math.hypot(x - POOL_X, z - CZ) <= 10 + pad
                || Math.abs(x - GARDEN_X) <= 8 + pad && Math.abs(z - CZ) <= 9 + pad
                || Math.hypot(x - CX, z - TREE_Z) <= 6 + pad;
    }

    // ------------------------------------------------------------------ the island

    private static void ground(Canvas c) {
        Random rnd = new Random(4101);
        for (int x = CX - R - 3; x <= CX + R + 3; x++) for (int z = CZ - R - 3; z <= CZ + R + 3; z++) {
            double d = dist(x, z), e = edge(Math.atan2(z - CZ, x - CX));
            if (d > e) continue;
            boolean rim = d > e - 1.4;
            boolean lamp = !rim && Math.floorMod(x + 3 * z, 17) == 0;            // soft light from under the moss
            set(c, x, G, z, rim ? BRICK : lamp ? "verdant_froglight[axis=y]" : "moss_block");
            int depth = (int) Math.round(3 + Math.pow(1 - d / e, 0.65) * 15 + rnd.nextInt(2));
            for (int i = 1; i <= depth; i++) {
                String b = i <= 2 ? "rooted_dirt" : i < depth - 1 ? (rnd.nextInt(5) == 0 ? "deepslate" : "blackstone") : "basalt[axis=y]";
                set(c, x, G - i, z, b);
            }
            if (rim) {   // a low clipped hedge round the edge, open where the bridge lands
                if (!(Math.abs(x - CX) <= 2 && z < CZ)) { set(c, x, G + 1, z, "polished_blackstone_wall"); late(c, x, G + 2, z, "moss_carpet"); }
            }
        }
    }

    /** From the Merchant District's west edge: 40 blocks climbing 20, a level run over the ridge, then south onto the island. */
    private static void bridge(Canvas c) {
        int z0 = KingdomLayout.GROVE_BRIDGE_Z, xs = KingdomLayout.GROVE_BRIDGE_X, ramp = 40;
        for (int x = xs; x >= CX + 2 - 4; x--) {
            int i = xs - x, h = Math.min(i, ramp) / 2, y = SurvivalLayout.Y + h;
            boolean stair = i < ramp && i % 2 == 1;
            for (int s = -2; s <= 2; s++) {
                int z = z0 + s;
                boolean opening = s == 2 && x >= CX - 1;    // the south rail stops where the bridge turns onto the island
                if (Math.abs(s) == 2 && !opening) {
                    set(c, x, y, z, BRICK);
                    set(c, x, y + 1, z, "polished_blackstone_wall");
                    if (stair) set(c, x, y + 2, z, "polished_blackstone_wall");
                } else {
                    set(c, x, y, z, s == 0 ? "gilded_blackstone" : "polished_deepslate");
                    if (stair) set(c, x, y + 1, z, "polished_blackstone_brick_stairs[facing=west]");
                }
                set(c, x, y - 1, z, "blackstone");
            }
            if (i % 8 == 4) for (int s : new int[]{-2, 2}) {
                set(c, x, y + 2, z0 + s, "polished_blackstone_wall");
                set(c, x, y + 3, z0 + s, GOLD);
                late(c, x, y + 4, z0 + s, "lantern");
            }
            if (i % 12 == 0) for (int d = 2; d <= 14; d++) set(c, x, y - d, z0, "blackstone");
        }
        // the turn south, down onto the island's top
        for (int z = z0 - 2; z <= CZ - R + 2; z++) for (int x = CX - 2; x <= CX + 2; x++) {
            boolean side = Math.abs(x - CX) == 2;
            set(c, x, G, z, side ? BRICK : x == CX ? "gilded_blackstone" : "polished_deepslate");
            set(c, x, G - 1, z, "blackstone");
            if (side && z > z0 + 2 && z < CZ - R) set(c, x, G + 1, z, "polished_blackstone_wall");
        }
        // close the far (west) end of the level run
        for (int s = -2; s <= 2; s++) { set(c, CX - 3, G, z0 + s, BRICK); set(c, CX - 3, G + 1, z0 + s, "polished_blackstone_wall"); }
    }

    // ------------------------------------------------------------------ the formal parts

    private static void paving(Canvas c, int x, int z, boolean trim) {
        set(c, x, G, z, trim ? GOLD : BRICK);
    }

    /** The gold-edged avenue from the bridge to the pavilion, its arch and lamps. */
    private static void avenue(Canvas c) {
        int from = CZ - R + 1, to = CZ - PAV_R;
        for (int z = from; z <= to; z++) {
            for (int x = CX - 2; x <= CX + 2; x++) paving(c, x, z, Math.abs(x - CX) == 2);
            if (z < to - 1) for (int x : new int[]{CX - 4, CX + 4}) {     // clipped hedges along both sides
                set(c, x, G + 1, z, LEAF);
                if ((z - from) % 6 != 0) set(c, x, G + 2, z, (z % 2 == 0) ? BLOOM : LEAF);
            }
            if ((z - from) % 6 == 0) for (int x : new int[]{CX - 3, CX + 3}) {
                set(c, x, G, z, "chiseled_polished_blackstone");
                set(c, x, G + 1, z, "polished_blackstone_wall");
                set(c, x, G + 2, z, "polished_blackstone_wall");
                set(c, x, G + 3, z, GOLD);
                late(c, x, G + 4, z, "lantern");
            }
        }
        // the entrance arch over the warp point
        int az = KingdomLayout.GROVE_WARP_Z - 3;
        for (int x : new int[]{CX - 4, CX + 4}) {
            for (int y = 1; y <= 7; y++) set(c, x, G + y, az, y % 3 == 0 ? GOLD : QUARTZ);
            set(c, x, G, az, "gilded_blackstone");
        }
        for (int x = CX - 4; x <= CX + 4; x++) {
            int y = G + 8 + (Math.abs(x - CX) <= 1 ? 1 : 0);
            set(c, x, y, az, x == CX ? "emerald_block" : GOLD);
            set(c, x, y + 1, az, BLOOM);
        }
    }

    private static void spokes(Canvas c) {
        for (int z = CZ + PAV_R; z <= TREE_Z - 5; z++) for (int x = CX - 1; x <= CX + 1; x++) paving(c, x, z, Math.abs(x - CX) == 1 && z % 4 == 0);
        for (int x = CX - 15; x <= CX + 14; x++) {
            if (dist(x, CZ) <= PAV_R) continue;
            for (int z = CZ - 1; z <= CZ + 1; z++) paving(c, x, z, Math.abs(z - CZ) == 1 && x % 4 == 0);
        }
    }

    /** The heart of the place: quartz columns, a gold-ribbed glass dome and three stands (Daily Shop, Auction House, the Trader who buys farmed goods). */
    private static void pavilion(Canvas c) {
        int r = PAV_R;
        for (int x = CX - r; x <= CX + r; x++) for (int z = CZ - r; z <= CZ + r; z++) {
            double d = dist(x, z);
            if (d > r + 0.4) continue;
            set(c, x, G, z, d > r - 1 ? GOLD : d < 1.5 ? "emerald_block" : d < 3 ? GOLD : ((x + z) & 1) == 0 ? QUARTZ : "quartz_bricks");
            set(c, x, G - 1, z, "verdant_froglight[axis=y]");
        }
        for (int i = 0; i < 8; i++) {   // eight columns, leaving a wide gap on all four paths
            double a = Math.PI / 8 + i * Math.PI / 4;
            int x = CX + (int) Math.round(Math.cos(a) * (r - 1)), z = CZ + (int) Math.round(Math.sin(a) * (r - 1));
            set(c, x, G, z, "gilded_blackstone");
            for (int y = 1; y <= 8; y++) set(c, x, G + y, z, y == 1 || y == 8 ? "chiseled_quartz_block" : "quartz_pillar[axis=y]");
        }
        int base = G + 9;
        for (int x = CX - r - 1; x <= CX + r + 1; x++) for (int z = CZ - r - 1; z <= CZ + r + 1; z++) for (int y = 0; y <= r + 1; y++) {
            double d = Math.sqrt(Math.pow(x - CX, 2) + Math.pow(z - CZ, 2) + Math.pow(y * 1.05, 2));
            if (d > r + 0.5 || d < r - 0.6) continue;
            double a = Math.atan2(z - CZ, x - CX);
            boolean rib = Math.abs(((a + Math.PI / 8) / (Math.PI / 4)) - Math.round((a + Math.PI / 8) / (Math.PI / 4))) < 0.13;
            set(c, x, base + y, z, y == 0 || rib ? GOLD : "lime_stained_glass");
        }
        set(c, CX, base + 11, CZ, GOLD);
        set(c, CX, base + 12, CZ, "emerald_block");
        for (int y = base + 10; y >= base + 5; y--) if (y > base + 5) set(c, CX, y, CZ, "iron_chain[axis=y]");
        late(c, CX, base + 5, CZ, "lantern[hanging=true]");
        // the stands: a gold plinth either side of each NPC
        for (int[] s : new int[][]{{CX - 7, CZ - 3}, {CX - 7, CZ + 3}, {CX + 7, CZ - 3}, {CX + 7, CZ + 3}, {CX - 3, CZ + 7}, {CX + 3, CZ + 7}}) {
            set(c, s[0], G + 1, s[1], "chiseled_quartz_block");
            late(c, s[0], G + 2, s[1], "potted_flowering_azalea_bush");
        }
    }

    /** A quartz-rimmed pool with lily pads, and a fountain falling straight into it (water only ever falls into water). */
    private static void pool(Canvas c) {
        int r = 8;
        for (int x = POOL_X - r - 1; x <= POOL_X + r + 1; x++) for (int z = CZ - r - 1; z <= CZ + r + 1; z++) {
            double d = Math.hypot(x - POOL_X, z - CZ);
            if (d > r + 0.5) continue;
            if (d > r - 1) {
                set(c, x, G, z, QUARTZ);
                set(c, x, G - 1, z, QUARTZ);
            } else {
                set(c, x, G, z, "water");
                set(c, x, G - 1, z, d < 3 ? GOLD : "dark_prismarine");
                set(c, x, G - 2, z, "blackstone");
                if (Math.floorMod(x * 7 + z * 13, 11) == 0 && d > 3) late(c, x, G + 1, z, "lily_pad");
            }
        }
        // the fountain: a column beside the water with a closed basin on top, over a single open spout
        int fx = POOL_X, fz = CZ, top = G + 6;
        for (int y = G - 1; y <= top; y++) set(c, fx + 1, y, fz, y == top ? GOLD : QUARTZ);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            set(c, fx + dx, top + 1, fz + dz, GOLD);
            if (dx != 0 && dz != 0 || dx == 1) set(c, fx + dx, top, fz + dz, GOLD);
        }
        set(c, fx - 1, top, fz, GOLD);
        set(c, fx, top, fz - 1, GOLD);
        set(c, fx, top, fz + 1, GOLD);
        set(c, fx, top + 1, fz, "water");
        for (int y = top; y > G; y--) set(c, fx, y, fz, "water[level=8]");
    }

    private static void garden(Canvas c) {
        int x1 = GARDEN_X - 7, x2 = GARDEN_X + 7, z1 = CZ - 8, z2 = CZ + 8;
        for (int x = x1; x <= x2; x++) for (int z = z1; z <= z2; z++) {
            boolean ring = x == x1 || x == x2 || z == z1 || z == z2;
            boolean door = x == x2 && Math.abs(z - CZ) <= 1;
            if (ring && !door) for (int y = 1; y <= 3; y++) set(c, x, G + y, z, y == 3 && (x + z) % 3 == 0 ? BLOOM : LEAF);
            else if (!ring && (Math.abs(x - GARDEN_X) <= 1 || Math.abs(z - CZ) <= 1)) set(c, x, G, z, "polished_blackstone");
        }
        set(c, GARDEN_X, G, CZ, GOLD);
        set(c, GARDEN_X, G + 1, CZ, "chiseled_quartz_block");
        set(c, GARDEN_X, G + 2, CZ, GOLD);
        late(c, GARDEN_X, G + 3, CZ, "lantern");
        // benches in the four corners, each with a little hedge nook
        for (int sx : new int[]{-1, 1}) for (int sz : new int[]{-1, 1}) {
            int bx = GARDEN_X + sx * 5, bz = CZ + sz * 5;
            set(c, bx, G + 1, bz + sz, "dark_oak_stairs[facing=" + (sz < 0 ? "north" : "south") + "]");
            set(c, bx + sx, G + 1, bz + sz, "dark_oak_stairs[facing=" + (sz < 0 ? "north" : "south") + "]");
            set(c, bx - sx, G + 1, bz + sz, "dark_oak_stairs[facing=" + (sz < 0 ? "north" : "south") + "]");
        }
    }

    // ------------------------------------------------------------------ the trees

    private static void canopyBlob(Canvas c, Random rnd, double cx, double cy, double cz, double r) {
        for (int x = (int) (cx - r - 1); x <= cx + r + 1; x++) for (int z = (int) (cz - r - 1); z <= cz + r + 1; z++) for (int y = (int) (cy - r); y <= cy + r; y++) {
            double dy = (y - cy) * 1.35, d = Math.sqrt(Math.pow(x - cx, 2) + dy * dy + Math.pow(z - cz, 2));
            if (d > r - rnd.nextDouble() * 0.9 || c.isSet(x, y, z)) continue;
            String leaf = rnd.nextInt(5) == 0 ? BLOOM : LEAF;
            if (rnd.nextInt(90) == 0 && d < r - 1.5) leaf = "shroomlight";
            set(c, x, y, z, leaf);
        }
    }

    /** The landmark: a thick dark oak with buttress roots, spreading boughs and a small gold shrine at its foot. */
    private static void greatTree(Canvas c) {
        Random rnd = new Random(4102);
        int tx = CX, tz = TREE_Z, h = 26;
        for (int y = 0; y <= h; y++) {
            double r = 3.6 - 2.1 * y / h + (y < 4 ? (4 - y) * 0.55 : 0);
            for (int x = tx - 6; x <= tx + 6; x++) for (int z = tz - 6; z <= tz + 6; z++)
                if (Math.hypot(x - tx, z - tz) <= r) set(c, x, G + 1 + y, z, "dark_oak_wood");
        }
        for (int i = 0; i < 7; i++) {   // boughs, each ending in a cloud of leaves
            double a = i * 2 * Math.PI / 7 + 0.4, len = 9 + (i % 3) * 1.5;
            int y0 = G + 14 + (i % 3) * 3;
            double ex = tx, ey = y0, ez = tz;
            for (double t = 0; t <= len; t += 0.5) {
                ex = tx + Math.cos(a) * t; ez = tz + Math.sin(a) * t; ey = y0 + t * 0.55;
                set(c, (int) Math.round(ex), (int) Math.round(ey), (int) Math.round(ez), "dark_oak_wood");
            }
            canopyBlob(c, rnd, ex, ey + 1, ez, 5.5);
        }
        canopyBlob(c, rnd, tx, G + h + 2, tz, 8.5);
        // the shrine, on the north face
        int sz = tz - 6;
        set(c, tx, G + 1, sz, GOLD);
        set(c, tx - 1, G + 1, sz, "gilded_blackstone");
        set(c, tx + 1, G + 1, sz, "gilded_blackstone");
        set(c, tx, G + 2, sz, "emerald_block");
        late(c, tx, G + 3, sz, "lantern");
    }

    private static void tree(Canvas c, Random rnd, int x, int z) {
        int h = 7 + rnd.nextInt(5);
        for (int y = 1; y <= h; y++) set(c, x, G + y, z, "dark_oak_log[axis=y]");
        canopyBlob(c, rnd, x, G + h + 1, z, 4.2 + rnd.nextDouble() * 1.3);
    }

    private static void grove(Canvas c) {
        Random rnd = new Random(4103);
        // the avenue's framing pairs first, then the woods fill what is left
        for (int z : new int[]{CZ - 25, CZ - 17}) for (int x : new int[]{CX - 9, CX + 9}) tree(c, rnd, x, z);
        int[][] placed = new int[48][2];
        int n = 0;
        for (int tries = 0; tries < 600 && n < placed.length; tries++) {
            int x = CX - R + rnd.nextInt(2 * R + 1), z = CZ - R + rnd.nextInt(2 * R + 1);
            if (!inside(x, z, 4) || reserved(x, z, 3)) continue;
            boolean close = false;
            for (int i = 0; i < n; i++) if (Math.hypot(x - placed[i][0], z - placed[i][1]) < 8) close = true;
            if (close || Math.abs(x - CX) == 9 && z < CZ) continue;
            placed[n][0] = x; placed[n][1] = z; n++;
            tree(c, rnd, x, z);
        }
    }

    /** Ground cover: everything here sits on moss and stays the size it is placed. */
    private static void plants(Canvas c) {
        Random rnd = new Random(4104);
        for (int x = CX - R; x <= CX + R; x++) for (int z = CZ - R; z <= CZ + R; z++) {
            if (!inside(x, z, 2) || reserved(x, z, 0) || c.isSet(x, G + 1, z)) continue;
            String floor = c.get(x, G, z);
            if (floor != null && floor.contains("verdant_froglight")) { late(c, x, G + 1, z, "moss_carpet"); continue; }   // hides the light source
            if (!mc("moss_block").equals(floor)) continue;
            int roll = rnd.nextInt(100);
            String p = roll < 18 ? "moss_carpet" : roll < 26 ? "wildflowers" : roll < 34 ? "pink_petals" : roll < 40 ? "leaf_litter"
                    : roll < 44 ? "firefly_bush" : roll < 47 ? "flowering_azalea" : roll < 50 ? "azalea" : roll < 54 ? "short_grass" : roll < 56 ? "fern" : null;
            if (p != null) late(c, x, G + 1, z, p);
        }
    }
}
