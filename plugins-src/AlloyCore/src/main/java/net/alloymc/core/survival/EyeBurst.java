package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/**
 * The Gods-eye activation burst: a golden blast at the caster's feet, then glowing yellow cracks race out across the
 * ground, hold for a moment and close up again. Purely cosmetic - no damage, no knockback, no real blocks placed or
 * broken. The cracks are thin block displays laid flat on whatever surface is underneath (slabs and stairs included)
 * and stop where the ground drops away or climbs, so they never hang in the air.
 */
final class EyeBurst {
    private static final int CRACKS = 7;
    private static final int GROW_TICKS = 2;        // per crack segment
    private static final int HOLD_TICKS = 30;       // after the last segment lands
    private static final int FADE_TICKS = 8;
    private static final float CORE_W = 0.06f, GLOW_W = 0.18f, THICK = 0.012f;
    private static final Color GOLD = GodsEye.GOLD, LIGHT = GodsEye.LIGHT, AMBER = GodsEye.AMBER, WHITE = GodsEye.WHITE;

    private final AlloyCore plugin;
    private final Random rnd = new Random();
    private final Set<Display> live = new HashSet<>();
    private final BlockData core = Material.YELLOW_CONCRETE.createBlockData();
    private final BlockData glow = Material.YELLOW_STAINED_GLASS.createBlockData();

    EyeBurst(AlloyCore plugin) { this.plugin = plugin; }

    /** One crack piece: a flat bar from {@code from} along {@code angle}. */
    private record Seg(Location from, double angle, float len, int delay) {}

    void play(Location at) {
        World w = at.getWorld();
        if (w == null) return;
        Location c = at.clone();
        Double top = surface(w, c.getX(), c.getY() + 0.5, c.getZ(), 3.5);
        double gy = top != null ? top : c.getY();
        Location ground = new Location(w, c.getX(), gy, c.getZ());

        blast(ground);

        if (top == null) return;   // mid-air with nothing close below: just the blast
        List<Seg> segs = new ArrayList<>();
        double start = rnd.nextDouble() * Math.PI * 2;
        for (int i = 0; i < CRACKS; i++) {
            double a = start + i * 2 * Math.PI / CRACKS + (rnd.nextDouble() - 0.5) * 0.5;
            crack(w, ground.getX(), gy, ground.getZ(), a, 4 + rnd.nextInt(3), 0.3, 0, true, segs);
        }
        int maxDelay = 0;
        for (Seg s : segs) maxDelay = Math.max(maxDelay, s.delay);
        int sealFrom = maxDelay + GROW_TICKS + HOLD_TICKS;   // everything has landed and glowed for HOLD_TICKS
        for (Seg s : segs) spawn(s, sealFrom + (maxDelay - s.delay) / 2);   // tips seal first, the centre last
        later(sealFrom - 2, () -> w.playSound(ground, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 0.7f, 0.6f));
    }

    /**
     * A jagged crack: {@code n} segments, each turning a little, starting {@code startR} out from the centre.
     * The trunk can fork once partway along.
     */
    private void crack(World w, double x, double y, double z, double a, int n, double startR, int delay, boolean trunk, List<Seg> out) {
        double px = x + Math.cos(a) * startR, pz = z + Math.sin(a) * startR, py = y;
        double heading = a;
        for (int i = 0; i < n; i++) {
            float len = (float) (0.45 + rnd.nextDouble() * 0.45) * (trunk ? 1f : 0.75f);
            heading += (rnd.nextDouble() - 0.5) * 0.9;
            // where does this piece end, and is the ground there at about the same height?
            double ex = px + Math.cos(heading) * len, ez = pz + Math.sin(heading) * len;
            Double sy = surface(w, px, py + 0.6, pz, 1.2);
            Double ey = surface(w, ex, py + 0.6, ez, 1.2);
            if (sy == null || ey == null || Math.abs(sy - py) > 0.55 || Math.abs(ey - sy) > 0.55) return;
            py = sy;
            out.add(new Seg(new Location(w, px, py, pz), heading, len, delay));
            if (trunk && i == 1 + rnd.nextInt(2)) {   // a branch peeling off sideways
                double side = heading + (rnd.nextBoolean() ? 1 : -1) * (0.5 + rnd.nextDouble() * 0.5);
                crack(w, ex, py, ez, side, 2 + rnd.nextInt(2), 0, delay + GROW_TICKS, false, out);
            }
            px = ex; pz = ez;
            delay += GROW_TICKS;
        }
    }

    /** Top of the first solid surface straight down from {@code fromY}, within {@code depth}; null if none. */
    private static Double surface(World w, double x, double fromY, double z, double depth) {
        RayTraceResult r = w.rayTraceBlocks(new Location(w, x, fromY, z), new Vector(0, -1, 0), depth, FluidCollisionMode.NEVER, true);
        if (r == null || r.getHitBlockFace() != org.bukkit.block.BlockFace.UP) return null;
        return r.getHitPosition().getY();
    }

    /** Flat bar of length {@code len} (0 while closed), width {@code wd}, lying along +X before rotation. */
    private static Transformation bar(double angle, float len, float wd, float lift) {
        Quaternionf q = new Quaternionf().rotateY((float) -angle);
        Vector3f off = q.transform(new Vector3f(0, 0, -wd / 2)).add(0, lift, 0);
        return new Transformation(off, q, new Vector3f(Math.max(len, 0.001f), THICK, Math.max(wd, 0.001f)), new Quaternionf());
    }

    private void spawn(Seg s, int close) {
        World w = s.from.getWorld();
        BlockDisplay g = display(w, s.from, glow, bar(s.angle, 0, GLOW_W, 0.008f));
        BlockDisplay c = display(w, s.from, core, bar(s.angle, 0, CORE_W, 0.014f));
        // race outward
        later(s.delay + 1, () -> {
            move(g, bar(s.angle, s.len, GLOW_W, 0.008f), GROW_TICKS);
            move(c, bar(s.angle, s.len, CORE_W, 0.014f), GROW_TICKS);
            Location tip = s.from.clone().add(Math.cos(s.angle) * s.len, 0.05, Math.sin(s.angle) * s.len);
            w.spawnParticle(Particle.DUST, tip, 2, 0.05, 0.02, 0.05, 0, new Particle.DustOptions(rnd.nextBoolean() ? GOLD : LIGHT, 0.9f));
            if (rnd.nextInt(3) == 0) w.spawnParticle(Particle.END_ROD, tip, 0, 0, 1, 0, 0.06);
        });
        // seal up: the crack narrows to nothing
        later(close, () -> {
            move(g, bar(s.angle, s.len, 0, 0.008f), FADE_TICKS);
            move(c, bar(s.angle, s.len, 0, 0.014f), FADE_TICKS);
            if (rnd.nextInt(2) == 0) w.spawnParticle(Particle.DUST, s.from.clone().add(0, 0.05, 0), 1, 0.15, 0.01, 0.15, 0,
                    new Particle.DustOptions(AMBER, 0.7f));
        });
        later(close + FADE_TICKS + 1, () -> { kill(g); kill(c); });
    }

    private BlockDisplay display(World w, Location at, BlockData data, Transformation tf) {
        Location l = at.clone();
        l.setYaw(0); l.setPitch(0);
        BlockDisplay d = w.spawn(l, BlockDisplay.class, e -> {
            e.setPersistent(false);
            e.setBlock(data);
            e.setTransformation(tf);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setShadowRadius(0);
            e.setViewRange(1.5f);
        });
        live.add(d);
        return d;
    }

    /** The blast itself: a white-gold flash, a shockwave ring skimming the ground and sparks thrown up. */
    private void blast(Location g) {
        World w = g.getWorld();
        Location mid = g.clone().add(0, 0.6, 0);
        w.spawnParticle(Particle.EXPLOSION, mid, 3, 0.4, 0.2, 0.4, 0);
        w.spawnParticle(Particle.END_ROD, mid, 40, 0.2, 0.2, 0.2, 0.35);
        w.spawnParticle(Particle.FIREWORK, mid, 25, 0.3, 0.3, 0.3, 0.15);
        w.spawnParticle(Particle.DUST_COLOR_TRANSITION, mid, 30, 0.6, 0.4, 0.6, 0, new Particle.DustTransition(WHITE, AMBER, 2.2f));
        w.playSound(g, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.PLAYERS, 0.8f, 1.35f);
        w.playSound(g, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.PLAYERS, 1f, 0.7f);
        w.playSound(g, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.PLAYERS, 0.5f, 1.6f);
        // shockwave: a gold ring that expands over the ground for a few ticks
        int[] n = {0};
        plugin.getServer().getScheduler().runTaskTimer(plugin, task -> {
            int k = n[0]++;
            double r = 0.6 + k * 0.7;
            int pts = (int) (r * 14);
            for (int i = 0; i < pts; i++) {
                double a = i * 2 * Math.PI / pts;
                w.spawnParticle(Particle.DUST_COLOR_TRANSITION, g.getX() + Math.cos(a) * r, g.getY() + 0.15, g.getZ() + Math.sin(a) * r,
                        1, 0.03, 0.02, 0.03, 0, new Particle.DustTransition(k < 2 ? WHITE : GOLD, AMBER, 1.4f - k * 0.12f));
            }
            if (k >= 5) task.cancel();
        }, 0L, 1L);
    }

    private static void move(Display d, Transformation tf, int ticks) {
        if (d == null || !d.isValid()) return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformation(tf);
    }

    private void kill(Display d) { live.remove(d); if (d.isValid()) d.remove(); }

    private void later(int ticks, Runnable r) { plugin.getServer().getScheduler().runTaskLater(plugin, r, Math.max(1, ticks)); }

    /** Plugin disable: no cracks left behind. */
    void shutdown() {
        for (Display d : live) if (d.isValid()) d.remove();
        live.clear();
    }
}
