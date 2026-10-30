package net.alloymc.core.arcade.games.bedwars;

import java.util.ArrayList;
import java.util.List;

/**
 * The Golem's look as plain data: a list of boxes, each drawn in game as a block display. Local coordinates: feet at
 * y = 0, x to its left, z out of its chest (it faces +z, which is yaw 0). About 7.5 blocks tall to the tips of the ore.
 * Plain Java (no Bukkit), so the tests and the offline preview use exactly what the game spawns.
 *
 * The design is original: an ancient guardian of the ruined keep, hunched and knuckle-walking, mossy stone with a
 * glowing green heart-stone in its chest and hot-pink ore growing out of its back and shoulders.
 */
final class GolemModel {
    private GolemModel() {}

    /** Which parts move together in an animation. */
    enum Group { LEGS, BODY, HEAD, HEART, PLATE, ARM_L, ARM_R, ORE }

    /**
     * One box: centre (cx, cy, cz), size (sx, sy, sz), turned rx degrees about x (positive tips its top towards +z,
     * forward) and then rz about z (positive tips its top towards -x, the golem's right): rotation = Rz(rz) * Rx(rx). {@code glow} is a glow-outline colour (0xRRGGBB) or -1;
     * {@code bright} draws it at full brightness (lit from inside).
     */
    record Part(Group group, String block, double cx, double cy, double cz, double sx, double sy, double sz,
                double rx, double rz, int glow, boolean bright) {
        double top() { return cy + Math.max(sy, Math.max(sx, sz)) / 2; }
    }

    static final int HEART_GREEN = 0x6CFF7A, ORE_PINK = 0xFF1F8F;

    /** The whole model is drawn at this size (1 = the base design, ~6 blocks tall); 1.25 gives it presence in the tall hall. */
    static final double SCALE = 1.25;

    static final List<Part> PARTS = build();

    private static Part box(Group g, String block, double x1, double y1, double z1, double x2, double y2, double z2) {
        return new Part(g, block, (x1 + x2) / 2, (y1 + y2) / 2, (z1 + z2) / 2, x2 - x1, y2 - y1, z2 - z1, 0, 0, -1, false);
    }

    private static List<Part> build() {
        List<Part> l = new ArrayList<>();
        String stone = "minecraft:stone_bricks", mossy = "minecraft:mossy_stone_bricks", cobble = "minecraft:cobblestone",
                cracked = "minecraft:cracked_stone_bricks", moss = "minecraft:moss_block";
        // stubby legs
        for (int s : new int[]{-1, 1}) l.add(box(Group.LEGS, cobble, s * 0.75 - 0.4, 0, -0.5, s * 0.75 + 0.4, 1.3, 0.5));
        // the hunched body: lower torso, a wider upper torso pushed forward, the hump on top
        l.add(box(Group.BODY, stone, -1.7, 1.2, -1.0, 1.7, 2.5, 1.0));
        l.add(box(Group.BODY, mossy, -1.9, 2.4, -0.9, 1.9, 3.6, 1.3));
        l.add(box(Group.BODY, stone, -1.4, 3.5, -0.8, 1.4, 4.3, 1.0));
        l.add(box(Group.BODY, moss, -1.2, 4.3, -0.6, 1.2, 4.42, 0.8));
        // cracked chest plate (falls off in phase 2) and the heart-stone in front of it
        l.add(box(Group.PLATE, cracked, -1.2, 1.4, 1.0, 1.2, 2.8, 1.22));
        l.add(new Part(Group.HEART, "minecraft:verdant_froglight", 0, 2.1, 1.28, 0.55, 0.55, 0.12, 0, 0, HEART_GREEN, true));
        // low, wide head out in front of the shoulders: heavy brow, jaw, two glowing eyes under the brow
        l.add(box(Group.HEAD, cobble, -0.75, 3.25, 1.75, 0.75, 4.0, 2.5));
        l.add(box(Group.HEAD, stone, -1.0, 4.0, 1.7, 1.0, 4.3, 2.8));
        l.add(box(Group.HEAD, cobble, -0.5, 3.0, 2.0, 0.5, 3.25, 2.5));
        for (int s : new int[]{-1, 1})
            l.add(new Part(Group.HEAD, "minecraft:verdant_froglight", s * 0.45, 3.6, 2.52, 0.22, 0.16, 0.06, 0, 0, HEART_GREEN, true));
        // long arms, knuckle-walking: shoulder, upper arm, forearm, fist
        for (int s : new int[]{-1, 1}) {
            Group g = s < 0 ? Group.ARM_R : Group.ARM_L;   // -x is the golem's right (it faces +z)
            l.add(box(g, mossy, Math.min(s * 1.6, s * 2.6), 3.1, -0.5, Math.max(s * 1.6, s * 2.6), 4.0, 0.8));
            l.add(box(g, moss, Math.min(s * 1.7, s * 2.5), 4.0, -0.4, Math.max(s * 1.7, s * 2.5), 4.1, 0.7));
            l.add(box(g, stone, Math.min(s * 2.0, s * 2.8), 1.9, 0.0, Math.max(s * 2.0, s * 2.8), 3.3, 1.0));
            l.add(box(g, stone, Math.min(s * 2.2, s * 3.0), 0.7, 0.9, Math.max(s * 2.2, s * 3.0), 2.0, 1.9));
            l.add(box(g, cobble, Math.min(s * 2.0, s * 3.2), 0.0, 1.4, Math.max(s * 2.0, s * 3.2), 0.9, 2.5));
        }
        // hot-pink ore growing out of the back and shoulders: glowing crystal spikes leaning back and out
        double[][] spikes = {   // x, y, z of the root, length, lean back (deg), lean out (deg)
            {-1.0, 4.1, -0.6, 1.5, -28, 18}, {0, 4.3, -0.5, 1.7, -22, 0}, {1.0, 4.1, -0.6, 1.5, -28, -18},
            {-1.5, 3.2, -0.9, 1.1, -40, 25}, {1.5, 3.2, -0.9, 1.1, -40, -25}, {-0.5, 3.5, -0.85, 1.0, -35, 8},
            {0.5, 3.7, -0.85, 1.2, -32, -8}, {0, 2.5, -1.0, 0.8, -45, 0}, {-2.1, 4.05, 0.0, 0.8, -15, 30}, {2.1, 4.05, 0.0, 0.8, -15, -30}};
        for (double[] sp : spikes) {
            double len = sp[3], rx = sp[4], rz = sp[5];
            // centre the spike half its length out from its root, along its own leaning axis
            double ax = -Math.cos(Math.toRadians(rx)) * Math.sin(Math.toRadians(rz)), ay = Math.cos(Math.toRadians(rx)) * Math.cos(Math.toRadians(rz)), az = Math.sin(Math.toRadians(rx));
            double cx = sp[0] + ax * len / 2, cy = sp[1] + ay * len / 2, cz = sp[2] + az * len / 2;
            l.add(new Part(Group.ORE, "minecraft:pink_concrete", cx, cy, cz, 0.22, len, 0.22, rx, rz, ORE_PINK, true));
            if (len >= 1.2)   // the bigger crystals get a glassy sheath round the glowing core
                l.add(new Part(Group.ORE, "minecraft:pink_stained_glass", cx, cy - 0.1, cz, 0.34, len * 0.75, 0.34, rx, rz, -1, true));
        }
        // ore veins: specks of the same pink in the stone of its back
        double[][] veins = {{-1.1, 2.9}, {-0.3, 3.0}, {0.8, 2.7}, {1.3, 3.3}, {-0.9, 1.8}, {0.4, 1.6}, {1.4, 2.0}};
        for (double[] v : veins) l.add(new Part(Group.ORE, "minecraft:pink_concrete", v[0], v[1], v[1] > 2.4 ? -0.93 : -1.03, 0.2, 0.2, 0.04, 0, 0, -1, true));
        List<Part> scaled = new ArrayList<>();
        for (Part p : l) scaled.add(new Part(p.group(), p.block(), p.cx() * SCALE, p.cy() * SCALE, p.cz() * SCALE,
                p.sx() * SCALE, p.sy() * SCALE, p.sz() * SCALE, p.rx(), p.rz(), p.glow(), p.bright()));
        return List.copyOf(scaled);
    }

    /** Height of the model's highest point, in blocks. */
    static double height() { return PARTS.stream().mapToDouble(Part::top).max().orElse(0); }

    /** Half-width of the model (furthest point from the middle, sideways), in blocks. */
    static double halfWidth() { return PARTS.stream().mapToDouble(p -> Math.abs(p.cx()) + p.sx() / 2).max().orElse(0); }
}
