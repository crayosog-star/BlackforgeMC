package net.alloymc.core.arcade.games.tnttag;

import net.alloymc.core.Ui;
import net.alloymc.core.arcade.core.*;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

/** TNT Tag: a few players carry TNT. Hit someone to pass it on. Whoever holds it when the fuse runs out explodes. */
public final class TntTag extends ArcadeGame {
    public TntTag() {
        super("tnttag", "TNT Tag", "<#ff3c3c>", Material.TNT_MINECART, Category.PARTY,
                "Some players are tagged with TNT. Hit someone to pass it on before the fuse runs out - last one standing wins.");
        variants.add(Variant.ffa("solo", "Solo", 3, 20));
    }

    @Override public MapRequirements requirements() { return new MapRequirements().list("spawns", 6); }

    @Override public Map<String, Object> defaultSettings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time-limit", 900);
        m.put("round-seconds", 30);
        m.put("tagged-fraction", 0.25);
        m.put("tag-cooldown-ticks", 10);
        return m;
    }

    @Override public Match newMatch(MatchContext c) { return new TagMatch(c); }

    @Override public List<BuiltMap> builtInMaps() { return List.of(rooftops(), courtyard()); }

    private static BuiltMap rooftops() {
        MapKit k = new MapKit(71);
        int H = 24;
        String[] walls = {"bricks", "stone_bricks", "polished_andesite", "mud_bricks", "deepslate_bricks"};
        for (int bx = -2; bx <= 2; bx++) for (int bz = -2; bz <= 2; bz++) {
            int cx = bx * 10, cz = bz * 10, h = 2 + k.rnd.nextInt(5), half = 3 + k.rnd.nextInt(2);
            String w = walls[k.rnd.nextInt(walls.length)];
            for (int x = -half; x <= half; x++) for (int z = -half; z <= half; z++) {
                for (int y = -8; y < h; y++) k.set(cx + x, y, cz + z, y == h - 1 ? "smooth_stone" : w);
                if (Math.abs(x) == half || Math.abs(z) == half) k.set(cx + x, h, cz + z, (x + z) % 2 == 0 ? "stone_brick_wall" : "air");
            }
            k.late(cx, h, cz, "lantern");
            // planks bridges to the east/south neighbour
            if (bx < 2) for (int x = half; x <= 10 - half; x++) k.set(cx + x, h - 1, cz, "spruce_planks");
            if (bz < 2) for (int z = half; z <= 10 - half; z++) k.set(cx, h - 1, cz + z, "spruce_planks");
        }
        MapDef d = new MapDef("tnttag", "rooftops");
        d.display = "Rooftops"; d.description = "Run and jump across the city roofs.";
        d.minPlayers = 3; d.maxPlayers = 20;
        d.bounds = Box.of(-H - 4, -12, -H - 4, H + 4, 24, H + 4); d.voidY = -10;
        d.spectator = new Pt(0.5, 18, 0.5, 0, 70);
        for (int i = 0; i < 20; i++) { int bx = (i % 5) - 2, bz = (i / 5) - 2; d.add("spawns", Pt.of(bx * 10 + 1.5, 9, bz * 10 + 1.5)); }
        return new BuiltMap(d, k.c);
    }

    private static BuiltMap courtyard() {
        MapKit k = new MapKit(72);
        int H = 20;
        for (int x = -H; x <= H; x++) for (int z = -H; z <= H; z++) {
            k.set(x, 0, z, (Math.abs(x) + Math.abs(z)) % 7 == 0 ? "mossy_stone_bricks" : "stone_bricks");
            k.set(x, -1, z, "stone");
            if (Math.abs(x) == H || Math.abs(z) == H) for (int y = 1; y <= 6; y++) k.set(x, y, z, y == 6 ? "stone_brick_slab[type=bottom]" : "stone_bricks");
        }
        // hedges, a fountain, pillars and a gazebo to loop around
        for (int i = 0; i < 10; i++) { int x = k.rnd.nextInt(30) - 15, z = k.rnd.nextInt(30) - 15; if (Math.hypot(x, z) < 5) continue; int len = 3 + k.rnd.nextInt(5); boolean ax = k.rnd.nextBoolean(); for (int t = 0; t < len; t++) for (int y = 1; y <= 2; y++) k.set(x + (ax ? t : 0), y, z + (ax ? 0 : t), "oak_leaves[persistent=true]"); }
        k.ring(0, 1, 0, 2.5, 3.5, "stone_brick_wall"); k.disc(0, 0, 0, 2.6, "water"); for (int y = 1; y <= 3; y++) k.set(0, y, 0, "chiseled_stone_bricks"); k.late(0, 4, 0, "lantern");
        for (int[] c : new int[][]{{-12, -12}, {12, -12}, {-12, 12}, {12, 12}}) for (int y = 1; y <= 5; y++) k.set(c[0], y, c[1], "quartz_pillar");
        MapDef d = new MapDef("tnttag", "courtyard");
        d.display = "Courtyard"; d.description = "A castle courtyard with hedges and a fountain to circle around.";
        d.minPlayers = 3; d.maxPlayers = 20;
        d.bounds = Box.of(-H, -4, -H, H, 20, H); d.voidY = -3;
        d.spectator = new Pt(0.5, 15, 0.5, 0, 70);
        for (int i = 0; i < 20; i++) { double a = i * Math.PI * 2 / 20; d.add("spawns", Pt.of(Math.round(Math.cos(a) * 15) + 0.5, 1, Math.round(Math.sin(a) * 15) + 0.5).facing(0.5, 0.5)); }
        return new BuiltMap(d, k.c);
    }

    static final class TagMatch extends Match {
        private final Set<UUID> tagged = new HashSet<>();
        private final Map<UUID, Integer> lastTag = new HashMap<>();
        private int roundEnd, round, ticks, pause;

        TagMatch(MatchContext c) { super(c); }

        @Override protected String objective() { return "Don't be holding the TNT when it blows!"; }

        @Override protected void setup(List<Player> ps) { List<Location> sp = points("spawns"); Collections.shuffle(sp, rnd); for (int i = 0; i < ps.size(); i++) ps.get(i).teleport(sp.get(i % sp.size())); }

        @Override protected void onStart() { newRound(); }

        private void newRound() {
            round++;
            tagged.clear();
            List<Player> ps = new ArrayList<>(living());
            Collections.shuffle(ps, rnd);
            int n = Math.max(1, (int) Math.round(ps.size() * settings.d("tagged-fraction", 0.25)));
            if (n >= ps.size()) n = ps.size() - 1;
            for (Player p : ps) { p.getInventory().clear(); p.getInventory().setHelmet(null); p.removePotionEffect(PotionEffectType.SPEED); p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 3600, 0, false, false)); }
            for (int i = 0; i < n; i++) tag(ps.get(i), null);
            roundEnd = elapsed + settings.i("round-seconds", 30);
            broadcast(prefix() + "Round <white>" + round + "<gray>: " + n + " player" + (n > 1 ? "s are" : " is") + " IT!");
        }

        private void tag(Player p, Player from) {
            tagged.add(p.getUniqueId());
            lastTag.put(p.getUniqueId(), ticks);
            p.getInventory().setHelmet(new ItemStack(Material.TNT));
            for (int s = 0; s < 9; s++) p.getInventory().setItem(s, Ui.item(Material.TNT, "<red><bold>YOU'RE IT! <gray>Hit someone!"));
            p.removePotionEffect(PotionEffectType.SPEED);
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 3600, 1, false, false));
            p.setGlowing(true);
            title(p, "<red><bold>YOU'RE IT!", "<gray>Tag someone else!");
            if (from != null) {
                tagged.remove(from.getUniqueId());
                from.getInventory().clear(); from.getInventory().setHelmet(null); from.setGlowing(false);
                from.removePotionEffect(PotionEffectType.SPEED); from.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 3600, 0, false, false));
                broadcast(prefix() + nameOf(from) + " <gray>tagged " + nameOf(p));
                arcade.stats().add(from.getUniqueId(), game.id, "tags", 1);
                p.getWorld().playSound(p.getLocation(), Sound.ENTITY_TNT_PRIMED, 1f, 1.4f);
            }
        }

        @Override protected boolean onDamage(Player victim, Entity attacker, EntityDamageEvent e) {
            if (attacker instanceof Player a && tagged.contains(a.getUniqueId()) && !tagged.contains(victim.getUniqueId())
                    && ticks - lastTag.getOrDefault(a.getUniqueId(), -100) >= settings.i("tag-cooldown-ticks", 10)) tag(victim, a);
            e.setDamage(0);
            return attacker instanceof Player;   // knockback only, never real damage
        }

        @Override protected void fastTick() {
            ticks++;
            if (ticks % 10 == 0) for (UUID u : tagged) { Player p = org.bukkit.Bukkit.getPlayer(u); if (p != null) p.getWorld().spawnParticle(Particle.SMOKE, p.getLocation().add(0, 2.3, 0), 3, 0.1, 0.1, 0.1, 0.01); }
        }

        @Override protected void secondTick() {
            if (pause > 0) { if (--pause == 0) newRound(); return; }
            int left = roundEnd - elapsed;
            actionBar("<red>💣 <white>" + left + "s <gray>• " + tagged.size() + " tagged • " + alive.size() + " alive");
            if (left <= 5 && left > 0) sound(Sound.BLOCK_NOTE_BLOCK_HAT, 2f);
            if (left > 0) return;
            for (UUID u : new ArrayList<>(tagged)) {
                Player p = org.bukkit.Bukkit.getPlayer(u);
                if (p == null) continue;
                p.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, p.getLocation(), 1);
                p.getWorld().playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f);
                p.setGlowing(false);
                eliminate(p, nameOf(p) + " <gray>blew up!");
            }
            tagged.clear();
            if (alive.size() > 1) { pause = 3; broadcast(prefix() + "Next round in 3 seconds..."); for (Player p : living()) arcade.stats().max(p.getUniqueId(), game.id, "best_round", round); }
        }

        @Override protected void onVoid(Player p) { List<Location> sp = points("spawns"); p.teleport(sp.get(rnd.nextInt(sp.size()))); }

        @Override protected void onLeave(Player p) { tagged.remove(p.getUniqueId()); }

        @Override protected List<String> sidebar(Player p) {
            List<String> l = super.sidebar(p);
            l.add("<gray>Round: <white>" + round);
            l.add(tagged.contains(p.getUniqueId()) ? "<red><bold>YOU'RE IT!" : "<green>Safe... for now");
            return l;
        }
    }
}
