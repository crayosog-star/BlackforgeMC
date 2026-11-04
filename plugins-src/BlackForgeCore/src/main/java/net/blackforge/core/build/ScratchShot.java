package net.blackforge.core.build;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * One-off render of the Night Thieves (PR #107) on a Survival night: two players ambushed by a gang of three.
 * A thief in a copy of the diamond player's gear swings at him, a robed mage gathers a Hex Bolt behind, and a thief in
 * iron runs off with a stolen diamond floating over his head while the second player chases.
 * Box stand-ins only: the real thieves are a vanilla zombie and illusioner wearing the players' real items.
 *
 * Not committed to main: delete this and its gradle task once the picture is agreed.
 * gradle shot -Pjar=&lt;26.3 client jar&gt; [-Pdir=previews/scratch] [-Pw=640 -Ph=360 -Pss=1]
 */
public final class ScratchShot {
    private static final double[] UP = {0, 1, 0};

    public static void main(String[] a) throws Exception {
        File jar = new File(a[0]);
        File dir = new File(a.length > 1 && !a[1].isEmpty() ? a[1] : "previews/scratch");
        int w = a.length > 2 ? Integer.parseInt(a[2]) : 1920, h = a.length > 3 ? Integer.parseInt(a[3]) : 1080, ss = a.length > 4 ? Integer.parseInt(a[4]) : 2;
        dir.mkdirs();

        // open wilderness a little east of spawn, inside what the survival_top shot already loads
        double cx = 150.5, cz = 60.5;
        WorldCam wc = new WorldCam(jar);
        WorldCam.World world = wc.load(new File(Shots.NET, "survival/world/dimensions/minecraft/overworld/region"),
                (int) cx - 64, (int) cz - 64, (int) cx + 64, (int) cz + 64);
        int gy = Premiere.groundY(world, cx, cz, 70) + 1;
        System.out.println("ground at " + gy);

        List<WorldCam.Box> b = new ArrayList<>();
        // the diamond player, sword up, facing the thief that copied his kit
        double[] pA = {cx + 0.4, cz - 0.6};
        Body.person(b, pA[0], ground(world, pA, gy), pA[1], 90, Body.PLAYER_DIAMOND, pose(0, 0, 120, 20, 0, Body.SWORD_DIAMOND));
        // the melee thief: zombie, same diamond gear, his own copy of the sword, arms out
        double[] tA = {cx - 1.5, cz - 0.9};
        Body.person(b, tA[0], ground(world, tA, gy), tA[1], 270, Body.THIEF_DIAMOND, pose(-15, 15, 95, 80, 4, Body.SWORD_DIAMOND));
        // the mage, back and to the left, gathering a Hex Bolt at the diamond player
        double[] mg = {cx - 5.0, cz - 4.2};
        double mgy = ground(world, mg, gy), my = yawTo(mg, pA);
        Body.mage(b, mg[0], mgy, mg[1], my);
        hexCharge(b, mg[0], mgy, mg[1], my, 0.75);
        // the iron thief running off with a stolen diamond over his head, and the iron player chasing
        double[] tB = {cx + 5.2, cz - 5.0}, pB = {cx + 2.6, cz - 2.2};
        double tBy = ground(world, tB, gy);
        Body.person(b, tB[0], tBy, tB[1], 215, Body.THIEF_IRON, pose(45, -40, -35, 40, 14, null));
        loot(b, tB[0], tBy + 2.45, tB[1]);
        smoke(b, tB[0] - 0.6, tBy, tB[1] + 0.9);
        Body.person(b, pB[0], ground(world, pB, gy), pB[1], yawTo(pB, tB), Body.PLAYER_IRON, pose(-35, 30, 70, -30, 10, Body.SWORD_IRON));

        WorldCam.Look look = survivalNight();
        BufferedImage img = wc.render(world, look, new WorldCam.Cam(cx + 1.5, gy + 2.4, cz + 8.0, cx - 0.2, gy + 1.2, cz - 2.2, 68), b, w, h, ss);
        File out = new File(dir, "night_thieves.png");
        ImageIO.write(img, "png", out);
        System.out.println("wrote " + out.getAbsolutePath());
    }

    /** Survival at night: dark blue, moonlit, light enough to read gear by. */
    static WorldCam.Look survivalNight() {
        WorldCam.Look l = WorldCam.Look.frozenNight();
        l.sunX = -0.35; l.sunY = 0.62; l.sunZ = 0.55; l.daylight = 0.42;
        l.skyTop = 0x050816; l.skyHorizon = 0x1B2747; l.fogColor = 0x141D36; l.sunColor = 0xC9D6FF;
        l.fogStart = 60; l.fogEnd = 160; l.maxDist = 170;
        return l;
    }

    private static double ground(WorldCam.World w, double[] p, int fallback) { return Premiere.groundY(w, p[0], p[1], fallback - 1) + 1; }

    /** Yaw that faces from {@code from} to {@code to} (0 = south/+z, 90 = west, 270 = east). */
    private static double yawTo(double[] from, double[] to) { return Math.toDegrees(Math.atan2(-(to[0] - from[0]), to[1] - from[1])); }

    private static Body.Pose pose(double legR, double legL, double armR, double armL, double lean, String[] sword) {
        Body.Pose p = new Body.Pose();
        p.legR = legR; p.legL = legL; p.armR = armR; p.armL = armL; p.lean = lean; p.sword = sword;
        return p;
    }

    private static void cube(List<WorldCam.Box> o, String block, boolean glow, double x, double y, double z, double s) {
        o.add(new WorldCam.Box("minecraft:" + block, glow, new double[]{x - s / 2, y - s / 2, z - s / 2}, new double[]{1, 0, 0}, UP, new double[]{0, 0, 1}, s, s, s));
    }

    /** The Hex Bolt three quarters charged: a purple core in front of the mage's hand with specks spiralling in. */
    private static void hexCharge(List<WorldCam.Box> o, double x, double y, double z, double yaw, double c) {
        double r = Math.toRadians(yaw);
        double fx = -Math.sin(r), fz = Math.cos(r), rx = -Math.cos(r), rz = -Math.sin(r);
        double hx = x + fx * 1.2 + rx * 0.36, hy = y + 1.25, hz = z + fz * 1.2 + rz * 0.36;
        cube(o, "amethyst_block", true, hx, hy, hz, 0.14 + 0.32 * c);
        cube(o, "purple_concrete", true, hx, hy, hz, 0.08 + 0.18 * c);
        Random rnd = new Random(7);
        double rad = 1.3 * (1 - c * c * c) + 0.2;
        for (int i = 0; i < 22; i++) {
            double t = rnd.nextDouble() * Math.PI * 2, yy = rnd.nextDouble() * 2 - 1, k = Math.sqrt(1 - yy * yy), d = rad * (0.5 + rnd.nextDouble() * 0.7);
            cube(o, rnd.nextInt(3) == 0 ? "magenta_concrete" : "purple_concrete", true, hx + Math.cos(t) * k * d, hy + yy * d, hz + Math.sin(t) * k * d, 0.05 + rnd.nextDouble() * 0.05);
        }
    }

    /** The stolen item, floating and glowing over the thief's head (the real one is an item display of what he took). */
    private static void loot(List<WorldCam.Box> o, double x, double y, double z) {
        cube(o, "diamond_block", true, x, y, z, 0.32);
        cube(o, "white_concrete", true, x, y + 0.24, z, 0.06);
    }

    private static void smoke(List<WorldCam.Box> o, double x, double y, double z) {
        Random rnd = new Random(3);
        for (int i = 0; i < 7; i++)
            cube(o, rnd.nextBoolean() ? "gray_concrete" : "light_gray_concrete", false, x + rnd.nextGaussian() * 0.3, y + 0.15 + rnd.nextDouble() * 0.3, z + rnd.nextGaussian() * 0.3, 0.1 + rnd.nextDouble() * 0.12);
    }

    /** Blocky humanoids posed by joint angles, like the trailer's Figures, but drawn regardless of Figures.NO_PEOPLE. */
    static final class Body {
        record Kit(String skin, String body, String legs, String armour, String trim, String eyes, boolean glowEyes) {}

        static final Kit PLAYER_DIAMOND = new Kit("orange_terracotta", "cyan_concrete", "blue_concrete", "diamond_block", "light_blue_concrete", "black_concrete", false);
        static final Kit PLAYER_IRON = new Kit("brown_terracotta", "red_concrete", "brown_concrete", "iron_block", "light_gray_concrete", "black_concrete", false);
        static final Kit THIEF_DIAMOND = new Kit("green_terracotta", "black_concrete", "gray_concrete", "diamond_block", "light_blue_concrete", "red_concrete", true);
        static final Kit THIEF_IRON = new Kit("green_terracotta", "black_concrete", "gray_concrete", "iron_block", "light_gray_concrete", "red_concrete", true);
        static final String[] SWORD_DIAMOND = {"diamond_block", "light_blue_concrete"}, SWORD_IRON = {"iron_block", "gray_concrete"};

        static final class Pose { double legR, legL, armR, armL, lean; String[] sword; }

        private record Frame(double[] o, double[] r, double[] u, double[] f) {
            Frame pitch(double deg, double[] pivot) {
                double a = Math.toRadians(deg), c = Math.cos(a), s = Math.sin(a);
                return new Frame(at(pivot), r,
                        new double[]{u[0] * c - f[0] * s, u[1] * c - f[1] * s, u[2] * c - f[2] * s},
                        new double[]{f[0] * c + u[0] * s, f[1] * c + u[1] * s, f[2] * c + u[2] * s});
            }
            double[] at(double[] l) { return new double[]{o[0] + r[0] * l[0] + u[0] * l[1] + f[0] * l[2], o[1] + r[1] * l[0] + u[1] * l[1] + f[1] * l[2], o[2] + r[2] * l[0] + u[2] * l[1] + f[2] * l[2]}; }
            void box(List<WorldCam.Box> out, String block, boolean glow, double cx, double cy, double cz, double sx, double sy, double sz) {
                if (block == null) return;
                out.add(new WorldCam.Box("minecraft:" + block, glow, at(new double[]{cx, cy, cz}), r, u, f, sx, sy, sz));
            }
        }

        private static Frame base(double x, double y, double z, double yaw) {
            double a = Math.toRadians(yaw);
            return new Frame(new double[]{x, y, z}, new double[]{-Math.cos(a), 0, -Math.sin(a)}, UP, new double[]{-Math.sin(a), 0, Math.cos(a)});
        }

        static void person(List<WorldCam.Box> out, double x, double y, double z, double yaw, Kit k, Pose p) {
            Frame base = base(x, y, z, yaw);
            Frame body = new Frame(base.at(new double[]{0, 0.75, 0}), base.r, base.u, base.f).pitch(p.lean, new double[]{0, 0, 0});
            boolean arm = k.armour != null;
            double e = 0.045;
            for (int s = -1; s <= 1; s += 2) {
                Frame leg = base.pitch(s > 0 ? p.legR : p.legL, new double[]{s * 0.125, 0.75, 0});
                leg.box(out, k.legs, false, -0.125, -0.75, -0.125, 0.25, 0.75, 0.25);
                if (arm) leg.box(out, k.armour, false, -0.125 - e, -0.42, -0.125 - e, 0.25 + 2 * e, 0.42, 0.25 + 2 * e);
                if (arm) leg.box(out, k.armour, false, -0.125 - e, -0.75 - 0.01, -0.125 - e, 0.25 + 2 * e, 0.2, 0.25 + 2 * e + 0.04);   // boots
            }
            body.box(out, k.body, false, -0.25, 0, -0.125, 0.5, 0.75, 0.25);
            if (arm) {
                body.box(out, k.armour, false, -0.25 - e, 0.02, -0.125 - e, 0.5 + 2 * e, 0.73 + e, 0.25 + 2 * e);
                body.box(out, k.trim, false, -0.06, 0.3, 0.125 + e, 0.12, 0.3, 0.01);
            }
            body.box(out, k.skin, false, -0.25, 0.75, -0.25, 0.5, 0.5, 0.5);
            if (arm) body.box(out, k.armour, false, -0.25 - e, 0.75 + 0.3, -0.25 - e, 0.5 + 2 * e, 0.22 + e, 0.5 + 2 * e);   // helmet
            body.box(out, k.eyes, k.glowEyes, -0.17, 0.75 + 0.2, 0.25 + 0.005, 0.1, 0.07, 0.01);
            body.box(out, k.eyes, k.glowEyes, 0.07, 0.75 + 0.2, 0.25 + 0.005, 0.1, 0.07, 0.01);
            for (int s = -1; s <= 1; s += 2) {
                Frame am = body.pitch(s > 0 ? p.armR : p.armL, new double[]{s * 0.375, 0.75, 0});
                am.box(out, k.body, false, -0.125, -0.75, -0.125, 0.25, 0.75, 0.25);
                am.box(out, k.skin, false, -0.125, -0.75, -0.125, 0.25, 0.22, 0.25);
                if (arm) am.box(out, k.armour, false, -0.125 - e, -0.32, -0.125 - e, 0.25 + 2 * e, 0.32 + e, 0.25 + 2 * e);
                if (s > 0 && p.sword != null) {
                    am.box(out, p.sword[0], false, -0.035, -0.72, -0.08, 0.07, 0.07, 1.0);          // blade
                    am.box(out, p.sword[1], false, -0.16, -0.72, -0.1, 0.32, 0.07, 0.07);           // guard
                    am.box(out, "dark_oak_planks", false, -0.04, -0.72, -0.26, 0.08, 0.08, 0.18);   // grip
                }
            }
        }

        /** The mage (an illusioner in game): long blue robe to the ground, grey skin, big nose, shard held out. */
        static void mage(List<WorldCam.Box> out, double x, double y, double z, double yaw) {
            Frame base = base(x, y, z, yaw);
            base.box(out, "blue_terracotta", false, -0.27, 0, -0.16, 0.54, 1.45, 0.32);          // robe
            base.box(out, "cyan_terracotta", false, -0.29, 0.0, -0.18, 0.58, 0.14, 0.36);         // hem
            base.box(out, "light_gray_concrete", false, -0.05, 0.4, 0.16, 0.1, 1.0, 0.01);        // robe seam
            base.box(out, "light_gray_terracotta", false, -0.25, 1.45, -0.25, 0.5, 0.55, 0.5);    // head
            base.box(out, "light_gray_terracotta", false, -0.06, 1.48, 0.25, 0.12, 0.24, 0.1);    // nose
            base.box(out, "black_concrete", false, -0.26, 1.82, -0.26, 0.52, 0.18, 0.52);         // hair
            base.box(out, "light_blue_concrete", true, -0.17, 1.72, 0.255, 0.09, 0.06, 0.01);     // eyes
            base.box(out, "light_blue_concrete", true, 0.08, 1.72, 0.255, 0.09, 0.06, 0.01);
            Frame cast = base.pitch(80, new double[]{0.36, 1.4, 0});                              // right arm reaching out
            cast.box(out, "blue_terracotta", false, -0.11, -0.65, -0.11, 0.22, 0.65, 0.22);
            cast.box(out, "light_gray_terracotta", false, -0.1, -0.78, -0.1, 0.2, 0.15, 0.2);
            cast.box(out, "amethyst_block", true, -0.04, -0.95, -0.03, 0.08, 0.2, 0.06);          // the shard
            Frame off = base.pitch(-15, new double[]{-0.36, 1.4, 0});
            off.box(out, "blue_terracotta", false, -0.11, -0.65, -0.11, 0.22, 0.65, 0.22);
        }
    }
}
