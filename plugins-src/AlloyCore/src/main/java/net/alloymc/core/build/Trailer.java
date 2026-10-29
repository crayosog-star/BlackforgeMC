package net.alloymc.core.build;

import net.alloymc.core.hub.DragonFlight;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The BlackForgeMC trailer: camera moves through the live worlds rendered with {@link WorldCam}, the hub dragons flying,
 * captions and a title and end card drawn on top. Writes numbered PNG frames; tools/make_trailer.py adds the music and
 * encodes the video with ffmpeg.
 *
 * gradle trailer -Pjar=&lt;client jar&gt; [-Pfrom=0 -Pto=99999]  (frames go to previews/trailer/)
 */
public final class Trailer {
    static final int FPS = 30, W = 1280, H = 720;

    /** The two hub dragons, {@code steps} ticks into their flight. */
    static List<WorldCam.Box> dragons(int steps) {
        List<DragonFlight> fl = List.of(new DragonFlight(DragonFlight.Kind.BLACK, 11, 0.3), new DragonFlight(DragonFlight.Kind.SERPENT, 13, 4.4));
        fl.forEach(d -> d.setOthers(fl));
        for (int t = 0; t < steps; t++) fl.forEach(DragonFlight::step);
        List<WorldCam.Box> out = new ArrayList<>();
        for (DragonFlight d : fl)
            for (DragonFlight.Piece p : d.pose())
                out.add(new WorldCam.Box(p.block(), p.light() > 0, p.corner(), p.r(), p.u(), p.f(), p.sx(), p.sy(), p.sz()));
        return out;
    }

    /** One shot: where to load, the look, a camera path (keyframes x,y,z,lookX,lookY,lookZ) and the caption. */
    record Scene(String server, String dim, int cx, int cz, int radius, WorldCam.Look look, double seconds, double[][] path, String title, String sub, boolean dragons) {}

    static List<Scene> scenes() {
        WorldCam.Look inferno = WorldCam.Look.inferno(), sunset = WorldCam.Look.sunset(), day = WorldCam.Look.day(), frozen = WorldCam.Look.frozenNight();
        return List.of(
                new Scene("hub", "overworld", 0, 0, 230, inferno, 6.5, new double[][]{{0.5, 250, 320, 0.5, 120, 0}, {0.5, 200, 230, 0.5, 112, 0}, {0.5, 165, 150, 0.5, 108, 0}},
                        "", "", true),
                new Scene("hub", "overworld", 0, 0, 230, inferno, 5, new double[][]{{-26, 112, 26, 0.5, 104, 0.5}, {-8, 108, 34, 0.5, 105, 0.5}, {14, 107, 30, 0.5, 106, 0.5}},
                        "ONE NETWORK", "five worlds, one forge", true),
                new Scene("survival", "overworld", 0, 0, 170, sunset, 5, new double[][]{{-40, 158, 60, 0.5, 152, 0}, {0.5, 170, 75, 0.5, 152, 0}, {40, 185, 60, 0.5, 150, 0}},
                        "SURVIVAL", "claims · crates · jobs · world bosses", false),
                new Scene("survival", "overworld", 0, -400, 110, sunset, 4.5, new double[][]{{-60, 190, -340, 0.5, 158, -400}, {0, 200, -330, 0.5, 158, -400}, {60, 190, -340, 0.5, 158, -400}},
                        "KING OF THE HILL", "hold the hill · win the crates", false),
                new Scene("survival", "overworld", 0, 600, 150, day, 4.5, new double[][]{{60, 175, 520, 0, 166, 600}, {20, 171, 545, 0, 166, 610}, {-25, 170, 560, 0, 166, 620}},
                        "THE SCRAPYARD", "PvP for the bold", false),
                new Scene("skyblock", "overworld", 0, 0, 150, day, 5, new double[][]{{80, 190, 90, 0.5, 150, 0}, {0.5, 200, 110, 0.5, 150, 0}, {-80, 190, 90, 0.5, 150, 0}},
                        "SKYBLOCK & ONEBLOCK", "start with nothing · build everything", false),
                new Scene("factions", "overworld", 0, 0, 130, frozen, 4.5, new double[][]{{90, 230, 90, 0.5, 192, 0}, {0, 240, 110, 0.5, 192, 0}, {-90, 230, 90, 0.5, 192, 0}},
                        "FACTIONS", "raid · claim · conquer", false),
                new Scene("arcade", "overworld", 0, 0, 90, sunset, 4.5, new double[][]{{-35, 118, 40, 0.5, 103, 0}, {0.5, 122, 48, 0.5, 103, 0}, {35, 118, 40, 0.5, 103, 0}},
                        "ARCADE", "BedWars · SkyWars · Duels · and more", false),
                new Scene("hub", "overworld", 0, 0, 230, inferno, 7, new double[][]{{150, 200, 40, 0.5, 110, 0}, {110, 215, 150, 0.5, 112, 0}, {0.5, 240, 255, 0.5, 118, 0}},
                        "END", "", true));
    }

    public static void main(String[] a) throws Exception {
        File jar = new File(a[0]);
        int from = a.length > 1 ? Integer.parseInt(a[1]) : 0, to = a.length > 2 ? Integer.parseInt(a[2]) : Integer.MAX_VALUE;
        File out = new File(a.length > 3 ? a[3] : "previews/trailer");
        int every = a.length > 4 ? Integer.parseInt(a[4]) : 1;   // >1: a quick look at every Nth frame
        out.mkdirs();
        BufferedImage logo = ImageIO.read(new File(Shots.NET, "website/assets/img/logo-full.png"));
        WorldCam cam = new WorldCam(jar);
        int frame = 0, dragonTick = 300;
        String loaded = null;
        WorldCam.World world = null;
        for (Scene s : scenes()) {
            int n = (int) Math.round(s.seconds * FPS);
            String key = s.server + "/" + s.dim + "/" + s.cx + "," + s.cz + "," + s.radius;
            boolean need = false;
            for (int i = 0; i < n; i++) if (frame + i >= from && frame + i <= to) need = true;
            if (need && !key.equals(loaded)) {
                world = cam.load(new File(Shots.NET, s.server + "/world/dimensions/minecraft/" + s.dim + "/region"), s.cx - s.radius, s.cz - s.radius, s.cx + s.radius, s.cz + s.radius);
                loaded = key;
                System.out.println("loaded " + key + ": " + world.blocks.size() + " chunks");
            }
            for (int i = 0; i < n; i++, frame++) {
                dragonTick += 1;
                if (frame < from || frame > to || frame % every != 0) continue;
                File f = new File(out, String.format("f%05d.png", frame));
                if (f.exists()) continue;
                double t = ease(i / (double) (n - 1));
                double[] p = along(s.path, t);
                long t0 = System.currentTimeMillis();
                BufferedImage img = cam.render(world, s.look, new WorldCam.Cam(p[0], p[1], p[2], p[3], p[4], p[5], 70), s.dragons ? dragons(dragonTick) : null, W, H, 2);
                overlay(img, s, i, n, logo);
                ImageIO.write(img, "png", f);
                System.out.printf("frame %d (%s) %d ms%n", frame, s.title.isEmpty() ? s.server : s.title, System.currentTimeMillis() - t0);
            }
        }
        System.out.println("total frames: " + frame);
    }

    static double ease(double t) { return t * t * (3 - 2 * t); }

    /** Smooth path through the keyframes (Catmull-Rom). */
    static double[] along(double[][] k, double t) {
        int segs = k.length - 1;
        double ft = Math.min(segs - 1e-9, t * segs);
        int i = (int) ft;
        double u = ft - i;
        double[] p0 = k[Math.max(0, i - 1)], p1 = k[i], p2 = k[i + 1], p3 = k[Math.min(segs, i + 2)];
        double[] r = new double[6];
        for (int c = 0; c < 6; c++)
            r[c] = 0.5 * (2 * p1[c] + (-p0[c] + p2[c]) * u + (2 * p0[c] - 5 * p1[c] + 4 * p2[c] - p3[c]) * u * u + (-p0[c] + 3 * p1[c] - 3 * p2[c] + p3[c]) * u * u * u);
        return r;
    }

    /** Letterbox bars, captions with fades, the opening title and the end card. */
    static void overlay(BufferedImage img, Scene s, int i, int n, BufferedImage logo) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        int bar = 72;
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, W, bar);
        g.fillRect(0, H - bar, W, bar);
        double sec = i / (double) FPS, len = n / (double) FPS;
        // scene fades: from black at the start of the film, a quick dip between scenes
        double dip = Math.min(1, Math.min(sec / 0.35, (len - sec) / 0.35));
        boolean first = s == scenes().getFirst();
        if (first) dip = Math.min(dip, sec / 1.5);
        if (dip < 1) { g.setColor(new Color(0, 0, 0, (int) (255 * (1 - Math.max(0, dip))))); g.fillRect(0, 0, W, H); }
        if (first) {   // opening title over the hub
            double a = fade(sec, 1.5, 2.3, len - 0.8, len);
            drawLogo(g, logo, W / 2, H / 2 - 10, 560, a);
            text(g, "A MINECRAFT SERVER NETWORK", W / 2, H / 2 + 92, 20, 6, new Color(230, 200, 170), a * 0.9);
        } else if (s.title.equals("END")) {
            double a = fade(sec, 2.0, 3.0, len + 9, len + 10);
            g.setColor(new Color(0, 0, 0, (int) (150 * a)));
            g.fillRect(0, bar, W, H - 2 * bar);
            drawLogo(g, logo, W / 2, H / 2 - 50, 600, a);
            text(g, "PLAY NOW", W / 2, H / 2 + 62, 26, 10, new Color(255, 196, 120), a);
            text(g, "blackforgemc.com", W / 2, H / 2 + 108, 40, 2, Color.WHITE, a);
            text(g, "Java Edition  ·  join the Discord at discord.gg/CChc5MfbD5", W / 2, H / 2 + 146, 18, 1, new Color(200, 200, 210), a * 0.85);
        } else if (!s.title.isEmpty()) {
            double a = fade(sec, 0.4, 1.0, len - 0.9, len - 0.3);
            text(g, s.title, 90, H - bar - 70, 46, 6, new Color(255, 214, 160), a, false);
            text(g, s.sub, 92, H - bar - 34, 22, 2, new Color(235, 235, 240), a * 0.9, false);
            g.setColor(new Color(255, 122, 26, (int) (230 * a)));
            g.fillRect(90, H - bar - 128, (int) (70 * a), 4);
        }
        g.dispose();
    }

    static double fade(double t, double in0, double in1, double out0, double out1) {
        if (t < in0 || t > out1) return 0;
        if (t < in1) return (t - in0) / (in1 - in0);
        if (t > out0) return 1 - (t - out0) / (out1 - out0);
        return 1;
    }

    static void drawLogo(Graphics2D g, BufferedImage logo, int cx, int cy, int width, double a) {
        if (logo == null || a <= 0) return;
        int h = logo.getHeight() * width / logo.getWidth();
        Composite old = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) Math.min(1, a)));
        g.drawImage(logo, cx - width / 2, cy - h / 2, width, h, null);
        g.setComposite(old);
    }

    static void text(Graphics2D g, String s, int x, int y, int size, int tracking, Color c, double a) { text(g, s, x, y, size, tracking, c, a, true); }

    static void text(Graphics2D g, String s, int x, int y, int size, int tracking, Color c, double a, boolean centre) {
        if (a <= 0 || s.isEmpty()) return;
        Font f = new Font("Segoe UI", Font.BOLD, size);
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        int w = 0;
        for (char ch : s.toCharArray()) w += fm.charWidth(ch) + tracking;
        int px = centre ? x - w / 2 : x;
        for (char ch : s.toCharArray()) {
            g.setColor(new Color(0, 0, 0, (int) (160 * Math.min(1, a))));
            g.drawString(String.valueOf(ch), px + 2, y + 2);
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) (255 * Math.min(1, a))));
            g.drawString(String.valueOf(ch), px, y);
            px += fm.charWidth(ch) + tracking;
        }
    }
}
