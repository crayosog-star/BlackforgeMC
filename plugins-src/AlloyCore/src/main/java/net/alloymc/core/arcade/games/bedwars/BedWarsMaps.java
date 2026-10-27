package net.alloymc.core.arcade.games.bedwars;

import net.alloymc.core.arcade.core.*;

/** Generated BedWars maps: team islands in a ring, diamond islands between them, an emerald centre. */
final class BedWarsMaps {
    private BedWarsMaps() {}

    record Theme(String top, String mid, String bottom, String accent, String path, String light, String tree, String leaves, String centre) {}

    static final Theme FORGE = new Theme("polished_deepslate", "deepslate_tiles", "cobbled_deepslate", "waxed_cut_copper", "waxed_copper_grate", "copper_lantern", "", "", "waxed_oxidized_copper");
    static final Theme VERDANT = new Theme("grass_block", "dirt", "stone", "gold_block", "moss_block", "lantern", "oak_log", "oak_leaves[persistent=true]", "emerald_block");
    static final Theme FROST = new Theme("snow_block", "packed_ice", "stone", "blue_ice", "polished_diorite", "sea_lantern", "spruce_log", "spruce_leaves[persistent=true]", "packed_ice");

    static BuiltMap build(String id, String name, String desc, int teams, int ringR, Theme t) {
        MapKit k = new MapKit(id.hashCode());
        MapDef d = new MapDef("bedwars", id);
        d.display = name; d.description = desc; d.teams = teams;
        d.minPlayers = 2; d.maxPlayers = teams * 4;
        int ext = ringR + 14;
        d.bounds = Box.of(-ext, -40, -ext, ext, 40, ext);
        d.voidY = -22;
        d.spectator = new Pt(0.5, 30, 0.5, 0, 60);

        // centre island with emerald generators
        k.island(0, 0, 0, 9, t.top(), t.mid(), t.bottom());
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) k.set(x, 0, z, t.centre());
        int[][] em = teams >= 8 ? new int[][]{{-3, -3}, {3, -3}, {-3, 3}, {3, 3}} : new int[][]{{-3, 0}, {3, 0}};
        for (int[] e : em) { k.set(e[0], 0, e[1], "emerald_block"); d.add("emerald_generators", Pt.of(e[0] + 0.5, 1, e[1] + 0.5)); }
        for (int y = 1; y <= 6; y++) k.set(0, y, 0, y == 6 ? "beacon" : t.accent());
        k.set(0, 7, 0, "emerald_block");

        for (int i = 0; i < teams; i++) {
            double a = Math.PI * 2 * i / teams - Math.PI / 2;
            double ca = Math.cos(a), sa = Math.sin(a);
            int cx = (int) Math.round(ca * ringR), cz = (int) Math.round(sa * ringR);
            TeamColor col = TeamColor.of(i);
            // team island: 11x11 core plus natural edge
            k.island(cx, 0, cz, 8, t.top(), t.mid(), t.bottom());
            for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) k.set(cx + x, 0, cz + z, (Math.abs(x) == 5 || Math.abs(z) == 5) ? col.block("concrete") : t.path());
            // outward direction (away from the centre) for the bed and generator, sideways for the villagers
            int ox = (int) Math.round(ca), oz = (int) Math.round(sa);
            if (ox != 0 && oz != 0) { if (Math.abs(ca) > Math.abs(sa)) oz = 0; else ox = 0; }
            int sx = -oz, sz = ox;   // perpendicular
            String facing = ox > 0 ? "east" : ox < 0 ? "west" : oz > 0 ? "south" : "north";
            String back = ox > 0 ? "west" : ox < 0 ? "east" : oz > 0 ? "north" : "south";
            // bed (foot nearer the centre, head pointing outward)
            int bx = cx + ox * 2, bz = cz + oz * 2;
            k.late(bx, 1, bz, col.dye + "_bed[part=foot,facing=" + facing + "]");
            k.late(bx + ox, 1, bz + oz, col.dye + "_bed[part=head,facing=" + facing + "]");
            d.point("team." + i + ".bed", new Pt(bx + 0.5, 1, bz + 0.5, yawFor(facing), 0));
            // generator pad at the back edge
            int gx = cx + ox * 5, gz = cz + oz * 5;
            for (int u = -1; u <= 1; u++) k.set(gx + sx * u, 0, gz + sz * u, "iron_block");
            k.set(gx, 0, gz, "gold_block");
            d.point("team." + i + ".generator", Pt.of(gx + 0.5, 1, gz + 0.5));
            // spawn in front of the bed, facing the middle
            Pt spawn = Pt.of(cx - ox * 2 + 0.5, 1, cz - oz * 2 + 0.5).facing(0.5, 0.5);
            d.point("team." + i + ".spawn", spawn);
            // villagers on the two sides with a little awning
            int vx = cx + sx * 4, vz = cz + sz * 4, ux = cx - sx * 4, uz = cz - sz * 4;
            d.point("team." + i + ".shop", Pt.of(vx + 0.5, 1, vz + 0.5).facing(cx + 0.5, cz + 0.5));
            d.point("team." + i + ".upgrades", Pt.of(ux + 0.5, 1, uz + 0.5).facing(cx + 0.5, cz + 0.5));
            for (int[] v : new int[][]{{vx, vz}, {ux, uz}}) {
                k.set(v[0], 0, v[1], t.accent());
                k.late(v[0] - ox, 3, v[1] - oz, t.light() + (t.light().contains("lantern") ? "[hanging=false]" : ""));
            }
            // a couple of decorations
            if (!t.tree().isEmpty()) k.tree(cx - sx * 6 + ox * 3, 1, cz - sz * 6 + oz * 3, t.tree(), t.leaves(), 4);
            else for (int y = 1; y <= 3; y++) k.set(cx - sx * 6 + ox * 3, y, cz - sz * 6 + oz * 3, y == 3 ? t.light() : "chain[axis=y]");
            // diamond island between this team and the next, further out
            double b2 = a + Math.PI / teams;
            int dx = (int) Math.round(Math.cos(b2) * (ringR + 6)), dz = (int) Math.round(Math.sin(b2) * (ringR + 6));
            k.island(dx, 0, dz, 4, t.top(), t.mid(), t.bottom());
            k.set(dx, 0, dz, "diamond_block");
            d.add("diamond_generators", Pt.of(dx + 0.5, 1, dz + 0.5));
        }
        d.waiting = d.spectator;
        return new BuiltMap(d, k.c);
    }

    private static float yawFor(String facing) {
        return switch (facing) { case "south" -> 0; case "west" -> 90; case "north" -> 180; default -> -90; };
    }
}
