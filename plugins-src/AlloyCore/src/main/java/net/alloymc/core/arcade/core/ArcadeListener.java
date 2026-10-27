package net.alloymc.core.arcade.core;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.projectiles.ProjectileSource;

/**
 * Routes Bukkit events to the one match a player (or block/entity, by instance slot) belongs to.
 * Anything in the match world that doesn't belong to a running match is denied.
 */
public final class ArcadeListener implements Listener {
    private final Arcade arcade;

    ArcadeListener(Arcade arcade) { this.arcade = arcade; }

    private Match of(Player p) { return arcade.matches().matchOf(p); }

    private Match at(Location l) {
        if (l.getWorld() != arcade.worlds().matches()) return null;
        int s = SlotAllocator.slotAt(l.getX());
        for (Match m : arcade.matches().active()) if (m.slot == s) return m;
        return null;
    }

    private boolean staff(Player p) { return p.hasPermission("arcade.admin") && p.getGameMode() == GameMode.CREATIVE && of(p) == null; }

    private static boolean live(Match m) { return m != null && m.state() == MatchState.IN_PROGRESS; }

    // ------------------------------------------------------------------ blocks

    @EventHandler(priority = EventPriority.HIGH)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Match m = of(p);
        if (m == null) { if (p.getWorld() == arcade.worlds().matches() && !staff(p)) e.setCancelled(true); return; }
        Block b = e.getBlock();
        if (!live(m) || !m.isAlive(p) || !m.inBounds(b.getLocation().add(0.5, 0.5, 0.5)) || !m.allowBreak(p, b)) { e.setCancelled(true); return; }
        m.onBreak(p, e);
        if (!e.isCancelled()) m.placed.remove(Match.key(b));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlace(BlockPlaceEvent e) {
        Player p = e.getPlayer();
        Match m = of(p);
        if (m == null) { if (p.getWorld() == arcade.worlds().matches() && !staff(p)) e.setCancelled(true); return; }
        Block b = e.getBlockPlaced();
        if (!live(m) || !m.isAlive(p) || !m.inBounds(b.getLocation().add(0.5, 0.5, 0.5)) || b.getY() >= m.oy + m.map.bounds.y2() || !m.allowPlace(p, b)) { e.setCancelled(true); return; }
        m.onPlace(p, e);
        if (!e.isCancelled()) m.placed.add(Match.key(b));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBucket(PlayerBucketEmptyEvent e) {
        Match m = of(e.getPlayer());
        Block b = e.getBlockClicked().getRelative(e.getBlockFace());
        if (m == null) { if (e.getPlayer().getWorld() == arcade.worlds().matches() && !staff(e.getPlayer())) e.setCancelled(true); return; }
        if (!live(m) || !m.inBounds(b.getLocation()) || !m.allowPlace(e.getPlayer(), b)) e.setCancelled(true);
        else m.placed.add(Match.key(b));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBucketFill(PlayerBucketFillEvent e) {
        Match m = of(e.getPlayer());
        if (m == null) { if (e.getPlayer().getWorld() == arcade.worlds().matches() && !staff(e.getPlayer())) e.setCancelled(true); return; }
        if (!live(m) || !m.inBounds(e.getBlockClicked().getLocation()) || !m.allowBreak(e.getPlayer(), e.getBlockClicked())) e.setCancelled(true);
    }

    /** Liquids may not flow out of their match's arena. */
    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        if (e.getBlock().getWorld() != arcade.worlds().matches()) return;
        Match m = at(e.getToBlock().getLocation());
        if (m == null || !m.inBounds(e.getToBlock().getLocation()) || !m.allowFlow(e.getBlock(), e.getToBlock())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        Match m = at(e.getLocation());
        if (m == null) { if (e.getLocation().getWorld() == arcade.worlds().matches()) e.blockList().clear(); return; }
        e.blockList().removeIf(b -> !m.explodable(b));
        for (Block b : e.blockList()) m.placed.remove(Match.key(b));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        Match m = at(e.getBlock().getLocation());
        if (m == null) { if (e.getBlock().getWorld() == arcade.worlds().matches()) e.blockList().clear(); return; }
        e.blockList().removeIf(b -> !m.explodable(b));
    }

    @EventHandler(ignoreCancelled = true)
    public void onPhysicsChange(EntityChangeBlockEvent e) {
        if (e.getEntity() instanceof Player || e.getBlock().getWorld() != arcade.worlds().matches()) return;
        Match m = at(e.getBlock().getLocation());
        if (m == null || !m.inBounds(e.getBlock().getLocation())) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ damage & death

    static Player attacker(EntityDamageEvent e) {
        if (!(e instanceof EntityDamageByEntityEvent be)) return null;
        Entity d = be.getDamager();
        if (d instanceof Player p) return p;
        if (d instanceof Projectile pr) { ProjectileSource s = pr.getShooter(); if (s instanceof Player p) return p; }
        if (d instanceof TNTPrimed t && t.getSource() instanceof Player p) return p;
        if (d instanceof AreaEffectCloud c && c.getSource() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player victim)) {
            Match m = at(e.getEntity().getLocation());
            if (m != null) m.onEntityDamage(e, attacker(e));
            else if (e.getEntity().getWorld() == arcade.worlds().matches()) e.setCancelled(true);
            return;
        }
        Match m = of(victim);
        if (m == null) { if (victim.getWorld() == arcade.worlds().matches()) e.setCancelled(true); return; }
        if (!live(m) || !m.isAlive(victim)) { e.setCancelled(true); return; }
        Player att = attacker(e);
        Entity damager = e instanceof EntityDamageByEntityEvent be ? be.getDamager() : null;
        if (att != null && !att.equals(victim)) {
            if (!m.has(att.getUniqueId()) || !m.isAlive(att) || !m.pvp() || (m.sameTeam(victim, att) && !m.friendlyFire())) { e.setCancelled(true); return; }
            m.recordDamage(victim, att);
        }
        if (!m.onDamage(victim, att != null ? att : damager, e)) { e.setCancelled(true); return; }
        if (e.isCancelled()) return;
        if (e.getFinalDamage() >= victim.getHealth()) {
            if (victim.getInventory().getItemInMainHand().getType() == org.bukkit.Material.TOTEM_OF_UNDYING
                    || victim.getInventory().getItemInOffHand().getType() == org.bukkit.Material.TOTEM_OF_UNDYING) return;   // let the totem work
            e.setCancelled(true);
            String how = e.getCause() == EntityDamageEvent.DamageCause.VOID ? "void" : e.getCause().name().toLowerCase();
            m.killPlayer(victim, how);
        }
    }

    @EventHandler public void onHunger(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p) { Match m = of(p); if (m != null && (!live(m) || !m.hunger()) && e.getFoodLevel() < p.getFoodLevel()) e.setCancelled(true); }
    }

    @EventHandler public void onRegen(EntityRegainHealthEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Match m = of(p);
        if (m != null && !m.naturalRegen() && (e.getRegainReason() == EntityRegainHealthEvent.RegainReason.SATIATED || e.getRegainReason() == EntityRegainHealthEvent.RegainReason.REGEN)) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ interaction

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        Match m = of(p);
        if (m == null) return;
        if (!m.isAlive(p)) { e.setCancelled(true); return; }
        if (e.getClickedBlock() != null && !m.inBounds(e.getClickedBlock().getLocation())) { e.setCancelled(true); return; }
        m.onInteract(p, e);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Match m = of(e.getPlayer());
        if (m == null) return;
        if (!m.isAlive(e.getPlayer())) { e.setCancelled(true); return; }
        m.onInteractEntity(e.getPlayer(), e);
    }

    @EventHandler
    public void onProjectile(ProjectileHitEvent e) {
        if (!(e.getEntity().getShooter() instanceof Player p)) return;
        Match m = of(p);
        if (m != null && live(m)) m.onProjectileHit(p, e);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onLaunch(ProjectileLaunchEvent e) {
        if (!(e.getEntity().getShooter() instanceof Player p)) return;
        Match m = of(p);
        if (m != null && (!live(m) || !m.isAlive(p))) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p) { Match m = of(p); if (m != null) m.onInventoryClick(p, e); }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrop(PlayerDropItemEvent e) {
        Match m = of(e.getPlayer());
        if (m != null && (!live(m) || !m.isAlive(e.getPlayer()) || !m.allowDrop(e.getPlayer(), e.getItemDrop().getItemStack()))) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Match m = of(p);
        if (m == null) { if (p.getWorld() == arcade.worlds().matches()) e.setCancelled(true); return; }
        if (!m.isAlive(p) || !m.allowPickup(p, e.getItem().getItemStack())) e.setCancelled(true);
    }

    @EventHandler public void onCraft(CraftItemEvent e) {
        if (e.getWhoClicked() instanceof Player p) { Match m = of(p); if (m != null && !m.allowCraft()) e.setCancelled(true); }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        Match m = of(p);
        if (m == null) return;
        Location from = e.getFrom(), to = e.getTo();
        if (m.state() == MatchState.STARTING && m.freezeWhileStarting() && m.isAlive(p)
                && (from.getX() != to.getX() || from.getZ() != to.getZ() || to.getY() > from.getY())) {
            Location back = from.clone();
            back.setYaw(to.getYaw()); back.setPitch(to.getPitch());
            e.setTo(back);
            return;
        }
        if (!live(m)) return;
        if (m.isAlive(p) && !m.belowVoid(to) && !m.inBounds(to) && to.getY() > m.oy + m.map.voidY) {
            // walls of the arena: you can't leave (falling below is the void, handled per tick)
            if (to.getY() < m.oy + m.map.bounds.y2()) { e.setTo(from.clone().setDirection(to.getDirection())); return; }
        }
        if (from.getBlockX() != to.getBlockX() || from.getBlockY() != to.getBlockY() || from.getBlockZ() != to.getBlockZ() || m.wantsFineMoves())
            m.onMove(p, from, to);
    }

    @EventHandler
    public void onConsume(PlayerItemConsumeEvent e) { Match m = of(e.getPlayer()); if (live(m)) m.onConsume(e.getPlayer(), e.getItem()); }

    /** Spawn eggs and other player-made mobs only where the game allows them. */
    @EventHandler(ignoreCancelled = true)
    public void onMobSpawn(CreatureSpawnEvent e) {
        if (e.getLocation().getWorld() != arcade.worlds().matches() || e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM) return;
        Match m = at(e.getLocation());
        if (m == null || !m.allowMobSpawn()) e.setCancelled(true);
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) { Match m = of(e.getPlayer()); if (live(m)) m.onSneak(e.getPlayer(), e.isSneaking()); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onTeleport(PlayerTeleportEvent e) {
        // spectators may not use the spectator menu to fly into another match
        if (e.getCause() != PlayerTeleportEvent.TeleportCause.SPECTATE) return;
        Match m = of(e.getPlayer());
        if (m == null || !m.inBounds(e.getTo())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) { arcade.matches().quit(e.getPlayer()); }
}
