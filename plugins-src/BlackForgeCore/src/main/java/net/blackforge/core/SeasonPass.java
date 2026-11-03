package net.blackforge.core;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.*;

/**
 * The Season Pass: a track of 30 tiers that runs with each limited-time season. Season Points (from quests, KOTH, bosses,
 * arcade wins and the rest) carry you up the tiers, and every tier has a free reward and a Premium reward (keys, Forge
 * Dust and that season's own cosmetics). Premium comes with a Supporter rank, or on its own from the store.
 * <p>
 * Claims are per player and season in shared/pass/&lt;uuid&gt;.yml.
 */
final class SeasonPass implements Listener {
    static final int TIERS = 30;

    private record Tier(Quests.Reward reward, String cosmetic) {}

    private final BlackForgeCore plugin;
    private final Cosmetics cos;
    private final File dir;

    SeasonPass(BlackForgeCore plugin, Cosmetics cos, File shared) {
        this.plugin = plugin;
        this.cos = cos;
        this.dir = new File(shared, "pass");
        dir.mkdirs();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getCommandMap().register("blackforge", new Command("pass", "Your Season Pass", "/pass", List.of("battlepass", "seasonpass")) {
            @Override public boolean execute(CommandSender s, String label, String[] a) {
                if (s instanceof Player p) open(p, 0); else s.sendMessage("Players only.");
                return true;
            }
        });
        // for the store: Tebex runs "passgrant {username}" when someone buys the Premium pass
        Command grant = new Command("passgrant", "Give a player the Premium Season Pass", "/passgrant <player>", List.of()) {
            @Override public boolean execute(CommandSender s, String label, String[] a) {
                if (!s.hasPermission("blackforge.admin")) { s.sendMessage("No permission."); return true; }
                if (a.length < 1) { s.sendMessage("Usage: /passgrant <player> [take]"); return true; }
                var op = Bukkit.getOfflinePlayerIfCached(a[0]);
                if (op == null) { s.sendMessage("Never seen " + a[0] + " on the network."); return true; }
                Cosmetics.Season se = season();
                if (se == null) { s.sendMessage("No season is running right now."); return true; }
                boolean take = a.length > 1 && a[1].equalsIgnoreCase("take");
                Shared.update(file(op.getUniqueId()), y -> y.set("premium." + key(se), take ? null : true));
                s.sendMessage((take ? "Premium pass removed from " : "Premium pass given to ") + op.getName() + " for " + se.id());
                if (!take) cos.addNotice(op.getUniqueId(), "<gold>✦ <gray>Your <light_purple>Premium Season Pass<gray> is active! <yellow>/pass");
                Player online = op.getPlayer();
                if (online != null) cos.deliverNotices(online);
                return true;
            }
        };
        Bukkit.getCommandMap().register("blackforge", grant);
    }

    private File file(UUID u) { return new File(dir, u + ".yml"); }

    /** The season the pass is following: the first running event season. */
    Cosmetics.Season season() {
        for (Cosmetics.Season s : cos.liveSeasons()) if (!s.grantOnly()) return s;
        return null;
    }

    private static String key(Cosmetics.Season s) { return s.id() + "-" + s.start().getYear(); }

    int pointsPerTier() { return Math.max(5, cos.conf().getInt("pass.points-per-tier", 25)); }

    int tierOf(Cosmetics.Season s, UUID u) { return Math.min(TIERS, cos.board().pointsOf(s, u) / pointsPerTier()); }

    boolean premium(Player p, Cosmetics.Season s) {
        return Supporters.tierOf(p) != null || p.hasPermission("blackforge.pass.premium") || Shared.read(file(p.getUniqueId())).getBoolean("premium." + key(s));
    }

    // ------------------------------------------------------------------ the rewards

    private Tier free(Cosmetics.Season s, int t) {
        if (t % 10 == 0) return new Tier(new Quests.Reward(2, 50, 0), null);
        if (t % 5 == 0) return new Tier(new Quests.Reward(1, 25, 0), null);
        return new Tier(new Quests.Reward(0, 10 + t, 0), null);
    }

    private Tier premiumTier(Cosmetics.Season s, int t) {
        List<Cosmetics.Cosmetic> own = Cosmetics.ofSeason(s.id()).stream().filter(c -> !Cosmetics.awardOnly(c)).toList();
        int n = own.size();
        if (t == 30 && n > 0) return new Tier(new Quests.Reward(3, 100, 0), own.get(n - 1).id());
        if (t == 24 && n > 1) return new Tier(new Quests.Reward(1, 60, 0), own.get(Math.min(n - 1, 1)).id());
        if (t == 12 && n > 0) return new Tier(new Quests.Reward(1, 40, 0), own.get(0).id());
        if (t % 4 == 2) return new Tier(new Quests.Reward(1, 30, 0), null);
        return new Tier(new Quests.Reward(0, 25 + t, 0), null);
    }

    // ------------------------------------------------------------------ menu

    private static final class Menu implements InventoryHolder {
        Inventory inv;
        int page;
        Cosmetics.Season season;
        @Override public Inventory getInventory() { return inv; }
    }

    void open(Player p, int page) {
        Cosmetics.Season s = season();
        if (s == null) {
            p.sendMessage(Ui.mm("<gray>No season is running right now. The next Season Pass starts with the next event - <yellow>/cosmetics<gray> shows the calendar."));
            return;
        }
        Menu m = new Menu();
        m.season = s;
        m.page = Math.max(0, Math.min(page, (TIERS - 1) / 9));
        m.inv = Bukkit.createInventory(m, 54, Ui.mm("<dark_gray>" + s.name() + " Pass"));
        ItemStack pane = Ui.item(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) m.inv.setItem(i, pane);
        UUID u = p.getUniqueId();
        int points = cos.board().pointsOf(s, u), tier = tierOf(s, u), per = pointsPerTier();
        boolean prem = premium(p, s);
        YamlConfiguration y = Shared.read(file(u));
        List<Integer> freeDone = y.getIntegerList("claimed." + key(s) + ".free"), premDone = y.getIntegerList("claimed." + key(s) + ".premium");

        m.inv.setItem(4, Ui.item(Material.EXPERIENCE_BOTTLE, "<light_purple><bold>" + s.name() + " Pass",
                "<gray>Tier: <white>" + tier + "<gray>/" + TIERS,
                tier >= TIERS ? "<green>Every tier reached!" : "<gray>Next tier: <white>" + (points - tier * per) + "<gray>/" + per + " Season Points",
                "<gray>Ends: <white>" + s.end(), "",
                "<gray>Season Points come from quests, KOTH, boss hits,", "<gray>arcade wins, crates and events."));
        m.inv.setItem(13, prem
                ? Ui.item(Material.NETHER_STAR, "<light_purple><bold>Premium unlocked", "<gray>You get both rows of rewards.")
                : Ui.item(Material.GRAY_DYE, "<light_purple><bold>Premium Pass", "<gray>A second reward on every tier, including", "<gray>this season's own cosmetics.", "", "<gray>Included with any <gold>Supporter rank<gray>,", "<gray>or on its own: <white>/store"));

        for (int i = 0; i < 9; i++) {
            int t = m.page * 9 + i + 1;
            if (t > TIERS) break;
            boolean reached = tier >= t;
            m.inv.setItem(18 + i, Ui.item(reached ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE, (reached ? "<green>" : "<red>") + "Tier " + t,
                    reached ? "<gray>Reached" : "<gray>Needs <white>" + (t * per - points) + "<gray> more points"));
            m.inv.setItem(27 + i, rewardItem(free(s, t), t, reached, freeDone.contains(t), true, true));
            m.inv.setItem(36 + i, rewardItem(premiumTier(s, t), t, reached, premDone.contains(t), prem, false));
        }
        if (m.page > 0) m.inv.setItem(45, Ui.item(Material.ARROW, "<white>Previous tiers"));
        if ((m.page + 1) * 9 < TIERS) m.inv.setItem(53, Ui.item(Material.ARROW, "<white>More tiers"));
        m.inv.setItem(49, Ui.item(Material.OAK_DOOR, "<white>Close"));
        p.openInventory(m.inv);
    }

    private ItemStack rewardItem(Tier t, int tier, boolean reached, boolean claimed, boolean allowed, boolean free) {
        Cosmetics.Cosmetic c = t.cosmetic == null ? null : Cosmetics.byId(t.cosmetic);
        Material icon = c != null ? c.icon() : t.reward.keys() > 0 ? Material.TRIAL_KEY : Material.GLOWSTONE_DUST;
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + t.reward.text());
        if (c != null) lore.add("<gray>+ " + c.name() + " <dark_gray>(" + c.rarity().label + ")");
        lore.add("");
        lore.add(claimed ? "<green>Claimed" : !reached ? "<red>Locked" : !allowed ? "<light_purple>Needs the Premium Pass" : "<yellow>Click to claim");
        ItemStack it = Ui.item(claimed ? Material.CHEST_MINECART : icon, (free ? "<white>" : "<light_purple>") + "Tier " + tier, lore.toArray(new String[0]));
        if (reached && allowed && !claimed) it.editMeta(x -> x.setEnchantmentGlintOverride(true));
        return it;
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true); }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu m) || !(e.getWhoClicked() instanceof Player p)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getSlot();
        if (slot == 49) { p.closeInventory(); return; }
        if (slot == 45 && m.page > 0) { open(p, m.page - 1); return; }
        if (slot == 53 && (m.page + 1) * 9 < TIERS) { open(p, m.page + 1); return; }
        boolean freeRow = slot >= 27 && slot <= 35, premRow = slot >= 36 && slot <= 44;
        if (!freeRow && !premRow) return;
        int t = m.page * 9 + (slot - (freeRow ? 27 : 36)) + 1;
        if (t > TIERS) return;
        claim(p, m.season, t, freeRow);
        open(p, m.page);
    }

    private void claim(Player p, Cosmetics.Season s, int t, boolean free) {
        UUID u = p.getUniqueId();
        if (tierOf(s, u) < t) { p.sendMessage(Ui.mm("<red>You haven't reached tier " + t + " yet.")); return; }
        if (!free && !premium(p, s)) { p.sendMessage(Ui.mm("<light_purple>That reward needs the Premium Pass <gray>- it comes with any Supporter rank, or <white>/store<gray>.")); return; }
        String path = "claimed." + key(s) + "." + (free ? "free" : "premium");
        boolean[] first = {false};
        Shared.update(file(u), y -> {
            List<Integer> done = new ArrayList<>(y.getIntegerList(path));
            if (!done.contains(t)) { done.add(t); y.set(path, done); first[0] = true; }
        });
        if (!first[0]) return;
        Tier tier = free ? free(s, t) : premiumTier(s, t);
        tier.reward.give(cos, p);
        String extra = "";
        if (tier.cosmetic != null) {
            cos.unlock(p, tier.cosmetic);
            Cosmetics.Cosmetic c = Cosmetics.byId(tier.cosmetic);
            if (c != null) extra = " <gray>and <white>" + c.name();
        }
        p.sendMessage(Ui.mm("<light_purple>✦ <gray>Tier " + t + " reward: " + tier.reward.text() + extra));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
    }
}
