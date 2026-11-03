package net.blackforge.core.build;

import net.blackforge.core.build.Figures.Kit;
import net.blackforge.core.build.Figures.Pose;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.BiConsumer;

/**
 * The BlackForgeMC premiere trailer, rendered from the live worlds with {@link WorldCam}: the saved region files of the real
 * Survival, Hub and Wilds worlds, with blocky people (see {@link Figures}) placed in them. Three cuts come from one shot library:
 * the film (about 95 s, 1920x1080), a seamless 20 s hero loop for the website and a 30 s vertical cut.
 *
 * gradle premiere -Pjar=&lt;client jar&gt; -Pproduct=main|hero|short [-Pfrom=0 -Pto=99999 -Pevery=1 -Pss=2]
 * Frames go to previews/premiere/&lt;product&gt;/ ; tools/make_premiere.py scores and encodes them.
 */
public final class Premiere {
    static final int FPS = 30;
    static final File NET = Shots.NET;

    // ------------------------------------------------------------------ shots

    interface Path { double[] at(WorldCam.World w, double t); }
    interface Extras { void add(List<WorldCam.Box> out, WorldCam.World w, double t, double sim); }

    record Shot(String id, String server, String dim, int cx, int cz, int radius, WorldCam.Look look, Path path, Extras fx, BiConsumer<WorldCam, WorldCam.World> prep) {
        String key() { return server + "/" + dim + "/" + cx + "," + cz + "," + radius; }
    }

    static double[] keys(double[][] k, double t) { return Trailer.along(k, Math.max(0, Math.min(1, t))); }

    static double[] cam(double x, double y, double z, double lx, double ly, double lz) { return new double[]{x, y, z, lx, ly, lz}; }

    static WorldCam.Look dusk() {
        WorldCam.Look l = WorldCam.Look.sunset();
        l.fogStart = 260; l.fogEnd = 600; l.maxDist = 620;
        return l;
    }

    static WorldCam.Look forgeLight() {
        WorldCam.Look l = WorldCam.Look.inferno();
        l.daylight = 0.28;
        return l;
    }

    static WorldCam.Look arenaLook() {
        WorldCam.Look l = WorldCam.Look.sunset();
        l.sunX = -0.6; l.sunY = 0.34; l.sunZ = 0.5; l.daylight = 0.95;
        l.fogStart = 300; l.fogEnd = 640; l.maxDist = 640;
        return l;
    }

    static int groundY(WorldCam.World w, double x, double z, int fallback) {
        int t = w.top((int) Math.floor(x), (int) Math.floor(z));
        return t == Integer.MIN_VALUE ? fallback : t;
    }

    // ---- who stands where

    /** Walkers on the spawn plaza and the market street. */
    static void citizens(List<WorldCam.Box> out, double sim, double cx, double cz, double y, int n, double radius, long seed) {
        Random rnd = new Random(seed);
        for (int i = 0; i < n; i++) {
            double a0 = rnd.nextDouble() * Math.PI * 2, r = radius * (0.45 + 0.55 * rnd.nextDouble()), sp = (0.12 + rnd.nextDouble() * 0.1) * (rnd.nextBoolean() ? 1 : -1);
            double a = a0 + sim * sp / Math.max(4, r) * 4;
            double x = cx + Math.cos(a) * r, z = cz + Math.sin(a) * r;
            double dir = sp > 0 ? a + Math.PI / 2 : a - Math.PI / 2;
            double yaw = Math.toDegrees(Math.atan2(-(Math.cos(dir)), -(Math.sin(dir)))) ;
            yaw = Math.toDegrees(Math.atan2(-Math.cos(dir), Math.sin(dir)) ) + 0;
            Pose p = new Pose();
            double w = Math.sin(sim * 6 + i) * 28;
            p.legR = w; p.legL = -w; p.armR = -w * 0.8; p.armL = w * 0.8;
            Kit k = Kit.CROWD[rnd.nextInt(Kit.CROWD.length)];
            Figures.person(out, x, y, z, yawToward(x, z, x + Math.cos(dir), z + Math.sin(dir)), k, p);
        }
    }

    static double yawToward(double x, double z, double tx, double tz) {
        double dx = tx - x, dz = tz - z;
        return Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** A cheering group facing a point. */
    static void gathering(List<WorldCam.Box> out, double sim, double cx, double cz, double y, int n, double radius, long seed) {
        Random rnd = new Random(seed);
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2 / n + rnd.nextDouble() * 0.4, r = radius * (0.7 + 0.5 * rnd.nextDouble());
            double x = cx + Math.cos(a) * r, z = cz + Math.sin(a) * r;
            Pose p = new Pose();
            double bob = Math.max(0, Math.sin(sim * (5 + rnd.nextDouble() * 2) + i * 1.9));
            boolean wave = i % 3 == 0;
            p.armR = wave ? 150 + bob * 25 : 12; p.armL = wave && i % 2 == 0 ? 160 - bob * 20 : 8;
            p.crouch = bob * 0.06;
            Figures.person(out, x, y + bob * 0.12, z, yawToward(x, z, cx, cz), Kit.CROWD[(i + (int) seed) % Kit.CROWD.length], p);
        }
    }

    // ---- the duel

    static final double DUEL_X = 0.5, DUEL_Z = 300.5;

    /** Terrain height under the Wilds fly-over, averaged over a wide patch and a stretch of the path, so the camera glides instead of bobbing over every tree. */
    static double airY(WorldCam.World w, double t, double ox, double oz) {
        double sum = 0; int n = 0;
        for (int ti = -10; ti <= 10; ti++) {
            double tt = Math.max(0, Math.min(1, t + ti * 0.01));
            double x = WX - 110 + 220 * tt + ox, z = WZ - 70 + 60 * Math.sin(tt * 2.4) + oz;
            for (int gx = -1; gx <= 1; gx++) for (int gz = -1; gz <= 1; gz++) { sum += groundY(w, x + gx * 16, z + gz * 16, 70); n++; }
        }
        return sum / n;
    }

    /** Both fighters at {@code t} seconds into the duel script; also the spark times. */
    static void duel(List<WorldCam.Box> out, double fy, double t) {
        if (Figures.NO_PEOPLE) return;
        double[] ap = new double[3], bp = new double[3];
        Pose a = new Pose(), b = new Pose();
        a.sword = a.shield = b.sword = b.shield = true;
        double ax = -12, bx = 13;
        if (t < 3.2) {
            double u = t / 3.2, w = Math.sin(t * 7.5) * 35;
            ax = -12 + 8.6 * u; bx = 13 - 8.6 * u;
            a.legR = w; a.legL = -w; b.legR = -w; b.legL = w; a.armR = 20; b.armR = 20; a.armL = 30; b.armL = 30;
        } else {
            ax = -3.4; bx = 4.4;
            if (t < 5.0) { double s = Math.min(1, (t - 3.2) / 0.6); a.armR = 20 + 135 * s; b.armR = 20 + 135 * s; a.armL = 40; b.armL = 40; }
            else if (t < 13.0) {
                double ph = ((t - 5.0) / 1.7) % 1.0;   // 0..0.5 A strikes, 0.5..1 B strikes
                boolean aStrikes = ph < 0.5;
                double s = aStrikes ? ph / 0.5 : (ph - 0.5) / 0.5, swing = Math.sin(Math.min(1, s * 1.25) * Math.PI);
                Pose atk = aStrikes ? a : b, def = aStrikes ? b : a;
                atk.armR = 150 - 125 * Math.min(1, s * 1.5) + (s > 0.8 ? 0 : 0); atk.lean = 14 * swing;
                def.armL = 85; def.lean = -8 * swing; def.armR = 60;
                double lunge = 0.9 * swing;
                if (aStrikes) { ax += lunge; bx += lunge * 0.4; } else { bx -= lunge; ax -= lunge * 0.4; }
                atk.legR = 18 * swing; atk.legL = -18 * swing;
            } else {
                // the finishing blow, then the fall
                double s = Math.min(1, (t - 13.0) / 1.2);
                a.armR = 160 - 140 * Math.min(1, s * 1.6); a.lean = 18 * Math.sin(Math.min(1, s) * Math.PI);
                ax += 1.0 * Math.sin(Math.min(1, s) * Math.PI * 0.5);
                double f = Math.max(0, Math.min(1, (t - 13.5) / 1.6));
                bx += 2.6 * (1 - Math.pow(1 - f, 2));
                b.fall = 88 * f * f; b.armR = 70 + 60 * f; b.armL = 20 + 80 * f; b.sword = f < 0.6;
                if (t > 15.8) { double c = Math.min(1, (t - 15.8) / 0.8); a.armR = 20 + 150 * c; a.armL = 20 + 140 * c; a.lean = -4 * c; }
            }
        }
        Figures.person(out, DUEL_X + ax, fy, DUEL_Z, 270, Kit.NETHERITE, a);
        Figures.person(out, DUEL_X + bx, fy, DUEL_Z, 90, Kit.DIAMOND, b);
        // sparks where the blades meet
        if (t >= 5.0 && t < 13.2) {
            double ph = ((t - 5.0) / 1.7) % 1.0, age = ((ph % 0.5) - 0.34) * 1.7;
            int k = (int) ((t - 5.0) / 1.7 * 2);
            if (age >= 0) Figures.sparks(out, DUEL_X + (ph < 0.5 ? -1.3 : 2.2), fy + 1.3, DUEL_Z, age, 22, 100 + k);
        }
        if (t >= 13.7 && t < 14.8) Figures.sparks(out, DUEL_X + 0.9, fy + 1.4, DUEL_Z, t - 13.7, 40, 900);
    }

    static boolean open(WorldCam.World w, int x, int y, int z) {
        int id = w.get(x, y, z);
        if (id == 0) return true;
        String n = w.states.get(id);
        return n.endsWith("air") || n.contains("light") || n.contains("barrier") || n.contains("lantern") || n.contains("carpet") || n.contains("torch");
    }

    /** A crowd seated in the Colosseum stands, laid into the world as blocks (only where there is air). */
    static void crowd(WorldCam cam, WorldCam.World w) {
        if (Figures.NO_PEOPLE) return;
        Canvas c = new Canvas(); int[] cnt = new int[2];
        Random rnd = new Random(7);
        String[] cloth = {"red_wool", "white_wool", "black_wool", "orange_wool", "gray_wool", "blue_wool"};
        String[] head = {"orange_terracotta", "terracotta", "brown_terracotta", "light_gray_terracotta"};
        for (int x = -46; x <= 46; x++)
            for (int z = 254; z <= 346; z++) {
                double r = Math.hypot(x, z - 300);
                if (r < 19 || r > 40 || rnd.nextInt(100) >= 26) continue;
                int top = Integer.MIN_VALUE; cnt[0]++;
                for (int y = 188; y >= 151; y--) {
                    if (open(w, x, y, z) || !open(w, x, y + 1, z) || !open(w, x, y + 2, z)) continue;
                    String below = w.states.get(w.get(x, y, z));
                    if (below.endsWith("air") || below.contains("lantern") || below.contains("carpet") || below.contains("banner") || below.contains("wall") || below.contains("fence") || below.contains("brazier") || below.contains("campfire")) continue;
                    top = y; break;
                }
                if (top == Integer.MIN_VALUE) { if (x == 25 || x == 30) { StringBuilder sb = new StringBuilder(); for (int yy = 150; yy <= 190; yy += 2) sb.append(yy).append(":").append(w.states.get(w.get(x, yy, z)).replace("minecraft:", "")).append(" "); System.out.println("no seat at " + x + "," + z + " " + sb); } continue; }
                cnt[1]++;
                c.set(x, top + 1, z, "minecraft:" + cloth[rnd.nextInt(cloth.length)]);
                c.set(x, top + 2, z, "minecraft:" + head[rnd.nextInt(head.length)]);
            }
        System.out.println("crowd candidates " + cnt[0] + " seats " + cnt[1] + " placed: " + cam.overlay(w, c) + "; sample top at 25,300: " + w.top(25, 300) + " " + w.states.get(w.get(25, w.top(25, 300), 300)) + " / 30,300: " + w.top(30, 300) + " " + w.states.get(w.get(30, w.top(30, 300), 300)));
    }

    /** Natural terrain out in the Wilds (a region the world generator filled in; nobody has built there). */
    static final int WX = 36608, WZ = 39680;

    static final java.util.Map<String, Shot> SHOTS = new java.util.LinkedHashMap<>();

    static void add(Shot s) { SHOTS.put(s.id(), s); }

    static {
        add(new Shot("forge", "survival", "overworld", 111, 0, 60, forgeLight(),
                (w, t) -> keys(new double[][]{cam(100.5, 152.5, 0.5, 112, 152.0, 0.5), cam(106.0, 152.35, 0.5, 112, 151.7, 0.5), cam(109.2, 152.2, 0.5, 111.5, 151.6, 0.5)}, t),
                (out, w, t, sim) -> {
                    Figures.embers(out, 111.5, 151.4, 0.5, 2.6, 7, 110, sim, 5.5, 11);
                    // a smith at the anvil: the hammer comes down every 1.6 s, one blow landing exactly on 8.2
                    double ph = ((sim - 8.2) % 1.6 + 1.6) % 1.6 / 1.6;
                    Pose sp = new Pose(); sp.hammer = true; sp.armL = 25;
                    sp.armR = 150 - 125 * Math.pow(Math.min(1, ph < 0.75 ? ph / 0.75 : 1), 3) * (ph < 0.75 ? 1 : 1 - (ph - 0.75) / 0.25);
                    sp.lean = 10 * (ph > 0.5 && ph < 0.8 ? 1 : 0);
                    Figures.person(out, 108.2, 151, -1.7, 180, Kit.IRON, sp);
                    double since = (sim - 8.2) % 1.6; if (since < 0) since += 1.6;
                    if (!Figures.NO_PEOPLE) Figures.sparks(out, 108.5, 152.0, -3.0, since, 26, 60 + (long) Math.floor((sim - 8.2) / 1.6));
                    Figures.sparks(out, 111.5, 152.2, 0.5, sim - 8.2, 70, 31);
                }, null));
        add(new Shot("kingdom", "survival", "overworld", 0, 0, 190, dusk(),
                (w, t) -> keys(new double[][]{cam(-170, 225, -150, 0.5, 150, -10), cam(-105, 205, -70, 0.5, 152, 0), cam(-30, 190, -40, 0.5, 154, 10)}, t),
                (out, w, t, sim) -> { citizens(out, sim, 0.5, 0.5, 151, 10, 15, 5); Figures.embers(out, 0.5, 150, 0.5, 40, 30, 40, sim, 8, 3); }, null));
        add(new Shot("plaza", "survival", "overworld", 0, 0, 190, dusk(),
                (w, t) -> keys(new double[][]{cam(-17, 154.6, 38, 0.5, 153.4, 22), cam(0.5, 154.2, 40, 0.5, 153.8, 22), cam(17, 154.8, 37, 0.5, 153.4, 22)}, t),
                (out, w, t, sim) -> { gathering(out, sim, 0.5, 22, 151, 14, 5.5, 9); citizens(out, sim, 0.5, 20, 151, 8, 12, 9); Figures.embers(out, 0.5, 151, 22, 18, 14, 30, sim, 6, 2); }, null));
        add(new Shot("market", "survival", "overworld", 0, 111, 120, dusk(),
                (w, t) -> keys(new double[][]{cam(-16, 157.5, 66, 0.5, 157, 111), cam(-5, 156.0, 82, 0.5, 156.5, 111), cam(10, 153.9, 94, 0.5, 156.5, 111)}, t),
                (out, w, t, sim) -> citizens(out, sim, 0.5, 100, 151, 14, 12, 21), null));
        add(new Shot("hall", "survival", "overworld", 0, -60, 150, dusk(),
                (w, t) -> keys(new double[][]{cam(-30, 152.6, -30, 0.5, 163, -110), cam(-12, 153.0, -40, 0.5, 164, -110), cam(8, 153.4, -48, 0.5, 164, -110)}, t),
                (out, w, t, sim) -> { citizens(out, sim, 0.5, -60, 151, 9, 18, 33); Figures.embers(out, 0.5, 151, -80, 30, 22, 50, sim, 7, 4); }, null));
        add(new Shot("colosseum", "survival", "overworld", 0, 255, 140, arenaLook(),
                (w, t) -> keys(new double[][]{cam(-80, 182, 160, 0.5, 164, 258), cam(-42, 165, 205, 0.5, 164, 258), cam(-6, 156.5, 232, 0.5, 166, 258)}, t),
                (out, w, t, sim) -> { gathering(out, sim, 0.5, 242, 150.3, 7, 8, 41); Figures.embers(out, 0.5, 151, 250, 22, 18, 40, sim, 6, 5); }, Premiere::crowd));
        add(new Shot("colosseum_hi", "survival", "overworld", 0, 255, 140, arenaLook(),
                (w, t) -> keys(new double[][]{cam(40, 210, 205, 0.5, 168, 262), cam(10, 198, 214, 0.5, 168, 262), cam(-26, 190, 222, 0.5, 168, 262)}, t),
                (out, w, t, sim) -> Figures.embers(out, 0.5, 160, 258, 30, 25, 50, sim, 7, 6), Premiere::crowd));
        add(new Shot("pit_in", "survival", "overworld", 0, 255, 140, arenaLook(),
                (w, t) -> {
                    int fy = groundY(w, 0, 300, 150);
                    return keys(new double[][]{cam(-13.5, fy + 2.2, 303.4, -6, fy + 1.4, 300.5), cam(-12.5, fy + 2.0, 303.0, -3, fy + 1.6, 300.5), cam(-11.0, fy + 1.9, 302.6, 0.5, fy + 1.8, 300.5)}, t);
                },
                (out, w, t, sim) -> duel(out, groundY(w, 0, 300, 150) + 1, sim), Premiere::crowd));
        add(new Shot("pit_orbit", "survival", "overworld", 0, 255, 140, arenaLook(),
                (w, t) -> {
                    int fy = groundY(w, 0, 300, 150);
                    double a = Math.toRadians(205 + 95 * t), r = 9.5 - 2 * t;
                    return cam(DUEL_X + 0.7 + Math.cos(a) * r, fy + 2.3 - 0.6 * t, DUEL_Z + Math.sin(a) * r, DUEL_X + 0.7, fy + 2.0, DUEL_Z);
                },
                (out, w, t, sim) -> duel(out, groundY(w, 0, 300, 150) + 1, sim), Premiere::crowd));
        add(new Shot("pit_close", "survival", "overworld", 0, 255, 140, arenaLook(),
                (w, t) -> {
                    int fy = groundY(w, 0, 300, 150);
                    return keys(new double[][]{cam(0.5, fy + 1.7, 296.2, 0.5, fy + 1.8, 300.5), cam(1.4, fy + 1.9, 296.0, 0.9, fy + 1.9, 300.5), cam(2.6, fy + 2.1, 296.4, 1.4, fy + 1.7, 300.5)}, t);
                },
                (out, w, t, sim) -> duel(out, groundY(w, 0, 300, 150) + 1, sim), Premiere::crowd));
        add(new Shot("pit_win", "survival", "overworld", 0, 255, 140, arenaLook(),
                (w, t) -> {
                    int fy = groundY(w, 0, 300, 150);
                    return keys(new double[][]{cam(-1.5, fy + 1.0, 307.2, -2.5, fy + 2.4, 300.5), cam(-3.5, fy + 1.4, 306.0, -2.5, fy + 2.8, 300.5), cam(-6.5, fy + 2.0, 303.8, -2.7, fy + 2.9, 300.5)}, t);
                },
                (out, w, t, sim) -> { duel(out, groundY(w, 0, 300, 150) + 1, sim); Figures.embers(out, -2.7, fy(w) + 1, 300.5, 7, 9, 40, sim, 5, 8); }, Premiere::crowd));
        add(new Shot("wilds_air", "survival", "overworld", WX, WZ, 170, WorldCam.Look.day(),
                (w, t) -> {
                    double x = WX - 110 + 220 * t, z = WZ - 70 + 60 * Math.sin(t * 2.4);
                    return cam(x, airY(w, t, 0, 0) + 34, z, x + 70, airY(w, t, 70, 20) + 6, z + 20);
                },
                (out, w, t, sim) -> {
                    for (int i = 0; i < 3; i++) {
                        double wx = WX - 50 + 24 * i, wz = WZ - 45 + 10 * i;
                        Pose p = new Pose(); double s = Math.sin((sim + i) * 6) * 28; p.legR = s; p.legL = -s; p.armR = -s; p.armL = s; p.sword = true;
                        Figures.person(out, wx + sim * 0.8, groundY(w, wx + sim * 0.8, wz, 70) + 1, wz, 270, Kit.IRON, p);
                    }
                }, null));
        add(new Shot("wilds_ground", "survival", "overworld", WX, WZ, 170, WorldCam.Look.day(),
                (w, t) -> {
                    double[] c = clearing(w);
                    return keys(new double[][]{cam(c[0] - 1.0, c[2] + 1.8, c[1] + 4.0, c[0] + 2.4, c[2] + 1.6, c[1] - 0.6), cam(c[0] + 1.2, c[2] + 1.9, c[1] + 4.6, c[0] + 2.4, c[2] + 1.6, c[1] - 0.6), cam(c[0] + 3.4, c[2] + 2.0, c[1] + 4.0, c[0] + 2.4, c[2] + 1.6, c[1] - 0.6)}, t);
                },
                (out, w, t, sim) -> {
                    double[] c = clearing(w);
                    Pose p = new Pose(); p.pick = true; p.armR = 70 + 55 * Math.max(0, Math.sin(sim * 5.2)); p.lean = 8;
                    Figures.person(out, c[0] + 1.0, c[2], c[1], 270, Kit.NETHERITE, p);
                    Pose zp = new Pose(); double q = Math.sin(sim * 5) * 25; zp.legR = q; zp.legL = -q; zp.armR = 90; zp.armL = 90;
                    double zz = c[1] - 9 + sim * 0.9;
                    Figures.person(out, c[0] + 1.2, c[2], Math.min(zz, c[1] - 1.8), 0, Kit.ZOMBIE, zp);
                },
                (cam, w) -> {
                    double[] c = clearing(w);
                    Canvas cv = new Canvas();
                    int x = (int) Math.floor(c[0]) + 3, z = (int) Math.floor(c[1]) - 1, y = (int) c[2];
                    String[] ore = {"stone", "iron_ore", "stone", "coal_ore", "diamond_ore", "stone", "iron_ore", "gold_ore", "stone"};
                    int k = 0;
                    for (int dz = 0; dz < 3; dz++) for (int dy = 0; dy < 3; dy++) { cv.set(x, y + dy, z + dz, "minecraft:" + ore[k++ % ore.length]); if (dy < 2) cv.set(x + 1, y + dy, z + dz, "minecraft:deepslate"); }
                    cam.overlay(w, cv);
                }));
        add(new Shot("scrapyard", "survival", "overworld", 0, 600, 150, WorldCam.Look.day(),
                (w, t) -> keys(new double[][]{cam(9, 163.9, 590.0, 0, 163.2, 600.5), cam(3, 163.5, 591.2, 0, 163.2, 600.5), cam(-6, 163.8, 590.4, 0, 163.2, 600.5)}, t),
                (out, w, t, sim) -> {
                    int gy = groundY(w, 0, 600, 161) + 1;
                    for (int i = 0; i < 2; i++) {
                        Pose p = new Pose(); p.sword = true; p.shield = true; double s = Math.sin(sim * 4 + i * 3.14);
                        p.armR = 80 + 70 * Math.max(0, s); p.armL = 70; p.lean = 10 * Math.max(0, s);
                        Figures.person(out, (i == 0 ? -2.4 : 2.4) + (i == 0 ? 1 : -1) * 0.5 * Math.max(0, s), gy, 600.5, i == 0 ? 270 : 90, i == 0 ? Kit.NETHERITE : Kit.IRON, p);
                    }
                    double ph = (sim * 4 / 6.2831853) % 1.0;
                    if (!Figures.NO_PEOPLE) Figures.sparks(out, 0, gy + 1.3, 600.5, ph * 1.4 - 0.15, 22, 77 + (long) (sim * 4 / 6.2831853));
                }, null));
        add(new Shot("koth", "survival", "overworld", 0, -400, 110, WorldCam.Look.sunset(),
                (w, t) -> keys(new double[][]{cam(-46, 172, -372, 0.5, 160, -400), cam(0, 176, -364, 0.5, 161, -400), cam(46, 172, -372, 0.5, 160, -400)}, t),
                (out, w, t, sim) -> { Figures.embers(out, 0.5, 158, -400, 22, 16, 40, sim, 6, 12); Pose p = new Pose(); p.sword = true; p.armR = 160; Figures.person(out, 0.5, 158, -400, 180, Kit.NETHERITE, p); }, null));
        add(new Shot("hub", "hub", "overworld", 0, 0, 230, WorldCam.Look.inferno(),
                (w, t) -> keys(new double[][]{cam(0.5, 250, 320, 0.5, 120, 0), cam(0.5, 200, 230, 0.5, 112, 0), cam(0.5, 165, 150, 0.5, 108, 0)}, t),
                (out, w, t, sim) -> out.addAll(Trailer.dragons(300 + (int) (sim * 30))), null));
        add(new Shot("hub_close", "hub", "overworld", 0, 0, 230, WorldCam.Look.inferno(),
                (w, t) -> keys(new double[][]{cam(100, 140, 62, 0.5, 108, 0.5), cam(62, 150, 98, 0.5, 108, 0.5), cam(8, 152, 112, 0.5, 108, 0.5)}, t),
                (out, w, t, sim) -> out.addAll(Trailer.dragons(300 + (int) (sim * 30))), null));
        // ---- the rest of the network (Skyblock, OneBlock, Factions, Arcade)
        add(new Shot("skyhub_air", "skyblock", "skyhub", 0, 0, 200, dusk(),
                (w, t) -> keys(new double[][]{cam(-55, 150, 120, 0.5, 100, -5), cam(0.5, 168, 112, 0.5, 100, -5), cam(55, 150, 120, 0.5, 100, -5)}, t),
                (out, w, t, sim) -> gathering(out, sim, 0.5, 30, 101, 8, 4, 61), null));
        add(new Shot("sky_aerial", "skyblock", "overworld", 0, 0, 180, dusk(),
                (w, t) -> keys(new double[][]{cam(-60, 196, 70, 0.5, 152, 0), cam(0.5, 196, 82, 0.5, 152, 0), cam(60, 196, 70, 0.5, 152, 0)}, t),
                (out, w, t, sim) -> { gathering(out, sim, 0.5, 12, 151, 6, 4, 62); Figures.embers(out, 0.5, 151, 0.5, 24, 14, 24, sim, 7, 8); }, null));
        add(new Shot("sky_spawn", "skyblock", "overworld", 0, 0, 180, WorldCam.Look.sunset(),
                (w, t) -> keys(new double[][]{cam(-9, 153.2, 28, 0.5, 153.5, -10), cam(0.5, 153.0, 26, 0.5, 153.8, -10), cam(9, 153.2, 28, 0.5, 153.5, -10)}, t),
                (out, w, t, sim) -> { gathering(out, sim, 0.5, 8, 151, 8, 5, 63); citizens(out, sim, 0.5, 6, 151, 5, 8, 64); }, null));
        add(new Shot("oneblock", "skyblock", "oneblock_spawn", 0, 0, 160, WorldCam.Look.sunset(),
                (w, t) -> keys(new double[][]{cam(-14, 104, 26, 0.5, 106, -8), cam(0.5, 103.4, 22, 0.5, 107, -8), cam(14, 104, 26, 0.5, 106, -8)}, t),
                (out, w, t, sim) -> { gathering(out, sim, 0.5, 8, 101, 6, 4, 65); Figures.embers(out, 0.5, 102, 0, 14, 12, 26, sim, 6, 9); }, null));
        add(new Shot("oneblock_air", "skyblock", "oneblock_spawn", 0, 0, 160, WorldCam.Look.sunset(),
                (w, t) -> keys(new double[][]{cam(-70, 150, 90, 0.5, 100, 0), cam(0.5, 160, 100, 0.5, 100, 0), cam(70, 150, 90, 0.5, 100, 0)}, t),
                null, null));
        add(new Shot("factions", "factions", "overworld", 0, 0, 130, WorldCam.Look.frozenNight(),
                (w, t) -> keys(new double[][]{cam(62, 212, 66, 0.5, 197, 0), cam(0, 220, 80, 0.5, 197, 0), cam(-62, 212, 66, 0.5, 197, 0)}, t),
                (out, w, t, sim) -> Figures.embers(out, 0.5, 192, 0.5, 40, 25, 50, sim, 8, 14), null));
        add(new Shot("factions_low", "factions", "overworld", 0, 0, 130, WorldCam.Look.frozenNight(),
                (w, t) -> keys(new double[][]{cam(40, 198, 62, 0.5, 193, 0), cam(10, 197, 60, 0.5, 193, 0), cam(-24, 198, 62, 0.5, 193, 0)}, t),
                (out, w, t, sim) -> {
                    int gy = groundY(w, 0, 20, 192) + 1;
                    for (int i = 0; i < 2; i++) {
                        Pose p = new Pose(); p.sword = true; p.shield = true; double q = Math.sin(sim * 4 + i * 3.14);
                        p.armR = 80 + 70 * Math.max(0, q); p.armL = 70; p.lean = 10 * Math.max(0, q);
                        Figures.person(out, (i == 0 ? -2.4 : 2.4) + (i == 0 ? 1 : -1) * 0.5 * Math.max(0, q), gy, 20.5, i == 0 ? 270 : 90, i == 0 ? Kit.RED : Kit.BLUE, p);
                    }
                    if (!Figures.NO_PEOPLE) Figures.sparks(out, 0, gy + 1.3, 20.5, ((sim * 4 / 6.2831853) % 1.0) * 1.4 - 0.15, 20, 91);
                }, null));
        add(new Shot("arcade", "arcade", "overworld", 0, 0, 100, WorldCam.Look.neonNight(),
                (w, t) -> keys(new double[][]{cam(-35, 118, 40, 0.5, 103, 0), cam(0.5, 122, 48, 0.5, 103, 0), cam(35, 118, 40, 0.5, 103, 0)}, t),
                (out, w, t, sim) -> { gathering(out, sim, 0.5, 14, 101, 10, 5, 71); Figures.embers(out, 0.5, 102, 14, 20, 12, 30, sim, 6, 15); }, null));
        add(new Shot("arcade_low", "arcade", "overworld", 0, 0, 100, WorldCam.Look.neonNight(),
                (w, t) -> keys(new double[][]{cam(-14, 103.6, 33.5, 0.5, 108, -10), cam(0.5, 103.2, 33.5, 0.5, 108, -10), cam(14, 103.6, 33.5, 0.5, 108, -10)}, t),
                (out, w, t, sim) -> gathering(out, sim, 0.5, 22, 102, 8, 4, 72), null));
        // the website hero: a full turn round the kingdom, everything periodic
        add(new Shot("orbit", "survival", "overworld", 0, 0, 215, dusk(),
                (w, t) -> {
                    double a = Math.toRadians(215 + 360 * t), r = 150;
                    return cam(0.5 + Math.cos(a) * r, 196 + 6 * Math.sin(a * 2), 0.5 + Math.sin(a) * r, 0.5, 153, 0.5);
                },
                (out, w, t, sim) -> { citizens(out, 0, 0.5, 0.5, 151, 0, 1, 1); Figures.embers(out, 0.5, 150, 0.5, 45, 40, 70, sim, 10, 3); }, null));
    }

    static double[] clearingCache;

    /** An open patch of natural ground near the Wilds shot centre: {x, z, y of the surface}. */
    static double[] clearing(WorldCam.World w) {
        if (clearingCache != null) return clearingCache;
        double best = 1e9; double[] res = {WX, WZ, 70}; int cand = 0, gcount = 0; String sample = "";
        for (int x = WX - 150; x <= WX + 150; x += 2)
            for (int z = WZ - 150; z <= WZ + 150; z += 2) {
                int top = w.top(x, z);
                if (top == Integer.MIN_VALUE) continue;
                String b = w.states.get(w.get(x, top, z));
                if (gcount++ == 500) sample = b + "@" + top;
                if (!(b.contains("grass_block") || b.contains("dirt") || b.contains("sand") || b.contains("stone") || b.contains("podzol"))) continue;
                boolean ok = true; double rough = 0;
                for (int dx = -4; dx <= 4 && ok; dx++)
                    for (int dz = -4; dz <= 4 && ok; dz++) {
                        int t2 = w.top(x + dx, z + dz);
                        if (t2 == Integer.MIN_VALUE) { ok = false; break; }
                        String b2 = w.states.get(w.get(x + dx, t2, z + dz));
                        if (b2.contains("leaves") || b2.contains("log") || b2.contains("water") || b2.contains("lava")) ok = false;
                        rough += Math.abs(t2 - top);
                    }
                cand++;
                if (!ok || rough > 70) continue;
                double score = rough + Math.hypot(x - WX, z - WZ) * 0.1;
                if (score < best) { best = score; res = new double[]{x + 0.5, z + 0.5, top + 1}; }
            }
        System.out.println("clearing candidates " + cand + " ground " + gcount + " sample " + sample);
        System.out.println("clearing at " + res[0] + " " + res[1] + " y " + res[2]);
        return clearingCache = res;
    }

    static int fy(WorldCam.World w) { return groundY(w, 0, 300, 150) + 1; }

    // ------------------------------------------------------------------ the cut

    enum Kind { NORMAL, FORGE, LADDER, END, BLACK }

    /** Caption: text, when it shows (seconds into the clip), where. */
    record Cap(String text, double from, double to, boolean centre) {}

    /** One clip: a shot, the part of its path to fly, how long, the simulation seconds to play, captions. */
    record Clip(String shot, double p0, double p1, double dur, double sim0, double sim1, Kind kind, Cap... caps) {}

    static Clip clip(String shot, double p0, double p1, double dur, Cap... caps) { return new Clip(shot, p0, p1, dur, 0, dur, Kind.NORMAL, caps); }

    static Clip simClip(String shot, double p0, double p1, double dur, double s0, double s1, Cap... caps) { return new Clip(shot, p0, p1, dur, s0, s1, Kind.NORMAL, caps); }

    static Cap cap(String t, double a, double b) { return new Cap(t, a, b, false); }

    static Cap mid(String t, double a, double b) { return new Cap(t, a, b, true); }

    static List<Clip> mainFilm() {
        List<Clip> f = new ArrayList<>();
        f.add(new Clip("forge", 0, 1, 12, 0, 12, Kind.FORGE, mid("EVERY LEGEND BEGINS WITH A SPARK.", 3.4, 7.3)));
        f.add(clip("kingdom", 0, 1, 6.5, cap("A WORLD BUILT TO BE EXPLORED.", 0.7, 5.6)));
        f.add(clip("market", 0, 1, 5.5));
        f.add(clip("hall", 0, 1, 6, cap("YOUR STORY STARTS HERE.", 0.6, 5.2)));
        f.add(clip("colosseum", 0, 1, 5.5, cap("ENTER THE ARENA.", 0.6, 4.9)));
        f.add(simClip("pit_in", 0, 1, 4.6, 0, 3.6));
        f.add(simClip("pit_orbit", 0, 1, 5.2, 5.0, 11.2, cap("PROVE YOUR WORTH.", 0.6, 4.6)));
        f.add(simClip("pit_close", 0, 1, 3.6, 12.2, 15.2));
        f.add(new Clip("pit_win", 0, 1, 3.6, 16.0, 19.6, Kind.NORMAL, cap("CLAIM YOUR PLACE AMONG THE LEGENDS.", 0.3, 3.4)));
        f.add(new Clip("colosseum_hi", 0, 1, 4.0, 0, 4, Kind.LADDER));
        f.add(clip("wilds_air", 0, 1, 4.5, cap("EXPLORE.", 0.5, 4.0)));
        f.add(clip("wilds_ground", 0, 1, 4.2, cap("BUILD.", 0.4, 3.8)));
        f.add(clip("scrapyard", 0, 1, 4.2, cap("FIGHT.", 0.4, 3.8)));
        f.add(clip("koth", 0, 1, 4.2, cap("THRIVE.", 0.4, 3.8)));
        f.add(clip("plaza", 0, 1, 6.3, cap("BUILT FOR ADVENTURERS.", 0.6, 5.5)));
        f.add(clip("hub_close", 0, 1, 6.5, cap("MADE FOR LEGENDS.", 0.6, 5.7)));
        // the rapid sequence
        f.add(clip("colosseum_hi", 0.0, 0.5, 1.3));
        f.add(clip("kingdom", 0.4, 0.8, 1.1));
        f.add(simClip("pit_orbit", 0.3, 0.8, 1.3, 6.5, 8.0));
        f.add(clip("hub", 0.2, 0.6, 1.1));
        f.add(simClip("pit_close", 0.2, 0.9, 1.1, 13.4, 14.5));
        f.add(clip("wilds_air", 0.5, 0.9, 1.0));
        f.add(clip("scrapyard", 0.3, 0.8, 1.0));
        f.add(simClip("pit_win", 0.3, 0.8, 1.2, 16.6, 17.8));
        f.add(clip("colosseum", 0.5, 1.0, 1.3));
        f.add(new Clip("forge", 0, 0, 3.0, 0, 3, Kind.BLACK));
        f.add(new Clip("hub", 1, 1, 6.5, 0, 6.5, Kind.END));
        return f;
    }

    static List<Clip> networkFilm() {
        List<Clip> f = new ArrayList<>();
        f.add(new Clip("forge", 0.35, 1, 4.5, 7.0, 11.5, Kind.FORGE));
        f.add(clip("hub", 0, 1, 6.0, cap("ONE NETWORK. FIVE WORLDS.", 0.6, 5.4)));
        f.add(clip("skyhub_air", 0, 1, 4.5, cap("CHOOSE YOUR WORLD.", 0.5, 4.0)));
        f.add(clip("sky_aerial", 0, 1, 4.6, cap("SKYBLOCK. START WITH NOTHING.", 0.5, 4.1)));
        f.add(clip("oneblock", 0, 1, 4.6, cap("ONEBLOCK. ONE BLOCK, ENDLESS.", 0.5, 4.1)));
        f.add(clip("kingdom", 0, 1, 5.0, cap("SURVIVAL. A WORLD TO CONQUER.", 0.5, 4.5)));
        f.add(clip("market", 0, 1, 3.0));
        f.add(clip("wilds_air", 0, 1, 3.8, cap("EXPLORE. MINE. BUILD.", 0.4, 3.4)));
        f.add(clip("wilds_ground", 0, 1, 3.2));
        f.add(clip("koth", 0, 1, 3.8, cap("KING OF THE HILL.", 0.4, 3.4)));
        f.add(clip("scrapyard", 0, 1, 3.8, cap("THE SCRAPYARD.", 0.4, 3.4)));
        f.add(clip("colosseum", 0, 1, 3.4, cap("THE COLOSSEUM.", 0.4, 3.0)));
        f.add(simClip("pit_orbit", 0, 1, 3.6, 5.6, 9.2));
        f.add(clip("factions", 0, 1, 4.6, cap("FACTIONS. RAID. CLAIM. CONQUER.", 0.4, 4.2)));
        f.add(clip("factions_low", 0, 1, 3.0));
        f.add(clip("arcade", 0, 1, 4.4, cap("ARCADE. QUICK MATCHES.", 0.4, 4.0)));
        f.add(clip("plaza", 0, 1, 4.8, cap("ONE COMMUNITY.", 0.5, 4.3)));
        f.add(clip("hub_close", 0, 1, 4.0, cap("MADE FOR LEGENDS.", 0.4, 3.6)));
        // rapid sequence
        f.add(clip("hub", 0.3, 0.7, 1.0));
        f.add(clip("sky_aerial", 0.4, 0.8, 1.0));
        f.add(clip("oneblock", 0.2, 0.6, 1.0));
        f.add(clip("kingdom", 0.4, 0.8, 1.0));
        f.add(clip("wilds_air", 0.5, 0.9, 1.0));
        f.add(simClip("pit_close", 0.2, 0.9, 1.0, 13.4, 14.5));
        f.add(clip("factions", 0.3, 0.7, 1.0));
        f.add(clip("arcade", 0.3, 0.7, 1.0));
        f.add(clip("koth", 0.4, 0.8, 1.0));
        f.add(clip("colosseum_hi", 0.0, 0.5, 1.2));
        f.add(new Clip("forge", 0, 0, 1.6, 0, 1.6, Kind.BLACK));
        f.add(new Clip("hub", 1, 1, 6.5, 0, 6.5, Kind.END));
        return f;
    }

    static List<Clip> heroFilm() {
        return List.of(new Clip("orbit", 0, 1, 20, 0, 20, Kind.NORMAL));
    }

    static List<Clip> shortFilm() {
        List<Clip> f = new ArrayList<>();
        f.add(new Clip("forge", 0.35, 1, 4.5, 7.0, 11.5, Kind.FORGE));
        f.add(clip("kingdom", 0.1, 0.75, 3.5));
        f.add(clip("colosseum", 0.2, 1, 3.5, cap("ENTER THE ARENA.", 0.3, 3.2)));
        f.add(simClip("pit_orbit", 0, 1, 4.5, 5.5, 10.0, cap("PROVE YOUR WORTH.", 0.3, 4.1)));
        f.add(simClip("pit_close", 0, 1, 3.0, 12.4, 15.0));
        f.add(new Clip("pit_win", 0, 1, 3.0, 16.4, 19.4, Kind.NORMAL, cap("CLAIM YOUR PLACE.", 0.2, 2.8)));
        f.add(clip("wilds_air", 0.2, 0.8, 2.6));
        f.add(clip("scrapyard", 0, 0.9, 2.8));
        f.add(new Clip("hub", 1, 1, 4.6, 0, 4.6, Kind.END));
        return f;
    }

    // ------------------------------------------------------------------ rendering

    static double smooth(double t) { t = Math.max(0, Math.min(1, t)); return 0.5 * t + 0.5 * (t * t * (3 - 2 * t)); }

    public static void main(String[] a) throws Exception {
        File jar = new File(a[0]);
        String product = a[1];
        int from = Integer.parseInt(a[2]), to = Integer.parseInt(a[3]), every = Integer.parseInt(a[4]), ss = Integer.parseInt(a[5]);
        File out = new File(a[6]);
        boolean vertical = product.equals("short");
        int W = vertical ? 1080 : 1920, H = vertical ? 1920 : 1080;
        if (a.length > 8) { W = Integer.parseInt(a[7]); H = Integer.parseInt(a[8]); }
        List<Clip> film = product.equals("hero") ? heroFilm() : product.equals("short") ? shortFilm() : product.equals("network") ? networkFilm() : mainFilm();
        out.mkdirs();
        BufferedImage logo = ImageIO.read(new File(NET, "website/assets/img/logo-full.png"));
        WorldCam wc = new WorldCam(jar);
        Font font = Font.createFont(Font.TRUETYPE_FONT, new File(NET, "tools/brand/fonts/ChakraPetch-Bold.ttf"));
        int frame = 0;
        String loaded = null;
        WorldCam.World world = null;
        double total = film.stream().mapToDouble(Clip::dur).sum();
        double filmT0 = 0;
        for (Clip c : film) {
            int n = (int) Math.round(c.dur() * FPS);
            Shot s = SHOTS.get(c.shot());
            boolean need = false;
            for (int i = 0; i < n; i++) if (frame + i >= from && frame + i <= to && (frame + i) % every == 0 && !new File(out, String.format("f%05d.png", frame + i)).exists()) need = true;
            if (need && c.kind() != Kind.BLACK && !s.key().equals(loaded)) {
                world = wc.load(new File(NET, s.server() + "/world/dimensions/minecraft/" + s.dim() + "/region"), s.cx() - s.radius(), s.cz() - s.radius(), s.cx() + s.radius(), s.cz() + s.radius());
                if (s.prep() != null) s.prep().accept(wc, world);
                loaded = s.key();
                System.out.println("loaded " + loaded + ": " + world.blocks.size() + " chunks");
            }
            for (int i = 0; i < n; i++, frame++) {
                if (frame < from || frame > to || frame % every != 0) continue;
                File f = new File(out, String.format("f%05d.png", frame));
                if (f.exists()) continue;
                double sec = i / (double) FPS, u = product.equals("hero") ? i / (double) n : n > 1 ? i / (double) (n - 1) : 0;
                BufferedImage img;
                long t0 = System.currentTimeMillis();
                if (c.kind() == Kind.BLACK) img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
                else {
                    double pt = c.p0() + (c.p1() - c.p0()) * (product.equals("hero") ? u : smooth(u));
                    double sim = c.sim0() + (c.sim1() - c.sim0()) * u;
                    double[] p = s.path().at(world, pt);
                    List<WorldCam.Box> boxes = new ArrayList<>();
                    if (s.fx() != null) s.fx().add(boxes, world, pt, sim);
                    double fov = vertical ? 74 : 66;
                    img = wc.render(world, s.look(), new WorldCam.Cam(p[0], p[1], p[2], p[3], p[4], p[5], fov), boxes, W, H, ss);
                }
                post(img, c, sec, filmT0 + sec, vertical, product.equals("hero"), logo, font, c.sim0() + (c.sim1() - c.sim0()) * u, frame);
                ImageIO.write(img, "png", f);
                System.out.printf("frame %d (%s) %d ms%n", frame, c.shot(), System.currentTimeMillis() - t0);
            }
            filmT0 += c.dur();
        }
        System.out.println("total frames: " + frame + " (" + total + " s)");
    }

    // ------------------------------------------------------------------ grade, bloom, text

    static int[] px(BufferedImage im) { return ((java.awt.image.DataBufferInt) toInt(im).getRaster().getDataBuffer()).getData(); }

    static BufferedImage toInt(BufferedImage im) { return im; }

    static void post(BufferedImage img, Clip c, double sec, double filmT, boolean vertical, boolean hero, BufferedImage logo, Font font, double sim, int frame) {
        int W = img.getWidth(), H = img.getHeight();
        int[] d = px(img);
        // bloom: bright pixels blurred at quarter size and added back
        int bw = W / 4, bh = H / 4;
        float[] br = new float[bw * bh * 3], tmp = new float[bw * bh * 3];
        for (int y = 0; y < bh; y++)
            for (int x = 0; x < bw; x++) {
                float r = 0, g = 0, b = 0;
                for (int dy = 0; dy < 4; dy++) for (int dx = 0; dx < 4; dx++) { int p = d[(y * 4 + dy) * W + x * 4 + dx]; r += (p >> 16) & 255; g += (p >> 8) & 255; b += p & 255; }
                r /= 16; g /= 16; b /= 16;
                float l = 0.3f * r + 0.59f * g + 0.11f * b, k = Math.max(0, l - 150) / 105f;
                int i = (y * bw + x) * 3;
                br[i] = r * k; br[i + 1] = g * k; br[i + 2] = b * k;
            }
        for (int pass = 0; pass < 2; pass++) {
            int rad = 6;
            for (int y = 0; y < bh; y++) for (int x = 0; x < bw; x++) for (int ch = 0; ch < 3; ch++) { float s = 0; for (int k = -rad; k <= rad; k++) s += br[(y * bw + Math.max(0, Math.min(bw - 1, x + k))) * 3 + ch]; tmp[(y * bw + x) * 3 + ch] = s / (2 * rad + 1); }
            for (int y = 0; y < bh; y++) for (int x = 0; x < bw; x++) for (int ch = 0; ch < 3; ch++) { float s = 0; for (int k = -rad; k <= rad; k++) s += tmp[(Math.max(0, Math.min(bh - 1, y + k)) * bw + x) * 3 + ch]; br[(y * bw + x) * 3 + ch] = s / (2 * rad + 1); }
        }
        Random gr = new Random(frame * 7919L);
        double cx = W / 2.0, cy = H / 2.0, maxR = Math.hypot(cx, cy);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                int p = d[y * W + x];
                double r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
                int bi = (Math.min(bh - 1, y / 4) * bw + Math.min(bw - 1, x / 4)) * 3;
                r += br[bi] * 0.9; g += br[bi + 1] * 0.85; b += br[bi + 2] * 0.8;
                // grade: lift contrast, warm highlights, cool shadows
                double l = (0.3 * r + 0.59 * g + 0.11 * b) / 255, s = l * l * (3 - 2 * l);
                double k = 1 + (s - l) * 0.9;
                r = r * k + (l > 0.5 ? 6 * (l - 0.5) * 2 : 0); b = b * k - (l > 0.5 ? 7 * (l - 0.5) * 2 : 0) + (l < 0.4 ? 5 * (0.4 - l) : 0);
                g = g * k;
                double dist = Math.hypot(x - cx, y - cy) / maxR, vig = 1 - 0.55 * Math.pow(Math.max(0, dist - 0.25), 1.6) / 0.9;
                double n = (gr.nextDouble() - 0.5) * 7;
                d[y * W + x] = (clamp(r * vig + n) << 16) | (clamp(g * vig + n) << 8) | clamp(b * vig + n);
            }
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        double sc = vertical ? W / 1080.0 : W / 1920.0;
        g.scale(sc, sc);
        W = (int) Math.round(W / sc); H = (int) Math.round(H / sc);
        double len = c.dur();
        Font f = font;
        if (!hero) {
            // soft dip between clips (not after the rapid sequence, which cuts hard)
            boolean quick = len < 1.5;
            double fadeLen = quick ? 0.0 : 0.3;
            double dip = 1;
            if (fadeLen > 0) dip = Math.min(1, Math.min(sec / fadeLen, (len - sec) / fadeLen));
            if (c.kind() == Kind.FORGE) dip = len > 8 ? Math.min(1, Math.max(0, sec / 4.5)) * (len - sec > 0.4 ? 1 : (len - sec) / 0.4) : Math.min(1, sec / 0.3);
            if (c.kind() == Kind.END) dip = Math.min(1, sec / 0.01);
            if (filmT < 0.01) dip = 0;
            if (dip < 1) { g.setColor(new Color(0, 0, 0, (int) (255 * (1 - Math.max(0, dip))))); g.fillRect(0, 0, W, H); }
        }
        switch (c.kind()) {
            case FORGE -> forgeOverlay(g, W, H, sec, len, logo, f, vertical);
            case LADDER -> ladder(g, W, H, sec, len, f, vertical);
            case END -> endCard(g, W, H, sec, len, logo, f, vertical, frame);
            default -> { }
        }
        if (System.getenv("NOCAP") == null) for (Cap cp : c.caps()) caption(g, W, H, cp, sec, f, vertical);
        g.dispose();
    }

    static int clamp(double v) { return v < 0 ? 0 : v > 255 ? 255 : (int) v; }

    static double fade(double t, double a, double b, double c, double d) { return Trailer.fade(t, a, b, c, d); }

    static Font sized(Font f, float size) { return f.deriveFont(Font.BOLD, size); }

    static void spaced(Graphics2D g, String s, double cx, double y, float size, Font base, int track, Color col, double alpha, boolean left, double leftX) {
        if (alpha <= 0) return;
        g.setFont(sized(base, size));
        FontMetrics fm = g.getFontMetrics();
        int w = 0;
        for (char ch : s.toCharArray()) w += fm.charWidth(ch) + track;
        double x = left ? leftX : cx - w / 2.0;
        for (char ch : s.toCharArray()) {
            String t = String.valueOf(ch);
            g.setColor(new Color(0, 0, 0, (int) (170 * Math.min(1, alpha))));
            g.drawString(t, (float) x + 3, (float) y + 3);
            g.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), (int) (255 * Math.min(1, alpha))));
            g.drawString(t, (float) x, (float) y);
            x += fm.charWidth(ch) + track;
        }
    }

    static void caption(Graphics2D g, int W, int H, Cap cp, double sec, Font f, boolean vertical) {
        double a = fade(sec, cp.from(), cp.from() + 0.5, cp.to() - 0.5, cp.to());
        if (a <= 0) return;
        float size = vertical ? 50 : 54;
        double drift = (1 - Math.min(1, a)) * 10;
        if (cp.centre()) {
            spaced(g, cp.text(), W / 2.0, H / 2.0 + 20 + drift, vertical ? 42 : 58, f, 8, new Color(255, 214, 160), a, false, 0);
            g.setColor(new Color(255, 122, 26, (int) (230 * a)));
            g.fillRect(W / 2 - 50, (int) (H / 2.0 + 50), 100, 4);
        } else {
            double y = vertical ? H * 0.74 : H - 150;
            double x = vertical ? 70 : 120;
            g.setColor(new Color(255, 122, 26, (int) (240 * a)));
            g.fillRect((int) x, (int) (y - size - 14), (int) (84 * Math.min(1, a * 1.5)), 5);
            spaced(g, cp.text(), 0, y + drift, size * (vertical ? 0.8f : 1f), f, 5, new Color(255, 236, 205), a, true, x);
        }
    }

    static void forgeOverlay(Graphics2D g, int W, int H, double sec, double len, BufferedImage logo, Font f, boolean vertical) {
        double strike = 8.2, local = len < 8 ? sec + (len == 4.5 ? 7.0 - 0 : 0) : sec;
        // an ember in the dark at the very start
        double ea = fade(sec, 0.3, 1.0, 2.2, 3.2);
        if (ea > 0 && len > 8) {
            RadialGradientPaint rg = new RadialGradientPaint(new java.awt.geom.Point2D.Double(W / 2.0, H / 2.0 + 30), 38f, new float[]{0, 0.3f, 1}, new Color[]{new Color(255, 210, 120, (int) (255 * ea)), new Color(255, 110, 20, (int) (170 * ea)), new Color(255, 60, 0, 0)});
            g.setPaint(rg); g.fillRect(0, 0, W, H);
        }
        double st = len > 8 ? strike : (strike - 7.0);
        double tt = len > 8 ? sec : sec;
        if (len > 8 || len == 4.5) {
            double s0 = len > 8 ? strike : 1.2;
            double flash = tt >= s0 ? Math.exp(-(tt - s0) * 5.5) : 0;
            // the hammer's ring: a white flash over the whole frame, fast attack
            if (flash > 0) { g.setColor(new Color(255, 236, 200, (int) (255 * Math.min(1, flash * 1.1)))); g.fillRect(0, 0, W, H); }
            double la = fade(tt, s0 + 0.5, s0 + 2.2, len + 9, len + 10);
            if (la > 0) {
                g.setColor(new Color(0, 0, 0, (int) (120 * la))); g.fillRect(0, 0, W, H);
                RadialGradientPaint glow = new RadialGradientPaint(new java.awt.geom.Point2D.Double(W / 2.0, H / 2.0), W * 0.35f, new float[]{0, 1}, new Color[]{new Color(255, 120, 30, (int) (90 * la)), new Color(255, 120, 30, 0)});
                g.setPaint(glow); g.fillRect(0, 0, W, H);
                Trailer.drawLogo(g, logo, W / 2, H / 2, vertical ? (int) (W * 0.86) : 900, la);
            }
        }
    }

    static void ladder(Graphics2D g, int W, int H, double sec, double len, Font f, boolean vertical) {
        double a = fade(sec, 0.3, 0.9, len - 0.7, len - 0.1);
        if (a <= 0) return;
        int w = 560, h = 300, x = W - w - 110, y = H / 2 - h / 2;
        g.setColor(new Color(10, 8, 8, (int) (200 * a))); g.fillRoundRect(x, y, w, h, 18, 18);
        g.setColor(new Color(255, 122, 26, (int) (230 * a))); g.fillRect(x, y, 6, h);
        spaced(g, "GLADIATOR LADDER", 0, y + 56, 30, f, 5, new Color(255, 214, 160), a, true, x + 34);
        spaced(g, "1v1 duels  ·  Elo-rated  ·  your own gear", 0, y + 92, 17, f, 1, new Color(210, 205, 200), a, true, x + 34);
        String[] tier = {"GRAND CHAMPION", "ELITE GLADIATOR", "VETERAN CHAMPION"};
        Color[] col = {new Color(255, 200, 90), new Color(240, 240, 245), new Color(214, 140, 92)};
        for (int i = 0; i < 3; i++) {
            g.setColor(new Color(col[i].getRed(), col[i].getGreen(), col[i].getBlue(), (int) (255 * a)));
            g.fillRect(x + 34, y + 130 + i * 50, 10, 30);
            spaced(g, "#" + (i + 1) + "   " + tier[i], 0, y + 156 + i * 50, 24, f, 3, col[i], a, true, x + 58);
        }
    }

    static void endCard(Graphics2D g, int W, int H, double sec, double len, BufferedImage logo, Font f, boolean vertical, int frame) {
        // slam in at 0.35 s: black first, then the emblem with a hard impact flash
        double hit = 0.35;
        if (sec < hit) { g.setColor(Color.BLACK); g.fillRect(0, 0, W, H); return; }
        double t = sec - hit;
        double flash = Math.exp(-t * 7);
        g.setColor(new Color(0, 0, 0, (int) (235 * Math.min(1, Math.max(0.0, 0.92 - Math.min(1, t / 3.0) * 0.25)))));
        g.fillRect(0, 0, W, H);
        double la = Math.min(1, t / 0.25);
        double scale = 1.0 + 0.1 * Math.exp(-t * 5);
        RadialGradientPaint glow = new RadialGradientPaint(new java.awt.geom.Point2D.Double(W / 2.0, H * 0.42), W * 0.45f, new float[]{0, 1}, new Color[]{new Color(255, 120, 30, (int) (110 * la)), new Color(255, 120, 30, 0)});
        g.setPaint(glow); g.fillRect(0, 0, W, H);
        int lw = (int) ((vertical ? W * 0.88 : 1000) * scale);
        Trailer.drawLogo(g, logo, W / 2, (int) (H * (vertical ? 0.36 : 0.40)), lw, la);
        // embers rising across the card
        Random rnd = new Random(5);
        for (int i = 0; i < 70; i++) {
            double ph = rnd.nextDouble(), x = rnd.nextDouble() * W, sp = 0.05 + rnd.nextDouble() * 0.1, size = 2 + rnd.nextDouble() * 4;
            double life = (ph + t * sp * 1.6) % 1.0, y = H * (1.05 - life * 1.15), xx = x + Math.sin(life * 9 + i) * 26;
            double al = Math.min(1, la) * (life < 0.85 ? 1 : (1 - life) / 0.15) * (0.3 + 0.7 * rnd.nextDouble());
            g.setColor(new Color(255, (int) (120 + 100 * rnd.nextDouble()), 30, (int) (255 * al)));
            g.fillOval((int) xx, (int) y, (int) size, (int) size);
        }
        double y0 = H * (vertical ? 0.54 : 0.62);
        spaced(g, "FORGED BY ADVENTURE. DEFINED BY LEGENDS.", W / 2.0, y0, vertical ? 30 : 38, f, vertical ? 3 : 6, new Color(255, 232, 200), fade(t, 0.9, 1.6, len + 9, len + 10), false, 0);
        spaced(g, "YOUR LEGEND STARTS HERE.", W / 2.0, y0 + (vertical ? 60 : 74), vertical ? 26 : 30, f, vertical ? 5 : 9, new Color(255, 170, 70), fade(t, 1.8, 2.5, len + 9, len + 10), false, 0);
        double ua = fade(t, 2.8, 3.4, len + 9, len + 10);
        spaced(g, "play.blackforgemc.com", W / 2.0, y0 + (vertical ? 150 : 170), vertical ? 40 : 50, f, 2, Color.WHITE, ua, false, 0);
        spaced(g, "blackforgemc.com   ·   Java Edition", W / 2.0, y0 + (vertical ? 196 : 218), vertical ? 22 : 24, f, 2, new Color(210, 210, 220), ua * 0.9, false, 0);
        if (flash > 0.02) { g.setColor(new Color(255, 236, 200, (int) (240 * flash))); g.fillRect(0, 0, W, H); }
        // closing fade
        double out = Math.max(0, (sec - (len - 1.2)) / 1.2);
        if (out > 0) { g.setColor(new Color(0, 0, 0, (int) (255 * Math.min(1, out)))); g.fillRect(0, 0, W, H); }
    }
}
