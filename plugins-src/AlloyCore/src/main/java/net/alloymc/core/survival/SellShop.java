package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.NetEconomy;
import net.alloymc.core.Ui;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.*;

/**
 * The Survival Trader: sell farmed goods for money with /shop. Prices and daily limits come from shop.yml
 * ({@link ShopPrices}); limits count per player per UTC day. The counts live in memory and are saved to
 * sell_limits.yml after every sale (written off the main thread), so a restart doesn't reset the day.
 * Only plain vanilla items are bought - anything with a custom name, lore, enchantments or plugin data is skipped,
 * so Fuel Cells (coal), Mob Essence and other custom items can never be sold here.
 *
 * <p>/shop - today's sales and limits; /shop prices [category]; /shop sell &lt;item|hand|all&gt; [amount].
 */
public final class SellShop {
    private static final String PREFIX = "<dark_gray>[<gold>Trader<dark_gray>] ";

    private final AlloyCore plugin;
    private ShopPrices prices;
    private long day = ShopPrices.utcDay(System.currentTimeMillis());
    /** player -> item -> sold today */
    private final Map<UUID, Map<String, Integer>> sold = new HashMap<>();
    /** plugins/AlloyCore/sell_limits.yml: today's counts, so they survive a restart. */
    private File limitsFile;
    private final Object writeLock = new Object();
    private long saveSeq, writtenSeq;   // newest snapshot handed to a writer / newest one on disk

    public SellShop(AlloyCore plugin) {
        this.plugin = plugin;
        if (!"survival".equals(plugin.getConfig().getString("mode"))) {
            plugin.getLogger().warning("SellShop is Survival-only - not started in this mode");
            return;
        }
        load();
        limitsFile = new File(plugin.getDataFolder(), "sell_limits.yml");
        loadLimits();
        Bukkit.getScheduler().runTaskTimer(plugin, this::rollDay, 1200L, 1200L);
        register();
    }

    // ------------------------------------------------------------------ data

    private void load() {
        File f = new File(plugin.getDataFolder(), "shop.yml");
        if (!f.exists()) plugin.saveResource("shop.yml", false);
        prices = new ShopPrices(toMap(YamlConfiguration.loadConfiguration(f)));
        plugin.getLogger().info("Trader: " + prices.items().size() + " items in " + prices.categories().size() + " categories");
    }

    private static Map<String, Object> toMap(ConfigurationSection s) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : s.getKeys(false)) {
            Object v = s.get(k);
            m.put(k, v instanceof ConfigurationSection c ? toMap(c) : v);
        }
        return m;
    }

    /** Clear the counts when the UTC day changes (checked every minute and before every sale); the file follows. */
    private void rollDay() {
        long today = ShopPrices.utcDay(System.currentTimeMillis());
        if (today != day) { day = today; sold.clear(); saveLimitsAsync(); }
    }

    // ------------------------------------------------------------------ sell_limits.yml

    /** yyyy-MM-dd of a UTC day number. */
    static String utcDate(long utcDay) { return LocalDate.ofEpochDay(utcDay).toString(); }

    /**
     * Today's counts from a parsed sell_limits.yml ({@code limits: <uuid>: {date: yyyy-MM-dd, <item>: n}}). Players whose
     * date isn't {@code today} are stale and skipped, as are bad UUIDs and non-positive counts.
     */
    static Map<UUID, Map<String, Integer>> readLimits(Map<String, ?> root, String today) {
        Map<UUID, Map<String, Integer>> out = new HashMap<>();
        if (!(root.get("limits") instanceof Map<?, ?> players)) return out;
        for (var pe : players.entrySet()) {
            if (!(pe.getValue() instanceof Map<?, ?> m) || !today.equals(dateOf(m.get("date")))) continue;
            UUID id;
            try { id = UUID.fromString(String.valueOf(pe.getKey())); } catch (IllegalArgumentException e) { continue; }
            Map<String, Integer> items = new HashMap<>();
            for (var ie : m.entrySet())
                if (!"date".equals(ie.getKey()) && ie.getValue() instanceof Number n && n.intValue() > 0) items.put(String.valueOf(ie.getKey()), n.intValue());
            if (!items.isEmpty()) out.put(id, items);
        }
        return out;
    }

    /** The date as yyyy-MM-dd; an unquoted date in hand-edited YAML loads as a java.util.Date, so read that as UTC too. */
    private static String dateOf(Object o) {
        if (o instanceof Date d) return d.toInstant().atZone(java.time.ZoneOffset.UTC).toLocalDate().toString();
        return String.valueOf(o);
    }

    /** The sell_limits.yml content for these counts, all dated {@code date}. */
    static Map<String, Object> limitsDoc(Map<UUID, Map<String, Integer>> counts, String date) {
        Map<String, Object> players = new LinkedHashMap<>();
        for (var e : counts.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", date);
            m.putAll(new TreeMap<>(e.getValue()));
            players.put(e.getKey().toString(), m);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("limits", players);
        return root;
    }

    private void loadLimits() {
        if (!limitsFile.exists()) return;
        Map<UUID, Map<String, Integer>> today = readLimits(toMap(YamlConfiguration.loadConfiguration(limitsFile)), utcDate(day));
        today.forEach((id, m) -> sold.put(id, new HashMap<>(m)));
        plugin.getLogger().info("Trader: restored today's sell limits for " + today.size() + " player" + (today.size() == 1 ? "" : "s"));
    }

    /**
     * Snapshot the counts on the main thread and write them off it. Each write replaces the whole file (stale days
     * drop out), goes through a temp file + rename, and an older snapshot never overwrites a newer one.
     */
    private void saveLimitsAsync() {
        YamlConfiguration y = new YamlConfiguration();
        limitsDoc(sold, utcDate(day)).forEach((k, v) -> set(y, k, v));
        String text = y.saveToString();
        long seq = ++saveSeq;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> writeLimits(seq, text));
    }

    @SuppressWarnings("unchecked")
    private static void set(ConfigurationSection s, String key, Object v) {
        if (v instanceof Map<?, ?> m) { ConfigurationSection c = s.createSection(key); ((Map<String, Object>) m).forEach((k, x) -> set(c, k, x)); }
        else s.set(key, v);
    }

    private void writeLimits(long seq, String text) {
        synchronized (writeLock) {
            if (seq <= writtenSeq) return;   // a newer snapshot is already on disk
            try {
                File tmp = new File(limitsFile.getParentFile(), limitsFile.getName() + ".tmp");
                Files.writeString(tmp.toPath(), text, StandardCharsets.UTF_8);
                try { Files.move(tmp.toPath(), limitsFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
                catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(tmp.toPath(), limitsFile.toPath(), StandardCopyOption.REPLACE_EXISTING); }
                writtenSeq = seq;
            } catch (Exception e) { plugin.getLogger().warning("Trader: could not save sell_limits.yml: " + e.getMessage()); }
        }
    }

    private int soldItem(UUID id, String item) { return sold.getOrDefault(id, Map.of()).getOrDefault(item, 0); }

    private int soldCategory(UUID id, String category) {
        int n = 0;
        for (var e : sold.getOrDefault(id, Map.of()).entrySet()) if (category.equals(prices.category(e.getKey()))) n += e.getValue();
        return n;
    }

    private static String key(Material m) { return m.name().toLowerCase(Locale.ROOT); }

    /** A plain vanilla stack: no name, lore, enchants, model data or plugin data. */
    static boolean plain(ItemStack it) {
        return it != null && !it.getType().isAir() && it.isSimilar(new ItemStack(it.getType()));
    }

    // ------------------------------------------------------------------ selling

    /** Result of selling one item type. */
    private record Sale(String item, int sold, int limited, double money) {}

    /**
     * Sells up to {@code wanted} of an item from the given slots (only plain stacks of it). Takes the items only once the
     * money is paid. limited = how many were held back by a daily limit.
     */
    private Sale sell(Player p, String item, int wanted, int[] slots) {
        OptionalDouble price = prices.sellPrice(item);
        if (price.isEmpty() || wanted <= 0) return new Sale(item, 0, 0, 0);
        UUID id = p.getUniqueId();
        String cat = prices.category(item);
        int ok = ShopPrices.allowed(wanted, soldItem(id, item), prices.itemLimit(item), soldCategory(id, cat), prices.categoryLimit(cat));
        if (ok <= 0) return new Sale(item, 0, wanted, 0);
        double money = ShopPrices.payout(price.getAsDouble(), ok);
        if (!plugin.economy().deposit(p, money)) return new Sale(item, 0, 0, 0);
        PlayerInventory inv = p.getInventory();
        int left = ok;
        for (int slot : slots) {
            if (left <= 0) break;
            ItemStack s = inv.getItem(slot);
            if (!plain(s) || !key(s.getType()).equals(item)) continue;
            int take = Math.min(left, s.getAmount());
            left -= take;
            if (take == s.getAmount()) inv.setItem(slot, null);
            else { s.setAmount(s.getAmount() - take); inv.setItem(slot, s); }
        }
        sold.computeIfAbsent(id, k -> new HashMap<>()).merge(item, ok, Integer::sum);
        return new Sale(item, ok, wanted - ok, money);
    }

    private static int[] storageSlots() {
        int[] s = new int[36];
        for (int i = 0; i < 36; i++) s[i] = i;
        return s;
    }

    /** How many plain items of this kind are in the given slots. */
    private static int count(PlayerInventory inv, String item, int[] slots) {
        int n = 0;
        for (int slot : slots) { ItemStack s = inv.getItem(slot); if (plain(s) && key(s.getType()).equals(item)) n += s.getAmount(); }
        return n;
    }

    private void sellCommand(Player p, String[] a) {
        rollDay();
        if (a.length < 2) { p.sendMessage(Ui.mm(PREFIX + "<gray>/shop sell <white><item|hand|all> [amount]")); return; }
        String what = a[1].toLowerCase(Locale.ROOT);
        PlayerInventory inv = p.getInventory();
        List<Sale> sales = new ArrayList<>();
        if (what.equals("all")) {
            // every sellable plain item in the main inventory (not armour or offhand), grouped by kind
            LinkedHashMap<String, Integer> have = new LinkedHashMap<>();
            for (int i = 0; i < 36; i++) {
                ItemStack s = inv.getItem(i);
                if (plain(s) && prices.sellPrice(key(s.getType())).isPresent()) have.merge(key(s.getType()), s.getAmount(), Integer::sum);
            }
            if (have.isEmpty()) { p.sendMessage(Ui.mm(PREFIX + "<red>Nothing in your inventory the Trader buys. <gray>See <white>/shop prices")); return; }
            for (var e : have.entrySet()) sales.add(sell(p, e.getKey(), e.getValue(), storageSlots()));
        } else {
            boolean hand = what.equals("hand");
            ItemStack held = inv.getItemInMainHand();
            String item;
            if (hand) {
                if (held.getType().isAir()) { p.sendMessage(Ui.mm(PREFIX + "<red>Hold the item you want to sell.")); return; }
                if (!plain(held)) { p.sendMessage(Ui.mm(PREFIX + "<red>The Trader only buys plain items (no custom names, enchants or special items).")); return; }
                item = key(held.getType());
            } else {
                Material m = Material.matchMaterial(what);
                item = m == null ? what : key(m);
            }
            if (prices.sellPrice(item).isEmpty()) { p.sendMessage(Ui.mm(PREFIX + "<red>The Trader doesn't buy <white>" + nice(item) + "<red>. <gray>See <white>/shop prices")); return; }
            int[] slots = hand ? new int[]{inv.getHeldItemSlot()} : storageSlots();
            int have = count(inv, item, slots);
            int wanted = have;
            if (a.length >= 3) {
                try { wanted = Math.min(have, Integer.parseInt(a[2])); } catch (NumberFormatException e) { p.sendMessage(Ui.mm(PREFIX + "<red>Amount must be a whole number.")); return; }
                if (wanted <= 0) { p.sendMessage(Ui.mm(PREFIX + "<red>Amount must be at least 1.")); return; }
            }
            if (have == 0) { p.sendMessage(Ui.mm(PREFIX + "<red>You have no plain <white>" + nice(item) + "<red> to sell.")); return; }
            sales.add(sell(p, item, wanted, slots));
        }
        if (sales.stream().anyMatch(s -> s.sold() > 0)) saveLimitsAsync();
        report(p, sales);
    }

    private void report(Player p, List<Sale> sales) {
        int items = 0; double money = 0;
        for (Sale s : sales) { items += s.sold(); money += s.money(); }
        money = Math.round(money * 100) / 100.0;
        if (items > 0) {
            p.sendMessage(Ui.mm(PREFIX + "<green>Sold <white>" + items + " <green>item" + (items == 1 ? "" : "s") + " for <gold>" + NetEconomy.money(money)
                    + "<green>. <gray>Balance: <white>" + NetEconomy.money(plugin.economy().balance(p.getUniqueId()))));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_TRADE, 0.8f, 1.1f);
            plugin.getLogger().info("[shop] " + p.getName() + " sold " + items + " items for " + NetEconomy.money(money));
        }
        for (Sale s : sales) if (s.limited() > 0)
            p.sendMessage(Ui.mm(PREFIX + "<yellow>" + s.limited() + " " + nice(s.item()) + " <yellow>not sold: daily limit reached <gray>(resets " + resetIn() + ")"));
        if (items == 0 && sales.stream().noneMatch(s -> s.limited() > 0)) p.sendMessage(Ui.mm(PREFIX + "<red>Nothing was sold."));
    }

    // ------------------------------------------------------------------ info

    private static String nice(String item) {
        String n = item.replace('_', ' ');
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    private static String resetIn() {
        long ms = 86_400_000L - Math.floorMod(System.currentTimeMillis(), 86_400_000L);
        long h = ms / 3_600_000, m = ms / 60_000 % 60;
        return "in " + (h > 0 ? h + "h " : "") + m + "m";
    }

    private void summary(Player p) {
        rollDay();
        UUID id = p.getUniqueId();
        p.sendMessage(Ui.mm("<gold><bold>Trader</bold> <gray>- sell your farmed goods <dark_gray>(limits reset " + resetIn() + ", 00:00 UTC)"));
        for (String cat : prices.categories()) {
            int lim = prices.categoryLimit(cat), s = soldCategory(id, cat);
            p.sendMessage(Ui.mm("<yellow>" + nice(cat) + " <dark_gray>» <white>" + s + "<gray>/" + (lim > 0 ? lim : "∞") + " sold today"
                    + (lim > 0 ? " <dark_gray>(" + Math.max(0, lim - s) + " left)" : "")));
            for (String item : prices.itemsIn(cat)) if (prices.itemLimit(item) > 0)
                p.sendMessage(Ui.mm("   <gray>" + nice(item) + ": <white>" + soldItem(id, item) + "<gray>/" + prices.itemLimit(item)));
        }
        p.sendMessage(Ui.mm("<gray>/shop prices [category] <dark_gray>• <gray>/shop sell <item|hand|all> [amount]"));
    }

    private void priceList(Player p, String cat) {
        List<String> cats = cat == null ? prices.categories() : prices.categories().stream().filter(c -> c.equalsIgnoreCase(cat)).toList();
        if (cats.isEmpty()) { p.sendMessage(Ui.mm(PREFIX + "<red>Categories: <white>" + String.join(", ", prices.categories()))); return; }
        for (String c : cats) {
            StringBuilder sb = new StringBuilder("<yellow>" + nice(c) + ": ");
            List<String> items = prices.itemsIn(c);
            for (int i = 0; i < items.size(); i++)
                sb.append(i > 0 ? "<dark_gray>, " : "").append("<white>").append(nice(items.get(i))).append(" <green>").append(NetEconomy.money(prices.sellPrice(items.get(i)).getAsDouble()));
            p.sendMessage(Ui.mm(sb.toString()));
        }
    }

    // ------------------------------------------------------------------ command

    private void register() {
        Bukkit.getCommandMap().register("alloy", new Command("shop", "Survival Trader - sell farmed goods", "/shop [sell|prices]", List.of("trader")) {
            @Override public boolean execute(CommandSender s, String label, String[] a) {
                if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
                String sub = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "";
                switch (sub) {
                    case "sell" -> sellCommand(p, a);
                    case "prices", "price", "list" -> priceList(p, a.length > 1 ? a[1] : null);
                    default -> summary(p);
                }
                return true;
            }

            @Override public List<String> tabComplete(CommandSender s, String alias, String[] a) {
                if (a.length == 1) return filter(List.of("sell", "prices"), a[0]);
                if (a.length == 2 && a[0].equalsIgnoreCase("sell")) {
                    List<String> opts = new ArrayList<>(List.of("all", "hand"));
                    opts.addAll(prices.items());
                    return filter(opts, a[1]);
                }
                if (a.length == 2 && a[0].equalsIgnoreCase("prices")) return filter(prices.categories(), a[1]);
                return List.of();
            }
        });
    }

    private static List<String> filter(Collection<String> opts, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        return opts.stream().filter(o -> o.startsWith(p)).toList();
    }
}
