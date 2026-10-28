package net.alloymc.core.hub;

import java.util.*;

import static net.alloymc.core.hub.HubLayout.Y;

/**
 * One living hub dragon: where it flies and what pose it is in, as plain maths (no Bukkit) so it can be previewed and
 * checked offline. {@link DragonAnimator} turns the pose into block displays and plays the fire and sounds.
 *
 * Every dragon has its own random temperament (speed, agility, wing beat) and wanders between random waypoints around
 * the island: swooping low, climbing, gliding and banking into its turns. The body follows the path the head took, so
 * the neck and tail curve through every turn. Breath, roars, fireballs and blinks come at random, never on a timer.
 */
public final class DragonFlight {
    public enum Kind { BLACK, SERPENT }

    /** A box drawn by one block display: its corner, its three unit axes (right, up, forward) and its size along each. */
    public record Piece(String block, int light, double[] corner, double[] r, double[] u, double[] f, double sx, double sy, double sz) {}

    public final Kind kind;
    private final Random rnd;
    private final double cruise, agility, flapRate, slither;

    private double x, y, z, yaw, pitch, bank, speed, tx, ty, tz;
    private final ArrayDeque<double[]> path = new ArrayDeque<>();   // newest first: x, y, z, bank
    private long age;

    private double flapPhase, flapAmp, fold, jaw, sweepPhase, sweepAmp, sweepRate, headTilt;
    private int glide, breathLeft, breathCooldown, roarTicks, roarCooldown, fireCooldown, volley, volleyGap, blink, blinkCooldown;

    /** What happened this tick, for the animator: a roar, a wing downstroke, a fireball to launch. */
    public boolean roared, flapped, fireball;

    private List<Piece> pose = List.of();
    private double[] mouth = new double[3], breathDir = {1, 0, 0}, nose = new double[3], forward = {1, 0, 0};
    private double[][] trails = new double[0][];

    public DragonFlight(Kind kind, long seed, double startAngle) {
        this.kind = kind;
        this.rnd = new Random(seed);
        boolean serpent = kind == Kind.SERPENT;
        cruise = serpent ? 0.32 + rnd.nextDouble() * 0.1 : 0.48 + rnd.nextDouble() * 0.16;
        agility = serpent ? 0.03 + rnd.nextDouble() * 0.012 : 0.032 + rnd.nextDouble() * 0.018;
        flapRate = 0.16 + rnd.nextDouble() * 0.07;
        slither = 0.06 + rnd.nextDouble() * 0.04;
        speed = cruise;
        // start somewhere in the south half, so the straight starting trail never runs through the BLACKFORGE sign
        startAngle = 0.6 + Math.floorMod((long) (startAngle * 1000), 1942L) / 1000.0;
        x = 0.5 + Math.cos(startAngle) * 70;
        z = 0.5 + Math.sin(startAngle) * 70;
        y = Y + 26 + rnd.nextDouble() * 14;
        yaw = startAngle + Math.PI / 2;
        // a trail behind it along the same circle, so the body has somewhere to be on the first tick
        for (int i = 0; i < 400; i++) {
            double a = startAngle - i * speed / 70;
            path.addLast(new double[]{0.5 + Math.cos(a) * 70, y, 0.5 + Math.sin(a) * 70, 0});
        }
        breathCooldown = 60 + rnd.nextInt(300);
        roarCooldown = 300 + rnd.nextInt(600);
        fireCooldown = 200 + rnd.nextInt(500);
        blinkCooldown = rnd.nextInt(120);
        sweepPhase = rnd.nextDouble() * 6;
        pickTarget();
        buildPose();
    }

    public List<Piece> pose() { return pose; }
    public double[] mouth() { return mouth; }
    public double[] nose() { return nose; }
    public double[] forward() { return forward; }
    public double speed() { return speed; }
    public double[][] trails() { return trails; }
    public boolean breathing() { return breathLeft > 0; }
    public double breathStrength() { return Math.min(1, Math.min(breathLeft, breathTotal - breathLeft + 1) / 8.0); }
    private int breathTotal = 1;

    /** Fire direction: the way the head faces, swung by the sweep and angled down. */
    public double[] breathDir() { return breathDir; }

    /** Head centre, for sounds. */
    public double[] head() { return new double[]{x, y, z}; }

    // ------------------------------------------------------------------ flight

    /** The other dragons in the sky, so they keep their distance from each other. */
    private List<DragonFlight> others = List.of();
    public void setOthers(List<DragonFlight> all) { others = all.stream().filter(o -> o != this).toList(); }

    /**
     * Below LOW a dragon is "low": it has to stay out in the ring between the floating islands and the castle walls
     * (CastleBuilder.INNER), sweeping along the sides of the hub. Wing tips reach about 22 blocks either side.
     */
    private static final double LOW = Y + 30, OUTER = 108;

    private void pickTarget() {
        boolean low = y < LOW;
        for (int tries = 0; tries < 80; tries++) {
            double a = rnd.nextDouble() * Math.PI * 2, r, py;
            // now and then dive down past the edge and sweep along the cliffs, just outside the island
            if ((low ? rnd.nextDouble() < 0.55 : rnd.nextDouble() < 0.3)) { r = OUTER + rnd.nextDouble() * 4; py = Y - 14 + rnd.nextDouble() * 20; }
            else { r = 24 + rnd.nextDouble() * 66; py = (r < 60 ? Y + 34 : Y + 32) + rnd.nextDouble() * 20; }
            double px = 0.5 + Math.cos(a) * r, pz = 0.5 + Math.sin(a) * r;
            if (pz < -10) continue;                                  // keep clear of the BLACKFORGE sign in the north
            if (Math.hypot(px - x, pz - z) < 35) continue;           // somewhere worth flying to
            boolean crowded = false;                                 // and well away from where the others are and are going
            for (DragonFlight o : others) crowded |= Math.hypot(px - o.x, pz - o.z) < 60 || Math.hypot(px - o.tx, pz - o.tz) < 60;
            if (crowded && tries < 60) continue;
            // low passes stay low around the outside: from low, only climb back in once out past the islands
            if (low && py < LOW && Math.hypot(x, z) < OUTER - 4) continue;
            tx = px; ty = py; tz = pz;
            return;
        }
        tx = 0.5; ty = Y + 44; tz = 40.5;
    }

    public void step() {
        age++;
        roared = flapped = fireball = false;
        double dx = tx - x, dy = ty - y, dz = tz - z, flat = Math.hypot(dx, dz);
        if (flat < 12) pickTarget();
        double rad = Math.hypot(x - 0.5, z - 0.5);
        // too far out (or heading for the sign): turn back towards the middle
        if (rad > OUTER + 6) { tx = 0.5; tz = 30.5; ty = Math.max(ty, Y + 34); }
        for (DragonFlight o : others)                            // another dragon too close: go somewhere else
            if (Math.hypot(x - o.x, z - o.z) < 40 && age % 40 == 0) pickTarget();
        dx = tx - x; dy = ty - y; dz = tz - z; flat = Math.hypot(dx, dz);
        double wantYaw = Math.atan2(dz, dx);
        // low and not yet out past the floating islands: bank outwards and climb, never through them
        boolean tooLowInside = y < LOW && rad < OUTER - 4;
        if (tooLowInside) { wantYaw = Math.atan2(Math.max(z - 0.5, 0), x - 0.5); dy = Math.max(dy, 6); }
        // about to leave the flight ring (the castle walls are out there): curve along it and back in, in good time
        double[] ahead = dir(yaw, 0);
        if (Math.hypot(x + ahead[0] * 25 - 0.5, z + ahead[2] * 25 - 0.5) > OUTER + 6) {
            double ang = Math.atan2(z - 0.5, x - 0.5), left = ang + Math.PI / 2, right = ang - Math.PI / 2;
            wantYaw = Math.abs(wrap(left - yaw)) < Math.abs(wrap(right - yaw)) ? left + 0.35 : right - 0.35;
            if (rad > OUTER + 12) wantYaw = ang + Math.PI;   // already past it: straight back in
        }
        // the BLACKFORGE sign hangs over the north edge: if the next 25 blocks of flight would reach it, turn south now
        if (z + ahead[2] * 25 < -40 || z < -40) { wantYaw = Math.atan2(1, -0.3 * Math.signum(x)); tx = x * 0.5; tz = 30.5; }
        if (kind == Kind.SERPENT) wantYaw += 0.45 * Math.sin(age * slither);     // slithering through the air
        double agil = rad > OUTER + 6 ? agility * 2.5 : agility;   // pull hard back into the ring before the castle walls
        double turn = clamp(wrap(wantYaw - yaw), -agil, agil);
        yaw += turn;
        double wantPitch = clamp(Math.atan2(dy, Math.max(flat, 1)), -0.42, 0.38);
        if (kind == Kind.SERPENT) wantPitch += 0.12 * Math.sin(age * slither * 0.7 + 1);
        pitch += clamp(wantPitch - pitch, -0.015, 0.015);
        bank += (clamp(turn * 14, -0.55, 0.55) - bank) * 0.07;
        speed += (cruise - pitch * 0.45 - speed) * 0.03;                         // faster in a dive, slower climbing
        double[] f = dir(yaw, pitch);
        x += f[0] * speed; y += f[1] * speed; z += f[2] * speed;
        if (y < Y - 16) y = Y - 16;
        // hard wall: never further out than the edge of the flight ring, whatever the steering was doing
        double out = Math.hypot(x - 0.5, z - 0.5);
        if (out > OUTER + 14) { x = 0.5 + (x - 0.5) * (OUTER + 14) / out; z = 0.5 + (z - 0.5) * (OUTER + 14) / out; }
        // hard floor over the island and its floating islands, in case a dive comes in too steep
        if (Math.hypot(x - 0.5, z - 0.5) < OUTER - 10 && y < LOW + 2) y = LOW + 2;
        path.addFirst(new double[]{x, y, z, bank});
        if (path.size() > 400) path.removeLast();
        behave();
        buildPose();
    }

    private void behave() {
        boolean climbing = pitch > 0.08, diving = pitch < -0.12;
        if (kind != Kind.SERPENT) {
            if (glide > 0) glide--;
            else if (!climbing && rnd.nextDouble() < 0.008) glide = 40 + rnd.nextInt(140);
            double wantAmp = diving ? 0.08 : climbing ? 1.0 : glide > 0 ? 0.1 : 0.65 + 0.2 * Math.sin(age * 0.013);
            flapAmp += (wantAmp - flapAmp) * 0.05;
            double before = Math.sin(flapPhase);
            flapPhase += flapRate * (climbing ? 1.25 : 1) * (0.9 + 0.2 * rnd.nextDouble());
            if (before > 0 && Math.sin(flapPhase) <= 0 && flapAmp > 0.45) flapped = true;
            fold += ((diving ? 1 : 0) - fold) * 0.04;
        }
        if (breathLeft > 0) {
            if (--breathLeft == 0) breathCooldown = 140 + rnd.nextInt(420);
        } else if (--breathCooldown <= 0) {
            breathTotal = breathLeft = 25 + rnd.nextInt(65);
            sweepAmp = 0.15 + rnd.nextDouble() * 0.5;
            sweepRate = 0.05 + rnd.nextDouble() * 0.14;
            if (rnd.nextDouble() < 0.3) { roared = true; roarTicks = 26; }
        }
        sweepPhase += sweepRate;
        if (--roarCooldown <= 0) {
            roarCooldown = 400 + rnd.nextInt(900);
            if (breathLeft == 0) { roared = true; roarTicks = 30; }
        }
        if (--fireCooldown <= 0) { fireCooldown = 260 + rnd.nextInt(700); volley = 1 + rnd.nextInt(3); volleyGap = 0; }
        if (volley > 0 && breathLeft == 0 && --volleyGap <= 0) { fireball = true; volley--; volleyGap = 7 + rnd.nextInt(10); }
        double wantJaw = roarTicks > 0 ? 1 : breathLeft > 0 ? 0.75 : volleyGap > 0 && volley >= 0 && fireball ? 1 : 0.06;
        jaw += (wantJaw - jaw) * 0.3;
        double wantTilt = roarTicks > 0 ? 0.45 : breathLeft > 0 ? -0.25 : 0.08 * Math.sin(age * 0.03);
        headTilt += (wantTilt - headTilt) * 0.15;
        if (roarTicks > 0) roarTicks--;
        if (blink > 0) blink--;
        else if (--blinkCooldown <= 0) { blink = 4; blinkCooldown = rnd.nextDouble() < 0.2 ? 7 : 50 + rnd.nextInt(160); }
    }

    // ------------------------------------------------------------------ pose

    /** Frame (position, right, up, forward) on the flown path s blocks behind the head. */
    private double[][] frame(double[][] spine, double s) {
        int i = (int) Math.floor(s);
        double t = s - i;
        double[] p = lerp(spine[Math.min(i, spine.length - 1)], spine[Math.min(i + 1, spine.length - 1)], t);
        double[] a = spine[Math.max(0, i - 1)], b = spine[Math.min(spine.length - 1, i + 2)];
        double[] f = norm(sub(a, b));
        if (i == 0 || len(sub(a, b)) < 1e-3) f = dir(yaw, pitch);
        double[] up = norm(sub(new double[]{0, 1, 0}, mul(f, f[1])));
        double[] r0 = cross(up, f);
        double bk = p[3];
        double[] u = norm(sub(mul(up, Math.cos(bk)), mul(r0, Math.sin(bk))));
        double[] r = cross(u, f);
        return new double[][]{p, r, u, f};
    }

    /** Points along the path every block, from the head back. */
    private double[][] spine(int n) {
        double[][] pts = new double[n + 1][];
        Iterator<double[]> it = path.iterator();
        double[] prev = it.next();
        pts[0] = prev;
        double acc = 0;
        int k = 1;
        while (k <= n && it.hasNext()) {
            double[] cur = it.next();
            double seg = len(sub(prev, cur));
            while (k <= n && seg > 1e-9 && acc + seg >= k) { pts[k] = lerp(prev, cur, (k - acc) / seg); k++; }
            acc += seg;
            prev = cur;
        }
        while (k <= n) { pts[k] = pts[k - 1]; k++; }
        return pts;
    }

    private void buildPose() {
        List<Piece> out = new ArrayList<>(64);
        double[][] sp = spine(kind == Kind.SERPENT ? 62 : 48);
        if (kind == Kind.SERPENT) serpent(out, sp); else winged(out, sp);
        pose = out;
    }

    private record Look(String hide, String hide2, String belly, String bone, String skin, String horn, String ridge, String eye) {}

    private static final Look BLACK = new Look("minecraft:polished_blackstone", "minecraft:blackstone", "minecraft:gilded_blackstone",
            "minecraft:polished_blackstone_bricks", "minecraft:red_nether_bricks", "minecraft:gold_block", "minecraft:magma_block", "minecraft:shroomlight");
    private static final int BODY_LIGHT = 9;

    /** Head shared by every dragon: skull, snout, hinged jaw, glowing eyes that blink, and fire in the mouth. */
    private double[][] head(List<Piece> out, double[][] sp, String hide, String belly, String eye, String glow, double scale) {
        double[][] h = frame(sp, 0);
        // tilt the head up for a roar, down to aim the fire
        double c = Math.cos(headTilt), s = Math.sin(headTilt);
        double[] f = norm(add(mul(h[3], c), mul(h[2], s))), u = norm(sub(mul(h[2], c), mul(h[3], s))), r = h[1];
        double[] p = h[0];
        box(out, hide, BODY_LIGHT, at(p, r, u, f, 0, 0.2, 0), r, u, f, 2.6 * scale, 2.0 * scale, 3.4 * scale);
        box(out, hide, BODY_LIGHT, at(p, r, u, f, 0, 0.25 * scale, 2.9 * scale), r, u, f, 1.8 * scale, 1.1 * scale, 3.2 * scale);
        double ja = jaw * 0.65;
        double[] jf = norm(sub(mul(f, Math.cos(ja)), mul(u, Math.sin(ja)))), ju = norm(add(mul(u, Math.cos(ja)), mul(f, Math.sin(ja))));
        double[] hinge = at(p, r, u, f, 0, -0.6 * scale, 1.0 * scale);
        box(out, belly, BODY_LIGHT, add(add(hinge, mul(jf, 1.7 * scale)), mul(ju, -0.25 * scale)), r, ju, jf, 1.5 * scale, 0.5 * scale, 3.4 * scale);
        String e = blink > 0 ? hide : eye;
        for (int side : new int[]{-1, 1})
            box(out, e, blink > 0 ? BODY_LIGHT : 15, at(p, r, u, f, side * 1.2 * scale, 0.55 * scale, 1.0 * scale), r, u, f, 0.45, 0.45, 0.45);
        double g = jaw > 0.3 ? 0.9 * scale : 0.01;
        box(out, glow, 15, at(p, r, u, f, 0, -0.35 * scale, 2.6 * scale), r, u, f, g, g, g);
        double sw = sweepAmp * Math.sin(sweepPhase);
        double[] side = r;
        mouth = at(p, r, u, f, 0, -0.3 * scale, 4.7 * scale);
        nose = at(p, r, u, f, 0, 0.7 * scale, 4.4 * scale);
        breathDir = norm(add(add(mul(f, Math.cos(sw)), mul(side, Math.sin(sw))), mul(u, -0.3)));
        forward = f;
        return new double[][]{p, r, u, f};
    }

    private void winged(List<Piece> out, double[][] sp) {
        Look k = BLACK;
        double[][] h = head(out, sp, k.hide, k.belly, k.eye, "minecraft:shroomlight", 1);
        for (int side : new int[]{-1, 1}) {                                                       // horns
            double[] base = at(h[0], h[1], h[2], h[3], side * 0.75, 0.9, -1.0);
            double[] d = norm(add(add(mul(h[3], -0.85), mul(h[2], 0.5)), mul(h[1], side * 0.22)));
            seg(out, k.horn, BODY_LIGHT, add(base, mul(d, 2.8)), base, 0.45, 0.45, h[2]);
        }
        // neck, body and tail along the path
        double[] stations = {1.5, 4, 6.5, 9, 12, 15, 18, 21, 23.4, 25.8, 28.2, 30.6, 33, 35.4, 37.8, 40.2, 42.6, 45};
        double[] widths = {1.3, 1.5, 1.7, 2.0, 2.3, 2.2, 1.9, 1.6, 1.45, 1.3, 1.15, 1.0, 0.85, 0.72, 0.6, 0.5, 0.4};
        for (int i = 0; i + 1 < stations.length; i++) {
            double[][] a = frame(sp, stations[i]), b = frame(sp, stations[i + 1]);
            double w = widths[i] * 2;
            seg(out, i % 2 == 0 ? k.hide : k.hide2, BODY_LIGHT, a[0], b[0], w, w * 0.85, a[2]);
        }
        double[][] tip = frame(sp, 45);
        box(out, k.horn, BODY_LIGHT, at(tip[0], tip[1], tip[2], tip[3], 0, 0, -1.2), tip[1], tip[2], tip[3], 2.6, 0.25, 2.6);
        for (double s : new double[]{3, 5.5, 8, 11, 14, 17, 20, 24, 28, 32}) {                   // spikes down the back
            double[][] fr = frame(sp, s);
            double rad = s < 9 ? 0.8 : s < 21 ? 1.15 : 0.9 - (s - 21) * 0.02;
            double[] fu = norm(add(fr[3], fr[2])), uu = norm(sub(fr[2], fr[3]));
            box(out, k.ridge, 12, add(fr[0], mul(fr[2], rad + 0.25)), fr[1], uu, fu, 0.55, 0.7, 0.7);
        }
        for (double s : new double[]{11, 19}) {                                                   // legs tucked back, claws
            double[][] fr = frame(sp, s);
            for (int side : new int[]{-1, 1}) {
                double[] hip = at(fr[0], fr[1], fr[2], fr[3], side * 1.3, -1.2, 0);
                double swing = 0.15 * Math.sin(age * 0.05 + side + s);
                double[] d = norm(add(mul(fr[2], -0.6), mul(fr[3], -0.8 + swing)));
                double[] foot = add(hip, mul(d, s < 15 ? 2.4 : 3.0));
                seg(out, k.hide2, BODY_LIGHT, hip, foot, 0.8, 0.8, fr[2]);
                box(out, k.horn, BODY_LIGHT, foot, fr[1], fr[2], fr[3], 0.6, 0.5, 0.8);
            }
        }
        wings(out, sp, k);
    }

    private void wings(List<Piece> out, double[][] sp, Look k) {
        double[][] ch = frame(sp, 10), back = frame(sp, 21);
        List<double[]> tips = new ArrayList<>();
        for (int side : new int[]{-1, 1}) {
            double[] o = mul(ch[1], side), f = ch[3], u = ch[2];
            double[] sh = add(add(ch[0], mul(u, 1.1)), mul(o, 1.5));
            double phi = flapAmp * 0.85 * Math.sin(flapPhase) + 0.18;
            double phi2 = phi + flapAmp * 0.35 * Math.sin(flapPhase - 0.8);
            double[] arm = norm(add(add(mul(o, Math.cos(phi)), mul(u, Math.sin(phi))), mul(f, 0.15 - fold * 0.6)));
            double[] elbow = add(sh, mul(arm, 6));
            double[] outUp = norm(add(mul(o, Math.cos(phi2)), mul(u, Math.sin(phi2))));
            double[] fore = norm(sub(outUp, mul(f, 0.3 + fold * 0.9)));
            double[] wrist = add(elbow, mul(fore, 6));
            seg(out, k.bone, BODY_LIGHT, sh, elbow, 0.75, 0.75, u);
            seg(out, k.bone, BODY_LIGHT, elbow, wrist, 0.6, 0.6, u);
            double[] backs = {0.35 + fold * 0.8, 0.95 + fold * 0.8, 1.9 + fold * 0.6}, lens = {9, 9.5, 7.5};
            double[][] fd = new double[3][], tp = new double[3][];
            for (int i = 0; i < 3; i++) {
                fd[i] = norm(sub(outUp, mul(f, backs[i])));
                tp[i] = add(wrist, mul(fd[i], lens[i]));
                seg(out, k.bone, BODY_LIGHT, wrist, tp[i], 0.38, 0.38, u);
            }
            double[] root = add(back[0], mul(o, 1.4));
            panel(out, k.skin, wrist, fd[0], lens[0], tp[1], 0.7);
            panel(out, k.skin, wrist, fd[1], lens[1], tp[2], 0.75);
            panel(out, k.skin, wrist, fd[2], lens[2], root, 0.85);
            panel(out, k.skin, sh, norm(sub(wrist, sh)), len(sub(wrist, sh)), root, 0.9);
            tips.add(tp[0]);
        }
        double[][] tail = frame(sp, 45);
        tips.add(tail[0]);
        trails = tips.toArray(new double[0][]);
    }

    private static final String S_HIDE = "minecraft:dark_prismarine", S_BAND = "minecraft:prismarine_bricks",
            S_BELLY = "minecraft:calcite", S_FIN = "minecraft:warped_wart_block", S_BONE = "minecraft:bone_block",
            S_GLOW = "minecraft:sea_lantern";

    private void serpent(List<Piece> out, double[][] sp) {
        double[][] h = head(out, sp, S_HIDE, S_BELLY, S_GLOW, S_GLOW, 1.1);
        double[] p = h[0], r = h[1], u = h[2], f = h[3];
        for (int side : new int[]{-1, 1}) {
            double[] base = at(p, r, u, f, side * 0.9, 1.0, -0.6);                                // antlers with a branch
            double[] d = norm(add(add(mul(f, -0.75), mul(u, 0.65)), mul(r, side * 0.3)));
            double[] top = add(base, mul(d, 3.6));
            seg(out, S_BONE, BODY_LIGHT, top, base, 0.45, 0.45, u);
            double[] mid = add(base, mul(d, 1.8));
            seg(out, S_BONE, BODY_LIGHT, add(mid, mul(norm(add(u, mul(r, side * 0.4))), 1.8)), mid, 0.35, 0.35, f);
            double wave = 0.35 * Math.sin(age * 0.12 + side);                                     // whiskers drifting in the wind
            double[] wb = at(p, r, u, f, side * 0.8, 0.1, 4.2);
            double[] wd = norm(add(add(mul(f, -0.8), mul(r, side * 0.55)), mul(u, -0.35 + wave)));
            seg(out, S_FIN, BODY_LIGHT, add(wb, mul(wd, 6)), wb, 0.3, 0.3, u);
        }
        int n = 22;
        double len = 58;
        for (int i = 0; i < n; i++) {
            double s0 = 2 + len * i / n, s1 = 2 + len * (i + 1) / n, t = (double) i / n;
            double rad = t < 0.25 ? 1.3 + t * 3.2 : 2.1 * Math.pow(1 - (t - 0.25) / 0.75, 0.7) + 0.35;
            double[][] a = frame(sp, s0), b = frame(sp, s1);
            seg(out, i % 3 == 0 ? S_BAND : S_HIDE, BODY_LIGHT, a[0], b[0], rad * 2, rad * 1.8, a[2]);
            if (i % 2 == 0 && i < n - 2) {
                double[] mid = lerp3(a[0], b[0], 0.5);
                double fl = 0.9 + 0.5 * Math.sin(age * 0.15 - i * 0.5);                           // fins ripple down the back
                box(out, S_FIN, BODY_LIGHT, add(mid, mul(a[2], rad * 0.9 + fl * 0.5)), a[1], a[2], a[3], 0.18, fl, 1.8);
            }
        }
        double[][] end = frame(sp, 2 + len);
        box(out, S_FIN, BODY_LIGHT, end[0], end[1], end[2], end[3], 1.6, 1.6, 2.2);
        for (double s : new double[]{14, 34}) {                                                   // four little paddling legs
            double[][] fr = frame(sp, s);
            for (int side : new int[]{-1, 1}) {
                double[] hip = at(fr[0], fr[1], fr[2], fr[3], side * 1.4, -1.0, 0);
                double pad = 0.5 * Math.sin(age * 0.2 + side * 1.5 + s);
                double[] d = norm(add(add(mul(fr[2], -0.7), mul(fr[3], pad - 0.3)), mul(fr[1], side * 0.4)));
                seg(out, S_HIDE, BODY_LIGHT, hip, add(hip, mul(d, 2.6)), 0.7, 0.7, fr[2]);
            }
        }
        double[][] t0 = frame(sp, 2 + len), t1 = frame(sp, 2 + len * 0.6), t2 = frame(sp, 2 + len * 0.3);
        trails = new double[][]{t0[0], add(t1[0], mul(t1[2], 2)), add(t2[0], mul(t2[2], 2.5))};
    }

    // ------------------------------------------------------------------ pieces

    private static void box(List<Piece> out, String block, int light, double[] centre, double[] r, double[] u, double[] f, double sx, double sy, double sz) {
        double[] corner = sub(sub(sub(centre, mul(r, sx / 2)), mul(u, sy / 2)), mul(f, sz / 2));
        out.add(new Piece(block, light, corner, r, u, f, sx, sy, sz));
    }

    /** A box from a to b (a little overlap so segments join), w wide and h tall, its up side towards upHint. */
    private static void seg(List<Piece> out, String block, int light, double[] a, double[] b, double w, double h, double[] upHint) {
        double[] d = sub(a, b);
        double l = len(d);
        double[] f = l < 1e-6 ? new double[]{0, 0, 1} : mul(d, 1 / l);
        double[] u = sub(upHint, mul(f, dot(upHint, f)));
        if (len(u) < 1e-6) u = Math.abs(f[1]) < 0.9 ? new double[]{0, 1, 0} : new double[]{1, 0, 0};
        u = norm(sub(u, mul(f, dot(u, f))));
        double[] r = cross(u, f);
        box(out, block, light, lerp3(a, b, 0.5), r, u, f, w, h, l + Math.min(w, h) * 0.5);
    }

    /** A thin sheet of wing membrane from origin along axis for length, stretched towards q. */
    private static void panel(List<Piece> out, String block, double[] origin, double[] axis, double length, double[] q, double reach) {
        double[] toQ = sub(q, origin);
        double[] b = sub(toQ, mul(axis, dot(toQ, axis)));
        double width = Math.max(0.05, len(b) * reach);   // always a piece, even if squashed flat: the display list never changes length
        double[] bd = len(b) < 1e-6 ? norm(cross(axis, new double[]{0, 1, 0})) : mul(b, 1 / len(b));
        double[] n = cross(axis, bd);                 // (bd, n, axis) is right-handed
        double[] corner = sub(origin, mul(n, 0.09));
        out.add(new Piece(block, BODY_LIGHT, corner, bd, n, axis, width, 0.18, length));
    }

    // ------------------------------------------------------------------ vectors

    static double[] dir(double yaw, double pitch) { return new double[]{Math.cos(yaw) * Math.cos(pitch), Math.sin(pitch), Math.sin(yaw) * Math.cos(pitch)}; }
    static double[] at(double[] p, double[] r, double[] u, double[] f, double a, double b, double c) {
        return new double[]{p[0] + r[0] * a + u[0] * b + f[0] * c, p[1] + r[1] * a + u[1] * b + f[1] * c, p[2] + r[2] * a + u[2] * b + f[2] * c};
    }
    static double[] add(double[] a, double[] b) { return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]}; }
    static double[] sub(double[] a, double[] b) { return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]}; }
    static double[] mul(double[] a, double k) { return new double[]{a[0] * k, a[1] * k, a[2] * k}; }
    static double dot(double[] a, double[] b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
    static double len(double[] a) { return Math.sqrt(dot(a, a)); }
    static double[] norm(double[] a) { double l = len(a); return l < 1e-9 ? new double[]{0, 1, 0} : mul(a, 1 / l); }
    static double[] cross(double[] a, double[] b) { return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]}; }
    static double[] lerp3(double[] a, double[] b, double t) { return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t}; }
    /** Lerp of path points, including their bank. */
    static double[] lerp(double[] a, double[] b, double t) {
        double[] o = new double[a.length];
        for (int i = 0; i < a.length; i++) o[i] = a[i] + (b[i] - a[i]) * t;
        return o;
    }
    static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    static double wrap(double a) { while (a > Math.PI) a -= 2 * Math.PI; while (a < -Math.PI) a += 2 * Math.PI; return a; }
}
