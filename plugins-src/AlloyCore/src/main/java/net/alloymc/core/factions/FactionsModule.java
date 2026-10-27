package net.alloymc.core.factions;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.LobbyRules;
import net.alloymc.core.Ui;
import net.alloymc.core.build.Paster;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;

import java.io.File;
import java.time.*;
import java.util.*;

/**
 * Factions server: the floating war-citadel spawn (protected, unclaimable), gates that drop you into a
 * random spot in the wild, /spawn and /rtp, and the 3-month season clock. When a season ends the top
 * factions are recorded, everyone is sent to the hub, and tools/factions_reset.py archives the old world
 * and factions data, starts a fresh map and the next season.
 */
public final class FactionsModule implements Listener {
    private final AlloyCore plugin;
    private final World world;
    private final File seasonFile, seasonsDir;
    private YamlConfiguration season;
    private final List<TextDisplay> holos = new ArrayList<>();
    private final Map<UUID, Long> gateCooldown = new HashMap<>(), rtpCooldown = new HashMap<>(), lastPvp = new HashMap<>();
    private final Map<UUID, org.bukkit.scheduler.BukkitTask> warmups = new HashMap<>();
    private final int border;
    private boolean ending;
    private TextDisplay seasonBoard;

    public FactionsModule(AlloyCore plugin) {
        this.plugin = plugin;
        this.world = Bukkit.getWorlds().getFirst();
        this.border = plugin.getConfig().getInt("rtp.border-size", 12000);
        this.seasonFile = new File(plugin.getDataFolder(), "season.yml");
        this.seasonsDir = new File(plugin.getServer().getWorldContainer(), "seasons");
        loadSeason();
        world.getWorldBorder().setCenter(0, 0);
        world.getWorldBorder().setSize(border);
        world.setSpawnLocation(spawn());
        for (int cx = -4; cx <= 3; cx++) for (int cz = -4; cz <= 3; cz++) world.addPluginChunkTicket(cx, cz, plugin);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        new net.alloymc.core.SpawnSafety(plugin, this::inSpawn, p -> spawn());
        new net.alloymc.core.survival.SilkSpawners(plugin);
        hookClaims();
        registerCommands();
        // a new season means a new world: the citadel is rebuilt the first time the world is used
        if (!new File(world.getWorldFolder(), "blackforge-spawn-v2").exists()) build(Bukkit.getConsoleSender());
        else Bukkit.getScheduler().runTaskLater(plugin, this::spawnHolograms, 40L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::everyMinute, 200L, 1200L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshBoard, 100L, 200L);
    }

    public void shutdown() { holos.forEach(TextDisplay::remove); }

    public Location spawn() { double[] s = FactionsBuilder.spawn(); return new Location(world, s[0], s[1], s[2], 180, 0); }

    public void build(CommandSender who) {
        who.sendMessage(Component.text("Building the Factions citadel..."));
        Paster.paste(plugin, world, FactionsBuilder.build(), 6000, () -> {
            try { new File(world.getWorldFolder(), "blackforge-spawn-v2").createNewFile(); } catch (Exception ignored) { }
            who.sendMessage(Component.text("Factions citadel built."));
            spawnHolograms();
        });
    }

    // ------------------------------------------------------------------ spawn area

    /** Inside the citadel (its column of sky, so nothing can be built over or under it either). */
    public boolean inSpawn(Location l) {
        return l.getWorld() == world && Math.hypot(l.getX(), l.getZ()) <= FactionsBuilder.R + 6 && l.getY() > FactionsBuilder.Y - 40;
    }

    private boolean staff(Player p) { return p.hasPermission("alloy.admin") && p.getGameMode() == GameMode.CREATIVE; }

    @EventHandler(ignoreCancelled = true) public void onBreak(BlockBreakEvent e) { if (inSpawn(e.getBlock().getLocation()) && !staff(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void onPlace(BlockPlaceEvent e) { if (inSpawn(e.getBlock().getLocation()) && !staff(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void onBucket(PlayerBucketEmptyEvent e) { if (inSpawn(e.getBlockClicked().getLocation()) && !staff(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void onExplode(EntityExplodeEvent e) { e.blockList().removeIf(b -> inSpawn(b.getLocation())); }
    @EventHandler(ignoreCancelled = true) public void onBlockExplode(BlockExplodeEvent e) { e.blockList().removeIf(b -> inSpawn(b.getLocation())); }
    @EventHandler(ignoreCancelled = true) public void onBurn(BlockBurnEvent e) { if (inSpawn(e.getBlock().getLocation())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void onIgnite(BlockIgniteEvent e) { if (inSpawn(e.getBlock().getLocation()) && e.getCause() != BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void onMobSpawn(CreatureSpawnEvent e) { if (inSpawn(e.getLocation()) && e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void onHunger(FoodLevelChangeEvent e) { if (inSpawn(e.getEntity().getLocation()) && e.getFoodLevel() < e.getEntity().getFoodLevel()) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b != null && inSpawn(b.getLocation()) && !staff(e.getPlayer()) && e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK && b.getType().isInteractable())
            e.setUseInteractedBlock(Event.Result.DENY);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (inSpawn(p.getLocation())) { e.setCancelled(true); if (e.getCause() == EntityDamageEvent.DamageCause.VOID) p.teleport(spawn()); return; }
        if (e instanceof EntityDamageByEntityEvent be) {
            Entity d = be.getDamager();
            Player att = d instanceof Player x ? x : d instanceof Projectile pr && pr.getShooter() instanceof Player x ? x : null;
            if (att != null) {
                if (inSpawn(att.getLocation())) { e.setCancelled(true); return; }
                long now = System.currentTimeMillis();
                lastPvp.put(p.getUniqueId(), now);
                lastPvp.put(att.getUniqueId(), now);
            }
        }
        cancelWarmup(p, "<red>Teleport cancelled - you took damage.");
    }

    /** Factions can't claim the citadel or the ground around it. */
    @SuppressWarnings("unchecked")
    private void hookClaims() {
        try {
            Class<? extends Event> cls = (Class<? extends Event>) Class.forName("com.pvpindex.factions.event.FactionChunkClaimEvent");
            var getX = cls.getMethod("getChunkX"); var getZ = cls.getMethod("getChunkZ"); var getW = cls.getMethod("getWorldName"); var getP = cls.getMethod("getPlayerUUID");
            int safe = (FactionsBuilder.R + 32) / 16 + 1;
            Bukkit.getPluginManager().registerEvent(cls, this, EventPriority.HIGH, (l, ev) -> {
                try {
                    if (!cls.isInstance(ev) || !world.getName().equals(getW.invoke(ev))) return;
                    int cx = (int) getX.invoke(ev), cz = (int) getZ.invoke(ev);
                    if (Math.abs(cx) > safe || Math.abs(cz) > safe) return;
                    ((org.bukkit.event.Cancellable) ev).setCancelled(true);
                    Player p = Bukkit.getPlayer((UUID) getP.invoke(ev));
                    if (p != null) p.sendMessage(Ui.mm("<red>You can't claim land this close to spawn."));
                } catch (Exception ex) { plugin.getLogger().warning("claim hook: " + ex); }
            }, plugin, true);
            plugin.getLogger().info("Factions: spawn claim protection active (" + (safe * 2 + 1) + "x" + (safe * 2 + 1) + " chunks)");
        } catch (ClassNotFoundException e) {
            plugin.getLogger().warning("Factions plugin not found - spawn chunks are protected by AlloyCore only");
        } catch (Exception e) {
            plugin.getLogger().warning("Factions claim hook failed: " + e);
        }
    }

    // ------------------------------------------------------------------ joining, gates, /spawn, /rtp

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!p.hasPlayedBefore()) Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) p.teleport(spawn()); }, 5L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            p.sendMessage(Ui.mm(Ui.TITLE + " <dark_gray>» <white><bold>FACTIONS</bold> <gray>Season <white>" + season.getInt("number") + " <gray>ends in <gold>" + left() + "<gray>. <white>/season"));
            p.sendMessage(Ui.mm("<gray>Walk through any gate to drop into the wild, or <white>/rtp<gray>. Build a faction with <white>/f create <name><gray>."));
        }, 40L);
    }

    /** The gate curtains are portal blocks: never actually send anyone to the nether from spawn. */
    @EventHandler(ignoreCancelled = true) public void onPortal(PlayerPortalEvent e) { if (inSpawn(e.getFrom())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void onEntityPortal(EntityPortalEvent e) { if (inSpawn(e.getFrom())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void onPhysics(BlockPhysicsEvent e) { if (e.getBlock().getType() == Material.NETHER_PORTAL && inSpawn(e.getBlock().getLocation())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent e) { if (!e.isBedSpawn() && !e.isAnchorSpawn()) e.setRespawnLocation(spawn()); }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Location to = e.getTo();
        Player p = e.getPlayer();
        if (e.getFrom().getBlockX() != to.getBlockX() || e.getFrom().getBlockZ() != to.getBlockZ() || e.getFrom().getBlockY() != to.getBlockY())
            cancelWarmup(p, "<red>Teleport cancelled - you moved.");
        if (to.getWorld() != world || to.getBlock().getType() != Material.NETHER_PORTAL && to.clone().add(0, 1, 0).getBlock().getType() != Material.NETHER_PORTAL) {
            if (to.getWorld() == world && inSpawn(to) && to.getY() < FactionsBuilder.Y - 30) p.teleport(spawn());   // fell off the island
            return;
        }
        if (!inSpawn(to)) return;
        long now = System.currentTimeMillis();
        if (gateCooldown.getOrDefault(p.getUniqueId(), 0L) > now) return;
        gateCooldown.put(p.getUniqueId(), now + 5000);
        p.sendMessage(Ui.mm("<gray>Finding a spot in the wild..."));
        wild(p);
    }

    /** Random safe surface spot inside the border, away from spawn. */
    private void wild(Player p) {
        Random r = new Random();
        tryWild(p, r, 0);
    }

    private void tryWild(Player p, Random r, int attempt) {
        if (attempt >= 12 || !p.isOnline()) { p.sendMessage(Ui.mm("<red>Couldn't find a safe spot - try again.")); return; }
        int half = border / 2 - 50, x, z;
        do { x = r.nextInt(half * 2) - half; z = r.nextInt(half * 2) - half; } while (Math.hypot(x, z) < 400);
        int fx = x, fz = z;
        world.getChunkAtAsync(fx >> 4, fz >> 4).thenAccept(ch -> {
            Block top = world.getHighestBlockAt(fx, fz);
            Material m = top.getType();
            if (top.getY() < world.getSeaLevel() - 2 || m == Material.WATER || m == Material.LAVA || m.name().contains("LEAVES") || m == Material.MAGMA_BLOCK || m == Material.POWDER_SNOW) { tryWild(p, r, attempt + 1); return; }
            p.teleportAsync(top.getLocation().add(0.5, 1, 0.5)).thenRun(() -> {
                p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 0.8f);
                p.sendMessage(Ui.mm("<green>You've been dropped at <white>" + fx + ", " + fz + "<green>. Good luck out there."));
            });
        });
    }

    private void cancelWarmup(Player p, String why) {
        var t = warmups.remove(p.getUniqueId());
        if (t != null) { t.cancel(); if (!why.isEmpty()) p.sendMessage(Ui.mm(why)); }
    }

    private void warmup(Player p, int seconds, String what, Runnable go) {
        cancelWarmup(p, "");
        if (lastPvp.getOrDefault(p.getUniqueId(), 0L) > System.currentTimeMillis() - 15_000) { p.sendMessage(Ui.mm("<red>You're in combat - wait 15 seconds after your last fight.")); return; }
        p.sendMessage(Ui.mm("<gray>Teleporting to " + what + " in <white>" + seconds + "s<gray> - don't move."));
        warmups.put(p.getUniqueId(), Bukkit.getScheduler().runTaskLater(plugin, () -> { warmups.remove(p.getUniqueId()); go.run(); }, seconds * 20L));
    }

    private interface Cmd { void run(Player p, String[] a); }

    private void reg(String name, List<String> aliases, Cmd cmd) {
        Bukkit.getCommandMap().register("alloy", new Command(name, "BlackForgeMC Factions", "/" + name, aliases) {
            @Override public boolean execute(CommandSender s, String label, String[] a) {
                if (s instanceof Player p) cmd.run(p, a); else if (name.equals("season")) s.sendMessage(Ui.mm(seasonText()));
                return true;
            }
        });
    }

    private void registerCommands() {
        reg("spawn", List.of(), (p, a) -> warmup(p, inSpawn(p.getLocation()) ? 0 : 5, "spawn", () -> p.teleport(spawn())));
        reg("rtp", List.of("wild"), (p, a) -> {
            long now = System.currentTimeMillis();
            if (rtpCooldown.getOrDefault(p.getUniqueId(), 0L) > now) { p.sendMessage(Ui.mm("<red>/rtp again in " + (rtpCooldown.get(p.getUniqueId()) - now) / 1000 + "s.")); return; }
            rtpCooldown.put(p.getUniqueId(), now + 60_000);
            warmup(p, inSpawn(p.getLocation()) ? 0 : 5, "the wild", () -> wild(p));
        });
        reg("season", List.of("seasons"), (p, a) -> { for (String l : seasonText().split("\n")) p.sendMessage(Ui.mm(l)); });
    }

    // ------------------------------------------------------------------ holograms

    private void spawnHolograms() {
        holos.forEach(TextDisplay::remove);
        holos.clear();
        for (TextDisplay td : world.getEntitiesByClass(TextDisplay.class)) if (inSpawn(td.getLocation())) td.remove();
        double[] r = FactionsBuilder.rules();
        holos.add(Ui.hologram(new Location(world, r[0], r[1], r[2]), "<red><bold>FACTIONS RULES</bold><newline><gray>Raiding, griefing and PvP are allowed<newline><gray>outside spawn - it's war out there.<newline>"
                + "<gray>No cheats, no lag machines, no x-ray.<newline><gray>No harassment or hate speech.<newline><gray>Spawn is a safe zone - no claims, no PvP.", 1.0f));
        double[] s = FactionsBuilder.season();
        seasonBoard = Ui.hologram(new Location(world, s[0], s[1], s[2]), seasonText(), 1.0f);
        holos.add(seasonBoard);
        int g = FactionsBuilder.GATE;
        for (int[] p : new int[][]{{0, -g}, {g, 0}, {0, g}, {-g, 0}})
            holos.add(Ui.hologram(new Location(world, p[0] + 0.5, FactionsBuilder.Y + 11.5, p[1] + 0.5), "<red><bold>TO THE WILD</bold><newline><gray>Walk through the gate", 1.2f));
        holos.add(Ui.hologram(new Location(world, -10.5, FactionsBuilder.Y + 4.5, 8.5), "<#ff5f5f><bold>THE CRIMSON WARLORD", 1.2f));
        holos.add(Ui.hologram(new Location(world, 11.5, FactionsBuilder.Y + 4.5, 8.5), "<#5f9dff><bold>THE AZURE VANGUARD", 1.2f));
        double[] sp = FactionsBuilder.spawn();
        holos.add(Ui.hologram(new Location(world, sp[0], sp[1] + 3.2, sp[2] - 3), Ui.TITLE + " <white><bold>FACTIONS</bold><newline><gray>Season <white>" + season.getInt("number") + " <dark_gray>• <white>/f create <gray>to start a faction", 1.3f));
    }

    private void refreshBoard() {
        if (seasonBoard == null || !seasonBoard.isValid()) { if (new File(world.getWorldFolder(), "blackforge-spawn-v2").exists()) spawnHolograms(); return; }
        seasonBoard.text(Ui.mm(seasonText()));
    }

    // ------------------------------------------------------------------ seasons

    private void loadSeason() {
        season = YamlConfiguration.loadConfiguration(seasonFile);
        if (!season.contains("number")) {
            long now = System.currentTimeMillis();
            season.set("number", 1);
            season.set("started", now);
            season.set("ends", endAfter(now, plugin.getConfig().getInt("season.months", 3)));
            try { season.save(seasonFile); } catch (Exception e) { plugin.getLogger().warning("season save: " + e.getMessage()); }
        }
    }

    static long endAfter(long start, int months) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(start), ZoneId.systemDefault()).plusMonths(months).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private long msLeft() { return season.getLong("ends") - System.currentTimeMillis(); }

    private String left() {
        long ms = Math.max(0, msLeft()), d = ms / 86_400_000L, h = ms / 3_600_000L % 24, m = ms / 60_000L % 60;
        return d > 0 ? d + "d " + h + "h" : h > 0 ? h + "h " + m + "m" : m + "m";
    }

    private String seasonText() {
        StringBuilder sb = new StringBuilder("<gold><bold>SEASON " + season.getInt("number") + "</bold><newline><gray>Ends in <white>" + left()
                + "<newline><gray>" + LocalDate.ofInstant(Instant.ofEpochMilli(season.getLong("ends")), ZoneId.systemDefault()) + "<newline><dark_gray>Every 3 months the map resets<newline><dark_gray>for a fresh, fair start.");
        File last = new File(seasonsDir, "season-" + (season.getInt("number") - 1) + "-results.txt");
        if (last.exists()) {
            try {
                List<String> lines = java.nio.file.Files.readAllLines(last.toPath());
                sb.append("<newline><newline><gold>Last season's top factions:");
                for (String l : lines.stream().filter(s -> !s.isBlank()).limit(4).toList()) sb.append("<newline><gray>").append(l.replace("<", ""));
            } catch (Exception ignored) { }
        }
        return sb.toString();
    }

    private int lastWarned = -1;

    private void everyMinute() {
        long ms = msLeft();
        long[] warn = {7 * 86_400_000L, 86_400_000L, 3_600_000L, 600_000L, 60_000L};
        String[] label = {"7 days", "24 hours", "1 hour", "10 minutes", "1 minute"};
        for (int i = 0; i < warn.length; i++)
            if (ms <= warn[i] && ms > warn[i] - 120_000L && i > lastWarned && (lastWarned = i) >= 0)   // once each, even if a tick runs late
                Bukkit.broadcast(Ui.mm("<gold><bold>SEASON " + season.getInt("number") + "</bold> <gray>ends in <white>" + label[i] + "<gray>! The map resets for a fresh start."));
        if (ms <= 0 && !ending) endSeason(Bukkit.getConsoleSender());
    }

    /** What /f top says right now (captured from the factions plugin). */
    private List<String> standings() {
        List<String> top = new ArrayList<>();
        try {
            CommandSender capture = Bukkit.createCommandSender(c -> top.add(PlainTextComponentSerializer.plainText().serialize(c)));
            Bukkit.dispatchCommand(capture, "f top");
        } catch (Exception e) { plugin.getLogger().warning("couldn't read /f top: " + e); }
        return top;
    }

    /** Record the winners, move everyone to the hub, then hand over to the reset script and stop. */
    public void endSeason(CommandSender who) {
        if (ending) return;
        ending = true;
        int n = season.getInt("number");
        List<String> top = standings();
        seasonsDir.mkdirs();
        try { java.nio.file.Files.write(new File(seasonsDir, "season-" + n + "-results.txt").toPath(), top); } catch (Exception ignored) { }
        Bukkit.broadcast(Ui.mm("<gold><bold>SEASON " + n + " IS OVER!</bold> <gray>Final standings:"));
        for (String l : top.stream().limit(8).toList()) Bukkit.broadcast(Ui.mm("<gray>" + l.replace("<", "")));
        Bukkit.broadcast(Ui.mm("<gray>The map is resetting. Season <white>" + (n + 1) + " <gray>opens in a few minutes!"));
        who.sendMessage(Ui.mm("<gold>Season " + n + " ended; reset starting."));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) plugin.net().send(p, "hub");
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                try {
                    File script = new File(plugin.getServer().getWorldContainer().getCanonicalFile().getParentFile(), "tools/factions_reset.py");
                    new ProcessBuilder("python", script.getAbsolutePath(), String.valueOf(n), String.valueOf(plugin.getConfig().getInt("season.months", 3)))
                            .redirectOutput(ProcessBuilder.Redirect.appendTo(new File(seasonsDir, "reset.log"))).redirectErrorStream(true).start();
                } catch (Exception e) { plugin.getLogger().severe("could not start the season reset script: " + e); ending = false; return; }
                Bukkit.shutdown();
            }, 60L);
        }, 200L);
    }

    /** /alloy season ... (staff). Returns true if handled. */
    public boolean admin(CommandSender s, String[] a) {
        if (a.length == 0) return false;
        switch (a[0].toLowerCase()) {
            case "buildfactions" -> { build(s); return true; }
            case "season" -> {
                String sub = a.length > 1 ? a[1].toLowerCase() : "info";
                switch (sub) {
                    case "standings" -> { for (String l : standings()) s.sendMessage(Ui.mm("<gray>" + l.replace("<", ""))); }
                    case "end" -> { if (a.length > 2 && a[2].equalsIgnoreCase("confirm")) endSeason(s); else s.sendMessage(Ui.mm("<red>This ends the season NOW and resets the map. <white>/alloy season end confirm")); }
                    case "set-end" -> {
                        if (a.length < 3) { s.sendMessage(Ui.mm("<gray>/alloy season set-end <days from now>")); return true; }
                        season.set("ends", System.currentTimeMillis() + (long) (Double.parseDouble(a[2]) * 86_400_000L));
                        try { season.save(seasonFile); } catch (Exception ignored) { }
                        s.sendMessage(Ui.mm("<green>Season ends in " + left() + "."));
                    }
                    default -> s.sendMessage(Ui.mm(seasonText().replace("<newline>", " | ")));
                }
                return true;
            }
            default -> { return false; }
        }
    }
}
