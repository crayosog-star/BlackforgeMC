package net.blackforge.core.survival;

import net.blackforge.core.BlackForgeCore;
import net.blackforge.core.Ui;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Lag guard: when too many dropped items pile up, everyone is warned 30 seconds ahead and the oldest are cleared
 * (never inside protected spawn, never anything younger than 30 seconds). Staff are told about chunks with a pile-up of
 * mobs, so a runaway farm is found before it hurts the tick rate.
 */
final class LagGuard {
    private static final int ITEM_LIMIT = 600, MOB_LIMIT = 90;

    private final BlackForgeCore plugin;
    private final World world;
    private final Predicate<Location> protectedArea;
    private final Map<Long, Long> alerted = new HashMap<>();
    private boolean clearing;

    LagGuard(BlackForgeCore plugin, World world, Predicate<Location> protectedArea) {
        this.plugin = plugin;
        this.world = world;
        this.protectedArea = protectedArea;
        Bukkit.getScheduler().runTaskTimer(plugin, this::checkItems, 20L * 300, 20L * 300);
        Bukkit.getScheduler().runTaskTimer(plugin, this::checkMobs, 20L * 90, 20L * 90);
    }

    private List<Item> clearable() {
        return world.getEntitiesByClass(Item.class).stream().filter(i -> i.getTicksLived() > 600 && !protectedArea.test(i.getLocation())).toList();
    }

    private void checkItems() {
        if (clearing || world.getPlayers().isEmpty()) return;
        int n = world.getEntitiesByClass(Item.class).size();
        if (n < ITEM_LIMIT) return;
        clearing = true;
        Bukkit.broadcast(Ui.mm("<#ffb347>🧹 <gray>Lag guard: <white>" + n + " <gray>dropped items on the ground. They'll be cleared in <white>30 seconds<gray> - grab what you want!"));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            List<Item> list = clearable();
            list.forEach(Item::remove);
            clearing = false;
            Bukkit.broadcast(Ui.mm("<#ffb347>🧹 <gray>Lag guard cleared <white>" + list.size() + " <gray>old dropped items."));
            plugin.getLogger().info("Lag guard cleared " + list.size() + " items");
        }, 20L * 30);
    }

    private void checkMobs() {
        long now = System.currentTimeMillis();
        for (Chunk c : world.getLoadedChunks()) {
            int n = 0;
            for (Entity e : c.getEntities()) if (e instanceof LivingEntity && !(e instanceof Player)) n++;
            if (n < MOB_LIMIT) continue;
            long key = ((long) c.getX() << 32) ^ (c.getZ() & 0xFFFFFFFFL);
            if (now - alerted.getOrDefault(key, 0L) < 600_000L) continue;
            alerted.put(key, now);
            String msg = "<red>⚠ Lag guard: <white>" + n + " <gray>mobs in one chunk near <white>" + (c.getX() * 16 + 8) + ", " + (c.getZ() * 16 + 8) + "<gray> (" + world.getName() + "). A farm or pile-up?";
            for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("blackforge.staff")) p.sendMessage(Ui.mm(msg));
            plugin.getLogger().warning("Lag guard: " + n + " mobs in chunk " + c.getX() + "," + c.getZ());
        }
    }
}
