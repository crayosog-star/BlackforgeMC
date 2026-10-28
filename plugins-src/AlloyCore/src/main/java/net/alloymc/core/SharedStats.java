package net.alloymc.core;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.*;

/**
 * Network-wide totals in shared/stats/&lt;uuid&gt;.yml: player kills (open worlds), arcade kills, games and wins.
 * A player is only ever on one server, so each file is written by one server at a time. The hub reads them all
 * for its leaderboards.
 */
public final class SharedStats {
    private final File dir;

    public SharedStats(File shared) {
        this.dir = new File(shared, "stats");
        dir.mkdirs();
        Hooks.onKill((killer, victim, where) -> add(killer, where.startsWith("arcade") ? "arcade_kills" : "kills", 1));
        Hooks.onArcadeEnd((p, game, won) -> { add(p, "arcade_games", 1); if (won) add(p, "arcade_wins", 1); });
    }

    public synchronized void add(Player p, String key, long n) {
        File f = new File(dir, p.getUniqueId() + ".yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        y.set("name", p.getName());
        y.set(key, y.getLong(key) + n);
        try { y.save(f); } catch (Exception ignored) { }
    }

    public record Row(String name, long value) {}

    /** Top players for a stat, across the whole network. */
    public List<Row> top(String key, int n) {
        List<Row> rows = new ArrayList<>();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".yml"));
        if (files != null) for (File f : files) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            long v = y.getLong(key);
            String name = y.getString("name", "?");
            if (v > 0 && !name.startsWith("AlloyTester")) rows.add(new Row(name, v));
        }
        rows.sort(Comparator.comparingLong(Row::value).reversed());
        return rows.size() > n ? rows.subList(0, n) : rows;
    }
}
