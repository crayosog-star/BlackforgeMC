package net.alloymc.core.hub;

import net.alloymc.core.build.BlockFont;
import net.alloymc.core.build.Canvas;

import java.util.Random;

import static net.alloymc.core.hub.HubLayout.Y;

/**
 * Generates the BlackForgeMC hub: a dark volcanic forge island with a molten crucible,
 * four portal gates and giant cast-metal lettering. Every bit of lava a player can get near
 * is sealed under glass or behind a rail (checked offline by gradle walkcheck).
 */
public final class HubBuilder {
    private final Canvas c = new Canvas();
    private final Random rnd = new Random(0xA110E);

    public static Canvas build() {
        HubBuilder b = new HubBuilder();
        b.island(0, 0, 0, 1.0, true);
        b.plaza();
        b.paths();
        b.crucible();
        for (HubLayout.Gate g : HubLayout.GATES) b.gate(g);
        b.grounds();
        b.lavafalls();
        b.floatingIslands();
        b.goldInlay();
        b.lettering();
        return b.c;
    }

    // ------------------------------------------------------------------ island

    private double edgeRadius(double theta, double scale) {
        return scale * (46 + 3 * Math.sin(3 * theta + 1) + 2 * Math.sin(5 * theta + 2) + 1.5 * Math.sin(9 * theta));
    }

    private boolean onMain(int x, int z) {
        return Math.hypot(x, z) <= edgeRadius(Math.atan2(z, x), 1.0);
    }

    /** Island body with a tapering rocky underside. */
    private void island(int ox, int oy, int oz, double scale, boolean main) {
        int top = Y + oy;
        int max = (int) Math.ceil(52 * scale) + 1;
        for (int x = -max; x <= max; x++)
            for (int z = -max; z <= max; z++) {
                double r = Math.hypot(x, z);
                double R = edgeRadius(Math.atan2(z, x) + (main ? 0 : ox * 0.1), scale);
                if (r > R) continue;
                double t = 1 - r / R;
                int depth = (int) Math.round(Math.pow(t, 0.75) * 34 * scale + 1 + rnd.nextInt(2)
                        + 2 * Math.sin(x * 0.35) * Math.cos(z * 0.3) * scale);
                depth = Math.max(depth, 1);
                int lift = main ? bump(x, z) : 0;
                for (int k = 1; k <= lift; k++) c.set(ox + x, top + k, oz + z, k == lift ? edgeTop(R - r) : "minecraft:blackstone");
                if (lift > 0) c.set(ox + x, top, oz + z, "minecraft:blackstone");
                for (int dy = lift > 0 ? 1 : 0; dy < depth; dy++) {
                    int y = top - dy;
                    String b;
                    if (dy == 0) b = edgeTop(R - r);
                    else if (dy <= 2) b = rnd.nextInt(5) == 0 ? "minecraft:basalt" : "minecraft:blackstone";
                    else b = rock(dy, depth);
                    c.set(ox + x, y, oz + z, b);
                }
            }
    }

    /** Gentle hills on the outer grounds; flat wherever something is built. */
    private int bump(int x, int z) {
        double r = Math.hypot(x, z);
        double R = edgeRadius(Math.atan2(z, x), 1.0);
        if (r < 19 || R - r < 4 || Math.abs(x) <= 5 || Math.abs(z) <= 5) return 0;
        for (HubLayout.Gate g : HubLayout.GATES) if (Math.hypot(x - g.cx(), z - g.cz()) < 11) return 0;
        for (int[] q : new int[][]{{1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
            if (Math.hypot(x - q[0] * 20, z - q[1] * 20) < 5) return 0;
            // lavafall channels run along the diagonals
            if (Math.abs(x * q[1] - z * q[0]) / Math.sqrt(2) < 3 && x * q[0] > 0 && z * q[1] > 0) return 0;
        }
        double h = 1.4 * Math.sin(x * 0.17 + 1) * Math.cos(z * 0.14) + 0.9 * Math.sin((x + z) * 0.09) + 0.6;
        double fade = Math.min(1, Math.min((r - 19) / 4, (R - r - 4) / 3));
        return (int) Math.max(0, Math.min(3, Math.round(h * fade)));
    }

    private int ground(int x, int z) { return Y + bump(x, z); }

    private String edgeTop(double fromEdge) {
        int n = rnd.nextInt(100);
        if (fromEdge < 1.5) return n < 45 ? "minecraft:blackstone" : n < 70 ? "minecraft:polished_blackstone" : n < 85 ? "minecraft:gilded_blackstone" : "minecraft:basalt";
        if (n < 34) return "minecraft:polished_blackstone";
        if (n < 54) return "minecraft:blackstone";
        if (n < 70) return "minecraft:polished_blackstone_bricks";
        if (n < 80) return "minecraft:cracked_polished_blackstone_bricks";
        if (n < 89) return "minecraft:gilded_blackstone";
        if (n < 95) return "minecraft:smooth_basalt";
        return "minecraft:chiseled_polished_blackstone";
    }

    private String rock(int dy, int depth) {
        int n = rnd.nextInt(100);
        if (n < 4) return "minecraft:nether_gold_ore";
        if (n < 6) return "minecraft:gilded_blackstone";
        if (n < 8) return "minecraft:magma_block";
        if (dy > depth - 3 && n < 35) return "minecraft:basalt";
        if (dy > 12) return n < 55 ? "minecraft:blackstone" : n < 80 ? "minecraft:basalt" : "minecraft:smooth_basalt";
        return n < 60 ? "minecraft:blackstone" : n < 80 ? "minecraft:polished_blackstone" : "minecraft:basalt";
    }

    // ------------------------------------------------------------------ plaza

    private void plaza() {
        for (int x = -16; x <= 16; x++)
            for (int z = -16; z <= 16; z++) {
                double r = Math.hypot(x, z);
                if (r > 16.4) continue;
                String b = ((Math.floorDiv(x, 2) + Math.floorDiv(z, 2)) & 1) == 0
                        ? "minecraft:polished_deepslate" : "minecraft:deepslate_tiles";
                if (r > 10.4 && r <= 11.6) b = "minecraft:red_nether_bricks";
                if (r > 15.0) b = "minecraft:polished_blackstone_bricks";
                c.set(x, Y, z, b);
                c.set(x, Y - 1, z, "minecraft:stone");
            }
        // floor lights between the red ring and the border
        for (int i = 0; i < 12; i++) {
            double a = i * Math.PI / 6 + Math.PI / 12;
            c.set((int) Math.round(13.2 * Math.cos(a)), Y, (int) Math.round(13.2 * Math.sin(a)), "minecraft:shroomlight");
        }
    }

    // ------------------------------------------------------------------ paths

    private void paths() {
        for (int d = 16; d <= HubLayout.GATE_DIST - 1; d++)
            for (int p = -2; p <= 2; p++) {
                String b = Math.abs(p) == 2 ? "minecraft:polished_blackstone_bricks" : "minecraft:polished_deepslate";
                if (Math.abs(p) == 1) b = "minecraft:red_nether_bricks";
                for (int[] dir : new int[][]{{0, -1}, {1, 0}, {0, 1}, {-1, 0}}) {
                    int x = dir[0] * d + (dir[0] == 0 ? p : 0);
                    int z = dir[1] * d + (dir[1] == 0 ? p : 0);
                    c.set(x, Y, z, b);
                }
            }
        // molten channels under glass from the crucible out to each gate
        for (int d = 9; d <= HubLayout.GATE_DIST - 3; d++)
            for (int[] dir : new int[][]{{0, -1}, {1, 0}, {0, 1}, {-1, 0}}) {
                int x = dir[0] * d, z = dir[1] * d;
                c.set(x, Y, z, "minecraft:orange_stained_glass");
                c.setLate(x, Y - 1, z, "minecraft:lava");
                c.set(x, Y - 2, z, "minecraft:magma_block");
                // keep the lava sealed in
                c.set(x + dir[1], Y - 1, z + dir[0], "minecraft:polished_blackstone");
                c.set(x - dir[1], Y - 1, z - dir[0], "minecraft:polished_blackstone");
            }
        // lamp posts along the paths
        for (int d = 18; d <= 28; d += 5)
            for (int[] dir : new int[][]{{0, -1}, {1, 0}, {0, 1}, {-1, 0}})
                for (int side : new int[]{-3, 3}) {
                    int x = dir[0] * d + (dir[0] == 0 ? side : 0);
                    int z = dir[1] * d + (dir[1] == 0 ? side : 0);
                    lampPost(x, z);
                }
    }

    private void lampPost(int x, int z) {
        c.set(x, Y, z, "minecraft:polished_blackstone_bricks");
        c.set(x, Y + 1, z, "minecraft:polished_blackstone_wall");
        c.set(x, Y + 2, z, "minecraft:polished_blackstone_wall");
        c.set(x, Y + 3, z, "minecraft:gilded_blackstone");
        c.setLate(x, Y + 4, z, "minecraft:lantern");
    }

    // ------------------------------------------------------------------ crucible

    private void crucible() {
        // raised spawn dais ringed by a lava moat sealed under glass: walk across the molten metal
        for (int x = -9; x <= 9; x++)
            for (int z = -9; z <= 9; z++) {
                double r = Math.hypot(x, z);
                if (r <= 3.6) {
                    c.set(x, Y + 1, z, "minecraft:polished_blackstone_bricks");
                    c.set(x, Y + 2, z, r <= 1.5 ? "minecraft:gold_block" : r <= 2.6 ? "minecraft:red_nether_bricks" : "minecraft:polished_deepslate");
                } else if (r <= 7.2) {
                    c.set(x, Y - 2, z, "minecraft:magma_block");
                    c.setLate(x, Y - 1, z, "minecraft:lava");
                    c.set(x, Y, z, "minecraft:orange_stained_glass");
                } else if (r <= 8.3) {
                    c.set(x, Y, z, "minecraft:polished_blackstone_bricks");
                    c.set(x, Y + 1, z, r > 7.8 ? "minecraft:gold_block" : (((x + z) & 1) == 0 ? "minecraft:nether_bricks" : "minecraft:red_nether_bricks"));
                }
            }
        // four bridges with steps up onto the dais
        int[][] dirs = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        String[] face = {"south", "west", "north", "east"};
        for (int i = 0; i < 4; i++) {
            int[] d = dirs[i];
            for (int dist = 4; dist <= 8; dist++)
                for (int p = -1; p <= 1; p++) {
                    int x = d[0] * dist + (d[0] == 0 ? p : 0), z = d[1] * dist + (d[1] == 0 ? p : 0);
                    c.set(x, Y + 1, z, Math.abs(p) == 1 ? "minecraft:red_nether_bricks" : "minecraft:polished_blackstone_bricks");
                    if (dist == 4) c.set(x, Y + 2, z, "minecraft:polished_blackstone_brick_stairs[facing=" + face[i] + "]");
                }
        }
        // smelter pillars with spouts over the moat (the molten pour is particles, see HubModule.ambient)
        for (int[] q : new int[][]{{1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
            int px = q[0] * 6, pz = q[1] * 6;
            for (int y = Y + 1; y <= Y + 7; y++) c.set(px, y, pz, y == Y + 4 ? "minecraft:shroomlight" : "minecraft:polished_blackstone_bricks");
            c.set(px, Y + 8, pz, "minecraft:gold_block");
            c.set(px, Y + 9, pz, "minecraft:shroomlight");
            c.set(px - q[0], Y + 8, pz, "minecraft:gilded_blackstone");
            c.set(px - q[0], Y + 8, pz - q[1], "minecraft:gold_block");
            for (int[] f : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
                if (!c.isSet(px + f[0], Y + 1, pz + f[1])) c.set(px + f[0], Y + 1, pz + f[1], "minecraft:netherite_block");
        }
        // floating alloy ingot overhead
        int iy = Y + 14;
        c.fill(-4, iy, -1, 4, iy, 1, "minecraft:gold_block");
        c.fill(-3, iy + 1, 0, 3, iy + 1, 0, "minecraft:gold_block");
        c.fill(-3, iy + 1, -1, 3, iy + 1, -1, "minecraft:raw_gold_block");
        c.fill(-3, iy + 1, 1, 3, iy + 1, 1, "minecraft:raw_gold_block");
        c.set(-4, iy + 1, 0, "minecraft:shroomlight");
        c.set(4, iy + 1, 0, "minecraft:shroomlight");
    }

    // ------------------------------------------------------------------ gates

    private interface Put { void at(int u, int y, int w, String b); }

    /** Arcade gate bands: glowing at the opening, cooling to blackstone at the edge. */
    private static final String[] FIRE = {"minecraft:shroomlight", "minecraft:orange_concrete", "minecraft:red_concrete",
            "minecraft:red_nether_bricks", "minecraft:nether_bricks", "minecraft:polished_blackstone_bricks"};

    private void gate(HubLayout.Gate g) {
        Put put = (u, y, w, b) -> {
            if (g.alongX()) c.set(g.cx() + u, y, g.cz() + w, b);
            else c.set(g.cx() + w, y, g.cz() + u, b);
        };
        String main, accent, trim, light;
        switch (g.theme()) {
            case "oxidized" -> { main = "minecraft:polished_blackstone_bricks"; accent = "minecraft:warped_wart_block"; trim = "minecraft:warped_planks"; light = "minecraft:sea_lantern"; }
            case "iron" -> { main = "minecraft:smooth_basalt"; accent = "minecraft:iron_block"; trim = "minecraft:polished_blackstone_bricks"; light = "minecraft:shroomlight"; }
            case "netherite" -> { main = "minecraft:polished_blackstone_bricks"; accent = "minecraft:netherite_block"; trim = "minecraft:red_nether_bricks"; light = "minecraft:shroomlight"; }
            default -> { main = "minecraft:polished_blackstone_bricks"; accent = "minecraft:gold_block"; trim = "minecraft:gold_block"; light = "minecraft:ochre_froglight"; }
        }
        int base = Y + 1;
        // plinth
        for (int u = -7; u <= 7; u++)
            for (int w = -2; w <= 2; w++)
                put.at(u, Y, w, Math.abs(u) <= 3 && Math.abs(w) <= 1 ? "minecraft:polished_blackstone" : accent);
        for (int u = -6; u <= 6; u++)
            for (int y = base; y <= base + 11; y++)
                for (int w = -1; w <= 1; w++) {
                    int au = Math.abs(u), ry = y - base;
                    boolean open = (ry <= 7 && au <= 3) || (ry == 8 && au <= 2) || (ry == 9 && au <= 1);
                    if (open) {
                        if (w == 0) put.at(u, y, w, "minecraft:nether_portal[axis=" + (g.alongX() ? "x" : "z") + "]");
                        continue;
                    }
                    if (au >= 5 && ry >= 11) continue;          // stepped top corners
                    boolean edgeOfOpening = (ry <= 8 && au == 4) || (ry == 8 && au == 3) || (ry == 9 && au == 2) || (ry == 10 && au <= 1);
                    String b = main;
                    if (au == 6 || ry == 11) b = accent;
                    if (edgeOfOpening) b = trim;
                    if (g.theme().equals("gold") && w != 0 && !(au == 6 || ry == 11)) {
                        // molten bands radiating out from the opening
                        int openTop = au <= 1 ? 9 : au == 2 ? 8 : au == 3 ? 7 : -1;
                        int layer = au >= 4 ? au - 4 : ry - openTop - 1;
                        b = FIRE[Math.max(0, Math.min(FIRE.length - 1, layer))];
                    }
                    if (au == 5 && (ry == 3 || ry == 6) && w != 0) b = light;
                    put.at(u, y, w, b);
                }
        crown(g, put, base + 12);
    }

    private void crown(HubLayout.Gate g, Put put, int y) {
        switch (g.theme()) {
            case "oxidized" -> {   // warped cap and two floating warped-fungus islets beside the gate
                for (int u = -5; u <= 5; u++)
                    for (int w = -1; w <= 1; w++) put.at(u, y - 1, w, "minecraft:warped_wart_block");
                for (int side : new int[]{-1, 1}) {
                    int cu = side * 10, fy = y - 5;
                    for (int u = -2; u <= 2; u++)
                        for (int w = -2; w <= 2; w++) {
                            int d2 = u * u + w * w;
                            if (d2 > 5) continue;
                            put.at(cu + u, fy, w, "minecraft:warped_nylium");
                            if (d2 <= 2) put.at(cu + u, fy - 1, w, "minecraft:blackstone");
                        }
                    put.at(cu, fy - 2, 0, "minecraft:basalt");
                    for (int k = 1; k <= 3; k++) put.at(cu, fy + k, 0, "minecraft:warped_stem");
                    for (int u = -2; u <= 2; u++)
                        for (int w = -2; w <= 2; w++)
                            for (int k = 3; k <= 5; k++)
                                if (Math.abs(u) + Math.abs(w) + (k - 3) <= 3 && !(u == 0 && w == 0 && k < 4))
                                    put.at(cu + u, fy + k, w, (u + w + k) % 5 == 0 ? "minecraft:shroomlight" : "minecraft:warped_wart_block");
                }
            }
            case "iron" -> {       // forge hearth with smoke
                for (int u = -3; u <= 3; u++)
                    for (int w = -1; w <= 1; w++) put.at(u, y, w, "minecraft:smooth_stone");
                put.at(0, y + 1, 0, "minecraft:blast_furnace[facing=north,lit=true]");
                put.at(-2, y + 1, 0, "minecraft:campfire[lit=true]");
                put.at(2, y + 1, 0, "minecraft:campfire[lit=true]");
                put.at(-3, y + 1, 0, "minecraft:anvil");
                put.at(3, y + 1, 0, "minecraft:anvil");
                put.at(0, y + 2, 0, "minecraft:iron_block");
            }
            case "netherite" -> {  // battlements
                for (int u = -6; u <= 6; u++)
                    for (int w = -1; w <= 1; w += 2)
                        if (Math.abs(u) <= 4 && (u & 1) == 0) {
                            put.at(u, y, w, "minecraft:polished_blackstone_bricks");
                            put.at(u, y + 1, w, "minecraft:crying_obsidian");
                        }
                put.at(0, y, 0, "minecraft:netherite_block");
                put.at(0, y + 1, 0, "minecraft:netherite_block");
                put.at(0, y + 2, 0, "minecraft:shroomlight");
            }
            default -> {           // arcade marquee
                for (int u = -4; u <= 4; u++) put.at(u, y, 0, "minecraft:gold_block");
                for (int u = -3; u <= 3; u++) put.at(u, y + 1, 0, u % 2 == 0 ? "minecraft:tnt" : "minecraft:note_block");
                put.at(0, y + 2, 0, "minecraft:jukebox");
            }
        }
    }

    // ------------------------------------------------------------------ grounds

    private void grounds() {
        // forge stations in each quadrant
        int[][] quads = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int[] q : quads) {
            int x = q[0] * 14, z = q[1] * 14;
            forgeStation(x + q[0] * 6, z + q[1] * 6, q);
            tree(q[0] * 30, q[1] * 22);
            tree(q[0] * 22, q[1] * 31);
            tree(q[0] * 36, q[1] * 34);
        }
        // scattered ore boulders and flowers
        // extra trees scattered over the hills
        for (int i = 0, planted = 0; i < 200 && planted < 14; i++) {
            int x = rnd.nextInt(90) - 45, z = rnd.nextInt(90) - 45;
            if (Math.hypot(x, z) < 21 || bump(x, z) == 0 && rnd.nextBoolean()) continue;
            if (Math.abs(x) <= 6 || Math.abs(z) <= 6) continue;
            boolean nearGate = false;
            for (HubLayout.Gate g : HubLayout.GATES) nearGate |= Math.hypot(x - g.cx(), z - g.cz()) < 12;
            if (nearGate || c.isSet(x, ground(x, z) + 2, z)) continue;
            tree(x, z);
            planted++;
        }
        // flowers, grass tufts and ore boulders
        for (int i = 0; i < 420; i++) {
            int x = rnd.nextInt(95) - 47, z = rnd.nextInt(95) - 47;
            double r = Math.hypot(x, z);
            if (r < 18 || !onMain(x, z) || Math.abs(x) <= 4 || Math.abs(z) <= 4) continue;
            int gy = ground(x, z);
            String top = c.get(x, gy, z);
            if (top == null || !top.contains("blackstone") || top.contains("bricks")) continue;
            if (c.isSet(x, gy + 1, z) || rnd.nextInt(3) > 0) continue;
            int n = rnd.nextInt(10);
            String deco = n < 3 ? "minecraft:polished_blackstone_button[face=floor]" : n < 5 ? "minecraft:gold_block" : n < 6 ? "minecraft:raw_gold_block"
                    : n < 7 ? "minecraft:lantern" : n < 8 ? "minecraft:soul_lantern" : n < 9 ? "minecraft:polished_blackstone_slab" : "minecraft:gilded_blackstone";
            c.setLate(x, gy + 1, z, deco);
        }
    }

    private void forgeStation(int x, int z, int[] q) {
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) c.set(x + dx, Y, z + dz, "minecraft:polished_blackstone");
        c.set(x, Y + 1, z, "minecraft:anvil");
        c.set(x + q[0] * 2, Y + 1, z, "minecraft:blast_furnace[lit=true]");
        c.set(x + q[0] * 2, Y + 1, z + q[1], "minecraft:barrel[facing=up]");
        c.set(x + q[0] * 2, Y + 2, z, "minecraft:polished_blackstone_wall");
        c.set(x, Y + 1, z + q[1] * 2, "minecraft:smithing_table");
        c.set(x - q[0], Y + 1, z + q[1] * 2, "minecraft:grindstone[face=floor]");
        c.set(x - q[0] * 2, Y + 1, z - q[1] * 2, "minecraft:gold_block");
        c.setLate(x - q[0] * 2, Y + 2, z - q[1] * 2, "minecraft:lantern");
    }

    /** Blackstone obelisk with a gold cap and a flame, in place of trees. */
    private void tree(int x, int z) {
        if (!onMain(x, z) || Math.hypot(x, z) > edgeRadius(Math.atan2(z, x), 1) - 4) return;
        int h = 6 + rnd.nextInt(4), gy = ground(x, z);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            c.set(x + dx, gy, z + dz, "minecraft:polished_blackstone_bricks");
            c.set(x + dx, gy + 1, z + dz, Math.abs(dx) + Math.abs(dz) == 2 ? "minecraft:gold_block" : "minecraft:chiseled_polished_blackstone");
        }
        for (int y = 2; y <= h; y++) c.set(x, gy + y, z, y % 3 == 0 ? "minecraft:gilded_blackstone" : "minecraft:polished_blackstone");
        c.set(x, gy + h + 1, z, "minecraft:gold_block");
        c.setLate(x, gy + h + 2, z, rnd.nextBoolean() ? "minecraft:campfire[lit=true]" : "minecraft:soul_campfire[lit=true]");
        for (int[] k : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) c.setLate(x + k[0], gy + 2, z + k[1], "minecraft:lantern");
    }

    /** Gold lines radiating from the plaza, and a gold ring around the grounds. */
    private void goldInlay() {
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 8 + i * Math.PI / 4;
            if (i % 2 == 0) continue;   // the lavafall channels already run along the diagonals
            for (double d = 17; d < 52; d += 0.5) {
                int x = (int) Math.round(d * Math.cos(a)), z = (int) Math.round(d * Math.sin(a));
                if (!onMain(x, z)) break;
                int gy = ground(x, z);
                String cur = c.get(x, gy, z);
                if (cur != null && cur.contains("blackstone") || cur != null && cur.contains("basalt")) c.set(x, gy, z, "minecraft:gold_block");
            }
        }
        for (int i = 0; i < 720; i++) {
            double a = i * Math.PI * 2 / 720;
            int x = (int) Math.round(38 * Math.cos(a)), z = (int) Math.round(38 * Math.sin(a));
            if (!onMain(x, z)) continue;
            int gy = ground(x, z);
            String cur = c.get(x, gy, z);
            if (cur != null && (cur.contains("blackstone") || cur.contains("basalt"))) c.set(x, gy, z, i % 2 == 0 ? "minecraft:gold_block" : "minecraft:raw_gold_block");
        }
    }

    // ------------------------------------------------------------------ lavafalls

    private void lavafalls() {
        for (int i = 0; i < 4; i++) {
            double a = Math.PI / 4 + i * Math.PI / 2;
            double R = edgeRadius(a, 1);
            for (double d = 19; d <= R + 0.5; d += 0.5) {
                int x = (int) Math.round(d * Math.cos(a)), z = (int) Math.round(d * Math.sin(a));
                if (!onMain(x, z)) continue;
                for (int[] o : new int[][]{{0, 0}, {1, 0}, {0, 1}}) {
                    int px = x + o[0], pz = z + o[1];
                    if (!onMain(px, pz)) continue;
                    c.set(px, Y, pz, "minecraft:orange_stained_glass");
                    c.setLate(px, Y - 1, pz, "minecraft:lava");
                    c.set(px, Y - 2, pz, "minecraft:magma_block");
                }
            }
            // a rail where each river pours off the island, so nobody walks off into the lavafall
            for (int x = -55; x <= 55; x++)
                for (int z = -55; z <= 55; z++) {
                    if (!onMain(x, z) || edgeDist(x, z) > 1.6) continue;
                    double along = x * Math.cos(a) + z * Math.sin(a), across = -x * Math.sin(a) + z * Math.cos(a);
                    if (along > 0 && Math.abs(across) <= 4) c.set(x, ground(x, z) + 1, z, "minecraft:polished_blackstone_wall");
                }
        }
        // re-seal channel walls so lava only escapes at the island edge
        for (int x = -55; x <= 55; x++)
            for (int z = -55; z <= 55; z++) {
                String b = c.get(x, Y - 1, z);
                if (b == null || !b.equals("minecraft:lava")) continue;
                for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int nx = x + o[0], nz = z + o[1];
                    if (onMain(nx, nz) && !"minecraft:lava".equals(c.get(nx, Y - 1, nz)))
                        c.set(nx, Y - 1, nz, "minecraft:polished_blackstone");
                }
            }
    }

    /** How far a column is inside the island edge. */
    private double edgeDist(int x, int z) { return edgeRadius(Math.atan2(z, x), 1.0) - Math.hypot(x, z); }

    // ------------------------------------------------------------------ floating islands

    private void floatingIslands() {
        double[][] spots = {{0.35, 66, -6}, {1.3, 70, 8}, {2.15, 62, -12}, {2.9, 72, 4}, {3.8, 64, -4}, {4.6, 68, 10}, {5.5, 63, -10}};
        for (double[] s : spots) {
            int ox = (int) (s[1] * Math.cos(s[0])), oz = (int) (s[1] * Math.sin(s[0]));
            double scale = 0.11 + rnd.nextDouble() * 0.06;
            island(ox, (int) s[2], oz, scale, false);
            int ty = Y + (int) s[2];
            int n = rnd.nextInt(3);
            if (n == 0) {
                for (int y = 1; y <= 5; y++) c.set(ox, ty + y, oz, y % 2 == 0 ? "minecraft:gilded_blackstone" : "minecraft:polished_blackstone");
                c.set(ox, ty + 6, oz, "minecraft:gold_block");
                c.setLate(ox, ty + 7, oz, "minecraft:soul_campfire[lit=true]");
            } else if (n == 1) {
                c.set(ox, ty + 1, oz, "minecraft:raw_gold_block");
                c.set(ox + 1, ty + 1, oz, "minecraft:gold_block");
                c.set(ox, ty + 2, oz, "minecraft:raw_gold_block");
                c.set(ox, ty + 1, oz + 1, "minecraft:gilded_blackstone");
            } else {
                c.set(ox, ty, oz, "minecraft:magma_block");
                c.setLate(ox, ty + 1, oz, "minecraft:campfire[lit=true]");
                c.set(ox + 1, ty + 1, oz, "minecraft:gold_block");
                c.setLate(ox + 1, ty + 2, oz, "minecraft:lantern");
            }
        }
    }

    // ------------------------------------------------------------------ lettering

    private void lettering() {
        String text = "BLACKFORGE";
        int scale = 2, spacing = 1;
        int width = BlockFont.width(text, spacing) * scale;
        int x0 = -width / 2, y0 = Y + 27, z = -76;
        BlockFont.draw(text, spacing, (col, row, glyphRow) -> {
            boolean forge = col >= (5 + spacing) * 5;   // "BLACK" | "FORGE"
            String b;
            if (forge) b = glyphRow <= 1 ? "minecraft:shroomlight" : glyphRow <= 4 ? "minecraft:gold_block" : "minecraft:raw_gold_block";
            else b = glyphRow <= 1 ? "minecraft:netherite_block" : glyphRow <= 4 ? "minecraft:polished_blackstone_bricks" : "minecraft:blackstone";
            String plate = forge ? "minecraft:polished_blackstone" : "minecraft:gold_block";
            for (int sx = 0; sx < scale; sx++)
                for (int sy = 0; sy < scale; sy++) {
                    int x = x0 + col * scale + sx, y = y0 + row * scale + sy;
                    c.set(x, y, z, b);
                    c.set(x, y, z - 1, b);
                    // contrasting outline / backing plate
                    for (int ox = -1; ox <= 1; ox++)
                        for (int oy = -1; oy <= 1; oy++)
                            if (!c.isSet(x + ox, y + oy, z - 2)) c.set(x + ox, y + oy, z - 2, plate);
                }
        });
        // chains anchoring the sign to two floating islands below
        for (int side : new int[]{-1, 1}) {
            int ax = side * (width / 2 - 6);
            for (int y = Y + 4; y < y0 - 1; y++) c.set(ax, y, z - 2, "minecraft:iron_chain[axis=y]");
            island(ax, 3, z - 2, 0.12, false);
        }
    }
}
