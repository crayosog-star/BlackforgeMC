package net.alloymc.core;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.List;
import java.util.UUID;

/**
 * Network-wide nicknames (shared/nicknames.yml): the name shown in chat, the tab list, the kill feed, Arcade messages and
 * the Discord feed on every server. The name tag above the head stays the real account name (Minecraft draws that itself).
 * Staff set them with /netnick &lt;player&gt; &lt;nickname|off&gt;.
 */
public final class Nicks {
    private static File file;
    private static YamlConfiguration data = new YamlConfiguration();
    private static long loaded = -1;

    private Nicks() {}

    static void init(AlloyCore plugin, File shared) {
        file = new File(shared, "nicknames.yml");
        Bukkit.getCommandMap().register("alloy", new Command("netnick", "Set a network-wide nickname", "/netnick <player> <nickname|off>", List.of("bfnick")) {
            @Override public boolean execute(CommandSender s, String label, String[] a) {
                if (!s.hasPermission("alloy.admin")) { s.sendMessage(Ui.mm("<red>Staff only.")); return true; }
                if (a.length < 2) { s.sendMessage(Ui.mm("<red>Usage: /netnick <player> <nickname|off>")); return true; }
                OfflinePlayer t = Bukkit.getOfflinePlayer(a[0]);
                String nick = a[1].equalsIgnoreCase("off") ? null : a[1].replaceAll("[^A-Za-z0-9_]", "");
                if (nick != null && (nick.isEmpty() || nick.length() > 16)) { s.sendMessage(Ui.mm("<red>Nicknames are 1-16 letters, numbers or _.")); return true; }
                set(t.getUniqueId(), nick);
                s.sendMessage(Ui.mm(nick == null ? "<green>Removed " + a[0] + "'s nickname." : "<green>" + a[0] + " is now shown as <white>" + nick
                        + "<green> on every server (from their next join or server switch)."));
                return true;
            }
        });
    }

    private static synchronized YamlConfiguration data() {
        if (file != null && file.lastModified() != loaded) { data = YamlConfiguration.loadConfiguration(file); loaded = file.lastModified(); }
        return data;
    }

    private static synchronized void set(UUID id, String nick) {
        YamlConfiguration y = data();
        y.set(id.toString(), nick);
        try { y.save(file); loaded = -1; } catch (Exception ignored) { }
    }

    /** The name to show for this player: their nickname if they have one, else their account name. */
    public static String of(OfflinePlayer p) {
        String n = data().getString(p.getUniqueId().toString());
        return n != null && !n.isEmpty() ? n : p.getName();
    }
}
