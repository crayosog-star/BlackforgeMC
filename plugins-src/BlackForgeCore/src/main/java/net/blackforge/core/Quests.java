package net.blackforge.core;

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
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.io.File;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * Daily and weekly quests for the whole network. Three daily quests and three weekly ones are picked from a pool (the same
 * three for everybody), and anything you do on any server counts: arcade wins, ore, mobs, fish, kills, KOTH, boss hits...
 * Quests pay Forge Dust, Season Points and Cosmetic Keys - cosmetic things only, nothing that makes anyone stronger.
 * <p>
 * Progress is stored as adds, not totals (shared/quests/&lt;uuid&gt;.yml), so servers side by side never overwrite each other.
 */
final class Quests implements Listener {
    /** What finishing something pays. */
    record Reward(int keys, int dust, int points) {
        String text() {
            List<String> l = new ArrayList<>();
            if (keys > 0) l.add("<gold>" + keys + " Key" + (keys == 1 ? "" : "s"));
            if (dust > 0) l.add("<#ffb347>" + dust + " Dust");
            if (points > 0) l.add("<aqua>" + points + " Season Pts");
            return String.join("<gray>, ", l);
        }

        void give(Cosmetics cos, Player p) {
            if (keys > 0) cos.giveKeys(p, keys);
            if (dust > 0) cos.addDust(p.getUniqueId(), dust);
            if (points > 0) cos.board().bonus(p, points);
        }
    }

    record Quest(String id, String text, String stat, int amount, Reward reward, Material icon) {}

    // dust per daily quest is small on purpose: keys come from the weekly set, so the store's keys keep their value
    static final List<Quest> DAILY = List.of(
            new Quest("d_arcade_win", "Win 2 Arcade games", "arcade_win", 2, new Reward(0, 20, 3), Material.FIREWORK_ROCKET),
            new Quest("d_arcade_play", "Play 3 Arcade matches", "arcade_play", 3, new Reward(0, 15, 2), Material.CLOCK),
            new Quest("d_ores", "Mine 40 ores", "ore", 40, new Reward(0, 15, 2), Material.IRON_PICKAXE),
            new Quest("d_mobs", "Defeat 30 hostile mobs", "mob", 30, new Reward(0, 15, 2), Material.IRON_SWORD),
            new Quest("d_fish", "Catch 6 fish", "fish", 6, new Reward(0, 15, 2), Material.FISHING_ROD),
            new Quest("d_kills", "Win 3 PvP fights", "kill", 3, new Reward(0, 20, 3), Material.NETHERITE_SWORD),
            new Quest("d_vote", "Vote for the server", "vote", 1, new Reward(0, 15, 2), Material.PAPER),
            new Quest("d_parkour", "Finish the hub parkour", "parkour", 1, new Reward(0, 15, 2), Material.LEATHER_BOOTS),
            new Quest("d_boss", "Hit the world boss", "boss", 1, new Reward(0, 25, 4), Material.WITHER_SKELETON_SKULL),
            new Quest("d_sell", "Sell something at the auction", "ah_sold", 1, new Reward(0, 15, 2), Material.GOLD_NUGGET),
            new Quest("d_crate", "Open any crate", "crate", 1, new Reward(0, 15, 2), Material.ENDER_CHEST));

    static final List<Quest> WEEKLY = List.of(
            new Quest("w_arcade_win", "Win 12 Arcade games", "arcade_win", 12, new Reward(1, 40, 10), Material.FIREWORK_STAR),
            new Quest("w_ores", "Mine 400 ores", "ore", 400, new Reward(1, 40, 10), Material.DIAMOND_PICKAXE),
            new Quest("w_mobs", "Defeat 250 hostile mobs", "mob", 250, new Reward(1, 40, 10), Material.DIAMOND_SWORD),
            new Quest("w_fish", "Catch 40 fish", "fish", 40, new Reward(1, 40, 10), Material.COD),
            new Quest("w_kills", "Win 15 PvP fights", "kill", 15, new Reward(1, 40, 12), Material.TRIDENT),
            new Quest("w_koth", "Win 2 King of the Hill rounds", "koth", 2, new Reward(1, 50, 14), Material.GOLDEN_HELMET),
            new Quest("w_boss", "Hit the world boss 3 times", "boss", 3, new Reward(1, 50, 14), Material.DRAGON_HEAD),
            new Quest("w_vote", "Vote on 5 different days", "vote", 5, new Reward(1, 40, 10), Material.WRITABLE_BOOK),
            new Quest("w_crates", "Open 5 crates", "crate", 5, new Reward(1, 40, 10), Material.CHEST),
            new Quest("w_arena", "Fight in the Colosseum 5 times", "arena", 5, new Reward(1, 50, 12), Material.SHIELD));

    static final Reward ALL_DAILY = new Reward(0, 30, 5);
    static final Reward ALL_WEEKLY = new Reward(2, 100, 25);

    static final ZoneId ZONE = ZoneId.systemDefault();

    private final BlackForgeCore plugin;
    private final Cosmetics cos;
    private final String mode;
    private final File dir;
    /** player -> (quest id -> count not yet written). */
    private final Map<UUID, Map<String, Integer>> pending = new HashMap<>();

    Quests(BlackForgeCore plugin, String mode, Cosmetics cos, File shared) {
        this.plugin = plugin;
        this.cos = cos;
        this.mode = mode;
        this.dir = new File(shared, "quests");
        dir.mkdirs();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Hooks.onStat((p, key, n, max) -> { if (!max) count(p, key, (int) Math.max(1, Math.min(n, 50))); });
        Hooks.onKill((killer, victim, where) -> count(killer, "kill", 1));
        Hooks.onArcadeEnd((p, game, won) -> { count(p, "arcade_play", 1); if (won) count(p, "arcade_win", 1); });
        Bukkit.getScheduler().runTaskTimer(plugin, this::flushAll, 400L, 400L);
        Bukkit.getCommandMap().register("blackforge", new Command("quests", "Daily and weekly quests", "/quests", List.of("quest", "daily", "dailies")) {
            @Override public boolean execute(CommandSender s, String label, String[] a) {
                if (s instanceof Player p) open(p); else s.sendMessage("Players only.");
                return true;
            }
        });
    }

    // ------------------------------------------------------------------ which quests are on

    static List<Quest> pick(List<Quest> pool, long seed, int n) {
        List<Quest> l = new ArrayList<>(pool);
        Collections.shuffle(l, new Random(seed));
        return List.copyOf(l.subList(0, Math.min(n, l.size())));
    }

    static String dayKey() { return LocalDate.now(ZONE).toString(); }

    static String weekKey() {
        LocalDate monday = LocalDate.now(ZONE).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return monday.toString();
    }

    static List<Quest> today() { return pick(DAILY, LocalDate.now(ZONE).toEpochDay() * 31L + 7, 3); }

    static List<Quest> thisWeek() { return pick(WEEKLY, LocalDate.now(ZONE).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay() * 17L + 3, 3); }

    private File file(UUID u) { return new File(dir, u + ".yml"); }

    // ------------------------------------------------------------------ counting

    private void count(Player p, String stat, int n) {
        if (p == null) return;
        Map<String, Integer> mine = null;
        for (Quest q : today()) if (q.stat.equals(stat)) { if (mine == null) mine = pending.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()); mine.merge("d:" + dayKey() + ":" + q.id, n, Integer::sum); }
        for (Quest q : thisWeek()) if (q.stat.equals(stat)) { if (mine == null) mine = pending.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()); mine.merge("w:" + weekKey() + ":" + q.id, n, Integer::sum); }
    }

    private void flushAll() { for (UUID u : new ArrayList<>(pending.keySet())) flush(u); }

    /** Write what's counted so far, and pay out anything that just finished. */
    void flush(UUID u) {
        Map<String, Integer> mine = pending.remove(u);
        if (mine == null || mine.isEmpty()) return;
        List<Quest> finished = new ArrayList<>();
        List<Boolean> allDay = new ArrayList<>(), allWeek = new ArrayList<>();
        Shared.update(file(u), y -> {
            for (Map.Entry<String, Integer> e : mine.entrySet()) {
                String k = e.getKey().replace(':', '.');
                y.set("progress." + k, y.getInt("progress." + k, 0) + e.getValue());
            }
            for (boolean weekly : new boolean[]{false, true}) {
                String key = weekly ? weekKey() : dayKey();
                List<Quest> set = weekly ? thisWeek() : today();
                for (Quest q : set) {
                    String path = "progress." + (weekly ? "w" : "d") + "." + key + "." + q.id;
                    String done = "done." + (weekly ? "w" : "d") + "." + key + "." + q.id;
                    if (y.getInt(path, 0) >= q.amount && !y.getBoolean(done)) { y.set(done, true); finished.add(q); }
                }
                boolean all = set.stream().allMatch(q -> y.getBoolean("done." + (weekly ? "w" : "d") + "." + key + "." + q.id));
                String bonus = "done." + (weekly ? "w" : "d") + "." + key + ".all";
                if (all && !y.getBoolean(bonus)) { y.set(bonus, true); (weekly ? allWeek : allDay).add(true); }
            }
        });
        Player p = Bukkit.getPlayer(u);
        if (p == null) return;
        for (Quest q : finished) {
            q.reward.give(cos, p);
            p.sendMessage(Ui.mm("<green>✔ <white>Quest complete: <gray>" + q.text + " <dark_gray>- " + q.reward.text()));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
            Hooks.stat(p, "quest", 1);
        }
        if (!allDay.isEmpty()) bonus(p, ALL_DAILY, "all three daily quests");
        if (!allWeek.isEmpty()) bonus(p, ALL_WEEKLY, "all three weekly quests");
    }

    private void bonus(Player p, Reward r, String what) {
        r.give(cos, p);
        p.sendMessage(Ui.mm("<gold>✦ <bold>Bonus!</bold> <gray>You finished " + what + ": " + r.text()));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
    }

    int progress(UUID u, Quest q, boolean weekly) {
        YamlConfiguration y = Shared.read(file(u));
        String path = "progress." + (weekly ? "w" : "d") + "." + (weekly ? weekKey() : dayKey()) + "." + q.id;
        int stored = y.getInt(path, 0);
        Map<String, Integer> mine = pending.get(u);
        if (mine != null) stored += mine.getOrDefault((weekly ? "w:" + weekKey() : "d:" + dayKey()) + ":" + q.id, 0);
        return Math.min(stored, q.amount);
    }

    // ------------------------------------------------------------------ tracking in the open worlds

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent e) {
        if (mode.equals("arcade") || mode.equals("hub") || !(e.getEntity() instanceof Enemy) || e.getEntity().getKiller() == null) return;
        count(e.getEntity().getKiller(), "mob", 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (mode.equals("arcade") || mode.equals("hub") || !e.getBlock().getType().name().endsWith("_ORE") || e.getPlayer().getGameMode() != org.bukkit.GameMode.SURVIVAL) return;
        count(e.getPlayer(), "ore", 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() == PlayerFishEvent.State.CAUGHT_FISH) count(e.getPlayer(), "fish", 1);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { flush(e.getPlayer().getUniqueId()); }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            long left = today().stream().filter(q -> progress(p.getUniqueId(), q, false) < q.amount).count();
            if (left > 0) p.sendMessage(Ui.mm("<yellow>✦ <gray>You have <white>" + left + " <gray>daily quest" + (left == 1 ? "" : "s") + " to do. <yellow>/quests"));
        }, 140L);
    }

    // ------------------------------------------------------------------ menu

    private static final class Menu implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    void open(Player p) {
        flush(p.getUniqueId());
        Menu m = new Menu();
        m.inv = Bukkit.createInventory(m, 36, Ui.mm("<dark_gray>Quests"));
        var pane = Ui.item(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 36; i++) m.inv.setItem(i, pane);
        long hours = java.time.Duration.between(java.time.LocalDateTime.now(ZONE), LocalDate.now(ZONE).plusDays(1).atStartOfDay()).toHours();
        long days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(ZONE), LocalDate.now(ZONE).with(TemporalAdjusters.next(DayOfWeek.MONDAY)));
        m.inv.setItem(0, Ui.item(Material.SUNFLOWER, "<yellow><bold>Daily quests", "<gray>New ones in <white>" + hours + "h", "", "<gray>All three: " + ALL_DAILY.text()));
        m.inv.setItem(9, Ui.item(Material.GOLD_BLOCK, "<gold><bold>Weekly quests", "<gray>New ones in <white>" + days + "d", "", "<gray>All three: " + ALL_WEEKLY.text()));
        fill(m, p, today(), false, 2, 1);
        fill(m, p, thisWeek(), true, 2, 10);
        m.inv.setItem(35, Ui.item(Material.OAK_DOOR, "<white>Close"));
        p.openInventory(m.inv);
    }

    private void fill(Menu m, Player p, List<Quest> set, boolean weekly, int step, int first) {
        int slot = first + 2;
        for (Quest q : set) {
            int have = progress(p.getUniqueId(), q, weekly);
            boolean done = have >= q.amount;
            var it = Ui.item(done ? Material.LIME_DYE : q.icon, (done ? "<green>✔ " : "<white>") + q.text,
                    "<gray>Progress: " + (done ? "<green>" : "<white>") + have + "<gray>/" + q.amount, "<gray>Reward: " + q.reward.text());
            if (done) it.editMeta(x -> x.setEnchantmentGlintOverride(true));
            m.inv.setItem(slot, it);
            slot += step;
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true); }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu)) return;
        e.setCancelled(true);
        if (e.getSlot() == 35 && e.getClickedInventory() == e.getView().getTopInventory()) e.getWhoClicked().closeInventory();
    }
}
