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

    static final int CORE_R = 20;        // centre island radius
    static final int PLAZA_R = 5;        // the empty objective plaza in the middle of the Core

    static BuiltMap build(String id, String name, String desc, int teams, int ringR, Theme t, String... variants) {
        MapKit k = new MapKit(id.hashCode());
        MapDef d = new MapDef(ForgeWars.ID, id);
        d.display = name; d.description = desc; d.teams = teams;
        d.minPlayers = 2; d.maxPlayers = teams * 4;
        d.variants.addAll(java.util.List.of(variants));
        d.builderVersion = 1;
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
        k.disc(0, 0, 0, 14.5, t.path());
        k.ring(0, 0, 0, 13.5, 14.5, t.accent());
        // objective plaza: flat, open, a bright rim so it reads as "something happens here" from every island
        k.disc(0, 0, 0, PLAZA_R + 0.5, t.core());
        k.ring(0, 0, 0, PLAZA_R - 0.5, PLAZA_R + 0.5, t.coreRim());
        d.point("core", Pt.of(0.5, 1, 0.5));
        // emerald pedestals: one block up, between the plaza and the outer ring
        int[][] em = teams >= 8 ? new int[][]{{-8, -8}, {8, -8}, {-8, 8}, {8, 8}} : new int[][]{{-9, 0}, {9, 0}, {0, -9}, {0, 9}};
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
        // lamp posts round the outer ring
        for (int i = 0; i < 8; i++) {
            double a = Math.PI * 2 * i / 8 + Math.PI / 8;
            int x = (int) Math.round(Math.cos(a) * 17), z = (int) Math.round(Math.sin(a) * 17);
            for (int y = 1; y <= 4; y++) k.set(x, y, z, y == 4 ? t.light() : t.accent());
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
