package net.alloymc.core.arcade.games.bedwars;

/**
 * The Golem's animation as plain maths (no Bukkit, no joml), so the tests and the offline GIF use exactly what the game
 * shows. A {@link Pose} turns each arm about its shoulder, the head about the neck and the ore about the middle of the
 * back, then leans and twists everything above the legs about the hips. {@link #transform} turns a model part and a
 * pose into a block display transformation (translation, rotation, scale).
 *
 * Angles are degrees. Rotations follow the right-hand rule: a positive x angle tips the top of something forward
 * (towards +z, where the Golem faces), so an arm hanging down swings forward and up with a NEGATIVE x angle. For an arm
 * hanging down, a positive z angle swings it towards +x (the Golem's left), a negative one towards -x (its right).
 * Clips give target poses; the Golem blends from where it is to the target (see {@link #lerp}), re-posing every few
 * ticks so big swings follow a real arc instead of a straight line.
 */
final class GolemAnim {
    private GolemAnim() {}

    /** A joint turn: x first (forward/back), then z (sideways), then y (twist). */
    record Rot(double x, double y, double z) {
        static final Rot ZERO = new Rot(0, 0, 0);
        /** The same turn on the other side of the body. */
        Rot mirror() { return new Rot(x, -y, -z); }
        static Rot lerp(Rot a, Rot b, double f) { return new Rot(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, a.z + (b.z - a.z) * f); }
    }

    /**
     * Everything one frame needs. {@code lift}: the whole model up/down (rising out of the floor). {@code bob}: the body
     * above the legs up/down. {@code leanX}: forward (+) / back (-). {@code leanZ}: side to side. {@code twist}: the
     * torso turning (+ turns towards its left). {@code heart}: heart-stone size.
     */
    record Pose(double lift, double bob, double leanX, double leanZ, double twist, Rot armL, Rot armR, Rot head, Rot ore, double heart) {
        static final Pose REST = new Pose(0, 0, 0, 0, 0, Rot.ZERO, Rot.ZERO, Rot.ZERO, Rot.ZERO, 1);
        Pose lift(double v) { return new Pose(v, bob, leanX, leanZ, twist, armL, armR, head, ore, heart); }
        Pose heart(double v) { return new Pose(lift, bob, leanX, leanZ, twist, armL, armR, head, ore, v); }
    }

    /** Builds a pose; arms default to mirror images (set the right arm, the left copies it unless set too). */
    static final class B {
        double lift, bob, leanX, leanZ, twist, heart = 1;
        Rot armL, armR = Rot.ZERO, head = Rot.ZERO, ore = Rot.ZERO;
        B bob(double v) { bob = v; return this; }
        B lean(double v) { leanX = v; return this; }
        B side(double v) { leanZ = v; return this; }
        B twist(double v) { twist = v; return this; }
        B arms(double x, double y, double z) { armR = new Rot(x, y, z); return this; }
        B armR(double x, double y, double z) { armR = new Rot(x, y, z); return this; }
        B armL(double x, double y, double z) { armL = new Rot(x, y, z); return this; }
        B head(double x, double y, double z) { head = new Rot(x, y, z); return this; }
        B ore(double x) { ore = new Rot(x, 0, 0); return this; }
        Pose build() { return new Pose(lift, bob, leanX, leanZ, twist, armL != null ? armL : armR.mirror(), armR, head, ore, heart); }
    }

    static B pose() { return new B(); }

    static double smooth(double u) { u = Math.max(0, Math.min(1, u)); return u * u * (3 - 2 * u); }

    static Pose lerp(Pose a, Pose b, double f) {
        return new Pose(a.lift + (b.lift - a.lift) * f, a.bob + (b.bob - a.bob) * f, a.leanX + (b.leanX - a.leanX) * f,
                a.leanZ + (b.leanZ - a.leanZ) * f, a.twist + (b.twist - a.twist) * f, Rot.lerp(a.armL, b.armL, f),
                Rot.lerp(a.armR, b.armR, f), Rot.lerp(a.head, b.head, f), Rot.lerp(a.ore, b.ore, f), a.heart + (b.heart - a.heart) * f);
    }

    // ------------------------------------------------------------------ clips (target poses)

    /** Standing: slow breathing. */
    static Pose idle(int t) {
        double s = Math.sin(t * 0.08);
        return pose().bob(0.05 * s).lean(5 + s).head(2 * s, 0, 0).build();
    }

    /** Knuckle-walking: arms swing in turn, the body rolls over each step. {@code rate} 1 = normal, less when slowed. */
    static Pose walk(int t, double rate) {
        double ph = t * 0.2 * rate, s = Math.sin(ph);
        return pose().bob(0.1 * Math.abs(s) - 0.05).lean(8).side(3 * s).twist(4 * s)
                .armR(15 * s, 0, 0).armL(-15 * s, 0, 0).head(3, -3 * s, 0).build();
    }

    /** Dazed after a pillar: slumped forward, swaying. */
    static Pose stunned(int t) {
        double s = Math.sin(t * 0.22);
        return pose().bob(-0.35).lean(22).side(4 * s).twist(5 * s).arms(10, 0, -6).head(28, 6 * s, 0).build();
    }

    /** Where the wind-up of a move ends (the Golem eases into it over the whole wind-up). */
    static Pose windup(GolemMath.Move m) {
        return switch (m) {
            case SWIPE -> pose().twist(-20).lean(4).armR(25, 0, -75).armL(-10, 0, 8).head(0, -15, 0).build();
            case SLAM -> pose().bob(0.25).lean(-12).arms(-150, 0, -15).head(-12, 0, 0).build();
            case BOULDER -> pose().lean(-8).twist(-16).armR(-170, 0, -20).armL(-25, 0, 10).head(-6, -10, 0).build();
            case ROOTS -> pose().lean(-4).arms(-110, 0, -10).head(-6, 0, 0).build();
            case CHARGE -> pose().bob(-0.2).lean(22).arms(35, 0, -10).head(15, 0, 0).build();
            case ROCKFALL -> pose().bob(0.3).lean(-18).arms(-160, 0, -40).head(-22, 0, 0).build();
            case ERUPTION -> pose().bob(-0.1).lean(14).arms(-70, 0, -8).head(5, 0, 0).ore(-18).build();
            case SHARDS -> pose().lean(22).arms(12, 0, -25).head(14, 0, 0).ore(-32).build();
        };
    }

    /** How many ticks a move takes to reach its strike pose. */
    static int strikeTicks(GolemMath.Move m) {
        return switch (m) { case SWIPE -> 4; case BOULDER -> 4; case CHARGE -> 3; case SHARDS -> 3; default -> 3; };
    }

    /** The strike: where a move lands. {@code t} drives anything that keeps moving while the move lasts. */
    static Pose strike(GolemMath.Move m, int t) {
        return switch (m) {
            case SWIPE -> pose().twist(22).lean(12).armR(-60, 0, 35).armL(10, 0, -5).head(0, 15, 0).build();
            case SLAM, ROCKFALL -> pose().bob(-0.4).lean(20).arms(-12, 0, -6).head(8, 0, 0).build();
            case BOULDER -> pose().lean(14).twist(18).armR(-40, 0, 12).armL(15, 0, 0).head(4, 10, 0).build();
            case ROOTS, ERUPTION -> pose().bob(-0.45).lean(24).arms(4, 0, -5).head(12, 0, 0).ore(m == GolemMath.Move.ERUPTION ? -26 : 0).build();
            case CHARGE -> {
                double s = Math.sin(t * 0.9);
                yield pose().bob(0.12 * Math.abs(s) - 0.15).lean(26).side(4 * s).armR(28 * s, 0, -6).armL(-28 * s, 0, 6).head(14, 0, 0).build();
            }
            case SHARDS -> pose().lean(22).arms(12, 0, -25).head(14, 0, 0).ore(-32 + 10 * Math.max(0, Math.sin(t * 1.05))).build();   // the ore kicks as each shard leaves
        };
    }

    /** Rising out of the floor over the first 3 seconds. */
    static Pose rising(double u) {
        return pose().lean(-6).arms(-30, 0, -10).head(-15, 0, 0).build().lift(-(GolemModel.height() + 0.2) * (1 - smooth(u)));
    }

    // ------------------------------------------------------------------ the maths

    static final double S = GolemModel.SCALE;
    static final double[] SHOULDER_R = {-2.1 * S, 3.55 * S, 0.15 * S}, SHOULDER_L = {2.1 * S, 3.55 * S, 0.15 * S},
            NECK = {0, 3.55 * S, 1.6 * S}, BACK = {0, 3.4 * S, -0.9 * S}, HIPS = {0, 1.2 * S, 0};

    /** A part's display transformation in a pose: {translation xyz, rotation quaternion xyzw, scale xyz}. */
    static double[][] transform(GolemModel.Part p, Pose pose) {
        double hs = p.group() == GolemModel.Group.HEART ? pose.heart : 1;
        double[] scale = {p.sx() * hs, p.sy() * hs, p.sz() * hs};
        double[] q = mul(axis(0, 0, 1, p.rz()), axis(1, 0, 0, p.rx()));
        double[] half = rotate(q, new double[]{scale[0] / 2, scale[1] / 2, scale[2] / 2});
        double[] tr = {p.cx() - half[0], p.cy() - half[1], p.cz() - half[2]};
        Rot joint = null;
        double[] pivot = null;
        switch (p.group()) {
            case ARM_L -> { joint = pose.armL; pivot = SHOULDER_L; }
            case ARM_R -> { joint = pose.armR; pivot = SHOULDER_R; }
            case HEAD -> { joint = pose.head; pivot = NECK; }
            case ORE -> { joint = pose.ore; pivot = BACK; }
            default -> {}
        }
        if (joint != null) {
            double[] g = euler(joint.x(), joint.y(), joint.z());
            tr = about(g, pivot, tr);
            q = mul(g, q);
        }
        if (p.group() != GolemModel.Group.LEGS) {
            double[] l = euler(pose.leanX, pose.twist, pose.leanZ);
            tr = about(l, HIPS, tr);
            q = mul(l, q);
            tr[1] += pose.bob;
        }
        tr[1] += pose.lift;
        return new double[][]{tr, q, scale};
    }

    /** Where a point of a part (u, v, w in 0..1 across the box) ends up in a pose. */
    static double[] point(GolemModel.Part p, Pose pose, double u, double v, double w) {
        double[][] tf = transform(p, pose);
        double[] r = rotate(tf[1], new double[]{u * tf[2][0], v * tf[2][1], w * tf[2][2]});
        return new double[]{tf[0][0] + r[0], tf[0][1] + r[1], tf[0][2] + r[2]};
    }

    /** Rotate point {@code at} by {@code q} about {@code pivot}. */
    private static double[] about(double[] q, double[] pivot, double[] at) {
        double[] r = rotate(q, new double[]{at[0] - pivot[0], at[1] - pivot[1], at[2] - pivot[2]});
        return new double[]{pivot[0] + r[0], pivot[1] + r[1], pivot[2] + r[2]};
    }

    /** x first, then z, then y: Ry * Rz * Rx. */
    static double[] euler(double x, double y, double z) { return mul(axis(0, 1, 0, y), mul(axis(0, 0, 1, z), axis(1, 0, 0, x))); }

    static double[] axis(double ax, double ay, double az, double deg) {
        double h = Math.toRadians(deg) / 2, s = Math.sin(h);
        return new double[]{ax * s, ay * s, az * s, Math.cos(h)};
    }

    static double[] mul(double[] a, double[] b) {
        return new double[]{
                a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
                a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
                a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
                a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]};
    }

    static double[] rotate(double[] q, double[] v) {
        double[] p = {v[0], v[1], v[2], 0}, c = {-q[0], -q[1], -q[2], q[3]};
        double[] r = mul(mul(q, p), c);
        return new double[]{r[0], r[1], r[2]};
    }
}
