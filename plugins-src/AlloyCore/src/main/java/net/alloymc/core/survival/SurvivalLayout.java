package net.alloymc.core.survival;

import java.util.List;

/** Fixed coordinates for the floating survival spawn and the KOTH arena. */
public final class SurvivalLayout {
    private SurvivalLayout() {}

    public static final int Y = 150;                       // spawn plaza surface
    public static final double SPAWN_X = 0.5, SPAWN_Y = Y + 1, SPAWN_Z = 32.5;
    public static final float SPAWN_YAW = 180f;
    public static final int SPAWN_RADIUS = 92;             // protected, no fall damage
    public static final int ISLAND_R = 84;

    /** The Farm Ring: decorative crop fields between these radii around the town (protected like the rest of spawn). */
    public static final int FARM_IN = 61, FARM_OUT = 77;
    /** Crop per 45-degree sector, starting east and going clockwise on the map (+z is south). "hall" = the Trading Hall. */
    public static final String[] FARM_SECTORS = {"wheat", "carrots", "melons", "potatoes", "sugar_cane", "beetroots", "hall", "wheat"};
    public static int farmSector(double x, double z) {
        double a = Math.atan2(z, x);
        return Math.floorMod((int) Math.floor((a + Math.PI / 8) / (Math.PI / 4)), 8);
    }
    /** Distance from (x, z) to the nearest radial farm road (roads run between the sectors). */
    public static double farmRoadDist(double x, double z) {
        double a = Math.atan2(z, x), best = 99;
        for (int i = 0; i < 8; i++) {
            double ra = Math.PI / 8 + i * Math.PI / 4;
            double along = x * Math.cos(ra) + z * Math.sin(ra);
            if (along < 0) continue;
            best = Math.min(best, Math.abs(-x * Math.sin(ra) + z * Math.cos(ra)));
        }
        return best;
    }
    public static boolean inFarm(double x, double y, double z) {
        double r = Math.hypot(x, z);
        return r >= FARM_IN && r <= FARM_OUT && y >= Y && y < Y + 5 && !FARM_SECTORS[farmSector(x, z)].equals("hall");
    }

    /** The Trading Hall (north, in the farm ring): villagers with fixed trades, Gold rank and up. */
    public static final int TH_X1 = -15, TH_X2 = 15, TH_Z1 = -79, TH_Z2 = -59;
    public static boolean inTradingHall(double x, double y, double z) {
        return x >= TH_X1 + 1 && x < TH_X2 && z >= TH_Z1 + 1 && z < TH_Z2 && y >= Y && y < Y + 12;
    }
    /** Trading Hall stalls: villager profession, where the villager stands, its workstation block, and which way it faces. */
    public record TradeStall(String profession, int x, int z, String station, String facing) {}
    public static final List<TradeStall> STALLS = List.of(
        new TradeStall("librarian", -6, -76, "lectern", "east"), new TradeStall("armorer", 6, -76, "blast_furnace", "west"),
        new TradeStall("toolsmith", -6, -72, "smithing_table", "east"), new TradeStall("weaponsmith", 6, -72, "grindstone", "west"),
        new TradeStall("cleric", -6, -68, "brewing_stand", "east"), new TradeStall("fletcher", 6, -68, "fletching_table", "west"),
        new TradeStall("farmer", -6, -64, "composter", "east"), new TradeStall("butcher", 6, -64, "smoker", "west"),
        new TradeStall("mason", -2, -77, "stonecutter", "south"), new TradeStall("shepherd", 2, -77, "loom", "south"));
    /** Where someone without the rank is put back: just outside the hall's doors. */
    public static final double TH_DOOR_X = 0.5, TH_DOOR_Z = TH_Z2 + 3.5;

    /** Crate pedestals: id, x, z (vault sits at Y + 2). */
    public record CrateSpot(String crate, int x, int z) {}
    public static final int CRATE_Z = -28;
    public static final List<CrateSpot> CRATES = List.of(
        new CrateSpot("iron", -9, CRATE_Z), new CrateSpot("gold", -3, CRATE_Z - 2),
        new CrateSpot("netherite", 3, CRATE_Z - 2), new CrateSpot("koth", 9, CRATE_Z), new CrateSpot("vote", 0, CRATE_Z - 6));
    public static final double CRATES_WARP_X = 0.5, CRATES_WARP_Z = -19.5;

    /** Portal gates on the spawn island: wild (random teleport) east, koth west. */
    public static final int GATE_X = 42;
    public static boolean inWildGate(double x, double y, double z) { return inGate(x, y, z, GATE_X); }
    public static boolean inKothGate(double x, double y, double z) { return inGate(x, y, z, -GATE_X); }
    private static boolean inGate(double x, double y, double z, int gx) {
        return Math.abs(x - (gx + 0.5)) <= 1.2 && Math.abs(z - 0.5) <= 3.5 && y >= Y + 1 && y <= Y + 10;
    }

    /** The Drop Zone (west of spawn): jump down a shaft to fall into the Scrapyard or the KOTH arena. */
    public static final int DROP_X = -GATE_X + 2, PVP_SHAFT_Z = -5, KOTH_SHAFT_Z = 5;
    public static boolean inShaft(double x, double y, double z, int shaftZ) {
        return x >= DROP_X - 1 && x < DROP_X + 2 && z >= shaftZ - 1 && z < shaftZ + 2 && y < Y - 1 && y > Y - 60;
    }

    /** Hall of Fame pedestals (money, mined, kills, playtime, deaths), in an arc north-east of the plaza. */
    public record HallSpot(int x, int z) {}
    public static final List<HallSpot> HALL = List.of(
        new HallSpot(21, -12), new HallSpot(25, -17), new HallSpot(30, -19), new HallSpot(35, -17), new HallSpot(39, -12));
    public static final int HALL_CX = 30, HALL_CZ = -10;

    /** Rules board (west of spawn, facing east) and leaderboard (east, facing west). */
    public static final int BOARD_X = 17, BOARD_Z = 33;
    /** Market hall with the auction bell, south of spawn. */
    public static final int MARKET_Z = 46, BELL_X = 0, BELL_Z = 50;

    // The Scrapyard: 200x200 walled PvP zone with supply drops, floating south of spawn
    public static final int PVP_X = 0, PVP_Y = 160, PVP_Z = 520, PVP_HALF = 100, PVP_WALL = 18;
    public static boolean inPvp(double x, double y, double z) {
        return Math.abs(x - PVP_X) <= PVP_HALF + 1 && Math.abs(z - PVP_Z) <= PVP_HALF + 1 && y > PVP_Y - 40 && y < PVP_Y + 70;
    }

    // KOTH arena, floating far north of spawn
    public static final int KOTH_X = 0, KOTH_Y = 150, KOTH_Z = -420, KOTH_R = 32;
    public static final int HILL_TOP = KOTH_Y + 7;           // capture platform surface
    public static boolean inCapture(double x, double y, double z) {
        return Math.abs(x - (KOTH_X + 0.5)) <= 2.5 && Math.abs(z - (KOTH_Z + 0.5)) <= 2.5 && y >= HILL_TOP + 0.5 && y <= HILL_TOP + 5;
    }
    public static boolean inArena(double x, double y, double z) {
        double dx = x - KOTH_X, dz = z - KOTH_Z;
        return dx * dx + dz * dz <= (KOTH_R + 1) * (KOTH_R + 1) && y > KOTH_Y - 20 && y < KOTH_Y + 30;
    }
    public static boolean inSpawn(double x, double y, double z) {
        return x * x + z * z <= SPAWN_RADIUS * SPAWN_RADIUS && y > Y - 40 && y < Y + 60;
    }
}
