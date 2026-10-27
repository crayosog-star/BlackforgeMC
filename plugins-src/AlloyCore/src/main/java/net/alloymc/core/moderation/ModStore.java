package net.alloymc.core.moderation;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.FileWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Moderation state shared by every BlackForgeMC server: they all run on this machine, so mutes and
 * offence counts live in one file next to the servers (MinecraftNetwork/shared) and are re-read
 * when another server changes them.
 */
public final class ModStore {
    public record Mute(String name, long until, String reason, String by) {}

    private final Plugin plugin;
    private final File dir, file;
    private long loadedStamp = -1;
    private final Map<UUID, Mute> mutes = new HashMap<>();
    private final Map<UUID, Integer> offences = new HashMap<>();

    public ModStore(Plugin plugin) {
        this.plugin = plugin;
        this.dir = new File(serverDir(plugin).getParentFile(), "shared");
        dir.mkdirs();
        this.file = new File(dir, "moderation.yml");
        reloadIfChanged();
    }

    public synchronized void reloadIfChanged() {
        long stamp = file.exists() ? file.lastModified() : 0;
        if (stamp == loadedStamp) return;
        loadedStamp = stamp;
        mutes.clear();
        offences.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        var ms = y.getConfigurationSection("mutes");
        if (ms != null) for (String k : ms.getKeys(false)) {
            var s = ms.getConfigurationSection(k);
            mutes.put(UUID.fromString(k), new Mute(s.getString("name"), s.getLong("until"), s.getString("reason"), s.getString("by")));
        }
        var os = y.getConfigurationSection("offences");
        if (os != null) for (String k : os.getKeys(false)) offences.put(UUID.fromString(k), os.getInt(k));
    }

    private synchronized void save() {
        YamlConfiguration y = new YamlConfiguration();
        long now = System.currentTimeMillis();
        mutes.forEach((u, m) -> {
            if (m.until() < now) return;
            String b = "mutes." + u + ".";
            y.set(b + "name", m.name()); y.set(b + "until", m.until()); y.set(b + "reason", m.reason()); y.set(b + "by", m.by());
        });
        offences.forEach((u, n) -> y.set("offences." + u, n));
        try { y.save(file); loadedStamp = file.lastModified(); } catch (Exception e) { plugin.getLogger().warning("moderation save failed: " + e.getMessage()); }
    }

    public synchronized Mute muteOf(UUID id) {
        reloadIfChanged();
        Mute m = mutes.get(id);
        if (m != null && m.until() < System.currentTimeMillis()) { mutes.remove(id); return null; }
        return m;
    }

    public synchronized void mute(UUID id, String name, long millis, String reason, String by) {
        reloadIfChanged();
        mutes.put(id, new Mute(name, System.currentTimeMillis() + millis, reason, by));
        save();
        log("MUTE " + name + " for " + fmt(millis) + " by " + by + ": " + reason);
    }

    public synchronized boolean unmute(UUID id, String by) {
        reloadIfChanged();
        Mute m = mutes.remove(id);
        save();
        if (m != null) log("UNMUTE " + m.name() + " by " + by);
        return m != null;
    }

    public synchronized List<Mute> activeMutes() {
        reloadIfChanged();
        long now = System.currentTimeMillis();
        return mutes.values().stream().filter(m -> m.until() > now).toList();
    }

    /** Bumps and returns this player's network-wide offence count (drives escalating mutes). */
    public synchronized int addOffence(UUID id) {
        reloadIfChanged();
        int n = offences.getOrDefault(id, 0) + 1;
        offences.put(id, n);
        save();
        return n;
    }

    public synchronized void log(String line) { append("moderation.log", line); }

    public synchronized void append(String fileName, String line) {
        try (FileWriter w = new FileWriter(new File(dir, fileName), true)) {
            w.write("[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "] [" + serverName() + "] " + line + System.lineSeparator());
        } catch (Exception ignored) { }
    }

    private String serverName() { return serverDir(plugin).getName(); }

    /** The server's own folder (the world container is "." so resolve it properly). */
    private static File serverDir(Plugin plugin) {
        try { return plugin.getServer().getWorldContainer().getCanonicalFile(); }
        catch (Exception e) { return plugin.getServer().getWorldContainer().getAbsoluteFile().toPath().normalize().toFile(); }
    }

    public static String fmt(long ms) {
        long m = ms / 60_000;
        if (m < 60) return Math.max(1, m) + "m";
        if (m < 1440) return (m / 60) + "h" + (m % 60 > 0 ? " " + m % 60 + "m" : "");
        return (m / 1440) + "d" + (m % 1440 / 60 > 0 ? " " + m % 1440 / 60 + "h" : "");
    }

    /** "10m", "2h", "3d", "1w" -> millis; -1 if unreadable. */
    public static long parse(String s) {
        try {
            char unit = Character.toLowerCase(s.charAt(s.length() - 1));
            long n = Long.parseLong(Character.isDigit(unit) ? s : s.substring(0, s.length() - 1));
            return switch (unit) {
                case 's' -> n * 1000; case 'h' -> n * 3_600_000; case 'd' -> n * 86_400_000; case 'w' -> n * 604_800_000;
                default -> n * 60_000;
            };
        } catch (Exception e) { return -1; }
    }
}
