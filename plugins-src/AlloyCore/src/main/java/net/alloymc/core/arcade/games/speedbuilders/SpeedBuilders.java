package net.alloymc.core.arcade.games.speedbuilders;

import net.alloymc.core.Ui;
import net.alloymc.core.arcade.core.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/**
 * Speed Builders: study the structure on your plot, it vanishes, rebuild it from memory before time runs out.
 * The least accurate builder is eliminated each round; structures get bigger and more colourful.
 */
public final class SpeedBuilders extends ArcadeGame {
    public SpeedBuilders() {
        super("speedbuilders", "Speed Builders", "<#7fffd4>", Material.LIME_CONCRETE, Category.CASUAL,
                "Memorise the structure, then rebuild it from memory. The least accurate builder is out each round.");
        variants.add(Variant.ffa("solo", "Solo", 2, 12));
    }

    @Override public MapRequirements requirements() { return new MapRequirements().list("plots", MapRequirements.PER_PLAYER); }

    @Override public Map<String, Object> defaultSettings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time-limit", 0);
        m.put("view-seconds", 10);
        m.put("build-seconds", 35);
        m.put("build-seconds-min", 18);
        m.put("area-size", 5);
        m.put("area-height", 5);
        m.put("max-rounds", 12);
        m.put("palette", List.of("WHITE_CONCRETE", "RED_CONCRETE", "BLUE_CONCRETE", "YELLOW_CONCRETE", "LIME_CONCRETE", "BLACK_CONCRETE",
                "OAK_PLANKS", "STONE_BRICKS", "GLASS", "ORANGE_WOOL", "PURPLE_WOOL", "QUARTZ_BLOCK"));
        return m;
    }

    @Override public Match newMatch(MatchContext c) { return new SpeedMatch(c); }

    @Override public List<BuiltMap> builtInMaps() { return List.of(ring(), plaza()); }

    private static void plotPad(MapKit k, int cx, int cz, String top, String rim) {
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) k.set(cx + x, 0, cz + z, Math.abs(x) == 3 || Math.abs(z) == 3 ? rim : top);
        // standing platform in front of the build area
        for (int x = -3; x <= 3; x++) for (int z = 4; z <= 7; z++) k.set(cx + x, 0, cz + z, rim);
    }

    private static BuiltMap ring() {
        MapKit k = new MapKit(141);
        MapDef d = new MapDef("speedbuilders", "island_ring");
        d.display = "Island Ring"; d.description = "Eight island plots around the judge's tower.";
        d.minPlayers = 2; d.maxPlayers = 8;
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI * 2 / 8;
            int cx = (int) Math.round(Math.cos(a) * 26), cz = (int) Math.round(Math.sin(a) * 26);
            k.island(cx, 0, cz + 2, 7, "grass_block", "dirt", "stone");
            plotPad(k, cx, cz, "white_concrete", "light_gray_concrete");
            d.add("plots", Pt.of(cx + 0.5, 1, cz + 0.5));
        }
        k.island(0, 0, 0, 6, "quartz_block", "calcite", "calcite");
        for (int y = 1; y <= 9; y++) k.set(0, y, 0, y == 9 ? "sea_lantern" : "quartz_pillar");
        d.bounds = Box.of(-40, -15, -40, 40, 30, 40);
        d.voidY = -12;
        d.spectator = new Pt(0.5, 14, 0.5, 0, 60);
        return new BuiltMap(d, k.c);
    }

    private static BuiltMap plaza() {
        MapKit k = new MapKit(142);
        MapDef d = new MapDef("speedbuilders", "grid_plaza");
        d.display = "Grid Plaza"; d.description = "Twelve plots side by side on a stone plaza.";
        d.minPlayers = 2; d.maxPlayers = 12;
        for (int x = -38; x <= 38; x++) for (int z = -16; z <= 22; z++) { k.set(x, 0, z, ((x + z) & 1) == 0 ? "polished_andesite" : "andesite"); k.set(x, -1, z, "stone"); }
        for (int i = 0; i < 12; i++) {
            int cx = -33 + (i % 6) * 13, cz = i < 6 ? -10 : 8;
            plotPad(k, cx, cz, "white_concrete", "gray_concrete");
            d.add("plots", Pt.of(cx + 0.5, 1, cz + 0.5));
        }
        d.bounds = Box.of(-42, -6, -20, 42, 30, 26);
        d.voidY = -5;
        d.spectator = new Pt(0.5, 18, 26.5, 180, 40);
        return new BuiltMap(d, k.c);
    }

    static final class SpeedMatch extends Match {
        private enum Phase { VIEW, BUILD, JUDGE }
        private Phase phase = Phase.VIEW;
        private final Map<UUID, Integer> plotOf = new HashMap<>();
        private final Map<UUID, Double> total = new HashMap<>();
        private Material[][][] structure;   // [x][y][z], null = air
        private int round, phaseEnd;
        private List<Material> palette;

        SpeedMatch(MatchContext c) { super(c); }

        @Override protected String objective() { return "Memorise it, then rebuild it!"; }
        @Override protected boolean pvp() { return false; }
        @Override protected boolean onDamage(Player v, Entity a, EntityDamageEvent e) { return false; }
        @Override protected boolean freezeWhileStarting() { return false; }

        private int size() { return settings.i("area-size", 5); }
        private int height() { return settings.i("area-height", 5); }
        /** Build-area corner (lowest x/z, first layer) for a plot. */
        private Location corner(UUID u) { Location c = points("plots").get(plotOf.get(u)); return new Location(world, c.getBlockX() - size() / 2, c.getBlockY(), c.getBlockZ() - size() / 2); }

        @Override protected void setup(List<Player> ps) {
            palette = new ArrayList<>();
            for (String s : settings.list("palette", List.of("WHITE_CONCRETE"))) { Material m = Material.matchMaterial(s); if (m != null && m.isBlock()) palette.add(m); }
            for (int i = 0; i < ps.size(); i++) { plotOf.put(ps.get(i).getUniqueId(), i); ps.get(i).teleport(stand(ps.get(i).getUniqueId())); }
        }

        private Location stand(UUID u) { Location c = points("plots").get(plotOf.get(u)); Location l = c.clone().add(0, 0, 5.5); l.setDirection(c.toVector().subtract(l.toVector())); return l; }

        @Override protected void onStart() { nextRound(); }

        /** Procedural structure: symmetric heightmap blobs; more blocks, layers and colours each round. */
        private void generate() {
            int s = size(), h = height();
            structure = new Material[s][h][s];
            int colours = Math.min(palette.size(), 2 + round / 2);
            List<Material> use = new ArrayList<>(palette);
            Collections.shuffle(use, rnd);
            use = use.subList(0, colours);
            int maxH = Math.min(h, 1 + (round + 1) / 2);
            double fill = Math.min(0.85, 0.35 + round * 0.06);
            for (int x = 0; x <= s / 2; x++) for (int z = 0; z < s; z++) {
                if (rnd.nextDouble() > fill) continue;
                int colH = 1 + rnd.nextInt(maxH);
                for (int y = 0; y < colH; y++) {
                    Material m = use.get(rnd.nextInt(use.size()));
                    structure[x][y][z] = m;
                    structure[s - 1 - x][y][z] = m;   // mirrored
                }
            }
        }

        private void place(UUID u, boolean show) {
            Location c = corner(u);
            for (int x = 0; x < size(); x++) for (int y = 0; y < height(); y++) for (int z = 0; z < size(); z++) {
                Material m = show ? structure[x][y][z] : null;
                world.getBlockAt(c.getBlockX() + x, c.getBlockY() + y, c.getBlockZ() + z).setType(m == null ? Material.AIR : m, false);
            }
        }

        private void nextRound() {
            round++;
            if (round > settings.i("max-rounds", 12)) { endByScore(); return; }
            generate();
            phase = Phase.VIEW;
            phaseEnd = elapsed + settings.i("view-seconds", 10);
            for (Player p : living()) { place(p.getUniqueId(), true); p.setGameMode(GameMode.ADVENTURE); p.getInventory().clear(); p.teleport(stand(p.getUniqueId())); }
            title(game.color + "<bold>ROUND " + round, "<gray>Memorise the structure!");
            sound(Sound.BLOCK_NOTE_BLOCK_PLING, 1.2f);
        }

        private void startBuild() {
            phase = Phase.BUILD;
            int secs = Math.max(settings.i("build-seconds-min", 18), settings.i("build-seconds", 35) - (round - 1) * 2);
            phaseEnd = elapsed + secs;
            Set<Material> needed = new LinkedHashSet<>();
            for (Material[][] a : structure) for (Material[] b : a) for (Material m : b) if (m != null) needed.add(m);
            for (Player p : living()) {
                place(p.getUniqueId(), false);
                p.setGameMode(GameMode.CREATIVE);
                int slot = 0;
                for (Material m : needed) p.getInventory().setItem(slot++, new ItemStack(m, 64));
            }
            title("", game.color + "<bold>BUILD! <white>" + secs + "s");
        }

        private double accuracy(UUID u) {
            Location c = corner(u);
            int cells = 0, right = 0;
            for (int x = 0; x < size(); x++) for (int y = 0; y < height(); y++) for (int z = 0; z < size(); z++) {
                Material want = structure[x][y][z];
                Material got = world.getBlockAt(c.getBlockX() + x, c.getBlockY() + y, c.getBlockZ() + z).getType();
                boolean gotAir = got.isAir();
                if (want == null && gotAir) continue;
                cells++;
                if (want != null && got == want) right++;
            }
            return cells == 0 ? 1 : right / (double) cells;
        }

        private void judge() {
            phase = Phase.JUDGE;
            phaseEnd = elapsed + 5;
            Map<UUID, Double> acc = new HashMap<>();
            for (Player p : living()) { p.setGameMode(GameMode.ADVENTURE); p.getInventory().clear(); double a = accuracy(p.getUniqueId()); acc.put(p.getUniqueId(), a); total.merge(p.getUniqueId(), a, Double::sum); }
            List<Map.Entry<UUID, Double>> sorted = new ArrayList<>(acc.entrySet());
            sorted.sort(Map.Entry.comparingByValue());
            for (var e : sorted) { Player p = Bukkit.getPlayer(e.getKey()); if (p != null) broadcast(prefix() + "<white>" + p.getName() + " <gray>- " + pct(e.getValue())); }
            if (sorted.isEmpty()) return;
            double worst = sorted.getFirst().getValue();
            if (worst >= 0.999 && sorted.getLast().getValue() >= 0.999) { broadcast(prefix() + "<green>Everyone was perfect - nobody is eliminated!"); return; }
            List<UUID> lowest = sorted.stream().filter(e -> e.getValue() <= worst + 1e-9).map(Map.Entry::getKey).toList();
            UUID out = lowest.get(rnd.nextInt(lowest.size()));
            Player p = Bukkit.getPlayer(out);
            if (p != null && alive.size() > 1) { place(out, false); eliminate(p, nameOf(p) + " <gray>was the least accurate (" + pct(acc.get(out)) + ")"); arcade.stats().max(out, game.id, "best_round", round); }
        }

        private static String pct(double d) { return (d >= 0.999 ? "<green>" : d >= 0.7 ? "<yellow>" : "<red>") + Math.round(d * 100) + "%"; }

        private void endByScore() {
            UUID best = total.entrySet().stream().filter(e -> alive.contains(e.getKey())).max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            finish(best == null ? new ArrayList<>(alive) : List.of(best), "Final round done - best total accuracy wins!");
        }

        @Override protected void secondTick() {
            int left = phaseEnd - elapsed;
            actionBar(game.color + phase.name().charAt(0) + phase.name().substring(1).toLowerCase() + " <gray>• <white>" + Math.max(0, left) + "s <gray>• round " + round);
            if (left > 0) return;
            switch (phase) {
                case VIEW -> startBuild();
                case BUILD -> judge();
                case JUDGE -> { if (alive.size() > 1 || players.size() == 1) nextRound(); }
            }
        }

        private boolean inArea(Player p, Block b) {
            if (phase != Phase.BUILD || !plotOf.containsKey(p.getUniqueId())) return false;
            Location c = corner(p.getUniqueId());
            return b.getX() >= c.getBlockX() && b.getX() < c.getBlockX() + size() && b.getZ() >= c.getBlockZ() && b.getZ() < c.getBlockZ() + size()
                && b.getY() >= c.getBlockY() && b.getY() < c.getBlockY() + height();
        }

        @Override protected boolean allowPlace(Player p, Block b) { return inArea(p, b); }
        @Override protected boolean allowBreak(Player p, Block b) { return inArea(p, b); }
        @Override protected void onVoid(Player p) { p.teleport(stand(p.getUniqueId())); }

        @Override protected List<UUID> checkWinners() {
            if (alive.size() == 1 && players.size() > 1) return new ArrayList<>(alive);
            return alive.isEmpty() ? List.of() : null;
        }

        @Override protected List<String> sidebar(Player p) {
            List<String> l = new ArrayList<>();
            l.add("<gray>Round: <white>" + round);
            l.add("<gray>Builders left: <white>" + alive.size());
            l.add("<gray>Your total: <white>" + Math.round(total.getOrDefault(p.getUniqueId(), 0.0) * 100) + "%");
            return l;
        }
    }
}
