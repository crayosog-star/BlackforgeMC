package net.blackforge.core.survival;

/**
 * Pure maths for text drawn onto menu art, with no Bukkit types so it can be unit-tested. The pack's gui font has
 * space characters worth +1..+128 (U+F100..) and -1..-128 (U+F110..), which move the drawing point sideways.
 */
final class StationText {
    private StationText() {}

    /** Characters that move the drawing point by this many pixels. */
    static String shift(int pixels) {
        StringBuilder sb = new StringBuilder();
        int base = pixels < 0 ? 0xF110 : 0xF100, left = Math.abs(pixels);
        for (int bit = 7; bit >= 0; bit--) {
            int size = 1 << bit;
            while (left >= size) { sb.append((char) (base + bit)); left -= size; }
        }
        return sb.toString();
    }

    /** Pixel width of text in the default Minecraft font, 1 px gap included, for the characters menus use. */
    static int width(String text) {
        int w = 0;
        for (char c : text.toCharArray()) w += switch (c) { case 'I' -> 4; case ' ' -> 4; case '.' -> 2; default -> 6; };
        return w;
    }

    /** "$35K", "$1.5M": short enough to fit under an agent. */
    static String money(int amount) {
        if (amount >= 1_000_000) return "$" + trim(amount / 1_000_000.0) + "M";
        if (amount >= 1_000) return "$" + trim(amount / 1_000.0) + "K";
        return "$" + amount;
    }

    private static String trim(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format(java.util.Locale.ROOT, "%.1f", v);
    }
}
