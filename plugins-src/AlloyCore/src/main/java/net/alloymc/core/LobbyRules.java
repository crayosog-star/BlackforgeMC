package net.alloymc.core;

import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.weather.WeatherChangeEvent;

import java.util.function.Predicate;

/**
 * Protection for lobby worlds: no griefing, no damage, no hunger, no fire.
 * Players for whom {@code exempt} is true (e.g. in an arcade game) are skipped.
 */
public final class LobbyRules implements Listener {
    private final Predicate<Player> exempt;
    private Predicate<Player> itemsFree = p -> false;

    public LobbyRules(Predicate<Player> exempt) { this.exempt = exempt; }

    /** Players for whom this is true may still move, drop and pick up items (e.g. in the hub's Arena) while every other rule stays on. */
    public LobbyRules allowItems(Predicate<Player> who) { this.itemsFree = who; return this; }

    private boolean itemsOk(Player p) { return free(p) || itemsFree.test(p); }

    public static void setupWorld(AlloyCore plugin, World w, long time) {
        w.setTime(time);
        w.setStorm(false);
        w.setThundering(false);
        // Gamerule ids changed across versions; match by keyword so this keeps working.
        for (String rule : w.getGameRules()) {
            String r = rule.toLowerCase();
            String val = null;
            if (r.contains("daylight") || r.contains("advance_time")) val = "false";
            else if (r.contains("weather")) val = "false";
            else if (r.contains("mob_spawning") || r.contains("mobspawning") || r.contains("spawn_mobs")) val = "false";
            else if (r.contains("firetick") || r.contains("fire_tick")) val = "false";
            else if (r.contains("fire_spread")) val = "0";
            else if (r.contains("announceadvancements") || r.contains("show_advancement")) val = "false";
            else if (r.contains("immediate_respawn") || r.contains("immediaterespawn")) val = "true";
            if (val != null) {
                try {
                    @SuppressWarnings("deprecation") boolean ok = w.setGameRuleValue(rule, val);
                    plugin.getLogger().info("gamerule " + rule + " = " + val + (ok ? "" : " (rejected)"));
                } catch (Exception ignored) { }
            }
        }
    }

    private boolean free(Player p) {
        return exempt.test(p) || (p.getGameMode() == GameMode.CREATIVE && p.hasPermission("alloy.admin"));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { if (!free(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { if (!free(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        if (free(e.getPlayer())) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        if (e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK && b.getType().isInteractable())
            e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        if (e.getAction() == org.bukkit.event.block.Action.PHYSICAL && b.getType() == org.bukkit.Material.FARMLAND) e.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && !exempt.test(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onHunger(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && exempt.test(p)) return;
        e.setCancelled(true);
        e.getEntity().setFoodLevel(20);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) { if (!itemsOk(e.getPlayer())) e.setCancelled(true); }

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) { if (e.getEntity() instanceof Player p && !itemsOk(p)) e.setCancelled(true); }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent e) { if (!itemsOk(e.getPlayer())) e.setCancelled(true); }

    @EventHandler
    public void onInvClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && !itemsOk(p) && e.getClickedInventory() == p.getInventory()) e.setCancelled(true);
    }

    @EventHandler public void onIgnite(BlockIgniteEvent e) { e.setCancelled(true); }
    @EventHandler public void onBurn(BlockBurnEvent e) { e.setCancelled(true); }
    @EventHandler public void onSpread(BlockSpreadEvent e) { if (e.getSource().getType() == org.bukkit.Material.FIRE) e.setCancelled(true); }
    @EventHandler public void onFade(BlockFadeEvent e) { e.setCancelled(true); }
    @EventHandler public void onLeaves(LeavesDecayEvent e) { e.setCancelled(true); }
    @EventHandler public void onWeather(WeatherChangeEvent e) { if (e.toWeatherState()) e.setCancelled(true); }
    @EventHandler public void onPortal(PlayerPortalEvent e) { e.setCancelled(true); }
    @EventHandler public void onEntityPortal(EntityPortalEvent e) { e.setCancelled(true); }
    @EventHandler public void onSpawn(CreatureSpawnEvent e) {
        if (e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL) e.setCancelled(true);
    }
}
