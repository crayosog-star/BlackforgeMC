package net.alloymc.core.hub;

import com.destroystokyo.paper.ParticleBuilder;
import net.alloymc.core.Ui;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * "The Forge Awakens" - the first-join welcome show. 6 seconds, staged around the whole hub island:
 * <ul>
 *   <li>16 fire pillars erupt in a sweep around the island's rim</li>
 *   <li>a molten wave runs across the BLACKFORGE sign (yellow → orange → red), then the whole sign flares at the finale</li>
 *   <li>five waves of real fireworks (44 rockets) launch in a ring around the island</li>
 *   <li>a giant spinning halo of fire circles the sky above the island</li>
 *   <li>embers rain down and the action bar reads "✦ The forge burns for you, &lt;name&gt; ✦"</li>
 * </ul>
 * Only the joining player sees it: particles have that player as their only receiver, the fireworks are hidden
 * from everyone else, sounds are player-only, and the glowing sign is a client-side block change that is put
 * back when the show ends (a relog or chunk reload also restores it).
 * Real fireworks stay within ~30 blocks (horizontally) of spawn because servers only send fireworks to
 * players within the "misc" entity-tracking range (32 by default); the particle effects go further out.
 */
public final class WelcomeShow {

    // vibrant yellow → red palette
    private static final Color WHITE_HOT = Color.fromRGB(0xFFFBE0), YELLOW = Color.fromRGB(0xFFF04D),
            GOLD = Color.fromRGB(0xFFC300), AMBER = Color.fromRGB(0xFF9500), ORANGE = Color.fromRGB(0xFF5E00),
            RED = Color.fromRGB(0xFF1A1A), CRIMSON = Color.fromRGB(0xC8102E);
    private static final Color[] PALETTE = {YELLOW, GOLD, AMBER, ORANGE, RED, CRIMSON};

    private static final int LENGTH = 120;                     // 6 seconds
    private static final double RIM = 46, HALO_R = 42, HALO_UP = 28, FIREWORK_UP = 14;
    private static final Set<UUID> RUNNING = new HashSet<>();  // main thread only

    /** Starts the show for {@code p} (must be on the hub). Returns false if one is already playing for them. */
    public static boolean play(Plugin plugin, Player p) {
        if (!p.isOnline() || !RUNNING.add(p.getUniqueId())) return false;
        WelcomeShow show = new WelcomeShow(plugin, p);
        Bukkit.getScheduler().runTaskTimer(plugin, show::tick, 0L, 1L);
        return true;
    }

    private final Plugin plugin;
    private final Player p;
    private final World world;
    private final Vector center;        // island centre at plaza level
    private final double front;         // angle (around the island) the player is facing, so the show opens in view
    private final List<int[]> sign;     // BLACKFORGE letter blocks: x, y, z
    private final int[] signState;      // what each letter block currently shows the player (-1 = real block)
    private final int signMinX, signMaxX;
    private int t = 0;

    private static final Material[] HEAT = {     // molten stages a letter block passes through
            Material.OCHRE_FROGLIGHT, Material.SHROOMLIGHT, Material.MAGMA_BLOCK, Material.REDSTONE_BLOCK};

    private WelcomeShow(Plugin plugin, Player p) {
        this.plugin = plugin;
        this.p = p;
        this.world = p.getWorld();
        this.center = new Vector(HubLayout.SPAWN_X, HubLayout.Y + 1, HubLayout.SPAWN_Z);
        Vector look = p.getLocation().getDirection();
        this.front = Math.atan2(look.getZ(), look.getX());
        this.sign = HubBuilder.signFront();
        this.signState = new int[sign.size()];
        java.util.Arrays.fill(signState, -1);
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (int[] b : sign) { lo = Math.min(lo, b[0]); hi = Math.max(hi, b[0]); }
        this.signMinX = lo;
        this.signMaxX = Math.max(lo + 1, hi);
    }

    private void tick(BukkitTask task) {
        if (t > LENGTH || !p.isOnline() || p.getWorld() != world) { stop(task); return; }
        try {
            frame();
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Welcome show for " + p.getName() + " stopped: " + ex);
            stop(task);
            return;
        }
        t++;
    }

    private void stop(BukkitTask task) {
        task.cancel();
        RUNNING.remove(p.getUniqueId());
        if (p.isOnline() && p.getWorld() == world) restoreSign();
    }

    private void frame() {
        if (t == 0) {
            soundFrom(center, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1f, 0.6f);
            soundFrom(center, Sound.ENTITY_BLAZE_SHOOT, 1f, 0.5f);
        }
        if (t < 64 && t % 2 == 0) pillars();
        sign();
        fireworks();
        if (t >= 38 && t < 112 && t % 2 == 0) halo();
        if (t == 88) {
            soundFrom(center, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 0.7f);
            p.sendActionBar(Ui.mm("<gradient:#fff04d:#ff9500:#ff1a1a>✦ The forge burns for you, " + p.getName() + " ✦</gradient>"));
        }
        if (t >= 86) embers();
    }

    // ------------------------------------------------------------------ fire pillars around the rim

    private void pillars() {
        for (int i = 0; i < 16; i++) {
            int age = t - i;                                     // erupt one per tick...
            if (age < 0) continue;
            double a = front + (i % 2 == 0 ? 1 : -1) * Math.PI * ((i + 1) / 2) / 8.0;   // ...fanning both ways round from the front
            Vector base = ring(center, RIM, a, -2);
            double h = age < 10 ? 26 * easeOut((age + 1) / 10.0) : t < 50 ? 26 : 26 * Math.max(0, 1 - (t - 50) / 14.0);
            if (age == 0) {
                soundFrom(base, Sound.ITEM_FIRECHARGE_USE, 0.6f, (float) rnd(0.6, 0.9));
                fx(Particle.LAVA, base, 14, 1.2, 0.5, 1.2, 0);
            }
            for (double y = 0; y < h; y += 0.9) {
                double s = y / 26;                                   // yellow at the base, red at the top
                Color from = mix(YELLOW, ORANGE, s), to = mix(AMBER, CRIMSON, s);
                double wob = 0.5 + s * 0.6;
                fx(Particle.DUST_COLOR_TRANSITION, base.clone().add(new Vector(rnd(-wob, wob), y, rnd(-wob, wob))), 1, 0, 0, 0, 0,
                        new Particle.DustTransition(from, to, 3.6f));
            }
            if (h > 2) {
                Vector top = base.clone().add(new Vector(0, h, 0));
                fx(Particle.FLAME, top, 6, 0.8, 0.4, 0.8, 0.08);
                fx(Particle.DUST, top, 3, 1, 0.6, 1, 0, new Particle.DustOptions(WHITE_HOT, 4f));
            }
        }
    }

    // ------------------------------------------------------------------ molten BLACKFORGE sign

    private void sign() {
        if (sign.isEmpty()) return;
        List<BlockState> changes = new ArrayList<>();
        for (int i = 0; i < sign.size(); i++) {
            int[] b = sign.get(i);
            int want = signStage(b);
            if (want == signState[i]) continue;
            signState[i] = want;
            Block block = world.getBlockAt(b[0], b[1], b[2]);
            if (block.getType().isAir()) continue;               // hub not built - don't draw phantom letters
            BlockState s = block.getState();
            if (want >= 0) s.setBlockData(HEAT[want].createBlockData());
            changes.add(s);
        }
        if (!changes.isEmpty()) p.sendBlockChanges(changes);

        // sparks and fire spilling off the wave front
        if (t >= 6 && t <= 38) {
            double frontX = signMinX + (signMaxX - signMinX) * (t - 6) / 30.0;
            for (int i = 0; i < 10; i++) {
                int[] b = sign.get(ThreadLocalRandom.current().nextInt(sign.size()));
                if (Math.abs(b[0] - frontX) > 3) continue;
                Vector at = new Vector(b[0] + 0.5, b[1] + 0.5, b[2] + 1.3);
                fx(Particle.FLAME, at, 3, 0.4, 0.4, 0.2, 0.05);
                fx(Particle.DUST, at, 1, 0.3, 0.3, 0.1, 0, new Particle.DustOptions(YELLOW, 4f));
                fx(Particle.LAVA, at, 1, 0.3, 0.3, 0.1, 0);
            }
            if (t % 8 == 6) soundFrom(new Vector(frontX, b0y(), sign.get(0)[2]), Sound.BLOCK_LAVA_POP, 1f, 0.7f);
        }
        if (t == 88) {   // the whole sign flares with the finale
            soundFrom(new Vector(0, b0y(), sign.get(0)[2]), Sound.ITEM_FIRECHARGE_USE, 1f, 0.5f);
            for (int i = 0; i < 40; i++) {
                int[] b = sign.get(ThreadLocalRandom.current().nextInt(sign.size()));
                fx(Particle.FLAME, new Vector(b[0] + 0.5, b[1] + 0.5, b[2] + 1.3), 4, 0.5, 0.5, 0.3, 0.06);
            }
        }
    }

    private double b0y() { return sign.get(0)[1]; }

    /** Which heat stage a letter block shows right now, or -1 for its real block. */
    private int signStage(int[] b) {
        // a left-to-right wave (ticks 6-36); each block glows yellow → orange → magma → red, 4 ticks each, then cools
        double reach = 6 + 30.0 * (b[0] - signMinX) / (signMaxX - signMinX) + (b[1] % 3);
        int age = (int) Math.floor(t - reach);
        if (age >= 0 && age < 16) return age / 4;
        // finale flare (ticks 88-111): everything goes white-yellow, then cools through orange and red
        if (t >= 88 && t < 112) return Math.min(3, (t - 88) / 6);
        return -1;
    }

    private void restoreSign() {
        List<BlockState> changes = new ArrayList<>();
        for (int i = 0; i < sign.size(); i++) {
            if (signState[i] < 0) continue;
            int[] b = sign.get(i);
            changes.add(world.getBlockAt(b[0], b[1], b[2]).getState());
            signState[i] = -1;
        }
        if (!changes.isEmpty()) p.sendBlockChanges(changes);
    }

    // ------------------------------------------------------------------ fireworks ringing the island

    private void fireworks() {
        switch (t) {
            case 12 -> wave(6, 27, 0, 1, false);
            case 26 -> wave(6, 25, Math.PI / 6, 1, false);
            case 40 -> wave(8, 29, 0, 1, false);
            case 54 -> wave(8, 23, Math.PI / 8, 1, false);
            case 68 -> wave(12, 27, Math.PI / 12, 1, true);   // finale ring - power 1 rockets burst 1-1.6 s after launch
            case 76 -> wave(4, 20, Math.PI / 4, 1, true);
            default -> { }
        }
    }

    private void wave(int n, double r, double offset, int power, boolean big) {
        for (int i = 0; i < n; i++) {
            double a = front + offset + Math.PI * 2 * i / n;
            Vector at = ring(center, r + rnd(-1.5, 1.5), a, FIREWORK_UP);
            firework(at, power, big ? bigEffect() : randomEffect());
            if (i % 3 == 0) soundFrom(at, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.9f, (float) rnd(0.8, 1.2));
        }
    }

    private static FireworkEffect randomEffect() {
        var r = ThreadLocalRandom.current();
        FireworkEffect.Type[] types = {FireworkEffect.Type.BALL_LARGE, FireworkEffect.Type.BALL, FireworkEffect.Type.STAR, FireworkEffect.Type.BURST};
        int c = r.nextInt(PALETTE.length - 1);
        return FireworkEffect.builder().with(types[r.nextInt(types.length)])
                .withColor(PALETTE[c], PALETTE[c + 1], WHITE_HOT)
                .withFade(PALETTE[r.nextInt(PALETTE.length)])
                .flicker(r.nextBoolean()).trail(true).build();
    }

    private static FireworkEffect bigEffect() {
        return FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE)
                .withColor(YELLOW, GOLD, ORANGE, RED).withFade(CRIMSON, AMBER)
                .flicker(true).trail(true).build();
    }

    /** A real firework rocket that only this player can see. */
    private void firework(Vector at, int power, FireworkEffect effect) {
        Firework fw = world.spawn(at.toLocation(world), Firework.class, f -> {
            f.setVisibleByDefault(false);
            FireworkMeta m = f.getFireworkMeta();
            m.addEffect(effect);
            m.setPower(power);
            f.setFireworkMeta(m);
        });
        p.showEntity(plugin, fw);
    }

    // ------------------------------------------------------------------ halo of fire in the sky

    private void halo() {
        int k = t - 38;
        double spin = k * 0.03;
        double extent = Math.min(Math.PI, Math.PI * (k + 2) / 14.0);   // draws itself both ways from the front
        double rise = k > 60 ? (k - 60) * 0.5 : 0;                      // lifts away at the end
        int pts = 110;
        for (int i = 0; i < pts; i++) {
            double rel = Math.PI * 2 * i / pts - Math.PI;              // -π..π around the front
            if (Math.abs(rel) > extent) continue;
            double a = front + rel + spin;
            double hue = (Math.sin(a * 3 + k * 0.2) + 1) / 2;          // colour bands chase round the ring
            Color c = mix(YELLOW, RED, hue);
            fx(Particle.DUST_COLOR_TRANSITION, ring(center, HALO_R, a, HALO_UP + rise + Math.sin(a * 6 + k * 0.3) * 1.2), 1, 0.2, 0.2, 0.2, 0,
                    new Particle.DustTransition(c, ORANGE, 4f));
        }
        if (k % 4 == 0)
            for (int i = 0; i < 8; i++) {   // drips of fire falling from the halo
                double a = rnd(0, Math.PI * 2);
                fx(Particle.FLAME, ring(center, HALO_R, a, HALO_UP + rise), 0, 0, -1, 0, 0.25);
            }
    }

    // ------------------------------------------------------------------ ember rain

    private void embers() {
        for (int i = 0; i < 18; i++) {
            double a = rnd(0, Math.PI * 2), r = Math.sqrt(rnd(0.05, 1)) * 44;
            Vector at = ring(center, r, a, rnd(14, 34));
            fx(i % 3 == 0 ? Particle.LAVA : Particle.FALLING_LAVA, at, 1, 0, 0, 0, 0);
            if (i % 2 == 0) fx(Particle.DUST, at, 1, 0, 0, 0, 0, new Particle.DustOptions(PALETTE[i % PALETTE.length], 2.5f));
        }
    }

    // ------------------------------------------------------------------ helpers

    private void fx(Particle type, Vector at, int count, double ox, double oy, double oz, double extra) {
        fx(type, at, count, ox, oy, oz, extra, null);
    }

    private void fx(Particle type, Vector at, int count, double ox, double oy, double oz, double extra, Object data) {
        ParticleBuilder b = new ParticleBuilder(type).location(world, at.getX(), at.getY(), at.getZ())
                .receivers(p).force(true).count(count).offset(ox, oy, oz).extra(extra);
        if (data != null) b.data(data);
        b.spawn();
    }

    /** Plays a sound from the direction of a distant source, placed close enough to the player to be heard. */
    private void soundFrom(Vector source, Sound s, float volume, float pitch) {
        Vector eye = p.getEyeLocation().toVector();
        Vector dir = source.clone().subtract(eye);
        Vector at = dir.lengthSquared() < 1e-6 ? eye : eye.add(dir.normalize().multiply(Math.min(10, dir.length())));
        p.playSound(at.toLocation(world), s, volume, pitch);
    }

    /** point on a horizontal circle around {@code c}, {@code up} blocks above it */
    private static Vector ring(Vector c, double r, double a, double up) {
        return c.clone().add(new Vector(Math.cos(a) * r, up, Math.sin(a) * r));
    }

    private static Color mix(Color a, Color b, double s) {
        s = Math.max(0, Math.min(1, s));
        return Color.fromRGB((int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * s),
                (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * s),
                (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * s));
    }

    private static double easeOut(double x) { return 1 - Math.pow(1 - x, 3); }

    private static double rnd(double lo, double hi) { return ThreadLocalRandom.current().nextDouble(lo, hi); }
}
