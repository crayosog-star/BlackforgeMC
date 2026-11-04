package net.blackforge.core.survival;

import net.blackforge.core.Ui;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

/**
 * Custom menu art. The server can't add new screens, so a menu's title carries font glyphs from the pack: the art, drawn
 * over the vanilla panel, then any live text and pips at exact positions. The fonts come from tools/gui_art.py.
 */
final class StationUi {
    private StationUi() {}

    enum Screen {
        FORGE('', 176), STORAGE('', 176), AGENT('', 176), WORKSHOP('', 176);
        final char glyph; final int width;
        Screen(char glyph, int width) { this.glyph = glyph; this.width = width; }
    }

    static final char PIP_ON = '', PIP_OFF = '', COUNT_ON = '', COUNT_OFF = '';
    private static final Key GUI = Key.key("blackforge", "gui");
    private static final int TITLE_X = 8;
    private static boolean art;

    static void setArt(boolean on) { art = on; }
    static boolean art() { return art; }

    /** Builds a title left to right; x is measured from the panel's left edge. */
    static final class Canvas {
        private Component out = Component.empty();
        private int cursor = TITLE_X;

        private void moveTo(int x) {
            if (x != cursor) out = out.append(Component.text(StationText.shift(x - cursor)).font(GUI));
            cursor = x;
        }

        Canvas art(Screen s) {
            moveTo(0);
            out = out.append(Component.text(String.valueOf(s.glyph)).font(GUI));
            cursor += s.width + 1;
            return this;
        }

        /** Text in one of the pack's line fonts ("title" is the normal title line). */
        Canvas text(String line, int centreX, String text, int rgb) {
            moveTo(centreX - StationText.width(text) / 2);
            Component c = Component.text(text).color(TextColor.color(rgb));
            out = out.append(line.equals("title") ? c : c.font(Key.key("blackforge", line)));
            cursor += StationText.width(text);
            return this;
        }

        /** A row of pips, lit up to the given count. Each is drawn in the gui font, 4 px apart. */
        Canvas pips(int centreX, int lit, int of, char on, char off, int step) {
            moveTo(centreX - of * step / 2);
            for (int i = 0; i < of; i++) {
                out = out.append(Component.text(String.valueOf(i < lit ? on : off)).font(GUI));
                cursor += step;
            }
            return this;
        }

        Component done() { moveTo(TITLE_X); return out; }
    }

    static Component title(Screen s, String text) {
        if (!art) return Ui.mm("<dark_gray>" + text);
        return new Canvas().art(s).text("title", TITLE_X + StationText.width(text) / 2, text, 0xE8C9A0).done();
    }
}
