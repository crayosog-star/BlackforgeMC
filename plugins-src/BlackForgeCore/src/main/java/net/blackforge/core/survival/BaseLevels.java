package net.blackforge.core.survival;

import net.blackforge.core.BlackForgeCore;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Each base's level and lifetime Agent deliveries. A base is a claim or island, so moving its Forge doesn't reset it.
 * In the Foundry nothing is saved, and the whole island shares one test level, starting at the top so its tests keep working.
 */
final class BaseLevels {
    private static final class Base { int level = 1; long delivered; }

    private final BlackForgeCore plugin;
    private final boolean sandbox, enabled;
    private final BaseLevelRules.Table table;
    private final File file;
    private final Map<String, Base> bases = new HashMap<>();
    private final Base test = new Base();
    private boolean dirty;

    BaseLevels(BlackForgeCore plugin, boolean sandbox) {
        this.plugin = plugin;
        this.sandbox = sandbox;
        var c = plugin.getConfig();
        this.enabled = c.getBoolean("base-levels.enabled", false);
        this.table = table(plugin, c);
        this.file = new File(plugin.getDataFolder(), "base-levels.yml");
        resetTest();
        if (sandbox || !enabled) return;
        load();
        Bukkit.getScheduler().runTaskTimer(plugin, this::save, 1200L, 1200L);
    }

    private static BaseLevelRules.Table table(BlackForgeCore plugin, FileConfiguration c) {
        BaseLevelRules.Table d = BaseLevelRules.Table.DEFAULT;
        try {
            List<Long> deliveries = c.getLongList("base-levels.deliveries");
            int[] jobs = new int[BaseLevelRules.JOBS.size()];
            for (int i = 0; i < jobs.length; i++) jobs[i] = c.getInt("base-levels.job-levels." + BaseLevelRules.JOBS.get(i), d.jobLevels()[i]);
            return new BaseLevelRules.Table(
                    deliveries.isEmpty() ? d.deliveries() : deliveries.stream().mapToLong(Long::longValue).toArray(),
                    list(c, "base-levels.money", d.money()), list(c, "base-levels.essence", d.essence()), jobs,
                    list(c, "base-levels.allay-levels", d.allayLevels()), list(c, "base-levels.hopper-levels", d.hopperLevels()),
                    list(c, "base-levels.hopper-money", d.hopperMoney()), list(c, "base-levels.hopper-essence", d.hopperEssence()));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("base-levels config is invalid, so the defaults are in use: " + ex.getMessage());
            return d;
        }
    }

    private static int[] list(FileConfiguration c, String key, int[] fallback) {
        List<Integer> v = c.getIntegerList(key);
        return v.isEmpty() ? fallback : v.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Are levels in force here? Everything stays as it was when they aren't. */
    boolean active() { return sandbox || enabled; }

    BaseLevelRules.Table table() { return table; }

    private Base base(String region) { return sandbox ? test : bases.computeIfAbsent(region, r -> new Base()); }

    int level(String region) {
        if (sandbox) return test.level;
        Base b = bases.get(region);
        return b == null ? 1 : b.level;
    }

    long delivered(String region) {
        if (sandbox) return test.delivered;
        Base b = bases.get(region);
        return b == null ? 0 : b.delivered;
    }

    /** Agents put this many items in this base's Forge. */
    void countDelivery(String region, long amount) {
        if (!active() || region == null || amount <= 0) return;
        Base b = base(region);
        b.delivered = b.delivered > Long.MAX_VALUE - amount ? Long.MAX_VALUE : b.delivered + amount;
        dirty = true;
    }

    void setLevel(String region, int level) {
        if (region == null || level < 1 || level > BaseLevelRules.MAX_LEVEL) throw new IllegalArgumentException("bad base level: " + region + " " + level);
        base(region).level = level;
        dirty = true;
    }

    /** The Foundry's island goes back to how a new tester should find it. */
    void resetTest() {
        test.level = BaseLevelRules.MAX_LEVEL;
        test.delivered = 0;
    }

    void shutdown() { save(); }

    private void load() {
        ConfigurationSection all = YamlConfiguration.loadConfiguration(file).getConfigurationSection("bases");
        if (all == null) return;
        for (String i : all.getKeys(false)) {
            ConfigurationSection s = all.getConfigurationSection(i);
            String region = s == null ? null : s.getString("region");
            if (region == null) continue;
            Base b = new Base();
            b.level = Math.max(1, Math.min(BaseLevelRules.MAX_LEVEL, s.getInt("level", 1)));
            b.delivered = Math.max(0, s.getLong("delivered"));
            bases.put(region, b);
        }
    }

    /** Only after a real change, so an unreadable file stays there to be fixed until then. */
    private void save() {
        if (sandbox || !dirty) return;
        YamlConfiguration y = new YamlConfiguration();
        int i = 0;
        for (Map.Entry<String, Base> en : bases.entrySet()) {
            String k = "bases." + i++ + ".";
            y.set(k + "region", en.getKey());
            y.set(k + "level", en.getValue().level);
            y.set(k + "delivered", en.getValue().delivered);
        }
        try { y.save(file); dirty = false; } catch (Exception e) { plugin.getLogger().warning("base level save failed: " + e.getMessage()); }
    }
}
