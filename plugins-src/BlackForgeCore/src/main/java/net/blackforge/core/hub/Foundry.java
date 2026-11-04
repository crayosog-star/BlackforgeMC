package net.blackforge.core.hub;

import net.blackforge.core.BlackForgeCore;
import net.blackforge.core.survival.FoundrySystems;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The Foundry's rules: inside its bounds you're in Survival and can build, only you can break what you placed, and
 * leaving takes away everything you placed, your agents and anything you picked up in there. Fall damage, hunger and
 * PvP stay off (LobbyRules and TestArena.onPvp).
 */
public final class Foundry implements Listener {
    private final TestArena arena;
    private final PlacedBlocks placed = new PlacedBlocks();
    private final Set<UUID> inside = new HashSet<>();
    private final FoundrySystems systems;

    public Foundry(BlackForgeCore plugin, TestArena arena) {
        this.arena = arena;
        this.systems = new FoundrySystems(plugin, this::inZone,
                (p, l) -> inZone(l) && (placed.owner(pos(l.getBlock())) == null || p.getUniqueId().equals(placed.owner(pos(l.getBlock())))));
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::poll, 20L, 5L);
        arena.setVoidReturn(p -> {
            Location entry = new Location(arena.world(), FoundryZone.ENTRY_X, FoundryZone.ENTRY_Y, FoundryZone.ENTRY_Z, 180f, 0f);
            return inside.contains(p.getUniqueId()) && entry.clone().subtract(0, 1, 0).getBlock().getType().isSolid() ? entry : arena.spawn();
        });
    }

    private boolean inZone(Location l) { return l.getWorld() == arena.world() && FoundryZone.contains(l.getX(), l.getZ()); }

    /** Staff building the island itself in Creative: their blocks are permanent and every rule here skips them. */
    private static boolean builder(Player p) { return p.getGameMode() == GameMode.CREATIVE && p.hasPermission("blackforge.admin"); }

    private static PlacedBlocks.Pos pos(Block b) { return new PlacedBlocks.Pos(b.getX(), b.getY(), b.getZ()); }

    // ------------------------------------------------------------------ going in and out

    /** A poll rather than move events, so teleports, knockback and abilities all count. */
    private void poll() {
        for (Player p : arena.world().getPlayers()) {
            boolean now = inZone(p.getLocation()), was = inside.contains(p.getUniqueId());
            if (now == was || p.isDead()) continue;
            if (now) {
                inside.add(p.getUniqueId());
                if (!builder(p)) p.setGameMode(GameMode.SURVIVAL);
            } else {
                left(p);
                if (!builder(p)) p.setGameMode(GameMode.ADVENTURE);
                arena.kit(p);
            }
        }
    }

    private void left(Player p) {
        inside.remove(p.getUniqueId());
        wipe(p.getUniqueId());
    }

    private void wipe(UUID who) {
        for (PlacedBlocks.Pos at : placed.removeAll(who)) {
            Block b = arena.world().getBlockAt(at.x(), at.y(), at.z());
            if (b.getState(false) instanceof Container c) c.getInventory().clear();
            b.setType(org.bukkit.Material.AIR);
        }
        systems.clear(who);
    }

    /** The hub's own world change handler gives back the hub kit and game mode. */
    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        if (e.getFrom() == arena.world() && inside.contains(e.getPlayer().getUniqueId())) left(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (!inside.contains(p.getUniqueId())) return;
        left(p);
        if (!builder(p)) p.setGameMode(GameMode.ADVENTURE);   // don't come back to the hub in Survival
    }

    public void shutdown() {
        for (UUID who : placed.everyone()) wipe(who);
        for (UUID who : inside) systems.clear(who);
    }

    // ------------------------------------------------------------------ blocks

    /** Also covers Miner agents: they break as their owner, so they can't eat the island or another player's blocks. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (builder(e.getPlayer()) || !inZone(b.getLocation())) return;
        if (!placed.mayBreak(pos(b), e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBroken(BlockBreakEvent e) { placed.remove(pos(e.getBlock())); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        if (!builder(e.getPlayer()) && inZone(b.getLocation())) placed.add(pos(b), e.getPlayer().getUniqueId());
    }

    /** LobbyRules doesn't cover buckets, because nobody could use one in Adventure. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        if (builder(e.getPlayer()) || e.getBlock().getWorld() != arena.world()) return;
        if (!inZone(e.getBlock().getLocation())) e.setCancelled(true);
        else placed.add(pos(e.getBlock()), e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        if (builder(e.getPlayer()) || e.getBlock().getWorld() != arena.world()) return;
        if (!inZone(e.getBlock().getLocation()) || !placed.mayBreak(pos(e.getBlock()), e.getPlayer().getUniqueId())) e.setCancelled(true);
        else placed.remove(pos(e.getBlock()));
    }
}
