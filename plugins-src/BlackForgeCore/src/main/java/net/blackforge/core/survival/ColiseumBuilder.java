package net.blackforge.core.survival;

import net.blackforge.core.build.BlockFont;
import net.blackforge.core.build.Canvas;

import java.util.Random;

/**
 * The BlackForge Colosseum: a dark gothic-Roman amphitheatre on its own floating rock south of the kingdom. A sand pit
 * with an anvil emblem, twelve tiers of seating, a four-storey arcaded outer wall hung with red banners, and a grand
 * frontage: a tall central gate pavilion (a vaulted entrance hall, the BLACKFORGEMC sign, an anvil-and-swords crest)
 * between two stepped wings and corner towers, with giant armoured knights and braziers flanking the red carpet.
 * Inside: the vaulted tunnel to the pit, fighter cells either side of it, and the Champion's throne above the north
 * seats. Fixed points: pit centre (CX, Y, CZ) radius {@link #PIT_R}; the forecourt (lobby) in front of the pavilion.
 */
public final class ColiseumBuilder {
    private ColiseumBuilder() {}

    public static final int CX = 0, CZ = 300, Y = SurvivalLayout.Y;
    public static final int PIT_R = 14, WALL_IN = 42, WALL_OUT = 45, RING_TOP = 36, PROM = 16;
    /** Build-time coordinates (the frontage is built facing south, then the whole thing is turned to face north, towards the caldera). */
    private static final int BF_Z = CZ + 40, BP_Z = CZ + 46, BT_Z = CZ - 39;
    /** After the turn: the wings' front face (the rankings boards hang on it), the pavilion's, and the throne. */
    public static final int FACADE_Z = CZ - 40, PAV_Z = CZ - 46, THRONE_Z = CZ + 39;

    private static String mc(String s) { return "minecraft:" + s; }
    private static void set(Canvas c, int x, int y, int z, String b) { c.set(x, Y + y, z, mc(b)); }
    private static void late(Canvas c, int x, int y, int z, String b) { c.setLate(x, Y + y, z, mc(b)); }
    private static void fill(Canvas c, int x1, int y1, int z1, int x2, int y2, int z2, String b) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) set(c, x, y, z, b);
    }
    private static String outward(int dx, int dz) { return Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? "east" : "west") : (dz > 0 ? "south" : "north"); }
    /** Dark gothic stone: mostly stone bricks and deepslate bricks, some cracked, with polished bands. */
    private static String brick(Random r, int y) {
        if (y % 12 == 0) return "polished_andesite";
        int v = r.nextInt(20);
        return v < 7 ? "deepslate_bricks" : v < 9 ? "cracked_stone_bricks" : v < 10 ? "cracked_deepslate_bricks" : "stone_bricks";
    }

    public static Canvas build() {
        Canvas c = new Canvas();
        rock(c);
        pit(c);
        stands(c);
        outerWall(c);
        frontage(c);
        tunnelAndCells(c);
        throne(c);
        forecourt(c);
        return rotate(c);
    }

    /** A half turn about the pit's centre, so the grand frontage looks north at the volcano and away from the Scrapyard. */
    private static Canvas rotate(Canvas src) {
        Canvas out = new Canvas();
        src.blocks().forEach((k, v) -> out.set(2 * CX - Canvas.kx(k), Canvas.ky(k), 2 * CZ - Canvas.kz(k), turn(v)));
        src.lateBlocks().forEach((k, v) -> out.setLate(2 * CX - Canvas.kx(k), Canvas.ky(k), 2 * CZ - Canvas.kz(k), turn(v)));
        return out;
    }

    private static String turn(String s) {
        return s.replace("facing=north", "facing=@S").replace("facing=south", "facing=north").replace("facing=@S", "facing=south")
                .replace("facing=east", "facing=@W").replace("facing=west", "facing=east").replace("facing=@W", "facing=west");
    }

    // ------------------------------------------------------------------ the rock

    private static void rock(Canvas c) {
        Random rnd = new Random(77);
        for (int x = CX - 62; x <= CX + 62; x++) for (int z = CZ - 62; z <= CZ + 86; z++) {
            int dx = x - CX, dz = z - CZ;
            double r = Math.hypot(dx, dz), d1 = 56 - r, d2 = (Math.abs(dx) <= 48 && dz >= 28 && dz <= 84) ? Math.min(48 - Math.abs(dx), 84 - dz) : -1;
            double e = Math.max(d1, d2);
            if (e <= 0) continue;
            set(c, x, 0, z, e < 1.5 ? "polished_blackstone_bricks" : (((x >> 2) + (z >> 2)) & 1) == 0 ? "stone_bricks" : "polished_andesite");
            int depth = 4 + (int) (Math.min(e, 16) * 1.2);
            for (int y = 1; y <= depth; y++) set(c, x, -y, z, y <= 2 ? "stone_bricks" : y < depth - 1 ? (rnd.nextInt(60) == 0 ? "magma_block" : "deepslate") : "basalt[axis=y]");
        }
    }

    // ------------------------------------------------------------------ the pit

    private static void pit(Canvas c) {
        Random rnd = new Random(78);
        for (int x = CX - PIT_R; x <= CX + PIT_R; x++) for (int z = CZ - PIT_R; z <= CZ + PIT_R; z++) {
            double r = Math.hypot(x - CX, z - CZ);
            if (r >= PIT_R) continue;
            set(c, x, 0, z, r > 12.2 && r < 13.2 ? "red_terracotta" : rnd.nextInt(9) == 0 ? "red_sand" : "sand");
        }
        for (int x = -6; x <= 6; x++) for (int z = -1; z <= 1; z++) set(c, CX + x, 0, CZ + z, "blackstone");
        for (int x = 7; x <= 9; x++) set(c, CX + x, 0, CZ, "blackstone");
        for (int x = -1; x <= 1; x++) for (int z : new int[]{-3, -2, 2, 3}) set(c, CX + x, 0, CZ + z, "polished_blackstone");
        for (int x = CX - PIT_R - 2; x <= CX + PIT_R + 2; x++) for (int z = CZ - PIT_R - 2; z <= CZ + PIT_R + 2; z++) {
            double r = Math.hypot(x - CX, z - CZ);
            if (r < PIT_R || r >= PIT_R + 1.6) continue;
            for (int y = 1; y <= 3; y++) set(c, x, y, z, y == 3 ? "polished_andesite" : "stone_bricks");
            set(c, x, 4, z, "iron_bars");
            set(c, x, 5, z, "iron_bars");
        }
    }

    // ------------------------------------------------------------------ the seating

    private static void stands(Canvas c) {
        Random rnd = new Random(79);
        for (int x = CX - WALL_IN; x <= CX + WALL_IN; x++) for (int z = CZ - WALL_IN; z <= CZ + WALL_IN; z++) {
            int dx = x - CX, dz = z - CZ;
            double r = Math.hypot(dx, dz);
            if (r < PIT_R + 1.6 || r >= WALL_IN) continue;
            int k = (int) ((r - 16) / 2);
            if (r < 16) { for (int y = 1; y <= 3; y++) set(c, x, y, z, "stone_bricks"); set(c, x, 4, z, "polished_andesite"); continue; }
            if (k > 11) {   // the promenade behind the top row
                for (int y = 1; y < PROM; y++) set(c, x, y, z, "stone_bricks");
                set(c, x, PROM, z, "polished_andesite");
                if (r > WALL_IN - 0.8) set(c, x, PROM + 1, z, "stone_brick_wall");
                continue;
            }
            int top = 4 + k;
            for (int y = 1; y < top; y++) set(c, x, y, z, rnd.nextInt(12) == 0 ? "cracked_stone_bricks" : "stone_bricks");
            boolean aisle = Math.floorMod((int) Math.round(Math.toDegrees(Math.atan2(dz, dx)) / 5), 9) == 0;
            set(c, x, top, z, (aisle ? "polished_andesite_stairs" : "stone_brick_stairs") + "[facing=" + outward(dx, dz) + ",half=bottom]");
        }
    }

    // ------------------------------------------------------------------ the outer wall: four storeys of arches

    private static void outerWall(Canvas c) {
        Random rnd = new Random(80);
        for (int x = CX - WALL_OUT; x <= CX + WALL_OUT; x++) for (int z = CZ - WALL_OUT; z <= CZ + WALL_OUT; z++) {
            int dx = x - CX, dz = z - CZ;
            double r = Math.hypot(dx, dz);
            if (r < WALL_IN || r >= WALL_OUT) continue;
            double a = Math.atan2(dz, dx) / (Math.PI * 2) * 48;
            double f = a - Math.floor(a);
            boolean pillar = f < 0.22 || f > 0.78;
            boolean outerFace = r >= WALL_OUT - 1.3, glassLayer = r >= WALL_OUT - 2.6 && r < WALL_OUT - 1.3;
            boolean arch = f > 0.34 && f < 0.66;
            for (int y = 1; y <= RING_TOP - 1; y++) {
                String b = null;
                if (!pillar) {
                    if (y >= 3 && y <= 10 && (y < 10 || arch) && outerFace) b = "air";                                                  // ground niches
                    else if (y >= PROM + 1 && y <= PROM + 7 && (y < PROM + 7 || arch)) b = y == PROM + 1 ? "stone_brick_wall" : "air";  // the promenade arches see right through
                    else if (y >= 25 && y <= 31 && (y < 31 || arch)) { if (outerFace) b = "air"; else if (glassLayer) b = "orange_stained_glass"; }   // lit arches
                    else if (y >= 33 && y <= 35 && f > 0.4 && f < 0.6) { if (outerFace) b = "air"; else if (glassLayer) b = "orange_stained_glass"; }
                }
                if (b == null) b = (y == 12 || y == 24 || y == 32 || y == PROM) ? "polished_andesite" : pillar && y % 8 == 0 ? "polished_deepslate" : brick(rnd, y);
                set(c, x, y, z, b);
            }
            if (outerFace && Math.floorMod((int) Math.floor(a * 2), 2) == 0) set(c, x, RING_TOP, z, "stone_brick_wall");
            if (outerFace && pillar && r >= WALL_OUT - 1.0 && (f < 0.1 || f > 0.9) && Math.floorMod((int) Math.round(a), 2) == 0)   // a tall red banner on every other pillar
                for (int y = PROM + 1; y <= PROM + 6; y++) late(c, x, y, z, (y == PROM + 6 ? "black" : "red") + "_wall_banner[facing=" + outward(dx, dz) + "]");
        }
        for (int i = 0; i < 48; i += 2) {   // braziers on the crown
            double ang = (i + 0.5) / 48 * Math.PI * 2;
            int x = CX + (int) Math.round(Math.cos(ang) * (WALL_OUT - 1.5)), z = CZ + (int) Math.round(Math.sin(ang) * (WALL_OUT - 1.5));
            set(c, x, RING_TOP, z, "polished_blackstone_wall");
            late(c, x, RING_TOP + 1, z, "campfire[lit=true]");
        }
    }

    // ------------------------------------------------------------------ the frontage: pavilion, wings, towers, crest

    private static void frontage(Canvas c) {
        Random rnd = new Random(81);
        // wings: stepped masses either side of the pavilion, tied back into the ring
        for (int sx : new int[]{-1, 1}) for (int ax = 25; ax <= 37; ax++) {
            int x = CX + sx * ax;
            for (int z = CZ + 24; z <= BF_Z; z++) for (int y = 1; y <= 34; y++) set(c, x, y, z, brick(rnd, y));
            bay(c, x, ax);
        }
        // the pavilion: the tall central block
        for (int x = CX - 24; x <= CX + 24; x++) for (int z = CZ + 26; z <= BP_Z; z++) for (int y = 1; y <= 44; y++) set(c, x, y, z, brick(rnd, y));
        // buttresses at the pavilion's corners, topped with pinnacles
        for (int sx : new int[]{-1, 1}) {
            fill(c, CX + sx * 25, 1, BP_Z - 1, CX + sx * 26, 46, BP_Z, "polished_deepslate");
            fill(c, CX + sx * 25, 47, BP_Z - 1, CX + sx * 26, 48, BP_Z, "deepslate_tiles");
            fill(c, CX + sx * 25, 49, BP_Z, CX + sx * 25, 52, BP_Z, "blackstone");
            set(c, CX + sx * 25, 53, BP_Z, "gold_block");
            late(c, CX + sx * 25, 54, BP_Z, "lightning_rod");
        }
        // the entrance hall: a tall vaulted arch (15 wide) cut deep into the pavilion
        for (int z = CZ + 30; z <= BP_Z; z++) for (int x = -7; x <= 7; x++) {
            set(c, CX + x, 0, z, "polished_deepslate");
            for (int y = 1; y <= 19; y++) if (y <= 11 || Math.hypot(Math.abs(x), y - 11) <= 7.6) set(c, CX + x, y, z, "air");
        }
        for (int x = -3; x <= 3; x++) for (int z = CZ + 30; z <= BP_Z; z++) set(c, CX + x, 1, z, "red_carpet");
        // arch molding on the pavilion front: stepped rings of gilded and polished stone
        for (int x = -11; x <= 11; x++) for (int y = 1; y <= 22; y++) {
            double d = y <= 11 ? Math.abs(x) : Math.hypot(Math.abs(x), y - 11);
            if (d > 7.6 && d <= 8.6) set(c, CX + x, y, BP_Z, "gilded_blackstone");
            else if (d > 8.6 && d <= 9.6) set(c, CX + x, y, BP_Z, "polished_andesite");
            else if (d > 9.6 && d <= 10.6) set(c, CX + x, y, BP_Z, "polished_deepslate");
        }
        for (int z = CZ + 32; z <= BP_Z - 2; z += 4) for (int sx : new int[]{-1, 1}) { set(c, CX + sx * 7, 1, z, "polished_blackstone_wall"); set(c, CX + sx * 7, 2, z, "polished_blackstone_wall"); late(c, CX + sx * 7, 3, z, "lantern"); }
        for (int z = CZ + 34; z <= BP_Z - 3; z += 5) { for (int y = 19; y >= 15; y--) late(c, CX, y, z, "iron_chain[axis=y]"); late(c, CX, 14, z, "lantern[hanging=true]"); }
        // tall red anvil banners (blocks), lancet windows, either side of the arch
        for (int sx : new int[]{-1, 1}) {
            int bx = CX + sx * 17;
            for (int y = 3; y <= 20; y++) for (int dx = -1; dx <= 1; dx++) set(c, bx + dx, y, BP_Z + 1, y >= 19 || y <= 4 ? "gold_block" : "red_concrete");
            for (int dx = -1; dx <= 1; dx++) { set(c, bx + dx, 12, BP_Z + 1, "black_concrete"); set(c, bx + dx, 14, BP_Z + 1, "black_concrete"); }
            set(c, bx, 13, BP_Z + 1, "black_concrete");
            for (int dx = -1; dx <= 1; dx++) set(c, bx + dx, 15, BP_Z + 1, "black_concrete");
            for (int ax : new int[]{12, 22}) {
                int wx = CX + sx * ax;
                for (int y = 5; y <= 17; y++) { set(c, wx, y, BP_Z, "air"); set(c, wx, y, BP_Z - 1, "orange_stained_glass"); set(c, wx, y, BP_Z - 2, "shroomlight"); }
            }
        }
        // the sign: a dark panel with a gold frame and two lines of silver letters
        int sy0 = 24, sy1 = 41;
        fill(c, CX - 23, sy0, BP_Z + 1, CX + 23, sy1, BP_Z + 1, "polished_deepslate");
        for (int x = CX - 24; x <= CX + 24; x++) { set(c, x, sy0 - 1, BP_Z + 1, "gilded_blackstone"); set(c, x, sy1 + 1, BP_Z + 1, "gilded_blackstone"); }
        for (int y = sy0 - 1; y <= sy1 + 1; y++) { set(c, CX - 24, y, BP_Z + 1, "gilded_blackstone"); set(c, CX + 24, y, BP_Z + 1, "gilded_blackstone"); }
        for (int x = CX - 22; x <= CX + 22; x++) { set(c, x, sy0, BP_Z + 1, "red_concrete"); set(c, x, sy1, BP_Z + 1, "red_concrete"); }
        sign(c, "BLACK", CX, 33, BP_Z + 2);
        sign(c, "FORGEMC", CX, 25, BP_Z + 2);
        // the crest: a great anvil with crossed swords behind it and a gold crown above
        int cz = BP_Z - 1;
        for (int x = -8; x <= 8; x++) set(c, CX + x, 45, cz, "blackstone");
        for (int x = -7; x <= 7; x++) set(c, CX + x, 46, cz, "polished_blackstone");
        for (int y = 47; y <= 49; y++) for (int x = -3; x <= 3; x++) set(c, CX + x, y, cz, "blackstone");
        for (int y = 50; y <= 52; y++) for (int x = -6; x <= 6; x++) set(c, CX + x, y, cz, y == 52 ? "netherite_block" : "polished_blackstone");
        for (int x = 7; x <= 10; x++) for (int y = 51; y <= 52; y++) set(c, CX + x, y, cz, "polished_blackstone");
        for (int i = 0; i <= 20; i++) { set(c, CX - 10 + i, 46 + i, cz - 1, "iron_block"); set(c, CX + 10 - i, 46 + i, cz - 1, "iron_block"); }
        for (int sx : new int[]{-1, 1}) { fill(c, CX + sx * 10 - 2, 45, cz - 1, CX + sx * 10 + 2, 45, cz - 1, "gold_block"); set(c, CX + sx * 10, 44, cz - 1, "gold_block"); }
        for (int x = -4; x <= 4; x++) { set(c, CX + x, 53, cz, "gold_block"); if (Math.abs(x) % 2 == 0) set(c, CX + x, 54, cz, "gold_block"); }
        for (int x : new int[]{-4, 0, 4}) { set(c, CX + x, 55, cz, "gold_block"); set(c, CX + x, 56, cz, "gold_block"); }
        set(c, CX, 54, cz, "red_concrete");
        // end towers with spires
        for (int sx : new int[]{-1, 1}) tower(c, CX + sx * 41, CZ + 34, 4, 42);
        // pavilion cornices and merlons
        for (int x = CX - 24; x <= CX + 24; x++) { set(c, x, 21, BP_Z + 1, "polished_andesite"); set(c, x, 43, BP_Z + 1, "polished_andesite"); }
        for (int x = CX - 24; x <= CX + 24; x += 2) set(c, x, 45, BP_Z, "stone_brick_wall");
    }

    /** One bay of a wing: pillars and three storeys of lit arches on the front face. */
    private static void bay(Canvas c, int x, int ax) {
        int phase = Math.floorMod(ax, 6);
        if (phase < 2) return;
        int[][] levels = {{3, 10}, {13, 21}, {24, 32}};
        for (int[] l : levels) for (int y = l[0]; y <= l[1]; y++) {
            if (y == l[1] && (phase == 2 || phase == 5)) continue;
            set(c, x, y, BF_Z, "air");
            set(c, x, y, BF_Z - 1, y == l[0] ? "polished_deepslate" : "orange_stained_glass");
            set(c, x, y, BF_Z - 2, "shroomlight");
        }
    }

    private static void sign(Canvas c, String text, int cx, int y0, int z) {
        int w = BlockFont.width(text, 1);
        BlockFont.draw(text, 1, (col, row, gr) -> {
            int x = cx - w / 2 + col, y = y0 + row;
            set(c, x, y, z, "iron_block");
            set(c, x, y, z - 1, "red_concrete");
        });
    }

    private static void tower(Canvas c, int cx, int cz, int half, int height) {
        Random rnd = new Random(cx * 31L + cz);
        for (int y = 1; y <= height; y++) for (int x = cx - half; x <= cx + half; x++) for (int z = cz - half; z <= cz + half; z++) set(c, x, y, z, brick(rnd, y));
        for (int y = 6; y <= height - 6; y += 8) for (int dz : new int[]{cz + half, cz - half}) for (int k = 0; k < 3; k++) set(c, cx, y + k, dz, "orange_stained_glass");
        fill(c, cx - half - 1, height + 1, cz - half - 1, cx + half + 1, height + 1, cz + half + 1, "polished_andesite");
        for (int i = 0; i < 10; i++) { int h = half + 1 - i / 2; if (h < 0) break; fill(c, cx - h, height + 2 + i, cz - h, cx + h, height + 2 + i, cz + h, i % 3 == 2 ? "blackstone" : "deepslate_tiles"); }
        set(c, cx, height + 12, cz, "gold_block");
        late(c, cx, height + 13, cz, "lightning_rod");
    }

    // ------------------------------------------------------------------ the tunnel, the fighter cells and the gate into the pit

    private static void tunnelAndCells(Canvas c) {
        for (int z = CZ + PIT_R; z <= CZ + 29; z++) {
            for (int x = -6; x <= 6; x++) {
                int ax = Math.abs(x);
                for (int y = 0; y <= 13; y++) {
                    boolean inside = (ax <= 3 && y >= 1 && y <= 11) || (ax >= 4 && ax <= 5 && y >= 1 && y <= 10);
                    if (inside) set(c, CX + x, y, z, "air");
                    else if (y == 0) { if (ax <= 5) set(c, CX + x, y, z, "polished_deepslate"); }
                    else set(c, CX + x, y, z, y % 6 == 0 ? "polished_andesite" : "stone_bricks");
                }
            }
            for (int x = -1; x <= 1; x++) set(c, CX + x, 1, z, "red_carpet");
            if ((z - CZ) % 6 == 0) for (int x : new int[]{-5, 5}) { set(c, CX + x, 1, z, "polished_blackstone_wall"); set(c, CX + x, 2, z, "polished_blackstone_wall"); late(c, CX + x, 3, z, "lantern"); }
        }
        for (int x = -3; x <= 3; x++) for (int z = CZ + PIT_R - 1; z <= CZ + PIT_R + 2; z++) for (int y = 1; y <= 3; y++) set(c, CX + x, y, z, "air");
        for (int sx : new int[]{-1, 1}) {
            for (int d = PIT_R; d <= PIT_R + 8; d++) for (int z = -3; z <= 3; z++) for (int y = 0; y <= 5; y++) {
                int x = CX + sx * d, zz = CZ + z;
                if (Math.abs(z) <= 1 && y >= 1 && y <= 3) set(c, x, y, zz, "air");
                else if (y == 0) set(c, x, y, zz, "polished_blackstone");
                else set(c, x, y, zz, "stone_bricks");
            }
            for (int z = -1; z <= 1; z++) for (int y = 1; y <= 3; y++) set(c, CX + sx * (PIT_R + 9), y, CZ + z, "stone_bricks");
            for (int z = -1; z <= 1; z++) for (int y = 1; y <= 3; y++) set(c, CX + sx * PIT_R, y, CZ + z, "iron_bars");
            late(c, CX + sx * (PIT_R + 4), 3, CZ, "lantern[hanging=true]");
        }
    }

    // ------------------------------------------------------------------ the Champion's throne

    private static void throne(Canvas c) {
        int z = BT_Z;
        fill(c, -7, PROM, z - 3, 7, PROM, z + 3, "gilded_blackstone");
        fill(c, -5, PROM + 1, z - 3, 5, PROM + 1, z + 1, "polished_blackstone_bricks");
        for (int i = 0; i < 4; i++) fill(c, -3 + i, PROM + 1, z + 2 + i, 3 - i, PROM + 1, z + 2 + i, "red_carpet");
        set(c, CX, PROM + 2, z, "gold_block");
        for (int dx : new int[]{-1, 1}) { set(c, CX + dx, PROM + 2, z, "gold_block"); set(c, CX + dx, PROM + 3, z, "gold_block"); }
        for (int y = PROM + 2; y <= PROM + 7; y++) for (int dx = -1; dx <= 1; dx++) set(c, CX + dx, y, z - 1, "gold_block");
        for (int dx = -2; dx <= 2; dx += 2) set(c, CX + dx, PROM + 8, z - 1, "gold_block");
        for (int sx : new int[]{-1, 1}) { fill(c, CX + sx * 6, PROM + 1, z - 1, CX + sx * 6, PROM + 4, z - 1, "polished_blackstone_wall"); late(c, CX + sx * 6, PROM + 5, z - 1, "campfire[lit=true]"); }
        for (int sx : new int[]{-1, 1}) for (int y = PROM + 3; y <= PROM + 8; y++) late(c, CX + sx * 4, y, z - 2, (y == PROM + 8 ? "black" : "red") + "_wall_banner[facing=south]");
    }

    // ------------------------------------------------------------------ the forecourt: statues, braziers, boards

    private static void forecourt(Canvas c) {
        for (int z = BP_Z + 2; z <= CZ + 82; z++) for (int x = -3; x <= 3; x++) set(c, CX + x, 1, z, "red_carpet");
        for (int sx : new int[]{-1, 1}) {
            knight(c, CX + sx * 22, BP_Z + 8, sx);
            for (int dz : new int[]{BP_Z + 6, BP_Z + 16, BP_Z + 26}) {   // great braziers on stone pedestals
                int bx = CX + sx * 9;
                fill(c, bx - 1, 1, dz - 1, bx + 1, 2, dz + 1, "stone_bricks");
                fill(c, bx, 3, dz, bx, 5, dz, "polished_blackstone_wall");
                fill(c, bx - 1, 6, dz - 1, bx + 1, 6, dz + 1, "polished_blackstone");
                for (int dx = -1; dx <= 1; dx++) for (int dd = -1; dd <= 1; dd++) if (Math.abs(dx) + Math.abs(dd) == 2 || (dx == 0 && dd == 0)) late(c, bx + dx, 7, dz + dd, "campfire[lit=true]");
            }
            int px = CX + sx * 31;   // the rankings and duel boards on the wings
            fill(c, px - 4, 3, BF_Z + 1, px + 4, 11, BF_Z + 1, "polished_deepslate");
            for (int x = px - 5; x <= px + 5; x++) { set(c, x, 2, BF_Z + 1, "gilded_blackstone"); set(c, x, 12, BF_Z + 1, "gilded_blackstone"); }
            for (int y = 2; y <= 12; y++) { set(c, px - 5, y, BF_Z + 1, "gilded_blackstone"); set(c, px + 5, y, BF_Z + 1, "gilded_blackstone"); }
        }
    }

    /** A giant armoured knight, point of his sword resting on the plinth, a red plume on his helm. Faces south. */
    private static void knight(Canvas c, int cx, int cz, int side) {
        fill(c, cx - 3, 1, cz - 3, cx + 3, 2, cz + 3, "polished_andesite");
        fill(c, cx - 3, 3, cz - 3, cx + 3, 3, cz + 3, "gilded_blackstone");
        for (int dx : new int[]{-2, 1}) { fill(c, cx + dx, 4, cz - 1, cx + dx + 1, 5, cz + 1, "iron_block"); fill(c, cx + dx, 6, cz - 1, cx + dx + 1, 11, cz, "polished_deepslate"); }
        fill(c, cx - 3, 12, cz - 1, cx + 3, 12, cz + 1, "gilded_blackstone");
        fill(c, cx - 3, 13, cz - 1, cx + 3, 18, cz + 1, "polished_deepslate");
        fill(c, cx - 1, 13, cz + 1, cx + 1, 17, cz + 1, "red_concrete");
        for (int dx : new int[]{-1, 0, 1}) set(c, cx + dx, 15, cz + 1, "iron_block");
        for (int dx : new int[]{-5, 4}) { fill(c, cx + dx, 17, cz - 1, cx + dx + 1, 19, cz + 1, "iron_block"); fill(c, cx + dx, 12, cz - 1, cx + dx + 1, 16, cz, "polished_deepslate"); fill(c, cx + dx, 10, cz - 1, cx + dx + 1, 11, cz, "iron_block"); }
        fill(c, cx - 1, 19, cz - 1, cx + 1, 21, cz + 1, "iron_block");
        fill(c, cx - 1, 20, cz + 1, cx + 1, 20, cz + 1, "black_concrete");
        fill(c, cx, 22, cz, cx, 26, cz, "red_concrete");
        int sx = side > 0 ? cx + 5 : cx - 5;   // the sword hangs in the outer hand
        fill(c, sx, 4, cz + 2, sx, 14, cz + 2, "iron_block");
        fill(c, sx - 1, 15, cz + 2, sx + 1, 15, cz + 2, "gold_block");
        set(c, sx, 16, cz + 2, "gold_block");
    }

    // ------------------------------------------------------------------ fixed points used by the plugin

    public static final double LOBBY_X = CX + 0.5, LOBBY_Z = CZ - 70.5;                  // the forecourt, facing the gate
    public static final double FIGHTER_A_X = CX - 9.5, FIGHTER_B_X = CX + 10.5, FIGHT_Z = CZ + 0.5;
}
