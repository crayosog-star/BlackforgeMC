package net.alloymc.core.arcade.core;

import java.util.*;

/**
 * Hands out instance slots: separate regions of the match world that are far apart, so every running
 * match has its own copy of its map and a reset can never touch another match.
 */
public final class SlotAllocator {
    public static final int SPACING = 1024;
    private final int max;
    private final Map<Integer, String> owner = new TreeMap<>();

    public SlotAllocator(int max) { this.max = max; }

    /** Reserve a free slot for a match, or -1 when every slot is busy. */
    public synchronized int acquire(String matchId) {
        for (int i = 0; i < max; i++)
            if (!owner.containsKey(i)) { owner.put(i, matchId); return i; }
        return -1;
    }

    public synchronized void release(int slot, String matchId) {
        if (matchId.equals(owner.get(slot))) owner.remove(slot);
    }

    public synchronized String ownerOf(int slot) { return owner.get(slot); }
    public synchronized int used() { return owner.size(); }
    public int max() { return max; }

    /** World X of a slot's origin. Slots run along +X, starting away from 0 to leave room for nothing else. */
    public static int originX(int slot) { return (slot + 1) * SPACING; }

    /** Which slot a world X belongs to (or -1). */
    public static int slotAt(double x) {
        int s = (int) Math.floor((x + SPACING / 2.0) / SPACING) - 1;
        return s < 0 ? -1 : s;
    }
}
