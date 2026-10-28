package net.alloymc.core.hub;

import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/**
 * Flies the hub dragons. Each one is a few dozen block displays that sit at one anchor above the island and are moved
 * purely by their transformation, interpolated by the client, so nothing teleports and only the boxes that change are sent.
 * Fire, roars, fireballs, nostril smoke and ember trails are particles and sounds, all triggered at random by {@link DragonFlight}.
 * The displays are never saved with the world; any left over from a crash are removed by tag on start.
 */
final class DragonAnimator {
    private static final String TAG = "alloy_hub_dragon";
    private static final int SEND_EVERY = 3;

    private record Palette(Particle flame, Particle.DustTransition near, Particle.DustTransition far, Particle ember, Particle spark) {}

    private static final Palette FIRE = new Palette(Particle.FLAME,
            new Particle.DustTransition(Color.fromRGB(255, 240, 120), Color.fromRGB(255, 120, 20), 3.0f),
            new Particle.DustTransition(Color.fromRGB(255, 150, 30), Color.fromRGB(200, 30, 10), 3.6f), Particle.SMALL_FLAME, Particle.LAVA);
    private static final Palette SOUL = new Palette(Particle.SOUL_FIRE_FLAME,
            new Particle.DustTransition(Color.fromRGB(200, 255, 255), Color.fromRGB(60, 220, 230), 3.0f),
            new Particle.DustTransition(Color.fromRGB(70, 210, 230), Color.fromRGB(20, 80, 160), 3.6f), Particle.SOUL, Particle.SOUL);

    private static final class Fireball {
        double x, y, z, vx, vy, vz;
        int life;
        final Palette pal;
        Fireball(double[] p, double[] v, int life, Palette pal) { x = p[0]; y = p[1]; z = p[2]; vx = v[0]; vy = v[1]; vz = v[2]; this.life = life; this.pal = pal; }
    }

    private final World world;
    private final Location anchor;
    private final List<DragonFlight> flights = new ArrayList<>();
    private final List<List<BlockDisplay>> displays = new ArrayList<>();
    private final Map<String, BlockData> blockData = new HashMap<>();
    private final List<Fireball> fireballs = new ArrayList<>();
    private final Random rnd = new Random();
    private int tick;

    DragonAnimator(Plugin plugin, World world) {
        this.world = world;
        this.anchor = new Location(world, 0.5, HubLayout.Y + 30, 0.5);
        for (Entity e : world.getEntitiesByClass(BlockDisplay.class)) if (e.getScoreboardTags().contains(TAG)) e.remove();
        long seed = System.nanoTime();
        DragonFlight.Kind[] kinds = {DragonFlight.Kind.BLACK, DragonFlight.Kind.GOLD, DragonFlight.Kind.SERPENT};
        for (int i = 0; i < kinds.length; i++) {
            DragonFlight d = new DragonFlight(kinds[i], seed + i * 7919L, i * Math.PI * 2 / 3 + rnd.nextDouble());
            flights.add(d);
            List<BlockDisplay> list = new ArrayList<>();
            for (DragonFlight.Piece p : d.pose()) list.add(spawn(p));
            displays.add(list);
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 1L);
    }

    void remove() {
        displays.forEach(l -> l.forEach(Entity::remove));
        displays.clear();
    }

    private BlockData data(String id) { return blockData.computeIfAbsent(id, Bukkit::createBlockData); }

    private BlockDisplay spawn(DragonFlight.Piece p) {
        return world.spawn(anchor, BlockDisplay.class, d -> {
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
            d.setBlock(data(p.block()));
            d.setBrightness(new Display.Brightness(p.light(), 15));
            d.setViewRange(4f);
            d.setShadowRadius(0);
            d.setInterpolationDuration(SEND_EVERY);
            d.setTransformation(transform(p));
        });
    }

    private Transformation transform(DragonFlight.Piece p) {
        Matrix3f m = new Matrix3f(
                (float) p.r()[0], (float) p.r()[1], (float) p.r()[2],
                (float) p.u()[0], (float) p.u()[1], (float) p.u()[2],
                (float) p.f()[0], (float) p.f()[1], (float) p.f()[2]);
        Quaternionf q = new Quaternionf().setFromNormalized(m);
        Vector3f t = new Vector3f((float) (p.corner()[0] - anchor.getX()), (float) (p.corner()[1] - anchor.getY()), (float) (p.corner()[2] - anchor.getZ()));
        return new Transformation(t, q, new Vector3f((float) p.sx(), (float) p.sy(), (float) p.sz()), new Quaternionf());
    }

    private void tick() {
        tick++;
        boolean watched = !world.getPlayers().isEmpty();
        for (int i = 0; i < flights.size(); i++) {
            DragonFlight d = flights.get(i);
            d.step();
            if (!watched) continue;
            effects(d);
            if (tick % SEND_EVERY == i % SEND_EVERY) send(d, displays.get(i));
        }
        if (watched) fly(); else fireballs.clear();
    }

    private void send(DragonFlight d, List<BlockDisplay> list) {
        List<DragonFlight.Piece> pose = d.pose();
        for (int k = 0; k < list.size() && k < pose.size(); k++) {
            BlockDisplay e = list.get(k);
            DragonFlight.Piece p = pose.get(k);
            BlockData bd = data(p.block());
            if (!e.getBlock().equals(bd)) {
                e.setBlock(bd);
                e.setBrightness(new Display.Brightness(p.light(), 15));
            }
            e.setInterpolationDelay(0);
            e.setInterpolationDuration(SEND_EVERY);
            e.setTransformation(transform(p));
        }
    }

    // ------------------------------------------------------------------ particles and sounds

    private void effects(DragonFlight d) {
        Palette pal = d.kind == DragonFlight.Kind.SERPENT ? SOUL : FIRE;
        double[] m = d.mouth(), f = d.breathDir(), fw = d.forward(), h = d.head();
        double v = d.speed();
        Location head = new Location(world, h[0], h[1], h[2]);
        if (d.roared) {
            world.playSound(head, d.kind == DragonFlight.Kind.SERPENT ? Sound.ENTITY_ELDER_GUARDIAN_CURSE : Sound.ENTITY_ENDER_DRAGON_GROWL,
                    3f, 0.7f + rnd.nextFloat() * 0.3f);
            shockwave(m, fw);
        }
        if (d.flapped) world.playSound(head, Sound.ENTITY_ENDER_DRAGON_FLAP, 2.5f, 0.6f + rnd.nextFloat() * 0.4f);
        if (d.fireball) launch(m, d.breathDir(), fw, v, pal);
        if (d.breathing()) {
            double ramp = d.breathStrength();
            if (rnd.nextInt(9) == 0)
                world.playSound(new Location(world, m[0], m[1], m[2]), pal == SOUL ? Sound.BLOCK_SOUL_SAND_BREAK : Sound.ITEM_FIRECHARGE_USE, 3f, 0.5f + rnd.nextFloat() * 0.3f);
            if (ramp > 0.9 && rnd.nextInt(30) == 0) world.playSound(new Location(world, m[0], m[1], m[2]), Sound.ENTITY_BLAZE_SHOOT, 3f, 0.5f);
            for (int k = 0; k < 6 * ramp + 1; k++) {
                double dx = f[0] + (rnd.nextDouble() - 0.5) * 0.3, dy = f[1] + (rnd.nextDouble() - 0.5) * 0.3, dz = f[2] + (rnd.nextDouble() - 0.5) * 0.3;
                // a flame's velocity is direction * speed: add the dragon's own speed so the fire doesn't trail behind it
                double sp = 0.55 + rnd.nextDouble() * 0.4;
                world.spawnParticle(pal.flame, m[0], m[1], m[2], 0, dx + fw[0] * v / sp, dy + fw[1] * v / sp, dz + fw[2] * v / sp, sp, null, true);
            }
            for (int k = 0; k < 7 * ramp; k++) {
                double s = rnd.nextDouble() * 11 * ramp, spread = 0.3 + s * 0.22;
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, m[0] + f[0] * s, m[1] + f[1] * s, m[2] + f[2] * s, 1,
                        spread, spread, spread, 0, s < 4 ? pal.near : pal.far, true);
            }
            if (rnd.nextInt(4) == 0) {
                double s = 8 + rnd.nextDouble() * 5;
                world.spawnParticle(Particle.LARGE_SMOKE, m[0] + f[0] * s, m[1] + f[1] * s, m[2] + f[2] * s, 2, 1, 1, 1, 0.02, null, true);
            }
            if (rnd.nextInt(7) == 0) world.spawnParticle(pal.spark, m[0], m[1], m[2], 1, 0.3, 0.3, 0.3, 0, null, true);
        } else if (rnd.nextInt(14) == 0) {
            double[] n = d.nose();
            world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, n[0], n[1], n[2], 0, -fw[0] * 0.3, 0.4, -fw[2] * 0.3, 0.06, null, true);
            if (rnd.nextInt(3) == 0) world.spawnParticle(pal.ember, n[0], n[1], n[2], 2, 0.2, 0.1, 0.2, 0.01, null, true);
        }
        // embers streaming off the wing tips and tail, more of them the faster it flies
        for (double[] p : d.trails())
            if (rnd.nextDouble() < 0.25 + v * 0.5)
                world.spawnParticle(pal.ember, p[0], p[1], p[2], 0, -fw[0], -0.1, -fw[2], 0.15 + rnd.nextDouble() * 0.2, null, true);
    }

    /** A ring of smoke blasting out in front of the jaws as the dragon roars. */
    private void shockwave(double[] m, double[] fw) {
        double[] c = {m[0] + fw[0] * 3, m[1] + fw[1] * 3, m[2] + fw[2] * 3};
        double sx = -fw[2], sz = fw[0];
        double sl = Math.hypot(sx, sz);
        if (sl < 1e-6) { sx = 1; sz = 0; } else { sx /= sl; sz /= sl; }
        for (int k = 0; k < 36; k++) {
            double a = k * Math.PI * 2 / 36, cu = Math.cos(a), cv = Math.sin(a);
            world.spawnParticle(Particle.CLOUD, c[0], c[1], c[2], 0, sx * cu + fw[0] * 0.3, cv, sz * cu + fw[2] * 0.3, 0.5, null, true);
        }
        world.spawnParticle(Particle.EXPLOSION, c[0], c[1], c[2], 1, 0, 0, 0, 0, null, true);
    }

    private void launch(double[] m, double[] dir, double[] fw, double v, Palette pal) {
        double speed = 1.3 + rnd.nextDouble() * 0.5;
        double[] vel = {dir[0] * speed + fw[0] * v, dir[1] * speed + 0.3 + rnd.nextDouble() * 0.3, dir[2] * speed + fw[2] * v};
        fireballs.add(new Fireball(m, vel, 18 + rnd.nextInt(16), pal));
        world.playSound(new Location(world, m[0], m[1], m[2]), Sound.ENTITY_GHAST_SHOOT, 4f, 0.5f + rnd.nextFloat() * 0.3f);
    }

    private void fly() {
        for (Iterator<Fireball> it = fireballs.iterator(); it.hasNext(); ) {
            Fireball b = it.next();
            b.x += b.vx; b.y += b.vy; b.z += b.vz;
            b.vy -= 0.05;
            world.spawnParticle(b.pal.flame, b.x, b.y, b.z, 5, 0.3, 0.3, 0.3, 0.02, null, true);
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, b.x, b.y, b.z, 2, 0.25, 0.25, 0.25, 0, b.pal.near, true);
            if (rnd.nextBoolean()) world.spawnParticle(Particle.SMOKE, b.x, b.y, b.z, 2, 0.2, 0.2, 0.2, 0.01, null, true);
            // bursts when its time is up or before it reaches the island
            boolean overIsland = Math.hypot(b.x, b.z) < 52 && b.y < HubLayout.Y + 22;
            if (--b.life > 0 && !overIsland) continue;
            it.remove();
            world.spawnParticle(Particle.EXPLOSION_EMITTER, b.x, b.y, b.z, 1, 0, 0, 0, 0, null, true);
            world.spawnParticle(b.pal.flame, b.x, b.y, b.z, 50 + rnd.nextInt(30), 0.4, 0.4, 0.4, 0.3, null, true);
            world.spawnParticle(b.pal.spark, b.x, b.y, b.z, 10, 1.5, 1.5, 1.5, 0, null, true);
            world.playSound(new Location(world, b.x, b.y, b.z), Sound.ENTITY_GENERIC_EXPLODE, 3f, 0.6f + rnd.nextFloat() * 0.3f);
        }
    }
}
