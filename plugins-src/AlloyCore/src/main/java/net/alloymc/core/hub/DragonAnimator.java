package net.alloymc.core.hub;

import net.alloymc.core.build.Canvas;
import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.*;

/**
 * Brings the sky dragon statues to life for everyone on the hub, all with particles, sounds and client-side block changes:
 * <ul>
 *   <li>a fire breath that sweeps side to side (blue soul fire for the serpent), taking turns every 11 seconds,</li>
 *   <li>a roar now and then, with a shockwave ring of smoke in front of the jaws,</li>
 *   <li>fireballs lobbed out over the void between breaths, bursting into embers,</li>
 *   <li>eyes that blink, smoke curling from the nostrils, and embers streaming off the wing tips and tail in the wind.</li>
 * </ul>
 */
final class DragonAnimator {
    private static final int BREATH_PERIOD = 220, BREATH_LENGTH = 60, FIREBALL_PERIOD = 300;

    private record Palette(Particle flame, Particle.DustTransition near, Particle.DustTransition far, Particle ember) {}

    private static final Palette FIRE = new Palette(Particle.FLAME,
            new Particle.DustTransition(Color.fromRGB(255, 240, 120), Color.fromRGB(255, 120, 20), 3.2f),
            new Particle.DustTransition(Color.fromRGB(255, 150, 30), Color.fromRGB(200, 30, 10), 3.8f), Particle.SMALL_FLAME);
    private static final Palette SOUL = new Palette(Particle.SOUL_FIRE_FLAME,
            new Particle.DustTransition(Color.fromRGB(200, 255, 255), Color.fromRGB(60, 220, 230), 3.2f),
            new Particle.DustTransition(Color.fromRGB(70, 210, 230), Color.fromRGB(20, 80, 160), 3.8f), Particle.SOUL);

    /** A fireball in flight: position, velocity and how many ticks it has left before it bursts. */
    private static final class Fireball {
        double x, y, z, vx, vy, vz;
        int life;
        final Palette pal;
        Fireball(double[] p, double[] v, int life, Palette pal) { x = p[0]; y = p[1]; z = p[2]; vx = v[0]; vy = v[1]; vz = v[2]; this.life = life; this.pal = pal; }
    }

    private final World world;
    private final List<Map<Location, BlockData>> eyes = new ArrayList<>(), lids = new ArrayList<>();
    private final List<Fireball> fireballs = new ArrayList<>();
    private final Random rnd = new Random();
    private int tick;

    DragonAnimator(Plugin plugin, World world) {
        this.world = world;
        for (Dragons.Dragon d : Dragons.ALL) {
            // the eye blocks, found from the sculpted shape, and the eyelid block to flash over them when blinking
            Canvas c = Dragons.build(d);
            Map<Location, BlockData> open = new HashMap<>(), shut = new HashMap<>();
            for (double[] e : d.eyes())
                for (int x = (int) Math.floor(e[0]) - 1; x <= e[0] + 1; x++)
                    for (int y = (int) Math.floor(e[1]) - 1; y <= e[1] + 1; y++)
                        for (int z = (int) Math.floor(e[2]) - 1; z <= e[2] + 1; z++)
                            if (d.kind().eye.equals(c.get(x, y, z))) {
                                Location l = new Location(world, x, y, z);
                                open.put(l, Bukkit.createBlockData(d.kind().eye));
                                shut.put(l, Bukkit.createBlockData(d.kind().eyelid));
                            }
            eyes.add(open);
            lids.add(shut);
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 2L);
    }

    private Palette palette(Dragons.Dragon d) { return d.kind() == Dragons.Kind.SERPENT ? SOUL : FIRE; }

    private void tick() {
        tick += 2;
        List<Player> viewers = world.getPlayers();
        if (viewers.isEmpty()) { fireballs.clear(); return; }
        int n = Dragons.ALL.size();
        for (int i = 0; i < n; i++) {
            Dragons.Dragon d = Dragons.ALL.get(i);
            Palette pal = palette(d);
            int t = Math.floorMod(tick - i * BREATH_PERIOD / n, BREATH_PERIOD);
            if (t < BREATH_LENGTH) breathe(d, i, t, pal);
            else if (t % 16 == 0) nostrils(d);
            if (Math.floorMod(tick - i * 97, FIREBALL_PERIOD) == 140) launch(d, pal);
            blink(i, Math.floorMod(tick - i * 53, 120), viewers);
            trails(d, pal);
        }
        fly();
    }

    // ------------------------------------------------------------------ breath and roar

    private void breathe(Dragons.Dragon d, int i, int t, Palette pal) {
        double[] m = d.mouth();
        Location at = new Location(world, m[0], m[1], m[2]);
        boolean roar = (tick / BREATH_PERIOD + i) % 3 == 0;
        if (t == 0) {
            if (roar) {
                world.playSound(at, d.kind() == Dragons.Kind.SERPENT ? Sound.ENTITY_ELDER_GUARDIAN_CURSE : Sound.ENTITY_ENDER_DRAGON_GROWL, 3f, 0.8f);
                shockwave(d);
            }
            world.playSound(at, Sound.ENTITY_BLAZE_SHOOT, 4f, d.kind() == Dragons.Kind.SERPENT ? 0.7f : 0.5f);
        } else if (t % 10 == 0) {
            world.playSound(at, d.kind() == Dragons.Kind.SERPENT ? Sound.BLOCK_SOUL_SAND_BREAK : Sound.ITEM_FIRECHARGE_USE, 3f, 0.6f);
        }
        double ramp = Math.min(1, Math.min(t, BREATH_LENGTH - t) / 10.0);   // flare up, then die down
        double[] f = d.breath(28 * Math.sin(t * Math.PI * 2 / BREATH_LENGTH));   // the head swings left, right and back
        // fast flames streaming out of the mouth
        for (int k = 0; k < 10 * ramp + 2; k++) {
            double dx = f[0] + (rnd.nextDouble() - 0.5) * 0.25, dy = f[1] + (rnd.nextDouble() - 0.5) * 0.25, dz = f[2] + (rnd.nextDouble() - 0.5) * 0.25;
            world.spawnParticle(pal.flame, m[0], m[1], m[2], 0, dx, dy, dz, 0.55 + rnd.nextDouble() * 0.35, null, true);
        }
        // a glowing cone of fire that widens away from the mouth, so it reads from across the island
        for (int k = 0; k < 14 * ramp; k++) {
            double s = rnd.nextDouble() * 11 * ramp, spread = 0.3 + s * 0.22;
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, m[0] + f[0] * s, m[1] + f[1] * s, m[2] + f[2] * s, 1,
                    spread, spread, spread, 0, s < 4 ? pal.near : pal.far, true);
        }
        if (rnd.nextDouble() < 0.5) {
            double s = 8 + rnd.nextDouble() * 4;
            world.spawnParticle(Particle.LARGE_SMOKE, m[0] + f[0] * s, m[1] + f[1] * s, m[2] + f[2] * s, 2, 1, 1, 1, 0.02, null, true);
        }
        if (rnd.nextDouble() < 0.3) world.spawnParticle(pal == SOUL ? Particle.SOUL : Particle.LAVA, m[0], m[1], m[2], 1, 0.3, 0.3, 0.3, 0, null, true);
    }

    /** A ring of smoke blasting outwards in front of the jaws as the dragon roars. */
    private void shockwave(Dragons.Dragon d) {
        double[] m = d.mouth(), fw = d.forward();
        double[] c = {m[0] + fw[0] * 3, m[1], m[2] + fw[2] * 3};
        // the ring stands across the dragon's line of sight: spanned by "up" and the dragon's side
        double sx = -fw[2], sz = fw[0];
        for (int k = 0; k < 40; k++) {
            double a = k * Math.PI * 2 / 40, cu = Math.cos(a), cv = Math.sin(a);
            world.spawnParticle(Particle.CLOUD, c[0], c[1], c[2], 0, sx * cu + fw[0] * 0.3, cv, sz * cu + fw[2] * 0.3, 0.5, null, true);
        }
        world.spawnParticle(Particle.EXPLOSION, c[0], c[1], c[2], 1, 0, 0, 0, 0, null, true);
    }

    private void nostrils(Dragons.Dragon d) {
        double[] p = d.nose();
        world.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, p[0], p[1], p[2], 0, 0, 0.6, 0, 0.06, null, true);
        if (rnd.nextDouble() < 0.4) world.spawnParticle(Particle.SMALL_FLAME, p[0], p[1], p[2], 2, 0.2, 0.1, 0.2, 0.01, null, true);
    }

    // ------------------------------------------------------------------ fireballs

    /** Lobs a fireball out ahead of the dragon, arcing down over the void. */
    private void launch(Dragons.Dragon d, Palette pal) {
        double[] m = d.mouth(), f = d.breath((rnd.nextDouble() - 0.5) * 40);
        double speed = 1.4;
        fireballs.add(new Fireball(m, new double[]{f[0] * speed, f[1] * speed + 0.5, f[2] * speed}, 22 + rnd.nextInt(8), pal));
        world.playSound(new Location(world, m[0], m[1], m[2]), Sound.ENTITY_GHAST_SHOOT, 4f, 0.6f);
    }

    private void fly() {
        for (Iterator<Fireball> it = fireballs.iterator(); it.hasNext(); ) {
            Fireball b = it.next();
            for (int step = 0; step < 2; step++) {   // two ticks of motion per run, with a trail for each
                b.x += b.vx; b.y += b.vy; b.z += b.vz;
                b.vy -= 0.06;
                world.spawnParticle(b.pal.flame, b.x, b.y, b.z, 6, 0.35, 0.35, 0.35, 0.02, null, true);
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, b.x, b.y, b.z, 3, 0.3, 0.3, 0.3, 0, b.pal.near, true);
                world.spawnParticle(Particle.SMOKE, b.x, b.y, b.z, 2, 0.2, 0.2, 0.2, 0.01, null, true);
            }
            b.life -= 2;
            if (b.life > 0) continue;
            it.remove();
            world.spawnParticle(Particle.EXPLOSION_EMITTER, b.x, b.y, b.z, 1, 0, 0, 0, 0, null, true);
            world.spawnParticle(b.pal.flame, b.x, b.y, b.z, 60, 0.4, 0.4, 0.4, 0.35, null, true);
            world.spawnParticle(Particle.LAVA, b.x, b.y, b.z, 12, 1.5, 1.5, 1.5, 0, null, true);
            world.playSound(new Location(world, b.x, b.y, b.z), Sound.ENTITY_GENERIC_EXPLODE, 3f, 0.7f);
        }
    }

    // ------------------------------------------------------------------ idle

    /** Every six seconds the eyes shut for a moment (twice in a row now and then). */
    private void blink(int i, int t, List<Player> viewers) {
        Map<Location, BlockData> show = t == 0 || (t == 8 && i % 2 == 0) ? lids.get(i) : t == 4 || (t == 12 && i % 2 == 0) ? eyes.get(i) : null;
        if (show == null || show.isEmpty()) return;
        for (Player p : viewers) p.sendMultiBlockChange(show);
    }

    /** Embers streaming back off the wing tips and tail as if the dragons were flying through the wind. */
    private void trails(Dragons.Dragon d, Palette pal) {
        double[] fw = d.forward();
        for (double[] p : d.trails()) {
            world.spawnParticle(pal.ember, p[0], p[1], p[2], 0, -fw[0], -0.05, -fw[2], 0.35, null, true);
            if (rnd.nextDouble() < 0.25)
                world.spawnParticle(Particle.WHITE_ASH, p[0], p[1], p[2], 3, 0.6, 0.4, 0.6, 0, null, true);
        }
    }
}
