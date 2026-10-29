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
    static final int GROTTO_Z = -19;     // the Black Market grotto: off the north wall, away from the middle of the hall

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
        // emerald pedestals: one block up, each in its own ruined shrine, off the approach lines
        int[][] em = teams >= 8 ? new int[][]{{11, 5}, {-5, 11}, {-11, -5}, {5, -11}} : new int[][]{{-8, -8}, {8, -8}, {-8, 8}, {8, 8}};
        for (int[] e : em) {
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) k.set(e[0] + dx, 1, e[1] + dz, dx == 0 && dz == 0 ? "emerald_block" : t.accent());
            d.add("emerald_generators", Pt.of(e[0] + 0.5, 2, e[1] + 0.5));
        }
        new Ruin(k, seed, teams, em).build();
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
            double r = y >= -30 ? 25.5 : 25.5 - (-30 - y) * 1.3;   // wide enough to hold the Black Market grotto off the north wall
            if (r <= 0) break;
            int ri = (int) Math.ceil(r + 1.5);
            for (int x = -ri; x <= ri; x++) for (int z = -ri; z <= ri; z++) {
                double wob = (MapKit.noise(x / 4.0 + 50, z / 4.0 + y / 6.0, 91) - 0.5) * 3;
                if (Math.sqrt(x * x + z * z) > r + wob || k.isSet(x, y, z)) continue;
                k.set(x, y, z, y > -4 ? t.mid() : k.rnd.nextInt(4) == 0 ? "tuff" : t.bottom());
            }
        }
    }

    // ------------------------------------------------------------------ the Fallen Keep (the Core surface)

    /**
     * The Core's surface is the ruin of an old keep, gone wild: a broken curtain wall round an inner ward with a gate
     * facing every team's bridge line, ruined towers set in the wall between the gates (climb the vines inside to the
     * top), a broken colonnade round the plaza with fallen columns for cover, a ruined shrine round each emerald
     * generator, and in the outer bailey the crypt stairs to the Undercroft, a great cherry tree and a fairy ring.
     *
     * Always kept clear: the plaza and launch shaft, a 5-wide approach line from every team's side to the plaza, and
     * the two crypt stairways.
     */
    private static final class Ruin {
        static final double WALL_IN = 18, WALL_OUT = 20, WALL_MID = 19, GATE = 2.8;
        final MapKit k;
        final long seed;
        final double[] teamAng;
        final int[][] pedestals;
        final java.util.Set<Long> built = new java.util.HashSet<>();   // castle blocks, for the vines

        Ruin(MapKit k, long seed, int teams, int[][] pedestals) {
            this.k = k; this.seed = seed; this.pedestals = pedestals;
            teamAng = new double[teams];
            for (int i = 0; i < teams; i++) teamAng[i] = angle(Math.cos(Math.PI * 2 * i / teams - Math.PI / 2), Math.sin(Math.PI * 2 * i / teams - Math.PI / 2));
        }

        static long key(int x, int y, int z) { return (((long) x + 512) << 40) | (((long) y + 512) << 20) | (z + 512); }
        static double wrap(double a) { a %= Math.PI * 2; if (a > Math.PI) a -= Math.PI * 2; if (a < -Math.PI) a += Math.PI * 2; return a; }

        // ---- where nothing may be built
        boolean corridor(double x, double z) {
            for (double a : teamAng) {
                double along = x * Math.cos(a) + z * Math.sin(a), perp = Math.abs(-x * Math.sin(a) + z * Math.cos(a));
                if (along > PLAZA_R && perp < 3) return true;
            }
            return false;
        }
        static boolean stairs(int x, int z) { return Math.abs(x) >= 17 && Math.abs(x) <= 27 && Math.abs(z) <= 3; }
        boolean nearPedestal(int x, int z, int r) { for (int[] p : pedestals) if (Math.abs(x - p[0]) <= r && Math.abs(z - p[1]) <= r) return true; return false; }
        boolean clear(int x, int z) { return Math.hypot(x, z) < PLAZA_R + 1.5 || corridor(x, z) || stairs(x, z) || nearPedestal(x, z, 1); }

        String stone() { int r = k.rnd.nextInt(20); return r < 7 ? "stone_bricks" : r < 14 ? "mossy_stone_bricks" : r < 18 ? "cracked_stone_bricks" : "mossy_cobblestone"; }
        String slab() { return k.rnd.nextBoolean() ? "stone_brick_slab[type=bottom]" : "mossy_stone_brick_slab[type=bottom]"; }
        void put(int x, int y, int z, String b) { k.set(x, y, z, b); built.add(key(x, y, z)); }
        void clearAt(int x, int y, int z) { k.set(x, y, z, "air"); built.remove(key(x, y, z)); }
        boolean empty(int x, int y, int z) { String b = k.get(x, y, z); return b == null || b.equals("minecraft:air"); }

        void build() {
            curtain();
            towers();
            colonnade();
            shrines();
            bailey();
            groundcover();
            ivy();
        }

        /**
         * Wall height in half blocks at this angle: rubble at each gate, rising along the wall so the wall-tops can be
         * climbed from the gates, capped by noise (up to 7 blocks) so some stretches have fallen to breaches.
         */
        int wallHalf(double ang) {
            double arc = Double.MAX_VALUE;
            for (double a : teamAng) arc = Math.min(arc, Math.abs(wrap(ang - a)) * WALL_MID);
            // half a block per block for the first 4 blocks out of the gate (walk up), then a block per block (hop up),
            // so the wall is ~6 high where it meets a tower and the lower tower tops join the wall-walk
            double out = arc - GATE;
            int ramp = out <= 0 ? 0 : out <= 4 ? (int) Math.floor(out) : 4 + (int) Math.floor((out - 4) * 2);
            int cap = (int) Math.round(MapKit.noise(Math.cos(ang) * 1.7 + 30, Math.sin(ang) * 1.7 + 30, seed + 21) * 26 - 3);
            return Math.max(0, Math.min(14, Math.min(cap, ramp)));
        }

        void curtain() {
            for (int x = -21; x <= 21; x++) for (int z = -21; z <= 21; z++) {
                double d = Math.hypot(x, z);
                if (d < WALL_IN || d >= WALL_OUT || stairs(x, z)) continue;
                double ang = angle(x, z);
                int h2 = wallHalf(ang);
                if (h2 <= 0) { if (!corridor(x, z) && k.rnd.nextInt(3) == 0) put(x, 1, z, slab()); continue; }   // breach: rubble
                int full = h2 / 2;
                for (int y = 1; y <= full; y++) put(x, y, z, stone());
                if (h2 % 2 == 1) put(x, full + 1, z, slab());
                else if (d >= WALL_MID && full >= 3 && Math.floorMod((int) Math.floor(ang * WALL_MID / 1.5), 2) == 0)
                    put(x, full + 1, z, stone());   // merlons on the outer edge; the inner edge stays a walkway
            }
        }

        /** Four ruined towers in the wall between the gates, all different heights, hollow, with vines to climb. */
        void towers() {
            int[] hs = {11, 7, 9, 6};
            for (int i = 0; i < 4; i++) {
                double ang = teamAng[0] + Math.PI / teamAng.length + i * Math.PI / 2;
                int cx = (int) Math.round(Math.cos(ang) * WALL_MID), cz = (int) Math.round(Math.sin(ang) * WALL_MID), h = hs[i];
                int ox, oz;   // outward, snapped to an axis
                if (Math.abs(Math.cos(ang)) >= Math.abs(Math.sin(ang))) { ox = (int) Math.signum(Math.cos(ang)); oz = 0; } else { ox = 0; oz = (int) Math.signum(Math.sin(ang)); }
                for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
                    double dd = Math.hypot(dx, dz);
                    int x = cx + dx, z = cz + dz;
                    if (dd > 3.3) continue;
                    if (dd >= 1.9) {
                        for (int y = 1; y <= h; y++) {
                            if (y >= h - 1 && MapKit.noise(x / 2.0 + y, z / 2.0, seed + 31) < 0.45) continue;   // broken top
                            put(x, y, z, stone());
                        }
                        if (MapKit.noise(x / 2.0, z / 2.0 + 9, seed + 33) > 0.5) put(x, h + 1, z, stone());   // what's left of the parapet
                    } else {
                        for (int y = 1; y < h; y++) clearAt(x, y, z);
                        if (!(dx == ox && dz == oz)) put(x, h, z, k.rnd.nextInt(3) == 0 ? "moss_block" : stone());   // floor of the top
                    }
                }
                // doorway on the courtyard side
                for (int s = 2; s <= 3; s++) for (int y = 1; y <= 2; y++) clearAt(cx - ox * s, y, cz - oz * s);
                String face = face(ox, oz);
                // inside: vines up the far wall through a hole in the top floor
                for (int y = 1; y <= h; y++) k.set(cx + ox, y, cz + oz, "vine[" + face + "=true]");
                // outside: an ivy strip up the outer face
                String back = face(-ox, -oz);
                for (int y = 1; y <= h - 2; y++) if (empty(cx + ox * 4, y, cz + oz * 4)) k.set(cx + ox * 4, y, cz + oz * 4, "vine[" + back + "=true]");
            }
        }

        static String face(int dx, int dz) { return dx > 0 ? "east" : dx < 0 ? "west" : dz > 0 ? "south" : "north"; }

        /** A broken ring of columns round the plaza: some still carry an arch, some have fallen across the ground. */
        void colonnade() {
            int[] hs = {6, 2, 6, 1, 5, 3, 0, 6};
            int[][] at = new int[8][];
            for (int i = 0; i < 8; i++) {
                double ang = Math.PI / 8 + i * Math.PI / 4;
                int x = (int) Math.round(Math.cos(ang) * 9.5), z = (int) Math.round(Math.sin(ang) * 9.5);
                at[i] = new int[]{x, z};
                if (clear(x, z) || nearPedestal(x, z, 3)) { hs[i] = -1; continue; }
                int h = hs[i];
                for (int y = 1; y <= h; y++) put(x, y, z, y == 1 || (h >= 5 && y == h) ? "chiseled_stone_bricks" : stone());
                if (h == 0) put(x, 1, z, slab());
                if (h <= 3) {   // the rest of it lies on the ground, along the ring
                    int tx = (int) Math.round(-Math.sin(ang)), tz = (int) Math.round(Math.cos(ang));
                    if (tx == 0 && tz == 0) tx = 1;
                    for (int s = 2; s <= 2 + (5 - h); s++) {
                        int fx = x + tx * s, fz = z + tz * s;
                        if (clear(fx, fz) || nearPedestal(fx, fz, 3)) break;
                        put(fx, 1, fz, s == 2 + (5 - h) ? "chiseled_stone_bricks" : stone());
                    }
                }
            }
            // arches between neighbouring columns that still stand, with glow berries hanging off them
            for (int i = 0; i < 8; i++) {
                int j = (i + 1) % 8;
                if (hs[i] < 5 || hs[j] < 5) continue;
                double a0 = Math.PI / 8 + i * Math.PI / 4, a1 = a0 + Math.PI / 4;
                for (double a = a0; a <= a1 + 1e-6; a += 0.03) {
                    int x = (int) Math.round(Math.cos(a) * 9.5), z = (int) Math.round(Math.sin(a) * 9.5);
                    if (!built.contains(key(x, 7, z))) put(x, 7, z, k.rnd.nextInt(4) == 0 ? "mossy_stone_brick_slab[type=bottom]" : stone());
                    if (k.rnd.nextInt(5) == 0 && empty(x, 6, z) && !built.contains(key(x, 6, z))) {
                        int len = 1 + k.rnd.nextInt(3);
                        for (int n = 0; n < len; n++) k.set(x, 6 - n, z, n == len - 1 ? "cave_vines[berries=true]" : "cave_vines_plant[berries=true]");
                    }
                }
            }
        }

        /** Each emerald generator stands in a ruined shrine: low walls on two sides, pillars with candles at the corners. */
        void shrines() {
            for (int[] p : pedestals) {
                int ax = Math.abs(p[0]) >= Math.abs(p[1]) ? 1 : 0;   // the open sides face the plaza and away from it
                for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != 2) continue;
                    int x = p[0] + dx, z = p[1] + dz;
                    if (corridor(x, z) || stairs(x, z)) continue;
                    boolean corner = Math.abs(dx) == 2 && Math.abs(dz) == 2;
                    int along = ax == 1 ? dx : dz, side = ax == 1 ? dz : dx;
                    if (!corner && Math.abs(along) == 2 && Math.abs(side) <= 1) continue;   // open front and back
                    if (corner) {
                        for (int y = 1; y <= 3; y++) put(x, y, z, y == 1 ? "chiseled_stone_bricks" : stone());
                        k.set(x, 4, z, k.rnd.nextBoolean() ? "candle[lit=true,candles=4]" : "amethyst_cluster[facing=up]");
                    } else {
                        put(x, 1, z, stone());
                        if (k.rnd.nextInt(3) > 0) put(x, 2, z, k.rnd.nextBoolean() ? slab() : stone());
                    }
                }
            }
        }

        /** Outer bailey: broken lamp posts, the great cherry tree and the fairy ring. */
        void bailey() {
            double treeA = Math.PI * 3 / 4 + 0.4, ringA = Math.PI * 7 / 4 - 0.4;
            int tx = (int) Math.round(Math.cos(treeA) * 24), tz = (int) Math.round(Math.sin(treeA) * 24);
            int rx = (int) Math.round(Math.cos(ringA) * 23.5), rz = (int) Math.round(Math.sin(ringA) * 23.5);
            for (int i = 0; i < 8; i++) {
                double a = Math.PI * 2 * i / 8 + Math.PI / 8;
                int x = (int) Math.round(Math.cos(a) * 24), z = (int) Math.round(Math.sin(a) * 24);
                if (clear(x, z) || Math.hypot(x - tx, z - tz) < 7 || Math.hypot(x - rx, z - rz) < 6) continue;
                boolean broken = i % 3 == 2;
                int h = broken ? 1 + k.rnd.nextInt(2) : 3;
                for (int y = 1; y <= h; y++) put(x, y, z, y == 1 ? "chiseled_stone_bricks" : "mossy_stone_brick_wall");
                if (!broken) k.set(x, h + 1, z, "lantern[hanging=false]");
            }
            cherry(tx, tz);
            fairyRing(rx, rz);
        }

        void cherry(int cx, int cz) {
            for (int dx = 0; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++) for (int y = 1; y <= 8; y++) k.set(cx + dx, y, cz + dz, "cherry_log[axis=y]");
            int[][] br = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int b = 0; b < 4; b++) for (int s = 1; s <= 3; s++) {
                int x = cx + (br[b][0] > 0 ? 1 : 0) + br[b][0] * s, z = cz + (br[b][1] > 0 ? 1 : 0) + br[b][1] * s;
                k.set(x, 6 + s / 2 + b % 2, z, "cherry_log[axis=" + (br[b][0] != 0 ? "x" : "z") + "]");
            }
            for (int dx = -7; dx <= 8; dx++) for (int dz = -7; dz <= 8; dz++) for (int y = 6; y <= 13; y++) {
                double ex = dx - 0.5, ez = dz - 0.5, ey = (y - 9.5) / 3.0;
                double e = (ex * ex + ez * ez) / 42.0 + ey * ey;
                if (e > 1 + (MapKit.noise((cx + dx) / 2.5, (cz + dz) / 2.5 + y, seed + 41) - 0.5) * 0.5) continue;
                if (empty(cx + dx, y, cz + dz)) k.set(cx + dx, y, cz + dz, "cherry_leaves[persistent=true]");
            }
            // petals fallen round it
            for (int dx = -6; dx <= 7; dx++) for (int dz = -6; dz <= 7; dz++) {
                int x = cx + dx, z = cz + dz;
                if (Math.hypot(dx - 0.5, dz - 0.5) > 6.5 || !empty(x, 1, z) || empty(x, 0, z) || stairs(x, z) || k.rnd.nextInt(3) == 0) continue;
                k.set(x, 0, z, "moss_block");
                k.set(x, 1, z, "pink_petals[facing=" + face(k.rnd.nextInt(3) - 1, 1) + ",flower_amount=" + (1 + k.rnd.nextInt(4)) + "]");
            }
        }

        void fairyRing(int cx, int cz) {
            // a giant red mushroom in the middle
            for (int y = 1; y <= 5; y++) k.set(cx, y, cz, "mushroom_stem");
            for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
                double d = Math.hypot(dx, dz);
                if (d <= 2.6) k.set(cx + dx, 6, cz + dz, "red_mushroom_block");
                else if (d <= 3.4) k.set(cx + dx, 5, cz + dz, "red_mushroom_block");
            }
            // the ring of small mushrooms on podzol, with amethyst glinting between them
            for (int dx = -5; dx <= 5; dx++) for (int dz = -5; dz <= 5; dz++) {
                double d = Math.hypot(dx, dz);
                int x = cx + dx, z = cz + dz;
                if (d < 4.2 || d > 5.2 || empty(x, 0, z) || !empty(x, 1, z) || clear(x, z)) continue;
                k.set(x, 0, z, "podzol");
                int r = k.rnd.nextInt(10);
                if (r < 4) k.set(x, 1, z, "red_mushroom");
                else if (r < 7) k.set(x, 1, z, "brown_mushroom");
                else if (r < 8) k.set(x, 1, z, "amethyst_cluster[facing=up]");
            }
        }

        /** Moss patches, grass, petals and flowers across the ground; bushes only off the approach lines. */
        void groundcover() {
            String[] flowers = {"allium", "azure_bluet", "blue_orchid", "lily_of_the_valley", "cornflower"};
            for (int x = -28; x <= 28; x++) for (int z = -28; z <= 28; z++) {
                double d = Math.hypot(x, z);
                if (d < PLAZA_R + 1 || d > 27 || stairs(x, z) || nearPedestal(x, z, 1)) continue;
                if (empty(x, 0, z) || !empty(x, 1, z)) continue;
                boolean moss = k.rnd.nextInt(10) < 3;
                if (moss) k.set(x, 0, z, "moss_block");
                int r = k.rnd.nextInt(100);
                if (r < 22) k.set(x, 1, z, "moss_carpet");
                else if (!moss) continue;
                else if (r < 40) k.set(x, 1, z, "short_grass");
                else if (r < 46) k.set(x, 1, z, "pink_petals[facing=north,flower_amount=" + (1 + k.rnd.nextInt(4)) + "]");
                else if (r < 50) k.set(x, 1, z, flowers[k.rnd.nextInt(flowers.length)]);
                else if (r < 53) k.set(x, 1, z, "fern");
                else if (r < 55 && !corridor(x, z) && d > 8) k.set(x, 1, z, k.rnd.nextBoolean() ? "azalea" : "flowering_azalea");
            }
        }

        /** Vines creeping down the walls, moss and candles on the wall-tops, leaves on the merlons. */
        void ivy() {
            int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (long kk : new java.util.ArrayList<>(built)) {
                int x = (int) ((kk >> 40) - 512), y = (int) (((kk >> 20) & 0xFFFFF) - 512), z = (int) ((kk & 0xFFFFF) - 512);
                if (empty(x, y, z) || y < 2) continue;
                for (int[] dv : dirs) {
                    int vx = x + dv[0], vz = z + dv[1];
                    if (k.rnd.nextInt(10) != 0 || !empty(vx, y, vz) || built.contains(key(vx, y, vz))) continue;
                    String f = face(-dv[0], -dv[1]);   // the vine hangs on the wall it's next to
                    for (int yy = y; yy >= 1 && empty(vx, yy, vz) && !empty(x, yy, z); yy--) {
                        k.set(vx, yy, vz, "vine[" + f + "=true]");
                        if (k.rnd.nextInt(3) == 0) break;
                    }
                }
                // tops: a walkable top gets moss carpet or a candle now and then; a merlon gets leaves
                if (empty(x, y + 1, z) && !built.contains(key(x, y + 1, z))) {
                    int r = k.rnd.nextInt(20);
                    if (r < 5) k.set(x, y + 1, z, "moss_carpet");
                    else if (r == 5) k.set(x, y + 1, z, "candle[lit=true,candles=" + (1 + k.rnd.nextInt(4)) + "]");
                    else if (r < 8 && y >= 4 && Math.hypot(x, z) >= WALL_MID) k.set(x, y + 1, z, "flowering_azalea_leaves[persistent=true]");
                }
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
        final java.util.Set<Long> grotto = new java.util.HashSet<>();          // Black Market grotto and its passage: no bushes

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
            // boulders: waist-high cover, round the edge of the open middle
            for (int[] b : new int[][]{{-3, 6}, {3, 4}, {-6, -3}, {8, -4}, {-9, 3}}) {   // all outside r=4: the middle is the boss arena
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

        /**
         * The Black Market hides in its own grotto off the north wall, out of the main hall (which is kept clear for fights):
         * a low passage under the spiral ramp leads into a small domed chamber with the stall at the back. The villager
         * stands boxed in behind a counter that faces the way in.
         */
        void market(MapDef d) {
            int fy = CAVE_FLOOR, sy = CAVE_FLOOR + 1;
            // the chamber: an ellipse centred on (0, GROTTO_Z), domed ceiling
            for (int x = -6; x <= 6; x++) for (int z = GROTTO_Z - 5; z <= GROTTO_Z + 5; z++) {
                double e = (x * x) / 25.0 + ((z - GROTTO_Z) * (z - GROTTO_Z)) / 16.0;
                if (e > 1 + (MapKit.noise(x / 2.0, z / 2.0, seed + 51) - 0.5) * 0.3) continue;
                int ceil = sy + 3 + (int) Math.round((1 - e) * 4);
                k.set(x, fy - 1, z, "rooted_dirt");
                k.set(x, fy, z, k.rnd.nextInt(3) == 0 ? "rooted_dirt" : "moss_block");
                for (int y = sy; y <= ceil; y++) k.set(x, y, z, "air");
                top.put(col(x, z), ceil);
                grotto.add(col(x, z));
            }
            // the passage: 5 wide, 3 high, from the main hall through the wall into the chamber, brick-lined where it's in rock
            for (int z = -9; z >= GROTTO_Z + 3; z--) {
                for (int x = -2; x <= 2; x++) {
                    boolean rock = !inCave(x, z);
                    k.set(x, fy, z, "mossy_cobblestone");
                    for (int y = sy; y <= sy + 2; y++) k.set(x, y, z, "air");
                    if (rock) { k.set(x, sy + 3, z, "mossy_stone_bricks"); top.put(col(x, z), sy + 2); grotto.add(col(x, z)); }
                }
                for (int x : new int[]{-3, 3}) if (!inCave(x, z) || top.get(col(x, z)) < sy + 3)
                    for (int y = sy; y <= sy + 3; y++) k.set(x, y, z, y == sy + 1 && Math.floorMod(z, 3) == 0 ? "shroomlight" : k.rnd.nextInt(4) == 0 ? "cracked_stone_bricks" : "mossy_stone_bricks");
            }
            // the stall at the back of the chamber, counter facing the passage
            int cz = GROTTO_Z - 1;
            for (int x = -2; x <= 2; x++) for (int z = cz - 2; z <= cz + 2; z++) { k.set(x, fy, z, "spruce_planks"); for (int y = sy; y <= sy + 2; y++) k.set(x, y, z, "air"); }
            for (int[] c : new int[][]{{-2, cz - 2}, {2, cz - 2}, {-2, cz + 2}, {2, cz + 2}})
                for (int y = sy; y <= sy + 2; y++) k.set(c[0], y, c[1], "stripped_spruce_log[axis=y]");
            int ry = sy + 3;
            for (int x = -3; x <= 3; x++) for (int z = cz - 3; z <= cz + 3; z++) {
                boolean edge = Math.abs(x) == 3 || Math.abs(z - cz) == 3;
                if (edge && (Math.abs(x) == 3 && Math.abs(z - cz) == 3 || k.rnd.nextInt(4) == 0)) continue;
                if (!air(k, x, ry, z)) continue;   // tucked under the dome: never into the rock
                int r = k.rnd.nextInt(10);
                k.set(x, ry, z, r < 4 ? "moss_block" : r < 8 ? "azalea_leaves[persistent=true]" : "flowering_azalea_leaves[persistent=true]");
                if (edge && k.rnd.nextInt(3) == 0 && Math.abs(z - cz) != 3) k.set(x, ry - 1, z, "hanging_roots");
            }
            for (int x = -1; x <= 1; x++) k.set(x, sy, cz + 1, "spruce_slab[type=top]");
            for (int x = -1; x <= 1; x++) k.set(x, sy, cz - 1, "bookshelf");
            for (int x : new int[]{-1, 1}) k.set(x, sy, cz, "bookshelf");
            k.late(-1, sy + 1, cz - 1, "potted_brown_mushroom");
            k.late(1, sy + 1, cz - 1, "potted_red_mushroom");
            k.late(-1, sy + 1, cz + 1, "candle[lit=true,candles=3]");
            k.late(1, sy + 1, cz + 1, "candle[lit=true,candles=2]");
            for (int x : new int[]{-2, 2}) k.late(x, ry - 1, cz + 1, "lantern[hanging=true]");
            // amethyst glinting on the chamber floor round the edge
            for (int x = -5; x <= 5; x++) for (int z = GROTTO_Z - 4; z <= GROTTO_Z + 4; z++) {
                if (!grotto.contains(col(x, z)) || Math.abs(x) <= 2 && z >= cz - 3 || !air(k, x, sy, z) || k.rnd.nextInt(7) != 0) continue;
                boolean edge = !grotto.contains(col(x + 1, z)) || !grotto.contains(col(x - 1, z)) || !grotto.contains(col(x, z + 1)) || !grotto.contains(col(x, z - 1));
                if (edge && Math.abs(x) >= 3) k.set(x, sy, z, "amethyst_cluster[facing=up]");
            }
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
                else if (d > 9 && r < 49 && !grotto.contains(e.getKey())) k.set(x, sy, z, k.rnd.nextBoolean() ? "azalea" : "flowering_azalea");
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
