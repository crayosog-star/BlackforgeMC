package net.blackforge.core.survival;

import java.util.List;

/**
 * Where everything stands in the Infernal Kingdom of BlackForge (see docs/INFERNAL_KINGDOM.md). Coordinates are relative to
 * the kingdom's origin: x east, z south, y 0 = the plaza floor. The generator ({@link InfernalKingdom}) and, once the
 * kingdom goes live, the features that are re-pointed at it (crates, rank displays, leaderboards, NPCs, the gate) read them
 * from here, so a feature can be moved without touching its logic.
 */
public final class InfernalLayout {
    private InfernalLayout() {}

    /** World y of the plaza floor (the live spawn's level). */
    public static final int Y = SurvivalLayout.Y;

    public static final int PLAZA_X = 0, PLAZA_Z = 30, PLAZA_R = 46;
    /** The citadel terrace: its front edge, its height, and its depth. */
    public static final int TERRACE_Y = 20, TERRACE_FRONT = -40, TERRACE_BACK = -176, TERRACE_HALF = 148;
    public static final int GATE_Z = 172;

    /** A feature's spot: the crate id / rank id / board name, and where it stands (y is the floor the pedestal sits on). */
    public record Spot(String id, int x, int y, int z) {}

    // ------------------------------------------------------------------ Royal Treasury (under the citadel terrace)
    public static final int TREASURY_X1 = -44, TREASURY_X2 = 44, TREASURY_Z1 = -41, TREASURY_Z2 = -98, TREASURY_H = 18;
    /** The five existing crates, by their config ids: Spark (vote), Cinder (iron), Molten (gold), Forgeheart (netherite), KOTH. */
    public static final List<Spot> CRATES = List.of(
            new Spot("vote", -36, 0, -56), new Spot("iron", -36, 0, -74),
            new Spot("koth", 36, 0, -56), new Spot("gold", 36, 0, -74),
            new Spot("netherite", 0, 0, -88));
    /** /warp crates lands here, facing the Forgeheart dais. */
    public static final int CRATES_WARP_X = 0, CRATES_WARP_Z = -48;

    // ------------------------------------------------------------------ Hall of Ascension (east wing of the terrace), ranks low to high
    public static final int ASC_X1 = 72, ASC_X2 = 140, ASC_Z1 = -48, ASC_Z2 = -90;
    public static final List<Spot> RANKS = List.of(
            new Spot("copper", 82, 20, -69), new Spot("iron", 93, 20, -69), new Spot("gold", 104, 20, -69),
            new Spot("diamond", 116, 20, -69), new Spot("netherite", 130, 20, -69));

    // ------------------------------------------------------------------ Hall of Champions (west wing), the five leaderboards
    public static final int CHAMP_X1 = -140, CHAMP_X2 = -72, CHAMP_Z1 = -48, CHAMP_Z2 = -90;
    public static final List<Spot> BOARDS = List.of(
            new Spot("money", -82, 20, -69), new Spot("mined", -93, 20, -69), new Spot("kills", -104, 20, -69),
            new Spot("playtime", -116, 20, -69), new Spot("deaths", -130, 20, -69));

    // ------------------------------------------------------------------ districts
    public static final int DISTRICT_Y = 4;
    public static final int MERCH_X1 = -142, MERCH_X2 = -58, BLACK_X1 = 58, BLACK_X2 = 142, DISTRICT_Z1 = -6, DISTRICT_Z2 = 112;
    /** Merchant District buildings (id = the existing service) and the Blacksmith's anvil. */
    public static final List<Spot> SHOPS = List.of(
            new Spot("tradinghall", -112, 4, 18), new Spot("auction", -84, 4, 30), new Spot("dailyshop", -112, 4, 58),
            new Spot("sell", -84, 4, 62), new Spot("scrap", -112, 4, 92), new Spot("chestshops", -84, 4, 96));
    public static final Spot BLACKSMITH = new Spot("blacksmith", 100, 4, 52);

    /** The Dragon Gate's walk-through trigger (the Survival wilderness teleport) and where the gate warp lands. */
    public static final Spot WILD_GATE = new Spot("wild", 0, 0, GATE_Z + 6);
    public static final int WILDS_WARP_Z = GATE_Z - 14;
}
