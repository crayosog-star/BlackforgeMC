package net.alloymc.core.survival;

import java.util.List;

import net.alloymc.core.survival.SurvivalLayout.HallSpot;
import net.alloymc.core.survival.SurvivalLayout.TradeStall;

/**
 * Where the Survival features stand now that they live in the BlackForge kingdom (spawn upgrade phase 3): the Trading
 * Hall off the Merchant District, the Hall of Fame off the Grand Hall, the Wild gate in the Wilds district and the
 * Drop Zone shafts off it. The old island's {@link SurvivalLayout} coordinates stay for the island builder and previews.
 */
public final class KingdomLayout {
    private KingdomLayout() {}

    public static final int Y = SurvivalLayout.Y;
    static final int D = SpawnUpgradeBuilder.DIST;

    // ------------------------------------------------------------------ the Trading Hall (south-east annex)
    /** Hall walls run x 22..44, z 95..107 on a platform x 20..46, z 93..109. */
    public static final int TH_X1 = 22, TH_X2 = 44, TH_Z1 = 95, TH_Z2 = 107;
    public static boolean inTradingHall(double x, double y, double z) {
        return x >= TH_X1 + 1 && x < TH_X2 && z >= TH_Z1 + 1 && z < TH_Z2 && y >= Y && y < Y + 12;
    }
    public static final List<TradeStall> STALLS = List.of(
            new TradeStall("librarian", 26, 99, "lectern", "south"), new TradeStall("armorer", 30, 99, "blast_furnace", "south"),
            new TradeStall("toolsmith", 34, 99, "smithing_table", "south"), new TradeStall("weaponsmith", 38, 99, "grindstone", "south"),
            new TradeStall("cleric", 42, 99, "brewing_stand", "south"), new TradeStall("fletcher", 26, 103, "fletching_table", "north"),
            new TradeStall("farmer", 30, 103, "composter", "north"), new TradeStall("butcher", 34, 103, "smoker", "north"),
            new TradeStall("mason", 38, 103, "stonecutter", "north"), new TradeStall("shepherd", 42, 103, "loom", "north"));
    /** Where someone without the rank is put back: just outside the hall's door. */
    public static final double TH_DOOR_X = 32.5, TH_DOOR_Z = 93.5;

    // ------------------------------------------------------------------ the Hall of Fame (north-east annex)
    public static final List<HallSpot> HALL = List.of(new HallSpot(26, -103), new HallSpot(30, -103), new HallSpot(34, -103), new HallSpot(38, -103), new HallSpot(42, -103));
    public static final int HALL_CX = 34, HALL_CZ = -103;

    // ------------------------------------------------------------------ boards in the Grand Hall
    /** Rules board and leaderboard: inside the Grand Hall's door wall, either side of the entrance, facing in. */
    public static final double BOARD_Z = -(D - 6) - 0.1 + 0.0;

    // ------------------------------------------------------------------ the Wild gate (Wilds district) and the Drop Zone (south-west annex)
    /** The Wild gate is the passage through the arch's far curtain: x about -115.5, z -3..3. */
    public static boolean inWildGate(double x, double y, double z) {
        return Math.abs(x - (-D - 4.5)) <= 1.2 && Math.abs(z - 0.5) <= 3.5 && y >= Y + 1 && y <= Y + 10;
    }
    /** The Drop Zone: two 3x3 shafts through the annex, centred x = DROP_X, z = SCRAP_Z (Scrapyard) and KOTH_Z (KOTH arena). */
    public static final int DROP_X = -103, SCRAP_Z = 26, KOTH_Z = 36;
    public static boolean inShaft(double x, double y, double z, int shaftZ) {
        return x >= DROP_X - 1 && x < DROP_X + 2 && z >= shaftZ - 1 && z < shaftZ + 2 && y < Y - 1 && y > Y - 60;
    }
}
