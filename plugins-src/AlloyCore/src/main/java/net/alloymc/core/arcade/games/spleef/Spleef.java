package net.alloymc.core.arcade.games.spleef;

import net.alloymc.core.Ui;
import net.alloymc.core.arcade.core.*;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/** Spleef: dig the floor out from under everyone else. Configurable tool, floor blocks and rounds. */
public final class Spleef extends ArcadeGame {
    public Spleef() {
        super("spleef", "Spleef", "<#7fd6ff>", Material.DIAMOND_SHOVEL, Category.PARTY,
                "Break the snow under your rivals. Fall through every floor and you're out - last one standing wins.");
        variants.add(Variant.ffa("solo", "Solo", 2, 16));
    }

    @Override public MapRequirements requirements() { return new MapRequirements().list("spawns", MapRequirements.PER_PLAYER); }

    @Override public Map<String, Object> defaultSettings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tool", "DIAMOND_SHOVEL");
        m.put("floor-blocks", List.of("SNOW_BLOCK"));
        m.put("rounds", 1);
        m.put("grace-seconds", 3);
        m.put("snowballs", true);
        m.put("regen-seconds", 0);
        m.put("time-limit", 300);
        return m;
    }

    @Override public List<String> howToPlay() {
        return List.of("Left-click the floor to dig it away. Broken snow gives snowballs that break blocks too.",
                "Fall below the last floor and you're out. With several rounds, most round wins takes it.");
    }

    @Override public Match newMatch(MatchContext c) { return new SpleefMatch(c); }

    @Override public List<BuiltMap> builtInMaps() { return List.of(frostbowl(), glacierGrid(), snowflake()); }

    // ------------------------------------------------------------------ maps

    private static void ringSpawns(MapDef d, double r, int y, int n) {
        for (int i = 0; i < n; i++) {
            double a = i * Math.PI * 2 / n;
            d.add("spawns", new Pt(Math.cos(a) * r + 0.5, y, Math.sin(a) * r + 0.5, 0, 0).facing(0.5, 0.5));
        }
    }

    private static BuiltMap frostbowl() {
        MapKit k = new MapKit(11);
        int R = 16, L = 8;
        for (int x = -R - 3; x <= R + 3; x++) for (int z = -R - 3; z <= R + 3; z++) {
            double r = Math.hypot(x, z);
            if (r <= R + 0.5 && r > 0) { k.set(x, 0, z, "snow_block"); k.set(x, -L, z, "snow_block"); }
            if (r <= 0.5) { k.set(x, 0, z, "snow_block"); k.set(x, -L, z, "snow_block"); }
            if (r > R + 0.5 && r <= R + 2.5)
                for (int y = -L - 2; y <= 4; y++) k.set(x, y, z, y >= 2 ? "light_blue_stained_glass" : y == 1 && ((x + z) & 3) == 0 ? "sea_lantern" : r > R + 1.5 ? "packed_ice" : "blue_ice");
            if (r > R + 0.5 && r <= R + 3.2) k.set(x, 5, z, "waxed_cut_copper");
        }
        MapDef d = new MapDef("spleef", "frostbowl");
        d.display = "Frostbowl"; d.description = "A round ice bowl with two snow floors.";
        d.minPlayers = 2; d.maxPlayers = 16; d.bounds = Box.of(-22, -L - 14, -22, 22, 20, 22); d.voidY = -L - 4;
        d.spectator = new Pt(0.5, 14, 0.5, 0, 60);
        ringSpawns(d, R - 4, 1, 16);
        return new BuiltMap(d, k.c);
    }

    private static BuiltMap glacierGrid() {
        MapKit k = new MapKit(12);
        int H = 15, gap = 6;
        for (int l = 0; l < 3; l++) {
            int y = -l * gap;
            for (int x = -H; x <= H; x++) for (int z = -H; z <= H; z++) {
                boolean hole = l == 0 && (Math.floorMod(x, 8) == 0 && Math.floorMod(z, 8) == 0);
                if (!hole) k.set(x, y, z, "snow_block");
            }
        }
        for (int x = -H - 2; x <= H + 2; x++) for (int z = -H - 2; z <= H + 2; z++) {
            if (Math.abs(x) <= H && Math.abs(z) <= H) continue;
            for (int y = -3 * gap - 2; y <= 3; y++) k.set(x, y, z, y >= 1 ? "white_stained_glass" : (x + z + y) % 5 == 0 ? "sea_lantern" : "packed_ice");
        }
        // corner ice spires
        for (int[] c : new int[][]{{-H - 1, -H - 1}, {H + 1, -H - 1}, {-H - 1, H + 1}, {H + 1, H + 1}})
            for (int y = 4; y <= 10; y++) k.set(c[0], y, c[1], y == 10 ? "sea_lantern" : "blue_ice");
        MapDef d = new MapDef("spleef", "glacier_grid");
        d.display = "Glacier Grid"; d.description = "Three square floors; the top one already has holes in it.";
        d.minPlayers = 2; d.maxPlayers = 16; d.bounds = Box.of(-20, -3 * gap - 8, -20, 20, 20, 20); d.voidY = -2 * gap - 4;
        d.spectator = new Pt(0.5, 14, 0.5, 0, 60);
        for (int i = 0; i < 16; i++) { int gx = i % 4, gz = i / 4; d.add("spawns", new Pt(-10.5 + gx * 7, 1, -10.5 + gz * 7, 0, 0).facing(0.5, 0.5)); }
        d.set("rounds", 2);
        return new BuiltMap(d, k.c);
    }

    private static BuiltMap snowflake() {
        MapKit k = new MapKit(13);
        int R = 20, L = 7;
        for (int x = -R; x <= R; x++) for (int z = -R; z <= R; z++) {
            double r = Math.hypot(x, z);
            double ang = Math.atan2(z, x);
            // six arms + a hub
            double arm = Math.abs(Math.sin(ang * 3));
            boolean in = r <= 7 || (r <= R && arm < 0.22) || (r > 11 && r <= 13 && arm < 0.5);
            if (!in) continue;
            k.set(x, 0, z, "snow_block");
            k.set(x, -L, z, r <= 9 ? "snow_block" : "air");
            if (r > 9 && r <= R && arm < 0.22) k.set(x, -L, z, "snow_block");
        }
        // glowing frame under the arms
        for (int x = -R; x <= R; x++) for (int z = -R; z <= R; z++) {
            double r = Math.hypot(x, z);
            if (r > R - 0.5 && r <= R + 0.5 && Math.abs(Math.sin(Math.atan2(z, x) * 3)) < 0.25) for (int y = -L - 1; y <= 2; y++) k.set(x, y, z, y == 2 ? "sea_lantern" : "packed_ice");
        }
        MapDef d = new MapDef("spleef", "snowflake");
        d.display = "Snowflake"; d.description = "A six-armed snowflake. Narrow arms, nowhere to hide.";
        d.minPlayers = 2; d.maxPlayers = 12; d.bounds = Box.of(-26, -L - 14, -26, 26, 20, 26); d.voidY = -L - 4;
        d.spectator = new Pt(0.5, 16, 0.5, 0, 70);
        for (int i = 0; i < 12; i++) {
            double a = Math.PI / 3 * (i % 6), r = i < 6 ? 17 : 5;
            d.add("spawns", new Pt(Math.round(Math.cos(a) * r) + 0.5, 1, Math.round(Math.sin(a) * r) + 0.5, 0, 0).facing(0.5, 0.5));
        }
        return new BuiltMap(d, k.c);
    }

    // ------------------------------------------------------------------ match

    static final class SpleefMatch extends Match {
        private final Set<Material> floor = new HashSet<>();
        private final Map<UUID, Integer> roundWins = new HashMap<>();
        private final Map<Long, Long> broken = new HashMap<>();   // for regeneration
        private int round = 1, roundStart;
        private boolean betweenRounds;

        SpleefMatch(MatchContext c) {
            super(c);
            for (String s : settings.list("floor-blocks", List.of("SNOW_BLOCK"))) { Material m = Material.matchMaterial(s); if (m != null) floor.add(m); }
        }

        @Override protected String objective() { return "Dig the floor out from under your rivals!"; }
        int rounds() { return Math.max(1, settings.i("rounds", 1)); }

        @Override protected void setup(List<Player> ps) { place(ps); }

        private void place(List<Player> ps) {
            List<Location> sp = points("spawns");
            Collections.shuffle(sp, rnd);
            for (int i = 0; i < ps.size(); i++) {
                Player p = ps.get(i);
                p.teleport(sp.get(i % sp.size()));
                p.setGameMode(GameMode.SURVIVAL);
                p.getInventory().clear();
                Material t = Material.matchMaterial(settings.s("tool", "DIAMOND_SHOVEL"));
                ItemStack tool = Ui.item(t == null ? Material.DIAMOND_SHOVEL : t, game.color + "<bold>Spleef Tool");
                tool.editMeta(m -> { m.setUnbreakable(true); m.addEnchant(Enchantment.EFFICIENCY, 5, true); });
                p.getInventory().setItem(0, tool);
            }
        }

        @Override protected void onStart() {
            roundStart = elapsed;
            broadcast(prefix() + "Round <white>" + round + "/" + rounds() + "<gray> - digging unlocks in <white>" + settings.i("grace-seconds", 3) + "s<gray>.");
        }

        private boolean canDig() { return !betweenRounds && elapsed - roundStart >= settings.i("grace-seconds", 3); }

        @Override protected void secondTick() {
            if (elapsed - roundStart == settings.i("grace-seconds", 3) && !betweenRounds) { title("", game.color + "<bold>DIG!"); sound(Sound.BLOCK_SNOW_BREAK, 0.8f); }
            int regen = settings.i("regen-seconds", 0);
            if (regen > 0) {
                long now = System.currentTimeMillis();
                broken.entrySet().removeIf(e -> {
                    if (now - e.getValue() < regen * 1000L) return false;
                    long k = e.getKey();
                    Block b = world.getBlockAt((int) (k >> 38), (int) (k << 52 >> 52), (int) (k << 26 >> 38));
                    boolean occupied = living().stream().anyMatch(p -> p.getLocation().getBlock().equals(b) || p.getLocation().getBlock().getRelative(0, 1, 0).equals(b));
                    if (!occupied && b.getType().isAir()) b.setType(floor.iterator().next(), false);
                    return !occupied;
                });
            }
            actionBar(game.color + "Spleef <gray>• round " + round + "/" + rounds() + " • <white>" + alive.size() + " alive");
        }

        @Override protected boolean allowBreak(Player p, Block b) { return canDig() && floor.contains(b.getType()); }

        @Override protected void onBreak(Player p, BlockBreakEvent e) {
            e.setDropItems(false);
            broken.put(key(e.getBlock()), System.currentTimeMillis());
            if (settings.b("snowballs", true) && !p.getInventory().containsAtLeast(new ItemStack(Material.SNOWBALL), 16)) p.getInventory().addItem(new ItemStack(Material.SNOWBALL));
        }

        @Override protected void onProjectileHit(Player shooter, ProjectileHitEvent e) {
            Block b = e.getHitBlock();
            if (!(e.getEntity() instanceof Snowball) || b == null || !canDig() || !floor.contains(b.getType()) || !inBounds(b.getLocation())) return;
            b.setType(Material.AIR);
            broken.put(key(b), System.currentTimeMillis());
            world.playSound(b.getLocation(), Sound.BLOCK_SNOW_BREAK, 1f, 1f);
        }

        @Override protected boolean pvp() { return false; }
        @Override protected boolean onDamage(Player v, org.bukkit.entity.Entity a, org.bukkit.event.entity.EntityDamageEvent e) { return false; }

        @Override protected void onVoid(Player p) { eliminate(p, nameOf(p) + " <gray>fell through the floor!"); }

        @Override protected List<UUID> checkWinners() {
            if (betweenRounds || alive.size() > 1 && players.size() > 1) return null;
            if (players.size() <= 1 && !alive.isEmpty()) return null;   // solo test game keeps running until they fall
            UUID roundWinner = alive.isEmpty() ? null : alive.iterator().next();
            if (roundWinner != null) roundWins.merge(roundWinner, 1, Integer::sum);
            if (round >= rounds()) {
                if (rounds() == 1) return roundWinner == null ? List.of() : List.of(roundWinner);
                int best = roundWins.values().stream().max(Integer::compare).orElse(0);
                List<UUID> top = roundWins.entrySet().stream().filter(e -> e.getValue() == best).map(Map.Entry::getKey).toList();
                return top.size() == 1 ? top : roundWinner != null && top.contains(roundWinner) ? List.of(roundWinner) : top;
            }
            // next round: restore the floors, everyone back in
            betweenRounds = true;
            broadcast(prefix() + (roundWinner == null ? "Nobody" : "<white>" + org.bukkit.Bukkit.getOfflinePlayer(roundWinner).getName()) + " <gray>wins round " + round + "!");
            round++;
            arcade.worlds().restore(map, slot, bd -> floor.contains(bd.getMaterial()), () -> {
                broken.clear();
                alive.clear();
                List<Player> back = everyone();
                for (Player p : back) alive.add(p.getUniqueId());
                place(back);
                betweenRounds = false;
                roundStart = elapsed;
                title(game.color + "<bold>ROUND " + round, "<gray>Get ready...");
            });
            return null;
        }

        @Override protected List<String> sidebar(Player p) {
            List<String> l = super.sidebar(p);
            if (rounds() > 1) l.add("<gray>Round: <white>" + round + "/" + rounds() + " <gray>• your wins: <white>" + roundWins.getOrDefault(p.getUniqueId(), 0));
            return l;
        }
    }
}
