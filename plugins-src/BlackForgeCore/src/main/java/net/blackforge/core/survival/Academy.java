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
    private static final int SIZE = 54, RANK = 4, PROGRESS = 20, LIFETIME = 22, TODAY = 24, PERKS = 38, ADD_LEVELS = 40, SPEC = 42, BACK = 49;
    private static final int CONFIRM_YES = 29, CONFIRM_NO = 33;
    private static final int[] PERK_SLOTS = {20, 21, 22, 23, 24};
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
        final Map<Integer, Runnable> actions = new HashMap<>();
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
    void openAgent(Player p, Robots.Bot b, boolean readOnly, Runnable back, BooleanSupplier stillAllowed) {
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
        if (!readOnly) {
            int waiting = AcademyRules.perkPicksAt(level) - (track == null ? 0 : track.perks.size());
            v.inv.setItem(PERKS, Ui.item(Material.ENCHANTED_BOOK, VERDIGRIS + "<bold>Perks", "<gray>Earned at job levels <white>5<gray>, <white>15 <gray>and <white>25<gray>.",
                    "<gray>Chosen: <white>" + (track == null ? 0 : track.perks.size()) + "/3", waiting > 0 ? "<green>" + waiting + " to choose" : "<dark_gray>Nothing to choose yet", "", "<yellow>Click to view"));
            v.actions.put(PERKS, () -> openPerks(p, b, back, stillAllowed));
            String spec = track == null ? null : track.spec;
            v.inv.setItem(SPEC, Ui.item(Material.NETHER_STAR, VERDIGRIS + "<bold>Specialization", "<gray>Chosen at job level <white>10<gray>.",
                    "<gray>Path: <white>" + (spec == null ? "none" : specName(spec)), "", "<yellow>Click to view"));
            v.actions.put(SPEC, () -> openSpecs(p, b, back, stillAllowed));
        }
        if (robots.isSandbox() && !maxed) v.inv.setItem(ADD_LEVELS, Ui.item(Material.ANVIL, BRASS + "<bold>Add 10 Levels", "<gray>Foundry only: raises this job's level for free.", "", "<yellow>Click to add"));
        p.openInventory(v.inv);
    }

    private static String perkName(String perk) {
        return switch (perk) {
            case AcademyRules.EFFICIENT -> "Efficient";
            case AcademyRules.QUICK -> "Quick Hands";
            case AcademyRules.REACH -> "Long Reach";
            case AcademyRules.KEEN -> "Keen Eye";
            default -> "Steady Charge";
        };
    }

    private String perkEffect(String perk) {
        AgentStats.Tuning t = robots.tuning();
        return switch (perk) {
            case AcademyRules.EFFICIENT -> "Burns " + percentOff(t.efficientFuel()) + "% less fuel";
            case AcademyRules.QUICK -> "Works " + percentOn(t.quickSpeed()) + "% faster";
            case AcademyRules.REACH -> "+" + t.reachRadius() + " work radius";
            case AcademyRules.KEEN -> Math.round(t.keenDouble() * 100) + "% chance to double a drop";
            default -> "Builds Charge " + percentOn(t.steadyCharge()) + "% faster";
        };
    }

    private static String specName(String spec) {
        return switch (spec) {
            case AcademyRules.PROSPECTOR -> "Prospector";
            case AcademyRules.EXCAVATOR -> "Excavator";
            case AcademyRules.HARVESTER -> "Harvester";
            case AcademyRules.BOTANIST -> "Botanist";
            case AcademyRules.REAPER -> "Reaper";
            case AcademyRules.BUTCHER -> "Butcher";
            case AcademyRules.ANGLER -> "Angler";
            case AcademyRules.DIVER -> "Treasure Diver";
            default -> spec;
        };
    }

    private String specEffect(String spec) {
        AgentStats.Tuning t = robots.tuning();
        return switch (spec) {
            case AcademyRules.PROSPECTOR -> "+" + t.prospectorFortune() + " Fortune level";
            case AcademyRules.EXCAVATOR -> "Also mines the block above its target";
            case AcademyRules.HARVESTER -> "+" + t.harvesterExtra() + " harvests per cycle";
            case AcademyRules.BOTANIST -> "Grows " + t.botanistCrops() + " crop one stage per cycle";
            case AcademyRules.REAPER -> "+" + percentOn(t.reaperDamage()) + "% damage, +" + t.reaperHits() + " hit";
            case AcademyRules.BUTCHER -> Math.round(t.butcherChance() * 100) + "% chance to copy a normal drop";
            case AcademyRules.ANGLER -> "Fishes " + percentOn(t.anglerSpeed()) + "% faster";
            case AcademyRules.DIVER -> "Treasure chance x" + t.diverMult();
            default -> "";
        };
    }

    private void openSpecs(Player p, Robots.Bot b, Runnable agentBack, BooleanSupplier stillAllowed) {
        View v = open(b.id, "Specialization", () -> openAgent(p, b, false, agentBack, stillAllowed), stillAllowed);
        String job = Robots.jobKey(b);
        AgentRecord.JobTrack track = b.record.jobs.computeIfAbsent(job, k -> new AgentRecord.JobTrack());
        int level = robots.academyRules().levelFor(track.points);
        int[] slots = {20, 24};
        List<String> paths = AcademyRules.specsFor(job);
        for (int i = 0; i < paths.size(); i++) {
            String spec = paths.get(i);
            boolean chosen = spec.equals(track.spec), can = AcademyRules.canPickSpec(track, spec, job, level);
            ItemStack icon = Ui.item(chosen ? Material.NETHER_STAR : Material.GLOWSTONE_DUST, (chosen ? "<green>" : can ? VERDIGRIS : "<gray>") + "<bold>" + specName(spec),
                    "<gray>" + specEffect(spec), "", chosen ? "<green>Chosen" : can ? "<yellow>Click to choose" : "<dark_gray>" + (level < 10 ? "Unlocks at level 10" : "Another path is chosen"));
            v.inv.setItem(slots[i], icon);
            if (can) v.actions.put(slots[i], () -> confirm(p, b, "Become a " + specName(spec) + "?", "Changing this later costs money.", () -> {
                AgentRecord.JobTrack now = b.record.jobs.computeIfAbsent(Robots.jobKey(b), k -> new AgentRecord.JobTrack());
                if (AcademyRules.canPickSpec(now, spec, Robots.jobKey(b), robots.academyRules().levelFor(now.points))) { now.spec = spec; robots.save(); }
                openSpecs(p, b, agentBack, stillAllowed);
            }, () -> openSpecs(p, b, agentBack, stillAllowed), stillAllowed));
        }
        p.openInventory(v.inv);
    }

    private static long percentOff(double mult) { return Math.round((1 - mult) * 100); }

    private static long percentOn(double mult) { return Math.round((mult - 1) * 100); }

    private void openPerks(Player p, Robots.Bot b, Runnable agentBack, BooleanSupplier stillAllowed) {
        Runnable toAgent = () -> openAgent(p, b, false, agentBack, stillAllowed);
        View v = open(b.id, "Perks", toAgent, stillAllowed);
        String job = Robots.jobKey(b);
        AgentRecord.JobTrack track = b.record.jobs.computeIfAbsent(job, k -> new AgentRecord.JobTrack());
        int level = robots.academyRules().levelFor(track.points);
        int slot = 0;
        for (String perk : AcademyRules.PERKS) {
            if (!AcademyRules.perkFits(perk, job)) continue;   // Long Reach is hidden for jobs it can't help
            boolean chosen = track.perks.contains(perk), can = AcademyRules.canPickPerk(track, perk, job, level);
            ItemStack icon = Ui.item(chosen ? Material.ENCHANTED_BOOK : Material.BOOK, (chosen ? "<green>" : can ? VERDIGRIS : "<gray>") + "<bold>" + perkName(perk), "<gray>" + perkEffect(perk), "",
                    chosen ? "<green>Chosen" : can ? "<yellow>Click to choose" : "<dark_gray>No pick available");
            v.inv.setItem(PERK_SLOTS[slot], icon);
            if (can) v.actions.put(PERK_SLOTS[slot], () -> confirm(p, b, "Choose " + perkName(perk) + "?", perkEffect(perk), () -> {
                AgentRecord.JobTrack now = b.record.jobs.computeIfAbsent(Robots.jobKey(b), k -> new AgentRecord.JobTrack());
                if (AcademyRules.canPickPerk(now, perk, Robots.jobKey(b), robots.academyRules().levelFor(now.points))) { now.perks.add(perk); robots.save(); }
                openPerks(p, b, agentBack, stillAllowed);
            }, () -> openPerks(p, b, agentBack, stillAllowed), stillAllowed));
            slot++;
        }
        p.openInventory(v.inv);
    }

    /** A yes or no screen. Choices only ever happen from here, so one stray click can't make one. */
    private void confirm(Player p, Robots.Bot b, String question, String detail, Runnable yes, Runnable no, BooleanSupplier stillAllowed) {
        View v = open(b.id, question, no, stillAllowed);
        v.inv.setItem(13, Ui.item(Material.PAPER, BRASS + "<bold>" + question, "<gray>" + detail));
        v.inv.setItem(CONFIRM_YES, Ui.item(Material.LIME_CONCRETE, "<green><bold>Yes"));
        v.inv.setItem(CONFIRM_NO, Ui.item(Material.RED_CONCRETE, "<red><bold>No"));
        v.actions.put(CONFIRM_YES, yes);
        v.actions.put(CONFIRM_NO, no);
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
        Runnable action = v.actions.get(slot);
        if (action != null) { action.run(); return; }
        if (slot == ADD_LEVELS && v.agentId != null && robots.isSandbox()) {
            Robots.Bot b = robots.agent(v.agentId);
            robots.addLevels(b, 10);
            openAgent(p, b, false, v.back, v.stillAllowed);
            return;
        }
        String picked = v.agents.get(slot);
        Robots.Bot chosen = picked == null ? null : robots.agent(picked);
        if (chosen != null) openAgent(p, chosen, false, () -> openList(p, v.back, v.stillAllowed), v.stillAllowed);
    }

    /** For the Foundry's first-use tip. */
    public static boolean isMenu(InventoryHolder holder) { return holder instanceof View; }

    private void closeLater(Player p, View v) {
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.getOpenInventory().getTopInventory().equals(v.inv)) p.closeInventory(); });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof View) e.setCancelled(true); }
}
