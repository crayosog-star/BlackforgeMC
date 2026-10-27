package net.alloymc.core.arcade.games.dropper;

import net.alloymc.core.Ui;
import net.alloymc.core.arcade.core.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;

import java.util.*;

/** Dropper: fall down obstacle shafts and land in the water. Several levels per map; fastest total time wins. */
public final class Dropper extends ArcadeGame {
    public Dropper() {
        super("dropper", "Dropper", "<#6bd5ff>", Material.WATER_BUCKET, Category.CASUAL,
                "Fall down each shaft, dodge the obstacles and land in the water. Clear every level as fast as you can.");
        variants.add(Variant.ffa("solo", "Solo Run", 1, 1));
        variants.add(Variant.ffa("race", "Race", 2, 8));
    }

    @Override public int queueCountdown() { return 3; }

    @Override public MapRequirements requirements() {
        return new MapRequirements().list("level_starts", 1).check(m -> {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < m.list("level_starts").size(); i++) if (m.region("level." + i + ".goal") == null) out.add("missing region 'level." + i + ".goal' (the water for level " + (i + 1) + ")");
            return out;
        });
    }

    @Override public Map<String, Object> defaultSettings() { Map<String, Object> m = new LinkedHashMap<>(); m.put("time-limit", 600); m.put("race-grace-seconds", 20); return m; }

    @Override public Match newMatch(MatchContext c) { return new DropMatch(c); }

    @Override public List<BuiltMap> builtInMaps() {
        return List.of(build("forge_shafts", "Forge Shafts", "Medium", 51, "polished_blackstone_bricks", "gilded_blackstone", "gold_block", "shroomlight", 4, 46,
                               new int[][]{{2, 2}, {2, 1}, {2, 1}}, 5),
                       build("emerald_depths", "Emerald Depths", "Hard", 52, "prismarine_bricks", "dark_prismarine", "emerald_block", "sea_lantern", 5, 54,
                               new int[][]{{2, 1}, {1, 1}, {1, 1}, {1, 1}}, 6),
                       build("inferno_abyss", "Inferno Abyss", "Expert", 53, "nether_bricks", "red_nether_bricks", "magma_block", "ochre_froglight", 5, 60,
                               new int[][]{{1, 1}, {1, 1}, {1, 1}, {1, 1}, {1, 1}}, 8));
    }

    /**
     * Each level is a shaft crossed by full floors ("plates"), each with several holes. Only one column of holes
     * runs through every plate down to the water - the rest are decoys that end on a plate or on the covered
     * pool. Falling fast leaves almost no air control (about 1 block sideways per 14 down), so the column has
     * to be found and lined up before the drop. {@code cols[l]} is the column's width x depth on level l.
     */
    static BuiltMap build(String id, String name, String diff, long seed, String wall, String plate, String rim, String light, int H, int depth, int[][] cols, int plates) {
        MapKit k = new MapKit(seed);
        MapDef d = new MapDef("dropper", id);
        int levels = cols.length;
        d.display = name; d.description = diff + " - " + levels + " shafts, " + depth + " blocks deep.";
        d.minPlayers = 1; d.maxPlayers = 8;
        d.builderVersion = 2;
        d.set("difficulty", diff);
        for (int l = 0; l < levels; l++) {
            int cx = l * 20;
            for (int y = -2; y <= depth; y++) for (int x = -H - 1; x <= H + 1; x++) for (int z = -H - 1; z <= H + 1; z++)
                if (Math.abs(x) == H + 1 || Math.abs(z) == H + 1) k.set(cx + x, y, z, y % 8 == 4 && (x + z) % 3 == 0 ? light : wall);
            int cw = cols[l][0], cd = cols[l][1];
            if (k.rnd.nextBoolean()) { int t = cw; cw = cd; cd = t; }
            int colX = k.rnd.nextInt(2 * H + 2 - cw) - H, colZ = k.rnd.nextInt(2 * H + 2 - cd) - H;
            int n = plates + l;
            for (int i = 0; i < n; i++) {
                int y = depth - 7 - (int) Math.round(i * (depth - 12.0) / Math.max(1, n - 1)) + (i > 0 && i < n - 1 ? k.rnd.nextInt(3) - 1 : 0);
                boolean[][] hole = new boolean[2 * H + 1][2 * H + 1];
                for (int x = colX; x < colX + cw; x++) for (int z = colZ; z < colZ + cd; z++) hole[x + H][z + H] = true;
                // decoys: blobs the same size as the column (or bigger) elsewhere on the plate
                int decoys = 2 + k.rnd.nextInt(2) + l / 2;
                for (int dcy = 0; dcy < decoys; dcy++) {
                    int w = cw + k.rnd.nextInt(2), dd = cd + k.rnd.nextInt(2);
                    int hx = k.rnd.nextInt(2 * H + 2 - w) - H, hz = k.rnd.nextInt(2 * H + 2 - dd) - H;
                    for (int x = hx; x < hx + w; x++) for (int z = hz; z < hz + dd; z++) hole[x + H][z + H] = true;
                }
                // widen the true hole on one side now and then so it isn't always the smallest
                if (k.rnd.nextInt(3) == 0) { int ex = Math.min(H, colX + cw); for (int z = colZ; z < colZ + cd; z++) hole[ex + H][z + H] = true; }
                for (int x = -H; x <= H; x++) for (int z = -H; z <= H; z++) {
                    if (hole[x + H][z + H]) continue;
                    boolean edge = (x > -H && hole[x - 1 + H][z + H]) || (x < H && hole[x + 1 + H][z + H]) || (z > -H && hole[x + H][z - 1 + H]) || (z < H && hole[x + H][z + 1 + H]);
                    k.set(cx + x, y, z, edge ? rim : plate);
                }
            }
            // pool: covered except under the column
            for (int x = -H; x <= H; x++) for (int z = -H; z <= H; z++) {
                k.set(cx + x, -2, z, wall);
                boolean col = x >= colX && x < colX + cw && z >= colZ && z < colZ + cd;
                k.set(cx + x, -1, z, col ? "water" : plate);
            }
            // start ledge on top
            for (int x = -1; x <= 1; x++) for (int z = H + 2; z <= H + 4; z++) k.set(cx + x, depth, z, "polished_andesite");
            d.add("level_starts", Pt.of(cx + 0.5, depth + 1, H + 3.5, 180));
            d.region("level." + l + ".goal", Box.of(cx + colX, -1, colZ, cx + colX + cw - 1, 0, colZ + cd - 1));
        }
        d.bounds = Box.of(-H - 8, -6, -H - 8, (levels - 1) * 20 + H + 8, depth + 10, H + 10);
        d.voidY = -5;
        d.spectator = new Pt(0.5, depth + 6, H + 6.5, 180, 50);
        for (int i = 0; i < 8; i++) d.add("spawns", d.list("level_starts").getFirst().add((i % 3) - 1, 0, (i / 3) - 1));
        return new BuiltMap(d, k.c);
    }

    static final class DropMatch extends Match {
        private final Map<UUID, Integer> level = new HashMap<>();
        private final Map<UUID, Long> finished = new LinkedHashMap<>();
        private final Map<UUID, Long> landedAt = new HashMap<>();
        private long startMs;
        private int raceEndAt = -1, fails;

        DropMatch(MatchContext c) { super(c); }

        @Override protected String objective() { return "Land in the water on every level!"; }
        @Override protected boolean pvp() { return false; }
        @Override protected boolean onDamage(Player v, Entity a, EntityDamageEvent e) { return false; }
        @Override protected boolean wantsFineMoves() { return true; }

        @Override protected void setup(List<Player> ps) { for (Player p : ps) { level.put(p.getUniqueId(), 0); p.teleport(points("level_starts").getFirst()); } }
        @Override protected void onStart() { startMs = System.currentTimeMillis(); }

        private void restartLevel(Player p, String why) {
            p.setFallDistance(0);
            p.setVelocity(new org.bukkit.util.Vector());
            p.teleport(points("level_starts").get(level.get(p.getUniqueId())));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_HURT, 0.6f, 1.2f);
            p.sendActionBar(Ui.mm("<red>" + why + " <gray>- try again!"));
            arcade.stats().add(p.getUniqueId(), game.id, "fails", 1);
        }

        @Override protected void onVoid(Player p) { restartLevel(p, "Out of the shaft"); }

        @Override protected void onMove(Player p, Location from, Location to) {
            UUID u = p.getUniqueId();
            if (finished.containsKey(u) || !level.containsKey(u)) return;
            int lv = level.get(u);
            Location start = points("level_starts").get(lv);
            if (to.getY() > start.getY() - 3) return;
            Block in = to.getBlock();
            Box goal = region("level." + lv + ".goal");
            if (in.getType() == Material.WATER && goal.contains(to.getX(), to.getY(), to.getZ())) {
                int next = lv + 1;
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
                if (next >= map.list("level_starts").size()) { complete(p); return; }
                level.put(u, next);
                title(p, game.color + "<bold>LEVEL " + (next + 1), "<gray>" + Parkour2.time(System.currentTimeMillis() - startMs));
                p.teleport(points("level_starts").get(next));
                return;
            }
            // landed on something solid below the start = fail
            if (((org.bukkit.entity.Entity) p).isOnGround() && in.getType() != Material.WATER) restartLevel(p, "Splat");
        }

        private void complete(Player p) {
            long t = System.currentTimeMillis() - startMs;
            finished.put(p.getUniqueId(), t);
            boolean pb = arcade.stats().best(p.getUniqueId(), game.id, "best_time", t);
            arcade.stats().best(p.getUniqueId(), game.id, "best_time_" + map.id, t);
            broadcast(prefix() + nameOf(p) + " <gray>cleared every level in <white>" + Parkour2.time(t) + (pb ? " <gold><bold>NEW PERSONAL BEST!" : ""));
            title(p, "<green><bold>COMPLETE!", "<white>" + Parkour2.time(t));
            p.teleport(at(map.spectator));
            firework(p.getLocation());
            if (finished.size() == 1 && variant.id().equals("race") && players.size() > 1) raceEndAt = elapsed + settings.i("race-grace-seconds", 20);
        }

        @Override protected void secondTick() {
            for (Player p : living()) if (!finished.containsKey(p.getUniqueId()))
                p.sendActionBar(Ui.mm(game.color + Parkour2.time(System.currentTimeMillis() - startMs) + " <gray>• level " + (level.getOrDefault(p.getUniqueId(), 0) + 1) + "/" + map.list("level_starts").size()));
        }

        @Override protected List<UUID> checkWinners() {
            if (finished.isEmpty()) return alive.isEmpty() ? List.of() : null;
            if (living().stream().allMatch(p -> finished.containsKey(p.getUniqueId())) || (raceEndAt >= 0 && elapsed >= raceEndAt)) return List.of(finished.keySet().iterator().next());
            return null;
        }

        @Override protected void onTimeUp() { finish(finished.isEmpty() ? List.of() : List.of(finished.keySet().iterator().next()), "Time's up!"); }

        @Override protected List<String> sidebar(Player p) {
            List<String> l = new ArrayList<>();
            l.add("<gray>Map: <white>" + map.display);
            l.add("<gray>Level: <white>" + (level.getOrDefault(p.getUniqueId(), 0) + 1) + "/" + map.list("level_starts").size());
            l.add("<gray>Time: <white>" + Parkour2.time(System.currentTimeMillis() - startMs));
            for (var e : finished.entrySet()) l.add("<green>✔ <white>" + org.bukkit.Bukkit.getOfflinePlayer(e.getKey()).getName() + " <gray>" + Parkour2.time(e.getValue()));
            return l;
        }
    }

    /** Small shared time formatter. */
    static final class Parkour2 { static String time(long ms) { return String.format("%d:%02d.%02d", ms / 60000, (ms / 1000) % 60, (ms / 10) % 100); } }
}
