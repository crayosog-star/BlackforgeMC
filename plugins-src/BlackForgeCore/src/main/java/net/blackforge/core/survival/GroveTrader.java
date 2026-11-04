package net.blackforge.core.survival;

import net.blackforge.core.BlackForgeCore;
import net.blackforge.core.NetEconomy;
import net.blackforge.core.Ui;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The Trader in the Gilded Grove: sells farmed goods for the player's Survival balance. The menu never holds player items;
 * it reads the inventory and removes what sold. Only plain stacks in the main inventory and hotbar count, so worn armour,
 * the offhand and anything with custom data (Mob Essence, named items, enchanted items) can't be sold by accident.
 */
final class GroveTrader implements Listener {
    private static final int SELL_ALL_SLOT = 49, BALANCE_SLOT = 45, ROW_SLOTS = 45;

    private record Offer(Material material, SellCatalog.Item item, double price) {}

    private static final class Menu implements InventoryHolder {
        Inventory inv;
        final List<Offer> offers = new ArrayList<>();
        public Inventory getInventory() { return inv; }
    }

    private final BlackForgeCore plugin;
    private final File file;
    private final YamlConfiguration data;
    private final List<Offer> offers = new ArrayList<>();
    private final boolean enabled;
    private final int softCap;
    private final double overRate;
    private final NamespacedKey frameTag;

    GroveTrader(BlackForgeCore plugin) {
        this.plugin = plugin;
        this.frameTag = new NamespacedKey(BlackForgeCore.NS, "grove_frame");
        this.file = new File(plugin.getDataFolder(), "grove-sales.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        var cfg = plugin.getConfig();
        enabled = cfg.getBoolean("grove-shop.enabled", true);
        softCap = cfg.getInt("grove-shop.daily-soft-cap", SellCatalog.DEFAULT_SOFT_CAP);
        overRate = cfg.getDouble("grove-shop.over-cap-rate", SellCatalog.DEFAULT_OVER_CAP_RATE);
        for (SellCatalog.Item it : SellCatalog.ITEMS) {
            Material m = Material.matchMaterial(it.id());
            if (m == null) { plugin.getLogger().warning("grove shop: unknown item " + it.id()); continue; }
            offers.add(new Offer(m, it, cfg.getDouble("grove-shop.prices." + it.id(), it.price())));
            if (offers.size() == ROW_SLOTS) break;
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private static long today() { return LocalDate.now(ZoneOffset.UTC).toEpochDay(); }
    private String key(UUID u, String id) { return today() + "." + u + "." + id; }
    private int sold(UUID u, String id) { return data.getInt(key(u, id)); }

    private static boolean plain(ItemStack s, Material m) { return s != null && s.getType() == m && s.isSimilar(new ItemStack(m)); }

    private static int held(PlayerInventory inv, Material m) {
        int n = 0;
        for (ItemStack s : inv.getStorageContents()) if (plain(s, m)) n += s.getAmount();
        return n;
    }

    void open(Player p) {
        if (!enabled) { p.sendMessage(Ui.mm("<gold>The Trader is closed right now.")); return; }
        Menu menu = new Menu();
        menu.offers.addAll(offers);
        Inventory inv = Bukkit.createInventory(menu, 54, Ui.mm("<dark_gray>Trader <gray>- sell your goods"));
        menu.inv = inv;
        UUID id = p.getUniqueId();
        for (int i = 0; i < offers.size(); i++) {
            Offer o = offers.get(i);
            int have = held(p.getInventory(), o.material()), left = Math.max(0, softCap - sold(id, o.item().id()));
            inv.setItem(i, Ui.item(o.material(), "<white>" + o.item().label(),
                    "<gray>Price: <gold>" + NetEconomy.money(o.price()) + " <gray>each",
                    "<gray>You have: <white>" + have,
                    "<gray>Full price left today: <white>" + left,
                    "<yellow>Click <gray>sells a stack, <yellow>shift-click <gray>sells all"));
        }
        ItemStack pane = Ui.item(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = ROW_SLOTS; i < 54; i++) inv.setItem(i, pane);
        inv.setItem(BALANCE_SLOT, Ui.item(Material.GOLD_INGOT, "<gold>Balance", "<white>" + NetEconomy.money(plugin.economy().balance(id))));
        inv.setItem(SELL_ALL_SLOT, Ui.item(Material.EMERALD, "<gradient:#ffd27a:#5fd38a><bold>Sell everything listed</bold></gradient>",
                "<gray>Sells every listed item in your", "<gray>inventory and hotbar. Armour, offhand", "<gray>and custom items are never touched."));
        p.openInventory(inv);
    }

    /** Takes up to {@code limit} of a material out of the main inventory and hotbar; returns how many were taken. */
    private static int take(PlayerInventory inv, Material m, int limit) {
        ItemStack[] all = inv.getStorageContents();
        int taken = 0;
        for (int i = 0; i < all.length && taken < limit; i++) {
            ItemStack s = all[i];
            if (!plain(s, m)) continue;
            int n = Math.min(s.getAmount(), limit - taken);
            if (n == s.getAmount()) all[i] = null; else s.setAmount(s.getAmount() - n);
            taken += n;
        }
        inv.setStorageContents(all);
        return taken;
    }

    /** Writes the day's totals. Sales made away from the menu call this now and then, not once a sale. */
    void saveSales() {
        for (String k : new ArrayList<>(data.getKeys(false))) if (!k.equals(String.valueOf(today()))) data.set(k, null);   // only today counts
        try { data.save(file); } catch (Exception ex) { plugin.getLogger().warning("grove shop save: " + ex); }
    }

    private Offer offerFor(Material m) {
        for (Offer o : offers) if (o.material() == m) return o;
        return null;
    }

    /** Does the Trader buy this right now? */
    boolean buys(Material m) { return enabled && offerFor(m) != null; }

    /** What this many are worth to this owner today, counting what they've already sold. 0 if the Trader doesn't buy it. */
    double saleValue(UUID owner, Material m, int amount) {
        Offer o = offerFor(m);
        return o == null ? 0 : saleValue(owner, o, amount);
    }

    /** Counts a sale towards this owner's daily total, the same total the Trader's menu uses. */
    void recordSale(UUID owner, Material m, int amount) {
        Offer o = offerFor(m);
        if (o != null) recordSale(owner, o, amount);
    }

    private double saleValue(UUID owner, Offer o, int amount) {
        return SellCatalog.payout(o.price(), sold(owner, o.item().id()), amount, softCap, overRate);
    }

    private void recordSale(UUID owner, Offer o, int amount) {
        data.set(key(owner, o.item().id()), sold(owner, o.item().id()) + amount);
    }

    /** Removes the items, pays once, records the day's total. Returns the money paid; if payment fails the items go back. */
    private double sell(Player p, Offer o, int limit) {
        int taken = take(p.getInventory(), o.material(), limit);
        if (taken == 0) return 0;
        UUID id = p.getUniqueId();
        double pay = saleValue(id, o, taken);
        if (!plugin.economy().deposit(p, pay)) {
            int left = taken;
            while (left > 0) {
                int n = Math.min(left, o.material().getMaxStackSize());
                p.getInventory().addItem(new ItemStack(o.material(), n)).values().forEach(rest -> p.getWorld().dropItemNaturally(p.getLocation(), rest));
                left -= n;
            }
            p.sendMessage(Ui.mm("<red>The sale failed, so your items were returned."));
            return 0;
        }
        recordSale(id, o, taken);
        plugin.getLogger().info("grove shop: " + p.getName() + " sold " + taken + " " + o.item().id() + " for " + pay);
        return pay;
    }

    private void finish(Player p, double total) {
        if (total <= 0) { p.sendMessage(Ui.mm("<gray>You have nothing of that to sell.")); p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1f); return; }
        saveSales();
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.8f, 1.2f);
        p.sendMessage(Ui.mm("<green>Sold for <gold>" + NetEconomy.money(total) + "<green>."));
    }

    // ------------------------------------------------------------------ the glow item frames at the centre of the pavilion

    private static Location at(World world, SellCatalog.FrameSlot s) {
        return new Location(world, KingdomLayout.GROVE_X + s.dx() + 0.5, KingdomLayout.GROVE_Y + s.dy() + 0.5, KingdomLayout.GROVE_Z + s.dz() + 0.5);
    }

    /** Puts back any frame that has gone missing; duplicates are removed. */
    void ensureFrames(World world) {
        Location centre = new Location(world, KingdomLayout.GROVE_X + 0.5, KingdomLayout.GROVE_Y + 4, KingdomLayout.GROVE_Z + 0.5);
        java.util.Set<Integer> found = new java.util.HashSet<>();
        for (Entity e : world.getNearbyEntities(centre, 7, 6, 7)) {
            Integer i = e.getPersistentDataContainer().get(frameTag, PersistentDataType.INTEGER);
            if (i != null && !found.add(i)) e.remove();
        }
        List<SellCatalog.FrameSlot> slots = SellCatalog.gallery();
        for (int i = 0; i < slots.size() && i < SellCatalog.ITEMS.size(); i++) {
            if (found.contains(i)) continue;
            Material m = Material.matchMaterial(SellCatalog.ITEMS.get(i).id());
            if (m == null) continue;
            SellCatalog.FrameSlot s = slots.get(i);
            int index = i;
            world.spawn(at(world, s), GlowItemFrame.class, f -> {
                f.setFacingDirection(BlockFace.valueOf(s.facing()), true);
                f.setItem(new ItemStack(m), false);
                f.setItemDropChance(0f);
                f.setFixed(true);
                f.setInvulnerable(true);
                f.setPersistent(true);
                f.getPersistentDataContainer().set(frameTag, PersistentDataType.INTEGER, index);
            });
        }
    }

    /** A small price tag over each frame. */
    void priceTags(World world, List<TextDisplay> into) {
        List<SellCatalog.FrameSlot> slots = SellCatalog.gallery();
        for (int i = 0; i < slots.size() && i < SellCatalog.ITEMS.size(); i++) {
            SellCatalog.Item it = SellCatalog.ITEMS.get(i);
            double price = plugin.getConfig().getDouble("grove-shop.prices." + it.id(), it.price());
            into.add(Ui.hologram(at(world, slots.get(i)).add(0, 0.72, 0), "<gold>" + NetEconomy.money(price), 0.55f));
        }
    }

    private boolean isFrame(Entity e) { return e instanceof GlowItemFrame && e.getPersistentDataContainer().has(frameTag, PersistentDataType.INTEGER); }

    @EventHandler
    public void onFrameClick(PlayerInteractEntityEvent e) {
        if (!isFrame(e.getRightClicked())) return;
        e.setCancelled(true);
        if (e.getHand() == org.bukkit.inventory.EquipmentSlot.HAND) open(e.getPlayer());
    }

    @EventHandler
    public void onFrameDamage(EntityDamageEvent e) { if (isFrame(e.getEntity())) e.setCancelled(true); }

    @EventHandler
    public void onFrameBreak(HangingBreakEvent e) { if (isFrame(e.getEntity())) e.setCancelled(true); }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        int slot = e.getRawSlot();
        double total = 0;
        if (slot == SELL_ALL_SLOT) {
            for (Offer o : m.offers) total += sell(p, o, Integer.MAX_VALUE);
        } else if (slot >= 0 && slot < m.offers.size()) {
            Offer o = m.offers.get(slot);
            total = sell(p, o, e.isShiftClick() ? Integer.MAX_VALUE : o.material().getMaxStackSize());
        } else return;
        finish(p, total);
        open(p);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true); }
}
