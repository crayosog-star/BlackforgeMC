package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import net.alloymc.core.build.Paster;
import net.kyori.adventure.text.Component;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;

import java.io.File;
import java.lang.reflect.Method;
import java.util.*;

import static net.alloymc.core.survival.SkyblockBuilder.*;

/**
 * BlackForgeMC Skyblock: BentoBox runs the islands; this adds the Sky Harbor spawn and the same
 * crates, auction house, Scrap Shop, ranks, daily rewards, name tags, enchants and leaderboards
 * as Survival.
 */
public final class SkyblockModule implements Listener {
    private final AlloyCore plugin;
    private final World world;
    private final Crates crates;
    private final NameTags tags;
    private final CustomEnchants enchants;
    private final Scrap scrap;
    private final Ranks ranks;
    private final HallOfFame hall;
    private final DailyRewards daily;
    private final RankKits rankKits;
    private final Spawners spawners;
    private final Robots robots;
    private final OneBlockExtras oneblockExtras;
    private final List<TextDisplay> holos = new ArrayList<>();
    private final Map<UUID, Long> gateCooldown = new HashMap<>();
    private AuctionHouse ah, ahOneBlock;
    private final SkyHub skyHub;
    private final InventoryGroups groups;
    private final Crates obCrates;
    private Economy eco;

    public SkyblockModule(AlloyCore plugin) {
        this.plugin = plugin;
        this.world = Bukkit.getWorlds().getFirst();
        crates = new Crates(plugin, world, CRATES, Y + 2);
        // the Skyblock Hub picks the game; Skyblock and OneBlock keep separate inventories, crates and auction houses
        skyHub = new SkyHub(plugin, world, spawn());
        groups = new InventoryGroups(plugin, skyHub::groupOf);
        skyHub.setGroups(groups);
        obCrates = new Crates(plugin, skyHub.oneblockSpawnWorld(), SkyHub.OB_CRATES, SkyHub.Y + 1);
        // AFK zone off the side of the Skyblock Hub: Scrap only (keys would land in the hub's empty inventory)
        new AfkZone(plugin, skyHub.hubWorld(), new Location(skyHub.hubWorld(), 0.5, SkyHub.Y + 6, 40.5), 6, null, "skyblock-afkzone-built");
        skyHub.onFirstVisit((p, game) -> {
            crates.give(p, "iron", 1);
            p.sendMessage(Ui.mm("<gray>A welcome <white>Cinder Key <gray>for your first trip to " + (game.equals(SkyHub.ONEBLOCK) ? "<#b06bff>OneBlock" : "<#4fd1c5>Skyblock") + "<gray>!"));
        });
        tags = new NameTags(plugin);
        enchants = new CustomEnchants(plugin);
        scrap = new Scrap(plugin, crates, tags, enchants);
        new SilkSpawners(plugin);
        ranks = new Ranks(plugin, crates);
        ranks.setFlyRule(this::onOwnIsland);
        scrap.setRanks(ranks);
        tags.setRanks(ranks);
        hall = new HallOfFame(plugin, world, HALL, Y, HallOfFame.SKYBLOCK_BOARDS);
        hall.setLevelSource(this::islandLevel);
        hall.setOneBlockSource(id -> { OneBlockStat s = oneblockStat(id); return s == null ? null : (double) s.lifetime(); });
        daily = new DailyRewards(plugin, crates, enchants, tags);
        daily.setScrap(scrap);
        rankKits = new RankKits(plugin, ranks, tags, crates);
        spawners = new Spawners(plugin);
        spawners.setAccess(this::islandMember);
        oneblockExtras = new OneBlockExtras(plugin, spawners);
        robots = new Robots(plugin);
        robots.setAccess(this::islandMember);
        robots.setRanks(ranks);
        scrap.addRobotOffers();
        world.setSpawnLocation(spawn());
        for (int cx = -4; cx <= 3; cx++) for (int cz = -4; cz <= 3; cz++) world.addPluginChunkTicket(cx, cz, plugin);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        new net.alloymc.core.SpawnSafety(plugin, l -> l.getWorld() == world && inSpawn(l.getX(), l.getY(), l.getZ()), p -> spawn());
        registerCommands();
        Bukkit.getScheduler().runTask(plugin, () -> {
            var reg = Bukkit.getServicesManager().getRegistration(Economy.class);
            if (reg == null) { plugin.getLogger().warning("No Vault economy - /ah disabled"); return; }
            eco = reg.getProvider();
            spawners.setEconomy(eco);
            robots.setEconomy(eco);
            ah = new AuctionHouse(plugin, eco);
            ahOneBlock = new AuctionHouse(plugin, eco, "auctions-oneblock.yml");
            tags.setEconomy(eco);
            daily.setEconomy(eco);
            hall.setEconomy(eco);
        });
        if (!new File(plugin.getDataFolder(), "skyblock-built").exists()) build(Bukkit.getConsoleSender());
        Bukkit.getScheduler().runTaskTimer(plugin, this::everySecond, 40L, 20L);
    }

    public void shutdown() {
        holos.forEach(TextDisplay::remove);
        skyHub.shutdown();
        scrap.save();
        robots.save();
        oneblockExtras.shutdown();
        hall.shutdown();
    }

    public Location spawn() { return new Location(world, SPAWN_X, SPAWN_Y, SPAWN_Z, 180, 0); }

    public void build(CommandSender who) {
        who.sendMessage(Component.text("Building Sky Harbor..."));
        int[] x = {-80};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            for (int n = 0; n < 2 && x[0] <= 80; n++, x[0]++)
                for (int z = -80; z <= 80; z++)
                    for (int y = Y - 50; y <= Y + 45; y++) {
                        Block b = world.getBlockAt(x[0], y, z);
                        if (!b.getType().isAir()) b.setType(Material.AIR, false);
                    }
            if (x[0] <= 80) return;
            task.cancel();
            Paster.paste(plugin, world, SkyblockBuilder.build(), 6000, () -> {
                try { new File(plugin.getDataFolder(), "skyblock-built").createNewFile(); } catch (Exception ignored) { }
                who.sendMessage(Component.text("Sky Harbor built."));
                spawnHolograms();
            });
        }, 1L, 1L);
    }

    // ------------------------------------------------------------------ BentoBox (by reflection, so AlloyCore needs no compile dependency)

    private Object islands() {
        try {
            Class<?> bb = Class.forName("world.bentobox.bentobox.BentoBox");
            Object inst = bb.getMethod("getInstance").invoke(null);
            return inst.getClass().getMethod("getIslands").invoke(inst);
        } catch (Exception e) { return null; }
    }

    private World islandWorld() { return Bukkit.getWorld("bskyblock_world"); }

    /** Is this spot on an island the player belongs to? (staff may use anything) */
    private boolean islandMember(Player p, Location l) {
        if (p.hasPermission("alloy.admin")) return true;
        try {
            Object im = islands();
            if (im == null) return false;
            Optional<?> island = (Optional<?>) im.getClass().getMethod("getIslandAt", Location.class).invoke(im, l);
            if (island.isEmpty()) return false;
            @SuppressWarnings("unchecked") Set<UUID> members = (Set<UUID>) island.get().getClass().getMethod("getMemberSet").invoke(island.get());
            return members.contains(p.getUniqueId());
        } catch (Exception e) { return false; }
    }

    /** Is the player standing on an island they belong to? */
    private boolean onOwnIsland(Player p) {
        try {
            Object im = islands();
            if (im == null) return false;
            Optional<?> island = (Optional<?>) im.getClass().getMethod("getIslandAt", Location.class).invoke(im, p.getLocation());
            if (island.isEmpty()) return false;
            Object is = island.get();
            @SuppressWarnings("unchecked") Set<UUID> members = (Set<UUID>) is.getClass().getMethod("getMemberSet").invoke(is);
            return members.contains(p.getUniqueId());
        } catch (Exception e) { return false; }
    }

    private java.lang.invoke.MethodHandle levelMethod;
    private Object levelAddon;

    /** Island level from the BentoBox Level addon. */
    private Double islandLevel(UUID id) {
        try {
            if (levelAddon == null || levelMethod == null) {
                Class<?> bb = Class.forName("world.bentobox.bentobox.BentoBox");
                Object inst = bb.getMethod("getInstance").invoke(null);
                Object am = inst.getClass().getMethod("getAddonsManager").invoke(inst);
                Optional<?> addon = (Optional<?>) am.getClass().getMethod("getAddonByName", String.class).invoke(am, "Level");
                if (addon.isEmpty()) return null;
                // a method handle, not getMethod: plain reflection loads every method signature on the addon and
                // Level has one returning the optional Visit addon, which isn't installed (NoClassDefFoundError)
                levelMethod = java.lang.invoke.MethodHandles.publicLookup().findVirtual(addon.get().getClass(), "getIslandLevel",
                        java.lang.invoke.MethodType.methodType(long.class, World.class, UUID.class));
                levelAddon = addon.get();
            }
            World w = islandWorld();
            if (w == null) return null;
            return (double) (long) levelMethod.invoke(levelAddon, w, id);
        } catch (Throwable e) { return null; }
    }

    /** OneBlock progress for one island: owner, lifetime blocks broken, current phase. */
    record OneBlockStat(UUID owner, long lifetime, int blockNumber, String phase, double level) {}

    private Object aoneblock;

    private Object aoneblockAddon() throws Exception {
        if (aoneblock == null) {
            Class<?> bb = Class.forName("world.bentobox.bentobox.BentoBox");
            Object inst = bb.getMethod("getInstance").invoke(null);
            Object am = inst.getClass().getMethod("getAddonsManager").invoke(inst);
            Optional<?> addon = (Optional<?>) am.getClass().getMethod("getAddonByName", String.class).invoke(am, "AOneBlock");
            aoneblock = addon.orElse(null);
        }
        return aoneblock;
    }

    /** Every OneBlock island, best first. */
    List<OneBlockStat> oneblockTop() {
        List<OneBlockStat> out = new ArrayList<>();
        try {
            Object addon = aoneblockAddon();
            World w = Bukkit.getWorld("oneblock_world");
            Object im = islands();
            if (addon == null || w == null || im == null) return out;
            Collection<?> all = (Collection<?>) im.getClass().getMethod("getIslands", World.class).invoke(im, w);
            Method getOb = addon.getClass().getMethod("getOneBlocksIsland", Class.forName("world.bentobox.bentobox.database.objects.Island"));
            for (Object is : all) {
                UUID owner = (UUID) is.getClass().getMethod("getOwner").invoke(is);
                if (owner == null) continue;
                Object ob = getOb.invoke(addon, is);
                long life = (Long) ob.getClass().getMethod("getLifetime").invoke(ob);
                int num = (Integer) ob.getClass().getMethod("getBlockNumber").invoke(ob);
                String phase = (String) ob.getClass().getMethod("getPhaseName").invoke(ob);
                out.add(new OneBlockStat(owner, Math.max(life, num), num, phase == null ? "?" : phase, 0));
            }
        } catch (Exception e) { plugin.getLogger().fine("oneblock top: " + e); }
        out.sort((a, b) -> Long.compare(b.lifetime(), a.lifetime()));
        return out;
    }

    OneBlockStat oneblockStat(UUID id) {
        for (OneBlockStat s : oneblockTop()) if (s.owner().equals(id)) return s;
        return null;
    }

    private void istop(Player p) {
        List<OneBlockStat> top = oneblockTop();
        p.sendMessage(Ui.mm("<#b06bff><bold>TOP ONEBLOCK ISLANDS</bold> <gray>(by blocks broken)"));
        if (top.isEmpty()) p.sendMessage(Ui.mm("<gray>No OneBlock islands yet - walk through the <#b06bff>OneBlock Gate<gray> or type <white>/ob<gray>!"));
        String[] medal = {"<#ffd700>", "<#c0c0c0>", "<#cd7f32>"};
        for (int i = 0; i < Math.min(10, top.size()); i++) {
            OneBlockStat s = top.get(i);
            String name = Optional.ofNullable(Bukkit.getOfflinePlayer(s.owner()).getName()).orElse("?");
            Double lv = islandLevelIn(s.owner(), "oneblock_world");
            p.sendMessage(Ui.mm((i < 3 ? medal[i] : "<yellow>") + "#" + (i + 1) + " <white>" + name + " <dark_gray>- <#b06bff>" + String.format("%,d", s.lifetime()) + " blocks <dark_gray>| <gray>Phase: <white>"
                    + s.phase() + (lv != null ? " <dark_gray>| <gray>Level <#4fd1c5>" + lv.longValue() : "")));
        }
        OneBlockStat mine = oneblockStat(p.getUniqueId());
        if (mine != null) {
            int rank = top.indexOf(mine) + 1;
            p.sendMessage(Ui.mm("<gray>You: <white>#" + rank + " <dark_gray>- <#b06bff>" + String.format("%,d", mine.lifetime()) + " blocks <gray>in <white>" + mine.phase()));
        }
    }

    private Double islandLevelIn(UUID id, String worldName) {
        try {
            if (levelAddon == null) islandLevel(id);
            World w = Bukkit.getWorld(worldName);
            if (levelAddon == null || w == null) return null;
            return (double) (long) levelMethod.invoke(levelAddon, w, id);
        } catch (Throwable e) { return null; }
    }

    // ------------------------------------------------------------------ commands

    private interface PlayerCmd { void run(Player p, String[] args); }

    private void reg(String name, List<String> aliases, PlayerCmd cmd) {
        Bukkit.getCommandMap().register("alloy", new Command(name, "BlackForgeMC " + name, "/" + name, aliases) {
            @Override public boolean execute(CommandSender s, String label, String[] args) {
                if (s instanceof Player p) cmd.run(p, args); else s.sendMessage("Players only.");
                return true;
            }
        });
    }

    private void registerCommands() {
        reg("spawn", List.of(), (p, a) -> p.teleportAsync(skyHub.spawnFor(p)));
        reg("skyhub", List.of("skyblockhub", "gamepicker"), (p, a) -> p.teleportAsync(skyHub.hubSpawn()));
        reg("istop", List.of("obtop", "oneblocktop"), (p, a) -> istop(p));
        reg("ah", List.of("auction", "auctionhouse"), (p, a) -> {
            switch (skyHub.groupOf(p.getLocation())) {
                case SkyHub.ONEBLOCK -> { if (ahOneBlock != null) ahOneBlock.command(p, a); }
                case SkyHub.SKYBLOCK -> { if (ah != null) ah.command(p, a); }
                default -> p.sendMessage(Ui.mm("<gray>Pick a game first - Skyblock and OneBlock each have their own auction house."));
            }
        });
        reg("scrap", List.of("scrapshop", "shards"), (p, a) -> scrap.command(p, a));
        reg("daily", List.of("dailyreward", "reward"), (p, a) -> daily.claim(p));
        reg("tags", List.of("tag", "namecolor", "colors"), (p, a) -> tags.open(p));
        reg("ranks", List.of("rank"), (p, a) -> ranks.info(p));
        reg("monthly", List.of("rankkeys"), (p, a) -> rankKits.open(p));   // keys now come in the 10-day /kit
        reg("repairhand", List.of("rh", "fixhand"), (p, a) -> ranks.repairHand(p));
        reg("fly", List.of(), (p, a) -> ranks.fly(p));
        reg("kit", List.of("rankkit", "rankkits"), (p, a) -> rankKits.open(p));
        reg("enchants", List.of("ce", "customenchants"), (p, a) -> enchants.info(p));
        reg("warp", List.of("warps"), (p, a) -> {
            String w = a.length > 0 ? a[0].toLowerCase() : "";
            switch (w) {
                case "crates" -> p.teleportAsync(new Location(world, -21.5, Y + 1, 0.5, 90, 0));
                case "market", "ah" -> p.teleportAsync(new Location(world, MARKET_X + 0.5, Y + 1, MARKET_Z - 1.5, 0, 0));
                case "spawn" -> p.teleportAsync(spawn());
                default -> p.sendMessage(Ui.mm("<gold>Warps: <yellow>spawn <gray>• <yellow>crates <gray>• <yellow>market <gray>• Your island: <white>/is"));
            }
        });
    }

    /** Console/admin: /alloy crate give, voucher give, ce give, tag give, scrap give, buildskyblock. */
    public boolean admin(CommandSender s, String[] a) {
        if (a.length >= 1 && a[0].equalsIgnoreCase("buildskyblock")) { build(s); return true; }
        if (a.length >= 2 && a[0].equalsIgnoreCase("keyall")) {
            int n = crates.giveAll(a[1], a.length > 2 ? Integer.parseInt(a[2]) : 1, a.length > 3 ? String.join(" ", java.util.Arrays.copyOfRange(a, 3, a.length)) : "");
            s.sendMessage(Component.text(n < 0 ? "Unknown crate" : "Key all: " + n + " players"));
            return true;
        }
        if (a.length < 4 || !a[1].equalsIgnoreCase("give")) return false;
        Player t = Bukkit.getPlayerExact(a[2]);
        if (t == null) { s.sendMessage(Component.text("Player must be online")); return true; }
        boolean ok = switch (a[0].toLowerCase()) {
            case "crate" -> crates.give(t, a[3], a.length > 4 ? Integer.parseInt(a[4]) : 1);
            case "voucher" -> ranks.giveVoucher(t, a[3]);
            case "ce" -> enchants.give(t, a[3], a.length > 4 ? Integer.parseInt(a[4]) : 1);
            case "tag" -> tags.give(t, a[3]);
            case "scrap" -> { scrap.add(t, Long.parseLong(a[3]), "gift"); yield true; }
            default -> false;
        };
        s.sendMessage(Component.text(ok ? "Done." : "Unknown item"));
        return true;
    }

    // ------------------------------------------------------------------ players

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        // everyone lands in the Skyblock Hub (SkyHub); the welcome key comes on the first trip into each game
        if (!p.hasPlayedBefore()) Bukkit.broadcast(Ui.mm("<#4fd1c5>✦ <gray>Welcome <white>" + p.getName() + " <gray>to BlackForgeMC Skyblock!"));
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!e.hasChangedBlock() || e.getTo().getWorld() != world) return;
        Location to = e.getTo();
        Player p = e.getPlayer();
        if (to.getY() < Y - 60 && inSpawn(to.getX(), Y, to.getZ())) { p.teleport(spawn()); return; }   // fell off the harbor
        boolean sky = inGate(to.getX(), to.getY(), to.getZ()), ob = inOneBlockGate(to.getX(), to.getY(), to.getZ());
        if (!sky && !ob) return;
        long now = System.currentTimeMillis();
        if (gateCooldown.getOrDefault(p.getUniqueId(), 0L) > now) return;
        gateCooldown.put(p.getUniqueId(), now + 3000);
        p.setVelocity(new org.bukkit.util.Vector(0, 0.3, 0.8));
        p.playSound(p.getLocation(), Sound.BLOCK_PORTAL_TRAVEL, 0.3f, 1.6f);
        // the Island Gate goes to your island (or creates one); the other gate leads back to the Skyblock Hub
        if (sky) p.performCommand("island");
        else p.teleport(skyHub.hubSpawn());
    }

    /** Market: bell = auction house, smithing table = Scrap Shop, ender chest = daily reward. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onMarket(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || e.getHand() != EquipmentSlot.HAND || e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || b.getWorld() != world) return;
        int[] at = {b.getX(), b.getY(), b.getZ()};
        Player p = e.getPlayer();
        if (Arrays.equals(at, BELL)) { e.setCancelled(true); world.playSound(b.getLocation(), Sound.BLOCK_BELL_USE, 1f, 1f); if (ah != null) ah.openBrowse(p, 0); }
        else if (Arrays.equals(at, SHOP)) { e.setCancelled(true); scrap.open(p, "Keys"); }
        else if (Arrays.equals(at, DAILY)) { e.setCancelled(true); daily.claim(p); }
    }

    private boolean protectedHere(Location l) { return l.getWorld() == world && inSpawn(l.getX(), l.getY(), l.getZ()); }
    private boolean staff(Player p) { return p.hasPermission("alloy.admin") && p.getGameMode() == GameMode.CREATIVE; }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent e) { if (protectedHere(e.getBlock().getLocation()) && !staff(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent e) { if (protectedHere(e.getBlock().getLocation()) && !staff(e.getPlayer())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || staff(e.getPlayer()) || !protectedHere(b.getLocation())) return;
        Material m = b.getType();
        if (m == Material.VAULT || m == Material.BELL || m == Material.SMITHING_TABLE || m == Material.ENDER_CHEST) return;
        if (e.getAction() == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK && (m.isInteractable() || e.getItem() != null)) e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        if (e.getItem() != null && e.getItem().getType().name().endsWith("_BUCKET")) e.setCancelled(true);
    }

    @EventHandler public void onDamage(EntityDamageEvent e) { if (e.getEntity() instanceof Player p && protectedHere(p.getLocation())) e.setCancelled(true); }
    @EventHandler public void onHunger(FoodLevelChangeEvent e) { if (e.getEntity() instanceof Player p && protectedHere(p.getLocation()) && e.getFoodLevel() < p.getFoodLevel()) e.setCancelled(true); }
    @EventHandler public void onSpawnMob(CreatureSpawnEvent e) {
        if (e.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL && protectedHere(e.getLocation())) e.setCancelled(true);
        if (e.getEntity() instanceof Monster && e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM && protectedHere(e.getLocation())) e.setCancelled(true);
    }
    @EventHandler public void onPortal(PlayerPortalEvent e) { if (protectedHere(e.getFrom())) e.setCancelled(true); }

    // ------------------------------------------------------------------ holograms + sidebar

    private void spawnHolograms() {
        holos.forEach(TextDisplay::remove);
        holos.clear();
        world.getEntitiesByClass(TextDisplay.class).stream().filter(t -> protectedHere(t.getLocation())).forEach(TextDisplay::remove);
        holos.add(Ui.hologram(new Location(world, SKY_GATE_X + 0.5, Y + 9.5, GATE_Z + 3.5), "<#4fd1c5><bold>SKYBLOCK</bold><newline><gray>Classic island • <white>/is", 1.5f));
        holos.add(Ui.hologram(new Location(world, OB_GATE_X + 0.5, Y + 9.5, GATE_Z + 3.5), "<#b06bff><bold>SKYBLOCK HUB</bold><newline><gray>Back to the game picker • <white>/skyhub", 1.5f));
        holos.add(Ui.hologram(new Location(world, 0.5, Y + 3.2, 13.5),
                Ui.TITLE + " <white><bold>SKYBLOCK & ONEBLOCK</bold><newline><gray>Classic island: <#4fd1c5>/is</#4fd1c5> • OneBlock: <#b06bff>/ob</#b06bff> (or walk into a gate)<newline><gray>Crates <white>west</white> • Hall of Fame <white>east</white> • Market <white>south</white>"
                        + "<newline><gold>/ah <gray>• <#c6a15b>/scrap <gray>• <gold>/daily <gray>• <light_purple>/tags <gray>• <white>/ranks", 1.0f));
        holos.add(Ui.hologram(new Location(world, MARKET_X + 0.5, Y + 4.2, MARKET_Z + 3.5),
                "<gold><bold>MARKET</bold><newline><gray>Bell: <white>Auction House<gray> • Forge: <#c6a15b>Scrap Shop<gray> • Chest: <gold>Daily Reward", 1.0f));
        holos.add(Ui.hologram(new Location(world, 26.5, Y + 8.5, 0.5), "<gradient:#ffd27a:#ff7a1a><bold>HALL OF FAME</bold></gradient>", 1.5f));
        crates.spawnHolograms();
        obCrates.spawnHolograms();
        Bukkit.getScheduler().runTaskLater(plugin, hall::refresh, 5L);
    }

    private void everySecond() {
        if ((holos.isEmpty() || holos.stream().anyMatch(t -> !t.isValid()) || crates.hologramsMissing() || obCrates.hologramsMissing()) && new File(plugin.getDataFolder(), "skyblock-built").exists()) spawnHolograms();
        if (hall.hologramsMissing() && Bukkit.getCurrentTick() % 5 == 0) hall.refresh();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Ranks.Rank r = ranks.of(p);
            Double lv = Bukkit.getCurrentTick() % 100 < 20 ? islandLevel(p.getUniqueId()) : null;
            if (lv != null) p.setMetadata("alloy_islandlevel", new org.bukkit.metadata.FixedMetadataValue(plugin, lv));
            double shown = p.hasMetadata("alloy_islandlevel") ? p.getMetadata("alloy_islandlevel").getFirst().asDouble() : 0;
            Ui.sidebar(p, List.of(
                    "<dark_gray>" + "-".repeat(18),
                    "<gray>Rank: " + (r == null ? "<white>Member" : r.color() + r.name()),
                    "<gray>Island level: <#4fd1c5>" + (long) shown,
                    "<gray>Balance: <green>" + (eco == null ? "?" : AuctionHouse.money(eco.getBalance(p))),
                    "<gray>Scrap: <#c6a15b>⚙ " + scrap.get(p),
                    " ",
                    "<gray>Online: <white>" + Bukkit.getOnlinePlayers().size(),
                    "<gold>" + plugin.getConfig().getString("address", "BlackForgeMC"),
                    "<dark_gray>" + "-".repeat(18) + " "));
        }
    }
}
