package net.alloymc.core.arcade.games.bedwars;

import net.alloymc.core.arcade.core.*;

/** Generated BedWars maps: team islands in a ring, diamond islands between them, an emerald centre. */
final class BedWarsMaps {
    private BedWarsMaps() {}

    record Theme(String top, String mid, String bottom, String accent, String path, String light, String tree, String leaves, String centre) {}

    static final Theme FORGE = new Theme("polished_deepslate", "deepslate_tiles", "cobbled_deepslate", "waxed_cut_copper", "waxed_copper_grate", "copper_lantern", "", "", "waxed_oxidized_copper");
    static final Theme VERDANT = new Theme("grass_block", "dirt", "stone", "gold_block", "moss_block", "lantern", "oak_log", "oak_leaves[persistent=true]", "emerald_block");
    static final Theme FROST = new Theme("snow_block", "packed_ice", "stone", "blue_ice", "polished_diorite", "sea_lantern", "spruce_log", "spruce_leaves[persistent=true]", "packed_ice");

    static final Theme ASHEN = new Theme("crimson_nylium", "netherrack", "blackstone", "gilded_blackstone", "polished_blackstone_bricks", "shroomlight", "crimson_stem", "nether_wart_block", "crying_obsidian");

    /**
     * v2 layout: big team bases (a 17x17 platform on a radius-13 island) with a covered forge over the generator,
     * market stalls for the two villagers and lamp posts; diamond islands with a little shrine between the bases;
     * stepping islets on the way in; and a two-tier centre with a beacon tower and the emerald generators on top.
     * Bed: foot 2 and head 3 blocks out from the base centre, generator 7 out, so there's room to cover the bed.
     */
    static BuiltMap build(String id, String name, String desc, int teams, int ringR, Theme t) {
        MapKit k = new MapKit(id.hashCode());
        MapDef d = new MapDef("bedwars", id);
        d.display = name; d.description = desc; d.teams = teams;
        d.minPlayers = 2; d.maxPlayers = teams * 4;
        d.builderVersion = 2;
        int ext = ringR + 24;
        d.bounds = Box.of(-ext, -40, -ext, ext, 40, ext);
        d.voidY = -22;
        d.spectator = new Pt(0.5, 30, 0.5, 0, 60);

        // ---- centre: wide lower plaza, a raised inner tier (one jump up) with the emerald generators, beacon tower
        k.island(0, 0, 0, 18, t.top(), t.mid(), t.bottom());
        k.disc(0, 0, 0, 12.5, t.path());
        k.ring(0, 0, 0, 11.5, 12.5, t.accent());
        k.disc(0, 1, 0, 7.5, t.centre());
        k.ring(0, 1, 0, 6.5, 7.5, t.accent());
        int[][] em = teams >= 8 ? new int[][]{{-4, -4}, {4, -4}, {-4, 4}, {4, 4}} : new int[][]{{-4, 0}, {4, 0}};
        for (int[] e : em) { k.set(e[0], 1, e[1], "emerald_block"); d.add("emerald_generators", Pt.of(e[0] + 0.5, 2, e[1] + 0.5)); }
        for (int y = 2; y <= 8; y++) k.set(0, y, 0, y == 8 ? "beacon" : y % 3 == 1 ? t.light() : t.accent());
        k.set(0, 9, 0, "emerald_block");
        for (int i = 0; i < 8; i++) {   // lamp posts round the plaza edge
            double a = Math.PI * 2 * i / 8 + Math.PI / 8;
            int x = (int) Math.round(Math.cos(a) * 10), z = (int) Math.round(Math.sin(a) * 10);
            for (int y = 1; y <= 3; y++) k.set(x, y, z, y == 3 ? t.light() : t.accent());
        }
        if (!t.tree().isEmpty()) for (int i = 0; i < 4; i++) {
            double a = Math.PI / 4 + i * Math.PI / 2;
            k.tree((int) Math.round(Math.cos(a) * 15), 1, (int) Math.round(Math.sin(a) * 15), t.tree(), t.leaves(), 5);
        }

        for (int i = 0; i < teams; i++) {
            double a = Math.PI * 2 * i / teams - Math.PI / 2;
            double ca = Math.cos(a), sa = Math.sin(a);
            int cx = (int) Math.round(ca * ringR), cz = (int) Math.round(sa * ringR);
            TeamColor col = TeamColor.of(i);
            // outward direction (away from the centre) for the bed and generator, sideways for the villagers
            int ox = (int) Math.round(ca), oz = (int) Math.round(sa);
            if (ox != 0 && oz != 0) { if (Math.abs(ca) > Math.abs(sa)) oz = 0; else ox = 0; }
            int sx = -oz, sz = ox;
            Base base = new Base(k, cx, cz, ox, oz, sx, sz);
            String facing = ox > 0 ? "east" : ox < 0 ? "west" : oz > 0 ? "south" : "north";

            // island + platform with a team-coloured rim
            k.island(cx, 0, cz, 13, t.top(), t.mid(), t.bottom());
            for (int u = -8; u <= 8; u++) for (int v = -8; v <= 8; v++)
                base.set(u, 0, v, Math.abs(u) == 8 || Math.abs(v) == 8 ? col.block("concrete") : Math.floorMod(u + v, 4) == 0 ? t.accent() : t.path());
            // corner lamps in team glass
            for (int[] c : new int[][]{{-8, -8}, {-8, 8}, {8, -8}, {8, 8}})
                for (int y = 1; y <= 3; y++) base.set(c[0], y, c[1], y == 3 ? t.light() : col.block("stained_glass"));
            // bed (foot nearer the centre, head pointing outward)
            k.late(cx + ox * 2, 1, cz + oz * 2, col.dye + "_bed[part=foot,facing=" + facing + "]");
            k.late(cx + ox * 3, 1, cz + oz * 3, col.dye + "_bed[part=head,facing=" + facing + "]");
            d.point("team." + i + ".bed", new Pt(cx + ox * 2 + 0.5, 1, cz + oz * 2 + 0.5, yawFor(facing), 0));
            // the forge: generator pad at u=7 under an open-fronted arch
            for (int v = -1; v <= 1; v++) base.set(7, 0, v, "iron_block");
            base.set(7, 0, 0, "gold_block");
            for (int v : new int[]{-2, 2}) for (int y = 1; y <= 3; y++) base.set(7, y, v, t.accent());
            for (int v = -2; v <= 2; v++) base.set(7, 4, v, col.block("concrete"));
            base.set(7, 4, 0, t.light());
            d.point("team." + i + ".generator", Pt.of(cx + ox * 7 + 0.5, 1, cz + oz * 7 + 0.5));
            // spawn in front of the bed, facing the middle
            d.point("team." + i + ".spawn", Pt.of(cx - ox * 4 + 0.5, 1, cz - oz * 4 + 0.5).facing(0.5, 0.5));
            // market stalls: villager on an accent block with posts and a team-wool awning
            for (int side : new int[]{1, -1}) {
                int vv = 5 * side;
                base.set(-1, 0, vv, t.accent());
                for (int[] post : new int[][]{{-3, vv - side}, {1, vv - side}, {-3, vv + side}, {1, vv + side}})
                    for (int y = 1; y <= 2; y++) base.set(post[0], y, post[1], t.accent());
                for (int u = -3; u <= 1; u++) for (int w = vv - side; w != vv + 2 * side; w += side) base.set(u, 3, w, col.block("wool"));
                base.set(-1, 3, vv, t.light());
                int vx = cx - ox + sx * vv, vz = cz - oz + sz * vv;
                d.point("team." + i + (side > 0 ? ".shop" : ".upgrades"), Pt.of(vx + 0.5, 1, vz + 0.5).facing(cx + 0.5, cz + 0.5));
            }
            // decoration off the platform
            if (!t.tree().isEmpty()) for (int side : new int[]{1, -1}) base.tree(-3, 11 * side, t);
            else for (int side : new int[]{1, -1}) for (int y = 1; y <= 4; y++) base.set(-3, y, 11 * side, y == 4 ? t.light() : "chain[axis=y]");

            // a stepping islet halfway to the middle, a little to one side
            int hx = (int) Math.round(ca * ringR * 0.52 - sa * 4), hz = (int) Math.round(sa * ringR * 0.52 + ca * 4);
            k.island(hx, -1, hz, 3.2, t.top(), t.mid(), t.bottom());
            k.set(hx, 0, hz, t.light());

            // diamond island between this team and the next, further out, with a little shrine
            double b2 = a + Math.PI / teams;
            int dx = (int) Math.round(Math.cos(b2) * (ringR + 10)), dz = (int) Math.round(Math.sin(b2) * (ringR + 10));
            k.island(dx, 0, dz, 6, t.top(), t.mid(), t.bottom());
            k.disc(dx, 0, dz, 3.5, t.path());
            k.set(dx, 0, dz, "diamond_block");
            for (int[] c : new int[][]{{-3, -3}, {3, -3}, {-3, 3}, {3, 3}})
                for (int y = 1; y <= 3; y++) k.set(dx + c[0], y, dz + c[1], y == 3 ? t.light() : t.accent());
            d.add("diamond_generators", Pt.of(dx + 0.5, 1, dz + 0.5));
        }
        d.waiting = d.spectator;
        return new BuiltMap(d, k.c);
    }

    /** Base-local coordinates: u = outward from the centre, v = sideways. */
    private record Base(MapKit k, int cx, int cz, int ox, int oz, int sx, int sz) {
        void set(int u, int y, int v, String block) { k.set(cx + ox * u + sx * v, y, cz + oz * u + sz * v, block); }
        void tree(int u, int v, Theme t) { k.tree(cx + ox * u + sx * v, 1, cz + oz * u + sz * v, t.tree(), t.leaves(), 4 + k.rnd.nextInt(2)); }
    }

    private static float yawFor(String facing) {
        return switch (facing) { case "south" -> 0; case "west" -> 90; case "north" -> 180; default -> -90; };
    }
}
