package net.alloymc.core.arcade.games.duels;

import net.alloymc.core.arcade.core.*;

import java.util.*;

/** Duel arenas, each tagged with the kits (variants) it supports. */
final class DuelMaps {
    private DuelMaps() {}

    private static final List<String> OPEN = List.of("classic_1v1", "nodebuff_1v1", "boxing_1v1", "combo_1v1", "classic_2v2", "ranked_classic", "ranked_nodebuff");
    private static final List<String> SUMO = List.of("sumo_1v1", "sumo_2v2", "ranked_sumo");
    private static final List<String> BUILD = List.of("builduhc_1v1", "builduhc_2v2");

    static List<BuiltMap> all() {
        return List.of(coliseum(), ironyard(), grove(), dojo(), stoneRing(), canyon(), highlands());
    }

    private static MapDef base(String id, String name, String desc, List<String> kits, int half, int voidY) {
        MapDef d = new MapDef("duels", id);
        d.display = name; d.description = desc; d.teams = 2; d.minPlayers = 2; d.maxPlayers = 4;
        d.variants.addAll(kits);
        d.bounds = Box.of(-half, voidY - 6, -half, half, 40, half);
        d.voidY = voidY;
        d.spectator = new Pt(0.5, 14, -half + 4.5, 0, 35);
        return d;
    }

    private static void spawns(MapDef d, int dist, int y) {
        d.point("team.0.spawn", Pt.of(0.5, y, -dist + 0.5).facing(0.5, 0.5));
        d.point("team.1.spawn", Pt.of(0.5, y, dist + 0.5).facing(0.5, 0.5));
    }

    private static BuiltMap coliseum() {
        MapKit k = new MapKit(41);
        int R = 22;
        for (int x = -R - 3; x <= R + 3; x++) for (int z = -R - 3; z <= R + 3; z++) {
            double r = Math.hypot(x, z);
            if (r <= R) { k.set(x, 0, z, (int) r % 6 == 0 ? "chiseled_sandstone" : k.pick("smooth_sandstone", "smooth_sandstone", "sandstone")); k.set(x, -1, z, "sandstone"); }
            else if (r <= R + 3) for (int y = 0; y <= 6 + (int) (r - R); y++) k.set(x, y, z, y == 6 + (int) (r - R) ? "cut_red_sandstone" : (y % 3 == 2 && ((x + z) & 3) == 0) ? "air" : "smooth_sandstone");
        }
        for (int i = 0; i < 16; i++) { double a = i * Math.PI / 8; int x = (int) Math.round(Math.cos(a) * (R - 1)), z = (int) Math.round(Math.sin(a) * (R - 1)); for (int y = 1; y <= 4; y++) k.set(x, y, z, y == 4 ? "lantern" : "sandstone_wall"); }
        MapDef d = base("coliseum", "Coliseum", "A sandstone arena under the open sky.", OPEN, R + 4, -6);
        spawns(d, 12, 1);
        return new BuiltMap(d, k.c);
    }

    private static BuiltMap ironyard() {
        MapKit k = new MapKit(42);
        int H = 18;
        for (int x = -H; x <= H; x++) for (int z = -H; z <= H; z++) {
            k.set(x, 0, z, ((x + z) & 1) == 0 ? "polished_deepslate" : "deepslate_tiles");
            k.set(x, -1, z, "deepslate");
            if (Math.abs(x) == H || Math.abs(z) == H) for (int y = 1; y <= 7; y++) k.set(x, y, z, y == 7 ? "waxed_cut_copper" : y == 4 ? "waxed_copper_bulb[lit=true]" : "deepslate_bricks");
        }
        for (int[] p : new int[][]{{-8, -4}, {8, -4}, {-8, 4}, {8, 4}, {0, 0}}) for (int y = 1; y <= 3; y++) for (int dx = 0; dx <= 1; dx++) k.set(p[0] + dx, y, p[1], y == 3 ? "waxed_oxidized_cut_copper" : "iron_block");
        MapDef d = base("ironyard", "Ironyard", "An enclosed foundry yard with iron cover.", OPEN, H + 1, -4);
        spawns(d, 13, 1);
        return new BuiltMap(d, k.c);
    }

    private static BuiltMap grove() {
        MapKit k = new MapKit(43);
        int R = 20;
        for (int x = -R; x <= R; x++) for (int z = -R; z <= R; z++) {
            if (Math.hypot(x, z) > R + MapKit.noise(x / 4.0, z / 4.0, 3) * 3) continue;
            k.set(x, 0, z, MapKit.noise(x / 3.0, z / 3.0, 5) > 0.7 ? "moss_block" : "grass_block");
            for (int y = -4; y <= -1; y++) k.set(x, y, z, y == -1 ? "dirt" : "stone");
        }
        for (int i = 0; i < 9; i++) { int x = k.rnd.nextInt(30) - 15, z = k.rnd.nextInt(16) - 8; if (Math.abs(z) > 3 || Math.abs(x) > 3) k.tree(x, 1, z, "oak_log", "oak_leaves[persistent=true]", 4 + k.rnd.nextInt(2)); }
        MapDef d = base("grove", "Grove", "A forest clearing with trees for cover.", OPEN, R + 4, -8);
        spawns(d, 14, 1);
        return new BuiltMap(d, k.c);
    }

    private static BuiltMap dojo() {
        MapKit k = new MapKit(44);
        int H = 6;
        for (int x = -H; x <= H; x++) for (int z = -H; z <= H; z++) { k.set(x, 0, z, Math.abs(x) == H || Math.abs(z) == H ? "stripped_dark_oak_log[axis=x]" : "bamboo_mosaic"); k.set(x, -1, z, "dark_oak_planks"); }
        for (int[] c : new int[][]{{-H, -H}, {H, -H}, {-H, H}, {H, H}}) { for (int y = -6; y <= -1; y++) k.set(c[0], y, c[1], "dark_oak_log"); k.late(c[0], 1, c[1], "lantern"); }
        MapDef d = base("dojo", "Dojo", "A tiny bamboo platform above the void.", SUMO, 14, -3);
        spawns(d, 3, 1);
        d.spectator = new Pt(0.5, 8, -10.5, 0, 30);
        return new BuiltMap(d, k.c);
    }

    private static BuiltMap stoneRing() {
        MapKit k = new MapKit(45);
        k.disc(0, 0, 0, 7.5, "polished_andesite");
        k.ring(0, 0, 0, 6.5, 7.5, "stone_bricks");
        k.disc(0, -1, 0, 7.5, "stone");
        k.disc(0, -2, 0, 5, "stone");
        k.disc(0, -3, 0, 2.5, "cobblestone");
        k.set(0, 0, 0, "chiseled_stone_bricks");
        MapDef d = base("stone_ring", "Stone Ring", "A round stone platform - no edges to hide behind.", SUMO, 15, -3);
        spawns(d, 4, 1);
        d.spectator = new Pt(0.5, 8, -11.5, 0, 30);
        return new BuiltMap(d, k.c);
    }

    private static BuiltMap terrain(String id, String name, String desc, long seed, String top, String under, String tree, String leaves) {
        MapKit k = new MapKit(seed);
        int H = 24;
        for (int x = -H; x <= H; x++) for (int z = -H; z <= H; z++) {
            int h = (int) Math.round(MapKit.fbm(x + 50, z + 50, seed) * 9) - 2;
            k.set(x, h, z, top);
            for (int y = -6; y < h; y++) k.set(x, y, z, y >= h - 3 ? under : "stone");
            if (Math.abs(x) == H || Math.abs(z) == H) for (int y = h + 1; y <= 22; y++) k.set(x, y, z, "barrier");
            if (k.rnd.nextInt(70) == 0 && Math.abs(z) > 4) k.tree(x, h + 1, z, tree, leaves, 4);
        }
        // flat spawn pads
        for (int s : new int[]{-16, 16}) for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) { k.fill(x, 0, s + z, x, 12, s + z, "air"); k.set(x, 0, s + z, top); for (int y = -6; y < 0; y++) k.set(x, y, s + z, under); }
        MapDef d = base(id, name, desc, BUILD, H, -10);
        spawns(d, 16, 1);
        d.spectator = new Pt(0.5, 22, -20.5, 0, 30);
        return new BuiltMap(d, k.c);
    }

    private static BuiltMap canyon() { return terrain("canyon", "Red Canyon", "Rolling red sand hills - build, bridge and burn.", 46, "red_sand", "terracotta", "acacia_log", "acacia_leaves[persistent=true]"); }
    private static BuiltMap highlands() { return terrain("highlands", "Highlands", "Grassy hills with scattered trees.", 47, "grass_block", "dirt", "spruce_log", "spruce_leaves[persistent=true]"); }
}
