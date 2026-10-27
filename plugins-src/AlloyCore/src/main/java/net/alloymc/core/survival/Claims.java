package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.*;
import org.bukkit.projectiles.ProjectileSource;

import java.io.File;
import java.util.*;

/**
 * Chunk claiming: /claim protects the chunk you stand in. Owners can trust friends.
 * Claim allowance grows with playtime.
 */
public final class Claims implements Listener {
    private final AlloyCore plugin;
    private final File file;
    private final Map<String, UUID> owners = new HashMap<>();          // "world;cx;cz" -> owner
    private final Map<UUID, Set<UUID>> trusted = new HashMap<>();
    private final Map<UUID, String> lastArea = new HashMap<>();
    private final Set<UUID> bypass = new HashSet<>();
    private Ranks ranks;

    void setRanks(Ranks r) { this.ranks = r; }

    static final int BASE_CLAIMS = 6, PER_HOURS = 2, MAX_CLAIMS = 120;   // +PER_HOURS claims per hour played

    Claims(AlloyCore plugin) {
        this.plugin = plugin;
        file = new File(plugin.getDataFolder(), "claims.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        var cs = y.getConfigurationSection("chunks");
        if (cs != null) for (String k : cs.getKeys(false)) owners.put(k.replace('|', ';'), UUID.fromString(cs.getString(k)));
        var ts = y.getConfigurationSection("trusted");
        if (ts != null) for (String k : ts.getKeys(false)) {
            Set<UUID> s = new HashSet<>();
            for (String id : ts.getStringList(k)) s.add(UUID.fromString(id));
            trusted.put(UUID.fromString(k), s);
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        owners.forEach((k, v) -> y.set("chunks." + k.replace(';', '|'), v.toString()));
        trusted.forEach((k, v) -> y.set("trusted." + k, v.stream().map(UUID::toString).toList()));
        try { y.save(file); } catch (Exception e) { plugin.getLogger().warning("claims save failed: " + e.getMessage()); }
    }

    static String key(Chunk ch) { return ch.getWorld().getName() + ";" + ch.getX() + ";" + ch.getZ(); }
    static String key(World w, int cx, int cz) { return w.getName() + ";" + cx + ";" + cz; }

    public UUID ownerAt(Location l) { return owners.get(key(l.getWorld(), l.getBlockX() >> 4, l.getBlockZ() >> 4)); }

    /** May this player build/interact here? */
    public boolean canUse(Player p, Location l) {
        if (bypass.contains(p.getUniqueId())) return true;
        UUID owner = ownerAt(l);
        if (owner == null) return true;
        return owner.equals(p.getUniqueId()) || trusted.getOrDefault(owner, Set.of()).contains(p.getUniqueId());
    }

    int count(UUID owner) { int n = 0; for (UUID u : owners.values()) if (u.equals(owner)) n++; return n; }

    int allowance(Player p) {
        int hours = p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20 / 3600;
        return Math.min(MAX_CLAIMS, BASE_CLAIMS + hours * PER_HOURS) + (ranks == null ? 0 : ranks.bonusClaims(p));
    }

    private static String name(UUID id) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        return op.getName() != null ? op.getName() : id.toString().substring(0, 8);
    }

    private boolean nearProtectedArea(Chunk ch) {
        int x = (ch.getX() << 4) + 8, z = (ch.getZ() << 4) + 8;
        return Math.hypot(x, z) < 96 || Math.hypot(x - SurvivalLayout.KOTH_X, z - SurvivalLayout.KOTH_Z) < 96
                || (Math.abs(x - SurvivalLayout.PVP_X) < SurvivalLayout.PVP_HALF + 48 && Math.abs(z - SurvivalLayout.PVP_Z) < SurvivalLayout.PVP_HALF + 48);
    }

    // ------------------------------------------------------------------ commands

    public boolean command(Player p, String label, String[] a) {
        String sub = label.equalsIgnoreCase("claim") ? (a.length > 0 ? a[0].toLowerCase() : "here")
                : label.equalsIgnoreCase("unclaim") ? "unclaim" : label.equalsIgnoreCase("trust") ? "trust" : label.equalsIgnoreCase("untrust") ? "untrust" : "help";
        String arg = label.equalsIgnoreCase("claim") ? (a.length > 1 ? a[1] : null) : (a.length > 0 ? a[0] : null);
        Chunk ch = p.getLocation().getChunk();
        String k = key(ch);
        switch (sub) {
            case "here" -> {
                UUID cur = owners.get(k);
                if (cur != null) { msg(p, cur.equals(p.getUniqueId()) ? "<yellow>You already own this chunk." : "<red>This chunk belongs to <white>" + name(cur) + "<red>."); show(p, ch); return true; }
                if (p.getWorld().getEnvironment() == World.Environment.THE_END && Math.hypot(p.getLocation().getX(), p.getLocation().getZ()) < 300) { msg(p, "<red>You can't claim the main End island."); return true; }
                if (nearProtectedArea(ch)) { msg(p, "<red>Too close to spawn or the KOTH arena to claim."); return true; }
                int have = count(p.getUniqueId()), max = allowance(p);
                if (have >= max && !p.hasPermission("alloy.admin")) { msg(p, "<red>You've used all <white>" + max + "<red> claims. Play more to earn extra (<white>+" + PER_HOURS + "<red> per hour)."); return true; }
                owners.put(k, p.getUniqueId());
                save();
                msg(p, "<green>✔ Chunk claimed! <gray>(" + (have + 1) + "/" + max + ") Only you and trusted players can build here.");
                p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.6f, 1.4f);
                show(p, ch);
            }
            case "unclaim" -> {
                UUID cur = owners.get(k);
                if (cur == null) { msg(p, "<gray>This chunk isn't claimed."); return true; }
                if (!cur.equals(p.getUniqueId()) && !p.hasPermission("alloy.admin")) { msg(p, "<red>That's not your chunk."); return true; }
                owners.remove(k);
                save();
                msg(p, "<yellow>Chunk unclaimed.");
            }
            case "unclaimall" -> {
                int n = 0;
                for (var it = owners.entrySet().iterator(); it.hasNext(); ) if (it.next().getValue().equals(p.getUniqueId())) { it.remove(); n++; }
                save();
                msg(p, "<yellow>Unclaimed <white>" + n + "<yellow> chunks.");
            }
            case "trust", "untrust" -> {
                if (arg == null) { msg(p, "<gray>Usage: /" + sub + " <player>"); return true; }
                OfflinePlayer t = Bukkit.getOfflinePlayerIfCached(arg);
                if (t == null) t = Bukkit.getPlayerExact(arg);
                if (t == null) { msg(p, "<red>Never seen a player called " + arg + "."); return true; }
                Set<UUID> s = trusted.computeIfAbsent(p.getUniqueId(), x -> new HashSet<>());
                if (sub.equals("trust")) s.add(t.getUniqueId()); else s.remove(t.getUniqueId());
                save();
                msg(p, sub.equals("trust") ? "<green>" + t.getName() + " can now build in all your claims." : "<yellow>" + t.getName() + " is no longer trusted.");
            }
            case "info" -> {
                UUID cur = owners.get(k);
                msg(p, "<gray>Chunk <white>" + ch.getX() + ", " + ch.getZ() + "<gray>: " + (cur == null ? "<green>Wilderness" : "owned by <white>" + name(cur)));
                msg(p, "<gray>Your claims: <white>" + count(p.getUniqueId()) + "/" + allowance(p)
                        + " <gray>• Trusted: <white>" + String.join(", ", trusted.getOrDefault(p.getUniqueId(), Set.of()).stream().map(Claims::name).toList()));
                show(p, ch);
            }
            case "map" -> map(p);
            case "show" -> show(p, ch);
            case "list" -> {
                List<String> mine = new ArrayList<>();
                owners.forEach((kk, v) -> { if (v.equals(p.getUniqueId())) { String[] s = kk.split(";"); mine.add("(" + (Integer.parseInt(s[1]) * 16 + 8) + ", " + (Integer.parseInt(s[2]) * 16 + 8) + ")"); } });
                msg(p, "<gray>Your claims (" + mine.size() + "/" + allowance(p) + "): <white>" + (mine.isEmpty() ? "none" : String.join(" ", mine)));
            }
            case "bypass" -> {
                if (!p.hasPermission("alloy.admin")) return true;
                if (!bypass.remove(p.getUniqueId())) bypass.add(p.getUniqueId());
                msg(p, "<gold>Claim bypass " + (bypass.contains(p.getUniqueId()) ? "ON" : "OFF"));
            }
            default -> {
                msg(p, "<gold><bold>Land claims</bold> <gray>- protect your builds, one chunk at a time");
                msg(p, "<yellow>/claim <gray>claim this chunk  <yellow>/unclaim <gray>release it");
                msg(p, "<yellow>/claim map <gray>see nearby claims  <yellow>/claim show <gray>see chunk edges");
                msg(p, "<yellow>/trust <name> <gray>let a friend build  <yellow>/untrust <name>");
                msg(p, "<yellow>/claim info <gray>  <yellow>/claim list <gray>  <yellow>/claim unclaimall");
            }
        }
        return true;
    }

    private void msg(Player p, String s) { p.sendMessage(Ui.mm("<dark_gray>[<gold>Claims<dark_gray>] " + s)); }

    private void map(Player p) {
        Chunk c = p.getLocation().getChunk();
        msg(p, "<gray>Claim map <dark_gray>(<green>■ <gray>yours <aqua>■ <gray>trusted <red>■ <gray>others <dark_gray>■ <gray>free <yellow>✦ <gray>you<dark_gray>)");
        float yaw = (p.getYaw() % 360 + 360) % 360;
        String facing = yaw >= 315 || yaw < 45 ? "S" : yaw < 135 ? "W" : yaw < 225 ? "N" : "E";
        for (int dz = -5; dz <= 5; dz++) {
            StringBuilder sb = new StringBuilder(dz == 0 ? "<gray>W " : "<gray>  ");
            for (int dx = -9; dx <= 9; dx++) {
                if (dx == 0 && dz == 0) { sb.append("<yellow>✦"); continue; }
                UUID o = owners.get(key(c.getWorld(), c.getX() + dx, c.getZ() + dz));
                if (o == null) sb.append("<dark_gray>■");
                else if (o.equals(p.getUniqueId())) sb.append("<green>■");
                else if (trusted.getOrDefault(o, Set.of()).contains(p.getUniqueId())) sb.append("<aqua>■");
                else sb.append("<red>■");
            }
            if (dz == 0) sb.append(" <gray>E");
            p.sendMessage(Ui.mm(sb.toString()));
        }
        p.sendMessage(Ui.mm("<gray>North is up. You're facing <white>" + facing));
    }

    /**
     * Outline a claim with particles for 10 seconds. Touching chunks with the same owner are one claim, so the
     * outline follows the outside edge of the whole connected area (no lines between your own chunks), with a
     * taller post at every corner. An unclaimed chunk is outlined on its own.
     */
    void show(Player p, Chunk ch) {
        World w = ch.getWorld();
        UUID o = owners.get(key(ch));
        Color col = o == null ? Color.GRAY : o.equals(p.getUniqueId()) ? Color.LIME : Color.RED;
        Particle.DustOptions dust = new Particle.DustOptions(col, 1.4f);
        // the connected area: flood-fill neighbouring chunks with the same owner (capped so a huge claim stays cheap)
        Set<Long> area = new HashSet<>();
        Deque<long[]> todo = new ArrayDeque<>();
        todo.add(new long[]{ch.getX(), ch.getZ()});
        area.add(pack(ch.getX(), ch.getZ()));
        while (o != null && !todo.isEmpty() && area.size() < 400) {
            long[] c = todo.poll();
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = (int) c[0] + d[0], nz = (int) c[1] + d[1];
                if (o.equals(owners.get(key(w, nx, nz))) && area.add(pack(nx, nz))) todo.add(new long[]{nx, nz});
            }
        }
        // outside edges only: a chunk side is drawn when the chunk across it isn't part of the area
        List<int[]> edges = new ArrayList<>();   // x0, z0, dx, dz (16 blocks long)
        Set<Long> corners = new HashSet<>();
        for (long k : area) {
            int cx = (int) (k >> 32), cz = (int) k, x0 = cx << 4, z0 = cz << 4;
            if (!area.contains(pack(cx, cz - 1))) edges.add(new int[]{x0, z0, 1, 0});
            if (!area.contains(pack(cx, cz + 1))) edges.add(new int[]{x0, z0 + 16, 1, 0});
            if (!area.contains(pack(cx - 1, cz))) edges.add(new int[]{x0, z0, 0, 1});
            if (!area.contains(pack(cx + 1, cz))) edges.add(new int[]{x0 + 16, z0, 0, 1});
        }
        for (int[] e : edges) { corners.add(pack(e[0], e[1])); corners.add(pack(e[0] + 16 * e[2], e[1] + 16 * e[3])); }
        // a corner is only a real corner if the outline turns there (not where two straight edges meet)
        Map<Long, Integer> dirs = new HashMap<>();
        for (int[] e : edges) for (long c : new long[]{pack(e[0], e[1]), pack(e[0] + 16 * e[2], e[1] + 16 * e[3])}) dirs.merge(c, e[2] == 1 ? 1 : 2, (x, y) -> x | y);
        corners.removeIf(c -> dirs.get(c) != 3);
        if (area.size() > 1) msg(p, (o.equals(p.getUniqueId()) ? "<green>Your claim" : "<red>" + name(o) + "'s claim") + " <gray>here is <white>" + area.size() + " chunks<gray> - outlined for 10 seconds.");
        final int[] runs = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (!p.isOnline() || p.getWorld() != w || ++runs[0] > 20) { task.cancel(); return; }
            Location at = p.getLocation();
            double y = at.getY() + 1;
            for (int[] e : edges) {
                if (Math.abs(e[0] + 8 * e[2] - at.getX()) > 120 || Math.abs(e[1] + 8 * e[3] - at.getZ()) > 120) continue;
                for (int i = 0; i <= 16; i++)
                    for (double dy = -1; dy <= 2; dy += 1.5)
                        p.spawnParticle(Particle.DUST, e[0] + i * e[2], y + dy, e[1] + i * e[3], 1, dust);
            }
            for (long c : corners) {
                int x = (int) (c >> 32), z = (int) c;
                if (Math.abs(x - at.getX()) > 120 || Math.abs(z - at.getZ()) > 120) continue;
                for (double dy = -2; dy <= 6; dy += 0.5) p.spawnParticle(Particle.DUST, x, y + dy, z, 1, dust);
            }
        }, 0L, 10L);
    }

    private static long pack(int a, int b) { return ((long) a << 32) | (b & 0xffffffffL); }

    // ------------------------------------------------------------------ protection

    private boolean deny(Player p, Location l) {
        if (canUse(p, l)) return false;
        p.sendActionBar(Ui.mm("<red>This land is claimed by <white>" + name(ownerAt(l))));
        return true;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { if (deny(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { if (deny(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null) return;
        boolean physical = e.getAction() == org.bukkit.event.block.Action.PHYSICAL;
        boolean use = e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK && (b.getType().isInteractable() || e.getItem() != null);
        if ((physical || use) && deny(e.getPlayer(), b.getLocation())) {
            e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
            e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
            if (physical) e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucket(PlayerBucketEmptyEvent e) { if (deny(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) { if (deny(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent e) {
        Entity t = e.getRightClicked();
        if (t instanceof Villager || t instanceof WanderingTrader || t instanceof Player) return;
        if (deny(e.getPlayer(), t.getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) { if (deny(e.getPlayer(), e.getRightClicked().getLocation())) e.setCancelled(true); }

    private Player attacker(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr) { ProjectileSource s = pr.getShooter(); if (s instanceof Player p) return p; }
        return null;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamageEntity(EntityDamageByEntityEvent e) {
        if (e.getEntity() instanceof Player || e.getEntity() instanceof Monster) return;
        Player p = attacker(e.getDamager());
        if (p != null && deny(p, e.getEntity().getLocation())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHanging(HangingBreakByEntityEvent e) {
        Player p = attacker(e.getRemover());
        if (p != null ? deny(p, e.getEntity().getLocation()) : ownerAt(e.getEntity().getLocation()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) { e.blockList().removeIf(b -> ownerAt(b.getLocation()) != null); }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { e.blockList().removeIf(b -> ownerAt(b.getLocation()) != null); }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent e) {
        if (e.getEntity() instanceof Player p) { if (deny(p, e.getBlock().getLocation())) e.setCancelled(true); }
        else if (!(e.getEntity() instanceof FallingBlock) && ownerAt(e.getBlock().getLocation()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) { if (ownerAt(e.getBlock().getLocation()) != null) e.setCancelled(true); }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (e.getPlayer() != null) { if (deny(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true); }
        else if (e.getCause() != BlockIgniteEvent.IgniteCause.LIGHTNING && ownerAt(e.getBlock().getLocation()) != null) e.setCancelled(true);
    }

    private boolean crosses(Location from, Location to) {
        return !Objects.equals(ownerAt(from), ownerAt(to)) && ownerAt(to) != null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        if (crosses(e.getBlock().getLocation(), e.getToBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        Location from = e.getBlock().getLocation();
        for (Block b : e.getBlocks())
            if (crosses(from, b.getRelative(e.getDirection()).getLocation())) { e.setCancelled(true); return; }
        if (crosses(from, e.getBlock().getRelative(e.getDirection()).getLocation())) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        Location from = e.getBlock().getLocation();
        for (Block b : e.getBlocks()) if (crosses(from, b.getLocation())) { e.setCancelled(true); return; }
    }

    // entering / leaving land
    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (e.getFrom().getChunk() == e.getTo().getChunk() && e.getFrom().getWorld() == e.getTo().getWorld()) return;
        UUID o = ownerAt(e.getTo());
        String area = o == null ? "wild" : o.toString();
        if (area.equals(lastArea.put(e.getPlayer().getUniqueId(), area))) return;
        Player p = e.getPlayer();
        if (o == null) p.sendActionBar(Ui.mm("<dark_green>☘ <green>Wilderness"));
        else p.sendActionBar(Ui.mm(o.equals(p.getUniqueId()) ? "<gold>⌂ <yellow>Your land" : "<gold>⌂ <white>" + name(o) + "<gray>'s land"));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { lastArea.remove(e.getPlayer().getUniqueId()); }
}
