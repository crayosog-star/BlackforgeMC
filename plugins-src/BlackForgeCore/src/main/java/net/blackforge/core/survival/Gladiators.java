package net.blackforge.core.survival;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.blackforge.core.BlackForgeCore;
import net.blackforge.core.Ui;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The BlackForge Colosseum: 1v1 gladiator duels with the players' own survival gear, an Elo-rated ladder with a Grand
 * Champion, an optional chat rank, public announcements for spectators, and safe state handling.
 *
 * <b>Safety</b>: before a duel each fighter's inventory, armour, off hand, XP, health, food, effects and position are
 * saved to disk (plugins/BlackForgeCore/gladiator/snapshots). Fatal damage is cancelled (nobody dies, nothing drops), and when
 * the duel ends, is stopped, or a fighter disconnects, the saved state is put back exactly, so nothing can be lost or
 * duplicated. If the server stops mid-duel, the snapshot is restored the next time the fighter joins.
 */
final class Gladiators implements Listener {
    private static final int Y = SurvivalLayout.Y;
    private static final int CX = ColiseumBuilder.CX, CZ = ColiseumBuilder.CZ;

    private record Challenge(UUID from, UUID to, long expires) {}

    private static final class Duel {
        final UUID a, b;
        final String nameA, nameB;
        final long created = System.currentTimeMillis();
        int phase;   // 0 countdown, 1 fight, 2 over
        long fightStart;
        BukkitTask countdown, monitor, timeout;
        final List<Location> placed = new ArrayList<>();
        Duel(Player a, Player b) { this.a = a.getUniqueId(); this.b = b.getUniqueId(); nameA = a.getName(); nameB = b.getName(); }
        boolean has(UUID u) { return a.equals(u) || b.equals(u); }
        UUID other(UUID u) { return a.equals(u) ? b : a; }
    }

    private enum Menu { PLAYERS, CONFIRM, PROFILE }

    private static final class MenuHolder implements InventoryHolder {
        final Menu type; final UUID target; Inventory inv;
        MenuHolder(Menu type, UUID target) { this.type = type; this.target = target; }
        public Inventory getInventory() { return inv; }
    }

    private final BlackForgeCore plugin;
    private final World world;
    private final Supplier<Location> exit;
    private final File cfgFile, dataFile, snapDir, matchLog;
    private YamlConfiguration cfg, data;
    private final Map<UUID, Challenge> incoming = new HashMap<>();   // by target
    private final Map<UUID, UUID> outgoing = new HashMap<>();        // from -> target
    private final Map<UUID, String> tags = new ConcurrentHashMap<>();
    private final List<TextDisplay> holos = new ArrayList<>();
    private List<UUID> ranking = List.of();
    private Duel duel;
    private UUID champion;
    private boolean dirty;
    private final Random rnd = new Random();

    Gladiators(BlackForgeCore plugin, World world, Supplier<Location> exit) {
        this.plugin = plugin;
        this.world = world;
        this.exit = exit;
        File dir = new File(plugin.getDataFolder(), "gladiator");
        dir.mkdirs();
        this.snapDir = new File(dir, "snapshots");
        snapDir.mkdirs();
        this.cfgFile = new File(plugin.getDataFolder(), "colosseum.yml");
        this.dataFile = new File(dir, "gladiators.yml");
        this.matchLog = new File(dir, "matches.log");
        if (!cfgFile.exists()) plugin.saveResource("colosseum.yml", false);
        reload();
        champion = data.getString("champion") == null ? null : UUID.fromString(data.getString("champion"));
        Bukkit.getPluginManager().registerEvents(this, plugin);
        register("duel", "Challenge a player to a gladiator duel", "/duel <player|accept|deny|cancel|list>", List.of("challenge"), this::duelCommand);
        register("colosseum", "Visit the BlackForge Colosseum", "/colosseum [spectate|leave]", List.of("coliseum", "arena"), this::colosseumCommand);
        register("gladiator", "Your gladiator profile and the rankings", "/gladiator [stats|rank|leaderboard|toggle]", List.of("gladiators", "glad"), this::gladiatorCommand);
        Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 100L, 20L * 60);
        Bukkit.getScheduler().runTaskTimer(plugin, this::saveIfDirty, 20L * 120, 20L * 120);
        Bukkit.getScheduler().runTaskLater(plugin, this::buildIfNeeded, 200L);
    }

    /** The first time: paste the Colosseum onto its floating rock south of the kingdom. */
    private void buildIfNeeded() {
        File v2 = new File(plugin.getDataFolder(), "coliseum-v2-built"), v1 = new File(plugin.getDataFolder(), "coliseum-v1-built");
        if (v2.exists()) { keepLoaded(); return; }
        if (v1.exists()) {   // v1 stood at z=250 facing the Scrapyard: take it down one slice per tick, then build the new one
            plugin.getLogger().info("Removing the first Colosseum...");
            int[] x = {-66};
            Bukkit.getScheduler().runTaskTimer(plugin, task -> {
                if (x[0] > 66) { task.cancel(); v1.delete(); paste(v2); return; }
                for (int z = 184; z <= 340; z++) for (int y = Y - 32; y <= Y + 72; y++) {
                    var b = world.getBlockAt(x[0], y, z);
                    if (!b.getType().isAir()) b.setType(Material.AIR, false);
                }
                x[0]++;
            }, 1L, 1L);
            return;
        }
        paste(v2);
    }

    private void paste(File marker) {
        plugin.getLogger().info("Building the Colosseum...");
        net.blackforge.core.build.Paster.paste(plugin, world, ColiseumBuilder.build(), 4000, () -> {
            try { marker.createNewFile(); } catch (Exception ignored) { }
            plugin.getLogger().info("Built the Colosseum");
            keepLoaded();
            refresh();
        });
    }

    private void keepLoaded() {
        for (int cx = (CX - 48) >> 4; cx <= (CX + 48) >> 4; cx++) for (int cz = (CZ - 86) >> 4; cz <= (CZ + 56) >> 4; cz++) world.addPluginChunkTicket(cx, cz, plugin);
    }

    private void reload() {
        cfg = YamlConfiguration.loadConfiguration(cfgFile);
        data = YamlConfiguration.loadConfiguration(dataFile);
    }

    private interface Cmd { void run(CommandSender s, String[] a); }

    private void register(String name, String desc, String usage, List<String> aliases, Cmd cmd) {
        Bukkit.getCommandMap().register("blackforge", new Command(name, desc, usage, aliases) {
            @Override public boolean execute(CommandSender s, String label, String[] args) { cmd.run(s, args); return true; }
            @Override public List<String> tabComplete(CommandSender s, String alias, String[] args) {
                if (args.length != 1) return List.of();
                List<String> opts = new ArrayList<>();
                switch (name) {
                    case "duel" -> { opts.addAll(List.of("accept", "deny", "cancel", "list")); Bukkit.getOnlinePlayers().forEach(p -> opts.add(p.getName())); }
                    case "colosseum" -> { opts.addAll(List.of("spectate", "leave")); if (s.hasPermission("blackforge.admin")) opts.addAll(List.of("setspawn", "setarena", "setexit", "setlobby", "setspectator", "reload", "forcestop")); }
                    default -> opts.addAll(List.of("stats", "rank", "leaderboard", "toggle"));
                }
                return opts.stream().filter(o -> o.toLowerCase().startsWith(args[0].toLowerCase())).toList();
            }
        });
    }

    // ------------------------------------------------------------------ config and locations

    private double num(String path, double def) { return cfg.getDouble(path, def); }
    private boolean rule(String path) { return cfg.getBoolean("rules." + path, true); }

    private Location loc(String key, double x, double y, double z, float yaw) {
        String b = "locations." + key + ".";
        return new Location(world, cfg.getDouble(b + "x", x), cfg.getDouble(b + "y", y), cfg.getDouble(b + "z", z), (float) cfg.getDouble(b + "yaw", yaw), (float) cfg.getDouble(b + "pitch", 0));
    }
    private Location lobby() { return loc("lobby", ColiseumBuilder.LOBBY_X, Y + 1, ColiseumBuilder.LOBBY_Z, 0); }
    private Location arenaCenter() { return loc("arena", CX + 0.5, Y + 1, CZ + 0.5, 0); }
    private Location fighter(int n) { return n == 1 ? loc("spawn1", ColiseumBuilder.FIGHTER_A_X, Y + 1, ColiseumBuilder.FIGHT_Z, -90) : loc("spawn2", ColiseumBuilder.FIGHTER_B_X, Y + 1, ColiseumBuilder.FIGHT_Z, 90); }
    private Location exitLoc() { return cfg.contains("locations.exit.x") ? loc("exit", 0, 0, 0, 0) : exit.get(); }
    private Location seat() {
        if (cfg.contains("locations.spectator.x")) return loc("spectator", 0, 0, 0, 0);
        double ang = Math.toRadians(200 + rnd.nextInt(140));   // the north and side seats, facing the pit
        double r = 25.5;
        double x = CX + 0.5 + Math.cos(ang) * r, z = CZ + 0.5 + Math.sin(ang) * r;
        Location l = new Location(world, x, Y + 4 + (int) ((r - 16) / 2) + 1.0, z);
        l.setDirection(new org.bukkit.util.Vector(CX + 0.5 - x, -0.2, CZ + 0.5 - z));
        return l;
    }

    private boolean inColiseum(Location l) {
        if (l.getWorld() != world || l.getY() < Y - 40 || l.getY() > Y + 60) return false;
        double dx = l.getX() - CX, dz = l.getZ() - CZ;
        return Math.hypot(dx, dz) <= 58 || (Math.abs(dx) <= 48 && dz <= -28 && dz >= -86);
    }
    private boolean inPit(Location l) { return l.getWorld() == world && Math.hypot(l.getX() - CX, l.getZ() - CZ) < ColiseumBuilder.PIT_R + 3 && l.getY() < Y + 7; }

    // ------------------------------------------------------------------ ratings

    private String path(UUID u, String f) { return "players." + u + "." + f; }
    private double rating(UUID u) { return data.getDouble(path(u, "rating"), num("rating.start", 1000)); }
    private int games(UUID u) { return data.getInt(path(u, "wins")) + data.getInt(path(u, "losses")); }
    private boolean displayOn(UUID u) { return data.getBoolean(path(u, "display"), false); }

    private void refresh() {
        List<UUID> all = new ArrayList<>();
        var sec = data.getConfigurationSection("players");
        int min = cfg.getInt("rating.min-games-for-rank", 3);
        if (sec != null) for (String k : sec.getKeys(false)) { UUID u = UUID.fromString(k); if (games(u) >= min) all.add(u); }
        all.sort((x, y) -> Double.compare(rating(y), rating(x)));
        ranking = all;
        for (int i = 0; i < all.size(); i++) {
            int best = data.getInt(path(all.get(i), "best-rank"), 0);
            if (best == 0 || i + 1 < best) { data.set(path(all.get(i), "best-rank"), i + 1); dirty = true; }
        }
        tags.clear();
        for (UUID u : displayUsers()) tags.put(u, tagFor(u));
        for (Player p : Bukkit.getOnlinePlayers()) if (displayOn(p.getUniqueId())) tags.put(p.getUniqueId(), tagFor(p.getUniqueId()));
        updateHolograms();
    }

    private List<UUID> displayUsers() {
        List<UUID> out = new ArrayList<>();
        var sec = data.getConfigurationSection("players");
        if (sec != null) for (String k : sec.getKeys(false)) if (data.getBoolean("players." + k + ".display")) out.add(UUID.fromString(k));
        return out;
    }

    /** The Discord role a player earns from the ladder (top three only), or null. */
    String discordRole(Player p) {
        int r = rankOf(p.getUniqueId());
        return r >= 1 && r <= 3 ? titleOf(r) : null;
    }

    int rankOf(UUID u) { int i = ranking.indexOf(u); return i < 0 ? 0 : i + 1; }

    private static String titleOf(int rank) {
        return switch (rank) { case 1 -> "Grand Champion"; case 2 -> "Elite Gladiator"; case 3 -> "Veteran Champion"; default -> rank > 0 ? "Gladiator" : "Unranked"; };
    }

    private String tagFor(UUID u) {
        int r = rankOf(u);
        return switch (r) {
            case 0 -> "<gray>[UNRANKED]</gray>";
            case 1 -> "<gradient:#fff2a8:#ffb300><bold>♛ [GLADIATOR #1]</bold></gradient>";
            case 2 -> "<#e3e8ee><bold>[GLADIATOR #2]</bold>";
            case 3 -> "<#e0955a><bold>[GLADIATOR #3]</bold>";
            default -> "<#ffb347>[GLADIATOR #" + r + "]</#ffb347>";
        };
    }

    private String nameOf(UUID u) {
        Player p = Bukkit.getPlayer(u);
        return p != null ? p.getName() : data.getString(path(u, "name"), "Unknown");
    }

    private void remember(Player p) { if (!p.getName().equals(data.getString(path(p.getUniqueId(), "name")))) { data.set(path(p.getUniqueId(), "name"), p.getName()); dirty = true; } }

    private void saveIfDirty() {
        if (!dirty) return;
        dirty = false;
        try { data.save(dataFile); } catch (Exception e) { plugin.getLogger().warning("gladiator save: " + e); }
    }

    /** The rating change factor for two players who keep meeting: full at first, then less and less. */
    private double pairFactor(UUID a, UUID b) {
        String key = a.compareTo(b) < 0 ? a + "_" + b : b + "_" + a;
        long window = (long) (num("rating.pair-window-hours", 24) * 3600_000L), now = System.currentTimeMillis();
        List<Long> times = new ArrayList<>();
        for (Object o : data.getList("pairs." + key, List.of())) if (o instanceof Number n && now - n.longValue() < window) times.add(n.longValue());
        int full = cfg.getInt("rating.pair-full-value-duels", 2);
        List<Double> f = cfg.getDoubleList("rating.pair-factors");
        double factor = times.size() < full ? 1.0 : (times.size() - full < f.size() ? f.get(times.size() - full) : 0.05);
        times.add(now);
        data.set("pairs." + key, times);
        return factor;
    }

    // ------------------------------------------------------------------ challenges

    private void duelCommand(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Players only."); return; }
        if (a.length == 0) { playerMenu(p); return; }
        switch (a[0].toLowerCase()) {
            case "accept" -> accept(p);
            case "deny", "decline" -> deny(p);
            case "cancel" -> cancel(p);
            case "list", "pending" -> list(p);
            default -> {
                Player t = Bukkit.getPlayerExact(a[0]);
                if (t == null) p.sendMessage(Ui.mm("<red>No player called <white>" + a[0] + "<red> is online."));
                else challenge(p, t);
            }
        }
    }

    private String why(Player p) {
        if (p.getWorld() != world) return "the Colosseum duels run on Survival";
        if (duel != null && duel.has(p.getUniqueId())) return "you're already in a duel";
        return null;
    }

    void challenge(Player p, Player t) {
        String w = why(p);
        if (w != null) { p.sendMessage(Ui.mm("<red>You can't duel: " + w + ".")); return; }
        if (t.equals(p)) { p.sendMessage(Ui.mm("<red>You can't challenge yourself.")); return; }
        if (t.getWorld() != world || (duel != null && duel.has(t.getUniqueId()))) { p.sendMessage(Ui.mm("<red>" + t.getName() + " can't duel right now.")); return; }
        if (duel != null) { p.sendMessage(Ui.mm("<red>A duel is already underway - <white>/colosseum spectate<red> to watch, then challenge again.")); return; }
        if (outgoing.containsKey(p.getUniqueId())) { p.sendMessage(Ui.mm("<red>You already have a challenge out. <gray>/duel cancel to withdraw it.")); return; }
        if (incoming.containsKey(t.getUniqueId())) { p.sendMessage(Ui.mm("<red>" + t.getName() + " already has a challenge waiting.")); return; }
        int secs = cfg.getInt("duel.challenge-expire-seconds", 60);
        incoming.put(t.getUniqueId(), new Challenge(p.getUniqueId(), t.getUniqueId(), System.currentTimeMillis() + secs * 1000L));
        outgoing.put(p.getUniqueId(), t.getUniqueId());
        remember(p);
        p.sendMessage(Ui.mm("<#ffb347>⚔ <gray>Challenge sent to <white>" + t.getName() + "<gray>. It expires in " + secs + "s. <gray>(/duel cancel to withdraw)"));
        t.sendMessage(Ui.mm("<#ff6a00>━━━━━━━━━━━━━━━━━━━━"));
        t.sendMessage(Ui.mm("<#ffb347>⚔ <white><bold>" + p.getName() + "</bold> <gray>challenges you to a Colosseum duel! <dark_gray>(" + tagFor(p.getUniqueId()).replaceAll("<[^>]+>", "") + ")"));
        t.sendMessage(Ui.mm("  <click:run_command:'/duel accept'><green><bold>[ACCEPT]</bold></green></click>  <click:run_command:'/duel deny'><red><bold>[DENY]</bold></red></click>  <gray>Your own gear, no kits."));
        t.sendMessage(Ui.mm("<#ff6a00>━━━━━━━━━━━━━━━━━━━━"));
        t.playSound(t.getLocation(), Sound.BLOCK_ANVIL_USE, 0.6f, 1.2f);
        UUID from = p.getUniqueId(), to = t.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Challenge c = incoming.get(to);
            if (c != null && c.from().equals(from) && System.currentTimeMillis() >= c.expires()) {
                incoming.remove(to); outgoing.remove(from);
                Player f = Bukkit.getPlayer(from);
                if (f != null) f.sendMessage(Ui.mm("<gray>Your challenge to <white>" + nameOf(to) + "<gray> expired."));
            }
        }, 20L * secs + 5);
    }

    private void accept(Player p) {
        Challenge c = incoming.get(p.getUniqueId());
        if (c == null || System.currentTimeMillis() > c.expires()) { incoming.remove(p.getUniqueId()); p.sendMessage(Ui.mm("<red>You have no challenge to accept.")); return; }
        Player f = Bukkit.getPlayer(c.from());
        incoming.remove(p.getUniqueId());
        outgoing.remove(c.from());
        if (f == null || f.getWorld() != world) { p.sendMessage(Ui.mm("<red>Your challenger is no longer here.")); return; }
        if (duel != null) { p.sendMessage(Ui.mm("<red>The arena is in use. Try again in a moment.")); f.sendMessage(Ui.mm("<red>The arena is in use. Challenge again shortly.")); return; }
        f.sendMessage(Ui.mm("<green>" + p.getName() + " accepted your challenge!"));
        start(f, p);
    }

    private void deny(Player p) {
        Challenge c = incoming.remove(p.getUniqueId());
        if (c == null) { p.sendMessage(Ui.mm("<red>You have no challenge to deny.")); return; }
        outgoing.remove(c.from());
        p.sendMessage(Ui.mm("<gray>Challenge declined."));
        Player f = Bukkit.getPlayer(c.from());
        if (f != null) f.sendMessage(Ui.mm("<red>" + p.getName() + " declined your challenge."));
    }

    private void cancel(Player p) {
        UUID t = outgoing.remove(p.getUniqueId());
        if (t == null) { p.sendMessage(Ui.mm("<red>You have no challenge out.")); return; }
        incoming.remove(t);
        p.sendMessage(Ui.mm("<gray>Challenge withdrawn."));
        Player tp = Bukkit.getPlayer(t);
        if (tp != null) tp.sendMessage(Ui.mm("<gray>" + p.getName() + " withdrew their challenge."));
    }

    private void list(Player p) {
        Challenge in = incoming.get(p.getUniqueId());
        UUID out = outgoing.get(p.getUniqueId());
        if (in == null && out == null) { p.sendMessage(Ui.mm("<gray>No pending challenges. <white>/duel <player><gray> to start one.")); return; }
        if (in != null) p.sendMessage(Ui.mm("<#ffb347>Incoming: <white>" + nameOf(in.from()) + " <click:run_command:'/duel accept'><green>[ACCEPT]</green></click> <click:run_command:'/duel deny'><red>[DENY]</red></click>"));
        if (out != null) p.sendMessage(Ui.mm("<#ffb347>Outgoing: <white>" + nameOf(out) + " <click:run_command:'/duel cancel'><gray>[CANCEL]</gray></click>"));
    }

    // ------------------------------------------------------------------ snapshots (the safety net)

    private File snapFile(UUID u) { return new File(snapDir, u + ".yml"); }

    private void snapshot(Player p) {
        YamlConfiguration y = new YamlConfiguration();
        var inv = p.getInventory();
        ItemStack[] all = new ItemStack[41];
        System.arraycopy(inv.getStorageContents(), 0, all, 0, 36);
        System.arraycopy(inv.getArmorContents(), 0, all, 36, 4);
        all[40] = inv.getItemInOffHand();
        y.set("items", Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(all)));
        y.set("level", p.getLevel());
        y.set("exp", (double) p.getExp());
        y.set("health", p.getHealth());
        y.set("food", p.getFoodLevel());
        y.set("sat", (double) p.getSaturation());
        y.set("fire", p.getFireTicks());
        y.set("effects", new ArrayList<>(p.getActivePotionEffects()));
        y.set("loc", p.getLocation());
        y.set("gm", p.getGameMode().name());
        y.set("flight", p.getAllowFlight());
        try { y.save(snapFile(p.getUniqueId())); } catch (Exception e) { plugin.getLogger().severe("could not save duel snapshot for " + p.getName() + ": " + e); }
    }

    /** Puts a fighter back exactly as they were. Returns false if there was nothing saved. */
    private boolean restore(Player p) {
        File f = snapFile(p.getUniqueId());
        if (!f.exists()) return false;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        try {
            ItemStack[] all = ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(y.getString("items", "")));
            ItemStack[] storage = new ItemStack[36], armor = new ItemStack[4];
            System.arraycopy(all, 0, storage, 0, 36);
            System.arraycopy(all, 36, armor, 0, 4);
            var inv = p.getInventory();
            inv.setStorageContents(storage);
            inv.setArmorContents(armor);
            inv.setItemInOffHand(all.length > 40 ? all[40] : null);
            p.setLevel(y.getInt("level"));
            p.setExp((float) y.getDouble("exp"));
            double max = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue();
            p.setHealth(Math.max(0.5, Math.min(max, y.getDouble("health", max))));
            p.setFoodLevel(y.getInt("food", 20));
            p.setSaturation((float) y.getDouble("sat", 5));
            p.setFireTicks(y.getInt("fire", 0));
            for (PotionEffect e : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(e.getType());
            for (Object o : y.getList("effects", List.of())) if (o instanceof PotionEffect e) p.addPotionEffect(e);
            p.setGameMode(GameMode.valueOf(y.getString("gm", "SURVIVAL")));
            p.setAllowFlight(y.getBoolean("flight", false));
            Location back = y.getLocation("loc");
            p.setFallDistance(0);
            p.teleport(back != null ? back : exitLoc());
        } catch (Exception e) {
            plugin.getLogger().severe("duel restore failed for " + p.getName() + " (snapshot kept in " + f.getName() + "): " + e);
            return false;
        }
        f.delete();
        return true;
    }

    // ------------------------------------------------------------------ the duel

    private void start(Player a, Player b) {
        Duel d = new Duel(a, b);
        duel = d;
        remember(a); remember(b);
        for (Player p : List.of(a, b)) {
            snapshot(p);
            p.closeInventory();
            p.setGameMode(GameMode.SURVIVAL);
            p.setAllowFlight(false);
            p.setFallDistance(0);
            p.setFireTicks(0);
            if (cfg.getBoolean("duel.full-health-at-start", true)) {
                p.setHealth(p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue());
                p.setFoodLevel(20); p.setSaturation(20f);
            }
            for (PotionEffect e : new ArrayList<>(p.getActivePotionEffects())) p.removePotionEffect(e.getType());
        }
        a.teleport(fighter(1));
        b.teleport(fighter(2));
        if (cfg.getBoolean("announcements.duel-start", true)) {
            String ra = tagFor(a.getUniqueId()).replaceAll("<[^>]+>", ""), rb = tagFor(b.getUniqueId()).replaceAll("<[^>]+>", "");
            Bukkit.broadcast(Ui.mm("<#ff6a00>━━━━━━━━━━━━━━━━━━━━"));
            Bukkit.broadcast(Ui.mm("<#ffb347><bold>⚔ BLACKFORGEMC COLOSSEUM ⚔</bold>"));
            Bukkit.broadcast(Ui.mm(""));
            Bukkit.broadcast(Ui.mm("<white><bold>A NEW GLADIATOR BATTLE IS BEGINNING!</bold>"));
            Bukkit.broadcast(Ui.mm("<#ffd27a>⚔ <white>" + a.getName() + " <dark_gray>" + ra + " <red><bold>VS</bold> <white>" + b.getName() + " <dark_gray>" + rb));
            Bukkit.broadcast(Ui.mm(""));
            Bukkit.broadcast(Ui.mm("<gray>The arena is open to spectators!"));
            Bukkit.broadcast(Ui.mm("<gray>Type <click:run_command:'/colosseum spectate'><#ffd27a><bold>/colosseum</bold></click> <gray>to teleport to the arena and watch! <click:run_command:'/colosseum spectate'><green>[CLICK TO WATCH]</green></click>"));
            Bukkit.broadcast(Ui.mm("<#ff6a00>━━━━━━━━━━━━━━━━━━━━"));
        }
        int secs = cfg.getInt("duel.countdown-seconds", 5);
        int[] left = {secs};
        d.countdown = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Player pa = Bukkit.getPlayer(d.a), pb = Bukkit.getPlayer(d.b);
            if (pa == null || pb == null || d.phase == 2) { d.countdown.cancel(); return; }
            if (left[0] > 0) {
                for (Player p : List.of(pa, pb)) {
                    p.showTitle(Title.title(Ui.mm("<#ffb347><bold>" + left[0]), Ui.mm("<gray>vs <white>" + (p == pa ? d.nameB : d.nameA)), Title.Times.times(java.time.Duration.ZERO, java.time.Duration.ofMillis(900), java.time.Duration.ofMillis(100))));
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 0.8f + 0.1f * (secs - left[0]));
                }
                left[0]--;
                return;
            }
            d.countdown.cancel();
            d.phase = 1;
            d.fightStart = System.currentTimeMillis();
            for (Player p : List.of(pa, pb)) {
                p.showTitle(Title.title(Ui.mm("<red><bold>FIGHT!"), Ui.mm(""), Title.Times.times(java.time.Duration.ZERO, java.time.Duration.ofMillis(900), java.time.Duration.ofMillis(200))));
                p.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 0.8f, 1.3f);
            }
            int limit = cfg.getInt("duel.match-timeout-seconds", 300);
            d.timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> finish(d, null, null, "time ran out"), 20L * limit);
        }, 20L, 20L);
        d.monitor = Bukkit.getScheduler().runTaskTimer(plugin, () -> watch(d), 20L, 10L);
    }

    /** Boundary and presence checks while a duel is on. */
    private void watch(Duel d) {
        if (d.phase == 2) return;
        double radius = num("duel.arena-radius", 16.5);
        for (UUID u : List.of(d.a, d.b)) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) { finish(d, d.other(u), u, "disconnected"); return; }
            Location l = p.getLocation();
            if (l.getWorld() != world || Math.hypot(l.getX() - CX, l.getZ() - CZ) > radius || l.getY() < Y - 6 || l.getY() > Y + 30) {
                if (d.phase == 1) { finish(d, d.other(u), u, "left the arena"); return; }
                p.teleport(fighter(u.equals(d.a) ? 1 : 2));
            }
        }
    }

    private void finish(Duel d, UUID winner, UUID loser, String reason) {
        if (d.phase == 2) return;
        d.phase = 2;
        if (d.countdown != null) d.countdown.cancel();
        if (d.monitor != null) d.monitor.cancel();
        if (d.timeout != null) d.timeout.cancel();
        duel = null;
        for (Location l : d.placed) l.getBlock().setType(Material.AIR, false);
        String wn = winner == null ? null : nameOf(winner), ln = loser == null ? null : nameOf(loser);
        // ratings
        double dW = 0, dL = 0, wBefore = 0, lBefore = 0;
        boolean upset = false;
        if (winner != null) {
            wBefore = rating(winner); lBefore = rating(loser);
            double expW = 1 / (1 + Math.pow(10, (lBefore - wBefore) / 400.0)), expL = 1 - expW;
            int newGames = cfg.getInt("rating.new-player-games", 10);
            double kW = games(winner) < newGames ? num("rating.k-factor-new", 48) : num("rating.k-factor", 32);
            double kL = games(loser) < newGames ? num("rating.k-factor-new", 48) : num("rating.k-factor", 32);
            double factor = pairFactor(winner, loser);
            dW = kW * (1 - expW) * factor;
            dL = -kL * expL * factor;
            double floor = num("rating.floor", 100);
            if (lBefore + dL < floor) dL = floor - lBefore;
            upset = lBefore - wBefore >= num("rating.upset-rating-gap", 100);
            data.set(path(winner, "rating"), wBefore + dW);
            data.set(path(loser, "rating"), lBefore + dL);
            data.set(path(winner, "wins"), data.getInt(path(winner, "wins")) + 1);
            data.set(path(loser, "losses"), data.getInt(path(loser, "losses")) + 1);
            int streak = data.getInt(path(winner, "streak")) + 1;
            data.set(path(winner, "streak"), streak);
            data.set(path(winner, "best-streak"), Math.max(streak, data.getInt(path(winner, "best-streak"))));
            data.set(path(loser, "streak"), 0);
            data.set(path(winner, "peak"), Math.max(wBefore + dW, data.getDouble(path(winner, "peak"), 0)));
            history(winner, "<green>W <white>vs " + ln + " <gray>(" + signed(dW) + ")");
            history(loser, "<red>L <white>vs " + wn + " <gray>(" + signed(dL) + ")");
            dirty = true;
            logMatch(wn + " beat " + ln + " (" + reason + "): " + String.format("%.1f→%.1f, %.1f→%.1f", wBefore, wBefore + dW, lBefore, lBefore + dL));
        } else logMatch(d.nameA + " vs " + d.nameB + ": no result (" + reason + ")");
        // put everyone back
        for (UUID u : List.of(d.a, d.b)) { Player p = Bukkit.getPlayer(u); if (p != null) restore(p); }
        refresh();
        saveIfDirty();
        if (winner == null) {
            Bukkit.broadcast(Ui.mm("<#ffb347>⚔ <gray>The duel between <white>" + d.nameA + "<gray> and <white>" + d.nameB + "<gray> ended without a winner (" + reason + "). No rating changes."));
            return;
        }
        for (UUID u : List.of(winner, loser)) { Player p = Bukkit.getPlayer(u); if (p != null) p.sendMessage(Ui.mm("<#ffb347>⚔ <gray>" + (u.equals(winner) ? "You won" : "You lost") + ": <white>" + (u.equals(winner) ? signed(dW) : signed(dL)) + " <gray>rating, now <white>" + Math.round(rating(u)) + " <gray>(" + tagFor(u).replaceAll("<[^>]+>", "") + ")")); }
        if (cfg.getBoolean("announcements.duel-end", true)) {
            Bukkit.broadcast(Ui.mm("<#ffb347><bold>⚔ VICTORY!</bold>"));
            Bukkit.broadcast(Ui.mm("<white>" + wn + " <gray>has defeated <white>" + ln + " <gray>in the BlackForgeMC Colosseum!" + (reason.equals("disconnected") || reason.equals("left the arena") ? " <dark_gray>(" + reason + ")" : "")));
        }
        if (upset && cfg.getBoolean("announcements.upset", true)) {
            Bukkit.broadcast(Ui.mm("<#ff5a1a><bold>🔥 UPSET VICTORY!</bold>"));
            Bukkit.broadcast(Ui.mm("<gray>A lower-ranked gladiator has defeated a higher-ranked opponent!"));
        }
        for (String cmd : cfg.getStringList("rewards.win")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", wn));
        checkChampion();
    }

    private void history(UUID u, String line) {
        List<String> h = new ArrayList<>(data.getStringList(path(u, "last5")));
        h.add(0, line);
        data.set(path(u, "last5"), new ArrayList<>(h.subList(0, Math.min(5, h.size()))));
    }

    private static String signed(double v) { return (v >= 0 ? "+" : "") + String.format("%.1f", v); }

    private void logMatch(String line) {
        plugin.getLogger().info("[Colosseum] " + line);
        try (var w = new java.io.FileWriter(matchLog, true)) { w.write(new Date() + " " + line + System.lineSeparator()); } catch (Exception ignored) { }
    }

    private void checkChampion() {
        if (ranking.isEmpty()) return;
        UUID top = ranking.get(0);
        if (top.equals(champion)) return;
        champion = top;
        data.set("champion", top.toString());
        dirty = true;
        saveIfDirty();
        String n = nameOf(top);
        if (cfg.getBoolean("announcements.new-champion", true)) {
            Bukkit.broadcast(Ui.mm("<gradient:#fff2a8:#ffb300><bold>🏆 A NEW CHAMPION HAS RISEN!</bold></gradient>"));
            Bukkit.broadcast(Ui.mm("<white>" + n + " <gray>has claimed <#ffd27a><bold>GLADIATOR RANK #1</bold><gray>!"));
        }
        for (String cmd : cfg.getStringList("rewards.champion-claimed")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", n));
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
    }

    // ------------------------------------------------------------------ /colosseum

    private void colosseumCommand(CommandSender s, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase() : "";
        if (List.of("setspawn", "setarena", "setexit", "setlobby", "setspectator", "reload", "forcestop").contains(sub)) { staff(s, sub, a); return; }
        if (!(s instanceof Player p)) { s.sendMessage("Players only."); return; }
        if (p.getWorld() != world) { p.sendMessage(Ui.mm("<red>The Colosseum is on Survival.")); return; }
        if (duel != null && duel.has(p.getUniqueId())) { p.sendMessage(Ui.mm("<red>You're in a duel.")); return; }
        switch (sub) {
            case "spectate", "watch" -> {
                p.teleport(seat());
                p.sendMessage(Ui.mm(duel == null ? "<gray>No duel is on right now - take a seat. <white>/colosseum leave<gray> to go back." : "<#ffb347>⚔ <gray>Watching <white>" + duel.nameA + " <red>vs <white>" + duel.nameB + "<gray>. Spectators can't touch the fight."));
            }
            case "leave", "exit" -> { p.teleport(exitLoc()); p.sendMessage(Ui.mm("<gray>You left the Colosseum.")); }
            default -> {
                p.teleport(lobby());
                p.sendMessage(Ui.mm("<#ffb347>⚔ <white>Welcome to the BlackForge Colosseum! <gray>/duel <player> to fight • /colosseum spectate to watch • /gladiator for rankings"));
                if (duel != null) p.sendMessage(Ui.mm("<#ff6a00>A duel is on now: <white>" + duel.nameA + " <red>vs <white>" + duel.nameB));
            }
        }
    }

    private void staff(CommandSender s, String sub, String[] a) {
        if (!s.hasPermission("blackforge.admin")) { s.sendMessage(Ui.mm("<red>Staff only.")); return; }
        switch (sub) {
            case "reload" -> { saveIfDirty(); reload(); refresh(); s.sendMessage(Ui.mm("<green>Colosseum reloaded.")); }
            case "forcestop" -> {
                if (duel == null) { s.sendMessage(Ui.mm("<gray>No duel is running.")); return; }
                finish(duel, null, null, "stopped by staff");
                s.sendMessage(Ui.mm("<green>Duel stopped; both fighters were restored."));
            }
            default -> {
                if (!(s instanceof Player p)) { s.sendMessage("Players only."); return; }
                String key = switch (sub) {
                    case "setspawn" -> (a.length > 1 && a[1].equals("2")) ? "spawn2" : "spawn1";
                    case "setarena" -> "arena";
                    case "setexit" -> "exit";
                    case "setlobby" -> "lobby";
                    default -> "spectator";
                };
                Location l = p.getLocation();
                String b = "locations." + key + ".";
                cfg.set(b + "x", l.getX()); cfg.set(b + "y", l.getY()); cfg.set(b + "z", l.getZ()); cfg.set(b + "yaw", (double) l.getYaw()); cfg.set(b + "pitch", (double) l.getPitch());
                try { cfg.save(cfgFile); } catch (Exception e) { s.sendMessage(Ui.mm("<red>Could not save: " + e.getMessage())); return; }
                p.sendMessage(Ui.mm("<green>Colosseum " + key + " set here." + (sub.equals("setspawn") ? " <gray>(/colosseum setspawn 2 for the second fighter)" : "")));
            }
        }
    }

    // ------------------------------------------------------------------ /gladiator

    private void gladiatorCommand(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Players only."); return; }
        remember(p);
        String sub = a.length > 0 ? a[0].toLowerCase() : "";
        switch (sub) {
            case "stats" -> {
                UUID u = p.getUniqueId();
                int w = data.getInt(path(u, "wins")), l = data.getInt(path(u, "losses"));
                p.sendMessage(Ui.mm("<#ffb347><bold>⚔ " + p.getName() + "</bold> <dark_gray>" + tagFor(u).replaceAll("<[^>]+>", "")));
                p.sendMessage(Ui.mm("<gray>Rating <white>" + Math.round(rating(u)) + " <gray>(peak <white>" + Math.round(data.getDouble(path(u, "peak"), rating(u))) + "<gray>) • Wins <white>" + w + " <gray>• Losses <white>" + l + " <gray>• Win rate <white>" + (w + l == 0 ? "-" : Math.round(100.0 * w / (w + l)) + "%")));
                p.sendMessage(Ui.mm("<gray>Streak <white>" + data.getInt(path(u, "streak")) + " <gray>• Best streak <white>" + data.getInt(path(u, "best-streak")) + " <gray>• Best rank <white>" + (data.getInt(path(u, "best-rank")) == 0 ? "-" : "#" + data.getInt(path(u, "best-rank")))));
            }
            case "rank" -> {
                int r = rankOf(p.getUniqueId());
                p.sendMessage(Ui.mm(r == 0 ? "<gray>You're <white>UNRANKED<gray> - finish " + cfg.getInt("rating.min-games-for-rank", 3) + " duels to enter the ladder." : "<#ffb347>⚔ <gray>You are <white>Gladiator #" + r + " <gray>- <#ffd27a>" + titleOf(r) + " <gray>(" + Math.round(rating(p.getUniqueId())) + ")"));
            }
            case "leaderboard", "top", "lb" -> leaderboardChat(p);
            case "toggle" -> {
                if (!cfg.getBoolean("display.allow-toggle", true)) { p.sendMessage(Ui.mm("<red>Rank display is switched off.")); return; }
                boolean on = !displayOn(p.getUniqueId());
                data.set(path(p.getUniqueId(), "display"), on);
                dirty = true;
                tags.remove(p.getUniqueId());
                if (on) tags.put(p.getUniqueId(), tagFor(p.getUniqueId()));
                p.sendMessage(Ui.mm(on ? "<green>Your Gladiator rank is now shown in chat: " + tagFor(p.getUniqueId()) : "<gray>Your Gladiator rank is hidden."));
            }
            default -> profileMenu(p, p.getUniqueId());
        }
    }

    private void leaderboardChat(Player p) {
        p.sendMessage(Ui.mm("<#ffb347><bold>⚔ GLADIATOR RANKINGS</bold>"));
        if (ranking.isEmpty()) { p.sendMessage(Ui.mm("<gray>No ranked gladiators yet. <white>/duel <player>")); return; }
        for (int i = 0; i < Math.min(10, ranking.size()); i++) p.sendMessage(Ui.mm(boardLine(i, ranking.get(i))));
    }

    private String boardLine(int i, UUID u) {
        int w = data.getInt(path(u, "wins")), l = data.getInt(path(u, "losses"));
        String c = i == 0 ? "<#ffd27a>" : i == 1 ? "<#e3e8ee>" : i == 2 ? "<#e0955a>" : "<gray>";
        return c + "#" + (i + 1) + (i == 0 ? " ♛" : "") + " <white>" + nameOf(u) + " " + c + Math.round(rating(u)) + " <dark_gray>· <gray>" + w + "W " + (w + l == 0 ? 0 : Math.round(100.0 * w / (w + l))) + "%";
    }

    // ------------------------------------------------------------------ menus

    private ItemStack head(UUID u, String name, String... lore) {
        ItemStack it = new ItemStack(Material.PLAYER_HEAD);
        it.editMeta(SkullMeta.class, m -> {
            m.setOwningPlayer(Bukkit.getOfflinePlayer(u));
            m.displayName(Ui.mm(name));
            m.lore(Arrays.stream(lore).map(Ui::mm).toList());
        });
        return it;
    }

    private void open(Player p, Menu type, UUID target, int size, String title, java.util.function.Consumer<Inventory> fill) {
        MenuHolder h = new MenuHolder(type, target);
        Inventory inv = Bukkit.createInventory(h, size, Ui.mm(title));
        h.inv = inv;
        ItemStack pane = Ui.item(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < size; i++) inv.setItem(i, pane);
        fill.accept(inv);
        p.openInventory(inv);
    }

    private void playerMenu(Player p) {
        List<Player> others = Bukkit.getOnlinePlayers().stream().filter(o -> !o.equals(p) && o.getWorld() == world).map(o -> (Player) o).limit(45).toList();
        open(p, Menu.PLAYERS, null, 54, "<dark_gray>⚔ Challenge a Gladiator", inv -> {
            int i = 0;
            for (Player o : others) inv.setItem(i++, head(o.getUniqueId(), "<white>" + o.getName(), "<gray>" + tagFor(o.getUniqueId()).replaceAll("<[^>]+>", ""), "<gray>Rating <white>" + Math.round(rating(o.getUniqueId())), "", "<yellow>» Click to challenge"));
            if (others.isEmpty()) inv.setItem(22, Ui.item(Material.BARRIER, "<red>No one else is online on Survival"));
            inv.setItem(49, Ui.item(Material.PAPER, "<#ffb347>Pending challenges", "<gray>/duel list"));
            inv.setItem(53, Ui.item(Material.NETHER_STAR, "<#ffd27a>Your profile", "<yellow>» Click"));
        });
    }

    private void confirmMenu(Player p, UUID target) {
        open(p, Menu.CONFIRM, target, 27, "<dark_gray>Duel Challenge", inv -> {
            inv.setItem(11, head(target, "<#ffd27a><bold>" + nameOf(target), "<gray>Challenge Player", "<gray>" + tagFor(target).replaceAll("<[^>]+>", ""), "<gray>Rating <white>" + Math.round(rating(target))));
            inv.setItem(15, Ui.item(Material.IRON_SWORD, "<#ffb347>Your gear decides it", "<gray>No kits. What you wear and carry", "<gray>is what you fight with - and it's", "<gray>restored exactly afterwards."));
            inv.setItem(20, Ui.item(Material.LIME_CONCRETE, "<green><bold>Send Challenge", "<gray>Challenge " + nameOf(target)));
            inv.setItem(24, Ui.item(Material.RED_CONCRETE, "<red><bold>Cancel"));
            inv.setItem(22, Ui.item(Material.GOLD_INGOT, "<#ffd27a>Your Rank", "<white>" + tagFor(p.getUniqueId()).replaceAll("<[^>]+>", ""), "<gray>" + titleOf(rankOf(p.getUniqueId())) + " • " + Math.round(rating(p.getUniqueId()))));
        });
    }

    private void profileMenu(Player viewer, UUID u) {
        int w = data.getInt(path(u, "wins")), l = data.getInt(path(u, "losses")), r = rankOf(u);
        open(viewer, Menu.PROFILE, u, 36, "<dark_gray>⚔ Gladiator: " + nameOf(u), inv -> {
            inv.setItem(4, head(u, "<#ffd27a><bold>" + nameOf(u), "<gray>" + tagFor(u).replaceAll("<[^>]+>", ""), "<gray>" + titleOf(r)));
            inv.setItem(10, Ui.item(Material.EXPERIENCE_BOTTLE, "<#ffb347>Rating", "<white>" + Math.round(rating(u)), "<gray>Peak <white>" + Math.round(data.getDouble(path(u, "peak"), rating(u)))));
            inv.setItem(12, Ui.item(Material.DIAMOND_SWORD, "<green>Wins <white>" + w, "<gray>Win rate <white>" + (w + l == 0 ? "-" : Math.round(100.0 * w / (w + l)) + "%")));
            inv.setItem(14, Ui.item(Material.SKELETON_SKULL, "<red>Losses <white>" + l));
            inv.setItem(16, Ui.item(Material.BLAZE_POWDER, "<gold>Streak <white>" + data.getInt(path(u, "streak")), "<gray>Best streak <white>" + data.getInt(path(u, "best-streak"))));
            List<String> last = data.getStringList(path(u, "last5"));
            inv.setItem(22, Ui.item(Material.BOOK, "<#ffb347>Last five duels", last.isEmpty() ? new String[]{"<gray>None yet"} : last.toArray(String[]::new)));
            inv.setItem(30, Ui.item(Material.NETHER_STAR, "<#ffd27a>Leaderboard", "<yellow>» Click"));
            if (u.equals(viewer.getUniqueId()))
                inv.setItem(32, Ui.item(displayOn(u) ? Material.LIME_DYE : Material.GRAY_DYE, "<#ffb347>Show rank in chat: " + (displayOn(u) ? "<green>ON" : "<red>OFF"), "<yellow>» Click to toggle"));
        });
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof MenuHolder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        int slot = e.getRawSlot();
        switch (h.type) {
            case PLAYERS -> {
                if (slot == 53) { profileMenu(p, p.getUniqueId()); return; }
                ItemStack it = e.getCurrentItem();
                if (it != null && it.getType() == Material.PLAYER_HEAD && it.getItemMeta() instanceof SkullMeta m && m.getOwningPlayer() != null) confirmMenu(p, m.getOwningPlayer().getUniqueId());
            }
            case CONFIRM -> {
                if (slot == 20) { p.closeInventory(); Player t = Bukkit.getPlayer(h.target); if (t == null) p.sendMessage(Ui.mm("<red>That player left.")); else challenge(p, t); }
                else if (slot == 24) playerMenu(p);
            }
            case PROFILE -> {
                if (slot == 30) { p.closeInventory(); leaderboardChat(p); }
                else if (slot == 32 && h.target.equals(p.getUniqueId())) { gladiatorCommand(p, new String[]{"toggle"}); profileMenu(p, h.target); }
            }
        }
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof MenuHolder) e.setCancelled(true); }

    // ------------------------------------------------------------------ holograms

    private void updateHolograms() {
        if (!new File(plugin.getDataFolder(), "coliseum-v2-built").exists()) return;
        StringBuilder top = new StringBuilder("<#ffd27a><bold>🏆 GLADIATOR RANKINGS 🏆</bold><newline>");
        if (ranking.isEmpty()) top.append("<gray>No ranked gladiators yet.<newline><white>/duel <player> to climb!");
        for (int i = 0; i < Math.min(10, ranking.size()); i++) top.append(boardLine(i, ranking.get(i))).append("<newline>");
        String champ = ranking.isEmpty() ? "<gray>The throne is empty<newline><white>Who will claim it?" : "<#ffd27a><bold>♛ #1 GRAND CHAMPION</bold><newline><white><bold>" + nameOf(ranking.get(0)) + "</bold><newline><gray>" + Math.round(rating(ranking.get(0))) + " rating";
        String info = "<#ffb347><bold>⚔ HOW TO DUEL</bold><newline><white>/duel <player> <gray>- challenge<newline><white>/duel accept <gray>- fight<newline><white>/colosseum spectate <gray>- watch<newline><white>/gladiator <gray>- profile & rank<newline><white>/gladiator toggle <gray>- show rank<newline><gray>Your own gear. No kits.";
        if (holos.size() != 4 || holos.stream().anyMatch(t -> !t.isValid())) {
            holos.forEach(TextDisplay::remove);
            holos.clear();
            double fz = ColiseumBuilder.FACADE_Z - 2.4;
            holos.add(Ui.hologram(new Location(world, CX - 31 + 0.5, Y + 7.5, fz), top.toString(), 1.0f));
            holos.add(Ui.hologram(new Location(world, CX + 31 + 0.5, Y + 7.5, fz), info, 1.0f));
            holos.add(Ui.hologram(new Location(world, CX + 0.5, Y + ColiseumBuilder.PROM + 9.5, ColiseumBuilder.THRONE_Z - 1.5), champ, 1.6f));
            holos.add(Ui.hologram(new Location(world, CX + 0.5, Y + 5, CZ - 66), "<#ff6a00><bold>THE COLOSSEUM</bold><newline><gray>Enter, fight, rise", 1.4f));
            return;
        }
        Ui.setText(holos.get(0), top.toString());
        Ui.setText(holos.get(1), info);
        Ui.setText(holos.get(2), champ);
    }

    // ------------------------------------------------------------------ chat rank

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        if (!cfg.getBoolean("display.chat-prefix", true)) return;
        String tag = tags.get(e.getPlayer().getUniqueId());
        if (tag == null) return;
        var prev = e.renderer();
        e.renderer((source, name, msg, viewer) -> Ui.mm(tag + " ").append(prev.render(source, name, msg, viewer)));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (snapFile(p.getUniqueId()).exists() && (duel == null || !duel.has(p.getUniqueId()))) {   // the server stopped mid-duel: give everything back
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline() && restore(p)) p.sendMessage(Ui.mm("<#ffb347>⚔ <gray>Your duel was interrupted. Your gear and position have been restored."));
            }, 10L);
        }
        if (displayOn(p.getUniqueId())) tags.put(p.getUniqueId(), tagFor(p.getUniqueId()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        UUID u = p.getUniqueId();
        Challenge c = incoming.remove(u);
        if (c != null) outgoing.remove(c.from());
        UUID t = outgoing.remove(u);
        if (t != null) incoming.remove(t);
        if (duel != null && duel.has(u)) finish(duel, duel.other(u), u, "disconnected");
        tags.remove(u);
    }

    // ------------------------------------------------------------------ protecting the fight

    private boolean fighting(Entity en) { return en instanceof Player p && duel != null && duel.has(p.getUniqueId()); }
    private boolean fightOn() { return duel != null && duel.phase == 1; }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (fighting(p)) {
            if (duel.phase != 1) { e.setCancelled(true); return; }
            if (e instanceof EntityDamageByEntityEvent be) {
                Entity dmg = be.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Entity s ? s : be.getDamager();
                if (!(dmg instanceof Player ap) || !duel.has(ap.getUniqueId()) || ap.equals(p)) { e.setCancelled(true); return; }
            } else if (!rule("environmental-damage")) { e.setCancelled(true); return; }
            if (p.getHealth() - e.getFinalDamage() <= 0) {   // lethal: nobody dies, the duel is decided
                e.setCancelled(true);
                finish(duel, duel.other(p.getUniqueId()), p.getUniqueId(), "defeated");
            }
        } else if (inColiseum(p.getLocation())) e.setCancelled(true);   // spectators and visitors are untouchable
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHit(EntityDamageByEntityEvent e) {
        Entity victim = e.getEntity();
        Entity dmg = e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Entity s ? s : e.getDamager();
        if (!inColiseum(victim.getLocation()) && !inColiseum(dmg.getLocation())) return;
        if (fighting(victim) && fighting(dmg) && !victim.equals(dmg) && fightOn()) return;
        e.setCancelled(true);
    }

    @EventHandler
    public void onRegen(EntityRegainHealthEvent e) {
        if (fighting(e.getEntity()) && !rule("natural-regeneration") && (e.getRegainReason() == EntityRegainHealthEvent.RegainReason.SATIATED || e.getRegainReason() == EntityRegainHealthEvent.RegainReason.REGEN)) e.setCancelled(true);
    }

    @EventHandler
    public void onConsume(PlayerItemConsumeEvent e) {
        if (!fighting(e.getPlayer())) return;
        Material m = e.getItem().getType();
        if ((m == Material.GOLDEN_APPLE || m == Material.ENCHANTED_GOLDEN_APPLE) && !rule("golden-apples")) e.setCancelled(true);
        else if (!rule("consumables") && m != Material.GOLDEN_APPLE && m != Material.ENCHANTED_GOLDEN_APPLE) e.setCancelled(true);
    }

    @EventHandler
    public void onLaunch(ProjectileLaunchEvent e) {
        if (!(e.getEntity().getShooter() instanceof Player p)) return;
        if (!fighting(p)) { if (inColiseum(p.getLocation()) && !p.hasPermission("blackforge.admin")) e.setCancelled(true); return; }
        EntityType t = e.getEntityType();
        if (t == EntityType.ENDER_PEARL && !rule("ender-pearls")) e.setCancelled(true);
        else if ((t == EntityType.SPLASH_POTION || t == EntityType.LINGERING_POTION) && !rule("potions")) e.setCancelled(true);
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent e) {
        Player p = e.getPlayer();
        if (fighting(p)) {
            if (e.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN) return;   // our own moves
            Location to = e.getTo();
            if (to.getWorld() != world || Math.hypot(to.getX() - CX, to.getZ() - CZ) > num("duel.arena-radius", 16.5)) e.setCancelled(true);
        } else if (duel != null && inPit(e.getTo()) && !p.hasPermission("blackforge.admin") && e.getCause() != PlayerTeleportEvent.TeleportCause.PLUGIN) e.setCancelled(true);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        Location to = e.getTo();
        if (fighting(p) && duel.phase == 0 && (e.getFrom().getX() != to.getX() || e.getFrom().getZ() != to.getZ())) { e.setTo(e.getFrom()); return; }
        if (!e.hasChangedBlock()) return;
        if (to.getWorld() == world && to.getY() < Y - 30 && Math.hypot(to.getX() - CX, to.getZ() - (CZ - 30)) < 70 && !fighting(p)) { p.teleport(lobby()); return; }
        if (duel != null && !fighting(p) && inPit(to) && !p.hasPermission("blackforge.admin")) {   // outsiders keep out of the sand during a duel
            p.teleport(seat());
            p.sendMessage(Ui.mm("<red>The pit is closed during a duel."));
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (e.getAction() == Action.PHYSICAL) return;
        if (fighting(p)) {
            if (e.getClickedBlock() != null && e.getClickedBlock().getState() instanceof org.bukkit.inventory.InventoryHolder) e.setCancelled(true);   // no chests, furnaces, shulkers
            return;
        }
        if (!inColiseum(p.getLocation()) || p.hasPermission("blackforge.admin")) return;
        ItemStack it = e.getItem();
        if (it == null) return;
        String n = it.getType().name();
        if (n.endsWith("_BUCKET") || n.equals("FLINT_AND_STEEL") || n.equals("FIRE_CHARGE") || n.equals("ENDER_PEARL") || n.equals("CHORUS_FRUIT") || n.endsWith("_SPAWN_EGG") || n.equals("WIND_CHARGE") || n.equals("END_CRYSTAL")) e.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) { if (fighting(e.getPlayer())) e.setCancelled(true); }

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) { if (fighting(e.getEntity())) e.setCancelled(true); }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (!inColiseum(e.getBlock().getLocation())) return;
        if (fighting(e.getPlayer()) && rule("building") && inPit(e.getBlock().getLocation())) {
            if (duel.placed.stream().noneMatch(l -> l.equals(e.getBlock().getLocation()))) e.setCancelled(true);
            return;
        }
        if (!e.getPlayer().hasPermission("blackforge.admin") || e.getPlayer().getGameMode() != GameMode.CREATIVE) e.setCancelled(true);
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
        if (!inColiseum(e.getBlock().getLocation())) return;
        if (fighting(e.getPlayer()) && rule("building") && inPit(e.getBlock().getLocation())) { duel.placed.add(e.getBlock().getLocation()); return; }
        if (!e.getPlayer().hasPermission("blackforge.admin") || e.getPlayer().getGameMode() != GameMode.CREATIVE) e.setCancelled(true);
    }

    @EventHandler
    public void onExplode(EntityExplodeEvent e) { if (inColiseum(e.getLocation())) e.blockList().clear(); }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!fighting(p) || p.hasPermission("blackforge.admin")) return;
        String cmd = e.getMessage().substring(1).split(" ")[0].toLowerCase();
        if (cmd.contains(":")) cmd = cmd.substring(cmd.indexOf(':') + 1);
        if (!cfg.getStringList("duel.allowed-commands").contains(cmd)) { e.setCancelled(true); p.sendMessage(Ui.mm("<red>You can't use that command during a duel.")); }
    }

    @EventHandler
    public void onTotem(EntityResurrectEvent e) { if (fighting(e.getEntity()) && !rule("totems")) e.setCancelled(true); }

    // ------------------------------------------------------------------ lifecycle

    /** Called when the server stops: a running duel is ended without a result so everyone is restored first. */
    void shutdown() {
        if (duel != null) finish(duel, null, null, "server stopping");
        holos.forEach(TextDisplay::remove);
        saveIfDirty();
    }
}
