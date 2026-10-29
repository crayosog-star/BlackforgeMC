package net.alloymc.core;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

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
    /** Something achievements count: {@code n} more of {@code key} ("boss", "koth", "crate"...), or a new best when max. */
    public interface Stat { void on(Player p, String key, long n, boolean max); }
    /** A player just queued for an arcade game; {@code sameQueue} queues someone else for exactly the same game. */
    public interface Queued { void on(Player p, Consumer<Player> sameQueue); }

    private static final List<Kill> KILLS = new CopyOnWriteArrayList<>();
    private static final List<ArcadeEnd> ARCADE = new CopyOnWriteArrayList<>();
    private static final List<Stat> STATS = new CopyOnWriteArrayList<>();
    private static final List<Queued> QUEUED = new CopyOnWriteArrayList<>();

    public static void onKill(Kill k) { KILLS.add(k); }
    public static void onArcadeEnd(ArcadeEnd a) { ARCADE.add(a); }
    public static void onStat(Stat s) { STATS.add(s); }
    public static void onQueued(Queued q) { QUEUED.add(q); }

    public static void kill(Player killer, Player victim, String where) {
        for (Kill k : KILLS) try { k.on(killer, victim, where); } catch (Exception ignored) { }
    }

    public static void arcadeEnd(Player p, String game, boolean won) {
        for (ArcadeEnd a : ARCADE) try { a.on(p, game, won); } catch (Exception ignored) { }
    }

    /** Count {@code n} more of {@code key} for {@code p}. */
    public static void stat(Player p, String key, long n) {
        if (p == null) return;
        for (Stat s : STATS) try { s.on(p, key, n, false); } catch (Exception ignored) { }
    }

    /** {@code p}'s {@code key} is now at least {@code value} (a streak, a total kept elsewhere). */
    public static void statMax(Player p, String key, long value) {
        if (p == null) return;
        for (Stat s : STATS) try { s.on(p, key, value, true); } catch (Exception ignored) { }
    }

    public static void queued(Player p, Consumer<Player> sameQueue) {
        for (Queued q : QUEUED) try { q.on(p, sameQueue); } catch (Exception ignored) { }
    }
}
