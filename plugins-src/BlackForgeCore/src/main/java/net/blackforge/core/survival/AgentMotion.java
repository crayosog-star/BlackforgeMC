package net.blackforge.core.survival;

/**
 * How an Agent moves: a pose for any moment of idling or of doing its job. No Bukkit types, so it can be unit-tested. Every
 * job has a wind-up, a hit and a recovery, on eased curves, so nothing snaps. Lengths are in blocks, angles in radians.
 *
 * @param bob    up and down
 * @param lean   tipped forward
 * @param arm    the working arm raised forward, 0 hanging down
 * @param travel how far along its way to the target, 0 at its post and 1 at the target
 */
record AgentPose(double bob, double lean, double arm, double travel) {}

final class AgentMotion {
    private AgentMotion() {}

    /** Enum names, so this stays free of the Bukkit-using Robots class. */
    enum Kind { MINER, FARMER, GRINDER, FISHERMAN }

    static final int MIN_TICKS = 8;
    /** Ticks a job takes when it has time. It's shortened to fit a fast Agent's cycle, and has to end before the next job. */
    static int ticks(Kind kind, int intervalSeconds) {
        int wanted = switch (kind) { case MINER -> 16; case FARMER -> 44; case GRINDER -> 20; case FISHERMAN -> 40; };
        return Math.max(MIN_TICKS, Math.min(wanted, intervalSeconds * 20 - 2));
    }

    /** The moment in a job, 0-1, when the hit lands: when its sound and particles go off. */
    static double impactAt(Kind kind) {
        return switch (kind) { case MINER -> 0.55; case FARMER -> 0.5; case GRINDER -> 0.42; case FISHERMAN -> 0.75; };
    }

    /** How far it may walk to its target: only the Farmer and Grinder leave their post, and it needs time to get there and back. */
    static double reach(Kind kind, int ticks, double distance) {
        double most = switch (kind) { case FARMER -> ticks * 0.08; case GRINDER -> Math.min(1.0, ticks * 0.05); default -> 0; };
        return Math.min(most, Math.max(0, distance - 0.6));
    }

    /** Standing by: it breathes, and it sways a little so a row of Agents doesn't move as one. */
    static AgentPose idle(long tick, int seed, boolean hasFuel) {
        if (!hasFuel) return new AgentPose(-0.03, 0.35, 0.0, 0);   // out of fuel: it slumps forward and stops
        double t = tick * 0.08 + seed;
        return new AgentPose(Math.sin(t) * 0.015, Math.sin(t * 0.5) * 0.03, 0.08 + Math.sin(t) * 0.05, 0);
    }

    /** Partway through a job, {@code p} from 0 to 1. */
    static AgentPose work(Kind kind, double p) {
        double t = clamp(p);
        return switch (kind) {
            case MINER -> {
                double arm = t < 0.45 ? 2.4 * ease(t / 0.45) : t < 0.58 ? 2.4 - 2.0 * ease((t - 0.45) / 0.13) : 0.4 * (1 - ease((t - 0.58) / 0.42));
                double lean = t < 0.45 ? -0.2 * ease(t / 0.45) : t < 0.58 ? -0.2 + 0.55 * ease((t - 0.45) / 0.13) : 0.35 * (1 - ease((t - 0.58) / 0.42));
                yield new AgentPose(0, lean, arm, 0);
            }
            case FARMER -> {
                double go = t < 0.3 ? ease(t / 0.3) : t < 0.7 ? 1 : 1 - ease((t - 0.7) / 0.3);
                double chop = t < 0.3 || t > 0.7 ? 0 : Math.sin((t - 0.3) / 0.4 * Math.PI);
                yield new AgentPose(Math.abs(Math.sin(t * Math.PI * 8)) * 0.05 * (t < 0.3 || t > 0.7 ? 1 : 0), 0.45 * chop, 0.3 + 1.2 * chop, go);
            }
            case GRINDER -> {
                double go = t < 0.3 ? ease(t / 0.3) : t < 0.55 ? 1 : 1 - ease((t - 0.55) / 0.45);
                double arm = t < 0.3 ? 2.6 * ease(t / 0.3) : t < 0.45 ? 2.6 - 2.2 * ease((t - 0.3) / 0.15) : 0.4 * (1 - ease((t - 0.45) / 0.55));
                yield new AgentPose(0, 0.3 * go, arm, go);
            }
            case FISHERMAN -> {
                double arm = t < 0.2 ? 0.9 + 1.5 * ease(t / 0.2) : t < 0.32 ? 2.4 - 1.6 * ease((t - 0.2) / 0.12) : t < 0.72 ? 0.8 : t < 0.86 ? 0.8 + 1.4 * ease((t - 0.72) / 0.14) : 2.2 - 1.3 * ease((t - 0.86) / 0.14);
                yield new AgentPose(0, t > 0.32 && t < 0.72 ? 0.12 : 0, arm, 0);
            }
        };
    }

    static double ease(double t) {
        double c = clamp(t);
        return c * c * (3 - 2 * c);
    }

    private static double clamp(double t) { return Math.max(0, Math.min(1, t)); }
}
