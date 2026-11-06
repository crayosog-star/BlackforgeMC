package net.blackforge.core.survival;

/**
 * The numbers behind the {@link Nightrend} daggers, with no Bukkit types so the build can test them.
 *
 * <ul>
 *   <li>Attribute maths: a player's base attack damage is 1 and base attack speed 4, so an item that should hit for
 *   {@code d} at {@code s} attacks a second carries modifiers of {@code d - 1} and {@code s - 4}.</li>
 *   <li>The "from behind" test: the attacker stands inside the arc behind where the target is facing.</li>
 *   <li>The off-hand slash: a short eased arc of points drawn over a few ticks.</li>
 * </ul>
 */
final class NightrendMath {
    private NightrendMath() {}

    static final double BASE_ATTACK_DAMAGE = 1.0;
    static final double BASE_ATTACK_SPEED = 4.0;

    /** Ticks the off-hand slash takes to sweep across. */
    static final int SLASH_TICKS = 4;
    /** How wide the slash arc is, end to end, in degrees. */
    static final double SLASH_ARC_DEGREES = 110;

    /** Item modifier that makes a hit land for {@code damage} in total. */
    static double damageModifier(double damage) { return damage - BASE_ATTACK_DAMAGE; }

    /** Item modifier that gives {@code speed} attacks a second in total. */
    static double speedModifier(double speed) { return speed - BASE_ATTACK_SPEED; }

    /** Extra speed the pair adds on top of one dagger (never negative). */
    static double pairBonus(double singleSpeed, double pairSpeed) { return Math.max(0, pairSpeed - singleSpeed); }

    /**
     * Is the attacker behind the target? {@code (faceX, faceZ)}: the way the target is facing, flat.
     * {@code (toX, toZ)}: from the target to the attacker, flat. {@code arcDegrees}: how wide the "behind" arc is,
     * centred on the target's back (120 = 60 degrees either side). An attacker straight above or on top of the
     * target (no flat distance) is never behind.
     */
    static boolean behind(double faceX, double faceZ, double toX, double toZ, double arcDegrees) {
        double fl = Math.hypot(faceX, faceZ), tl = Math.hypot(toX, toZ);
        if (fl < 1e-6 || tl < 1e-6) return false;
        double half = Math.toRadians(Math.max(0, Math.min(360, arcDegrees)) / 2.0);
        double cos = (faceX * toX + faceZ * toZ) / (fl * tl);   // 1 = in front, -1 = straight behind
        return cos <= -Math.cos(half) + 1e-9;
    }

    /** Damage multiplier for a hit: {@code behindMultiplier} (clamped 0-1) from behind, full damage otherwise. */
    static double hitMultiplier(boolean fromBehind, double behindMultiplier) {
        return fromBehind ? Math.max(0, Math.min(1, behindMultiplier)) : 1.0;
    }

    /**
     * Which arm swings next: normally the other one, but with {@code repeatChance} (clamped 0-0.9) the same arm goes
     * again, so the rhythm isn't perfectly predictable. {@code roll} is a uniform random number in [0, 1).
     */
    static boolean nextIsOffHand(boolean lastWasOff, double roll, double repeatChance) {
        double c = Math.max(0, Math.min(0.9, repeatChance));
        return roll < c ? lastWasOff : !lastWasOff;
    }

    /** Eased progress of the slash at tick {@code t} (0..SLASH_TICKS): fast out of the swing, slowing at the end. */
    static double slashProgress(int t) {
        double x = Math.max(0, Math.min(1, t / (double) SLASH_TICKS));
        return 1 - Math.pow(1 - x, 3);   // ease-out cubic
    }

    /**
     * Angle (degrees, relative to straight ahead) of the slash's leading edge at tick {@code t}. The off-hand is the
     * left hand, so the slash runs from the left (negative) across to the right (positive).
     */
    static double slashAngle(int t) { return -SLASH_ARC_DEGREES / 2 + SLASH_ARC_DEGREES * slashProgress(t); }

    // ------------------------------------------------------------------ equip flip

    /**
     * The equip flourish: the blades flip up into your grip and hold for a beat; then, in the same tick, the flipping
     * copies go and the real held daggers (hidden while it plays) come back, so there's never two of them on screen.
     */
    static final int FLIP_TICKS = 10, FLIP_HOLD = 3;

    // ------------------------------------------------------------------ your own pull-out (the pack's item model)

    /**
     * Your own pull-out runs on the dagger's item cooldown, which the client only re-reads once a game tick, so the
     * pose can change at most 20 times a second however many frame models the pack has. It holds low and upside down
     * for {@link #DRAW_HOLD} ticks (the game is still raising a freshly selected item while its attack charges), then
     * flips evenly over {@link #DRAW_FLIP} ticks, at most about 21 degrees a tick, with a small settle at the end.
     * tools/gen_nightrend_draw.py writes the frames from the same two formulas.
     */
    static final int DRAW_HOLD = 3, DRAW_FLIP = 14, DRAW_TICKS = DRAW_HOLD + DRAW_FLIP;

    private static double clamp01(double v) { return Math.max(0, Math.min(1, v)); }

    /** Flip progress (0..1) at overall draw progress {@code x} (0..1): 0 through the hold, then 0 to 1. */
    static double drawFlipProgress(double x) {
        double h = DRAW_HOLD / (double) DRAW_TICKS;
        return clamp01((x - h) / (1 - h));
    }

    /** Degrees from upright at draw progress {@code x}: -180 through the hold, ease-in-out to 0, a little past near the end. */
    static double drawOffset(double x) {
        double u = drawFlipProgress(x);
        double ease = 0.5 - 0.5 * Math.cos(Math.PI * u);
        double settle = 8 * Math.sin(Math.PI * clamp01((u - 0.8) / 0.2));
        return -180 * (1 - ease) + settle;
    }

    /** How low the blade sits (1 = lowest) at draw progress {@code x}: low through the hold, then rises over the first 60% of the flip. */
    static double drawDrop(double x) {
        double r = clamp01(drawFlipProgress(x) / 0.6);
        return 1 - r * r * (3 - 2 * r);
    }
    static final int FLIP_TOTAL = FLIP_TICKS + FLIP_HOLD;
    /** How far the blades rise out of your hands, in blocks. */
    static final double FLIP_LIFT = 0.45;

    /**
     * Blade pitch in degrees at tick {@code t}: starts pointing down (-180) and flips forward to point up (0), with a
     * small snap past upright that settles back (ease-out-back), then stays at 0.
     */
    static double flipPitch(int t) {
        double x = Math.max(0, Math.min(1, t / (double) FLIP_TICKS));
        double c1 = 1.0, c3 = c1 + 1;   // snappy: most of the turn in the first 3 ticks, a ~6 degree settle
        double e = 1 + c3 * Math.pow(x - 1, 3) + c1 * Math.pow(x - 1, 2);
        return -180 + 180 * e;
    }

    /** Height above the hand at tick {@code t}: rises fast and eases in, then holds. */
    static double flipLift(int t) {
        double x = Math.max(0, Math.min(1, t / (double) FLIP_TICKS));
        return FLIP_LIFT * (1 - Math.pow(1 - x, 3));
    }

    /** Display scale at tick {@code t}: grows in over the first 3 ticks, then full size until it hands over. */
    static double flipScale(int t) {
        if (t <= 0) return 0;
        return t < 3 ? t / 3.0 : 1;
    }
}
