package net.alloymc.core.arcade.games.parkour;

import net.alloymc.core.Ui;
import net.alloymc.core.arcade.core.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/** Parkour: jump courses with checkpoints, timers and personal bests. Practice solo, or race others. */
public final class Parkour extends ArcadeGame {
    public Parkour() {
        super("parkour", "Parkour", "<#9dff6b>", Material.FEATHER, Category.CASUAL,
                "Jump through the course as fast as you can. Checkpoints save your progress; beat your personal best.");
        variants.add(Variant.ffa("solo", "Solo Run", 1, 1));
        variants.add(Variant.ffa("race", "Race", 2, 8));
    }

    @Override public int queueCountdown() { return 3; }

    @Override public MapRequirements requirements() {
        return new MapRequirements().point("start").list("checkpoints", 1).region("finish").list("spawns", 1);
    }

    @Override public Map<String, Object> defaultSettings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time-limit", 600);
        m.put("race-grace-seconds", 20);
        return m;
    }

    @Override public List<String> howToPlay() {
        return List.of("Reach the finish platform. Touching a checkpoint (gold plate) saves your spot; fall and you go back to it.",
                "Right-click the red dye to return to your checkpoint, the barrier to restart. Personal bests go on the leaderboard.");
    }

    @Override public Match newMatch(MatchContext c) { return new ParkourMatch(c, "parkour"); }

    @Override public List<BuiltMap> builtInMaps() {
        return List.of(course("meadow_run", "Meadow Run", "Easy", 24, 3, 0, 41, "grass_block", "oak_leaves[persistent=true]", "moss_block"),
                       course("foundry_climb", "Foundry Climb", "Medium", 30, 3, 1, 42, "waxed_cut_copper", "iron_block", "polished_deepslate"),
                       course("ember_spire", "Ember Spire", "Hard", 36, 4, 1, 43, "polished_blackstone", "magma_block", "gilded_blackstone"));
    }

    /** A jump course: platforms rising along a winding path; gap/height grow with difficulty. */
    static BuiltMap course(String id, String name, String diff, int jumps, int gap, int rise, long seed, String block, String accent, String checkpointBlock) {
        MapKit k = new MapKit(seed);
        MapDef d = new MapDef("parkour", id);
        d.display = name; d.description = diff + " - " + jumps + " jumps.";
        d.builderVersion = 2;
        d.minPlayers = 1; d.maxPlayers = 8;
        d.set("difficulty", diff);
        // start platform
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) k.set(x, 0, z, "polished_andesite");
        k.late(0, 1, 2, "light_weighted_pressure_plate");
        // each course has its own start layout on its pad
        int di = diff.equals("Easy") ? 0 : diff.equals("Medium") ? 1 : 2;
        d.point("start", Pt.of(0.5 + (di - 1), 1, 0.5 - di, 180));
        for (int i = 0; i < 8; i++) d.add("spawns", di == 1 ? Pt.of(-3 + i % 7 + 0.5, 1, 2.5 - (i / 7) * 2.0, 180)
                                                         : Pt.of(-3 + (i % 4) * 2 + 0.5 + (di == 2 ? 0.5 : 0), 1, -2.5 + (i / 4) * (di == 2 ? 2.0 : 1.0), 180));
        // Every jump is built from an edge-to-edge gap and checked against real sprint-jump physics
        // (jumpReach), with a safety margin per difficulty, so no course can contain an impossible jump.
        int di2 = di;
        double margin = new double[]{1.2, 0.6, 0.3}[di2];
        int[] prev = {-3, 3, -3, 3, 0};          // x0, x1, z0, z1, y of the start pad
        int x = 0, y = 0, z = 0, minX = -4, maxX = 4, minZ = -6, maxZ = 4, maxY = 2;
        for (int j = 1; j <= jumps; j++) {
            boolean cp = j % 6 == 0;
            int up = rise == 0 ? 0 : switch (k.rnd.nextInt(6)) { case 0, 1 -> 1; case 2 -> -1; default -> 0; };
            if (prev[4] + up < 0) up = 0;
            // gap along the course (blocks of air between the platforms)
            int g = switch (di2) {
                case 0 -> up > 0 ? 1 : 1 + k.rnd.nextInt(2);                               // 1-2
                case 1 -> up > 0 ? 1 + k.rnd.nextInt(2) : up < 0 ? 3 : 2 + k.rnd.nextInt(2); // 2-3
                default -> up > 0 ? 2 : up < 0 ? 3 + k.rnd.nextInt(2) : 2 + k.rnd.nextInt(2); // 3s and +1 2s
            };
            int half = cp ? 1 : di2 == 0 ? 1 : 0;                                         // 3x3 or 1x1
            int wide = !cp && di2 == 1 && k.rnd.nextInt(3) == 0 ? 1 : 0;                   // some 2x1 on Medium
            int px = (prev[0] + prev[1]) / 2, lat = k.rnd.nextInt(5) - 2;
            if (Math.abs(px + lat) > 10) lat = -Integer.signum(px) * Math.abs(lat);
            int[] next = null;
            for (int tryLat : new int[]{lat, lat / 2, 0}) {
                int nx = px + tryLat;
                int[] c = {nx - half, nx + half + wide, prev[2] - 1 - g - 2 * half, prev[2] - 1 - g, prev[4] + up};
                int gx = Math.max(0, Math.max(c[0] - prev[1] - 1, prev[0] - c[1] - 1));
                double dist = Math.hypot(gx, g);
                if (dist - 0.6 + margin <= jumpReach(up)) { next = c; break; }
            }
            if (next == null) throw new IllegalStateException("parkour " + id + ": jump " + j + " has no possible landing");
            for (int bx = next[0]; bx <= next[1]; bx++) for (int bz = next[2]; bz <= next[3]; bz++)
                k.set(bx, next[4], bz, cp ? checkpointBlock : (j % 3 == 0 ? accent : block));
            x = (next[0] + next[1]) / 2; z = (next[2] + next[3]) / 2; y = next[4];
            if (cp) { k.late(x, y + 1, z, "light_weighted_pressure_plate"); d.add("checkpoints", Pt.of(x + 0.5, y + 1, z + 0.5, 180)); }
            prev = next;
            minX = Math.min(minX, next[0] - 3); maxX = Math.max(maxX, next[1] + 3); minZ = Math.min(minZ, next[2] - 3); maxZ = Math.max(maxZ, z + 3); maxY = Math.max(maxY, y);
        }
        gap = di2 == 0 ? 2 : 3;
        // finish platform with a gold arch (a flat jump from the last platform)
        z = prev[2] - 1 - gap - 2;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) k.set(x + dx, y, z + dz, "emerald_block");
        for (int h = 1; h <= 4; h++) { k.set(x - 2, y + h, z, "gold_block"); k.set(x + 2, y + h, z, "gold_block"); }
        for (int dx = -2; dx <= 2; dx++) k.set(x + dx, y + 5, z, "gold_block");
        k.late(x, y + 6, z, "beacon");
        d.region("finish", Box.of(x - 2, y + 1, z - 2, x + 2, y + 3, z + 2));
        minZ = Math.min(minZ, z - 4);
        d.bounds = Box.of(minX - 6, -20, minZ - 6, maxX + 6, maxY + 24, maxZ + 6);
        d.voidY = -8;
        d.spectator = new Pt(x + 0.5, y + 10, z + 8.5, 180, 30);
        if (d.list("checkpoints").isEmpty()) d.add("checkpoints", d.point("start"));
        return new BuiltMap(d, k.c);
    }

    /**
     * Furthest a sprint-jump carries the player's centre (blocks, horizontally) before dropping below a landing
     * {@code dy} blocks higher/lower than the take-off. Vanilla movement: ground sprint 0.13 accel / 0.546 drag,
     * jump +0.2 boost and 0.42 up, air 0.026 accel / 0.91 drag, gravity 0.08 with 0.98 drag. A gap of G air
     * blocks needs G - 0.6 of travel (you can stand 0.3 past an edge and land 0.3 short of one).
     */
    static double jumpReach(int dy) {
        double v = 0.1563, vy = 0.42, x = 0, y = 0, best = 0;
        double move = v + 0.2 + 0.13;
        x += move; y += vy; v = move * 0.546; vy = (vy - 0.08) * 0.98;
        for (int t = 0; t < 80; t++) {
            move = v + 0.026; x += move; y += vy;
            v = move * 0.91; vy = (vy - 0.08) * 0.98;
            if (y >= dy) best = x;
            else if (vy < 0) break;
        }
        return best;
    }

    /** Shared by Parkour and Dropper-style time trials: checkpoints, fall recovery, finish times, PBs. */
    static class ParkourMatch extends Match {
        final String statGame;
        final Map<UUID, Integer> checkpoint = new HashMap<>();
        final Map<UUID, Long> finished = new LinkedHashMap<>();
        long startMs;
        int raceEndAt = -1;

        ParkourMatch(MatchContext c, String statGame) { super(c); this.statGame = statGame; }

        @Override protected String objective() { return variant.id().equals("race") ? "First to the finish wins!" : "Reach the finish - beat your best time!"; }
        @Override protected boolean pvp() { return false; }
        @Override protected boolean onDamage(Player v, Entity a, EntityDamageEvent e) { return false; }
        @Override protected boolean freezeWhileStarting() { return true; }

        @Override protected void setup(List<Player> ps) {
            List<Location> sp = points("spawns");
            for (int i = 0; i < ps.size(); i++) { ps.get(i).teleport(sp.get(i % sp.size())); hotbar(ps.get(i)); }
        }

        void hotbar(Player p) {
            p.getInventory().setItem(3, Ui.item(Material.RED_DYE, "<red><bold>Back to checkpoint</bold> <gray>(right-click)"));
            p.getInventory().setItem(5, Ui.item(Material.BARRIER, "<gray><bold>Restart</bold> <gray>(right-click)"));
        }

        @Override protected void onStart() { startMs = System.currentTimeMillis(); }

        Location respawn(Player p) {
            int cp = checkpoint.getOrDefault(p.getUniqueId(), -1);
            List<Location> cps = points("checkpoints");
            return cp < 0 || cp >= cps.size() ? point("start") : cps.get(cp);
        }

        @Override protected void onVoid(Player p) {
            p.setFallDistance(0);
            p.teleport(respawn(p));
            p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.2f);
        }

        @Override protected void onInteract(Player p, PlayerInteractEvent e) {
            ItemStack it = e.getItem();
            if (it == null || (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
            if (it.getType() == Material.RED_DYE) { e.setCancelled(true); onVoid(p); }
            else if (it.getType() == Material.BARRIER) { e.setCancelled(true); checkpoint.remove(p.getUniqueId()); p.teleport(point("start")); p.sendMessage(Ui.mm(prefix() + "Restarted - the clock keeps running.")); }
        }

        @Override protected void onMove(Player p, Location from, Location to) {
            if (finished.containsKey(p.getUniqueId())) return;
            List<Location> cps = points("checkpoints");
            int cur = checkpoint.getOrDefault(p.getUniqueId(), -1);
            for (int i = cur + 1; i < cps.size(); i++)
                if (cps.get(i).distanceSquared(to) < 2.3) {
                    checkpoint.put(p.getUniqueId(), i);
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, 1.4f);
                    p.sendActionBar(Ui.mm("<gold>Checkpoint " + (i + 1) + "/" + cps.size() + " <gray>• " + time(System.currentTimeMillis() - startMs)));
                    break;
                }
            if (region("finish").contains(to.getX(), to.getY(), to.getZ())) complete(p);
        }

        void complete(Player p) {
            long t = System.currentTimeMillis() - startMs;
            finished.put(p.getUniqueId(), t);
            boolean pb = arcade.stats().best(p.getUniqueId(), statGame, "best_time", t);
            boolean mapPb = arcade.stats().best(p.getUniqueId(), statGame, "best_time_" + map.id, t);
            arcade.stats().add(p.getUniqueId(), statGame, "completions", 1);
            broadcast(prefix() + nameOf(p) + " <gray>finished in <white>" + time(t) + (pb ? " <gold><bold>NEW PERSONAL BEST!" : mapPb ? " <yellow>(best on this map)" : ""));
            title(p, "<green><bold>FINISHED!", "<white>" + time(t));
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.2f);
            firework(p.getLocation());
            if (finished.size() == 1 && variant.id().equals("race") && players.size() > 1) {
                raceEndAt = elapsed + settings.i("race-grace-seconds", 20);
                broadcast(prefix() + "The race ends in <white>" + settings.i("race-grace-seconds", 20) + "s<gray>!");
            }
        }

        static String time(long ms) { return String.format("%d:%02d.%02d", ms / 60000, (ms / 1000) % 60, (ms / 10) % 100); }

        @Override protected void secondTick() {
            for (Player p : living()) if (!finished.containsKey(p.getUniqueId())) p.sendActionBar(Ui.mm(game.color + time(System.currentTimeMillis() - startMs) + " <gray>• checkpoint " + (checkpoint.getOrDefault(p.getUniqueId(), -1) + 1) + "/" + map.list("checkpoints").size()));
        }

        @Override protected List<UUID> checkWinners() {
            boolean allDone = living().stream().allMatch(p -> finished.containsKey(p.getUniqueId()));
            if (finished.isEmpty()) return alive.isEmpty() ? List.of() : null;
            if (allDone || (raceEndAt >= 0 && elapsed >= raceEndAt)) return List.of(finished.keySet().iterator().next());
            return null;
        }

        @Override protected void onTimeUp() {
            finish(finished.isEmpty() ? List.of() : List.of(finished.keySet().iterator().next()), "Time's up!");
        }

        @Override protected List<String> sidebar(Player p) {
            List<String> l = new ArrayList<>();
            l.add("<gray>Course: <white>" + map.display + " <dark_gray>(" + settings.s("difficulty", "?") + ")");
            l.add("<gray>Time: <white>" + time(System.currentTimeMillis() - startMs));
            if (arcade.stats().has(p.getUniqueId(), statGame, "best_time_" + map.id)) l.add("<gray>Your best: <gold>" + time(arcade.stats().get(p.getUniqueId(), statGame, "best_time_" + map.id)));
            for (var e : finished.entrySet()) l.add("<green>✔ <white>" + org.bukkit.Bukkit.getOfflinePlayer(e.getKey()).getName() + " <gray>" + time(e.getValue()));
            return l;
        }
    }
}
