package net.blackforge.core.hub;

import net.blackforge.core.Events;

import net.blackforge.core.BlackForgeCore;
import net.blackforge.core.LobbyRules;
import net.blackforge.core.Ui;
import net.blackforge.core.build.Paster;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.io.File;
import java.time.Duration;
import java.util.*;

/** The BlackForgeMC hub: spawn, server selector, walk-in portals, double jump, sidebar and holograms. */
public final class HubModule implements Listener {
    private final BlackForgeCore plugin;
    private final World world;
    private final TestArena arena;
    private final Foundry foundry;
    private final net.blackforge.core.survival.GaiaPotion gaia;   // Potion of Gaia, usable in the Arena only
    private final net.blackforge.core.survival.GaleBellows bellows;   // Gale Bellows, works in the Arena (PvP is on there)
    private final net.blackforge.core.survival.Surge surge;   // Surge ability item, usable in the Arena only
    private final net.blackforge.core.survival.LastRite rite;   // Last Rite, Arena only; the test dummy gives a solo practice rite
    private final net.blackforge.core.survival.DarknessPotion darkness;   // Potion of Darkness, darkens players in the Arena only
    private final net.blackforge.core.survival.GodsEye eye;   // Gods-eye, Arena only: marks the closest player for 10 s
    private final net.blackforge.core.survival.Rimelock cage;   // Rimelock, Arena only: a 10 s cage only the caster's side can leave
    private final net.blackforge.core.survival.AshenChain chain;   // Ashen Chain, Arena only: drag yourself to a block or a player to you
    private final net.blackforge.core.survival.AshenBonfire bonfire;   // Ashen Bonfire, Arena only: a breakable healing ring
    private final net.blackforge.core.survival.Emp emp;   // EMP, Arena only: launch up, shut down every ability in 10 blocks
    private final net.blackforge.core.survival.Nightrend nightrend;   // Nightrend daggers, Arena only: a pair, one in each hand
    private final Map<UUID, Long> portalCooldown = new HashMap<>();
    private final Set<UUID> hidingPlayers = new HashSet<>();
    private final List<TextDisplay> holograms = new ArrayList<>();
    private final Map<String, TextDisplay> gateHolos = new HashMap<>();
    /** Get "The Forge Awakens" show every time they join the hub, not just the first time: GavVFX and Causee. */
    private static final Set<UUID> SHOW_EVERY_JOIN = Set.of(
            UUID.fromString("f2aef66f-fb87-4635-8c61-4b116baaa5b5"),
            UUID.fromString("8c67e820-3611-42b8-9f5f-d355d31f9ca9"));

    private static final String[][] MENU = {
        // server, slot, material, title, description
        {"skyblock", "10", "GRASS_BLOCK", "<#4fd1c5><bold>Skyblock", "<gray>Start on a tiny island in the sky.<newline><gray>Grow it, level it, beat the challenges."},
        {"survival", "12", "IRON_PICKAXE", "<#d9dde3><bold>Survival", "<gray>Classic survival with land claims.<newline><gray>Build safely with friends."},
        {"factions", "14", "NETHERITE_SWORD", "<#e5484d><bold>Factions", "<gray>Form a faction, claim land, raid rivals.<newline><gray>PvP is ON. New map every 3 months."},
        {"arcade", "16", "TNT", "<#ffc53d><bold>Arcade", "<gray>18 minigames: BedWars, SkyWars,<newline><gray>Duels, Murder Mystery and more."},
        {"raft", "20", "OAK_BOAT", "<#4fc3f7><bold>Raft", "<gray>Stranded on a few planks at sea.<newline><gray>Hook debris, purify water, fight sharks,<newline><gray>follow the radio towers to Utopia."},
    };

    public HubModule(BlackForgeCore plugin) {
        this.plugin = plugin;
        this.world = Bukkit.getWorlds().getFirst();
        LobbyRules.setupWorld(plugin, world, 18000);   // midnight under the Inferno biome's red sky
        world.setSpawnLocation(spawn());
        // the Arena (item testing ground) is free to shuffle, drop and pick up items; everything else stays locked
        Bukkit.getPluginManager().registerEvents(new LobbyRules(p -> false).allowItems(p -> p.getWorld().getName().equals(TestArena.WORLD) || p.getWorld().getName().equals(net.blackforge.core.survival.RiteChambers.WORLD))
                .allowBuild((p, l) -> l.getWorld().getName().equals(TestArena.WORLD) && FoundryZone.contains(l.getX(), l.getZ())), plugin);
        new net.blackforge.core.SpawnSafety(plugin, l -> l.getWorld() == world, p -> spawn()).withStuckCommand();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        registerJoinCommand();   // the clickable links in the first-join guide run /bfjoin
        // keep the whole hub loaded: holograms survive, lava keeps flowing, first joins are instant
        for (int cx = -6; cx <= 5; cx++) for (int cz = -9; cz <= 5; cz++) world.addPluginChunkTicket(cx, cz, plugin);
        if (!new File(plugin.getDataFolder(), "hub-built").exists() || !new File(plugin.getDataFolder(), "hub-forge-v1").exists()) build(Bukkit.getConsoleSender());   // hub-forge-v1: the Black Forge redesign
        else {
            if (!new File(plugin.getDataFolder(), "hub-biome-v2").exists()) applyBiome();
            if (!new File(plugin.getDataFolder(), "hub-castle-v1").exists()) addCastle();
            if (!new File(plugin.getDataFolder(), "hub-raft-gate-v1").exists()) addRaftGate();
            if (new File(plugin.getDataFolder(), "hub-dragons-v1").exists() && !new File(plugin.getDataFolder(), "hub-dragons-flying").exists()) removeStatues();
        }
        Bukkit.getScheduler().runTaskLater(plugin, this::spawnHolograms, 40L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 60L, 40L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::ambient, 20L, 10L);
        DragonFlight.fireScale = Math.max(0.1, plugin.getConfig().getDouble("hub.dragons.fire-interval-scale", 1.0));
        if (plugin.getConfig().getBoolean("hub.dragons.enabled", true)) dragons = new DragonAnimator(plugin, world);
        tutorial = new Tutorial(plugin);
        parkour = new HubParkour(plugin, this, world);
        Bukkit.getScheduler().runTaskTimer(plugin, this::leaderboards, 60L, 1200L);
        arena = new TestArena(plugin);
        foundry = new Foundry(plugin, arena);   // agents, hopper tiers and the Forge, free and unsaved, inside the Foundry only
        gaia = new net.blackforge.core.survival.GaiaPotion(plugin, (pl, l) -> l.getWorld() == arena.world(),   // never on the hub itself
                l -> l.getWorld() == arena.world());   // Arena: no refund (refill covers it), new cloud replaces your old one
        darkness = new net.blackforge.core.survival.DarknessPotion(plugin, l -> l.getWorld() == arena.world());
        surge = new net.blackforge.core.survival.Surge(plugin, l -> l.getWorld() == arena.world(), true);   // staff: no cooldown in the Arena
        bellows = new net.blackforge.core.survival.GaleBellows(plugin, l -> l.getWorld() == arena.world(), arena::isTestDummy);   // Arena: never breaks (7 s cooldown still applies); can hit the Test Dummy
        // Arena test items: each goes on the showcase wall, into the Arena hotbar (in this order) and never runs out there.
        // EVERY NEW ITEM GETS ONE LINE HERE.
        arena.addTestItem(bellows::item, bellows::isBellows, bellows::isWorn);
        arena.addTestItem(gaia::item, gaia::isGaia);
        arena.addTestItem(darkness::item, darkness::isDarkness);
        arena.addTestItem(surge::item, surge::isSurge);
        rite = new net.blackforge.core.survival.LastRite(plugin, l -> l.getWorld() == arena.world(), true, arena::isTestDummy,
                net.blackforge.core.survival.RiteChambers.Timeout.BOTH_DIE);
        arena.addTestItem(rite::item, rite::isRite, rite::isWorn);
        cage = new net.blackforge.core.survival.Rimelock(plugin, l -> l.getWorld() == arena.world());   // Arena only; never used up
        arena.addTestItem(cage::item, cage::isCage);
        eye = new net.blackforge.core.survival.GodsEye(plugin, l -> l.getWorld() == arena.world());   // Arena only; never used up
        arena.addTestItem(eye::item, eye::isEye);
        chain = new net.blackforge.core.survival.AshenChain(plugin, l -> l.getWorld() == arena.world(), arena::isPracticeTarget, arena::isTestDummy);   // Arena only; never used up; the dummy is pinned, the practice zombie can be pulled
        surge.onActivate(p -> chain.breakFree(p, "Surge"));   // Surge tears you loose from a burning chain
        chain.crystalsFrom(cage);   // a Rimelock's rim crystals are a way out: hook one with the chain
        arena.addTestItem(chain::item, chain::isChain);
        bonfire = new net.blackforge.core.survival.AshenBonfire(plugin, l -> l.getWorld() == arena.world());   // Arena only; never used up
        arena.addTestItem(bonfire::item, bonfire::isBonfire);
        nightrend = new net.blackforge.core.survival.Nightrend(plugin, l -> l.getWorld() == arena.world());   // Arena only; registered on the wall after the EMP
        emp = new net.blackforge.core.survival.Emp(plugin, l -> l.getWorld() == arena.world(), true);   // Arena only; staff: no cooldown in the Arena
        emp.hackable(surge, cage, eye, chain, bonfire, gaia, rite, darkness, nightrend);   // everything the EMP shuts down
        arena.addTestItem(emp::item, emp::isEmp);
        arena.addTestItem(nightrend::item, nightrend::isMainBlade);                       // the pair: one in the kit...
        arena.addTestItem(nightrend::offItem, nightrend::isOffBlade, it -> false, true);  // ...and one straight into the off hand
        // staff test commands, Arena only: one item each
        // On the wall everywhere in the Arena; they only work inside the Foundry.
        arena.addTestItem(net.blackforge.core.survival.BaseStation::item, net.blackforge.core.survival.BaseStation::isStationItem);
        arena.addTestItem(net.blackforge.core.survival.HopperUpgrades::upgradeItem, net.blackforge.core.survival.HopperUpgrades::isUpgrade);
        testCommand("jbello", List.of("bellows", "galebellows"), () -> bellows.item());
        testCommand("gaia", List.of("gaiapotion"), () -> gaia.item());
        testCommand("darkness", List.of("darknesspotion"), () -> darkness.item());
        testCommand("surge", List.of(), () -> surge.item());
        testCommand("lastrite", List.of("rite"), () -> rite.item());
        testCommand("rimelock", List.of("cage"), () -> cage.item());
        testCommand("godseye", List.of("eye"), () -> eye.item());
        testCommand("ashenchain", List.of("chain", "tether"), () -> chain.item());
        testCommand("bonfire", List.of("ashenbonfire", "totem"), () -> bonfire.item());
        testCommand("emp", List.of(), () -> emp.item());
        testCommand("nightrend", List.of("daggers"), () -> nightrend.item());   // one per use: run it twice for a pair
        testCommand("basestation", List.of("station"), net.blackforge.core.survival.BaseStation::item);
        testCommand("hopperupgrade", List.of(), net.blackforge.core.survival.HopperUpgrades::upgradeItem);
    }

    public TestArena arena() { return arena; }

    /** A staff-only command that hands out one test item, inside the Arena. */
    private void testCommand(String name, List<String> aliases, java.util.function.Supplier<ItemStack> item) {
        Bukkit.getCommandMap().register("blackforge", new org.bukkit.command.Command(name, "Arena test item (staff)", "/" + name, aliases) {
            @Override public boolean execute(org.bukkit.command.CommandSender s, String label, String[] a) {
                if (!(s instanceof Player p)) return true;
                if (!p.hasPermission("blackforge.admin")) { p.sendMessage(Ui.mm("<gray>Test items are staff-only. Grab them from the Arena wall or <white>/arena kit<gray>.")); return true; }
                if (!arena.inArena(p)) { p.sendMessage(Ui.mm("<gray>Go to the Arena first: <white>/arena")); return true; }
                ItemStack it = item.get();
                p.getInventory().addItem(it).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
                p.sendActionBar(Ui.mm("<green>+ <white>").append(it.effectiveName()));
                return true;
            }
        });
    }

    private void registerJoinCommand() {
        Bukkit.getCommandMap().register("blackforge", new org.bukkit.command.Command("bfjoin", "Join a game mode", "/bfjoin <server>", List.of()) {
            @Override public boolean execute(org.bukkit.command.CommandSender s, String label, String[] a) {
                if (s instanceof Player p && a.length == 1 && a[0].equals("arena")) arena.enter(p);
                else if (s instanceof Player p && a.length == 1 && a[0].equals("forgewars")) joinForgeWars(p);
                else if (s instanceof Player p && a.length == 1 && List.of("skyblock", "survival", "factions", "arcade", "raft").contains(a[0])) connect(p, a[0]);
                return true;
            }
        });
        // /arena: go to the Arena; /arena kit: refill the test kit while in it
        Bukkit.getCommandMap().register("blackforge", new org.bukkit.command.Command("arena", "Go to the Arena (item testing ground)", "/arena [kit]", List.of()) {
            @Override public boolean execute(org.bukkit.command.CommandSender s, String label, String[] a) {
                if (!(s instanceof Player p)) return true;
                if (a.length > 0 && a[0].equalsIgnoreCase("kit")) {
                    if (arena.inArena(p)) { arena.kit(p); p.sendActionBar(Ui.mm("<green>Kit refilled.")); }
                    else p.sendMessage(Ui.mm("<gray>Go to the Arena first: <white>/arena"));
                } else if (arena.inArena(p)) p.sendMessage(Ui.mm("<gray>You're already in the Arena. <white>/hub <gray>to leave."));
                else arena.enter(p);
                return true;
            }
        });
    }

    private DragonAnimator dragons;

    public void shutdown() { gaia.shutdown(); surge.shutdown(); rite.shutdown(); cage.shutdown(); eye.shutdown(); chain.shutdown(); bonfire.shutdown(); emp.shutdown(); nightrend.shutdown(); holograms.forEach(TextDisplay::remove); boards.forEach(TextDisplay::remove); if (parkour != null) parkour.shutdown(); if (dragons != null) dragons.remove(); foundry.shutdown(); arena.shutdown(); }
    // ------------------------------------------------------------------ leaderboards

    private final List<TextDisplay> boards = new ArrayList<>();

    /** Two network-wide boards either side of spawn, refreshed every minute from shared/stats. */
    private void leaderboards() {
        var stats = plugin.sharedStats();
        if (stats == null) return;
        String kills = board("<red><bold>⚔ TOP KILLERS</bold>", stats.top("kills", 10), "kills");
        String wins = board("<gold><bold>★ ARCADE CHAMPIONS</bold>", stats.top("arcade_wins", 10), "wins");
        if (boards.size() != 2 || boards.stream().anyMatch(b -> !b.isValid())) {
            boards.forEach(TextDisplay::remove);
            boards.clear();
            boards.add(Ui.hologram(new Location(world, -9.5, HubLayout.Y + 5.5, -7.5), kills, 1.15f));
            boards.add(Ui.hologram(new Location(world, 10.5, HubLayout.Y + 5.5, -7.5), wins, 1.15f));
        } else {
            Ui.setText(boards.get(0), kills);
            Ui.setText(boards.get(1), wins);
        }
    }

    private static String board(String title, List<net.blackforge.core.SharedStats.Row> rows, String unit) {
        StringBuilder sb = new StringBuilder(title).append("\n<dark_gray>").append("-".repeat(16));
        if (rows.isEmpty()) sb.append("\n<gray>Nobody yet - be the first!");
        for (int i = 0; i < rows.size(); i++) {
            String medal = i == 0 ? "<gold>" : i == 1 ? "<#d9dde3>" : i == 2 ? "<#c6773a>" : "<gray>";
            sb.append("\n").append(medal).append(i + 1).append(". <white>").append(rows.get(i).name()).append(" <gray>").append(rows.get(i).value()).append(" ").append(unit);
        }
        return sb.toString();
    }


    public Location spawn() {
        var cfg = plugin.getConfig();
        if (cfg.contains("hub.spawn.x"))
            return new Location(world, cfg.getDouble("hub.spawn.x"), cfg.getDouble("hub.spawn.y"), cfg.getDouble("hub.spawn.z"),
                    (float) cfg.getDouble("hub.spawn.yaw", HubLayout.SPAWN_YAW), (float) cfg.getDouble("hub.spawn.pitch", HubLayout.SPAWN_PITCH));
        return new Location(world, HubLayout.SPAWN_X, HubLayout.SPAWN_Y, HubLayout.SPAWN_Z, HubLayout.SPAWN_YAW, HubLayout.SPAWN_PITCH);
    }

    public void build(CommandSender who) {
        who.sendMessage(Component.text("Building the BlackForgeMC hub..."));
        long t = System.currentTimeMillis();
        var canvas = HubBuilder.build();
        world.getChunkAt(0, 0).load();
        // wipe the old hub first so nothing from a previous design is left floating
        int e = CastleBuilder.EXTENT;
        int[] x = {-e};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            for (int n = 0; n < 2 && x[0] <= e; n++, x[0]++)
                for (int z = -e; z <= e; z++)
                    for (int y = HubLayout.Y - 80; y <= HubLayout.Y + 120; y++) {
                        var b = world.getBlockAt(x[0], y, z);
                        if (!b.getType().isAir()) b.setType(Material.AIR, false);
                    }
            if (x[0] <= e) return;
            task.cancel();
            pasteHub(who, canvas, t);
        }, 1L, 1L);
    }

    private void pasteHub(CommandSender who, net.blackforge.core.build.Canvas canvas, long t) {
        Paster.paste(plugin, world, canvas, 6000, () -> {
            try { new File(plugin.getDataFolder(), "hub-built").createNewFile(); } catch (Exception ignored) { }
            try { new File(plugin.getDataFolder(), "hub-castle-v1").createNewFile(); new File(plugin.getDataFolder(), "hub-keep-v1").createNewFile(); new File(plugin.getDataFolder(), "hub-forge-v1").createNewFile(); } catch (Exception ignored) { }
            applyBiome();
            who.sendMessage(Component.text("Hub built: " + canvas.size() + " blocks in " + (System.currentTimeMillis() - t) / 1000 + "s"));
            for (Player p : Bukkit.getOnlinePlayers()) toSpawn(p);
        });
    }

    /**
     * Paints the hub with the blackforge:inferno biome (datapack in world/datapacks/blackforge): a dark red sky,
     * red fog, drifting ash and embers, nether ambience. Skipped with a warning if the datapack isn't loaded.
     */
    private void applyBiome() {
        org.bukkit.block.Biome inferno = io.papermc.paper.registry.RegistryAccess.registryAccess()
                .getRegistry(io.papermc.paper.registry.RegistryKey.BIOME).get(NamespacedKey.fromString("blackforge:inferno"));
        if (inferno == null) { plugin.getLogger().warning("blackforge:inferno biome missing - is the blackforge datapack in hub/world/datapacks?"); return; }
        int e = (CastleBuilder.EXTENT + 32) / 16 * 16;
        for (int x = -e; x < e; x += 4)
            for (int z = -e; z < e; z += 4)
                for (int y = HubLayout.Y - 96; y < HubLayout.Y + 96; y += 4) world.setBiome(x, y, z, inferno);
        for (int cx = -e / 16; cx < e / 16; cx++) for (int cz = -e / 16; cz < e / 16; cz++) world.refreshChunk(cx, cz);
        try { new File(plugin.getDataFolder(), "hub-biome-v2").createNewFile(); } catch (Exception ignored) { }
        plugin.getLogger().info("Hub painted with the blackforge:inferno biome");
    }

    // ------------------------------------------------------------------ players

    public void toSpawn(Player p) {
        p.teleport(spawn());
        p.setFallDistance(0);
    }

    private void kit(Player p) {
        p.getInventory().clear();
        p.getInventory().setItem(0, Ui.item(Material.KNOWLEDGE_BOOK, "<#4fd1c5><bold>Guide</bold> <gray>(right-click)", "<gray>Servers, custom items, money, rules"));
        p.getInventory().setItem(1, Ui.item(Material.CLOCK, "<#ffb347><bold>Milestones</bold> <gray>(right-click)", "<gray>Rewards for playing on any server"));
        p.getInventory().setItem(2, Ui.item(Material.BELL, "<#ffb347><bold>Events</bold> <gray>(right-click)", "<gray>What's on and what's next"));
        p.getInventory().setItem(7, Ui.item(Material.PLAYER_HEAD, "<#7bd88f><bold>Friends & Party</bold> <gray>(right-click)", "<gray>/friend add <name>, /party invite <name>"));
        p.getInventory().setItem(4, Ui.item(Material.COMPASS, "<gold><bold>Server Selector</bold> <gray>(right-click)", "<gray>Pick a game mode to play"));
        p.getInventory().setItem(8, visibilityItem(hidingPlayers.contains(p.getUniqueId())));
        p.getInventory().setHeldItemSlot(4);
    }

    private ItemStack visibilityItem(boolean hiding) {
        return hiding ? Ui.item(Material.GRAY_DYE, "<gray>Players: <red>Hidden <gray>(right-click)")
                : Ui.item(Material.LIME_DYE, "<gray>Players: <green>Visible <gray>(right-click)");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        e.joinMessage(null);
        if (!p.hasPermission("blackforge.admin") || p.getGameMode() != GameMode.CREATIVE) p.setGameMode(GameMode.ADVENTURE);
        p.setHealth(p.getAttribute(Attribute.MAX_HEALTH) != null ? p.getAttribute(Attribute.MAX_HEALTH).getValue() : 20);
        p.setFoodLevel(20);
        p.setAllowFlight(true);
        toSpawn(p);
        kit(p);
        if (!p.hasPlayedBefore()) welcomeNewPlayer(p);
        else {
            p.showTitle(Title.title(Ui.mm(Ui.TITLE), Ui.mm("<gray>Welcome back, <white>" + p.getName()),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(800))));
            p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.4f, 1.4f);
            if (plugin.getConfig().getBoolean("hub.join.enabled", true)) {
                if (SHOW_EVERY_JOIN.contains(p.getUniqueId()) || fullShowDue(p)) {
                    // after the loading screen, same delay as the first-join show
                    Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) { WelcomeShow.play(plugin, p); markShow(p); } }, 30L);
                } else if (plugin.getConfig().getBoolean("hub.join.short-effects", true)) {
                    Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) shortArrival(p); }, 20L);
                }
            }
        }
        for (UUID id : hidingPlayers) { Player h = Bukkit.getPlayer(id); if (h != null) h.hidePlayer(plugin, p); }
        Bukkit.getScheduler().runTaskLater(plugin, () -> refreshPlayer(p), 5L);
    }

    /** Everyone joins through the hub first, so this is the network's first-join welcome. */
    private void welcomeNewPlayer(Player p) {
        int number = Bukkit.getOfflinePlayers().length;
        p.showTitle(Title.title(Ui.mm("<gradient:#ffd27a:#ff7a1a><bold>WELCOME!</bold></gradient>"), Ui.mm("<gray>to " + Ui.TITLE + "<gray>, <white>" + p.getName()),
                Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(4), Duration.ofMillis(1000))));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.1f);
        // tell the whole network (a moment later so the proxy has finished connecting them)
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            plugin.net().broadcastAll(p, Ui.mm("<gold>✦ <gray>Welcome <white><bold>" + p.getName() + "</bold> <gray>to " + Ui.TITLE + "<gray>! They're player <gold>#" + number + "<gray>. Say hi!"));
            // "The Forge Awakens" firework show, seen only by the new player (started after the
            // loading screen so the opening rune isn't missed)
            if (plugin.getConfig().getBoolean("hub.join.enabled", true)) { WelcomeShow.play(plugin, p); markShow(p); }
        }, 30L);
        // a short, clickable guide
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            p.sendMessage(Ui.mm(" "));
            p.sendMessage(Ui.mm("  " + Ui.TITLE + " <gray>- glad you're here, <white>" + p.getName() + "<gray>!"));
            p.sendMessage(Ui.mm("  <gray>Pick a game with the <gold>compass</gold> or walk into a portal:"));
            p.sendMessage(Ui.mm("  <#4fd1c5>» <u><click:run_command:'/bfjoin skyblock'>Skyblock</click></u> <gray>- grow your own sky island"));
            p.sendMessage(Ui.mm("  <#d9dde3>» <u><click:run_command:'/bfjoin survival'>Survival</click></u> <gray>- claims, crates, KOTH, the Scrapyard"));
            p.sendMessage(Ui.mm("  <#e5484d>» <u><click:run_command:'/bfjoin factions'>Factions</click></u> <gray>- build a faction, raid rivals"));
            p.sendMessage(Ui.mm("  <#ffc53d>» <u><click:run_command:'/bfjoin arcade'>Arcade</click></u> <gray>- BedWars, SkyWars, Duels + 15 more minigames"));
            p.sendMessage(Ui.mm("  <#4fc3f7>» <u><click:run_command:'/bfjoin raft'>Raft</click></u> <gray>- survive on a raft at sea, with friends"));
            p.sendMessage(Ui.mm("  <#ff6a00>» <u><click:run_command:'/bfjoin forgewars'>Forge Wars</click></u> <gray>- BedWars with BlackForgeMC abilities"));
            p.sendMessage(Ui.mm("  <gray>Lost? The <#4fd1c5>Guide</#4fd1c5> book (or <white>/guide</white>) explains everything."));
            p.sendMessage(Ui.mm("  <gray>Brought by a friend? <white>/referral <name></white> pays you both."));
            p.sendMessage(Ui.mm("  <gray>Be respectful and have fun - type <white>/hub</white> any time to come back here."));
            p.sendMessage(Ui.mm(" "));
        }, 60L);
    }

    /** Back from the Arena: the hub kit and double jump again. */
    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (p.getWorld() != world) return;
        if (!p.hasPermission("blackforge.admin") || p.getGameMode() != GameMode.CREATIVE) p.setGameMode(GameMode.ADVENTURE);
        p.setAllowFlight(true);
        p.setFireTicks(0);
        kit(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        e.quitMessage(null);
        hidingPlayers.remove(e.getPlayer().getUniqueId());
        portalCooldown.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        Player p = e.getPlayer();
        if (it == null) return;
        if (it.getType() == Material.COMPASS) { e.setCancelled(true); openMenu(p); }
        else if (it.getType() == Material.KNOWLEDGE_BOOK) { e.setCancelled(true); p.performCommand("guide"); }
        else if (it.getType() == Material.CLOCK) { e.setCancelled(true); p.performCommand("milestones"); }
        else if (it.getType() == Material.BELL) { e.setCancelled(true); p.performCommand("events"); }
        else if (it.getType() == Material.PLAYER_HEAD) { e.setCancelled(true); p.performCommand("friend list"); p.performCommand("party list"); }
        else if (it.getType() == Material.LIME_DYE || it.getType() == Material.GRAY_DYE) {
            e.setCancelled(true);
            boolean hide = !hidingPlayers.remove(p.getUniqueId());
            if (hide) hidingPlayers.add(p.getUniqueId());
            for (Player o : Bukkit.getOnlinePlayers()) if (o != p) { if (hide) p.hidePlayer(plugin, o); else p.showPlayer(plugin, o); }
            p.getInventory().setItem(8, visibilityItem(hide));
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, hide ? 0.8f : 1.2f);
        }
    }

    // ------------------------------------------------------------------ selector

    private static final class MenuHolder implements InventoryHolder {
        Inventory inv;
        public Inventory getInventory() { return inv; }
    }

    private static final int ARENA_SLOT = 22;
    private static final int FORGE_WARS_SLOT = 4;   // Forge Wars: its own button, but it runs on the Arcade server

    private void openMenu(Player p) {
        MenuHolder h = new MenuHolder();
        Inventory inv = Bukkit.createInventory(h, 27, Ui.mm("<dark_gray>Select a Server"));
        h.inv = inv;
        ItemStack pane = Ui.item(Material.ORANGE_STAINED_GLASS_PANE, " ");
        ItemStack pane2 = Ui.item(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) inv.setItem(i, (i < 9 || i >= 18) ? pane : pane2);
        for (String[] m : MENU) {
            int n = plugin.net().count(m[0]);
            List<String> lore = new ArrayList<>(Arrays.asList(m[4].split("<newline>")));
            lore.add("");
            lore.add("<gray>Playing: <white>" + n);
            String ev = net.blackforge.core.Events.statusFor(m[0]);
            if (ev != null) lore.add(ev);
            var news = net.blackforge.core.News.latestFor(m[0]);
            if (news != null && !news.server().equals("all")) lore.add("<gold>New: <gray>" + (news.text().length() > 42 ? news.text().substring(0, 40) + "..." : news.text()));
            lore.add("<yellow>» Click to join");
            inv.setItem(Integer.parseInt(m[1]), Ui.item(Material.valueOf(m[2]), m[3], lore.toArray(String[]::new)));
        }
        inv.setItem(FORGE_WARS_SLOT, Ui.item(Material.RESPAWN_ANCHOR, "<#ff6a00><bold>Forge Wars", "<gray>BlackForgeMC's own BedWars:", "<gray>defend your bed, buy abilities", "<gray>like Surge and Rimelock at the Arsenal.", "",
                "<gray>On the Arcade: <white>" + plugin.net().count("arcade"), "<yellow>» Click to play"));
        inv.setItem(ARENA_SLOT, Ui.item(Material.TARGET, "<#7bd88f><bold>Arena", "<gray>Testing ground: try every item", "<gray>on the dummy, grab the newest ones", "<gray>from the showcase wall.", "",
                "<gray>In the Arena: <white>" + arena.world().getPlayers().size(), "<yellow>» Click to enter"));
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.5f, 1.3f);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof MenuHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getRawSlot() == ARENA_SLOT) { p.closeInventory(); arena.enter(p); return; }
        if (e.getRawSlot() == FORGE_WARS_SLOT) { p.closeInventory(); joinForgeWars(p); return; }
        for (String[] m : MENU)
            if (e.getRawSlot() == Integer.parseInt(m[1])) {
                p.closeInventory();
                connect(p, m[0]);
            }
    }

    private Tutorial tutorial;
    private HubParkour parkour;

    /** Forge Wars lives on the Arcade server: leave a note so the Arcade opens its mode picker when they land. */
    private void joinForgeWars(Player p) {
        try { net.blackforge.core.ArcadeIntents.request(plugin.shared(), p.getUniqueId(), "forgewars"); }
        catch (IllegalStateException ex) { plugin.getLogger().warning("Couldn't save the Forge Wars pick: " + ex.getMessage()); }   // still send them; they can pick it there
        connect(p, "arcade");
    }

    private void connect(Player p, String server) {
        if (tutorial != null) tutorial.pickedGame(p);
        p.sendActionBar(Ui.mm("<gold>Sending you to <white>" + server + "<gold>..."));
        p.playSound(p.getLocation(), Sound.BLOCK_PORTAL_TRAVEL, 0.25f, 1.6f);
        plugin.net().send(p, plugin.getConfig().getString("hub.portals." + server, server));
        // still standing here a few seconds later: the proxy couldn't reach it
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && p.getWorld() == world)
                p.sendMessage(Ui.mm("<red>" + server.substring(0, 1).toUpperCase() + server.substring(1) + " isn't reachable right now. <gray>Give it a minute, or pick another game."));
        }, 80L);
    }

    // ------------------------------------------------------------------ arrival effects

    private File showsFile() { return new File(plugin.getDataFolder(), "hub-last-show.yml"); }

    /** A returning player sees the full "Forge Awakens" show only after hub.join.full-show-cooldown-minutes away. */
    private boolean fullShowDue(Player p) {
        long mins = plugin.getConfig().getLong("hub.join.full-show-cooldown-minutes", 360);
        long last = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(showsFile()).getLong(p.getUniqueId().toString(), 0);
        return System.currentTimeMillis() - last >= mins * 60_000L;
    }

    private void markShow(Player p) {
        try {
            var y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(showsFile());
            y.set(p.getUniqueId().toString(), System.currentTimeMillis());
            plugin.getDataFolder().mkdirs();
            y.save(showsFile());
        } catch (Exception ignored) { }
    }

    /** The short arrival for someone who was here recently: a ring of fire opening under their feet, embers rising, a soft chime. Only they see it. */
    void shortArrival(Player p) {
        Location at = p.getLocation().clone();
        p.playSound(at, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.7f, 1.2f);
        p.playSound(at, Sound.BLOCK_ANVIL_PLACE, 0.35f, 0.7f);
        if (rnd.nextDouble() < plugin.getConfig().getDouble("hub.join.roar-chance", 0.2)) p.playSound(at, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.35f, 0.8f);
        int[] t = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (!p.isOnline() || ++t[0] > 14) { task.cancel(); return; }
            double r = t[0] * 0.35;
            for (int i = 0; i < 24; i++) {
                double a = i * Math.PI / 12;
                p.spawnParticle(i % 3 == 0 ? Particle.PORTAL : Particle.FLAME, at.getX() + Math.cos(a) * r, at.getY() + 0.1, at.getZ() + Math.sin(a) * r, 1, 0, 0.02, 0, 0.01);
            }
            p.spawnParticle(Particle.SMALL_FLAME, at.getX(), at.getY() + 0.3 + t[0] * 0.2, at.getZ(), 3, 0.4, 0.2, 0.4, 0.02);
            if (t[0] % 4 == 0) p.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, at.getX(), at.getY() + 0.2, at.getZ(), 0, 0, 0.1, 0, 0.05);
        }, 0L, 1L);
    }

    // ------------------------------------------------------------------ admin: /blackforge setspawn|reload|dragons|effects|portals|info

    private final Random rnd = new Random();
    private static final List<String> ADMIN = List.of("setspawn", "reload", "dragons", "effects", "portals", "info");

    public static boolean isAdminSub(String s) { return ADMIN.contains(s.toLowerCase()); }

    public List<String> adminTabs(String[] a) {
        String s = a[0].toLowerCase();
        if (a.length == 2 && s.equals("dragons")) return List.of("fire", "on", "off");
        if (a.length == 2 && s.equals("effects")) return List.of("on", "off", "cooldown", "test");
        if (a.length == 3 && s.equals("effects") && a[1].equalsIgnoreCase("test")) return List.of("short", "full");
        if (a.length == 2 && s.equals("portals")) return List.of("set");
        if (a.length == 3 && s.equals("portals") && a[1].equalsIgnoreCase("set")) return HubLayout.GATES.stream().map(HubLayout.Gate::server).toList();
        if (a.length == 4 && s.equals("portals") && a[1].equalsIgnoreCase("set")) return List.of(net.blackforge.core.Net.SERVERS);
        return List.of();
    }

    public boolean admin(CommandSender s, String[] a) {
        if (a.length == 0 || !isAdminSub(a[0])) return false;
        if (!s.hasPermission("blackforge.admin")) { s.sendMessage(Ui.mm("<red>You need blackforge.admin for that.")); return true; }
        var cfg = plugin.getConfig();
        switch (a[0].toLowerCase()) {
            case "setspawn" -> {
                if (!(s instanceof Player p) || p.getWorld() != world) { s.sendMessage(Ui.mm("<red>Stand in the hub to set its spawn.")); return true; }
                Location l = p.getLocation();
                cfg.set("hub.spawn.x", l.getX()); cfg.set("hub.spawn.y", l.getY()); cfg.set("hub.spawn.z", l.getZ());
                cfg.set("hub.spawn.yaw", (double) l.getYaw()); cfg.set("hub.spawn.pitch", (double) l.getPitch());
                plugin.saveConfig();
                world.setSpawnLocation(l);
                s.sendMessage(Ui.mm("<green>Hub spawn set to <white>" + String.format("%.1f %.1f %.1f", l.getX(), l.getY(), l.getZ())));
            }
            case "reload" -> {
                plugin.reloadConfig();
                DragonFlight.fireScale = Math.max(0.1, plugin.getConfig().getDouble("hub.dragons.fire-interval-scale", 1.0));
                spawnHolograms();
                s.sendMessage(Ui.mm("<green>Hub config reloaded. <gray>(Dragons on/off needs a restart.)"));
            }
            case "dragons" -> {
                if (a.length >= 3 && a[1].equalsIgnoreCase("fire")) {
                    try {
                        double v = Math.max(0.1, Math.min(10, Double.parseDouble(a[2])));
                        cfg.set("hub.dragons.fire-interval-scale", v); plugin.saveConfig(); DragonFlight.fireScale = v;
                        s.sendMessage(Ui.mm("<green>Dragons wait <white>x" + v + "<green> as long between fire breaths."));
                    } catch (NumberFormatException e) { s.sendMessage(Ui.mm("<red>/blackforge dragons fire <number>  (1 = normal, 2 = half as often)")); }
                } else if (a.length >= 2 && (a[1].equalsIgnoreCase("on") || a[1].equalsIgnoreCase("off"))) {
                    cfg.set("hub.dragons.enabled", a[1].equalsIgnoreCase("on")); plugin.saveConfig();
                    s.sendMessage(Ui.mm("<green>Dragons " + a[1].toLowerCase() + " from the next hub restart."));
                } else {
                    s.sendMessage(Ui.mm("<gold>Dragons: <white>" + (dragons != null ? "4 flying (black, crimson, violet, serpent)" : "off")
                            + "<gold>, fire interval x<white>" + DragonFlight.fireScale));
                    s.sendMessage(Ui.mm("<gray>/blackforge dragons fire <scale> | on | off"));
                }
            }
            case "effects" -> {
                String sub = a.length > 1 ? a[1].toLowerCase() : "";
                switch (sub) {
                    case "on", "off" -> { cfg.set("hub.join.enabled", sub.equals("on")); plugin.saveConfig(); s.sendMessage(Ui.mm("<green>Join effects " + sub + ".")); }
                    case "cooldown" -> {
                        try {
                            long m = Math.max(0, Long.parseLong(a[2]));
                            cfg.set("hub.join.full-show-cooldown-minutes", m); plugin.saveConfig();
                            s.sendMessage(Ui.mm("<green>Returning players see the full show after <white>" + m + "<green> minutes away."));
                        } catch (Exception e) { s.sendMessage(Ui.mm("<red>/blackforge effects cooldown <minutes>")); }
                    }
                    case "test" -> {
                        if (!(s instanceof Player p)) { s.sendMessage(Ui.mm("<red>Players only.")); return true; }
                        if (a.length > 2 && a[2].equalsIgnoreCase("full")) WelcomeShow.play(plugin, p); else shortArrival(p);
                    }
                    default -> {
                        s.sendMessage(Ui.mm("<gold>Join effects: <white>" + (cfg.getBoolean("hub.join.enabled", true) ? "on" : "off")
                                + "<gold>, full show after <white>" + cfg.getLong("hub.join.full-show-cooldown-minutes", 360) + " min<gold> away, short effects <white>"
                                + cfg.getBoolean("hub.join.short-effects", true) + "<gold>, roar chance <white>" + cfg.getDouble("hub.join.roar-chance", 0.2)));
                        s.sendMessage(Ui.mm("<gray>/blackforge effects on|off|cooldown <min>|test [short|full]"));
                    }
                }
            }
            case "portals" -> {
                if (a.length >= 4 && a[1].equalsIgnoreCase("set")) {
                    if (HubLayout.GATES.stream().noneMatch(g -> g.server().equalsIgnoreCase(a[2]))) { s.sendMessage(Ui.mm("<red>No portal called " + a[2])); return true; }
                    cfg.set("hub.portals." + a[2].toLowerCase(), a[3]); plugin.saveConfig();
                    s.sendMessage(Ui.mm("<green>The " + a[2] + " portal now connects to <white>" + a[3] + "<green>."));
                } else {
                    s.sendMessage(Ui.mm("<gold>Portals:"));
                    for (HubLayout.Gate g : HubLayout.GATES) {
                        String dest = cfg.getString("hub.portals." + g.server(), g.server());
                        s.sendMessage(Ui.mm(" <white>" + g.title() + " <gray>at " + g.cx() + ", " + g.cz() + " -> <white>" + dest + " <gray>(" + plugin.net().count(dest) + " online)"));
                    }
                    s.sendMessage(Ui.mm("<gray>/blackforge portals set <portal> <server>"));
                }
            }
            default -> {
                Location sp = spawn();
                s.sendMessage(Ui.mm("<gold>Black Forge hub <gray>- spawn <white>" + String.format("%.0f %.0f %.0f", sp.getX(), sp.getY(), sp.getZ())
                        + "<gray>, " + HubLayout.GATES.size() + " portals, dragons " + (dragons != null ? "on" : "off")
                        + ", fortress " + (new File(plugin.getDataFolder(), "hub-keep-v1").exists() ? "built" : "not built")));
                s.sendMessage(Ui.mm("<gray>/blackforge setspawn | reload | dragons | effects | portals | info   (config.yml, hub: section)"));
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ movement

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        Location to = e.getTo();
        if (to.getWorld() != world) return;   // the Arena handles its own world
        if (to.getY() < HubLayout.VOID_Y) { toSpawn(p); p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1f); return; }
        if (p.getGameMode() == GameMode.ADVENTURE && !p.getAllowFlight() && p.isOnGround()) p.setAllowFlight(true);
        if (!e.hasChangedBlock()) return;
        for (HubLayout.Gate g : HubLayout.GATES) {
            if (!g.contains(to.getX(), to.getY(), to.getZ())) continue;
            long now = System.currentTimeMillis();
            if (portalCooldown.getOrDefault(p.getUniqueId(), 0L) > now) return;
            portalCooldown.put(p.getUniqueId(), now + 3000);
            // bounce back out so a failed connect doesn't leave them standing in the portal
            Vector back = new Vector(0, 0, 1).multiply(0.9).setY(0.35);
            p.setVelocity(back);
            connect(p, g.server());
            return;
        }
    }

    @EventHandler
    public void onDoubleJump(PlayerToggleFlightEvent e) {
        Player p = e.getPlayer();
        if (p.getWorld() != world) return;
        if (p.getGameMode() != GameMode.ADVENTURE && p.getGameMode() != GameMode.SURVIVAL) return;
        e.setCancelled(true);
        p.setAllowFlight(false);
        p.setFlying(false);
        p.setVelocity(p.getLocation().getDirection().setY(0).normalize().multiply(1.25).setY(0.95));
        p.playSound(p.getLocation(), Sound.ENTITY_BREEZE_JUMP, 0.5f, 1.2f);
        world.spawnParticle(Particle.FLAME, p.getLocation(), 12, 0.25, 0.05, 0.25, 0.02);
    }

    // ------------------------------------------------------------------ holograms + sidebar

    private void spawnHolograms() {
        holograms.forEach(TextDisplay::remove);   // only our own labels: the boards and parkour signs are left alone
        for (HubLayout.Gate g : HubLayout.GATES) {
            // in front of the gate, on the side facing spawn
            double fx = g.alongX() ? g.cx() + 0.5 : g.cx() + 0.5 - Math.signum(g.cx()) * 3.5;
            double fz = g.alongX() ? g.cz() + 0.5 - Math.signum(g.cz()) * 3.5 : g.cz() + 0.5;
            TextDisplay td = Ui.hologram(new Location(world, fx, HubLayout.Y + 9.2, fz), gateText(g), 1.6f);
            holograms.add(td);
            gateHolos.put(g.server(), td);
        }
        holograms.add(Ui.hologram(new Location(world, 0.5, HubLayout.Y + 6.3, -3.0),
                Ui.TITLE + "<newline><gray>Use your <gold>compass</gold> or walk into a portal<newline><gray>Double-tap <white>space</white> to dash", 1.2f));
    }

    private String gateText(HubLayout.Gate g) {
        return "<" + g.color() + "><bold>" + g.title() + "</bold><newline><gray>" + plugin.net().count(g.server()) + " playing<newline><yellow>Walk in to play";
    }

    private void refresh() {
        if (holograms.stream().anyMatch(td -> !td.isValid())) { holograms.clear(); gateHolos.clear(); spawnHolograms(); }
        for (HubLayout.Gate g : HubLayout.GATES) {
            TextDisplay td = gateHolos.get(g.server());
            if (td != null && td.isValid()) Ui.setText(td, gateText(g));
        }
        for (Player p : Bukkit.getOnlinePlayers()) refreshPlayer(p);
    }

    private void refreshPlayer(Player p) {
        var net = plugin.net();
        Ui.sidebar(p, List.of(
                "<dark_gray>" + "-".repeat(18),
                "<gray>Online: <white>" + net.count("ALL"),
                " ",
                "<#4fd1c5>■ <white>Skyblock <gray>" + net.count("skyblock"),
                "<#d9dde3>■ <white>Survival <gray>" + net.count("survival"),
                "<#e5484d>■ <white>Factions <gray>" + net.count("factions"),
                "<#ffc53d>■ <white>Arcade <gray>" + net.count("arcade"),
                "<#4fc3f7>■ <white>Raft <gray>" + net.count("raft"),
                "  ",
                Events.sidebarLine(),
                "   ",
                "<#7289da>/discord <dark_gray>- <gray>join us",
                "<gold>" + plugin.getConfig().getString("address", "BlackForgeMC"),
                "<dark_gray>" + "-".repeat(18) + " "));
        p.sendPlayerListHeaderAndFooter(Ui.mm("\n" + Ui.TITLE + "\n<gray>Forged for fun\n"),
                Ui.mm("\n<gray>Online: <white>" + net.count("ALL") + "  <dark_gray>|  <gray>/hub to return\n"));
    }

    private void ambient() {
        // embers rising from the crucible moat and the smelter pours
        for (int i = 0; i < 4; i++) {
            double a = Math.random() * Math.PI * 2, r = 4 + Math.random() * 3;
            world.spawnParticle(Particle.LAVA, HubLayout.SPAWN_X + Math.cos(a) * r, HubLayout.Y + 1, 0.5 + Math.sin(a) * r, 1);
        }
        world.spawnParticle(Particle.SMALL_FLAME, 0.5, HubLayout.Y + 15.5, 0.5, 6, 4, 0.4, 1.2, 0.01);
        // molten metal pouring from the four smelter spouts into the moat
        for (int[] q : new int[][]{{1, 1}, {1, -1}, {-1, 1}, {-1, -1}}) {
            double sx = q[0] * 5 + 0.5, sz = q[1] * 5 + 0.5;
            world.spawnParticle(Particle.FALLING_LAVA, sx, HubLayout.Y + 7.9, sz, 3, 0.12, 0.05, 0.12, 0);
            world.spawnParticle(Particle.LAVA, sx, HubLayout.Y + 1.05, sz, 1, 0.3, 0, 0.3, 0);
        }
        // embers drifting up across the island
        for (int i = 0; i < 6; i++) {
            double a = Math.random() * Math.PI * 2, r = 10 + Math.random() * 34;
            world.spawnParticle(Particle.FLAME, Math.cos(a) * r, HubLayout.Y + 1.5 + Math.random() * 4, Math.sin(a) * r, 0, 0, 0.6, 0, 0.04);
        }
        for (HubLayout.Gate g : HubLayout.GATES)
            world.spawnParticle(Particle.PORTAL, g.cx() + 0.5, HubLayout.Y + 5, g.cz() + 0.5, 10, g.alongX() ? 2 : 0.3, 2.5, g.alongX() ? 0.3 : 2, 0.2);
    }

    // ------------------------------------------------------------------ dragons

    /** Raises the castle wall around a hub that was built before it existed. */
    /** The Raft portal, added to a hub that is already built: only places blocks, never clears anything. */
    private void addRaftGate() {
        HubLayout.Gate g = HubLayout.GATES.stream().filter(x -> x.server().equals("raft")).findFirst().orElse(null);
        if (g == null) return;
        var gate = HubBuilder.buildGate(g);
        world.getChunkAt(g.cx() >> 4, g.cz() >> 4).load();
        Paster.paste(plugin, world, gate, 4000, () -> {
            try { new File(plugin.getDataFolder(), "hub-raft-gate-v1").createNewFile(); } catch (Exception ignored) { }
            plugin.getLogger().info("Hub Raft gate built: " + gate.size() + " blocks");
        });
    }

    private void addCastle() {
        var castle = CastleBuilder.build();
        Paster.paste(plugin, world, castle, 4000, () -> {
            try { new File(plugin.getDataFolder(), "hub-castle-v1").createNewFile(); } catch (Exception ignored) { }
            plugin.getLogger().info("Hub castle built: " + castle.size() + " blocks");
        });
    }

    /** The dragons fly now: clear the old statue blocks (both versions) out of the sky, leaving every hub block alone. */
    private void removeStatues() {
        var hub = HubBuilder.build();
        var clear = new net.blackforge.core.build.Canvas();
        var old = DragonStatues.build();
        old.blocks().putAll(DragonStatues.build(DragonStatues.OLD_WEST).blocks());
        for (long k : old.blocks().keySet())
            if (!hub.blocks().containsKey(k) && !hub.lateBlocks().containsKey(k))
                clear.set(net.blackforge.core.build.Canvas.kx(k), net.blackforge.core.build.Canvas.ky(k), net.blackforge.core.build.Canvas.kz(k), "minecraft:air");
        Paster.paste(plugin, world, clear, 6000, () -> {
            try { new File(plugin.getDataFolder(), "hub-dragons-flying").createNewFile(); } catch (Exception ignored) { }
            plugin.getLogger().info("Hub dragon statues cleared (" + clear.size() + " blocks) - the dragons fly now");
        });
    }
}
