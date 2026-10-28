package net.alloymc.core.hub;

import com.destroystokyo.paper.ParticleBuilder;
import net.alloymc.core.Ui;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
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
 * "The Forge Awakens" - the first-join welcome show (~12.5 seconds).
 * <ol>
 *   <li>A soul-fire rune circle ignites under the player and twin flame helixes climb out of it.</li>
 *   <li>A fireball arcs from the player into the sky and bursts into a double ring.</li>
 *   <li>Ember rockets launch from the island: rings, starbursts, soul-fire bursts, lava willows, crackles.</li>
 *   <li>A giant rotating forge sigil forms, pulls enchanting glyphs into its core, collapses...</li>
 *   <li>...and detonates into a real-firework finale, then embers rain down.</li>
 * </ol>
 * Everything is sent only to the joining player (particle receivers, per-player hidden fireworks, player-only
 * sounds), so a wave of new players never floods the hub for everyone else.
 * The show surrounds the player: rockets launch from a ring around them and burst on every side, while the
 * fireball, sigil (a flat halo) and finale centre straight overhead and the finale fireworks circle them.
 */
public final class WelcomeShow {

    private static final Color WHITE_HOT = Color.fromRGB(0xFFF1C1), GOLD = Color.fromRGB(0xFFC53D),
            EMBER = Color.fromRGB(0xFF7A1A), CRIMSON = Color.fromRGB(0xE5484D), BLOOD = Color.fromRGB(0x7A0A0A),
            SOUL = Color.fromRGB(0x4FD1C5);
    private static final int LENGTH = 250;                       // ticks
    private static final Set<UUID> RUNNING = new HashSet<>();    // main thread only

    /** Starts the show for {@code p}. Returns false if one is already playing for them. */
    public static boolean play(Plugin plugin, Player p) {
        if (!p.isOnline() || !RUNNING.add(p.getUniqueId())) return false;
        WelcomeShow show = new WelcomeShow(plugin, p);
        Bukkit.getScheduler().runTaskTimer(plugin, show::tick, 0L, 1L);
        return true;
    }

    private enum Kind { AWAKEN, RING, STARBURST, SOUL, WILLOW, CRACKLE }
    private record Rocket(int launch, Vector from, Vector to, int flight, Kind kind) { }
    private record Burst(int start, Vector at, Kind kind) { }

    private final Plugin plugin;
    private final Player p;
    private final World world;
    private final Vector origin, fwd, right, up = new Vector(0, 1, 0), sky;
    private final List<Rocket> rockets = new ArrayList<>();
    private final List<Burst> bursts = new ArrayList<>();
    private int t = 0;

    private WelcomeShow(Plugin plugin, Player p) {
        this.plugin = plugin;
        this.p = p;
        Location l = p.getLocation();
        this.world = l.getWorld();
        this.origin = l.toVector();
        double yaw = Math.toRadians(l.getYaw());
        this.fwd = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
        this.right = fwd.clone().crossProduct(up);
        // centre of the show: straight above the player, well inside entity tracking range for the real fireworks
        this.sky = rel(origin, 0, 14, 0);

        // ember volleys from a ring around the player: launch tick, angle (0 = where they face, 90 = their right),
        // extra burst height. Front and back alternate with the sides so the show goes all the way round.
        volley(86, 0, 2, Kind.RING);
        volley(94, 180, 4, Kind.STARBURST);
        volley(104, 90, 6, Kind.SOUL);
        volley(112, 270, 0, Kind.WILLOW);
        volley(120, 45, 1, Kind.RING);
        volley(128, 225, 5, Kind.CRACKLE);
        volley(136, 135, 3, Kind.STARBURST);
        volley(140, 315, 7, Kind.SOUL);
    }

    /** A rocket launched 10 blocks out at {@code deg} around the player, bursting 7 blocks out and 11+ up. */
    private void volley(int launch, double deg, double height, Kind kind) {
        double a = Math.toRadians(deg), side = Math.sin(a), ahead = Math.cos(a);
        rockets.add(new Rocket(launch, rel(origin, side * 10, 0, ahead * 10), rel(origin, side * 7, 11 + height, ahead * 7), 14, kind));
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
    }

    private void frame() {
        rune();
        if (t == 55) {
            sound(origin, Sound.ENTITY_BLAZE_SHOOT, 1f, 0.7f);
            sound(origin, Sound.ITEM_FIRECHARGE_USE, 0.8f, 0.6f);
        }
        if (t >= 55 && t < 73) fireball(t - 55);
        if (t == 73) bursts.add(new Burst(t, sky.clone(), Kind.AWAKEN));
        for (Rocket r : rockets) rocket(r);
        bursts.removeIf(b -> !burst(b));
        if (t >= 150 && t < 198) sigil(t - 150);
        if (t >= 198) finale(t - 198);
    }

    // ------------------------------------------------------------------ 1. ignition rune

    private void rune() {
        if (t > 56) return;
        Vector c = origin.clone().add(new Vector(0, 0.15, 0));
        if (t == 0) {
            sound(origin, Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.5f);
            sound(origin, Sound.ITEM_FIRECHARGE_USE, 0.8f, 0.7f);
        }
        if (t == 30) sound(origin, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.7f, 1.4f);
        double spin = t * 0.03;
        if (t % 2 == 0) {
            int pts = 40, drawn = (int) Math.ceil(pts * Math.min(1, (t + 2) / 16.0));   // the circle traces itself in
            for (int i = 0; i < drawn; i++) fx(Particle.SOUL_FIRE_FLAME, flat(c, 2.4, Math.PI * 2 * i / pts + spin), 1, 0, 0, 0, 0);
            if (t >= 16) {   // eight-point star once the circle closes
                Particle.DustOptions dust = new Particle.DustOptions(EMBER, 1.0f);
                for (int k = 0; k < 8; k++)
                    line(flat(c, 2.4, Math.PI * k / 4 - spin), flat(c, 2.4, Math.PI * (k + 3) / 4 - spin), 6, Particle.DUST, dust);
            }
        }
        if (t >= 14) {   // twin helix climbing out of the rune
            Particle.DustTransition dust = new Particle.DustTransition(GOLD, CRIMSON, 1.1f);
            double h = Math.min(4.5, (t - 14) * 0.18);
            for (double y = 0; y <= h; y += 0.3)
                for (int s = 0; s < 2; s++)
                    fx(Particle.DUST_COLOR_TRANSITION, flat(c.clone().add(new Vector(0, y, 0)), 1.3, y * 1.6 + t * 0.25 + s * Math.PI), 1, 0, 0, 0, 0, dust);
            if (t % 4 == 0) fx(Particle.ENCHANT, c.clone().add(new Vector(0, 2, 0)), 12, 1.2, 1, 1.2, 0.8);
        }
    }

    // ------------------------------------------------------------------ 2. fireball

    private void fireball(int k) {
        Vector start = origin.clone().add(new Vector(0, 2.2, 0)), ctrl = rel(origin, 0, 10, 5);
        Particle.DustOptions core = new Particle.DustOptions(EMBER, 2.5f);
        for (int sub = 0; sub < 2; sub++) {   // two sub-steps per tick keep the trail continuous
            Vector pos = bezier(start, ctrl, sky, (k + 0.5 * (sub + 1)) / 18.0);
            fx(Particle.FLAME, pos, 5, 0.18, 0.18, 0.18, 0.01);
            fx(Particle.SMALL_FLAME, pos, 2, 0.1, 0.1, 0.1, 0.02);
            fx(Particle.DUST, pos, 3, 0.25, 0.25, 0.25, 0, core);
        }
        if (k % 3 == 0) fx(Particle.LAVA, bezier(start, ctrl, sky, (k + 1) / 18.0), 1, 0, 0, 0, 0);
    }

    // ------------------------------------------------------------------ 3. ember rockets and bursts

    private void rocket(Rocket r) {
        int age = t - r.launch();
        if (age < 0 || age > r.flight()) return;
        if (age == 0) sound(r.from(), Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 3f, (float) rnd(0.85, 1.15));
        for (int sub = 0; sub < 3; sub++) {
            double s = easeOut(Math.min(1, (age + sub / 3.0) / r.flight()));
            Vector pos = lerp(r.from(), r.to(), s).add(right.clone().multiply(Math.sin(s * 9 + r.launch()) * 0.25));
            fx(Particle.FLAME, pos, 1, 0.03, 0.03, 0.03, 0.005);
            if (sub == 0) fx(Particle.SMALL_FLAME, pos, 1, 0.05, 0.05, 0.05, 0.01);
        }
        if (age == r.flight()) bursts.add(new Burst(t, r.to().clone(), r.kind()));
    }

    /** Draws one frame of a burst; returns false once it has finished. */
    private boolean burst(Burst b) {
        int k = t - b.start();
        Vector c = b.at();
        switch (b.kind()) {
            case AWAKEN -> {   // the fireball's burst: a wide white-hot ring with a soul-fire ring inside
                if (k == 0) {
                    boom(c, true);
                    spray(Particle.FLAME, c, 70, 0.4);
                    spray(Particle.SOUL_FIRE_FLAME, c, 30, 0.25);
                    fx(Particle.LAVA, c, 12, 0.6, 0.6, 0.6, 0);
                }
                double r = 9 * easeOut(Math.min(1, (k + 1) / 10.0));
                Particle.DustTransition outer = new Particle.DustTransition(WHITE_HOT, EMBER, 2f), inner = new Particle.DustTransition(SOUL, GOLD, 1.4f);
                for (int i = 0; i < 56; i++) fx(Particle.DUST_COLOR_TRANSITION, flat(c, r, Math.PI * 2 * i / 56), 1, 0, 0, 0, 0, outer);
                for (int i = 0; i < 32; i++) fx(Particle.DUST_COLOR_TRANSITION, flat(c, r * 0.6, Math.PI * 2 * i / 32 - k * 0.1), 1, 0, 0, 0, 0, inner);
                return k < 12;
            }
            case RING -> {
                if (k == 0) { boom(c, false); spray(Particle.FLAME, c, 24, 0.12); }
                double r = 6.5 * easeOut(Math.min(1, (k + 1) / 8.0));
                Particle.DustTransition dust = k < 4 ? new Particle.DustTransition(WHITE_HOT, GOLD, 1.7f) : new Particle.DustTransition(EMBER, CRIMSON, 1.7f);
                for (int i = 0; i < 40; i++) fx(Particle.DUST_COLOR_TRANSITION, facing(c, r, Math.PI * 2 * i / 40), 1, 0, 0, 0, 0, dust);
                if (k == 9) fx(Particle.FIREWORK, c, 30, 3, 3, 3, 0.02);
                return k < 10;
            }
            case STARBURST -> {
                if (k == 0) {
                    boom(c, true);
                    spray(Particle.FLAME, c, 60, 0.32);
                    spray(Particle.SMALL_FLAME, c, 30, 0.22);
                    fx(Particle.LAVA, c, 8, 0.5, 0.5, 0.5, 0);
                }
                if (k == 6) {
                    sound(c, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE_FAR, 3f, 1f);
                    fx(Particle.ELECTRIC_SPARK, c, 40, 3.5, 3.5, 3.5, 0);
                }
                return k < 7;
            }
            case SOUL -> {   // the "magic" accent: teal soul fire cooling into embers
                if (k == 0) {
                    boom(c, false);
                    sound(c, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 3f, 0.6f);
                    spray(Particle.SOUL_FIRE_FLAME, c, 50, 0.28);
                    spray(Particle.END_ROD, c, 18, 0.16);
                }
                double r = 4.5 * easeOut(Math.min(1, (k + 1) / 7.0));
                Particle.DustTransition dust = new Particle.DustTransition(SOUL, EMBER, 1.4f);
                for (int i = 0; i < 28; i++) fx(Particle.DUST_COLOR_TRANSITION, facing(c, r, Math.PI * 2 * i / 28 + k * 0.08), 1, 0, 0, 0, 0, dust);
                if (k % 2 == 0) fx(Particle.ENCHANT, c, 25, 2, 2, 2, 1);
                return k < 8;
            }
            case WILLOW -> {   // molten droplets drifting down like a willow
                if (k == 0) { boom(c, true); spray(Particle.FLAME, c, 36, 0.22); }
                if (k == 10) sound(c, Sound.BLOCK_LAVA_POP, 3f, 0.8f);
                double r = Math.min(5, 1 + k * 0.4);
                for (int i = 0; i < 6; i++) {
                    Vector d = randomUnit().multiply(r);
                    d.setY(d.getY() * 0.6 - k * 0.12);
                    fx(i % 3 == 0 ? Particle.DRIPPING_LAVA : Particle.FALLING_LAVA, c.clone().add(d), 1, 0, 0, 0, 0);
                }
                return k < 24;
            }
            case CRACKLE -> {
                if (k == 0) { boom(c, false); fx(Particle.DUST, c, 30, 1.5, 1.5, 1.5, 0, new Particle.DustOptions(GOLD, 2f)); }
                if (k == 4 || k == 9) sound(c, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE_FAR, 3f, k == 4 ? 1f : 1.3f);
                if (k >= 3) for (int i = 0; i < 5; i++) {
                    Vector q = c.clone().add(randomUnit().multiply(rnd(1.5, 5)));
                    fx(Particle.ELECTRIC_SPARK, q, 3, 0.2, 0.2, 0.2, 0.05);
                    fx(Particle.WAX_ON, q, 2, 0.2, 0.2, 0.2, 0);
                }
                return k < 15;
            }
        }
        return false;
    }

    private void boom(Vector c, boolean large) {
        sound(c, large ? Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST_FAR : Sound.ENTITY_FIREWORK_ROCKET_BLAST_FAR, 4f, (float) rnd(0.9, 1.1));
        fx(Particle.DUST, c, 10, 0.3, 0.3, 0.3, 0, new Particle.DustOptions(WHITE_HOT, 3f));
    }

    // ------------------------------------------------------------------ 4. forge sigil

    private void sigil(int k) {
        // grow over 12 ticks, hold, collapse over the last 8
        double s = k < 12 ? easeOut((k + 1) / 12.0) : k >= 40 ? Math.max(0.05, 1 - (k - 39) / 8.0) : 1;
        switch (k) {
            case 0 -> sound(sky, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 4f, 0.7f);
            case 12 -> sound(sky, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 4f, 1.0f);
            case 24 -> sound(sky, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 4f, 1.3f);
            case 36 -> sound(sky, Sound.BLOCK_BEACON_POWER_SELECT, 4f, 0.8f);
            case 40 -> sound(sky, Sound.BLOCK_CONDUIT_ACTIVATE, 4f, 1.2f);
            default -> { }
        }
        if (k % 2 == 0) {
            double spin = k * 0.05;
            Particle.DustOptions gold = new Particle.DustOptions(GOLD, 1.6f), crimson = new Particle.DustOptions(CRIMSON, 1.4f),
                    ember = new Particle.DustOptions(EMBER, 1.2f);
            Particle.DustTransition star = new Particle.DustTransition(GOLD, BLOOD, 1.3f);
            for (int i = 0; i < 56; i++) fx(Particle.DUST, flat(sky, 8.5 * s, Math.PI * 2 * i / 56), 1, 0, 0, 0, 0, gold);
            for (int i = 0; i < 40; i++) fx(Particle.DUST, flat(sky, 6 * s, Math.PI * 2 * i / 40), 1, 0, 0, 0, 0, crimson);
            for (int i = 0; i < 16; i++) {   // rotating notches between the two circles
                double a = Math.PI * 2 * i / 16 + spin;
                line(flat(sky, 6.3 * s, a), flat(sky, 8.2 * s, a), 3, Particle.DUST, ember);
            }
            for (int j = 0; j < 8; j++) {    // counter-rotating eight-point star (two squares)
                double a0 = -spin * 1.5 + Math.PI / 4 * j, a1 = a0 + Math.PI / 2;
                line(flat(sky, 6 * s, a0), flat(sky, 6 * s, a1), 7, Particle.DUST_COLOR_TRANSITION, star);
            }
            fx(Particle.FLAME, sky, 4, 0.3, 0.3, 0.3, 0.01);
        }
        if (k >= 20 && k < 44)   // enchanting glyphs pulled into the core
            for (int i = 0; i < 6; i++) {
                Vector d = randomUnit().multiply(7 * s + 2);
                fx(Particle.ENCHANT, sky, 0, d.getX(), d.getY(), d.getZ(), 1);
            }
    }

    // ------------------------------------------------------------------ 5. finale

    private void finale(int k) {
        switch (k) {
            case 0 -> {
                sound(sky, Sound.ENTITY_GENERIC_EXPLODE, 2f, 1.4f);
                sound(sky, Sound.ENTITY_FIREWORK_ROCKET_LARGE_BLAST_FAR, 4f, 0.8f);
                spray(Particle.FLAME, sky, 90, 0.5);
                spray(Particle.SOUL_FIRE_FLAME, sky, 40, 0.35);
                fx(Particle.LAVA, sky, 20, 1, 1, 1, 0);
                fx(Particle.DUST, sky, 25, 0.5, 0.5, 0.5, 0, new Particle.DustOptions(WHITE_HOT, 4f));
                firework(sky, effect(FireworkEffect.Type.BALL_LARGE, GOLD, EMBER, CRIMSON));
            }
            case 5 -> {
                firework(rel(sky, -8, -2, 0), effect(FireworkEffect.Type.STAR, WHITE_HOT, GOLD, EMBER));
                firework(rel(sky, 8, -2, 0), effect(FireworkEffect.Type.STAR, WHITE_HOT, GOLD, EMBER));
            }
            case 11 -> {
                firework(rel(sky, 0, -1, 8), effect(FireworkEffect.Type.BURST, CRIMSON, EMBER, BLOOD));
                firework(rel(sky, 0, -1, -8), effect(FireworkEffect.Type.BURST, SOUL, EMBER, GOLD));
            }
            case 17 -> {
                firework(rel(sky, 0, 4, 0), effect(FireworkEffect.Type.BALL_LARGE, EMBER, GOLD, WHITE_HOT, CRIMSON));
                sound(origin, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 0.8f);
                p.sendActionBar(Ui.mm("<gradient:#ffd27a:#ff5a1a>✦ The forge burns for you, " + p.getName() + " ✦</gradient>"));
            }
            default -> { }
        }
        if (k >= 4) {   // embers and ash raining back down
            int n = Math.max(1, 10 - k / 6);
            Vector above = rel(sky, 0, 4, 0);
            fx(Particle.FALLING_LAVA, above, n, 9, 2, 9, 0);
            fx(Particle.ASH, above, n * 3, 10, 3, 10, 0);
            if (k % 5 == 0) fx(Particle.LAVA, above, 2, 6, 1, 6, 0);
        }
    }

    /** last colour is the fade; the rest are the burst colours */
    private static FireworkEffect effect(FireworkEffect.Type type, Color... colors) {
        return FireworkEffect.builder().with(type)
                .withColor(java.util.Arrays.copyOf(colors, colors.length - 1))
                .withFade(colors[colors.length - 1])
                .flicker(true).trail(true).build();
    }

    /** A real firework only this player can see, detonated where we want it. */
    private void firework(Vector at, FireworkEffect effect) {
        Firework fw = world.spawn(at.clone().subtract(up).toLocation(world), Firework.class, f -> {
            f.setVisibleByDefault(false);
            FireworkMeta m = f.getFireworkMeta();
            m.addEffect(effect);
            m.setPower(1);
            f.setFireworkMeta(m);
        });
        p.showEntity(plugin, fw);
        // a couple of ticks' flight so the client is tracking it before it bursts
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (fw.isValid()) fw.detonate(); }, 3L);
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

    private void sound(Vector at, Sound s, float volume, float pitch) {
        p.playSound(at.toLocation(world), s, volume, pitch);
    }

    /** Particles flying outward evenly in every direction (count 0 = offset is the velocity). */
    private void spray(Particle type, Vector c, int n, double speed) {
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < n; i++) {
            double y = 1 - 2 * (i + 0.5) / n, q = Math.sqrt(1 - y * y), a = golden * i;
            fx(type, c, 0, q * Math.cos(a), y, q * Math.sin(a), speed * rnd(0.85, 1.15));
        }
    }

    private void line(Vector a, Vector b, int steps, Particle type, Object data) {
        for (int i = 0; i <= steps; i++) fx(type, lerp(a, b, (double) i / steps), 1, 0, 0, 0, 0, data);
    }

    /** base + right*side + up*height + forward*ahead */
    private Vector rel(Vector base, double side, double height, double ahead) {
        return base.clone().add(right.clone().multiply(side)).add(up.clone().multiply(height)).add(fwd.clone().multiply(ahead));
    }

    /** point on a circle lying flat on the ground */
    private static Vector flat(Vector c, double r, double a) {
        return c.clone().add(new Vector(Math.cos(a) * r, 0, Math.sin(a) * r));
    }

    /** point on a circle standing upright and turned to face the player, wherever around them it is */
    private Vector facing(Vector c, double r, double a) {
        Vector toward = c.clone().subtract(origin).setY(0);
        Vector side = toward.lengthSquared() < 1 ? right : toward.normalize().crossProduct(up);
        return c.clone().add(side.clone().multiply(Math.cos(a) * r)).add(up.clone().multiply(Math.sin(a) * r));
    }

    private static Vector lerp(Vector a, Vector b, double s) {
        return a.clone().multiply(1 - s).add(b.clone().multiply(s));
    }

    private static Vector bezier(Vector a, Vector ctrl, Vector b, double s) {
        double u = 1 - s;
        return a.clone().multiply(u * u).add(ctrl.clone().multiply(2 * u * s)).add(b.clone().multiply(s * s));
    }

    private static double easeOut(double x) { return 1 - Math.pow(1 - x, 3); }

    private static double rnd(double lo, double hi) { return ThreadLocalRandom.current().nextDouble(lo, hi); }

    private static Vector randomUnit() {
        double z = rnd(-1, 1), a = rnd(0, Math.PI * 2), q = Math.sqrt(1 - z * z);
        return new Vector(q * Math.cos(a), z, q * Math.sin(a));
    }
}
