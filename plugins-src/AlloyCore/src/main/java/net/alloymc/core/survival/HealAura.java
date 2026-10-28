package net.alloymc.core.survival;

/**
 * Pure rules for the Ashen Bonfire's healing ring: who is inside it and how much they get back.
 * No Bukkit types, so it can be unit tested without a server.
 */
public final class HealAura {
    private HealAura() {}

    /** How far above or below the bonfire's base a player can stand and still be healed. */
    public static final double REACH_UP = 3.0, REACH_DOWN = 1.5;

    /**
     * True if a player at offset (dx, dy, dz) from the bonfire's base (player feet minus base) is inside a ring of
     * {@code radius} blocks: measured flat on the ground, with a little height either way.
     */
    public static boolean inRange(double dx, double dy, double dz, double radius) {
        if (dy > REACH_UP || dy < -REACH_DOWN) return false;
        return dx * dx + dz * dz <= radius * radius;
    }

    /** How much health to give: {@code amount}, but never past {@code max}. 0 when already full or dead. */
    public static double heal(double health, double max, double amount) {
        if (health <= 0 || health >= max) return 0;
        return Math.min(amount, max - health);
    }

    /** The hit bar over the bonfire: one flame per hit left, grey ash for each hit taken. */
    public static String hitBar(int left, int total) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < total; i++) b.append(i < left ? "<#FFB347>🔥" : "<dark_gray>🔥");
        return b.toString();
    }
}
