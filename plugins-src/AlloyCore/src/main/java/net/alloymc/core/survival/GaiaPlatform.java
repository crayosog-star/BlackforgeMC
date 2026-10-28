package net.alloymc.core.survival;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.*;

/**
 * One running Potion of Gaia cloud: a particle mist grows slow-to-fast, blooms, hardens into a platform from the
 * centre outward, rises 8 blocks carrying (and slowly healing) whoever stands on it, holds, then shatters.
 * Looks come from block displays (they glide smoothly); what players stand on is an invisible barrier layer that
 * only fills air or replaceable plants (grass, flowers...) and always puts back exactly what was there.
 * Driven one tick at a time by {@link GaiaPotion}.
 */
final class GaiaPlatform {
    record Cell(int dx, int dz, double dist) {}

    // palette (see the plan's visual guide)
    static final Color BLOSSOM_WHITE = Color.fromRGB(0xFFF0FA), PETAL_PINK = Color.fromRGB(0xFFB3DE), BUBBLEGUM = Color.fromRGB(0xFF7CC8),
            LILAC = Color.fromRGB(0xE2C6FF), LAVENDER = Color.fromRGB(0xB48CFF);

    private static final float SLAB_THICKNESS = 0.4f;
    private static final int HARDEN_TICKS = 14;          // each cell thickens over 0.7 s once it starts to harden
    private static final int HEAL_EVERY = 40;            // riders heal half a heart every 2 s
    private static final double HEAL_AMOUNT = 1.0;
    private static final int SHARDS = 6;

    /** Open space for the cloud: air, or a plant the floor can swap out and put back (never liquids or fire). */
    static boolean open(Block b) {
        Material m = b.getType();
        if (m.isAir()) return true;
        return b.isReplaceable() && !b.isLiquid() && m != Material.FIRE && m != Material.SOUL_FIRE;
    }

    private final org.bukkit.plugin.Plugin plugin;
    final UUID owner;
    final World world;
    final int cx, cz, groundY;           // groundY: the first open layer above the ground under the centre
    final double cap;                    // how high it may rise (headroom), at most 8
    private final List<Cell> cells;
    private final Map<Cell, BlockDisplay> shown = new HashMap<>();
    private final Map<Cell, Integer> hardenedAt = new HashMap<>();
    private final Map<Block, BlockData> barriers = new HashMap<>();   // barrier block -> what was there before
    private final List<BlockDisplay> shards = new ArrayList<>();
    private final Map<UUID, Location> lastStep = new HashMap<>();
    private final Random rnd = new Random();
    private int tick = 0, barrierLayer = Integer.MIN_VALUE;
    private boolean bloomed, shattered, done, flared;
    private static final int BEAM_HEIGHT = 60;            // the sky beam: how far above the platform it reaches
    boolean barriersChanged;

    GaiaPlatform(org.bukkit.plugin.Plugin plugin, UUID owner, World world, int cx, int groundY, int cz, List<Cell> cells, double cap) {
        this.plugin = plugin;
        this.owner = owner; this.world = world; this.cx = cx; this.groundY = groundY; this.cz = cz;
        this.cells = cells; this.cap = cap;
    }

    boolean done() { return done; }

    /** Shattering or finished: on its way out, so it no longer counts as the owner's cloud or blocks a new one. */
    boolean fading() { return done || BloomCurve.phase(tick / 20.0).ordinal() >= BloomCurve.Phase.FADE.ordinal(); }
    Map<Block, BlockData> barriers() { return barriers; }
    Location centre() { return new Location(world, cx + 0.5, groundY, cz + 0.5); }

    // ------------------------------------------------------------------ the tick

    void tick() {
        if (done) return;
        double t = tick++ / 20.0;
        BloomCurve.Phase phase = BloomCurve.phase(t);
        double r = BloomCurve.radius(t);
        switch (phase) {
            case GROW -> mistEffects(t, r, 1.0);
            case BLOOM -> {
                mistEffects(t, r, 1.0);
                if (!bloomed && t >= BloomCurve.BLOOM_PEAK - 0.06) { bloomed = true; bloomBurst(); }
            }
            case HARDEN -> harden(t);
            case RISE, HOLD -> {
                harden(t);   // finishes any cell still thickening
                double h = BloomCurve.height(t, cap);
                moveTo(h);
                if (phase == BloomCurve.Phase.RISE) { carryRiders(t, h); riseEffects(h); }
                else holdEffects(h);
                orbitShards(h);
                if (!flared) { flared = true; flare(h); }
                spotlight(h);
                if (tick % HEAL_EVERY == 0) healRiders(h);
                stepSparkles(h);
            }
            case FADE -> { if (!shattered) { shattered = true; shatter(BloomCurve.height(t, cap)); } }
            case DONE -> remove();
        }
    }

    // ------------------------------------------------------------------ mist + bloom (particles only)

    /** Pure particle fog filling the disc, thinning by {@code density} as the platform hardens. */
    private void mistEffects(double t, double r, double density) {
        int fill = (int) Math.min(40, Math.max(4, Math.PI * r * r * 1.6 * density));
        Particle.DustTransition fog = new Particle.DustTransition(PETAL_PINK, LILAC, 1.6f);
        for (int i = 0; i < fill; i++) {
            double a = rnd.nextDouble() * 2 * Math.PI, d = Math.sqrt(rnd.nextDouble()) * r;
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, cx + 0.5 + d * Math.cos(a), groundY + 0.1 + rnd.nextDouble() * 0.5,
                    cz + 0.5 + d * Math.sin(a), 1, 0, 0, 0, 0, fog);
        }
        ringParticles(r, 1 + (int) (BloomCurve.growProgress(t) * 3));   // the edge gets denser as it speeds up
        if (tick % 3 == 0) world.spawnParticle(Particle.SPORE_BLOSSOM_AIR, centre().add(0, 0.3, 0), 2, r * 0.5, 0.1, r * 0.5, 0);
        if (tick % 4 == 0 && t < BloomCurve.GROW_END) {
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

    // ------------------------------------------------------------------ harden

    /**
     * Cells solidify from the centre outward: each appears paper-thin and dim, then thickens and brightens over a
     * second, while the fog around it thins. Nothing can be stood on until the rise begins.
     */
    private void harden(double t) {
        double hard = BloomCurve.hardness(t);
        if (hard < 1) mistEffects(t, BloomCurve.RADIUS, 1 - hard);
        for (Cell c : cells) {
            if (shown.containsKey(c) || !BloomCurve.cellHard(t, c.dist())) continue;
            BlockDisplay d = spawnDisplay(c);
            shown.put(c, d);
            hardenedAt.put(c, tick);
            Location at = new Location(world, cx + c.dx() + 0.5, groundY + 0.2, cz + c.dz() + 0.5);
            world.spawnParticle(Particle.DUST, at, 3, 0.25, 0.1, 0.25, 0, new Particle.DustOptions(BLOSSOM_WHITE, 1.0f));
            if (rnd.nextInt(3) == 0)
                world.playSound(at, Sound.BLOCK_AMETHYST_CLUSTER_PLACE, SoundCategory.PLAYERS, 0.5f, (float) (0.8 + hard * 0.9));
        }
        // brighten as each cell firms up (display brightness can't be interpolated, so step it)
        for (Map.Entry<Cell, BlockDisplay> e : shown.entrySet()) {
            Integer since = hardenedAt.get(e.getKey());
            if (since == null) continue;
            int age = tick - since;
            if (age > HARDEN_TICKS) { hardenedAt.remove(e.getKey()); continue; }
            if (age % 4 == 0 && e.getValue().isValid()) {
                int light = Math.min(15, 4 + age * 11 / HARDEN_TICKS);
                e.getValue().setBrightness(new Display.Brightness(light, light));
            }
        }
    }

    private BlockDisplay spawnDisplay(Cell c) {
        boolean rim = c.dist() > BloomCurve.RADIUS - 1.0;
        Location at = new Location(world, cx + c.dx(), groundY + 0.01, cz + c.dz());
        BlockDisplay d = world.spawn(at, BlockDisplay.class, e -> {
            e.setBlock((rim ? Material.AMETHYST_BLOCK : Material.PINK_STAINED_GLASS).createBlockData());
            e.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(1f, 0.02f, 1f), new AxisAngle4f()));
            e.setBrightness(new Display.Brightness(4, 4));
            e.setPersistent(false);   // never saved: a crash can't leave displays behind
            e.setTeleportDuration(1);
            if (rim) { e.setGlowing(true); e.setGlowColorOverride(LAVENDER); }
        });
        // thicken from paper-thin to a slab over a second
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!d.isValid()) return;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(HARDEN_TICKS);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(1f, SLAB_THICKNESS, 1f), new AxisAngle4f()));
        });
        return d;
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
            if (!open(b)) continue;   // only ever fills air or a plant, and remembers which
            barriers.put(b, b.getBlockData().clone());
            b.setType(Material.BARRIER, false);
        }
        barriersChanged = true;
    }

    private boolean onPlatform(Player p, double h, double below, double above) {
        double dx = p.getX() - (cx + 0.5), dz = p.getZ() - (cz + 0.5);
        if (dx * dx + dz * dz > (BloomCurve.RADIUS + 0.4) * (BloomCurve.RADIUS + 0.4)) return false;
        double surface = groundY + h;
        return p.getY() >= surface - below && p.getY() <= surface + above;
    }

    /**
     * Riders move up with the platform. Each tick their upward speed is set to exactly the platform's speed, plus a
     * small correction toward the visible surface (half the gap, capped), so they stay glued to it instead of
     * drifting above it. Only someone who has sunk well into it (lag) is snapped back up. Players already well
     * above the surface (mid-jump) are left alone.
     */
    private void carryRiders(double t, double h) {
        double perTick = BloomCurve.riseSpeed(t, cap) / 20.0;
        double surface = groundY + h;
        for (Player p : world.getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR || p.isFlying() || !onPlatform(p, h, 1.0, 0.6)) continue;
            double gap = surface - p.getY();   // > 0: below the surface, < 0: above it
            if (gap > 0.6) {
                Location to = p.getLocation(); to.setY(surface + 0.01);
                p.teleport(to);
                gap = 0;
            }
            double vy = perTick + Math.max(-0.06, Math.min(0.15, gap * 0.5));
            Vector v = p.getVelocity();
            p.setVelocity(new Vector(v.getX(), vy, v.getZ()));
            p.setFallDistance(0f);
        }
    }

    /** Slight healing while standing on the platform: half a heart every 2 s. */
    private void healRiders(double h) {
        for (Player p : world.getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR || !onPlatform(p, h, 0.6, 1.2) || p.isDead()) continue;
            var max = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            if (max == null || p.getHealth() >= max.getValue()) continue;
            p.heal(HEAL_AMOUNT, EntityRegainHealthEvent.RegainReason.MAGIC);
            p.getWorld().spawnParticle(Particle.HEART, p.getLocation().add(0, 2.1, 0), 1, 0.2, 0.1, 0.2, 0);
            p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.3f, 1.8f);
        }
    }

    /** Each footstep on the platform leaves a tiny pink glint. */
    private void stepSparkles(double h) {
        for (Player p : world.getPlayers()) {
            if (!onPlatform(p, h, 0.3, 0.6)) { lastStep.remove(p.getUniqueId()); continue; }
            Location now = p.getLocation(), before = lastStep.put(p.getUniqueId(), now);
            if (before != null && before.getWorld() == now.getWorld() && before.distanceSquared(now) > 0.04)
                world.spawnParticle(Particle.DUST, now.clone().add(0, 0.05, 0), 2, 0.15, 0, 0.15, 0, new Particle.DustOptions(BUBBLEGUM, 0.8f));
        }
    }

    private void riseEffects(double h) {
        // a column of fog hangs under the platform as it climbs, thinning toward the ground
        for (int i = 0; i < 6; i++) {
            double a = rnd.nextDouble() * 2 * Math.PI, d = Math.sqrt(rnd.nextDouble()) * (BloomCurve.RADIUS - 0.5);
            double y = groundY + rnd.nextDouble() * Math.max(0.5, h - 0.4);
            world.spawnParticle(Particle.DUST, cx + 0.5 + d * Math.cos(a), y, cz + 0.5 + d * Math.sin(a), 1, 0, 0, 0, 0,
                    new Particle.DustOptions(rnd.nextBoolean() ? LILAC : PETAL_PINK, 1.4f));
        }
        if (tick % 2 == 0) {
            double a = rnd.nextDouble() * 2 * Math.PI;
            world.spawnParticle(Particle.CHERRY_LEAVES, cx + 0.5 + BloomCurve.RADIUS * Math.cos(a), groundY + h, cz + 0.5 + BloomCurve.RADIUS * Math.sin(a), 2, 0.2, 0.1, 0.2, 0);
        }
        if (tick % 10 == 0) world.playSound(centre().add(0, h, 0), Sound.BLOCK_BEACON_AMBIENT, SoundCategory.PLAYERS, 1.0f, (float) (0.8 + h / 8.0));
    }

    private void holdEffects(double h) {
        // petal waterfall pouring off the rim
        for (int i = 0; i < 3; i++) {
            double a = rnd.nextDouble() * 2 * Math.PI;
            world.spawnParticle(Particle.CHERRY_LEAVES, cx + 0.5 + (BloomCurve.RADIUS + 0.2) * Math.cos(a), groundY + h - 0.2,
                    cz + 0.5 + (BloomCurve.RADIUS + 0.2) * Math.sin(a), 1, 0.05, 0.05, 0.05, 0);
        }
        if (tick % 6 == 0) world.spawnParticle(Particle.SPORE_BLOSSOM_AIR, cx + 0.5, groundY + h + 0.4, cz + 0.5, 4, 2.0, 0.2, 2.0, 0);
        // a soft pink pulse ripples across the surface once a second
        int beat = tick % 20;
        if (beat < 4) {
            double r = 0.8 + beat * 0.8;
            int points = (int) (r * 10);
            Particle.DustOptions pink = new Particle.DustOptions(BUBBLEGUM, 1.0f);
            for (int i = 0; i < points; i++) {
                double a = 2 * Math.PI * i / points;
                world.spawnParticle(Particle.DUST, cx + 0.5 + r * Math.cos(a), groundY + h + 0.05, cz + 0.5 + r * Math.sin(a), 1, 0, 0, 0, 0, pink);
            }
        }
        if (tick % 40 == 0) world.playSound(centre().add(0, h, 0), Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.5f, 1.6f);
    }

    /** Small amethyst shards orbit the rim from the rise on, spinning faster the higher it goes. */
    private void orbitShards(double h) {
        if (shards.isEmpty())
            for (int i = 0; i < SHARDS; i++)
                shards.add(world.spawn(centre(), BlockDisplay.class, d -> {
                    d.setBlock(Material.AMETHYST_CLUSTER.createBlockData());
                    d.setTransformation(new Transformation(new Vector3f(-0.2f, 0, -0.2f), new AxisAngle4f(), new Vector3f(0.4f, 0.4f, 0.4f), new AxisAngle4f()));
                    d.setBrightness(new Display.Brightness(15, 15));
                    d.setPersistent(false);
                    d.setTeleportDuration(1);
                    d.setGlowing(true);
                    d.setGlowColorOverride(PETAL_PINK);
                }));
        double speed = 0.04 + 0.02 * Math.min(h, BloomCurve.MAX_RISE);
        for (int i = 0; i < shards.size(); i++) {
            BlockDisplay d = shards.get(i);
            if (!d.isValid()) continue;
            double a = tick * speed + i * 2 * Math.PI / SHARDS;
            double bob = Math.sin(tick * 0.15 + i) * 0.25;
            d.teleport(new Location(world, cx + 0.5 + (BloomCurve.RADIUS + 0.9) * Math.cos(a), groundY + h + 0.4 + bob, cz + 0.5 + (BloomCurve.RADIUS + 0.9) * Math.sin(a)));
        }
    }

    // ------------------------------------------------------------------ the downside: everyone can see you

    /**
     * The moment it starts rising: a burst of light and a loud call that carries ~64 blocks, so everyone nearby knows
     * someone just took the high ground.
     */
    private void flare(double h) {
        Location c = centre().add(0, h + 0.5, 0);
        for (int i = 0; i < 64; i++) {
            double a = 2 * Math.PI * i / 64;
            Vector out = new Vector(Math.cos(a), 0.15, Math.sin(a));
            world.spawnParticle(Particle.END_ROD, c.getX(), c.getY(), c.getZ(), 0, out.getX(), out.getY(), out.getZ(), 0.6, null, true);
        }
        world.spawnParticle(Particle.DUST, c.getX(), c.getY(), c.getZ(), 60, 1.5, 0.6, 1.5, 0, new Particle.DustOptions(BLOSSOM_WHITE, 2.5f), true);
        world.playSound(c, Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 4.0f, 1.3f);   // volume 4 carries ~64 blocks
        world.playSound(c, Sound.ITEM_TRIDENT_THUNDER, SoundCategory.PLAYERS, 1.5f, 1.8f);
    }

    /**
     * While it's up: a bright beam into the sky, two sweeping searchlights, a halo on the rim, and every rider
     * outlined (Glowing, visible through walls). Particles are forced, so they show from far beyond normal range.
     * It's the trade for the high ground: you can see everyone, and everyone can see you.
     */
    private void spotlight(double h) {
        double top = groundY + h;
        // the sky beam: a twisting column of light rising out of the centre
        if (tick % 2 == 0)
            for (int i = 0; i < BEAM_HEIGHT; i += 2) {
                double a = tick * 0.25 + i * 0.35, r = 0.35;
                world.spawnParticle(Particle.END_ROD, cx + 0.5 + r * Math.cos(a), top + 0.5 + i, cz + 0.5 + r * Math.sin(a), 1, 0, 0, 0, 0, null, true);
                if (i % 6 == 0)
                    world.spawnParticle(Particle.DUST, cx + 0.5, top + 0.5 + i, cz + 0.5, 1, 0.15, 0.3, 0.15, 0, new Particle.DustOptions(PETAL_PINK, 2.0f), true);
            }
        // two searchlights sweeping out and up from the rim, slowly turning
        if (tick % 2 == 1)
            for (int beam = 0; beam < 2; beam++) {
                double a = tick * 0.05 + beam * Math.PI;
                double ox = cx + 0.5 + BloomCurve.RADIUS * Math.cos(a), oz = cz + 0.5 + BloomCurve.RADIUS * Math.sin(a);
                for (int k = 1; k <= 14; k++)
                    world.spawnParticle(Particle.END_ROD, ox + k * 1.1 * Math.cos(a), top + 0.3 + k * 0.9, oz + k * 1.1 * Math.sin(a), 1, 0, 0, 0, 0, null, true);
            }
        // a bright halo around the rim
        if (tick % 4 == 0)
            for (int i = 0; i < 24; i++) {
                double a = 2 * Math.PI * i / 24 + tick * 0.02;
                world.spawnParticle(Particle.DUST, cx + 0.5 + (BloomCurve.RADIUS + 0.3) * Math.cos(a), top + 0.15, cz + 0.5 + (BloomCurve.RADIUS + 0.3) * Math.sin(a),
                        1, 0, 0, 0, 0, new Particle.DustOptions(BLOSSOM_WHITE, 1.4f), true);
            }
        // riders light up: an outline everyone can see, even through walls
        if (tick % 10 == 0)
            for (Player p : world.getPlayers()) {
                if (p.getGameMode() == GameMode.SPECTATOR || !onPlatform(p, h, 0.8, 2.5)) continue;
                p.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 30, 0, false, false, false));
                p.sendActionBar(net.kyori.adventure.text.Component.text("✦ You're lit up — everyone can see you up here",
                        net.kyori.adventure.text.format.TextColor.color(0xFFB3DE)));
            }
    }

    // ------------------------------------------------------------------ shatter + cleanup

    /**
     * The end: the floor goes, everyone on it floats down on Slow Falling, and the platform cracks into pieces that
     * tumble and shrink as they drift down with the petals.
     */
    private void shatter(double h) {
        for (Player p : world.getPlayers()) {
            if (!onPlatform(p, h, 1.5, 4.0)) continue;
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 120, 0, true, true, true));
            p.setFallDistance(0f);
        }
        clearBarriers();
        Location c = centre().add(0, h, 0);
        world.playSound(c, Sound.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 1.2f, 0.9f);
        world.playSound(c, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, SoundCategory.PLAYERS, 1.0f, 1.2f);
        world.spawnParticle(Particle.CHERRY_LEAVES, c, 40, BloomCurve.RADIUS * 0.6, 0.3, BloomCurve.RADIUS * 0.6, 0);
        world.spawnParticle(Particle.DUST, c, 30, BloomCurve.RADIUS * 0.6, 0.2, BloomCurve.RADIUS * 0.6, 0, new Particle.DustOptions(PETAL_PINK, 1.4f));
        int ticks = (int) ((BloomCurve.FADE_END - BloomCurve.HOLD_END) * 20) - 2;
        for (BlockDisplay d : shown.values()) {
            if (!d.isValid()) continue;
            float tilt = (float) Math.toRadians(rnd.nextInt(120) - 60);
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(ticks);
            d.setTransformation(new Transformation(new Vector3f(0.3f, -2.5f - rnd.nextFloat() * 2f, 0.3f),
                    new AxisAngle4f(tilt, new Vector3f(rnd.nextFloat(), 1f, rnd.nextFloat()).normalize()), new Vector3f(0.35f, 0.15f, 0.35f), new AxisAngle4f()));
        }
        for (BlockDisplay d : shards) if (d.isValid()) d.remove();
        shards.clear();
    }

    private void clearBarriers() {
        for (Map.Entry<Block, BlockData> e : barriers.entrySet())
            if (e.getKey().getType() == Material.BARRIER) e.getKey().setBlockData(e.getValue(), false);   // put back exactly what was there
        if (!barriers.isEmpty()) barriersChanged = true;
        barriers.clear();
    }

    /** Take everything down now: displays, shards and every barrier this cloud placed. Safe to call twice. */
    void remove() {
        for (BlockDisplay d : shown.values()) if (d.isValid()) d.remove();
        shown.clear();
        for (BlockDisplay d : shards) if (d.isValid()) d.remove();
        shards.clear();
        clearBarriers();
        done = true;
    }
}
