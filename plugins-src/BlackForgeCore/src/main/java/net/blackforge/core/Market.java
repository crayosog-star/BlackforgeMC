package net.blackforge.core;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The Trading Post: players sell cosmetics they don't want to other players for Forge Dust. Only the permanent Cosmetic
 * Crate pool can be traded - limited-time, Founder and Champion cosmetics never can, so they stay special.
 * <p>
 * A cosmetic leaves your collection when you list it (held for you) and comes back if the listing is cancelled or runs out
 * after 7 days. 5% of every sale is taken as a fee, which takes Forge Dust out of the economy. Listings are one shared
 * file written under a lock, so two servers can never sell the same item twice.
 */
final class Market implements Listener {
    static final int MAX_LISTINGS = 5, DAYS = 7, FEE_PERCENT = 5, MIN_PRICE = 5, MAX_PRICE = 20_000;

    private final BlackForgeCore plugin;
    private final Cosmetics cos;
    private final File file;
    private final Map<UUID, String> confirm = new HashMap<>();

    Market(BlackForgeCore plugin, Cosmetics cos, File shared) {
        this.plugin = plugin;
        this.cos = cos;
        this.file = new File(shared, "market/listings.yml");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::expire, 1200L, 12_000L);
        Bukkit.getCommandMap().register("blackforge", new Command("market", "Buy and sell cosmetics", "/market [sell <cosmetic> <price>]", List.of("tradingpost", "cosmarket")) {
            @Override public boolean execute(CommandSender s, String label, String[] a) {
                if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
                if (a.length == 3 && a[0].equalsIgnoreCase("sell")) {
                    try { sell(p, a[1], Integer.parseInt(a[2])); } catch (NumberFormatException ex) { p.sendMessage(Ui.mm("<red>The price must be a number of Forge Dust.")); }
                } else browse(p, 0, null);
                return true;
            }
            @Override public List<String> tabComplete(CommandSender s, String alias, String[] a) {
                if (a.length == 1) return List.of("sell");
                if (a.length == 2 && a[0].equalsIgnoreCase("sell") && s instanceof Player p) return tradable(p).stream().map(Cosmetics.Cosmetic::id).toList();
                return List.of();
            }
        });
    }

    static boolean tradable(Cosmetics.Cosmetic c) { return c != null && c.crate() && c.season() == null && !Cosmetics.awardOnly(c); }

    List<Cosmetics.Cosmetic> tradable(Player p) {
        List<String> owned = cos.ownedIds(p.getUniqueId());
        return Cosmetics.ALL.stream().filter(c -> tradable(c) && owned.contains(c.id())).toList();
    }

    /** What a cosmetic of this rarity costs to craft: the price floor we suggest. */
    static int suggested(Cosmetics.Cosmetic c) { return c.rarity().craft; }

    // ------------------------------------------------------------------ data

    private record Listing(String id, UUID seller, String sellerName, String cosmetic, int price, long at) {}

    private List<Listing> listings() {
        YamlConfiguration y = Shared.read(file);
        ConfigurationSection sec = y.getConfigurationSection("listings");
        List<Listing> out = new ArrayList<>();
        if (sec == null) return out;
        for (String id : sec.getKeys(false)) {
            try { out.add(new Listing(id, UUID.fromString(sec.getString(id + ".seller", "")), sec.getString(id + ".name", "?"), sec.getString(id + ".cosmetic", ""), sec.getInt(id + ".price"), sec.getLong(id + ".at"))); }
            catch (IllegalArgumentException ignored) { }
        }
        out.sort(Comparator.comparingLong(Listing::at).reversed());
        return out;
    }

    // ------------------------------------------------------------------ selling

    void sell(Player p, String cosmeticId, int price) {
        Cosmetics.Cosmetic c = Cosmetics.byId(cosmeticId);
        if (!tradable(c)) { p.sendMessage(Ui.mm("<red>That one can't be traded. Only permanent Cosmetic Crate items can.")); return; }
        if (price < MIN_PRICE || price > MAX_PRICE) { p.sendMessage(Ui.mm("<red>Price it between " + MIN_PRICE + " and " + MAX_PRICE + " Forge Dust.")); return; }
        if (!cos.ownedIds(p.getUniqueId()).contains(c.id())) { p.sendMessage(Ui.mm("<red>You don't own that.")); return; }
        long mine = listings().stream().filter(l -> l.seller.equals(p.getUniqueId())).count();
        if (mine >= MAX_LISTINGS) { p.sendMessage(Ui.mm("<red>You can have " + MAX_LISTINGS + " listings at a time. Cancel one first.")); return; }
        if (!cos.takeOwned(p.getUniqueId(), c.id())) { p.sendMessage(Ui.mm("<red>You don't own that.")); return; }
        String id = Long.toString(System.currentTimeMillis(), 36) + Integer.toString(new Random().nextInt(1296), 36);
        Shared.update(file, y -> {
            y.set("listings." + id + ".seller", p.getUniqueId().toString());
            y.set("listings." + id + ".name", p.getName());
            y.set("listings." + id + ".cosmetic", c.id());
            y.set("listings." + id + ".price", price);
            y.set("listings." + id + ".at", System.currentTimeMillis());
        });
        p.sendMessage(Ui.mm("<green>✔ <gray>Listed " + c.name() + " <gray>for <#ffb347>" + price + " Forge Dust<gray> (you keep " + (price - fee(price)) + " after the " + FEE_PERCENT + "% fee). It's held for you for " + DAYS + " days."));
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.7f, 1.2f);
    }

    static int fee(int price) { return (int) Math.ceil(price * FEE_PERCENT / 100.0); }

    private void cancel(Player p, Listing l) {
        if (!take(l.id)) { p.sendMessage(Ui.mm("<red>That listing is already gone.")); return; }
        cos.unlock(l.seller, l.cosmetic);
        p.sendMessage(Ui.mm("<green>✔ <gray>Listing cancelled - " + nameOf(l.cosmetic) + " <gray>is back in your collection."));
    }

    /** Remove a listing; true only for the one server that removed it. */
    private boolean take(String id) {
        AtomicBoolean got = new AtomicBoolean();
        Shared.update(file, y -> { if (y.contains("listings." + id)) { y.set("listings." + id, null); got.set(true); } });
        return got.get();
    }

    private void buy(Player p, Listing l) {
        if (l.seller.equals(p.getUniqueId())) { p.sendMessage(Ui.mm("<red>That's your own listing - use the My Listings page to cancel it.")); return; }
        if (cos.ownedIds(p.getUniqueId()).contains(l.cosmetic)) { p.sendMessage(Ui.mm("<red>You already own that one.")); return; }
        if (cos.dust(p.getUniqueId()) < l.price) { p.sendMessage(Ui.mm("<red>That costs " + l.price + " Forge Dust. You have " + cos.dust(p.getUniqueId()) + ".")); return; }
        if (!take(l.id)) { p.sendMessage(Ui.mm("<red>Someone just bought it.")); return; }
        cos.addDust(p.getUniqueId(), -l.price);
        cos.addDust(l.seller, l.price - fee(l.price));
        cos.unlock(p, l.cosmetic);
        p.sendMessage(Ui.mm("<green>✔ <gray>You bought " + nameOf(l.cosmetic) + " <gray>for <#ffb347>" + l.price + " Forge Dust<gray>. <yellow>/cosmetics<gray> to wear it."));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
        cos.addNotice(l.seller, "<gold>✦ <gray>Your " + nameOf(l.cosmetic) + " <gray>sold to <white>" + p.getName() + "<gray> for <#ffb347>" + (l.price - fee(l.price)) + " Forge Dust<gray> after the fee.");
        Player seller = Bukkit.getPlayer(l.seller);
        if (seller != null) cos.deliverNotices(seller);
    }

    private static String nameOf(String id) {
        Cosmetics.Cosmetic c = Cosmetics.byId(id);
        return c == null ? id : c.name();
    }

    /** Listings that sat for a week go back to their seller. */
    private void expire() {
        long old = System.currentTimeMillis() - DAYS * 86_400_000L;
        for (Listing l : listings()) {
            if (l.at >= old || !take(l.id)) continue;
            cos.unlock(l.seller, l.cosmetic);
            cos.addNotice(l.seller, "<gray>Nobody bought your " + nameOf(l.cosmetic) + " <gray>in " + DAYS + " days - it's back in your collection.");
            Player online = Bukkit.getPlayer(l.seller);
            if (online != null) cos.deliverNotices(online);
        }
    }

    // ------------------------------------------------------------------ menus

    private static final class Menu implements InventoryHolder {
        Inventory inv;
        final Map<Integer, Consumer<Player>> actions = new HashMap<>();
        @Override public Inventory getInventory() { return inv; }
    }

    private Menu menu(String title, int rows) {
        Menu m = new Menu();
        m.inv = Bukkit.createInventory(m, rows * 9, Ui.mm("<dark_gray>" + title));
        return m;
    }

    private void bar(Menu m, Player p, Runnable back) {
        ItemStack pane = Ui.item(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < 54; i++) m.inv.setItem(i, pane);
        m.inv.setItem(49, Ui.item(Material.GLOWSTONE_DUST, "<#ffb347><bold>Your Forge Dust", "<white>" + cos.dust(p.getUniqueId()), "", "<gray>Duplicates from the crate turn into dust."));
        if (back != null) { m.inv.setItem(48, Ui.item(Material.ARROW, "<white>Back")); m.actions.put(48, pl -> back.run()); }
    }

    void browse(Player p, int page, Cosmetics.Kind kind) {
        List<Listing> all = listings().stream().filter(l -> kind == null || (Cosmetics.byId(l.cosmetic) != null && Cosmetics.byId(l.cosmetic).kind() == kind)).toList();
        Menu m = menu("Trading Post", 6);
        bar(m, p, null);
        int per = 45, pages = Math.max(1, (all.size() + per - 1) / per);
        page = Math.max(0, Math.min(page, pages - 1));
        for (int i = 0; i < per && page * per + i < all.size(); i++) {
            Listing l = all.get(page * per + i);
            Cosmetics.Cosmetic c = Cosmetics.byId(l.cosmetic);
            if (c == null) continue;
            boolean own = l.seller.equals(p.getUniqueId());
            String pending = confirm.get(p.getUniqueId());
            boolean sure = l.id.equals(pending);
            ItemStack it = Ui.item(c.icon(), c.name(), c.rarity().color + c.rarity().label + " <dark_gray>- <gray>" + c.kind().title,
                    "<gray>" + c.lore(), "", "<gray>Seller: <white>" + l.sellerName, "<gray>Price: <#ffb347>" + l.price + " Forge Dust", "",
                    own ? "<gray>Your listing" : sure ? "<green>Click again to buy" : "<yellow>Click to buy");
            if (sure) it.editMeta(x -> x.setEnchantmentGlintOverride(true));
            final int pg = page;
            m.inv.setItem(i, it);
            m.actions.put(i, pl -> {
                if (own) return;
                if (l.id.equals(confirm.get(pl.getUniqueId()))) { confirm.remove(pl.getUniqueId()); buy(pl, l); browse(pl, pg, kind); }
                else { confirm.put(pl.getUniqueId(), l.id); browse(pl, pg, kind); }
            });
        }
        if (all.isEmpty()) m.inv.setItem(22, Ui.item(Material.BARRIER, "<gray>Nothing for sale here", "<gray>Be the first - use <white>Sell a cosmetic<gray>."));
        final int pg = page;
        if (page > 0) { m.inv.setItem(45, Ui.item(Material.ARROW, "<white>Previous page")); m.actions.put(45, pl -> browse(pl, pg - 1, kind)); }
        if (page + 1 < pages) { m.inv.setItem(53, Ui.item(Material.ARROW, "<white>Next page")); m.actions.put(53, pl -> browse(pl, pg + 1, kind)); }
        Cosmetics.Kind[] kinds = Cosmetics.Kind.values();
        Cosmetics.Kind next = kind == null ? kinds[0] : kind.ordinal() + 1 < kinds.length ? kinds[kind.ordinal() + 1] : null;
        m.inv.setItem(46, Ui.item(Material.HOPPER, "<white><bold>Filter: " + (kind == null ? "everything" : kind.title), "<gray>Click to show: " + (next == null ? "everything" : next.title)));
        m.actions.put(46, pl -> browse(pl, 0, next));
        m.inv.setItem(47, Ui.item(Material.EMERALD, "<green><bold>Sell a cosmetic", "<gray>List one you own for Forge Dust.", "<gray>Fee: " + FEE_PERCENT + "%"));
        m.actions.put(47, pl -> picker(pl, 0));
        m.inv.setItem(51, Ui.item(Material.CHEST, "<aqua><bold>My listings", "<gray>See and cancel your own."));
        m.actions.put(51, this::mine);
        m.inv.setItem(52, Ui.item(Material.OAK_DOOR, "<white>Close"));
        m.actions.put(52, Player::closeInventory);
        p.openInventory(m.inv);
    }

    private void mine(Player p) {
        Menu m = menu("My Listings", 6);
        bar(m, p, () -> browse(p, 0, null));
        List<Listing> mine = listings().stream().filter(l -> l.seller.equals(p.getUniqueId())).toList();
        for (int i = 0; i < mine.size() && i < 45; i++) {
            Listing l = mine.get(i);
            Cosmetics.Cosmetic c = Cosmetics.byId(l.cosmetic);
            if (c == null) continue;
            long left = Math.max(0, (l.at + DAYS * 86_400_000L - System.currentTimeMillis()) / 3_600_000L);
            m.inv.setItem(i, Ui.item(c.icon(), c.name(), "<gray>Price: <#ffb347>" + l.price + " Forge Dust", "<gray>Runs out in <white>" + left + "h", "", "<red>Click to cancel"));
            m.actions.put(i, pl -> { cancel(pl, l); mine(pl); });
        }
        if (mine.isEmpty()) m.inv.setItem(22, Ui.item(Material.BARRIER, "<gray>You have nothing listed"));
        p.openInventory(m.inv);
    }

    private void picker(Player p, int page) {
        Menu m = menu("Sell which one?", 6);
        bar(m, p, () -> browse(p, 0, null));
        List<Cosmetics.Cosmetic> have = tradable(p);
        int per = 45, pages = Math.max(1, (have.size() + per - 1) / per);
        page = Math.max(0, Math.min(page, pages - 1));
        for (int i = 0; i < per && page * per + i < have.size(); i++) {
            Cosmetics.Cosmetic c = have.get(page * per + i);
            m.inv.setItem(i, Ui.item(c.icon(), c.name(), c.rarity().color + c.rarity().label, "", "<yellow>Click to choose a price"));
            m.actions.put(i, pl -> price(pl, c));
        }
        if (have.isEmpty()) m.inv.setItem(22, Ui.item(Material.BARRIER, "<gray>Nothing you own can be traded", "<gray>Only permanent Cosmetic Crate items can."));
        final int pg = page;
        if (page > 0) { m.inv.setItem(45, Ui.item(Material.ARROW, "<white>Previous page")); m.actions.put(45, pl -> picker(pl, pg - 1)); }
        if (page + 1 < pages) { m.inv.setItem(53, Ui.item(Material.ARROW, "<white>Next page")); m.actions.put(53, pl -> picker(pl, pg + 1)); }
        p.openInventory(m.inv);
    }

    private void price(Player p, Cosmetics.Cosmetic c) {
        Menu m = menu("Price for " + plain(c.name()), 3);
        bar(m, p, () -> picker(p, 0));
        ItemStack pane = Ui.item(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) m.inv.setItem(i, pane);
        m.inv.setItem(4, Ui.item(c.icon(), c.name(), c.rarity().color + c.rarity().label, "<gray>Crafting one costs <#ffb347>" + suggested(c) + " Dust<gray>."));
        int base = suggested(c);
        int[] prices = {Math.max(MIN_PRICE, base / 2), base, base * 3 / 2, base * 2, base * 3};
        for (int i = 0; i < prices.length; i++) {
            int price = prices[i];
            m.inv.setItem(10 + i, Ui.item(Material.GLOWSTONE_DUST, "<#ffb347><bold>" + price + " Forge Dust", "<gray>You receive <white>" + (price - fee(price)) + "<gray> after the fee.", "", "<yellow>Click to list"));
            m.actions.put(10 + i, pl -> { pl.closeInventory(); sell(pl, c.id(), price); });
        }
        m.inv.setItem(26, Ui.item(Material.PAPER, "<white>Your own price", "<gray>Type <white>/market sell " + c.id() + " <number>"));
        p.openInventory(m.inv);
    }

    private static String plain(String mm) { return mm.replaceAll("<[^>]*>", ""); }

    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true); }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu m) || !(e.getWhoClicked() instanceof Player p)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        Consumer<Player> a = m.actions.get(e.getSlot());
        if (a == null) return;
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
        a.accept(p);
    }
}
