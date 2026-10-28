package net.alloymc.core.arcade.games.skywars;

import net.alloymc.core.arcade.core.*;

/**
 * Generated SkyWars maps: lumpy spawn islands in a ring, themed mid islands, a big centre island with a temple.
 * Islands carry life - grass and flowers, trees and bushes, ore veins in their undersides, hanging roots and
 * vines, ponds, little ruins and camps, crystal islets in between - and each spawn island has a crafting
 * table and furnace so players can craft from what they mine.
 */
final class SkyWarsMaps {
    private SkyWarsMaps() {}

    record Theme(String top, String mid, String bottom, String wall, String pillar, String roof, String light, String log, String leaves,
                 String[] flora, String[] ores, String hanging, String path, String water, String crystal) {}

    static final Theme RUINS = new Theme("grass_block", "dirt", "stone", "stone_bricks", "cracked_stone_bricks", "mossy_stone_bricks", "lantern", "oak_log",
            "oak_leaves[persistent=true]", new String[]{"short_grass", "short_grass", "short_grass", "fern", "poppy", "dandelion", "oxeye_daisy", "azure_bluet"},
            new String[]{"coal_ore", "coal_ore", "iron_ore", "copper_ore"}, "vine[north=true]", "mossy_cobblestone", "water", "amethyst_block");
    static final Theme GILDED = new Theme("moss_block", "rooted_dirt", "calcite", "gold_block", "emerald_block", "waxed_cut_copper", "sea_lantern", "birch_log",
            "flowering_azalea_leaves[persistent=true]", new String[]{"moss_carpet", "short_grass", "pink_petals[flower_amount=4]", "allium", "lily_of_the_valley", "cornflower", "azalea"},
            new String[]{"iron_ore", "gold_ore", "copper_ore", "lapis_ore"}, "hanging_roots", "smooth_quartz", "water", "amethyst_block");
    static final Theme CINDER = new Theme("crimson_nylium", "netherrack", "basalt", "polished_blackstone_bricks", "gilded_blackstone", "nether_bricks", "shroomlight", "crimson_stem",
            "nether_wart_block", new String[]{"crimson_roots", "crimson_roots", "crimson_fungus", "crimson_roots", "crimson_fungus"},
            new String[]{"nether_quartz_ore", "nether_gold_ore", "gilded_blackstone", "magma_block"}, "weeping_vines", "polished_basalt", "lava", "crying_obsidian");

    static BuiltMap build(String id, String name, String desc, int islands, int ringR, Theme t) {
        MapKit k = new MapKit(id.hashCode());
        MapDef d = new MapDef("skywars", id);
        d.display = name; d.description = desc;
        d.minPlayers = 2; d.maxPlayers = islands;
        d.builderVersion = 3;
        int ext = ringR + 16;
        d.bounds = Box.of(-ext, -40, -ext, ext, 50, ext);
        d.voidY = -25;
        d.spectator = new Pt(0.5, 35, 0.5, 0, 60);
        d.teams = islands / 2;

        // ---- centre island: a bigger lumpy island, the temple, an enchanting/brewing nook and trees around it
        land(k, t, 0, 0, 0, 13, true);
        for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) k.set(x, 0, z, Math.abs(x) == 5 || Math.abs(z) == 5 ? t.path() : t.wall());
        for (int[] c : new int[][]{{-4, -4}, {4, -4}, {-4, 4}, {4, 4}}) for (int y = 1; y <= 5; y++) k.set(c[0], y, c[1], y == 3 ? t.light() : t.pillar());
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) if (Math.abs(x) == 4 || Math.abs(z) == 4) k.set(x, 6, z, t.roof());
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) if (Math.abs(x) == 2 || Math.abs(z) == 2) k.set(x, 7, z, t.roof());
        k.set(0, 7, 0, t.roof()); k.late(0, 6, 0, t.light().equals("lantern") ? "lantern[hanging=true]" : t.light());
        int[][] cc = {{-2, -2}, {2, -2}, {-2, 2}, {2, 2}, {0, -3}, {0, 3}};
        for (int[] c : cc) { k.set(c[0], 1, c[1], "chest"); d.add("center_chests", Pt.of(c[0], 1, c[1])); }
        k.set(0, 1, 0, "enchanting_table");
        k.set(-3, 1, 0, "anvil"); k.set(3, 1, 0, "brewing_stand"); k.set(3, 1, 1, "crafting_table");
        for (int i = 0; i < 4; i++) {
            double a = Math.PI / 4 + i * Math.PI / 2;
            int tx = (int) Math.round(Math.cos(a) * 9), tz = (int) Math.round(Math.sin(a) * 9);
            if (!t.log().isEmpty()) k.tree(tx, 1, tz, t.log(), t.leaves(), 4 + k.rnd.nextInt(2));
        }
        // diamond/emerald vein deep in the centre's underside - worth digging for
        for (int i = 0; i < 6; i++) k.set(k.rnd.nextInt(5) - 2, -5 - k.rnd.nextInt(3), k.rnd.nextInt(5) - 2, i < 4 ? "diamond_ore" : "emerald_ore");

        // ---- ring of themed mid islands, each with a chest, something to look at and a couple of animals
        int mids = islands / 2;
        for (int i = 0; i < mids; i++) {
            double a = Math.PI * 2 * (i + 0.5) / mids;
            int mx = (int) Math.round(Math.cos(a) * ringR * 0.55), mz = (int) Math.round(Math.sin(a) * ringR * 0.55);
            land(k, t, mx, -2, mz, 5, false);
            k.set(mx, -1, mz, "chest");
            d.add("center_chests", Pt.of(mx, -1, mz));
            feature(k, t, mx, -1, mz, i % 4);
            d.add("animals", Pt.of(mx + 2.5, -1, mz - 1.5));
        }
        // ---- spawn islands: hill, tree or bush, flowers, crafting table + furnace, ores to mine underneath
        for (int i = 0; i < islands; i++) {
            double a = Math.PI * 2 * i / islands;
            int sx = (int) Math.round(Math.cos(a) * ringR), sz = (int) Math.round(Math.sin(a) * ringR);
            land(k, t, sx, 0, sz, 7, false);
            int ox = (int) Math.round(Math.cos(a) * 3), oz = (int) Math.round(Math.sin(a) * 3);
            int px = -(int) Math.round(Math.sin(a) * 2), pz = (int) Math.round(Math.cos(a) * 2);
            k.set(sx + ox + px, 1, sz + oz + pz, "chest");
            k.set(sx + ox - px, 1, sz + oz - pz, "chest");
            d.add("island_chests", Pt.of(sx + ox + px, 1, sz + oz + pz));
            d.add("island_chests", Pt.of(sx + ox - px, 1, sz + oz - pz));
            k.set(sx - px, 1, sz - pz, "crafting_table");
            k.set(sx + px, 1, sz + pz, "furnace");
            int bx = sx + (int) Math.round(Math.cos(a) * 4.5), bz = sz + (int) Math.round(Math.sin(a) * 4.5);
            if (!t.log().isEmpty() && i % 2 == 0) k.tree(bx, 1, bz, t.log(), t.leaves(), 4);
            else bush(k, t, bx, 1, bz);
            d.add("spawns", Pt.of(sx + 0.5, 1, sz + 0.5).facing(0.5, 0.5));
        }
        // ---- small crystal islets drifting between the rings (decoration, and a few blocks to grab)
        for (int i = 0; i < islands; i++) {
            double a = Math.PI * 2 * (i + 0.5) / islands;
            int r = (int) (ringR * 0.8);
            int cx = (int) Math.round(Math.cos(a) * r), cz = (int) Math.round(Math.sin(a) * r), cy = 6 + k.rnd.nextInt(8);
            k.island(cx, cy, cz, 1.6, t.bottom(), t.bottom(), t.bottom());
            k.set(cx, cy + 1, cz, t.crystal());
            if (t.crystal().equals("amethyst_block")) k.late(cx, cy + 2, cz, "amethyst_cluster");
        }
        return new BuiltMap(d, k.c);
    }

    /** A living island: lumpy top with a little hill, ground cover and flowers, ores in the rock, roots/vines underneath. */
    private static void land(MapKit k, Theme t, int cx, int y, int cz, double r, boolean big) {
        k.island(cx, y, cz, r, t.top(), t.mid(), t.bottom());
        int ri = (int) Math.ceil(r);
        double hx = cx + (k.rnd.nextDouble() - 0.5) * r * 0.8, hz = cz + (k.rnd.nextDouble() - 0.5) * r * 0.8;
        for (int x = cx - ri; x <= cx + ri; x++) for (int z = cz - ri; z <= cz + ri; z++) {
            String top = k.get(x, y, z);
            if (top == null || !top.endsWith(t.top())) continue;
            // a soft hill: one or two extra layers near a random spot (never on the spawn/chest area)
            double hd = Math.hypot(x - hx, z - hz);
            // hills stay off the middle, where the chests, crafting table and spawn sit
            int lift = Math.hypot(x - cx, z - cz) < (big ? 7 : 5) ? 0 : hd < r * 0.28 ? 2 : hd < r * 0.5 ? 1 : 0;
            for (int l = 1; l <= lift; l++) { k.set(x, y + l - 1, z, t.mid()); k.set(x, y + l, z, t.top()); }
            int ty = y + lift;
            if (Math.hypot(x - cx, z - cz) >= 3 && !k.isSet(x, ty + 1, z) && k.rnd.nextInt(100) < 38)
                k.late(x, ty + 1, z, t.flora()[k.rnd.nextInt(t.flora().length)]);
            // walk down the column: ore pockets in the rock, roots/vines hanging off the bottom
            int bot = y - 1;
            while (k.isSet(x, bot - 1, z)) bot--;
            for (int yy = bot; yy <= y - 3; yy++) if (k.rnd.nextInt(100) < 9) k.set(x, yy, z, t.ores()[k.rnd.nextInt(t.ores().length)]);
            if (k.rnd.nextInt(100) < 22) {
                int len = 1 + k.rnd.nextInt(3);
                for (int h = 1; h <= len; h++) if (!k.isSet(x, bot - h, z)) k.late(x, bot - h, z, t.hanging().startsWith("vine") ? "glow_lichen[down=false,up=true]" : t.hanging());
            }
        }
    }

    private static void bush(MapKit k, Theme t, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = 0; dy <= 1; dy++)
            if (Math.abs(dx) + Math.abs(dz) + dy <= 2 && !k.isSet(x + dx, y + dy, z + dz)) k.set(x + dx, y + dy, z + dz, t.leaves());
        k.set(x, y, z, t.log());
    }

    /** One of four little scenes for a mid island: ruined tower, pond, camp, or loot cart. */
    private static void feature(MapKit k, Theme t, int x, int y, int z, int kind) {
        switch (kind) {
            case 0 -> {   // ruined watchtower
                for (int h = 0; h <= 4; h++) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    if (h > 2 && k.rnd.nextInt(3) == 0) continue;
                    k.set(x - 3 + dx, y + h, z + 2 + dz, h == 4 ? t.roof() : (k.rnd.nextBoolean() ? t.wall() : t.pillar()));
                }
                k.late(x - 3, y, z + 2, t.light().equals("lantern") ? "lantern" : t.light());
            }
            case 1 -> {   // pond (water - or lava on Cinderfall) with a rim
                for (int dx = -1; dx <= 1; dx++) for (int dz = 0; dz <= 1; dz++) k.set(x + 2 + dx, y - 1, z + 2 + dz, t.water());
                for (int dx = -2; dx <= 2; dx++) for (int dz = -1; dz <= 2; dz++) if (!k.isSet(x + 2 + dx, y - 1, z + 2 + dz) || Math.abs(dx) == 2 || dz == -1 || dz == 2)
                    if (!(Math.abs(dx) <= 1 && dz >= 0 && dz <= 1)) k.set(x + 2 + dx, y - 1, z + 2 + dz, t.path());
                for (int dx = -2; dx <= 2; dx++) for (int dz = -1; dz <= 2; dz++) k.set(x + 2 + dx, y, z + 2 + dz, "air");
            }
            case 2 -> {   // campsite
                k.set(x - 2, y, z - 2, "campfire[lit=true]");
                k.set(x - 3, y, z - 2, "hay_block"); k.set(x - 1, y, z - 3, "barrel[facing=up]");
                k.set(x - 2, y, z - 3, t.log().isEmpty() ? t.pillar() : t.log().replace("_stem", "_hyphae").replace("_log", "_wood"));
            }
            default -> {  // old loot cart on rails
                for (int dz = -2; dz <= 2; dz++) k.set(x + 2, y, z + dz, "rail");
                k.set(x + 2, y, z + 3, t.pillar());
                k.set(x + 3, y, z, "target");
            }
        }
    }
}
