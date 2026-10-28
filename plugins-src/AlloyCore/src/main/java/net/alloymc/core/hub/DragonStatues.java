package net.alloymc.core.hub;

import net.alloymc.core.build.Canvas;

import java.util.List;

import static net.alloymc.core.hub.HubLayout.Y;

/**
 * RETIRED: the hub dragons fly now ({@link DragonFlight}). This is the old block-statue version, kept only so a hub that
 * still has the statues in it can work out which blocks to clear (see HubModule.removeStatues).
 *
 * Three dragon statues gliding in the sky around the hub, sculpted from smooth shapes so they look right at any heading:
 * a blackstone dragon, a gold one, and a long wingless soul serpent that breathes blue fire.
 * The blocks are part of {@link HubBuilder}; the fire and the rest of the animation are played by HubModule.
 *
 * Shapes are written in dragon space: u points forward (towards the snout), v up, w out to the dragon's right.
 */
public final class DragonStatues {
    private DragonStatues() {}

    public enum Kind {
        //      mouth u, v, breath pitch, eye u, v, w,       eye block,                  blink block
        BLACK  (19.5, 4.8, 24, 15.6, 6.9, 1.35, "minecraft:shroomlight", "minecraft:polished_blackstone"),
        GOLD   (19.5, 4.8, 24, 15.6, 6.9, 1.35, "minecraft:shroomlight", "minecraft:gold_block"),
        SERPENT(20.6, -0.6, 14, 16.3, 1.4, 1.45, "minecraft:sea_lantern", "minecraft:dark_prismarine");

        final double mouthU, mouthV, breathPitch, eyeU, eyeV, eyeW;
        public final String eye, eyelid;

        Kind(double mouthU, double mouthV, double breathPitch, double eyeU, double eyeV, double eyeW, String eye, String eyelid) {
            this.mouthU = mouthU; this.mouthV = mouthV; this.breathPitch = breathPitch;
            this.eyeU = eyeU; this.eyeV = eyeV; this.eyeW = eyeW; this.eye = eye; this.eyelid = eyelid;
        }
    }

    /** A dragon statue: body origin, heading in degrees (0 = +X, 90 = +Z) and which design it is. */
    public record Dragon(double ox, double oy, double oz, double heading, Kind kind) {
        double cos() { return Math.cos(Math.toRadians(heading)); }
        double sin() { return Math.sin(Math.toRadians(heading)); }

        /** World position of the open mouth, where the fire comes from. */
        public double[] mouth() { return toWorld(kind.mouthU, kind.mouthV, 0); }

        /** World position of the nostrils, just above the mouth. */
        public double[] nose() { return toWorld(kind.mouthU, kind.mouthV + 1.4, 0); }

        /** World centres of the two eyes. */
        public double[][] eyes() {
            return new double[][]{toWorld(kind.eyeU, kind.eyeV, kind.eyeW), toWorld(kind.eyeU, kind.eyeV, -kind.eyeW)};
        }

        /** Points that trail embers in the wind: wing tips and tail for the winged dragons, spine humps for the serpent. */
        public double[][] trails() {
            if (kind == Kind.SERPENT) {
                double[] a = spine(0.02), b = spine(0.3), c = spine(0.55), e = spine(0.8);
                return new double[][]{toWorld(a[0], a[1], a[2]), toWorld(b[0], b[1] + 2.5, b[2]),
                        toWorld(c[0], c[1] + 2.5, c[2]), toWorld(e[0], e[1] + 2.5, e[2])};
            }
            return new double[][]{toWorld(-3.8, 9.4, 22.9), toWorld(-3.8, 9.4, -22.9), toWorld(-34, spineV(-31), spineW(-31))};
        }

        /** Unit forward direction. */
        public double[] forward() { return new double[]{cos(), 0, sin()}; }

        /** Unit direction of the fire, angled down and swung {@code sweep} degrees to the side. */
        public double[] breath(double sweep) {
            double p = Math.toRadians(kind.breathPitch), h = Math.toRadians(heading + sweep);
            return new double[]{Math.cos(h) * Math.cos(p), -Math.sin(p), Math.sin(h) * Math.cos(p)};
        }

        double[] toWorld(double u, double v, double w) {
            return new double[]{ox + u * cos() - w * sin(), oy + v, oz + u * sin() + w * cos()};
        }
    }

    /** Circling the island anticlockwise, side-on to spawn, clear of the BLACKFORGE sign in the north. */
    public static final List<Dragon> ALL = List.of(
            new Dragon(62.5, Y + 27, 0.5, 90, Kind.BLACK),      // east, flying south
            new Dragon(0.5, Y + 34, 70.5, 180, Kind.GOLD),      // south, over the arcade gate, flying west
            new Dragon(-62.5, Y + 29, 0.5, 270, Kind.SERPENT)); // west, flying north

    /** The first version's west dragon (a second blackstone one), cleared when upgrading a hub to the serpent. */
    public static final Dragon OLD_WEST = new Dragon(-62.5, Y + 25, 0.5, 270, Kind.BLACK);

    /** Just the dragons, for pasting into a hub that was built before they existed. */
    public static Canvas build() {
        Canvas c = new Canvas();
        addTo(c);
        return c;
    }

    /** One dragon on its own. */
    public static Canvas build(Dragon d) {
        Canvas c = new Canvas();
        sculpt(c, d);
        return c;
    }

    static void addTo(Canvas c) {
        for (Dragon d : ALL) sculpt(c, d);
    }

    private static void sculpt(Canvas c, Dragon d) {
        if (d.kind == Kind.SERPENT) serpentBody(c, d);
        int r = 48;
        int bx = (int) Math.floor(d.ox), bz = (int) Math.floor(d.oz), by = (int) Math.floor(d.oy);
        for (int x = bx - r; x <= bx + r; x++)
            for (int z = bz - r; z <= bz + r; z++) {
                double px = x + 0.5 - d.ox, pz = z + 0.5 - d.oz;
                double u = px * d.cos() + pz * d.sin(), w = -px * d.sin() + pz * d.cos();
                if (d.kind == Kind.SERPENT ? u < -30 || u > 23 || Math.abs(w) > 12 : u < -34 || u > 22 || Math.abs(w) > 24) continue;
                for (int y = by - 9; y <= by + 16; y++) {
                    double v = y + 0.5 - d.oy;
                    String b = d.kind == Kind.SERPENT ? serpentPart(u, v, w) : block(u, v, w, d.kind == Kind.GOLD);
                    if (b != null && !c.isSet(x, y, z)) c.set(x, y, z, b);
                }
            }
    }

    // ------------------------------------------------------------------ soul serpent

    private static final String S_HIDE = "minecraft:dark_prismarine", S_BAND = "minecraft:prismarine_bricks",
            S_BELLY = "minecraft:calcite", S_FIN = "minecraft:warped_wart_block", S_BONE = "minecraft:bone_block",
            S_GLOW = "minecraft:sea_lantern";
    private static final double S_TAIL = -46, S_LEN = 59;

    /** Spine point {u, v, w} at s (0 = tail tip, 1 = where the neck meets the head); it straightens out towards the head. */
    static double[] spine(double s) {
        double env = Math.min(1, (1 - s) / 0.15);
        return new double[]{S_TAIL + S_LEN * s,
                5.5 * Math.sin(2 * Math.PI * s + 0.8) * env,
                8 * Math.sin(2 * Math.PI * 1.25 * s) * env * (0.5 + 0.5 * (1 - s))};
    }

    private static double serpentRadius(double s) {
        if (s > 0.9) return 2.5 - (s - 0.9) * 7;
        return 0.5 + 2.0 * Math.pow(Math.min(1, s / 0.35), 0.8);
    }

    /** Stamps the coiling body as a chain of spheres, with a fin running along its back and a tuft on the tail. */
    private static void serpentBody(Canvas c, Dragon d) {
        for (double s = 0; s <= 1; s += 0.0015) {
            double[] p = spine(s);
            double r = serpentRadius(s);
            double[] wc = d.toWorld(p[0], p[1], p[2]);
            boolean band = Math.floorMod((int) Math.floor(s * S_LEN / 2.5), 2) == 0;
            int ri = (int) Math.ceil(r) + 1;
            for (int x = (int) Math.floor(wc[0]) - ri; x <= wc[0] + ri; x++)
                for (int y = (int) Math.floor(wc[1]) - ri; y <= wc[1] + ri; y++)
                    for (int z = (int) Math.floor(wc[2]) - ri; z <= wc[2] + ri; z++) {
                        double dx = x + 0.5 - wc[0], dy = y + 0.5 - wc[1], dz = z + 0.5 - wc[2];
                        if (dx * dx + dy * dy + dz * dz > r * r || c.isSet(x, y, z)) continue;
                        c.set(x, y, z, dy < -r * 0.45 ? S_BELLY : band ? S_HIDE : S_BAND);
                    }
            if (s > 0.06 && s < 0.93 && Math.floorMod((int) Math.floor(s * S_LEN / 1.5), 2) == 0)
                for (double k = 0.4; k <= 0.4 + r * 0.6; k += 0.4) {
                    int x = (int) Math.floor(wc[0]), y = (int) Math.floor(wc[1] + r + k), z = (int) Math.floor(wc[2]);
                    if (!c.isSet(x, y, z)) c.set(x, y, z, S_FIN);
                }
        }
        double[] t = spine(0);
        double[] tw = d.toWorld(t[0] - 1.2, t[1], t[2]);
        for (int x = (int) tw[0] - 2; x <= tw[0] + 2; x++)
            for (int y = (int) tw[1] - 2; y <= tw[1] + 2; y++)
                for (int z = (int) tw[2] - 2; z <= tw[2] + 2; z++)
                    if (Math.pow(x + 0.5 - tw[0], 2) + Math.pow(y + 0.5 - tw[1], 2) + Math.pow(z + 0.5 - tw[2], 2) <= 2.6 && !c.isSet(x, y, z))
                        c.set(x, y, z, S_FIN);
    }

    /** The serpent's head (long snout, open jaw, antlers, whiskers) and its four small clawed legs. */
    private static String serpentPart(double u, double v, double w) {
        double aw = Math.abs(w);
        if (dist(u, v, aw, 16.3, 1.4, 1.45) < 0.75) return S_GLOW;                                // eyes
        if (dist(u, v, w, 18.0, -0.6, 0) < 0.8) return S_GLOW;                                    // soul fire in the mouth
        if (ell(u - 15, v - 0.6, w, 3.2, 1.7, 1.8)) return v > 1.7 ? S_BAND : S_HIDE;
        if (capsule(u, v, w, 16, 0.5, 0, 20.6, 0.2, 0, 1.15, 0.85)) return S_HIDE;
        if (capsule(u, v, w, 15.5, -0.7, 0, 19.9, -1.9, 0, 0.75, 0.55)) return S_BELLY;
        if (capsule(u, v, aw, 14, 2, 1, 10.5, 4.6, 2.1, 0.55, 0.4)) return S_BONE;                // antlers
        if (capsule(u, v, aw, 12.2, 3.4, 1.6, 12.8, 5.8, 2.8, 0.45, 0.35)) return S_BONE;
        if (capsule(u, v, aw, 12.5, 1.6, 1.0, 8.5, 2.8, 2.4, 0.6, 0.4)) return S_FIN;             // mane
        if (capsule(u, v, aw, 19.5, 0.1, 0.9, 15, -2, 3.5, 0.5, 0.45)) return S_FIN;              // whiskers
        if (capsule(u, v, aw, 15, -2, 3.5, 10, -2.6, 5, 0.45, 0.4)) return S_FIN;
        for (double s : new double[]{0.42, 0.72}) {                                                // legs
            double[] p = spine(s);
            double sw = p[2], side = w - sw;
            double lw = Math.abs(side);
            if (capsule(u, v, lw, p[0], p[1] - 1, 1.2, p[0] + 1.5, p[1] - 4, 2.4, 0.75, 0.55)) return S_HIDE;
            if (capsule(u, v, lw, p[0] + 1.5, p[1] - 4, 2.4, p[0] + 2.8, p[1] - 4.3, 2.4, 0.45, 0.35)) return S_BONE;
        }
        return null;
    }

    // ------------------------------------------------------------------ winged dragons

    private static String block(double u, double v, double w, boolean gold) {
        String hide = gold ? "minecraft:gold_block" : "minecraft:polished_blackstone";
        String hide2 = gold ? "minecraft:raw_gold_block" : "minecraft:blackstone";
        String belly = gold ? "minecraft:yellow_terracotta" : "minecraft:gilded_blackstone";
        String bone = gold ? "minecraft:netherite_block" : "minecraft:polished_blackstone_bricks";
        String skin = gold ? "minecraft:orange_terracotta" : "minecraft:red_nether_bricks";
        String horn = gold ? "minecraft:netherite_block" : "minecraft:gold_block";
        String ridge = gold ? "minecraft:netherite_block" : "minecraft:magma_block";

        // eyes and the fire glowing in the open mouth
        if (dist(u, v, Math.abs(w), 15.6, 6.9, 1.35) < 0.75) return "minecraft:shroomlight";
        if (dist(u, v, w, 17.2, 5.0, 0) < 0.8) return "minecraft:shroomlight";

        // head, snout, open jaw, horns
        if (ell(u - 14, v - 6.2, w, 2.8, 1.9, 1.7)) return v > 7.3 ? hide2 : hide;
        if (capsule(u, v, w, 15, 6.4, 0, 19.8, 5.9, 0, 1.25, 0.85)) return hide;
        if (capsule(u, v, w, 14.4, 5.0, 0, 19.2, 3.7, 0, 0.85, 0.6)) return belly;
        if (capsule(u, v, Math.abs(w), 13, 7.4, 1.0, 9.0, 9.6, 1.9, 0.6, 0.3)) return horn;

        // neck
        if (capsule(u, v, w, 4.5, 1.2, 0, 12.5, 5.6, 0, 2.2, 1.6)) return hide;

        // legs tucked under, gold-tipped claws
        double aw = Math.abs(w);
        if (capsule(u, v, aw, 2, -1.2, 2.2, 4.5, -3.8, 2.5, 0.95, 0.7)) return hide2;
        if (capsule(u, v, aw, 4.5, -3.8, 2.5, 6, -4.3, 2.5, 0.55, 0.4)) return horn;
        if (capsule(u, v, aw, -6, -1.0, 2.5, -4, -4.5, 2.8, 1.15, 0.85)) return hide2;
        if (capsule(u, v, aw, -4, -4.5, 2.8, -0.5, -5.4, 2.8, 0.8, 0.55)) return hide2;
        if (capsule(u, v, aw, -0.5, -5.4, 2.8, 1.2, -5.6, 2.8, 0.5, 0.4)) return horn;

        // body and tail along a swaying spine, with a ridge of spikes and a spade on the tail
        if (u >= -31 && u <= 6) {
            double cv = spineV(u), cw = spineW(u), rad = radius(u);
            double dv = v - cv, dw = w - cw;
            if (dv * dv + dw * dw <= rad * rad) return dv < -rad * 0.45 ? belly : ((int) Math.floor(u * 0.5) % 2 == 0 ? hide : hide2);
            if (Math.abs(dw) < 0.55 && dv > rad - 0.4 && dv < rad + 0.6 + rad * 0.3 && Math.floorMod((int) Math.floor(u), 3) == 0) return ridge;
        }
        if (u < -29.5 && u > -35) {
            double t = (u + 35) / 5.5;                       // 0 at the spade tip, 1 where it meets the tail
            double half = 2.6 * Math.sin(Math.PI * Math.min(1, t * 1.15)) * (t < 0.85 ? 1 : (1 - t) / 0.15);
            if (Math.abs(w - spineW(-30)) <= half && Math.abs(v - spineV(-30)) <= 0.55) return horn;
        }

        // wings
        double a = aw - 2.4;
        if (a >= 0 && a <= 21) {
            double vw = wingV(a), le = leadingEdge(a);
            if (u <= le + 0.3 && u >= le - 1.3 && Math.abs(v - vw) <= 1.0) return bone;                  // arm along the front
            if (Math.abs(v - vw) <= 0.6) {
                for (double[] f : FINGERS)
                    if (segDist(a, u, WRIST_A, leadingEdge(WRIST_A), f[0], f[1]) < 0.62) return bone;    // fingers
                if (u <= le && u >= trailingEdge(a)) return skin;                                         // membrane
            }
        }
        return null;
    }

    private static double spineV(double u) {
        if (u >= -4) return 0.15 * (u + 4) * 0.2;
        double t = (-4 - u) / 27;                           // 0 at the hips, 1 at the tail tip
        return -4.2 * Math.sin(Math.PI * 0.6 * t) + 2.4 * t * t;
    }

    private static double spineW(double u) {
        if (u >= -6) return 0;
        double t = (-6 - u) / 25;
        return 4.5 * Math.sin(Math.PI * 1.15 * t) * t;
    }

    private static double radius(double u) {
        if (u > 3) return 3.1 - (u - 3) * 0.3;
        if (u >= -7) return 3.2;
        double t = (u + 31) / 24;                           // 0 at the tail tip, 1 at the hips
        return 0.55 + 2.65 * Math.pow(Math.max(0, t), 1.25);
    }

    private static final double WRIST_A = 9;
    /** Finger tips in (out from the shoulder, along the body), the scalloped back edge runs between them. */
    private static final double[][] FINGERS = {{8.5, -12}, {14, -12.5}, {18.8, -8.5}};
    private static final double[][] TRAILING = {{0, -5}, {8.5, -12}, {14, -12.5}, {18.8, -8.5}, {21, -3.6}};

    private static double wingV(double a) {
        return 2.0 + a * 0.24 + (a > WRIST_A ? (a - WRIST_A) * 0.22 : 0);
    }

    private static double leadingEdge(double a) {
        return a < WRIST_A ? 3 + a * 0.35 : 3 + WRIST_A * 0.35 - (a - WRIST_A) * 0.92;
    }

    private static double trailingEdge(double a) {
        for (int i = 0; i + 1 < TRAILING.length; i++) {
            double[] p = TRAILING[i], q = TRAILING[i + 1];
            if (a >= p[0] && a <= q[0]) {
                double t = (a - p[0]) / (q[0] - p[0]);
                return p[1] + (q[1] - p[1]) * t + 2.2 * Math.sin(Math.PI * t);   // each scallop bows towards the arm
            }
        }
        return Double.MAX_VALUE;
    }

    // ------------------------------------------------------------------ geometry

    private static double dist(double u, double v, double w, double cu, double cv, double cw) {
        return Math.sqrt((u - cu) * (u - cu) + (v - cv) * (v - cv) + (w - cw) * (w - cw));
    }

    private static boolean ell(double du, double dv, double dw, double ru, double rv, double rw) {
        return du * du / (ru * ru) + dv * dv / (rv * rv) + dw * dw / (rw * rw) <= 1;
    }

    /** Tapered capsule from a (radius ra) to b (radius rb). */
    private static boolean capsule(double u, double v, double w, double au, double av, double aw,
                                   double bu, double bv, double bw, double ra, double rb) {
        double eu = bu - au, ev = bv - av, ew = bw - aw;
        double t = ((u - au) * eu + (v - av) * ev + (w - aw) * ew) / (eu * eu + ev * ev + ew * ew);
        t = Math.max(0, Math.min(1, t));
        double r = ra + (rb - ra) * t;
        return dist(u, v, w, au + eu * t, av + ev * t, aw + ew * t) <= r;
    }

    private static double segDist(double x, double y, double ax, double ay, double bx, double by) {
        double ex = bx - ax, ey = by - ay;
        double t = Math.max(0, Math.min(1, ((x - ax) * ex + (y - ay) * ey) / (ex * ex + ey * ey)));
        return Math.hypot(x - ax - ex * t, y - ay - ey * t);
    }
}
