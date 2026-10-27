package net.alloymc.core;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Backstop for spawns and lobbies (the builds themselves are checked offline by gradle walkcheck):
 * anyone in the protected area who touches lava or fire, or ends up wedged inside a block, is put back
 * on the last safe ground they stood on (or spawn) with no damage. Also offers /stuck.
 */
public final class SpawnSafety implements Listener {
    private final AlloyCore plugin;
    private final Predicate<Location> area;
    private final Function<Player, Location> spawn;
    private final Map<UUID, Location> lastSafe = new HashMap<>();
    private final Map<UUID, Long> lastRescue = new HashMap<>();

    public SpawnSafety(AlloyCore plugin, Predicate<Location> area, Function<Player, Location> spawn) {
        this.plugin = plugin;
        this.area = area;
        this.spawn = spawn;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /** /stuck (and /unstuck): back to spawn, only while inside the protected area. */
    public SpawnSafety withStuckCommand() {
        Bukkit.getCommandMap().register("alloy", new org.bukkit.command.Command("stuck", "Get unstuck at spawn", "/stuck", List.of("unstuck")) {
            @Override public boolean execute(org.bukkit.command.CommandSender s, String label, String[] a) {
                if (!(s instanceof Player p)) return true;
                if (!area.test(p.getLocation())) { p.sendMessage(Ui.mm("<red>/stuck only works around spawn - use <white>/spawn<red>.")); return true; }
                rescue(p, spawn.apply(p), "<gold>Unstuck! <gray>Back to spawn.");
                return true;
            }
        });
        return this;
    }

    private static boolean hazard(Material m) {
        return m == Material.LAVA || m == Material.FIRE || m == Material.SOUL_FIRE;
    }

    private static boolean standable(Location l) {
        Block feet = l.getBlock(), head = feet.getRelative(BlockFace.UP), below = feet.getRelative(BlockFace.DOWN);
        return !feet.getType().isSolid() && !head.getType().isSolid() && !hazard(feet.getType()) && !hazard(head.getType())
                && below.getType().isSolid() && below.getType() != Material.MAGMA_BLOCK && !hazard(below.getType());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!e.hasChangedBlock()) return;
        Player p = e.getPlayer();
        Location to = e.getTo();
        if (!area.test(to)) return;
        Block feet = to.getBlock();
        if (hazard(feet.getType()) || hazard(feet.getRelative(BlockFace.UP).getType())) { rescue(p, null, "<gold>Whoa, that's molten! <gray>Back you go."); return; }
        if (p.isOnGround() && standable(to)) lastSafe.put(p.getUniqueId(), to.clone());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p) || !area.test(p.getLocation())) return;
        switch (e.getCause()) {
            case LAVA, FIRE, FIRE_TICK, HOT_FLOOR, CAMPFIRE -> {
                e.setCancelled(true);
                p.setFireTicks(0);
                if (e.getCause() == EntityDamageEvent.DamageCause.LAVA) rescue(p, null, "<gold>Whoa, that's molten! <gray>Back you go.");
            }
            case SUFFOCATION -> { e.setCancelled(true); rescue(p, null, "<gold>You were stuck in a block - moved you out."); }
            default -> { }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { lastSafe.remove(e.getPlayer().getUniqueId()); lastRescue.remove(e.getPlayer().getUniqueId()); }

    private void rescue(Player p, Location to, String msg) {
        long now = System.currentTimeMillis();
        if (now - lastRescue.getOrDefault(p.getUniqueId(), 0L) < 500) return;
        lastRescue.put(p.getUniqueId(), now);
        if (to == null) {
            Location safe = lastSafe.get(p.getUniqueId());
            to = safe != null && safe.getWorld() == p.getWorld() && standable(safe) ? safe : spawn.apply(p);
        }
        Location dest = to.clone();
        dest.setYaw(p.getLocation().getYaw());
        dest.setPitch(p.getLocation().getPitch());
        p.teleport(dest);
        p.setFireTicks(0);
        p.setFallDistance(0);
        p.setVelocity(new org.bukkit.util.Vector());
        Bukkit.getScheduler().runTaskLater(plugin, () -> p.setFireTicks(0), 2L);
        p.sendActionBar(Ui.mm(msg));
        p.playSound(p.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 0.5f, 1.2f);
    }
}
