package net.blackforge.core.survival;

import net.blackforge.core.BlackForgeCore;
import net.blackforge.core.NetEconomy;
import net.blackforge.core.Ui;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/**
 * Player chest shops (Survival). Put a sign on the front of a chest that holds the item you want to trade and write:
 * <pre>
 *   [Shop]
 *   16            (how many per trade)
 *   B 100         (players buy 16 for $100) and/or  S 80  (players sell 16 to you for $80), e.g. "B 100 S 80"
 * </pre>
 * The shop trades whatever item is in the chest when the sign is made. Right-click the sign to buy, left-click to sell.
 * Money moves between the buyer and the owner (even when the owner is offline). Only the owner (or staff) can break it,
 * and chests stay safe inside the owner's claim.
 */
public final class ChestShops implements Listener {
    private final BlackForgeCore plugin;
    private final Claims claims;
    private final NamespacedKey ownerKey, itemKey, amountKey, buyKey, sellKey;

    ChestShops(BlackForgeCore plugin, Claims claims) {
        this.plugin = plugin;
        this.claims = claims;
        ownerKey = new NamespacedKey(BlackForgeCore.NS, "shop_owner");
        itemKey = new NamespacedKey(BlackForgeCore.NS, "shop_item");
        amountKey = new NamespacedKey(BlackForgeCore.NS, "shop_amount");
        buyKey = new NamespacedKey(BlackForgeCore.NS, "shop_buy");
        sellKey = new NamespacedKey(BlackForgeCore.NS, "shop_sell");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private static Chest chestBehind(Block sign) {
        if (!(sign.getBlockData() instanceof WallSign ws)) return null;
        Block b = sign.getRelative(ws.getFacing().getOppositeFace());
        return b.getState() instanceof Chest c ? c : null;
    }

    private static String plain(net.kyori.adventure.text.Component c) { return c == null ? "" : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c).trim(); }

    private static String itemName(ItemStack it) {
        if (it.hasItemMeta() && it.getItemMeta().hasDisplayName()) return plain(it.getItemMeta().displayName());
        String n = it.getType().name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCreate(SignChangeEvent e) {
        if (!plain(e.line(0)).equalsIgnoreCase("[shop]")) return;
        Player p = e.getPlayer();
        Chest chest = chestBehind(e.getBlock());
        if (chest == null) { p.sendMessage(Ui.mm("<red>Put the [Shop] sign on the front of a chest.")); return; }
        UUID owner = p.getUniqueId();
        if (!ownsShop(owner, e.getBlock(), chest)) {
            e.setCancelled(true);
            p.sendMessage(Ui.mm("<red>The sign and all of its stock must be inside your own claim."));
            return;
        }
        ItemStack item = null;
        for (ItemStack it : chest.getInventory().getContents()) if (it != null && !it.getType().isAir()) { item = it.clone(); break; }
        if (item == null) { p.sendMessage(Ui.mm("<red>Put the item you want to trade in the chest first.")); e.setCancelled(true); return; }
        int amount;
        try { amount = Math.max(1, Math.min(64 * 9, Integer.parseInt(plain(e.line(1))))); } catch (NumberFormatException ex) { p.sendMessage(Ui.mm("<red>Line 2: how many per trade, like <white>16")); e.setCancelled(true); return; }
        double buy = -1, sell = -1;
        String[] parts = plain(e.line(2)).toUpperCase().replace("$", "").split("\\s+");
        for (int i = 0; i + 1 < parts.length; i++) {
            if (parts[i].equals("B")) buy = NetEconomy.parsePrice(parts[i + 1], 1);
            if (parts[i].equals("S")) sell = NetEconomy.parsePrice(parts[i + 1], 1);
        }
        if (buy < 0 && sell < 0) { p.sendMessage(Ui.mm("<red>Line 3: prices, like <white>B 100 <red>(players buy) and/or <white>S 80 <red>(players sell)")); e.setCancelled(true); return; }
        item.setAmount(1);
        e.line(0, Ui.mm("<dark_blue><bold>[Shop]"));
        e.line(1, Ui.mm("<black>" + amount + "x " + itemName(item)));
        e.line(2, Ui.mm((buy >= 0 ? "<dark_green>B " + NetEconomy.money(buy) : "") + (buy >= 0 && sell >= 0 ? " " : "") + (sell >= 0 ? "<dark_red>S " + NetEconomy.money(sell) : "")));
        e.line(3, Ui.mm("<dark_gray>" + p.getName()));
        double fb = buy, fs = sell;
        ItemStack fi = item;
        Bukkit.getScheduler().runTask(plugin, () -> {
            Chest stock = chestBehind(e.getBlock());
            if (!(e.getBlock().getState() instanceof Sign s) || stock == null || !ownsShop(owner, e.getBlock(), stock)) return;
            var pdc = s.getPersistentDataContainer();
            pdc.set(ownerKey, PersistentDataType.STRING, p.getUniqueId().toString());
            pdc.set(itemKey, PersistentDataType.STRING, Base64.getEncoder().encodeToString(fi.serializeAsBytes()));
            pdc.set(amountKey, PersistentDataType.INTEGER, amount);
            pdc.set(buyKey, PersistentDataType.DOUBLE, fb);
            pdc.set(sellKey, PersistentDataType.DOUBLE, fs);
            s.setWaxed(true);
            s.update();
        });
        p.sendMessage(Ui.mm("<green>Shop created! <gray>Right-click the sign to buy, left-click to sell. Keep the chest stocked" + (sell >= 0 ? " (and keep money for buying)" : "") + "."));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.4f);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null || e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND || !(b.getState() instanceof Sign s)) return;
        var pdc = s.getPersistentDataContainer();
        String owner = pdc.get(ownerKey, PersistentDataType.STRING);
        if (owner == null) return;
        Player p = e.getPlayer();
        boolean buying = e.getAction() == Action.RIGHT_CLICK_BLOCK, selling = e.getAction() == Action.LEFT_CLICK_BLOCK;
        if (!buying && !selling) return;
        if (selling && p.getUniqueId().toString().equals(owner) && p.isSneaking()) return;   // the owner can still break it
        e.setCancelled(true);
        Chest chest = chestBehind(b);
        if (chest == null) { p.sendMessage(Ui.mm("<red>This shop's chest is gone.")); return; }
        UUID ownerId = UUID.fromString(owner);
        if (!ownsShop(ownerId, b, chest)) {
            p.sendMessage(Ui.mm("<red>The shop sign and stock must be inside the owner's claim."));
            return;
        }
        ItemStack item = ItemStack.deserializeBytes(Base64.getDecoder().decode(pdc.get(itemKey, PersistentDataType.STRING)));
        int amount = pdc.getOrDefault(amountKey, PersistentDataType.INTEGER, 1);
        double price = pdc.getOrDefault(buying ? buyKey : sellKey, PersistentDataType.DOUBLE, -1.0);
        OfflinePlayer own = Bukkit.getOfflinePlayer(ownerId);
        if (own.getUniqueId().equals(p.getUniqueId())) { p.sendMessage(Ui.mm("<gray>That's your shop. <white>Sneak + left-click <gray>to break it.")); return; }
        if (price < 0) { p.sendMessage(Ui.mm("<red>This shop doesn't " + (buying ? "sell" : "buy") + " items.")); return; }
        if (!NetEconomy.validPrice(price)) {
            p.sendMessage(Ui.mm("<red>This shop has an invalid price. Ask the owner to replace the sign."));
            return;
        }
        Inventory stock = chest.getInventory();
        if (buying) {
            if (count(stock, item) < amount) { p.sendMessage(Ui.mm("<red>This shop is out of stock.")); return; }
            if (!fits(p.getInventory(), item, amount)) { p.sendMessage(Ui.mm("<red>Make room in your inventory first.")); return; }
            if (!plugin.economy().withdraw(p, price)) { p.sendMessage(Ui.mm("<red>You need " + NetEconomy.money(price) + ".")); return; }
            if (!credit(p, own, price, p)) return;
            if (own.getPlayer() != null) net.blackforge.core.Hooks.stat(own.getPlayer(), "shop_sold", 1);
            remove(stock, item, amount);
            give(p.getInventory(), item, amount);
            p.sendMessage(Ui.mm("<green>Bought " + amount + "x " + itemName(item) + " for " + NetEconomy.money(price) + "."));
            if (own.getPlayer() != null) own.getPlayer().sendMessage(Ui.mm("<green>⚖ " + p.getName() + " bought " + amount + "x " + itemName(item) + " from your shop for " + NetEconomy.money(price) + "."));
        } else {
            if (count(p.getInventory(), item) < amount) { p.sendMessage(Ui.mm("<red>You need " + amount + "x " + itemName(item) + " to sell.")); return; }
            if (!fits(stock, item, amount)) { p.sendMessage(Ui.mm("<red>This shop's chest is full.")); return; }
            if (!plugin.economy().withdraw(own, price)) { p.sendMessage(Ui.mm("<red>The shop owner can't afford to buy right now.")); return; }
            if (!credit(own, p, price, p)) return;
            remove(p.getInventory(), item, amount);
            give(stock, item, amount);
            p.sendMessage(Ui.mm("<green>Sold " + amount + "x " + itemName(item) + " for " + NetEconomy.money(price) + "."));
        }
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_TRADE, 1f, 1.1f);
    }

    private boolean ownsShop(UUID owner, Block sign, Chest stock) {
        return owner.equals(claims.ownerAt(sign.getLocation())) && claims.ownsStock(owner, stock.getInventory());
    }

    private boolean credit(OfflinePlayer payer, OfflinePlayer payee, double price, Player customer) {
        if (plugin.economy().deposit(payee, price)) return true;
        if (!plugin.economy().deposit(payer, price)) plugin.getLogger().severe("Could not refund failed shop trade for " + payer.getUniqueId());
        customer.sendMessage(Ui.mm("<red>The shop payment could not be completed."));
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        Player p = e.getPlayer();
        for (Block s : signsOn(b)) {
            if (!(s.getState() instanceof Sign sign)) continue;
            String owner = sign.getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
            Chest stock = chestBehind(s);
            if (owner != null && stock != null && ownsShop(UUID.fromString(owner), s, stock)
                    && !owner.equals(p.getUniqueId().toString()) && !p.hasPermission("blackforge.admin")) {
                e.setCancelled(true);
                p.sendMessage(Ui.mm("<red>That's someone else's shop."));
                return;
            }
        }
    }

    /** The block itself if it's a sign, plus any shop signs stuck on it (when it's the chest). */
    private static List<Block> signsOn(Block b) {
        List<Block> out = new ArrayList<>();
        out.add(b);
        for (BlockFace f : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block s = b.getRelative(f);
            if (s.getBlockData() instanceof WallSign ws && ws.getFacing() == f) out.add(s);
        }
        return out;
    }

    private static int count(Inventory inv, ItemStack like) {
        int n = 0;
        for (ItemStack it : inv.getContents()) if (it != null && it.isSimilar(like)) n += it.getAmount();
        return n;
    }

    private static boolean fits(Inventory inv, ItemStack like, int amount) {
        int room = 0;
        for (ItemStack it : inv.getStorageContents()) {
            if (it == null || it.getType().isAir()) room += like.getMaxStackSize();
            else if (it.isSimilar(like)) room += like.getMaxStackSize() - it.getAmount();
        }
        return room >= amount;
    }

    private static void remove(Inventory inv, ItemStack like, int amount) {
        for (ItemStack it : inv.getContents()) {
            if (amount <= 0) break;
            if (it == null || !it.isSimilar(like)) continue;
            int t = Math.min(amount, it.getAmount());
            it.setAmount(it.getAmount() - t);
            amount -= t;
        }
    }

    private static void give(Inventory inv, ItemStack like, int amount) {
        while (amount > 0) {
            ItemStack it = like.clone();
            it.setAmount(Math.min(amount, like.getMaxStackSize()));
            amount -= it.getAmount();
            inv.addItem(it);
        }
    }
}
