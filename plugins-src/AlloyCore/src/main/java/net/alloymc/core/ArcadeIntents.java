package net.alloymc.core;

import java.io.File;
import java.util.UUID;

/**
 * "Send me to this arcade game": the hub writes the pick to a shared file, then moves the player to the Arcade server,
 * which reads it on join and opens that game's mode picker. Picks older than a minute are ignored (a failed connect
 * shouldn't open a menu the next time someone joins the Arcade normally).
 */
public final class ArcadeIntents {
    private ArcadeIntents() {}

    static final long MAX_AGE_MS = 60_000;

    private static File file(File shared) { return new File(shared, "arcade-intents.yml"); }

    /** Remember that this player picked {@code game} (an arcade game id) on the hub. */
    public static void request(File shared, UUID player, String game) {
        Shared.update(file(shared), y -> y.set(player.toString(), game + ";" + System.currentTimeMillis()));
    }

    /** The game this player picked on the hub in the last minute, or null. Clears the pick either way. */
    public static String take(File shared, UUID player) {
        File f = file(shared);
        if (!f.exists()) return null;
        String[] out = {null};
        try {
            Shared.update(f, y -> {
                String v = y.getString(player.toString());
                if (v == null) return;
                y.set(player.toString(), null);
                out[0] = parse(v, System.currentTimeMillis());
            });
        } catch (IllegalStateException e) {
            return null;   // a broken shared file must never stop someone joining the Arcade
        }
        return out[0];
    }

    /** "game;millis" -> game, if it's fresh. */
    static String parse(String value, long now) {
        int i = value.lastIndexOf(';');
        if (i <= 0) return null;
        try {
            long at = Long.parseLong(value.substring(i + 1));
            return now - at >= 0 && now - at <= MAX_AGE_MS ? value.substring(0, i) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
