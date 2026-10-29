package net.alloymc.core.hub;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.NetEconomy;
import net.alloymc.core.Ui;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.*;

import java.io.File;
import java.time.Duration;
import java.util.*;

/**
 * The new-player tutorial on the hub: a boss bar walks first-timers through four quick steps (dash, open the server
 * selector, find the Discord, pick a game) and pays a welcome reward of Ingots and Scrap when they step into their first
 * game. /tutorial replays it (the reward is once only). Progress in tutorial.yml.
 */
public final class Tutorial implements Listener {
    private static final String[] STEPS = {
            "<gold>Step 1/4 <dark_gray>» <white>Double-tap <yellow>SPACE <white>to dash",
            "<gold>Step 2/4 <dark_gray>» <white>Right-click the <yellow>compass <white>to see every game",
            "<gold>Step 3/4 <dark_gray>» <white>Type <yellow>/discord <white>to find the community",
            "<gold>Step 4/4 <dark_gray>» <white>Walk into a <yellow>portal <white>(or use the compass) to play!"};
    private static final int REWARD_MONEY = 1000, REWARD_SCRAP = 250;

    private final AlloyCore plugin;
    private final File file;
    private final YamlConfiguration data;
    private final Map<UUID, Integer> step = new HashMap<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();

    Tutorial(AlloyCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "tutorial.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getCommandMap().register("alloy", new Command("tutorial", "Replay the BlackForgeMC tutorial", "/tutorial", List.of("guide")) {
            @Override public boolean execute(CommandSender s, String label, String[] a) { if (s instanceof Player p) start(p); return true; }
        });
    }

    private void save() { try { data.save(file); } catch (Exception ignored) { } }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (data.getBoolean(p.getUniqueId() + ".done") || data.getBoolean(p.getUniqueId() + ".skipped")) return;
        if (p.hasPlayedBefore() && data.get(p.getUniqueId().toString()) == null) return;   // existing players aren't forced into it
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) start(p); }, 100L);
    }

    public void start(Player p) {
        step.put(p.getUniqueId(), 0);
        p.showTitle(Title.title(Ui.mm("<gold><bold>Welcome to BlackForgeMC!"), Ui.mm("<gray>Four quick steps - and a reward at the end"),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(600))));
        p.sendMessage(Ui.mm("<gold>✦ <gray>Quick tour: follow the bar at the top. Finish it for <green>" + NetEconomy.money(REWARD_MONEY)
                + " <gray>and <#c6a15b>" + REWARD_SCRAP + " Scrap<gray>. <dark_gray>(type <gray>/tutorial skip</gray> to skip)"));
        show(p);
    }

    private void show(Player p) {
        int s = step.getOrDefault(p.getUniqueId(), 0);
        BossBar bar = bars.computeIfAbsent(p.getUniqueId(), k -> BossBar.bossBar(Ui.mm(""), 0f, BossBar.Color.YELLOW, BossBar.Overlay.NOTCHED_6));
        bar.name(Ui.mm(STEPS[s]));
        bar.progress((s + 0.0001f) / STEPS.length);
        p.showBossBar(bar);
    }

    private void advance(Player p, int from) {
        Integer s = step.get(p.getUniqueId());
        if (s == null || s != from) return;
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.3f + from * 0.15f);
        if (from + 1 < STEPS.length) { step.put(p.getUniqueId(), from + 1); show(p); return; }
        finish(p);
    }

    private void finish(Player p) {
        step.remove(p.getUniqueId());
        BossBar bar = bars.remove(p.getUniqueId());
        if (bar != null) p.hideBossBar(bar);
        String id = p.getUniqueId().toString();
        if (!data.getBoolean(id + ".done")) {
            plugin.economy().deposit(p, REWARD_MONEY);
            plugin.economy().addScrap(p, REWARD_SCRAP);
            p.sendMessage(Ui.mm("<gold>✦ <bold>Tutorial complete!</bold> <green>+" + NetEconomy.money(REWARD_MONEY) + " <#c6a15b>+" + REWARD_SCRAP
                    + " Scrap <gray>- your money and Scrap work on every server. Have fun!"));
            data.set(id + ".done", true);
            save();
        }
    }

    /** Called by the hub when the player is sent to a game (portal or selector). */
    void pickedGame(Player p) { advance(p, 3); }

    @EventHandler
    public void onDash(PlayerToggleFlightEvent e) { if (e.isFlying()) Bukkit.getScheduler().runTask(plugin, () -> advance(e.getPlayer(), 0)); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCompass(PlayerInteractEvent e) {
        if (e.getItem() != null && e.getItem().getType() == Material.COMPASS && e.getAction().isRightClick()) advance(e.getPlayer(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        String c = e.getMessage().toLowerCase(Locale.ROOT);
        Player p = e.getPlayer();
        if (c.startsWith("/discord") || c.startsWith("/dc")) Bukkit.getScheduler().runTask(plugin, () -> advance(p, 2));
        if (c.equals("/tutorial skip") || c.equals("/guide skip")) {
            e.setCancelled(true);
            step.remove(p.getUniqueId());
            BossBar bar = bars.remove(p.getUniqueId());
            if (bar != null) p.hideBossBar(bar);
            data.set(p.getUniqueId() + ".skipped", true);
            save();
            p.sendMessage(Ui.mm("<gray>Tutorial skipped. <white>/tutorial <gray>brings it back."));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        Integer s = step.remove(id);
        bars.remove(id);
        if (s != null && !data.getBoolean(id + ".done")) { data.set(id + ".step", s); save(); }   // picks up again next time
    }
}
