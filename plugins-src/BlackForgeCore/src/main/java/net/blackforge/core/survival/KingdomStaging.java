package net.blackforge.core.survival;

import net.blackforge.core.BlackForgeCore;
import net.blackforge.core.Ui;
import net.blackforge.core.arcade.core.Worlds;
import net.blackforge.core.build.Canvas;
import net.blackforge.core.build.Paster;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;

/**
 * Staging for the Infernal Kingdom: its own void world ("kingdom_staging"), so the kingdom can be walked and tested without
 * touching the live Survival spawn or world. /blackforge kingdom stage builds it, tp visits it, remove unloads it (the world
 * folder is left for the host to delete). Staff only (blackforge.admin).
 */
final class KingdomStaging {
    static final String WORLD = "kingdom_staging";
    private final BlackForgeCore plugin;
    private boolean building;

    KingdomStaging(BlackForgeCore plugin) { this.plugin = plugin; }

    boolean command(CommandSender s, String[] a) {
        if (a.length < 1 || !a[0].equalsIgnoreCase("kingdom")) return false;
        if (!s.hasPermission("blackforge.admin")) { s.sendMessage(Ui.mm("<red>You need blackforge.admin for that.")); return true; }
        String sub = a.length > 1 ? a[1].toLowerCase() : "status";
        switch (sub) {
            case "stage" -> stage(s);
            case "tp" -> {
                World w = Bukkit.getWorld(WORLD);
                if (!(s instanceof Player p)) s.sendMessage(Ui.mm("<red>Players only."));
                else if (w == null) s.sendMessage(Ui.mm("<red>Not built yet: /blackforge kingdom stage"));
                else p.teleport(new Location(w, 0.5, InfernalLayout.Y + 1, 76.5, 180f, 0f));
            }
            default -> s.sendMessage(Ui.mm("<gold>Infernal Kingdom staging: <white>" + (Bukkit.getWorld(WORLD) != null ? "world loaded" : "not built")
                    + (new File(plugin.getDataFolder(), "kingdom-staged").exists() ? ", staged" : "") + "<gray>  /blackforge kingdom stage | tp"));
        }
        return true;
    }

    private void stage(CommandSender s) {
        if (building) { s.sendMessage(Ui.mm("<yellow>Already building.")); return; }
        building = true;
        World w = Bukkit.getWorld(WORLD);
        if (w == null) w = new WorldCreator(WORLD).generator(new Worlds.Void()).environment(World.Environment.NORMAL).type(WorldType.FLAT).generateStructures(false).createWorld();
        final World world = w;
        world.setTime(18000);
        world.setGameRule(org.bukkit.GameRule.DO_MOB_SPAWNING, false);
        long t = System.currentTimeMillis();
        Canvas c = InfernalKingdom.build();
        s.sendMessage(Ui.mm("<gold>Pasting the Infernal Kingdom into " + WORLD + ": " + c.size() + " blocks..."));
        Paster.paste(plugin, world, c, 6000, () -> {
            building = false;
            try { new File(plugin.getDataFolder(), "kingdom-staged").createNewFile(); } catch (Exception ignored) { }
            s.sendMessage(Ui.mm("<green>Kingdom staged in " + (System.currentTimeMillis() - t) / 1000 + "s. <gray>/blackforge kingdom tp"));
        });
    }
}
