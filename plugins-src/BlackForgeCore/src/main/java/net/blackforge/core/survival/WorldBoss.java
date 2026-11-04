package net.blackforge.core.survival;

import net.blackforge.core.BlackForgeCore;
import net.blackforge.core.NetEconomy;
import net.blackforge.core.Ui;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.io.File;
import java.util.*;

/**
 * The Sundering: five bosses lie chained in their own arena ({@link SunderingArena}). Every Saturday at 1pm Central, or with
 * /blackforge boss start [id], one wakes up, breaks its chains and fights everyone. Each attack has a visible wind-up. Your
 * share of the damage is your share of the money and Scrap and your chance on each key, shown live on a leaderboard. A boss
 * that isn't killed in time retreats to its pit and pays part of the pool. PvP is off in the arena.
 */
public final class WorldBoss implements Listener {
    enum Ability { SLAM, FIRE_RAIN, SUMMON, CHARGE, ARROW_STORM, BLINK, WITHER_PULSE, FROST_NOVA, LIGHTNING }

    /** One kind of boss. */
    record Boss(String id, String name, String color, Class<? extends Mob> type, double health, double scale, double damage,
                Material weapon, Class<? extends Mob> minion, BossBar.Color barColor, Particle aura, List<Ability> abilities) {}

    static final List<Boss> BOSSES = List.of(
            new Boss("ravager", "Molten Ravager", "<#ff4d00>", Ravager.class, 1500, 2.4, 14, null, MagmaCube.class, BossBar.Color.RED, Particle.FLAME,
                    List.of(Ability.SLAM, Ability.FIRE_RAIN, Ability.SUMMON, Ability.CHARGE)),
            new Boss("colossus", "Bone Colossus", "<#e8e2cf>", Skeleton.class, 1200, 3.0, 10, Material.BOW, Skeleton.class, BossBar.Color.WHITE, Particle.WHITE_ASH,
                    List.of(Ability.ARROW_STORM, Ability.SUMMON, Ability.SLAM, Ability.BLINK)),
            new Boss("revenant", "Void Revenant", "<#9c7cff>", WitherSkeleton.class, 1300, 2.5, 16, Material.NETHERITE_SWORD, WitherSkeleton.class, BossBar.Color.PURPLE, Particle.PORTAL,
                    List.of(Ability.BLINK, Ability.WITHER_PULSE, Ability.SUMMON, Ability.LIGHTNING)),
            new Boss("titan", "Frost Titan", "<#9fe6ff>", IronGolem.class, 1700, 2.6, 18, null, Stray.class, BossBar.Color.BLUE, Particle.SNOWFLAKE,
                    List.of(Ability.FROST_NOVA, Ability.SLAM, Ability.CHARGE, Ability.SUMMON)),
            new Boss("brute", "Storm Brute", "<#ffe066>", PiglinBrute.class, 1400, 2.4, 15, Material.GOLDEN_AXE, Vindicator.class, BossBar.Color.YELLOW, Particle.ELECTRIC_SPARK,
                    List.of(Ability.LIGHTNING, Ability.CHARGE, Ability.SLAM, Ability.SUMMON)));

    private enum Phase { IDLE, WAKING, FIGHT, RESET }

    private static final int WINDUP_TICKS = 28;
    private static final double SLAM_R = 14, NOVA_R = 16, PULSE_R = 14, REACH = 60;
    private static boolean fightLive;

    private final BlackForgeCore plugin;
    private final World world;
    private final Crates crates;
    private final SunderingArena arena;
    private final Random rnd = new Random();
    private final Map<UUID, Double> damage = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final List<Entity> adds = new ArrayList<>();
    private final File store;
    private final YamlConfiguration saved;
    private final double size, fightSeconds, playerDps, minShare, retreatShare;
    private final int timeLimit;
    private final long moneyPool, scrapPool;
    private final List<String> keyRolls;
    private Phase phase = Phase.IDLE;
    private SunderingArena.Sleeper chosen;
    private Boss kind;
    private Mob boss;
    private BossModel model;
    private BossBar bar;
    private TextDisplay finalBoard;
    private org.bukkit.scheduler.BukkitTask wakeTask;
    private int seconds, nextAbility, wakeTick;
    private double multiplier = 1;

    WorldBoss(BlackForgeCore plugin, World world, Crates crates) {
        this.plugin = plugin;
        this.world = world;
        this.crates = crates;
        var cfg = plugin.getConfig();
        size = cfg.getDouble("boss.size", 2.0);
        fightSeconds = cfg.getDouble("boss.fight-seconds", 600);
        playerDps = cfg.getDouble("boss.player-dps", 12);
        timeLimit = cfg.getInt("boss.time-limit-seconds", 1200);
        moneyPool = cfg.getLong("boss.money-pool", 25000);
        scrapPool = cfg.getLong("boss.scrap-pool", 1500);
        minShare = cfg.getDouble("boss.min-share", 0.01);
        retreatShare = cfg.getDouble("boss.retreat-share", 0.25);
        List<String> rolls = cfg.getStringList("boss.key-rolls");
        keyRolls = rolls.isEmpty() ? List.of("netherite", "gold", "gold", "iron", "iron", "iron") : rolls;
        store = new File(plugin.getDataFolder(), "sundering.yml");
        saved = YamlConfiguration.loadConfiguration(store);
        arena = new SunderingArena(plugin, size);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 20L);
    }

    SunderingArena arena() { return arena; }
    public boolean active() { return phase != Phase.IDLE; }
    /** True while a fight is on in the arena: the abilities allowed there use this. */
    static boolean liveAt(Location l) { return fightLive && l.getWorld().getName().equals(SunderingArena.WORLD); }
    private String title() { return kind.color() + "<bold>" + kind.name(); }
    private void save() { try { saved.save(store); } catch (Exception ex) { plugin.getLogger().warning("The Sundering: couldn't save: " + ex.getMessage()); } }

    // ------------------------------------------------------------------ schedule

    private void tick() {
        long now = System.currentTimeMillis();
        long slot = net.blackforge.core.Events.bossSlot(now);
        // saved, so a restart during the event window can't start a second fight
        if (phase == Phase.IDLE && arena.ready() && now - slot < 20 * 60_000L && saved.getLong("last-slot") != slot) {
            saved.set("last-slot", slot);
            save();
            start(null);
        }
        if (phase == Phase.FIGHT) {
            if (boss == null || !boss.isValid()) end(null);
            else fight();
        }
    }

    /** Wake a boss: the given id, or a random one (never the last one). */
    public String start(String id) {
        if (active()) return "A boss is already up.";
        if (!arena.ready()) return "The arena is still being built.";
        int last = -1;
        for (int i = 0; i < BOSSES.size(); i++) if (BOSSES.get(i).id().equals(saved.getString("last-boss"))) last = i;
        kind = BOSSES.stream().filter(b -> b.id().equalsIgnoreCase(String.valueOf(id))).findFirst()
                .orElse(BOSSES.get(SunderingMath.pickBoss(BOSSES.size(), last, rnd.nextDouble())));
        saved.set("last-boss", kind.id());
        save();
        damage.clear();
        names.clear();
        seconds = 0;
        nextAbility = 8;
        wakeTick = 0;
        if (finalBoard != null) finalBoard.remove();
        arena.hold(true);
        chosen = arena.sleeper(kind.id());
        boss = chosen.host;
        model = chosen.model;
        if (boss == null) { arena.chain(chosen, false); boss = chosen.host; model = chosen.model; }
        phase = Phase.WAKING;
        bar = BossBar.bossBar(Ui.mm("<gray>Something stirs..."), 0f, kind.barColor(), BossBar.Overlay.NOTCHED_20);
        wakeTask = Bukkit.getScheduler().runTaskTimer(plugin, this::wake, 1L, 1L);
        return "Waking the " + kind.name() + ".";
    }

    // ------------------------------------------------------------------ the wake-up

    private void wake() {
        if (boss == null || !boss.isValid()) { end(null); return; }
        World w = arena.world();
        int t = ++wakeTick;
        for (Player p : w.getPlayers()) p.showBossBar(bar);
        if (t < SunderingMath.TREMOR_END) {
            for (SunderingArena.Sleeper s : arena.sleepers()) if (s.model != null) s.model.setSlump(1 - 0.18 * Math.abs(Math.sin(t * 0.5 + s.pit)));
            bar.progress((float) t / SunderingMath.LANDS);
            if (t % 20 == 1) for (Player p : w.getPlayers()) { p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1.2f, 0.6f); p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.25f, 0.5f); }
            return;
        }
        if (t == SunderingMath.TREMOR_END) {
            for (SunderingArena.Sleeper s : arena.sleepers()) if (s.model != null && s != chosen) s.model.setSlump(1);
            bar.name(Ui.mm(title()));
            Bukkit.broadcast(Ui.mm(kind.color() + "⚑ <bold>THE " + kind.name().toUpperCase() + " IS WAKING!</bold> <gray>Your share of the damage is your share of the loot. "
                    + "<u><click:run_command:'/warp sundering'><#4fd1c5>[Join]</click></u>"));
            for (Player p : w.getPlayers()) p.showTitle(net.kyori.adventure.title.Title.title(Ui.mm(title()), Ui.mm("<gray>is breaking free")));
        }
        bar.progress((float) Math.min(1, t / (double) SunderingMath.LANDS));
        Location at = boss.getLocation();
        if (t <= SunderingMath.BREAK_OUT) {
            if (model != null) model.setSlump(SunderingMath.slump(t));
            w.spawnParticle(kind.aura(), at.clone().add(0, 4, 0), 6, 2.5, 2.5, 2.5, 0.02);
            int snap = SunderingMath.snapAt(t);
            if (snap >= 0) {
                arena.snap(chosen, snap);
                if (model != null) model.pulse();
                w.playSound(at, Sound.BLOCK_ANVIL_BREAK, 6f, 0.6f);
                w.playSound(at, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 6f, 0.5f);
            }
        }
        if (t == SunderingMath.BREAK_OUT) {
            w.strikeLightningEffect(at);
            w.spawnParticle(Particle.EXPLOSION_EMITTER, at, 4, 3, 1, 3);
            w.playSound(at, Sound.ENTITY_ENDER_DRAGON_GROWL, 10f, 0.5f);
            shockwave(at);
            if (model != null) model.pulse();
        }
        if (t > SunderingMath.BREAK_OUT) {
            Location from = arena.home(chosen), to = arena.center();
            double[] p = SunderingMath.leap(from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ(), t - SunderingMath.BREAK_OUT, SunderingMath.LANDS - SunderingMath.BREAK_OUT, 28);
            Location step = new Location(w, p[0], p[1], p[2], from.getYaw(), 0f);
            boss.teleport(step);
        }
        if (t >= SunderingMath.LANDS) land();
    }

    /** Looks only: a ring of fire rolling out from the pit. */
    private void shockwave(Location at) {
        final int[] step = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (++step[0] > 20) { task.cancel(); return; }
            ring(at, step[0] * 2.2, Particle.FLAME, 48);
        }, 0L, 1L);
    }

    private void land() {
        wakeTask.cancel();
        wakeTask = null;
        Location l = boss.getLocation();
        World w = l.getWorld();
        w.spawnParticle(Particle.EXPLOSION_EMITTER, l, 5, 4, 1, 4);
        w.playSound(l, Sound.ENTITY_GENERIC_EXPLODE, 6f, 0.5f);
        w.playSound(l, Sound.ENTITY_ENDER_DRAGON_GROWL, 8f, 0.6f);
        arena.release(chosen);
        if (model != null) model.releaseYaw();
        boss.setAI(true);
        boss.setInvulnerable(false);
        phase = Phase.FIGHT;
        fightLive = true;
    }

    // ------------------------------------------------------------------ display (screenshots)

    private static final String DISPLAY_TAG = "boss_display";

    /** Keep all five bosses up (chained in their pits) in the Dragon's Graveyard so they can be looked at. */
    public String display(boolean on) {
        arena.hold(on);
        return on ? "The five bosses are standing in their pits in the Dragon's Graveyard (/warp worldboss). /blackforge boss display off lets them sleep again." : "Display released.";
    }

    private static void set(LivingEntity e, Attribute a, double v) { var i = e.getAttribute(a); if (i != null) i.setBaseValue(v); }

    /** A boss's invisible mob: the model is what people see, this is its hitbox. */
    static Mob spawnHost(Location at, Boss b, double size) {
        return at.getWorld().spawn(at, b.type(), m -> {
            m.customName(Ui.mm(b.color() + "<bold>" + b.name()));
            m.setCustomNameVisible(true);
            m.setPersistent(false);
            m.setRemoveWhenFarAway(false);
            set(m, Attribute.MAX_HEALTH, b.health());
            set(m, Attribute.SCALE, b.scale() * size);
            set(m, Attribute.ATTACK_DAMAGE, b.damage());
            set(m, Attribute.ARMOR, 12);
            set(m, Attribute.KNOCKBACK_RESISTANCE, 1);
            set(m, Attribute.FOLLOW_RANGE, 100);
            m.setHealth(b.health());
            if (m instanceof PiglinAbstract pa) pa.setImmuneToZombification(true);
            if (m instanceof IronGolem g) g.setPlayerCreated(false);
            m.setInvisible(true);
        });
    }

    private void hideBar() { if (bar != null) for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar); }

    private void clearFight() {
        fightLive = false;
        if (wakeTask != null) { wakeTask.cancel(); wakeTask = null; }
        adds.forEach(Entity::remove);
        adds.clear();
        hideBar();
    }

    /** Staff or shutdown: no payout. */
    public void stop(String why) {
        clearFight();
        boolean wasOn = phase != Phase.IDLE;
        if (boss != null) boss.remove();
        if (model != null) model.remove();
        boss = null;
        model = null;
        phase = Phase.IDLE;
        if (why != null) Bukkit.broadcast(Ui.mm("<#ff4d00>⚑ <gray>" + why));
        if (!plugin.isEnabled()) return;
        if (wasOn && chosen != null) arena.chain(chosen, true);
        arena.hold(false);
    }

    /** The fight is over: bring the boss back in chains. */
    private void end(Runnable after) {
        clearFight();
        boss = null;
        model = null;
        phase = Phase.RESET;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (chosen != null) arena.chain(chosen, true);
            phase = Phase.IDLE;
            arena.hold(false);
            if (after != null) after.run();
        }, 200L);
    }

    /** Out of time: it leaps back to its pit, and part of the pool is paid. */
    private void retreat() {
        Bukkit.broadcast(Ui.mm(kind.color() + "⚑ <bold>The " + kind.name() + " retreats!</bold> <gray>It'll be back in chains. Part of the loot is still paid."));
        clearFight();
        payOut(retreatShare, false);
        phase = Phase.RESET;
        boss.setAI(false);
        boss.setInvulnerable(true);
        Location from = boss.getLocation(), to = arena.home(chosen);
        final int[] step = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (boss == null || !boss.isValid() || ++step[0] > SunderingMath.RETURN_TICKS) {
                task.cancel();
                if (boss != null) boss.remove();
                if (model != null) model.remove();
                boss = null;
                model = null;
                arena.chain(chosen, true);
                phase = Phase.IDLE;
                arena.hold(false);
                return;
            }
            double[] p = SunderingMath.leap(from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ(), step[0], SunderingMath.RETURN_TICKS, 28);
            boss.teleport(new Location(from.getWorld(), p[0], p[1], p[2], to.getYaw() + 180, 0f));
        }, 0L, 1L);
    }

    // ------------------------------------------------------------------ the fight

    private List<Player> fighters() {
        List<Player> out = new ArrayList<>();
        for (Player p : arena.world().getPlayers()) if (p.getGameMode() == GameMode.SURVIVAL) out.add(p);
        return out;
    }

    private List<Player> nearby(double r) {
        List<Player> out = new ArrayList<>();
        for (Player p : fighters()) if (p.getLocation().distanceSquared(boss.getLocation()) < r * r) out.add(p);
        return out;
    }

    private void fight() {
        seconds++;
        World w = arena.world();
        Location c = arena.center();
        if (boss.getLocation().distanceSquared(c) > Math.pow(SunderingMath.RADIUS - 6, 2)) boss.teleport(c);   // stays in the arena
        double max = kind.health(), frac = boss.getHealth() / max;
        multiplier = SunderingMath.incomingMultiplier(max, fighters().size(), fightSeconds, playerDps);
        bar.progress((float) Math.max(0, Math.min(1, frac)));
        bar.name(Ui.mm(title() + "</bold> <gray>" + Math.round(frac * 100) + "% <dark_gray>| <gray>" + (timeLimit - seconds) / 60 + "m left"));
        for (Player p : Bukkit.getOnlinePlayers()) { if (p.getWorld() == w) p.showBossBar(bar); else p.hideBossBar(bar); }
        w.spawnParticle(kind.aura(), boss.getLocation().add(0, 4, 0), 14, 3, 3, 3, 0.02);
        adds.removeIf(e -> !e.isValid());
        List<Player> targets = nearby(REACH);
        if (boss.getTarget() == null && !targets.isEmpty()) boss.setTarget(targets.get(rnd.nextInt(targets.size())));   // golems don't hunt players on their own
        if (seconds >= timeLimit) { retreat(); return; }
        if (--nextAbility > 0 || targets.isEmpty()) return;
        nextAbility = (int) Math.round(SunderingMath.cadenceSeconds(frac, rnd.nextDouble()));
        if (model != null) model.pulse();
        windUp(kind.abilities().get(rnd.nextInt(kind.abilities().size())), targets);
    }

    /** Every attack shows where it's going to land, then gives you time to get out. */
    private void windUp(Ability a, List<Player> targets) {
        World w = boss.getWorld();
        List<Location> spots = new ArrayList<>();
        for (Player p : targets) spots.add(p.getLocation());
        Player one = targets.get(rnd.nextInt(targets.size()));
        Player farthest = targets.stream().max(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(boss.getLocation()))).orElse(one);
        Location blinkTo = behind(one);
        Location chargeTo = farthest.getLocation();
        final int[] t = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (phase != Phase.FIGHT || boss == null || !boss.isValid()) { task.cancel(); return; }
            t[0] += 4;
            Location l = boss.getLocation();
            switch (a) {
                case SLAM -> ring(l, SLAM_R, Particle.FLAME, 56);
                case FROST_NOVA -> ring(l, NOVA_R, Particle.SNOWFLAKE, 64);
                case WITHER_PULSE -> ring(l, PULSE_R, Particle.SMOKE, 56);
                case FIRE_RAIN -> spots.forEach(s -> ring(s, 2, Particle.FLAME, 14));
                case LIGHTNING -> spots.forEach(s -> ring(s, 2, Particle.ELECTRIC_SPARK, 14));
                case BLINK -> ring(blinkTo, 2, Particle.PORTAL, 14);
                case CHARGE -> line(l, chargeTo);
                case ARROW_STORM, SUMMON -> w.spawnParticle(kind.aura(), l.clone().add(0, 6, 0), 20, 2, 2, 2, 0.02);
            }
            if (t[0] == 4) w.playSound(l, Sound.BLOCK_NOTE_BLOCK_BASS, 5f, 0.5f);
            if (t[0] < WINDUP_TICKS) return;
            task.cancel();
            List<Player> now = nearby(REACH);
            if (now.isEmpty()) return;
            switch (a) {
                case SLAM -> slam(now);
                case FIRE_RAIN -> fireRain(spots);
                case SUMMON -> summon();
                case CHARGE -> charge(chargeTo, farthest);
                case ARROW_STORM -> arrowStorm(now);
                case BLINK -> blink(blinkTo, one);
                case WITHER_PULSE -> pulse(now, PotionEffectType.WITHER, Particle.SMOKE, Sound.ENTITY_WITHER_SHOOT);
                case FROST_NOVA -> frost(now);
                case LIGHTNING -> lightning(spots, now);
            }
        }, 0L, 4L);
    }

    private static void ring(Location at, double r, Particle particle, int points) {
        for (int i = 0; i < points; i++) {
            double a = i * 2 * Math.PI / points;
            at.getWorld().spawnParticle(particle, at.getX() + Math.cos(a) * r, at.getY() + 0.3, at.getZ() + Math.sin(a) * r, 1, 0, 0, 0, 0);
        }
    }

    private static void line(Location from, Location to) {
        Vector d = to.toVector().subtract(from.toVector()).setY(0);
        double len = d.length();
        if (len < 0.1) return;
        d.normalize();
        for (double s = 0; s < len; s += 1.5) from.getWorld().spawnParticle(Particle.FLAME, from.getX() + d.getX() * s, from.getY() + 0.3, from.getZ() + d.getZ() * s, 1, 0, 0, 0, 0);
    }

    private Location behind(Player t) {
        Vector back = t.getLocation().getDirection().setY(0);
        if (back.lengthSquared() < 0.01) back = new Vector(1, 0, 0);
        return t.getLocation().subtract(back.normalize().multiply(4));
    }

    private void slam(List<Player> targets) {
        World w = boss.getWorld();
        Location l = boss.getLocation();
        w.playSound(l, Sound.ENTITY_RAVAGER_ROAR, 4f, 0.6f);
        w.spawnParticle(Particle.EXPLOSION_EMITTER, l, 2, 2, 0, 2);
        for (Player p : targets) {
            if (p.getLocation().distanceSquared(l) > SLAM_R * SLAM_R) continue;
            Vector away = p.getLocation().toVector().subtract(l.toVector()).setY(0);
            if (away.lengthSquared() < 0.01) away = new Vector(1, 0, 0);
            p.setVelocity(away.normalize().multiply(1.4).setY(0.9));
            p.damage(6, boss);
        }
    }

    private void fireRain(List<Location> spots) {
        World w = boss.getWorld();
        w.playSound(boss.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 4f, 0.5f);
        for (Location s : spots)
            w.spawn(s.clone().add(rnd.nextGaussian(), 14, rnd.nextGaussian()), SmallFireball.class, f -> { f.setDirection(new Vector(0, -1, 0)); f.setShooter(boss); f.setIsIncendiary(false); });
    }

    private void summon() {
        World w = boss.getWorld();
        w.playSound(boss.getLocation(), Sound.ENTITY_EVOKER_PREPARE_SUMMON, 4f, 0.7f);
        for (int i = 0; i < 6 && adds.size() < 20; i++) {
            Location l = boss.getLocation().add(rnd.nextGaussian() * 6, 1, rnd.nextGaussian() * 6);
            adds.add(w.spawn(l, kind.minion(), m -> {
                m.setPersistent(false);
                m.setRemoveWhenFarAway(true);
                if (m instanceof MagmaCube mc) mc.setSize(3);
                if (m instanceof PiglinAbstract pa) pa.setImmuneToZombification(true);
            }));
        }
    }

    private void charge(Location to, Player target) {
        Vector dir = to.toVector().subtract(boss.getLocation().toVector()).setY(0);
        if (dir.lengthSquared() < 0.01) return;
        boss.setVelocity(dir.normalize().multiply(2.2).setY(0.3));
        if (target.isOnline() && target.getWorld() == boss.getWorld()) boss.setTarget(target);
        boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 4f, 1.2f);
    }

    private void arrowStorm(List<Player> targets) {
        World w = boss.getWorld();
        w.playSound(boss.getLocation(), Sound.ENTITY_SKELETON_SHOOT, 4f, 0.5f);
        Location from = boss.getEyeLocation();
        for (Player p : targets)
            for (int i = 0; i < 3; i++) {
                Vector d = p.getEyeLocation().toVector().subtract(from.toVector()).normalize().add(new Vector(rnd.nextGaussian() * 0.06, 0.05, rnd.nextGaussian() * 0.06));
                w.spawnArrow(from, d, 2.2f, 4f).setShooter(boss);
            }
    }

    private void blink(Location to, Player target) {
        World w = boss.getWorld();
        w.spawnParticle(Particle.PORTAL, boss.getLocation(), 60, 1, 2, 1, 0.5);
        w.playSound(boss.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 3f, 0.6f);
        if (to.distanceSquared(arena.center()) < Math.pow(SunderingMath.RADIUS - 6, 2)) boss.teleport(to);
        if (target.isOnline() && target.getWorld() == w) boss.setTarget(target);
    }

    private void pulse(List<Player> targets, PotionEffectType effect, Particle particle, Sound sound) {
        World w = boss.getWorld();
        w.playSound(boss.getLocation(), sound, 4f, 0.6f);
        w.spawnParticle(particle, boss.getLocation().add(0, 1, 0), 200, 8, 1, 8, 0.02);
        for (Player p : targets) if (p.getLocation().distanceSquared(boss.getLocation()) < PULSE_R * PULSE_R) p.addPotionEffect(new PotionEffect(effect, 80, 1));
    }

    private void frost(List<Player> targets) {
        World w = boss.getWorld();
        w.playSound(boss.getLocation(), Sound.BLOCK_GLASS_BREAK, 4f, 0.5f);
        w.spawnParticle(Particle.SNOWFLAKE, boss.getLocation().add(0, 1, 0), 250, 9, 1, 9, 0.05);
        for (Player p : targets) {
            if (p.getLocation().distanceSquared(boss.getLocation()) > NOVA_R * NOVA_R) continue;
            p.setFreezeTicks(Math.max(p.getFreezeTicks(), 200));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 2));
            p.damage(4, boss);
        }
    }

    private void lightning(List<Location> spots, List<Player> targets) {
        World w = boss.getWorld();
        for (Location s : spots) {
            w.strikeLightningEffect(s);
            for (Player p : targets) if (p.getLocation().distanceSquared(s) < 4) p.damage(5, boss);
        }
    }

    // ------------------------------------------------------------------ damage and the leaderboard

    /** Bigger crowds hit less hard per person, so the fight lasts about the same. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onScale(EntityDamageEvent e) {
        if (phase == Phase.FIGHT && e.getEntity() == boss) e.setDamage(e.getDamage() * multiplier);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (phase != Phase.FIGHT || e.getEntity() != boss) return;
        Player p = e.getDamager() instanceof Player pl ? pl : e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player pl2 ? pl2 : null;
        if (p == null) return;
        damage.merge(p.getUniqueId(), Math.min(e.getFinalDamage(), boss.getHealth()), Double::sum);   // overkill doesn't count
        names.put(p.getUniqueId(), p.getName());
    }

    /** The sidebar for someone in the arena during the event, or null to show the normal one. */
    public List<String> board(Player p) {
        if ((phase != Phase.WAKING && phase != Phase.FIGHT) || p.getWorld() != arena.world()) return null;
        Map<UUID, Double> shares = SunderingMath.shares(damage);
        List<String> lines = new ArrayList<>();
        lines.add(kind.color() + "<bold>" + kind.name());
        lines.add("<dark_gray>" + "-".repeat(18));
        int i = 0;
        for (Map.Entry<UUID, Double> e : shares.entrySet()) {
            if (i++ == 5) break;
            lines.add("<gold>" + i + ". <white>" + names.getOrDefault(e.getKey(), "?") + " <gray>" + percent(e.getValue()));
        }
        if (shares.isEmpty()) lines.add("<gray>No damage yet");
        lines.add(" ");
        lines.add("<gray>You: <white>" + percent(shares.getOrDefault(p.getUniqueId(), 0.0)));
        return lines;
    }

    private static String percent(double share) { return String.format(Locale.ROOT, "%.1f%%", share * 100); }

    /** The boss and its minions only ever go for players. */
    @EventHandler
    public void onTarget(EntityTargetEvent e) {
        if (active() && (e.getEntity() == boss || adds.contains(e.getEntity())) && e.getTarget() != null && !(e.getTarget() instanceof Player)) e.setCancelled(true);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        if (boss == null || e.getEntity() != boss) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        Location l = boss.getLocation();
        l.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, l, 6, 3, 1, 3);
        l.getWorld().playSound(l, Sound.ENTITY_WITHER_DEATH, 5f, 0.8f);
        Bukkit.broadcast(Ui.mm(kind.color() + "⚑ <bold>The " + kind.name() + " has fallen!</bold>"));
        payOut(1, true);
        end(null);
    }

    // ------------------------------------------------------------------ loot

    private void payOut(double fraction, boolean keys) {
        Map<UUID, Double> all = SunderingMath.shares(damage), paid = SunderingMath.eligible(all, minShare);
        StringBuilder top = new StringBuilder(kind.color() + "<bold>" + kind.name() + "</bold><newline><gray>Damage dealt");
        int i = 0;
        for (Map.Entry<UUID, Double> e : all.entrySet()) {
            if (++i > 10) break;
            String row = (i <= 3 ? "<gold>" : "<gray>") + i + ". <white>" + names.getOrDefault(e.getKey(), "?") + " <gray>" + percent(e.getValue());
            top.append("<newline>").append(row);
            if (i <= 5) Bukkit.broadcast(Ui.mm("  " + row));
        }
        finalBoard = Ui.hologram(arena.center().add(0, 7, 0), top.toString(), 2.5f);
        if (keys && !paid.isEmpty())
            for (String key : keyRolls) {
                UUID winner = SunderingMath.rollWinner(paid, rnd.nextDouble());
                give(winner, key);
                Bukkit.broadcast(Ui.mm("<gray>  " + names.getOrDefault(winner, "?") + " won a " + crates.keyName(key)));
            }
        for (Map.Entry<UUID, Double> e : paid.entrySet()) {
            OfflinePlayer o = Bukkit.getOfflinePlayer(e.getKey());
            long money = SunderingMath.payout(Math.round(moneyPool * fraction), e.getValue()), scrap = SunderingMath.payout(Math.round(scrapPool * fraction), e.getValue());
            plugin.economy().deposit(o, money);
            plugin.economy().addScrap(o, scrap);
            if (o instanceof Player p)
                p.sendMessage(Ui.mm(kind.color() + "⚑ <gray>Your share <white>" + percent(all.get(e.getKey())) + " <dark_gray>» <green>"
                        + NetEconomy.money(money) + " <#c6a15b>+" + scrap + " Scrap"));
        }
        for (UUID id : damage.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) net.blackforge.core.Hooks.stat(p, "boss", 1);
        }
    }

    private void give(UUID id, String key) {
        Player p = Bukkit.getPlayer(id);
        if (p != null) { crates.give(p, key, 1); return; }
        List<String> owed = new ArrayList<>(saved.getStringList("owed." + id));   // they left before the end: it waits for them
        owed.add(key);
        saved.set("owed." + id, owed);
        save();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        String path = "owed." + p.getUniqueId();
        List<String> owed = saved.getStringList(path);
        if (owed.isEmpty()) return;
        owed.forEach(key -> crates.give(p, key, 1));
        saved.set(path, null);
        save();
        p.sendMessage(Ui.mm("<#e5484d>⚑ <gray>You won these keys in The Sundering after you left."));
    }
}
