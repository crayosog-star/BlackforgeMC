package net.blackforge.core.hub;

/**
 * Where the Foundry sits in the Arena world: north of the archery targets. Pure bounds with no Bukkit types, so they
 * can be unit-tested. Height doesn't count: someone falling off the island is still inside until they're brought back.
 */
public final class FoundryZone {
    private FoundryZone() {}

    public static final int MIN_X = -30, MAX_X = 30, MIN_Z = -96, MAX_Z = -46;
    /** Where a player who falls off lands, once the island exists. */
    public static final double ENTRY_X = 0.5, ENTRY_Y = TestArenaBuilder.Y + 1, ENTRY_Z = MAX_Z - 2.5;
    /** Chunk row the Arena must keep loaded to cover the zone. */
    public static final int MIN_CHUNK_Z = MIN_Z >> 4;

    /** The island: a circle north of the bridge. */
    public static final int ISLAND_X = 0, ISLAND_Z = -71, ISLAND_R = 24;
    /** The bridge runs from behind the archery targets to the gate. */
    public static final int BRIDGE_HALF = 3, BRIDGE_FROM_Z = -21, GATE_Z = -46;
    /** The infinite chests, one for each of FoundrySupply's menus. */
    public static final int[] SUPPLY_X = {-8, -4, 0, 4, 8};
    public static final int SUPPLY_Z = -53;
    /** The Forge: a Base Station anyone can use. */
    public static final int FORGE_X = 0, FORGE_Z = -60;
    /** Hopper lanes, tier I to III: source chest on top, the hopper, then the sink one block east. */
    public static final int LANE_X = 14;
    public static final int[] LANE_Z = {-66, -61, -56};
    /** Farmer bench: farmland with a water block in the middle. */
    public static final int FARM_X1 = -18, FARM_X2 = -10, FARM_Z1 = -66, FARM_Z2 = -58;
    /** Fisherman bench: a one-deep pond. */
    public static final int POND_X1 = -19, POND_X2 = -13, POND_Z1 = -78, POND_Z2 = -72;
    /** Miner bench: an ore wall on z = MINE_Z that grows back. */
    public static final int MINE_X1 = 8, MINE_X2 = 18, MINE_Z = -82, MINE_HEIGHT = 3;
    /** Grinder bench: a fenced pen of mobs. */
    public static final int PEN_X1 = -5, PEN_X2 = 5, PEN_Z1 = -90, PEN_Z2 = -80, PEN_MOBS = 4;

    public static boolean inMineWall(int x, int y, int z) {
        return z == MINE_Z && x >= MINE_X1 && x <= MINE_X2 && y > TestArenaBuilder.Y && y <= TestArenaBuilder.Y + MINE_HEIGHT;
    }

    /** Which infinite chest is here, or -1. */
    public static int supplyAt(int x, int y, int z) {
        if (z != SUPPLY_Z || y != TestArenaBuilder.Y + 1) return -1;
        for (int i = 0; i < SUPPLY_X.length; i++) if (SUPPLY_X[i] == x) return i;
        return -1;
    }

    public static boolean contains(double x, double z) {
        return x >= MIN_X && x < MAX_X + 1 && z >= MIN_Z && z < MAX_Z + 1;
    }
}
