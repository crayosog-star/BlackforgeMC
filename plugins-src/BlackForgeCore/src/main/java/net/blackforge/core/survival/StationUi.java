package net.blackforge.core.survival;

import net.blackforge.core.Ui;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;

/**
 * Custom menu art. The server can't add new screens, so a menu's title carries a font glyph from the pack: it shifts
 * left to the panel's edge, draws the art over the vanilla panel, then shifts back so the title text starts normally.
 * The shifts must match resourcepack/assets/blackforge/font/gui.json.
 */
final class StationUi {
    private StationUi() {}

    enum Screen {
        HOME(''), STORAGE(''), HOPPERS(''), AGENTS(''), AGENT(''), WORKSHOP('');
        final char glyph;
        Screen(char glyph) { this.glyph = glyph; }
    }

    private static final Key FONT = Key.key("blackforge", "gui");
    private static final String TO_PANEL_EDGE = "", BACK_TO_TITLE = "";
    private static boolean art;

    static void setArt(boolean on) { art = on; }
    static boolean art() { return art; }

    static Component title(Screen s, String text) {
        if (!art) return Ui.mm("<dark_gray>" + text);
        return Component.text(TO_PANEL_EDGE + s.glyph + BACK_TO_TITLE).font(FONT).append(Ui.mm("<#e8c9a0>" + text));
    }
}
