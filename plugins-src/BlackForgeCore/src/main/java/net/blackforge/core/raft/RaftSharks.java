package net.blackforge.core.raft;

import net.blackforge.core.Ui;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.Dolphin;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.*;

import static net.blackforge.core.raft.RaftMover.TILE_Y;

/**
 * Sharks. Every few minutes one circles a raft that has people on it, bites anyone in the water or at the edge, and sometimes chews
 * a tile off. Hit it enough and it flees. Built on dolphins (a shark is a big grey one that has had enough), steered here.
 * The same machinery runs Bruce, the shark of Utopia ({@link RaftStory}).
 */
final class RaftSharks implements Listener {
    static final String TAG = "raft_shark", BOSS = "raft_bruce";

    private final RaftModule mod;
    private final Random rnd = new Random();
    private final Map<UUID, Long> nextBite = new HashMap<>(), bornAt = new HashMap<>();
    private final Map<UUID, Double> angle = new HashMap<>();

    RaftSharks(RaftModule mod) {
        this.mod = mod;
        Bukkit.getPluginManager().registerEvents(this, mod.plugin());
        Bukkit.getScheduler().runTaskTimer(mod.plugin(), this::spawnTick, 200L, 100L);
        Bukkit.getScheduler().runTaskTimer(mod.plugin(), this::aiTick, 100L, 5L);
    }

    private void spawnTick() {
        World w = mod.world();
        if (w == null || !mod.plugin().getConfig().getBoolean("raft.sharks", true)) return;
        long now = System.currentTimeMillis();
        for (RaftTeam t : mod.activeTeams()) {
            if (t.sharkAt == 0) { t.sharkAt = now + (3 + rnd.nextInt(4)) * 60_000L; continue; }
            if (now < t.sharkAt || t.tiles.isEmpty() || sharksNear(t) > 0) continue;
            t.sharkAt = now + (6 + rnd.nextInt(6)) * 60_000L;
            int[] c = t.centre();
            for (int tries = 0; tries < 12; tries++) {
                double a = rnd.nextDouble() * Math.PI * 2, d = 16 + rnd.nextInt(8);
                int x = c[0] + (int) Math.round(Math.cos(a) * d), z = c[1] + (int) Math.round(Math.sin(a) * d);
                if (!w.isChunkLoaded(x >> 4, z >> 4) || w.getBlockAt(x, TILE_Y - 1, z).getType() != Material.WATER || w.getBlockAt(x, TILE_Y, z).getType() != Material.WATER) continue;
                spawn(new Location(w, x + 0.5, TILE_Y - 1, z + 0.5), "<red><bold>Shark", 2.0, 40, false);
                mod.tell(t, "<red>A fin cuts the water. <gray>A shark circles your raft.");
                break;
            }
        }
    }

    private int sharksNear(RaftTeam t) {
        int[] c = t.centre();
        int n = 0;
        for (Entity e : mod.world().getNearbyEntities(new Location(mod.world(), c[0], TILE_Y, c[1]), 60, 30, 60)) if (e.getScoreboardTags().contains(TAG)) n++;
        return n;
    }

    Dolphin spawn(Location at, String name, double scale, double hp, boolean boss) {
        Dolphin d = at.getWorld().spawn(at, Dolphin.class, s -> {
            s.addScoreboardTag(TAG);
            if (boss) s.addScoreboardTag(BOSS);
            s.setPersistent(false);
            s.setRemoveWhenFarAway(true);
            s.setAdult();
            s.customName(Ui.mm(name));
            s.setCustomNameVisible(true);
            s.getAttribute(Attribute.SCALE).setBaseValue(scale);
            s.getAttribute(Attribute.MAX_HEALTH).setBaseValue(hp);
            s.setHealth(hp);
            s.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(boss ? 0.5 : 0.42);
        });
        bornAt.put(d.getUniqueId(), System.currentTimeMillis());
        angle.put(d.getUniqueId(), rnd.nextDouble() * Math.PI * 2);
        return d;
    }

    private void aiTick() {
        World w = mod.world();
        if (w == null) return;
        long now = System.currentTimeMillis();
        for (Dolphin d : w.getEntitiesByClass(Dolphin.class)) {
            if (!d.getScoreboardTags().contains(TAG)) continue;
            boolean boss = d.getScoreboardTags().contains(BOSS);
            double hp = d.getHealth(), max = d.getAttribute(Attribute.MAX_HEALTH).getValue();
            RaftTeam t = mod.teamNear(d.getLocation(), boss ? 140 : 70);
            if (t == null && !boss) { d.remove(); continue; }
            if (!boss && (hp < max * 0.3 || now - bornAt.getOrDefault(d.getUniqueId(), now) > 120_000)) {      // had enough, or bored
                w.spawnParticle(Particle.SPLASH, d.getLocation(), 40, 0.8, 0.4, 0.8, 0.2);
                w.playSound(d.getLocation(), Sound.ENTITY_DOLPHIN_SPLASH, 1f, 0.5f);
                d.remove();
                continue;
            }
            // the prey: someone in the water, or at the very edge of the raft
            Player prey = null; double best = boss ? 40 : 26;
            for (Player p : w.getPlayers()) {
                if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) continue;
                if (t != null && !t.members.contains(p.getUniqueId()) && !boss) continue;
                boolean wet = p.isInWater() || p.getLocation().getBlock().getType() == Material.WATER;
                boolean edge = t != null && edgeNear(t, p.getLocation());
                if (!wet && !edge && !boss) continue;
                if (boss && !wet && !edge && p.getLocation().distance(d.getLocation()) > 7) continue;
                double dist = p.getLocation().distance(d.getLocation());
                if (dist < best) { best = dist; prey = p; }
            }
            if (prey != null) {
                Location to = prey.getLocation();
                if (!w.getBlockAt(to).isLiquid()) to = nearestWater(to, d.getLocation());
                d.getPathfinder().moveTo(to, boss ? 1.6 : 1.4);
                if (d.getLocation().distance(prey.getLocation()) < (boss ? 3.5 : 2.6) && now >= nextBite.getOrDefault(d.getUniqueId(), 0L)) {
                    nextBite.put(d.getUniqueId(), now + (boss ? 1800 : 2500));
                    prey.damage(boss ? 7.0 : 4.0, d);
                    w.playSound(prey.getLocation(), Sound.ENTITY_PLAYER_ATTACK_STRONG, 1f, 0.6f);
                    w.spawnParticle(Particle.DAMAGE_INDICATOR, prey.getLocation().add(0, 1, 0), 6, 0.3, 0.3, 0.3, 0.1);
                }
                continue;
            }
            // nobody to bite: circle the raft, and now and then chew a tile off its edge
            if (t != null) {
                int[] c = t.centre();
                double a = angle.merge(d.getUniqueId(), 0.09, Double::sum), r = 11 + Math.min(10, Math.sqrt(t.tiles.size()));
                d.getPathfinder().moveTo(new Location(w, c[0] + 0.5 + Math.cos(a) * r, TILE_Y - 1, c[1] + 0.5 + Math.sin(a) * r), 1.3);
                if (!boss && now >= nextBite.getOrDefault(d.getUniqueId(), 0L) && t.tiles.size() > 4) chew(t, d, now);
            }
        }
    }

    private Location nearestWater(Location want, Location from) {
        World w = from.getWorld();
        Location l = from.clone();
        for (int dy = 0; dy >= -3; dy--) { Block b = w.getBlockAt(want.getBlockX(), TILE_Y + dy, want.getBlockZ()); if (b.getType() == Material.WATER) return b.getLocation().add(0.5, 0, 0.5); }
        return l;
    }

    private boolean edgeNear(RaftTeam t, Location l) {
        int x = l.getBlockX(), z = l.getBlockZ();
        if (l.getY() < TILE_Y || l.getY() > TILE_Y + 3) return false;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (!t.has(x + dx, z + dz) && mod.world().getBlockAt(x + dx, TILE_Y, z + dz).getType() == Material.WATER) return t.has(x, z);
        return false;
    }

    private void chew(RaftTeam t, Dolphin d, long now) {
        World w = mod.world();
        for (long k : t.tiles) {
            int x = RaftTeam.tx(k), z = RaftTeam.tz(k);
            if (d.getLocation().distanceSquared(new Location(w, x + 0.5, TILE_Y, z + 0.5)) > 3.2 * 3.2) continue;
            boolean edge = false;
            for (int[] n : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) if (!t.has(x + n[0], z + n[1])) edge = true;
            if (!edge || t.stations.keySet().stream().anyMatch(key -> t.stations.get(key).x == x && t.stations.get(key).z == z)) continue;
            nextBite.put(d.getUniqueId(), now + 9000);
            if (rnd.nextInt(3) != 0) return;
            boolean clear = true;
            for (int y = TILE_Y + 1; y <= RaftMover.TOP_Y; y++) if (!w.getBlockAt(x, y, z).getType().isAir()) clear = false;
            if (!clear) return;
            t.tiles.remove(k);
            w.getBlockAt(x, TILE_Y, z).setType(Material.WATER, false);
            w.playSound(new Location(w, x, TILE_Y, z), Sound.ENTITY_GENERIC_EAT, 1f, 0.5f);
            w.spawnParticle(Particle.BLOCK, new Location(w, x + 0.5, TILE_Y + 0.5, z + 0.5), 30, 0.4, 0.3, 0.4, 0, Material.OAK_PLANKS.createBlockData());
            mod.tell(t, "<red>The shark tore a tile off your raft!");
            mod.markDirty();
            return;
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        LivingEntity m = e.getEntity();
        if (!m.getScoreboardTags().contains(TAG)) return;
        e.getDrops().clear();
        boolean boss = m.getScoreboardTags().contains(BOSS);
        e.getDrops().add(new ItemStack(Material.COD, 3 + rnd.nextInt(3)));
        if (rnd.nextInt(3) == 0 || boss) e.getDrops().add(RaftItems.make("scrap", 2 + rnd.nextInt(3)));
        e.setDroppedExp(boss ? 500 : 25);
        if (boss) mod.story().bruceDied(m);
        else mod.world().spawnParticle(Particle.SPLASH, m.getLocation(), 40, 0.8, 0.4, 0.8, 0.2);
    }
}
