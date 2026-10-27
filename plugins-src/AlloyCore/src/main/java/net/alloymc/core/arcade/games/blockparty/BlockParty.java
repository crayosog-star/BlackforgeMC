package net.alloymc.core.arcade.games.blockparty;

import net.alloymc.core.Ui;
import net.alloymc.core.arcade.core.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/** Block Party: get onto the shown colour before the countdown ends - every other colour vanishes. Rounds speed up. */
public final class BlockParty extends ArcadeGame {
    public BlockParty() {
        super("blockparty", "Block Party", "<#ff8ad8>", Material.MAGENTA_CONCRETE, Category.PARTY,
                "A colour is shown - stand on it before time runs out. Every other colour disappears, and each round gets faster.");
        variants.add(Variant.ffa("solo", "Solo", 2, 20));
    }

    @Override public MapRequirements requirements() { return new MapRequirements().region("floor").list("spawns", 4); }

    @Override public Map<String, Object> defaultSettings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time-limit", 600);
        m.put("start-seconds", 5.0);
        m.put("min-seconds", 1.4);
        m.put("speedup-per-round", 0.3);
        m.put("palette", List.of("RED", "ORANGE", "YELLOW", "LIME", "LIGHT_BLUE", "BLUE", "PURPLE", "MAGENTA", "WHITE", "BLACK"));
        m.put("tile-size", 3);
        return m;
    }

    @Override public Match newMatch(MatchContext c) { return new PartyMatch(c); }

    @Override public List<BuiltMap> builtInMaps() { return List.of(disco(), neonRing(), gardenGrid()); }

    private static BuiltMap floorMap(String id, String name, String desc, int half, boolean round, String frame, String light, long seed, Map<String, Object> extra) {
        MapKit k = new MapKit(seed);
        for (int x = -half - 2; x <= half + 2; x++) for (int z = -half - 2; z <= half + 2; z++) {
            boolean in = round ? Math.hypot(x, z) <= half + 0.5 : Math.abs(x) <= half && Math.abs(z) <= half;
            boolean rim = round ? Math.hypot(x, z) <= half + 2.5 && !in : !in;
            if (in) k.set(x, 0, z, "white_concrete");
            else if (rim) for (int y = -1; y <= 3; y++) k.set(x, y, z, y == 3 ? light : frame);
        }
        // speakers in the corners
        for (int[] c : new int[][]{{-half - 4, -half - 4}, {half + 4, -half - 4}, {-half - 4, half + 4}, {half + 4, half + 4}})
            for (int y = -1; y <= 5; y++) k.set(c[0], y, c[1], y % 2 == 0 ? "note_block" : "jukebox");
        MapDef d = new MapDef("blockparty", id);
        d.display = name; d.description = desc; d.minPlayers = 2; d.maxPlayers = 20;
        d.bounds = Box.of(-half - 6, -24, -half - 6, half + 6, 20, half + 6);
        d.voidY = -8;
        d.region("floor", Box.of(-half, 0, -half, half, 0, half));
        d.spectator = new Pt(0.5, 14, 0.5, 0, 70);
        if (round) d.set("round-floor", true);
        extra.forEach(d::set);
        for (int i = 0; i < 20; i++) { double a = i * Math.PI * 2 / 20; d.add("spawns", Pt.of(Math.round(Math.cos(a) * (half - 3)) + 0.5, 1, Math.round(Math.sin(a) * (half - 3)) + 0.5).facing(0.5, 0.5)); }
        return new BuiltMap(d, k.c);
    }

    private static BuiltMap disco() { return floorMap("disco_floor", "Disco Floor", "A big square dance floor.", 16, false, "black_concrete", "sea_lantern", 61, Map.of()); }
    private static BuiltMap neonRing() { return floorMap("neon_ring", "Neon Ring", "A round floor with glowing edges and small tiles.", 15, true, "polished_blackstone", "shroomlight", 62, Map.of("tile-size", 2)); }
    private static BuiltMap gardenGrid() {
        return floorMap("garden_grid", "Garden Grid", "Big pastel tiles in a hedge garden.", 13, false, "moss_block", "pearlescent_froglight", 63,
                Map.of("tile-size", 4, "palette", List.of("PINK", "LIME", "YELLOW", "LIGHT_BLUE", "WHITE", "MAGENTA", "CYAN")));
    }

    static final class PartyMatch extends Match {
        private enum Phase { DANCE, COUNT, DROP }
        private Phase phase = Phase.DANCE;
        private int round, ticks, phaseEnd;
        private Material target;
        private List<Material> palette;

        PartyMatch(MatchContext c) { super(c); }

        @Override protected String objective() { return "Stand on the right colour!"; }
        @Override protected boolean pvp() { return false; }
        @Override protected boolean onDamage(Player v, Entity a, EntityDamageEvent e) { return false; }

        @Override protected void setup(List<Player> ps) {
            palette = new ArrayList<>();
            for (String s : settings.list("palette", List.of("RED", "BLUE"))) { Material m = Material.matchMaterial(s + "_CONCRETE"); if (m != null) palette.add(m); }
            List<Location> sp = points("spawns");
            for (int i = 0; i < ps.size(); i++) ps.get(i).teleport(sp.get(i % sp.size()));
            paint();
        }

        private void paint() {
            Box f = region("floor");
            int t = Math.max(1, settings.i("tile-size", 3));
            boolean round = settings.b("round-floor", false);
            Map<Long, Material> tiles = new HashMap<>();
            for (int x = f.x1(); x <= f.x2(); x++) for (int z = f.z1(); z <= f.z2(); z++) {
                if (round && Math.hypot(x - (ox + 0.5), z - (oz + 0.5)) > f.sizeX() / 2.0 + 0.5) continue;
                long key = ((long) Math.floorDiv(x - f.x1(), t) << 32) | (Math.floorDiv(z - f.z1(), t) & 0xffffffffL);
                world.getBlockAt(x, f.y1(), z).setType(tiles.computeIfAbsent(key, k -> palette.get(rnd.nextInt(palette.size()))), false);
            }
        }

        private double countSeconds() {
            return Math.max(settings.d("min-seconds", 1.4), settings.d("start-seconds", 5.0) - settings.d("speedup-per-round", 0.3) * (round - 1));
        }

        @Override protected void onStart() { nextRound(); }

        private void nextRound() {
            round++;
            phase = Phase.DANCE;
            phaseEnd = ticks + 40;
            paint();
            for (Player p : living()) p.getInventory().clear();
            sound(Sound.BLOCK_NOTE_BLOCK_BIT, 1.2f);
        }

        @Override protected void fastTick() {
            ticks++;
            if (ticks < phaseEnd) {
                if (phase == Phase.COUNT && (phaseEnd - ticks) % 10 == 0) for (Player p : living()) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.7f, 2f - (phaseEnd - ticks) / 100f);
                if (phase == Phase.COUNT) actionBar(name(target) + " <white>" + String.format("%.1f", (phaseEnd - ticks) / 20.0) + "s");
                return;
            }
            switch (phase) {
                case DANCE -> {
                    Box f = region("floor");
                    // pick a colour that is actually on the floor
                    Location probe = new Location(world, f.x1() + rnd.nextInt(f.sizeX()), f.y1(), f.z1() + rnd.nextInt(f.sizeZ()));
                    Material m = probe.getBlock().getType();
                    target = palette.contains(m) ? m : palette.get(rnd.nextInt(palette.size()));
                    ItemStack it = Ui.item(target, name(target));
                    for (Player p : living()) for (int s = 0; s < 9; s++) p.getInventory().setItem(s, it.clone());
                    title("", name(target));
                    phase = Phase.COUNT;
                    phaseEnd = ticks + (int) (countSeconds() * 20);
                }
                case COUNT -> {
                    Box f = region("floor");
                    for (int x = f.x1(); x <= f.x2(); x++) for (int z = f.z1(); z <= f.z2(); z++) {
                        var b = world.getBlockAt(x, f.y1(), z);
                        if (b.getType() != target) b.setType(Material.AIR, false);
                    }
                    sound(Sound.ENTITY_GENERIC_EXPLODE, 1.6f);
                    phase = Phase.DROP;
                    phaseEnd = ticks + 60;
                }
                case DROP -> {
                    if (alive.size() > 1 || players.size() == 1) { broadcast(prefix() + "Round <white>" + round + "<gray> survived by <white>" + alive.size()); nextRound(); }
                    else phaseEnd = ticks + 20;
                }
            }
        }

        private static String name(Material m) {
            String c = m.name().replace("_CONCRETE", "");
            String mm = switch (c) { case "RED" -> "<red>"; case "ORANGE" -> "<gold>"; case "YELLOW" -> "<yellow>"; case "LIME" -> "<green>"; case "GREEN" -> "<dark_green>";
                case "LIGHT_BLUE", "CYAN" -> "<aqua>"; case "BLUE" -> "<blue>"; case "PURPLE" -> "<dark_purple>"; case "MAGENTA", "PINK" -> "<light_purple>"; case "BLACK" -> "<dark_gray>"; default -> "<white>"; };
            return mm + "<bold>" + c.replace('_', ' ');
        }

        @Override protected void onVoid(Player p) {
            eliminate(p, nameOf(p) + " <gray>fell in round " + round + "!");
            arcade.stats().max(p.getUniqueId(), game.id, "best_round", round);
        }

        @Override protected void onTimeUp() { finish(new ArrayList<>(alive), "Time's up - everyone left standing wins!"); }

        @Override protected List<String> sidebar(Player p) {
            List<String> l = super.sidebar(p);
            l.add("<gray>Round: <white>" + round);
            l.add("<gray>Speed: <white>" + String.format("%.1fs", countSeconds()));
            return l;
        }
    }
}
