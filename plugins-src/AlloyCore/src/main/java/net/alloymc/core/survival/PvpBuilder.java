package net.alloymc.core.survival;

import net.alloymc.core.build.BlockFont;
import net.alloymc.core.build.Canvas;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static net.alloymc.core.survival.SurvivalLayout.*;

/**
 * The Scrapyard: an abandoned foundry district floating in the sky, walled in on all sides.
 * Rusted copper ruins, a collapsed smelter with smoking stacks, cranes, scrap heaps and lava pits.
 */
public final class PvpBuilder {
    private final Canvas c = new Canvas();
    private final Random rnd = new Random(0x5C4A9);
    private final int H = PVP_HALF;
    private final List<int[]> taken = new ArrayList<>();   // x, z, radius of placed structures

    public static Canvas build() {
        PvpBuilder b = new PvpBuilder();
        b.ground();
        b.smelter(0, 0);
        b.chimney(14, -9, 30);
        b.chimney(-12, 13, 24);
        b.gatehouses();
        for (int i = 0; i < 14; i++) b.ruin();
        for (int i = 0; i < 4; i++) b.crane();
        for (int i = 0; i < 7; i++) b.lavaPit();
        for (int i = 0; i < 26; i++) b.scrapHeap();
        b.pipes();
        b.rails();
        b.debris();
        b.walls();
        b.lettering();
        return b.c;
    }

    // ------------------------------------------------------------------ helpers

    private void set(int x, int y, int z, String b) { c.set(PVP_X + x, PVP_Y + y, PVP_Z + z, b); }
    private void late(int x, int y, int z, String b) { c.setLate(PVP_X + x, PVP_Y + y, PVP_Z + z, b); }
    private String get(int x, int y, int z) { return c.get(PVP_X + x, PVP_Y + y, PVP_Z + z); }

    /** Terrain height above PVP_Y (0..5). */
    int height(int x, int z) {
        double h = 2.0 * Math.sin(x * 0.07 + 1) * Math.cos(z * 0.06) + 1.3 * Math.sin((x + z) * 0.045) + 1.6;
        return (int) Math.max(0, Math.min(5, Math.round(h)));
    }

    private boolean free(int x, int z, int r) {
        if (Math.abs(x) > H - r - 8 || Math.abs(z) > H - r - 8) return false;
        if (Math.abs(x) < r + 14 && Math.abs(z) < r + 14) return false;            // keep the smelter clear
        for (int[] t : taken) if (Math.hypot(x - t[0], z - t[1]) < r + t[2] + 4) return false;
        // gatehouse approaches
        for (int[] g : new int[][]{{0, H - 8}, {0, -H + 8}, {H - 8, 0}, {-H + 8, 0}}) if (Math.hypot(x - g[0], z - g[1]) < r + 12) return false;
        return true;
    }

    private int[] spot(int r) {
        for (int i = 0; i < 400; i++) {
            int x = rnd.nextInt(2 * H) - H, z = rnd.nextInt(2 * H) - H;
            if (free(x, z, r)) { taken.add(new int[]{x, z, r}); return new int[]{x, z}; }
        }
        return null;
    }

    private String pick(String... opts) { return opts[rnd.nextInt(opts.length)]; }

    // ------------------------------------------------------------------ terrain

    private void ground() {
        for (int x = -H - 3; x <= H + 3; x++)
            for (int z = -H - 3; z <= H + 3; z++) {
                int h = Math.abs(x) > H || Math.abs(z) > H ? 0 : height(x, z);
                double n = Math.sin(x * 0.21 + z * 0.13) + Math.cos(z * 0.19 - x * 0.07) + rnd.nextDouble() * 0.8;
                String top = n > 1.5 ? "minecraft:gravel" : n > 0.9 ? "minecraft:coarse_dirt" : n > 0.3 ? "minecraft:packed_mud"
                        : n > -0.3 ? "minecraft:tuff" : n > -0.9 ? "minecraft:rooted_dirt" : n > -1.4 ? "minecraft:smooth_basalt" : "minecraft:mud_bricks";
                if (rnd.nextInt(60) == 0) top = "minecraft:waxed_oxidized_copper";
                if (rnd.nextInt(80) == 0) top = "minecraft:raw_iron_block";
                set(x, h, z, top);
                for (int y = h - 1; y >= -1; y--) set(x, y, z, y >= h - 2 ? "minecraft:dirt" : "minecraft:tuff");
                // rocky underside, deeper towards the middle
                double edge = Math.min(H + 3 - Math.abs(x), H + 3 - Math.abs(z));
                int depth = (int) Math.min(26, 3 + Math.sqrt(Math.max(0, edge)) * 2.2 + rnd.nextInt(3));
                for (int d = 2; d <= depth; d++) {
                    int k = rnd.nextInt(100);
                    set(x, -d, z, k < 3 ? "minecraft:copper_ore" : k < 5 ? "minecraft:iron_ore" : d > 12 ? "minecraft:deepslate" : k < 70 ? "minecraft:stone" : "minecraft:tuff");
                }
            }
    }

    // ------------------------------------------------------------------ centrepiece

    /** The collapsed smelter: a broken round tower with a lava heart and doorways on four sides. */
    private void smelter(int cx, int cz) {
        int base = height(cx, cz);
        for (int x = -11; x <= 11; x++)
            for (int z = -11; z <= 11; z++) {
                double r = Math.hypot(x, z);
                if (r > 10.5) continue;
                set(cx + x, base, cz + z, r < 3.2 ? "minecraft:magma_block" : ((x + z) & 1) == 0 ? "minecraft:cracked_deepslate_tiles" : "minecraft:polished_blackstone_bricks");
                for (int y = base + 1; y <= base + 6; y++) if (get(cx + x, y, cz + z) != null) set(cx + x, y, cz + z, "minecraft:air");
            }
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) if (Math.hypot(x, z) < 2.6) late(cx + x, base, cz + z, "minecraft:lava");
        // tower wall with a ragged, collapsed top
        for (int i = 0; i < 360; i++) {
            double a = Math.toRadians(i);
            int x = (int) Math.round(8.5 * Math.cos(a)), z = (int) Math.round(8.5 * Math.sin(a));
            boolean door = Math.abs(x) <= 1 || Math.abs(z) <= 1;
            int top = 10 + (int) (7 * Math.abs(Math.sin(a * 2.5 + 0.7))) + (int) (4 * Math.sin(a * 7));
            for (int y = 1; y <= top; y++) {
                if (door && y <= 4) continue;
                for (int t = 0; t <= 1; t++) {
                    int px = (int) Math.round((8.5 + t) * Math.cos(a)), pz = (int) Math.round((8.5 + t) * Math.sin(a));
                    String b = y % 6 == 0 ? "minecraft:waxed_weathered_cut_copper" : pick("minecraft:bricks", "minecraft:bricks", "minecraft:cracked_deepslate_bricks", "minecraft:deepslate_bricks", "minecraft:mud_bricks");
                    if (y == top && rnd.nextInt(3) == 0) b = "minecraft:air";
                    set(cx + px, base + y, cz + pz, b);
                }
            }
        }
        // inner gallery ring to fight on, reached by a ramp of slabs
        for (int i = 0; i < 360; i += 2) {
            double a = Math.toRadians(i);
            for (double rr = 6.5; rr <= 7.6; rr += 0.5)
                set(cx + (int) Math.round(rr * Math.cos(a)), base + 6, cz + (int) Math.round(rr * Math.sin(a)), "minecraft:waxed_cut_copper");
        }
        for (int k = 0; k < 6; k++) set(cx + 6, base + 1 + k, cz - 5 + k, "minecraft:polished_blackstone_brick_slab[type=top]");
        for (int k = 0; k < 6; k++) set(cx + 6, base + k, cz - 5 + k, "minecraft:polished_blackstone_bricks");
        // rubble spilling out of the breaches
        for (int i = 0; i < 70; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, r = 10 + rnd.nextDouble() * 6;
            int x = cx + (int) (r * Math.cos(a)), z = cz + (int) (r * Math.sin(a));
            set(x, height(x, z) + 1, z, pick("minecraft:bricks", "minecraft:cracked_deepslate_bricks", "minecraft:cobbled_deepslate", "minecraft:mud_bricks", "minecraft:waxed_weathered_copper"));
        }
        taken.add(new int[]{cx, cz, 16});
    }

    private void chimney(int cx, int cz, int h) {
        int base = height(cx, cz);
        for (int y = 1; y <= h; y++)
            for (int x = -2; x <= 2; x++)
                for (int z = -2; z <= 2; z++) {
                    if (Math.abs(x) == 2 && Math.abs(z) == 2) continue;
                    boolean wall = Math.abs(x) == 2 || Math.abs(z) == 2;
                    if (!wall) continue;
                    set(cx + x, base + y, cz + z, y % 7 == 0 ? "minecraft:waxed_oxidized_cut_copper" : "minecraft:bricks");
                }
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) late(cx + x, base + h - 1, cz + z, "minecraft:campfire[lit=true,signal_fire=true]");
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) set(cx + x, base + h - 2, cz + z, "minecraft:hay_block");
        taken.add(new int[]{cx, cz, 4});
    }

    // ------------------------------------------------------------------ ruins

    private void ruin() {
        int w = 7 + rnd.nextInt(7), d = 7 + rnd.nextInt(5);
        int[] s = spot(Math.max(w, d) / 2 + 1);
        if (s == null) return;
        int x0 = s[0] - w / 2, z0 = s[1] - d / 2;
        int base = height(s[0], s[1]);
        String[][] styles = {
            {"minecraft:waxed_weathered_cut_copper", "minecraft:waxed_oxidized_copper", "minecraft:waxed_exposed_cut_copper"},
            {"minecraft:bricks", "minecraft:cracked_deepslate_bricks", "minecraft:mud_bricks"},
            {"minecraft:deepslate_bricks", "minecraft:cracked_deepslate_bricks", "minecraft:waxed_weathered_copper"},
            {"minecraft:tuff_bricks", "minecraft:cracked_stone_bricks", "minecraft:waxed_oxidized_cut_copper"},
        };
        String[] st = styles[rnd.nextInt(styles.length)];
        int wallH = 4 + rnd.nextInt(5);
        double tilt = rnd.nextDouble() * Math.PI * 2;
        for (int x = 0; x < w; x++)
            for (int z = 0; z < d; z++) {
                int gx = x0 + x, gz = z0 + z;
                for (int y = height(gx, gz) + 1; y <= base; y++) set(gx, y, gz, "minecraft:cobbled_deepslate");   // level the footprint
                set(gx, base, gz, rnd.nextInt(4) == 0 ? "minecraft:cracked_deepslate_tiles" : "minecraft:deepslate_tiles");
                for (int y = base + 1; y <= base + wallH + 1; y++) set(gx, y, gz, "minecraft:air");
                boolean wall = x == 0 || z == 0 || x == w - 1 || z == d - 1;
                if (!wall) continue;
                // collapse: the wall's height drifts with a wave, with holes punched through
                int top = (int) Math.round(wallH * (0.45 + 0.55 * Math.abs(Math.sin((x + z) * 0.45 + tilt))));
                for (int y = 1; y <= top; y++) {
                    if (rnd.nextInt(9) == 0) continue;
                    boolean window = y == 3 && (x + z) % 3 == 1;
                    set(gx, base + y, gz, window ? "minecraft:iron_bars" : st[rnd.nextInt(st.length)]);
                }
            }
        // doorways
        set(x0 + w / 2, base + 1, z0, "minecraft:air"); set(x0 + w / 2, base + 2, z0, "minecraft:air");
        set(x0 + w / 2, base + 1, z0 + d - 1, "minecraft:air"); set(x0 + w / 2, base + 2, z0 + d - 1, "minecraft:air");
        set(x0, base + 1, z0 + d / 2, "minecraft:air"); set(x0, base + 2, z0 + d / 2, "minecraft:air");
        // half-collapsed roof beams and interior cover
        for (int x = 1; x < w - 1; x++) if (rnd.nextInt(3) > 0) set(x0 + x, base + wallH, z0 + d / 2, "minecraft:stripped_dark_oak_log[axis=x]");
        for (int i = 0; i < 3; i++) {
            int x = x0 + 1 + rnd.nextInt(w - 2), z = z0 + 1 + rnd.nextInt(d - 2);
            set(x, base + 1, z, pick("minecraft:barrel[facing=up]", "minecraft:damaged_anvil", "minecraft:cauldron", "minecraft:blast_furnace[facing=north]", "minecraft:waxed_copper_grate"));
        }
        for (int i = 0; i < 4; i++) late(x0 + rnd.nextInt(w), base + 1 + rnd.nextInt(3), z0 + rnd.nextInt(d), "minecraft:cobweb");
    }

    // ------------------------------------------------------------------ cranes, heaps, pits

    private void crane() {
        int[] s = spot(4);
        if (s == null) return;
        int base = height(s[0], s[1]), h = 18 + rnd.nextInt(6);
        for (int y = 1; y <= h; y++)
            for (int[] k : new int[][]{{0, 0}, {1, 0}, {0, 1}, {1, 1}})
                set(s[0] + k[0], base + y, s[1] + k[1], (y % 4 == 0) ? "minecraft:waxed_oxidized_cut_copper" : "minecraft:iron_bars");
        for (int[] k : new int[][]{{0, 0}, {1, 0}, {0, 1}, {1, 1}}) set(s[0] + k[0], base + h + 1, s[1] + k[1], "minecraft:waxed_oxidized_copper");
        boolean alongX = rnd.nextBoolean();
        int dir = rnd.nextBoolean() ? 1 : -1, len = 10 + rnd.nextInt(5);
        for (int i = -4; i <= len; i++) {
            int jx = s[0] + (alongX ? i * dir : 0), jz = s[1] + (alongX ? 0 : i * dir);
            set(jx, base + h + 2, jz, i < 0 ? "minecraft:waxed_oxidized_copper" : "minecraft:waxed_oxidized_cut_copper");
        }
        int hx = s[0] + (alongX ? len * dir : 0), hz = s[1] + (alongX ? 0 : len * dir);
        int drop = 6 + rnd.nextInt(5);
        for (int y = 1; y <= drop; y++) set(hx, base + h + 2 - y, hz, "minecraft:iron_chain[axis=y]");
        int ly = base + h + 1 - drop;
        set(hx, ly, hz, "minecraft:anvil");
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) set(hx + x, ly - 1, hz + z, pick("minecraft:waxed_weathered_copper", "minecraft:iron_block", "minecraft:raw_copper_block"));
    }

    private void scrapHeap() {
        int r = 2 + rnd.nextInt(3);
        int[] s = spot(r);
        if (s == null) return;
        for (int x = -r; x <= r; x++)
            for (int z = -r; z <= r; z++) {
                double dd = Math.hypot(x, z);
                if (dd > r + 0.3) continue;
                int g = height(s[0] + x, s[1] + z);
                int hh = (int) Math.round((r + 0.5 - dd) * 1.1) + rnd.nextInt(2);
                for (int y = 1; y <= hh; y++)
                    set(s[0] + x, g + y, s[1] + z, pick("minecraft:raw_iron_block", "minecraft:waxed_weathered_copper", "minecraft:waxed_oxidized_copper", "minecraft:waxed_copper_grate",
                            "minecraft:iron_block", "minecraft:cobbled_deepslate", "minecraft:raw_copper_block", "minecraft:waxed_exposed_copper", "minecraft:gravel", "minecraft:coal_block"));
                if (hh > 0 && rnd.nextInt(5) == 0) late(s[0] + x, g + hh + 1, s[1] + z, pick("minecraft:iron_chain[axis=y]", "minecraft:lightning_rod", "minecraft:damaged_anvil", "minecraft:cauldron", "minecraft:iron_bars"));
            }
    }

    private void lavaPit() {
        int r = 2 + rnd.nextInt(3);
        int[] s = spot(r + 1);
        if (s == null) return;
        int g = height(s[0], s[1]);
        for (int x = -r - 1; x <= r + 1; x++)
            for (int z = -r - 1; z <= r + 1; z++) {
                double dd = Math.hypot(x, z);
                if (dd > r + 1.3) continue;
                int px = s[0] + x, pz = s[1] + z;
                for (int y = height(px, pz); y > g; y--) set(px, y, pz, "minecraft:air");
                if (dd <= r) {
                    set(px, g - 1, pz, "minecraft:magma_block");
                    set(px, g - 2, pz, "minecraft:blackstone");
                    late(px, g, pz, "minecraft:lava");
                } else set(px, g, pz, pick("minecraft:magma_block", "minecraft:blackstone", "minecraft:basalt", "minecraft:smooth_basalt"));
            }
    }

    private void pipes() {
        for (int run = 0; run < 5; run++) {
            int[] s = spot(2);
            if (s == null) continue;
            taken.removeLast();
            boolean alongX = rnd.nextBoolean();
            int len = 20 + rnd.nextInt(20), dir = rnd.nextBoolean() ? 1 : -1;
            int y0 = height(s[0], s[1]) + 4;
            for (int i = 0; i < len; i++) {
                int x = s[0] + (alongX ? i * dir : 0), z = s[1] + (alongX ? 0 : i * dir);
                if (Math.abs(x) > H - 6 || Math.abs(z) > H - 6) break;
                if (get(x, y0, z) != null && !"minecraft:air".equals(get(x, y0, z))) continue;
                set(x, y0, z, i % 6 == 0 ? "minecraft:waxed_copper_grate" : "minecraft:waxed_weathered_copper");
                if (i % 8 == 0) for (int y = height(x, z) + 1; y < y0; y++) set(x, y, z, "minecraft:iron_chain[axis=y]");
            }
        }
    }

    private void rails() {
        for (int run = 0; run < 4; run++) {
            int x = rnd.nextInt(2 * H - 30) - H + 15, z = rnd.nextInt(2 * H - 30) - H + 15;
            boolean alongX = rnd.nextBoolean();
            int len = 15 + rnd.nextInt(20);
            for (int i = 0; i < len; i++) {
                int px = x + (alongX ? i : 0), pz = z + (alongX ? 0 : i);
                int g = height(px, pz);
                String below = get(px, g, pz);
                if (below == null || below.contains("lava") || get(px, g + 1, pz) != null) continue;
                if (rnd.nextInt(7) == 0) continue;   // broken track
                set(px, g, pz, "minecraft:gravel");
                late(px, g + 1, pz, "minecraft:rail[shape=" + (alongX ? "east_west" : "north_south") + "]");
            }
        }
    }

    private void debris() {
        for (int i = 0; i < 900; i++) {
            int x = rnd.nextInt(2 * H - 4) - H + 2, z = rnd.nextInt(2 * H - 4) - H + 2;
            int g = height(x, z);
            String top = get(x, g, z);
            if (top == null || top.contains("lava") || top.contains("magma") || get(x, g + 1, z) != null) continue;
            int n = rnd.nextInt(20);
            String b = n < 7 ? "minecraft:dead_bush" : n < 10 ? "minecraft:short_dry_grass" : n < 12 ? "minecraft:bone_block" : n < 13 ? "minecraft:skeleton_skull"
                    : n < 15 ? "minecraft:cobweb" : n < 17 ? "minecraft:cobbled_deepslate" : n < 18 ? "minecraft:iron_bars" : "minecraft:brown_mushroom";
            if (b.equals("minecraft:dead_bush") || b.equals("minecraft:short_dry_grass")) set(x, g, z, "minecraft:coarse_dirt");
            late(x, g + 1, z, b);
        }
    }

    // ------------------------------------------------------------------ walls + entrances

    private void walls() {
        for (int x = -H - 2; x <= H + 2; x++)
            for (int z = -H - 2; z <= H + 2; z++) {
                int ax = Math.abs(x), az = Math.abs(z);
                if (ax <= H && az <= H) continue;
                for (int y = -3; y <= PVP_WALL; y++) {
                    String b = (y == 5 || y == 11) ? "minecraft:waxed_cut_copper" : y == PVP_WALL ? "minecraft:polished_blackstone_bricks" : "minecraft:polished_blackstone_bricks";
                    if (y == 8 && ((x + z) % 9 == 0)) b = "minecraft:shroomlight";
                    if (y < 0 && rnd.nextInt(3) == 0) b = "minecraft:blackstone";
                    set(x, y, z, b);
                }
                if (((x + z) & 1) == 0 && (ax == H + 2 || az == H + 2)) set(x, PVP_WALL + 1, z, "minecraft:polished_blackstone_brick_wall");
                for (int y = PVP_WALL + 2; y <= PVP_WALL + 45; y++) set(x, y, z, "minecraft:barrier");
            }
        // watchtowers on the corners
        for (int[] k : new int[][]{{1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
            int cx = k[0] * (H + 1), cz = k[1] * (H + 1);
            for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++)
                for (int y = -4; y <= PVP_WALL + 8; y++) {
                    boolean shell = Math.abs(x) == 4 || Math.abs(z) == 4;
                    if (!shell && y > 0 && y < PVP_WALL + 7) continue;
                    String b = (y - PVP_WALL) % 4 == 0 ? "minecraft:waxed_cut_copper" : "minecraft:deepslate_bricks";
                    if (shell && y % 5 == 2 && (x == 0 || z == 0)) b = "minecraft:waxed_copper_bulb[lit=true]";
                    set(cx + x, y, cz + z, b);
                }
            for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++)
                if (Math.abs(x) == 5 || Math.abs(z) == 5) { set(cx + x, PVP_WALL + 8, cz + z, "minecraft:waxed_cut_copper"); if (((x + z) & 1) == 0) set(cx + x, PVP_WALL + 9, cz + z, "minecraft:polished_blackstone_brick_wall"); }
            set(cx, PVP_WALL + 9, cz, "minecraft:soul_campfire[lit=true]");
        }
    }

    /** Entry pads on each side: a sheltered alcove players arrive in. */
    private void gatehouses() {
        for (int[] d : new int[][]{{0, 1}, {1, 0}, {0, -1}, {-1, 0}}) {
            int cx = d[0] * (H - 6), cz = d[1] * (H - 6);
            int g = height(cx, cz);
            for (int u = -4; u <= 4; u++)
                for (int v = -4; v <= 4; v++) {
                    int x = cx + (d[0] == 0 ? u : v), z = cz + (d[0] == 0 ? v : u);
                    for (int y = height(x, z) + 1; y <= g + 7; y++) set(x, y, z, "minecraft:air");
                    for (int y = height(x, z); y < g; y++) set(x, y, z, "minecraft:cobbled_deepslate");
                    set(x, g, z, Math.abs(u) <= 1 && Math.abs(v) <= 1 ? "minecraft:crimson_nylium" : "minecraft:polished_blackstone_bricks");
                    boolean post = Math.abs(u) == 4 && Math.abs(v) == 4;
                    if (post) for (int y = 1; y <= 5; y++) set(x, g + y, z, y == 3 ? "minecraft:shroomlight" : "minecraft:polished_blackstone_bricks");
                    if (Math.abs(v) <= 4 && Math.abs(u) <= 4 && (Math.abs(u) == 4 || Math.abs(v) == 4)) set(x, g + 6, z, "minecraft:waxed_cut_copper");
                }
            taken.add(new int[]{cx, cz, 6});
        }
    }

    /** "SCRAPYARD" cast in rusted copper on the north wall, facing in. */
    private void lettering() {
        String text = "SCRAPYARD";
        int w = BlockFont.width(text, 1) * 2;
        BlockFont.draw(text, 1, (col, row, gr) -> {
            for (int sx = 0; sx < 2; sx++) for (int sy = 0; sy < 2; sy++) {
                int x = -w / 2 + col * 2 + sx, y = PVP_WALL + 3 + row * 2 + sy;
                set(x, y, -H - 2, gr <= 1 ? "minecraft:waxed_copper_block" : gr <= 4 ? "minecraft:waxed_weathered_copper" : "minecraft:waxed_oxidized_copper");
                set(x, y, -H - 3, "minecraft:polished_blackstone");
            }
        });
    }
}
