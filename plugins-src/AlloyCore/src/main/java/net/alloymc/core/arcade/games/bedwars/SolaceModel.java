package net.alloymc.core.arcade.games.bedwars;

import java.util.ArrayList;
import java.util.List;

/**
 * Solace's look as plain data: a list of boxes, each drawn in game as a block display. Local coordinates: y = 0 is the
 * floor it hovers over, x to its left, z out of its chest (it faces +z). Plain Java (no Bukkit), so the tests and offline
 * renders use exactly what the game will spawn. Model only for now: nothing spawns it yet.
 *
 * The design is original: a floating wraith with no legs, a tall hood, a cracked porcelain mask, a tattered cloak, long
 * thin clawed arms, a gold lantern-heart in its chest and a broken halo of gold shards behind its head.
 */
final class SolaceModel {
    private SolaceModel() {}

    /** Which parts will move together in an animation. */
    enum Group { CLOAK, BODY, HOOD, MASK, HEART, ARM_L, ARM_R, HALO }

    /** One box: centre, size, then rx about x and rz about z, in degrees: rotation = Rz(rz) * Rx(rx), as in GolemModel. */
    record Part(Group group, String block, double cx, double cy, double cz, double sx, double sy, double sz,
                double rx, double rz, int glow, boolean bright) {
        double top() { return cy + Math.max(sy, Math.max(sx, sz)) / 2; }
    }

    static final int GOLD = 0xFFD36B;
    private static final String SHADOW = "minecraft:black_concrete", CLOTH = "minecraft:black_wool",
            VIOLET = "minecraft:gray_wool", PORCELAIN = "minecraft:calcite", LIGHT = "minecraft:ochre_froglight",
            SHARD = "minecraft:gold_block";

    static final List<Part> PARTS = build();

    private static Part box(Group g, String b, double x1, double y1, double z1, double x2, double y2, double z2) {
        return new Part(g, b, (x1 + x2) / 2, (y1 + y2) / 2, (z1 + z2) / 2, x2 - x1, y2 - y1, z2 - z1, 0, 0, -1, false);
    }

    /** A box stretched from point a to point c; sx and sz are its thickness. */
    private static Part limb(Group g, String b, double[] a, double[] c, double sx, double sz, boolean bright) {
        double dx = c[0] - a[0], dy = c[1] - a[1], dz = c[2] - a[2], len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double rx = Math.toDegrees(Math.asin(dz / len)), rz = Math.toDegrees(Math.atan2(-dx, dy));
        return new Part(g, b, (a[0] + c[0]) / 2, (a[1] + c[1]) / 2, (a[2] + c[2]) / 2, sx, len, sz, rx, rz,
                bright ? GOLD : -1, bright);
    }

    private static double[] p(double x, double y, double z) { return new double[]{x, y, z}; }

    private static List<Part> build() {
        List<Part> l = new ArrayList<>();
        // the cloak: narrow at the shoulders, flaring to the hem, hovering clear of the floor
        l.add(box(Group.BODY, SHADOW, -0.8, 4.2, -0.5, 0.8, 5.5, 0.5));
        l.add(box(Group.CLOAK, CLOTH, -1.0, 3.2, -0.65, 1.0, 4.3, 0.65));
        l.add(box(Group.CLOAK, CLOTH, -1.25, 2.3, -0.85, 1.25, 3.3, 0.85));
        l.add(box(Group.CLOAK, SHADOW, -1.5, 1.6, -1.05, 1.5, 2.4, 1.05));
        // tattered strips round the hem, different lengths so the edge looks torn
        double[][] tatters = {{-1.35, 1.05, 0.15}, {-0.75, 1.1, 0.85}, {-0.1, 1.1, 0.45}, {0.55, 1.1, 1.05}, {1.2, 1.05, 0.25},
                {-1.35, -0.9, 0.55}, {-0.65, -1.1, 0.05}, {0.05, -1.1, 0.65}, {0.75, -1.1, 0.1}, {1.35, -0.9, 0.5},
                {-1.5, 0.1, 0.35}, {1.5, 0.1, 0.4}};
        for (double[] t : tatters) {
            double out = 1.28;
            l.add(limb(Group.CLOAK, CLOTH, p(t[0], 1.8, t[1]), p(t[0] * out, t[2], t[1] * out), 0.68, 0.16, false));
        }
        // a torn shawl over the shoulders, hanging in points
        for (int s : new int[]{-1, 1}) {
            l.add(limb(Group.BODY, VIOLET, p(s * 0.3, 5.5, 0.5), p(s * 1.2, 3.9, 0.82), 0.72, 0.1, false));
            l.add(limb(Group.BODY, VIOLET, p(s * 0.9, 5.5, -0.5), p(s * 1.45, 3.7, -0.85), 0.6, 0.1, false));
        }
        // the lantern-heart: an open gap in the chest with a gold light in an iron cage
        l.add(new Part(Group.HEART, LIGHT, 0, 3.75, 0.72, 0.5, 0.5, 0.2, 0, 0, GOLD, true));
        l.add(box(Group.HEART, "minecraft:iron_block", -0.33, 4.05, 0.7, 0.33, 4.13, 0.9));
        l.add(box(Group.HEART, "minecraft:iron_block", -0.33, 3.37, 0.7, 0.33, 3.45, 0.9));
        for (int s : new int[]{-1, 1}) l.add(box(Group.HEART, "minecraft:iron_block", s * 0.3 - 0.04, 3.4, 0.82, s * 0.3 + 0.04, 4.1, 0.9));
        // the tall hood, leaning forward, with a dark hollow and a point sweeping back
        l.add(box(Group.HOOD, CLOTH, -0.82, 5.4, -0.62, 0.82, 6.75, 0.62));
        l.add(box(Group.HOOD, CLOTH, -0.7, 6.7, -0.58, 0.7, 7.1, 0.5));
        l.add(limb(Group.HOOD, CLOTH, p(0, 6.95, -0.05), p(0, 7.65, -0.55), 1.0, 0.8, false));
        l.add(limb(Group.HOOD, CLOTH, p(0, 7.55, -0.45), p(0, 8.2, -1.25), 0.5, 0.4, false));
        for (int s : new int[]{-1, 1}) l.add(box(Group.HOOD, CLOTH, s * 0.72 - 0.12, 5.45, 0.55, s * 0.72 + 0.12, 6.7, 0.82));
        l.add(box(Group.HOOD, CLOTH, -0.82, 6.5, 0.55, 0.82, 6.75, 0.82));
        // the mask inside the hood: cracked, missing its lower left corner, with two thin slit eyes
        l.add(box(Group.MASK, PORCELAIN, -0.56, 5.95, 0.64, 0.56, 6.45, 0.72));
        l.add(box(Group.MASK, PORCELAIN, -0.1, 5.45, 0.64, 0.5, 5.95, 0.72));
        l.add(box(Group.MASK, SHADOW, -0.03, 5.65, 0.7, 0.03, 6.45, 0.74));
        for (int s : new int[]{-1, 1}) l.add(new Part(Group.MASK, LIGHT, s * 0.28, 6.12, 0.74, 0.28, 0.06, 0.03, 0, 0, GOLD, true));
        // long thin arms held out and down, each with four long black claws tipped in gold
        for (int s : new int[]{-1, 1}) {
            Group g = s > 0 ? Group.ARM_L : Group.ARM_R;
            l.add(limb(g, SHADOW, p(s * 0.8, 5.15, 0), p(s * 2.55, 4.4, 0.45), 0.42, 0.42, false));
            l.add(limb(g, SHADOW, p(s * 2.55, 4.4, 0.45), p(s * 3.2, 2.9, 1.3), 0.34, 0.34, false));
            l.add(limb(g, CLOTH, p(s * 1.2, 4.95, -0.2), p(s * 1.85, 3.1, -0.05), 0.5, 0.08, false));
            l.add(limb(g, CLOTH, p(s * 2.05, 4.65, 0.2), p(s * 2.55, 3.0, 0.3), 0.42, 0.08, false));
            l.add(box(g, SHADOW, s * 3.25 - 0.3, 2.45, 1.2, s * 3.25 + 0.3, 2.95, 1.75));
            for (int c = 0; c < 4; c++) {
                double fan = c - 1.5, x = s * 3.25 + fan * 0.17;
                double[] root = p(x, 2.5, 1.65), mid = p(x + fan * 0.22, 1.5, 2.15), tip = p(x + fan * 0.32, 0.75, 2.2);
                l.add(limb(g, SHADOW, root, mid, 0.13, 0.13, false));
                l.add(limb(g, SHARD, mid, tip, 0.08, 0.08, false));
            }
        }
        // the broken halo: gold shards on a ring behind the head, with a gap at the lower left
        for (int deg = -60; deg <= 240; deg += 30) {
            if (deg == 150) continue;
            double a = Math.toRadians(deg), r = 1.45, cy = 6.25;
            l.add(limb(Group.HALO, SHARD, p(Math.cos(a) * (r - 0.2), cy + Math.sin(a) * (r - 0.2), -0.95),
                    p(Math.cos(a) * (r + 0.32), cy + Math.sin(a) * (r + 0.32), -0.95), 0.13, 0.08, true));
        }
        return List.copyOf(l);
    }

    /** Height of the model's highest point, in blocks. */
    static double height() { return PARTS.stream().mapToDouble(Part::top).max().orElse(0); }

    /** Half-width of the model (furthest point sideways from the middle), in blocks. */
    static double halfWidth() { return PARTS.stream().mapToDouble(p -> Math.abs(p.cx()) + p.sx() / 2).max().orElse(0); }
}
