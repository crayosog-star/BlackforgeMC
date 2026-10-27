package net.alloymc.core.arcade.core;

import net.alloymc.core.Ui;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Queues and matches. One queue per (game, variant); when a queue has enough players and its countdown
 * ends, a map is picked at random from that game's own pool, a free instance slot is reserved, a fresh
 * copy of the map is loaded into it, and only then are the players teleported in. A failed load releases
 * the slot reservation and tries the next eligible map (each map at most once), never looping forever.
 */
public final class MatchManager {
    public static final String TAG = "<aqua><bold>BLACKFORGE ARCADE</bold> <dark_gray>» ";

    public final class Queue {
        public final ArcadeGame game;
        public final Variant variant;
        final LinkedHashSet<UUID> waiting = new LinkedHashSet<>();
        int countdown = -1;
        boolean loading;
        Queue(ArcadeGame g, Variant v) { game = g; variant = v; }
        public int size() { return waiting.size(); }
        public int countdown() { return countdown; }
        public boolean loading() { return loading; }
    }

    private final Arcade arcade;
    private final SlotAllocator slots;
    private final MapSelector selector;
    private final Map<String, Queue> queues = new LinkedHashMap<>();
    private final Map<UUID, Queue> queuedIn = new HashMap<>();
    private final Map<String, Match> active = new LinkedHashMap<>();
    private final Map<UUID, Match> playerMatch = new HashMap<>();
    private final Map<UUID, String[]> rejoin = new HashMap<>();   // uuid -> {matchId, expiresAtMillis}
    private final Map<String, String> forced = new HashMap<>();     // game -> map id for the next match (staff)
    private final Map<UUID, Match> spectating = new HashMap<>();
    private final Random rnd = new Random();
    private int counter;
    private final int maxQueue;

    MatchManager(Arcade arcade, int maxMatches, int rememberMaps, int maxQueue) {
        this.arcade = arcade;
        this.slots = new SlotAllocator(maxMatches);
        this.selector = new MapSelector(rememberMaps);
        this.maxQueue = maxQueue;
    }

    public Collection<Match> active() { return active.values(); }
    public Match get(String id) { return active.get(id); }
    public Match matchOf(Player p) { return playerMatch.get(p.getUniqueId()); }
    public Queue queueOf(Player p) { return queuedIn.get(p.getUniqueId()); }
    public SlotAllocator slots() { return slots; }
    public MapSelector selector() { return selector; }
    public void force(String game, String map) { if (map == null) forced.remove(game); else forced.put(game, map); }

    public Queue queue(ArcadeGame g, Variant v) { return queues.computeIfAbsent(g.id + ":" + v.id(), k -> new Queue(g, v)); }

    public int playing(ArcadeGame g) {
        int n = 0;
        for (Match m : active.values()) if (m.game == g) n += m.players().size();
        for (Queue q : queues.values()) if (q.game == g) n += q.size();
        return n;
    }

    public List<Match> matchesOf(ArcadeGame g) { return active.values().stream().filter(m -> m.game == g).toList(); }

    // ------------------------------------------------------------------ joining

    public boolean join(Player p, ArcadeGame g, Variant v) {
        if (!p.hasPermission("arcade.play")) { p.sendMessage(Ui.mm(TAG + "<red>You don't have permission to play the arcade.")); return false; }
        if (playerMatch.containsKey(p.getUniqueId())) { p.sendMessage(Ui.mm(TAG + "<red>You're already in a match. <white>/arcade leave <red>first.")); return false; }
        if (!arcade.hasPlayableMap(g, v)) { p.sendMessage(Ui.mm(TAG + "<red>" + g.name + " " + v.label() + " has no available maps right now.")); return false; }
        Queue cur = queuedIn.get(p.getUniqueId());
        if (cur != null && cur.game == g && cur.variant.equals(v)) { p.sendMessage(Ui.mm(TAG + "<gray>You're already queued for " + g.color + g.name + " <gray>(" + v.label() + ").")); return false; }
        // free-for-all modes: drop straight into a running match that has room
        if (g.lateJoin()) {
            for (Match m : active.values())
                if (m.game == g && m.variant.equals(v) && m.state() == MatchState.IN_PROGRESS && m.players().size() < Math.min(v.maxPlayers(), m.map.maxPlayers)) {
                    leaveQueue(p, false);
                    playerMatch.put(p.getUniqueId(), m);
                    p.sendMessage(Ui.mm(TAG + "<green>Joining a game in progress on <white>" + m.map.display + "<green>."));
                    m.lateJoin(p);
                    return true;
                }
        }
        Queue q = queue(g, v);
        if (q.size() >= maxQueue) { p.sendMessage(Ui.mm(TAG + "<red>That queue is full - try again in a moment.")); return false; }
        leaveQueue(p, false);
        q.waiting.add(p.getUniqueId());
        queuedIn.put(p.getUniqueId(), q);
        p.sendMessage(Ui.mm(TAG + "<gray>Finding a match... " + g.color + g.name + " <gray>(" + v.label() + ") <dark_gray>[" + q.size() + "/" + v.maxPlayers() + "]"));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, 1.6f);
        arcade.giveQueueItems(p);
        return true;
    }

    public boolean leaveQueue(Player p, boolean tell) {
        Queue q = queuedIn.remove(p.getUniqueId());
        if (q == null) return false;
        q.waiting.remove(p.getUniqueId());
        if (tell) p.sendMessage(Ui.mm(TAG + "<gray>You left the " + q.game.name + " queue."));
        return true;
    }

    /** Watch a running match from its spectator spawn (never takes part, never affects it). */
    public void spectate(Player p, Match m) {
        leaveQueue(p, false);
        spectating.put(p.getUniqueId(), m);
        m.addSpectator(p);
    }

    public void forceStart(Queue q) { if (!q.loading && q.size() > 0) start(q); }

    // ------------------------------------------------------------------ rematches / private matches

    private static final class Rematch {
        final ArcadeGame game; final Variant variant; final List<UUID> ids; final Set<UUID> accepted = new HashSet<>(); final long expires;
        Rematch(ArcadeGame g, Variant v, List<UUID> ids) { game = g; variant = v; this.ids = ids; expires = System.currentTimeMillis() + 30_000; }
    }
    private final Map<UUID, Rematch> rematches = new HashMap<>();

    /** Everyone from a finished match may click [Rematch] within 30 seconds; when all have, it starts. */
    public void offerRematch(ArcadeGame g, Variant v, List<UUID> ids) {
        Rematch r = new Rematch(g, v, List.copyOf(ids));
        for (UUID u : ids) rematches.put(u, r);
    }

    public void acceptRematch(Player p) {
        Rematch r = rematches.get(p.getUniqueId());
        if (r == null || r.expires < System.currentTimeMillis()) { rematches.remove(p.getUniqueId()); p.sendMessage(Ui.mm(TAG + "<gray>No rematch to accept.")); return; }
        if (!r.accepted.add(p.getUniqueId())) { p.sendMessage(Ui.mm(TAG + "<gray>Waiting for your opponent(s) to accept...")); return; }
        for (UUID u : r.ids) { Player o = Bukkit.getPlayer(u); if (o != null && !o.equals(p)) o.sendMessage(Ui.mm(TAG + "<white>" + p.getName() + " <gray>wants a rematch! ").append(Ui.mm("<green><bold>[Accept]").clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/arcade rematch")))); }
        if (!r.accepted.containsAll(r.ids)) { p.sendMessage(Ui.mm(TAG + "<gray>Rematch requested - waiting for the others.")); return; }
        List<Player> ps = new ArrayList<>();
        for (UUID u : r.ids) { Player o = Bukkit.getPlayer(u); if (o == null || playerMatch.containsKey(u)) { p.sendMessage(Ui.mm(TAG + "<red>Someone is no longer available.")); return; } ps.add(o); }
        for (UUID u : r.ids) rematches.remove(u);
        startPrivate(r.game, r.variant, ps);
    }

    /** A match for exactly these players (rematches), skipping the public queue but using the same map selection. */
    public void startPrivate(ArcadeGame g, Variant v, List<Player> ps) {
        Queue q = new Queue(g, v);
        for (Player p : ps) { leaveQueue(p, false); q.waiting.add(p.getUniqueId()); queuedIn.put(p.getUniqueId(), q); p.sendMessage(Ui.mm(TAG + "<green>Rematch! Finding an arena...")); }
        start(q);
    }

    /** /arcade leave: out of the queue or match, back to the lobby. */
    public void leave(Player p) {
        if (leaveQueue(p, true)) { arcade.toLobby(p); return; }
        Match sp = spectating.remove(p.getUniqueId());
        if (sp != null) { sp.spectators.remove(p.getUniqueId()); arcade.toLobby(p); return; }
        Match m = playerMatch.remove(p.getUniqueId());
        if (m != null) { m.remove(p, false); arcade.toLobby(p); p.sendMessage(Ui.mm(TAG + "<gray>You left the match.")); return; }
        p.sendMessage(Ui.mm(TAG + "<gray>You're not in a queue or match."));
    }

    public void quit(Player p) {
        leaveQueue(p, false);
        Match sp = spectating.remove(p.getUniqueId());
        if (sp != null) sp.spectators.remove(p.getUniqueId());
        Match m = playerMatch.remove(p.getUniqueId());
        if (m != null) {
            if (m.supportsRejoin() && m.state() == MatchState.IN_PROGRESS)
                rejoin.put(p.getUniqueId(), new String[]{m.id, String.valueOf(System.currentTimeMillis() + 120_000)});
            m.remove(p, true);
        }
    }

    /** Back within 2 minutes of disconnecting from a match that allows it? Put them back. */
    public boolean tryRejoin(Player p) {
        String[] r = rejoin.remove(p.getUniqueId());
        if (r == null || Long.parseLong(r[1]) < System.currentTimeMillis()) return false;
        Match m = active.get(r[0]);
        if (m == null || m.state() != MatchState.IN_PROGRESS || !m.canRejoin(p)) return false;
        playerMatch.put(p.getUniqueId(), m);
        m.rejoin(p);
        p.sendMessage(Ui.mm(TAG + "<green>Reconnected to your match on <white>" + m.map.display + "<green>."));
        return true;
    }

    // ------------------------------------------------------------------ ticking

    void tickSecond() {
        for (Queue q : queues.values()) tickQueue(q);
        for (Match m : new ArrayList<>(active.values())) {
            try { m.tickSecond(); }
            catch (Exception e) { arcade.plugin().getLogger().log(java.util.logging.Level.SEVERE, "[Arcade] match " + m.id + " crashed; ending it", e); abort(m, "<red>This match hit an error and was stopped. Sorry!"); }
        }
    }

    void tickFast() {
        for (Match m : new ArrayList<>(active.values())) {
            try { m.tickFast(); }
            catch (Exception e) { arcade.plugin().getLogger().log(java.util.logging.Level.SEVERE, "[Arcade] match " + m.id + " crashed; ending it", e); abort(m, "<red>This match hit an error and was stopped. Sorry!"); }
        }
    }

    private void tickQueue(Queue q) {
        q.waiting.removeIf(u -> Bukkit.getPlayer(u) == null);
        if (q.loading) return;
        Variant v = q.variant;
        List<Player> ps = new ArrayList<>();
        for (UUID u : q.waiting) ps.add(Bukkit.getPlayer(u));
        if (q.size() < v.minPlayers()) {
            if (q.countdown >= 0) for (Player p : ps) p.sendMessage(Ui.mm(TAG + "<red>Not enough players - countdown stopped."));
            q.countdown = -1;
            for (Player p : ps) p.sendActionBar(Ui.mm(q.game.color + q.game.name + " <gray>" + v.label() + " • waiting for players <white>" + q.size() + "/" + v.minPlayers()));
            return;
        }
        if (q.countdown < 0) q.countdown = q.game.queueCountdown();
        if (q.size() >= v.maxPlayers()) q.countdown = Math.min(q.countdown, 3);
        for (Player p : ps) p.sendActionBar(Ui.mm(q.game.color + q.game.name + " <gray>" + v.label() + " • match found, starting in <white>" + q.countdown + "s <gray>(" + q.size() + "/" + v.maxPlayers() + ")"));
        if (q.countdown <= 5 && q.countdown > 0) for (Player p : ps) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.2f);
        if (--q.countdown <= 0) start(q);
    }

    // ------------------------------------------------------------------ starting a match

    private void start(Queue q) {
        List<UUID> taking = new ArrayList<>();
        for (UUID u : q.waiting) { if (taking.size() >= q.variant.maxPlayers()) break; taking.add(u); }
        String id = q.game.id.toUpperCase().replace("_", "") + "-" + Integer.toString(++counter, 36).toUpperCase() + Long.toString(System.currentTimeMillis() % 46656, 36).toUpperCase();
        int slot = slots.acquire(id);
        if (slot < 0) {
            q.countdown = 5;
            for (UUID u : taking) msg(u, TAG + "<yellow>All arenas are busy - you're first in line, retrying in 5s.");
            return;
        }
        q.loading = true;
        tryLoad(q, id, slot, taking, new HashSet<>());
    }

    private void tryLoad(Queue q, String id, int slot, List<UUID> taking, Set<String> failed) {
        Collection<MapDef> pool = arcade.maps().pool(q.game.id);
        Optional<MapDef> pick = Optional.empty();
        String forcedId = forced.remove(q.game.id);
        if (forcedId != null && failed.isEmpty()) {
            MapDef f = arcade.maps().get(q.game.id, forcedId);
            if (f != null && arcade.isValid(f) && f.supports(q.variant)) pick = Optional.of(f);
        }
        if (pick.isEmpty()) pick = selector.pick(q.game.id, pool, q.variant, taking.size(), failed, arcade::isValid, rnd);
        if (pick.isEmpty()) {
            slots.release(slot, id);
            q.loading = false;
            q.countdown = 10;
            for (UUID u : taking)
                msg(u, TAG + (failed.isEmpty() ? "<red>No " + q.game.name + " map fits " + taking.size() + " players right now - waiting for more players." : "<red>No arena could be loaded. Staying in the queue."));
            return;
        }
        MapDef map = pick.get();
        for (UUID u : taking) { msg(u, TAG + "<green>Map selected: <white>" + map.display); msg(u, TAG + "<gray>Loading arena..."); }
        arcade.worlds().loadInstance(map, slot, ok -> {
            if (!ok) {
                arcade.plugin().getLogger().warning("[Arcade] " + id + ": map " + map + " failed to load in slot " + slot + ", trying another");
                failed.add(map.id);
                tryLoad(q, id, slot, taking, failed);
                return;
            }
            selector.played(q.game.id, map.id);
            launch(q, id, slot, map, taking);
        });
    }

    private void launch(Queue q, String id, int slot, MapDef map, List<UUID> taking) {
        q.loading = false;
        List<Player> ps = new ArrayList<>();
        for (UUID u : taking) {
            Player p = Bukkit.getPlayer(u);
            // only players still waiting in this queue go in (someone may have left during loading)
            if (p != null && q.waiting.contains(u) && !playerMatch.containsKey(u)) ps.add(p);
        }
        int need = Math.min(q.variant.minPlayers(), Math.max(1, map.minPlayers));
        if (ps.size() < need) {
            for (Player p : ps) p.sendMessage(Ui.mm(TAG + "<yellow>Players left while the arena loaded - back in the queue."));
            arcade.worlds().clearSlot(slot, map.copyRegion().grow(4), map.templateY, () -> slots.release(slot, id));
            return;
        }
        for (Player p : ps) { q.waiting.remove(p.getUniqueId()); queuedIn.remove(p.getUniqueId()); }
        Settings s = new Settings(arcade.gameSettings(q.game), map.settings);
        Match m = q.game.newMatch(new MatchContext(arcade, id, q.game, q.variant, map, slot, arcade.worlds().matches(), SlotAllocator.originX(slot), map.templateY, 0, s));
        active.put(id, m);
        for (Player p : ps) playerMatch.put(p.getUniqueId(), m);
        arcade.plugin().getLogger().info("[Arcade] " + id + " started: " + q.game.name + " " + q.variant.label() + " on " + map + " (slot " + slot + ") with " + ps.stream().map(Player::getName).toList());
        try { m.begin(ps); }
        catch (Exception e) {
            arcade.plugin().getLogger().log(java.util.logging.Level.SEVERE, "[Arcade] " + id + " failed to start", e);
            abort(m, "<red>The match failed to start. Sorry!");
        }
    }

    // ------------------------------------------------------------------ ending

    /** The match declared its result: stats, rating and rewards (each exactly once). */
    void onFinished(Match m) {
        for (UUID u : m.players()) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            boolean won = m.winners.contains(u);
            arcade.stats().add(u, m.game.id, won ? "wins" : "losses", 1);
            arcade.stats().add(u, m.game.id, "playtime", (System.currentTimeMillis() - m.joinedAt.getOrDefault(u, System.currentTimeMillis())) / 1000);
            arcade.stats().max(u, m.game.id, "best_streak", m.bestStreak.getOrDefault(u, 0));
            arcade.stats().add(u, "_maps", m.game.id + "." + m.map.id, 1);
        }
        if (m.variant.ranked()) Ratings.update(arcade.stats(), m);
        arcade.rewards().finish(m);
    }

    /** ENDING is over: everyone back to the lobby, then the instance is wiped and the slot freed. */
    void reset(Match m) {
        if (m.state() == MatchState.RESETTING || m.state() == MatchState.AVAILABLE) return;
        m.state.go(MatchState.RESETTING);
        m.cleanup();
        for (UUID u : new ArrayList<>(m.players())) {
            if (playerMatch.get(u) == m) playerMatch.remove(u);
            Player p = Bukkit.getPlayer(u);
            if (p != null && p.getWorld() == arcade.worlds().matches()) arcade.toLobby(p);
        }
        // anyone else somehow standing in this slot (spectators that slipped through) goes too
        for (Player p : arcade.worlds().matches().getPlayers()) if (SlotAllocator.slotAt(p.getLocation().getX()) == m.slot && !playerMatch.containsKey(p.getUniqueId())) arcade.toLobby(p);
        rejoin.values().removeIf(r -> r[0].equals(m.id));
        for (UUID u : new ArrayList<>(m.spectators)) { spectating.remove(u); Player p = Bukkit.getPlayer(u); if (p != null) arcade.toLobby(p); }
        m.spectators.clear();
        arcade.worlds().clearSlot(m.slot, m.map.copyRegion().grow(4), m.oy, () -> {
            slots.release(m.slot, m.id);
            m.state.go(MatchState.AVAILABLE);
            active.remove(m.id);
            arcade.plugin().getLogger().info("[Arcade] " + m.id + " reset, slot " + m.slot + " free");
        });
    }

    /** Stop a match without rewards (errors, staff /arcade stop, shutdown). */
    public void abort(Match m, String why) {
        if (m.state() == MatchState.RESETTING || m.state() == MatchState.AVAILABLE) return;
        m.broadcast(TAG + why);
        if (m.state() != MatchState.ENDING) {
            try { m.state.go(MatchState.ENDING); } catch (IllegalStateException ignored) { }
        }
        reset(m);
    }

    void shutdown() {
        for (Match m : new ArrayList<>(active.values())) {
            m.cleanup();
            for (Player p : m.everyone()) arcade.toLobby(p);
        }
        active.clear();
        playerMatch.clear();
    }

    private static void msg(UUID u, String mm) { Player p = Bukkit.getPlayer(u); if (p != null) p.sendMessage(Ui.mm(mm)); }
}
