package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import net.alloymc.core.build.Paster;
import net.milkbowl.vault.economy.Economy;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.projectiles.ProjectileSource;

import java.io.File;
import java.util.*;

import static net.alloymc.core.survival.SurvivalLayout.*;

/** BlackForgeMC Survival: floating spawn, chunk claims, crates, KOTH, warps and random teleport. */
public final class SurvivalModule implements Listener {
    private final AlloyCore plugin;
    private final World world;
    private final Claims claims;
    private final Crates crates;
    private final Koth koth;
    private final PvpZone pvp;
    private final NameTags tags;
    private final Kits kits;
    private final CustomEnchants enchants;
    private final Votes votes;
    private final Scrap scrap;
    private final GaleBellows bellows;
    private final GaiaPotion gaia;
    private final Ranks ranks;
    private final HallOfFame hall;
    private final DailyRewards daily;
    private final RankKits rankKits;
    private final Spawners spawners;
    private final Robots robots;
    private final Set<UUID> dropping = new HashSet<>(), landed = new HashSet<>();
    private final Set<UUID> warmupApproved = new HashSet<>();
    private final Map<UUID, Integer> warmups = new HashMap<>();
    static final int ESCAPE_SECONDS = 10;
    private final Map<UUID, Long> combat = new HashMap<>();
    private final Map<UUID, Long> rtpCooldown = new HashMap<>();
    private final Map<UUID, Long> gateCooldown = new HashMap<>();
    private final List<TextDisplay> holos = new ArrayList<>();
    private TextDisplay kothHolo, leaderboard;
    private Economy eco;
    private AuctionHouse ah;
    private String topText = "<gray>Loading...";
    private final int borderSize;
    private final int rtpRange;   // wild teleports land within this square (smaller than the border so players stay near each other)

    public SurvivalModule(AlloyCore plugin) {
        this.plugin = plugin;
        this.world = Bukkit.getWorlds().getFirst();
        claims = new Claims(plugin);
        crates = new Crates(plugin, world);
        koth = new Koth(plugin, world, crates);
        pvp = new PvpZone(plugin, world, crates);
        tags = new NameTags(plugin);
        kits = new Kits(plugin);
        enchants = new CustomEnchants(plugin);
        pvp.setEnchants(enchants);
        votes = new Votes(plugin, crates);
        scrap = new Scrap(plugin, crates, tags, enchants);
        bellows = new GaleBellows(plugin);   // crate-only: crate rewards and rare Scrapyard supply crates hand them out
        pvp.setBellows(bellows);
        // Potion of Gaia: works anywhere except spawn and other people's claims (KOTH and the Scrapyard included -
        // its floor is temporary, only fills air, and always cleans itself up)
        gaia = new GaiaPotion(plugin, (p, l) -> (l.getWorld() != world || !inSpawn(l.getX(), l.getY(), l.getZ())) && claims.canUse(p, l));
        new SilkSpawners(plugin);
        votes.setScrap(scrap);
        ranks = new Ranks(plugin, crates);
        ranks.setClaims(claims);
        claims.setRanks(ranks);
        scrap.setRanks(ranks);
        tags.setRanks(ranks);
        hall = new HallOfFame(plugin, world);
        daily = new DailyRewards(plugin, crates, enchants, tags);
        daily.setScrap(scrap);
        rankKits = new RankKits(plugin, ranks, tags, crates);
        spawners = new Spawners(plugin);
        spawners.setAccess((p, l) -> p.hasPermission("alloy.admin") || claims.ownerAt(l) != null && claims.canUse(p, l));
        robots = new Robots(plugin);
        robots.setAccess((p, l) -> p.getUniqueId().equals(claims.ownerAt(l)));   // only in your own claim, where it can work
        robots.setWorkArea((owner, l) -> owner.equals(claims.ownerAt(l)));
        robots.setRanks(ranks);
        robots.setCrateOnly("<#ffb52e>Molten<gray>, <#ff4422>Forgeheart<gray> and <#c084fc>Conqueror");
        koth.setScrap(scrap);
        pvp.setScrap(scrap);
        borderSize = plugin.getConfig().getInt("rtp.border-size", 10000);
        rtpRange = Math.min(borderSize, plugin.getConfig().getInt("rtp.range", 10000));
        world.getWorldBorder().setCenter(0, 0);
        world.getWorldBorder().setSize(borderSize);
        World nether = Bukkit.getWorld(world.getName() + "_nether");
        if (nether != null) { nether.getWorldBorder().setCenter(0, 0); nether.getWorldBorder().setSize(borderSize / 8.0); }
        // the economy (Essentials via Vault) registers after we load
        Bukkit.getScheduler().runTask(plugin, () -> {
            var reg = Bukkit.getServicesManager().getRegistration(Economy.class);
            if (reg == null) { plugin.getLogger().warning("No Vault economy found - /ah and the money leaderboard are disabled"); return; }
            eco = reg.getProvider();
            spawners.setEconomy(eco);
            robots.setEconomy(eco);
            tags.setEconomy(eco);
            kits.setEconomy(eco);
            votes.setEconomy(eco);
            hall.setEconomy(eco);
            daily.setEconomy(eco);
            ah = new AuctionHouse(plugin, eco);
            refreshLeaderboard();
        });
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshLeaderboard, 1200L, 1200L);
        world.setSpawnLocation(spawn());
        for (int cx = -3; cx <= 2; cx++) for (int cz = -3; cz <= 2; cz++) world.addPluginChunkTicket(cx, cz, plugin);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        new net.alloymc.core.SpawnSafety(plugin, l -> l.getWorld() == world && inSpawn(l.getX(), l.getY(), l.getZ()), p -> spawn());
        new TradingHall(plugin, world, ranks);
        registerCommands();
        if (!new File(plugin.getDataFolder(), "survival-built").exists()) build(Bukkit.getConsoleSender());
        else if (!new File(plugin.getDataFolder(), "spawn-v2").exists()) rebuildSpawn(Bukkit.getConsoleSender());
        else if (!new File(plugin.getDataFolder(), "pvp-built").exists()) buildPvp(Bukkit.getConsoleSender());
        Bukkit.getScheduler().runTaskTimer(plugin, this::everySecond, 40L, 20L);
    }

    public void shutdown() {
        gaia.shutdown();   // first: removes every cloud's barrier blocks
        holos.forEach(TextDisplay::remove);
        if (koth.active()) koth.stop(null);
        pvp.shutdown();
        scrap.save();
        robots.save();
        hall.shutdown();
    }

    public Location spawn() { return new Location(world, SPAWN_X, SPAWN_Y, SPAWN_Z, SPAWN_YAW, 0); }

    public void build(CommandSender who) {
        who.sendMessage(Component.text("Building survival spawn + KOTH arena..."));
        clearBox(-128, Y - 50, -128, 128, Y + 45, 128, () -> Paster.paste(plugin, world, SpawnBuilder.spawn(), 5000, () ->
            Paster.paste(plugin, world, SpawnBuilder.arena(), 5000, () -> {
                try { new File(plugin.getDataFolder(), "survival-built").createNewFile(); new File(plugin.getDataFolder(), "spawn-v2").createNewFile(); } catch (Exception ignored) { }
                who.sendMessage(Component.text("Survival spawn and arena built."));
                spawnHolograms();
                buildPvp(who);
            })));
    }

    /** Rebuild just the spawn island (v2: bigger island, farm ring, Trading Hall), leaving the arena and Scrapyard alone. */
    public void rebuildSpawn(CommandSender who) {
        who.sendMessage(Component.text("Rebuilding the survival spawn (v2)..."));
        rebuilding = true;
        clearBox(-128, Y - 50, -128, 128, Y + 45, 128, () -> Paster.paste(plugin, world, SpawnBuilder.spawn(), 5000, () -> {
            try { new File(plugin.getDataFolder(), "spawn-v2").createNewFile(); } catch (Exception ignored) { }
            rebuilding = false;
            who.sendMessage(Component.text("Survival spawn v2 built."));
            spawnHolograms();
        }));
    }

    private volatile boolean rebuilding;

    /** Nobody joins while the spawn is mid-rebuild (the area is cleared to air first). */
    @EventHandler
    public void onLogin(org.bukkit.event.player.PlayerLoginEvent e) {
        if (rebuilding) e.disallow(org.bukkit.event.player.PlayerLoginEvent.Result.KICK_OTHER, Ui.mm("<gold>Survival's spawn is being rebuilt.<newline><gray>Try again in about 30 seconds!"));
    }

    public void buildPvp(CommandSender who) {
        who.sendMessage(Component.text("Building the Scrapyard (PvP zone)..."));
        clearBox(PVP_X - PVP_HALF - 8, PVP_Y - 32, PVP_Z - PVP_HALF - 8, PVP_X + PVP_HALF + 8, PVP_Y + 32, PVP_Z + PVP_HALF + 8, () ->
            Paster.paste(plugin, world, PvpBuilder.build(), 8000, () -> {
                try { new File(plugin.getDataFolder(), "pvp-built").createNewFile(); } catch (Exception ignored) { }
                who.sendMessage(Component.text("Scrapyard built."));
            }));
    }

    /** Wipe a box to air a few columns per tick (removes an older spawn before rebuilding). */
    private void clearBox(int x1, int y1, int z1, int x2, int y2, int z2, Runnable done) {
        int[] x = {x1};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            for (int n = 0; n < 2 && x[0] <= x2; n++, x[0]++)
                for (int z = z1; z <= z2; z++)
                    for (int y = y1; y <= y2; y++) {
                        Block b = world.getBlockAt(x[0], y, z);
                        if (!b.getType().isAir()) b.setType(Material.AIR, false);
                    }
            if (x[0] > x2) { task.cancel(); done.run(); }
        }, 1L, 1L);
    }

    // ------------------------------------------------------------------ commands

    private void registerCommands() {
        // registered before Essentials loads (loadbefore), so these take the plain names
        reg("spawn", List.of(), (p, a) -> teleportSafe(p, spawn(), "spawn"));
        reg("warp", List.of("warps"), this::warp);
        reg("rtp", List.of("wild", "randomtp"), (p, a) -> rtp(p));
        reg("claim", List.of("claims"), (p, a) -> claims.command(p, "claim", a));
        reg("unclaim", List.of(), (p, a) -> claims.command(p, "unclaim", a));
        reg("trust", List.of(), (p, a) -> claims.command(p, "trust", a));
        reg("untrust", List.of(), (p, a) -> claims.command(p, "untrust", a));
        reg("ah", List.of("auction", "auctionhouse"), (p, a) -> {
            if (ah == null) { p.sendMessage(Ui.mm("<red>The auction house is unavailable right now.")); return; }
            ah.command(p, a);
        });
        reg("pvp", List.of("scrapyard", "envoy"), (p, a) -> {
            if (a.length > 0 && a[0].equalsIgnoreCase("drop") && p.hasPermission("alloy.admin")) { pvp.drop(); return; }
            dropInto(p, pvp.arrival(), "the Scrapyard - PvP is on!");
            p.sendMessage(Ui.mm("<red>⚔ <gray>You're in <white>the Scrapyard<gray> - PvP is on. " + pvp.status() + "<gray>. Leave with <white>/spawn<gray>."));
        });
        reg("tags", List.of("tag", "namecolor", "colors"), (p, a) -> tags.open(p));
        reg("kits", List.of("godkits"), (p, a) -> kits.open(p));
        reg("kit", List.of("rankkit", "rankkits"), (p, a) -> rankKits.open(p));
        reg("enchants", List.of("ce", "customenchants"), (p, a) -> enchants.info(p));
        reg("vote", List.of("votes"), (p, a) -> votes.info(p));
        reg("ranks", List.of("rank"), (p, a) -> ranks.info(p));
        reg("daily", List.of("dailyreward", "reward"), (p, a) -> daily.claim(p));
        reg("monthly", List.of("rankkeys"), (p, a) -> rankKits.open(p));   // keys now come in the 10-day /kit
        reg("repairhand", List.of("rh", "fixhand"), (p, a) -> ranks.repairHand(p));
        reg("fly", List.of(), (p, a) -> ranks.fly(p));
        reg("scrap", List.of("scrapshop", "shards"), (p, a) -> scrap.command(p, a));
        reg("jbello", List.of("bellows", "galebellows"), (p, a) -> {   // staff only: players get bellows from crates
            if (p.hasPermission("alloy.admin")) bellows.give(p);
            else p.sendMessage(Ui.mm("<#9EE6FF>🌬 <gray>The " + GaleBellows.NAME + " <gray>only comes from <white>crates <gray>and <white>Scrapyard supply drops<gray>."));
        });
        reg("gaia", List.of("gaiapotion"), (p, a) -> {   // staff only for now, like the bellows: players will get it from crates
            if (p.hasPermission("alloy.admin")) gaia.give(p);
            else p.sendMessage(Ui.mm("<#FFB3DE>✿ <gray>The " + GaiaPotion.NAME + " <gray>isn't available to players yet."));
        });
        reg("koth", List.of(), (p, a) -> {
            if (a.length > 0 && p.hasPermission("alloy.admin")) {
                if (a[0].equalsIgnoreCase("start")) koth.start();
                else if (a[0].equalsIgnoreCase("stop")) koth.stop("KOTH was stopped by staff.");
                return;
            }
            p.sendMessage(Ui.mm(koth.status() + " <gray>• <white>/warp koth"));
        });
    }

    private interface PlayerCmd { void run(Player p, String[] args); }

    private void reg(String name, List<String> aliases, PlayerCmd cmd) {
        Command c = new Command(name, "BlackForgeMC " + name, "/" + name, aliases) {
            @Override public boolean execute(CommandSender s, String label, String[] args) {
                if (s instanceof Player p) {
                    if (label.equalsIgnoreCase("warps")) { warp(p, new String[0]); return true; }
                    cmd.run(p, args);
                } else s.sendMessage("Players only.");
                return true;
            }
            @Override public List<String> tabComplete(CommandSender s, String alias, String[] args) {
                if (name.equals("warp") && args.length == 1) return List.of("spawn", "crates", "koth", "pvp", "wild", "market");
                if (name.equals("ah") && args.length == 1) return List.of("sell", "listings", "collect", "help");
                if (name.equals("claim") && args.length == 1) return List.of("map", "show", "info", "list", "trust", "untrust", "unclaimall", "help");
                return super.tabComplete(s, alias, args);
            }
        };
        Bukkit.getCommandMap().register("alloy", c);
    }

    /** Console/admin: /alloy crate give <player> <crate> [amount], /alloy koth start|stop, /alloy buildsurvival */
    public boolean admin(CommandSender s, String[] a) {
        if (a.length >= 3 && a[0].equalsIgnoreCase("gaia") && a[1].equalsIgnoreCase("give")) {   // /alloy gaia give <player> (for crates)
            Player t = Bukkit.getPlayerExact(a[2]);
            if (t == null) { s.sendMessage(Component.text("Player must be online")); return true; }
            gaia.give(t);
            s.sendMessage(Component.text("Gave a Potion of Gaia to " + a[2]));
            return true;
        }
        if (a.length >= 3 && a[0].equalsIgnoreCase("bellows") && a[1].equalsIgnoreCase("give")) {   // /alloy bellows give <player>
            Player t = Bukkit.getPlayerExact(a[2]);
            if (t == null) {
                var off = Bukkit.getOfflinePlayerIfCached(a[2]);
                if (off == null) { s.sendMessage(Component.text("Never seen " + a[2] + " - they need to have joined once")); return true; }
                bellows.queue(off.getUniqueId());
                s.sendMessage(Component.text(a[2] + " is offline - they get the Gale Bellows the next time they join Survival"));
                return true;
            }
            bellows.give(t);
            s.sendMessage(Component.text("Gave a Gale Bellows to " + a[2]));
            return true;
        }
        if (a.length >= 4 && a[0].equalsIgnoreCase("crate") && a[1].equalsIgnoreCase("give")) {
            Player t = Bukkit.getPlayerExact(a[2]);
            int amount = a.length > 4 ? Integer.parseInt(a[4]) : 1;
            boolean ok = t != null && crates.give(t, a[3], amount);
            s.sendMessage(Component.text(ok ? "Gave " + amount + " " + a[3] + " key(s) to " + a[2] : "Unknown player or crate (" + crates.ids() + ")"));
            return true;
        }
        if (a.length >= 2 && a[0].equalsIgnoreCase("keyall")) {
            int n = crates.giveAll(a[1], a.length > 2 ? Integer.parseInt(a[2]) : 1, a.length > 3 ? String.join(" ", java.util.Arrays.copyOfRange(a, 3, a.length)) : "");
            s.sendMessage(Component.text(n < 0 ? "Unknown crate (" + crates.ids() + ")" : "Key all: " + n + " players"));
            return true;
        }
        if (a.length >= 2 && a[0].equalsIgnoreCase("koth")) {
            if (a[1].equalsIgnoreCase("start")) koth.start(); else koth.stop("KOTH was stopped by staff.");
            return true;
        }
        if (a.length >= 4 && a[0].equalsIgnoreCase("tag") && a[1].equalsIgnoreCase("give")) {
            Player t = Bukkit.getPlayerExact(a[2]);
            s.sendMessage(Component.text(t != null && tags.give(t, a[3]) ? "Gave " + a[3] + " name tag to " + a[2] : "Unknown player or tag"));
            return true;
        }
        if (a.length >= 4 && a[0].equalsIgnoreCase("voucher") && a[1].equalsIgnoreCase("give")) {
            Player t = Bukkit.getPlayerExact(a[2]);
            s.sendMessage(Component.text(t != null && ranks.giveVoucher(t, a[3]) ? "Gave " + a[3] + " voucher to " + a[2] : "Unknown player (must be online) or rank"));
            return true;
        }
        if (a.length >= 3 && a[0].equalsIgnoreCase("vote") && a[1].equalsIgnoreCase("test")) {
            votes.vote(a[2], a.length > 3 ? a[3] : "TestList");
            s.sendMessage(Component.text("Simulated a vote for " + a[2]));
            return true;
        }
        if (a.length >= 4 && a[0].equalsIgnoreCase("scrap") && a[1].equalsIgnoreCase("give")) {
            Player t = Bukkit.getPlayerExact(a[2]);
            if (t == null) { s.sendMessage(Component.text("Player must be online")); return true; }
            scrap.add(t, Long.parseLong(a[3]), "gift");
            s.sendMessage(Component.text("Gave " + a[3] + " scrap to " + a[2]));
            return true;
        }
        if (a.length >= 4 && a[0].equalsIgnoreCase("ce") && a[1].equalsIgnoreCase("give")) {
            Player t = Bukkit.getPlayerExact(a[2]);
            int lvl = a.length > 4 ? Integer.parseInt(a[4]) : 1;
            s.sendMessage(Component.text(t != null && enchants.give(t, a[3], lvl) ? "Gave " + a[3] + " " + lvl + " book to " + a[2] : "Unknown player or enchant"));
            return true;
        }
        if (a.length >= 4 && a[0].equalsIgnoreCase("kit") && a[1].equalsIgnoreCase("give")) {
            Player t = Bukkit.getPlayerExact(a[2]);
            s.sendMessage(Component.text(t != null && kits.giveFree(t, a[3]) ? "Delivered kit " + a[3] + " to " + a[2] : "Unknown player (must be online) or kit " + kits.ids()));
            return true;
        }
        if (a.length >= 1 && a[0].equalsIgnoreCase("buildsurvival")) { build(s); return true; }
        if (a.length >= 1 && a[0].equalsIgnoreCase("buildpvp")) { buildPvp(s); return true; }
        if (a.length >= 2 && a[0].equalsIgnoreCase("pvp") && a[1].equalsIgnoreCase("sample")) {
            Player any = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
            if (any == null) { s.sendMessage(Component.text("Needs a player online (keys broadcast their name).")); return true; }
            for (String line : pvp.sample(any, a.length > 2 ? Integer.parseInt(a[2]) : 20)) plugin.getLogger().info("[loot] " + line);
            s.sendMessage(Component.text("Rolled - see the server log."));
            return true;
        }
        if (a.length >= 2 && a[0].equalsIgnoreCase("pvp") && a[1].equalsIgnoreCase("drop")) { pvp.drop(); s.sendMessage(Component.text("Supply drop started.")); return true; }
        return false;
    }

    private boolean inCombat(Player p) {
        Long t = combat.get(p.getUniqueId());
        if (t != null && t > System.currentTimeMillis()) {
            p.sendMessage(Ui.mm("<red>You're in combat! Wait <white>" + (t - System.currentTimeMillis()) / 1000 + "s<red>."));
            return true;
        }
        return false;
    }

    private void dropInto(Player p, Location to, String what) {
        if (inCombat(p)) return;
        warmupApproved.add(p.getUniqueId());
        p.teleportAsync(to).thenRun(() -> {
            dropping.add(p.getUniqueId());
            landed.remove(p.getUniqueId());
            p.playSound(p.getLocation(), Sound.ITEM_ELYTRA_FLYING, 0.5f, 1.4f);
            p.showTitle(net.kyori.adventure.title.Title.title(Ui.mm("<red><bold>DROPPING IN"), Ui.mm("<gray>" + what)));
        });
        Bukkit.getScheduler().runTaskLater(plugin, () -> dropping.remove(p.getUniqueId()), 20L * 20);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDropLanding(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && e.getCause() == EntityDamageEvent.DamageCause.FALL && dropping.contains(p.getUniqueId())) {
            e.setCancelled(true);
            p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 20, 0.5, 0.1, 0.5, 0.05);
            p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_BIG_FALL, 1f, 0.8f);
        }
    }

    private boolean inDangerZone(Location l) {
        return l.getWorld() == world && (inArena(l.getX(), l.getY(), l.getZ()) || inPvp(l.getX(), l.getY(), l.getZ()));
    }

    /**
     * Leaving the KOTH arena or the Scrapyard by teleport (spawn, home, rtp, tpa, back, warps...)
     * takes a 10 second warmup; moving or taking damage cancels it.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEscapeTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        if (warmupApproved.remove(p.getUniqueId())) return;
        var cause = e.getCause();
        if (cause != PlayerTeleportEvent.TeleportCause.COMMAND && cause != PlayerTeleportEvent.TeleportCause.PLUGIN && cause != PlayerTeleportEvent.TeleportCause.UNKNOWN) return;
        if (!inDangerZone(e.getFrom()) || inDangerZone(e.getTo())) return;
        if (p.hasPermission("alloy.admin") && p.getGameMode() == GameMode.CREATIVE) return;
        Location dest = e.getTo().clone();
        e.setCancelled(true);
        startWarmup(p, () -> {
            warmupApproved.add(p.getUniqueId());
            plugin.getLogger().info("[warmup] " + p.getName() + " -> " + dest.getBlockX() + "," + dest.getBlockY() + "," + dest.getBlockZ());
            p.teleportAsync(dest);
        });
    }

    /** /hub from inside a fight zone also waits out the warmup. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEscapeCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        String root = e.getMessage().substring(1).split(" ")[0].toLowerCase();
        if (!List.of("hub", "lobby", "l", "server").contains(root) || !inDangerZone(p.getLocation())) return;
        if (warmupApproved.remove(p.getUniqueId())) return;
        e.setCancelled(true);
        String cmd = e.getMessage().substring(1);
        startWarmup(p, () -> { warmupApproved.add(p.getUniqueId()); p.performCommand(cmd); });
    }

    private void startWarmup(Player p, Runnable then) {
        if (warmups.containsKey(p.getUniqueId())) { p.sendActionBar(Ui.mm("<yellow>You're already teleporting out...")); return; }
        Location start = p.getLocation().clone();
        double hp0 = p.getHealth();
        final int[] left = {ESCAPE_SECONDS};
        p.sendMessage(Ui.mm("<yellow>⏳ Teleporting out in <white>" + ESCAPE_SECONDS + "s<yellow> - don't move or take damage!"));
        warmups.put(p.getUniqueId(), 1);
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            boolean moved = !p.isOnline() || p.getLocation().getWorld() != start.getWorld() || p.getLocation().distanceSquared(start) > 0.6;
            if (moved || p.getHealth() < hp0 - 0.01 || p.isDead()) {
                task.cancel();
                warmups.remove(p.getUniqueId());
                if (p.isOnline()) { p.sendActionBar(Ui.mm("<red>✖ Teleport cancelled")); p.sendMessage(Ui.mm("<red>✖ Teleport cancelled - you " + (moved ? "moved" : "took damage") + ".")); }
                return;
            }
            if (left[0] <= 0) { task.cancel(); warmups.remove(p.getUniqueId()); then.run(); return; }
            p.sendActionBar(Ui.mm("<yellow>⏳ Teleporting out in <white>" + left[0] + "s"));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.2f);
            left[0]--;
        }, 0L, 20L);
    }

    private void teleportSafe(Player p, Location to, String what) {
        if (inCombat(p)) return;
        p.teleportAsync(to).thenRun(() -> {
            p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.2f);
            p.sendActionBar(Ui.mm("<gold>Warped to <white>" + what));
        });
    }

    private void warp(Player p, String[] a) {
        if (a.length == 0) {
            p.sendMessage(Ui.mm("<gold><bold>Warps</bold> <dark_gray>» <yellow>spawn <gray>• <yellow>crates <gray>• <yellow>market <gray>• <yellow>koth <gray>• <yellow>pvp <gray>• <yellow>wild"));
            p.sendMessage(Ui.mm("<gray>Use <white>/warp <name>"));
            return;
        }
        switch (a[0].toLowerCase()) {
            case "spawn" -> teleportSafe(p, spawn(), "spawn");
            case "crates", "crate" -> teleportSafe(p, new Location(world, CRATES_WARP_X, Y + 2, CRATES_WARP_Z, 180, 0), "crates");
            case "koth", "arena" -> dropInto(p, koth.arrival(), "the KOTH arena - PvP is on!");
            case "pvp", "scrapyard" -> { dropInto(p, pvp.arrival(), "the Scrapyard - PvP is on!"); }
            case "market", "ah", "auction" -> teleportSafe(p, new Location(world, BELL_X + 0.5, Y + 1, BELL_Z - 4.5, 0, 0), "the market");
            case "wild", "rtp" -> rtp(p);
            default -> p.sendMessage(Ui.mm("<red>No warp called " + a[0] + ". <gray>Try /warp"));
        }
    }

    // ------------------------------------------------------------------ random teleport

    private void rtp(Player p) {
        if (inCombat(p)) return;
        long now = System.currentTimeMillis();
        long until = rtpCooldown.getOrDefault(p.getUniqueId(), 0L);
        if (until > now && !p.hasPermission("alloy.admin")) { p.sendMessage(Ui.mm("<red>Wait <white>" + (until - now) / 1000 + "s <red>before using /rtp again.")); return; }
        rtpCooldown.put(p.getUniqueId(), now + 60_000);
        p.sendActionBar(Ui.mm("<green>Finding a safe spot in the wild..."));
        tryRtp(p, 0);
    }

    private void tryRtp(Player p, int attempt) {
        if (attempt >= 30) { p.sendMessage(Ui.mm("<red>Couldn't find a safe spot, try again.")); rtpCooldown.remove(p.getUniqueId()); return; }
        // anywhere inside the world border, but well away from spawn
        Random r = new Random();
        int half = rtpRange / 2 - 64;
        int x, z;
        do { x = r.nextInt(half * 2) - half; z = r.nextInt(half * 2) - half; } while (Math.hypot(x, z) < 400);
        final int fx = x, fz = z;
        world.getChunkAtAsync(fx >> 4, fz >> 4).thenAccept(ch -> {
            Block top = world.getHighestBlockAt(fx, fz);
            Material t = top.getType();
            boolean bad = top.isLiquid() || t == Material.MAGMA_BLOCK || t == Material.CACTUS || t.name().contains("LEAVES") || t == Material.POWDER_SNOW
                    || t == Material.BAMBOO || t.name().contains("ICE") || claims.ownerAt(top.getLocation()) != null || top.getY() < 55 || top.getY() > 200
                    || (Math.abs(fx - PVP_X) < PVP_HALF + 40 && Math.abs(fz - PVP_Z) < PVP_HALF + 40) || Math.hypot(fx - KOTH_X, fz - KOTH_Z) < 90;
            if (bad) { tryRtp(p, attempt + 1); return; }
            Location dest = top.getLocation().add(0.5, 1, 0.5);
            dest.setYaw(p.getLocation().getYaw());
            p.teleportAsync(dest).thenRun(() -> {
                p.sendMessage(Ui.mm("<green>☘ Welcome to the wild! <gray>You're at <white>" + fx + ", " + fz + "<gray>. Use <white>/claim <gray>to protect your base."));
                p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 0.9f);
            });
        });
    }

    // ------------------------------------------------------------------ players

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!p.hasPlayedBefore()) {
            p.teleport(spawn());
            Bukkit.broadcast(Ui.mm("<gold>✦ <gray>Welcome <white>" + p.getName() + " <gray>to BlackForgeMC Survival!"));
            Bukkit.getScheduler().runTaskLater(plugin, () -> crates.give(p, "iron", 1), 40L);
        }
        crates.onJoin(p);
        if (koth.active()) Bukkit.getScheduler().runTaskLater(plugin, () -> p.sendMessage(Ui.mm(koth.status() + " <gray>• <white>/warp koth")), 60L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { combat.remove(e.getPlayer().getUniqueId()); }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        if (!e.isBedSpawn() && !e.isAnchorSpawn()) e.setRespawnLocation(spawn());
    }

    private boolean protectedArea(Location l) {
        return l.getWorld() == world && (inSpawn(l.getX(), l.getY(), l.getZ()) || inArena(l.getX(), l.getY(), l.getZ()) || inPvp(l.getX(), l.getY(), l.getZ()));
    }

    private boolean staff(Player p) { return p.hasPermission("alloy.admin") && p.getGameMode() == GameMode.CREATIVE; }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent e) { if (protectedArea(e.getBlock().getLocation()) && !staff(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent e) { if (protectedArea(e.getBlock().getLocation()) && !staff(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || staff(e.getPlayer()) || !protectedArea(b.getLocation())) return;
        if (e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK && (b.getType().isInteractable() || e.getItem() != null) && b.getType() != Material.VAULT)
            e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        boolean pvpBucket = inPvp(b.getX(), b.getY(), b.getZ()) && e.getItem() != null && e.getItem().getType().name().endsWith("_BUCKET");
        if (pvpBucket) { e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY); e.setUseItemInHand(org.bukkit.event.Event.Result.ALLOW); return; }
        if (e.getItem() != null && e.getItem().getType().name().endsWith("_BUCKET")) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBell(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.BELL || e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        if (b.getX() != BELL_X || b.getZ() != BELL_Z || b.getY() != Y + 2 || e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        e.setCancelled(true);
        world.playSound(b.getLocation(), Sound.BLOCK_BELL_USE, 1f, 1f);
        if (ah != null) ah.openBrowse(e.getPlayer(), 0);
    }

    /** Nobody (players or mobs) tramples the farm ring or any other farmland around spawn. */
    @EventHandler(priority = EventPriority.LOW)
    public void onTrample(PlayerInteractEvent e) {
        if (e.getAction() == org.bukkit.event.block.Action.PHYSICAL && e.getClickedBlock() != null && e.getClickedBlock().getType() == Material.FARMLAND
                && protectedArea(e.getClickedBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler public void onMobTrample(org.bukkit.event.entity.EntityChangeBlockEvent e) {
        if (e.getBlock().getType() == Material.FARMLAND && protectedArea(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler public void onFade(org.bukkit.event.block.BlockFadeEvent e) {
        if (e.getBlock().getType() == Material.FARMLAND && protectedArea(e.getBlock().getLocation())) e.setCancelled(true);
    }

    @EventHandler public void onBucket(PlayerBucketEmptyEvent e) { if (protectedArea(e.getBlock().getLocation()) && !staff(e.getPlayer())) e.setCancelled(true); }

    @EventHandler
    public void onExplode(EntityExplodeEvent e) { e.blockList().removeIf(b -> protectedArea(b.getLocation())); }

    @EventHandler
    public void onMobSpawn(CreatureSpawnEvent e) {
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM && e.getEntity() instanceof Monster && protectedArea(e.getLocation())) e.setCancelled(true);
        if (e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL && protectedArea(e.getLocation())) e.setCancelled(true);
    }

    @EventHandler public void onFire(org.bukkit.event.block.BlockIgniteEvent e) { if (protectedArea(e.getBlock().getLocation()) && e.getPlayer() == null) e.setCancelled(true); }

    @EventHandler
    public void onPortal(PlayerPortalEvent e) { if (protectedArea(e.getFrom())) e.setCancelled(true); }

    @EventHandler
    public void onHunger(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p && inSpawn(p.getX(), p.getY(), p.getZ()) && e.getFoodLevel() < p.getFoodLevel()) e.setCancelled(true);
    }

    private Player attacker(Entity d) {
        if (d instanceof Player p) return p;
        if (d instanceof Projectile pr) { ProjectileSource s = pr.getShooter(); if (s instanceof Player p) return p; }
        if (d instanceof TNTPrimed t && t.getSource() instanceof Player p) return p;
        return null;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Location l = victim.getLocation();
        if (inSpawn(l.getX(), l.getY(), l.getZ())) { e.setCancelled(true); return; }
        if (e instanceof EntityDamageByEntityEvent ee) {
            Player a = attacker(ee.getDamager());
            if (a == null || a == victim) return;
            boolean arena = (inArena(l.getX(), l.getY(), l.getZ()) && inArena(a.getX(), a.getY(), a.getZ()))
                    || (inPvp(l.getX(), l.getY(), l.getZ()) && inPvp(a.getX(), a.getY(), a.getZ()));
            if (!arena) { e.setCancelled(true); a.sendActionBar(Ui.mm("<gray>PvP is only allowed in the <red>KOTH arena<gray> and <red>/pvp<gray>.")); return; }
            long until = System.currentTimeMillis() + 15_000;
            ranks.dropFlight(victim);
            ranks.dropFlight(a);
            combat.put(victim.getUniqueId(), until);
            combat.put(a.getUniqueId(), until);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        combat.remove(e.getPlayer().getUniqueId());
        Player k = e.getPlayer().getKiller();
        if (k != null && inArena(k.getX(), k.getY(), k.getZ())) k.sendActionBar(Ui.mm("<red>⚔ <white>You killed " + e.getPlayer().getName()));
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        // a dropped-in player is fall-protected until a moment after they first touch the ground
        if (p.isOnGround() && dropping.contains(p.getUniqueId()) && landed.add(p.getUniqueId()))
            Bukkit.getScheduler().runTaskLater(plugin, () -> { dropping.remove(p.getUniqueId()); landed.remove(p.getUniqueId()); }, 10L);
        if (!e.hasChangedBlock()) return;
        Location to = e.getTo();
        if (to.getWorld() != world) return;
        if (inShaft(to.getX(), to.getY(), to.getZ(), PVP_SHAFT_Z)) { dropInto(p, pvp.arrival(), "the Scrapyard - PvP is on!"); return; }
        if (inShaft(to.getX(), to.getY(), to.getZ(), KOTH_SHAFT_Z)) { dropInto(p, koth.arrival(), "the KOTH arena - PvP is on!"); return; }
        boolean wild = inWildGate(to.getX(), to.getY(), to.getZ()), arena = false;
        if (!wild) return;
        long now = System.currentTimeMillis();
        if (gateCooldown.getOrDefault(p.getUniqueId(), 0L) > now) return;
        gateCooldown.put(p.getUniqueId(), now + 3000);
        p.setVelocity(new org.bukkit.util.Vector(wild ? -0.8 : 0.8, 0.3, 0));
        if (wild) rtp(p); else dropInto(p, koth.arrival(), "the KOTH arena - PvP is on!");
    }

    // ------------------------------------------------------------------ holograms / sidebar / timers

    static final String RULES = String.join("<newline>",
            "<gold><bold>SERVER RULES</bold>",
            "",
            "<yellow>1. <white>Be respectful <gray>- no harassment, hate or slurs",
            "<yellow>2. <white>No griefing or stealing <gray>- claimed or not",
            "<yellow>3. <white>No hacks, x-ray or unfair mods",
            "<yellow>4. <white>No spamming or advertising",
            "<yellow>5. <white>No exploiting bugs or duping",
            "<yellow>6. <white>No scamming in trades or the /ah",
            "<yellow>7. <white>PvP only in the KOTH arena",
            "<yellow>8. <white>Staff have the final say",
            "",
            "<gray>Breaking rules can get you muted, kicked or banned.",
            "<gold>Have fun and protect your stuff with /claim!");

    private void spawnHolograms() {
        holos.forEach(TextDisplay::remove);
        holos.clear();
        world.getEntitiesByClass(TextDisplay.class).stream().filter(t -> protectedArea(t.getLocation())).forEach(TextDisplay::remove);
        int archZ = (int) SPAWN_Z - 9;
        holos.add(Ui.hologram(new Location(world, 0.5, Y + 10.6, archZ + 0.5),
                "<gray>Welcome to</gray><newline>" + Ui.TITLE + " <white><bold>SURVIVAL", 1.6f));                                    // 0
        holos.add(Ui.hologram(new Location(world, GATE_X - 2.5, Y + 9.5, 0.5), "<#7ddc4a><bold>THE WILD</bold><newline><gray>Random teleport", 1.5f));   // 1
        holos.add(Ui.hologram(new Location(world, DROP_X + 0.5, Y + 11.4, 0.5), "<#b06bff><bold>THE DROP ZONE</bold><newline><gray>Jump in a shaft to drop into a fight", 1.5f));      // 2
        holos.add(Ui.hologram(new Location(world, 0.5, Y + 2.6, 9.5),
                "<gold><bold>GETTING STARTED</bold><newline><green>/rtp <gray>head out into the wild<newline><yellow>/claim <gray>protect the chunk you're in"
                        + "<newline><yellow>/sethome <gray>then <yellow>/home <gray>to come back<newline><gold>/ah <gray>buy and sell • <gold>/warp crates <gray>open keys<newline><red>/pvp <gray>the Scrapyard: supply drops every 5h<newline><light_purple>/tags <gray>colour your name • <gold>/kits <gray>god kits • <aqua>/enchants<newline><gold>/daily <gray>free reward • <#5fd35f>/vote <gray>for keys • <#c6a15b>/scrap <gray>the Scrap Shop", 0.9f));  // 3
        kothHolo = Ui.hologram(new Location(world, SurvivalLayout.KOTH_X + 0.5, HILL_TOP + 4.5, SurvivalLayout.KOTH_Z + 0.5), "<#e5484d><bold>KING OF THE HILL", 2.0f);
        holos.add(kothHolo);                                                                                                         // 4
        holos.add(Ui.hologram(new Location(world, DROP_X + 0.5, Y + 6.6, 0.5), koth.status(), 1.0f));                              // 5
        holos.add(Ui.board(new Location(world, -BOARD_X + 0.6, Y + 1.6, BOARD_Z + 0.5), RULES, 1.0f, -90));                         // 6
        leaderboard = Ui.board(new Location(world, BOARD_X + 0.4, Y + 1.6, BOARD_Z + 0.5), topText, 1.0f, 90);
        holos.add(leaderboard);                                                                                                      // 7
        holos.add(Ui.hologram(new Location(world, BELL_X + 0.5, Y + 3.8, BELL_Z + 0.5),
                "<gold><bold>AUCTION HOUSE</bold><newline><gray>Ring the bell or type <white>/ah<newline><gray>Sell with <white>/ah sell <price>", 1.1f));   // 8
        holos.add(Ui.hologram(new Location(world, DROP_X + 0.5, Y + 3.2, PVP_SHAFT_Z + 0.5), "<#ff5a3c><bold>⬇ THE SCRAPYARD ⬇</bold><newline><gray>Supply drops • PvP", 1.2f));   // 9
        holos.add(Ui.hologram(new Location(world, DROP_X + 0.5, Y + 3.2, KOTH_SHAFT_Z + 0.5), "<#e5484d><bold>⬇ KOTH ARENA ⬇</bold><newline><gray>Hold the hill • PvP", 1.2f));       // 10
        holos.add(Ui.hologram(new Location(world, HALL_CX + 0.5, Y + 9.0, HALL_CZ - 8.5), "<gradient:#ffd27a:#ff7a1a><bold>HALL OF FAME</bold></gradient><newline><gray>The best players on BlackForgeMC", 1.6f));
        crates.spawnHolograms();
        Bukkit.getScheduler().runTaskLater(plugin, hall::refresh, 5L);
    }

    private void refreshLeaderboard() {
        if (eco == null) return;
        List<Map.Entry<String, Double>> rows = new ArrayList<>();
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            if (op.getName() == null || op.getName().startsWith("AlloyTester")) continue;
            try { rows.add(Map.entry(op.getName(), eco.getBalance(op))); } catch (Exception ignored) { }
        }
        rows.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        StringBuilder sb = new StringBuilder("<gold><bold>RICHEST PLAYERS</bold><newline><gray>Updated every minute • /baltop<newline> ");
        String[] medal = {"<#ffd700>", "<#c0c0c0>", "<#cd7f32>"};
        for (int i = 0; i < 10; i++) {
            sb.append("<newline>");
            if (i < rows.size()) sb.append(i < 3 ? medal[i] : "<yellow>").append("#").append(i + 1).append(" <white>").append(rows.get(i).getKey())
                    .append(" <dark_gray>- <green>").append(AuctionHouse.money(rows.get(i).getValue()));
            else sb.append("<dark_gray>#").append(i + 1).append(" ---");
        }
        topText = sb.toString();
        if (leaderboard != null && leaderboard.isValid()) leaderboard.text(Ui.mm(topText));
    }

    private void everySecond() {
        if (holos.isEmpty() || holos.stream().anyMatch(t -> !t.isValid()) || crates.hologramsMissing()) {
            if (new File(plugin.getDataFolder(), "survival-built").exists()) spawnHolograms();
        }
        if (hall.hologramsMissing() && Bukkit.getCurrentTick() % 5 == 0) hall.refresh();
        if (holos.size() > 5) holos.get(5).text(Ui.mm(koth.status() + "<newline>" + pvp.status() + " <gray>• <white>/pvp"));
        if (kothHolo != null && kothHolo.isValid()) kothHolo.text(Ui.mm("<#e5484d><bold>KING OF THE HILL</bold><newline>" + (koth.active() ? "<green>LIVE - hold the gold pad!" : koth.status())));
        for (Player p : Bukkit.getOnlinePlayers()) {
            Ui.sidebar(p, List.of(
                    "<dark_gray>" + "-".repeat(18),
                    "<gray>Balance: <green>" + (eco == null ? "?" : AuctionHouse.money(eco.getBalance(p))),
                    "<gray>Rank: " + (ranks.of(p) == null ? "<white>Member" : ranks.of(p).color() + ranks.of(p).name()),
                    "<gray>Scrap: <#c6a15b>⚙ " + scrap.get(p),
                    "<gray>Claims: <white>" + claims.count(p.getUniqueId()) + "/" + claims.allowance(p),
                    " ",
                    koth.active() ? "<#e5484d><bold>KOTH LIVE!" : "<gray>KOTH: <white>" + koth.status().replaceAll("<[^>]+>", "").replace("Next KOTH in ", ""),
                    pvp.waiting() > 0 ? "<gold><bold>" + pvp.waiting() + " supply crates!" : "<gray>Drop: <white>" + pvp.status().replaceAll("<[^>]+>", "").replace("Next supply drop in ", ""),
                    "<gray>Vote party: <#5fd35f>" + votes.partyStatus(),
                    "<gray>Online: <white>" + Bukkit.getOnlinePlayers().size(),
                    "  ",
                    "<gold>" + plugin.getConfig().getString("address", "BlackForgeMC"),
                    "<dark_gray>" + "-".repeat(18) + " "));
        }
    }

}
