package net.alloymc.core.arcade.core;

import java.util.*;
import java.util.function.Predicate;

/**
 * Picks the map for a new match from that game's own pool: weighted random over every map that is
 * enabled, valid, supports the variant and player count and isn't excluded (e.g. failed to load),
 * avoiding the game's recently played maps whenever another eligible map exists.
 */
public final class MapSelector {
    private final Map<String, Deque<String>> recent = new HashMap<>();   // game -> most recent first
    private final int remember;

    public MapSelector(int remember) { this.remember = Math.max(0, remember); }

    public List<MapDef> eligible(Collection<MapDef> pool, Variant v, int players, Set<String> exclude, Predicate<MapDef> valid) {
        List<MapDef> out = new ArrayList<>();
        for (MapDef m : pool)
            if (m.enabled && m.supports(v) && m.fits(v, players) && !exclude.contains(m.id) && valid.test(m)) out.add(m);
        return out;
    }

    public Optional<MapDef> pick(String game, Collection<MapDef> pool, Variant v, int players, Set<String> exclude, Predicate<MapDef> valid, Random rnd) {
        List<MapDef> ok = eligible(pool, v, players, exclude, valid);
        if (ok.isEmpty()) return Optional.empty();
        Deque<String> rec = recent.getOrDefault(game, new ArrayDeque<>());
        // remember at most (pool size - 1) maps so there is always something fresh to pick
        int window = Math.min(remember, ok.size() - 1);
        Set<String> avoid = new HashSet<>();
        Iterator<String> it = rec.iterator();
        for (int i = 0; i < window && it.hasNext(); i++) avoid.add(it.next());
        List<MapDef> fresh = ok.stream().filter(m -> !avoid.contains(m.id)).toList();
        List<MapDef> from = fresh.isEmpty() ? ok : fresh;
        int total = 0;
        for (MapDef m : from) total += Math.max(1, m.weight);
        int r = rnd.nextInt(total);
        for (MapDef m : from) {
            r -= Math.max(1, m.weight);
            if (r < 0) return Optional.of(m);
        }
        return Optional.of(from.getLast());
    }

    /** Record that a map was actually played (call after it loaded). */
    public void played(String game, String mapId) {
        Deque<String> d = recent.computeIfAbsent(game, g -> new ArrayDeque<>());
        d.remove(mapId);
        d.addFirst(mapId);
        while (d.size() > 16) d.removeLast();
    }

    public List<String> recent(String game) { return List.copyOf(recent.getOrDefault(game, new ArrayDeque<>())); }
}
