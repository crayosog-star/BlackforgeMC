package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;

import java.io.File;
import java.util.List;

/**
 * /showcase: a warp to the build of the week. Staff point it at a player's build with
 * /showcase set <builder> <title...> (stand where visitors should arrive); /showcase clear removes it.
 */
final class ShowcaseWarp {
    private final AlloyCore plugin;
    private final File file;
    private YamlConfiguration cfg;
    private TextDisplay holo;

    ShowcaseWarp(AlloyCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "showcase.yml");
        this.cfg = YamlConfiguration.loadConfiguration(file);
        Bukkit.getCommandMap().register("alloy", new Command("showcase", "Visit the build of the week", "/showcase", List.of("buildoftheweek")) {
            @Override public boolean execute(CommandSender s, String label, String[] a) { command(s, a); return true; }
            @Override public List<String> tabComplete(CommandSender s, String alias, String[] a) { return a.length == 1 && s.hasPermission("alloy.admin") ? List.of("set", "clear", "info") : List.of(); }
        });
        Bukkit.getScheduler().runTaskTimer(plugin, this::hologram, 200L, 600L);
    }

    private Location where() {
        if (!cfg.contains("world")) return null;
        World w = Bukkit.getWorld(cfg.getString("world"));
        return w == null ? null : new Location(w, cfg.getDouble("x"), cfg.getDouble("y"), cfg.getDouble("z"), (float) cfg.getDouble("yaw"), (float) cfg.getDouble("pitch"));
    }

    private void command(CommandSender s, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase() : "";
        if ((sub.equals("set") || sub.equals("clear")) && !s.hasPermission("alloy.admin")) { s.sendMessage(Ui.mm("<red>Staff only.")); return; }
        if (sub.equals("set") && s instanceof Player p) {
            if (a.length < 3) { p.sendMessage(Ui.mm("<red>/showcase set <builder> <title...>")); return; }
            Location l = p.getLocation();
            cfg.set("world", l.getWorld().getName()); cfg.set("x", l.getX()); cfg.set("y", l.getY()); cfg.set("z", l.getZ());
            cfg.set("yaw", (double) l.getYaw()); cfg.set("pitch", (double) l.getPitch());
            cfg.set("builder", a[1]); cfg.set("title", String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length)));
            save();
            hologramRefresh();
            p.sendMessage(Ui.mm("<green>Showcase set: <white>" + cfg.getString("title") + " <gray>by <white>" + a[1]));
            Bukkit.broadcast(Ui.mm("<#ffb347>🏗 <white>New Build of the Week: <#ffd27a>" + cfg.getString("title") + " <gray>by <white>" + a[1] + "<gray>! <click:run_command:'/showcase'><green>[VISIT]</green></click>"));
            return;
        }
        if (sub.equals("clear")) { cfg = new YamlConfiguration(); save(); hologramRefresh(); s.sendMessage(Ui.mm("<green>Showcase cleared.")); return; }
        Location l = where();
        if (l == null) { s.sendMessage(Ui.mm("<gray>No build is being showcased right now.")); return; }
        if (sub.equals("info") || !(s instanceof Player)) { s.sendMessage(Ui.mm("<white>" + cfg.getString("title") + " <gray>by <white>" + cfg.getString("builder"))); return; }
        Player p = (Player) s;
        p.teleportAsync(l);
        p.sendMessage(Ui.mm("<#ffb347>🏗 <gray>Build of the Week: <white>" + cfg.getString("title") + " <gray>by <white>" + cfg.getString("builder") + "<gray>. Look, don't touch!"));
    }

    private void save() { try { cfg.save(file); } catch (Exception e) { plugin.getLogger().warning("showcase save: " + e); } }

    private void hologramRefresh() { if (holo != null) holo.remove(); holo = null; hologram(); }

    private void hologram() {
        Location l = where();
        if (l == null) { if (holo != null) { holo.remove(); holo = null; } return; }
        if (holo != null && holo.isValid()) return;
        holo = Ui.hologram(l.clone().add(0, 2.6, 0), "<#ffd27a><bold>🏗 BUILD OF THE WEEK</bold><newline><white>" + cfg.getString("title") + "<newline><gray>by <white>" + cfg.getString("builder"), 1.2f);
    }
}
