package net.alloymc.core.hub;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.LobbyRules;
import net.alloymc.core.Ui;
import net.alloymc.core.build.Paster;
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
    private final AlloyCore plugin;
    private final World world;
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
        {"arcade", "16", "TNT", "<#ffc53d><bold>Arcade", "<gray>17 minigames: BedWars, SkyWars,<newline><gray>Duels, Murder Mystery and more."},
    };

    public HubModule(AlloyCore plugin) {
        this.plugin = plugin;
        this.world = Bukkit.getWorlds().getFirst();
        LobbyRules.setupWorld(plugin, world, 18000);   // midnight under the Inferno biome's red sky
        world.setSpawnLocation(spawn());
        Bukkit.getPluginManager().registerEvents(new LobbyRules(p -> false), plugin);
        new net.alloymc.core.SpawnSafety(plugin, l -> l.getWorld() == world, p -> spawn()).withStuckCommand();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        registerJoinCommand();   // the clickable links in the first-join guide run /alloyjoin
        // keep the whole hub loaded: holograms survive, lava keeps flowing, first joins are instant
        for (int cx = -6; cx <= 5; cx++) for (int cz = -6; cz <= 5; cz++) world.addPluginChunkTicket(cx, cz, plugin);
        if (!new File(plugin.getDataFolder(), "hub-built").exists()) build(Bukkit.getConsoleSender());
        else {
            if (!new File(plugin.getDataFolder(), "hub-biome-v2").exists()) applyBiome();
            if (!new File(plugin.getDataFolder(), "hub-castle-v1").exists()) addCastle();
            if (new File(plugin.getDataFolder(), "hub-dragons-v1").exists() && !new File(plugin.getDataFolder(), "hub-dragons-flying").exists()) removeStatues();
        }
        Bukkit.getScheduler().runTaskLater(plugin, this::spawnHolograms, 40L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 60L, 40L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::ambient, 20L, 10L);
        dragons = new DragonAnimator(plugin, world);
    }

    private void registerJoinCommand() {
        Bukkit.getCommandMap().register("alloy", new org.bukkit.command.Command("alloyjoin", "Join a game mode", "/alloyjoin <server>", List.of()) {
            @Override public boolean execute(org.bukkit.command.CommandSender s, String label, String[] a) {
                if (s instanceof Player p && a.length == 1 && List.of("skyblock", "survival", "factions", "arcade").contains(a[0])) connect(p, a[0]);
                return true;
            }
        });
    }

    private DragonAnimator dragons;

    public void shutdown() { holograms.forEach(TextDisplay::remove); if (dragons != null) dragons.remove(); }

    public Location spawn() {
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
                    for (int y = HubLayout.Y - 80; y <= HubLayout.Y + 70; y++) {
                        var b = world.getBlockAt(x[0], y, z);
                        if (!b.getType().isAir()) b.setType(Material.AIR, false);
                    }
            if (x[0] <= e) return;
            task.cancel();
            pasteHub(who, canvas, t);
        }, 1L, 1L);
    }

    private void pasteHub(CommandSender who, net.alloymc.core.build.Canvas canvas, long t) {
        Paster.paste(plugin, world, canvas, 6000, () -> {
            try { new File(plugin.getDataFolder(), "hub-built").createNewFile(); } catch (Exception ignored) { }
            try { new File(plugin.getDataFolder(), "hub-castle-v1").createNewFile(); } catch (Exception ignored) { }
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
        if (!p.hasPermission("alloy.admin") || p.getGameMode() != GameMode.CREATIVE) p.setGameMode(GameMode.ADVENTURE);
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
            if (SHOW_EVERY_JOIN.contains(p.getUniqueId())) {
                // after the loading screen, same delay as the first-join show
                Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) WelcomeShow.play(plugin, p); }, 30L);
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
            WelcomeShow.play(plugin, p);
        }, 30L);
        // a short, clickable guide
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            p.sendMessage(Ui.mm(" "));
            p.sendMessage(Ui.mm("  " + Ui.TITLE + " <gray>- glad you're here, <white>" + p.getName() + "<gray>!"));
            p.sendMessage(Ui.mm("  <gray>Pick a game with the <gold>compass</gold> or walk into a portal:"));
            p.sendMessage(Ui.mm("  <#4fd1c5>» <u><click:run_command:'/alloyjoin skyblock'>Skyblock</click></u> <gray>- grow your own sky island"));
            p.sendMessage(Ui.mm("  <#d9dde3>» <u><click:run_command:'/alloyjoin survival'>Survival</click></u> <gray>- claims, crates, KOTH, the Scrapyard"));
            p.sendMessage(Ui.mm("  <#e5484d>» <u><click:run_command:'/alloyjoin factions'>Factions</click></u> <gray>- build a faction, raid rivals"));
            p.sendMessage(Ui.mm("  <#ffc53d>» <u><click:run_command:'/alloyjoin arcade'>Arcade</click></u> <gray>- BedWars, SkyWars, Duels + 14 more minigames"));
            p.sendMessage(Ui.mm("  <gray>Be respectful and have fun - type <white>/hub</white> any time to come back here."));
            p.sendMessage(Ui.mm(" "));
        }, 60L);
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
            lore.add("<yellow>» Click to join");
            inv.setItem(Integer.parseInt(m[1]), Ui.item(Material.valueOf(m[2]), m[3], lore.toArray(String[]::new)));
        }
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.5f, 1.3f);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof MenuHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        for (String[] m : MENU)
            if (e.getRawSlot() == Integer.parseInt(m[1])) {
                p.closeInventory();
                connect(p, m[0]);
            }
    }

    private void connect(Player p, String server) {
        p.sendActionBar(Ui.mm("<gold>Sending you to <white>" + server + "<gold>..."));
        p.playSound(p.getLocation(), Sound.BLOCK_PORTAL_TRAVEL, 0.25f, 1.6f);
        plugin.net().send(p, server);
    }

    // ------------------------------------------------------------------ movement

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        Location to = e.getTo();
        if (to.getY() < HubLayout.VOID_Y) { toSpawn(p); p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1f); return; }
        if (p.getGameMode() == GameMode.ADVENTURE && !p.getAllowFlight() && p.isOnGround()) p.setAllowFlight(true);
        if (!e.hasChangedBlock()) return;
        for (HubLayout.Gate g : HubLayout.GATES) {
            if (!g.contains(to.getX(), to.getY(), to.getZ())) continue;
            long now = System.currentTimeMillis();
            if (portalCooldown.getOrDefault(p.getUniqueId(), 0L) > now) return;
            portalCooldown.put(p.getUniqueId(), now + 3000);
            // bounce back out so a failed connect doesn't leave them standing in the portal
            Vector back = new Vector(-g.cx(), 0, -g.cz()).normalize().multiply(0.9).setY(0.35);
            p.setVelocity(back);
            connect(p, g.server());
            return;
        }
    }

    @EventHandler
    public void onDoubleJump(PlayerToggleFlightEvent e) {
        Player p = e.getPlayer();
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
        world.getEntitiesByClass(TextDisplay.class).forEach(TextDisplay::remove);
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
            if (td != null && td.isValid()) td.text(Ui.mm(gateText(g)));
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
                "  ",
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
        var clear = new net.alloymc.core.build.Canvas();
        var old = DragonStatues.build();
        old.blocks().putAll(DragonStatues.build(DragonStatues.OLD_WEST).blocks());
        for (long k : old.blocks().keySet())
            if (!hub.blocks().containsKey(k) && !hub.lateBlocks().containsKey(k))
                clear.set(net.alloymc.core.build.Canvas.kx(k), net.alloymc.core.build.Canvas.ky(k), net.alloymc.core.build.Canvas.kz(k), "minecraft:air");
        Paster.paste(plugin, world, clear, 6000, () -> {
            try { new File(plugin.getDataFolder(), "hub-dragons-flying").createNewFile(); } catch (Exception ignored) { }
            plugin.getLogger().info("Hub dragon statues cleared (" + clear.size() + " blocks) - the dragons fly now");
        });
    }
}
