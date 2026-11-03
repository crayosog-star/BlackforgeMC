package net.alloymc.core.build;

import java.util.List;

/**
 * Blocky people and sparks for the trailer, built from the same textured boxes as the hub dragons. Figures are Steve-sized
 * (1.8 high) with armour plates a hair larger than the body, a sword and shield; every pose is a few joint angles.
 */
final class Figures {
    private Figures() {}

    /** What a figure wears: skin, outfit and armour blocks (armour null = cloth only). */
    record Kit(String skin, String body, String legs, String armour, String trim) {
        static final Kit NETHERITE = new Kit("minecraft:orange_terracotta", "minecraft:black_concrete", "minecraft:black_concrete", "minecraft:netherite_block", "minecraft:gold_block");
        static final Kit DIAMOND = new Kit("minecraft:terracotta", "minecraft:cyan_concrete", "minecraft:blue_concrete", "minecraft:diamond_block", "minecraft:iron_block");
        static final Kit IRON = new Kit("minecraft:brown_terracotta", "minecraft:gray_concrete", "minecraft:black_concrete", "minecraft:iron_block", "minecraft:copper_block");
        static final Kit RED = new Kit("minecraft:orange_terracotta", "minecraft:red_concrete", "minecraft:brown_concrete", null, null);
        static final Kit BLUE = new Kit("minecraft:terracotta", "minecraft:blue_concrete", "minecraft:gray_concrete", null, null);
        static final Kit GREEN = new Kit("minecraft:brown_terracotta", "minecraft:green_concrete", "minecraft:brown_concrete", null, null);
        static final Kit WHITE = new Kit("minecraft:orange_terracotta", "minecraft:white_concrete", "minecraft:light_gray_concrete", null, null);
        static final Kit ORANGE = new Kit("minecraft:terracotta", "minecraft:orange_concrete", "minecraft:gray_concrete", null, null);
        static final Kit ZOMBIE = new Kit("minecraft:green_concrete_powder", "minecraft:cyan_concrete", "minecraft:blue_concrete", null, null);
        static final Kit[] CROWD = {RED, BLUE, GREEN, WHITE, ORANGE};
    }

    /** Joint angles in degrees: arm/leg swing (+ forward), the sword arm's raise, a body lean and a fall (rotation about the feet). */
    static final class Pose {
        double legR, legL, armR, armL, lean, fall, crouch;
        boolean sword, shield, pick, hammer;
        static Pose stand() { return new Pose(); }
    }

    private static double[] add(double[] a, double[] b, double s) { return new double[]{a[0] + b[0] * s, a[1] + b[1] * s, a[2] + b[2] * s}; }

    private static double[] norm(double[] v) { double l = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); return new double[]{v[0] / l, v[1] / l, v[2] / l}; }

    /** A local frame: origin and unit right / up / forward. */
    private record Frame(double[] o, double[] r, double[] u, double[] f) {
        /** The frame rotated about its right axis by {@code deg} (positive tips the forward direction down: a swing forward of something hanging). */
        Frame pitch(double deg, double[] pivotLocal) {
            double a = Math.toRadians(deg), c = Math.cos(a), s = Math.sin(a);
            double[] u2 = {u[0] * c - f[0] * s, u[1] * c - f[1] * s, u[2] * c - f[2] * s};
            double[] f2 = {f[0] * c + u[0] * s, f[1] * c + u[1] * s, f[2] * c + u[2] * s};
            return new Frame(at(pivotLocal), r, u2, f2);
        }
        double[] at(double[] l) { return new double[]{o[0] + r[0] * l[0] + u[0] * l[1] + f[0] * l[2], o[1] + r[1] * l[0] + u[1] * l[1] + f[1] * l[2], o[2] + r[2] * l[0] + u[2] * l[1] + f[2] * l[2]}; }
        WorldCam.Box box(String block, boolean glow, double cx, double cy, double cz, double sx, double sy, double sz) {
            return new WorldCam.Box(block, glow, at(new double[]{cx, cy, cz}), r, u, f, sx, sy, sz);
        }
    }

    /** A person standing with feet at (x,y,z) facing {@code yaw} (degrees; 0 = south/+z, 90 = west/-x, 180 = north, 270 = east). */
    /** The trailer shows the real places with nobody in them: no spectators, citizens or fighters. */
    static final boolean NO_PEOPLE = true;

    static void person(List<WorldCam.Box> out, double x, double y, double z, double yaw, Kit k, Pose p) {
        if (NO_PEOPLE) return;
        double a = Math.toRadians(yaw);
        double[] f = {-Math.sin(a), 0, Math.cos(a)};
        double[] r = {-f[2], 0, f[0]};
        r = new double[]{-Math.cos(a), 0, -Math.sin(a)};   // right-hand side of someone facing f (x east, z south, y up)
        f = new double[]{-Math.sin(a), 0, Math.cos(a)};
        Frame base = new Frame(new double[]{x, y, z}, r, new double[]{0, 1, 0}, f);
        if (p.fall != 0) base = base.pitch(-p.fall, new double[]{0, 0, 0});   // tip over backwards about the feet
        double lean = p.lean, drop = p.crouch;
        Frame hip = new Frame(base.at(new double[]{0, 0.75 - drop, 0}), base.r, base.u, base.f);
        Frame body = hip.pitch(lean, new double[]{0, 0, 0});
        boolean armoured = k.armour() != null;
        double e = 0.045;
        // legs
        limb(out, base.pitch(p.legR, new double[]{0.125, 0.75 - drop, 0}), k.legs(), armoured ? k.armour() : null, 0.25, 0.75 - drop * 0.0, 0.25, e, 0.75);
        limb(out, base.pitch(p.legL, new double[]{-0.125, 0.75 - drop, 0}), k.legs(), armoured ? k.armour() : null, 0.25, 0.75, 0.25, e, 0.75);
        // torso and head
        out.add(body.box(k.body(), false, -0.25, 0, -0.125, 0.5, 0.75, 0.25));
        if (armoured) {
            out.add(body.box(k.armour(), false, -0.25 - e, 0, -0.125 - e, 0.5 + 2 * e, 0.75 + e, 0.25 + 2 * e));
            out.add(body.box(k.trim(), false, -0.06, 0.3, -0.125 - e - 0.01, 0.12, 0.3, 0.01));
        }
        Frame head = body;
        out.add(head.box(k.skin(), false, -0.25, 0.75, -0.25, 0.5, 0.5, 0.5));
        if (armoured) {
            out.add(head.box(k.armour(), false, -0.25 - e, 0.75 + 0.18, -0.25 - e, 0.5 + 2 * e, 0.32 + e, 0.5 + 2 * e));
            out.add(head.box(k.trim(), false, -0.04, 0.75 + 0.34, 0.25 + e, 0.08, 0.2, 0.02));   // crest ridge on the brow
            out.add(head.box("minecraft:black_concrete", false, -0.17, 0.75 + 0.2, 0.25 + e + 0.005, 0.34, 0.07, 0.01));   // visor slit
        } else {
            out.add(head.box("minecraft:black_concrete", false, -0.17, 0.75 + 0.2, 0.25 + 0.005, 0.1, 0.07, 0.01));
            out.add(head.box("minecraft:black_concrete", false, 0.07, 0.75 + 0.2, 0.25 + 0.005, 0.1, 0.07, 0.01));
        }
        // arms: right (sword) and left (shield)
        Frame rArm = body.pitch(p.armR, new double[]{0.375, 0.75, 0});
        arm(out, rArm, k, armoured, e);
        Frame lArm = body.pitch(p.armL, new double[]{-0.375, 0.75, 0});
        arm(out, lArm, k, armoured, e);
        if (p.sword) {
            out.add(rArm.box("minecraft:iron_block", false, -0.035, -0.8, -0.08, 0.07, 0.07, 1.0));      // blade along the arm's forward
            out.add(rArm.box("minecraft:gold_block", false, -0.18, -0.8, -0.1, 0.36, 0.07, 0.07));       // crossguard
            out.add(rArm.box("minecraft:black_concrete", false, -0.04, -0.8, -0.28, 0.08, 0.08, 0.2));   // grip
        }
        if (p.pick) {
            out.add(rArm.box("minecraft:oak_planks", false, -0.03, -0.8, -0.05, 0.06, 0.06, 0.8));
            out.add(rArm.box("minecraft:iron_block", false, -0.03, -0.9, 0.62, 0.06, 0.3, 0.14));
        }
        if (p.hammer) {
            out.add(rArm.box("minecraft:oak_planks", false, -0.03, -0.8, -0.05, 0.06, 0.06, 0.7));
            out.add(rArm.box("minecraft:iron_block", false, -0.11, -0.88, 0.58, 0.22, 0.22, 0.3));
        }
        if (p.shield) out.add(lArm.box(armoured ? k.trim() : "minecraft:oak_planks", false, -0.28, -0.85, 0.16, 0.56, 0.78, 0.07));
    }

    private static void limb(List<WorldCam.Box> out, Frame fr, String block, String armour, double w, double h, double d, double e, double top) {
        out.add(fr.box(block, false, -w / 2, -h, -d / 2, w, h, d));
        if (armour != null) out.add(fr.box(armour, false, -w / 2 - e, -h, -d / 2 - e, w + 2 * e, h * 0.55, d + 2 * e));
    }

    private static void arm(List<WorldCam.Box> out, Frame fr, Kit k, boolean armoured, double e) {
        out.add(fr.box(k.body(), false, -0.125, -0.75, -0.125, 0.25, 0.75, 0.25));
        out.add(fr.box(k.skin(), false, -0.125, -0.75, -0.125, 0.25, 0.22, 0.25));
        if (armoured) out.add(fr.box(k.armour(), false, -0.125 - e, -0.55, -0.125 - e, 0.25 + 2 * e, 0.55 + e, 0.25 + 2 * e));
    }

    /** Embers drifting up: {@code n} glowing specks around (cx,cy,cz), looping every {@code period} seconds so a loop stays clean. */
    static void embers(List<WorldCam.Box> out, double cx, double cy, double cz, double spreadXZ, double rise, int n, double t, double period, long seed) {
        java.util.Random rnd = new java.util.Random(seed);
        double[] up = {0, 1, 0}, rr = {1, 0, 0}, ff = {0, 0, 1};
        for (int i = 0; i < n; i++) {
            double ox = (rnd.nextDouble() - 0.5) * 2 * spreadXZ, oz = (rnd.nextDouble() - 0.5) * 2 * spreadXZ, ph = rnd.nextDouble(), sp = 0.6 + rnd.nextDouble() * 0.8;
            double life = ((t / period) * sp * Math.round(1 / sp * 1) + ph) % 1.0;
            life = (((t / period) + ph) % 1.0);
            double sway = Math.sin((life * 6 + i) * 1.7) * 0.8;
            double size = 0.07 + rnd.nextDouble() * 0.11;
            double yy = cy + life * rise * sp;
            double fade = life < 0.85 ? 1 : (1 - life) / 0.15;
            if (fade <= 0.05) continue;
            double s = size * fade;
            out.add(new WorldCam.Box(rnd.nextInt(4) == 0 ? "minecraft:yellow_concrete" : "minecraft:orange_concrete", true,
                    new double[]{cx + ox + sway, yy, cz + oz + Math.cos((life * 5 + i) * 1.3) * 0.8}, rr, up, ff, s, s, s));
        }
    }

    /** A burst of sparks from a point, {@code age} seconds after the strike. */
    static void sparks(List<WorldCam.Box> out, double x, double y, double z, double age, int n, long seed) {
        if (age < 0 || age > 1.0) return;
        java.util.Random rnd = new java.util.Random(seed);
        double[] up = {0, 1, 0}, rr = {1, 0, 0}, ff = {0, 0, 1};
        for (int i = 0; i < n; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, v = 2 + rnd.nextDouble() * 4, vy = 1 + rnd.nextDouble() * 3.5;
            double px = x + Math.cos(a) * v * age, pz = z + Math.sin(a) * v * age, py = y + vy * age - 4.5 * age * age;
            double s = 0.09 * (1 - age);
            out.add(new WorldCam.Box("minecraft:yellow_concrete", true, new double[]{px, py, pz}, rr, up, ff, s, s, s));
        }
    }
}
