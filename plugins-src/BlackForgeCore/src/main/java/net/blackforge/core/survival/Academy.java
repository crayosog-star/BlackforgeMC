package net.blackforge.core.survival;

import net.blackforge.core.BlackForgeCore;
import net.blackforge.core.Ui;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** The Academy: read-only menus showing an Agent's Mastery. Nothing in here moves items or changes an Agent yet. */
public final class Academy implements Listener {
    static final String BRASS = "<#C9A227>";
    private static final String VERDIGRIS = "<#4FA08B>";
    private static final int SIZE = 54, RANK = 4, PROGRESS = 20, LIFETIME = 22, TODAY = 24, BACK = 49;
    private static final int[] JOB_SLOTS = {28, 30, 32, 34};
    private static final int LIST_START = 9, LIST_END = 44;
    private static final int BAR_SEGMENTS = 20;

    private final BlackForgeCore plugin;
    private final Robots robots;

    Academy(BlackForgeCore plugin, Robots robots) {
        this.plugin = plugin;
        this.robots = robots;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /** One open Academy screen. agentId is null on the list of a player's Agents. */
    private static final class View implements InventoryHolder {
        final String agentId;
        final Runnable back;
        final BooleanSupplier stillAllowed;
        final Map<Integer, String> agents = new HashMap<>();
        Inventory inv;
        View(String agentId, Runnable back, BooleanSupplier stillAllowed) {
            this.agentId = agentId;
            this.back = back;
            this.stillAllowed = stillAllowed;
        }
        @Override public Inventory getInventory() { return inv; }
    }

    private View open(String agentId, String title, Runnable back, BooleanSupplier stillAllowed) {
        View v = new View(agentId, back, stillAllowed);
        v.inv = Bukkit.createInventory(v, SIZE, Ui.mm("<dark_gray>" + title));
        ItemStack pane = Ui.item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < SIZE; i++) v.inv.setItem(i, pane);
        v.inv.setItem(BACK, Ui.item(Material.ARROW, "<yellow><bold>Back"));
        return v;
    }

    /** One Agent's view. back reopens wherever it was opened from. */
    void openAgent(Player p, Robots.Bot b, Runnable back, BooleanSupplier stillAllowed) {
        View v = open(b.id, "The Academy", back, stillAllowed);
        AcademyRules rules = robots.academyRules();
        long softCap = robots.academySoftCap();
        AgentRecord.JobTrack track = b.record.jobs.get(Robots.jobKey(b));
        long points = track == null ? 0 : track.points;
        int level = rules.levelFor(points);

        v.inv.setItem(RANK, Ui.item(Material.COPPER_GOLEM_SPAWN_EGG, BRASS + "<bold>" + AcademyRules.rankFor(level),
                "<gray>" + b.title(), "<gray>Level <white>" + level + " <gray>" + b.job.label));

        boolean maxed = rules.isMaxLevel(level);
        v.inv.setItem(PROGRESS, Ui.item(Material.EXPERIENCE_BOTTLE, VERDIGRIS + "<bold>Next Level",
                bar(rules.progressToNext(points)),
                maxed ? "<gold>Max level" : "<white>" + points + " <gray>/ <white>" + rules.pointsFor(level + 1) + " <gray>points"));

        long today = track != null && Robots.academyToday().equals(track.todayDate) ? track.todayPoints : 0;
        v.inv.setItem(TODAY, Ui.item(Material.CLOCK, VERDIGRIS + "<bold>Today",
                bar(softCap <= 0 ? 1 : (double) today / softCap),
                "<white>" + today + " <gray>/ <white>" + softCap + " <gray>full-value points",
                today >= softCap ? "<yellow>Past today's limit, so it earns less" : "<gray>Resets at midnight UTC",
                "", "<gray>Earns more while you're close by"));

        v.inv.setItem(LIFETIME, Ui.item(Material.BOOK, BRASS + "<bold>Lifetime",
                "<gray>Work done: <white>" + b.record.workDone,
                "<gray>Time worked: <white>" + Robots.time(b.record.minutesWorked * 60_000L)));

        Robots.Job[] jobs = Robots.Job.values();
        for (int i = 0; i < jobs.length && i < JOB_SLOTS.length; i++) {
            Robots.Job j = jobs[i];
            AgentRecord.JobTrack t = b.record.jobs.get(j.name().toLowerCase(java.util.Locale.ROOT));
            int l = rules.levelFor(t == null ? 0 : t.points);
            ItemStack icon = Ui.item(j.icon, j.color + "<bold>" + j.label, "<gray>Level <white>" + l, "<gray>" + AcademyRules.rankFor(l),
                    "", j == b.job ? "<green>Current job" : "<dark_gray>Levels are kept when you switch job");
            if (j == b.job) icon.editMeta(m -> m.setEnchantmentGlintOverride(true));
            v.inv.setItem(JOB_SLOTS[i], icon);
        }
        p.openInventory(v.inv);
    }

    /** The list of a player's placed Agents, opened from the Forge. */
    void openList(Player p, Runnable back, BooleanSupplier stillAllowed) {
        View v = open(null, "The Academy", back, stillAllowed);
        List<Robots.Bot> all = robots.agentsOf(p.getUniqueId());
        AcademyRules rules = robots.academyRules();
        int slot = LIST_START;
        for (Robots.Bot b : all) {
            if (slot > LIST_END) break;   // only the Foundry allows more Agents than fit here
            AgentRecord.JobTrack t = b.record.jobs.get(Robots.jobKey(b));
            int level = rules.levelFor(t == null ? 0 : t.points);
            v.inv.setItem(slot, Ui.item(b.job.icon, b.job.color + "<bold>" + b.title(), "<gray>" + AcademyRules.rankFor(level) + " <white>" + level,
                    "", "<yellow>Click to view"));
            v.agents.put(slot, b.id);
            slot++;
        }
        if (all.isEmpty()) v.inv.setItem(RANK, Ui.item(Material.BOOK, BRASS + "<bold>No Agents placed", "<gray>Only placed Agents show here."));
        p.openInventory(v.inv);
    }

    private static String bar(double progress) {
        int lit = (int) Math.round(Math.clamp(progress, 0.0, 1.0) * BAR_SEGMENTS);
        return BRASS + "▮".repeat(lit) + "<dark_gray>" + "▯".repeat(BAR_SEGMENTS - lit);
    }

    // Everything is cancelled first, including shift-clicks, number keys and double-clicks in the player's own inventory.
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof View v)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        // the Agent can be picked up, the Station broken or access lost while this stays open
        if (!v.stillAllowed.getAsBoolean() || (v.agentId != null && robots.agent(v.agentId) == null)) { closeLater(p, v); return; }
        int slot = e.getRawSlot();
        if (slot == BACK) { v.back.run(); return; }
        String picked = v.agents.get(slot);
        Robots.Bot b = picked == null ? null : robots.agent(picked);
        if (b != null) openAgent(p, b, () -> openList(p, v.back, v.stillAllowed), v.stillAllowed);
    }

    private void closeLater(Player p, View v) {
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.getOpenInventory().getTopInventory().equals(v.inv)) p.closeInventory(); });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof View) e.setCancelled(true); }
}
