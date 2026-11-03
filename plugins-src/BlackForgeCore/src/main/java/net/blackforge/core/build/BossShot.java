package net.blackforge.core.build;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * A stand-in picture of the five world bosses standing in the KOTH arena, built from the same textured boxes as the trailer people.
 * The real bosses are scaled vanilla mobs with their own attacks; this only shows their size, silhouette and colours.
 *
 * gradle bosses -Pjar=&lt;client jar&gt; [-Pdir=out]
 */
public final class BossShot {
    private static final double[] R = {1, 0, 0}, U = {0, 1, 0}, F = {0, 0, 1};

    /** A box in the boss's own frame (feet at the origin, x right, y up, z forward), scaled and turned to face {@code yaw}. */
    private static void box(List<WorldCam.Box> out, double ox, double oy, double oz, double yaw, double k, String block, boolean glow, double cx, double cy, double cz, double sx, double sy, double sz) {
        double a = Math.toRadians(yaw);
        double[] f = {-Math.sin(a), 0, Math.cos(a)}, r = {-Math.cos(a), 0, -Math.sin(a)};
        double[] c = {ox + (r[0] * cx + f[0] * cz) * k, oy + cy * k, oz + (r[2] * cx + f[2] * cz) * k};
        out.add(new WorldCam.Box("minecraft:" + block, glow, c, r, U, f, sx * k, sy * k, sz * k));
    }

    static void skeleton(List<WorldCam.Box> o, double x, double y, double z, double yaw, double k, String bone, String dark, String weapon) {
        for (double s : new double[]{-1, 1}) {
            box(o, x, y, z, yaw, k, bone, false, s * 0.125 - 0.0625, 0, -0.0625, 0.125, 0.75, 0.125);   // thin legs
            box(o, x, y, z, yaw, k, bone, false, s * 0.375 - 0.0625, 0.75, -0.0625, 0.125, 0.75, 0.125);   // arms
        }
        box(o, x, y, z, yaw, k, bone, false, -0.25, 0.75, -0.0625, 0.5, 0.75, 0.125);                      // ribs
        box(o, x, y, z, yaw, k, dark, false, -0.2, 0.9, -0.07, 0.4, 0.08, 0.14);
        box(o, x, y, z, yaw, k, dark, false, -0.2, 1.1, -0.07, 0.4, 0.08, 0.14);
        box(o, x, y, z, yaw, k, bone, false, -0.25, 1.5, -0.25, 0.5, 0.5, 0.5);                            // skull
        box(o, x, y, z, yaw, k, "black_concrete", false, -0.19, 1.68, 0.25, 0.12, 0.1, 0.02);
        box(o, x, y, z, yaw, k, "black_concrete", false, 0.07, 1.68, 0.25, 0.12, 0.1, 0.02);
        box(o, x, y, z, yaw, k, "shroomlight", true, -0.16, 1.7, 0.255, 0.06, 0.05, 0.01);
        box(o, x, y, z, yaw, k, "shroomlight", true, 0.1, 1.7, 0.255, 0.06, 0.05, 0.01);
        if (weapon != null) box(o, x, y, z, yaw, k, weapon, false, -0.45, 1.25, 0.25, 0.08, 0.08, 0.9);
    }

    static void titan(List<WorldCam.Box> o, double x, double y, double z, double yaw, double k) {
        for (double s : new double[]{-1, 1}) {
            box(o, x, y, z, yaw, k, "packed_ice", false, s * 0.32 - 0.16, 0, -0.16, 0.32, 0.7, 0.32);       // legs
            box(o, x, y, z, yaw, k, "blue_ice", false, s * 0.78 - 0.18, 0.2, -0.18, 0.36, 1.3, 0.36);     // long arms
        }
        box(o, x, y, z, yaw, k, "iron_block", false, -0.62, 0.7, -0.38, 1.24, 0.95, 0.76);                 // chest
        box(o, x, y, z, yaw, k, "blue_ice", false, -0.46, 0.6, -0.4, 0.92, 0.2, 0.8);
        box(o, x, y, z, yaw, k, "snow_block", false, -0.7, 1.55, -0.4, 1.4, 0.25, 0.8);                     // shoulders
        box(o, x, y, z, yaw, k, "iron_block", false, -0.2, 1.65, -0.22, 0.4, 0.4, 0.4);                      // head
        box(o, x, y, z, yaw, k, "sea_lantern", true, -0.12, 1.8, 0.18, 0.08, 0.06, 0.02);
        box(o, x, y, z, yaw, k, "sea_lantern", true, 0.04, 1.8, 0.18, 0.08, 0.06, 0.02);
    }

    static void ravager(List<WorldCam.Box> o, double x, double y, double z, double yaw, double k) {
        for (double s : new double[]{-1, 1}) for (double zz : new double[]{-0.55, 0.45}) box(o, x, y, z, yaw, k, "nether_bricks", false, s * 0.42 - 0.17, 0, zz - 0.17, 0.34, 0.75, 0.34);
        box(o, x, y, z, yaw, k, "netherrack", false, -0.55, 0.7, -0.9, 1.1, 0.95, 1.8);                    // body
        box(o, x, y, z, yaw, k, "magma_block", true, -0.4, 1.6, -0.6, 0.8, 0.12, 1.0);                      // glowing back
        box(o, x, y, z, yaw, k, "nether_bricks", false, -0.4, 1.05, 0.85, 0.8, 0.7, 0.7);                   // head
        box(o, x, y, z, yaw, k, "red_nether_bricks", false, -0.3, 0.8, 1.35, 0.6, 0.35, 0.45);              // snout
        for (double s : new double[]{-1, 1}) {
            box(o, x, y, z, yaw, k, "bone_block", false, s * 0.38 - 0.06, 1.7, 1.2, 0.12, 0.45, 0.12);        // horns
            box(o, x, y, z, yaw, k, "shroomlight", true, s * 0.22 - 0.05, 1.5, 1.56, 0.1, 0.08, 0.02);
        }
    }

    static void brute(List<WorldCam.Box> o, double x, double y, double z, double yaw, double k) {
        for (double s : new double[]{-1, 1}) {
            box(o, x, y, z, yaw, k, "black_concrete", false, s * 0.14 - 0.12, 0, -0.12, 0.24, 0.75, 0.24);
            box(o, x, y, z, yaw, k, "gold_block", false, s * 0.14 - 0.14, 0, -0.14, 0.28, 0.35, 0.28);
            box(o, x, y, z, yaw, k, "pink_terracotta", false, s * 0.42 - 0.13, 0.7, -0.13, 0.26, 0.8, 0.26);
        }
        box(o, x, y, z, yaw, k, "gold_block", false, -0.3, 0.75, -0.17, 0.6, 0.8, 0.34);
        box(o, x, y, z, yaw, k, "black_concrete", false, -0.3, 0.95, -0.18, 0.6, 0.12, 0.36);
        box(o, x, y, z, yaw, k, "pink_terracotta", false, -0.28, 1.55, -0.28, 0.56, 0.5, 0.56);
        box(o, x, y, z, yaw, k, "pink_terracotta", false, -0.17, 1.6, 0.26, 0.34, 0.2, 0.15);             // snout
        for (double s : new double[]{-1, 1}) box(o, x, y, z, yaw, k, "pink_terracotta", false, s * 0.45 - 0.05, 1.9, -0.05, 0.1, 0.25, 0.1);
        box(o, x, y, z, yaw, k, "shroomlight", true, -0.18, 1.85, 0.28, 0.1, 0.05, 0.02);
        box(o, x, y, z, yaw, k, "shroomlight", true, 0.08, 1.85, 0.28, 0.1, 0.05, 0.02);
        box(o, x, y, z, yaw, k, "oak_planks", false, -0.62, 0.3, 0.1, 0.07, 0.07, 1.2);                     // axe haft
        box(o, x, y, z, yaw, k, "gold_block", false, -0.72, 0.2, 1.0, 0.3, 0.5, 0.12);                      // axe head
    }

    public static void main(String[] a) throws Exception {
        File jar = new File(a[0]);
        File dir = new File(a.length > 1 ? a[1] : "previews");
        dir.mkdirs();
        WorldCam wc = new WorldCam(jar);
        WorldCam.World w = wc.load(new File(Shots.NET, "survival/world/dimensions/minecraft/overworld/region"), -80, -500, 80, -340);
        int gy = Premiere.groundY(w, 0, -392, 150) + 1;
        System.out.println("arena floor at " + gy);
        List<WorldCam.Box> boxes = new ArrayList<>();
        double z = -402;
        titan(boxes, -13.4, gy, z, 0, 2.6);
        skeleton(boxes, -6.8, gy, z, 0, 3.0, "bone_block", "white_concrete", "bow");
        ravager(boxes, 0, gy, z, 0, 2.4);
        skeleton(boxes, 6.8, gy, z, 0, 2.5, "polished_blackstone", "blackstone", "netherite_block");
        brute(boxes, 13.4, gy, z, 0, 2.4);
        // a player for scale
        Figures.person(boxes, 3.4, gy, z + 6, 180, Figures.Kit.NETHERITE, sword());
        WorldCam.Look look = Premiere.arenaLook();
        BufferedImage img = wc.render(w, look, new WorldCam.Cam(0.5, gy + 3.4, z + 15.5, 0.5, gy + 3.6, z, 74), boxes, 1920, 900, 2);
        ImageIO.write(img, "png", new File(dir, "bosses_lineup.png"));
        System.out.println("wrote " + new File(dir, "bosses_lineup.png"));
    }

    private static Figures.Pose sword() { Figures.Pose p = new Figures.Pose(); p.sword = true; p.armR = 40; return p; }
}
