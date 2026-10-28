package net.alloymc.core;

import org.bukkit.*;
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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.io.File;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * Weekly challenges: three tasks per game mode, new every Monday, picked from a pool the same way for everyone.
 * Finishing one pays Scrap and Ingots straight away. /challenges shows progress. Survival, Skyblock and Factions
 * share the open-world pool; the Arcade has its own. Progress lives in the server's challenges.yml.
 */
public final class Challenges implements Listener {
    /** One kind of task. {@code amount} is what's needed, rewards are paid on completion. */
    public record Task(String id, String text, Material icon, int amount, int scrap, int money) {}

    private static final List<Task> WORLD = List.of(
            new Task("mobs", "Kill %d hostile mobs", Material.ZOMBIE_HEAD, 150, 120, 1500),
            new Task("mobs_big", "Kill %d hostile mobs", Material.WITHER_SKELETON_SKULL, 400, 300, 4000),
            new Task("ores", "Mine %d ores", Material.DIAMOND_ORE, 200, 120, 1500),
            new Task("fish", "Catch %d fish", Material.FISHING_ROD, 40, 100, 1200),
            new Task("pvp", "Kill %d players", Material.IRON_SWORD, 10, 200, 2500),
            new Task("build", "Place %d blocks", Material.BRICKS, 1500, 100, 1200),
            new Task("play", "Play for %d minutes", Material.CLOCK, 180, 150, 2000));
    private static final List<Task> ARCADE = List.of(
            new Task("a_games", "Play %d Arcade games", Material.FIREWORK_ROCKET, 15, 120, 1500),
            new Task("a_wins", "Win %d Arcade games", Material.GOLD_INGOT, 5, 200, 2500),
            new Task("a_kills", "Get %d kills in the Arcade", Material.IRON_SWORD, 30, 150, 2000),
            new Task("a_variety", "Play %d different Arcade games", Material.COMPASS, 5, 150, 1500),
            new Task("play", "Play for %d minutes", Material.CLOCK, 120, 120, 1500));

    private final AlloyCore plugin;
    private final boolean arcade;
    private final File file;
    private final YamlConfiguration data;
    private static final ZoneId ZONE = ZoneId.systemDefault();

    public Challenges(AlloyCore plugin, String mode) {
        this.plugin = plugin;
        this.arcade = mode.equals("arcade");
        this.file = new File(plugin.getDataFolder(), "challenges.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        for (String old : data.getKeys(false)) if (!old.equals(week())) data.set(old, null);   // last week's progress
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Hooks.onKill((killer, victim, where) -> progress(killer, where.startsWith("arcade") ? "a_kills" : "pvp", 1));
        Hooks.onArcadeEnd((p, game, won) -> {
            progress(p, "a_games", 1);
            if (won) progress(p, "a_wins", 1);
            String key = week() + "." + p.getUniqueId() + ".played_games";
            List<String> played = new ArrayList<>(data.getStringList(key));
            if (!played.contains(game)) { played.add(game); data.set(key, played); progress(p, "a_variety", 1); }
        });
        Bukkit.getScheduler().runTaskTimer(plugin, () -> { for (Player p : Bukkit.getOnlinePlayers()) progress(p, "play", 1); }, 1200L, 1200L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::save, 6000L, 6000L);
        Bukkit.getCommandMap().register("alloy", new Command("challenges", "BlackForgeMC weekly challenges", "/challenges", List.of("challenge", "weekly", "quests")) {
            @Override public boolean execute(CommandSender s, String label, String[] a) {
                if (s instanceof Player p) open(p); else s.sendMessage("Players only.");
                return true;
            }
        });
    }

    public void save() { try { data.save(file); } catch (Exception ignored) { } }

    private String week() {
        LocalDate d = LocalDate.now(ZONE);
        return d.get(IsoFields.WEEK_BASED_YEAR) + "-w" + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    /** This week's three tasks: the same for everyone, different every week. */
    public List<Task> thisWeek() {
        List<Task> pool = new ArrayList<>(arcade ? ARCADE : WORLD);
        Collections.shuffle(pool, new Random(week().hashCode() * 31L + (arcade ? 7 : 3)));
        List<Task> out = new ArrayList<>();
        for (Task t : pool) {
            if (out.size() == 3) break;
            if (out.stream().anyMatch(o -> o.id.startsWith("mobs") && t.id.startsWith("mobs"))) continue;   // never two mob tasks
            out.add(t);
        }
        return out;
    }

    private void progress(Player p, String kind, int n) {
        for (Task t : thisWeek()) {
            if (!t.id.equals(kind) && !(kind.equals("mobs") && t.id.startsWith("mobs"))) continue;
            String base = week() + "." + p.getUniqueId() + "." + t.id;
            if (data.getBoolean(base + ".done")) continue;
            int v = data.getInt(base + ".n") + n;
            data.set(base + ".n", v);
            if (v >= t.amount) complete(p, t, base);
        }
    }

    private void complete(Player p, Task t, String base) {
        data.set(base + ".done", true);
        plugin.economy().addScrap(p, t.scrap);
        plugin.economy().deposit(p, t.money);
        p.sendMessage(Ui.mm("<gold>✦ <bold>Challenge complete:</bold> <white>" + String.format(t.text, t.amount)
                + " <dark_gray>» <#c6a15b>+" + t.scrap + " Scrap <green>+" + NetEconomy.money(t.money)));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        long done = thisWeek().stream().filter(x -> data.getBoolean(week() + "." + p.getUniqueId() + "." + x.id + ".done")).count();
        if (done == 3) {
            plugin.economy().addScrap(p, 250);
            p.sendMessage(Ui.mm("<gold>✦ <bold>All three done this week!</bold> <gray>Bonus <#c6a15b>+250 Scrap"));
            Bukkit.broadcast(Ui.mm("<gold>✦ <white>" + p.getName() + " <gray>finished every weekly challenge! <yellow>/challenges"));
        }
        save();
    }

    // ------------------------------------------------------------------ tracking (open worlds)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent e) {
        if (arcade || !(e.getEntity() instanceof Enemy) || e.getEntity().getKiller() == null) return;
        progress(e.getEntity().getKiller(), "mobs", 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (arcade || !e.getBlock().getType().name().endsWith("_ORE") || e.getPlayer().getGameMode() != GameMode.SURVIVAL) return;
        progress(e.getPlayer(), "ores", 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (arcade || e.getPlayer().getGameMode() != GameMode.SURVIVAL) return;
        progress(e.getPlayer(), "build", 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (!arcade && e.getState() == PlayerFishEvent.State.CAUGHT_FISH) progress(e.getPlayer(), "fish", 1);
    }

    // ------------------------------------------------------------------ menu

    private static final class Menu implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    public void open(Player p) {
        Menu m = new Menu();
        LocalDate next = LocalDate.now(ZONE).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        long days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(ZONE), next);
        m.inv = Bukkit.createInventory(m, 9, Ui.mm("<dark_gray>Weekly Challenges <gray>- new in " + days + "d"));
        int slot = 2;
        for (Task t : thisWeek()) {
            String base = week() + "." + p.getUniqueId() + "." + t.id;
            boolean done = data.getBoolean(base + ".done");
            int n = Math.min(t.amount, data.getInt(base + ".n"));
            int bars = (int) Math.round(10.0 * n / t.amount);
            var it = Ui.item(done ? Material.LIME_DYE : t.icon, (done ? "<green>✔ " : "<white>") + String.format(t.text, t.amount),
                    "<gray>Progress: <white>" + n + "/" + t.amount,
                    "<green>" + "■".repeat(bars) + "<dark_gray>" + "■".repeat(10 - bars),
                    "",
                    "<gray>Reward: <#c6a15b>" + t.scrap + " Scrap <gray>+ <green>" + NetEconomy.money(t.money));
            if (done) it.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
            m.inv.setItem(slot, it);
            slot += 2;
        }
        m.inv.setItem(8, Ui.item(Material.NETHER_STAR, "<gold>Weekly bonus", "<gray>Finish all three for", "<#c6a15b>+250 Scrap <gray>extra"));
        p.openInventory(m.inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }
}
