package net.alloymc.core;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Network-wide game events that several features care about (stats, challenges, cosmetics, events), so the game modes
 * only have to announce them once. Fired on the main thread.
 */
public final class Hooks {
    private Hooks() {}

    /** A player killed another player. {@code where} is "survival", "factions", "arcade:&lt;game&gt;"... */
    public interface Kill { void on(Player killer, Player victim, String where); }
    /** An arcade match ended for a player who was still in it. */
    public interface ArcadeEnd { void on(Player p, String game, boolean won); }

    private static final List<Kill> KILLS = new CopyOnWriteArrayList<>();
    private static final List<ArcadeEnd> ARCADE = new CopyOnWriteArrayList<>();

    public static void onKill(Kill k) { KILLS.add(k); }
    public static void onArcadeEnd(ArcadeEnd a) { ARCADE.add(a); }

    public static void kill(Player killer, Player victim, String where) {
        for (Kill k : KILLS) try { k.on(killer, victim, where); } catch (Exception ignored) { }
    }

    public static void arcadeEnd(Player p, String game, boolean won) {
        for (ArcadeEnd a : ARCADE) try { a.on(p, game, won); } catch (Exception ignored) { }
    }
}
