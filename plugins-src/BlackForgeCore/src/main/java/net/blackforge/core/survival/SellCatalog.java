package net.blackforge.core.survival;

import java.util.ArrayList;
import java.util.List;

/**
 * What the Gilded Grove's Trader buys, and the maths of a sale (no Bukkit, so it can be unit tested).
 * Only raw farmed goods are listed. Left out on purpose, because a Trading Hall villager or the Daily Shop sells them or
 * something cheap turns into them: emeralds, redstone, lapis, ender pearls, cooked food, wool, stone. Each price stays
 * below what the Daily Shop charges, so buying from one shop and selling to the other always loses money.
 */
final class SellCatalog {
    private SellCatalog() {}

    record Item(String id, String label, double price) {}

    static final int DEFAULT_SOFT_CAP = 1728;          // 27 stacks of one item per player per day at full price
    static final double DEFAULT_OVER_CAP_RATE = 0.25;  // automated farms still earn past that, just far less

    static final List<Item> ITEMS = List.of(
            // crops
            new Item("wheat", "Wheat", 1.0), new Item("carrot", "Carrot", 0.6), new Item("potato", "Potato", 0.6),
            new Item("beetroot", "Beetroot", 1.0), new Item("melon_slice", "Melon Slice", 0.3), new Item("pumpkin", "Pumpkin", 2.5),
            new Item("sugar_cane", "Sugar Cane", 0.8), new Item("cactus", "Cactus", 0.8), new Item("bamboo", "Bamboo", 0.2),
            new Item("nether_wart", "Nether Wart", 1.2), new Item("cocoa_beans", "Cocoa Beans", 1.0), new Item("sweet_berries", "Sweet Berries", 0.5),
            // mob drops
            new Item("rotten_flesh", "Rotten Flesh", 0.5), new Item("bone", "Bone", 1.0), new Item("string", "String", 1.0),
            new Item("spider_eye", "Spider Eye", 2.0), new Item("gunpowder", "Gunpowder", 3.0), new Item("blaze_rod", "Blaze Rod", 8.0),
            new Item("slime_ball", "Slimeball", 3.0), new Item("leather", "Leather", 3.0), new Item("feather", "Feather", 0.5),
            new Item("beef", "Raw Beef", 1.5), new Item("porkchop", "Raw Porkchop", 1.5), new Item("chicken", "Raw Chicken", 1.0),
            new Item("mutton", "Raw Mutton", 1.5), new Item("rabbit", "Raw Rabbit", 1.5),
            // fishing
            new Item("cod", "Raw Cod", 2.0), new Item("salmon", "Raw Salmon", 3.0), new Item("tropical_fish", "Tropical Fish", 6.0),
            new Item("pufferfish", "Pufferfish", 6.0),
            // wood
            new Item("oak_log", "Oak Log", 0.8), new Item("spruce_log", "Spruce Log", 0.8), new Item("birch_log", "Birch Log", 0.8),
            new Item("jungle_log", "Jungle Log", 0.8), new Item("acacia_log", "Acacia Log", 0.8), new Item("dark_oak_log", "Dark Oak Log", 0.8),
            new Item("mangrove_log", "Mangrove Log", 0.8), new Item("cherry_log", "Cherry Log", 0.8), new Item("pale_oak_log", "Pale Oak Log", 0.8),
            // mining
            new Item("coal", "Coal", 1.0), new Item("raw_copper", "Raw Copper", 1.0), new Item("raw_iron", "Raw Iron", 3.0),
            new Item("raw_gold", "Raw Gold", 4.0), new Item("quartz", "Nether Quartz", 2.0), new Item("diamond", "Diamond", 40.0));

    /** One glow item frame on the pavilion's centre block: the air block it hangs in (relative to the centre), and which way it faces. */
    record FrameSlot(int dx, int dy, int dz, String facing) {}

    static final int CORE_HALF = 3, ROW_LOW = 2, ROW_HIGH = 4;

    /** One frame per item, in {@link #ITEMS} order: crops north (facing arrivals), mob drops east, fish and wood south, mining west. */
    static List<FrameSlot> gallery() {
        List<FrameSlot> out = new ArrayList<>();
        face(out, 12, "NORTH");
        face(out, 14, "EAST");
        face(out, 13, "SOUTH");
        face(out, 6, "WEST");
        return out;
    }

    private static void face(List<FrameSlot> out, int count, String facing) {
        int width = 2 * CORE_HALF + 1, high = Math.min(count, width), low = count - high;
        row(out, high, ROW_HIGH, facing);
        row(out, low, ROW_LOW, facing);
    }

    private static void row(List<FrameSlot> out, int count, int dy, String facing) {
        int start = -(count - 1) / 2, out1 = CORE_HALF + 1;
        for (int i = 0; i < count; i++) {
            int v = start + i;
            switch (facing) {
                case "NORTH" -> out.add(new FrameSlot(v, dy, -out1, facing));
                case "SOUTH" -> out.add(new FrameSlot(v, dy, out1, facing));
                case "EAST" -> out.add(new FrameSlot(out1, dy, v, facing));
                default -> out.add(new FrameSlot(-out1, dy, v, facing));
            }
        }
    }

    /** Money for selling {@code amount} more of an item after {@code soldToday} already sold: full price up to the cap, the over-cap rate after it. */
    static double payout(double price, int soldToday, int amount, int softCap, double overRate) {
        if (amount <= 0) return 0;
        int full = Math.max(0, Math.min(amount, softCap - soldToday));
        return Math.round((full * price + (amount - full) * price * overRate) * 100) / 100.0;
    }
}
