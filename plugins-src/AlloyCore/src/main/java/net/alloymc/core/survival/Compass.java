package net.alloymc.core.survival;

/** Which way to turn: a direction arrow from a player's facing to a target, kept free of Bukkit for tests. */
final class Compass {
    private Compass() {}

    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    /**
     * The arrow for a target {@code dx}, {@code dz} blocks away from someone facing {@code yaw} (Minecraft yaw:
     * 0 = south/+z, 90 = west/-x, turning right increases it). Up means straight ahead, right means turn right.
     */
    static String arrow(float yaw, double dx, double dz) {
        double want = Math.toDegrees(Math.atan2(-dx, dz));
        double rel = ((want - yaw) % 360 + 540) % 360 - 180;   // -180..180, positive = to your right
        int i = (int) Math.round(rel / 45.0);
        return ARROWS[((i % 8) + 8) % 8];
    }
}
