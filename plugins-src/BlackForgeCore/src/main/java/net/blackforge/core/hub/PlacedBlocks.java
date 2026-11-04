package net.blackforge.core.hub;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Who placed which block in the Foundry, so only they can break it and it goes when they leave. No Bukkit types. */
final class PlacedBlocks {
    record Pos(int x, int y, int z) {}

    private final Map<Pos, UUID> owners = new HashMap<>();

    void add(Pos at, UUID who) { owners.put(at, who); }
    void remove(Pos at) { owners.remove(at); }
    UUID owner(Pos at) { return owners.get(at); }

    /** Nobody may break the island itself, or someone else's block. */
    boolean mayBreak(Pos at, UUID who) { return who.equals(owners.get(at)); }

    /** Forgets everything this player placed, and returns it so the caller can clear the blocks. */
    List<Pos> removeAll(UUID who) {
        List<Pos> gone = new ArrayList<>();
        owners.entrySet().removeIf(en -> {
            if (!en.getValue().equals(who)) return false;
            gone.add(en.getKey());
            return true;
        });
        return gone;
    }

    List<UUID> everyone() { return owners.values().stream().distinct().toList(); }
}
