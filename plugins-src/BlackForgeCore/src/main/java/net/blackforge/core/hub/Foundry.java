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
import org.bukkit.inventory.ItemStack;

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
    private final Set<UUID> demoed = new HashSet<>();
    private final ItemStack[] bar;

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
                new Location(w, (FoundryZone.PEN_X1 + FoundryZone.PEN_X2) / 2 + 0.5, y + 1, (FoundryZone.PEN_Z1 + FoundryZone.PEN_Z2) / 2 + 0.5), FoundryZone.PEN_SPOTS,
                new Location(w, 0.5, y + 7.5, FoundryZone.GATE_Z + 0.5), supply,
                new Location(w, FoundryZone.ALLAY_BENCH_X + 0.5, y + 1, FoundryZone.ALLAY_BENCH_Z + 0.5), demoSpots(w),
                new int[]{FoundryZone.FARM_X1, FoundryZone.FARM_X2, FoundryZone.FARM_Z1, FoundryZone.FARM_Z2}, signs(w),
                FoundryZone.MINE_X1, FoundryZone.MINE_X2, FoundryZone.MINE_Z, FoundryZone.MINE_HEIGHT,
                new int[]{FoundryZone.POND_X1, FoundryZone.POND_X2, FoundryZone.POND_Z1, FoundryZone.POND_Z2}),
                l -> l.getWorld() == w ? FoundryZone.supplyAt(l.getBlockX(), l.getBlockY(), l.getBlockZ()) : -1, this::inZone,
                l -> FoundryZone.regionAt(l.getBlockX(), l.getBlockZ()), FoundryZone.SHARED_REGION,
                (p, l) -> inZone(l) && (placed.owner(pos(l.getBlock())) == null || p.getUniqueId().equals(placed.owner(pos(l.getBlock())))),
                () -> !inside.isEmpty());   // includes anyone in the leave grace, so stepping out doesn't reset the island
        this.bar = systems.hotbar();
        arena.setFoundryKit(p -> inside.contains(p.getUniqueId()) && !builder(p), systems::hotbar, systems::extras);
        new FoundryCommand(this, arena);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getPluginManager().registerEvents(new FoundryTips(plugin, this::inZone), plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::poll, 20L, 5L);
        arena.setVoidReturn(p -> {
            Location entry = new Location(arena.world(), FoundryZone.ENTRY_X, FoundryZone.ENTRY_Y, FoundryZone.ENTRY_Z, 180f, 0f);
            return inside.contains(p.getUniqueId()) && entry.clone().subtract(0, 1, 0).getBlock().getType().isSolid() ? entry : arena.spawn();
        });
    }

    /** One sign per bench: what to do there and how to get going. */
    private static FoundrySystems.Sign[] signs(org.bukkit.World w) {
        double y = TestArenaBuilder.Y + 1;
        return new FoundrySystems.Sign[]{
                sign(w, (FoundryZone.FARM_X1 + FoundryZone.FARM_X2) / 2.0 + 0.5, y + 2.2, (FoundryZone.FARM_Z1 + FoundryZone.FARM_Z2) / 2.0 + 0.5, "#7ed957", "Farm", "Place a Farmer beside the wheat", "/foundry tp farm"),
                sign(w, (FoundryZone.POND_X1 + FoundryZone.POND_X2) / 2.0 + 0.5, y + 1.6, (FoundryZone.POND_Z1 + FoundryZone.POND_Z2) / 2.0 + 0.5, "#5ec8ff", "Pond", "Place a Fisherman at the water", "/foundry tp pond"),
                sign(w, (FoundryZone.MINE_X1 + FoundryZone.MINE_X2) / 2.0 + 0.5, y + FoundryZone.MINE_HEIGHT + 0.6, FoundryZone.MINE_Z + 0.5, "#c6a15b", "Mine", "Place a Miner facing this wall", "/foundry tp mine"),
                sign(w, 0.5, y + 2.2, FoundryZone.PEN_Z2 + 1.5, "#ff5e5e", "Grinder Pen", "Place a Grinder against any fence", "/foundry tp pen"),
                sign(w, FoundryZone.LANE_X - 2.5, y + 2.0, FoundryZone.LANE_Z[1] + 2.5, "#ffb347", "Hopper Lanes", "Live speed against rated speed", "/foundry status"),
                sign(w, (FoundryZone.PLOT_X1 + FoundryZone.PLOT_X2) / 2.0 + 0.5, y + 1.6, (FoundryZone.PLOT_Z1 + FoundryZone.PLOT_Z2) / 2.0 + 0.5, "#e8e4d8", "Build Plot", "Build your own base here", "/foundry tp plot"),
        };
    }

    private static FoundrySystems.Sign sign(org.bukkit.World w, double x, double y, double z, String color, String title, String how, String command) {
        return new FoundrySystems.Sign(new Location(w, x, y, z), "<" + color + "><bold>" + title + "</bold><newline><gray>" + how + "<newline><white>" + command);
    }

    private static Location[] demoSpots(org.bukkit.World w) {
        Location[] out = new Location[FoundryZone.DEMO.length];
        for (int i = 0; i < out.length; i++) out[i] = new Location(w, FoundryZone.DEMO[i][0], TestArenaBuilder.Y + 1, FoundryZone.DEMO[i][1]);
        return out;
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
                if (inside.size() == 1 && systems.allayTier() == 0) systems.setAllayTier(id, 1);
                if (builder(p)) p.sendMessage(net.blackforge.core.Ui.mm("<gray>You're building in Creative, so blocks you place here stay. <white>/foundry start<gray> tests as a player."));
                else {
                    p.setGameMode(GameMode.SURVIVAL);
                    arena.kit(p);
                }
                p.sendMessage(net.blackforge.core.Ui.mm("<#ffb347><bold>The Foundry</bold> <dark_gray>· <gray>chests never run out and the Forge is free. Everything you place or carry from here goes when you leave. <click:run_command:'/foundry'><white><u>/foundry</u></white></click><gray> lists every test command."));
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
            // only while standing in the zone: someone in the leave grace keeps their test, not a free refill outside
            if (now && inside.contains(id) && !builder(p) && p.getItemOnCursor().getType().isAir()) restock(p);
        }
    }

    /** The hotbar's test tools come straight back when used. An empty slot stays free, so a hand can open the Forge's storage. */
    private void restock(Player p) {
        var inv = p.getInventory();
        for (int i = 0; i < bar.length; i++) {
            if (bar[i] == null) continue;
            ItemStack now = inv.getItem(i);
            if (now == null || now.getType().isAir()) inv.setItem(i, bar[i].clone());
        }
    }

    // ------------------------------------------------------------------ what /foundry uses

    FoundrySystems systems() { return systems; }
    boolean testing(Player p) { return inside.contains(p.getUniqueId()) && arena.inArena(p); }
    int testers() { return inside.size(); }

    void tell(String text) {
        for (UUID id : inside) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.sendMessage(net.blackforge.core.Ui.mm(text));
        }
    }

    /** Starts a test for anyone in the Arena, including staff in Creative: they test as a player does. */
    void begin(Player p) {
        p.teleport(new Location(arena.world(), FoundryZone.ENTRY_X, FoundryZone.ENTRY_Y, FoundryZone.ENTRY_Z, 180f, 0f));
        p.setFallDistance(0);
        p.setGameMode(GameMode.SURVIVAL);
        p.setFlying(false);
        p.setAllowFlight(false);
        inside.add(p.getUniqueId());
        outUntil.remove(p.getUniqueId());
        arena.kit(p);
        if (systems.allayTier() == 0) systems.setAllayTier(p.getUniqueId(), 1);
    }

    /** False if there was nothing to stop. */
    boolean end(Player p) {
        if (!inside.contains(p.getUniqueId())) return false;
        left(p);
        p.teleport(arena.spawn());
        p.setGameMode(GameMode.ADVENTURE);
        arena.kit(p);
        return true;
    }

    /** One set per test, so running it twice doesn't stack agents on the same spots. */
    String demo(Player p) {
        if (!demoed.add(p.getUniqueId())) return "<gray>Your demo agents are already running. <white>/foundry status<gray> shows them.";
        int n = systems.demo(p);
        if (n == 0) {
            demoed.remove(p.getUniqueId());
            return "<gray>No demo agents went down: bench spots are taken or blocked, or you're at your agent limit. <white>/foundry status<gray> shows yours.";
        }
        return "<green>" + n + " demo agents placed at the benches. <gray>What they make goes to the Forge. <white>/foundry status<gray> shows where each one delivers.";
    }

    /** /foundry check places the demo set too, so a /foundry demo after it doesn't stack a second one. Nothing placed, nothing marked. */
    void check(Player p, java.util.function.Consumer<String> say) {
        if (FoundrySystems.placedAny(systems.check(p, say))) demoed.add(p.getUniqueId());
    }

    private void left(Player p) {
        inside.remove(p.getUniqueId());
        outUntil.remove(p.getUniqueId());
        wipe(p.getUniqueId());
    }

    private void wipe(UUID who) {
        demoed.remove(who);
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
