package net.blackforge.core.abyss;

import net.blackforge.core.BlackForgeCore;
import net.blackforge.core.Ui;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

import static net.blackforge.core.abyss.AbyssGenerator.SEA;

/**
 * Jörmungandr, the World Serpent. A 360-block-long thing of dark scales that rises from the Abyssal Sea: first only its coils,
 * far off, breaking the surface in a great ring; then the ring closes, slowly, a little tighter each lap; then it strikes,
 * out of the dark water under whoever it has chosen, again and again, faster as it is hurt.
 *
 * Built from block displays that follow a path of the head (a few hundred, teleported every other tick while it lives), with
 * one invisible slime as the hitbox of the head. It exists only while players are near, one at a time per world.
 */
final class Leviathan implements Listener {
    private static final String TAG = "leviathan_part";
    private static final int SEG = 72, SEND = 2;
    private static final double SPACING = 5.0;

    private enum State { APPEAR, CIRCLE, TENSION, DIVE, LUNGE, RECOVER, DYING }

    private final BlackForgeCore plugin;
    private final World world;
    private final Random rnd = new Random();
    private final List<BlockDisplay> body = new ArrayList<>(), spine = new ArrayList<>(), headParts = new ArrayList<>();
    private final ArrayDeque<double[]> path = new ArrayDeque<>();
    private final Map<UUID, Double> damage = new HashMap<>();
    private final Map<UUID, Integer> contactCooldown = new HashMap<>();
    private final BossBar bar = Bukkit.createBossBar("<>", BarColor.GREEN, BarStyle.SEGMENTED_10);
    private BukkitTask task;
    private Slime hitbox;

    private State state = State.APPEAR;
    private int t, stateT, strikes, farTicks;
    private double maxHp, hp, theta, phase, jaw;
    private double hx, hy, hz, vx, vy, vz;
    private double cx, cz;                        // what it circles
    private UUID target;
    private double[] lungeFrom, lungeApex, lungeTo, lungeCtl;
    private boolean lungeHit;
    private int lungesLeft;
    private final Runnable onEnd;

    Leviathan(BlackForgeCore plugin, World world, Player target, Runnable onEnd) {
        this.plugin = plugin;
        this.world = world;
        this.onEnd = onEnd;
        this.target = target.getUniqueId();
        Location l = target.getLocation();
        cx = l.getX(); cz = l.getZ();
        // it surfaces far off, in a random direction
        theta = rnd.nextDouble() * Math.PI * 2;
        hx = cx + Math.cos(theta) * 150; hz = cz + Math.sin(theta) * 150; hy = SEA - 6;
        double a = theta + Math.PI / 2;
        for (int i = 0; i < 480; i++) path.addLast(new double[]{hx - Math.cos(a) * i, SEA - 6, hz - Math.sin(a) * i});
        int near = 0;
        for (Player p : world.getPlayers()) if (p.getLocation().distance(l) < 140) near++;
        maxHp = hp = 2400 + 900 * Math.max(0, near - 1);
        buildDisplays();
        hitbox = world.spawn(new Location(world, hx, hy - 3, hz), Slime.class, s -> {
            s.setSize(14); s.setAI(false); s.setInvisible(true); s.setSilent(true); s.setPersistent(false); s.setCollidable(false);
            s.setRemoveWhenFarAway(false); s.addScoreboardTag(TAG);
        });
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        for (Player p : world.getPlayers()) if (p.getLocation().distance(l) < 200) {
            p.showTitle(net.kyori.adventure.title.Title.title(Ui.mm("<dark_aqua><bold>The sea grows quiet..."), Ui.mm("<gray>Something is rising"),
                    net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(500), java.time.Duration.ofSeconds(4), java.time.Duration.ofSeconds(1))));
            p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.AMBIENT, 2f, 0.5f);
        }
    }

    // ------------------------------------------------------------------ the displays

    private BlockDisplay part(String block, int light) {
        return world.spawn(new Location(world, hx, hy, hz), BlockDisplay.class, d -> {
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
            d.setBlock(Bukkit.createBlockData(block));
            d.setBrightness(new Display.Brightness(light, Math.min(15, light + 4)));
            d.setViewRange(8f);
            d.setShadowRadius(0);
            d.setInterpolationDuration(SEND);
            d.setTeleportDuration(SEND);
        });
    }

    private void buildDisplays() {
        for (int i = 0; i < SEG; i++) {
            body.add(part(i % 5 == 4 ? "minecraft:warped_wart_block" : "minecraft:dark_prismarine", 3));
            if (i % 2 == 0) spine.add(part("minecraft:prismarine_bricks", 5));
        }
        headParts.add(part("minecraft:dark_prismarine", 4));              // 0 skull
        headParts.add(part("minecraft:prismarine_bricks", 4));            // 1 jaw
        headParts.add(part("minecraft:verdant_froglight", 15));           // 2 eye
        headParts.add(part("minecraft:verdant_froglight", 15));           // 3 eye
        headParts.add(part("minecraft:bone_block", 6));                   // 4 horn
        headParts.add(part("minecraft:bone_block", 6));                   // 5 horn
        for (int i = 0; i < 10; i++) headParts.add(part("minecraft:calcite", 8));   // 6..15 teeth
        headParts.add(part("minecraft:prismarine_bricks", 5));            // 16 brow
    }

    private static double[] cross(double[] a, double[] b) { return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]}; }
    private static double[] norm(double[] a) { double l = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]); return l < 1e-9 ? new double[]{1, 0, 0} : new double[]{a[0] / l, a[1] / l, a[2] / l}; }
    private static double[] add(double[] a, double[] b) { return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]}; }
    private static double[] mul(double[] a, double k) { return new double[]{a[0] * k, a[1] * k, a[2] * k}; }

    /** A box centred on p, with local axes r (width), u (height), f (length). */
    private void put(BlockDisplay d, double[] p, double[] r, double[] u, double[] f, double sx, double sy, double sz) {
        Matrix3f m = new Matrix3f((float) r[0], (float) r[1], (float) r[2], (float) u[0], (float) u[1], (float) u[2], (float) f[0], (float) f[1], (float) f[2]);
        Quaternionf q = new Quaternionf().setFromNormalized(m);
        Vector3f tr = new Vector3f((float) (-r[0] * sx / 2 - u[0] * sy / 2 - f[0] * sz / 2), (float) (-r[1] * sx / 2 - u[1] * sy / 2 - f[1] * sz / 2), (float) (-r[2] * sx / 2 - u[2] * sy / 2 - f[2] * sz / 2));
        d.setTransformation(new Transformation(tr, q, new Vector3f((float) sx, (float) sy, (float) sz), new Quaternionf()));
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(SEND);
        d.teleport(new Location(world, p[0], p[1], p[2]));
    }

    /** The arc positions of every segment along the head's trail. */
    private double[][] trail() {
        double[][] out = new double[SEG + 2][];
        double[] prev = {hx, hy, hz};
        double want = 7, acc = 0;
        int k = 0;
        out[k++] = prev;
        for (double[] p : path) {
            double d = Math.sqrt(Math.pow(p[0] - prev[0], 2) + Math.pow(p[1] - prev[1], 2) + Math.pow(p[2] - prev[2], 2));
            while (acc + d >= want && k < out.length) {
                double f = d < 1e-9 ? 0 : (want - acc) / d;
                out[k++] = new double[]{prev[0] + (p[0] - prev[0]) * f, prev[1] + (p[1] - prev[1]) * f, prev[2] + (p[2] - prev[2]) * f};
                want += SPACING;
            }
            acc += d;
            prev = p;
            if (k >= out.length) break;
        }
        while (k < out.length) { out[k] = out[k - 1] == null ? prev : out[k - 1]; k++; }   // short trail: stack the rest
        return out;
    }

    private void send() {
        double[][] tr = trail();
        phase += 0.1 * SEND;
        double[] up = {0, 1, 0};
        // body
        for (int i = 0; i < SEG; i++) {
            double[] a = tr[i + 1], b = tr[i + 2], prevP = tr[i];
            double[] f = norm(new double[]{prevP[0] - b[0], prevP[1] - b[1], prevP[2] - b[2]});
            double w = 9.5 * Math.pow(Math.max(0, 1 - Math.pow(i / (double) (SEG + 8), 1.3)), 0.8) + 1.4;
            double hump = hit(i);
            double[] c = {a[0], a[1] + hump, a[2]};
            double[] r = norm(cross(up, f)), u = norm(cross(f, r));
            put(body.get(i), c, r, u, f, w, w * 0.9, SPACING * 1.35);
            if (i % 2 == 0) {
                double[] sp = add(c, mul(u, w * 0.45 + 1.2));
                double h = 2.2 + w * 0.35;
                put(spine.get(i / 2), sp, r, u, f, 1.0, h, SPACING * 0.7);
            }
        }
        // head
        double[] f = norm(new double[]{vx, vy * 0.6, vz});
        if (Math.abs(vx) + Math.abs(vz) < 1e-6) f = norm(new double[]{hx - tr[1][0], 0.0, hz - tr[1][2]});
        double[] r = norm(cross(up, f)), u = norm(cross(f, r));
        double[] hp0 = {hx, hy, hz};
        put(headParts.get(0), add(hp0, mul(f, 2)), r, u, f, 11, 7, 15);
        double ja = jaw * 0.75, cs = Math.cos(ja), sn = Math.sin(ja);
        double[] jf = norm(add(mul(f, cs), mul(u, -sn))), ju = norm(add(mul(u, cs), mul(f, sn)));
        put(headParts.get(1), add(add(hp0, mul(f, 1.5)), mul(u, -4.3)), r, ju, jf, 9, 2.4, 13);
        for (int s = 0; s < 2; s++) {
            double side = s == 0 ? -1 : 1;
            put(headParts.get(2 + s), add(add(add(hp0, mul(f, 7)), mul(r, side * 5.4)), mul(u, 1.6)), r, u, f, 1.9, 1.9, 1.9);
            double[] hf = norm(add(mul(f, -0.8), mul(u, 0.55))), hu = norm(add(mul(u, 0.8), mul(f, 0.55)));
            put(headParts.get(4 + s), add(add(add(hp0, mul(f, -2)), mul(r, side * 3.5)), mul(u, 4.8)), r, hu, hf, 1.4, 1.4, 9);
        }
        for (int i = 0; i < 5; i++) for (int s = 0; s < 2; s++) {
            double side = s == 0 ? -1 : 1;
            put(headParts.get(6 + i * 2 + s), add(add(add(hp0, mul(f, 5.5 - i * 2.2)), mul(r, side * 4.6)), mul(u, -1.6 + jaw * -1.2)), r, ju, jf, 0.8, 2.4, 0.8);
        }
        put(headParts.get(16), add(add(hp0, mul(f, 6)), mul(u, 3.6)), r, u, f, 10.5, 1.2, 4);
    }

    private double hit(int i) {
        // humps travelling down the body; calm during the dive and lunge, big while it circles
        double amp = state == State.LUNGE || state == State.DIVE ? 2.5 : 5.5;
        double wave = Math.sin(phase - i * 0.42);
        return amp * wave + (state == State.DIVE || state == State.LUNGE ? 0 : 0);
    }

    // ------------------------------------------------------------------ the brain

    private Player targetPlayer() {
        Player p = target == null ? null : Bukkit.getPlayer(target);
        if (p != null && p.isOnline() && p.getWorld() == world && !p.isDead()) return p;
        Player best = null; double bd = 160;
        for (Player q : world.getPlayers()) { if (q.isDead() || q.getGameMode() == GameMode.SPECTATOR) continue; double d = Math.hypot(q.getLocation().getX() - hx, q.getLocation().getZ() - hz); if (d < bd) { bd = d; best = q; } }
        target = best == null ? null : best.getUniqueId();
        return best;
    }

    private int phaseNo() { double f = hp / maxHp; return f > 0.66 ? 1 : f > 0.33 ? 2 : 3; }

    private void setState(State s) { state = s; stateT = 0; }

    private void steer(double dx, double dy, double dz, double speed, double turn) {
        double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (l < 1e-6) return;
        vx += (dx / l * speed - vx) * turn; vy += (dy / l * speed - vy) * turn; vz += (dz / l * speed - vz) * turn;
    }

    private void tick() {
        try { step(); }
        catch (RuntimeException ex) { plugin.getLogger().warning("Jormungandr failed and sank: " + ex); end(false); }
    }

    private void step() {
        t++; stateT++;
        if (world.getPlayers().isEmpty()) { end(false); return; }
        Player tp = state == State.DYING ? null : targetPlayer();
        if (tp != null) { cx += (tp.getLocation().getX() - cx) * 0.04; cz += (tp.getLocation().getZ() - cz) * 0.04; }
        else if (state != State.DYING) { if (++farTicks > 600) { end(false); return; } }
        double nearest = Double.MAX_VALUE;
        for (Player p : world.getPlayers()) nearest = Math.min(nearest, Math.hypot(p.getLocation().getX() - hx, p.getLocation().getZ() - hz));
        if (nearest > 260) { if (++farTicks > 900) { end(false); return; } } else if (tp != null) farTicks = 0;

        switch (state) {
            case APPEAR, CIRCLE, TENSION, RECOVER -> orbit();
            case DIVE -> dive(tp);
            case LUNGE -> lunge(tp);
            case DYING -> dying();
        }
        // move
        if (state != State.LUNGE) { hx += vx; hy += vy; hz += vz; }
        double[] last = path.peekFirst();
        if (last == null || Math.hypot(hx - last[0], hz - last[2]) + Math.abs(hy - last[1]) >= 1.0) { path.addFirst(new double[]{hx, hy, hz}); if (path.size() > 520) path.removeLast(); }
        jaw += ((state == State.LUNGE && stateT > 12 && stateT < 40 ? 1.0 : state == State.TENSION ? 0.35 : 0.08) - jaw) * 0.25;
        if (hitbox != null && hitbox.isValid()) hitbox.teleport(new Location(world, hx, hy - 3.5, hz));
        if (t % SEND == 0) send();
        if (t % 20 == 0) { updateBar(); ambience(); }
        if (t % 4 == 0) contact();
        if (state != State.DYING && hp <= 0) { setState(State.DYING); reward(); }
    }

    private void orbit() {
        int ph = phaseNo();
        double R, speed;
        switch (state) {
            case APPEAR -> { R = 125; speed = 0.7; if (stateT > 360) setState(State.CIRCLE); }
            case CIRCLE -> { R = 125 - 90 * Math.min(1, stateT / 1500.0); speed = 0.85; if (stateT > 1500) setState(State.TENSION); }
            case TENSION -> { R = 34; speed = 1.05; if (stateT > 220) { setState(State.DIVE); lungesLeft = ph >= 2 ? 2 : 1; } }
            default -> { R = 46; speed = 0.95 + 0.1 * ph; if (stateT > (ph == 3 ? 120 : ph == 2 ? 200 : 280) + 0) { setState(State.DIVE); lungesLeft = ph >= 2 ? 2 : 1; } }
        }
        theta += speed / R;
        double lead = 0.28;
        double tx = cx + Math.cos(theta + lead) * R, tz = cz + Math.sin(theta + lead) * R;
        steer(tx - hx, (SEA - 2 - hy), tz - hz, speed, 0.07);
        if (state == State.TENSION && stateT % 70 == 0) for (Player p : world.getPlayers()) if (p.getLocation().distance(new Location(world, hx, hy, hz)) < 120) p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, SoundCategory.HOSTILE, 1.4f, 0.5f);
    }

    private void dive(Player tp) {
        if (tp == null) { setState(State.RECOVER); return; }
        Location l = tp.getLocation();
        double ang = Math.atan2(hz - l.getZ(), hx - l.getX());
        double px = l.getX() + Math.cos(ang) * 26, pz = l.getZ() + Math.sin(ang) * 26;
        steer(px - hx, (SEA - 20) - hy, pz - hz, 1.5, 0.12);
        if (stateT == 1) for (Player p : world.getPlayers()) p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_SPLASH, SoundCategory.HOSTILE, 2f, 0.4f);
        if (stateT > 36 || (Math.hypot(px - hx, pz - hz) < 6 && stateT > 15)) {
            lungeFrom = new double[]{hx, hy, hz};
            lungeApex = new double[]{l.getX() + tp.getVelocity().getX() * 14, Math.max(l.getY(), SEA) + 12, l.getZ() + tp.getVelocity().getZ() * 14};
            double dx = lungeApex[0] - hx, dz = lungeApex[2] - hz, len = Math.max(1, Math.hypot(dx, dz));
            lungeTo = new double[]{lungeApex[0] + dx / len * 24, SEA - 16, lungeApex[2] + dz / len * 24};
            lungeCtl = new double[]{lungeApex[0] * 2 - (lungeFrom[0] + lungeTo[0]) / 2, lungeApex[1] * 2 - (lungeFrom[1] + lungeTo[1]) / 2, lungeApex[2] * 2 - (lungeFrom[2] + lungeTo[2]) / 2};
            lungeHit = false;
            setState(State.LUNGE);
            for (Player p : world.getPlayers()) { if (p.getLocation().distance(l) < 90) p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_ROAR, SoundCategory.HOSTILE, 2.5f, 0.6f); }
        }
    }

    private void lunge(Player tp) {
        double s = Math.min(1, stateT / 46.0);
        double a = (1 - s) * (1 - s), b = 2 * (1 - s) * s, c = s * s;
        double nx = a * lungeFrom[0] + b * lungeCtl[0] + c * lungeTo[0], ny = a * lungeFrom[1] + b * lungeCtl[1] + c * lungeTo[1], nz = a * lungeFrom[2] + b * lungeCtl[2] + c * lungeTo[2];
        vx = nx - hx; vy = ny - hy; vz = nz - hz;
        hx = nx; hy = ny; hz = nz;
        Location head = new Location(world, hx, hy, hz);
        if (hy > SEA - 2) world.spawnParticle(Particle.SPLASH, head, 40, 4, 1, 4, 0.5);
        if (!lungeHit && s >= 0.42 && s <= 0.62) {
            lungeHit = true;
            world.playSound(head, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.HOSTILE, 4f, 0.5f);
            world.spawnParticle(Particle.EXPLOSION, head, 6, 3, 2, 3, 0);
            double dmg = 18 + 6 * phaseNo();
            for (Player p : world.getPlayers()) {
                if (p.getLocation().distance(head) > 9.5 || p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE) continue;
                p.damage(dmg);
                p.setVelocity(new Vector(vx, 0, vz).normalize().multiply(1.1).setY(0.9));
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 100, 1));
            }
            for (Entity e : world.getNearbyEntities(head, 11, 8, 11)) if (e instanceof Boat || e instanceof ChestBoat) {
                for (Entity pass : new ArrayList<>(e.getPassengers())) e.removePassenger(pass);
                e.remove();
            }
        }
        if (s >= 1) {
            lungesLeft--;
            if (lungesLeft > 0) { setState(State.DIVE); strikes++; }
            else {
                strikes++;
                setState(State.RECOVER);
                if (phaseNo() == 3) for (Player p : world.getPlayers()) if (p.getLocation().distance(head) < 70) p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 160, 0));
            }
        }
    }

    private void dying() {
        vx *= 0.97; vz *= 0.97; vy = -0.35;
        phase += 0.05;
        if (stateT % 10 == 0) world.playSound(new Location(world, hx, hy, hz), Sound.ENTITY_WARDEN_DEATH, SoundCategory.HOSTILE, 3f, 0.5f);
        if (stateT == 1) for (Player p : world.getPlayers()) p.showTitle(net.kyori.adventure.title.Title.title(Ui.mm("<gold><bold>JÖRMUNGANDR FALLS"), Ui.mm("<gray>The sea is yours"), net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(300), java.time.Duration.ofSeconds(4), java.time.Duration.ofSeconds(2))));
        if (stateT > 200) end(true);
    }

    /** Coils shove players off a boat and bruise them, so being close to the body is never safe. */
    private void contact() {
        double[][] tr = trail();
        for (Player p : world.getPlayers()) {
            Location l = p.getLocation();
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            for (int i = 1; i < SEG; i += 2) {
                double[] a = tr[i + 1];
                double w = 9.5 * Math.pow(Math.max(0, 1 - Math.pow(i / (double) (SEG + 8), 1.3)), 0.8) + 1.4;
                double dx = l.getX() - a[0], dz = l.getZ() - a[2];
                if (Math.hypot(dx, dz) > w * 0.7 + 1.5 || Math.abs(l.getY() - (a[1] + hit(i))) > w * 0.7 + 2) continue;
                int cd = contactCooldown.getOrDefault(p.getUniqueId(), 0);
                if (t < cd) break;
                contactCooldown.put(p.getUniqueId(), t + 25);
                Vector away = new Vector(dx, 0, dz);
                if (away.lengthSquared() < 1e-4) away = new Vector(1, 0, 0);
                p.setVelocity(away.normalize().multiply(1.0).setY(0.45));
                p.damage(5);
                world.playSound(l, Sound.ENTITY_GENERIC_SPLASH, SoundCategory.HOSTILE, 1.5f, 0.6f);
                break;
            }
        }
    }

    private void ambience() {
        Location head = new Location(world, hx, hy, hz);
        for (Player p : world.getPlayers()) {
            double d = p.getLocation().distance(head);
            if (d > 300) continue;
            if (state == State.APPEAR || state == State.CIRCLE) p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, (float) Math.max(0.4, 2.2 - d / 90), 0.6f);
            if (t % 100 == 0 && d < 200) p.playSound(p.getLocation(), Sound.BLOCK_WATER_AMBIENT, SoundCategory.HOSTILE, 1.5f, 0.5f);
        }
    }

    private void updateBar() {
        bar.setTitle("§3§lJörmungandr §7- the World Serpent");
        bar.setProgress(Math.max(0, Math.min(1, hp / maxHp)));
        bar.setColor(phaseNo() == 1 ? BarColor.GREEN : phaseNo() == 2 ? BarColor.YELLOW : BarColor.RED);
        for (Player p : world.getPlayers()) {
            boolean near = Math.hypot(p.getLocation().getX() - hx, p.getLocation().getZ() - hz) < 220;
            if (near) bar.addPlayer(p); else bar.removePlayer(p);
        }
    }

    // ------------------------------------------------------------------ being hurt, and the reward

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (hitbox == null || e.getEntity() != hitbox) return;
        e.setCancelled(true);
        if (state == State.DYING) return;
        Player who = null;
        if (e instanceof EntityDamageByEntityEvent be) {
            Entity d = be.getDamager();
            if (d instanceof Player p) who = p;
            else if (d instanceof Projectile pr && pr.getShooter() instanceof Player p) who = p;
        }
        if (who == null) return;
        double dmg = Math.min(60, e.getDamage());
        hp -= dmg;
        damage.merge(who.getUniqueId(), dmg, Double::sum);
        world.spawnParticle(Particle.DAMAGE_INDICATOR, hitbox.getLocation().add(0, 3, 0), 6, 1.5, 1, 1.5, 0.1);
        world.playSound(hitbox.getLocation(), Sound.ENTITY_IRON_GOLEM_HURT, SoundCategory.HOSTILE, 1.5f, 0.4f);
        who.sendActionBar(Ui.mm("<dark_aqua>Jörmungandr <white>" + Math.max(0, (int) hp) + "<gray>/" + (int) maxHp));
    }

    private void reward() {
        double total = 0;
        for (double d : damage.values()) total += d;
        String names = "";
        for (Map.Entry<UUID, Double> en : damage.entrySet()) {
            if (en.getValue() < Math.max(60, total * 0.04)) continue;
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null) continue;
            names += (names.isEmpty() ? "" : ", ") + p.getName();
            String n = p.getName();
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "blackforge crate give " + n + " netherite 3");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "blackforge crate give " + n + " gold 5");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "eco give " + n + " 15000");
            ItemStack heart = new ItemStack(Material.HEART_OF_THE_SEA);
            ItemMeta m = heart.getItemMeta();
            m.displayName(Ui.mm("<#2bd9a5><bold>Heart of Jörmungandr"));
            m.lore(List.of(Ui.mm("<gray>Torn from the World Serpent of the Abyssal Sea."), Ui.mm("<dark_gray>Slain by " + n)));
            heart.setItemMeta(m);
            ItemStack scales = new ItemStack(Material.PRISMARINE_SHARD, 8);
            ItemMeta sm = scales.getItemMeta();
            sm.displayName(Ui.mm("<#2bd9a5>Serpent Scale"));
            sm.lore(List.of(Ui.mm("<gray>Cold, heavy, and larger than your hand.")));
            scales.setItemMeta(sm);
            p.getInventory().addItem(heart, scales).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        }
        Bukkit.broadcast(Ui.mm("<#2bd9a5>✦ <white>" + (names.isEmpty() ? "The sea" : names) + " <gray>slew <#2bd9a5><bold>Jörmungandr<gray>, the World Serpent of the Abyssal Sea!"));
    }

    void end(boolean killed) {
        if (task != null) task.cancel();
        task = null;
        HandlerList.unregisterAll(this);
        bar.removeAll();
        body.forEach(Entity::remove); spine.forEach(Entity::remove); headParts.forEach(Entity::remove);
        body.clear(); spine.clear(); headParts.clear();
        if (hitbox != null) hitbox.remove();
        hitbox = null;
        if (!killed) for (Player p : world.getPlayers()) p.sendMessage(Ui.mm("<dark_aqua>The water settles. Whatever it was has gone back down."));
        onEnd.run();
    }

    void forceEnd() { end(false); }
    boolean alive() { return task != null; }
}
