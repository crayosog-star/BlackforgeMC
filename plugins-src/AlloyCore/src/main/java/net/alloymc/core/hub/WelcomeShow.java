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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * "The Forge Awakens" - the hub welcome show. 6 seconds, staged around the whole hub island:
 * <ul>
 *   <li>16 fire pillars erupt in a sweep around the island's rim</li>
 *   <li>molten waves run across the BLACKFORGE sign - across, back, out from the middle, up the letters - then the
 *       whole sign flares as lightning strikes around the rim and the pillars erupt again</li>
 *   <li>five waves of real fireworks (44 rockets) launch in a ring around the island</li>
 *   <li>a giant spinning halo of fire circles the sky above the island</li>
 *   <li>embers rain down; the action bar reads "✦ The forge welcomes &lt;name&gt; ✦" (the new player sees "...burns for you...")</li>
 * </ul>
 * Everyone in the hub sees it (players who arrive mid-show see the rest). Only one show plays at a time:
 * if several players join together, up to {@value #MAX_QUEUE} more are queued and play back to back; anyone
 * beyond that still gets the title and chat welcome, just not a show.
 * The glowing sign is a client-side block change sent to each viewer and put back when the show ends
 * (a relog or chunk reload also restores it); the real blocks are never touched.
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
    private static final int MAX_QUEUE = 3;
    // main thread only
    private static WelcomeShow current;
    private static final Deque<UUID> QUEUE = new ArrayDeque<>();

    /**
     * Plays the show for {@code p} (who must be on the hub) for everyone to watch, or queues it if one is
     * already playing. Returns false if they already have a show playing/queued or the queue is full.
     */
    public static boolean play(Plugin plugin, Player p) {
        if (!p.isOnline()) return false;
        if (current == null) { start(plugin, p); return true; }
        if (current.p.getUniqueId().equals(p.getUniqueId()) || QUEUE.contains(p.getUniqueId()) || QUEUE.size() >= MAX_QUEUE) return false;
        QUEUE.add(p.getUniqueId());
        return true;
    }

    private static void start(Plugin plugin, Player p) {
        current = new WelcomeShow(plugin, p);
        Bukkit.getScheduler().runTaskTimer(plugin, current::tick, 0L, 1L);
    }

    private final Plugin plugin;
    private final Player p;              // the player being welcomed
    private final String name;
    private List<Player> viewers = List.of();
    private final World world;
    private final Vector center;        // island centre at plaza level
    private final double front;         // angle (around the island) the player is facing, so the show opens in view
    private final List<int[]> sign;     // BLACKFORGE letter blocks: x, y, z
    private final int[] signState;      // what each letter block currently shows the player (-1 = real block)
    private final int signMinX, signMaxX, signMinY, signMaxY;
    private int t = 0;

    private static final Material[] HEAT = {     // molten stages a letter block passes through
            Material.OCHRE_FROGLIGHT, Material.SHROOMLIGHT, Material.MAGMA_BLOCK, Material.REDSTONE_BLOCK};

    private WelcomeShow(Plugin plugin, Player p) {
        this.plugin = plugin;
        this.p = p;
        this.name = p.getName();
        this.world = p.getWorld();
        this.center = new Vector(HubLayout.SPAWN_X, HubLayout.Y + 1, HubLayout.SPAWN_Z);
        Vector look = p.getLocation().getDirection();
        this.front = Math.atan2(look.getZ(), look.getX());
        this.sign = HubBuilder.signFront();
        this.signState = new int[sign.size()];
        java.util.Arrays.fill(signState, -1);
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE, bot = Integer.MAX_VALUE, top = Integer.MIN_VALUE;
        for (int[] b : sign) { lo = Math.min(lo, b[0]); hi = Math.max(hi, b[0]); bot = Math.min(bot, b[1]); top = Math.max(top, b[1]); }
        this.signMinX = lo;
        this.signMaxX = Math.max(lo + 1, hi);
        this.signMinY = bot;
        this.signMaxY = Math.max(bot + 1, top);
    }

    private void tick(BukkitTask task) {
        viewers = world.getPlayers();   // keeps playing if the welcomed player leaves - others are watching
        if (t > LENGTH) { stop(task); return; }
        try {
            frame();
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("Welcome show for " + name + " stopped: " + ex);
            stop(task);
            return;
        }
        t++;
    }

    private void stop(BukkitTask task) {
        task.cancel();
        restoreSign();
        current = null;
        while (!QUEUE.isEmpty()) {   // next queued player who is still on the hub
            Player next = Bukkit.getPlayer(QUEUE.poll());
            if (next != null && next.getWorld() == world) {
                // short gap so the shows don't run together
                Bukkit.getScheduler().runTaskLater(plugin, () -> play(plugin, next), 20L);
                break;
            }
        }
    }

    private void frame() {
        if (t == 0) {
            soundFrom(center, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.5f, 0.6f);
            soundFrom(center, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1f, 0.6f);
            soundFrom(center, Sound.ENTITY_BLAZE_SHOOT, 1f, 0.5f);
        }
        if (t % 2 == 0) {
            if (t < 64) pillars(0);
            if (t >= 94) pillars(94);       // the rim erupts again for the finale
        }
        if (t == 96) finaleStrike();
        sign();
        fireworks();
        if (t >= 38 && t < 112 && t % 2 == 0) halo();
        if (t == 96) {
            soundFrom(center, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 0.7f);
            for (Player v : viewers)
                v.sendActionBar(Ui.mm("<gradient:#fff04d:#ff9500:#ff1a1a>✦ " + (v == p ? "The forge burns for you, " + name : "The forge welcomes " + name) + " ✦</gradient>"));
        }
        if (t >= 94) embers();
    }

    // ------------------------------------------------------------------ fire pillars around the rim

    /** Lightning (visual and thunder only - no damage, no fire) around the rim, and blasts of flame. */
    private void finaleStrike() {
        soundFrom(center, Sound.ENTITY_GENERIC_EXPLODE, 1f, 0.6f);
        for (int i = 0; i < 8; i++) {
            double a = front + Math.PI * 2 * i / 8 + Math.PI / 16;
            Vector at = ring(center, RIM + 4, a, 0);
            world.strikeLightningEffect(at.toLocation(world));
            fx(Particle.EXPLOSION_EMITTER, at.clone().add(new Vector(0, 3, 0)), 1, 0, 0, 0, 0);
            fx(Particle.FLAME, at.clone().add(new Vector(0, 2, 0)), 0, 0, 1, 0, 0.6);
            spray(at.clone().add(new Vector(0, 6, 0)), 24, 0.35);
        }
    }

    /** Flame flying out evenly in every direction. */
    private void spray(Vector c, int n, double speed) {
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < n; i++) {
            double y = 1 - 2 * (i + 0.5) / n, q = Math.sqrt(1 - y * y), a = golden * i;
            fx(Particle.FLAME, c, 0, q * Math.cos(a), y, q * Math.sin(a), speed);
        }
    }

    private void pillars(int start) {
        for (int i = 0; i < 16; i++) {
            int age = t - start - i;                             // erupt one per tick...
            if (age < 0) continue;
            double a = front + (i % 2 == 0 ? 1 : -1) * Math.PI * ((i + 1) / 2) / 8.0;   // ...fanning both ways round from the front
            Vector base = ring(center, RIM, a, -2);
            double h = age < 10 ? 26 * easeOut((age + 1) / 10.0) : t - start < 50 ? 26 : 26 * Math.max(0, 1 - (t - start - 50) / 14.0);
            if (age == 0) {
                soundFrom(base, Sound.ITEM_FIRECHARGE_USE, 0.6f, (float) rnd(0.6, 0.9));
                fx(Particle.LAVA, base, 14, 1.2, 0.5, 1.2, 0);
            }
            for (double y = 0; y < h; y += 1.3) {
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
        List<int[]> ignited = new ArrayList<>();
        for (int i = 0; i < sign.size(); i++) {
            int[] b = sign.get(i);
            int want = signStage(b);
            if (want == signState[i]) continue;
            if (want == 0) ignited.add(b);
            signState[i] = want;
            Block block = world.getBlockAt(b[0], b[1], b[2]);
            if (block.getType().isAir()) continue;               // hub not built - don't draw phantom letters
            BlockState s = block.getState();
            if (want >= 0) s.setBlockData(HEAT[want].createBlockData());
            changes.add(s);
        }
        if (!changes.isEmpty()) for (Player v : viewers) v.sendBlockChanges(changes);

        // sparks and fire spill off every block that has just ignited, whichever pass lit it
        java.util.Collections.shuffle(ignited);
        for (int i = 0; i < Math.min(14, ignited.size()); i++) {
            int[] b = ignited.get(i);
            Vector at = new Vector(b[0] + 0.5, b[1] + 0.5, b[2] + 1.3);
            fx(Particle.FLAME, at, 3, 0.4, 0.4, 0.2, 0.06);
            fx(Particle.DUST, at, 1, 0.3, 0.3, 0.1, 0, new Particle.DustOptions(i % 2 == 0 ? YELLOW : ORANGE, 4f));
            if (i % 3 == 0) fx(Particle.LAVA, at, 1, 0.3, 0.3, 0.1, 0);
        }
        Vector signMid = new Vector((signMinX + signMaxX) / 2.0, (signMinY + signMaxY) / 2.0, sign.get(0)[2]);
        switch (t) {   // a hiss for each pass
            case 6, 36, 60, 80 -> { soundFrom(signMid, Sound.BLOCK_LAVA_POP, 1f, 0.6f); soundFrom(signMid, Sound.ITEM_FIRECHARGE_USE, 0.7f, 0.8f); }
            case 96 -> {   // the whole sign flares with the finale
                soundFrom(signMid, Sound.ITEM_FIRECHARGE_USE, 1f, 0.5f);
                for (int i = 0; i < 60; i++) {
                    int[] b = sign.get(ThreadLocalRandom.current().nextInt(sign.size()));
                    fx(Particle.FLAME, new Vector(b[0] + 0.5, b[1] + 0.5, b[2] + 1.3), 4, 0.5, 0.5, 0.3, 0.08);
                }
            }
            default -> { }
        }
    }

    /**
     * Which heat stage a letter block shows right now (0 hottest .. 3), or -1 for its real block. Five passes in
     * the same molten style, each block running yellow → orange → magma → red (3 ticks each) as a pass reaches it.
     */
    private int signStage(int[] b) {
        double across = (b[0] - signMinX) / (double) (signMaxX - signMinX);   // 0..1 left → right
        double up = (b[1] - signMinY) / (double) (signMaxY - signMinY);   // 0..1 bottom → top
        double jitter = (b[1] % 3) * 0.5;
        int stage = -1;
        stage = hotter(stage, pass(6 + 28 * across + jitter));                        // 1. across, left → right
        stage = hotter(stage, pass(36 + 22 * (1 - across) + jitter));                 // 2. back, right → left
        stage = hotter(stage, pass(60 + 18 * Math.abs(across - 0.5) * 2 + jitter));   // 3. bursts out from the middle
        stage = hotter(stage, pass(80 + 12 * up + 4 * Math.abs(across - 0.5)));      // 4. flames climb the letters
        if (t >= 96 && t < 120) stage = hotter(stage, Math.min(3, (t - 96) / 6)); // 5. the whole sign flares and cools
        return stage;
    }

    private int pass(double reach) {
        int age = (int) Math.floor(t - reach);
        return age >= 0 && age < 12 ? age / 3 : -1;
    }

    private static int hotter(int a, int b) { return a < 0 ? b : b < 0 ? a : Math.min(a, b); }

    private void restoreSign() {
        List<BlockState> changes = new ArrayList<>();
        for (int i = 0; i < sign.size(); i++) {
            if (signState[i] < 0) continue;
            int[] b = sign.get(i);
            changes.add(world.getBlockAt(b[0], b[1], b[2]).getState());
            signState[i] = -1;
        }
        if (!changes.isEmpty()) for (Player v : world.getPlayers()) v.sendBlockChanges(changes);
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

    /** A real firework rocket, visible to everyone nearby. */
    private void firework(Vector at, int power, FireworkEffect effect) {
        world.spawn(at.toLocation(world), Firework.class, f -> {
            FireworkMeta m = f.getFireworkMeta();
            m.addEffect(effect);
            m.setPower(power);
            f.setFireworkMeta(m);
        });
    }

    // ------------------------------------------------------------------ halo of fire in the sky

    private void halo() {
        int k = t - 38;
        double spin = k * 0.03;
        double extent = Math.min(Math.PI, Math.PI * (k + 2) / 14.0);   // draws itself both ways from the front
        double rise = k > 60 ? (k - 60) * 0.5 : 0;                      // lifts away at the end
        int pts = 80;
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
                .receivers(viewers).force(true).count(count).offset(ox, oy, oz).extra(extra);
        if (data != null) b.data(data);
        b.spawn();
    }

    /** Plays a sound to every viewer from the direction of a distant source, placed close enough to be heard. */
    private void soundFrom(Vector source, Sound s, float volume, float pitch) {
        for (Player v : viewers) {
            Vector eye = v.getEyeLocation().toVector();
            Vector dir = source.clone().subtract(eye);
            Vector at = dir.lengthSquared() < 1e-6 ? eye : eye.add(dir.normalize().multiply(Math.min(10, dir.length())));
            v.playSound(at.toLocation(world), s, volume, pitch);
        }
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
