package net.alloymc.core.arcade.core;

import java.util.*;

/** Remembers which (match, player, reason) rewards were paid so nobody is ever paid twice for the same thing. */
public final class RewardLedger {
    private final Set<String> paid = new HashSet<>();
    private final Deque<String> order = new ArrayDeque<>();
    private final int capacity;

    public RewardLedger(int capacity) { this.capacity = capacity; }

    /** True exactly once per key; false if it was already granted. */
    public synchronized boolean claim(String matchId, UUID player, String reason) {
        String k = matchId + "|" + player + "|" + reason;
        if (!paid.add(k)) return false;
        order.addLast(k);
        while (order.size() > capacity) paid.remove(order.removeFirst());
        return true;
    }
}
