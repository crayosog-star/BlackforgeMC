package net.alloymc.core.arcade;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.LobbyRules;
import net.alloymc.core.Ui;
import net.alloymc.core.arcade.core.*;
import net.alloymc.core.build.Paster;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.*;

/**
 * The arcade server's lobby (the main world): spawn platform, quick-join booths, hotbar items, sidebar and
 * leaderboard hologram. All games run through {@link Arcade} in their own instance world.
 */
public final class ArcadeModule implements Listener, Arcade.Lobby {
    static final int LOBBY_Y = LobbyBuilder.Y;
    private final AlloyCore plugin;
    private final World world;
    private final Arcade arcade;
    private final Map<String, int[]> booths = new LinkedHashMap<>();   // game id (or hub) -> plate x, z
    private final Map<String, TextDisplay> boothHolos = new HashMap<>();
    private final Map<UUID, Long> plateCooldown = new HashMap<>();
    private TextDisplay lobbyTitle, board;

    public ArcadeModule(AlloyCore plugin) {
        this.plugin = plugin;
        this.world = Bukkit.getWorlds().getFirst();
        LobbyRules.setupWorld(plugin, world, 6000);
        world.setSpawnLocation(lobbySpawn());
        booths.putAll(LobbyBuilder.plates());
        arcade = new Arcade(plugin, this);
        new Seasons(plugin, arcade);   // monthly ranked seasons and the Weekend Cup
        Games.registerAll(arcade);
        net.alloymc.core.arcade.games.bedwars.ForgeArsenal.install(plugin, arcade);   // Forge Wars ability items (only work in its matches)
        Bukkit.getPluginManager().registerEvents(new LobbyRules(p -> p.getWorld() != world), plugin);
        new net.alloymc.core.SpawnSafety(plugin, l -> l.getWorld() == world, p -> lobbySpawn()).withStuckCommand();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (int cx = -3; cx <= 2; cx++) for (int cz = -3; cz <= 2; cz++) world.addPluginChunkTicket(cx, cz, plugin);
        if (!new File(plugin.getDataFolder(), LOBBY_MARKER).exists()) buildAll(Bukkit.getConsoleSender());
        Bukkit.getScheduler().runTaskLater(plugin, () -> arcade.loadMaps(msg -> plugin.getLogger().info("[Arcade] " + msg)), 20L);
        Bukkit.getScheduler().runTaskLater(plugin, this::spawnHolograms, 40L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 20L, 20L);
    }

    public World world() { return world; }
    public Arcade arcade() { return arcade; }

    public void shutdown() { arcade.shutdown(); }

    private static final String LOBBY_MARKER = "arcade-lobby-v2";

    /** Clear the lobby area (old lobby included) a few columns per tick, then paste the forge lobby. */
    public void buildAll(CommandSender who) {
        who.sendMessage(Component.text("Building the BlackForge Arcade lobby..."));
        int[] x = {-60};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            for (int n = 0; n < 3 && x[0] <= 60; n++, x[0]++)
                for (int z = -60; z <= 60; z++)
                    for (int y = LOBBY_Y - 40; y <= LOBBY_Y + 45; y++) {
                        Block b = world.getBlockAt(x[0], y, z);
                        if (!b.getType().isAir()) b.setType(Material.AIR, false);
                    }
            if (x[0] <= 60) return;
            task.cancel();
            pasteLobby(who);
        }, 1L, 1L);
    }

    private void pasteLobby(CommandSender who) {
        Paster.paste(plugin, world, LobbyBuilder.build(), 6000, () -> {
            try { new File(plugin.getDataFolder(), LOBBY_MARKER).createNewFile(); } catch (Exception ignored) { }
            spawnHolograms();
            who.sendMessage(Component.text("Arcade lobby built. Map templates are managed by /arcade."));
            for (Player p : Bukkit.getOnlinePlayers()) if (!inGame(p)) toLobby(p);
        });
    }

    // ------------------------------------------------------------------ API used by AlloyCore commands

    public boolean inGame(Player p) { return arcade.matches().matchOf(p) != null || arcade.matches().queueOf(p) != null; }
    public void leave(Player p) { arcade.matches().leave(p); }
    public void openSelector(Player p) { arcade.menu().openMain(p, null, 0); }
    public void command(CommandSender s, String[] args) { arcade.command().run(s, args); }

    // ------------------------------------------------------------------ lobby

    Location lobbySpawn() { double[] s = LobbyBuilder.spawn(); return new Location(world, s[0], s[1], s[2], 180, 0); }

    @Override
    public void toLobby(Player p) {
        Match.resetPlayer(p, GameMode.ADVENTURE);
        hotbar(p);
        p.teleport(lobbySpawn());
        Ui.sidebar(p, List.of(" "));
    }

    private void hotbar(Player p) {
        p.getInventory().clear();
        p.getInventory().setItem(0, Ui.item(Material.NETHER_STAR, "<aqua><bold>Game Selector</bold> <gray>(right-click)"));
        if (arcade.matches().queueOf(p) != null) p.getInventory().setItem(4, Ui.item(Material.RED_DYE, "<red><bold>Leave Queue</bold> <gray>(right-click)"));
        p.getInventory().setItem(7, Ui.item(Material.PLAYER_HEAD, "<aqua><bold>Your Stats</bold> <gray>(right-click)"));
        p.getInventory().setItem(8, Ui.item(Material.RED_BED, "<red><bold>Back to Hub</bold> <gray>(right-click)"));
    }

    @Override public void giveQueueItems(Player p) { if (p.getWorld() == world) hotbar(p); }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        e.joinMessage(null);
        Player p = e.getPlayer();
        arcade.stats().name(p.getUniqueId(), p.getName());
        String wanted = net.alloymc.core.ArcadeIntents.take(plugin.shared(), p.getUniqueId());   // picked a game on the hub
        if (arcade.matches().tryRejoin(p)) return;
        toLobby(p);
        ArcadeGame picked = arcade.game(wanted);
        if (picked == null) p.showTitle(net.kyori.adventure.title.Title.title(Ui.mm("<aqua><bold>BLACKFORGE ARCADE"), Ui.mm("<gray>Right-click the star to pick a game")));
        else {
            p.showTitle(net.kyori.adventure.title.Title.title(Ui.mm(picked.color + "<bold>" + picked.name.toUpperCase()), Ui.mm("<gray>Pick a mode to queue")));
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline() && arcade.matches().matchOf(p) == null) arcade.menu().openGame(p, picked); }, 20L);
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) { e.quitMessage(null); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (p.getWorld() != world) return;
        if (e.getAction() == Action.PHYSICAL && e.getClickedBlock() != null && e.getClickedBlock().getType() == Material.LIGHT_WEIGHTED_PRESSURE_PLATE) {
            Block b = e.getClickedBlock();
            for (var en : booths.entrySet())
                if (b.getX() == en.getValue()[0] && b.getZ() == en.getValue()[1] && Math.abs(b.getY() - LOBBY_Y - 1) <= 1) {
                    long now = System.currentTimeMillis();
                    if (plateCooldown.getOrDefault(p.getUniqueId(), 0L) > now) return;
                    plateCooldown.put(p.getUniqueId(), now + 2000);
                    booth(p, en.getKey());
                }
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        if (it == null) return;
        switch (it.getType()) {
            case NETHER_STAR -> { e.setCancelled(true); openSelector(p); }
            case RED_DYE -> { e.setCancelled(true); arcade.matches().leave(p); }
            case PLAYER_HEAD -> { e.setCancelled(true); arcade.command().run(p, new String[]{"stats"}); }
            case RED_BED -> { e.setCancelled(true); booth(p, "hub"); }
            default -> {}
        }
    }

    private void booth(Player p, String id) {
        if (id.equals("hub")) { arcade.matches().leave(p); plugin.net().send(p, "hub"); return; }
        ArcadeGame g = arcade.game(id);
        if (g == null) return;
        MatchManager.Queue q = arcade.matches().queueOf(p);
        if (q != null && q.game == g) return;   // standing on the plate you already used: stay quiet
        arcade.matches().join(p, g, g.variants().getFirst());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (p.getWorld() == world && e.getTo().getY() < LOBBY_Y - 45 && p.getGameMode() != GameMode.SPECTATOR) toLobby(p);
    }

    // ------------------------------------------------------------------ holograms + sidebar

    private void spawnHolograms() {
        world.getEntitiesByClass(TextDisplay.class).forEach(TextDisplay::remove);
        boothHolos.clear();
        for (var en : booths.entrySet()) {
            int[] b = en.getValue();
            boothHolos.put(en.getKey(), Ui.hologram(new Location(world, b[0] + 0.5, LOBBY_Y + 4.0, b[1] + 0.5), boothText(en.getKey()), 1.1f));
        }
        lobbyTitle = Ui.hologram(new Location(world, 0.5, LOBBY_Y + 11.6, 0.5),
                "<aqua><bold>BLACKFORGE ARCADE</bold><newline><gray>" + arcade.games().size() + " games • random maps every match<newline><gray>Right-click the <aqua>Game Selector<gray> or step on a plate", 1.3f);
        double[] bp = LobbyBuilder.board();
        board = Ui.hologram(new Location(world, bp[0], bp[1], bp[2]), "<gray>Loading...", 1.0f);
    }

    private String boothText(String id) {
        if (id.equals("hub")) return "<red><bold>BACK TO HUB";
        ArcadeGame g = arcade.game(id);
        if (g == null) return id;
        Variant v = g.variants().getFirst();
        MatchManager.Queue q = arcade.matches().queue(g, v);
        String st = q.countdown() >= 0 ? "<yellow>Starting in " + q.countdown() + "s" : q.size() > 0 ? "<green>" + q.size() + " waiting" : "<gray>Step on the plate";
        return g.color + "<bold>" + g.name.toUpperCase() + "</bold><newline>" + st + "<newline><gray>Playing: <white>" + arcade.matches().playing(g);
    }

    private int boardTick;

    private void refresh() {
        if (lobbyTitle == null || !lobbyTitle.isValid() || boothHolos.values().stream().anyMatch(td -> !td.isValid())) spawnHolograms();
        for (var en : boothHolos.entrySet()) if (en.getValue().isValid()) en.getValue().text(Ui.mm(boothText(en.getKey())));
        if (board != null && board.isValid() && boardTick++ % 30 == 0) {
            StringBuilder sb = new StringBuilder("<aqua><bold>TOP ARCADE WINNERS</bold>");
            List<StatsService.Row> rows = arcade.stats().top(null, "wins", 10, false);
            if (rows.isEmpty()) sb.append("<newline><gray>No winners yet - be the first!");
            for (int i = 0; i < rows.size(); i++) sb.append("<newline><gray>").append(i + 1).append(". <white>").append(rows.get(i).name()).append(" <aqua>").append(rows.get(i).value());
            board.text(Ui.mm(sb.toString()));
        }
        for (Player p : world.getPlayers()) {
            MatchManager.Queue q = arcade.matches().queueOf(p);
            List<String> lines = new ArrayList<>();
            lines.add("<dark_gray>" + "-".repeat(18));
            lines.add("<gray>Arcade wins: <aqua>" + arcade.stats().total(p.getUniqueId(), "wins"));
            lines.add("<gray>Games played: <white>" + arcade.stats().total(p.getUniqueId(), "played"));
            lines.add(" ");
            lines.add("<gray>Queue: " + (q == null ? "<white>none" : q.game.color + q.game.name));
            if (q != null) lines.add("<gray>" + (q.countdown() >= 0 ? "Starting in <white>" + q.countdown() + "s" : "Waiting <white>" + q.size() + "/" + q.variant.minPlayers()));
            lines.add("<gray>Matches live: <white>" + arcade.matches().active().size());
            lines.add("  ");
            lines.add("<aqua>" + plugin.getConfig().getString("address", "BlackForgeMC"));
            lines.add("<dark_gray>" + "-".repeat(18) + " ");
            Ui.sidebar(p, lines);
        }
    }
}
