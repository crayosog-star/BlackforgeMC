package net.alloymc.core.arcade.games.bedwars;

import net.alloymc.core.Ui;
import net.alloymc.core.arcade.core.Team;
import net.alloymc.core.survival.Rimelock;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

/**
 * The Golem: the Undercroft's mini boss in Forge Wars. The first time it wakes it breaks out of the hall wall (see
 * {@link GolemIntro}); later it rises out of the floor. It fights whoever is down there and gives the team that hurts it
 * most the Golem's Heart. The numbers live in arcade/games/forgewars.yml (golem-*).
 *
 * Body: an invisible, scaled-up iron golem with no AI carries the hitbox and health; the plugin moves it by teleporting.
 * Look: one block display per {@link GolemModel} part, posed by {@link GolemAnim} (arms swing from the shoulders, the
 * body leans and twists). A new pose goes out every 2-3 ticks and the client glides between them. Actions are timed
 * like a fighting game: wind-ups ease in and hold with a tremble, strikes snap in and overshoot a little, heavy hits
 * freeze for a few frames (hit-stop), recoveries ease out.
 *
 * Moves: Swipe, Ground Slam, Boulder Hurl, Root Snare, Crystal Eruption and Shard Volley (one shard off every crystal on
 * its back); from phase 2 Charge and Rockfall. Impacts crack the floor (real block-crack textures that fade), throw
 * rubble and raise dust. Below 30% health its crystals bleed from hot pink to blood red, its eyes turn red, and every
 * crystal, shard and spark of its moves follows. A Rimelock cage's cold slows it and dims its ore.
 * Everything runs off {@link #tick()} (called every tick by the match), so there are no scheduler tasks to leak.
 */
final class Golem {
    enum State { INTRO, RISING, WALK, WINDUP, ACT, STUNNED, DYING, DEAD }

    private static final double LEASH = 7.5 * ForgeWarsMaps.HALL, SPEED = 0.14, ENRAGED_SPEED = 0.2, RING_SPEED = 0.3, RING_REACH = 12 * ForgeWarsMaps.HALL, CHARGE_SPEED = 0.55, ERUPT_SPEED = 0.6;
    private static final double S = GolemModel.SCALE;
    private static final Particle.DustOptions GREEN = new Particle.DustOptions(Color.fromRGB(GolemModel.HEART_GREEN), 1.2f),
            RED = new Particle.DustOptions(Color.fromRGB(0xFF3030), 1.1f),
            FROST = new Particle.DustOptions(Color.fromRGB(0xBFF4FF), 1.2f),
            DUST_GREY = new Particle.DustOptions(Color.fromRGB(0x8A8580), 2.2f);
    private static final BlockData STONE = Bukkit.createBlockData(Material.MOSSY_STONE_BRICKS);
    private static final Material[] RUBBLE = {Material.COBBLESTONE, Material.COBBLESTONE, Material.MOSSY_COBBLESTONE, Material.MOSSY_COBBLESTONE, Material.TUFF, Material.COBBLED_DEEPSLATE};
    /** How the ore looks: full glow, dimmed by the cold, or guttered out (a flicker, or a crystal that just fired its shard). */
    private enum Ore { FULL, DIM, OUT }

    private final BedWarsMatch m;
    private final World world;
    private final Location home;   // middle of the hall, standing height
    private final Location breach; // where it breaks out of the wall (null: rise out of the floor instead)
    private final double inX, inZ; // from the breach into the hall
    private final Random rnd = new Random();
    private final List<BlockDisplay> parts = new ArrayList<>();
    private final List<GolemModel.Part> data = new ArrayList<>();
    private final double[] bloodAt;            // per part: how far into the blood fade it turns red
    private final boolean[] reddened;
    private final int[] sheathOf;              // per crystal core: its glass sheath's part index, or -1
    private final List<Display> fx = new ArrayList<>();
    private final Map<Display, Integer> fxRemoveAt = new HashMap<>();
    private final List<Glide> glides = new ArrayList<>();   // effect displays to animate a tick after they spawn
    private final BossBar bar;
    private final Set<UUID> viewers = new HashSet<>();
    private final Map<Team, Double> teamDamage = new HashMap<>();
    private final Map<UUID, Double> playerDamage = new HashMap<>();
    private final Map<UUID, Integer> lastHitBy = new HashMap<>();
    private final Map<UUID, Integer> rooted = new HashMap<>();
    private final Set<UUID> hit = new HashSet<>();
    private final Set<String> slamHit = new HashSet<>();   // "player:wave" already caught by a slam ring
    private final Map<double[], BlockDisplay> rockShown = new IdentityHashMap<>();
    private final List<double[]> rocks = new ArrayList<>();   // x, z, impact tick, tick it was called down
    private final List<Location> marks = new ArrayList<>();
    private final List<double[]> waves = new ArrayList<>();   // rings of light running over the floor: x, z, start tick, reach, rgb
    private List<Integer> spikeParts;                         // the big back crystals (found once)
    private final List<double[]> lanes = new ArrayList<>();   // Crystal Eruption: dir x, dir z, stopped (0/1)
    private final List<Player> shardTargets = new ArrayList<>();
    private final List<Shard> shards = new ArrayList<>();
    private final Map<UUID, Double> volleyDamage = new HashMap<>();
    private final Map<Integer, Integer> spentUntil = new HashMap<>();   // crystal part -> tick it starts growing back
    private final Map<Integer, Integer> flickerUntil = new HashMap<>();
    private final Map<Long, float[]> cracks = new HashMap<>();          // block -> {x, y, z, progress, fade from tick}
    private final List<Player> crackViewers = new ArrayList<>();
    private final List<Chunk> chunks = new ArrayList<>();
    private final List<Rubble> falling = new ArrayList<>();
    private final List<int[]> hole = new ArrayList<>();                  // the breach: x, y, z, rows up, depth*100
    private final ArrayDeque<int[]> sealQueue = new ArrayDeque<>();
    private final double maxHealth;
    private final double damageScale;
    private final double pace;                 // 1.5 = it winds up and recovers in two thirds of the time
    private IronGolem body;
    private Team lastTeam;
    private State state;
    private GolemMath.Move move;
    private Player target;
    private Location aim, eruptFrom;
    private Vector chargeDir;
    private BlockDisplay boulder;
    private float yaw;
    private final Pacing pacing = new Pacing(100);   // special-move countdown + swipe cooldown (FW-003)
    private int t, stateT, windup, stunTicks, idle, phaseShown = 1, eruptStep, chillUntil = -1, freezeUntil, stepSign, volleyEnd,
            blindUntil = -1, wanderAt;
    private double heartScale = 1, blood;
    private boolean removed, moving, chillShown, dimShown, eyesRed;
    private TextDisplay blindTag;   // "BLINDED" over its head while a Potion of Darkness has it
    private Location wander;        // where it stumbles to while blind
    // animation: blend from the last pose sent towards the current target over `blend` ticks after a state change
    private GolemAnim.Pose last, from;
    private int blend;
    private double fx0 = Double.NaN, fy0, fz0;
    private float fyaw;

    private record Glide(Display d, Transformation to, int at, int ticks) {}

    /** A rubble chunk thrown by an impact: flies, falls, bounces once, shrinks away. */
    private static final class Chunk {
        final BlockDisplay d; final Vector pos, vel; final float size; int life, spin; boolean bounced;
        Chunk(BlockDisplay d, Vector pos, Vector vel, float size) { this.d = d; this.pos = pos; this.vel = vel; this.size = size; }
    }

    /** A block of rubble falling into the breach; it becomes a real block when it lands. */
    private static final class Rubble {
        final BlockDisplay d; final int x, y, z; final Material type; double fy, vy; int tries;
        Rubble(BlockDisplay d, int x, int y, int z, Material type, double fy) { this.d = d; this.x = x; this.y = y; this.z = z; this.type = type; this.fy = fy; }
    }

    /** One shard of the volley, off one crystal: peels off, hangs, turns to its target, then whips forward. */
    private static final class Shard {
        final int part; final Player target; final int launchAt, fireAt;
        BlockDisplay core, glass; Vector pos, from, hover, dir, axis, lockDir; double[] slot; boolean spawned, fired; int flyT, idx, n; double travelled; float spin;
        Shard(int part, Player target, int launchAt, int fireAt, int idx, int n) { this.part = part; this.target = target; this.launchAt = launchAt; this.fireAt = fireAt; this.idx = idx; this.n = n; }
    }

    Golem(BedWarsMatch m, World world, Location home, Location breach, int playersBelow) {
        this.m = m;
        this.world = world;
        this.home = home.clone();
        // health and damage: the base settings, then a multiplier of their own (200 = double, 150 = 1.5x). New keys, so servers
        // that stored the old settings long ago still get the change.
        this.maxHealth = GolemMath.health(playersBelow, m.setting("golem-health-base", 300), m.setting("golem-health-per-player", 60)) * m.setting("golem-health-percent", 200) / 100.0;
        this.damageScale = m.setting("golem-damage-percent", 100) / 100.0 * m.setting("golem-damage-boost-percent", 150) / 100.0;
        this.pace = Math.max(0.5, m.setting("golem-attack-speed-percent", 150) / 100.0);
        // the breach only makes sense if it's out at the wall
        if (breach != null && Math.hypot(breach.getX() - home.getX(), breach.getZ() - home.getZ()) > 6) {
            this.breach = breach.clone();
            double dx = home.getX() - breach.getX(), dz = home.getZ() - breach.getZ(), d = Math.hypot(dx, dz);
            inX = dx / d; inZ = dz / d;
        } else { this.breach = null; inX = 0; inZ = 1; }
        state = this.breach != null ? State.INTRO : State.RISING;
        last = from = state == State.INTRO ? GolemAnim.intro(0) : GolemAnim.rising(0);
        bar = BossBar.bossBar(Ui.mm(title()), 1f, BossBar.Color.PINK, BossBar.Overlay.NOTCHED_10);
        Location at = start();
        bloodAt = new double[GolemModel.PARTS.size()];
        reddened = new boolean[GolemModel.PARTS.size()];
        sheathOf = new int[GolemModel.PARTS.size()];
        for (GolemModel.Part p : GolemModel.PARTS) { data.add(p); parts.add(spawnPart(at, p)); }
        for (int i = 0; i < data.size(); i++) {
            bloodAt[i] = 0.1 + rnd.nextDouble() * 0.8;   // the red spreads crystal by crystal
            GolemModel.Part p = data.get(i);
            boolean core = p.group() == GolemModel.Group.ORE && p.block().contains("pink_concrete") && p.glow() >= 0;
            sheathOf[i] = core && i + 1 < data.size() && data.get(i + 1).block().contains("pink_stained_glass") && Math.abs(data.get(i + 1).cx() - p.cx()) < 1e-6 ? i + 1 : -1;
        }
        body = spawnBody(at);
        if (state == State.RISING) {
            world.playSound(home, Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, 3f, 0.6f);
            world.playSound(home, Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 2.5f, 0.5f);
            crack(home.getX(), home.getZ(), 4.5, 1.0);
            debris(home.getX(), home.getZ(), 18, 1.0);
        }
    }

    /** Where it stands on its first tick: inside the wall for the breach, else in the middle of the hall. */
    private Location start() {
        if (breach == null) { Location l = home.clone(); l.setYaw(0); return l; }
        double[] p = GolemIntro.position(0, breach.getX(), breach.getZ(), inX, inZ, home.getX(), home.getZ());
        yaw = GolemMath.yawTo(inX, inZ);
        return new Location(world, p[0], home.getY(), p[2], yaw, 0);
    }

    // ------------------------------------------------------------------ the model

    private BlockDisplay spawnPart(Location at, GolemModel.Part p) {
        BlockData block = Bukkit.createBlockData(p.block());
        GolemAnim.Pose pose = last;
        return world.spawn(at, BlockDisplay.class, e -> {
            e.setBlock(block);
            e.setPersistent(false);
            e.setBrightness(p.bright() ? new Display.Brightness(15, 15) : new Display.Brightness(11, 11));
            e.setViewRange(1.6f);
            e.setTeleportDuration(2);
            e.setTransformation(tf(GolemAnim.transform(p, pose)));
        });
    }

    private IronGolem spawnBody(Location at) {
        boolean intro = state == State.INTRO;
        return world.spawn(at, IronGolem.class, g -> {
            g.setAI(false);
            g.setInvisible(true);
            g.setSilent(true);
            g.setPersistent(false);
            g.setRemoveWhenFarAway(false);
            g.setPlayerCreated(false);
            g.setInvulnerable(intro);   // it starts inside the rock
            g.addScoreboardTag(Rimelock.NO_HOLD_TAG);   // a Rimelock cage slows it (see tickChill), it doesn't pin it
            set(g, Attribute.MAX_HEALTH, maxHealth);
            set(g, Attribute.SCALE, 2.0);   // iron golem 1.4 x 2.7 -> a 2.8 x 5.4 hitbox round the model's body
            set(g, Attribute.KNOCKBACK_RESISTANCE, 1);
            g.setHealth(maxHealth);
        });
    }

    private static void set(LivingEntity e, Attribute a, double v) { var i = e.getAttribute(a); if (i != null) i.setBaseValue(v); }

    /** {@link GolemAnim#transform} output as a display transformation. */
    static Transformation tf(double[][] f) {
        return new Transformation(new Vector3f((float) f[0][0], (float) f[0][1], (float) f[0][2]),
                new Quaternionf((float) f[1][0], (float) f[1][1], (float) f[1][2], (float) f[1][3]),
                new Vector3f((float) f[2][0], (float) f[2][1], (float) f[2][2]), new Quaternionf());
    }

    /** A part at rest, shifted by (ox, oy, oz) and scaled about its own middle (used as it falls apart). */
    static Transformation transform(GolemModel.Part p, double ox, double oy, double oz, double scale) {
        Quaternionf rot = new Quaternionf().rotateZ((float) Math.toRadians(p.rz())).rotateX((float) Math.toRadians(p.rx()));
        float sx = (float) (p.sx() * scale), sy = (float) (p.sy() * scale), sz = (float) (p.sz() * scale);
        Vector3f half = new Vector3f(sx / 2, sy / 2, sz / 2).rotate(new Quaternionf(rot));
        return new Transformation(new Vector3f((float) (p.cx() + ox) - half.x, (float) (p.cy() + oy) - half.y, (float) (p.cz() + oz) - half.z),
                rot, new Vector3f(sx, sy, sz), new Quaternionf());
    }

    /** A crystal that fired its shard is a stub; it grows back over 12 ticks once its time is up. 1 = full size. */
    private double grown(int i) {
        Integer until = spentUntil.get(i);
        if (until == null) return 1;
        if (t < until) return 0.35;
        return 0.35 + 0.65 * GolemAnim.smooth((t - until) / 12.0);
    }

    /** The crystal (or its sheath) shortened to {@code f} of its length, still rooted where it grows from. */
    private static GolemModel.Part stub(GolemModel.Part p, double f) {
        double rx = Math.toRadians(p.rx()), rz = Math.toRadians(p.rz());
        double ax = -Math.cos(rx) * Math.sin(rz), ay = Math.cos(rx) * Math.cos(rz), az = Math.sin(rx);
        double len = p.sy(), nl = len * f;
        double bx = p.cx() - ax * len / 2, by = p.cy() - ay * len / 2, bz = p.cz() - az * len / 2;
        return new GolemModel.Part(p.group(), p.block(), bx + ax * nl / 2, by + ay * nl / 2, bz + az * nl / 2, p.sx(), nl, p.sz(), p.rx(), p.rz(), p.glow(), p.bright());
    }

    /** Send a pose to every part, gliding there over {@code ticks}. */
    private void apply(GolemAnim.Pose pose, int ticks) {
        last = pose = pose.heart(heartScale);
        for (int i = 0; i < parts.size(); i++) {
            BlockDisplay d = parts.get(i);
            if (!d.isValid()) continue;
            GolemModel.Part p = data.get(i);
            double g = grown(i);
            if (g < 1) p = stub(p, g);
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(ticks);
            d.setTransformation(tf(GolemAnim.transform(p, pose)));
        }
    }

    /** What it should look like right now (before blending in from the last state). */
    private GolemAnim.Pose target() {
        return switch (state) {
            case INTRO -> GolemAnim.intro(stateT);
            case RISING -> GolemAnim.rising(stateT / 60.0);
            case WALK -> moving ? GolemAnim.walk(t, slow()) : GolemAnim.idle(t);
            case WINDUP -> stateT >= windup * 0.75 ? GolemAnim.tremble(GolemAnim.windup(move), t, 1) : GolemAnim.windup(move);
            case ACT -> GolemAnim.strike(move, stateT);
            case STUNNED -> GolemAnim.stunned(t);
            case DYING -> GolemAnim.dying(stateT);
            case DEAD -> last;
        };
    }

    /**
     * A new pose every 2 ticks while fighting, every 3 while walking. Wind-ups ease in with a little overshoot and hold,
     * trembling, until release ({@link GolemAnim#windupAt}); strikes accelerate in, freeze on the impact frame and swing
     * through ({@link GolemAnim#strikeAt}); everything else eases in and out. Other heavy moments (breaking out,
     * landing, a pillar, the chest plate) freeze the pose for a few frames with {@link #hitStop}.
     */
    private void animate() {
        if (t < freezeUntil) return;
        int every = state == State.WALK || state == State.RISING ? 3 : 2;
        if (t % every != 0) return;
        switch (state) {
            case WINDUP -> apply(GolemAnim.windupAt(from, move, stateT, windup), every);
            case ACT -> apply(GolemAnim.strikeAt(from, move, stateT, t), every);
            default -> {
                GolemAnim.Pose tg = target();
                apply(blend > 0 && stateT < blend ? GolemAnim.lerp(from, tg, GolemAnim.smooth(stateT / (double) blend)) : tg, every);
            }
        }
    }

    /** The tick of a move's strike when the blow lands (the end of its acceleration). */
    private int impact() { return GolemAnim.impactTick(move); }

    /** Freeze the pose for a few frames: the weight of a heavy hit. */
    private void hitStop(int ticks) { freezeUntil = t + ticks; }

    /** Keep every part standing on the body, turned the way it faces (only when it moved or turned). */
    private void follow() {
        if (body == null) return;
        Location at = body.getLocation();
        if (at.getX() == fx0 && at.getY() == fy0 && at.getZ() == fz0 && yaw == fyaw) return;
        fx0 = at.getX(); fy0 = at.getY(); fz0 = at.getZ(); fyaw = yaw;
        at.setYaw(yaw);
        at.setPitch(0);
        for (BlockDisplay d : parts) if (d.isValid()) d.teleport(at);
    }

    /** A point on the Golem in its own frame (x to its left, z ahead, y up from its feet), in the world. */
    private Location local(double x, double y, double z) {
        Location at = body.getLocation();
        double r = Math.toRadians(yaw), c = Math.cos(r), s = Math.sin(r);
        return new Location(world, at.getX() + x * c - z * s, at.getY() + y, at.getZ() + x * s + z * c);
    }

    // ------------------------------------------------------------------ colour: hot pink, bleeding to blood red

    /** The colour of its crystals and everything they make, right now. */
    private int oreRgb() { return GolemMath.mix(GolemModel.ORE_PINK, GolemMath.BLOOD_GLOW, blood); }

    private Particle.DustOptions dust(float size) { return new Particle.DustOptions(Color.fromRGB(oreRgb()), size); }

    private boolean red() { return blood >= 0.5; }

    private BlockData crystalCore() { return Bukkit.createBlockData(red() ? Material.RED_CONCRETE : Material.PINK_CONCRETE); }

    private BlockData crystalGlass() { return Bukkit.createBlockData(red() ? Material.RED_STAINED_GLASS : Material.PINK_STAINED_GLASS); }

    /** Every 5 ticks: below 30% health the crystals fade to blood red one by one, then the eyes. Never fades back. */
    private void tickBlood() {
        if (t % 5 != 0 || body == null) return;
        double b = GolemMath.blood(body.getHealth(), maxHealth);
        if (b <= blood) return;
        boolean first = blood == 0;
        blood = b;
        for (int i = 0; i < parts.size(); i++) {
            GolemModel.Part p = data.get(i);
            BlockDisplay d = parts.get(i);
            if (!d.isValid()) continue;
            if (p.group() == GolemModel.Group.ORE) {
                if (!reddened[i] && blood >= bloodAt[i]) {
                    reddened[i] = true;
                    d.setBlock(Bukkit.createBlockData(p.block().contains("glass") ? Material.RED_STAINED_GLASS : Material.RED_CONCRETE));
                    world.spawnParticle(Particle.DUST, body.getLocation().add(0, 4.5, -0.5), 4, 1, 0.6, 0.6, new Particle.DustOptions(Color.fromRGB(GolemMath.BLOOD_RED), 1.3f));
                }
                ore(i, look(i));
            }
            if (!eyesRed && blood >= 0.4 && p.group() == GolemModel.Group.HEAD && p.block().contains("verdant_froglight")) {
                d.setBlock(Bukkit.createBlockData(Material.RED_CONCRETE));
                d.setBrightness(new Display.Brightness(15, 15));
            }
        }
        if (blood >= 0.4 && !eyesRed) {
            eyesRed = true;
            world.playSound(body.getLocation(), Sound.ENTITY_WARDEN_ANGRY, SoundCategory.HOSTILE, 2.5f, 0.6f);
        }
        if (first) {
            world.playSound(body.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, 3f, 0.5f);
            for (Player p : m.undercroftPlayers(home)) p.sendMessage(Ui.mm("<#B3000C>The Golem's crystals are bleeding red..."));
        }
        if (red()) bar.color(BossBar.Color.RED);
    }

    // ------------------------------------------------------------------ the fight

    boolean owns(Entity e) { return body != null && e.getUniqueId().equals(body.getUniqueId()); }

    boolean removed() { return removed; }

    private int phase() { return body == null ? 3 : GolemMath.phase(body.getHealth(), maxHealth); }

    private String title() {
        String ph = switch (phaseShown) { case 1 -> ""; case 2 -> " <gray>· cracked"; default -> " <red>· ENRAGED"; };
        String name = blood > 0 ? "<#B3000C><bold>THE GOLEM</bold>" : "<#ff3fa4><bold>THE GOLEM</bold>";
        return name + (state == State.INTRO ? " <gray>· breaking out" : ph) + (chilled() ? " <#7FD8FF>· frozen" : "") + (blinded() ? " <#9B59B6>· blinded" : "");
    }

    /** Called every tick by the match. */
    void tick() {
        if (removed) return;
        t++;
        stateT++;
        if (state != State.DEAD && (body == null || !body.isValid())) { remove(); return; }
        if (t % 10 == 0) refreshBar();
        tickRoots();
        tickRocks();
        tickShards();
        tickChunks();
        tickRubble();
        tickCracks();
        tickFx();
        tickWaves();
        if (state == State.WALK || state == State.WINDUP || state == State.ACT) crystalGlow();
        if (state != State.DEAD && state != State.DYING) { tickChill(); tickBlind(); tickDim(); tickSpent(); tickBlood(); }
        // the hall bleeds with it: red grit sifts down from the ceiling once its crystals turn
        if (blood > 0.3 && body != null && state != State.INTRO && state != State.DEAD && t % 14 == 0)
            ceilingDust(home.getX() + (rnd.nextDouble() - 0.5) * 18 * ForgeWarsMaps.HALL, home.getZ() + (rnd.nextDouble() - 0.5) * 18 * ForgeWarsMaps.HALL, 3, 2 + (int) (blood * 4));
        switch (state) {
            case INTRO -> intro();
            case RISING -> {
                if (t % 4 == 0) world.spawnParticle(Particle.BLOCK, home, 25, 2.2, 0.2, 2.2, STONE);
                if (t % 10 == 0) crack(home.getX(), home.getZ(), 3.5, 0.7);
                if (stateT >= 60) { to(State.WALK, 10); world.playSound(home, Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 3f, 0.6f); }
            }
            case WALK -> walk();
            case DYING -> dying();
            case WINDUP -> windup();
            case ACT -> act();
            case STUNNED -> {
                if (t % 4 == 0) world.spawnParticle(Particle.CRIT, body.getLocation().add(0, 5.8, 0), 6, 0.6, 0.2, 0.6, 0.05);
                if (stateT >= stunTicks) to(State.WALK, 12);
            }
            case DEAD -> {
                if (stateT >= 40) remove();
                return;
            }
        }
        if (state != State.DEAD) { animate(); follow(); }
    }

    private void to(State s, int blendTicks) { from = last; blend = blendTicks; state = s; stateT = 0; }

    private void refreshBar() {
        if (body == null) return;
        bar.progress((float) Math.max(0, Math.min(1, body.getHealth() / maxHealth)));
        bar.name(Ui.mm(title()));
        Set<UUID> now = new HashSet<>();
        for (Player p : m.undercroftPlayers(home)) { now.add(p.getUniqueId()); p.showBossBar(bar); }
        for (UUID id : viewers) if (!now.contains(id)) { Player p = Bukkit.getPlayer(id); if (p != null) p.hideBossBar(bar); }
        viewers.clear();
        viewers.addAll(now);
        crackViewers.clear();
        for (Player p : world.getPlayers()) if (p.getLocation().distanceSquared(home) < 48 * 48) crackViewers.add(p);
    }

    private void retarget(List<Player> below) {
        Player best = null;
        double bestScore = Double.MAX_VALUE;
        for (Player p : below) {
            double d = p.getLocation().distance(body.getLocation());
            if (!body.hasLineOfSight(p)) d += 6;   // a pillar in the way makes a player less interesting, not invisible: it still hunts them round it
            Integer last = lastHitBy.get(p.getUniqueId());
            if (last != null && t - last < 100) d -= 8;   // whoever hit it lately draws its attention
            if (p == target) d *= 0.75;   // and it sticks with its target rather than flipping between two players at similar range
            if (d < bestScore) { bestScore = d; best = p; }
        }
        target = best;
    }

    /** How fast it can turn, degrees a tick: quick enough to keep up with a player circling it, smooth enough to look like a body turning. */
    private float turnRate() { return phase() >= 3 ? 26f : 18f; }

    /**
     * While it rears up it keeps turning to face its target (and Charge and Crystal Eruption re-aim with it), then commits
     * once the wind-up is 60% through: a quick player can still dodge the commit, a slow one can't hide behind it.
     */
    private void trackDuringWindup() {
        if (target == null || !target.isOnline() || body == null || stateT > windup * 0.6) return;
        Location tl = target.getLocation(), at = body.getLocation();
        double dx = tl.getX() - at.getX(), dz = tl.getZ() - at.getZ();
        if (dx * dx + dz * dz < 0.01) return;
        yaw = GolemMath.turnToward(yaw, GolemMath.yawTo(dx, dz), turnRate());
        switch (move) {
            case CHARGE -> chargeDir = new Vector(dx, 0, dz).normalize();
            case ERUPTION -> {
                double base = Math.atan2(dz, dx);
                double[] offs = GolemMath.eruptionLanes(phase());
                for (int i = 0; i < lanes.size() && i < offs.length; i++) {
                    double a = base + Math.toRadians(offs[i]);
                    lanes.get(i)[0] = Math.cos(a);
                    lanes.get(i)[1] = Math.sin(a);
                }
            }
            default -> {}
        }
    }

    private boolean high(Player p) { return p.getLocation().getY() > home.getY() + 2.5; }

    private void walk() {
        moving = false;
        List<Player> below = m.undercroftPlayers(home);
        if (t % 5 == 0 || target == null || !target.isOnline() || !m.isAlive(target)) retarget(below);
        if (below.isEmpty()) {
            if (++idle > 400 && body.getHealth() < maxHealth) body.setHealth(Math.min(maxHealth, body.getHealth() + maxHealth * 0.02 / 20));
        } else idle = 0;
        pacing.tick();
        if (blinded()) { stumble(below); steps(); return; }
        if (target == null) stepTo(home, SPEED * slow());
        else {
            Location tl = target.getLocation();
            double dx = tl.getX() - body.getLocation().getX(), dz = tl.getZ() - body.getLocation().getZ(), dist = Math.hypot(dx, dz);
            yaw = GolemMath.turnToward(yaw, GolemMath.yawTo(dx, dz), turnRate());
            if (pacing.specialDue()) { start(GolemMath.pick(phase(), dist, high(target), below.size(), rnd)); return; }
            if (dist <= 4.1 && pacing.swipeReady() && !high(target)) { start(GolemMath.Move.SWIPE); return; }
            if (dist > 3.4) stepTo(tl, (phase() >= 3 ? ENRAGED_SPEED : SPEED) * (dist > 8 ? 1.5 : 1) * slow());   // and it closes a gap quickly
        }
        steps();
    }

    /** Knuckles hitting the floor, in step with the walk animation's arm swing. */
    private void steps() {
        if (!moving) return;
        int sign = (int) Math.signum(Math.sin(t * 0.2 * slow()));
        if (sign != 0 && sign != stepSign) { stepSign = sign; footfall(sign > 0 ? -1 : 1); }
    }

    /**
     * Blind: no targets, no moves. It stumbles between random spots in the hall, turning its head about. Hit it and it
     * hears where the blow came from: if you're within arm's reach it swings at you.
     */
    private void stumble(List<Player> below) {
        target = null;
        Location at = body.getLocation();
        for (Player p : below) {
            Integer hitAt = lastHitBy.get(p.getUniqueId());
            Location l = p.getLocation();
            double dx = l.getX() - at.getX(), dz = l.getZ() - at.getZ();
            if (hitAt != null && t - hitAt < 10 && Math.hypot(dx, dz) <= 4.1 && pacing.swipeReady()) {
                yaw = GolemMath.yawTo(dx, dz);
                start(GolemMath.Move.SWIPE);
                return;
            }
        }
        if (wander == null || t >= wanderAt || Math.hypot(wander.getX() - at.getX(), wander.getZ() - at.getZ()) < 0.6) {
            double a = rnd.nextDouble() * Math.PI * 2, r = 1.5 + rnd.nextDouble() * (LEASH - 2);
            wander = new Location(world, home.getX() + Math.cos(a) * r, home.getY(), home.getZ() + Math.sin(a) * r);
            wanderAt = t + 30 + rnd.nextInt(30);
            world.playSound(at, Sound.ENTITY_IRON_GOLEM_HURT, SoundCategory.HOSTILE, 1.2f, 0.4f);
        }
        yaw = GolemMath.yawTo(wander.getX() - at.getX(), wander.getZ() - at.getZ());
        stepTo(wander, SPEED * slow());
    }

    private void footfall(int side) {
        Location f = local(side * 2.6 * S, 0, 1.9 * S);
        crack(f.getX(), f.getZ(), 1.1, 0.4);
        world.spawnParticle(Particle.DUST_PILLAR, f.clone().add(0, 0.1, 0), 6, 0.4, 0.05, 0.4, 0.1, floorAt(f.getX(), f.getZ()));
        world.playSound(f, Sound.ENTITY_IRON_GOLEM_STEP, SoundCategory.HOSTILE, 1.4f, 0.5f);
        world.playSound(f, Sound.ENTITY_RAVAGER_STEP, SoundCategory.HOSTILE, 0.7f, 0.6f);
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
        moving = true;
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

    // ------------------------------------------------------------------ the entrance: breaking out of the wall

    private void intro() {
        int s = stateT;
        double bx = breach.getX(), bz = breach.getZ();
        double[] p = GolemIntro.position(s, bx, bz, inX, inZ, home.getX(), home.getZ());
        yaw = GolemMath.yawTo(inX, inZ);
        body.teleport(new Location(world, p[0], home.getY() + p[1], p[2], yaw, 0));
        Location face = new Location(world, bx - inX * 0.6, home.getY(), bz - inZ * 0.6);
        if (s < GolemIntro.BREACH) {
            // the tremor: the hall shakes, the wall cracks wider and wider, pink light leaks through
            double u = s / (double) GolemIntro.BREACH;
            if (s % 5 == 0) for (int[] b : holeBlocks(false)) if (b[4] <= 150) crackBlock(b[0], b[1], b[2], (float) Math.min(0.95, 0.15 + u * 0.9), 40);
            if (s % 2 == 0) {
                for (int i = 0; i < 2 + (int) (u * 6); i++) {
                    double side = (rnd.nextDouble() - 0.5) * GolemIntro.HALF_WIDTH * 1.6, up = rnd.nextDouble() * GolemIntro.HEIGHT;
                    world.spawnParticle(Particle.DUST, bx - inZ * side + inX * 0.1, home.getY() + up, bz + inX * side + inZ * 0.1, 1, 0.05, 0.05, 0.05, dust(1.2f));
                }
                for (int i = 0; i < 6; i++) {   // dust shaken off the ceiling all over the hall
                    double a = rnd.nextDouble() * Math.PI * 2, r = Math.sqrt(rnd.nextDouble()) * 12;
                    world.spawnParticle(Particle.FALLING_DUST, home.getX() + Math.cos(a) * r, home.getY() + 18, home.getZ() + Math.sin(a) * r, 1, 0, 0, 0, Bukkit.createBlockData(Material.TUFF));
                }
            }
            if (s % 4 == 0) world.spawnParticle(Particle.BLOCK, face.clone().add(0, 1 + rnd.nextDouble() * 5, 0), 6, 1.5, 0.5, 1.5, Bukkit.createBlockData(Material.TUFF));
            int beat = Math.max(5, 16 - s / 4);
            if (s % beat == 0) {
                world.playSound(face, Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, 3f, 0.5f + (float) u * 0.3f);
                world.playSound(face, Sound.BLOCK_DEEPSLATE_BREAK, SoundCategory.HOSTILE, 2f, 0.5f);
            }
            if (s == 1) world.playSound(home, Sound.AMBIENT_CAVE, SoundCategory.HOSTILE, 3f, 0.6f);
            if (s == GolemIntro.BREACH - 12) world.playSound(face, Sound.ENTITY_WARDEN_EMERGE, SoundCategory.HOSTILE, 3f, 0.7f);
            return;
        }
        if (s == GolemIntro.BREACH) breakOut(face);
        if (s == GolemIntro.ROAR + 2) {
            world.playSound(body.getLocation(), Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 4f, 0.45f);
            world.playSound(body.getLocation(), Sound.ENTITY_WARDEN_ROAR, SoundCategory.HOSTILE, 3f, 0.6f);
        }
        if (s > GolemIntro.ROAR + 6 && s < GolemIntro.CROUCH && s % 3 == 0) {
            world.spawnParticle(Particle.DUST, local(0, 5.4, -1.1), 10, 1.2, 0.8, 0.6, dust(1.6f));
            ring(body.getLocation(), (s - GolemIntro.ROAR) * 0.35, Particle.BLOCK);
        }
        if (s == GolemIntro.LEAP) {
            world.playSound(body.getLocation(), Sound.ENTITY_BREEZE_JUMP, SoundCategory.HOSTILE, 3f, 0.4f);
            crack(body.getLocation().getX(), body.getLocation().getZ(), 2.5, 0.8);
            debris(body.getLocation().getX(), body.getLocation().getZ(), 10, 0.8);
        }
        if (s > GolemIntro.LEAP && s < GolemIntro.LAND) world.spawnParticle(Particle.BLOCK, body.getLocation(), 6, 1, 0.3, 1, STONE);
        if (s == GolemIntro.LAND) land();
        if (s >= GolemIntro.SEAL && s < GolemIntro.SEAL + GolemIntro.SEAL_TICKS) seal(s - GolemIntro.SEAL);
        if (s >= GolemIntro.END) {
            body.setInvulnerable(false);
            pacing.restart(50);
            to(State.WALK, 12);
            refreshBar();
        }
    }

    /**
     * The breach blocks: every block whose middle is in the hole ({@link GolemIntro#inHole}), as {x, y, z, rows up,
     * depth*100}. {@code solidOnly}: just the ones that are rock right now.
     */
    private List<int[]> holeBlocks(boolean solidOnly) {
        List<int[]> out = new ArrayList<>();
        int fy = home.getBlockY(), r = (int) Math.ceil(GolemIntro.DEPTH + GolemIntro.HALF_WIDTH + 1);
        double bx = breach.getX(), bz = breach.getZ();
        for (int x = (int) Math.floor(bx) - r; x <= (int) Math.floor(bx) + r; x++)
            for (int z = (int) Math.floor(bz) - r; z <= (int) Math.floor(bz) + r; z++)
                for (int dy = 0; dy <= GolemIntro.HEIGHT; dy++) {
                    double dx = x + 0.5 - bx, dz = z + 0.5 - bz;
                    if (!GolemIntro.inHole(dx, dz, dy, inX, inZ)) continue;
                    if (solidOnly && world.getBlockAt(x, fy + dy, z).isPassable()) continue;
                    out.add(new int[]{x, fy + dy, z, dy, (int) Math.round(GolemIntro.depth(dx, dz, inX, inZ) * 100)});
                }
        return out;
    }

    /** The wall bursts: the hole is carved, its rock thrown into the hall, and the Golem lunges out. */
    private void breakOut(Location face) {
        List<int[]> rock = holeBlocks(true);
        Collections.shuffle(rock, rnd);
        int flung = 0;
        for (int[] b : rock) {
            Block bl = world.getBlockAt(b[0], b[1], b[2]);
            BlockData was = bl.getBlockData();
            bl.setType(Material.AIR, false);
            hole.add(b);
            crackBlock(b[0], b[1], b[2], 0, 0);
            if (flung++ < 46 && b[4] < 350) {   // the front of the wall flies into the hall
                double sp = 0.55 + rnd.nextDouble() * 0.6;
                Vector v = new Vector(inX * sp + (rnd.nextDouble() - 0.5) * 0.4, 0.2 + rnd.nextDouble() * 0.45, inZ * sp + (rnd.nextDouble() - 0.5) * 0.4);
                chunk(new Vector(b[0] + 0.5, b[1] + 0.5, b[2] + 0.5), v, was, 0.45f + rnd.nextFloat() * 0.45f);
            } else if (rnd.nextInt(3) == 0) world.spawnParticle(Particle.BLOCK, b[0] + 0.5, b[1] + 0.5, b[2] + 0.5, 6, 0.3, 0.3, 0.3, was);
        }
        Location mid = face.clone().add(0, 3, 0);
        world.spawnParticle(Particle.EXPLOSION_EMITTER, mid, 1);
        world.spawnParticle(Particle.DUST, mid, 80, 2.2, 2.5, 2.2, DUST_GREY);
        world.spawnParticle(Particle.DUST, mid, 40, 1.6, 2.0, 1.6, dust(1.6f));
        world.playSound(mid, Sound.ENTITY_WITHER_BREAK_BLOCK, SoundCategory.HOSTILE, 4f, 0.5f);
        world.playSound(mid, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 4f, 0.55f);
        world.playSound(mid, Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 3f, 0.5f);
        crack(face.getX() + inX * 3, face.getZ() + inZ * 3, 5, 0.85);
        hitStop(3);
        for (Player p : m.undercroftPlayers(home)) {
            Location l = p.getLocation();
            if (Math.hypot(l.getX() - face.getX(), l.getZ() - face.getZ()) <= 6)
                p.setVelocity(new Vector(inX, 0, inZ).multiply(1.0).setY(0.45));
        }
    }

    /** It comes down on the launch pad: the floor cracks wide, rubble flies, everyone close is thrown back (no damage). */
    private void land() {
        Location at = home.clone();
        crack(at.getX(), at.getZ(), 6.5, 1.0);
        debris(at.getX(), at.getZ(), 26, 1.3);
        world.spawnParticle(Particle.DUST_PILLAR, at.clone().add(0, 0.2, 0), 70, 3.5, 0.1, 3.5, 0.2, floorAt(at.getX(), at.getZ()));
        world.spawnParticle(Particle.EXPLOSION, at.clone().add(0, 0.5, 0), 4, 2, 0.2, 2);
        world.playSound(at, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, SoundCategory.HOSTILE, 4f, 0.5f);
        world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 3f, 0.5f);
        world.playSound(at, Sound.BLOCK_ANVIL_LAND, SoundCategory.HOSTILE, 2f, 0.4f);
        hitStop(4);
        for (Player p : m.undercroftPlayers(home)) {
            Vector away = p.getLocation().toVector().subtract(at.toVector()).setY(0);
            if (away.length() > 7) continue;
            if (away.lengthSquared() < 1e-4) away = new Vector(1, 0, 0);
            p.setVelocity(away.normalize().multiply(1.1).setY(0.5));
        }
    }

    /**
     * The hole caves in behind it: rubble fills it deepest first and bottom up; the front layer falls in from the top
     * of the hole as blocks you can see. A block never lands on a player (they're shoved out into the hall instead).
     */
    private void seal(int s) {
        if (s == 0) {
            hole.sort(Comparator.<int[]>comparingInt(b -> -b[4]).thenComparingInt(b -> b[3]));
            sealQueue.clear();
            sealQueue.addAll(hole);
            world.playSound(breach, Sound.BLOCK_GRAVEL_BREAK, SoundCategory.HOSTILE, 3f, 0.5f);
            world.playSound(breach, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, SoundCategory.HOSTILE, 2f, 0.4f);
        }
        int per = Math.max(1, (int) Math.ceil(hole.size() / (double) (GolemIntro.SEAL_TICKS - 6)));
        for (int i = 0; i < per && !sealQueue.isEmpty(); i++) {
            int[] b = sealQueue.poll();
            Material type = RUBBLE[rnd.nextInt(RUBBLE.length)];
            if (b[4] < 150) {   // the front: falls in where you can see it
                double top = home.getBlockY() + GolemIntro.HEIGHT + 1.5;
                BlockDisplay d = world.spawn(new Location(world, b[0] + 0.5, top, b[2] + 0.5), BlockDisplay.class, e -> {
                    e.setBlock(Bukkit.createBlockData(type));
                    e.setPersistent(false);
                    e.setTeleportDuration(1);
                    e.setTransformation(new Transformation(new Vector3f(-0.5f, 0, -0.5f), new Quaternionf(), new Vector3f(1f), new Quaternionf()));
                });
                falling.add(new Rubble(d, b[0], b[1], b[2], type, top));
            } else place(b[0], b[1], b[2], type);
        }
        if (s % 2 == 0) {
            world.spawnParticle(Particle.DUST, breach.clone().add(-inX, 3, -inZ), 30, 2.2, 2.2, 2.2, DUST_GREY);
            world.spawnParticle(Particle.FALLING_DUST, breach.clone().add(-inX, 6.5, -inZ), 10, 2.5, 0.3, 2.5, Bukkit.createBlockData(Material.GRAVEL));
        }
        if (s % 5 == 0) world.playSound(breach, Sound.BLOCK_STONE_PLACE, SoundCategory.HOSTILE, 2.5f, 0.5f + rnd.nextFloat() * 0.3f);
    }

    /** Make a breach block solid rubble, unless a player is standing in it (then shove them out and try again later). */
    private boolean place(int x, int y, int z, Material type) {
        Block b = world.getBlockAt(x, y, z);
        BoundingBox box = new BoundingBox(x, y, z, x + 1, y + 1, z + 1);
        for (Player p : world.getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR || !p.getBoundingBox().overlaps(box)) continue;
            p.setVelocity(new Vector(inX, 0, inZ).multiply(0.7).setY(0.3));
            return false;
        }
        if (!b.isPassable()) return true;
        b.setType(type, false);
        world.spawnParticle(Particle.BLOCK, x + 0.5, y + 0.5, z + 0.5, 8, 0.3, 0.3, 0.3, Bukkit.createBlockData(type));
        return true;
    }

    private void tickRubble() {
        for (var it = falling.iterator(); it.hasNext(); ) {
            Rubble r = it.next();
            r.vy -= 0.09;
            r.fy = Math.max(r.y, r.fy + r.vy);
            if (r.d.isValid()) r.d.teleport(new Location(world, r.x + 0.5, r.fy, r.z + 0.5));
            if (r.fy > r.y) continue;
            if (place(r.x, r.y, r.z, r.type) || ++r.tries > 40) {
                r.d.remove();
                it.remove();
                world.playSound(new Location(world, r.x + 0.5, r.y, r.z + 0.5), Sound.BLOCK_STONE_HIT, SoundCategory.HOSTILE, 0.8f, 0.6f);
            }
        }
    }

    // ------------------------------------------------------------------ Rimelock: the cold

    private boolean chilled() { return t < chillUntil; }

    private boolean blinded() { return t < blindUntil; }

    /** The ore is dimmed (and flickers) while the cold or the dark has it. */
    private boolean dimmed() { return chilled() || blinded(); }

    /** 1 normally; less while a Rimelock cage's cold or a Potion of Darkness has it (walking, wind-ups, charges, eruptions, the gap between moves). */
    private double slow() {
        double f = 1;
        if (chilled()) f = Math.min(f, GolemMath.CHILL_SLOW);
        if (blinded()) f = Math.min(f, GolemMath.BLIND_SLOW);
        return f;
    }

    /** How one ore part should look right now. */
    private Ore look(int i) {
        Integer until = spentUntil.get(i);
        if (until != null && t < until) return Ore.OUT;
        return dimmed() ? Ore.DIM : Ore.FULL;
    }

    /** Every 5 ticks: is a cage's cold on it? It stays slowed 2 s after the cage is gone. The ore dims and flickers meanwhile. */
    private void tickChill() {
        if (state == State.INTRO) return;
        if (t % 5 == 0 && body != null) {
            ForgeArsenal a = ForgeArsenal.get();
            if (a != null && a.rimelockTouches(body.getLocation(), 1.4)) chillUntil = t + 40;
        }
        boolean c = chilled();
        if (c != chillShown) {
            chillShown = c;
            if (c) {
                world.playSound(body.getLocation(), Sound.ENTITY_PLAYER_HURT_FREEZE, SoundCategory.HOSTILE, 2f, 0.5f);
                world.playSound(body.getLocation(), Sound.BLOCK_GLASS_BREAK, SoundCategory.HOSTILE, 1.5f, 0.6f);
                for (Player p : m.undercroftPlayers(home)) p.sendMessage(Ui.mm("<#7FD8FF>The frost bites into the Golem. <gray>It slows, and the ore on its back dims."));
            }
            refreshBar();
        }
        if (c && t % 4 == 0) {
            world.spawnParticle(Particle.SNOWFLAKE, body.getLocation().add(0, 3, 0), 6, 1.6, 2, 1.6, 0.01);
            world.spawnParticle(Particle.DUST, body.getLocation().add(0, 3, 0), 4, 1.5, 2, 1.5, FROST);
        }
    }

    // ------------------------------------------------------------------ Potion of Darkness: blinded

    private static final Particle.DustOptions VOID = new Particle.DustOptions(Color.fromRGB(0x0B0710), 1.8f),
            PLUM = new Particle.DustOptions(Color.fromRGB(0x2A1033), 1.5f), GRAVE = new Particle.DustOptions(Color.fromRGB(0x6E2C83), 1.2f);

    /** A Potion of Darkness that caught it (picked up from the Arsenal) blinds it; the mark over its head counts down. */
    private void tickBlind() {
        ForgeArsenal a = ForgeArsenal.get();
        if (a != null && body != null && a.takeDarkened(body.getUniqueId()) && state != State.INTRO && state != State.RISING) blind();
        if (blindTag == null) return;
        if (!blindTag.isValid()) { blindTag = null; return; }
        if (blinded()) {
            blindTag.teleport(local(0, GolemModel.height() + 1.1, 0));
            if (t % 10 == 0) blindTag.text(Ui.mm(blindText()));
            // a dark halo turning over its head, ink dripping off it
            Location c = local(0, GolemModel.height() + 0.3, 0);
            for (int i = 0; i < 8; i++) {
                double an = t * 0.15 + Math.PI * 2 * i / 8;
                world.spawnParticle(Particle.DUST, c.getX() + Math.cos(an) * 1.3, c.getY() + Math.sin(t * 0.2 + i) * 0.1, c.getZ() + Math.sin(an) * 1.3, 1, 0, 0, 0, i % 2 == 0 ? VOID : GRAVE);
            }
            if (t % 3 == 0) world.spawnParticle(Particle.DUST, c, 4, 0.7, 0.25, 0.7, PLUM);
            if (t % 7 == 0) world.spawnParticle(Particle.SQUID_INK, c.clone().add(0, -0.4, 0), 2, 0.6, 0.1, 0.6, 0.01);
            if (t % 25 == 0) world.playSound(c, Sound.ENTITY_WARDEN_TENDRIL_CLICKS, SoundCategory.HOSTILE, 1.5f, 0.6f);
        } else {   // the dark lifts: the mark shrinks away
            blindTag.setInterpolationDelay(0);
            blindTag.setInterpolationDuration(6);
            blindTag.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.01f), new Quaternionf()));
            effect(blindTag, 7);
            blindTag = null;
            world.playSound(body.getLocation(), Sound.ENTITY_IRON_GOLEM_REPAIR, SoundCategory.HOSTILE, 1.5f, 0.5f);
            for (Player p : m.undercroftPlayers(home)) p.sendActionBar(Ui.mm("<#ff3fa4>The Golem can see again."));
            refreshBar();
        }
    }

    private String blindText() {
        int left = Math.max(0, (int) Math.ceil((blindUntil - t) / 20.0));
        return "<#9B59B6>☁ <bold><#C9B6D6>BLINDED</bold> <#9B59B6>☁\n<gray>" + left + "s";
    }

    /** Blinded for 5 s: it forgets every target, drops a wind-up it was in, and the dark mark appears over its head. */
    private void blind() {
        boolean was = blinded();
        blindUntil = t + GolemMath.BLIND_TICKS;
        target = null;
        wander = null;
        if (state == State.WINDUP) { to(State.WALK, 8); pacing.restart(30); }   // lost track of who it was winding up at
        if (was) { if (blindTag != null) blindTag.text(Ui.mm(blindText())); return; }
        Location c = body.getLocation().add(0, 3, 0);
        world.playSound(c, Sound.ENTITY_IRON_GOLEM_HURT, SoundCategory.HOSTILE, 2f, 0.4f);
        world.playSound(c, Sound.ENTITY_WARDEN_AGITATED, SoundCategory.HOSTILE, 2f, 0.6f);
        world.spawnParticle(Particle.SQUID_INK, local(0, 4.6, 2.6), 30, 0.6, 0.4, 0.6, 0.05);
        for (Player p : m.undercroftPlayers(home)) p.sendMessage(Ui.mm("<#9B59B6>The Golem is blinded! <gray>It can't see anyone for 5 seconds."));
        Location tagAt = local(0, GolemModel.height() + 1.1, 0);
        blindTag = world.spawn(tagAt, TextDisplay.class, d -> {
            d.text(Ui.mm(blindText()));
            d.setBillboard(Display.Billboard.CENTER);
            d.setPersistent(false);
            d.setShadowed(true);
            d.setBackgroundColor(Color.fromARGB(150, 11, 7, 16));
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTeleportDuration(1);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.01f), new Quaternionf()));
        });
        glides.add(new Glide(blindTag, new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(1.5f), new Quaternionf()), t + 1, 5));   // pops in
        refreshBar();
    }

    /** The dim-and-flicker look, shared by the cold and the dark; its eyes go dark too while it's blinded. */
    private void tickDim() {
        boolean d = dimmed();
        if (d != dimShown) {
            dimShown = d;
            flickerUntil.clear();
            for (int i = 0; i < parts.size(); i++) ore(i, look(i));
            if (!d && body != null) {
                world.playSound(body.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.HOSTILE, 2f, 0.8f);
                world.spawnParticle(Particle.DUST, local(0, 5, -1), 30, 1.2, 0.8, 0.8, dust(1.4f));
            }
        }
        eyes(blinded() ? 3 : 15);
        if (!d) return;
        // the flicker: now and then a crystal gutters out for a moment
        for (int i = 0; i < parts.size(); i++) {
            if (data.get(i).group() != GolemModel.Group.ORE || look(i) == Ore.OUT) continue;
            Integer until = flickerUntil.get(i);
            if (until != null) { if (t >= until) { flickerUntil.remove(i); ore(i, look(i)); } continue; }
            if (rnd.nextInt(100) < 2) { flickerUntil.put(i, t + 2 + rnd.nextInt(4)); ore(i, Ore.OUT); }
        }
    }

    /** Spent crystals light up again when they start growing back, and are forgotten once fully grown. */
    private void tickSpent() {
        for (var it = spentUntil.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (t == e.getValue()) ore(e.getKey(), look(e.getKey()));
            if (t >= e.getValue() + 12) it.remove();
        }
    }

    /** Set one ore part's glow (other parts are left alone). */
    private void ore(int i, Ore look) {
        GolemModel.Part p = data.get(i);
        BlockDisplay d = parts.get(i);
        if (p.group() != GolemModel.Group.ORE || !d.isValid()) return;
        switch (look) {
            case FULL -> d.setBrightness(new Display.Brightness(15, 15));
            case DIM -> d.setBrightness(new Display.Brightness(10, 10));
            case OUT -> d.setBrightness(new Display.Brightness(6, 6));
        }
    }

    private int eyeLight = 15;

    /** Its eyes' brightness (they go dark while it's blind). */
    private void eyes(int light) {
        if (light == eyeLight) return;
        eyeLight = light;
        for (int i = 0; i < parts.size(); i++) {
            GolemModel.Part p = data.get(i);
            if (p.group() == GolemModel.Group.HEAD && p.bright() && parts.get(i).isValid()) parts.get(i).setBrightness(new Display.Brightness(light, light));
        }
    }

    // ------------------------------------------------------------------ ground: cracks, rubble, dust

    private BlockData floorAt(double x, double z) {
        Block b = world.getBlockAt((int) Math.floor(x), home.getBlockY() - 1, (int) Math.floor(z));
        return b.isPassable() ? STONE : b.getBlockData();
    }

    /** Crack the floor in a disc: strongest in the middle, ragged at the edge. Cracks hold for a moment, then fade. */
    private void crack(double x, double z, double radius, double strength) {
        int fy = home.getBlockY() - 1, r = (int) Math.ceil(radius);
        for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) {
            int bx = (int) Math.floor(x) + dx, bz = (int) Math.floor(z) + dz;
            double d = Math.hypot(bx + 0.5 - x, bz + 0.5 - z);
            if (d > radius) continue;
            double p = strength * (1 - 0.65 * d / Math.max(0.5, radius)) * (0.75 + rnd.nextDouble() * 0.35);
            if (p < 0.1 || world.getBlockAt(bx, fy, bz).isPassable()) continue;
            crackBlock(bx, fy, bz, (float) Math.min(1, p), 30);
        }
    }

    /** Show cracks on one block (progress 0..1; 0 clears it) for everyone near, held {@code hold} ticks before fading. */
    private void crackBlock(int x, int y, int z, float progress, int hold) {
        long k = (((long) x & 0x3FFFFFF) << 38) | (((long) z & 0x3FFFFFF) << 12) | (y & 0xFFF);
        float[] c = cracks.get(k);
        if (progress <= 0) { if (c != null) { c[3] = 0; sendCrack(k, c); cracks.remove(k); } return; }
        if (c == null) { if (cracks.size() > 700) return; c = new float[]{x, y, z, 0, 0}; cracks.put(k, c); }
        c[4] = Math.max(c[4], t + hold);
        if (progress > c[3]) { c[3] = progress; sendCrack(k, c); }
    }

    private void sendCrack(long k, float[] c) {
        Location l = new Location(world, c[0], c[1], c[2]);
        int id = -(1 + (int) Math.floorMod(k * 0x9E3779B97F4A7C15L, 2_000_000_000L));   // its own "breaker" per block, never a real entity id
        for (Player p : crackViewers) if (p.isOnline() && p.getWorld() == world) p.sendBlockDamage(l, Math.max(0, Math.min(1, c[3])), id);
    }

    private void tickCracks() {
        if (t % 5 != 0) return;
        for (var it = cracks.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            float[] c = e.getValue();
            if (t < c[4]) continue;
            c[3] -= 0.12f;
            if (c[3] <= 0.06f) { c[3] = 0; sendCrack(e.getKey(), c); it.remove(); }
            else sendCrack(e.getKey(), c);
        }
    }

    /** Throw {@code n} rubble chunks of the floor up from (x, z). */
    private void debris(double x, double z, int n, double power) {
        BlockData bd = floorAt(x, z);
        for (int i = 0; i < n; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, out = power * (0.12 + rnd.nextDouble() * 0.22);
            Vector pos = new Vector(x + (rnd.nextDouble() - 0.5) * 1.2, home.getY() + 0.1, z + (rnd.nextDouble() - 0.5) * 1.2);
            Vector vel = new Vector(Math.cos(a) * out, power * (0.3 + rnd.nextDouble() * 0.35), Math.sin(a) * out);
            chunk(pos, vel, rnd.nextInt(3) == 0 ? STONE : bd, 0.2f + rnd.nextFloat() * 0.3f);
        }
    }

    private void chunk(Vector pos, Vector vel, BlockData bd, float size) {
        if (chunks.size() >= 110) return;
        Quaternionf q = new Quaternionf().rotateXYZ(rnd.nextFloat() * 6, rnd.nextFloat() * 6, rnd.nextFloat() * 6);
        BlockDisplay d = world.spawn(pos.toLocation(world), BlockDisplay.class, e -> {
            e.setBlock(bd);
            e.setPersistent(false);
            e.setTeleportDuration(1);
            e.setTransformation(new Transformation(new Vector3f(-size / 2).rotate(new Quaternionf(q)), q, new Vector3f(size), new Quaternionf()));
        });
        chunks.add(new Chunk(d, pos, vel, size));
    }

    /** Rubble: gravity, a bounce, a tumble, then it shrinks away. */
    private void tickChunks() {
        for (var it = chunks.iterator(); it.hasNext(); ) {
            Chunk c = it.next();
            c.life++;
            if (!c.d.isValid() || c.life > 46) { c.d.remove(); it.remove(); continue; }
            c.vel.setY(c.vel.getY() - 0.07);
            c.vel.multiply(0.98);
            c.pos.add(c.vel);
            double floor = home.getY() + c.size / 2;
            if (c.pos.getY() < floor && Math.abs(c.pos.getX() - home.getX()) < 30) {
                c.pos.setY(floor);
                if (!c.bounced) { c.bounced = true; c.vel.setY(-c.vel.getY() * 0.3); c.vel.multiply(0.5); }
                else c.vel.multiply(new Vector(0.6, 0, 0.6));
            }
            c.d.teleport(c.pos.toLocation(world));
            if (c.life % 3 == 0 && c.life < 30 && !c.bounced) {   // tumbling while it flies
                Quaternionf q = new Quaternionf().rotateXYZ(rnd.nextFloat() * 6, rnd.nextFloat() * 6, rnd.nextFloat() * 6);
                c.d.setInterpolationDelay(0);
                c.d.setInterpolationDuration(3);
                c.d.setTransformation(new Transformation(new Vector3f(-c.size / 2).rotate(new Quaternionf(q)), q, new Vector3f(c.size), new Quaternionf()));
            }
            if (c.life == 36) {   // shrink away
                c.d.setInterpolationDelay(0);
                c.d.setInterpolationDuration(10);
                c.d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.01f), new Quaternionf()));
            }
        }
    }

    // ------------------------------------------------------------------ moves

    private void start(GolemMath.Move mv) {
        move = mv;
        windup = GolemMath.paced(GolemMath.telegraph(mv, phase()), slow() * pace, 8);
        to(State.WINDUP, windup);
        hit.clear();
        slamHit.clear();
        marks.clear();
        flare();
        switch (mv) {
            case BOULDER -> {
                Player p = farTarget();
                aim = (p != null ? p : target).getLocation().clone();
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
                world.playSound(body.getLocation(), Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 2f, 1.1f);
                wave(body.getLocation().getX(), body.getLocation().getZ(), 9, 0xFF3030);
            }
            case ROCKFALL -> world.playSound(body.getLocation(), Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 3f, 0.4f);
            case ERUPTION -> {
                eruptFrom = body.getLocation().clone();
                double base = Math.atan2(target.getLocation().getZ() - eruptFrom.getZ(), target.getLocation().getX() - eruptFrom.getX());
                lanes.clear();
                for (double off : GolemMath.eruptionLanes(phase())) {
                    double a = base + Math.toRadians(off);
                    lanes.add(new double[]{Math.cos(a), Math.sin(a), 0});
                }
                eruptStep = 0;
                world.playSound(eruptFrom, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.HOSTILE, 3f, 0.5f);
            }
            case SHARDS -> {
                shardTargets.clear();
                volleyDamage.clear();
                List<Player> below = new ArrayList<>(m.undercroftPlayers(home));
                below.removeIf(p -> !body.hasLineOfSight(p));
                below.sort(Comparator.comparingDouble(p -> -(p.getLocation().distance(body.getLocation()) + (high(p) ? 20 : 0))));   // up high and far off first
                shardTargets.addAll(below.subList(0, Math.min(3, below.size())));
                if (shardTargets.isEmpty() && target != null) shardTargets.add(target);
                for (Player p : shardTargets) {
                    p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.HOSTILE, 1.5f, 1.8f);
                    p.sendActionBar(Ui.mm("<#ff3fa4>⚠ The crystals on the Golem's back turn towards you"));
                }
            }
            default -> {}
        }
    }

    /** The heart-stone flares before every move: the "get ready" tell. */
    private void flare() {
        Location c = body.getLocation().add(0, 2.1 * S, 0);
        world.spawnParticle(Particle.DUST, c, 20, 0.4, 0.4, 0.4, GREEN);
        world.playSound(c, Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.HOSTILE, 1.5f, 1.4f);
        world.playSound(c, Sound.ENTITY_WARDEN_ANGRY, SoundCategory.HOSTILE, 1.2f, 0.7f);
        wave(body.getLocation().getX(), body.getLocation().getZ(), 5.5, GolemModel.HEART_GREEN);   // a ring of green light: "here it comes"
    }

    /** The ore on its back charging up: motes gathering on the crystals. */
    private void oreCharge() {
        Location c = local(0, 5.4, -1.1);
        world.spawnParticle(Particle.DUST, c, 6, 1.1, 0.7, 0.5, dust(1.4f));
        if (stateT % 6 == 0) world.spawnParticle(Particle.END_ROD, c, 2, 1, 0.6, 0.5, 0.02);
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
        trackDuringWindup();
        double u = stateT / (double) Math.max(1, windup);
        if (stateT % 2 == 0) switch (move) {
            case SLAM, ROCKFALL -> {
                ring(body.getLocation(), 1.6 + (stateT % 6) * 0.15, Particle.BLOCK);
                if (stateT % 4 == 0) crack(body.getLocation().getX(), body.getLocation().getZ(), 1.2 + u * 2.6, 0.2 + u * 0.35);   // the floor gives under it as it rears up
            }
            case BOULDER -> {
                circle(aim, 2.5, dust(1.4f));
                if (stateT == 2) { Location f = local(-2.6 * S, 0, 2.2); crack(f.getX(), f.getZ(), 1.4, 0.6); debris(f.getX(), f.getZ(), 5, 0.6); }   // it rips the boulder out of the floor
            }
            case ROOTS -> { for (Location l : marks) circle(l, 1.3, GREEN); }
            case CHARGE -> {
                Location c = body.getLocation().add(0, 0.2, 0);
                for (double s = 2; s <= 14; s += 0.8) world.spawnParticle(Particle.DUST, c.clone().add(chargeDir.clone().multiply(s)), 1, 0.15, 0, 0.15, RED);
                if (stateT % 6 == 0) { crack(c.getX(), c.getZ(), 1.6, 0.4); world.playSound(c, Sound.ENTITY_RAVAGER_STEP, SoundCategory.HOSTILE, 1.5f, 0.5f); }   // pawing the floor
            }
            case ERUPTION -> {
                oreCharge();
                for (double[] ln : lanes)   // where the crystals will come up: lines on the floor, up to the first thing in the way
                    for (double r = 1.5; r <= GolemMath.ERUPTION_REACH; r += 0.9) {
                        double x = eruptFrom.getX() + ln[0] * r, z = eruptFrom.getZ() + ln[1] * r;
                        if (!floorClear(x, z)) break;
                        world.spawnParticle(Particle.DUST, x, home.getY() + 0.15, z, 1, 0.1, 0, 0.1, dust(1.3f));
                    }
            }
            case SHARDS -> {
                oreCharge();
                for (Player p : shardTargets) if (p.isOnline()) circle(p.getLocation(), 1.0, dust(1.3f));
            }
            default -> {}
        }
        if (stateT >= windup) to(State.ACT, 0);
    }

    private void act() {
        switch (move) {
            case SWIPE -> {
                if (stateT < impact()) return;   // the arm comes round first
                Location at = body.getLocation();
                for (Player p : m.undercroftPlayers(home)) {
                    Location l = p.getLocation();
                    double dx = l.getX() - at.getX(), dz = l.getZ() - at.getZ();
                    if (Math.hypot(dx, dz) <= 4.5 && Math.abs(l.getY() - at.getY()) < 3 && GolemMath.inFront(yaw, dx, dz, 75))
                        hurt(p, 4, new Vector(dx, 0, dz).normalize().multiply(0.9).setY(0.35));
                }
                world.playSound(at, Sound.ENTITY_IRON_GOLEM_ATTACK, SoundCategory.HOSTILE, 1.5f, 0.6f);
                world.playSound(at, Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.HOSTILE, 1.5f, 0.5f);
                swipeFx(at);
                Location arc = local(0, 1.2, 2.6);
                world.spawnParticle(Particle.SWEEP_ATTACK, arc, 4, 1.2, 0.2, 0.8);
                Location f = local(1.0, 0, 3.2);
                crack(f.getX(), f.getZ(), 1.3, 0.45);   // its knuckles drag across the floor
                // FW-003: fix melee lock - a swipe used to end() and restart the special countdown, and its cooldown
                // (20 ticks at 150% speed) is shorter than that countdown (29-66), so a player in reach was swiped
                // forever and no special ever fired. Now an in-between swipe leaves the countdown running; only a
                // swipe that WAS the special pick restarts it.
                if (pacing.swiped((int) (30 / pace))) end(10);
                else to(State.WALK, 16);
            }
            case SLAM, ROCKFALL -> {
                if (stateT < impact()) return;
                if (stateT == impact()) impact(move == GolemMath.Move.ROCKFALL ? 3.5 : 4.5, move == GolemMath.Move.ROCKFALL ? 10 : 16);
                if (move == GolemMath.Move.ROCKFALL) { rockfall(); return; }
                Location at = body.getLocation();
                boolean twice = phase() >= 3;
                int tt0 = stateT - impact() + 1;
                for (int wave = 0; wave < (twice ? 2 : 1); wave++) {
                    int tt = tt0 - wave * 15;
                    if (tt < 1) continue;
                    double r = tt * RING_SPEED;
                    if (r <= RING_REACH) {
                        ring(at, r, Particle.DUST);
                        if (tt % 2 == 0) ringCracks(at, r);
                    }
                    for (Player p : m.undercroftPlayers(home)) {
                        Location l = p.getLocation();
                        double d = Math.hypot(l.getX() - at.getX(), l.getZ() - at.getZ());
                        if (GolemMath.ringHits(d, tt, RING_SPEED, 1.4, l.getY() - home.getY(), RING_REACH)
                                && slamHit.add(p.getUniqueId() + ":" + wave)) hurt(p, 6, new Vector(0, 0.75, 0));   // each wave hits a player once
                    }
                }
                if (tt0 * RING_SPEED > RING_REACH + (twice ? 15 * RING_SPEED : 0)) end(12);
            }
            case BOULDER -> {
                Location from = local(-2.4, 5.6, 0.6);   // out of its right hand, now forward of the shoulder
                int ticks = (int) Math.max(14, Math.min(26, from.distance(aim) * 1.2));
                int rel = impact();   // let go as the arm comes over
                if (stateT == rel) {
                    boulder = world.spawn(from, BlockDisplay.class, e -> {
                        e.setBlock(Bukkit.createBlockData(Material.MOSSY_COBBLESTONE));
                        e.setPersistent(false);
                        e.setTeleportDuration(1);
                        e.setTransformation(new Transformation(new Vector3f(-0.65f), new Quaternionf(), new Vector3f(1.3f), new Quaternionf()));
                    });
                    world.playSound(from, Sound.ENTITY_IRON_GOLEM_ATTACK, SoundCategory.HOSTILE, 2f, 0.4f);
                    world.playSound(from, Sound.ITEM_TRIDENT_THROW, SoundCategory.HOSTILE, 2f, 0.5f);
                    world.playSound(from, Sound.ENTITY_WIND_CHARGE_WIND_BURST, SoundCategory.HOSTILE, 2f, 0.6f);
                    burst(from, 2);
                }
                if (stateT < rel) return;
                if (boulder != null && boulder.isValid()) {
                    double f = GolemIntro.hang((stateT - rel) / (double) ticks, 0.6);   // leaves the hand fast, hangs at the top, comes down hard
                    double[] p = GolemMath.arc(from.getX(), from.getY(), from.getZ(), aim.getX(), aim.getY() + 0.65, aim.getZ(), 4.5, f);
                    boulder.teleport(new Location(world, p[0], p[1], p[2]));
                    // it tumbles with its flight: fast off the hand, slow at the top, fast again coming down (posed every tick)
                    double spin = GolemProjectile.boulderSpin(f);
                    Quaternionf q = new Quaternionf().rotateXYZ((float) spin, (float) (spin * 0.57), 0);
                    boulder.setInterpolationDelay(0);
                    boulder.setInterpolationDuration(1);
                    boulder.setTransformation(new Transformation(new Vector3f(-0.65f).rotate(new Quaternionf(q)), q, new Vector3f(1.3f), new Quaternionf()));
                    world.spawnParticle(Particle.BLOCK, p[0], p[1], p[2], 2, 0.3, 0.3, 0.3, STONE);
                    world.spawnParticle(Particle.FLAME, p[0], p[1], p[2], 2, 0.25, 0.25, 0.25, 0.01);   // it burns through the air
                    world.spawnParticle(Particle.LARGE_SMOKE, p[0], p[1], p[2], 2, 0.3, 0.3, 0.3, 0.01);
                    world.spawnParticle(Particle.DUST_COLOR_TRANSITION, p[0], p[1], p[2], 1, 0.25, 0.25, 0.25, 0, new Particle.DustTransition(Color.fromRGB(0x8A8580), Color.fromRGB(0x3A3835), 1.8f));
                    if (stateT % 2 == 0) circle(aim, GolemProjectile.ringRadius(f, 2.5, 0.9), dust(1.4f));   // the warning ring closes in as it comes down
                    if (stateT - rel >= ticks) {
                        boulder.remove();
                        boulder = null;
                        world.spawnParticle(Particle.EXPLOSION, aim, 2, 0.5, 0.2, 0.5);
                        world.spawnParticle(Particle.DUST_PILLAR, aim.clone().add(0, 0.2, 0), 30, 1.4, 0.1, 1.4, 0.2, floorAt(aim.getX(), aim.getZ()));
                        world.playSound(aim, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 1.5f, 0.8f);
                        world.playSound(aim, Sound.ITEM_MACE_SMASH_GROUND, SoundCategory.HOSTILE, 2f, 0.6f);
                        slamDrama(aim, 3.5);
                        crack(aim.getX(), aim.getZ(), 2.8, 0.9);
                        ringCracks(aim, 1.8);
                        ring(aim, 2.0, Particle.BLOCK);
                        ceilingDust(aim.getX(), aim.getZ(), 3, 10);
                        debris(aim.getX(), aim.getZ(), 10, 1.0);
                        for (Player pl : m.undercroftPlayers(home)) {
                            Vector away = pl.getLocation().toVector().subtract(aim.toVector());
                            if (away.length() <= 3) hurt(pl, 7, away.setY(0).normalize().multiply(0.8).setY(0.5));
                        }
                        end(10);
                    }
                } else end(10);
            }
            case ROOTS -> {
                if (stateT < impact()) return;   // fists into the floor first
                impact(2.5, 6);
                for (Location l : marks) {
                    for (Player p : m.undercroftPlayers(home))
                        if (p.getLocation().distance(l) <= 1.5 && hit.add(p.getUniqueId())) {
                            hurt(p, 2, new Vector(0, 0, 0));
                            rooted.put(p.getUniqueId(), 40);
                            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 6, false, false));
                        }
                    crack(l.getX(), l.getZ(), 1.6, 0.75);
                    debris(l.getX(), l.getZ(), 3, 0.6);
                    for (int i = 0; i < 4; i++) {
                        double a = Math.PI / 2 * i + rnd.nextDouble();
                        Location at = l.clone().add(Math.cos(a) * 0.6, -0.2, Math.sin(a) * 0.6);
                        float lean = (float) (rnd.nextDouble() - 0.5);
                        BlockDisplay r = world.spawn(at, BlockDisplay.class, e -> {
                            e.setBlock(Bukkit.createBlockData(Material.MANGROVE_ROOTS));
                            e.setPersistent(false);
                            e.setTransformation(new Transformation(new Vector3f(-0.15f, -1.2f, -0.15f), new Quaternionf().rotateZ(lean), new Vector3f(0.3f, 1.2f, 0.3f), new Quaternionf()));
                        });
                        effect(r, 40);
                        glides.add(new Glide(r, new Transformation(new Vector3f(-0.15f, 0, -0.15f), new Quaternionf().rotateZ(lean), new Vector3f(0.3f, 1.8f, 0.3f), new Quaternionf()), t + 1, 4));
                        glides.add(new Glide(r, new Transformation(new Vector3f(-0.15f, -1.6f, -0.15f), new Quaternionf().rotateZ(lean), new Vector3f(0.3f, 1.8f, 0.3f), new Quaternionf()), t + 32, 7));
                    }
                    world.playSound(l, Sound.BLOCK_ROOTS_BREAK, SoundCategory.HOSTILE, 2f, 0.6f);
                    world.playSound(l, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, SoundCategory.HOSTILE, 1.4f, 0.5f);
                    burst(l.clone().add(0, 0.4, 0), 1.3);
                    world.spawnParticle(Particle.HAPPY_VILLAGER, l.clone().add(0, 0.5, 0), 14, 0.6, 0.5, 0.6, 0);
                    wave(l.getX(), l.getZ(), 4, GolemModel.HEART_GREEN);
                }
                end(8);
            }
            case CHARGE -> {
                Location at = body.getLocation();
                double sp = CHARGE_SPEED * slow() * Math.min(1, 0.35 + stateT * 0.13);   // it builds up speed
                double nx = at.getX() + chargeDir.getX() * sp, nz = at.getZ() + chargeDir.getZ() * sp;
                double[] l = GolemMath.leash(nx - home.getX(), nz - home.getZ(), LEASH);
                boolean leashed = Math.abs(l[0] - (nx - home.getX())) > 1e-6 || Math.abs(l[1] - (nz - home.getZ())) > 1e-6;
                if (wallAt(nx, nz)) {
                    Location hitAt = local(0, 2.5, 2.2);
                    slamDrama(hitAt, 4.5);
                    crack(hitAt.getX(), hitAt.getZ(), 2.4, 0.9);
                    debris(hitAt.getX(), hitAt.getZ(), 12, 1.0);
                    world.playSound(hitAt, Sound.ENTITY_WITHER_BREAK_BLOCK, SoundCategory.HOSTILE, 2f, 0.7f);
                    hitStop(4);
                    stun(60, "<#ff3fa4>The Golem slammed into a pillar! <white>It's stunned and takes +50% damage.");
                    return;
                }
                if (leashed || blocked(nx, nz) || stateT > 32 / slow()) { end(12); return; }
                body.teleport(new Location(world, nx, home.getY(), nz, yaw, 0));
                if (stateT % 2 == 0) world.spawnParticle(Particle.BLOCK, at, 12, 1.2, 0.1, 1.2, STONE);
                world.spawnParticle(Particle.CLOUD, at.clone().add(0, 0.3, 0), 3, 1.0, 0.2, 1.0, 0.03);   // a wall of dust behind it
                world.spawnParticle(Particle.CRIT, at.clone().add(0, 0.2, 0), 4, 1.0, 0.1, 1.0, 0.25);   // sparks off its knuckles
                if (stateT % 3 == 0) crack(at.getX(), at.getZ(), 1.7, 0.55);
                if (stateT % 6 == 0) {
                    world.playSound(at, Sound.ENTITY_RAVAGER_STEP, SoundCategory.HOSTILE, 2f, 0.6f);
                    world.spawnParticle(Particle.DUST_PILLAR, at.clone().add(0, 0.1, 0), 8, 0.8, 0.05, 0.8, 0.1, floorAt(at.getX(), at.getZ()));
                    debris(at.getX(), at.getZ(), 2, 0.6);
                }
                for (Player p : m.undercroftPlayers(home)) {
                    Location pl = p.getLocation();
                    if (Math.hypot(pl.getX() - nx, pl.getZ() - nz) <= 2.4 && Math.abs(pl.getY() - home.getY()) < 3 && hit.add(p.getUniqueId())) {
                        Vector side = new Vector(-chargeDir.getZ(), 0, chargeDir.getX());
                        if (side.dot(pl.toVector().subtract(at.toVector())) < 0) side.multiply(-1);
                        hurt(p, 7, chargeDir.clone().multiply(0.9).add(side.multiply(0.8)).setY(0.55));
                    }
                }
            }
            case ERUPTION -> erupt();
            case SHARDS -> volley();
        }
    }

    /** Both fists hit the floor in front of it: a crater of cracks, rubble, a dust burst, hit-stop. */
    private void impact(double radius, int rubble) {
        Location f = local(0, 0, 2.6);
        slamDrama(f, radius);
        crack(f.getX(), f.getZ(), radius, 0.95);
        for (int side : new int[]{-1, 1}) { Location k = local(side * 2.6 * S, 0, 2.4); crack(k.getX(), k.getZ(), 1.3, 1.0); }
        debris(f.getX(), f.getZ(), rubble, 1.15);
        world.spawnParticle(Particle.DUST_PILLAR, f.clone().add(0, 0.1, 0), (int) (radius * 10), radius * 0.6, 0.05, radius * 0.6, 0.2, floorAt(f.getX(), f.getZ()));
        world.spawnParticle(Particle.EXPLOSION, f.clone().add(0, 0.3, 0), 3, 1.5, 0.1, 1.5);
        ceilingDust(f.getX(), f.getZ(), 3, 6 + (int) radius * 2);   // the whole hall feels it
        world.playSound(f, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 2f, 0.6f);
        world.playSound(f, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, SoundCategory.HOSTILE, 2.5f, 0.6f);
    }

    /** Cracks racing along under a slam's shockwave. */
    private void ringCracks(Location c, double r) {
        int n = Math.max(6, (int) (r * 2 * Math.PI / 1.1));
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n;
            double x = c.getX() + Math.cos(a) * r, z = c.getZ() + Math.sin(a) * r;
            crackBlock((int) Math.floor(x), home.getBlockY() - 1, (int) Math.floor(z), 0.45f + rnd.nextFloat() * 0.2f, 12);
            if (i % 3 == 0) world.spawnParticle(Particle.DUST_PILLAR, x, home.getY() + 0.1, z, 1, 0.1, 0, 0.1, 0.05, floorAt(x, z));
        }
    }

    /** Rockfall: the ceiling starts coming down, mostly on top of players. */
    private void rockfall() {
        List<Player> below = m.undercroftPlayers(home);
        for (int i = 0; i < 10; i++) {
            double x, z;
            if (!below.isEmpty() && rnd.nextBoolean()) {
                Location pl = below.get(rnd.nextInt(below.size())).getLocation();
                x = pl.getX() - home.getX() + rnd.nextGaussian();
                z = pl.getZ() - home.getZ() + rnd.nextGaussian();
            } else {
                double a = rnd.nextDouble() * Math.PI * 2, r = Math.sqrt(rnd.nextDouble()) * 11 * ForgeWarsMaps.HALL;
                x = Math.cos(a) * r;
                z = Math.sin(a) * r;
            }
            rocks.add(new double[]{home.getX() + x, home.getZ() + z, t + 20 + i * 10, t});
        }
        end(12);
    }

    // ------------------------------------------------------------------ Crystal Eruption

    /** Standing room at (x, z) on the hall floor: nothing solid at feet height, and floor under it. */
    private boolean floorClear(double x, double z) {
        int bx = (int) Math.floor(x), bz = (int) Math.floor(z), fy = home.getBlockY();
        return world.getBlockAt(bx, fy, bz).isPassable() && world.getBlockAt(bx, fy + 1, bz).isPassable() && !world.getBlockAt(bx, fy - 1, bz).isPassable();
    }

    /**
     * Its fists go into the floor and lanes of crystal race out from it towards the target, a spike every 1.2 blocks,
     * cracking the floor as they come. A spike throws anyone standing on it into the air. Step between the lanes (the gap
     * opens up further out) or get out of their way; a lane stops at the first pillar or boulder.
     */
    private void erupt() {
        if (stateT < impact()) return;
        if (stateT == impact()) impact(2.5, 8);
        double front = 1.5 + (stateT - impact()) * ERUPT_SPEED * slow();
        while (true) {
            double r = 1.5 + eruptStep * GolemMath.SPIKE_GAP;
            if (r > Math.min(front, GolemMath.ERUPTION_REACH)) break;
            for (double[] ln : lanes) {
                if (ln[2] != 0) continue;
                double x = eruptFrom.getX() + ln[0] * r, z = eruptFrom.getZ() + ln[1] * r;
                if (!floorClear(x, z)) { ln[2] = 1; world.spawnParticle(Particle.BLOCK, x, home.getY() + 0.5, z, 20, 0.3, 0.3, 0.3, crystalGlass()); continue; }
                spike(x, z, ln);
            }
            double[] mid = lanes.get(lanes.size() / 2);
            world.playSound(new Location(world, eruptFrom.getX() + mid[0] * r, home.getY(), eruptFrom.getZ() + mid[1] * r),
                    Sound.BLOCK_AMETHYST_CLUSTER_BREAK, SoundCategory.HOSTILE, 1.6f, 0.55f + eruptStep * 0.04f);
            world.playSound(new Location(world, eruptFrom.getX() + mid[0] * r, home.getY(), eruptFrom.getZ() + mid[1] * r),
                    Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.HOSTILE, 1.4f, Math.min(2f, 0.8f + eruptStep * 0.1f));   // a chime climbing as the lanes run out
            eruptStep++;
        }
        boolean left = false;
        for (double[] ln : lanes) left |= ln[2] == 0;
        if (front > GolemMath.ERUPTION_REACH || !left) end(12);
    }

    /** One crystal: a glowing core in a glass sheath, punching up out of the floor (with a little overshoot), then sinking back. */
    private void spike(double x, double z, double[] lane) {
        Location at = new Location(world, x, home.getY(), z);
        burst(at.clone().add(0, 0.5, 0), 1.2);
        Quaternionf q = new Quaternionf().rotateX((float) Math.toRadians((rnd.nextDouble() - 0.5) * 24)).rotateZ((float) Math.toRadians((rnd.nextDouble() - 0.5) * 24));
        float h = (float) (1.6 + rnd.nextDouble() * 1.1);
        BlockData coreB = crystalCore(), glassB = crystalGlass();
        for (int layer = 0; layer < 2; layer++) {
            boolean core = layer == 1;
            float w = core ? 0.3f : 0.7f, hh = core ? h + 0.4f : h;
            BlockDisplay d = world.spawn(at, BlockDisplay.class, e -> {
                e.setBlock(core ? coreB : glassB);
                e.setPersistent(false);
                e.setBrightness(new Display.Brightness(15, 15));
                e.setTransformation(column(w, hh, -hh - 0.1f, q));
            });
            effect(d, 42);
            glides.add(new Glide(d, column(w, hh * 1.08f, -0.2f, q), t + 1, 2));      // punches up past full height...
            glides.add(new Glide(d, column(w, hh, -0.3f, q), t + 3, 3));              // ...settles...
            glides.add(new Glide(d, column(w, hh, -hh - 0.1f, q), t + 30, 10));       // ...and sinks back after a moment
        }
        crack(x, z, 1.1, 0.75);
        if (rnd.nextBoolean()) debris(x, z, 2, 0.7);
        world.spawnParticle(Particle.DUST_PILLAR, at.clone().add(0, 0.1, 0), 5, 0.3, 0.05, 0.3, 0.1, floorAt(x, z));
        world.spawnParticle(Particle.DUST, at.clone().add(0, 1, 0), 6, 0.3, 0.6, 0.3, dust(1.3f));
        for (Player p : m.undercroftPlayers(home)) {
            Location l = p.getLocation();
            if (Math.hypot(l.getX() - x, l.getZ() - z) <= GolemMath.SPIKE_HIT + 0.3 && Math.abs(l.getY() - home.getY()) < 2.5 && hit.add(p.getUniqueId()))
                hurt(p, 5, new Vector(lane[0] * 0.3, 0.95, lane[1] * 0.3));
        }
    }

    /** A column {@code w} wide and {@code h} tall, tilted by {@code q} about the middle of its foot, foot at height {@code y}. */
    private static Transformation column(float w, float h, float y, Quaternionf q) {
        Vector3f c = new Vector3f(-w / 2, 0, -w / 2).rotate(new Quaternionf(q));
        return new Transformation(new Vector3f(c.x, c.y + y, c.z), new Quaternionf(q), new Vector3f(w, h, w), new Quaternionf());
    }

    // ------------------------------------------------------------------ Shard Volley

    /** The crystals on its back that carry a shard (the big glowing spikes, not the specks), top first. */
    private List<Integer> spikes() {
        List<Integer> l = new ArrayList<>();
        for (int i = 0; i < data.size(); i++) {
            GolemModel.Part p = data.get(i);
            if (p.group() == GolemModel.Group.ORE && p.glow() >= 0 && p.sy() > 0.5 && parts.get(i).isValid()) l.add(i);
        }
        l.sort(Comparator.comparingDouble(i -> -data.get(i).cy()));
        return l;
    }

    /**
     * Every crystal on its back fires its own shard, one every 2 ticks. Each swoops off its crystal to a spot above and
     * behind the Golem, hovers there and charges (swelling, spinning up, light gathering in), turns onto its target, draws
     * back like a bowstring, then crawls forward and whips away (time-remapped: slow, then quickly fast). The crystal is
     * left a dim stub that grows back.
     * Shards are split between the marked players; 1 heart each, at most 3 hearts per player per volley.
     */
    private void volley() {
        if (stateT == 1) {
            List<Integer> sp = spikes();
            if (shardTargets.isEmpty() || sp.isEmpty()) { end(10); return; }
            int every = (int) Math.round(2 / slow());
            for (int i = 0; i < sp.size(); i++) {
                int launch = t + i * every, fire = launch + GolemProjectile.FIRE_DELAY;
                shards.add(new Shard(sp.get(i), shardTargets.get(i % shardTargets.size()), launch, fire, i, sp.size()));
                volleyEnd = fire;
            }
            Location bl = body.getLocation().add(0, 5.5, -1);
            world.playSound(bl, Sound.ENTITY_WARDEN_SONIC_CHARGE, SoundCategory.HOSTILE, 3f, 1.5f);   // three layers: a rising whine, the crystals ringing, a low swell
            world.playSound(bl, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.HOSTILE, 3f, 0.6f);
            world.playSound(bl, Sound.BLOCK_BEACON_POWER_SELECT, SoundCategory.HOSTILE, 2f, 0.7f);
            wave(body.getLocation().getX(), body.getLocation().getZ(), 9, oreRgb());
        }
        if (t >= volleyEnd + 4) end(12);
    }

    private static Transformation shardTf(float w, float len, Vector dir, float spin) {
        Quaternionf q = new Quaternionf().rotationTo(0, 0, 1, (float) dir.getX(), (float) dir.getY(), (float) dir.getZ()).rotateZ(spin);
        Vector3f c = new Vector3f(-w / 2, -w / 2, -len / 2).rotate(new Quaternionf(q));
        return new Transformation(c, q, new Vector3f(w, w, len), new Quaternionf());
    }

    private void shardLook(Shard s, Vector dir, float spin, int ticks) { shardLook(s, dir, spin, ticks, 1f, 1f); }

    private void shardLook(Shard s, Vector dir, float spin, int ticks, float stretch) { shardLook(s, dir, spin, ticks, stretch, 1f); }

    /** Pose a shard's two parts: pointing along {@code dir}, rolled {@code spin} about its length, stretched into a streak when fast, swollen while charging. */
    private void shardLook(Shard s, Vector dir, float spin, int ticks, float stretch, float swell) {
        for (BlockDisplay d : new BlockDisplay[]{s.core, s.glass}) {
            if (d == null || !d.isValid()) continue;
            boolean core = d == s.core;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(ticks);
            d.setTransformation(shardTf((core ? 0.16f : 0.32f) * swell, (core ? 0.85f : 0.6f) * stretch * (1 + (swell - 1) * 0.7f), dir, spin));
        }
    }

    /** A fading pink streak behind a shard. */
    private void trail(Location at) {
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 1, 0.03, 0.03, 0.03, 0, new Particle.DustTransition(Color.fromRGB(oreRgb()), Color.fromRGB(0x2A0014), 1.1f));
    }

    private Vector aimAt(Shard s) {
        Player p = s.target;
        Location to = p.getLocation().add(0, 1.0, 0);
        Vector v = p.getVelocity();
        double dist = s.pos.distance(to.toVector());
        double[] a = GolemMath.leadAim(to.getX(), to.getY(), to.getZ(), v.getX(), v.getY(), v.getZ(), dist, GolemMath.shardEffectiveSpeed(dist), 0.6);
        Vector dir = new Vector(a[0], a[1], a[2]).subtract(s.pos);
        return dir.lengthSquared() < 1e-4 ? new Vector(0, 0, 1) : dir.normalize();
    }

    private void tickShards() {
        for (var it = shards.iterator(); it.hasNext(); ) {
            Shard s = it.next();
            if (t < s.launchAt) continue;
            if (!s.spawned) {
                if (body == null || !s.target.isOnline()) { it.remove(); continue; }
                launch(s);
                continue;
            }
            boolean gone = !s.core.isValid();
            if (!s.fired) {
                int u = t - s.launchAt;
                Vector hover = body != null ? local(s.slot[0], s.slot[1], s.slot[2]).toVector() : s.hover;   // it follows the Golem
                s.hover = hover;
                double c = 0;   // charge, 0..1
                int cu = u - GolemProjectile.PEEL;
                if (cu < 0) {   // fly off the back: bursts out, overshoots its spot a little, settles
                    double e = GolemProjectile.peel(u / (double) GolemProjectile.PEEL);
                    s.pos = s.from.clone().add(hover.clone().subtract(s.from).multiply(e)).add(new Vector(0, Math.sin(Math.PI * u / GolemProjectile.PEEL) * 0.9, 0));
                    s.spin += 0.15f + 0.5f * (float) Math.max(0, 1 - e);
                } else {   // hover and charge: it trembles harder, spins up and swells as the light gathers in
                    c = GolemProjectile.charge(cu / (double) GolemProjectile.CHARGE);
                    double sh = 0.04 + 0.1 * c;
                    s.pos = hover.clone().add(new Vector(Math.sin(cu * 0.5) * 0.12 + Math.sin(t * 2.7 + s.idx) * sh, Math.sin(cu * 0.6) * 0.08 + Math.sin(t * 3.1 + s.idx * 2) * sh, Math.sin(t * 2.3 + s.idx * 3) * sh));
                    s.spin += 0.15f + 0.9f * (float) c;
                    if (t % 2 == 0 && c > 0.04) {   // light gathers in on it
                        double rad = 1.3 * (1 - c) + 0.15;
                        for (int k = 0; k < 2; k++) {
                            Vector off = new Vector(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian());
                            if (off.lengthSquared() < 1e-6) continue;
                            off.normalize().multiply(rad);
                            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, s.pos.getX() + off.getX(), s.pos.getY() + off.getY(), s.pos.getZ() + off.getZ(), 1, 0, 0, 0, 0,
                                new Particle.DustTransition(Color.fromRGB(oreRgb()), Color.WHITE, 0.9f));
                        }
                    }
                    if (s.idx % 3 == 0 && t % 3 == 0) world.playSound(s.pos.toLocation(world), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.HOSTILE, 0.6f, (float) (0.6 + 1.3 * c));   // the hum climbs as it charges
                }
                // then a smooth turn onto the target (it keeps tracking), and a short lock so a quick player can dodge
                int turnStart = s.fireAt - GolemProjectile.LOCK - GolemProjectile.TURN, lockAt = s.fireAt - GolemProjectile.LOCK;
                Vector facing = s.axis;
                if (t >= turnStart) {
                    if (t < lockAt && s.target.isOnline()) s.lockDir = aimAt(s);
                    Vector to = s.lockDir != null ? s.lockDir : s.axis;
                    double[] v = GolemProjectile.slerp(new double[]{s.axis.getX(), s.axis.getY(), s.axis.getZ()}, new double[]{to.getX(), to.getY(), to.getZ()}, GolemProjectile.turn(t - turnStart));
                    facing = new Vector(v[0], v[1], v[2]);
                }
                if (t >= lockAt) s.pos = s.pos.clone().subtract(facing.clone().multiply(0.8 * GolemProjectile.pullBack((t - lockAt + 1) / (double) GolemProjectile.LOCK)));   // drawn back like a bowstring
                shardLook(s, facing, s.spin, 1, 1f, (float) (1 + 0.5 * c));
                if (t % 2 == 0) world.spawnParticle(Particle.DUST_COLOR_TRANSITION, s.pos.getX(), s.pos.getY(), s.pos.getZ(), 2, 0.12, 0.12, 0.12, 0,
                        new Particle.DustTransition(Color.fromRGB(oreRgb()), Color.fromRGB(0xFFD6F0), 1.0f + (float) c));   // it glows brighter as it charges: light, not an outline
                if (t >= s.fireAt) {
                    s.fired = true;
                    s.dir = s.lockDir != null ? s.lockDir.clone() : s.axis.clone();
                    Location at = s.pos.toLocation(world);
                    fireFx(s, at);
                }
            } else {
                // flight: creeps for a moment, then whips forward (time-remapped); it rolls faster and stretches into a streak as it speeds up
                double speed = GolemMath.shardSpeed(s.flyT++);
                int steps = (int) Math.ceil(speed / 0.4);
                Block hitBlock = null;
                for (int k = 0; k < steps && !gone; k++) {
                    s.pos.add(s.dir.clone().multiply(speed / steps));
                    s.travelled += speed / steps;
                    Location at = s.pos.toLocation(world);
                    if (!at.getBlock().isPassable()) { gone = true; hitBlock = at.getBlock(); break; }
                    for (Player p : m.undercroftPlayers(home)) {
                        if (p.getLocation().add(0, 0.9, 0).distanceSquared(at) > 0.85 * 0.85) continue;
                        double dealt = volleyDamage.getOrDefault(p.getUniqueId(), 0.0);
                        double dmg = Math.min(GolemMath.SHARD_DAMAGE, GolemMath.SHARD_CAP - dealt);
                        if (dmg > 0) { volleyDamage.put(p.getUniqueId(), dealt + dmg); hurt(p, dmg, s.dir.clone().multiply(0.35).setY(0.15)); world.playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.HOSTILE, 1.4f, 1.5f); }
                        gone = true;
                        break;
                    }
                    trail(at);
                }
                if (s.travelled > 40) gone = true;
                if (hitBlock != null && hitBlock.getY() <= home.getBlockY() + 1) crackBlock(hitBlock.getX(), hitBlock.getY(), hitBlock.getZ(), 0.6f, 20);
                if (s.flyT > 3 && s.flyT % 2 == 0) world.spawnParticle(Particle.END_ROD, s.pos.toLocation(world), 1, 0, 0, 0, 0);
                s.spin += (float) GolemProjectile.rollRate(speed);
                if (!gone) shardLook(s, s.dir, s.spin, 1, (float) GolemProjectile.stretch(speed));
            }
            if (gone) { shatter(s); it.remove(); continue; }
            Location l = s.pos.toLocation(world);
            s.core.teleport(l);
            if (s.glass != null && s.glass.isValid()) s.glass.teleport(l);
        }
    }

    /** A shard comes off its crystal: the crystal goes dim and short, the shard appears at its tip. */
    private void launch(Shard s) {
        GolemModel.Part p = data.get(s.part);
        double[] tip = GolemAnim.point(p, last, 0.5, 1, 0.5), root = GolemAnim.point(p, last, 0.5, 0, 0.5);
        Location tipW = local(tip[0], tip[1], tip[2]), rootW = local(root[0], root[1], root[2]);
        s.axis = tipW.toVector().subtract(rootW.toVector());
        if (s.axis.lengthSquared() < 1e-6) s.axis = new Vector(0, 1, 0);
        s.axis.normalize();
        s.from = tipW.toVector();
        s.slot = GolemProjectile.hoverSlot(s.idx, s.n);   // a spot above and behind the Golem, in its own frame
        s.hover = local(s.slot[0], s.slot[1], s.slot[2]).toVector();
        s.pos = s.from.clone();
        BlockData coreB = crystalCore(), glassB = crystalGlass();
        Vector ax = s.axis.clone();
        s.core = world.spawn(tipW, BlockDisplay.class, e -> {
            e.setBlock(coreB);
            e.setPersistent(false);
            e.setTeleportDuration(1);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setTransformation(shardTf(0.16f, 0.85f, ax, 0));
        });
        s.glass = world.spawn(tipW, BlockDisplay.class, e -> {
            e.setBlock(glassB);
            e.setPersistent(false);
            e.setTeleportDuration(1);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setTransformation(shardTf(0.32f, 0.6f, ax, 0));
        });
        s.spawned = true;
        spentUntil.put(s.part, t + 80);
        ore(s.part, Ore.OUT);
        if (sheathOf[s.part] >= 0) { spentUntil.put(sheathOf[s.part], t + 80); ore(sheathOf[s.part], Ore.OUT); }
        world.playSound(tipW, Sound.BLOCK_AMETHYST_BLOCK_BREAK, SoundCategory.HOSTILE, 1.2f, Math.min(2f, 1.0f + s.idx * 0.06f));   // the volley climbs like a scale
        world.playSound(tipW, Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR, SoundCategory.HOSTILE, 0.7f, Math.min(2f, 0.8f + s.idx * 0.08f));   // with a shimmer on top
        burst(tipW, 1.2);
        world.spawnParticle(Particle.DUST, tipW, 8, 0.15, 0.15, 0.15, dust(1.1f));
    }

    private void shatter(Shard s) {
        Location at = s.pos.toLocation(world);
        world.spawnParticle(Particle.BLOCK, at, 14, 0.15, 0.15, 0.15, crystalGlass());
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 10, 0.25, 0.25, 0.25, 0, new Particle.DustTransition(Color.fromRGB(oreRgb()), Color.fromRGB(0x2A0014), 1.3f));
        world.spawnParticle(Particle.END_ROD, at, 6, 0.1, 0.1, 0.1, 0.12);
        world.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_BREAK, SoundCategory.HOSTILE, 0.8f, 1.6f);
        world.playSound(at, Sound.BLOCK_GLASS_BREAK, SoundCategory.HOSTILE, 0.9f, 1.8f);
        if (s.core != null) s.core.remove();
        if (s.glass != null) s.glass.remove();
    }

    // ------------------------------------------------------------------ drama

    private void wave(double x, double z, double reach, int rgb) { waves.add(new double[]{x, z, t, reach, rgb}); }

    /** Rings of light running out over the floor, a little faster than a player walks, with sparks lifting off them. */
    private void tickWaves() {
        for (var it = waves.iterator(); it.hasNext(); ) {
            double[] w = it.next();
            double r = 1.2 + (t - w[2]) * 0.85;
            if (r > w[3]) { it.remove(); continue; }
            int n = Math.max(10, (int) (r * 6));
            Particle.DustTransition dt = new Particle.DustTransition(Color.fromRGB((int) w[4]), Color.WHITE, 1.5f);
            for (int i = 0; i < n; i++) {
                double a = Math.PI * 2 * i / n, x = w[0] + Math.cos(a) * r, z = w[1] + Math.sin(a) * r;
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, x, home.getY() + 0.2, z, 1, 0.05, 0.05, 0.05, 0, dt);
                if (i % 4 == 0) world.spawnParticle(Particle.END_ROD, x, home.getY() + 0.3, z, 0, 0, 1, 0, 0.1);   // count 0: the offset is a direction, so it drifts upward
            }
        }
    }

    /** A burst of light and sparks: {@code power} 1 is a spark, 3 a detonation. */
    private void burst(Location at, double power) {
        int n = (int) (10 * power);
        world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, n, 0.3 * power, 0.3 * power, 0.3 * power, 0, new Particle.DustTransition(Color.fromRGB(oreRgb()), Color.WHITE, 1.6f));
        world.spawnParticle(Particle.END_ROD, at, Math.max(2, n / 2), 0.15 * power, 0.15 * power, 0.15 * power, 0.12 * power);
        if (power >= 2) world.spawnParticle(Particle.EXPLOSION, at, 1, 0, 0, 0, 0);
    }

    /** The weight of a heavy blow on the floor: a ring of light running out, a flash at the point of impact, a boom under the crash. */
    private void slamDrama(Location f, double radius) {
        wave(f.getX(), f.getZ(), 4 + radius * 2.2, oreRgb());
        burst(f.clone().add(0, 0.6, 0), 1 + radius / 3);
        if (radius >= 3) {
            world.spawnParticle(Particle.EXPLOSION_EMITTER, f.clone().add(0, 0.5, 0), 1);
            world.spawnParticle(Particle.SONIC_BOOM, f.clone().add(0, 1.2, 0), 1);
        }
        world.playSound(f, Sound.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.HOSTILE, radius >= 3 ? 1.4f : 0.7f, 0.7f);
        world.playSound(f, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.HOSTILE, 1.2f, 0.5f);
    }

    /** The swipe: a crescent of slashes and sparks where the arm passes, and a crack on the floor in front. */
    private void swipeFx(Location at) {
        for (int k = -5; k <= 5; k++) {
            double a = Math.toRadians(yaw + k * 14), rr = 3.4;
            double x = at.getX() - Math.sin(a) * rr, z = at.getZ() + Math.cos(a) * rr;
            world.spawnParticle(Particle.SWEEP_ATTACK, x, at.getY() + 1.3, z, 1, 0, 0, 0, 0);
            world.spawnParticle(Particle.CRIT, x, at.getY() + 1.3, z, 4, 0.2, 0.3, 0.2, 0.3);
        }
        burst(local(0, 1.0, 3.0), 1.2);
        world.playSound(at, Sound.ITEM_MACE_SMASH_GROUND, SoundCategory.HOSTILE, 1.4f, 1.3f);
    }

    /** The shard leaves its draw: a flash at the nose, then a whoosh that climbs through the volley like a scale, with a crack under it. */
    private void fireFx(Shard s, Location at) {
        float pitch = (float) Math.min(2.0, 0.9 + s.idx * 0.07);
        world.playSound(at, Sound.ENTITY_BREEZE_SHOOT, SoundCategory.HOSTILE, 1.5f, pitch);
        world.playSound(at, Sound.ITEM_TRIDENT_RIPTIDE_1, SoundCategory.HOSTILE, 1.0f, Math.min(2f, pitch + 0.3f));
        world.playSound(at, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, SoundCategory.HOSTILE, 1.3f, 1.8f);
        if (s.idx % 4 == 0) world.playSound(at, Sound.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.HOSTILE, 0.5f, 1.9f);
        burst(at, 1.0);
        world.spawnParticle(Particle.FIREWORK, at, 6, 0.1, 0.1, 0.1, 0.15);
    }

    /**
     * The crystals shine without an outline (an outline shows through walls): light of their own colour drifts off every
     * crystal that hasn't just fired its shard.
     */
    private void crystalGlow() {
        if (t % 4 != 0 || body == null) return;
        if (spikeParts == null) spikeParts = spikes();
        for (int i : spikeParts) {
            Integer until = spentUntil.get(i);
            if (until != null && t < until + 12) continue;
            GolemModel.Part p = data.get(i);
            double[] tip = GolemAnim.point(p, last, 0.5, 1, 0.5), mid = GolemAnim.point(p, last, 0.5, 0.6, 0.5);
            Location tw = local(tip[0], tip[1], tip[2]), mw = local(mid[0], mid[1], mid[2]);
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, mw, 1, 0.12, 0.12, 0.12, 0, new Particle.DustTransition(Color.fromRGB(oreRgb()), Color.fromRGB(0xFFD6F0), 1.1f));
            if (rnd.nextInt(3) == 0) world.spawnParticle(Particle.END_ROD, tw, 1, 0.05, 0.05, 0.05, 0.01);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void end(int restTicks) {
        to(State.WALK, restTicks + 6);
        pacing.restart(GolemMath.paced(GolemMath.moveGap(phase()) + rnd.nextInt(20), slow() * pace, 20));
    }

    /**
     * When the Golem may start its next special move and its next swipe, counted in walking ticks. Plain Java (no Bukkit),
     * so the scheduling can be unit-tested without loading the Golem itself.
     */
    static final class Pacing {
        private int nextMove, swipeCd;

        Pacing(int firstMove) { nextMove = firstMove; }

        /** Every walking tick (blind or not): the swipe cooldown runs down. */
        void tick() { swipeCd--; }

        /** Every walking tick with a target: counts the special down; true when one is due. */
        boolean specialDue() { return --nextMove <= 0; }

        boolean swipeReady() { return swipeCd <= 0; }

        /**
         * A swipe landed: start its cooldown. Returns true if the special countdown must be restarted - only when this
         * swipe was itself the special pick (countdown already at 0); otherwise the countdown keeps running. (FW-003)
         */
        boolean swiped(int cooldown) { swipeCd = cooldown; return nextMove <= 0; }

        /** Start a fresh countdown to the next special move. */
        void restart(int ticks) { nextMove = ticks; }

        int nextMove() { return nextMove; }
    }

    private void stun(int ticks, String msg) {
        to(State.STUNNED, 6);
        stunTicks = ticks;
        world.playSound(body.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 1.5f, 1.4f);
        world.spawnParticle(Particle.BLOCK, body.getLocation().add(0, 2, 0), 60, 1.2, 1, 1.2, STONE);
        for (Player p : m.undercroftPlayers(home)) p.sendMessage(Ui.mm(msg));
    }

    /** Hurt a player, scaled by golem-damage-percent, with a shove. */
    private void hurt(Player p, double dmg, Vector push) {
        if (!m.isAlive(p) || p.getGameMode() != GameMode.SURVIVAL || body == null) return;
        p.damage(dmg * damageScale, body);
        if (push.lengthSquared() > 0) p.setVelocity(push);
    }

    /** An effect display that goes away by itself after {@code life} ticks. */
    private void effect(Display d, int life) { fx.add(d); fxRemoveAt.put(d, t + life); }

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

    /** A falling rock's orientation: its own fixed tilt (from where it lands) plus {@code a} radians of tumble. */
    private Quaternionf rockTurn(double[] r, double a) {
        return new Quaternionf().rotateXYZ((float) (r[0] * 0.37 + a), (float) (r[1] * 0.21 + a * 0.7), (float) (a * 0.5));
    }

    /** Rocks: a warning circle, then the rock appears under the ceiling and falls, faster and faster. */
    private void tickRocks() {
        for (var it = rocks.iterator(); it.hasNext(); ) {
            double[] r = it.next();
            int left = (int) (r[2] - t);
            Location spot = new Location(world, r[0], home.getY() + 0.1, r[1]);
            if (left > 0 && left % 3 == 0) {
                circle(spot, 1.9, RED);   // the danger zone is exactly the radius that hurts
                circle(spot, GolemProjectile.ringRadius((t - r[3]) / Math.max(1, r[2] - r[3]), 1.9, 0.3), RED);   // with a ring closing in on it
            }
            if (left == 15) {
                Location top = new Location(world, r[0], home.getY() + 17, r[1]);
                Quaternionf q = rockTurn(r, 0);
                BlockDisplay rock = world.spawn(top, BlockDisplay.class, e -> {
                    e.setBlock(Bukkit.createBlockData(rnd.nextBoolean() ? Material.COBBLESTONE : Material.TUFF));
                    e.setPersistent(false);
                    e.setTeleportDuration(1);
                    e.setTransformation(new Transformation(new Vector3f(-0.5f).rotate(new Quaternionf(q)), q, new Vector3f(1f), new Quaternionf()));
                });
                effect(rock, 16);
                rockShown.put(r, rock);
                world.spawnParticle(Particle.FALLING_DUST, top, 8, 0.4, 0.1, 0.4, Bukkit.createBlockData(Material.TUFF));
            }
            if (left < 15 && left > 0) {   // hangs at the ceiling, trembling, then drops: a slow start, a savage finish
                BlockDisplay rock = rockShown.get(r);
                double u = left > 10 ? 0 : (10 - left) / 10.0;
                if (rock != null && rock.isValid()) {
                    double wob = left > 10 ? Math.sin(t * 3.3) * 0.1 : 0;
                    Location at = spot.clone().add(wob, 16.9 * (1 - GolemProjectile.rockDrop(u)), -wob);
                    rock.teleport(at);
                    Quaternionf q = rockTurn(r, GolemProjectile.rockSpin(u) + wob);   // it tumbles all the way down
                    rock.setInterpolationDelay(0);
                    rock.setInterpolationDuration(1);
                    rock.setTransformation(new Transformation(new Vector3f(-0.5f).rotate(new Quaternionf(q)), q, new Vector3f(1f), new Quaternionf()));
                    world.spawnParticle(Particle.FALLING_DUST, at, 2, 0.3, 0.4, 0.3, Bukkit.createBlockData(Material.TUFF));
                    if (left == 9) world.playSound(spot.clone().add(0, 6, 0), Sound.ENTITY_PHANTOM_SWOOP, SoundCategory.HOSTILE, 1.2f, 0.6f);
                }
            }
            if (left <= 0) {
                world.spawnParticle(Particle.BLOCK, spot, 40, 0.9, 0.3, 0.9, STONE);
                world.spawnParticle(Particle.DUST_PILLAR, spot, 12, 0.6, 0.05, 0.6, 0.1, floorAt(r[0], r[1]));
                world.playSound(spot, Sound.BLOCK_STONE_BREAK, SoundCategory.HOSTILE, 1.5f, 0.6f);
                world.playSound(spot, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 0.8f, 1.2f);
                world.playSound(spot, Sound.ITEM_MACE_SMASH_GROUND, SoundCategory.HOSTILE, 1.4f, 0.8f);
                burst(spot.clone().add(0, 0.5, 0), 1.6);
                wave(r[0], r[1], 5, 0x8A8580);
                crack(r[0], r[1], 1.6, 0.8);
                debris(r[0], r[1], 4, 0.7);
                for (Player p : m.undercroftPlayers(home))
                    if (p.getLocation().distance(spot) <= 1.9 && Math.abs(p.getLocation().getY() - home.getY()) < 3) hurt(p, 5, new Vector(0, 0.3, 0));
                BlockDisplay rock = rockShown.remove(r);
                if (rock != null) rock.remove();
                it.remove();
            }
        }
    }

    private void tickFx() {
        for (var it = glides.iterator(); it.hasNext(); ) {   // a transformation set on the spawn tick isn't animated, so it waits a tick
            Glide g = it.next();
            if (g.at() > t) continue;
            it.remove();
            if (!g.d().isValid()) continue;
            g.d().setInterpolationDelay(0);
            g.d().setInterpolationDuration(g.ticks());
            g.d().setTransformation(g.to());
        }
        for (var it = fx.iterator(); it.hasNext(); ) {
            Display d = it.next();
            Integer at = fxRemoveAt.get(d);
            if (!d.isValid() || at != null && t >= at) { d.remove(); fxRemoveAt.remove(d); it.remove(); }
        }
    }

    private void ring(Location c, double r, Particle kind) {
        int n = Math.max(8, (int) (r * 7));
        Particle.DustOptions ore = dust(1.4f);
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n;
            Location l = new Location(world, c.getX() + Math.cos(a) * r, home.getY() + 0.15, c.getZ() + Math.sin(a) * r);
            if (kind == Particle.BLOCK) world.spawnParticle(Particle.BLOCK, l, 1, 0, 0, 0, STONE);
            else world.spawnParticle(Particle.DUST, l, 1, 0, 0.05, 0, i % 3 == 0 ? ore : GREEN);
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
        if (state == State.INTRO || state == State.RISING || state == State.DYING || state == State.DEAD || attacker == null || !m.isAlive(attacker)) { e.setCancelled(true); return; }
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
            heartScale = 1.6;   // the next pose sent shows the bigger heart
            world.spawnParticle(Particle.BLOCK, body.getLocation().add(0, 2.6, 1.5), 80, 0.8, 0.6, 0.3, Bukkit.createBlockData(Material.CRACKED_STONE_BRICKS));
            world.playSound(body.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 2f, 0.8f);
            hitStop(4);
            for (Player p : m.undercroftPlayers(home)) p.sendMessage(Ui.mm("<#ff3fa4>The Golem's chest plate shatters! <gray>It charges and brings the ceiling down now."));
        } else {
            world.playSound(body.getLocation(), Sound.ENTITY_RAVAGER_ROAR, SoundCategory.HOSTILE, 3f, 0.4f);
            for (Player p : m.undercroftPlayers(home)) p.sendMessage(Ui.mm("<red><bold>The Golem is enraged!</bold> <gray>Faster, more crystals, and every slam hits twice."));
        }
        ceilingDust(home.getX(), home.getZ(), 9, 70);
        world.playSound(home, Sound.BLOCK_DEEPSLATE_BREAK, SoundCategory.HOSTILE, 3f, 0.5f);
        refreshBar();
    }

    private Team dieBest;
    private UUID dieTop;

    /** Grit shaken off the hall's ceiling (red once the Golem is bleeding). */
    private void ceilingDust(double x, double z, double spread, int n) {
        world.spawnParticle(Particle.FALLING_DUST, x, home.getY() + 18, z, n, spread, 0.5, spread, Bukkit.createBlockData(blood > 0.5 ? Material.RED_CONCRETE_POWDER : Material.TUFF));
    }

    /** The killing blow: it doesn't fall at once. It's thrown back, sinks to its knees and shudders ({@link #dying}). */
    private void die() {
        // the team that dealt the most damage wins it; the last hit breaks a tie
        Team best = null;
        double top = -1;
        for (var e : teamDamage.entrySet()) if (e.getValue() > top || e.getValue() == top && e.getKey() == lastTeam) { top = e.getValue(); best = e.getKey(); }
        UUID topPlayer = null;
        double topDmg = -1;
        for (var e : playerDamage.entrySet()) if (best != null && best.members.contains(e.getKey()) && e.getValue() > topDmg) { topDmg = e.getValue(); topPlayer = e.getKey(); }
        dieBest = best;
        dieTop = topPlayer;
        to(State.DYING, 8);
        body.setInvulnerable(true);
        body.setVelocity(new Vector(0, 0, 0));
        hideBar();
        for (Shard s : shards) shatter(s);
        shards.clear();
        if (blindTag != null) { blindTag.remove(); blindTag = null; }
        Location at = body.getLocation();
        hitStop(5);
        world.playSound(at, Sound.ENTITY_WARDEN_HURT, SoundCategory.HOSTILE, 3f, 0.5f);
        world.playSound(at, Sound.ENTITY_IRON_GOLEM_DEATH, SoundCategory.HOSTILE, 3f, 0.5f);
        crack(at.getX(), at.getZ(), 2.5, 0.9);
        debris(at.getX(), at.getZ(), 10, 0.9);
        ceilingDust(at.getX(), at.getZ(), 4, 20);
    }

    /** Its last second or so: crystals go dark one by one, cracks run across the floor, the hall sheds grit. */
    private void dying() {
        int s = stateT;
        Location at = body.getLocation();
        for (int i = 0; i < parts.size(); i++) {
            if (data.get(i).group() != GolemModel.Group.ORE || s != GolemFinale.lightsOut(i, parts.size())) continue;
            ore(i, Ore.OUT);
            world.spawnParticle(Particle.DUST, local(0, 4.6, -1.0), 3, 0.9, 0.7, 0.6, dust(1.2f));
            world.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_BREAK, SoundCategory.HOSTILE, 1.4f, 0.5f + rnd.nextFloat() * 0.4f);
        }
        if (s == 34) eyes(3);
        if (s == 54) eyes(0);
        if (s % 5 == 0) {
            double a = rnd.nextDouble() * Math.PI * 2, r = 0.8 + s * 0.06;
            crack(at.getX() + Math.cos(a) * r, at.getZ() + Math.sin(a) * r, 1.2, 0.7);
        }
        ceilingDust(at.getX(), at.getZ(), 5, 1 + s / 12);
        if (s % 4 == 0) world.spawnParticle(Particle.BLOCK, at.clone().add(0, 2.5, 0), 5, 1, 1.2, 1, STONE);   // bits shaken off it
        if (s % 8 == 0) world.playSound(at, Sound.BLOCK_DEEPSLATE_BREAK, SoundCategory.HOSTILE, 2f, 0.5f);
        if (s >= GolemFinale.DIE) collapse();
    }

    /** The end: it falls apart, the floor breaks, grit pours down, and the winners are named. */
    private void collapse() {
        Location at = body.getLocation();
        to(State.DEAD, 0);
        body.remove();
        body = null;
        for (int i = 0; i < parts.size(); i++) {
            BlockDisplay d = parts.get(i);
            if (!d.isValid()) continue;
            GolemModel.Part p = data.get(i);
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(20);
            d.setTransformation(transform(p, (rnd.nextDouble() - 0.5) * 2, -p.cy() + 0.1, (rnd.nextDouble() - 0.5) * 2, 0.8));
            if (p.group() == GolemModel.Group.ORE || p.bright()) { d.setBrightness(new Display.Brightness(7, 7)); d.setGlowing(false); }   // the light goes out
        }
        crack(at.getX(), at.getZ(), 4.5, 0.95);
        debris(at.getX(), at.getZ(), 28, 1.1);
        ceilingDust(at.getX(), at.getZ(), 6, 70);
        world.spawnParticle(Particle.EXPLOSION_EMITTER, at, 1);
        world.spawnParticle(Particle.DUST, at.clone().add(0, 2, 0), 80, 1.5, 1.5, 1.5, dust(1.4f));
        world.spawnParticle(Particle.POOF, at.clone().add(0, 0.5, 0), 40, 2, 0.3, 2, 0.08);
        world.playSound(at, Sound.ENTITY_WARDEN_DEATH, SoundCategory.HOSTILE, 3f, 0.7f);
        world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, SoundCategory.HOSTILE, 3f, 0.5f);
        m.golemDefeated(dieBest, dieTop, at);
    }

    private void hideBar() {
        for (UUID id : viewers) { Player p = Bukkit.getPlayer(id); if (p != null) p.hideBossBar(bar); }
        viewers.clear();
    }

    /** Take everything out of the world (match over, or the Golem is gone). A half-sealed breach is filled in at once. */
    void remove() {
        if (removed) return;
        removed = true;
        hideBar();
        parts.forEach(Entity::remove);
        fx.forEach(Entity::remove);
        for (Shard s : shards) { if (s.core != null) s.core.remove(); if (s.glass != null) s.glass.remove(); }
        shards.clear();
        glides.clear();
        for (Chunk c : chunks) c.d.remove();
        chunks.clear();
        for (Rubble r : falling) { r.d.remove(); world.getBlockAt(r.x, r.y, r.z).setType(r.type, false); }
        falling.clear();
        while (!sealQueue.isEmpty()) { int[] b = sealQueue.poll(); Block bl = world.getBlockAt(b[0], b[1], b[2]); if (bl.isPassable()) bl.setType(Material.COBBLESTONE, false); }
        for (var e : cracks.entrySet()) { e.getValue()[3] = 0; sendCrack(e.getKey(), e.getValue()); }
        cracks.clear();
        if (boulder != null) boulder.remove();
        if (blindTag != null) blindTag.remove();
        if (body != null) body.remove();
        rooted.clear();
        rocks.clear();
        for (BlockDisplay r : rockShown.values()) r.remove();
        rockShown.clear();
    }
}
