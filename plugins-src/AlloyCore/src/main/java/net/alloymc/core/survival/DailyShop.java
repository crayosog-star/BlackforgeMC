package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.NetEconomy;
import net.alloymc.core.Ui;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/**
 * The Daily Shop: six offers that change every day (UTC), the same for everyone, one purchase of each per player per day.
 * Prices are in the server's money; the pool is a mix of useful and fun things, nothing PvP-exclusive.
 */
final class DailyShop implements Listener {
    private record Offer(Material item, int amount, double price) {}

    private static final List<Offer> POOL = List.of(
            new Offer(Material.DIAMOND, 4, 800), new Offer(Material.ANCIENT_DEBRIS, 2, 1800), new Offer(Material.NETHERITE_SCRAP, 2, 1500),
            new Offer(Material.GOLDEN_APPLE, 4, 400), new Offer(Material.ENDER_PEARL, 8, 350), new Offer(Material.EXPERIENCE_BOTTLE, 16, 450),
            new Offer(Material.TOTEM_OF_UNDYING, 1, 2500), new Offer(Material.SHULKER_SHELL, 2, 900), new Offer(Material.NAME_TAG, 2, 600),
            new Offer(Material.SADDLE, 1, 500), new Offer(Material.HEART_OF_THE_SEA, 1, 1600), new Offer(Material.NAUTILUS_SHELL, 2, 700),
            new Offer(Material.SPONGE, 8, 450), new Offer(Material.SLIME_BLOCK, 8, 400), new Offer(Material.HONEY_BLOCK, 8, 400),
            new Offer(Material.OBSIDIAN, 16, 500), new Offer(Material.BLAZE_ROD, 8, 450), new Offer(Material.GHAST_TEAR, 2, 800),
            new Offer(Material.PHANTOM_MEMBRANE, 8, 500), new Offer(Material.TURTLE_SCUTE, 4, 600), new Offer(Material.ECHO_SHARD, 2, 1100),
            new Offer(Material.AMETHYST_BLOCK, 16, 500), new Offer(Material.COPPER_BLOCK, 16, 450), new Offer(Material.QUARTZ_BLOCK, 32, 450),
            new Offer(Material.CAKE, 2, 200), new Offer(Material.MUSIC_DISC_CAT, 1, 700), new Offer(Material.TRIDENT, 1, 3000));
    private static final int[] SLOTS = {10, 11, 12, 14, 15, 16};

    private static final class Shop implements InventoryHolder {
        Inventory inv; List<Offer> offers;
        public Inventory getInventory() { return inv; }
    }

    private final AlloyCore plugin;
    private final File file;
    private final YamlConfiguration data;

    DailyShop(AlloyCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "dailyshop.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getCommandMap().register("alloy", new Command("dailyshop", "Today's shop", "/dailyshop", List.of("shop", "dshop")) {
            @Override public boolean execute(CommandSender s, String label, String[] a) {
                if (s instanceof Player p) open(p); else s.sendMessage("Players only.");
                return true;
            }
        });
    }

    private static long today() { return LocalDate.now(ZoneOffset.UTC).toEpochDay(); }

    static List<Offer> offersFor(long day) {
        List<Offer> pool = new ArrayList<>(POOL);
        Collections.shuffle(pool, new Random(day * 7919L));
        return pool.subList(0, SLOTS.length);
    }

    private boolean bought(UUID u, int i) { return data.getIntegerList(today() + "." + u).contains(i); }

    void open(Player p) {
        Shop h = new Shop();
        h.offers = offersFor(today());
        Inventory inv = Bukkit.createInventory(h, 27, Ui.mm("<dark_gray>🛒 Daily Shop"));
        h.inv = inv;
        ItemStack pane = Ui.item(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) inv.setItem(i, pane);
        for (int i = 0; i < SLOTS.length; i++) {
            Offer o = h.offers.get(i);
            ItemStack it = new ItemStack(o.item(), o.amount());
            boolean done = bought(p.getUniqueId(), i);
            it.editMeta(m -> {
                m.displayName(Ui.mm((done ? "<gray><st>" : "<white>") + o.amount() + "x " + pretty(o.item())));
                m.lore(List.of(Ui.mm("<gray>Price <gold>" + NetEconomy.money(o.price())), Ui.mm(done ? "<red>Bought today" : "<yellow>» Click to buy (once a day)")));
            });
            inv.setItem(SLOTS[i], it);
        }
        long mins = (Math.floorDiv(System.currentTimeMillis(), 86_400_000L) + 1) * 1440L - System.currentTimeMillis() / 60_000L;
        inv.setItem(22, Ui.item(Material.CLOCK, "<#ffb347>New offers in " + mins / 60 + "h " + mins % 60 + "m", "<gray>Your balance: <gold>" + NetEconomy.money(plugin.economy().balance(p.getUniqueId()))));
        p.openInventory(inv);
    }

    private static String pretty(Material m) {
        StringBuilder sb = new StringBuilder();
        for (String w : m.name().toLowerCase().split("_")) sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
        return sb.toString().trim();
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Shop h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        int idx = -1;
        for (int i = 0; i < SLOTS.length; i++) if (SLOTS[i] == e.getRawSlot()) idx = i;
        if (idx < 0) return;
        Offer o = h.offers.get(idx);
        if (bought(p.getUniqueId(), idx)) { p.sendMessage(Ui.mm("<red>You already bought that today.")); return; }
        if (p.getInventory().firstEmpty() == -1) { p.sendMessage(Ui.mm("<red>Your inventory is full.")); return; }
        if (!plugin.economy().withdraw(p, o.price())) { p.sendMessage(Ui.mm("<red>You need <gold>" + NetEconomy.money(o.price()) + "<red>.")); return; }
        p.getInventory().addItem(new ItemStack(o.item(), o.amount()));
        List<Integer> l = new ArrayList<>(data.getIntegerList(today() + "." + p.getUniqueId()));
        l.add(idx);
        data.set(today() + "." + p.getUniqueId(), l);
        for (String k : new ArrayList<>(data.getKeys(false))) if (!k.equals(String.valueOf(today()))) data.set(k, null);   // yesterday's purchases are forgotten
        try { data.save(file); } catch (Exception ex) { plugin.getLogger().warning("dailyshop save: " + ex); }
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_YES, 0.8f, 1.2f);
        p.sendMessage(Ui.mm("<green>Bought <white>" + o.amount() + "x " + pretty(o.item()) + " <green>for <gold>" + NetEconomy.money(o.price()) + "<green>."));
        open(p);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Shop) e.setCancelled(true); }
}
