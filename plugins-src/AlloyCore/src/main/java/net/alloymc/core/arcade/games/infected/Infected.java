package net.alloymc.core.arcade.games.infected;

import net.alloymc.core.Ui;
import net.alloymc.core.arcade.ArenaBuilder;
import net.alloymc.core.arcade.core.*;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

/** Infected: one (or more) zombies; every tag turns a human. Humans win by surviving the clock. */
public final class Infected extends ArcadeGame {
    public Infected() {
        super("infected", "Infected", "<#7ddc4a>", Material.ZOMBIE_HEAD, Category.PARTY,
                "One zombie. Every hit spreads the infection. Survive until the timer runs out.");
        variants.add(Variant.ffa("classic", "Classic", 3, 16));
    }

    @Override public MapRequirements requirements() { return new MapRequirements().list("spawns", 4).point("zombie_spawn"); }

    @Override public Map<String, Object> defaultSettings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("release-seconds", 10);
        m.put("time-limit", 180);
        m.put("zombies-per-8-players", 1);
        return m;
    }

    @Override public Match newMatch(MatchContext c) { return new InfectedMatch(c); }

    @Override public List<BuiltMap> builtInMaps() { return List.of(oldTown(), deepMine()); }

    private static BuiltMap oldTown() {
        var c = ArenaBuilder.build("infected", 0, 0, 0);
        MapDef d = new MapDef("infected", "old_town");
        d.display = "Old Town"; d.description = "Cobbled streets, houses with rooftops to climb.";
        d.minPlayers = 3; d.maxPlayers = 16; d.bounds = Box.of(-31, -3, -31, 31, 16, 31); d.voidY = -3;
        d.spectator = new Pt(0.5, 12, 0.5, 0, 50);
        for (int i = 0; i < 16; i++) { int sx = -27 + (i % 4) * 18, sz = -27 + (i / 4) * 18; d.add("spawns", Pt.of(sx + 0.5, 1, sz + 0.5)); }
        d.point("zombie_spawn", Pt.of(0.5, 2, 0.5));
        return new BuiltMap(d, c);
    }

    private static BuiltMap deepMine() {
        MapKit k = new MapKit(31);
        int H = 28;
        // a solid block of rock carved into tunnels and caverns
        for (int x = -H; x <= H; x++) for (int z = -H; z <= H; z++) {
            k.set(x, -1, z, "deepslate_tiles");
            k.set(x, 9, z, "deepslate");
            for (int y = 0; y <= 8; y++) {
                double n = MapKit.noise(x / 7.0, z / 7.0 + y * 0.05, 5);
                boolean tunnel = Math.floorMod(x, 9) <= 2 || Math.floorMod(z, 9) <= 2;
                boolean cave = n > 0.62;
                boolean open = (tunnel && y <= 4) || (cave && y <= 7) || (Math.abs(x) < 6 && Math.abs(z) < 6 && y <= 7);
                boolean edge = Math.abs(x) == H || Math.abs(z) == H;
                if (edge || !open) k.set(x, y, z, edge ? "bedrock" : MapKit.noise(x * 0.6, z * 0.6 + y, 8) > 0.82 ? "deepslate_iron_ore" : "deepslate");
            }
        }
        // supports and lanterns along tunnels
        for (int x = -H + 1; x < H; x++) for (int z = -H + 1; z < H; z++) {
            if (Math.floorMod(x, 9) == 1 && Math.floorMod(z, 9) == 4) { for (int y = 0; y <= 3; y++) k.set(x - 1, y, z, "oak_log"); k.set(x, 4, z, "oak_planks"); k.late(x, 3, z, "lantern[hanging=true]"); }
            if (Math.floorMod(x, 9) == 4 && Math.floorMod(z, 9) == 1 && MapKit.noise(x, z, 3) > 0.5) k.late(x, 0, z, "rail");
        }
        for (int y = 0; y <= 7; y++) k.set(0, y, 0, y == 7 ? "glowstone" : "spruce_fence");
        MapDef d = new MapDef("infected", "deep_mine");
        d.display = "Deep Mine"; d.description = "Dark tunnels and caverns underground.";
        d.minPlayers = 3; d.maxPlayers = 16; d.bounds = Box.of(-29, -2, -29, 29, 10, 29); d.voidY = -3;
        d.spectator = new Pt(3.5, 6, 3.5, 0, 30);
        for (int i = 0; i < 16; i++) { int sx = -27 + (i % 4) * 18, sz = -27 + (i / 4) * 18; d.add("spawns", Pt.of(sx + 1.5, 0, sz + 1.5)); }
        d.point("zombie_spawn", Pt.of(2.5, 0, 2.5));
        d.set("time-limit", 210);
        return new BuiltMap(d, k.c);
    }

    // ------------------------------------------------------------------ match

    static final class InfectedMatch extends Match {
        private final Set<UUID> zombies = new HashSet<>(), patientZero = new HashSet<>();

        InfectedMatch(MatchContext c) { super(c); }

        @Override protected String objective() { return "Humans: survive. Zombies: tag everyone!"; }

        @Override protected void setup(List<Player> ps) {
            List<Location> sp = points("spawns");
            Collections.shuffle(sp, rnd);
            for (int i = 0; i < ps.size(); i++) ps.get(i).teleport(sp.get(i % sp.size()));
        }

        @Override protected void onStart() {
            broadcast(prefix() + "The infection is picked in <white>" + settings.i("release-seconds", 10) + "s<gray> - scatter and hide!");
            for (Player p : living()) p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 600, 0, false, false));
        }

        @Override protected void secondTick() {
            int rel = settings.i("release-seconds", 10);
            if (elapsed == rel) {
                List<Player> ps = new ArrayList<>(living());
                Collections.shuffle(ps, rnd);
                int n = Math.max(1, ps.size() / 8 * settings.i("zombies-per-8-players", 1));
                for (int i = 0; i < n && i < ps.size(); i++) { patientZero.add(ps.get(i).getUniqueId()); infect(ps.get(i), null); ps.get(i).teleport(point("zombie_spawn")); }
            }
            actionBar(game.color + "☣ <white>" + humans().size() + " humans <gray>vs <#7ddc4a>" + zombies.size() + " zombies <gray>• " + clock(timeLimit() - elapsed));
        }

        private List<Player> humans() { return living().stream().filter(p -> !zombies.contains(p.getUniqueId())).toList(); }

        private void infect(Player p, Player by) {
            zombies.add(p.getUniqueId());
            ItemStack[] armour = {leather(Material.LEATHER_BOOTS), leather(Material.LEATHER_LEGGINGS), leather(Material.LEATHER_CHESTPLATE), new ItemStack(Material.ZOMBIE_HEAD)};
            p.getInventory().setArmorContents(armour);
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 600, 1, false, false));
            broadcast(prefix() + "<#7ddc4a>☣ <white>" + p.getName() + (by == null ? " <gray>is patient zero!" : " <gray>was infected by <#7ddc4a>" + by.getName()));
            p.playSound(p.getLocation(), Sound.ENTITY_ZOMBIE_VILLAGER_CONVERTED, 1f, 1f);
            if (by != null) { arcade.stats().add(by.getUniqueId(), game.id, "kills", 1); arcade.stats().add(by.getUniqueId(), game.id, "infections", 1); }
        }

        private static ItemStack leather(Material m) {
            ItemStack it = new ItemStack(m);
            it.editMeta(LeatherArmorMeta.class, meta -> { meta.setColor(Color.fromRGB(0x4c9a2a)); meta.setUnbreakable(true); });
            return it;
        }

        @Override protected boolean onDamage(Player victim, Entity attacker, EntityDamageEvent e) {
            if (attacker instanceof Player a && zombies.contains(a.getUniqueId()) && !zombies.contains(victim.getUniqueId()) && elapsed >= settings.i("release-seconds", 10)) infect(victim, a);
            return false;   // nobody actually takes damage
        }

        @Override protected void onVoid(Player p) { p.teleport(point("zombie_spawn")); }

        @Override protected List<UUID> checkWinners() {
            if (elapsed <= settings.i("release-seconds", 10)) return null;
            if (humans().isEmpty()) { broadcast(prefix() + "Everyone has been infected!"); return new ArrayList<>(patientZero); }
            if (living().stream().noneMatch(p -> zombies.contains(p.getUniqueId()))) { broadcast(prefix() + "The infection died out!"); return humans().stream().map(Player::getUniqueId).toList(); }
            return null;
        }

        @Override protected void onTimeUp() { finish(humans().stream().map(Player::getUniqueId).toList(), "The humans held out!"); }

        @Override protected List<String> sidebar(Player p) {
            List<String> l = super.sidebar(p);
            l.add("<gray>You are: " + (zombies.contains(p.getUniqueId()) ? "<#7ddc4a>ZOMBIE" : "<white>HUMAN"));
            return l;
        }
    }
}
