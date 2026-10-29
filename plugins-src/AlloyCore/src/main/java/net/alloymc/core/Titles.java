package net.alloymc.core;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;

/**
 * Staff titles like OWNER (shared/titles.yml: uuid -&gt; title), shown before the name in chat and the tab list with a
 * molten gold shimmer that sweeps across them, like the BLACKFORGE sign. A title replaces the supporter badge, and the
 * hub announces title holders when they join. Explicit UUIDs, not a permission, so ops don't all become OWNER.
 */
public final class Titles {
    private static File file;
    private static YamlConfiguration data = new YamlConfiguration();
    private static long loaded = -1;
    private static int phase;

    private Titles() {}

    static void init(Plugin plugin, File shared) {
        file = new File(shared, "titles.yml");
        // shimmer: move the gradient a step every few ticks and repaint title holders' names
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            phase = (phase + 1) % 40;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (of(p) == null) continue;
                var name = Ui.mm(badge(p) + "<white>" + Nicks.of(p));
                p.playerListName(name);
                p.displayName(name);
            }
        }, 40L, 3L);
    }

    private static synchronized YamlConfiguration data() {
        if (file != null && file.lastModified() != loaded) { data = YamlConfiguration.loadConfiguration(file); loaded = file.lastModified(); }
        return data;
    }

    /** The player's title (e.g. "OWNER"), or null. */
    public static String of(Player p) {
        String t = data().getString(p.getUniqueId().toString());
        return t == null || t.isBlank() ? null : t;
    }

    /** "👑 OWNER " in shimmering molten gold, at the current point of the shimmer. Empty if no title. */
    public static String badge(Player p) {
        String t = of(p);
        if (t == null) return "";
        double ph = phase / 20.0 - 1;   // -1 .. 1
        return "<gradient:#fff6c8:#ffc53d:#ff8a00:#ffc53d:#fff6c8:" + String.format(java.util.Locale.ROOT, "%.2f", ph) + "><bold>👑 " + t + "</bold></gradient> ";
    }
}
