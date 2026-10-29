package net.alloymc.core.arcade.games.bedwars;

import net.alloymc.core.arcade.core.*;

/**
 * Forge Wars maps. Same ring as BedWars (team islands round a centre, diamond islands between them) with a new island
 * design: every team base has a bed dais, a covered forge over the generator, and three stalls (Item Shop, Team
 * Upgrades, Arsenal). The centre is "The Core": four emerald pedestals round an empty plaza saved for the objective.
 *
 * Base-local coordinates: u = outward from the centre, v = sideways. Along u: spawn -4, bed 3-4, forge 8.
 */
final class ForgeWarsMaps {
    private ForgeWarsMaps() {}

    /** Blocks one map is built from. {@code arsenal} and {@code core} are the Arsenal stall and the Core plaza. */
    record Theme(String top, String mid, String bottom, String accent, String path, String light, String arsenal, String core, String coreRim) {}

    static final Theme MOLTEN = new Theme("polished_blackstone_bricks", "blackstone", "basalt[axis=y]", "gilded_blackstone",
            "polished_blackstone", "shroomlight", "crying_obsidian", "chiseled_polished_blackstone", "orange_concrete");
    static final Theme SKYFORGE = new Theme("tuff_bricks", "polished_tuff", "tuff", "waxed_cut_copper",
            "polished_deepslate", "copper_lantern", "waxed_oxidized_cut_copper", "chiseled_tuff_bricks", "waxed_copper_block");
    static final Theme EMBER = new Theme("red_nether_bricks", "nether_bricks", "netherrack", "quartz_pillar[axis=y]",
            "polished_basalt[axis=y]", "glowstone", "crimson_planks", "chiseled_quartz_block", "red_concrete");

    static final int CORE_R = 24;        // centre island radius
    static final int PLAZA_R = 5;        // the empty objective plaza in the middle of the Core

    // the Black Market cave under the Core
    static final int CAVE_FLOOR = -15;   // floor blocks; players stand at y = -14
    static final double CAVE_R = 11;     // average cave radius (the wall wobbles about +/-2 round it)
    static final int TUNNEL_TOP = 19;    // stairs start at x = +/-19 on the surface and end at x = +/-5 on the cave floor

    static BuiltMap build(String id, String name, String desc, int teams, int ringR, Theme t, String... variants) {
        MapKit k = new MapKit(id.hashCode());
        MapDef d = new MapDef(ForgeWars.ID, id);
        d.display = name; d.description = desc; d.teams = teams;
        d.minPlayers = 2; d.maxPlayers = teams * 4;
        d.variants.addAll(java.util.List.of(variants));
        d.builderVersion = 2;   // v2: bigger Core with the Black Market cave (a bump makes servers rebuild the template)
        int ext = ringR + 26;
        d.bounds = Box.of(-ext, -40, -ext, ext, 40, ext);
        d.voidY = -22;
        d.spectator = new Pt(0.5, 32, 0.5, 0, 60);

        core(k, d, teams, t);
        for (int i = 0; i < teams; i++) base(k, d, i, teams, ringR, t);
        d.waiting = d.spectator;
        return new BuiltMap(d, k.c);
    }

    // ------------------------------------------------------------------ the Core

    private static void core(MapKit k, MapDef d, int teams, Theme t) {
        k.island(0, 0, 0, CORE_R, t.top(), t.mid(), t.bottom());
        heart(k, t);
        k.disc(0, 0, 0, 17.5, t.path());
        k.ring(0, 0, 0, 16.5, 17.5, t.accent());
        // objective plaza: flat, open, a bright rim so it reads as "something happens here" from every island
        k.disc(0, 0, 0, PLAZA_R + 0.5, t.core());
        k.ring(0, 0, 0, PLAZA_R - 0.5, PLAZA_R + 0.5, t.coreRim());
        d.point("core", Pt.of(0.5, 1, 0.5));
        // emerald pedestals: one block up, between the plaza and the outer ring
        int[][] em = teams >= 8 ? new int[][]{{-9, -9}, {9, -9}, {-9, 9}, {9, 9}} : new int[][]{{-11, 0}, {11, 0}, {0, -11}, {0, 11}};
        for (int[] e : em) {
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) k.set(e[0] + dx, 1, e[1] + dz, dx == 0 && dz == 0 ? "emerald_block" : t.accent());
            d.add("emerald_generators", Pt.of(e[0] + 0.5, 2, e[1] + 0.5));
        }
        // four low corner braziers at the plaza edge (two high: no line-of-sight walls, nothing to hide behind)
        for (int i = 0; i < 4; i++) {
            double a = Math.PI / 4 + i * Math.PI / 2;
            int x = (int) Math.round(Math.cos(a) * (PLAZA_R + 2)), z = (int) Math.round(Math.sin(a) * (PLAZA_R + 2));
            k.set(x, 1, z, t.accent());
            k.set(x, 2, z, t.light());
        }
        // lamp posts round the outer ring (off the east/west axis, where the cave stairs come up)
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8 + Math.PI / 8;
            int x = (int) Math.round(Math.cos(a) * 21), z = (int) Math.round(Math.sin(a) * 21);
            for (int y = 1; y <= 4; y++) k.set(x, y, z, y == 4 ? t.light() : t.accent());
        }
        java.util.Map<Long, Integer> cave = cave(k);
        for (int side : new int[]{1, -1}) tunnel(k, cave, side);
        stall(k, d);
        overgrow(k, cave);
    }

    /** Solid rock under the Core, tapering to a point, so the cave and tunnels never open into the void. */
    private static void heart(MapKit k, Theme t) {
        for (int y = -1; y >= -28; y--) {
            double r = y >= -16 ? 15.5 : 15.5 - (-16 - y) * 1.3;
            if (r <= 0) break;
            int ri = (int) Math.ceil(r + 1.5);
            for (int x = -ri; x <= ri; x++) for (int z = -ri; z <= ri; z++) {
                double wob = (MapKit.noise(x / 4.0 + 50, z / 4.0 + y / 6.0, 91) - 0.5) * 3;
                if (Math.sqrt(x * x + z * z) > r + wob || k.isSet(x, y, z)) continue;
                k.set(x, y, z, y > -4 ? t.mid() : k.rnd.nextInt(4) == 0 ? "tuff" : t.bottom());
            }
        }
    }

    // ------------------------------------------------------------------ the Black Market cave

    private static boolean air(MapKit k, int x, int y, int z) { String b = k.get(x, y, z); return b != null && b.equals("minecraft:air"); }
    private static boolean solid(MapKit k, int x, int y, int z) { String b = k.get(x, y, z); return b != null && !b.equals("minecraft:air"); }
    /** Bare rock the cave was dug out of (not air, and nothing we built: tunnel, stall, pond, lights). */
    private static boolean natural(String b) {
        if (b == null || b.equals("minecraft:air")) return false;
        for (String built : new String[]{"brick", "stairs", "wall", "slab", "shroomlight", "water", "lily", "spruce", "bookshelf", "candle", "lantern", "potted", "leaves", "roots", "vines"})
            if (b.contains(built)) return false;
        return true;
    }

    private static long col(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

    /** Carve a domed, uneven cavern under the plaza. Returns each carved column's ceiling height (top air block). */
    private static java.util.Map<Long, Integer> cave(MapKit k) {
        java.util.Map<Long, Integer> ceil = new java.util.HashMap<>();
        for (int x = -15; x <= 15; x++) for (int z = -15; z <= 15; z++) {
            double d = Math.sqrt(x * x + z * z);
            double ang = Math.atan2(z, x);
            double r = CAVE_R + (MapKit.noise(Math.cos(ang) * 2.2 + 10, Math.sin(ang) * 2.2 + 10, 7) - 0.5) * 4;
            if (d > r) continue;
            double f = 1 - (d / r) * (d / r);
            int top = CAVE_FLOOR + 3 + (int) Math.round(f * 6 + (MapKit.noise(x / 3.0, z / 3.0, 11) - 0.5) * 2);
            k.set(x, CAVE_FLOOR - 1, z, "rooted_dirt");
            int roll = k.rnd.nextInt(20);
            k.set(x, CAVE_FLOOR, z, roll < 15 ? "moss_block" : roll < 18 ? "rooted_dirt" : "mossy_cobblestone");
            for (int y = CAVE_FLOOR + 1; y <= top; y++) k.set(x, y, z, "air");
            ceil.put(col(x, z), top);
        }
        // lily pond in front of the stall (only where the whole pond sits on cave floor, so the water can't spill)
        for (int x = -4; x <= 4; x++) for (int z = 5; z <= 10; z++) {
            double e = (x * x) / 9.0 + ((z - 7.5) * (z - 7.5)) / 3.2;
            if (e > 1 || !ceil.containsKey(col(x, z))) continue;
            boolean edge = false;
            for (int[] n : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) if (!ceil.containsKey(col(x + n[0], z + n[1]))) edge = true;
            if (edge) continue;
            k.set(x, CAVE_FLOOR, z, "water");
            k.set(x, CAVE_FLOOR - 1, z, "clay");
            if (k.rnd.nextInt(4) == 0) k.set(x, CAVE_FLOOR + 1, z, "lily_pad");
        }
        return ceil;
    }

    /**
     * Stairs from the Core surface down to the cave floor along the x axis ({@code side} = +1 east, -1 west): 3 wide,
     * 3 high, mossy brick lining with shroomlights, a rail round the opening, and a ramp body where it crosses the cave.
     * No drops anywhere: you can always walk back up.
     */
    private static void tunnel(MapKit k, java.util.Map<Long, Integer> cave, int side) {
        String stairs = "mossy_stone_brick_stairs[facing=" + (side > 0 ? "east" : "west") + ",half=bottom,shape=straight]";
        int steps = TUNNEL_TOP - 5;   // 14 steps: y 0 at x=19 down to y -14 at x=5
        for (int s = 0; s <= steps; s++) {
            int x = side * (TUNNEL_TOP - s), y = -s;
            boolean inCave = cave.containsKey(col(x, 0));
            for (int z = -1; z <= 1; z++) {
                for (int h = 1; h <= 3; h++) k.set(x, y + h, z, "air");
                k.set(x, y, z, stairs);
                // support: one block under every step; inside the cave, a solid ramp body down to the floor
                k.set(x, y - 1, z, "mossy_stone_bricks");
                if (inCave) for (int yy = y - 2; yy > CAVE_FLOOR; yy--) k.set(x, yy, z, "mossy_stone_bricks");
                // ceiling lining where the tunnel is still under rock
                if (y + 4 <= -1 && !air(k, x, y + 4, z)) k.set(x, y + 4, z, "mossy_stone_bricks");
            }
            for (int z : new int[]{-2, 2}) {
                if (inCave || air(k, x, y + 1, z)) {   // open to the cave: ramp edge + a waist-high rail
                    if (inCave) for (int yy = y; yy > CAVE_FLOOR; yy--) k.set(x, yy, z, "mossy_stone_bricks");
                    else k.set(x, y, z, "mossy_stone_bricks");
                    k.set(x, y + 1, z, "mossy_stone_brick_wall");
                } else if (y < 0) {                     // in the rock: brick walls with a light every third step
                    for (int h = 0; h <= 3; h++) if (y + h <= 0) k.set(x, y + h, z, h == 2 && s % 3 == 1 ? "shroomlight" : k.rnd.nextInt(5) == 0 ? "cracked_stone_bricks" : "mossy_stone_bricks");
                }
            }
        }
        // surface: rail round the opening (the open part is x = 16..19) and an arch over the way in
        for (int s = 0; s <= 3; s++) for (int z : new int[]{-2, 2}) k.set(side * (TUNNEL_TOP - s), 1, z, "mossy_stone_brick_wall");
        for (int z = -2; z <= 2; z++) k.set(side * (TUNNEL_TOP - 4), 1, z, "mossy_stone_brick_wall");
        int ax = side * (TUNNEL_TOP + 1);
        for (int z : new int[]{-2, 2}) for (int y = 1; y <= 3; y++) k.set(ax, y, z, "mossy_stone_bricks");
        for (int z = -2; z <= 2; z++) k.set(ax, 4, z, z == 0 ? "shroomlight" : "mossy_stone_brick_slab[type=bottom]");
        for (int z : new int[]{-1, 1}) k.set(ax, 3, z, "hanging_roots");
    }

    /** The Black Market: a spruce stall with a moss roof in the middle of the cave; the villager stands at its centre. */
    private static void stall(MapKit k, MapDef d) {
        int fy = CAVE_FLOOR, sy = CAVE_FLOOR + 1;   // floor level and standing level
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) k.set(x, fy, z, "spruce_planks");
        for (int[] c : new int[][]{{-3, -3}, {3, -3}, {-3, 3}, {3, 3}})
            for (int y = sy; y <= sy + 3; y++) k.set(c[0], y, c[1], "stripped_spruce_log[axis=y]");
        // roof: moss and azalea leaves, a little ragged at the edge, with roots hanging off it
        int ry = sy + 4;
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) {
            boolean edge = Math.abs(x) == 4 || Math.abs(z) == 4;
            if (edge && Math.abs(x) == 4 && Math.abs(z) == 4) continue;
            if (edge && k.rnd.nextInt(4) == 0) continue;
            int r = k.rnd.nextInt(10);
            k.set(x, ry, z, r < 4 ? "moss_block" : r < 8 ? "azalea_leaves[persistent=true]" : "flowering_azalea_leaves[persistent=true]");
            if (edge && k.rnd.nextInt(3) == 0) k.set(x, ry - 1, z, "hanging_roots");
        }
        // counters towards both stairways, shelves on the other two sides: the villager is boxed in, players talk across
        for (int z = -1; z <= 1; z++) for (int x : new int[]{-2, 2}) k.set(x, sy, z, "spruce_slab[type=top]");
        for (int x = -1; x <= 1; x++) for (int z : new int[]{-2, 2}) k.set(x, sy, z, "bookshelf");
        for (int x : new int[]{-1, 1}) for (int z : new int[]{-2, 2}) k.late(x, sy + 1, z, x < 0 ? "potted_azalea_bush" : "potted_red_mushroom");
        for (int x : new int[]{-2, 2}) { k.late(x, sy + 1, 1, "candle[lit=true,candles=3]"); k.late(x, sy + 1, -1, "candle[lit=true,candles=2]"); }
        for (int[] c : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) k.late(c[0], ry - 1, c[1], "lantern[hanging=true]");
        d.point("black_market", Pt.of(0.5, sy, 0.5).facing(TUNNEL_TOP + 0.5, 0.5));
    }

    /** Moss on the walls, things growing on the floor, glow berries, roots and spore blossoms hanging from the ceiling. */
    private static void overgrow(MapKit k, java.util.Map<Long, Integer> cave) {
        for (var e : cave.entrySet()) {
            int x = (int) (e.getKey() >> 32), z = e.getKey().intValue(), top = e.getValue();
            // walls: any rock touching cave air
            for (int y = CAVE_FLOOR + 1; y <= top; y++) {
                if (!air(k, x, y, z)) continue;
                for (int[] n : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                    int nx = x + n[0], nz = z + n[1];
                    Integer nTop = cave.get(col(nx, nz));
                    if (nTop != null && y <= nTop) continue;   // inside the cave space: air, the stall, the ramps or the pond
                    String b = k.get(nx, y, nz);
                    if (!natural(b)) continue;   // only bare rock: never fill tunnel air or touch the stairs, lining or stall
                    int r = k.rnd.nextInt(10);
                    if (r < 6) k.set(nx, y, nz, "moss_block");
                    else if (r < 8) k.set(nx, y, nz, "mossy_cobblestone");
                }
            }
            // ceiling (skip the stall roof and the tunnels)
            if (air(k, x, top, z) && natural(k.get(x, top + 1, z))) {
                int r = k.rnd.nextInt(100), room = top - (CAVE_FLOOR + 3);
                if (r < 8 && room >= 1) {
                    int len = 1 + k.rnd.nextInt(Math.min(3, room));
                    for (int i = 0; i < len; i++) k.set(x, top - i, z, i == len - 1 ? "cave_vines[berries=true]" : "cave_vines_plant[berries=" + (k.rnd.nextBoolean()) + "]");
                } else if (r < 14) k.set(x, top, z, "hanging_roots");
                else if (r < 17) k.set(x, top, z, "spore_blossom");
                else if (r < 22) k.set(x, top + 1, z, "shroomlight");
                else if (r < 40) k.set(x, top + 1, z, "moss_block");
            }
            // floor plants: only on open moss, never on the walkway between the stairs and the stall (|z| <= 2)
            int sy = CAVE_FLOOR + 1;
            if (!air(k, x, sy, z) || !"minecraft:moss_block".equals(k.get(x, CAVE_FLOOR, z))) continue;
            boolean walkway = Math.abs(z) <= 2;
            int r = k.rnd.nextInt(100);
            if (r < 30) k.set(x, sy, z, "moss_carpet");
            else if (walkway) continue;
            else if (r < 40) k.set(x, sy, z, "fern");
            else if (r < 47) k.set(x, sy, z, "short_grass");
            else if (r < 51) k.set(x, sy, z, "azalea");
            else if (r < 54) k.set(x, sy, z, "flowering_azalea");
            else if (r < 57) k.set(x, sy, z, "pink_petals[facing=north,flower_amount=" + (2 + k.rnd.nextInt(3)) + "]");
        }
    }

    // ------------------------------------------------------------------ one team

    private static void base(MapKit k, MapDef d, int i, int teams, int ringR, Theme t) {
        double a = Math.PI * 2 * i / teams - Math.PI / 2;
        double ca = Math.cos(a), sa = Math.sin(a);
        int cx = (int) Math.round(ca * ringR), cz = (int) Math.round(sa * ringR);
        TeamColor col = TeamColor.of(i);
        // outward direction (away from the centre) snapped to an axis, sideways for the stalls
        int ox = (int) Math.round(ca), oz = (int) Math.round(sa);
        if (ox != 0 && oz != 0) { if (Math.abs(ca) > Math.abs(sa)) oz = 0; else ox = 0; }
        int sx = -oz, sz = ox;
        Base b = new Base(k, cx, cz, ox, oz, sx, sz);
        String facing = ox > 0 ? "east" : ox < 0 ? "west" : oz > 0 ? "south" : "north";

        // island + round platform with a team-coloured rim
        k.island(cx, 0, cz, 14, t.top(), t.mid(), t.bottom());
        for (int u = -10; u <= 10; u++) for (int v = -10; v <= 10; v++) {
            double r = Math.sqrt(u * u + v * v);
            if (r > 10.5) continue;
            b.set(u, 0, v, r > 9.5 ? col.block("concrete") : Math.floorMod(u - v, 5) == 0 ? t.accent() : t.path());
        }

        // bed dais: accent floor round the bed with a clear 2-block margin (room for a full defence), braziers at v=±4
        for (int u = 1; u <= 6; u++) for (int v = -2; v <= 2; v++) b.set(u, 0, v, t.accent());
        for (int v : new int[]{-4, 4}) { b.set(3, 1, v, t.accent()); b.set(3, 2, v, t.light()); }
        k.late(cx + ox * 3, 1, cz + oz * 3, col.dye + "_bed[part=foot,facing=" + facing + "]");
        k.late(cx + ox * 4, 1, cz + oz * 4, col.dye + "_bed[part=head,facing=" + facing + "]");
        d.point("team." + i + ".bed", new Pt(cx + ox * 3 + 0.5, 1, cz + oz * 3 + 0.5, yawFor(facing), 0));

        // the forge: iron/gold pad at u=8 under a roofed arch, blast furnaces either side
        for (int v = -1; v <= 1; v++) b.set(8, 0, v, "iron_block");
        b.set(8, 0, 0, "gold_block");
        for (int v : new int[]{-3, 3}) for (int y = 1; y <= 4; y++) b.set(8, y, v, y == 4 ? t.light() : t.accent());
        for (int v = -3; v <= 3; v++) b.set(8, 5, v, col.block("concrete"));
        for (int v = -2; v <= 2; v += 4) b.set(9, 1, v, "blast_furnace[facing=" + opposite(facing) + "]");
        d.point("team." + i + ".generator", Pt.of(cx + ox * 8 + 0.5, 1, cz + oz * 8 + 0.5));

        // spawn in front of the bed, facing the Core
        d.point("team." + i + ".spawn", Pt.of(cx - ox * 4 + 0.5, 1, cz - oz * 4 + 0.5).facing(0.5, 0.5));

        // stalls: Item Shop (v=+6) and Team Upgrades (v=-6) near the spawn, the Arsenal on the forge side (u=5, v=+7)
        stall(b, d, i, cx, cz, ox, oz, sx, sz, -1, 6, "shop", col.block("wool"), t.accent(), t.light());
        stall(b, d, i, cx, cz, ox, oz, sx, sz, -1, -6, "upgrades", col.block("wool"), t.accent(), t.light());
        stall(b, d, i, cx, cz, ox, oz, sx, sz, 5, 7, "arsenal", t.arsenal(), t.arsenal(), "soul_lantern[hanging=false]");

        // team beacon: a glass column on the far side from the Arsenal, readable from the Core
        for (int y = 1; y <= 6; y++) b.set(5, y, -7, y == 6 ? t.light() : col.block("stained_glass"));

        // a stepping islet halfway to the Core, a little to one side
        int hx = (int) Math.round(ca * ringR * 0.52 - sa * 4), hz = (int) Math.round(sa * ringR * 0.52 + ca * 4);
        k.island(hx, -1, hz, 3.2, t.top(), t.mid(), t.bottom());
        k.set(hx, 0, hz, t.light());

        // diamond island between this team and the next, further out, with a crystal shrine
        double b2 = a + Math.PI / teams;
        int dx = (int) Math.round(Math.cos(b2) * (ringR + 10)), dz = (int) Math.round(Math.sin(b2) * (ringR + 10));
        k.island(dx, 0, dz, 6.5, t.top(), t.mid(), t.bottom());
        k.disc(dx, 0, dz, 3.5, t.path());
        k.set(dx, 0, dz, "diamond_block");
        for (int[] c : new int[][]{{-3, 0}, {3, 0}, {0, -3}, {0, 3}}) {
            k.set(dx + c[0], 1, dz + c[1], t.accent());
            k.set(dx + c[0], 2, dz + c[1], "amethyst_block");
            k.set(dx + c[0], 3, dz + c[1], "amethyst_cluster[facing=up]");
        }
        d.add("diamond_generators", Pt.of(dx + 0.5, 1, dz + 0.5));
    }

    /** A villager stall at (u, v): the villager stands on a pad, posts at the corners, an awning two above head height. */
    private static void stall(Base b, MapDef d, int i, int cx, int cz, int ox, int oz, int sx, int sz, int u, int v,
                              String key, String awning, String post, String light) {
        int side = Integer.signum(v);
        b.set(u, 0, v, post);
        for (int[] p : new int[][]{{u - 2, v - side}, {u + 2, v - side}, {u - 2, v + side}, {u + 2, v + side}})
            for (int y = 1; y <= 2; y++) b.set(p[0], y, p[1], post);
        for (int uu = u - 2; uu <= u + 2; uu++) for (int w = v - side; w != v + 2 * side; w += side) b.set(uu, 3, w, awning);
        b.set(u, 3, v, light.startsWith("soul_lantern") ? "soul_lantern[hanging=true]" : light);
        int vx = cx + ox * u + sx * v, vz = cz + oz * u + sz * v;
        d.point("team." + i + "." + key, Pt.of(vx + 0.5, 1, vz + 0.5).facing(cx + 0.5, cz + 0.5));
    }

    /** Base-local coordinates: u = outward from the centre, v = sideways. */
    private record Base(MapKit k, int cx, int cz, int ox, int oz, int sx, int sz) {
        void set(int u, int y, int v, String block) { k.set(cx + ox * u + sx * v, y, cz + oz * u + sz * v, block); }
    }

    private static String opposite(String facing) {
        return switch (facing) { case "east" -> "west"; case "west" -> "east"; case "north" -> "south"; default -> "north"; };
    }

    private static float yawFor(String facing) {
        return switch (facing) { case "south" -> 0; case "west" -> 90; case "north" -> 180; default -> -90; };
    }
}
