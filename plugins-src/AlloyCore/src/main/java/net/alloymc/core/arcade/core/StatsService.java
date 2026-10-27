package net.alloymc.core.arcade.core;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player, per-game statistics: arcade/stats/&lt;uuid&gt;.yml with one section per game
 * (played, wins, losses, kills, deaths, assists, best_streak, best_time, score, playtime, rating, ...).
 * Everything is cached; changed players are written off the main thread every minute and on shutdown.
 */
public final class StatsService {
    private final Plugin plugin;
    private final File dir;
    private final Map<UUID, YamlConfiguration> cache = new ConcurrentHashMap<>();
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();

    public StatsService(Plugin plugin, File dir) {
        this.plugin = plugin;
        this.dir = dir;
        dir.mkdirs();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files != null) for (File f : files) {
            try { cache.put(UUID.fromString(f.getName().replace(".yml", "")), YamlConfiguration.loadConfiguration(f)); }
            catch (Exception e) { plugin.getLogger().warning("[Arcade] bad stats file " + f.getName() + ": " + e.getMessage()); }
        }
        Bukkit.getScheduler().runTaskTimer(plugin, () -> flush(true), 1200L, 1200L);
    }

    private YamlConfiguration of(UUID u) { return cache.computeIfAbsent(u, k -> new YamlConfiguration()); }

    public void name(UUID u, String name) { YamlConfiguration y = of(u); if (!name.equals(y.getString("name"))) { y.set("name", name); dirty.add(u); } }

    public long get(UUID u, String game, String stat) { return of(u).getLong(game + "." + stat); }
    public boolean has(UUID u, String game, String stat) { return of(u).contains(game + "." + stat); }

    public void add(UUID u, String game, String stat, long n) { YamlConfiguration y = of(u); y.set(game + "." + stat, y.getLong(game + "." + stat) + n); dirty.add(u); }
    public void set(UUID u, String game, String stat, long v) { of(u).set(game + "." + stat, v); dirty.add(u); }
    public void max(UUID u, String game, String stat, long v) { if (!has(u, game, stat) || get(u, game, stat) < v) set(u, game, stat, v); }

    /** Lower-is-better records (parkour/dropper times). Returns true if it's a new personal best. */
    public boolean best(UUID u, String game, String stat, long v) {
        if (has(u, game, stat) && get(u, game, stat) <= v) return false;
        set(u, game, stat, v);
        return true;
    }

    public long total(UUID u, String stat) {
        long n = 0;
        YamlConfiguration y = of(u);
        for (String g : y.getKeys(false)) { ConfigurationSection s = y.getConfigurationSection(g); if (s != null) n += s.getLong(stat); }
        return n;
    }

    public record Row(UUID id, String name, long value) {}

    /** Top players for a stat (game null = sum over all games). */
    public List<Row> top(String game, String stat, int n, boolean ascending) {
        List<Row> rows = new ArrayList<>();
        for (var e : cache.entrySet()) {
            YamlConfiguration y = e.getValue();
            String name = y.getString("name");
            if (name == null || name.startsWith("AlloyTester")) continue;
            if (game != null && !y.contains(game + "." + stat)) continue;
            long v = game == null ? total(e.getKey(), stat) : y.getLong(game + "." + stat);
            if (game == null && v == 0) continue;
            rows.add(new Row(e.getKey(), name, v));
        }
        rows.sort(ascending ? Comparator.comparingLong(Row::value) : Comparator.comparingLong(Row::value).reversed());
        return rows.size() > n ? rows.subList(0, n) : rows;
    }

    /** Write changed players. async=true serialises on this thread and writes on another. */
    public void flush(boolean async) {
        if (dirty.isEmpty()) return;
        Map<UUID, String> out = new HashMap<>();
        for (UUID u : new ArrayList<>(dirty)) { dirty.remove(u); out.put(u, of(u).saveToString()); }
        Runnable write = () -> {
            for (var e : out.entrySet()) {
                try {
                    File f = new File(dir, e.getKey() + ".yml"), tmp = new File(dir, e.getKey() + ".yml.tmp");
                    java.nio.file.Files.writeString(tmp.toPath(), e.getValue());
                    java.nio.file.Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception ex) { plugin.getLogger().warning("[Arcade] could not save stats for " + e.getKey() + ": " + ex.getMessage()); dirty.add(e.getKey()); }
            }
        };
        if (async) Bukkit.getScheduler().runTaskAsynchronously(plugin, write); else write.run();
    }
}
