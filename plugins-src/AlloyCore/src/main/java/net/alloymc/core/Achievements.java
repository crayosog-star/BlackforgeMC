package net.alloymc.core;

import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.time.Duration;
import java.util.*;

/**
 * Achievements: one-time goals across the whole network, each paying money + Scrap once. /achievements shows them all
 * with progress. Unlocking 20 gives the Forge Master kill effect, all of them the Completionist victory effect.
 *
 * Counts come from {@link Hooks} (kills, arcade games, {@link Hooks#stat} calls from the game modes) and a few vanilla
 * events here (mining, building, fishing, mobs). Saved in shared/achievements/&lt;uuid&gt;.yml as running totals: each
 * server keeps only what it added since its last save and adds that on (never overwrites), so a server switch can't
 * lose progress.
 */
public final class Achievements implements Listener {

    public enum Cat { NETWORK, WORLD, ARCADE }

    /** {@code stat} reaches {@code goal}. */
    public record A(String id, Cat cat, String name, String desc, Material icon, String stat, long goal, double money, long scrap) {}

    public static final List<A> ALL = List.of(
            // network
            new A("tutorial", Cat.NETWORK, "First Steps", "Finish the hub tutorial", Material.MAP, "tutorial", 1, 500, 25),
            new A("arena", Cat.NETWORK, "Test Subject", "Visit the Arena in the hub", Material.TARGET, "arena", 1, 250, 10),
            new A("parkour", Cat.NETWORK, "Foundry Climber", "Finish the hub parkour", Material.FEATHER, "parkour", 1, 1000, 50),
            new A("play_5h", Cat.NETWORK, "Regular", "Play for 5 hours", Material.CLOCK, "playtime", 300, 1000, 50),
            new A("play_24h", Cat.NETWORK, "Dedicated", "Play for 24 hours", Material.CLOCK, "playtime", 1440, 3000, 150),
            new A("play_100h", Cat.NETWORK, "Lifer", "Play for 100 hours", Material.CLOCK, "playtime", 6000, 10000, 500),
            new A("calendar_7", Cat.NETWORK, "Week Streak", "Claim the daily calendar 7 days in a row", Material.CAKE, "calendar_streak", 7, 2000, 100),
            new A("friend_1", Cat.NETWORK, "Friendly", "Make a friend (/friend)", Material.POPPY, "friends", 1, 500, 25),
            new A("friend_5", Cat.NETWORK, "Popular", "Have 5 friends", Material.PINK_TULIP, "friends", 5, 2000, 100),
            new A("party", Cat.NETWORK, "Squad Up", "Join someone's party (/party)", Material.FIREWORK_ROCKET, "party", 1, 500, 25),
            new A("referral", Cat.NETWORK, "Recruiter", "Bring a friend to the server (/referral)", Material.EMERALD, "referrals", 1, 2500, 150),
            new A("discord", Cat.NETWORK, "Connected", "Link your Discord account (/link)", Material.AMETHYST_SHARD, "discord", 1, 1000, 50),
            new A("vote_1", Cat.NETWORK, "Voter", "Vote for the server (/vote)", Material.PAPER, "vote", 1, 250, 10),
            new A("vote_25", Cat.NETWORK, "Campaigner", "Vote 25 times", Material.PAPER, "vote", 25, 3000, 150),
            new A("vote_streak", Cat.NETWORK, "Loyal Voter", "Vote 7 days in a row", Material.WRITABLE_BOOK, "vote_streak", 7, 2500, 150),
            new A("event", Cat.NETWORK, "Festive", "Trade at a seasonal event shop", Material.JACK_O_LANTERN, "event_trade", 1, 500, 25),
            // open worlds
            new A("kill_1", Cat.WORLD, "First Blood", "Kill a player", Material.IRON_SWORD, "kills", 1, 500, 25),
            new A("kill_25", Cat.WORLD, "Hunter", "Kill 25 players", Material.DIAMOND_SWORD, "kills", 25, 2500, 150),
            new A("kill_100", Cat.WORLD, "Warlord", "Kill 100 players", Material.NETHERITE_SWORD, "kills", 100, 7500, 400),
            new A("mobs_100", Cat.WORLD, "Monster Slayer", "Kill 100 hostile mobs", Material.ZOMBIE_HEAD, "mobs", 100, 750, 40),
            new A("mobs_1000", Cat.WORLD, "Exterminator", "Kill 1,000 hostile mobs", Material.SKELETON_SKULL, "mobs", 1000, 4000, 200),
            new A("mine_1k", Cat.WORLD, "Miner", "Break 1,000 blocks", Material.IRON_PICKAXE, "mined", 1000, 750, 40),
            new A("mine_10k", Cat.WORLD, "Excavator", "Break 10,000 blocks", Material.DIAMOND_PICKAXE, "mined", 10000, 4000, 200),
            new A("build_1k", Cat.WORLD, "Builder", "Place 1,000 blocks", Material.BRICKS, "placed", 1000, 750, 40),
            new A("build_10k", Cat.WORLD, "Architect", "Place 10,000 blocks", Material.CHISELED_STONE_BRICKS, "placed", 10000, 4000, 200),
            new A("fish_50", Cat.WORLD, "Angler", "Catch 50 fish", Material.FISHING_ROD, "fish", 50, 1000, 50),
            new A("boss", Cat.WORLD, "Bossbreaker", "Help defeat a world boss", Material.WITHER_SKELETON_SKULL, "boss", 1, 3000, 200),
            new A("koth", Cat.WORLD, "King of the Hill", "Capture KOTH", Material.GOLDEN_HELMET, "koth", 1, 2500, 150),
            new A("crate_10", Cat.WORLD, "Unboxer", "Open 10 crates", Material.CHEST, "crate", 10, 1000, 50),
            new A("crate_100", Cat.WORLD, "Crate Fiend", "Open 100 crates", Material.ENDER_CHEST, "crate", 100, 5000, 250),
            new A("bounty", Cat.WORLD, "Bounty Hunter", "Claim a bounty (/bounty)", Material.NAME_TAG, "bounty", 1, 1500, 75),
            new A("ah_sell", Cat.WORLD, "Merchant", "Sell something on the auction house", Material.GOLD_NUGGET, "ah_sold", 1, 500, 25),
            new A("shop_sell", Cat.WORLD, "Shopkeeper", "Make 10 sales from your chest shops", Material.OAK_SIGN, "shop_sold", 10, 1500, 75),
            // arcade
            new A("arc_1", Cat.ARCADE, "Player One", "Play an Arcade game", Material.TNT, "arcade_games", 1, 250, 10),
            new A("arc_50", Cat.ARCADE, "Arcade Regular", "Play 50 Arcade games", Material.TNT, "arcade_games", 50, 2000, 100),
            new A("arc_250", Cat.ARCADE, "Arcade Legend", "Play 250 Arcade games", Material.TNT_MINECART, "arcade_games", 250, 7500, 400),
            new A("win_1", Cat.ARCADE, "Winner", "Win an Arcade game", Material.GOLD_INGOT, "arcade_wins", 1, 500, 25),
            new A("win_25", Cat.ARCADE, "Champion", "Win 25 Arcade games", Material.GOLD_BLOCK, "arcade_wins", 25, 3000, 150),
            new A("win_100", Cat.ARCADE, "Unstoppable", "Win 100 Arcade games", Material.BEACON, "arcade_wins", 100, 10000, 500),
            new A("variety", Cat.ARCADE, "Explorer", "Play 8 different Arcade games", Material.COMPASS, "arcade_variety", 8, 2000, 100),
            new A("arc_kills", Cat.ARCADE, "Gladiator", "Get 100 kills in the Arcade", Material.BOW, "arcade_kills", 100, 3000, 150));

    public static final int FORGE_MASTER_AT = 20;

    private final AlloyCore plugin;
    private final String mode;
    private final File dir;
    private final boolean openWorld;
    private final Map<UUID, Map<String, Long>> base = new HashMap<>(), delta = new HashMap<>(), best = new HashMap<>();
    private final Map<UUID, Set<String>> done = new HashMap<>();
    private final Map<UUID, Set<String>> games = new HashMap<>();

    public Achievements(AlloyCore plugin, String mode, File shared) {
        this.plugin = plugin;
        this.mode = mode;
        this.dir = new File(shared, "achievements");
        dir.mkdirs();
        this.openWorld = mode.equals("survival") || mode.equals("skyblock") || mode.equals("factions");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Hooks.onStat((p, key, n, max) -> { if (max) setMax(p, key, n); else add(p, key, n); });
        Hooks.onKill((killer, victim, where) -> add(killer, where.startsWith("arcade") ? "arcade_kills" : "kills", 1));
        Hooks.onArcadeEnd((p, game, won) -> {
            add(p, "arcade_games", 1);
            if (won) add(p, "arcade_wins", 1);
            Set<String> g = games.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());
            if (g.add(game)) setMax(p, "arcade_variety", g.size());
        });
        Bukkit.getScheduler().runTaskTimer(plugin, () -> { for (UUID id : new ArrayList<>(delta.keySet())) flush(id); }, 600L, 600L);
        for (Player p : Bukkit.getOnlinePlayers()) load(p);
        Bukkit.getCommandMap().register("alloy", new Command("achievements", "BlackForgeMC achievements", "/achievements", List.of("achievement", "ach", "goals")) {
            @Override public boolean execute(CommandSender s, String label, String[] a) {
                if (s instanceof Player p) open(p, Cat.NETWORK); else s.sendMessage("Players only.");
                return true;
            }
        });
    }

    // ------------------------------------------------------------------ storage

    private File file(UUID id) { return new File(dir, id + ".yml"); }

    private void load(Player p) {
        UUID id = p.getUniqueId();
        YamlConfiguration y = Shared.read(file(id));
        Map<String, Long> b = new HashMap<>();
        var sec = y.getConfigurationSection("stats");
        if (sec != null) for (String k : sec.getKeys(false)) b.put(k, sec.getLong(k));
        base.put(id, b);
        done.put(id, new HashSet<>(y.getStringList("done")));
        games.put(id, new HashSet<>(y.getStringList("games")));
    }

    /** Add this server's new progress onto the file (never overwrite another server's). */
    private void flush(UUID id) {
        Map<String, Long> d = delta.remove(id), m = best.remove(id);
        Set<String> dn = done.getOrDefault(id, Set.of()), g = games.getOrDefault(id, Set.of());
        Shared.update(file(id), y -> {
            if (d != null) d.forEach((k, v) -> y.set("stats." + k, y.getLong("stats." + k) + v));
            if (m != null) m.forEach((k, v) -> y.set("stats." + k, Math.max(v, y.getLong("stats." + k))));
            Set<String> all = new LinkedHashSet<>(y.getStringList("done")); all.addAll(dn); y.set("done", new ArrayList<>(all));
            Set<String> gs = new LinkedHashSet<>(y.getStringList("games")); gs.addAll(g); y.set("games", new ArrayList<>(gs));
        });
        Player p = Bukkit.getPlayer(id);
        if (p != null) load(p);
    }

    public void save() { for (UUID id : new ArrayList<>(base.keySet())) flush(id); }

    public long value(UUID id, String stat) {
        return base.getOrDefault(id, Map.of()).getOrDefault(stat, 0L) + delta.getOrDefault(id, Map.of()).getOrDefault(stat, 0L);
    }

    private void add(Player p, String stat, long n) {
        if (n <= 0 || !base.containsKey(p.getUniqueId())) return;
        delta.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()).merge(stat, n, Long::sum);
        check(p, stat);
    }

    private void setMax(Player p, String stat, long v) {
        UUID id = p.getUniqueId();
        if (!base.containsKey(id) || v <= value(id, stat)) return;
        best.computeIfAbsent(id, k -> new HashMap<>()).merge(stat, v, Math::max);
        base.get(id).put(stat, v - delta.getOrDefault(id, Map.of()).getOrDefault(stat, 0L));
        check(p, stat);
    }

    private void check(Player p, String stat) {
        UUID id = p.getUniqueId();
        Set<String> dn = done.computeIfAbsent(id, k -> new HashSet<>());
        boolean any = false;
        for (A a : ALL) {
            if (!a.stat.equals(stat) || dn.contains(a.id) || value(id, stat) < a.goal) continue;
            dn.add(a.id);
            any = true;
            plugin.economy().deposit(p, a.money);
            plugin.economy().addScrap(p, a.scrap);
            p.showTitle(Title.title(Ui.mm("<gold><bold>ACHIEVEMENT"), Ui.mm("<white>" + a.name),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(2500), Duration.ofMillis(600))));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
            p.sendMessage(Ui.mm("<gold>★ <gray>Achievement <white>" + a.name + " <dark_gray>(" + a.desc + ") <green>+" + NetEconomy.money(a.money)
                    + " <#c6a15b>+" + a.scrap + " Scrap <u><click:run_command:'/achievements'><gray>[" + dn.size() + "/" + ALL.size() + "]</click></u>"));
            if (dn.size() == FORGE_MASTER_AT) unlockCosmetic(p, "forgemaster", "Forge Master kill effect");
            if (dn.size() == ALL.size()) unlockCosmetic(p, "completionist", "Completionist victory effect");
        }
        if (any) flush(id);
    }

    private void unlockCosmetic(Player p, String id, String what) {
        plugin.cosmetics().unlock(p, id);
        p.sendMessage(Ui.mm("<gold>★ <gray>Unlocked the <gold>" + what + "<gray>! Equip it in <white>/cosmetics"));
        plugin.net().broadcastAll(p, Ui.mm("<gold>★ <white>" + Nicks.of(p) + " <gray>unlocked the <gold>" + what + " <gray>for their achievements!"));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        // a moment later: the server they came from saves on quit
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            load(p);
            // totals kept elsewhere, picked up on join
            YamlConfiguration j = Shared.read(new File(dir.getParentFile(), "journey/" + p.getUniqueId() + ".yml"));
            if (j.getInt("referrals") > 0) setMax(p, "referrals", j.getInt("referrals"));
            if (j.getLong("minutes") > 0) setMax(p, "playtime", j.getLong("minutes"));
            if (j.getInt("calendar-streak") > 0) setMax(p, "calendar_streak", j.getInt("calendar-streak"));
            YamlConfiguration v = Shared.read(new File(dir.getParentFile(), "votes/" + p.getUniqueId() + ".yml"));
            if (v.getInt("total") > 0) setMax(p, "vote", v.getInt("total"));
            if (v.getInt("best-streak") > 0) setMax(p, "vote_streak", v.getInt("best-streak"));
            if (DiscordLink.linkedAndPaid(dir.getParentFile(), p.getUniqueId())) setMax(p, "discord", 1);
        }, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        flush(id);
        base.remove(id); done.remove(id); games.remove(id);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { if (openWorld) add(e.getPlayer(), "mined", 1); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { if (openWorld) add(e.getPlayer(), "placed", 1); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMob(EntityDeathEvent e) {
        if (openWorld && e.getEntity() instanceof Enemy && e.getEntity().getKiller() != null) add(e.getEntity().getKiller(), "mobs", 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) { if (openWorld && e.getState() == PlayerFishEvent.State.CAUGHT_FISH) add(e.getPlayer(), "fish", 1); }

    // ------------------------------------------------------------------ menu

    private static final class Menu implements InventoryHolder {
        final Cat cat;
        Inventory inv;
        Menu(Cat cat) { this.cat = cat; }
        @Override public Inventory getInventory() { return inv; }
    }

    public void open(Player p, Cat cat) {
        UUID id = p.getUniqueId();
        if (!base.containsKey(id)) load(p);
        Set<String> dn = done.getOrDefault(id, Set.of());
        Menu m = new Menu(cat);
        m.inv = Bukkit.createInventory(m, 54, Ui.mm("<dark_gray>Achievements <gray>- " + dn.size() + "/" + ALL.size()));
        int slot = 0;
        for (A a : ALL) {
            if (a.cat != cat) continue;
            boolean got = dn.contains(a.id);
            long v = Math.min(a.goal, value(id, a.stat));
            int bars = (int) Math.floor(10.0 * v / a.goal);
            ItemStack it = Ui.item(got ? a.icon : Material.GRAY_DYE, (got ? "<gold>★ " : "<gray>") + a.name,
                    "<gray>" + a.desc,
                    got ? "<green>Unlocked" : "<gray>Progress: <white>" + String.format("%,d", v) + "<gray>/" + String.format("%,d", a.goal),
                    got ? "" : "<green>" + "■".repeat(bars) + "<dark_gray>" + "■".repeat(10 - bars),
                    "<gray>Reward: <green>" + NetEconomy.money(a.money) + " <gray>+ <#c6a15b>" + a.scrap + " Scrap");
            if (got) it.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
            m.inv.setItem(slot++, it);
        }
        String[][] tabs = {{"NETWORK", "COMPASS", "<#4fd1c5>Network"}, {"WORLD", "GRASS_BLOCK", "<#7bd88f>Open worlds"}, {"ARCADE", "TNT", "<#ffc53d>Arcade"}};
        for (int i = 0; i < tabs.length; i++) {
            Cat c = Cat.valueOf(tabs[i][0]);
            long have = ALL.stream().filter(a -> a.cat == c && dn.contains(a.id)).count(), all = ALL.stream().filter(a -> a.cat == c).count();
            ItemStack t = Ui.item(Material.valueOf(tabs[i][1]), tabs[i][2] + " <gray>(" + have + "/" + all + ")", c == cat ? "<green>Showing" : "<yellow>Click to show");
            if (c == cat) t.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
            m.inv.setItem(48 + i, t);
        }
        m.inv.setItem(53, Ui.item(Material.NETHER_STAR, "<gold>Bonus rewards",
                "<gray>" + FORGE_MASTER_AT + " achievements: <gold>Forge Master <gray>kill effect",
                "<gray>All " + ALL.size() + ": <gold>Completionist <gray>victory effect"));
        p.openInventory(m.inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu) || !(e.getWhoClicked() instanceof Player p)) return;
        e.setCancelled(true);
        int s = e.getRawSlot();
        if (s >= 48 && s <= 50) open(p, Cat.values()[s - 48]);
    }
}
