package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import net.alloymc.core.build.Canvas;
import net.alloymc.core.build.Paster;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;

import java.io.File;
import java.util.*;

/**
 * The AFK zone: /afk takes you to a quiet floating platform; every 10 minutes you stand in it you get Scrap, with a small
 * chance of a crate key. A countdown in the action bar shows the next payout. Leaving the platform resets the clock.
 */
public final class AfkZone {
    private static final int MINUTES = 10, SCRAP = 15;
    private static final double KEY_CHANCE = 0.05;

    private final AlloyCore plugin;
    private final World world;
    private final Location center;
    private final int radius;
    private final Crates crates;
    private final Map<UUID, Integer> seconds = new HashMap<>();
    private TextDisplay label;

    AfkZone(AlloyCore plugin, World world, Location center, int radius, Crates crates, String marker) {
        this.plugin = plugin;
        this.world = world;
        this.center = center;
        this.radius = radius;
        this.crates = crates;
        world.addPluginChunkTicket(center.getBlockX() >> 4, center.getBlockZ() >> 4, plugin);
        if (!new File(plugin.getDataFolder(), marker).exists())
            Paster.paste(plugin, world, platform(), 4000, () -> { try { new File(plugin.getDataFolder(), marker).createNewFile(); } catch (Exception ignored) { } });
        Bukkit.getCommandMap().register("alloy", new Command("afk", "Go to the AFK zone", "/afk", List.of("afkzone")) {
            @Override public boolean execute(CommandSender s, String l, String[] a) {
                if (s instanceof Player p) { p.teleport(center.clone().add(0, 1, 0)); p.sendMessage(Ui.mm("<aqua>💤 <gray>AFK zone: stay on the platform for <#c6a15b>" + SCRAP + " Scrap <gray>every " + MINUTES + " minutes (and a chance at a key).")); }
                return true;
            }
        });
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 20L);
    }

    private Canvas platform() {
        Canvas c = new Canvas();
        int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();
        for (int x = -radius - 1; x <= radius + 1; x++)
            for (int z = -radius - 1; z <= radius + 1; z++) {
                double d = Math.hypot(x, z);
                if (d > radius + 1.2) continue;
                c.set(cx + x, cy, cz + z, d > radius ? "minecraft:sea_lantern" : (x + z) % 2 == 0 ? "minecraft:light_blue_concrete" : "minecraft:white_concrete");
                if (d > radius) c.set(cx + x, cy + 1, cz + z, "minecraft:glass_pane");
            }
        return c;
    }

    private boolean inside(Player p) {
        Location l = p.getLocation();
        return l.getWorld() == world && Math.abs(l.getY() - center.getY() - 1) < 3 && Math.hypot(l.getX() - center.getX(), l.getZ() - center.getZ()) <= radius + 0.5;
    }

    private void tick() {
        if (label == null || !label.isValid()) label = Ui.hologram(center.clone().add(0.5, 4, 0.5), "<aqua><bold>💤 AFK ZONE</bold><newline><gray>" + SCRAP + " Scrap every " + MINUTES + " minutes", 1.4f);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!inside(p)) { seconds.remove(p.getUniqueId()); continue; }
            int s = seconds.merge(p.getUniqueId(), 1, Integer::sum);
            int left = MINUTES * 60 - s % (MINUTES * 60);
            p.sendActionBar(Ui.mm("<aqua>💤 AFK <gray>- next reward in <white>" + left / 60 + ":" + String.format("%02d", left % 60)));
            if (s % (MINUTES * 60) != 0) continue;
            plugin.economy().addScrap(p, SCRAP);
            String msg = "<aqua>💤 <gray>AFK reward: <#c6a15b>+" + SCRAP + " Scrap";
            if (crates != null && Math.random() < KEY_CHANCE) { crates.give(p, "iron", 1); msg += " <gray>and a <#e0895a>Cinder Key<gray>!"; }
            p.sendMessage(Ui.mm(msg));
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.5f, 1.2f);
        }
    }
}
