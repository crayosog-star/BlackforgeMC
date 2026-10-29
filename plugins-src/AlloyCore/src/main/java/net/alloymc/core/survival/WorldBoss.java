package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.NetEconomy;
import net.alloymc.core.Ui;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.*;

/**
 * World bosses: each time, one of five bosses is picked at random and rises in the KOTH arena on a schedule
 * (config boss.day / boss.hour, default Saturday 20:00) or with /alloy boss start [id]. Everyone fights it together
 * while PvP stays on. Each boss has its own mob, colours and four attacks. Loot is split by damage dealt: the top three
 * get keys, Ingots and Scrap, and everyone who did at least 2% gets a share. A boss retreats after 20 minutes.
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

    private static final int TIME_LIMIT = 20 * 60;

    private final AlloyCore plugin;
    private final World world;
    private final Crates crates;
    private final Koth koth;
    private final Random rnd = new Random();
    private final Map<UUID, Double> damage = new HashMap<>();
    private final List<Entity> adds = new ArrayList<>();
    private Boss kind;
    private Mob boss;
    private BossBar bar;
    private int seconds, nextAbility;
    private String lastScheduled = "";

    WorldBoss(AlloyCore plugin, World world, Crates crates, Koth koth) {
        this.plugin = plugin;
        this.world = world;
        this.crates = crates;
        this.koth = koth;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 20L);
    }

    private Location center() { return new Location(world, SurvivalLayout.KOTH_X + 0.5, SurvivalLayout.HILL_TOP + 1, SurvivalLayout.KOTH_Z + 0.5); }
    public boolean active() { return boss != null && boss.isValid(); }
    private String title() { return kind.color() + "<bold>" + kind.name(); }

    // ------------------------------------------------------------------ schedule

    private void tick() {
        LocalDateTime now = LocalDateTime.now();
        DayOfWeek day = DayOfWeek.valueOf(plugin.getConfig().getString("boss.day", "SATURDAY").toUpperCase());
        int hour = plugin.getConfig().getInt("boss.hour", 20);
        String slot = now.toLocalDate() + "@" + hour;
        if (now.getDayOfWeek() == day && !active()) {
            int mins = hour * 60 - (now.getHour() * 60 + now.getMinute());
            if ((mins == 10 || mins == 2) && now.getSecond() == 0)
                Bukkit.broadcast(Ui.mm("<#ff4d00>🔥 <bold>WORLD BOSS</bold> <gray>- a boss rises in the KOTH arena in <white>" + mins
                        + " minutes<gray>! Which one? Nobody knows. <click:run_command:'/warp koth'><yellow><u>/warp koth</u></click>"));
            if (mins <= 0 && mins > -30 && !slot.equals(lastScheduled) && !koth.active()) { lastScheduled = slot; start(null); }
        }
        if (active()) fight();
        else if (boss != null) { boss = null; hideBar(); }
    }

    /** Start a boss: the given id, or a random one. */
    public String start(String id) {
        if (active()) return "A boss is already up.";
        kind = BOSSES.stream().filter(b -> b.id().equalsIgnoreCase(String.valueOf(id))).findFirst().orElse(BOSSES.get(rnd.nextInt(BOSSES.size())));
        damage.clear();
        seconds = 0;
        nextAbility = 8;
        Location at = center();
        world.strikeLightningEffect(at);
        world.spawnParticle(Particle.EXPLOSION_EMITTER, at, 3, 2, 1, 2);
        boss = world.spawn(at, kind.type(), m -> {
            m.customName(Ui.mm(title()));
            m.setCustomNameVisible(true);
            m.setPersistent(false);
            m.setRemoveWhenFarAway(false);
            set(m, Attribute.MAX_HEALTH, kind.health());
            set(m, Attribute.SCALE, kind.scale());
            set(m, Attribute.ATTACK_DAMAGE, kind.damage());
            set(m, Attribute.ARMOR, 12);
            set(m, Attribute.KNOCKBACK_RESISTANCE, 1);
            set(m, Attribute.FOLLOW_RANGE, 48);
            m.setHealth(kind.health());
            if (kind.weapon() != null && m.getEquipment() != null) { m.getEquipment().setItemInMainHand(new ItemStack(kind.weapon())); m.getEquipment().setItemInMainHandDropChance(0); }
            if (m instanceof PiglinAbstract pa) pa.setImmuneToZombification(true);
            if (m instanceof IronGolem g) g.setPlayerCreated(false);
            if (kind.id().equals("ravager")) m.setVisualFire(true);
        });
        bar = BossBar.bossBar(Ui.mm(title()), 1f, kind.barColor(), BossBar.Overlay.NOTCHED_20);
        world.playSound(at, Sound.ENTITY_ENDER_DRAGON_GROWL, 6f, 0.5f);
        Bukkit.broadcast(Ui.mm(kind.color() + "🔥 <bold>THE " + kind.name().toUpperCase() + " HAS RISEN!</bold> <gray>Fight it in the KOTH arena - loot goes to whoever deals the most damage. "
                + "<click:run_command:'/warp koth'><yellow><u>/warp koth</u></click>"));
        return "Started the " + kind.name() + ".";
    }

    private static void set(LivingEntity e, Attribute a, double v) { var i = e.getAttribute(a); if (i != null) i.setBaseValue(v); }

    private void hideBar() { if (bar != null) for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar); }

    public void stop(String why) {
        if (boss != null) boss.remove();
        boss = null;
        adds.forEach(Entity::remove);
        adds.clear();
        hideBar();
        if (why != null) Bukkit.broadcast(Ui.mm("<#ff4d00>🔥 <gray>" + why));
    }

    // ------------------------------------------------------------------ the fight

    private List<Player> nearby(double r) {
        List<Player> out = new ArrayList<>();
        for (Player p : world.getPlayers()) if (p.getGameMode() == GameMode.SURVIVAL && p.getLocation().distanceSquared(boss.getLocation()) < r * r) out.add(p);
        return out;
    }

    private void fight() {
        seconds++;
        Location c = center();
        if (boss.getLocation().distanceSquared(c) > Math.pow(SurvivalLayout.KOTH_R, 2)) boss.teleport(c);   // stays in the arena
        double max = kind.health();
        bar.progress((float) Math.max(0, Math.min(1, boss.getHealth() / max)));
        bar.name(Ui.mm(title() + "</bold> <gray>" + (int) boss.getHealth() + " / " + (int) max + " <dark_gray>| <gray>" + (TIME_LIMIT - seconds) / 60 + "m left"));
        for (Player p : world.getPlayers()) {
            if (p.getLocation().distanceSquared(boss.getLocation()) < 80 * 80) p.showBossBar(bar); else p.hideBossBar(bar);
        }
        world.spawnParticle(kind.aura(), boss.getLocation().add(0, 2, 0), 14, 1.5, 1.5, 1.5, 0.02);
        adds.removeIf(e -> !e.isValid());
        List<Player> targets = nearby(30);
        if (boss.getTarget() == null && !targets.isEmpty()) boss.setTarget(targets.get(rnd.nextInt(targets.size())));   // golems don't hunt players on their own
        if (seconds >= TIME_LIMIT) { stop("The " + kind.name() + " retreats. It'll be back next week!"); return; }
        if (--nextAbility > 0 || targets.isEmpty()) return;
        nextAbility = 6 + rnd.nextInt(5);
        switch (kind.abilities().get(rnd.nextInt(kind.abilities().size()))) {
            case SLAM -> slam(targets);
            case FIRE_RAIN -> fireRain(targets);
            case SUMMON -> summon();
            case CHARGE -> charge(targets);
            case ARROW_STORM -> arrowStorm(targets);
            case BLINK -> blink(targets);
            case WITHER_PULSE -> pulse(targets, PotionEffectType.WITHER, Particle.SMOKE, Sound.ENTITY_WITHER_SHOOT);
            case FROST_NOVA -> frost(targets);
            case LIGHTNING -> lightning(targets);
        }
    }

    private void slam(List<Player> targets) {
        Location l = boss.getLocation();
        world.playSound(l, Sound.ENTITY_RAVAGER_ROAR, 4f, 0.6f);
        world.spawnParticle(Particle.EXPLOSION_EMITTER, l, 2, 1, 0, 1);
        for (Player p : targets) {
            if (p.getLocation().distanceSquared(l) > 9 * 9) continue;
            Vector away = p.getLocation().toVector().subtract(l.toVector()).setY(0);
            if (away.lengthSquared() < 0.01) away = new Vector(1, 0, 0);
            p.setVelocity(away.normalize().multiply(1.4).setY(0.9));
            p.damage(6, boss);
        }
    }

    private void fireRain(List<Player> targets) {
        world.playSound(boss.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 4f, 0.5f);
        for (Player p : targets)
            world.spawn(p.getLocation().add(rnd.nextGaussian(), 14, rnd.nextGaussian()), SmallFireball.class, f -> { f.setDirection(new Vector(0, -1, 0)); f.setShooter(boss); f.setIsIncendiary(false); });
    }

    private void summon() {
        world.playSound(boss.getLocation(), Sound.ENTITY_EVOKER_PREPARE_SUMMON, 4f, 0.7f);
        for (int i = 0; i < 4 && adds.size() < 10; i++) {
            Location l = boss.getLocation().add(rnd.nextGaussian() * 4, 1, rnd.nextGaussian() * 4);
            adds.add(world.spawn(l, kind.minion(), m -> {
                m.setPersistent(false);
                m.setRemoveWhenFarAway(true);
                if (m instanceof MagmaCube mc) mc.setSize(3);
                if (m instanceof PiglinAbstract pa) pa.setImmuneToZombification(true);
            }));
        }
    }

    private void charge(List<Player> targets) {
        Player far = targets.stream().max(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(boss.getLocation()))).orElse(null);
        if (far == null) return;
        Vector dir = far.getLocation().toVector().subtract(boss.getLocation().toVector()).setY(0);
        if (dir.lengthSquared() < 0.01) return;
        boss.setVelocity(dir.normalize().multiply(2.2).setY(0.3));
        boss.setTarget(far);
        world.playSound(boss.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 4f, 1.2f);
    }

    private void arrowStorm(List<Player> targets) {
        world.playSound(boss.getLocation(), Sound.ENTITY_SKELETON_SHOOT, 4f, 0.5f);
        Location from = boss.getEyeLocation();
        for (Player p : targets)
            for (int i = 0; i < 3; i++) {
                Vector d = p.getEyeLocation().toVector().subtract(from.toVector()).normalize().add(new Vector(rnd.nextGaussian() * 0.06, 0.05, rnd.nextGaussian() * 0.06));
                world.spawnArrow(from, d, 2.2f, 4f).setShooter(boss);
            }
    }

    private void blink(List<Player> targets) {
        Player t = targets.get(rnd.nextInt(targets.size()));
        Location behind = t.getLocation().subtract(t.getLocation().getDirection().setY(0).normalize().multiply(2.5));
        world.spawnParticle(Particle.PORTAL, boss.getLocation(), 60, 1, 2, 1, 0.5);
        world.playSound(boss.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 3f, 0.6f);
        if (behind.distanceSquared(center()) < Math.pow(SurvivalLayout.KOTH_R, 2)) boss.teleport(behind.setDirection(t.getLocation().toVector().subtract(behind.toVector())));
        boss.setTarget(t);
    }

    private void pulse(List<Player> targets, PotionEffectType effect, Particle particle, Sound sound) {
        world.playSound(boss.getLocation(), sound, 4f, 0.6f);
        world.spawnParticle(particle, boss.getLocation().add(0, 1, 0), 200, 6, 1, 6, 0.02);
        for (Player p : targets) if (p.getLocation().distanceSquared(boss.getLocation()) < 10 * 10) p.addPotionEffect(new PotionEffect(effect, 80, 1));
    }

    private void frost(List<Player> targets) {
        world.playSound(boss.getLocation(), Sound.BLOCK_GLASS_BREAK, 4f, 0.5f);
        world.spawnParticle(Particle.SNOWFLAKE, boss.getLocation().add(0, 1, 0), 250, 7, 1, 7, 0.05);
        for (Player p : targets) {
            if (p.getLocation().distanceSquared(boss.getLocation()) > 12 * 12) continue;
            p.setFreezeTicks(Math.max(p.getFreezeTicks(), 200));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 2));
            p.damage(4, boss);
        }
    }

    private void lightning(List<Player> targets) {
        for (Player p : targets) {
            if (rnd.nextInt(3) == 0) continue;
            Location l = p.getLocation().add(rnd.nextGaussian() * 1.5, 0, rnd.nextGaussian() * 1.5);
            world.strikeLightningEffect(l);
            if (p.getLocation().distanceSquared(l) < 4) p.damage(5, boss);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!active() || e.getEntity() != boss) return;
        Player p = e.getDamager() instanceof Player pl ? pl : e.getDamager() instanceof Projectile pr && pr.getShooter() instanceof Player pl2 ? pl2 : null;
        if (p != null) damage.merge(p.getUniqueId(), e.getFinalDamage(), Double::sum);
    }

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
        world.spawnParticle(Particle.EXPLOSION_EMITTER, l, 6, 2, 1, 2);
        world.playSound(l, Sound.ENTITY_WITHER_DEATH, 5f, 0.8f);
        boss = null;
        adds.forEach(Entity::remove);
        adds.clear();
        hideBar();
        payOut();
    }

    private void payOut() {
        double total = damage.values().stream().mapToDouble(Double::doubleValue).sum();
        List<Map.Entry<UUID, Double>> rank = new ArrayList<>(damage.entrySet());
        rank.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        Bukkit.broadcast(Ui.mm(kind.color() + "🔥 <bold>The " + kind.name() + " has fallen!</bold> <gray>Top damage:"));
        String[] keys = {"netherite", "gold", "gold"};
        int[] money = {5000, 3000, 2000}, scrap = {300, 200, 150};
        for (int i = 0; i < rank.size(); i++) {
            Player p = Bukkit.getPlayer(rank.get(i).getKey());
            double share = total <= 0 ? 0 : rank.get(i).getValue() / total;
            if (p == null) continue;
            if (i < 3) {
                crates.give(p, keys[i], 1);
                plugin.economy().deposit(p, money[i]);
                plugin.economy().addScrap(p, scrap[i]);
                Bukkit.broadcast(Ui.mm("<gold>  " + (i + 1) + ". <white>" + p.getName() + " <gray>" + Math.round(share * 100) + "% <dark_gray>» <green>"
                        + NetEconomy.money(money[i]) + " <#c6a15b>+" + scrap[i] + " Scrap <gray>+ a " + crates.keyName(keys[i])));
            } else if (share >= 0.02) {
                crates.give(p, "iron", 1);
                plugin.economy().deposit(p, 500);
                plugin.economy().addScrap(p, 50);
                p.sendMessage(Ui.mm(kind.color() + "🔥 <gray>Your share: <green>$500 <#c6a15b>+50 Scrap <gray>+ a " + crates.keyName("iron")));
            }
        }
    }
}
