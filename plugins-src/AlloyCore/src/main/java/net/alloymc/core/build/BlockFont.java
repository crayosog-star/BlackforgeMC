package net.alloymc.core.build;

import java.util.Map;

/** 5x7 pixel font for giant block lettering. */
public final class BlockFont {
    private BlockFont() {}

    private static final Map<Character, String[]> GLYPHS = Map.ofEntries(
        Map.entry('A', new String[]{".###.", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"}),
        Map.entry('C', new String[]{".####", "#....", "#....", "#....", "#....", "#....", ".####"}),
        Map.entry('E', new String[]{"#####", "#....", "#....", "####.", "#....", "#....", "#####"}),
        Map.entry('F', new String[]{"#####", "#....", "#....", "####.", "#....", "#....", "#...."}),
        Map.entry('I', new String[]{"#####", "..#..", "..#..", "..#..", "..#..", "..#..", "#####"}),
        Map.entry('K', new String[]{"#...#", "#..#.", "#.#..", "##...", "#.#..", "#..#.", "#...#"}),
        Map.entry('L', new String[]{"#....", "#....", "#....", "#....", "#....", "#....", "#####"}),
        Map.entry('M', new String[]{"#...#", "##.##", "#.#.#", "#.#.#", "#...#", "#...#", "#...#"}),
        Map.entry('N', new String[]{"#...#", "##..#", "#.#.#", "#..##", "#...#", "#...#", "#...#"}),
        Map.entry('O', new String[]{".###.", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."}),
        Map.entry('P', new String[]{"####.", "#...#", "#...#", "####.", "#....", "#....", "#...."}),
        Map.entry('R', new String[]{"####.", "#...#", "#...#", "####.", "#.#..", "#..#.", "#...#"}),
        Map.entry('S', new String[]{".####", "#....", "#....", ".###.", "....#", "....#", "####."}),
        Map.entry('T', new String[]{"#####", "..#..", "..#..", "..#..", "..#..", "..#..", "..#.."}),
        Map.entry('U', new String[]{"#...#", "#...#", "#...#", "#...#", "#...#", "#...#", ".###."}),
        Map.entry('V', new String[]{"#...#", "#...#", "#...#", "#...#", "#...#", ".#.#.", "..#.."}),
        Map.entry('Y', new String[]{"#...#", "#...#", ".#.#.", "..#..", "..#..", "..#..", "..#.."}),
        Map.entry('B', new String[]{"####.", "#...#", "#...#", "####.", "#...#", "#...#", "####."}),
        Map.entry('D', new String[]{"####.", "#...#", "#...#", "#...#", "#...#", "#...#", "####."}),
        Map.entry('G', new String[]{".####", "#....", "#....", "#.###", "#...#", "#...#", ".###."}),
        Map.entry('H', new String[]{"#...#", "#...#", "#...#", "#####", "#...#", "#...#", "#...#"}),
        Map.entry('W', new String[]{"#...#", "#...#", "#...#", "#.#.#", "#.#.#", "##.##", "#...#"}),
        Map.entry(' ', new String[]{".....", ".....", ".....", ".....", ".....", ".....", "....."})
    );

    public interface PixelPainter { void paint(int col, int row, int glyphRow); }

    /**
     * Walks the lit pixels of {@code text}. col grows left to right, row grows
     * bottom to top (row 0 = baseline), both in pixel units before scaling.
     */
    public static int draw(String text, int spacing, PixelPainter p) {
        int col = 0;
        for (char c : text.toUpperCase().toCharArray()) {
            String[] g = GLYPHS.getOrDefault(c, GLYPHS.get(' '));
            for (int r = 0; r < 7; r++)
                for (int x = 0; x < 5; x++)
                    if (g[r].charAt(x) == '#') p.paint(col + x, 6 - r, r);
            col += 5 + spacing;
        }
        return col - spacing;
    }

    public static int width(String text, int spacing) {
        return text.length() * (5 + spacing) - spacing;
    }
}
