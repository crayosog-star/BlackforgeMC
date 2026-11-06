package net.blackforge.core.survival;

import net.blackforge.core.Lab;
import net.blackforge.core.NetEconomy;
import net.blackforge.core.Ui;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.*;

/**
 * Hearth Contracts (Survival 2 experiment "hearth-contracts"): buy orders. A buyer posts "64 obsidian at $30 each";
 * the money is taken up front (plus a small posting fee) and held. Anyone can fill some or all of it from /contracts
 * and is paid at once from the held money; the items wait in the buyer's collection box. Cancelling or expiry refunds
 * whatever wasn't spent (not the fee). Only plain items (no names, enchants or custom tags) can be ordered or delivered.
 */
final class HearthContracts implements Listener {
    static final String NAME = "<gradient:#e8b04a:#b5562b><bold>Hearth Contracts</bold></gradient>";
    private static final String ID = "hearth-contracts";

    private record Order(int id, UUID buyer, String buyerName, Material material, int amount, int delivered, double each, long created) {}

    private final LabExperiments.Ctx c;
    private final NetEconomy eco;
    private final File file;
    private YamlConfiguration data;
    private final int maxOpen, maxAmount, expireDays;
    private final double feePercent, feeMin;

    HearthContracts(LabExperiments.Ctx c) {
        this.c = c;
        this.eco = c.plugin().economy();
        this.file = new File(c.plugin().getDataFolder(), "lab/contracts.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        var cfg = c.plugin().getConfig();
        maxOpen = cfg.getInt("lab-experiments.hearth-contracts.max-open", 5);
        maxAmount = cfg.getInt("lab-experiments.hearth-contracts.max-amount", 2304);
        expireDays = cfg.getInt("lab-experiments.hearth-contracts.expire-days", 7);
        feePercent = cfg.getDouble("lab-experiments.hearth-contracts.fee-percent", 1.0);
        feeMin = cfg.getDouble("lab-experiments.hearth-contracts.fee-min", 25);
        Bukkit.getPluginManager().registerEvents(this, c.plugin());
        LabExperiments.command(c.plugin(), ID, "contracts", "Hearth Contracts: buy orders", List.of("contract", "orders"), this::command);
        Bukkit.getScheduler().runTaskTimer(c.plugin(), this::expire, 200L, 20L * 60);
    }

    // ------------------------------------------------------------------ commands

    private void command(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Players only."); return; }
        if (a.length == 0) { open(p); return; }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "post" -> post(p, a);
            case "collect" -> collect(p);
            case "cancel" -> {
                Order o = a.length > 1 ? order(parseInt(a[1])) : null;
                if (o == null || !o.buyer().equals(p.getUniqueId())) { p.sendMessage(Ui.mm("<red>/contracts cancel <your order number>")); return; }
                cancel(o, "cancelled");
            }
            default -> help(p);
        }
    }

    private void help(Player p) {
        p.sendMessage(Ui.mm(NAME));
        p.sendMessage(Ui.mm("<gray>/contracts <dark_gray>- <white>browse and fill orders"));
        p.sendMessage(Ui.mm("<gray>/contracts post <amount> <price each> <dark_gray>- <white>order the item in your hand"));
        p.sendMessage(Ui.mm("<gray>/contracts collect <dark_gray>- <white>pick up items delivered to you"));
        p.sendMessage(Ui.mm("<gray>/contracts cancel <number> <dark_gray>- <white>refund what's left (not the fee)"));
    }

    private void post(Player p, String[] a) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir() || !plain(hand)) { p.sendMessage(Ui.mm("<red>Hold a plain item (no name or enchants) to order more of it.")); return; }
        if (a.length < 3) { p.sendMessage(Ui.mm("<red>/contracts post <amount> <price each>")); return; }
        int amount = parseInt(a[1]);
        double each = NetEconomy.parseAmount(a[2]);
        if (amount < 1 || amount > maxAmount) { p.sendMessage(Ui.mm("<red>Order between 1 and " + maxAmount + ".")); return; }
        if (!NetEconomy.validPrice(each) || each < 0.01) { p.sendMessage(Ui.mm("<red>That price doesn't work.")); return; }
        if (open(p.getUniqueId()).size() >= maxOpen) { p.sendMessage(Ui.mm("<red>You already have " + maxOpen + " open orders.")); return; }
        double escrow = LabMath.contractEscrow(amount, each);
        double fee = LabMath.contractFee(escrow, feePercent, feeMin);
        if (!eco.withdraw(p, escrow + fee)) { p.sendMessage(Ui.mm("<red>You need " + NetEconomy.money(escrow + fee) + " (" + NetEconomy.money(escrow) + " held + " + NetEconomy.money(fee) + " fee).")); return; }
        int id = data.getInt("next", 1);
        data.set("next", id + 1);
        Order o = new Order(id, p.getUniqueId(), p.getName(), hand.getType(), amount, 0, each, System.currentTimeMillis());
        write(o);
        save();
        p.sendMessage(Ui.mm(NAME + " <gray>Order <white>#" + id + "<gray>: " + amount + " " + pretty(o.material()) + " at <white>"
                + NetEconomy.money(each) + "<gray> each. Held <white>" + NetEconomy.money(escrow) + "<gray>, fee " + NetEconomy.money(fee) + "."));
        p.playSound(p.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 1f, 1f);
        for (Player o2 : Bukkit.getOnlinePlayers())
            if (o2 != p) o2.sendMessage(Ui.mm("<gradient:#e8b04a:#b5562b>✦</gradient> <gray>" + p.getName() + " wants <white>" + amount + " " + pretty(o.material())
                    + "<gray> at " + NetEconomy.money(each) + " each. <u><click:run_command:'/contracts'><#e8b04a>[Fill it]</click></u>"));
    }

    /** Hand in what the seller carries, up to what the order still wants. They're paid at once. */
    private void fill(Player p, Order o) {
        if (o.buyer().equals(p.getUniqueId())) { LabExperiments.bar(p, "<gray>That's your own order. Shift-click to cancel it."); return; }
        int carried = 0;
        for (ItemStack s : p.getInventory().getStorageContents()) if (s != null && s.getType() == o.material() && plain(s)) carried += s.getAmount();
        int n = LabMath.contractFill(o.amount(), o.delivered(), carried);
        if (n <= 0) { LabExperiments.bar(p, "<gray>You aren't carrying any plain " + pretty(o.material()) + "."); p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f); return; }
        double pay = LabMath.round2(n * o.each());
        take(p, o.material(), n);
        if (!eco.deposit(p, pay)) {   // give the items straight back if the payment fails
            p.getInventory().addItem(new ItemStack(o.material(), n)).values().forEach(r -> p.getWorld().dropItemNaturally(p.getLocation(), r));
            p.sendMessage(Ui.mm("<red>Payment failed, so your items were returned."));
            return;
        }
        Order done = new Order(o.id(), o.buyer(), o.buyerName(), o.material(), o.amount(), o.delivered() + n, o.each(), o.created());
        String box = "waiting." + o.buyer() + "." + o.material().name();
        data.set(box, data.getInt(box) + n);
        if (done.delivered() >= done.amount()) data.set("orders." + o.id(), null); else write(done);
        save();
        p.sendMessage(Ui.mm(NAME + " <gray>Delivered <white>" + n + " " + pretty(o.material()) + "<gray> to " + o.buyerName() + " for <green>" + NetEconomy.money(pay) + "<gray>."));
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.8f, 1.2f);
        Player b = Bukkit.getPlayer(o.buyer());
        if (b != null) b.sendMessage(Ui.mm(NAME + " <white>" + p.getName() + " <gray>delivered " + n + " " + pretty(o.material())
                + (done.delivered() >= done.amount() ? " <#7bd88f>(order complete)" : " <dark_gray>(" + done.delivered() + "/" + done.amount() + ")")
                + " <u><click:run_command:'/contracts collect'><#e8b04a>[Collect]</click></u>"));
    }

    private void collect(Player p) {
        ConfigurationSection box = data.getConfigurationSection("waiting." + p.getUniqueId());
        if (box == null || box.getKeys(false).isEmpty()) { p.sendMessage(Ui.mm("<gray>Nothing waiting for you.")); return; }
        int given = 0; boolean full = false;
        for (String m : box.getKeys(false)) {
            Material mat = Material.matchMaterial(m);
            int left = box.getInt(m);
            if (mat == null) { box.set(m, null); continue; }
            while (left > 0) {
                int stack = Math.min(left, mat.getMaxStackSize());
                Map<Integer, ItemStack> rest = p.getInventory().addItem(new ItemStack(mat, stack));
                int back = rest.values().stream().mapToInt(ItemStack::getAmount).sum();
                given += stack - back;
                left -= stack - back;
                if (back > 0) { full = true; break; }
            }
            box.set(m, left > 0 ? left : null);
            if (full) break;
        }
        save();
        p.sendMessage(Ui.mm(NAME + " <gray>Collected <white>" + given + "<gray> items." + (full ? " <yellow>Inventory full: run it again for the rest." : "")));
    }

    private void cancel(Order o, String why) {
        double refund = LabMath.round2((o.amount() - o.delivered()) * o.each());
        OfflinePlayer b = Bukkit.getOfflinePlayer(o.buyer());
        if (refund > 0 && !eco.deposit(b, refund)) { c.plugin().getLogger().severe("contract refund failed #" + o.id() + " " + o.buyer() + " " + refund); return; }
        data.set("orders." + o.id(), null);
        save();
        Player p = b.getPlayer();
        if (p != null) p.sendMessage(Ui.mm(NAME + " <gray>Order <white>#" + o.id() + "<gray> " + why + ": refunded <green>" + NetEconomy.money(refund) + "<gray>."));
        c.plugin().getLogger().info("contract #" + o.id() + " " + why + ", refunded " + refund + " to " + o.buyerName());
    }

    private void expire() {
        if (!Lab.enabled(ID)) return;
        long cutoff = System.currentTimeMillis() - expireDays * 86_400_000L;
        for (Order o : all()) if (o.created() < cutoff) cancel(o, "expired");
    }

    // ------------------------------------------------------------------ menu

    private static final class Menu implements InventoryHolder {
        final Map<Integer, Integer> orderAt = new HashMap<>();
        Inventory inv;
        public Inventory getInventory() { return inv; }
    }

    private void open(Player p) {
        Menu m = new Menu();
        m.inv = Bukkit.createInventory(m, 54, Ui.mm(NAME));
        List<Order> list = all();
        list.sort(Comparator.comparingInt(Order::id).reversed());
        int slot = 0;
        for (Order o : list) {
            if (slot >= 45) break;
            boolean mine = o.buyer().equals(p.getUniqueId());
            ItemStack it = Ui.item(o.material(), "<white>" + pretty(o.material()) + " <dark_gray>#" + o.id(),
                    "<gray>Wanted by <white>" + o.buyerName(),
                    "<gray>Pays <green>" + NetEconomy.money(o.each()) + " <gray>each",
                    "<gray>Delivered <white>" + o.delivered() + "<gray>/" + o.amount(),
                    "<gray>Left to earn <green>" + NetEconomy.money(LabMath.round2((o.amount() - o.delivered()) * o.each())),
                    "",
                    mine ? "<#e5484d>Shift-click: cancel and refund" : "<#e8b04a>Click: deliver what you carry");
            it.setAmount(Math.max(1, Math.min(64, o.amount() - o.delivered())));
            if (mine) it.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
            m.inv.setItem(slot, it);
            m.orderAt.put(slot, o.id());
            slot++;
        }
        if (list.isEmpty()) m.inv.setItem(22, Ui.item(Material.PAPER, "<gray>No open orders", "<gray>Post one: hold an item and", "<white>/contracts post <amount> <price each>"));
        int waiting = 0;
        ConfigurationSection box = data.getConfigurationSection("waiting." + p.getUniqueId());
        if (box != null) for (String k : box.getKeys(false)) waiting += box.getInt(k);
        m.inv.setItem(48, Ui.item(Material.WRITABLE_BOOK, "<#e8b04a>Post an order", "<gray>Hold the item, then:", "<white>/contracts post <amount> <price each>",
                "<dark_gray>" + feePercent + "% fee (min " + NetEconomy.money(feeMin) + "), " + maxOpen + " open max"));
        m.inv.setItem(50, Ui.item(waiting > 0 ? Material.CHEST_MINECART : Material.MINECART, "<#e8b04a>Your deliveries",
                waiting > 0 ? "<white>" + waiting + " <gray>items waiting" : "<gray>Nothing waiting", "<gray>Click to collect"));
        p.openInventory(m.inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        if (!Lab.enabled(ID)) { p.closeInventory(); return; }
        int slot = e.getRawSlot();
        if (slot == 50) { collect(p); reopen(p); return; }
        Integer id = m.orderAt.get(slot);
        Order o = id == null ? null : order(id);
        if (o == null) { reopen(p); return; }
        if (o.buyer().equals(p.getUniqueId())) {
            if (e.getClick() == ClickType.SHIFT_LEFT || e.getClick() == ClickType.SHIFT_RIGHT) cancel(o, "cancelled");
            else LabExperiments.bar(p, "<gray>Shift-click your own order to cancel it.");
        } else fill(p, o);
        reopen(p);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true); }

    private void reopen(Player p) { Bukkit.getScheduler().runTask(c.plugin(), () -> { if (p.isOnline()) open(p); }); }

    // ------------------------------------------------------------------ storage

    private Order order(int id) {
        ConfigurationSection s = data.getConfigurationSection("orders." + id);
        if (s == null) return null;
        Material m = Material.matchMaterial(s.getString("material", ""));
        if (m == null) return null;
        return new Order(id, UUID.fromString(s.getString("buyer")), s.getString("name", "?"), m, s.getInt("amount"), s.getInt("delivered"),
                s.getDouble("each"), s.getLong("created"));
    }

    private List<Order> all() {
        List<Order> out = new ArrayList<>();
        ConfigurationSection s = data.getConfigurationSection("orders");
        if (s != null) for (String k : s.getKeys(false)) { Order o = order(parseInt(k)); if (o != null) out.add(o); }
        return out;
    }

    private List<Order> open(UUID buyer) { return all().stream().filter(o -> o.buyer().equals(buyer)).toList(); }

    private void write(Order o) {
        String k = "orders." + o.id();
        data.set(k + ".buyer", o.buyer().toString()); data.set(k + ".name", o.buyerName());
        data.set(k + ".material", o.material().name()); data.set(k + ".amount", o.amount());
        data.set(k + ".delivered", o.delivered()); data.set(k + ".each", o.each()); data.set(k + ".created", o.created());
    }

    private void save() {
        try { file.getParentFile().mkdirs(); data.save(file); }
        catch (Exception ex) { c.plugin().getLogger().warning("contracts.yml save failed: " + ex.getMessage()); }
    }

    // ------------------------------------------------------------------ helpers

    /** Plain = exactly what a fresh stack of that material is: no name, lore, enchants or plugin tags. */
    static boolean plain(ItemStack s) { return s.isSimilar(new ItemStack(s.getType())); }

    private static void take(Player p, Material m, int n) {
        ItemStack[] inv = p.getInventory().getStorageContents();
        for (int i = 0; i < inv.length && n > 0; i++) {
            ItemStack s = inv[i];
            if (s == null || s.getType() != m || !plain(s)) continue;
            int t = Math.min(n, s.getAmount());
            s.setAmount(s.getAmount() - t);
            n -= t;
        }
        p.getInventory().setStorageContents(inv);
    }

    static String pretty(Material m) {
        String s = m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static int parseInt(String s) { try { return Integer.parseInt(s.replace("#", "")); } catch (Exception e) { return -1; } }
}
