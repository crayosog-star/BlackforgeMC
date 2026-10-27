package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.io.File;
import java.time.LocalDate;
import java.util.*;
import java.util.function.ToDoubleFunction;

import static net.alloymc.core.survival.SurvivalLayout.*;

/**
 * The Hall of Fame at spawn (top 10 money, blocks mined, kills, playtime, deaths) and player
 * heads that drop when someone is killed by another player.
 */
public final class HallOfFame implements Listener {
    record Board(String id, String title, String color, String unit) {}

    static final List<Board> SURVIVAL_BOARDS = List.of(
        new Board("money", "MOST MONEY", "<#5fd35f>", "$"),
        new Board("mined", "MOST BLOCKS MINED", "<#c6a15b>", "blocks"),
        new Board("kills", "MOST KILLS", "<#e5484d>", "kills"),
        new Board("playtime", "MOST PLAYTIME", "<#7fd6ff>", "h"),
        new Board("deaths", "MOST DEATHS", "<#b0b8c0>", "deaths"));
    static final List<Board> SKYBLOCK_BOARDS = List.of(
        new Board("level", "TOP ISLANDS", "<#4fd1c5>", "levels"),
        new Board("oneblock", "TOP ONEBLOCK", "<#b06bff>", "blocks"),
        new Board("money", "MOST MONEY", "<#5fd35f>", "$"),
        new Board("mined", "MOST BLOCKS MINED", "<#c6a15b>", "blocks"),
        new Board("playtime", "MOST PLAYTIME", "<#7fd6ff>", "h"));

    private final List<Board> BOARDS;
    private final List<HallSpot> spots;
    private final int baseY;
    private java.util.function.Function<UUID, Double> levelSource;

    void setLevelSource(java.util.function.Function<UUID, Double> f) { this.levelSource = f; }
    private java.util.function.Function<UUID, Double> oneblockSource;
    void setOneBlockSource(java.util.function.Function<UUID, Double> f) { this.oneblockSource = f; }

    private final AlloyCore plugin;
    private final World world;
    private final File file;
    private final YamlConfiguration data;   // stats.<uuid>.{name,mined,kills,playtime,deaths}
    private final Map<String, TextDisplay> holos = new HashMap<>();
    private Economy eco;
    private boolean dirty;

    HallOfFame(AlloyCore plugin, World world) { this(plugin, world, HALL, Y, SURVIVAL_BOARDS); }

    HallOfFame(AlloyCore plugin, World world, List<HallSpot> spots, int baseY, List<Board> boards) {
        this.spots = spots;
        this.baseY = baseY;
        this.BOARDS = boards;
        this.plugin = plugin;
        this.world = world;
        this.file = new File(plugin.getDataFolder(), "halloffame.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 100L, 1200L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::saveIfDirty, 6000L, 6000L);
    }

    void setEconomy(Economy eco) { this.eco = eco; }

    // ------------------------------------------------------------------ tracking

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMine(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() != GameMode.SURVIVAL) return;
        String k = "stats." + p.getUniqueId();
        data.set(k + ".mined", data.getLong(k + ".mined") + 1);
        data.set(k + ".name", p.getName());
        dirty = true;
    }

    /** Snapshot vanilla statistics while the player is online so offline players still rank. */
    private void snapshot(Player p) {
        String k = "stats." + p.getUniqueId();
        data.set(k + ".name", p.getName());
        data.set(k + ".kills", p.getStatistic(Statistic.PLAYER_KILLS));
        data.set(k + ".deaths", p.getStatistic(Statistic.DEATHS));
        data.set(k + ".playtime", p.getStatistic(Statistic.PLAY_ONE_MINUTE));   // ticks
        dirty = true;
    }

    /** Player heads: the victim's head drops when another player kills them. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getPlayer(), killer = victim.getKiller();
        if (killer == null || killer == victim) return;
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        head.editMeta(SkullMeta.class, m -> {
            m.setOwningPlayer(victim);
            m.displayName(Ui.mm("<yellow><bold>" + victim.getName() + "'s Head"));
            m.lore(List.of(Ui.mm("<gray>Slain by <red>" + killer.getName()), Ui.mm("<dark_gray>" + LocalDate.now())));
        });
        e.getDrops().add(head);
        killer.sendActionBar(Ui.mm("<yellow>☠ " + victim.getName() + "'s head dropped!"));
    }

    private void saveIfDirty() {
        if (!dirty) return;
        dirty = false;
        try { data.save(file); } catch (Exception ex) { plugin.getLogger().warning("hall of fame save failed: " + ex.getMessage()); }
    }

    // ------------------------------------------------------------------ boards

    private List<Map.Entry<String, Double>> top(ToDoubleFunction<String> value) {
        List<Map.Entry<String, Double>> rows = new ArrayList<>();
        var sec = data.getConfigurationSection("stats");
        if (sec == null) return rows;
        for (String u : sec.getKeys(false)) {
            String name = sec.getString(u + ".name");
            if (name == null || name.startsWith("AlloyTester")) continue;
            double v = value.applyAsDouble(u);
            if (v > 0) rows.add(Map.entry(name, v));
        }
        rows.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return rows.size() > 10 ? rows.subList(0, 10) : rows;
    }

    private String render(Board b, List<Map.Entry<String, Double>> rows) {
        StringBuilder sb = new StringBuilder(b.color() + "<bold>" + b.title() + "</bold><newline><dark_gray>" + "-".repeat(20));
        String[] medal = {"<#ffd700>", "<#c0c0c0>", "<#cd7f32>"};
        for (int i = 0; i < 10; i++) {
            sb.append("<newline>");
            if (i >= rows.size()) { sb.append("<dark_gray>#").append(i + 1).append(" ---"); continue; }
            double v = rows.get(i).getValue();
            String val = switch (b.id()) {
                case "money" -> AuctionHouse.money(v);
                case "playtime" -> String.format("%.1f", v / 72000.0) + "h";
                case "level" -> "Level " + String.format("%,d", (long) v);
                default -> String.format("%,d", (long) v) + " " + b.unit();
            };
            sb.append(i < 3 ? medal[i] : "<yellow>").append("#").append(i + 1).append(" <white>").append(rows.get(i).getKey())
              .append(" <dark_gray>- ").append(b.color()).append(val);
        }
        return sb.toString();
    }

    public void refresh() {
        for (Player p : Bukkit.getOnlinePlayers()) snapshot(p);
        if (eco != null) {
            var sec = data.getConfigurationSection("stats");
            for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
                if (op.getName() == null) continue;
                try {
                    data.set("stats." + op.getUniqueId() + ".money", eco.getBalance(op));
                    if (sec == null || !sec.contains(op.getUniqueId() + ".name")) data.set("stats." + op.getUniqueId() + ".name", op.getName());
                } catch (Exception ignored) { }
            }
        }
        if (levelSource != null) {
            var sec = data.getConfigurationSection("stats");
            if (sec != null) for (String u : sec.getKeys(false)) {
                try { Double lv = levelSource.apply(UUID.fromString(u)); if (lv != null) data.set("stats." + u + ".level", lv); } catch (Exception ignored) { }
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
                try { Double lv = levelSource.apply(p.getUniqueId()); if (lv != null) data.set("stats." + p.getUniqueId() + ".level", lv); } catch (Exception ignored) { }
            }
        }
        if (oneblockSource != null) {
            var sec = data.getConfigurationSection("stats");
            List<String> ids = new ArrayList<>(sec == null ? List.of() : sec.getKeys(false));
            for (Player p : Bukkit.getOnlinePlayers()) if (!ids.contains(p.getUniqueId().toString())) ids.add(p.getUniqueId().toString());
            for (String u : ids) {
                try { Double v = oneblockSource.apply(UUID.fromString(u)); if (v != null) data.set("stats." + u + ".oneblock", v); } catch (Exception ignored) { }
            }
        }
        saveIfDirty();
        for (Board b : BOARDS) {
            String text = render(b, top(u -> data.getDouble("stats." + u + "." + b.id())));
            TextDisplay td = holos.get(b.id());
            if (td == null || !td.isValid()) {
                HallSpot s = spots.get(BOARDS.indexOf(b));
                if (!world.isChunkLoaded(s.x() >> 4, s.z() >> 4)) continue;
                td = Ui.hologram(new Location(world, s.x() + 0.5, baseY + 4.2, s.z() + 0.5), text, 0.95f);
                holos.put(b.id(), td);
            } else td.text(Ui.mm(text));
        }
    }

    public boolean hologramsMissing() { return holos.size() < BOARDS.size() || holos.values().stream().anyMatch(t -> !t.isValid()); }

    public void shutdown() {
        holos.values().forEach(TextDisplay::remove);
        holos.clear();
        dirty = true;
        saveIfDirty();
    }
}
