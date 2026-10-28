package net.alloymc.core.hub;

import net.alloymc.core.build.Canvas;

import java.util.List;

import static net.alloymc.core.hub.HubLayout.Y;

/**
 * Three dragon statues gliding in the sky around the hub, sculpted from a smooth shape so they look right at any heading.
 * The blocks are part of {@link HubBuilder}; the fire they breathe is particles played by {@link HubModule}.
 *
 * Shapes are written in dragon space: u points forward (towards the snout), v up, w out to the dragon's right.
 */
public final class Dragons {
    private Dragons() {}

    /** A dragon statue: body origin, heading in degrees (0 = +X, 90 = +Z) and whether it is cast in gold. */
    public record Dragon(double ox, double oy, double oz, double heading, boolean gold) {
        double cos() { return Math.cos(Math.toRadians(heading)); }
        double sin() { return Math.sin(Math.toRadians(heading)); }

        /** World position of the open mouth, where the fire comes from. */
        public double[] mouth() { return toWorld(MOUTH_U, MOUTH_V, 0); }

        /** Unit direction of the fire: straight ahead and angled down. */
        public double[] breath() {
            double p = Math.toRadians(BREATH_PITCH);
            return new double[]{cos() * Math.cos(p), -Math.sin(p), sin() * Math.cos(p)};
        }

        double[] toWorld(double u, double v, double w) {
            return new double[]{ox + u * cos() - w * sin(), oy + v, oz + u * sin() + w * cos()};
        }
    }

    /** Circling the island anticlockwise, side-on to spawn, clear of the BLACKFORGE sign in the north. */
    public static final List<Dragon> ALL = List.of(
            new Dragon(62.5, Y + 27, 0.5, 90, false),      // east, flying south
            new Dragon(0.5, Y + 34, 70.5, 180, true),      // south, over the arcade gate, flying west
            new Dragon(-62.5, Y + 25, 0.5, 270, false));   // west, flying north

    static final double MOUTH_U = 19.5, MOUTH_V = 4.8, BREATH_PITCH = 24;

    /** Just the dragons, for pasting into a hub that was built before they existed. */
    public static Canvas build() {
        Canvas c = new Canvas();
        addTo(c);
        return c;
    }

    static void addTo(Canvas c) {
        for (Dragon d : ALL) sculpt(c, d);
    }

    private static void sculpt(Canvas c, Dragon d) {
        int r = 42;
        int bx = (int) Math.floor(d.ox), bz = (int) Math.floor(d.oz), by = (int) Math.floor(d.oy);
        for (int x = bx - r; x <= bx + r; x++)
            for (int z = bz - r; z <= bz + r; z++) {
                double px = x + 0.5 - d.ox, pz = z + 0.5 - d.oz;
                double u = px * d.cos() + pz * d.sin(), w = -px * d.sin() + pz * d.cos();
                if (u < -34 || u > 22 || Math.abs(w) > 24) continue;
                for (int y = by - 9; y <= by + 16; y++) {
                    String b = block(u, y + 0.5 - d.oy, w, d.gold);
                    if (b != null && !c.isSet(x, y, z)) c.set(x, y, z, b);
                }
            }
    }

    // ------------------------------------------------------------------ shape

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
