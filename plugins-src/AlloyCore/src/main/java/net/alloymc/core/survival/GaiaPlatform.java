package net.alloymc.core.survival;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;

/**
 * One running Potion of Gaia cloud: grows, blooms, rises 8 blocks carrying whoever stands on it, holds, fades.
 * Looks come from block displays (they glide smoothly); what players stand on is an invisible barrier layer
 * that only ever fills air and is always removed again. Driven one tick at a time by {@link GaiaPotion}.
 */
final class GaiaPlatform {
    record Cell(int dx, int dz, double dist) {}

    // palette (see the plan's visual guide)
    static final Color BLOSSOM_WHITE = Color.fromRGB(0xFFF0FA), PETAL_PINK = Color.fromRGB(0xFFB3DE), BUBBLEGUM = Color.fromRGB(0xFF7CC8),
            LILAC = Color.fromRGB(0xE2C6FF), LAVENDER = Color.fromRGB(0xB48CFF);

    private static final float MIST_THICKNESS = 0.12f, SLAB_THICKNESS = 0.4f;

    final UUID owner;
    final World world;
    final int cx, cz, groundY;           // groundY: the first air layer above the ground under the centre
    final double cap;                    // how high it may rise (headroom), at most 8
    private final List<Cell> cells;
    private final Map<Cell, BlockDisplay> shown = new HashMap<>();
    private final Set<Block> barriers = new HashSet<>();
    private final Random rnd = new Random();
    private int tick = 0, barrierLayer = Integer.MIN_VALUE;
    private boolean bloomed, solid, fading, done;
    boolean barriersChanged;

    GaiaPlatform(UUID owner, World world, int cx, int groundY, int cz, List<Cell> cells, double cap) {
        this.owner = owner; this.world = world; this.cx = cx; this.groundY = groundY; this.cz = cz;
        this.cells = cells; this.cap = cap;
    }

    boolean done() { return done; }
    Set<Block> barriers() { return barriers; }
    Location centre() { return new Location(world, cx + 0.5, groundY, cz + 0.5); }

    // ------------------------------------------------------------------ the tick

    void tick() {
        if (done) return;
        double t = tick++ / 20.0;
        BloomCurve.Phase phase = BloomCurve.phase(t);
        double r = BloomCurve.radius(t);
        switch (phase) {
            case GROW -> { growMist(r); growEffects(t, r); }
            case BLOOM -> {
                growMist(Math.min(r, BloomCurve.RADIUS));
                if (!bloomed && t >= BloomCurve.BLOOM_PEAK - 0.06) { bloomed = true; bloomBurst(); }
                ringParticles(r, 3);
            }
            case RISE, HOLD -> {
                if (!solid) { solid = true; thicken(); }
                double h = BloomCurve.height(t, cap);
                moveTo(h);
                if (phase == BloomCurve.Phase.RISE) { carryRiders(t, h); riseEffects(h); }
                else if (tick % 10 == 0) holdEffects(h);
            }
            case FADE -> {
                double h = BloomCurve.height(t, cap);
                if (!fading) { fading = true; softLanding(h); }
                shrink(r, h);
            }
            case DONE -> remove();
        }
    }

    // ------------------------------------------------------------------ grow + bloom

    private void growMist(double r) {
        for (Cell c : cells) if (c.dist() <= r && !shown.containsKey(c)) shown.put(c, spawnDisplay(c));
    }

    private BlockDisplay spawnDisplay(Cell c) {
        boolean rim = c.dist() > BloomCurve.RADIUS - 1.0;
        Location at = new Location(world, cx + c.dx(), groundY + 0.01, cz + c.dz());
        return world.spawn(at, BlockDisplay.class, d -> {
            d.setBlock((rim ? Material.AMETHYST_BLOCK : Material.PINK_STAINED_GLASS).createBlockData());
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(1f, MIST_THICKNESS, 1f), new AxisAngle4f()));
            d.setBrightness(new Display.Brightness(15, 15));
            d.setPersistent(false);   // never saved: a crash can't leave displays behind
            d.setTeleportDuration(1);
            if (rim) { d.setGlowing(true); d.setGlowColorOverride(LAVENDER); }
        });
    }

    private void growEffects(double t, double r) {
        ringParticles(r, 1 + (int) (BloomCurve.growProgress(t) * 3));   // denser as it speeds up
        if (tick % 3 == 0) world.spawnParticle(Particle.SPORE_BLOSSOM_AIR, centre().add(0, 0.3, 0), 2, r * 0.5, 0.1, r * 0.5, 0);
        if (tick % 4 == 0) {
            float pitch = (float) (0.5 + 1.5 * BloomCurve.growProgress(t));   // climbs with the speed-up
            world.playSound(centre(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 1.0f, pitch);
        }
    }

    private void ringParticles(double r, int density) {
        int points = Math.max(6, (int) (2 * Math.PI * r * 2 * density));
        Particle.DustTransition dust = new Particle.DustTransition(PETAL_PINK, LAVENDER, 1.1f);
        for (int i = 0; i < points; i++) {
            double a = 2 * Math.PI * i / points;
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, cx + 0.5 + r * Math.cos(a), groundY + 0.15, cz + 0.5 + r * Math.sin(a), 1, 0, 0, 0, 0, dust);
        }
    }

    private void bloomBurst() {
        Location c = centre().add(0, 0.4, 0);
        for (int i = 0; i < 48; i++) {
            double a = 2 * Math.PI * i / 48;
            Vector out = new Vector(Math.cos(a), 0.08, Math.sin(a));
            world.spawnParticle(Particle.END_ROD, c.clone().add(out.clone().multiply(BloomCurve.RADIUS)), 0, out.getX(), out.getY(), out.getZ(), 0.35);
            world.spawnParticle(Particle.CHERRY_LEAVES, c.clone().add(out.clone().multiply(BloomCurve.BLOOM_RADIUS)), 1, 0.2, 0.2, 0.2, 0);
        }
        world.spawnParticle(Particle.FIREWORK, c, 40, 0.4, 0.3, 0.4, 0.15);
        world.spawnParticle(Particle.DUST, c, 30, 1.0, 0.3, 1.0, 0, new Particle.DustOptions(BUBBLEGUM, 1.6f));
        world.spawnParticle(Particle.DUST, c, 20, 0.5, 0.3, 0.5, 0, new Particle.DustOptions(BLOSSOM_WHITE, 1.8f));
        world.playSound(c, Sound.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 1.2f, 1.4f);
        world.playSound(c, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 1.2f, 1.0f);
    }

    /** The mist turns into a solid-looking slab as the rise begins. */
    private void thicken() {
        for (BlockDisplay d : shown.values()) {
            if (!d.isValid()) continue;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(6);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(1f, SLAB_THICKNESS, 1f), new AxisAngle4f()));
        }
    }

    // ------------------------------------------------------------------ rise + hold

    /** Glide the displays to height h and keep the invisible floor under the surface. */
    private void moveTo(double h) {
        double surface = groundY + h;
        for (Map.Entry<Cell, BlockDisplay> e : shown.entrySet()) {
            BlockDisplay d = e.getValue();
            if (d.isValid()) d.teleport(new Location(world, cx + e.getKey().dx(), surface - SLAB_THICKNESS, cz + e.getKey().dz()));
        }
        int layer = (int) Math.floor(h) - 1;   // the block whose top is at or just below the surface
        if (layer < 0 || layer == barrierLayer) return;
        clearBarriers();
        barrierLayer = layer;
        for (Cell c : cells) {
            Block b = world.getBlockAt(cx + c.dx(), groundY + layer, cz + c.dz());
            if (!b.getType().isAir()) continue;   // only ever fills air
            b.setType(Material.BARRIER, false);
            barriers.add(b);
        }
        barriersChanged = true;
    }

    private boolean onPlatform(Player p, double h, double below, double above) {
        double dx = p.getX() - (cx + 0.5), dz = p.getZ() - (cz + 0.5);
        if (dx * dx + dz * dz > (BloomCurve.RADIUS + 0.4) * (BloomCurve.RADIUS + 0.4)) return false;
        double surface = groundY + h;
        return p.getY() >= surface - below && p.getY() <= surface + above;
    }

    /** Riders move up with the platform: a velocity matched to its speed, and never below its surface. */
    private void carryRiders(double t, double h) {
        double perTick = BloomCurve.riseSpeed(t, cap) / 20.0;
        double surface = groundY + h;
        for (Player p : world.getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR || p.isFlying() || !onPlatform(p, h, 1.0, 1.4)) continue;
            if (p.getY() < surface - 0.05) {
                Location to = p.getLocation(); to.setY(surface + 0.01);
                p.teleport(to);
            }
            Vector v = p.getVelocity();
            p.setVelocity(new Vector(v.getX(), Math.max(v.getY(), perTick + 0.08), v.getZ()));
            p.setFallDistance(0f);
        }
    }

    private void riseEffects(double h) {
        if (tick % 2 == 0) {
            double a = rnd.nextDouble() * 2 * Math.PI;
            world.spawnParticle(Particle.CHERRY_LEAVES, cx + 0.5 + BloomCurve.RADIUS * Math.cos(a), groundY + h, cz + 0.5 + BloomCurve.RADIUS * Math.sin(a), 2, 0.2, 0.1, 0.2, 0);
            world.spawnParticle(Particle.DUST, cx + 0.5, groundY + h - 0.6, cz + 0.5, 6, 1.4, 0.1, 1.4, 0, new Particle.DustOptions(LILAC, 1.2f));
        }
        if (tick % 10 == 0) world.playSound(centre().add(0, h, 0), Sound.BLOCK_BEACON_AMBIENT, SoundCategory.PLAYERS, 1.0f, (float) (0.8 + h / 8.0));
    }

    private void holdEffects(double h) {
        world.spawnParticle(Particle.SPORE_BLOSSOM_AIR, cx + 0.5, groundY + h + 0.4, cz + 0.5, 4, 2.0, 0.2, 2.0, 0);
        if (tick % 40 == 0) world.playSound(centre().add(0, h, 0), Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.5f, 1.6f);
    }

    // ------------------------------------------------------------------ fade + cleanup

    /** Everyone on or just above the cloud floats down: no fall damage from 8 blocks up. */
    private void softLanding(double h) {
        for (Player p : world.getPlayers()) {
            if (!onPlatform(p, h, 1.5, 4.0)) continue;
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 120, 0, true, true, true));
            p.setFallDistance(0f);
        }
        world.playSound(centre().add(0, h, 0), Sound.BLOCK_AMETHYST_CLUSTER_BREAK, SoundCategory.PLAYERS, 1.0f, 1.2f);
    }

    /** Remove the outer cells first as the radius shrinks; their floor goes with them. */
    private void shrink(double r, double h) {
        Iterator<Map.Entry<Cell, BlockDisplay>> it = shown.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Cell, BlockDisplay> e = it.next();
            if (e.getKey().dist() <= r) continue;
            Location at = new Location(world, cx + e.getKey().dx() + 0.5, groundY + h, cz + e.getKey().dz() + 0.5);
            world.spawnParticle(Particle.CLOUD, at, 3, 0.25, 0.1, 0.25, 0.01);
            world.spawnParticle(Particle.DUST, at, 2, 0.25, 0.1, 0.25, 0, new Particle.DustOptions(PETAL_PINK, 1.2f));
            e.getValue().remove();
            it.remove();
            if (barrierLayer >= 0) {
                Block b = world.getBlockAt(cx + e.getKey().dx(), groundY + barrierLayer, cz + e.getKey().dz());
                if (barriers.remove(b) && b.getType() == Material.BARRIER) b.setType(Material.AIR, false);
                barriersChanged = true;
            }
        }
        if (tick % 6 == 0) world.playSound(centre().add(0, h, 0), Sound.BLOCK_AMETHYST_CLUSTER_BREAK, SoundCategory.PLAYERS, 0.6f, (float) (0.6 + r / 5.0));
    }

    private void clearBarriers() {
        for (Block b : barriers) if (b.getType() == Material.BARRIER) b.setType(Material.AIR, false);
        if (!barriers.isEmpty()) barriersChanged = true;
        barriers.clear();
    }

    /** Take everything down now: displays and every barrier this cloud placed. Safe to call twice. */
    void remove() {
        for (BlockDisplay d : shown.values()) if (d.isValid()) d.remove();
        shown.clear();
        clearBarriers();
        done = true;
    }
}
