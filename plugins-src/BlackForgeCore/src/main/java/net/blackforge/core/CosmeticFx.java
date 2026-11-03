package net.blackforge.core;

import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;
import java.util.function.Predicate;

/**
 * Draws the equipped trail, aura, pet and cape of every player (see {@link Cosmetics}). All of it is server-side and
 * temporary: pets are unsaved mobs and capes are display entities, both removed when the player leaves, dies, enters
 * an arcade match or the plugin stops, so nothing is ever left behind in a world.
 */
final class CosmeticFx implements Listener {
    private static final String PET_TAG = "bf_pet", CAPE_TAG = "bf_cape";

    private final BlackForgeCore plugin;
    private final Cosmetics cos;
    private Predicate<Player> blocked = p -> false;
    private final Map<UUID, Pet> pets = new HashMap<>();
    private final Map<UUID, Cape> capes = new HashMap<>();
    private final Map<UUID, Location> last = new HashMap<>();
    private final BukkitTask fastTask, petTask;
    private int tick;

    CosmeticFx(BlackForgeCore plugin, Cosmetics cos) {
        this.plugin = plugin;
        this.cos = cos;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        fastTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickFx, 20L, 2L);
        petTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickPets, 40L, 10L);
        Hooks.onStat((p, key, n, max) -> { String pid = cos.equipped(p, Cosmetics.Kind.PET); if (pid != null && !max) cos.addPetXp(p.getUniqueId(), pid, (int) Math.min(25, n * 5)); });
        Hooks.onKill((killer, victim, where) -> petDid(killer, 3));
        // anything left over from a crashed run (they aren't saved, but a chunk can be loaded mid-spawn)
        for (World w : Bukkit.getWorlds()) for (Entity e : w.getEntities()) if (e.getScoreboardTags().contains(PET_TAG) || e.getScoreboardTags().contains(CAPE_TAG)) e.remove();
    }

    private static final Set<String> TRAILS = Set.of("t_ember", "t_ash", "t_spore", "t_bubble", "t_arc", "t_script", "t_sakura", "t_prism", "t_wraith", "t_molten", "t_galaxy", "t_haunt", "t_snowdrift", "t_heart", "t_bloom", "t_sun", "t_anniv", "t_founder");
    private static final Set<String> AURAS = Set.of("a_halo", "a_ember", "a_frost", "a_soul", "a_galaxy", "a_swarm", "a_blizzard", "a_cupid", "a_butterflies", "a_sunburst", "a_anniv", "a_founder");

    /** Whether a trail, aura, pet or cape in the catalog actually has something drawing it (the catalog test relies on this). */
    static boolean implemented(Cosmetics.Cosmetic c) {
        return switch (c.kind()) {
            case TRAIL -> TRAILS.contains(c.id());
            case AURA -> AURAS.contains(c.id());
            case PET -> PETS.containsKey(c.id());
            case CAPE -> CAPES.containsKey(c.id());
            case ICON -> Cosmetics.glyph(c.id()) != null;
            default -> true;
        };
    }

    void setBlocked(Predicate<Player> rule) { this.blocked = rule; }

    void shutdown() {
        fastTask.cancel();
        petTask.cancel();
        for (UUID u : new ArrayList<>(pets.keySet())) removePet(u);
        for (UUID u : new ArrayList<>(capes.keySet())) removeCape(u);
    }

    /** Called right after someone equips or removes something, so the change shows at once. */
    void refresh(Player p) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) return;
            tickOne(p);
            tickPet(p);
        });
    }

    // ------------------------------------------------------------------ events

/** The pet earns experience from what you do together. */
private void petDid(Player p, int xp) {
    if (p == null) return;
    String pid = cos.equipped(p, Cosmetics.Kind.PET);
    if (pid != null && pets.containsKey(p.getUniqueId())) cos.addPetXp(p.getUniqueId(), pid, xp);
}

@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
public void onMobKill(org.bukkit.event.entity.EntityDeathEvent e) {
    if (e.getEntity() instanceof Enemy && e.getEntity().getKiller() != null) petDid(e.getEntity().getKiller(), 2);
}

@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
public void onOre(org.bukkit.event.block.BlockBreakEvent e) {
    if (e.getBlock().getType().name().endsWith("_ORE")) petDid(e.getPlayer(), 1);
}

@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
public void onCatch(org.bukkit.event.player.PlayerFishEvent e) {
    if (e.getState() == org.bukkit.event.player.PlayerFishEvent.State.CAUGHT_FISH) petDid(e.getPlayer(), 2);
}

@EventHandler public void onQuit(PlayerQuitEvent e) { gone(e.getPlayer().getUniqueId()); }

    @EventHandler public void onDeath(PlayerDeathEvent e) { removePet(e.getPlayer().getUniqueId()); removeCape(e.getPlayer().getUniqueId()); }

    private void gone(UUID u) { removePet(u); removeCape(u); last.remove(u); }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEntityEvent e) { if (e.getRightClicked().getScoreboardTags().contains(PET_TAG)) e.setCancelled(true); }

    @EventHandler public void onTarget(EntityTargetEvent e) { if (e.getEntity().getScoreboardTags().contains(PET_TAG)) e.setCancelled(true); }

    // ------------------------------------------------------------------ the fast loop: trails, auras, capes

    private void tickFx() {
        tick++;
        for (Player p : Bukkit.getOnlinePlayers()) tickOne(p);
    }

    private boolean hidden(Player p) {
        return p.isDead() || p.getGameMode() == GameMode.SPECTATOR || p.hasPotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY) || blocked.test(p);
    }

    private void tickOne(Player p) {
        UUID u = p.getUniqueId();
        Location now = p.getLocation();
        Location was = last.put(u, now);
        double speed = was != null && was.getWorld() == now.getWorld() ? Math.hypot(now.getX() - was.getX(), now.getZ() - was.getZ()) / 2.0 : 0;   // blocks per tick
        String trail = cos.equipped(p, Cosmetics.Kind.TRAIL), aura = cos.equipped(p, Cosmetics.Kind.AURA), cape = cos.equipped(p, Cosmetics.Kind.CAPE);
        if (cape == null) cape = Crews.capeFor(p);   // no cape on: members of a crew wear their crew's colours
        if (hidden(p)) { removeCape(u); return; }
        if (trail != null && speed > 0.02 && !p.isFlying() && !p.isGliding()) trail(trail, p, now);
        if (aura != null) aura(aura, p, now);
        if (cape == null || p.isGliding() || p.isSwimming() || p.isSleeping() || p.isRiptiding()) removeCape(u);
        else cape(p, cape, speed);
    }

    // trails ---------------------------------------------------------------

    private static Color hue(double h) {
        h = ((h % 1) + 1) % 1;
        double r, g, b;
        double x = h * 6, f = x - Math.floor(x);
        switch ((int) x) {
            case 0 -> { r = 1; g = f; b = 0; }
            case 1 -> { r = 1 - f; g = 1; b = 0; }
            case 2 -> { r = 0; g = 1; b = f; }
            case 3 -> { r = 0; g = 1 - f; b = 1; }
            case 4 -> { r = f; g = 0; b = 1; }
            default -> { r = 1; g = 0; b = 1 - f; }
        }
        return Color.fromRGB((int) (r * 255), (int) (g * 255), (int) (b * 255));
    }

    private void trail(String id, Player p, Location at) {
        World w = at.getWorld();
        Location f = at.clone().add(0, 0.12, 0), mid = at.clone().add(0, 0.9, 0);
        switch (id) {
            case "t_ember" -> w.spawnParticle(Particle.SMALL_FLAME, f, 3, 0.15, 0.05, 0.15, 0.01);
            case "t_ash" -> w.spawnParticle(Particle.SMOKE, f, 4, 0.18, 0.05, 0.18, 0.01);
            case "t_spore" -> w.spawnParticle(Particle.SPORE_BLOSSOM_AIR, mid, 4, 0.35, 0.4, 0.35, 0);
            case "t_bubble" -> w.spawnParticle(Particle.BUBBLE_POP, f, 5, 0.2, 0.1, 0.2, 0.02);
            case "t_arc" -> w.spawnParticle(Particle.ELECTRIC_SPARK, f, 4, 0.2, 0.1, 0.2, 0.15);
            case "t_script" -> w.spawnParticle(Particle.ENCHANT, at.clone().add(0, 1.2, 0), 8, 0.35, 0.3, 0.35, 0.6);
            case "t_sakura" -> w.spawnParticle(Particle.CHERRY_LEAVES, mid, 3, 0.35, 0.5, 0.35, 0);
            case "t_prism" -> {
                for (int i = 0; i < 3; i++) w.spawnParticle(Particle.DUST, f, 2, 0.15, 0.06, 0.15, 0, new Particle.DustOptions(hue(tick * 0.02 + i * 0.11), 1.2f));
            }
            case "t_wraith" -> {
                w.spawnParticle(Particle.SOUL_FIRE_FLAME, f, 2, 0.15, 0.05, 0.15, 0.01);
                if (tick % 6 == 0) w.spawnParticle(Particle.SOUL, f, 1, 0.1, 0.2, 0.1, 0.02);
            }
            case "t_molten" -> {
                w.spawnParticle(Particle.FLAME, f, 2, 0.15, 0.04, 0.15, 0.01);
                w.spawnParticle(Particle.DUST, f, 3, 0.18, 0.05, 0.18, 0, new Particle.DustOptions(Color.fromRGB(255, 110, 20), 1.3f));
                if (tick % 4 == 0) w.spawnParticle(Particle.DRIPPING_LAVA, mid, 1, 0.25, 0.3, 0.25, 0);
            }
            case "t_galaxy" -> {
                w.spawnParticle(Particle.END_ROD, f, 1, 0.2, 0.05, 0.2, 0.01);
                w.spawnParticle(Particle.DUST, f, 3, 0.2, 0.08, 0.2, 0, new Particle.DustOptions(tick % 2 == 0 ? Color.fromRGB(150, 90, 255) : Color.fromRGB(70, 200, 255), 1.1f));
            }
            case "t_haunt" -> {
                w.spawnParticle(Particle.DUST, f, 3, 0.18, 0.05, 0.18, 0, new Particle.DustOptions(tick % 2 == 0 ? Color.fromRGB(255, 140, 20) : Color.fromRGB(150, 70, 230), 1.2f));
                if (tick % 5 == 0) w.spawnParticle(Particle.WITCH, mid, 2, 0.3, 0.4, 0.3, 0);
                if (tick % 9 == 0) w.spawnParticle(Particle.SOUL, f, 1, 0.1, 0.2, 0.1, 0.02);
            }
            case "t_snowdrift" -> {
                w.spawnParticle(Particle.SNOWFLAKE, f, 5, 0.22, 0.08, 0.22, 0.02);
                if (tick % 6 == 0) w.spawnParticle(Particle.END_ROD, f, 1, 0.15, 0.05, 0.15, 0.01);
            }
            case "t_heart" -> {
                w.spawnParticle(Particle.DUST, f, 3, 0.2, 0.05, 0.2, 0, new Particle.DustOptions(Color.fromRGB(255, 110, 160), 1.2f));
                if (tick % 3 == 0) w.spawnParticle(Particle.HEART, mid, 1, 0.3, 0.4, 0.3, 0);
            }
            case "t_bloom" -> {
                w.spawnParticle(Particle.DUST, f, 3, 0.2, 0.06, 0.2, 0, new Particle.DustOptions(tick % 2 == 0 ? Color.fromRGB(255, 160, 200) : Color.fromRGB(150, 230, 120), 1.1f));
                if (tick % 6 == 0) w.spawnParticle(Particle.HAPPY_VILLAGER, mid, 2, 0.3, 0.4, 0.3, 0);
            }
            case "t_sun" -> {
                w.spawnParticle(Particle.DUST, f, 3, 0.2, 0.06, 0.2, 0, new Particle.DustOptions(tick % 2 == 0 ? Color.fromRGB(255, 225, 80) : Color.fromRGB(255, 150, 30), 1.3f));
                if (tick % 5 == 0) w.spawnParticle(Particle.END_ROD, f, 1, 0.15, 0.05, 0.15, 0.01);
            }
            case "t_anniv" -> {
                w.spawnParticle(Particle.DUST, f, 3, 0.2, 0.06, 0.2, 0, new Particle.DustOptions(Color.fromRGB(255, 205, 80), 1.3f));
                if (tick % 4 == 0) w.spawnParticle(Particle.FIREWORK, f, 1, 0.15, 0.05, 0.15, 0.03);
            }
            case "t_founder" -> {
                w.spawnParticle(Particle.FLAME, f, 2, 0.15, 0.05, 0.15, 0.01);
                w.spawnParticle(Particle.DUST, f, 3, 0.2, 0.06, 0.2, 0, new Particle.DustOptions(tick % 2 == 0 ? Color.fromRGB(255, 190, 60) : Color.fromRGB(25, 20, 30), 1.3f));
                if (tick % 10 == 0) w.spawnParticle(Particle.LAVA, f, 1, 0.1, 0.05, 0.1, 0);
            }
            default -> {}
        }
    }

    // auras ----------------------------------------------------------------

    private void aura(String id, Player p, Location at) {
        World w = at.getWorld();
        double t = tick;
        switch (id) {
            case "a_halo" -> {
                for (int j = 0; j < 6; j++) {
                    double a = t * 0.15 + j * Math.PI / 3;
                    w.spawnParticle(Particle.DUST, at.clone().add(Math.cos(a) * 0.32, p.isSneaking() ? 1.95 : 2.2, Math.sin(a) * 0.32), 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(255, 215, 100), 0.75f));
                }
            }
            case "a_ember" -> {
                for (int j = 0; j < 3; j++) {
                    double a = t * 0.2 + j * Math.PI * 2 / 3;
                    w.spawnParticle(Particle.SMALL_FLAME, at.clone().add(Math.cos(a) * 0.85, 1.0 + 0.35 * Math.sin(t * 0.1 + j * 2), Math.sin(a) * 0.85), 1, 0, 0, 0, 0);
                }
            }
            case "a_frost" -> {
                for (int j = 0; j < 2; j++) {
                    double a = -t * 0.17 + j * Math.PI;
                    w.spawnParticle(Particle.SNOWFLAKE, at.clone().add(Math.cos(a) * 0.9, 0.5 + ((t * 0.04 + j * 0.5) % 1) * 1.5, Math.sin(a) * 0.9), 1, 0, 0, 0, 0);
                }
                if (tick % 8 == 0) w.spawnParticle(Particle.END_ROD, at.clone().add(0, 1, 0), 1, 0.6, 0.6, 0.6, 0.01);
            }
            case "a_soul" -> {
                for (int j = 0; j < 2; j++) {
                    double a = t * 0.25 + j * Math.PI;
                    w.spawnParticle(Particle.SOUL_FIRE_FLAME, at.clone().add(Math.cos(a) * 0.6, ((t * 0.035) % 1) * 2.0, Math.sin(a) * 0.6), 1, 0, 0, 0, 0);
                }
            }
            case "a_galaxy" -> {
                for (int arm = 0; arm < 2; arm++) {
                    for (int k = 0; k < 3; k++) {
                        double a = t * 0.15 + arm * Math.PI + k * 0.7, r = 0.35 + k * 0.28;
                        w.spawnParticle(Particle.DUST, at.clone().add(Math.cos(a) * r, 1.0 + 0.1 * Math.sin(t * 0.1 + k), Math.sin(a) * r), 1, 0, 0, 0, 0,
                                new Particle.DustOptions(arm == 0 ? Color.fromRGB(150, 90, 255) : Color.fromRGB(70, 200, 255), 0.9f));
                    }
                }
                if (tick % 5 == 0) w.spawnParticle(Particle.END_ROD, at.clone().add(0, 1, 0), 1, 0.8, 0.5, 0.8, 0.01);
            }
            case "a_swarm" -> {
                for (int j = 0; j < 6; j++) {
                    double a = t * 0.4 + j * Math.PI / 3, r = 0.7 + 0.25 * Math.sin(t * 0.13 + j);
                    w.spawnParticle(Particle.DUST, at.clone().add(Math.cos(a) * r, 1.0 + 0.7 * Math.sin(t * 0.21 + j * 1.7), Math.sin(a) * r), 1, 0, 0, 0, 0,
                            new Particle.DustOptions(j % 2 == 0 ? Color.fromRGB(30, 10, 45) : Color.fromRGB(150, 70, 230), 0.9f));
                }
            }
            case "a_blizzard" -> {
                w.spawnParticle(Particle.SNOWFLAKE, at.clone().add(0, 2.4, 0), 5, 0.8, 0.1, 0.8, 0.02);
                for (int j = 0; j < 3; j++) {
                    double a = t * 0.2 + j * Math.PI * 2 / 3;
                    w.spawnParticle(Particle.SNOWFLAKE, at.clone().add(Math.cos(a) * 1.1, 1.0 + 0.5 * Math.sin(t * 0.1 + j), Math.sin(a) * 1.1), 1, 0, 0, 0, 0);
                }
                if (tick % 10 == 0) w.spawnParticle(Particle.CLOUD, at.clone().add(0, 2.5, 0), 2, 0.6, 0.05, 0.6, 0.005);
            }
            case "a_cupid" -> {
                for (int j = 0; j < 6; j++) {
                    double a = t * 0.15 + j * Math.PI / 3;
                    w.spawnParticle(Particle.DUST, at.clone().add(Math.cos(a) * 0.33, p.isSneaking() ? 1.95 : 2.2, Math.sin(a) * 0.33), 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(255, 150, 190), 0.8f));
                }
                if (tick % 12 == 0) w.spawnParticle(Particle.HEART, at.clone().add(0, 2.5, 0), 1, 0.3, 0.1, 0.3, 0);
            }
            case "a_butterflies" -> {
                Color[] cs = {Color.fromRGB(255, 160, 200), Color.fromRGB(150, 230, 120), Color.fromRGB(255, 235, 120), Color.WHITE, Color.fromRGB(190, 160, 255)};
                for (int j = 0; j < 5; j++) {
                    double a = t * (0.12 + 0.03 * j) + j * 1.3, r = 0.8 + 0.3 * Math.sin(t * 0.09 + j);
                    w.spawnParticle(Particle.DUST, at.clone().add(Math.cos(a) * r, 1.1 + 0.6 * Math.sin(t * 0.3 + j * 2), Math.sin(a) * r), 1, 0, 0, 0, 0, new Particle.DustOptions(cs[j], 1.0f));
                }
            }
            case "a_sunburst" -> {
                for (int j = 0; j < 3; j++) {
                    double a = t * 0.22 + j * Math.PI * 2 / 3;
                    w.spawnParticle(Particle.DUST, at.clone().add(Math.cos(a) * 0.7, ((t * 0.04 + j / 3.0) % 1) * 2.1, Math.sin(a) * 0.7), 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(255, 215, 60), 1.0f));
                }
                if (tick % 6 == 0) w.spawnParticle(Particle.END_ROD, at.clone().add(0, 1, 0), 1, 0.5, 0.7, 0.5, 0.01);
            }
            case "a_anniv" -> {
                for (int j = 0; j < 4; j++) {
                    double a = t * 0.2 + j * Math.PI / 2;
                    w.spawnParticle(Particle.DUST, at.clone().add(Math.cos(a) * 0.9, 1.0 + 0.4 * Math.sin(t * 0.1 + j), Math.sin(a) * 0.9), 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(255, 205, 80), 1.1f));
                }
                if (tick % 5 == 0) w.spawnParticle(Particle.FIREWORK, at.clone().add(0, 1.2, 0), 1, 0.7, 0.6, 0.7, 0.02);
            }
            case "a_founder" -> {
                for (int j = 0; j < 8; j++) {
                    double a = t * 0.12 + j * Math.PI / 4;
                    w.spawnParticle(Particle.DUST, at.clone().add(Math.cos(a) * 0.36, (p.isSneaking() ? 1.95 : 2.2) + (j % 2 == 0 ? 0.12 : 0), Math.sin(a) * 0.36), 1, 0, 0, 0, 0, new Particle.DustOptions(Color.fromRGB(255, 190, 60), 0.8f));
                }
                if (tick % 3 == 0) w.spawnParticle(Particle.FLAME, at.clone().add(0, p.isSneaking() ? 2.0 : 2.25, 0), 1, 0.2, 0.02, 0.2, 0.005);
            }
            default -> {}
        }
    }

    // ------------------------------------------------------------------ capes


    /** A cape is a few thin blocks hanging off the shoulders: base cloth, a hem, an optional crest. */
    record CapeDef(Material base, Material hem, Material crest, boolean glow, Material[] cycle) {}

    private static final Material[] RAINBOW = {Material.RED_WOOL, Material.ORANGE_WOOL, Material.YELLOW_WOOL, Material.LIME_WOOL, Material.CYAN_WOOL, Material.BLUE_WOOL, Material.PURPLE_WOOL, Material.MAGENTA_WOOL};

    private static final Map<String, CapeDef> CAPES = Map.ofEntries(
            Map.entry("c_crimson", new CapeDef(Material.RED_WOOL, Material.BLACK_WOOL, null, false, null)),
            Map.entry("c_forest", new CapeDef(Material.GREEN_WOOL, Material.BROWN_WOOL, null, false, null)),
            Map.entry("c_midnight", new CapeDef(Material.BLUE_WOOL, Material.WHITE_WOOL, null, false, null)),
            Map.entry("c_ash", new CapeDef(Material.GRAY_WOOL, Material.BLACK_CONCRETE, null, false, null)),
            Map.entry("c_royal", new CapeDef(Material.PURPLE_WOOL, Material.GOLD_BLOCK, Material.YELLOW_CONCRETE, false, null)),
            Map.entry("c_frost", new CapeDef(Material.LIGHT_BLUE_WOOL, Material.WHITE_WOOL, Material.PACKED_ICE, false, null)),
            Map.entry("c_sun", new CapeDef(Material.ORANGE_WOOL, Material.RED_WOOL, Material.GOLD_BLOCK, false, null)),
            Map.entry("c_netherite", new CapeDef(Material.BLACKSTONE, Material.GOLD_BLOCK, Material.NETHERITE_BLOCK, false, null)),
            Map.entry("c_crystal", new CapeDef(Material.AMETHYST_BLOCK, Material.PURPLE_STAINED_GLASS, Material.AMETHYST_CLUSTER, true, null)),
            Map.entry("c_prism", new CapeDef(Material.WHITE_WOOL, Material.WHITE_CONCRETE, null, true, RAINBOW)),
            Map.entry("c_molten", new CapeDef(Material.MAGMA_BLOCK, Material.ORANGE_CONCRETE, Material.SHROOMLIGHT, true, null)),
            Map.entry("c_star", new CapeDef(Material.CRYING_OBSIDIAN, Material.PURPLE_CONCRETE, Material.SEA_LANTERN, true, null)),
            Map.entry("c_blackforge", new CapeDef(Material.BLACK_CONCRETE, Material.GOLD_BLOCK, Material.SHROOMLIGHT, true, null)),
            Map.entry("c_hallow", new CapeDef(Material.BLACK_WOOL, Material.ORANGE_CONCRETE, Material.JACK_O_LANTERN, true, null)),
            Map.entry("c_frostforged", new CapeDef(Material.BLUE_ICE, Material.WHITE_CONCRETE, Material.SEA_LANTERN, true, null)),
            Map.entry("c_rose", new CapeDef(Material.PINK_WOOL, Material.RED_WOOL, Material.NETHER_WART_BLOCK, true, null)),
            Map.entry("c_blossom", new CapeDef(Material.MOSS_BLOCK, Material.PINK_WOOL, Material.CHERRY_LEAVES, false, null)),
            Map.entry("c_tide", new CapeDef(Material.CYAN_WOOL, Material.SMOOTH_SANDSTONE, Material.SEA_LANTERN, true, null)),
            Map.entry("c_anniv", new CapeDef(Material.GOLD_BLOCK, Material.BLACK_CONCRETE, Material.SHROOMLIGHT, true, null)),
            Map.entry("c_founder", new CapeDef(Material.POLISHED_BLACKSTONE, Material.GOLD_BLOCK, Material.DIAMOND_BLOCK, true, null)),
            Map.entry("c_champ_hallow", new CapeDef(Material.ORANGE_CONCRETE, Material.GOLD_BLOCK, Material.JACK_O_LANTERN, true, null)),
            Map.entry("c_champ_frost", new CapeDef(Material.LIGHT_BLUE_CONCRETE, Material.GOLD_BLOCK, Material.SEA_LANTERN, true, null)),
            Map.entry("c_champ_emberheart", new CapeDef(Material.RED_CONCRETE, Material.GOLD_BLOCK, Material.NETHER_WART_BLOCK, true, null)),
            Map.entry("c_champ_bloomforge", new CapeDef(Material.PINK_CONCRETE, Material.GOLD_BLOCK, Material.CHERRY_LEAVES, true, null)),
            Map.entry("c_champ_sunforge", new CapeDef(Material.YELLOW_CONCRETE, Material.GOLD_BLOCK, Material.SEA_LANTERN, true, null)),
            Map.entry("c_champ_anniversary", new CapeDef(Material.GOLD_BLOCK, Material.GOLD_BLOCK, Material.SHROOMLIGHT, true, null)));


    /** Part geometry in the cape's own frame, measured from the top-centre of the cloth: x right, y up (so cloth is negative), z back. */
    private static final float W = 0.5f, H = 0.78f, T = 0.03f;
    private static final float[][] PARTS = {
            // minX, minY, minZ, sizeX, sizeY, sizeZ
            {-W / 2, -H, 0f, W, H, T},                 // cloth
            {-W / 2, -H, -0.002f, W, 0.1f, T + 0.004f}, // hem
            {-0.1f, -0.52f, -0.003f, 0.2f, 0.2f, T + 0.006f}  // crest
    };

    private static final class Cape {
        final String id;
        final BlockDisplay[] parts = new BlockDisplay[3];
        int cycle = -1;
        Cape(String id) { this.id = id; }
        boolean valid() { for (BlockDisplay d : parts) if (d != null && !d.isValid()) return false; return true; }
    }

    private float bodyYaw(Player p) {
        try { return p.getBodyYaw(); } catch (Throwable t) { return p.getLocation().getYaw(); }
    }

    private void cape(Player p, String id, double speed) {
        CapeDef found = CAPES.get(id);
        if (found == null && id.startsWith("crew_")) found = Crews.capeDef(id.substring(5));
        final CapeDef def = found;
        if (def == null) return;
        UUID u = p.getUniqueId();
        Cape c = capes.get(u);
        if (c != null && (!c.id.equals(id) || !c.valid() || c.parts[0].getWorld() != p.getWorld())) { removeCape(u); c = null; }
        Location at = p.getLocation();
        at.setYaw(bodyYaw(p));
        at.setPitch(0);
        if (c == null) {
            c = new Cape(id);
            for (int i = 0; i < 3; i++) {
                Material m = i == 0 ? def.base : i == 1 ? (cos.isOriginal(p, id) ? Material.GOLD_BLOCK : def.hem) : def.crest;
                if (m == null) continue;
                final Material mat = m;
                c.parts[i] = at.getWorld().spawn(at, BlockDisplay.class, d -> {
                    d.setBlock(mat.createBlockData());
                    d.setPersistent(false);
                    d.setViewRange(0.6f);
                    d.setTeleportDuration(2);
                    d.setInterpolationDuration(2);
                    d.setShadowRadius(0f);
                    d.addScoreboardTag(CAPE_TAG);
                    if (def.glow) d.setBrightness(new Display.Brightness(15, 15));
                });
            }
            capes.put(u, c);
            for (BlockDisplay d : c.parts) if (d != null) hideFromOptedOut(d, u);
        }
        // swing: the faster you move the further the cloth lifts off your back; ducking leans it out, a little flutter keeps it alive
        boolean sneak = p.isSneaking();
        double tilt = Math.toRadians(5 + Math.min(34, speed * 130) + (sneak ? 14 : 0) + (speed > 0.03 ? 3 * Math.sin(tick * 0.55) : 0));
        Quaternionf rot = new Quaternionf().rotateX((float) -tilt);
        Vector3f pivot = new Vector3f(0f, sneak ? 1.2f : 1.4f, sneak ? 0.2f : 0.15f);
        if (def.cycle != null) {
            int ci = (tick / 5) % def.cycle.length;
            if (ci != c.cycle) {
                c.cycle = ci;
                BlockData bd = def.cycle[ci].createBlockData();
                c.parts[0].setBlock(bd);
            }
        }
        for (int i = 0; i < 3; i++) {
            BlockDisplay d = c.parts[i];
            if (d == null) continue;
            float[] g = PARTS[i];
            Vector3f min = new Vector3f(g[0], g[1], g[2]);
            rot.transform(min);
            Vector3f t = new Vector3f(pivot).add(min);
            d.teleport(at);
            d.setInterpolationDelay(0);
            d.setTransformation(new org.bukkit.util.Transformation(t, new Quaternionf(rot), new Vector3f(g[3], g[4], g[5]), new Quaternionf()));
        }
    }

    private void removeCape(UUID u) {
        Cape c = capes.remove(u);
        if (c != null) for (BlockDisplay d : c.parts) if (d != null) d.remove();
    }

    // ------------------------------------------------------------------ pets

    private record PetDef(String type, boolean flies, java.util.function.Consumer<Entity> tweak) {
        PetDef(String type, boolean flies) { this(type, flies, null); }
    }

    private static final Map<String, PetDef> PETS = Map.ofEntries(
            Map.entry("p_cat", new PetDef("CAT", false)),
            Map.entry("p_rabbit", new PetDef("RABBIT", false)),
            Map.entry("p_chick", new PetDef("CHICKEN", false)),
            Map.entry("p_fox", new PetDef("FOX", false)),
            Map.entry("p_parrot", new PetDef("PARROT", true)),
            Map.entry("p_frog", new PetDef("FROG", false)),
            Map.entry("p_armadillo", new PetDef("ARMADILLO", false)),
            Map.entry("p_allay", new PetDef("ALLAY", true)),
            Map.entry("p_wolf", new PetDef("WOLF", false)),
            Map.entry("p_bee", new PetDef("BEE", true)),
            Map.entry("p_sniffer", new PetDef("SNIFFER", false)),
            Map.entry("p_golem", new PetDef("COPPER_GOLEM", false)),
            Map.entry("p_ghast", new PetDef("HAPPY_GHAST", true)),
            Map.entry("p_blackcat", new PetDef("CAT", false, e -> { if (e instanceof Cat c) c.setCatType(Cat.Type.ALL_BLACK); })),
            Map.entry("p_snowfox", new PetDef("FOX", false, e -> { if (e instanceof Fox f) f.setFoxType(Fox.Type.SNOW); })),
            Map.entry("p_lovebird", new PetDef("PARROT", true, e -> { if (e instanceof Parrot b) b.setVariant(Parrot.Variant.RED); })),
            Map.entry("p_bunny", new PetDef("RABBIT", false, e -> { if (e instanceof Rabbit r) r.setRabbitType(Rabbit.Type.WHITE); })),
            Map.entry("p_turtle", new PetDef("TURTLE", false)),
            Map.entry("p_piglet", new PetDef("PIG", false)));


    private static final class Pet {
        final String id;
        final Entity entity;
        final boolean flies;
        int level, sinceXp, idle;
        Location lastOwner;
        Pet(String id, Entity entity, boolean flies) { this.id = id; this.entity = entity; this.flies = flies; }
    }

    private void tickPets() {
        for (Player p : Bukkit.getOnlinePlayers()) tickPet(p);
        reconcileVisibility();
    }

    /** Viewers who switched off other people's pets and capes (/settings) don't get those entities sent to them. */
    private final Set<UUID> hiding = new HashSet<>();

    private void reconcileVisibility() {
        Map<Entity, UUID> all = new HashMap<>();
        for (Map.Entry<UUID, Pet> e : pets.entrySet()) if (e.getValue().entity.isValid()) all.put(e.getValue().entity, e.getKey());
        for (Map.Entry<UUID, Cape> e : capes.entrySet()) for (BlockDisplay d : e.getValue().parts) if (d != null && d.isValid()) all.put(d, e.getKey());
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            UUID v = viewer.getUniqueId();
            if (cos.hidesOthers(viewer)) {
                hiding.add(v);
                for (Map.Entry<Entity, UUID> e : all.entrySet()) if (!e.getValue().equals(v)) viewer.hideEntity(plugin, e.getKey());
            } else if (hiding.remove(v)) {
                for (Entity e : all.keySet()) viewer.showEntity(plugin, e);
            }
        }
        hiding.removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    /** Hide a freshly spawned pet or cape from everyone who opted out, right away. */
    private void hideFromOptedOut(Entity e, UUID owner) {
        for (Player viewer : Bukkit.getOnlinePlayers()) if (!viewer.getUniqueId().equals(owner) && hiding.contains(viewer.getUniqueId())) viewer.hideEntity(plugin, e);
    }


    private void tickPet(Player p) {
        UUID u = p.getUniqueId();
        String id = cos.equipped(p, Cosmetics.Kind.PET);
        if (id == null || hidden(p) || !PETS.containsKey(id)) { removePet(u); return; }
        Pet pet = pets.get(u);
        if (pet != null && (!pet.id.equals(id) || !pet.entity.isValid())) { removePet(u); pet = null; }
        if (pet == null) pet = spawnPet(p, id);
        if (pet == null) return;
        Entity e = pet.entity;
        Location me = p.getLocation();
        if (e.getWorld() != me.getWorld() || e.getLocation().distanceSquared(me) > 196) { e.teleport(behind(me)); return; }
        double d2 = e.getLocation().distanceSquared(me);
        if (e instanceof Mob m) {
            m.setTarget(null);
            if (d2 > 9) m.getPathfinder().moveTo(pet.flies ? me.clone().add(0, 1.2, 0) : me, pet.flies ? 1.0 : 1.3);
            else if (d2 < 2.5) m.getPathfinder().stopPathfinding();
        }
        if (d2 > 100) e.teleport(behind(me));
        levelUp(p, pet, id);
        abilities(p, pet, id);
    }

    // abilities ------------------------------------------------------------

    private record Perk(String first, String second, String text) {}

    private static PotionEffectType effect(String name) { return Registry.EFFECT.get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT))); }

    /** What each pet does for you: a small utility effect at level 3 and another at level 6. Never combat strength. */
    private static final Map<String, Perk> PERKS = Map.ofEntries(
            Map.entry("p_cat", new Perk("NIGHT_VISION", "LUCK", "Night Vision, then Luck")),
            Map.entry("p_blackcat", new Perk("NIGHT_VISION", "LUCK", "Night Vision, then Luck")),
            Map.entry("p_rabbit", new Perk("JUMP_BOOST", "SPEED", "Jump Boost, then Speed")),
            Map.entry("p_bunny", new Perk("JUMP_BOOST", "SPEED", "Jump Boost, then Speed")),
            Map.entry("p_chick", new Perk("SLOW_FALLING", "JUMP_BOOST", "Slow Falling, then Jump Boost")),
            Map.entry("p_fox", new Perk("SPEED", "HASTE", "Speed, then Haste")),
            Map.entry("p_snowfox", new Perk("SPEED", "HASTE", "Speed, then Haste")),
            Map.entry("p_parrot", new Perk("SLOW_FALLING", "SPEED", "Slow Falling, then Speed")),
            Map.entry("p_lovebird", new Perk("SLOW_FALLING", "SPEED", "Slow Falling, then Speed")),
            Map.entry("p_frog", new Perk("JUMP_BOOST", "WATER_BREATHING", "Jump Boost, then Water Breathing")),
            Map.entry("p_turtle", new Perk("WATER_BREATHING", "DOLPHINS_GRACE", "Water Breathing, then Dolphin's Grace")),
            Map.entry("p_armadillo", new Perk("HASTE", "FIRE_RESISTANCE", "Haste, then Fire Resistance")),
            Map.entry("p_allay", new Perk(null, "LUCK", "Collects nearby drops for you, then Luck")),
            Map.entry("p_wolf", new Perk("HASTE", "SPEED", "Haste, then Speed")),
            Map.entry("p_bee", new Perk("SPEED", "SLOW_FALLING", "Speed, then Slow Falling")),
            Map.entry("p_sniffer", new Perk("LUCK", "HASTE", "Luck, then Haste")),
            Map.entry("p_golem", new Perk("HASTE", "LUCK", "Haste, then Luck")),
            Map.entry("p_ghast", new Perk("SLOW_FALLING", "SPEED", "Slow Falling, then Speed")),
            Map.entry("p_piglet", new Perk("LUCK", "HASTE", "Luck, then Haste")));

    static String perkText(String id) {
        Perk k = PERKS.get(id);
        return k == null ? null : k.text;
    }

    /** Pets don't give abilities where fights are (Factions, Arcade), so nobody wins a fight because of a pet. */
    private boolean abilitiesOn() {
        String mode = plugin.getConfig().getString("mode", "backend");
        return !mode.equals("factions") && !mode.equals("arcade");
    }

/**
 * The ability starts out barely there and grows with the pet's level: the effect is switched on for a short part of every
 * ten seconds (nothing at level 1, about 60% at level 9) and is constant at max level, when the second ability joins.
 */
private void abilities(Player p, Pet pet, String id) {
    Perk k = PERKS.get(id);
    if (k == null || pet.level < 2 || !abilitiesOn()) return;
    long t = pet.entity.getTicksLived();
    boolean max = pet.level >= Cosmetics.PET_MAX;
    if (id.equals("p_allay")) {   // the allay brings drops to you: only a short reach until it's fully grown
        double r = max ? 8 : 1.5 + 0.4 * (pet.level - 1);
        for (Entity en : p.getNearbyEntities(r, r, r)) {
            if (!(en instanceof Item it) || it.getPickupDelay() > 0 || !it.getScoreboardTags().isEmpty()) continue;
            Vector v = p.getLocation().add(0, 0.5, 0).toVector().subtract(it.getLocation().toVector());
            if (v.lengthSquared() > 1) it.setVelocity(v.normalize().multiply(0.3));
        }
    }
    if (t % 20 >= 10) return;   // once a second is plenty
    long window = (t % 200) / 10 * 10;   // where we are in the 10 s cycle, in steps of 10 ticks
    boolean on = max || window < (pet.level - 1) * 15L;
    if (!on) return;
    if (k.first != null) p.addPotionEffect(new PotionEffect(effect(k.first), 30, 0, true, false, false));
    if (max && k.second != null) p.addPotionEffect(new PotionEffect(effect(k.second), 30, 0, true, false, false));
}

    /** XP while it walks with you, and what the pet can do at each level. */
    private void levelUp(Player p, Pet pet, String id) {
        UUID u = p.getUniqueId();
        Entity e = pet.entity;
        pet.sinceXp += 10;
        if (pet.sinceXp >= 1200) {   // a point a minute just for walking together
            pet.sinceXp = 0;
            cos.addPetXp(u, id, 1);
        }
        if (pet.level == 0 || pet.sinceXp == 0) {
            int lv = Cosmetics.petLevel(cos.petXp(u, id));
            if (pet.level != 0 && lv > pet.level) {
                p.sendMessage(Ui.mm("<gold>★ <gray>Your " + nameOf(id) + "<gray> reached <gold>level " + lv + "<gray>!" + perk(lv)));
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
                e.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, e.getLocation().add(0, 0.6, 0), 24, 0.3, 0.4, 0.3, 0.2);
            }
            pet.level = lv;
            Cosmetics.Cosmetic c = Cosmetics.byId(id);
            if (c != null) e.customName(Ui.mm("<gray>" + p.getName() + "'s </gray>" + c.name() + " <gold>" + (lv >= Cosmetics.PET_MAX ? "★ MAX" : "Lv " + lv)));
        }
        int lv = pet.level;
        Location at = e.getLocation().add(0, e instanceof Ageable ? 0.5 : 0.8, 0);
        pet.idle = pet.lastOwner != null && pet.lastOwner.getWorld() == p.getWorld() && pet.lastOwner.distanceSquared(p.getLocation()) < 0.04 ? pet.idle + 10 : 0;
        pet.lastOwner = p.getLocation();
        long t = e.getTicksLived();
        if (lv >= 3 && t % 400 < 10) e.getWorld().spawnParticle(Particle.HEART, at, 2, 0.25, 0.2, 0.25, 0);
        if (lv >= 5 && !pet.flies && pet.idle == 0 && t % 20 < 10) e.getWorld().spawnParticle(Particle.NOTE, at, 1, 0.2, 0.1, 0.2, 1);
        if (lv >= 7 && pet.idle >= 160) {   // owner standing still for 8 s: the pet settles down
            if (e instanceof org.bukkit.entity.Sittable s) s.setSitting(true);
            if (e instanceof Fox f) f.setSleeping(true);
        } else if (pet.idle == 0) {
            if (e instanceof org.bukkit.entity.Sittable s && s.isSitting()) s.setSitting(false);
            if (e instanceof Fox f && f.isSleeping()) f.setSleeping(false);
        }
        if (lv >= 9 && t % 20 < 10) e.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, at.clone().add(0, 0.4, 0), 2, 0.3, 0.2, 0.3, 0);
        if (lv >= Cosmetics.PET_MAX) {
            double a = t * 0.25;
            e.getWorld().spawnParticle(Particle.END_ROD, at.clone().add(Math.cos(a) * 0.5, 0.7, Math.sin(a) * 0.5), 1, 0, 0, 0, 0);
        }
    }

    private static String nameOf(String id) {
        Cosmetics.Cosmetic c = Cosmetics.byId(id);
        return c == null ? "pet" : c.name();
    }

    private static String perk(int lv) {
        return switch (lv) {
            case 2 -> " <gray>Its ability stirs - very faintly.";
            case 3 -> " <gray>It shows you some love now and then.";
            case 5 -> " <gray>It hums along as it walks.";
            case 7 -> " <gray>It settles down when you stand still.";
            case 9 -> " <gray>It glows with happiness.";
            case 10 -> " <gold>Fully grown - its ability is at full strength, a second one joins, and it wears a halo of light.";
            default -> "";
        };
    }

    private Location behind(Location me) {
        Vector back = me.getDirection().setY(0);
        if (back.lengthSquared() < 1.0e-4) back = new Vector(0, 0, 1); else back.normalize().multiply(-1.6);
        Location l = me.clone().add(back);
        return l.getBlock().isPassable() ? l : me.clone();
    }

    private Pet spawnPet(Player p, String id) {
        PetDef def = PETS.get(id);
        EntityType type;
        try { type = EntityType.valueOf(def.type); } catch (IllegalArgumentException ex) { return null; }
        Cosmetics.Cosmetic c = Cosmetics.byId(id);
        Entity e;
        try {
            e = p.getWorld().spawnEntity(behind(p.getLocation()), type, CreatureSpawnEvent.SpawnReason.CUSTOM, x -> {
                x.setPersistent(false);
                x.setInvulnerable(true);
                x.setSilent(true);
                x.addScoreboardTag(PET_TAG);
                if (c != null) { x.customName(Ui.mm("<gray>" + p.getName() + "'s </gray>" + c.name())); x.setCustomNameVisible(true); }
                if (x instanceof Ageable a) { a.setBaby(); a.setAgeLock(true); }
                if (x instanceof LivingEntity le) { le.setCollidable(false); le.setCanPickupItems(false); le.setRemoveWhenFarAway(false); }
                if (x instanceof Bee b) { b.setHasStung(true); b.setAnger(0); }
                if (def.tweak != null) def.tweak.accept(x);
            });
        } catch (Exception ex) {
            plugin.getLogger().warning("could not spawn pet " + id + ": " + ex.getMessage());
            return null;
        }
        Pet pet = new Pet(id, e, def.flies);
        pet.lastOwner = p.getLocation();
        pets.put(p.getUniqueId(), pet);
        hideFromOptedOut(e, p.getUniqueId());
        return pet;
    }

    private void removePet(UUID u) {
        Pet pet = pets.remove(u);
        if (pet != null) pet.entity.remove();
    }
}
