package net.alloymc.core.arcade.games.bedwars;

import net.alloymc.core.arcade.core.*;

/**
 * Forge Wars maps. Same ring as BedWars (team islands round a centre, diamond islands between them) with a new island
 * design: every team base has a bed dais, a covered forge over the generator, and three stalls (Item Shop, Team
 * Upgrades, Arsenal). The centre is "The Core": emerald pedestals round a plaza, and under it the Undercroft cave.
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

    static final int CORE_R = 28;        // centre island radius
    static final int PLAZA_R = 5;        // the plaza in the middle of the Core (the launch shaft comes up through it)

    // the Undercroft: the cave under the Core
    static final int CAVE_FLOOR = -28;   // floor blocks; players stand at y = -27
    static final int CAVE_TOP = -4;      // highest ceiling air, right under the plaza (23 blocks above the floor)
    static final int LANDING_Y = -9;     // standing height of the two upper ledges where the stairways come in
    static final int TUNNEL_TOP = 25;    // stairs start at x = +/-25 on the surface and reach LANDING_Y at x = +/-16
    static final double WALL_R = 13.5;   // average wall radius (wobbles +/-1.5 round the cave)
    static final double LEDGE_W = 3.2;   // width of the ledges and ramps that run along the wall
    static final double RAMP_ARC = 2.55; // radians each spiral ramp winds round the wall (landing + ramp < pi: the two never overlap)
    static final double LANDING_ARC = 0.5;

    static BuiltMap build(String id, String name, String desc, int teams, int ringR, Theme t, String... variants) {
        MapKit k = new MapKit(id.hashCode());
        MapDef d = new MapDef(ForgeWars.ID, id);
        d.display = name; d.description = desc; d.teams = teams;
        d.minPlayers = 2; d.maxPlayers = teams * 4;
        d.variants.addAll(java.util.List.of(variants));
        d.builderVersion = 3;   // v3: the Undercroft (tall cave arena, ramps, launch pad); a bump makes servers rebuild the template
        int ext = ringR + 26;
        d.bounds = Box.of(-ext, -56, -ext, ext, 40, ext);
        d.voidY = -50;           // below the bottom of the Core's rock: the cave floor (-27) must be well above the void
        d.spectator = new Pt(0.5, 32, 0.5, 0, 60);

        core(k, d, teams, t, id.hashCode());
        for (int i = 0; i < teams; i++) base(k, d, i, teams, ringR, t);
        d.waiting = d.spectator;
        return new BuiltMap(d, k.c);
    }

    // ------------------------------------------------------------------ the Core

    private static void core(MapKit k, MapDef d, int teams, Theme t, long seed) {
        k.island(0, 0, 0, CORE_R, t.top(), t.mid(), t.bottom());
        heart(k, t);
        k.disc(0, 0, 0, 20.5, t.path());
        k.ring(0, 0, 0, 19.5, 20.5, t.accent());
        k.disc(0, 0, 0, PLAZA_R + 0.5, t.core());
        k.ring(0, 0, 0, PLAZA_R - 0.5, PLAZA_R + 0.5, t.coreRim());
        d.point("core", Pt.of(0.5, 1, 0.5));
        // emerald pedestals: one block up, between the plaza and the outer ring
        int[][] em = teams >= 8 ? new int[][]{{-10, -10}, {10, -10}, {-10, 10}, {10, 10}} : new int[][]{{-13, 0}, {13, 0}, {0, -13}, {0, 13}};
        for (int[] e : em) {
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) k.set(e[0] + dx, 1, e[1] + dz, dx == 0 && dz == 0 ? "emerald_block" : t.accent());
            d.add("emerald_generators", Pt.of(e[0] + 0.5, 2, e[1] + 0.5));
        }
        // four low braziers round the plaza
        for (int i = 0; i < 4; i++) {
            double a = Math.PI / 4 + i * Math.PI / 2;
            int x = (int) Math.round(Math.cos(a) * (PLAZA_R + 2)), z = (int) Math.round(Math.sin(a) * (PLAZA_R + 2));
            k.set(x, 1, z, t.accent());
            k.set(x, 2, z, t.light());
        }
        // lamp posts round the outer ring (off the east/west axis, where the stairways come up)
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8 + Math.PI / 8;
            int x = (int) Math.round(Math.cos(a) * 24), z = (int) Math.round(Math.sin(a) * 24);
            for (int y = 1; y <= 4; y++) k.set(x, y, z, y == 4 ? t.light() : t.accent());
        }
        Cave c = new Cave(k, seed);
        c.carve();
        c.ledges();
        for (int side : new int[]{1, -1}) c.tunnel(side);
        c.floor(d, t);
        c.shaft(d);
        c.market(d);
        c.overgrow();
    }

    /** Solid rock under the Core, tapering to a point, so the cave and tunnels never open into the void. */
    private static void heart(MapKit k, Theme t) {
        for (int y = -1; y >= -48; y--) {
            double r = y >= -30 ? 19.5 : 19.5 - (-30 - y) * 1.2;
            if (r <= 0) break;
            int ri = (int) Math.ceil(r + 1.5);
            for (int x = -ri; x <= ri; x++) for (int z = -ri; z <= ri; z++) {
                double wob = (MapKit.noise(x / 4.0 + 50, z / 4.0 + y / 6.0, 91) - 0.5) * 3;
                if (Math.sqrt(x * x + z * z) > r + wob || k.isSet(x, y, z)) continue;
                k.set(x, y, z, y > -4 ? t.mid() : k.rnd.nextInt(4) == 0 ? "tuff" : t.bottom());
            }
        }
    }

    // ------------------------------------------------------------------ the Undercroft

    private static boolean air(MapKit k, int x, int y, int z) { String b = k.get(x, y, z); return b != null && b.equals("minecraft:air"); }

    /** Bare rock the cave was dug out of (not air, and nothing we built: ledges, tunnels, stall, pad, lights, plants). */
    private static boolean natural(String b) {
        if (b == null || b.equals("minecraft:air")) return false;
        for (String built : new String[]{"brick", "stairs", "wall", "slab", "shroomlight", "froglight", "slime", "emerald", "water", "lily", "spruce",
                "bookshelf", "candle", "lantern", "potted", "leaves", "roots", "vines", "dripstone", "carpet"})
            if (b.contains(built)) return false;
        return true;
    }

    private static long col(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

    /** Angle (0..2pi) of a column round the centre, measured from +x towards +z. */
    private static double angle(double x, double z) { double a = Math.atan2(z, x); return a < 0 ? a + Math.PI * 2 : a; }

    /** How far {@code a} is past {@code from}, going the same way round (0..2pi). */
    private static double past(double a, double from) { double d = (a - from) % (Math.PI * 2); return d < 0 ? d + Math.PI * 2 : d; }

    /**
     * The Undercroft: a tall, round cavern under the Core. Two stairways come in from the surface onto ledges high on
     * the wall, two ramps spiral down the wall from those ledges to the floor, and a launch pad in the middle throws
     * players straight up a shaft back onto the plaza. The Black Market stall sits on the floor with two deep emerald
     * generators, big pillars and boulders for cover.
     */
    private static final class Cave {
        final MapKit k;
        final long seed;
        final java.util.Map<Long, Integer> top = new java.util.HashMap<>();   // carved column -> highest air y
        final java.util.Map<Long, Double> stand = new java.util.HashMap<>();  // ledge column -> standing height (x.0 or x.5)

        Cave(MapKit k, long seed) { this.k = k; this.seed = seed; }

        double wall(double ang) { return WALL_R + (MapKit.noise(Math.cos(ang) * 2.2 + 10, Math.sin(ang) * 2.2 + 10, seed + 7) - 0.5) * 3; }

        boolean inCave(int x, int z) { return top.containsKey(col(x, z)); }

        void carve() {
            for (int x = -17; x <= 17; x++) for (int z = -17; z <= 17; z++) {
                double d = Math.sqrt(x * x + z * z), w = wall(angle(x, z));
                if (d > w) continue;
                // a high, gently domed ceiling: CAVE_TOP in the middle, a few blocks lower at the wall
                int ceil = Math.min(CAVE_TOP, CAVE_TOP - (int) Math.round(Math.pow(d / w, 4) * 2 + (MapKit.noise(x / 3.0, z / 3.0, seed + 11) - 0.5) * 2));
                k.set(x, CAVE_FLOOR - 1, z, "rooted_dirt");
                int roll = k.rnd.nextInt(20);
                k.set(x, CAVE_FLOOR, z, roll < 14 ? "moss_block" : roll < 17 ? "rooted_dirt" : "mossy_cobblestone");
                for (int y = CAVE_FLOOR + 1; y <= ceil; y++) k.set(x, y, z, "air");
                top.put(col(x, z), ceil);
            }
        }

        /** Standing height on the ledges for this column, or NaN when the column isn't on a ledge. */
        double ledgeAt(int x, int z) {
            double ang = angle(x, z), d = Math.sqrt(x * x + z * z), w = wall(ang);
            if (d < w - LEDGE_W || d > w) return Double.NaN;
            int floor = CAVE_FLOOR + 1, steps = (LANDING_Y - floor) * 2;   // half-block steps from the ledge to the floor
            for (double start : new double[]{0, Math.PI}) {
                double back = past(start, ang), on = past(ang, start);
                if (back <= LANDING_ARC) return LANDING_Y;                                  // the landing, just before the ramp
                if (on <= RAMP_ARC) return floor + Math.round((1 - on / RAMP_ARC) * steps) / 2.0;   // the ramp going down
            }
            return Double.NaN;
        }

        void ledges() {
            for (int x = -17; x <= 17; x++) for (int z = -17; z <= 17; z++) {
                if (!inCave(x, z)) continue;
                double s = ledgeAt(x, z);
                if (Double.isNaN(s) || s <= CAVE_FLOOR + 1) continue;   // the bottom of a ramp is just floor
                put(x, z, s);
            }
        }

        /** A ledge cell whose surface is at standing height {@code s}: full block or bottom slab, two thick, roots under. */
        void put(int x, int z, double s) {
            int y = (int) Math.floor(s);
            boolean half = s - y > 0.25;
            if (half) { k.set(x, y, z, "mossy_stone_brick_slab[type=bottom]"); k.set(x, y - 1, z, "mossy_stone_bricks"); k.set(x, y - 2, z, "mossy_cobblestone"); }
            else { k.set(x, y - 1, z, "mossy_stone_bricks"); k.set(x, y - 2, z, "mossy_cobblestone"); }
            int under = (half ? y : y - 1) - 2;
            if (under > CAVE_FLOOR + 2 && k.rnd.nextInt(3) == 0 && air(k, x, under - 1, z)) k.set(x, under - 1, z, "hanging_roots");
            for (int h = 0; h <= 2; h++) { int yy = (half ? y + 1 : y) + h; if (yy <= top.get(col(x, z))) k.set(x, yy, z, "air"); }
            stand.put(col(x, z), s);
        }

        /**
         * Stairs from the Core surface down to an upper ledge ({@code side} = +1 east, -1 west): 3 wide and 3 high, mossy
         * brick lining with shroomlights, then a level corridor out onto the landing. No drops: you can walk back up.
         */
        void tunnel(int side) {
            String stairs = "mossy_stone_brick_stairs[facing=" + (side > 0 ? "east" : "west") + ",half=bottom,shape=straight]";
            int steps = -LANDING_Y;   // y 0 at x=25 down to y -9 at x=16
            for (int s = 0; s <= steps; s++) {
                int x = side * (TUNNEL_TOP - s), y = -s;
                for (int z = -1; z <= 1; z++) {
                    for (int h = 1; h <= 3; h++) k.set(x, y + h, z, "air");
                    k.set(x, y, z, stairs);
                    k.set(x, y - 1, z, "mossy_stone_bricks");
                    if (y + 4 <= -1) k.set(x, y + 4, z, "mossy_stone_bricks");
                }
                lining(x, y, s, true);
            }
            // level corridor from the last step out to the landing (floor at LANDING_Y - 1), stopping where the ledge starts
            int lx = TUNNEL_TOP - steps - 1;
            for (int ax = lx; ax >= 0; ax--) {
                int x = side * ax;
                boolean any = false;
                for (int z = -1; z <= 1; z++) {
                    if (inCave(x, z)) continue;   // the ledge (or the cave) takes over here
                    any = true;
                    k.set(x, LANDING_Y - 1, z, "mossy_stone_bricks");
                    for (int h = 0; h <= 2; h++) k.set(x, LANDING_Y + h, z, "air");
                    k.set(x, LANDING_Y + 3, z, "mossy_stone_bricks");
                }
                if (!any) break;
                for (int z : new int[]{-2, 2})
                    if (!inCave(x, z)) for (int h = -1; h <= 3; h++) k.set(x, LANDING_Y + h, z, h == 1 && (lx - ax) % 3 == 1 ? "shroomlight" : "mossy_stone_bricks");
            }
            // surface: rail round the opening (open for the first four steps) and an arch over the way in
            for (int s = 0; s <= 3; s++) for (int z : new int[]{-2, 2}) k.set(side * (TUNNEL_TOP - s), 1, z, "mossy_stone_brick_wall");
            for (int z = -2; z <= 2; z++) k.set(side * (TUNNEL_TOP - 4), 1, z, "mossy_stone_brick_wall");
            int ax = side * (TUNNEL_TOP + 1);
            for (int z : new int[]{-2, 2}) for (int y = 1; y <= 3; y++) k.set(ax, y, z, "mossy_stone_bricks");
            for (int z = -2; z <= 2; z++) k.set(ax, 4, z, z == 0 ? "shroomlight" : "mossy_stone_brick_slab[type=bottom]");
            for (int z : new int[]{-1, 1}) k.set(ax, 3, z, "hanging_roots");
        }

        /** Brick walls either side of a tunnel step at (x, y): a light every third step. Above the surface it's a rail. */
        private void lining(int x, int y, int s, boolean stairs) {
            for (int z : new int[]{-2, 2}) {
                if (y + 1 > 0) { k.set(x, y, z, "mossy_stone_bricks"); continue; }   // the open top steps: the surface rail covers it
                for (int h = 0; h <= 3; h++) if (y + h <= 0)
                    k.set(x, y + h, z, h == 2 && s % 3 == 1 ? "shroomlight" : k.rnd.nextInt(5) == 0 ? "cracked_stone_bricks" : "mossy_stone_bricks");
            }
        }

        /** The floor: launch pad in the middle, two deep emerald generators, four great pillars and some boulders for cover. */
        void floor(MapDef d, Theme t) {
            int fy = CAVE_FLOOR, sy = CAVE_FLOOR + 1;
            // launch pad: 3x3 slime with a froglight ring
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++)
                k.set(x, fy, z, Math.abs(x) <= 1 && Math.abs(z) <= 1 ? "slime_block" : "pearlescent_froglight[axis=y]");
            d.point("launch_pad", Pt.of(0.5, sy, 0.5));
            // deep emerald generators east and west of the pad
            for (int ex : new int[]{-7, 7}) {
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) k.set(ex + dx, sy, dz, dx == 0 && dz == 0 ? "emerald_block" : "mossy_stone_bricks");
                d.add("emerald_generators", Pt.of(ex + 0.5, sy + 1, 0.5));
            }
            // great pillars, floor to ceiling, wrapped in moss and roots
            for (int[] p : new int[][]{{5, 5}, {-6, 5}, {-6, -6}, {5, -6}})
                for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++) {
                    int x = p[0] + dx, z = p[1] + dz, ceil = top.get(col(x, z));
                    for (int y = sy; y <= ceil; y++) k.set(x, y, z, k.rnd.nextInt(4) == 0 ? "moss_block" : k.rnd.nextInt(3) == 0 ? "mossy_cobblestone" : t.bottom());
                }
            // boulders: waist-high cover
            for (int[] b : new int[][]{{-3, 6}, {3, 4}, {-4, -3}, {8, -4}, {-9, 3}}) {
                for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++) {
                    k.set(b[0] + dx, sy, b[1] + dz, k.rnd.nextBoolean() ? "mossy_cobblestone" : "moss_block");
                    if (k.rnd.nextInt(3) > 0) k.set(b[0] + dx, sy + 1, b[1] + dz, k.rnd.nextBoolean() ? "moss_block" : "mossy_cobblestone");
                }
            }
        }

        /** The shaft from the cave ceiling up through the middle of the plaza (3x3). */
        void shaft(MapDef d) {
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) for (int y = CAVE_TOP; y <= 0; y++) k.set(x, y, z, "air");
            // a bright rim round the hole on the plaza so nobody walks in by accident (dropping in is fine: the pad catches you)
            for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) if (Math.abs(x) == 2 || Math.abs(z) == 2) k.set(x, 0, z, "pearlescent_froglight[axis=y]");
        }

        /** The Black Market: a spruce stall against the north side of the floor; the villager stands boxed in behind the counter. */
        void market(MapDef d) {
            int fy = CAVE_FLOOR, sy = CAVE_FLOOR + 1, cz = -8;
            for (int x = -2; x <= 2; x++) for (int z = cz - 2; z <= cz + 2; z++) { k.set(x, fy, z, "spruce_planks"); for (int y = sy; y <= sy + 2; y++) k.set(x, y, z, "air"); }
            for (int[] c : new int[][]{{-2, cz - 2}, {2, cz - 2}, {-2, cz + 2}, {2, cz + 2}})
                for (int y = sy; y <= sy + 2; y++) k.set(c[0], y, c[1], "stripped_spruce_log[axis=y]");
            int ry = sy + 3;
            for (int x = -3; x <= 3; x++) for (int z = cz - 3; z <= cz + 3; z++) {
                boolean edge = Math.abs(x) == 3 || Math.abs(z - cz) == 3;
                if (edge && (Math.abs(x) == 3 && Math.abs(z - cz) == 3 || k.rnd.nextInt(4) == 0)) continue;
                int r = k.rnd.nextInt(10);
                k.set(x, ry, z, r < 4 ? "moss_block" : r < 8 ? "azalea_leaves[persistent=true]" : "flowering_azalea_leaves[persistent=true]");
                if (edge && k.rnd.nextInt(3) == 0 && Math.abs(z - cz) != 3) k.set(x, ry - 1, z, "hanging_roots");
            }
            // villager at (0, cz): counter in front (towards the pad), shelves on the other three sides
            for (int x = -1; x <= 1; x++) k.set(x, sy, cz + 1, "spruce_slab[type=top]");
            for (int x = -1; x <= 1; x++) k.set(x, sy, cz - 1, "bookshelf");
            for (int x : new int[]{-1, 1}) k.set(x, sy, cz, "bookshelf");
            k.late(-1, sy + 1, cz - 1, "potted_azalea_bush");
            k.late(1, sy + 1, cz - 1, "potted_red_mushroom");
            k.late(-1, sy + 1, cz + 1, "candle[lit=true,candles=3]");
            k.late(1, sy + 1, cz + 1, "candle[lit=true,candles=2]");
            for (int x : new int[]{-2, 2}) k.late(x, ry - 1, cz + 1, "lantern[hanging=true]");
            d.point("black_market", Pt.of(0.5, sy, cz + 0.5).facing(0.5, 0.5));
        }

        /** Moss on the walls, low plants on the floor, glow berries, roots, spore blossoms and dripstone on the ceiling. */
        void overgrow() {
            for (var e : top.entrySet()) {
                int x = (int) (e.getKey() >> 32), z = e.getKey().intValue(), ceil = e.getValue();
                // walls: bare rock touching cave air
                for (int y = CAVE_FLOOR + 1; y <= ceil; y++) {
                    if (!air(k, x, y, z)) continue;
                    for (int[] n : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        int nx = x + n[0], nz = z + n[1];
                        Integer nTop = top.get(col(nx, nz));
                        if (nTop != null && y <= nTop) continue;
                        if (!natural(k.get(nx, y, nz))) continue;
                        int r = k.rnd.nextInt(10);
                        if (r < 6) k.set(nx, y, nz, "moss_block");
                        else if (r < 8) k.set(nx, y, nz, "mossy_cobblestone");
                    }
                }
                // ceiling (never over the pad and shaft, never into a ledge's headroom)
                boolean middle = Math.abs(x) <= 2 && Math.abs(z) <= 2;
                if (!middle && air(k, x, ceil, z) && natural(k.get(x, ceil + 1, z))) {
                    Double s = stand.get(col(x, z));
                    int lowest = s == null ? CAVE_FLOOR + 4 : (int) Math.ceil(s) + 3;   // keep 3 blocks over any ledge or the floor
                    int room = ceil - lowest;
                    int r = k.rnd.nextInt(100);
                    if (r < 9 && room >= 1) {
                        int len = 1 + k.rnd.nextInt(Math.min(8, room));
                        for (int i = 0; i < len; i++) k.set(x, ceil - i, z, i == len - 1 ? "cave_vines[berries=true]" : "cave_vines_plant[berries=" + k.rnd.nextBoolean() + "]");
                    } else if (r < 13 && room >= 3) {
                        int len = 2 + k.rnd.nextInt(Math.min(3, room - 1));
                        for (int i = 0; i < len; i++) k.set(x, ceil - i, z, "dripstone_block");   // stubby stalactites
                    } else if (r < 19) k.set(x, ceil, z, "hanging_roots");
                    else if (r < 22) k.set(x, ceil, z, "spore_blossom");
                    else if (r < 29) k.set(x, ceil + 1, z, "shroomlight");
                    else if (r < 50) k.set(x, ceil + 1, z, "moss_block");
                }
                // floor plants on open moss: low ones only near the middle so fights stay readable, bushes by the wall
                int sy = CAVE_FLOOR + 1;
                if (!air(k, x, sy, z) || !"minecraft:moss_block".equals(k.get(x, CAVE_FLOOR, z))) continue;
                double d = Math.sqrt(x * x + z * z);
                int r = k.rnd.nextInt(100);
                if (r < 30) k.set(x, sy, z, "moss_carpet");
                else if (r < 38) k.set(x, sy, z, "short_grass");
                else if (r < 44) k.set(x, sy, z, "fern");
                else if (d > 9 && r < 49) k.set(x, sy, z, k.rnd.nextBoolean() ? "azalea" : "flowering_azalea");
                else if (r < 53) k.set(x, sy, z, "pink_petals[facing=north,flower_amount=" + (2 + k.rnd.nextInt(3)) + "]");
            }
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
        int hx = (int) Math.round(ca * ringR * 0.6 - sa * 4), hz = (int) Math.round(sa * ringR * 0.6 + ca * 4);   // clear of the bigger Core
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
