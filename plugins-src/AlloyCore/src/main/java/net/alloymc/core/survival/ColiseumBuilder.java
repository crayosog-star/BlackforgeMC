package net.alloymc.core.survival;

import net.alloymc.core.build.BlockFont;
import net.alloymc.core.build.Canvas;

import java.util.Random;

/**
 * The BlackForge Colosseum: a Roman amphitheatre on its own floating rock south of the kingdom. A sand pit with an anvil
 * emblem, eight tiers of stone seating, a two-storey arcaded outer wall, a grand facade with a vaulted tunnel into the
 * pit, fighter cells either side of it, the Champion's throne above the north seats, and a forecourt with the rankings
 * board. Fixed points: pit centre (CX, Y, CZ) radius {@link #PIT_R}; the forecourt (lobby) in front of the facade.
 */
public final class ColiseumBuilder {
    private ColiseumBuilder() {}

    public static final int CX = 0, CZ = 250, Y = SurvivalLayout.Y;
    public static final int PIT_R = 14, WALL_IN = 34, WALL_OUT = 37, TOP = Y + 24;
    public static final int FACADE_Z = CZ + 40;
    private static final String TEXT = "BLACKFORGEMC";

    private static String mc(String s) { return "minecraft:" + s; }
    private static void set(Canvas c, int x, int y, int z, String b) { c.set(x, Y + y, z, mc(b)); }
    private static void late(Canvas c, int x, int y, int z, String b) { c.setLate(x, Y + y, z, mc(b)); }
    private static void fill(Canvas c, int x1, int y1, int z1, int x2, int y2, int z2, String b) {
        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) set(c, x, y, z, b);
    }
    private static String outward(int dx, int dz) { return Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? "east" : "west") : (dz > 0 ? "south" : "north"); }

    public static Canvas build() {
        Canvas c = new Canvas();
        rock(c);
        pit(c);
        stands(c);
        outerWall(c);
        facade(c);
        tunnelAndCells(c);
        throne(c);
        forecourt(c);
        return c;
    }

    // ------------------------------------------------------------------ the rock

    private static void rock(Canvas c) {
        Random rnd = new Random(77);
        for (int x = CX - 50; x <= CX + 50; x++) for (int z = CZ - 50; z <= CZ + 72; z++) {
            int dx = x - CX, dz = z - CZ;
            double r = Math.hypot(dx, dz), d1 = 45 - r, d2 = (Math.abs(dx) <= 34 && dz >= 30 && dz <= 70) ? Math.min(34 - Math.abs(dx), 70 - dz) : -1;
            double e = Math.max(d1, d2);
            if (e <= 0) continue;
            set(c, x, 0, z, e < 1.5 ? "polished_blackstone_bricks" : (((x >> 2) + (z >> 2)) & 1) == 0 ? "stone_bricks" : "polished_andesite");
            int depth = 4 + (int) (Math.min(e, 14) * 1.15);
            for (int y = 1; y <= depth; y++) set(c, x, -y, z, y <= 2 ? "stone_bricks" : y < depth - 1 ? (rnd.nextInt(60) == 0 ? "magma_block" : "deepslate") : "basalt[axis=y]");
        }
    }

    // ------------------------------------------------------------------ the pit

    private static void pit(Canvas c) {
        Random rnd = new Random(78);
        for (int x = CX - PIT_R; x <= CX + PIT_R; x++) for (int z = CZ - PIT_R; z <= CZ + PIT_R; z++) {
            double r = Math.hypot(x - CX, z - CZ);
            if (r >= PIT_R) continue;
            String b = r > 12.2 && r < 13.2 ? "red_terracotta" : rnd.nextInt(9) == 0 ? "red_sand" : "sand";
            set(c, x, 0, z, b);
        }
        // the anvil emblem in black stone
        for (int x = -6; x <= 6; x++) for (int z = -1; z <= 1; z++) set(c, CX + x, 0, CZ + z, "blackstone");
        for (int x = 7; x <= 9; x++) set(c, CX + x, 0, CZ, "blackstone");
        for (int x = -1; x <= 1; x++) for (int z : new int[]{-3, -2, 2, 3}) set(c, CX + x, 0, CZ + z, "polished_blackstone");
        // the pit wall, topped with bars so nothing leaves the sand
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
        for (int x = CX - 34; x <= CX + 34; x++) for (int z = CZ - 34; z <= CZ + 34; z++) {
            int dx = x - CX, dz = z - CZ;
            double r = Math.hypot(dx, dz);
            if (r < PIT_R + 1.6 || r >= 34) continue;
            int k = (int) ((r - 16) / 2);
            if (r < 16) { for (int y = 1; y <= 3; y++) set(c, x, y, z, "stone_bricks"); set(c, x, 4, z, "polished_andesite"); continue; }   // the ledge at the foot of the stands
            if (k > 7) {   // the promenade
                for (int y = 1; y <= 11; y++) set(c, x, y, z, "stone_bricks");
                set(c, x, 12, z, "polished_andesite");
                if (r > 33.2) set(c, x, 13, z, "stone_brick_wall");
                continue;
            }
            int top = 4 + k;
            for (int y = 1; y < top; y++) set(c, x, y, z, rnd.nextInt(12) == 0 ? "cracked_stone_bricks" : "stone_bricks");
            boolean aisle = Math.floorMod((int) Math.round(Math.toDegrees(Math.atan2(dz, dx)) / 5), 9) == 0;
            set(c, x, top, z, (aisle ? "polished_andesite_stairs" : "stone_brick_stairs") + "[facing=" + outward(dx, dz) + ",half=bottom]");
        }
    }

    // ------------------------------------------------------------------ the outer wall

    private static void outerWall(Canvas c) {
        Random rnd = new Random(80);
        for (int x = CX - WALL_OUT; x <= CX + WALL_OUT; x++) for (int z = CZ - WALL_OUT; z <= CZ + WALL_OUT; z++) {
            int dx = x - CX, dz = z - CZ;
            double r = Math.hypot(dx, dz);
            if (r < WALL_IN || r >= WALL_OUT) continue;
            double a = Math.atan2(dz, dx) / (Math.PI * 2) * 40;
            double f = a - Math.floor(a);
            boolean pillar = f < 0.2 || f > 0.8;
            boolean outerFace = r >= WALL_OUT - 1.3;
            for (int y = 1; y <= 24; y++) {
                String b;
                boolean open = false;
                int row = y;
                if (!pillar && y >= 13 && y <= 19) open = (y < 19 || (f > 0.35 && f < 0.65));                       // the promenade arches see right through
                if (!pillar && outerFace && y >= 2 && y <= 8) open = (y < 8 || (f > 0.35 && f < 0.65));              // blind niches at the foot
                if (!pillar && y >= 21 && y <= 23 && f > 0.35 && f < 0.65) open = false;
                if (open) { set(c, x, y, z, y == 13 ? "stone_brick_wall" : "air"); continue; }
                if (y == 12 || y == 20) b = "polished_andesite";
                else if (!pillar && y >= 21 && y <= 23 && f > 0.4 && f < 0.6 && outerFace) b = "orange_stained_glass";
                else b = pillar ? (row % 6 == 0 ? "polished_andesite" : rnd.nextInt(8) == 0 ? "cracked_stone_bricks" : "stone_bricks") : (rnd.nextInt(10) == 0 ? "cracked_stone_bricks" : "stone_bricks");
                set(c, x, y, z, b);
            }
            if (outerFace && Math.floorMod((int) Math.floor(a * 2), 2) == 0) set(c, x, 25, z, "stone_brick_wall");
            // a banner on the outer face of every other pillar
            if (outerFace && r >= WALL_OUT - 1.0 && pillar && (f < 0.1 || f > 0.9) && Math.floorMod((int) Math.round(a), 2) == 0)
                late(c, x, 15, z, (Math.floorMod((int) Math.round(a), 4) == 0 ? "red" : "black") + "_wall_banner[facing=" + outward(dx, dz) + "]");
        }
        for (int i = 0; i < 40; i += 2) {   // braziers on the crown
            double ang = (i + 0.5) / 40 * Math.PI * 2;
            int x = CX + (int) Math.round(Math.cos(ang) * 35.5), z = CZ + (int) Math.round(Math.sin(ang) * 35.5);
            set(c, x, 25, z, "polished_blackstone_wall");
            late(c, x, 26, z, "campfire[lit=true]");
        }
    }

    // ------------------------------------------------------------------ the grand facade

    private static void facade(Canvas c) {
        int w = BlockFont.width(TEXT, 1), half = w / 2 + 4, zf = FACADE_Z;
        for (int x = CX - half; x <= CX + half; x++) for (int z = zf; z <= zf + 3; z++) for (int y = 1; y <= 24; y++) {
            int ax = Math.abs(x - CX);
            boolean pillar = Math.floorMod(x - CX, 8) <= 1;
            boolean gate = ax <= 5 && y <= 12 && (y <= 9 || ax <= 5 - (y - 9) * 1);
            if (gate) { set(c, x, y, z, "air"); continue; }
            String b = pillar ? (y % 6 == 0 ? "polished_andesite" : "stone_bricks") : (y % 11 == 0 ? "polished_andesite" : "stone_bricks");
            if (!pillar && z == zf + 3 && y >= 3 && y <= 8 && ax > 7) b = (y % 2 == 0) ? "stone_bricks" : "cracked_stone_bricks";
            set(c, x, y, z, b);
        }
        // arched recesses either side of the gate with red banners
        for (int sx : new int[]{-1, 1}) for (int bx = 9; bx + 3 < half; bx += 8) {
            int x = CX + sx * (bx + 3);
            late(c, x, 12, zf + 4, (Math.floorMod(bx, 16) == 9 ? "red" : "black") + "_wall_banner[facing=south]");
            late(c, x, 11, zf + 4, "air");
        }
        // BLACKFORGEMC in burning letters, high on the facade, readable from the forecourt
        int y0 = 15;
        BlockFont.draw(TEXT, 1, (col, row, gr) -> {
            int x = CX - w / 2 + col, y = y0 + row;
            set(c, x, y, zf + 4, "shroomlight");
        });
        // the anvil crest and crossed swords
        for (int x = -6; x <= 6; x++) set(c, CX + x, 25, zf + 2, "blackstone");
        for (int x = -5; x <= 5; x++) set(c, CX + x, 26, zf + 2, "polished_blackstone");
        for (int y = 27; y <= 28; y++) for (int x = -2; x <= 2; x++) set(c, CX + x, y, zf + 2, "blackstone");
        for (int y = 29; y <= 30; y++) for (int x = -4; x <= 4; x++) set(c, CX + x, y, zf + 2, "polished_blackstone");
        for (int x = -2; x <= 2; x++) set(c, CX + x, 31, zf + 2, "gold_block");
        for (int i = 0; i <= 9; i++) { set(c, CX - 14 + i, 25 + i, zf + 2, "iron_block"); set(c, CX + 14 - i, 25 + i, zf + 2, "iron_block"); }
        set(c, CX - 15, 24, zf + 2, "gold_block"); set(c, CX + 15, 24, zf + 2, "gold_block");
        // the lintel over the gate
        for (int x = -6; x <= 6; x++) { set(c, CX + x, 13, zf + 1, "gilded_blackstone"); set(c, CX + x, 14, zf + 1, "polished_andesite"); }
    }

    // ------------------------------------------------------------------ the tunnel, the fighter cells and the gate into the pit

    private static void tunnelAndCells(Canvas c) {
        // vaulted tunnel from the facade through the stands to the pit
        for (int z = CZ + PIT_R; z <= FACADE_Z + 3; z++) {
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
            if ((z - CZ) % 6 == 0) { for (int x : new int[]{-5, 5}) { set(c, CX + x, 1, z, "polished_blackstone_wall"); set(c, CX + x, 2, z, "polished_blackstone_wall"); late(c, CX + x, 3, z, "lantern"); } }
        }
        // the opening in the pit wall (the bars stay overhead)
        for (int x = -3; x <= 3; x++) for (int z = CZ + PIT_R - 1; z <= CZ + PIT_R + 2; z++) for (int y = 1; y <= 3; y++) set(c, CX + x, y, z, "air");
        // fighter cells east and west: a short passage with a barred gate onto the sand
        for (int sx : new int[]{-1, 1}) {
            for (int d = PIT_R; d <= PIT_R + 8; d++) for (int z = -3; z <= 3; z++) for (int y = 0; y <= 5; y++) {
                int x = CX + sx * d, zz = CZ + z;
                if (Math.abs(z) <= 1 && y >= 1 && y <= 3) set(c, x, y, zz, "air");
                else if (y == 0) set(c, x, y, zz, "polished_blackstone");
                else set(c, x, y, zz, "stone_bricks");
            }
            for (int z = -1; z <= 1; z++) { set(c, CX + sx * (PIT_R + 9), 1, CZ + z, "stone_bricks"); set(c, CX + sx * (PIT_R + 9), 2, CZ + z, "stone_bricks"); set(c, CX + sx * (PIT_R + 9), 3, CZ + z, "stone_bricks"); }
            for (int z = -1; z <= 1; z++) for (int y = 1; y <= 3; y++) set(c, CX + sx * PIT_R, y, CZ + z, "iron_bars");
            late(c, CX + sx * (PIT_R + 4), 3, CZ, "lantern[hanging=true]");
        }
    }

    // ------------------------------------------------------------------ the Champion's throne

    private static void throne(Canvas c) {
        int z = CZ - 32;
        fill(c, -6, 12, z - 2, 6, 12, z + 2, "gilded_blackstone");
        fill(c, -4, 13, z - 2, 4, 13, z + 1, "polished_blackstone_bricks");
        for (int i = 0; i < 4; i++) fill(c, -3 + i, 13 + (i == 0 ? 0 : 0), z + 2 + i, 3 - i, 13, z + 2 + i, "red_carpet");
        // the throne: gold seat, arms and a tall back, a crown above
        set(c, CX, 14, z, "gold_block");
        for (int dx : new int[]{-1, 1}) { set(c, CX + dx, 14, z, "gold_block"); set(c, CX + dx, 15, z, "gold_block"); }
        for (int y = 14; y <= 19; y++) for (int dx = -1; dx <= 1; dx++) set(c, CX + dx, y, z - 1, y >= 18 && dx != 0 ? "gold_block" : "gold_block");
        for (int dx = -2; dx <= 2; dx += 2) set(c, CX + dx, 20, z - 1, "gold_block");
        late(c, CX, 15, z, "air");
        for (int sx : new int[]{-1, 1}) { fill(c, CX + sx * 5, 13, z - 1, CX + sx * 5, 16, z - 1, "polished_blackstone_wall"); late(c, CX + sx * 5, 17, z - 1, "campfire[lit=true]"); late(c, CX + sx * 3, 17, z - 2, (sx < 0 ? "red" : "black") + "_wall_banner[facing=south]"); }
    }

    // ------------------------------------------------------------------ the forecourt

    private static void forecourt(Canvas c) {
        for (int z = FACADE_Z + 4; z <= CZ + 68; z++) for (int x = -1; x <= 1; x++) set(c, CX + x, 1, z, "red_carpet");
        for (int sx : new int[]{-1, 1}) {
            // statues of gladiators flanking the way in
            int sxp = CX + sx * 9, sz = FACADE_Z + 8;
            fill(c, sxp - 1, 1, sz - 1, sxp + 1, 2, sz + 1, "polished_andesite");
            fill(c, sxp, 3, sz, sxp, 5, sz, "polished_deepslate");
            fill(c, sxp - 1, 6, sz, sxp + 1, 8, sz, "polished_deepslate");
            set(c, sxp, 9, sz, "chiseled_polished_blackstone");
            set(c, sxp, 10, sz, "gold_block");
            for (int y = 6; y <= 11; y++) set(c, sxp + sx * 2, y, sz, "iron_bars");
            set(c, sxp + sx * 2, 12, sz, "iron_block");
            for (int dx : new int[]{-1, 1}) set(c, sxp + dx * 2, 8, sz, "polished_deepslate");
            // braziers on the steps
            int bx = CX + sx * 6, bz = FACADE_Z + 16;
            fill(c, bx, 1, bz, bx, 2, bz, "polished_blackstone_wall");
            set(c, bx, 3, bz, "chiseled_polished_blackstone");
            late(c, bx, 4, bz, "campfire[lit=true]");
        }
        // the Rankings and Duel boards: black panels with gilded frames on the facade, either side of the gate
        for (int sx : new int[]{-1, 1}) {
            int px = CX + sx * 24;
            fill(c, px - 5, 3, FACADE_Z + 4, px + 5, 10, FACADE_Z + 4, "polished_deepslate");
            for (int x = px - 5; x <= px + 5; x++) { set(c, x, 2, FACADE_Z + 4, "gilded_blackstone"); set(c, x, 11, FACADE_Z + 4, "gilded_blackstone"); }
            for (int y = 2; y <= 11; y++) { set(c, px - 6, y, FACADE_Z + 4, "gilded_blackstone"); set(c, px + 6, y, FACADE_Z + 4, "gilded_blackstone"); }
            for (int dx : new int[]{-7, 7}) { fill(c, px + dx, 1, FACADE_Z + 6, px + dx, 2, FACADE_Z + 6, "polished_blackstone_wall"); late(c, px + dx, 3, FACADE_Z + 6, "soul_lantern"); }
        }
    }

    // ------------------------------------------------------------------ fixed points used by the plugin

    public static final double LOBBY_X = CX + 0.5, LOBBY_Z = CZ + 62.5;                  // the forecourt, facing the gate
    public static final double FIGHTER_A_X = CX - 9.5, FIGHTER_B_X = CX + 10.5, FIGHT_Z = CZ + 0.5;
    public static final double SPECTATOR_X = CX + 0.5, SPECTATOR_Z = CZ - 29.5;          // the north seats beside the throne
}
