package net.alloymc.core.survival;

import java.util.*;

/**
 * The Survival Trader's price list and daily sell limits, read from shop.yml. Pure (no Bukkit types): items are
 * lower-case material names ("wheat", "blaze_rod"), so it can be unit-tested and SellShop converts Materials.
 *
 * <pre>
 * limits:            # items per player per UTC day, per category
 *   crops: 5000
 * shop:
 *   wheat:     { price: 0.40, category: crops }
 *   blaze_rod: { price: 2.50, category: mob_drops, daily_limit: 500 }   # extra per-item limit inside the category
 * </pre>
 */
public final class ShopPrices {
    record Entry(String item, double price, String category, int itemLimit) {}

    private final Map<String, Entry> items = new LinkedHashMap<>();
    private final Map<String, Integer> categoryLimits = new LinkedHashMap<>();

    /** @param root the parsed shop.yml: nested maps, numbers as any {@link Number}. Bad entries are skipped. */
    public ShopPrices(Map<String, ?> root) {
        if (root.get("limits") instanceof Map<?, ?> lim)
            for (var e : lim.entrySet()) if (e.getValue() instanceof Number n) categoryLimits.put(key(e.getKey()), Math.max(0, n.intValue()));
        if (root.get("shop") instanceof Map<?, ?> shop)
            for (var e : shop.entrySet()) {
                if (!(e.getValue() instanceof Map<?, ?> m) || !(m.get("price") instanceof Number p) || p.doubleValue() <= 0) continue;
                String cat = m.get("category") == null ? "other" : key(m.get("category"));
                int lim = m.get("daily_limit") instanceof Number n ? Math.max(0, n.intValue()) : 0;
                String item = key(e.getKey());
                items.put(item, new Entry(item, p.doubleValue(), cat, lim));
            }
    }

    private static String key(Object o) { return String.valueOf(o).trim().toLowerCase(Locale.ROOT); }

    /** Sell price for one item, or empty if the Trader doesn't buy it. Accepts "wheat" or "WHEAT". */
    public OptionalDouble sellPrice(String material) {
        Entry e = items.get(key(material));
        return e == null ? OptionalDouble.empty() : OptionalDouble.of(e.price());
    }

    /** The item's own daily limit if it has one, otherwise its category's; 0 = unlimited (or not sold). */
    public int dailyLimit(String material) {
        Entry e = items.get(key(material));
        if (e == null) return 0;
        return e.itemLimit() > 0 ? e.itemLimit() : categoryLimit(e.category());
    }

    /** Only the per-item override (0 if the item just uses its category limit). */
    public int itemLimit(String material) {
        Entry e = items.get(key(material));
        return e == null ? 0 : e.itemLimit();
    }

    /** crops / mob_drops / fishing / wood / mining, or null if the item isn't sold. */
    public String category(String material) {
        Entry e = items.get(key(material));
        return e == null ? null : e.category();
    }

    /** 0 = unlimited. */
    public int categoryLimit(String category) { return categoryLimits.getOrDefault(key(category), 0); }

    /** Every category that has items, in shop.yml order. */
    public List<String> categories() {
        LinkedHashSet<String> c = new LinkedHashSet<>();
        for (Entry e : items.values()) c.add(e.category());
        return List.copyOf(c);
    }

    /** Items in a category, in shop.yml order. */
    public List<String> itemsIn(String category) {
        String c = key(category);
        return items.values().stream().filter(e -> e.category().equals(c)).map(Entry::item).toList();
    }

    public Set<String> items() { return Collections.unmodifiableSet(items.keySet()); }

    // ---------------------------------------------------------------- limits

    /**
     * How many of {@code wanted} may be sold now, given what was already sold today of this item and of its whole
     * category. Limits of 0 mean unlimited.
     */
    static int allowed(int wanted, int soldItem, int itemLimit, int soldCategory, int categoryLimit) {
        int n = Math.max(0, wanted);
        if (itemLimit > 0) n = Math.min(n, Math.max(0, itemLimit - soldItem));
        if (categoryLimit > 0) n = Math.min(n, Math.max(0, categoryLimit - soldCategory));
        return n;
    }

    /** Days since the epoch in UTC, so limits roll over at 00:00 UTC. */
    static long utcDay(long epochMillis) { return Math.floorDiv(epochMillis, 86_400_000L); }

    /** Money for n items, rounded to cents. */
    static double payout(double price, int n) { return Math.round(price * n * 100) / 100.0; }

    // ---------------------------------------------------------------- amounts

    /**
     * Parses "1500", "1,500", "$1.5k", "2m", "1.25b". Returns -1 if it isn't a number.
     * (Before: the auction house did replace("k","000"), so "1.5k" became "1.5000" = $1.50.)
     */
    public static double parseAmount(String s) {
        if (s == null) return -1;
        String t = s.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("$", "").replace("_", "");
        if (t.isEmpty()) return -1;
        double mul = 1;
        char last = t.charAt(t.length() - 1);
        if (last == 'k') mul = 1_000; else if (last == 'm') mul = 1_000_000; else if (last == 'b') mul = 1_000_000_000;
        if (mul > 1) t = t.substring(0, t.length() - 1);
        try {
            double v = Double.parseDouble(t) * mul;
            return Double.isFinite(v) && v >= 0 ? v : -1;
        } catch (NumberFormatException e) { return -1; }
    }
}
