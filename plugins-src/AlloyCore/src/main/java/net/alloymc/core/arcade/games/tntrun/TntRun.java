package net.alloymc.core.arcade.games.tntrun;

import net.alloymc.core.arcade.core.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

/** TNT Run: every block you step on falls away a moment later. Several layers; last one standing wins. */
public final class TntRun extends ArcadeGame {
    public TntRun() {
        super("tntrun", "TNT Run", "<#ff5a3c>", Material.TNT, Category.PARTY,
                "The floor vanishes under your feet. Keep running through every layer - last survivor wins.");
        variants.add(Variant.ffa("solo", "Solo", 2, 16));
    }

    @Override public MapRequirements requirements() { return new MapRequirements().list("spawns", 4); }

    @Override public Map<String, Object> defaultSettings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("blocks", List.of("SAND", "RED_SAND", "GRAVEL", "WHITE_CONCRETE_POWDER", "LIGHT_GRAY_CONCRETE_POWDER"));
        m.put("delay-ticks", 7);
        m.put("grace-seconds", 3);
        m.put("double-jumps", 2);
        m.put("time-limit", 420);
        return m;
    }

    @Override public List<String> howToPlay() {
        return List.of("Every floor block you touch disappears a moment later, along with the TNT holding it up.",
                "Drop through the layers carefully - fall past the last one and you're out. Sneak to double jump.");
    }

    @Override public Match newMatch(MatchContext c) { return new RunMatch(c); }

    @Override public List<BuiltMap> builtInMaps() { return List.of(tripleTier(), checkerfall(), molten()); }

    private static void scatter(MapDef d, int half, int y, int n, long seed) {
        Random r = new Random(seed);
        for (int i = 0; i < n; i++) d.add("spawns", new Pt(r.nextInt(half * 2 - 3) - half + 2 + 0.5, y, r.nextInt(half * 2 - 3) - half + 2 + 0.5, r.nextInt(4) * 90, 0));
    }

    private static BuiltMap tripleTier() {
        MapKit k = new MapKit(21);
        int H = 15, G = 7, L = 3;
        for (int l = 0; l < L; l++) for (int x = -H; x <= H; x++) for (int z = -H; z <= H; z++) {
            k.set(x, -l * G, z, l == 1 ? "red_sand" : "sand");
            k.set(x, -l * G - 1, z, "tnt");
        }
        int W = H + 1;
        for (int x = -W - 1; x <= W + 1; x++) for (int z = -W - 1; z <= W + 1; z++) {
            if (Math.abs(x) <= W - 1 && Math.abs(z) <= W - 1) continue;
            boolean outer = Math.abs(x) == W + 1 || Math.abs(z) == W + 1;
            for (int y = -G * L; y <= 5; y++) k.set(x, y, z, outer ? (y == 5 ? "gold_block" : "smooth_sandstone") : y >= 2 ? "orange_stained_glass" : y % G == 0 ? "redstone_lamp[lit=true]" : "cut_sandstone");
        }
        MapDef d = new MapDef("tntrun", "triple_tier");
        d.display = "Triple Tier"; d.description = "Three sand floors in a sandstone tower.";
        d.minPlayers = 2; d.maxPlayers = 16; d.bounds = Box.of(-19, -G * L - 10, -19, 19, 22, 19); d.voidY = -G * (L - 1) - 5;
        d.spectator = new Pt(0.5, 14, 0.5, 0, 70);
        scatter(d, H, 1, 16, 1);
        return new BuiltMap(d, k.c);
    }

    private static BuiltMap checkerfall() {
        MapKit k = new MapKit(22);
        int H = 13, G = 5, L = 4;
        for (int l = 0; l < L; l++) for (int x = -H; x <= H; x++) for (int z = -H; z <= H; z++) {
            boolean a = ((x >> 1) + (z >> 1) + l & 1) == 0;
            k.set(x, -l * G, z, a ? "white_concrete_powder" : "light_gray_concrete_powder");
            k.set(x, -l * G - 1, z, "tnt");
        }
        // open frame: quartz pillars at the corners and edges, no walls - fall off the side too
        for (int i = -H - 1; i <= H + 1; i += 7)
            for (int[] c : new int[][]{{i, -H - 2}, {i, H + 2}, {-H - 2, i}, {H + 2, i}})
                for (int y = -G * L; y <= 6; y++) k.set(c[0], y, c[1], y == 6 ? "sea_lantern" : "quartz_pillar");
        MapDef d = new MapDef("tntrun", "checkerfall");
        d.display = "Checkerfall"; d.description = "Four checkered floors and open sides - watch the edges.";
        d.minPlayers = 2; d.maxPlayers = 12; d.bounds = Box.of(-18, -G * L - 10, -18, 18, 22, 18); d.voidY = -G * (L - 1) - 4;
        d.spectator = new Pt(0.5, 14, 0.5, 0, 70);
        scatter(d, H, 1, 12, 2);
        return new BuiltMap(d, k.c);
    }

    private static BuiltMap molten() {
        MapKit k = new MapKit(23);
        int R = 17, G = 8;
        for (int l = 0; l < 2; l++) for (int x = -R; x <= R; x++) for (int z = -R; z <= R; z++) {
            if (Math.hypot(x, z) > R + 0.4) continue;
            k.set(x, -l * G, z, l == 0 ? "red_sand" : "gravel");
            k.set(x, -l * G - 1, z, "tnt");
        }
        // basalt crater wall and a lava sea below
        for (int x = -R - 4; x <= R + 4; x++) for (int z = -R - 4; z <= R + 4; z++) {
            double r = Math.hypot(x, z);
            if (r <= R + 1.4) { k.set(x, -2 * G - 4, z, "magma_block"); k.set(x, -2 * G - 3, z, "lava"); continue; }
            if (r > R + 4) continue;
            int top = 2 + (int) (MapKit.noise(x / 3.0, z / 3.0, 9) * 5);
            for (int y = -2 * G - 4; y <= top; y++) k.set(x, y, z, y == top ? "magma_block" : MapKit.noise(x, y, 4) > 0.7 ? "blackstone" : "basalt");
        }
        MapDef d = new MapDef("tntrun", "molten");
        d.display = "Molten Crater"; d.description = "Two floors over a lava sea.";
        d.minPlayers = 2; d.maxPlayers = 16; d.bounds = Box.of(-22, -2 * G - 8, -22, 22, 24, 22); d.voidY = -G - 4;
        d.spectator = new Pt(0.5, 16, 0.5, 0, 70);
        for (int i = 0; i < 16; i++) { double a = i * Math.PI * 2 / 16; d.add("spawns", new Pt(Math.round(Math.cos(a) * 12) + 0.5, 1, Math.round(Math.sin(a) * 12) + 0.5, 0, 0).facing(0.5, 0.5)); }
        d.set("delay-ticks", 6);
        return new BuiltMap(d, k.c);
    }

    // ------------------------------------------------------------------ match

    static final class RunMatch extends Match {
        private final Set<Material> fallers = new HashSet<>();
        private final Map<Long, Integer> doomed = new HashMap<>();
        private final Map<UUID, Integer> jumps = new HashMap<>();
        private int ticks;

        RunMatch(MatchContext c) {
            super(c);
            for (String s : settings.list("blocks", List.of("SAND", "RED_SAND"))) { Material m = Material.matchMaterial(s); if (m != null) fallers.add(m); }
        }

        @Override protected String objective() { return "Keep running - the floor falls away!"; }

        @Override protected void setup(List<Player> ps) {
            List<Location> sp = points("spawns");
            Collections.shuffle(sp, rnd);
            for (int i = 0; i < ps.size(); i++) {
                Player p = ps.get(i);
                // more players than spawn points: spread the extras slightly so nobody stacks
                Location l = sp.get(i % sp.size()).clone().add(i / sp.size() * 1.5, 0, 0);
                p.teleport(l);
                jumps.put(p.getUniqueId(), settings.i("double-jumps", 2));
                p.setAllowFlight(true);
            }
        }

        @Override protected void secondTick() {
            if (elapsed == settings.i("grace-seconds", 3)) { title("", game.color + "<bold>RUN!"); sound(Sound.ENTITY_TNT_PRIMED, 1f); }
            for (Player p : everyone())
                p.sendActionBar(net.alloymc.core.Ui.mm(game.color + "TNT Run <gray>• <white>" + alive.size() + " alive <gray>• double jumps: <white>" + jumps.getOrDefault(p.getUniqueId(), 0) + " <gray>(sneak in the air)"));
        }

        @Override protected boolean pvp() { return false; }
        @Override protected boolean onDamage(Player v, org.bukkit.entity.Entity a, org.bukkit.event.entity.EntityDamageEvent e) { return false; }

        @Override protected void fastTick() {
            ticks++;
            if (elapsed >= settings.i("grace-seconds", 3)) {
                for (Player p : living()) {
                    Location l = p.getLocation();
                    for (double dx : new double[]{-0.3, 0.3}) for (double dz : new double[]{-0.3, 0.3}) {
                        Block b = world.getBlockAt(Location.locToBlock(l.getX() + dx), Location.locToBlock(l.getY() - 0.2), Location.locToBlock(l.getZ() + dz));
                        if (fallers.contains(b.getType())) doomed.putIfAbsent(key(b), ticks + settings.i("delay-ticks", 7));
                    }
                }
            }
            Iterator<Map.Entry<Long, Integer>> it = doomed.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                if (e.getValue() > ticks) continue;
                long k = e.getKey();
                Block b = world.getBlockAt((int) (k >> 38), (int) (k << 52 >> 52), (int) (k << 26 >> 38));
                b.setType(Material.AIR, false);
                Block under = b.getRelative(0, -1, 0);
                if (under.getType() == Material.TNT) under.setType(Material.AIR, false);
                it.remove();
            }
        }

        @Override protected void onSneak(Player p, boolean sneaking) {
            if (!sneaking || !isAlive(p) || ((org.bukkit.entity.Entity) p).isOnGround()) return;
            int left = jumps.getOrDefault(p.getUniqueId(), 0);
            if (left <= 0) return;
            jumps.put(p.getUniqueId(), left - 1);
            p.setVelocity(p.getLocation().getDirection().multiply(0.6).setY(0.75));
            p.playSound(p.getLocation(), Sound.ENTITY_BAT_TAKEOFF, 0.7f, 1.4f);
        }

        @Override protected void onVoid(Player p) { eliminate(p, nameOf(p) + " <gray>fell out!"); }

        @Override protected void onTimeUp() { finish(new ArrayList<>(alive), "Time's up - everyone still running wins!"); }

        @Override protected void onStart() {
            for (Player p : living()) { p.setAllowFlight(false); p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 3600, 0, false, false)); }
        }
    }
}
