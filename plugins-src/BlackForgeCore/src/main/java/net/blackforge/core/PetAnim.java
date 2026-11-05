package net.blackforge.core;

import java.util.Random;

/**
 * The orange companion's body language and leash, as pure maths so it can be unit tested without a server.
 * One frame is one cosmetics update, which is two game ticks.
 */
final class PetAnim {
    private PetAnim() {}

    /** Roam points are picked this far from the owner, at any angle, so the pet is not tied to the camera. */
    static final double ROAM_MIN = 2.5, ROAM_MAX = 7;
    /** Past this distance the pet bounds back at sprint speed, until it is within CHASE_DONE. */
    static final double CHASE_START = 7.5, CHASE_DONE = 5;
    /** The hard limit: past this the pet is put back beside the owner. */
    static final double LEASH = 10;
    static final double STEP = .25, LEAP_STEP = 1;
    /** While bounding back, this many airborne frames in a row with no step means a door or high ledge is in the way. */
    static final int STUCK_FRAMES = 10;

    private static final float TAU = (float) (2 * Math.PI), PI = (float) Math.PI;

    /** pitch is forward lean, roll is sideways, spin is extra yaw, all in radians. stretch 1 is the resting height. */
    record Pose(float lift, float stretch, float pitch, float roll, float spin,
                float arm, float armRaise, float foot, float eye, float pivot) {
        static final Pose REST = new Pose(0, 1, 0, 0, 0, 0, 0, 0, 1, .35f);
    }

    /** Idle gags. Each one starts and ends in the resting pose, so it can follow or precede anything. */
    enum Gag { WIGGLE(16), BACKFLIP(14), SPIN(14), FACEPLANT(26), FLAIL(18);
        final int frames;
        Gag(int frames) { this.frames = frames; }
    }

    static int hopFrames(boolean leap) { return leap ? 6 : 8; }

    /** Only airborne frames travel, which is what makes it read as a hop and not a slide. */
    static boolean airborne(int frame, boolean leap) {
        return leap ? frame < 5 : frame >= 1 && frame <= 6;
    }

    /** Crouch, launch tall, arc nose-up to nose-down, then squash on landing. */
    static Pose hop(int frame, boolean leap) {
        if (!airborne(frame, leap)) return new Pose(0, frame == 0 ? .72f : .76f, 0, 0, 0, 0, 0, 0, 1, .35f);
        int air = leap ? 5 : 6;
        float t = (leap ? frame + 1f : frame) / (air + 1f);
        float height = leap ? .9f : .55f;
        float lift = height * 4 * t * (1 - t);
        float edge = Math.abs(1 - 2 * t);
        return new Pose(lift, 1 + .22f * edge, -.35f * (1 - 2 * t), 0, 0,
                (leap ? .9f : .5f) * (float) Math.sin(TAU * t), .08f * (1 - edge), (float) Math.sin(TAU * t), 1, .35f);
    }

    /** Stands still and blinks. */
    static Pose idle(int frame) {
        return Math.floorMod(frame, 37) < 2 ? new Pose(0, 1, 0, 0, 0, 0, 0, 0, .12f, .35f) : Pose.REST;
    }

    static Pose gag(Gag gag, int frame) {
        if (frame <= 0 || frame >= gag.frames - 1) return Pose.REST;
        return switch (gag) {
            case WIGGLE -> wiggle(frame, gag.frames);
            case BACKFLIP -> backflip(frame);
            case SPIN -> spin(frame, gag.frames);
            case FACEPLANT -> faceplant(frame);
            case FLAIL -> flail(frame);
        };
    }

    private static Pose wiggle(int frame, int frames) {
        float swing = (float) Math.sin(PI * frame / 2);
        float envelope = (float) Math.sin(PI * frame / (frames - 1));
        return new Pose(0, 1 - .05f * envelope, .22f * envelope, .4f * swing * envelope, 0, swing * envelope, 0, swing * envelope, 1, .1f);
    }

    /** Frames 1-2 crouch, 3-11 are the flip, 12 lands. */
    private static Pose backflip(int frame) {
        if (frame < 3) return new Pose(0, .7f, 0, 0, 0, 0, 0, 0, 1, .35f);
        if (frame > 11) return new Pose(0, .72f, 0, 0, 0, 0, 0, 0, 1, .35f);
        float t = (frame - 2) / 10f;
        float eased = t * t * (3 - 2 * t);
        return new Pose(1.15f * 4 * t * (1 - t), 1, -TAU * eased, 0, 0, 1, .12f, 1, 1.5f, .35f);
    }

    private static Pose spin(int frame, int frames) {
        float t = frame / (frames - 1f);
        float eased = t * t * (3 - 2 * t);
        return new Pose(.5f * 4 * t * (1 - t), 1.08f, 0, 0, 2 * TAU * eased, 0, .22f, 0, 1, .35f);
    }

    /** Trips, flails on the way down, lies there kicking, then gets up. */
    private static Pose faceplant(int frame) {
        if (frame <= 4) {
            float t = frame / 4f;
            return new Pose(.17f * t, 1, (PI / 2) * t * t, 0, 0, frame % 2 == 0 ? 1.4f : -1.4f, .2f, 0, 1.5f, 0);
        }
        if (frame <= 17) {
            float kick = frame % 2 == 0 ? 1.5f : -1.5f;
            return new Pose(.17f, frame == 5 ? .86f : 1, PI / 2, 0, 0, 0, 0, kick, 1, 0);
        }
        float t = (frame - 17) / 7f;
        return new Pose(.17f * (1 - t), 1, (PI / 2) * (1 - t * t * (3 - 2 * t)), 0, 0, 0, 0, 0, 1, 0);
    }

    /** Panicked little hops with the arms going. Also played whenever the pet talks. */
    private static Pose flail(int frame) {
        float beat = frame % 2 == 0 ? 1 : -1;
        float bounce = frame % 4 < 2 ? .16f : .02f;
        return new Pose(bounce, 1.04f, 0, .1f * beat, 0, 1.5f * beat, .2f, -beat, 1.5f, .35f);
    }

    /** A spot around the owner as {dx, dz}. The angle is uniform and never looks at where the owner faces. */
    static double[] roamOffset(Random rng, double min, double max) {
        double angle = rng.nextDouble() * 2 * Math.PI;
        double radius = min + rng.nextDouble() * (max - min);
        return new double[]{radius * Math.sin(angle), radius * Math.cos(angle)};
    }

    /** Whether the pet should be bounding back, with a gap so it does not flip modes at one distance. */
    static boolean chasing(boolean wasChasing, double distance) {
        return distance > CHASE_START || (wasChasing && distance > CHASE_DONE);
    }

    /** Counts airborne frames that found no step. Any step clears it. */
    static int blocked(int frames, boolean stepped) {
        return stepped ? 0 : frames + 1;
    }

    /** A chasing pet that keeps hitting the same wall is put back beside the owner instead of leaping on the spot. */
    static boolean stuck(boolean chasing, int blockedFrames) {
        return chasing && blockedFrames >= STUCK_FRAMES;
    }

    /** Turn from an angle toward another the short way, at most maxStep radians. */
    static float turnToward(float from, float to, float maxStep) {
        float delta = (float) Math.IEEEremainder(to - from, 2 * Math.PI);
        if (Math.abs(delta) <= maxStep) return to;
        return from + Math.copySign(maxStep, delta);
    }
}
