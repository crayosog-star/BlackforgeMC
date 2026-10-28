package net.alloymc.core.arcade.core;

import net.alloymc.core.Ui;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.*;

/**
 * One running game on one private map instance. Holds only its own players, teams, blocks and tasks,
 * so matches can never affect each other. Subclasses implement the rules; this class owns the
 * lifecycle (TELEPORTING_PLAYERS -> STARTING -> IN_PROGRESS -> ENDING -> RESETTING -> AVAILABLE),
 * deaths, spectators, rewards/stats hand-off and cleanup.
 */
public abstract class Match {
    public final String id;
    public final ArcadeGame game;
    public final Variant variant;
    public final MapDef map;
    public final int slot;
    protected final Arcade arcade;
    protected final World world;
    protected final int ox, oy, oz;
    protected final Settings settings;
    protected final Random rnd = new Random();

    final MatchState.Machine state = new MatchState.Machine(MatchState.MAP_LOADING);
    protected final List<UUID> players = new ArrayList<>();        // everyone who started (or late-joined)
    protected final Set<UUID> alive = new LinkedHashSet<>();         // still in the running
    protected final List<Team> teams = new ArrayList<>();
    protected final Map<UUID, Team> teamOf = new HashMap<>();
    protected final Map<UUID, Integer> kills = new HashMap<>(), deaths = new HashMap<>(), streak = new HashMap<>(), bestStreak = new HashMap<>();
    protected final Map<UUID, Map<UUID, Long>> damagers = new HashMap<>();   // victim -> attacker -> last hit time
    protected final Set<Long> placed = new HashSet<>();              // blocks placed during this match
    final Set<UUID> spectators = new HashSet<>();                    // outside watchers (/arcade spectate)
    private final List<BukkitTask> tasks = new ArrayList<>();
    final Map<UUID, Long> joinedAt = new HashMap<>();
    protected int elapsed, countdown;
    private final List<UUID> lastOut = new ArrayList<>();   // everyone eliminated in the most recent second
    private int lastOutAt = -1;
    private int endTimer;
    List<UUID> winners = List.of();
    private boolean ended;

    protected Match(MatchContext c) {
        this.id = c.id(); this.game = c.game(); this.variant = c.variant(); this.map = c.map(); this.slot = c.slot();
        this.arcade = c.arcade(); this.world = c.world(); this.ox = c.ox(); this.oy = c.oy(); this.oz = c.oz(); this.settings = c.settings();
    }

    public MatchState state() { return state.get(); }
    public List<UUID> players() { return players; }
    public boolean has(UUID u) { return players.contains(u); }
    public boolean isAlive(Player p) { return alive.contains(p.getUniqueId()); }
    public Team team(Player p) { return teamOf.get(p.getUniqueId()); }
    public int aliveCount() { return alive.size(); }

    // ------------------------------------------------------------------ coordinates

    public Location at(Pt p) { return new Location(world, ox + p.x(), oy + p.y(), oz + p.z(), p.yaw(), p.pitch()); }
    public Location at(double x, double y, double z) { return new Location(world, ox + x, oy + y, oz + z); }
    protected Location point(String key) { Pt p = map.point(key); return p == null ? null : at(p); }
    protected List<Location> points(String key) { List<Location> l = new ArrayList<>(); for (Pt p : map.list(key)) l.add(at(p)); return l; }
    protected Location teamPoint(Team t, String key) { return point("team." + t.index + "." + key); }
    protected Box absolute(Box b) { return new Box(b.x1() + ox, b.y1() + oy, b.z1() + oz, b.x2() + ox, b.y2() + oy, b.z2() + oz); }
    protected Box region(String key) { Box b = map.region(key); return b == null ? null : absolute(b); }
    public boolean inBounds(Location l) { return l.getWorld() == world && absolute(map.bounds).contains(l.getX(), l.getY(), l.getZ()); }
    protected boolean belowVoid(Location l) { return l.getY() < oy + map.voidY; }
    protected static long key(Block b) { return ((long) b.getX() & 0x3FFFFFF) << 38 | ((long) b.getZ() & 0x3FFFFFF) << 12 | (b.getY() & 0xFFF); }

    // ------------------------------------------------------------------ messaging

    protected List<Player> online(Collection<UUID> ids) {
        List<Player> l = new ArrayList<>();
        for (UUID u : ids) { Player p = Bukkit.getPlayer(u); if (p != null && p.isOnline()) l.add(p); }
        return l;
    }
    protected List<Player> everyone() { return online(players); }
    protected List<Player> living() { return online(alive); }

    public void broadcast(String mm) { for (Player p : everyone()) p.sendMessage(Ui.mm(mm)); }
    protected void title(String t, String sub) { for (Player p : everyone()) title(p, t, sub); }
    protected static void title(Player p, String t, String sub) {
        p.showTitle(Title.title(Ui.mm(t), Ui.mm(sub), Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(1600), Duration.ofMillis(400))));
    }
    protected void sound(Sound s, float pitch) { for (Player p : everyone()) p.playSound(p.getLocation(), s, 0.8f, pitch); }
    protected void actionBar(String mm) { for (Player p : everyone()) p.sendActionBar(Ui.mm(mm)); }
    protected String prefix() { return game.color + "<bold>" + game.name.toUpperCase() + "</bold> <dark_gray>» <gray>"; }

    // ------------------------------------------------------------------ tasks (all cancelled at cleanup)

    protected BukkitTask later(long ticks, Runnable r) { BukkitTask t = Bukkit.getScheduler().runTaskLater(arcade.plugin(), r, ticks); tasks.add(t); return t; }
    protected BukkitTask every(long ticks, Runnable r) { BukkitTask t = Bukkit.getScheduler().runTaskTimer(arcade.plugin(), r, ticks, ticks); tasks.add(t); return t; }

    // ------------------------------------------------------------------ hooks for games

    /** Teams are assigned; put everyone at their spawns with their kit. */
    protected abstract void setup(List<Player> ps);
    /** Called once when the game goes live (after the starting countdown). */
    protected void onStart() {}
    protected int startingSeconds() { return 3; }
    /** Freeze players in place during STARTING. */
    protected boolean freezeWhileStarting() { return true; }
    protected void secondTick() {}
    protected void fastTick() {}
    /** Round length in seconds; 0 = no limit. */
    protected int timeLimit() { return settings.i("time-limit", 600); }
    protected void onTimeUp() { finish(List.of(), "Time's up - it's a draw!"); }
    protected abstract String objective();
    /** Lines under the title in the sidebar. */
    protected List<String> sidebar(Player p) {
        List<String> l = new ArrayList<>();
        l.add("<gray>Map: <white>" + map.display);
        l.add("<gray>Alive: <white>" + alive.size());
        if (timeLimit() > 0) l.add("<gray>Time left: <white>" + clock(timeLimit() - elapsed));
        return l;
    }

    protected boolean pvp() { return true; }
    protected boolean friendlyFire() { return false; }
    protected boolean hunger() { return false; }
    protected boolean naturalRegen() { return true; }
    protected boolean allowBreak(Player p, Block b) { return false; }
    protected boolean allowPlace(Player p, Block b) { return false; }
    protected boolean allowDrop(Player p, ItemStack it) { return false; }
    protected boolean allowPickup(Player p, ItemStack it) { return true; }
    protected boolean allowCraft() { return false; }
    /** Return true to let vanilla damage through (after friendly-fire/pvp checks). */
    protected boolean onDamage(Player victim, Entity attacker, EntityDamageEvent e) { return true; }
    protected void onInteract(Player p, PlayerInteractEvent e) {}
    protected void onInteractEntity(Player p, PlayerInteractEntityEvent e) {}
    protected void onProjectileHit(Player shooter, ProjectileHitEvent e) {}
    protected void onInventoryClick(Player p, InventoryClickEvent e) {}
    protected void onMove(Player p, Location from, Location to) {}
    /** Get onMove for every movement, not just block changes. */
    protected boolean wantsFineMoves() { return false; }
    /** Blocks an explosion may destroy (default: only blocks placed during the match). */
    protected boolean explodable(Block b) { return placed.contains(key(b)); }
    /** Damage to a non-player entity inside this match's arena (NPCs, mobs). Default: protect it. */
    protected void onEntityDamage(EntityDamageEvent e, Player attacker) { e.setCancelled(true); }
    protected void onSneak(Player p, boolean sneaking) {}
    protected void onConsume(Player p, ItemStack it) {}
    /** May liquid flow from one block to another inside this arena? */
    protected boolean allowFlow(Block from, Block to) { return true; }
    /** May mobs be spawned (spawn eggs) in this arena? */
    protected boolean allowMobSpawn() { return false; }
    protected void onBreak(Player p, BlockBreakEvent e) {}
    protected void onPlace(Player p, BlockPlaceEvent e) {}
    /** A player "died" (lethal damage or the void). Default: out for good. */
    protected void onDeath(Player p, Player killer, String how) { eliminate(p, deathMessage(p, killer, how)); }
    protected void onVoid(Player p) { killPlayer(p, "void"); }
    protected void onLeave(Player p) {}
    /** Who has won right now (null = keep playing). Default: the last team or player standing. */
    protected List<UUID> checkWinners() {
        if (variant.isTeams()) {
            List<Team> left = teams.stream().filter(t -> !t.eliminated && t.members.stream().anyMatch(alive::contains)).toList();
            if (left.size() <= 1) return left.isEmpty() ? List.of() : new ArrayList<>(left.getFirst().members);
            return null;
        }
        if (alive.isEmpty()) return new ArrayList<>(lastOut);   // everyone went out together: the last ones out share it
        if (alive.size() <= 1 && players.size() > 1) return new ArrayList<>(alive);
        return null;
    }
    protected boolean supportsRejoin() { return false; }

    // ------------------------------------------------------------------ lifecycle (driven by MatchManager)

    void begin(List<Player> ps) {
        state.go(MatchState.TELEPORTING_PLAYERS);
        for (Player p : ps) { players.add(p.getUniqueId()); alive.add(p.getUniqueId()); joinedAt.put(p.getUniqueId(), System.currentTimeMillis()); }
        assignTeams(ps);
        for (Player p : ps) resetPlayer(p, GameMode.ADVENTURE);
        setup(ps);
        for (Player p : ps) arcade.stats().add(p.getUniqueId(), game.id, "played", 1);
        state.go(MatchState.STARTING);
        countdown = startingSeconds();
        if (countdown <= 0) goLive();
    }

    protected void assignTeams(List<Player> ps) {
        if (variant.isTeams()) {
            List<List<Player>> split = TeamBalancer.split(ps, map.teams > 0 ? Math.min(variant.teams(), map.teams) : variant.teams(), variant.teamSize());
            for (int i = 0; i < split.size(); i++) {
                Team t = new Team(i);
                for (Player p : split.get(i)) { t.members.add(p.getUniqueId()); teamOf.put(p.getUniqueId(), t); }
                teams.add(t);
            }
        } else {
            for (int i = 0; i < ps.size(); i++) {
                Team t = new Team(i);
                t.members.add(ps.get(i).getUniqueId());
                teamOf.put(ps.get(i).getUniqueId(), t);
                teams.add(t);
            }
        }
    }

    private void goLive() {
        state.go(MatchState.IN_PROGRESS);
        elapsed = 0;
        title(game.color + "<bold>" + game.name.toUpperCase(), "<white>" + objective());
        sound(Sound.ENTITY_ENDER_DRAGON_GROWL, 1.6f);
        broadcast("<aqua><bold>BLACKFORGE ARCADE</bold> <dark_gray>» <green>Match starting! <gray>" + objective());
        onStart();
    }

    void tickSecond() {
        switch (state.get()) {
            case STARTING -> {
                if (countdown > 0) {
                    title(game.color + "<bold>" + countdown, "<gray>" + map.display);
                    sound(Sound.BLOCK_NOTE_BLOCK_HAT, 1.0f + (5 - Math.min(countdown, 5)) * 0.15f);
                }
                if (--countdown <= 0) goLive();
            }
            case IN_PROGRESS -> {
                elapsed++;
                for (Player p : everyone()) if (!isAlive(p) && p.getGameMode() == GameMode.SPECTATOR && (!inBounds(p.getLocation()) || belowVoid(p.getLocation()))) p.teleport(at(map.spectator));
                for (Player p : online(spectators)) if (!inBounds(p.getLocation())) p.teleport(at(map.spectator));
                secondTick();
                if (state.is(MatchState.IN_PROGRESS) && timeLimit() > 0 && elapsed >= timeLimit()) onTimeUp();
                if (state.is(MatchState.IN_PROGRESS)) { List<UUID> w = checkWinners(); if (w != null) finish(w, null); }
            }
            case ENDING -> { if (--endTimer <= 0) arcade.matches().reset(this); }
            default -> {}
        }
        if (state.get().live() || state.is(MatchState.ENDING)) for (Player p : everyone()) {
            List<String> lines = new ArrayList<>();
            lines.add("<dark_gray>" + id);
            lines.addAll(sidebar(p));
            lines.add(" ");
            lines.add("<aqua>blackforgemc.com");
            Ui.sidebar(p, lines);
        }
    }

    void tickFast() {
        if (!state.get().live()) return;
        for (Player p : living()) if (belowVoid(p.getLocation())) {
            // a player waiting to respawn is a spectator: flying under the void must not "kill" them again
            // (that restarted their respawn timer forever), just bring them back up
            if (p.getGameMode() == GameMode.SPECTATOR) { if (map.spectator != null) p.teleport(at(map.spectator)); }
            else onVoid(p);
        }
        if (state.is(MatchState.IN_PROGRESS)) fastTick();
    }

    /** End the match. winners empty = draw. Safe to call more than once (only the first counts). */
    public void finish(List<UUID> winnerIds, String reason) {
        if (ended || !(state.get().live())) return;
        ended = true;
        state.go(MatchState.ENDING);
        endTimer = 8;
        winners = List.copyOf(winnerIds);
        if (reason != null) broadcast(prefix() + "<yellow>" + reason);
        List<Player> ws = online(winners);
        String names = ws.isEmpty() ? "nobody" : String.join(", ", ws.stream().map(Player::getName).toList());
        Team wt = ws.isEmpty() ? null : team(ws.getFirst());
        String who = variant.isTeams() && wt != null ? wt.name() + " team" : "<gold>" + names;
        broadcast("<dark_gray>" + "-".repeat(30));
        broadcast(prefix() + (ws.isEmpty() ? "<yellow>No winner this time." : "<white>Winner: " + who));
        List<Map.Entry<UUID, Integer>> top = kills.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(3).toList();
        for (int i = 0; i < top.size(); i++) if (top.get(i).getValue() > 0)
            broadcast("<gray>  " + (i + 1) + ". <white>" + Bukkit.getOfflinePlayer(top.get(i).getKey()).getName() + " <gray>- " + top.get(i).getValue() + " kills");
        broadcast("<dark_gray>" + "-".repeat(30));
        for (Player p : everyone()) {
            boolean won = winners.contains(p.getUniqueId());
            title(p, won ? "<gold><bold>VICTORY!" : ws.isEmpty() ? "<yellow><bold>DRAW" : "<red><bold>GAME OVER", won ? "<gray>You won " + game.name + "!" : "<gray>" + who + " <gray>won");
            if (won) { p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f); firework(p.getLocation()); }
        }
        arcade.matches().onFinished(this);
    }

    /** Called by MatchManager when the ENDING screen is over: undo everything this match did to players. */
    void cleanup() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        onCleanup();
    }

    protected void onCleanup() {}

    // ------------------------------------------------------------------ players

    public static void resetPlayer(Player p, GameMode mode) {
        p.setGameMode(mode);
        p.getInventory().clear();
        p.getInventory().setArmorContents(null);
        p.setItemOnCursor(null);
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
        var max = p.getAttribute(Attribute.MAX_HEALTH);
        if (max != null) { max.setBaseValue(20); p.setHealth(20); }
        p.setFoodLevel(20);
        p.setSaturation(5);
        p.setFireTicks(0);
        p.setFallDistance(0);
        p.setExp(0);
        p.setLevel(0);
        // spectators must keep flight: without it they fall straight through the map forever
        boolean spec = mode == GameMode.SPECTATOR;
        p.setAllowFlight(spec);
        p.setFlying(spec);
        p.setGlowing(false);
        p.setInvisible(false);
        p.setWalkSpeed(0.2f);
        p.setMaximumNoDamageTicks(20);
        p.closeInventory();
    }

    protected Player killerOf(Player victim) {
        Map<UUID, Long> m = damagers.get(victim.getUniqueId());
        if (m == null) return null;
        long now = System.currentTimeMillis();
        return m.entrySet().stream().filter(e -> now - e.getValue() < 12_000).max(Map.Entry.comparingByValue())
                .map(e -> Bukkit.getPlayer(e.getKey())).filter(p -> p != null && has(p.getUniqueId())).orElse(null);
    }

    /** Players (other than the killer) who hit the victim in the last 10 seconds. */
    protected List<Player> assistsOf(Player victim, Player killer) {
        Map<UUID, Long> m = damagers.getOrDefault(victim.getUniqueId(), Map.of());
        long now = System.currentTimeMillis();
        List<Player> out = new ArrayList<>();
        for (var e : m.entrySet()) if (now - e.getValue() < 10_000 && (killer == null || !e.getKey().equals(killer.getUniqueId()))) { Player p = Bukkit.getPlayer(e.getKey()); if (p != null) out.add(p); }
        return out;
    }

    /** Route a lethal hit / void fall through the game's death rules, with kill credit. */
    public void killPlayer(Player p, String how) {
        if (!isAlive(p) || !state.is(MatchState.IN_PROGRESS)) {
            if (state.get().live() && map.spectator != null) p.teleport(at(map.spectator));
            return;
        }
        Player killer = killerOf(p);
        deaths.merge(p.getUniqueId(), 1, Integer::sum);
        streak.put(p.getUniqueId(), 0);
        arcade.stats().add(p.getUniqueId(), game.id, "deaths", 1);
        if (killer != null && !killer.equals(p)) {
            kills.merge(killer.getUniqueId(), 1, Integer::sum);
            int s = streak.merge(killer.getUniqueId(), 1, Integer::sum);
            bestStreak.merge(killer.getUniqueId(), s, Math::max);
            arcade.stats().add(killer.getUniqueId(), game.id, "kills", 1);
            arcade.stats().max(killer.getUniqueId(), game.id, "best_streak", s);
            arcade.rewards().kill(this, killer);
            net.alloymc.core.Hooks.kill(killer, p, "arcade:" + game.id);
            killer.playSound(killer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.4f);
            for (Player a : assistsOf(p, killer)) arcade.stats().add(a.getUniqueId(), game.id, "assists", 1);
        }
        damagers.remove(p.getUniqueId());
        p.setFireTicks(0);
        p.setFallDistance(0);
        onDeath(p, killer, how);
    }

    protected String deathMessage(Player p, Player killer, String how) {
        String v = nameOf(p);
        if (killer != null) return v + " <gray>was " + (how.equals("void") ? "knocked into the void by " : "killed by ") + nameOf(killer)
                + net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().serialize(
                        net.alloymc.core.KillFeed.weapon(killer.getInventory().getItemInMainHand()));
        return v + " <gray>" + (how.equals("void") ? "fell into the void" : "died");
    }

    protected String nameOf(Player p) { Team t = team(p); return (variant.isTeams() && t != null ? t.color.mm : "<white>") + p.getName(); }

    /** Out of the game: becomes a spectator inside this map only. */
    protected void eliminate(Player p, String message) {
        if (!alive.remove(p.getUniqueId())) return;
        if (lastOutAt != elapsed) { lastOut.clear(); lastOutAt = elapsed; }
        lastOut.add(p.getUniqueId());
        if (message != null) broadcast(prefix() + message + " <dark_gray>(" + alive.size() + " left)");
        makeSpectator(p);
    }

    protected void makeSpectator(Player p) {
        resetPlayer(p, GameMode.SPECTATOR);
        if (map.spectator != null) p.teleport(at(map.spectator));
        p.sendMessage(Ui.mm("<gray>You're spectating: fly anywhere in the arena, <white>left-click a player<gray> to watch through their eyes (sneak to stop), or press <white>1<gray> to jump to a player. <white>/arcade leave <gray>to go back to the lobby."));
    }

    void addSpectator(Player p) {
        spectators.add(p.getUniqueId());
        resetPlayer(p, GameMode.SPECTATOR);
        p.teleport(at(map.spectator));
        p.sendMessage(Ui.mm(MatchManager.TAG + "<gray>Spectating <white>" + id + " <gray>(" + game.name + " on " + map.display + "). <white>/arcade leave <gray>to stop."));
    }

    /** A player left the match (quit, /leave, server switch). */
    void remove(Player p, boolean quitting) {
        UUID u = p.getUniqueId();
        if (!players.contains(u)) return;
        boolean wasAlive = alive.remove(u);
        onLeave(p);
        if (state.get().live() && wasAlive) {
            arcade.stats().add(u, game.id, "losses", 1);
            broadcast(prefix() + nameOf(p) + " <gray>left the game." + (quitting ? "" : ""));
        }
        players.remove(u);
        Team t = teamOf.get(u);
        if (t != null && !supportsRejoin()) t.members.remove(u);
        if (state.is(MatchState.STARTING) && alive.isEmpty()) finish(List.of(), "Everyone left.");
    }

    void lateJoin(Player p) {
        players.add(p.getUniqueId());
        alive.add(p.getUniqueId());
        joinedAt.put(p.getUniqueId(), System.currentTimeMillis());
        Team t = new Team(teams.size());
        t.members.add(p.getUniqueId());
        teams.add(t);
        teamOf.put(p.getUniqueId(), t);
        resetPlayer(p, GameMode.ADVENTURE);
        arcade.stats().add(p.getUniqueId(), game.id, "played", 1);
        onLateJoin(p);
    }

    protected void onLateJoin(Player p) {}

    boolean canRejoin(Player p) { Team t = teamOf.get(p.getUniqueId()); return t != null && !t.eliminated && allowRejoin(p); }
    protected boolean allowRejoin(Player p) { return true; }

    void rejoin(Player p) {
        UUID u = p.getUniqueId();
        if (!players.contains(u)) players.add(u);
        alive.add(u);
        resetPlayer(p, GameMode.SURVIVAL);
        broadcast(prefix() + nameOf(p) + " <gray>reconnected.");
        onRejoin(p);
    }

    /** Put a reconnecting player back in (spawn, kit). */
    protected void onRejoin(Player p) { if (map.spectator != null) p.teleport(at(map.spectator)); }

    public void recordDamage(Player victim, Player attacker) {
        damagers.computeIfAbsent(victim.getUniqueId(), k -> new HashMap<>()).put(attacker.getUniqueId(), System.currentTimeMillis());
    }

    protected boolean sameTeam(Player a, Player b) {
        Team ta = team(a), tb = team(b);
        return variant.isTeams() && ta != null && ta == tb;
    }

    protected void firework(Location l) {
        world.spawn(l.clone().add(0, 1, 0), Firework.class, fw -> {
            FireworkMeta m = fw.getFireworkMeta();
            m.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE).withColor(Color.AQUA, Color.SILVER).withFade(Color.TEAL).trail(true).build());
            m.setPower(1);
            fw.setFireworkMeta(m);
        });
    }

    protected static String clock(int s) { s = Math.max(0, s); return (s / 60) + ":" + String.format("%02d", s % 60); }

    /** Give a sword/tool that never breaks. */
    protected static ItemStack unbreakable(Material m) {
        ItemStack it = new ItemStack(m);
        it.editMeta(x -> x.setUnbreakable(true));
        return it;
    }

    public String summary() {
        return id + " " + game.name + " [" + variant.label() + "] on " + map.display + " - " + state.get() + ", " + players.size() + " players, " + alive.size() + " alive, " + clock(elapsed);
    }
}
