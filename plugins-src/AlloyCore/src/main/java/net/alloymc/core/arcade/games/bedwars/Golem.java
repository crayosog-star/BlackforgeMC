package net.alloymc.core.arcade.games.bedwars;

import net.alloymc.core.Ui;
import net.alloymc.core.arcade.core.Team;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/**
 * The Golem: the Undercroft's mini boss in Forge Wars. It rises out of the hall floor, fights whoever is down there and
 * gives the team that hurts it most the Golem's Heart. See the plan doc for the design; the numbers live in
 * arcade/games/forgewars.yml (golem-*).
 *
 * Body: an invisible, scaled-up iron golem with no AI carries the hitbox and health; the plugin moves it by teleporting.
 * Look: one block display per {@link GolemModel} part, all standing on the body's spot and turned with its yaw.
 * Everything runs off {@link #tick()} (called every tick by the match), so there are no scheduler tasks to leak.
 */
final class Golem {
    enum State { RISING, WALK, WINDUP, ACT, STUNNED, DEAD }

    private static final double LEASH = 7.5, SPEED = 0.11, ENRAGED_SPEED = 0.15, RING_SPEED = 0.3, RING_REACH = 12, CHARGE_SPEED = 0.55;
    private static final Particle.DustOptions PINK = new Particle.DustOptions(Color.fromRGB(GolemModel.ORE_PINK), 1.4f),
            GREEN = new Particle.DustOptions(Color.fromRGB(GolemModel.HEART_GREEN), 1.2f),
            RED = new Particle.DustOptions(Color.fromRGB(0xFF3030), 1.1f);
    private static final BlockData STONE = Bukkit.createBlockData(Material.MOSSY_STONE_BRICKS);

    private final BedWarsMatch m;
    private final World world;
    private final Location home;   // middle of the hall, standing height
    private final Random rnd = new Random();
    private final List<BlockDisplay> parts = new ArrayList<>();
    private final List<GolemModel.Part> data = new ArrayList<>();
    private final List<Display> fx = new ArrayList<>();
    private final Map<Display, Integer> fxRemoveAt = new HashMap<>();
    private final BossBar bar;
    private final Set<UUID> viewers = new HashSet<>();
    private final Map<Team, Double> teamDamage = new HashMap<>();
    private final Map<UUID, Double> playerDamage = new HashMap<>();
    private final Map<UUID, Integer> lastHitBy = new HashMap<>();
    private final Map<UUID, Integer> rooted = new HashMap<>();
    private final Set<UUID> hit = new HashSet<>();
    private final Set<String> slamHit = new HashSet<>();   // "player:wave" already caught by a slam ring
    private final Map<double[], BlockDisplay> rockShown = new IdentityHashMap<>();
    private final List<double[]> rocks = new ArrayList<>();   // x, z, impact tick, rock display index (-1 = not dropped yet)
    private final List<Location> marks = new ArrayList<>();
    private final double maxHealth;
    private final double damageScale;
    private IronGolem body;
    private Team lastTeam;
    private State state = State.RISING;
    private GolemMath.Move move;
    private Player target;
    private Location aim;
    private Vector chargeDir;
    private BlockDisplay boulder;
    private float yaw;
    private int t, stateT, windup, stunTicks, nextMove = 100, swipeCd, idle, phaseShown = 1, flight;
    private double heartScale = 1;
    private boolean removed;

    Golem(BedWarsMatch m, World world, Location home, int playersBelow) {
        this.m = m;
        this.world = world;
        this.home = home.clone();
        this.maxHealth = GolemMath.health(playersBelow, m.setting("golem-health-base", 300), m.setting("golem-health-per-player", 60));
        this.damageScale = m.setting("golem-damage-percent", 100) / 100.0;
        bar = BossBar.bossBar(Ui.mm(title()), 1f, BossBar.Color.PINK, BossBar.Overlay.NOTCHED_10);
        Location at = this.home.clone();
        at.setYaw(0);
        for (GolemModel.Part p : GolemModel.PARTS) { data.add(p); parts.add(spawnPart(at, p)); }
        body = spawnBody(at);
        world.playSound(home, Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, 3f, 0.6f);
        world.playSound(home, Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 2.5f, 0.5f);
        world.spawnParticle(Particle.BLOCK, home, 120, 2.5, 0.3, 2.5, STONE);
    }

    // ------------------------------------------------------------------ the model

    private BlockDisplay spawnPart(Location at, GolemModel.Part p) {
        BlockData block = Bukkit.createBlockData(p.block());
        return world.spawn(at, BlockDisplay.class, e -> {
            e.setBlock(block);
            e.setPersistent(false);
            e.setBrightness(p.bright() ? new Display.Brightness(15, 15) : new Display.Brightness(11, 11));
            e.setViewRange(1.6f);
            e.setTeleportDuration(2);
            e.setTransformation(transform(p, 0, -GolemModel.height() - 0.2, 0, 1));   // starts under the floor: it rises in the first 3 s
            if (p.glow() >= 0) { e.setGlowing(true); e.setGlowColorOverride(Color.fromRGB(p.glow())); }
        });
    }

    private IronGolem spawnBody(Location at) {
        return world.spawn(at, IronGolem.class, g -> {
            g.setAI(false);
            g.setInvisible(true);
            g.setSilent(true);
            g.setPersistent(false);
            g.setRemoveWhenFarAway(false);
            g.setPlayerCreated(false);
            set(g, Attribute.MAX_HEALTH, maxHealth);
            set(g, Attribute.SCALE, 2.0);   // iron golem 1.4 x 2.7 -> a 2.8 x 5.4 hitbox round the model's body
            set(g, Attribute.KNOCKBACK_RESISTANCE, 1);
            g.setHealth(maxHealth);
        });
    }

    private static void set(LivingEntity e, Attribute a, double v) { var i = e.getAttribute(a); if (i != null) i.setBaseValue(v); }

    /** A part's display transformation, shifted by (ox, oy, oz) and scaled about its own middle. */
    static Transformation transform(GolemModel.Part p, double ox, double oy, double oz, double scale) {
        Quaternionf rot = new Quaternionf().rotateZ((float) Math.toRadians(p.rz())).rotateX((float) Math.toRadians(p.rx()));
        float sx = (float) (p.sx() * scale), sy = (float) (p.sy() * scale), sz = (float) (p.sz() * scale);
        Vector3f half = new Vector3f(sx / 2, sy / 2, sz / 2).rotate(new Quaternionf(rot));
        return new Transformation(new Vector3f((float) (p.cx() + ox) - half.x, (float) (p.cy() + oy) - half.y, (float) (p.cz() + oz) - half.z),
                rot, new Vector3f(sx, sy, sz), new Quaternionf());
    }

    /** Glide every part of a group to an offset from its rest pose over {@code ticks}. */
    private void pose(GolemModel.Group g, double dx, double dy, double dz, int ticks) {
        for (int i = 0; i < parts.size(); i++) {
            GolemModel.Part p = data.get(i);
            if (p.group() != g || !parts.get(i).isValid()) continue;
            BlockDisplay d = parts.get(i);
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(ticks);
            d.setTransformation(transform(p, dx, dy, dz, g == GolemModel.Group.HEART ? heartScale : 1));
        }
    }

    private void rest(int ticks) { for (GolemModel.Group g : GolemModel.Group.values()) pose(g, 0, 0, 0, ticks); }

    /** Keep every part standing on the body, turned the way it faces. */
    private void follow() {
        if (body == null) return;
        Location at = body.getLocation();
        at.setYaw(yaw);
        at.setPitch(0);
        for (BlockDisplay d : parts) if (d.isValid()) d.teleport(at);
    }

    // ------------------------------------------------------------------ the fight

    boolean owns(Entity e) { return body != null && e.getUniqueId().equals(body.getUniqueId()); }

    boolean removed() { return removed; }

    private int phase() { return body == null ? 3 : GolemMath.phase(body.getHealth(), maxHealth); }

    private String title() {
        String ph = switch (phaseShown) { case 1 -> ""; case 2 -> " <gray>· cracked"; default -> " <red>· ENRAGED"; };
        return "<#ff3fa4><bold>THE GOLEM</bold>" + ph;
    }

    /** Called every tick by the match. */
    void tick() {
        if (removed) return;
        t++;
        stateT++;
        if (state != State.DEAD && (body == null || !body.isValid())) { remove(); return; }
        if (t == 3) for (GolemModel.Group g : GolemModel.Group.values()) pose(g, 0, 0, 0, 57);   // rise out of the floor
        if (t % 10 == 0) refreshBar();
        tickRoots();
        tickRocks();
        tickFx();
        switch (state) {
            case RISING -> {
                if (t % 4 == 0) world.spawnParticle(Particle.BLOCK, home, 25, 2.2, 0.2, 2.2, STONE);
                if (stateT >= 60) { to(State.WALK); world.playSound(home, Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 3f, 0.6f); }
            }
            case WALK -> walk();
            case WINDUP -> windup();
            case ACT -> act();
            case STUNNED -> {
                if (t % 4 == 0) world.spawnParticle(Particle.CRIT, body.getLocation().add(0, 5.8, 0), 6, 0.6, 0.2, 0.6, 0.05);
                if (stateT >= stunTicks) { to(State.WALK); rest(8); }
            }
            case DEAD -> {
                if (stateT >= 40) remove();
                return;
            }
        }
        follow();
    }

    private void to(State s) { state = s; stateT = 0; }

    private void refreshBar() {
        if (body == null) return;
        bar.progress((float) Math.max(0, Math.min(1, body.getHealth() / maxHealth)));
        bar.name(Ui.mm(title()));
        Set<UUID> now = new HashSet<>();
        for (Player p : m.undercroftPlayers(home)) { now.add(p.getUniqueId()); p.showBossBar(bar); }
        for (UUID id : viewers) if (!now.contains(id)) { Player p = Bukkit.getPlayer(id); if (p != null) p.hideBossBar(bar); }
        viewers.clear();
        viewers.addAll(now);
    }

    private void retarget(List<Player> below) {
        Player best = null;
        double bestScore = Double.MAX_VALUE;
        for (Player p : below) {
            if (!body.hasLineOfSight(p)) continue;
            double d = p.getLocation().distance(body.getLocation());
            Integer last = lastHitBy.get(p.getUniqueId());
            if (last != null && t - last < 100) d -= 8;   // whoever hit it lately draws its attention
            if (d < bestScore) { bestScore = d; best = p; }
        }
        target = best;
    }

    private boolean high(Player p) { return p.getLocation().getY() > home.getY() + 2.5; }

    private void walk() {
        List<Player> below = m.undercroftPlayers(home);
        if (t % 20 == 0 || target == null || !target.isOnline() || !m.isAlive(target)) retarget(below);
        if (below.isEmpty()) {
            if (++idle > 400 && body.getHealth() < maxHealth) body.setHealth(Math.min(maxHealth, body.getHealth() + maxHealth * 0.02 / 20));
        } else idle = 0;
        if (target == null) { stepTo(home, SPEED); return; }
        Location tl = target.getLocation();
        double dx = tl.getX() - body.getLocation().getX(), dz = tl.getZ() - body.getLocation().getZ(), dist = Math.hypot(dx, dz);
        yaw = GolemMath.yawTo(dx, dz);
        swipeCd--;
        if (--nextMove <= 0) { start(GolemMath.pick(phase(), dist, high(target), below.size(), rnd)); return; }
        if (dist <= 4.1 && swipeCd <= 0 && !high(target)) { start(GolemMath.Move.SWIPE); return; }
        if (dist > 3.4) stepTo(tl, phase() >= 3 ? ENRAGED_SPEED : SPEED);
    }

    /** Walk towards {@code dest} (kept on the hall floor), sliding along anything in the way. */
    private void stepTo(Location dest, double speed) {
        Location at = body.getLocation();
        double dx = dest.getX() - at.getX(), dz = dest.getZ() - at.getZ(), d = Math.hypot(dx, dz);
        if (d < 0.2) return;
        double nx = at.getX() + dx / d * speed, nz = at.getZ() + dz / d * speed;
        if (blocked(nx, nz)) {
            if (!blocked(nx, at.getZ())) nz = at.getZ();
            else if (!blocked(at.getX(), nz)) nx = at.getX();
            else return;
        }
        double[] l = GolemMath.leash(nx - home.getX(), nz - home.getZ(), LEASH);
        body.teleport(new Location(world, home.getX() + l[0], home.getY(), home.getZ() + l[1], yaw, 0));
    }

    /** Something solid where the Golem's feet, waist or shoulders would be at (x, z)? */
    private boolean blocked(double x, double z) {
        int fy = home.getBlockY();
        for (double[] o : new double[][]{{0, 0}, {1.3, 0}, {-1.3, 0}, {0, 1.3}, {0, -1.3}})
            for (int y = 0; y <= 2; y++)
                if (!world.getBlockAt((int) Math.floor(x + o[0]), fy + y, (int) Math.floor(z + o[1])).isPassable()) return true;
        return false;
    }

    /** Pillar-height blocking: a charge that runs into this is stunned. */
    private boolean wallAt(double x, double z) {
        int fy = home.getBlockY();
        for (double[] o : new double[][]{{0, 0}, {1.4, 0}, {-1.4, 0}, {0, 1.4}, {0, -1.4}})
            if (!world.getBlockAt((int) Math.floor(x + o[0]), fy + 2, (int) Math.floor(z + o[1])).isPassable()) return true;
        return false;
    }

    // ------------------------------------------------------------------ moves

    private void start(GolemMath.Move mv) {
        move = mv;
        to(State.WINDUP);
        windup = GolemMath.telegraph(mv, phase());
        hit.clear();
        slamHit.clear();
        marks.clear();
        flare();
        switch (mv) {
            case SWIPE -> pose(GolemModel.Group.ARM_R, 0, 0.9, -0.5, windup);
            case SLAM -> { pose(GolemModel.Group.ARM_L, 0, 1.7, -0.7, windup); pose(GolemModel.Group.ARM_R, 0, 1.7, -0.7, windup); }
            case BOULDER -> {
                Player p = farTarget();
                aim = (p != null ? p : target).getLocation().clone();
                pose(GolemModel.Group.ARM_R, 0, 1.5, -0.9, windup);
            }
            case ROOTS -> {
                List<Player> below = new ArrayList<>(m.undercroftPlayers(home));
                Collections.shuffle(below, rnd);
                for (Player p : below.subList(0, Math.min(3, below.size()))) marks.add(p.getLocation().clone());
            }
            case CHARGE -> {
                Location tl = target.getLocation();
                chargeDir = new Vector(tl.getX() - body.getLocation().getX(), 0, tl.getZ() - body.getLocation().getZ());
                if (chargeDir.lengthSquared() < 0.01) chargeDir = new Vector(0, 0, 1);
                chargeDir.normalize();
                pose(GolemModel.Group.HEAD, 0, -0.3, 0.3, windup);
                world.playSound(body.getLocation(), Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 2f, 1.1f);
            }
            case ROCKFALL -> {
                pose(GolemModel.Group.HEAD, 0, 0.4, -0.2, windup);
                world.playSound(body.getLocation(), Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 3f, 0.4f);
            }
        }
    }

    /** The heart-stone flares before every move: the "get ready" tell. */
    private void flare() {
        Location c = body.getLocation().add(0, 2.1 * GolemModel.SCALE, 0);
        world.spawnParticle(Particle.DUST, c, 20, 0.4, 0.4, 0.4, GREEN);
        world.playSound(c, Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, 1.5f, 1.4f);
    }

    private Player farTarget() {
        Player best = null;
        double far = -1;
        for (Player p : m.undercroftPlayers(home)) {
            double d = p.getLocation().distance(body.getLocation()) + (high(p) ? 20 : 0);   // players up high come first
            if (d > far && body.hasLineOfSight(p)) { far = d; best = p; }
        }
        return best;
    }

    private void windup() {
        if (stateT % 2 == 0) switch (move) {
            case SLAM -> ring(body.getLocation(), 1.6 + (stateT % 6) * 0.15, Particle.BLOCK);
            case BOULDER -> circle(aim, 2.5, PINK);
            case ROOTS -> { for (Location l : marks) circle(l, 1.3, GREEN); }
            case CHARGE -> {
                Location c = body.getLocation().add(0, 0.2, 0);
                for (double s = 2; s <= 14; s += 0.8) world.spawnParticle(Particle.DUST, c.clone().add(chargeDir.clone().multiply(s)), 1, 0.15, 0, 0.15, RED);
            }
            default -> {}
        }
        if (stateT >= windup) { to(State.ACT); flight = 0; }
    }

    private void act() {
        switch (move) {
            case SWIPE -> {
                Location at = body.getLocation();
                for (Player p : m.undercroftPlayers(home)) {
                    Location l = p.getLocation();
                    double dx = l.getX() - at.getX(), dz = l.getZ() - at.getZ();
                    if (Math.hypot(dx, dz) <= 4.5 && Math.abs(l.getY() - at.getY()) < 3 && GolemMath.inFront(yaw, dx, dz, 75))
                        hurt(p, 4, new Vector(dx, 0, dz).normalize().multiply(0.9).setY(0.35));
                }
                world.playSound(at, Sound.ENTITY_IRON_GOLEM_ATTACK, SoundCategory.HOSTILE, 1.5f, 0.6f);
                world.spawnParticle(Particle.SWEEP_ATTACK, at.clone().add(new Vector(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw))).multiply(2)).add(0, 1.2, 0), 3, 0.6, 0.2, 0.6);
                pose(GolemModel.Group.ARM_R, 0, -0.2, 0.6, 3);
                swipeCd = 30;
                end(8);
            }
            case SLAM -> {
                Location at = body.getLocation();
                if (stateT == 1) {
                    pose(GolemModel.Group.ARM_L, 0, -0.3, 0.4, 3);
                    pose(GolemModel.Group.ARM_R, 0, -0.3, 0.4, 3);
                    world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 2f, 0.6f);
                    world.spawnParticle(Particle.EXPLOSION, at.clone().add(0, 0.3, 0), 3, 1.5, 0.1, 1.5);
                }
                boolean twice = phase() >= 3;
                for (int wave = 0; wave < (twice ? 2 : 1); wave++) {
                    int tt = stateT - wave * 15;
                    if (tt < 1) continue;
                    double r = tt * RING_SPEED;
                    if (r <= RING_REACH) ring(at, r, Particle.DUST);
                    for (Player p : m.undercroftPlayers(home)) {
                        Location l = p.getLocation();
                        double d = Math.hypot(l.getX() - at.getX(), l.getZ() - at.getZ());
                        if (GolemMath.ringHits(d, tt, RING_SPEED, 1.4, l.getY() - home.getY(), RING_REACH)
                                && slamHit.add(p.getUniqueId() + ":" + wave)) hurt(p, 6, new Vector(0, 0.75, 0));   // each wave hits a player once
                    }
                }
                if (stateT * RING_SPEED > RING_REACH + (twice ? 15 * RING_SPEED : 0)) end(8);
            }
            case BOULDER -> {
                Location from = body.getLocation().add(new Vector(-Math.sin(Math.toRadians(yaw - 50)), 0, Math.cos(Math.toRadians(yaw - 50))).multiply(3.2)).add(0, 4.5, 0);
                int ticks = (int) Math.max(12, Math.min(24, from.distance(aim) * 1.1));
                if (stateT == 1) {
                    pose(GolemModel.Group.ARM_R, 0, 0.3, 0.9, 4);
                    boulder = world.spawn(from, BlockDisplay.class, e -> {
                        e.setBlock(Bukkit.createBlockData(Material.COBBLESTONE));
                        e.setPersistent(false);
                        e.setTeleportDuration(1);
                        e.setTransformation(new Transformation(new Vector3f(-0.6f, -0.6f, -0.6f), new Quaternionf(), new Vector3f(1.2f), new Quaternionf()));
                    });
                    world.playSound(from, Sound.BLOCK_STONE_BREAK, SoundCategory.HOSTILE, 2f, 0.5f);
                }
                if (boulder != null && boulder.isValid()) {
                    double f = Math.min(1, stateT / (double) ticks);
                    double[] p = GolemMath.arc(from.getX(), from.getY(), from.getZ(), aim.getX(), aim.getY() + 0.6, aim.getZ(), 4, f);
                    boulder.teleport(new Location(world, p[0], p[1], p[2]));
                    if (f >= 1) {
                        boulder.remove();
                        boulder = null;
                        world.spawnParticle(Particle.EXPLOSION, aim, 2, 0.5, 0.2, 0.5);
                        world.spawnParticle(Particle.BLOCK, aim, 60, 1.2, 0.3, 1.2, Bukkit.createBlockData(Material.COBBLESTONE));
                        world.playSound(aim, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 1.5f, 0.8f);
                        for (Player pl : m.undercroftPlayers(home)) {
                            Vector away = pl.getLocation().toVector().subtract(aim.toVector());
                            if (away.length() <= 3) hurt(pl, 7, away.setY(0).normalize().multiply(0.8).setY(0.5));
                        }
                        end(10);
                    }
                } else end(10);
            }
            case ROOTS -> {
                for (Location l : marks) {
                    for (Player p : m.undercroftPlayers(home))
                        if (p.getLocation().distance(l) <= 1.5 && hit.add(p.getUniqueId())) {
                            hurt(p, 2, new Vector(0, 0, 0));
                            rooted.put(p.getUniqueId(), 40);
                            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 6, false, false));
                        }
                    for (int i = 0; i < 4; i++) {
                        double a = Math.PI / 2 * i + rnd.nextDouble();
                        Location at = l.clone().add(Math.cos(a) * 0.6, -0.2, Math.sin(a) * 0.6);
                        BlockDisplay r = world.spawn(at, BlockDisplay.class, e -> {
                            e.setBlock(Bukkit.createBlockData(Material.MANGROVE_ROOTS));
                            e.setPersistent(false);
                            e.setTransformation(new Transformation(new Vector3f(-0.15f, -1.2f, -0.15f), new Quaternionf().rotateZ((float) (rnd.nextDouble() - 0.5)), new Vector3f(0.3f, 1.2f, 0.3f), new Quaternionf()));
                        });
                        r.setInterpolationDelay(0);
                        r.setInterpolationDuration(4);
                        r.setTransformation(new Transformation(new Vector3f(-0.15f, 0, -0.15f), new Quaternionf().rotateZ((float) (rnd.nextDouble() - 0.5)), new Vector3f(0.3f, 1.8f, 0.3f), new Quaternionf()));
                        fx.add(r);
                        fxRemoveAt.put(r, t + 40);
                    }
                    world.playSound(l, Sound.BLOCK_ROOTS_BREAK, SoundCategory.HOSTILE, 2f, 0.6f);
                }
                end(6);
            }
            case CHARGE -> {
                Location at = body.getLocation();
                double nx = at.getX() + chargeDir.getX() * CHARGE_SPEED, nz = at.getZ() + chargeDir.getZ() * CHARGE_SPEED;
                double[] l = GolemMath.leash(nx - home.getX(), nz - home.getZ(), LEASH);
                boolean leashed = Math.abs(l[0] - (nx - home.getX())) > 1e-6 || Math.abs(l[1] - (nz - home.getZ())) > 1e-6;
                if (wallAt(nx, nz)) { stun(60, "<#ff3fa4>The Golem slammed into a pillar! <white>It's stunned and takes +50% damage."); return; }
                if (leashed || blocked(nx, nz) || stateT > 32) { end(10); return; }
                body.teleport(new Location(world, nx, home.getY(), nz, yaw, 0));
                if (stateT % 2 == 0) world.spawnParticle(Particle.BLOCK, at, 12, 1.2, 0.1, 1.2, STONE);
                for (Player p : m.undercroftPlayers(home)) {
                    Location pl = p.getLocation();
                    if (Math.hypot(pl.getX() - nx, pl.getZ() - nz) <= 2.4 && Math.abs(pl.getY() - home.getY()) < 3 && hit.add(p.getUniqueId())) {
                        Vector side = new Vector(-chargeDir.getZ(), 0, chargeDir.getX());
                        if (side.dot(pl.toVector().subtract(at.toVector())) < 0) side.multiply(-1);
                        hurt(p, 7, chargeDir.clone().multiply(0.9).add(side.multiply(0.8)).setY(0.55));
                    }
                }
            }
            case ROCKFALL -> {
                List<Player> below = m.undercroftPlayers(home);
                for (int i = 0; i < 10; i++) {
                    double x, z;
                    if (!below.isEmpty() && rnd.nextBoolean()) {
                        Location pl = below.get(rnd.nextInt(below.size())).getLocation();
                        x = pl.getX() - home.getX() + rnd.nextGaussian();
                        z = pl.getZ() - home.getZ() + rnd.nextGaussian();
                    } else {
                        double a = rnd.nextDouble() * Math.PI * 2, r = Math.sqrt(rnd.nextDouble()) * 11;
                        x = Math.cos(a) * r;
                        z = Math.sin(a) * r;
                    }
                    rocks.add(new double[]{home.getX() + x, home.getZ() + z, t + 20 + i * 10, -1});
                }
                end(10);
            }
        }
    }

    private void end(int restTicks) {
        rest(restTicks);
        to(State.WALK);
        nextMove = GolemMath.moveGap(phase()) + rnd.nextInt(20);
    }

    private void stun(int ticks, String msg) {
        to(State.STUNNED);
        stunTicks = ticks;
        pose(GolemModel.Group.HEAD, 0, -0.5, 0.2, 6);
        world.playSound(body.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 1.5f, 1.4f);
        world.spawnParticle(Particle.BLOCK, body.getLocation().add(0, 2, 0), 60, 1.2, 1, 1.2, STONE);
        for (Player p : m.undercroftPlayers(home)) p.sendMessage(Ui.mm(msg));
    }

    /** Hurt a player, scaled by golem-damage-percent, with a shove. */
    private void hurt(Player p, double dmg, Vector push) {
        if (!m.isAlive(p) || p.getGameMode() != GameMode.SURVIVAL) return;
        p.damage(dmg * damageScale, body);
        if (push.lengthSquared() > 0) p.setVelocity(push);
    }

    private void tickRoots() {
        for (var it = rooted.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null || e.getValue() <= 0) { it.remove(); continue; }
            e.setValue(e.getValue() - 1);
            Vector v = p.getVelocity();
            p.setVelocity(new Vector(0, Math.min(0, v.getY()), 0));   // held in place
            if (e.getValue() % 5 == 0) world.spawnParticle(Particle.BLOCK, p.getLocation(), 6, 0.3, 0.1, 0.3, Bukkit.createBlockData(Material.ROOTED_DIRT));
        }
    }

    private void tickRocks() {
        for (var it = rocks.iterator(); it.hasNext(); ) {
            double[] r = it.next();
            int left = (int) (r[2] - t);
            Location spot = new Location(world, r[0], home.getY() + 0.1, r[1]);
            if (left > 0 && left % 3 == 0) circle(spot, 1.6, RED);
            if (left == 15) {   // the rock appears under the ceiling...
                Location top = new Location(world, r[0], home.getY() + 17, r[1]);
                BlockDisplay rock = world.spawn(top, BlockDisplay.class, e -> {
                    e.setBlock(Bukkit.createBlockData(rnd.nextBoolean() ? Material.COBBLESTONE : Material.TUFF));
                    e.setPersistent(false);
                    e.setTeleportDuration(13);
                    e.setTransformation(new Transformation(new Vector3f(-0.5f, 0, -0.5f), new Quaternionf(), new Vector3f(1f), new Quaternionf()));
                });
                fx.add(rock);
                fxRemoveAt.put(rock, t + 16);
                rockShown.put(r, rock);
            }
            if (left == 13) {   // ...and drops the next tick, gliding down over 13 ticks (a teleport on the spawn tick isn't animated)
                BlockDisplay rock = rockShown.get(r);
                if (rock != null && rock.isValid()) rock.teleport(spot);
            }
            if (left <= 0) {
                world.spawnParticle(Particle.BLOCK, spot, 40, 0.9, 0.3, 0.9, STONE);
                world.playSound(spot, Sound.BLOCK_STONE_BREAK, SoundCategory.HOSTILE, 1.5f, 0.6f);
                for (Player p : m.undercroftPlayers(home))
                    if (p.getLocation().distance(spot) <= 1.9 && Math.abs(p.getLocation().getY() - home.getY()) < 3) hurt(p, 5, new Vector(0, 0.3, 0));
                rockShown.remove(r);
                it.remove();
            }
        }
    }

    private void tickFx() {
        for (var it = fx.iterator(); it.hasNext(); ) {
            Display d = it.next();
            Integer at = fxRemoveAt.get(d);
            if (!d.isValid() || at != null && t >= at) { d.remove(); fxRemoveAt.remove(d); it.remove(); }
        }
    }

    private void ring(Location c, double r, Particle kind) {
        int n = Math.max(8, (int) (r * 7));
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n;
            Location l = new Location(world, c.getX() + Math.cos(a) * r, home.getY() + 0.15, c.getZ() + Math.sin(a) * r);
            if (kind == Particle.BLOCK) world.spawnParticle(Particle.BLOCK, l, 1, 0, 0, 0, STONE);
            else world.spawnParticle(Particle.DUST, l, 1, 0, 0.05, 0, i % 3 == 0 ? PINK : GREEN);
        }
    }

    private void circle(Location c, double r, Particle.DustOptions dust) {
        int n = Math.max(8, (int) (r * 8));
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n;
            world.spawnParticle(Particle.DUST, c.getX() + Math.cos(a) * r, c.getY() + 0.15, c.getZ() + Math.sin(a) * r, 1, 0, 0, 0, dust);
        }
    }

    // ------------------------------------------------------------------ getting hurt

    /** A player hit the body. Only damage from living players counts, +50% while it's stunned. */
    void onDamaged(EntityDamageEvent e, Player attacker) {
        if (state == State.RISING || state == State.DEAD || attacker == null || !m.isAlive(attacker)) { e.setCancelled(true); return; }
        if (state == State.STUNNED) e.setDamage(e.getDamage() * 1.5);
        double dmg = e.getFinalDamage();
        Team tm = m.team(attacker);
        if (tm != null) { teamDamage.merge(tm, dmg, Double::sum); lastTeam = tm; }
        playerDamage.merge(attacker.getUniqueId(), dmg, Double::sum);
        lastHitBy.put(attacker.getUniqueId(), t);
        world.spawnParticle(Particle.BLOCK, body.getLocation().add(0, 2, 0), 10, 0.8, 0.8, 0.8, STONE);
        world.playSound(body.getLocation(), Sound.BLOCK_STONE_BREAK, SoundCategory.HOSTILE, 1f, 0.8f);
        if (dmg >= body.getHealth()) { e.setCancelled(true); die(); return; }
        int ph = GolemMath.phase(body.getHealth() - dmg, maxHealth);
        if (ph > phaseShown) phaseChange(ph);
    }

    private void phaseChange(int ph) {
        phaseShown = ph;
        if (ph == 2) {
            for (int i = 0; i < parts.size(); i++) if (data.get(i).group() == GolemModel.Group.PLATE) parts.get(i).remove();
            heartScale = 1.6;
            pose(GolemModel.Group.HEART, 0, 0, 0.05, 6);
            world.spawnParticle(Particle.BLOCK, body.getLocation().add(0, 2.6, 1.5), 80, 0.8, 0.6, 0.3, Bukkit.createBlockData(Material.CRACKED_STONE_BRICKS));
            world.playSound(body.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 2f, 0.8f);
            for (Player p : m.undercroftPlayers(home)) p.sendMessage(Ui.mm("<#ff3fa4>The Golem's chest plate shatters! <gray>It charges and brings the ceiling down now."));
        } else {
            world.playSound(body.getLocation(), Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 3f, 0.4f);
            for (Player p : m.undercroftPlayers(home)) p.sendMessage(Ui.mm("<red><bold>The Golem is enraged!</bold> <gray>Faster, and every slam hits twice."));
        }
        refreshBar();
    }

    private void die() {
        Location at = body.getLocation();
        // the team that dealt the most damage wins it; the last hit breaks a tie
        Team best = null;
        double top = -1;
        for (var e : teamDamage.entrySet()) if (e.getValue() > top || e.getValue() == top && e.getKey() == lastTeam) { top = e.getValue(); best = e.getKey(); }
        UUID topPlayer = null;
        double topDmg = -1;
        for (var e : playerDamage.entrySet()) if (best != null && best.members.contains(e.getKey()) && e.getValue() > topDmg) { topDmg = e.getValue(); topPlayer = e.getKey(); }
        to(State.DEAD);
        body.remove();
        body = null;
        hideBar();
        // it falls apart
        for (int i = 0; i < parts.size(); i++) {
            BlockDisplay d = parts.get(i);
            if (!d.isValid()) continue;
            GolemModel.Part p = data.get(i);
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(20);
            d.setTransformation(transform(p, (rnd.nextDouble() - 0.5) * 2, -p.cy() + 0.1, (rnd.nextDouble() - 0.5) * 2, 0.8));
        }
        world.spawnParticle(Particle.EXPLOSION_EMITTER, at, 1);
        world.spawnParticle(Particle.DUST, at.clone().add(0, 2, 0), 80, 1.5, 1.5, 1.5, PINK);
        world.playSound(at, Sound.ENTITY_WARDEN_DEATH, SoundCategory.HOSTILE, 3f, 0.7f);
        m.golemDefeated(best, topPlayer, at);
    }

    private void hideBar() {
        for (UUID id : viewers) { Player p = Bukkit.getPlayer(id); if (p != null) p.hideBossBar(bar); }
        viewers.clear();
    }

    /** Take everything out of the world (match over, or the Golem is gone). */
    void remove() {
        if (removed) return;
        removed = true;
        hideBar();
        parts.forEach(Entity::remove);
        fx.forEach(Entity::remove);
        if (boulder != null) boulder.remove();
        if (body != null) body.remove();
        rooted.clear();
        rocks.clear();
        rockShown.clear();
    }
}
