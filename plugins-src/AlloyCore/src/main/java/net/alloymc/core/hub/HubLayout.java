package net.alloymc.core.hub;

import java.util.List;

/** Fixed coordinates shared by the hub builder and the hub runtime. */
public final class HubLayout {
    private HubLayout() {}

    public static final int Y = 100;            // plaza surface; players stand at Y+1
    public static final double SPAWN_X = 0.5, SPAWN_Y = Y + 3, SPAWN_Z = 0.5;
    public static final float SPAWN_YAW = 180f, SPAWN_PITCH = -10f;
    public static final int VOID_Y = 60;

    /**
     * A portal gate. (cx, cz) is the portal centre at ground level; {@code alongX}
     * means the gate plane runs along the X axis (gate faces +/-Z).
     */
    public record Gate(String server, String title, String color, String theme, int cx, int cz, boolean alongX) {
        public static final int HALF_WIDTH = 3, HEIGHT = 10;

        /** True if the block position is inside this gate's portal opening. */
        public boolean contains(double x, double y, double z) {
            double u = alongX ? x - (cx + 0.5) : z - (cz + 0.5);
            double w = alongX ? z - (cz + 0.5) : x - (cx + 0.5);
            return Math.abs(u) <= HALF_WIDTH + 0.5 && Math.abs(w) <= 1.2 && y >= Y + 1 && y <= Y + 1 + HEIGHT;
        }
    }

    public static final int GATE_DIST = 32;
    public static final List<Gate> GATES = List.of(
        new Gate("skyblock", "SKYBLOCK", "#4fd1c5", "oxidized", 0, -GATE_DIST, true),
        new Gate("survival", "SURVIVAL", "#d9dde3", "iron", GATE_DIST, 0, false),
        new Gate("factions", "FACTIONS", "#e5484d", "netherite", -GATE_DIST, 0, false),
        new Gate("arcade", "ARCADE", "#ffc53d", "gold", 0, GATE_DIST, true)
    );
}
