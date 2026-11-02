package net.alloymc.core.arcade.games.bedwars;

import java.util.ArrayList;
import java.util.List;

/**
 * Solace's look as plain data: a list of boxes, each drawn in game as a block display. Local coordinates: feet at
 * y = 0, x to its left, z out of its chest (it faces +z). Plain Java (no Bukkit), so the tests and offline renders use
 * exactly what the game will spawn. Model only for now: nothing spawns it yet.
 *
 * The design is original: a colossal earth titan, walking upright and leaning forward, with crimson hide, basalt armour,
 * molten seams, bone-white claws and a heavy swept-back horned head.
 */
final class SolaceModel {
    private SolaceModel() {}

    /** Which parts will move together in an animation. */
    enum Group { LEG_L, LEG_R, BODY, HEAD, JAW, ARM_L, ARM_R, TAIL, PLATES }

    /** One box: centre, size, then rx about x and rz about z, in degrees: rotation = Rz(rz) * Rx(rx), as in GolemModel. */
    record Part(Group group, String block, double cx, double cy, double cz, double sx, double sy, double sz,
                double rx, double rz, int glow, boolean bright) {
        double top() { return cy + Math.max(sy, Math.max(sx, sz)) / 2; }
    }

    static final int MOLTEN = 0xFF7A1A;
    private static final String HIDE = "minecraft:red_nether_bricks", HIDE_DARK = "minecraft:nether_bricks",
            ARMOUR = "minecraft:polished_blackstone", ARMOUR_ROUGH = "minecraft:basalt", BONE = "minecraft:bone_block",
            GLOW = "minecraft:shroomlight", EYE = "minecraft:ochre_froglight";

    static final List<Part> PARTS = build();

    private static Part box(Group g, String b, double x1, double y1, double z1, double x2, double y2, double z2) {
        return new Part(g, b, (x1 + x2) / 2, (y1 + y2) / 2, (z1 + z2) / 2, x2 - x1, y2 - y1, z2 - z1, 0, 0, -1, false);
    }

    /** A box stretched from point a to point b; sx and sz are its thickness. Used for limbs, claws, horns and spikes. */
    private static Part limb(Group g, String b, double[] a, double[] c, double sx, double sz, boolean bright) {
        double dx = c[0] - a[0], dy = c[1] - a[1], dz = c[2] - a[2], len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double rx = Math.toDegrees(Math.asin(dz / len)), rz = Math.toDegrees(Math.atan2(-dx, dy));
        return new Part(g, b, (a[0] + c[0]) / 2, (a[1] + c[1]) / 2, (a[2] + c[2]) / 2, sx, len, sz, rx, rz,
                bright ? MOLTEN : -1, bright);
    }

    private static double[] p(double x, double y, double z) { return new double[]{x, y, z}; }

    private static List<Part> build() {
        List<Part> l = new ArrayList<>();
        // torso: pelvis to chest, leaning forward, with a pale armoured belly and a molten seam down the middle
        l.add(limb(Group.BODY, HIDE, p(0, 2.8, -0.9), p(0, 4.5, -0.3), 3.0, 2.4, false));
        l.add(limb(Group.BODY, HIDE, p(0, 4.2, -0.5), p(0, 6.1, 0.6), 3.8, 2.7, false));
        l.add(limb(Group.BODY, HIDE_DARK, p(0, 2.7, 0.35), p(0, 4.6, 0.95), 2.1, 0.3, false));
        l.add(limb(Group.BODY, HIDE_DARK, p(0, 4.5, 1.0), p(0, 5.9, 1.85), 2.8, 0.3, false));
        l.add(limb(Group.BODY, GLOW, p(0, 2.9, 0.56), p(0, 4.5, 1.08), 0.16, 0.08, true));
        l.add(limb(Group.BODY, GLOW, p(0, 4.5, 1.2), p(0, 5.8, 1.98), 0.16, 0.08, true));
        // neck and low head: skull, brow, snout, jaw, teeth, eyes, swept-back horns
        l.add(limb(Group.HEAD, HIDE, p(0, 5.7, 0.8), p(0, 6.7, 2.0), 2.0, 1.8, false));
        l.add(box(Group.HEAD, HIDE, -1.05, 6.25, 1.5, 1.05, 7.55, 3.3));
        l.add(box(Group.HEAD, ARMOUR, -1.2, 7.3, 1.35, 1.2, 7.8, 3.05));
        l.add(box(Group.HEAD, HIDE, -0.75, 6.45, 3.25, 0.75, 7.2, 4.35));
        l.add(box(Group.HEAD, ARMOUR, -0.5, 7.15, 3.1, 0.5, 7.35, 4.2));
        l.add(box(Group.JAW, HIDE_DARK, -0.85, 5.8, 1.9, 0.85, 6.3, 4.05));
        for (int s : new int[]{-1, 1}) {
            l.add(new Part(Group.HEAD, EYE, s * 0.92, 7.0, 3.34, 0.3, 0.17, 0.08, 0, 0, MOLTEN, true));
            l.add(limb(Group.HEAD, BONE, p(s * 1.05, 7.55, 1.8), p(s * 1.75, 8.2, 0.35), 0.42, 0.42, false));
            l.add(limb(Group.HEAD, BONE, p(s * 1.75, 8.2, 0.35), p(s * 2.0, 8.95, -0.65), 0.24, 0.24, false));
            for (double z : new double[]{3.45, 4.1}) {
                l.add(box(Group.HEAD, BONE, s * 0.56 - 0.09, 6.2, z - 0.09, s * 0.56 + 0.09, 6.47, z + 0.09));
                l.add(box(Group.JAW, BONE, s * 0.56 - 0.08, 6.28, z - 0.32, s * 0.56 + 0.08, 6.5, z - 0.16));
            }
        }
        // legs: thick thigh, shin, armoured knee, wide foot, three toe claws
        for (int s : new int[]{-1, 1}) {
            Group g = s > 0 ? Group.LEG_L : Group.LEG_R;
            l.add(limb(g, HIDE, p(s * 1.35, 3.2, -0.8), p(s * 1.85, 1.55, 0.05), 1.7, 1.9, false));
            l.add(limb(g, HIDE_DARK, p(s * 1.85, 1.7, 0.05), p(s * 1.9, 0.4, -0.3), 1.35, 1.45, false));
            l.add(box(g, ARMOUR, s * 1.85 - 0.6, 1.35, 0.55, s * 1.85 + 0.6, 2.1, 0.95));
            l.add(limb(g, GLOW, p(s * 1.1, 3.35, -0.1), p(s * 1.6, 2.1, 0.74), 0.1, 0.08, true));
            l.add(box(g, ARMOUR_ROUGH, s * 1.9 - 0.95, 0, -0.75, s * 1.9 + 0.95, 0.5, 1.3));
            for (int t = -1; t <= 1; t++) {
                double x = s * 1.9 + t * 0.62;
                l.add(limb(g, BONE, p(x, 0.3, 1.2), p(x + t * 0.08, 0.05, 2.05), 0.34, 0.3, false));
            }
        }
        // arms: huge pauldron, upper arm, forearm guard, big hand, four long two-part claws
        for (int s : new int[]{-1, 1}) {
            Group g = s > 0 ? Group.ARM_L : Group.ARM_R;
            l.add(limb(g, ARMOUR, p(s * 1.9, 6.15, 0.25), p(s * 3.35, 5.45, 0.45), 1.05, 2.15, false));
            l.add(limb(g, HIDE, p(s * 2.55, 5.5, 0.35), p(s * 3.25, 3.75, 0.75), 1.4, 1.5, false));
            l.add(limb(g, HIDE, p(s * 3.25, 3.9, 0.7), p(s * 3.45, 2.45, 1.95), 1.45, 1.45, false));
            l.add(limb(g, ARMOUR, p(s * 3.95, 3.75, 0.55), p(s * 4.1, 2.55, 1.6), 0.25, 1.05, false));
            l.add(limb(g, GLOW, p(s * 4.08, 3.65, 0.6), p(s * 4.23, 2.65, 1.48), 0.05, 0.14, true));
            l.add(limb(g, HIDE_DARK, p(s * 3.45, 2.65, 1.8), p(s * 3.55, 1.85, 2.7), 1.7, 1.3, false));
            for (int c = 0; c < 4; c++) {
                double x = s * (2.95 + c * 0.42), spread = s * (c - 1.5) * 0.14;
                double[] root = p(x, 1.8, 3.0), knuckle = p(x + spread, 1.2, 3.65), tip = p(x + spread * 1.4, 0.3, 3.95);
                l.add(limb(g, BONE, root, knuckle, 0.34, 0.38, false));
                l.add(limb(g, BONE, knuckle, tip, 0.2, 0.22, false));
            }
        }
        // tail: tapering segments sweeping down and back
        double[][] tail = {{3.2, -1.3, 2.3, 2.1}, {2.45, -3.0, 1.85, 1.75}, {1.65, -4.65, 1.4, 1.3}, {0.95, -6.15, 0.95, 0.9}, {0.5, -7.45, 0.55, 0.55}, {0.3, -8.5, 0.25, 0.25}};
        for (int i = 0; i + 1 < tail.length; i++)
            l.add(limb(Group.TAIL, i % 2 == 0 ? HIDE : HIDE_DARK, p(0, tail[i][0], tail[i][1]), p(0, tail[i + 1][0], tail[i + 1][1]), tail[i][2], tail[i][3], false));
        // basalt plates down the spine and tail, largest over the shoulders
        double[][] plates = {{6.6, -0.55, 1.45, 0.8}, {5.85, -1.15, 1.75, 1.0}, {5.0, -1.55, 1.5, 0.9}, {4.1, -1.95, 1.2, 0.8},
                {3.25, -2.55, 1.1, 0.7}, {2.45, -3.85, 0.95, 0.6}, {1.75, -5.3, 0.75, 0.5}, {1.1, -6.7, 0.5, 0.35}};
        for (double[] v : plates) {
            Group g = v[1] < -2.2 ? Group.TAIL : Group.PLATES;
            l.add(limb(g, ARMOUR_ROUGH, p(0, v[0], v[1]), p(0, v[0] + v[2] * 0.72, v[1] - v[2] * 0.7), 0.22, v[3], false));
        }
        return List.copyOf(l);
    }

    /** Height of the model's highest point, in blocks. */
    static double height() { return PARTS.stream().mapToDouble(Part::top).max().orElse(0); }

    /** Half-width of the model (furthest point sideways from the middle), in blocks. */
    static double halfWidth() { return PARTS.stream().mapToDouble(p -> Math.abs(p.cx()) + p.sx() / 2).max().orElse(0); }
}
