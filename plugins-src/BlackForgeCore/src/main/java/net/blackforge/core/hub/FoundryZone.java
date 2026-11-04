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

    public static boolean contains(double x, double z) {
        return x >= MIN_X && x < MAX_X + 1 && z >= MIN_Z && z < MAX_Z + 1;
    }
}
