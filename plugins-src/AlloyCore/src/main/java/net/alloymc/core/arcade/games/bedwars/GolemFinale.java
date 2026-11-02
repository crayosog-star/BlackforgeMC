package net.alloymc.core.arcade.games.bedwars;

/**
 * The Golem's last moments and the hall's slow wake-up, as plain maths (tests and the offline render use exactly this).
 *
 * <ul>
 *   <li><b>Death:</b> the killing blow doesn't end it at once. It staggers, sinks to its knees, shudders while its
 *   crystals go dark one by one and cracks run across the floor, and only then does it fall apart ({@link #DIE} ticks).</li>
 *   <li><b>Stirring:</b> for the 30 seconds before it wakes, the hall rumbles and sheds dust from the ceiling, building up.</li>
 * </ul>
 */
final class GolemFinale {
    private GolemFinale() {}

    /** Ticks from the killing blow to the collapse. */
    static final int DIE = 64;
    /** When the shaking starts (it stops hard-hitting and starts to fade out after this). */
    static final int SHUDDER = 46;
    /** The hall starts stirring this many seconds before the Golem wakes. */
    static final int STIR_SECONDS = 30;

    /** The tick crystal {@code i} (of {@code n} model parts) goes dark: scattered between tick 14 and tick 58. */
    static int lightsOut(int i, int n) {
        long h = ((long) i * 2654435761L + n) & 0xFFFFFFFFL;
        return 14 + (int) (h % 45);
    }

    /** 0 to 1: how restless the hall is with this many seconds until the Golem wakes. Zero outside the last 30 s. */
    static double stir(double secondsLeft) {
        if (secondsLeft <= 0 || secondsLeft > STIR_SECONDS) return 0;
        double u = 1 - secondsLeft / STIR_SECONDS;
        return u * u * (3 - 2 * u);
    }

    /** Whole seconds between rumbles: slow at first, quick at the end. */
    static int rumbleEvery(double secondsLeft) { return secondsLeft > 20 ? 6 : secondsLeft > 10 ? 4 : secondsLeft > 4 ? 2 : 1; }

    /** Ceiling-dust particles to shake loose each second. */
    static int dustPerSecond(double secondsLeft) { return (int) Math.round(2 + 22 * stir(secondsLeft)); }
}
