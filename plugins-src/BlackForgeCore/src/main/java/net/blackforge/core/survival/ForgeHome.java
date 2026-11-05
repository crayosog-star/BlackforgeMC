package net.blackforge.core.survival;

import net.blackforge.core.BlackForgeCore;
import net.blackforge.core.NetEconomy;
import net.blackforge.core.Ui;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The Forge's first screen when base levels are on: what this base has, what's next and what it takes to get there.
 * Today's Agent cards sit one click behind it.
 */
final class ForgeHome implements Listener {
    private static final int SIZE = 54, LEVEL_UP = 45, AGENTS = 51, STORAGE = 52, HELP = 53;
    private static final int[] LEVEL_SLOTS = {0, 2, 4, 6, 8}, SUMMARY_SLOTS = {9, 10, 11, 12, 13, 14, 15, 16, 17};
    private static final int[] ALLAY_SLOTS = {21, 22, 23}, JOB_SLOTS = {30, 31, 32, 33}, HOPPER_SLOTS = {39, 40};
    private static final int TEAL = 0x4FD1B5, ORANGE = 0xFF8A3D, QUIET = 0x8A848F, INK = 0xECE8E2, DIM = 0x4A464E;
    /** Draws nothing: the panel shows these slots, and the item is only there for its tooltip. */
    private static final org.bukkit.NamespacedKey BLANK = new org.bukkit.NamespacedKey("blackforge", "blank");
    private static final Robots.Job[] JOB_ORDER = {Robots.Job.FARMER, Robots.Job.MINER, Robots.Job.FISHERMAN, Robots.Job.GRINDER};
    private static final String MONEY = "<green>", ESSENCE = "<#9d7bff>";

    private static final class Menu implements InventoryHolder {
        final Location at; Inventory inv;
        Menu(Location at) { this.at = at; }
        public Inventory getInventory() { return inv; }
    }

    private final org.bukkit.NamespacedKey seenIntro;
    private final BaseStation station;
    private final BaseLevels levels;
    private final Robots robots;

    ForgeHome(BlackForgeCore plugin, BaseStation station, BaseLevels levels, Robots robots) {
        this.seenIntro = new org.bukkit.NamespacedKey(plugin, "forge_home_intro");
        this.station = station;
        this.levels = levels;
        this.robots = robots;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    void open(Player p, Location at) {
        String region = station.regionAt(at);
        int level = levels.level(region);
        BaseLevelRules.Table t = levels.table();
        long delivered = levels.delivered(region);
        Menu m = new Menu(at);
        boolean art = StationUi.art();
        Allays allays = station.allays();
        boolean showAllays = allays != null && allays.available(p), claimTiers = station.sellsClaimTiers(region);
        m.inv = Bukkit.createInventory(m, SIZE, art ? title(t, level, delivered, region, showAllays, claimTiers) : Ui.mm("<dark_gray>The Forge - Base Level " + level));
        if (!art) {
            ItemStack pane = Ui.item(Material.GRAY_STAINED_GLASS_PANE, " ");
            for (int i = 0; i < SIZE; i++) m.inv.setItem(i, pane);
            m.inv.setItem(18, label("Forge Allays"));
            m.inv.setItem(27, label("Agents"));
            m.inv.setItem(36, label("Hoppers"));
        }
        ItemStack summary = summary(t, level, delivered);
        if (art) { hide(summary); for (int slot : SUMMARY_SLOTS) m.inv.setItem(slot, summary); }
        else m.inv.setItem(SUMMARY_SLOTS[4], summary);
        for (int l = 1; l <= BaseLevelRules.MAX_LEVEL; l++) {
            ItemStack icon = levelIcon(t, level, l);
            if (art) { icon.setAmount(1); hide(icon); }
            m.inv.setItem(LEVEL_SLOTS[l - 1], icon);
        }
        if (showAllays) for (int tier = 1; tier <= AllayRules.MAX_TIER; tier++) m.inv.setItem(ALLAY_SLOTS[tier - 1], allays.homeCard(tier, region));
        for (int i = 0; i < JOB_ORDER.length; i++) m.inv.setItem(JOB_SLOTS[i], jobIcon(t, level, JOB_ORDER[i]));
        m.inv.setItem(AGENTS, Ui.item(Material.COPPER_GOLEM_SPAWN_EGG, "<gold><bold>Your Agents",
                "<gray>Placed: <white>" + robots.owned(p.getUniqueId()) + "/" + robots.limit(p), "", "<yellow>Click to see and upgrade them"));
        if (claimTiers) for (int i = 0; i < HOPPER_SLOTS.length; i++) m.inv.setItem(HOPPER_SLOTS[i], station.claimTierIcon(region, i + 2));
        else m.inv.setItem(HOPPER_SLOTS[0], Ui.item(Material.HOPPER, "<gold><bold>Hopper Tiers", "<gray>This Forge sells Hopper Upgrades instead.", "<gray>Find them under <white>Your Agents<gray>."));
        m.inv.setItem(LEVEL_UP, levelUpIcon(t, level, delivered));
        m.inv.setItem(STORAGE, Ui.item(Material.BARREL, "<gold><bold>Storage", "<gray>What your Agents have delivered.", "", "<yellow>Click to open"));
        m.inv.setItem(HELP, Ui.item(Material.BOOK, "<gold><bold>How It Works",
                "<gray>1. Put Agents within range of this Forge.",
                "<gray>2. What they deliver here counts toward", "<gray>   your next Base Level.",
                "<gray>3. Click <white>Level Up<gray> when you've reached it.",
                "<gray>4. Each level unlocks more of the base.",
                "", "<dark_gray>Hoppers and allays don't add to the count."));
        p.openInventory(m.inv);
        var pdc = p.getPersistentDataContainer();
        if (pdc.has(seenIntro, org.bukkit.persistence.PersistentDataType.BYTE)) return;
        pdc.set(seenIntro, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
        p.sendMessage(Ui.mm("<#4fd1b5>Your base has a level now. <gray>What your Agents deliver here counts toward the next one, and each level unlocks more of your base."));
    }

    private static void hide(ItemStack it) { it.editMeta(m -> m.setItemModel(BLANK)); }

    private static int leftAt(int x, String text) { return x + StationText.width(text) / 2; }

    private static int slotX(int slot) { return 7 + 18 * (slot % 9); }

    /** The live part of the home screen: its text, progress bar and which wells are lit. */
    private net.kyori.adventure.text.Component title(BaseLevelRules.Table t, int level, long delivered, String region, boolean showAllays, boolean claimTiers) {
        StationUi.Canvas c = new StationUi.Canvas().art(StationUi.Screen.values()[StationUi.Screen.HOME_1.ordinal() + level - 1]);
        c.text("title", leftAt(8, "FORGE"), "FORGE", INK);
        String now = "LEVEL " + level;
        c.text("title", 168 - StationText.width(now) / 2, now, ORANGE);
        for (int l = 1; l <= BaseLevelRules.MAX_LEVEL; l++)
            c.text("gui_l0", slotX(LEVEL_SLOTS[l - 1]) + 9, String.valueOf(l), l <= level ? TEAL : l == level + 1 ? ORANGE : DIM);
        boolean top = level == BaseLevelRules.MAX_LEVEL;
        c.text("gui_l1", leftAt(8, top ? "TOP LEVEL" : "NEXT LEVEL"), top ? "TOP LEVEL" : "NEXT LEVEL", QUIET);
        String count = top ? String.valueOf(delivered) : Math.min(delivered, t.cost(level + 1).deliveries()) + " / " + t.cost(level + 1).deliveries();
        c.text("gui_l1", 168 - StationText.width(count) / 2, count, INK);
        c.pips(88, (int) Math.round(t.progress(level, delivered) * 20), 20, StationUi.HOME_BAR_ON, StationUi.HOME_BAR_OFF, 8);
        c.text("gui_l3", leftAt(8, "ALLAYS"), "ALLAYS", QUIET);
        c.text("gui_l4", leftAt(8, "AGENTS"), "AGENTS", QUIET);
        c.text("gui_l5", leftAt(8, "HOPPERS"), "HOPPERS", QUIET);
        c.text("gui_l6", leftAt(29, "LEVEL UP"), "LEVEL UP", t.canLevelUp(level, delivered) ? ORANGE : QUIET);
        if (showAllays) for (int tier = 1; tier <= AllayRules.MAX_TIER; tier++)
            if (t.allayUnlocked(level, tier)) c.glyph(slotX(ALLAY_SLOTS[tier - 1]), StationUi.HOME_ON_2, 18);
        for (int i = 0; i < JOB_ORDER.length; i++)
            if (t.jobUnlocked(level, JOB_ORDER[i].name().toLowerCase(java.util.Locale.ROOT))) c.glyph(slotX(JOB_SLOTS[i]), StationUi.HOME_ON_3, 18);
        if (claimTiers) for (int i = 0; i < HOPPER_SLOTS.length; i++)
            if (t.hopperUnlocked(level, i + 2)) c.glyph(slotX(HOPPER_SLOTS[i]), StationUi.HOME_ON_4, 18);
        if (t.canLevelUp(level, delivered)) c.glyph(slotX(LEVEL_UP), StationUi.HOME_READY, 18);
        return c.done();
    }

    private static ItemStack label(String text) { return Ui.item(Material.PAPER, "<white><bold>" + text); }

    private static String bar(double progress) {
        int lit = (int) Math.round(Math.max(0, Math.min(1, progress)) * 20);
        return "<#4fd1b5>" + "|".repeat(lit) + "<dark_gray>" + "|".repeat(20 - lit);
    }

    private static ItemStack summary(BaseLevelRules.Table t, int level, long delivered) {
        if (level == BaseLevelRules.MAX_LEVEL)
            return Ui.item(Material.NETHER_STAR, "<gradient:#c8743a:#4fd1b5><bold>Base Level " + level + "</bold></gradient>", "<gray>This is the top level.", "<gray>Agent deliveries: <white>" + delivered);
        BaseLevelRules.Cost next = t.cost(level + 1);
        return Ui.item(Material.NETHER_STAR, "<gradient:#c8743a:#4fd1b5><bold>Base Level " + level + "</bold></gradient>",
                "<gray>Next: Level <white>" + (level + 1),
                bar(t.progress(level, delivered)),
                "<gray>Agent deliveries: <white>" + Math.min(delivered, next.deliveries()) + " <dark_gray>/ " + next.deliveries());
    }

    private static ItemStack levelIcon(BaseLevelRules.Table t, int level, int shown) {
        List<String> lore = new ArrayList<>();
        lore.add(shown <= level ? "<green>Unlocked" : shown == level + 1 ? "<gold>Next" : "<dark_gray>Locked");
        lore.add("");
        lore.add("<gray>Unlocks:");
        for (String what : t.unlocks(shown)) lore.add("<white>" + what);
        if (shown > level) {
            BaseLevelRules.Cost cost = t.cost(shown);
            lore.add("");
            lore.add("<gray>Needs <white>" + cost.deliveries() + "<gray> Agent deliveries");
            lore.add(MONEY + NetEconomy.money(cost.money()) + " <dark_gray>+ " + ESSENCE + cost.essence() + " Mob Essence");
        }
        Material icon = shown <= level ? Material.LIME_CONCRETE : shown == level + 1 ? Material.ORANGE_CONCRETE : Material.GRAY_CONCRETE;
        ItemStack it = Ui.item(icon, "<white><bold>Level " + shown, lore.toArray(String[]::new));
        it.setAmount(shown);
        return it;
    }

    private static ItemStack jobIcon(BaseLevelRules.Table t, int level, Robots.Job job) {
        String name = job.name().toLowerCase(java.util.Locale.ROOT);
        if (!t.jobUnlocked(level, name))
            return Ui.item(Material.GRAY_DYE, "<gray><bold>" + job.label, "<gray>" + job.desc, "", "<red>Needs Base Level " + t.jobLevel(name));
        return Ui.item(job.icon, job.color + "<bold>" + job.label, "<gray>" + job.desc, "", "<green>Unlocked", "<dark_gray>Right-click an Agent to give it this job");
    }

    private static ItemStack levelUpIcon(BaseLevelRules.Table t, int level, long delivered) {
        if (level == BaseLevelRules.MAX_LEVEL) return Ui.item(Material.NETHER_STAR, "<gold><bold>Top Level", "<gray>This base has unlocked everything.");
        BaseLevelRules.Cost cost = t.cost(level + 1);
        String price = MONEY + NetEconomy.money(cost.money()) + " <dark_gray>+ " + ESSENCE + cost.essence() + " Mob Essence";
        if (t.canLevelUp(level, delivered))
            return Ui.item(Material.ANVIL, "<green><bold>Level Up to " + (level + 1), price, "", "<yellow>Click to level up");
        return Ui.item(Material.ANVIL, "<gray><bold>Level Up to " + (level + 1), "<red>" + (cost.deliveries() - delivered) + " more Agent deliveries needed", price);
    }

    private void levelUp(Player p, Location at) {
        String region = station.regionAt(at);
        if (region == null) { p.sendActionBar(Ui.mm("<red>This Forge isn't on a claim, so it can't level up.")); return; }
        int level = levels.level(region);   // read now: someone else may have leveled it up with this menu open
        BaseLevelRules.Table t = levels.table();
        if (level >= BaseLevelRules.MAX_LEVEL) { p.sendActionBar(Ui.mm("<gold>This base is already at the top level.")); return; }
        BaseLevelRules.Cost cost = t.cost(level + 1);
        long delivered = levels.delivered(region);
        if (!t.canLevelUp(level, delivered)) {
            p.sendActionBar(Ui.mm("<red>You need " + (cost.deliveries() - delivered) + " more Agent deliveries."));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
            return;
        }
        if (!station.pay(p, cost.money(), cost.essence())) return;
        int reached = level + 1;
        levels.setLevel(region, reached);
        p.playSound(at, Sound.BLOCK_ANVIL_USE, 0.8f, 0.8f);
        p.playSound(at, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.1f);
        at.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, at.clone().add(0.5, 1, 0.5), 40, 0.4, 0.5, 0.4, 0.05);
        p.showTitle(Title.title(Ui.mm("<gradient:#c8743a:#4fd1b5><bold>Base Level " + reached + "</bold></gradient>"), Ui.mm("<gray>Unlocked: <white>" + String.join(", ", t.unlocks(reached)))));
        p.sendMessage(Ui.mm("<#4fd1b5>Your base reached Level " + reached + ". <gray>Unlocked: <white>" + String.join(", ", t.unlocks(reached))));
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        // the Forge can be broken, or the player's access lost, while this stays open
        if (!station.usable(p, m.at)) { p.closeInventory(); return; }
        int slot = e.getRawSlot();
        if (slot == AGENTS) { station.openForge(p, m.at); return; }
        if (slot == STORAGE) { station.openStorage(p, m.at); return; }
        if (slot == LEVEL_UP) { levelUp(p, m.at); open(p, m.at); return; }
        Allays allays = station.allays();
        if (allays != null && allays.available(p)) for (int s : ALLAY_SLOTS) if (slot == s) { allays.openMenu(p, m.at); return; }
        String region = station.regionAt(m.at);
        if (station.sellsClaimTiers(region)) for (int i = 0; i < HOPPER_SLOTS.length; i++) if (slot == HOPPER_SLOTS[i]) { station.buyClaimTier(p, m.at, i + 2); open(p, m.at); return; }
        for (int i = 0; i < JOB_SLOTS.length; i++) if (slot == JOB_SLOTS[i]) {
            String job = JOB_ORDER[i].name().toLowerCase(java.util.Locale.ROOT);
            BaseLevelRules.Table t = levels.table();
            if (t.jobUnlocked(levels.level(region), job)) p.sendActionBar(Ui.mm("<gray>Right-click an Agent to give it this job."));
            else p.sendActionBar(Ui.mm("<red>" + JOB_ORDER[i].label + " Agents need Base Level " + t.jobLevel(job) + "."));
            return;
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true); }
}
