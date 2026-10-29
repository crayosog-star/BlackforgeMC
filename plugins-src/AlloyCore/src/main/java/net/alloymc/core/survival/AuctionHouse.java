package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.text.DecimalFormat;
import java.util.*;

/**
 * /ah - player auction house. List the item in your hand with /ah sell <price>; browse and buy
 * with /ah. Unsold items expire back to your collection box after 48 hours.
 */
public final class AuctionHouse implements Listener {
    static final class Listing {
        final String id; final UUID seller; final String sellerName; final ItemStack item; final double price; final long listedAt;
        Listing(String id, UUID seller, String sellerName, ItemStack item, double price, long listedAt) {
            this.id = id; this.seller = seller; this.sellerName = sellerName; this.item = item; this.price = price; this.listedAt = listedAt;
        }
    }

    private static final long EXPIRE_MS = 48L * 3600_000;
    private static final int MAX_LISTINGS = 10;
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.##");

    private final AlloyCore plugin;
    private final Economy eco;
    private final File file;
    private final LinkedHashMap<String, Listing> listings = new LinkedHashMap<>();
    private final Map<UUID, List<ItemStack>> collection = new HashMap<>();
    private final Map<UUID, Double> unpaidNotice = new HashMap<>();

    AuctionHouse(AlloyCore plugin, Economy eco) { this(plugin, eco, "auctions.yml"); }

    /** A separate auction house with its own listings file (Skyblock and OneBlock each have one, so items never cross). */
    AuctionHouse(AlloyCore plugin, Economy eco, String fileName) {
        this.plugin = plugin;
        this.eco = eco;
        this.file = new File(plugin.getDataFolder(), fileName);
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::expire, 1200L, 1200L);
    }

    public static String money(double d) { return "$" + MONEY.format(d); }

    // ------------------------------------------------------------------ storage

    private void load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        var ls = y.getConfigurationSection("listings");
        if (ls != null) for (String id : ls.getKeys(false)) {
            var s = ls.getConfigurationSection(id);
            listings.put(id, new Listing(id, UUID.fromString(s.getString("seller")), s.getString("name"),
                    ItemStack.deserializeBytes(Base64.getDecoder().decode(s.getString("item"))), s.getDouble("price"), s.getLong("at")));
        }
        var cs = y.getConfigurationSection("collection");
        if (cs != null) for (String u : cs.getKeys(false)) {
            List<ItemStack> l = new ArrayList<>();
            for (String b64 : cs.getStringList(u)) l.add(ItemStack.deserializeBytes(Base64.getDecoder().decode(b64)));
            collection.put(UUID.fromString(u), l);
        }
        var ps = y.getConfigurationSection("paid");
        if (ps != null) for (String u : ps.getKeys(false)) unpaidNotice.put(UUID.fromString(u), ps.getDouble(u));
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Listing l : listings.values()) {
            String b = "listings." + l.id + ".";
            y.set(b + "seller", l.seller.toString());
            y.set(b + "name", l.sellerName);
            y.set(b + "item", Base64.getEncoder().encodeToString(l.item.serializeAsBytes()));
            y.set(b + "price", l.price);
            y.set(b + "at", l.listedAt);
        }
        collection.forEach((u, items) -> y.set("collection." + u, items.stream().map(i -> Base64.getEncoder().encodeToString(i.serializeAsBytes())).toList()));
        unpaidNotice.forEach((u, d) -> y.set("paid." + u, d));
        try { y.save(file); } catch (Exception e) { plugin.getLogger().warning("auctions save failed: " + e.getMessage()); }
    }

    private void expire() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Iterator<Listing> it = listings.values().iterator(); it.hasNext(); ) {
            Listing l = it.next();
            if (now - l.listedAt < EXPIRE_MS) continue;
            it.remove();
            collection.computeIfAbsent(l.seller, k -> new ArrayList<>()).add(l.item);
            Player p = Bukkit.getPlayer(l.seller);
            if (p != null) p.sendMessage(Ui.mm(PREFIX + "<gray>Your listing expired - grab it from <white>/ah collect<gray>."));
            changed = true;
        }
        if (changed) save();
    }

    private static final String PREFIX = "<dark_gray>[<gold>AH<dark_gray>] ";

    // ------------------------------------------------------------------ commands

    public void command(Player p, String[] a) {
        String sub = a.length > 0 ? a[0].toLowerCase() : "";
        switch (sub) {
            case "sell", "list" -> sell(p, a);
            case "listings", "mine", "selling" -> openMine(p);
            case "collect", "claim", "expired" -> openCollection(p);
            case "help" -> help(p);
            default -> openBrowse(p, 0);
        }
    }

    private void help(Player p) {
        p.sendMessage(Ui.mm("<gold><bold>Auction House</bold>"));
        p.sendMessage(Ui.mm("<yellow>/ah <gray>browse and buy items"));
        p.sendMessage(Ui.mm("<yellow>/ah sell <price> <gray>sell the item in your hand"));
        p.sendMessage(Ui.mm("<yellow>/ah listings <gray>see / cancel your listings"));
        p.sendMessage(Ui.mm("<yellow>/ah collect <gray>pick up expired or cancelled items"));
    }

    private void sell(Player p, String[] a) {
        if (a.length < 2) { p.sendMessage(Ui.mm(PREFIX + "<gray>Hold an item and type <white>/ah sell <price>")); return; }
        double price;
        try { price = Double.parseDouble(a[1].replace(",", "").replace("$", "").toLowerCase().replace("k", "000").replace("m", "000000")); }
        catch (NumberFormatException e) { p.sendMessage(Ui.mm(PREFIX + "<red>That's not a valid price.")); return; }
        if (price < 1 || price > 100_000_000) { p.sendMessage(Ui.mm(PREFIX + "<red>Price must be between $1 and $100,000,000.")); return; }
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) { p.sendMessage(Ui.mm(PREFIX + "<red>Hold the item you want to sell.")); return; }
        long mine = listings.values().stream().filter(l -> l.seller.equals(p.getUniqueId())).count();
        if (mine >= MAX_LISTINGS) { p.sendMessage(Ui.mm(PREFIX + "<red>You can only have " + MAX_LISTINGS + " listings at once.")); return; }
        ItemStack item = hand.clone();
        p.getInventory().setItemInMainHand(null);
        String id = Long.toString(System.currentTimeMillis(), 36) + Integer.toString(new Random().nextInt(1296), 36);
        listings.put(id, new Listing(id, p.getUniqueId(), p.getName(), item, price, System.currentTimeMillis()));
        save();
        p.sendMessage(Ui.mm(PREFIX + "<green>Listed <white>" + item.getAmount() + "x " + itemName(item) + " <green>for <gold>" + money(price) + "<green>."));
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.7f, 1.2f);
    }

    private static String itemName(ItemStack it) {
        if (it.hasItemMeta() && it.getItemMeta().hasDisplayName())
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(it.getItemMeta().displayName());
        String n = it.getType().name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    // ------------------------------------------------------------------ menus

    private enum View { BROWSE, MINE, COLLECT, CONFIRM }

    private final class Menu implements InventoryHolder {
        final AuctionHouse owner = AuctionHouse.this;
        final View view; final int page; final List<String> ids = new ArrayList<>(); String confirmId; Inventory inv;
        Menu(View view, int page) { this.view = view; this.page = page; }
        public Inventory getInventory() { return inv; }
    }

    private ItemStack withLore(ItemStack base, List<String> lines) {
        ItemStack it = base.clone();
        it.editMeta(m -> {
            List<net.kyori.adventure.text.Component> lore = new ArrayList<>(m.hasLore() ? m.lore() : List.of());
            lore.add(Ui.mm("<dark_gray>" + "-".repeat(20)));
            for (String s : lines) lore.add(Ui.mm(s));
            m.lore(lore);
        });
        return it;
    }

    private static String timeLeft(Listing l) {
        long ms = Math.max(0, EXPIRE_MS - (System.currentTimeMillis() - l.listedAt));
        long h = ms / 3600_000, m = ms / 60_000 % 60;
        return h + "h " + m + "m";
    }

    private void frame(Inventory inv, int from) {
        ItemStack pane = Ui.item(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = from; i < from + 9; i++) inv.setItem(i, pane);
    }

    public void openBrowse(Player p, int page) {
        Menu m = new Menu(View.BROWSE, page);
        List<Listing> all = new ArrayList<>(listings.values());
        Collections.reverse(all);   // newest first
        int pages = Math.max(1, (all.size() + 44) / 45);
        page = Math.max(0, Math.min(page, pages - 1));
        m.inv = Bukkit.createInventory(m, 54, Ui.mm("<dark_gray>Auction House <gray>(" + (page + 1) + "/" + pages + ")"));
        for (int i = 0; i < 45 && page * 45 + i < all.size(); i++) {
            Listing l = all.get(page * 45 + i);
            m.ids.add(l.id);
            m.inv.setItem(i, withLore(l.item, List.of("<gray>Price: <gold>" + money(l.price), "<gray>Seller: <white>" + l.sellerName,
                    "<gray>Expires in: <white>" + timeLeft(l), l.seller.equals(p.getUniqueId()) ? "<yellow>This is your listing" : "<green>» Click to buy")));
        }
        frame(m.inv, 45);
        if (page > 0) m.inv.setItem(45, Ui.item(Material.ARROW, "<yellow>« Previous page"));
        if (page < pages - 1) m.inv.setItem(53, Ui.item(Material.ARROW, "<yellow>Next page »"));
        m.inv.setItem(48, Ui.item(Material.CHEST, "<gold>Your listings", "<gray>See and cancel what you're selling"));
        m.inv.setItem(49, Ui.item(Material.GOLD_INGOT, "<gold>Your balance: <white>" + money(eco.getBalance(p)), "<gray>Sell with <white>/ah sell <price>"));
        m.inv.setItem(50, Ui.item(Material.ENDER_CHEST, "<gold>Collection box", "<gray>Expired and cancelled items"));
        p.openInventory(m.inv);
    }

    private void openMine(Player p) {
        Menu m = new Menu(View.MINE, 0);
        m.inv = Bukkit.createInventory(m, 27, Ui.mm("<dark_gray>Your listings"));
        int i = 0;
        for (Listing l : listings.values()) {
            if (!l.seller.equals(p.getUniqueId()) || i >= 18) continue;
            m.ids.add(l.id);
            m.inv.setItem(i++, withLore(l.item, List.of("<gray>Price: <gold>" + money(l.price), "<gray>Expires in: <white>" + timeLeft(l), "<red>» Click to cancel")));
        }
        frame(m.inv, 18);
        m.inv.setItem(22, Ui.item(Material.ARROW, "<yellow>« Back to the Auction House"));
        p.openInventory(m.inv);
    }

    private void openCollection(Player p) {
        Menu m = new Menu(View.COLLECT, 0);
        m.inv = Bukkit.createInventory(m, 27, Ui.mm("<dark_gray>Collection box"));
        List<ItemStack> items = collection.getOrDefault(p.getUniqueId(), List.of());
        for (int i = 0; i < items.size() && i < 18; i++) m.inv.setItem(i, withLore(items.get(i), List.of("<green>» Click to take")));
        frame(m.inv, 18);
        m.inv.setItem(22, Ui.item(Material.ARROW, "<yellow>« Back to the Auction House"));
        p.openInventory(m.inv);
    }

    private void openConfirm(Player p, Listing l) {
        Menu m = new Menu(View.CONFIRM, 0);
        m.confirmId = l.id;
        m.inv = Bukkit.createInventory(m, 27, Ui.mm("<dark_gray>Buy for " + money(l.price) + "?"));
        ItemStack pane = Ui.item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) m.inv.setItem(i, pane);
        m.inv.setItem(13, withLore(l.item, List.of("<gray>Price: <gold>" + money(l.price), "<gray>Seller: <white>" + l.sellerName)));
        for (int s : new int[]{10, 11, 19, 20}) m.inv.setItem(s, Ui.item(Material.LIME_STAINED_GLASS_PANE, "<green><bold>BUY</bold> <gray>for <gold>" + money(l.price)));
        for (int s : new int[]{15, 16, 24, 25}) m.inv.setItem(s, Ui.item(Material.RED_STAINED_GLASS_PANE, "<red><bold>CANCEL"));
        p.openInventory(m.inv);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Menu m && m.owner == this) e.setCancelled(true); }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m) || m.owner != this) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        int slot = e.getRawSlot();
        switch (m.view) {
            case BROWSE -> {
                if (slot == 45 && e.getCurrentItem() != null && e.getCurrentItem().getType() == Material.ARROW) openBrowse(p, m.page - 1);
                else if (slot == 53 && e.getCurrentItem() != null && e.getCurrentItem().getType() == Material.ARROW) openBrowse(p, m.page + 1);
                else if (slot == 48) openMine(p);
                else if (slot == 50) openCollection(p);
                else if (slot < m.ids.size()) {
                    Listing l = listings.get(m.ids.get(slot));
                    if (l == null) { p.sendMessage(Ui.mm(PREFIX + "<red>That item was already sold.")); openBrowse(p, m.page); return; }
                    if (l.seller.equals(p.getUniqueId())) { openMine(p); return; }
                    openConfirm(p, l);
                }
            }
            case MINE -> {
                if (slot == 22) { openBrowse(p, 0); return; }
                if (slot >= m.ids.size()) return;
                Listing l = listings.remove(m.ids.get(slot));
                if (l == null) { openMine(p); return; }
                give(p, l.item);
                save();
                p.sendMessage(Ui.mm(PREFIX + "<yellow>Listing cancelled - the item is back in your inventory."));
                openMine(p);
            }
            case COLLECT -> {
                if (slot == 22) { openBrowse(p, 0); return; }
                List<ItemStack> items = collection.get(p.getUniqueId());
                if (items == null || slot >= items.size() || slot >= 18) return;
                give(p, items.remove(slot));
                if (items.isEmpty()) collection.remove(p.getUniqueId());
                save();
                openCollection(p);
            }
            case CONFIRM -> {
                if (slot == 15 || slot == 16 || slot == 24 || slot == 25) { openBrowse(p, 0); return; }
                if (slot == 10 || slot == 11 || slot == 19 || slot == 20) buy(p, m.confirmId);
            }
        }
    }

    private void give(Player p, ItemStack it) {
        p.getInventory().addItem(it).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
    }

    private void buy(Player p, String id) {
        Listing l = listings.get(id);
        p.closeInventory();
        if (l == null) { p.sendMessage(Ui.mm(PREFIX + "<red>Too slow - that item was already sold.")); return; }
        if (!eco.has(p, l.price)) { p.sendMessage(Ui.mm(PREFIX + "<red>You need <gold>" + money(l.price) + "<red> (you have " + money(eco.getBalance(p)) + ").")); return; }
        if (p.getInventory().firstEmpty() == -1) { p.sendMessage(Ui.mm(PREFIX + "<red>Your inventory is full.")); return; }
        if (!eco.withdrawPlayer(p, l.price).transactionSuccess()) { p.sendMessage(Ui.mm(PREFIX + "<red>Payment failed.")); return; }
        listings.remove(id);
        OfflinePlayer seller = Bukkit.getOfflinePlayer(l.seller);
        eco.depositPlayer(seller, l.price);
        if (seller.getPlayer() != null) net.alloymc.core.Hooks.stat(seller.getPlayer(), "ah_sold", 1);
        give(p, l.item);
        Player sp = seller.getPlayer();
        if (sp != null) {
            sp.sendMessage(Ui.mm(PREFIX + "<green>" + p.getName() + " bought your <white>" + itemName(l.item) + " <green>for <gold>" + money(l.price) + "<green>!"));
            sp.playSound(sp.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        } else unpaidNotice.merge(l.seller, l.price, Double::sum);
        save();
        p.sendMessage(Ui.mm(PREFIX + "<green>Bought <white>" + l.item.getAmount() + "x " + itemName(l.item) + " <green>for <gold>" + money(l.price) + "<green>."));
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_TRADE, 0.8f, 1.1f);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Double earned = unpaidNotice.remove(p.getUniqueId());
        if (earned != null) {
            save();
            Bukkit.getScheduler().runTaskLater(plugin, () -> p.sendMessage(Ui.mm(PREFIX + "<green>While you were away you sold items for <gold>" + money(earned) + "<green>!")), 60L);
        }
        if (collection.containsKey(p.getUniqueId()))
            Bukkit.getScheduler().runTaskLater(plugin, () -> p.sendMessage(Ui.mm(PREFIX + "<gray>You have items waiting in <white>/ah collect<gray>.")), 80L);
    }

    public int count() { return listings.size(); }
}
