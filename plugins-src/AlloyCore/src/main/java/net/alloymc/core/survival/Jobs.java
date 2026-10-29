package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.NetEconomy;
import net.alloymc.core.Ui;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
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
import java.util.*;

/**
 * Jobs (Survival): pick one of Miner, Lumberjack, Farmer, Hunter or Fisher with /jobs and get paid Ingots for doing it.
 * Each job levels up (to 50) the more you work, and higher levels pay more. Blocks players placed don't pay (no
 * place-and-break farming), crops only pay when fully grown. Earnings are added up and paid every few seconds.
 * You can switch job once a day; each job keeps its own level. Saved in jobs.yml.
 */
public final class Jobs implements Listener {
    public enum Job {
        MINER("Miner", Material.IRON_PICKAXE, "<gray>Mine ores"),
        LUMBERJACK("Lumberjack", Material.IRON_AXE, "<gray>Chop logs"),
        FARMER("Farmer", Material.WHEAT, "<gray>Harvest fully grown crops"),
        HUNTER("Hunter", Material.BOW, "<gray>Kill hostile mobs"),
        FISHER("Fisher", Material.FISHING_ROD, "<gray>Catch fish");
        final String label; final Material icon; final String desc;
        Job(String label, Material icon, String desc) { this.label = label; this.icon = icon; this.desc = desc; }
    }

    private static final int MAX_LEVEL = 50;
    private static final long SWITCH_COOLDOWN = 24 * 3600_000L;

    private final AlloyCore plugin;
    private final File file, placedFile;
    private final YamlConfiguration data;
    private final Set<Long> placed = new HashSet<>();   // player-placed ores and logs (don't pay)
    private final Map<UUID, Double> pending = new HashMap<>();
    private boolean dirty;

    Jobs(AlloyCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "jobs.yml");
        this.placedFile = new File(plugin.getDataFolder(), "jobs-placed.txt");
        this.data = YamlConfiguration.loadConfiguration(file);
        try { if (placedFile.exists()) for (String l : java.nio.file.Files.readAllLines(placedFile.toPath())) if (!l.isBlank()) placed.add(Long.parseLong(l.trim())); }
        catch (Exception ignored) { }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::payout, 100L, 100L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::save, 6000L, 6000L);
    }

    public void save() {
        if (dirty) { try { data.save(file); } catch (Exception ignored) { } dirty = false; }
        try { java.nio.file.Files.write(placedFile.toPath(), placed.stream().map(String::valueOf).toList()); } catch (Exception ignored) { }
    }

    private static long key(Block b) { return ((long) b.getX() & 0x3FFFFFF) << 38 | ((long) b.getZ() & 0x3FFFFFF) << 12 | (b.getY() & 0xFFF); }

    public Job jobOf(Player p) { String j = data.getString(p.getUniqueId() + ".job"); try { return j == null ? null : Job.valueOf(j); } catch (Exception e) { return null; } }
    private double xp(Player p, Job j) { return data.getDouble(p.getUniqueId() + ".xp." + j.name()); }
    public int level(Player p, Job j) { return Math.min(MAX_LEVEL, 1 + (int) Math.floor(Math.sqrt(xp(p, j) / 40.0))); }

    /** Credit a job action: pay (with the level bonus) and give job XP. */
    private void work(Player p, Job j, double pay, double xp) {
        if (jobOf(p) != j || p.getGameMode() != GameMode.SURVIVAL) return;
        int before = level(p, j);
        data.set(p.getUniqueId() + ".xp." + j.name(), xp(p, j) + xp);
        dirty = true;
        int after = level(p, j);
        pending.merge(p.getUniqueId(), pay * (1 + (before - 1) * 0.03), Double::sum);
        if (after > before) {
            p.sendMessage(Ui.mm("<gold>⚒ <bold>" + j.label + " level " + after + "!</bold> <gray>You now earn <white>" + Math.round((after - 1) * 3) + "% <gray>more."));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.3f);
        }
    }

    private void payout() {
        for (var e : pending.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            double amount = Math.round(e.getValue() * 100) / 100.0;
            if (p == null || amount <= 0) continue;
            plugin.economy().deposit(p, amount);
            Job j = jobOf(p);
            p.sendActionBar(Ui.mm("<green>+" + NetEconomy.money(amount) + " <gray>(" + (j == null ? "Job" : j.label + " Lv " + level(p, j)) + ")"));
        }
        pending.clear();
    }

    // ------------------------------------------------------------------ work

    private static double orePay(Material m) {
        String n = m.name();
        if (!n.endsWith("_ORE") && m != Material.ANCIENT_DEBRIS) return 0;
        if (n.contains("COAL")) return 1.5;
        if (n.contains("COPPER")) return 1.2;
        if (n.contains("IRON")) return 3;
        if (n.contains("REDSTONE") || n.contains("LAPIS")) return 3;
        if (n.contains("GOLD")) return 4;
        if (n.contains("EMERALD")) return 10;
        if (n.contains("DIAMOND")) return 12;
        if (m == Material.ANCIENT_DEBRIS) return 25;
        return 2;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Material m = e.getBlockPlaced().getType();
        if (orePay(m) > 0 || Tag.LOGS.isTagged(m)) placed.add(key(e.getBlockPlaced()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        Player p = e.getPlayer();
        Material m = b.getType();
        if (placed.remove(key(b))) return;   // they placed it themselves
        double ore = orePay(m);
        if (ore > 0) { work(p, Job.MINER, ore, ore * 2); return; }
        if (Tag.LOGS.isTagged(m)) { work(p, Job.LUMBERJACK, 1.2, 2); return; }
        if (b.getBlockData() instanceof Ageable a && a.getAge() == a.getMaximumAge() && (Tag.CROPS.isTagged(m) || m == Material.NETHER_WART || m == Material.COCOA))
            work(p, Job.FARMER, 1.0, 1.5);
        else if (m == Material.MELON || m == Material.PUMPKIN) work(p, Job.FARMER, 1.5, 2);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(EntityDeathEvent e) {
        Player k = e.getEntity().getKiller();
        if (k != null && e.getEntity() instanceof Enemy) work(k, Job.HUNTER, 4, 5);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() == PlayerFishEvent.State.CAUGHT_FISH) work(e.getPlayer(), Job.FISHER, 8, 8);
    }

    // ------------------------------------------------------------------ /jobs

    private static final class Menu implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    public void open(Player p) {
        Menu m = new Menu();
        Job cur = jobOf(p);
        m.inv = Bukkit.createInventory(m, 9, Ui.mm("<dark_gray>Jobs <gray>- " + (cur == null ? "pick one" : "you're a " + cur.label)));
        int slot = 2;
        for (Job j : Job.values()) {
            int lv = level(p, j);
            var it = Ui.item(j.icon, (j == cur ? "<green>✔ " : "<white>") + j.label + " <gray>Lv " + lv, j.desc,
                    "<gray>Pay bonus: <white>+" + (lv - 1) * 3 + "%", "",
                    j == cur ? "<green>Your current job" : "<yellow>Click to switch to this job");
            if (j == cur) it.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
            m.inv.setItem(slot++, it);
        }
        p.openInventory(m.inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu) || !(e.getWhoClicked() instanceof Player p)) return;
        e.setCancelled(true);
        int i = e.getRawSlot() - 2;
        if (i < 0 || i >= Job.values().length) return;
        Job j = Job.values()[i];
        if (j == jobOf(p)) return;
        long last = data.getLong(p.getUniqueId() + ".switched");
        if (jobOf(p) != null && System.currentTimeMillis() - last < SWITCH_COOLDOWN) {
            long h = (SWITCH_COOLDOWN - (System.currentTimeMillis() - last)) / 3600_000L + 1;
            p.sendMessage(Ui.mm("<red>You can switch job again in " + h + " hour" + (h == 1 ? "" : "s") + "."));
            return;
        }
        data.set(p.getUniqueId() + ".job", j.name());
        data.set(p.getUniqueId() + ".switched", System.currentTimeMillis());
        dirty = true;
        p.sendMessage(Ui.mm("<gold>⚒ <gray>You're now a <white>" + j.label + "<gray>. " + j.desc + " <gray>to earn Ingots."));
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_YES, 1f, 1f);
        open(p);
    }
}
