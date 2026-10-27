package net.alloymc.core.arcade;

import net.alloymc.core.build.Canvas;

import java.util.Random;

/** Procedural arcade lobby and arenas. Coordinates are relative to (ox, oy, oz). */
public final class ArenaBuilder {
    private final Canvas c = new Canvas();
    private final Random rnd;
    private final int ox, oy, oz;

    public static final int SPLEEF_R = 16, SPLEEF_LOWER = 8;
    public static final int TNT_HALF = 15, TNT_GAP = 7, TNT_LAYERS = 3;
    public static final int TOWN_HALF = 30;

    private ArenaBuilder(int ox, int oy, int oz, long seed) {
        this.ox = ox; this.oy = oy; this.oz = oz; this.rnd = new Random(seed);
    }

    public static Canvas build(String arena, int ox, int oy, int oz) {
        ArenaBuilder b = new ArenaBuilder(ox, oy, oz, arena.hashCode());
        switch (arena) {
            case "lobby" -> b.lobby();
            case "spleef" -> b.spleef();
            case "tntrun" -> b.tntrun();
            case "infected" -> b.infected();
            default -> throw new IllegalArgumentException(arena);
        }
        return b.c;
    }

    /** Only the breakable floors, for fast resets between rounds. */
    public static Canvas floors(String arena, int ox, int oy, int oz) {
        ArenaBuilder b = new ArenaBuilder(ox, oy, oz, arena.hashCode());
        if (arena.equals("spleef")) b.spleefFloors();
        else if (arena.equals("tntrun")) b.tntFloors();
        return b.c;
    }

    private void set(int x, int y, int z, String b) { c.set(ox + x, oy + y, oz + z, b); }
    private void late(int x, int y, int z, String b) { c.setLate(ox + x, oy + y, oz + z, b); }

    // --------------------------------------------------------------- lobby

    private void lobby() {
        for (int x = -20; x <= 20; x++)
            for (int z = -20; z <= 20; z++) {
                double r = Math.hypot(x, z);
                if (r > 18.5) continue;
                String b = ((Math.floorDiv(x, 2) + Math.floorDiv(z, 2)) & 1) == 0 ? "minecraft:polished_deepslate" : "minecraft:deepslate_tiles";
                if (r > 6.5 && r <= 7.5) b = "minecraft:waxed_cut_copper";
                if (r > 17.4) b = "minecraft:chiseled_tuff_bricks";
                set(x, 0, z, b);
                int depth = (int) ((18.5 - r) * 0.7) + 2;
                for (int d = 1; d <= depth; d++) set(x, -d, z, d == 1 ? "minecraft:polished_blackstone" : rnd.nextInt(6) == 0 ? "minecraft:copper_ore" : "minecraft:deepslate");
            }
        // central brazier
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) set(x, 0, z, "minecraft:gold_block");
        set(0, 0, 0, "minecraft:magma_block");
        // perimeter rail with lanterns
        for (int i = 0; i < 48; i++) {
            double a = i * Math.PI * 2 / 48;
            int x = (int) Math.round(18 * Math.cos(a)), z = (int) Math.round(18 * Math.sin(a));
            set(x, 1, z, "minecraft:polished_blackstone_wall");
            if (i % 6 == 0) { set(x, 2, z, "minecraft:waxed_chiseled_copper"); late(x, 3, z, "minecraft:copper_lantern"); }
        }
        booth(0, -11, "minecraft:packed_ice", "minecraft:snow_block", "minecraft:light_blue_stained_glass");
        booth(11, 0, "minecraft:tnt", "minecraft:sand", "minecraft:red_concrete");
        booth(-11, 0, "minecraft:green_concrete", "minecraft:moss_block", "minecraft:lime_stained_glass");
        booth(0, 11, "minecraft:waxed_copper_block", "minecraft:waxed_cut_copper", "minecraft:orange_stained_glass");
    }

    /** A game booth: themed pad with a join plate in the middle and corner pillars. */
    private void booth(int cx, int cz, String pillar, String pad, String glass) {
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) set(cx + x, 0, cz + z, pad);
        set(cx, 0, cz, "minecraft:gold_block");
        late(cx, 1, cz, "minecraft:light_weighted_pressure_plate");
        for (int[] k : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) {
            for (int y = 1; y <= 4; y++) set(cx + k[0], y, cz + k[1], pillar);
            set(cx + k[0], 5, cz + k[1], "minecraft:waxed_copper_bulb[lit=true]");
        }
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++)
            if (Math.abs(x) == 2 || Math.abs(z) == 2) set(cx + x, 6, cz + z, glass);
    }

    // --------------------------------------------------------------- spleef

    private void spleef() {
        int R = SPLEEF_R;
        // bowl walls
        for (int x = -R - 3; x <= R + 3; x++)
            for (int z = -R - 3; z <= R + 3; z++) {
                double r = Math.hypot(x, z);
                if (r <= R + 0.5 || r > R + 2.5) continue;
                for (int y = -SPLEEF_LOWER - 2; y <= 4; y++) {
                    String b = y >= 2 ? "minecraft:light_blue_stained_glass" : r > R + 1.5 ? "minecraft:packed_ice" : "minecraft:blue_ice";
                    if (y == 1 && ((x + z) & 3) == 0) b = "minecraft:sea_lantern";
                    set(x, y, z, b);
                }
            }
        for (int x = -R - 3; x <= R + 3; x++)
            for (int z = -R - 3; z <= R + 3; z++) {
                double r = Math.hypot(x, z);
                if (r > R + 0.5 && r <= R + 3.2) set(x, 5, z, "minecraft:waxed_cut_copper");
            }
        spleefFloors();
    }

    private void spleefFloors() {
        for (int x = -SPLEEF_R; x <= SPLEEF_R; x++)
            for (int z = -SPLEEF_R; z <= SPLEEF_R; z++)
                if (Math.hypot(x, z) <= SPLEEF_R + 0.5) {
                    set(x, 0, z, "minecraft:snow_block");
                    set(x, -SPLEEF_LOWER, z, "minecraft:snow_block");
                }
    }

    // --------------------------------------------------------------- tnt run

    private void tntrun() {
        int H = TNT_HALF + 1;
        for (int x = -H - 1; x <= H + 1; x++)
            for (int z = -H - 1; z <= H + 1; z++) {
                if (Math.abs(x) <= H - 1 && Math.abs(z) <= H - 1) continue;
                for (int y = -TNT_GAP * TNT_LAYERS; y <= 5; y++) {
                    boolean outer = Math.abs(x) == H + 1 || Math.abs(z) == H + 1;
                    String b = outer ? "minecraft:smooth_sandstone" : y >= 2 ? "minecraft:orange_stained_glass" : "minecraft:cut_sandstone";
                    if (!outer && y < 2 && (y % TNT_GAP == 0)) b = "minecraft:redstone_lamp[lit=true]";
                    if (outer && y == 5) b = "minecraft:gold_block";
                    set(x, y, z, b);
                }
            }
        tntFloors();
    }

    private void tntFloors() {
        for (int l = 0; l < TNT_LAYERS; l++)
            for (int x = -TNT_HALF; x <= TNT_HALF; x++)
                for (int z = -TNT_HALF; z <= TNT_HALF; z++) {
                    int y = -l * TNT_GAP;
                    set(x, y, z, l == 1 ? "minecraft:red_sand" : "minecraft:sand");
                    set(x, y - 1, z, "minecraft:tnt");
                }
    }

    // --------------------------------------------------------------- infected

    private static final String[][] HOUSE_STYLES = {
        {"minecraft:bricks", "minecraft:spruce_planks", "minecraft:spruce_log"},
        {"minecraft:stone_bricks", "minecraft:oak_planks", "minecraft:stripped_oak_log"},
        {"minecraft:waxed_cut_copper", "minecraft:polished_deepslate", "minecraft:waxed_copper_block"},
        {"minecraft:mud_bricks", "minecraft:dark_oak_planks", "minecraft:dark_oak_log"},
        {"minecraft:tuff_bricks", "minecraft:spruce_planks", "minecraft:polished_tuff"},
    };

    private void infected() {
        int H = TOWN_HALF;
        // ground: cobbled streets
        for (int x = -H; x <= H; x++)
            for (int z = -H; z <= H; z++) {
                int n = rnd.nextInt(10);
                set(x, 0, z, n < 5 ? "minecraft:cobblestone" : n < 8 ? "minecraft:andesite" : n < 9 ? "minecraft:gravel" : "minecraft:mossy_cobblestone");
                set(x, -1, z, "minecraft:stone");
            }
        // boundary wall + invisible ceiling ring
        for (int x = -H - 1; x <= H + 1; x++)
            for (int z = -H - 1; z <= H + 1; z++) {
                if (Math.abs(x) <= H && Math.abs(z) <= H) continue;
                for (int y = -1; y <= 7; y++) set(x, y, z, y == 7 ? "minecraft:polished_blackstone_bricks" : "minecraft:stone_bricks");
                for (int y = 8; y <= 14; y++) set(x, y, z, "minecraft:barrier");
            }
        // 5x5 plots of 9 with 3-wide streets (plot origin = -H + 3)
        int plot = 9, street = 3;
        for (int px = 0; px < 5; px++)
            for (int pz = 0; pz < 5; pz++) {
                int x0 = -H + street + px * (plot + street) - 1, z0 = -H + street + pz * (plot + street) - 1;
                if (px == 2 && pz == 2) { plaza(x0, z0, plot); continue; }
                int n = rnd.nextInt(10);
                if (n < 7) house(x0, z0, plot);
                else yard(x0, z0, plot);
            }
        // street lamps at intersections
        for (int i = 0; i <= 5; i++)
            for (int j = 0; j <= 5; j++) {
                int x = -H + 1 + i * (plot + street) - 1, z = -H + 1 + j * (plot + street) - 1;
                if (Math.abs(x) >= H || Math.abs(z) >= H) continue;
                set(x, 1, z, "minecraft:spruce_fence");
                set(x, 2, z, "minecraft:spruce_fence");
                late(x, 3, z, "minecraft:lantern");
            }
    }

    private void house(int x0, int z0, int size) {
        String[] st = HOUSE_STYLES[rnd.nextInt(HOUSE_STYLES.length)];
        int h = 4 + rnd.nextInt(3);
        boolean twoFloors = h >= 6 && rnd.nextBoolean();
        for (int x = 0; x < size; x++)
            for (int z = 0; z < size; z++) {
                boolean wall = x == 0 || z == 0 || x == size - 1 || z == size - 1;
                boolean corner = (x == 0 || x == size - 1) && (z == 0 || z == size - 1);
                set(x0 + x, 0, z0 + z, st[1]);
                for (int y = 1; y <= h; y++) {
                    if (!wall) continue;
                    String b = corner ? st[2] : st[0];
                    boolean window = !corner && (y == 2 || (twoFloors && y == 5)) && ((x + z) % 3 == 1);
                    set(x0 + x, y, z0 + z, window ? "minecraft:glass_pane" : b);
                }
                set(x0 + x, h + 1, z0 + z, wall ? st[2] : st[1]);
                if (twoFloors && !wall) set(x0 + x, 4, z0 + z, st[1]);
            }
        // doorways on two random sides
        int[][] doors = {{size / 2, 0}, {size / 2, size - 1}, {0, size / 2}, {size - 1, size / 2}};
        int a = rnd.nextInt(4), b = (a + 1 + rnd.nextInt(3)) % 4;
        for (int d : new int[]{a, b}) {
            set(x0 + doors[d][0], 1, z0 + doors[d][1], "minecraft:air");
            set(x0 + doors[d][0], 2, z0 + doors[d][1], "minecraft:air");
        }
        // ladder up the outside to the roof
        int[] lw = doors[(a + 2) % 4];
        String facing = lw[1] == 0 ? "north" : lw[1] == size - 1 ? "south" : lw[0] == 0 ? "west" : "east";
        int ladderX = x0 + lw[0] + (facing.equals("west") ? -1 : facing.equals("east") ? 1 : 0);
        int ladderZ = z0 + lw[1] + (facing.equals("north") ? -1 : facing.equals("south") ? 1 : 0);
        if (facing.equals("north") || facing.equals("south")) ladderX = x0 + lw[0] + 2;
        for (int y = 1; y <= h + 1; y++) late(ladderX, y, ladderZ, "minecraft:ladder[facing=" + facing + "]");
        // interior clutter
        for (int i = 0; i < 3; i++) {
            int x = x0 + 1 + rnd.nextInt(size - 2), z = z0 + 1 + rnd.nextInt(size - 2);
            set(x, 1, z, rnd.nextBoolean() ? "minecraft:barrel" : "minecraft:crafting_table");
        }
        late(x0 + size / 2, h, z0 + size / 2, "minecraft:lantern[hanging=true]");
    }

    private void yard(int x0, int z0, int size) {
        for (int x = 0; x < size; x++)
            for (int z = 0; z < size; z++) set(x0 + x, 0, z0 + z, rnd.nextInt(4) == 0 ? "minecraft:coarse_dirt" : "minecraft:grass_block");
        for (int i = 0; i < 6; i++) {
            int x = x0 + rnd.nextInt(size), z = z0 + rnd.nextInt(size);
            String b = switch (rnd.nextInt(4)) { case 0 -> "minecraft:hay_block"; case 1 -> "minecraft:barrel"; case 2 -> "minecraft:oak_planks"; default -> "minecraft:moss_block"; };
            set(x, 1, z, b);
            if (rnd.nextInt(3) == 0) set(x, 2, z, b);
        }
        int tx = x0 + size / 2, tz = z0 + size / 2;
        for (int y = 1; y <= 4; y++) set(tx, y, tz, "minecraft:oak_log");
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = 4; dy <= 6; dy++)
            if (Math.abs(dx) + Math.abs(dz) + Math.abs(dy - 5) <= 3 && !(dx == 0 && dz == 0 && dy == 4))
                set(tx + dx, dy, tz + dz, "minecraft:oak_leaves[persistent=true]");
    }

    private void plaza(int x0, int z0, int size) {
        int cx = x0 + size / 2, cz = z0 + size / 2;
        for (int x = 0; x < size; x++) for (int z = 0; z < size; z++) set(x0 + x, 0, z0 + z, "minecraft:polished_andesite");
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++)
            if (Math.abs(x) == 2 || Math.abs(z) == 2) set(cx + x, 1, cz + z, "minecraft:stone_brick_slab");
        set(cx, 1, cz, "minecraft:waxed_copper_block");
        set(cx, 2, cz, "minecraft:waxed_copper_block");
        late(cx, 3, cz, "minecraft:copper_lantern");
    }
}
