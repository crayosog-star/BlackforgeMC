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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Foundry's rules: inside its bounds you're in Survival and can build, and only you can break what you placed.
 * Leaving takes away everything you placed, your agents and anything you picked up in there, but stepping out gives you
 * a short grace period first, so a knockback or a walk to the bridge doesn't cost you a test. Leaving the Arena is
 * immediate. Fall damage, hunger and PvP stay off (LobbyRules and TestArena.onPvp).
 */
public final class Foundry implements Listener {
    private final BlackForgeCore plugin;
    private final TestArena arena;
    private final PlacedBlocks placed = new PlacedBlocks();
    private final Set<UUID> inside = new HashSet<>();
    private final FoundrySystems systems;
    /** Players who stepped out, and when their test gets cleared if they don't come back. */
    private final Map<UUID, Long> outUntil = new HashMap<>();
    private final long graceMs;

    public Foundry(BlackForgeCore plugin, TestArena arena) {
        this.plugin = plugin;
        this.arena = arena;
        this.graceMs = Math.max(0, plugin.getConfig().getInt("foundry.leave-grace-seconds", 15)) * 1000L;
        org.bukkit.World w = arena.world();
        int y = TestArenaBuilder.Y;
        Location[] supply = new Location[FoundryZone.SUPPLY_X.length];
        for (int i = 0; i < supply.length; i++) supply[i] = new Location(w, FoundryZone.SUPPLY_X[i], y + 1, FoundryZone.SUPPLY_Z);
        this.systems = new FoundrySystems(plugin, w, new FoundrySystems.Layout(FoundryZone.LANE_Z, FoundryZone.LANE_X, y + 1,
                new Location(w, FoundryZone.FORGE_X, y + 1, FoundryZone.FORGE_Z),
                new Location(w, (FoundryZone.PEN_X1 + FoundryZone.PEN_X2) / 2 + 0.5, y + 1, (FoundryZone.PEN_Z1 + FoundryZone.PEN_Z2) / 2 + 0.5), FoundryZone.PEN_MOBS,
                new Location(w, 0.5, y + 7.5, FoundryZone.GATE_Z + 0.5), supply,
                new Location(w, FoundryZone.ALLAY_BENCH_X + 0.5, y + 1, FoundryZone.ALLAY_BENCH_Z + 0.5)),
                l -> l.getWorld() == w ? FoundryZone.supplyAt(l.getBlockX(), l.getBlockY(), l.getBlockZ()) : -1, this::inZone,
                l -> FoundryZone.regionAt(l.getBlockX(), l.getBlockZ()), FoundryZone.SHARED_REGION,
                (p, l) -> inZone(l) && (placed.owner(pos(l.getBlock())) == null || p.getUniqueId().equals(placed.owner(pos(l.getBlock())))));
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getPluginManager().registerEvents(new FoundryTips(plugin, this::inZone), plugin);
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
            UUID id = p.getUniqueId();
            if (p.isDead()) continue;
            boolean now = inZone(p.getLocation()), was = inside.contains(id);
            Long until = outUntil.get(id);
            if (now && !was) {
                inside.add(id);
                if (!builder(p)) p.setGameMode(GameMode.SURVIVAL);
                p.sendMessage(net.blackforge.core.Ui.mm("<#ffb347><bold>The Foundry</bold> <dark_gray>· <gray>chests never run out and the Forge is free. Everything you place or carry from here goes when you leave."));
            } else if (now && until != null) {
                outUntil.remove(id);
                if (!builder(p)) p.setGameMode(GameMode.SURVIVAL);
                p.sendActionBar(net.blackforge.core.Ui.mm("<green>Back in the Foundry. Your test is still running."));
            } else if (!now && was) {
                long time = System.currentTimeMillis();
                if (until == null) {
                    outUntil.put(id, time + graceMs);
                    if (!builder(p)) p.setGameMode(GameMode.ADVENTURE);   // no building out here, and the wipe waits
                    p.sendMessage(net.blackforge.core.Ui.mm("<yellow>You stepped out of the Foundry. <gray>Come back within <white>" + graceMs / 1000 + " seconds<gray> or your builds, agents and Foundry items are cleared."));
                } else if (time >= until) {
                    left(p);
                    if (!builder(p)) p.setGameMode(GameMode.ADVENTURE);
                    arena.kit(p);
                    p.sendMessage(net.blackforge.core.Ui.mm("<gray>Your Foundry test was cleared."));
                } else p.sendActionBar(net.blackforge.core.Ui.mm("<yellow>Back to the Foundry in <white>" + ((until - time) / 1000 + 1) + "s<yellow> to keep your test"));
            }
        }
    }

    private void left(Player p) {
        inside.remove(p.getUniqueId());
        outUntil.remove(p.getUniqueId());
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
        systems.shutdown();
    }

    // ------------------------------------------------------------------ blocks

    /** Also covers Miner agents: they break as their owner, so they can't eat the island or another player's blocks. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (builder(e.getPlayer()) || !inZone(b.getLocation())) return;
        if (!FoundryZone.inMineWall(b.getX(), b.getY(), b.getZ()) && !placed.mayBreak(pos(b), e.getPlayer().getUniqueId())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBroken(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (b.getWorld() != arena.world()) return;
        placed.remove(pos(b));
        if (!FoundryZone.inMineWall(b.getX(), b.getY(), b.getZ())) return;
        org.bukkit.Material ore = b.getType();
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (b.getType().isAir()) b.setType(ore); }, 60L);   // the Miner's wall grows back
    }

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
