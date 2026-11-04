package net.blackforge.core.hub;

import net.blackforge.core.build.BlockFont;
import net.blackforge.core.build.Canvas;

import java.util.Random;

import static net.blackforge.core.hub.HubLayout.Y;

/**
 * Generates the BlackForgeMC hub: a dark volcanic forge island with a molten crucible,
 * four portal gates and giant cast-metal lettering. Every bit of lava a player can get near
 * is sealed under glass or behind a rail (checked offline by gradle walkcheck).
 */
public final class HubBuilder {
    final Canvas c = new Canvas();
    private final Random rnd = new Random(0xA110E);

    public static Canvas build() {
        HubBuilder b = new HubBuilder();
        ForgeHub f = new ForgeHub(b.c);
        f.rock();
        f.fountain();
        f.braziers();
        f.halls();
        for (HubLayout.Gate g : HubLayout.GATES) { b.gate(g); f.gatehouse(g); }
        f.trees();
        f.sign();
        KeepBuilder.addTo(b.c);
        f.islands(b);
        CastleBuilder.addTo(b.c);
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
    void island(int ox, int oy, int oz, double scale, boolean main) {
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

    // ------------------------------------------------------------------ gates

    private interface Put { void at(int u, int y, int w, String b); }

    /** Just one portal gate (for adding a gate to a hub that is already built). */
    public static Canvas buildGate(HubLayout.Gate g) {
        HubBuilder b = new HubBuilder();
        b.gate(g);
        return b.c;
    }

    /** Arcade gate bands: glowing at the opening, cooling to blackstone at the edge. */
    private static final String[] FIRE = {"minecraft:shroomlight", "minecraft:orange_concrete", "minecraft:red_concrete",
            "minecraft:red_nether_bricks", "minecraft:nether_bricks", "minecraft:polished_blackstone_bricks"};

    void gate(HubLayout.Gate g) {
        Put put = (u, y, w, b) -> {
            if (g.alongX()) c.set(g.cx() + u, y, g.cz() + w, b);
            else c.set(g.cx() + w, y, g.cz() + u, b);
        };
        String main, accent, trim, light;
        switch (g.theme()) {
            case "oxidized" -> { main = "minecraft:polished_blackstone_bricks"; accent = "minecraft:warped_wart_block"; trim = "minecraft:warped_planks"; light = "minecraft:sea_lantern"; }
            case "iron" -> { main = "minecraft:smooth_basalt"; accent = "minecraft:iron_block"; trim = "minecraft:polished_blackstone_bricks"; light = "minecraft:shroomlight"; }
            case "netherite" -> { main = "minecraft:polished_blackstone_bricks"; accent = "minecraft:netherite_block"; trim = "minecraft:red_nether_bricks"; light = "minecraft:shroomlight"; }
            case "prismarine" -> { main = "minecraft:dark_prismarine"; accent = "minecraft:prismarine_bricks"; trim = "minecraft:cut_copper"; light = "minecraft:sea_lantern"; }
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
            case "prismarine" -> {  // a crest of sea lanterns and a ship's wheel of chains over the harbour
                for (int u = -5; u <= 5; u++) put.at(u, y, 0, Math.abs(u) % 2 == 0 ? "minecraft:sea_lantern" : "minecraft:prismarine_bricks");
                for (int u = -3; u <= 3; u++) put.at(u, y + 1, 0, "minecraft:prismarine_brick_slab[type=bottom]");
                put.at(0, y + 1, 0, "minecraft:conduit");
                for (int side : new int[]{-1, 1}) for (int k = 1; k <= 3; k++) put.at(side * 7, y - k, 0, "minecraft:iron_chain[axis=y]");
            }
            default -> {           // arcade marquee
                for (int u = -4; u <= 4; u++) put.at(u, y, 0, "minecraft:gold_block");
                for (int u = -3; u <= 3; u++) put.at(u, y + 1, 0, u % 2 == 0 ? "minecraft:tnt" : "minecraft:note_block");
                put.at(0, y + 2, 0, "minecraft:jukebox");
            }
        }
    }

    /** Front-layer {x, y, z} of every lit sign letter block (the face players see from spawn). Used by WelcomeShow. */
    static java.util.List<int[]> signFront() { return ForgeHub.signFront(); }
}
