package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.components.UseCooldownComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.*;
import java.util.function.Predicate;

/**
 * EMP: a BlackForgeMC ability item in the spirit of Sombra's ultimate, all electric lime and yellow. Right-click and
 * you're thrown straight up while the charge gathers round you; at the top of the jump it goes off. A wave rolls out
 * {@code emp.radius} blocks (10 by default: a 20-block-wide circle) and, the moment it reaches them:
 *
 * <ul>
 *   <li>every other player's ability running inside it shuts down ({@link Hackable}): Rimelock cages shatter, Surges
 *   end, Ashen Chains snap with no pull, Gods-eye marks close, bonfires go out, Gaia clouds collapse, Last Rites break,
 *   another EMP still rising fizzles, and thrown potions in the air are fried;</li>
 *   <li>every other player it hits is HACKED: all their timed potion effects (good and bad) and absorption are
 *   stripped, fire put out;</li>
 *   <li>the caster is cleansed: every harmful effect, fire and freezing gone.</li>
 * </ul>
 *
 * The caster's own abilities are never touched. Then you float for a moment and drift down; no fall damage.
 * One use: right-clicking consumes it. Only where {@code allowedAt} says (the hub Arena for now). Wave maths lives
 * in {@link EmpWave}.
 */
public final class Emp implements Listener {
    public static final String NAME = "<gradient:#7CFF4F:#E8FF4A:#7CFF4F><bold>EMP</bold></gradient>";

    // palette: electric lime to volt yellow, with a deep green underneath and a pale flash
    static final Color VOLT = Color.fromRGB(0xE8FF4A), LIME = Color.fromRGB(0xB6FF3B), ACID = Color.fromRGB(0x7CFF4F),
            DEEP = Color.fromRGB(0x1F8F3A), PALE = Color.fromRGB(0xF4FFD0);
    private static final String HEX = "#B6FF3B";

    /** What the caster is cleansed of. Everything else (and anything infinite, e.g. set by the server) is left alone. */
    private static final Set<PotionEffectType> HARMFUL = Set.of(
            PotionEffectType.SLOWNESS, PotionEffectType.MINING_FATIGUE, PotionEffectType.NAUSEA, PotionEffectType.BLINDNESS,
            PotionEffectType.HUNGER, PotionEffectType.WEAKNESS, PotionEffectType.POISON, PotionEffectType.WITHER,
            PotionEffectType.LEVITATION, PotionEffectType.UNLUCK, PotionEffectType.DARKNESS, PotionEffectType.GLOWING,
            PotionEffectType.INFESTED, PotionEffectType.OOZING, PotionEffectType.WEAVING, PotionEffectType.WIND_CHARGED);

    private static final int HANG_TICKS = 30;        // Slow Falling after the blast: the float at the top
    private static final int TAG_TICKS = 40;         // how long the HACKED skull hangs over a hit player
    private static final int LINGER_TICKS = 10;      // static crackling over the disc after the wave

    private final AlloyCore plugin;
    private final Predicate<Location> allowedAt;
    private final boolean staffSkipCooldown;
    private final boolean enabled;
    private final double radius;
    private final int cooldownSeconds;
    private final NamespacedKey tagKey, cooldownGroup;
    private final List<Hackable> targets = new ArrayList<>();
    private final Map<UUID, BukkitTask> rising = new HashMap<>();
    private final Map<UUID, Long> readyAt = new HashMap<>(), noFallUntil = new HashMap<>();
    private final Map<UUID, TextDisplay> tags = new HashMap<>();
    private final Set<Display> live = new HashSet<>();
    private final Random rnd = new Random();

    public Emp(AlloyCore plugin, Predicate<Location> allowedAt, boolean staffSkipCooldown) {
        this.plugin = plugin;
        this.allowedAt = allowedAt;
        this.staffSkipCooldown = staffSkipCooldown;
        this.enabled = plugin.getConfig().getBoolean("emp.enabled", true);
        this.radius = Math.max(3, Math.min(20, plugin.getConfig().getDouble("emp.radius", EmpWave.DEFAULT_RADIUS)));
        this.cooldownSeconds = Math.max(0, plugin.getConfig().getInt("emp.cooldown-seconds", 45));
        this.tagKey = new NamespacedKey(plugin, "emp_item");
        this.cooldownGroup = new NamespacedKey("blackforge", "emp");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /** Abilities the EMP shuts down. HubModule hands it every one it runs. */
    public void hackable(Hackable... hs) { targets.addAll(Arrays.asList(hs)); }

    // ------------------------------------------------------------------ the item

    public ItemStack item() {
        ItemStack it = new ItemStack(Material.EMERALD);
        it.editMeta(m -> {
            m.displayName(Ui.mm(NAME));
            m.lore(List.of(
                    Ui.mm("<gray>Right-click to <white>launch up<gray> and fire an EMP"),
                    Ui.mm("<gray>Shuts down every ability within <white>" + fmt(radius) + " blocks"),
                    Ui.mm("<gray>Cages fall, surges end, chains snap"),
                    Ui.mm("<gray>Strips the buffs off everyone it hits"),
                    Ui.mm("<gray>and cleanses your debuffs"),
                    Ui.mm(""),
                    Ui.mm("<white>One use<gray> - consumed when you activate it"),
                    Ui.mm("<gray>Cooldown: <white>" + cooldownSeconds + "s"),
                    Ui.mm("<dark_gray>BlackForgeMC ability")));
            m.setMaxStackSize(1);
            m.setEnchantmentGlintOverride(true);
            UseCooldownComponent cd = m.getUseCooldown();
            cd.setCooldownSeconds(Math.max(1, cooldownSeconds));
            cd.setCooldownGroup(cooldownGroup);   // only the EMP greys out, not every emerald
            m.setUseCooldown(cd);
            m.getPersistentDataContainer().set(tagKey, PersistentDataType.BYTE, (byte) 1);
        });
        return it;
    }

    public boolean isEmp(ItemStack it) {
        return it != null && it.getType() == Material.EMERALD && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(tagKey, PersistentDataType.BYTE);
    }

    private static String fmt(double d) { return d == Math.rint(d) ? String.valueOf((int) d) : String.format(Locale.ROOT, "%.1f", d); }

    private boolean testing(Player p) { return staffSkipCooldown && p.hasPermission("alloy.admin"); }

    // ------------------------------------------------------------------ activation

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        if (!isEmp(e.getItem())) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        if (rising.containsKey(id)) return;
        if (!enabled) { p.sendActionBar(Ui.mm("<gray>The EMP isn't switched on yet.")); return; }
        if (p.getGameMode() == GameMode.SPECTATOR) return;
        if (!allowedAt.test(p.getLocation())) { p.sendActionBar(Ui.mm("<" + HEX + ">⚡ <gray>The EMP only works in the <white>Arena <gray>for now.")); return; }
        long left = readyAt.getOrDefault(id, 0L) - System.currentTimeMillis();
        if (left > 0 && !testing(p)) { p.sendActionBar(Ui.mm("<" + HEX + ">⚡ <gray>EMP is recharging... <white>" + (left / 1000 + 1) + "s")); return; }
        ItemStack held = p.getInventory().getItemInMainHand();
        if (!isEmp(held)) return;
        held.setAmount(held.getAmount() - 1);   // one use: taken now so it can't fire twice
        p.getInventory().setItemInMainHand(held.getAmount() > 0 ? held : null);
        if (testing(p)) readyAt.remove(id);
        else {
            readyAt.put(id, System.currentTimeMillis() + cooldownSeconds * 1000L);
            p.setCooldown(item(), cooldownSeconds * 20);
        }
        rise(p);
    }

    // ------------------------------------------------------------------ the rise

    /** Straight up (keeping a little of your run), the charge gathering round you, and it goes off at the top. */
    private void rise(Player p) {
        UUID id = p.getUniqueId();
        World w = p.getWorld();
        Vector v = p.getVelocity();
        p.setVelocity(new Vector(v.getX() * 0.3, EmpWave.LAUNCH_UP, v.getZ() * 0.3));
        p.setFallDistance(0f);
        noFallUntil.put(id, System.currentTimeMillis() + 10_000);   // cut short once they land (watchLanding)
        p.swingMainHand();
        launchFx(p);
        int[] n = {0};
        double[] lastY = {p.getLocation().getY()};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int k = ++n[0];
            if (!p.isOnline() || p.isDead() || p.getWorld() != w) { stopRise(id); return; }
            double y = p.getLocation().getY(), dy = y - lastY[0];   // measured: a player's server-side velocity is stale
            lastY[0] = y;
            p.setFallDistance(0f);
            chargeFx(p, k);
            if (EmpWave.detonateNow(k, dy)) { stopRise(id); detonate(p); }
        }, 1L, 1L);
        rising.put(id, task);
    }

    private void stopRise(UUID id) {
        BukkitTask t = rising.remove(id);
        if (t != null) t.cancel();
    }

    /** A pulse of lime at your feet and a crackle as you leave the ground. */
    private void launchFx(Player p) {
        World w = p.getWorld();
        Location feet = p.getLocation().add(0, 0.1, 0);
        for (int i = 0; i < 28; i++) {
            double a = 2 * Math.PI * i / 28;
            w.spawnParticle(Particle.DUST, feet.getX() + 0.9 * Math.cos(a), feet.getY(), feet.getZ() + 0.9 * Math.sin(a), 1, 0, 0, 0, 0, new Particle.DustOptions(LIME, 1.3f));
        }
        w.spawnParticle(Particle.CLOUD, feet, 10, 0.3, 0.05, 0.3, 0.04);
        w.spawnParticle(Particle.ELECTRIC_SPARK, feet.clone().add(0, 0.4, 0), 18, 0.4, 0.3, 0.4, 0.2);
        w.playSound(feet, Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.PLAYERS, 0.9f, 2.0f);
        w.playSound(feet, Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.7f, 1.6f);
    }

    /**
     * While rising: a double helix of lime and volt winds round you, tightening as it charges, and sparks are drawn
     * in from all around to your chest. The hum climbs in pitch.
     */
    private void chargeFx(Player p, int k) {
        World w = p.getWorld();
        Location base = p.getLocation();
        double prog = Math.min(1, k / (double) EmpWave.MAX_RISE_TICKS);
        double r = 1.1 - 0.7 * prog;
        for (int strand = 0; strand < 2; strand++) {
            for (int j = 0; j < 3; j++) {
                double step = k * 3 + j;
                double a = step * 0.45 + strand * Math.PI;
                double y = 0.1 + ((step % 18) / 18.0) * 2.0;
                Color from = strand == 0 ? LIME : VOLT, to = strand == 0 ? ACID : PALE;
                w.spawnParticle(Particle.DUST_COLOR_TRANSITION, base.getX() + r * Math.cos(a), base.getY() + y, base.getZ() + r * Math.sin(a),
                        1, 0, 0, 0, 0, new Particle.DustTransition(from, to, 1.1f));
            }
        }
        Location chest = base.clone().add(0, 1.1, 0);
        for (int i = 0; i < 4; i++) {   // drawn inward: spawn out on a shell, moving toward the chest
            Vector d = new Vector(rnd.nextGaussian(), rnd.nextGaussian() * 0.6, rnd.nextGaussian()).normalize();
            Location at = chest.clone().add(d.clone().multiply(2.4));
            w.spawnParticle(Particle.END_ROD, at, 0, -d.getX(), -d.getY(), -d.getZ(), 0.22);
        }
        if (k % 3 == 0) w.spawnParticle(Particle.ELECTRIC_SPARK, chest, 4, 0.25, 0.3, 0.25, 0.05);
        if (k == 1) w.playSound(base, Sound.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 1.0f, 1.6f);
        if (k == 5) w.playSound(base, Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR, SoundCategory.PLAYERS, 0.7f, 1.8f);
        if (k % 2 == 0) w.playSound(base, Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.7f, (float) Math.min(2.0, 0.8 + k * 0.09));
    }

    // ------------------------------------------------------------------ the blast

    private void detonate(Player p) {
        UUID id = p.getUniqueId();
        World w = p.getWorld();
        Location center = p.getLocation();
        Location chest = center.clone().add(0, 1.0, 0);
        Location ground = groundBelow(center);

        int cleansed = cleanseSelf(p);
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, HANG_TICKS, 0, false, false, false));   // the float at the top
        watchLanding(p);
        p.showTitle(Title.title(Ui.mm("<gradient:#E8FF4A:#7CFF4F:#E8FF4A><bold>EMP"), Component.empty(),
                Title.Times.times(Duration.ofMillis(50), Duration.ofMillis(300), Duration.ofMillis(300))));

        coreFlash(chest);
        shardBurst(chest);
        if (ground != null) groundRing(ground);
        centerBurst(chest);
        w.playSound(chest, Sound.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 1.6f, 1.5f);
        w.playSound(chest, Sound.ENTITY_WARDEN_SONIC_BOOM, SoundCategory.PLAYERS, 0.5f, 2.0f);
        w.playSound(chest, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, SoundCategory.PLAYERS, 1.0f, 1.6f);
        w.playSound(chest, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.PLAYERS, 0.6f, 1.8f);
        w.playSound(chest, Sound.BLOCK_CONDUIT_DEACTIVATE, SoundCategory.PLAYERS, 1.0f, 2.0f);
        p.playSound(chest, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, SoundCategory.PLAYERS, 1.0f, 1.4f);

        Set<UUID> reached = new HashSet<>();
        reached.add(id);
        int[] abilities = {0}, hacked = {0};
        int[] t = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int k = ++t[0];
            if (k <= EmpWave.EXPAND_TICKS) {
                double r = EmpWave.radius(k, radius);
                shellFx(w, chest, r, k);
                if (ground != null) groundFx(ground, r);
                // the wave shuts things down exactly as it reaches them
                Player caster = p.isOnline() ? p : null;
                if (caster != null) {
                    for (Hackable h : targets) abilities[0] += h.hack(center, r, caster);
                    abilities[0] += jamOtherEmps(center, r, id);
                    abilities[0] += fryPotions(w, center, r, caster);
                }
                for (Player q : w.getPlayers()) {
                    if (reached.contains(q.getUniqueId()) || q.getGameMode() == GameMode.SPECTATOR) continue;
                    if (!EmpWave.reaches(center, q.getLocation(), r) || !allowedAt.test(q.getLocation())) continue;
                    reached.add(q.getUniqueId());
                    hack(q, chest);
                    hacked[0]++;
                }
                return;
            }
            if (k <= EmpWave.EXPAND_TICKS + LINGER_TICKS) { if (ground != null) lingerFx(ground, k - EmpWave.EXPAND_TICKS); return; }
            task.cancel();
            if (p.isOnline()) p.sendActionBar(Ui.mm("<" + HEX + ">⚡ <gray>EMP: <white>" + abilities[0] + "<gray> abilit" + (abilities[0] == 1 ? "y" : "ies")
                    + " shut down · <white>" + hacked[0] + "<gray> hacked · <white>" + cleansed + "<gray> cleansed"));
        }, 1L, 1L);
    }

    /** The caster: every timed harmful effect, fire and freezing gone. Returns how many effects came off. */
    private static int cleanseSelf(Player p) {
        int n = 0;
        for (PotionEffect e : new ArrayList<>(p.getActivePotionEffects())) {
            if (e.isInfinite() || !HARMFUL.contains(e.getType())) continue;
            p.removePotionEffect(e.getType());
            n++;
        }
        if (p.getFireTicks() > 0) { p.setFireTicks(0); n++; }
        if (p.getFreezeTicks() > 0) p.setFreezeTicks(0);
        if (n > 0) {
            Location c = p.getLocation().add(0, 1, 0);
            p.getWorld().spawnParticle(Particle.DUST, c, 20, 0.3, 0.6, 0.3, 0, new Particle.DustOptions(PALE, 1.2f));
            p.playSound(c, Sound.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 0.8f, 2.0f);
        }
        return n;
    }

    /** Another player hit by the wave: stripped of every timed effect and absorption, fire out, and tagged HACKED. */
    private void hack(Player q, Location from) {
        for (PotionEffect e : new ArrayList<>(q.getActivePotionEffects()))
            if (!e.isInfinite()) q.removePotionEffect(e.getType());
        if (q.getAbsorptionAmount() > 0) q.setAbsorptionAmount(0);
        q.setFireTicks(0);
        arc(from, q.getLocation().add(0, 1.0, 0));
        tag(q);
        q.showTitle(Title.title(Component.empty(), Ui.mm("<" + HEX + ">⚡ <obfuscated>!!</obfuscated> <bold>HACKED</bold> <obfuscated>!!</obfuscated>"),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(700), Duration.ofMillis(300))));
        q.playSound(q.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, SoundCategory.PLAYERS, 1.0f, 1.8f);
        q.playSound(q.getLocation(), Sound.BLOCK_CONDUIT_DEACTIVATE, SoundCategory.PLAYERS, 1.0f, 1.5f);
        q.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, q.getLocation().add(0, 1, 0), 20, 0.35, 0.6, 0.35, 0.15);
    }

    /** Another EMP still rising inside the wave fizzles (its owner keeps falling safely). */
    private int jamOtherEmps(Location center, double r, UUID caster) {
        int n = 0;
        for (UUID id : new ArrayList<>(rising.keySet())) {
            Player q = Bukkit.getPlayer(id);
            if (q == null || id.equals(caster) || !EmpWave.reaches(center, q.getLocation(), r)) continue;
            stopRise(id);
            watchLanding(q);
            q.sendActionBar(Ui.mm("<" + HEX + ">⚡ <gray>Your EMP was <white>shut down <gray>by an EMP"));
            n++;
        }
        return n;
    }

    /** Splash potions other people threw, still in the air inside the wave, are fried before they land. */
    private int fryPotions(World w, Location center, double r, Player caster) {
        int n = 0;
        for (ThrownPotion tp : w.getEntitiesByClass(ThrownPotion.class)) {
            ProjectileSource src = tp.getShooter();
            if (src instanceof Player s && s.getUniqueId().equals(caster.getUniqueId())) continue;
            if (!EmpWave.reaches(center, tp.getLocation(), r)) continue;
            Location at = tp.getLocation();
            w.spawnParticle(Particle.ELECTRIC_SPARK, at, 12, 0.15, 0.15, 0.15, 0.2);
            w.spawnParticle(Particle.SMOKE, at, 6, 0.1, 0.1, 0.1, 0.02);
            w.playSound(at, Sound.BLOCK_GLASS_BREAK, SoundCategory.PLAYERS, 0.6f, 1.8f);
            tp.remove();
            n++;
        }
        return n;
    }

    /** No fall damage until they've landed from the EMP (and half a second after). */
    private void watchLanding(Player p) {
        UUID id = p.getUniqueId();
        noFallUntil.put(id, System.currentTimeMillis() + 10_000);
        int[] n = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int k = n[0]++;
            if (!p.isOnline() || p.isDead() || k > 200) { noFallUntil.remove(id); task.cancel(); return; }
            p.setFallDistance(0f);
            if (k > 2 && p.isOnGround()) {
                noFallUntil.put(id, System.currentTimeMillis() + 500);
                Location l = p.getLocation().add(0, 0.1, 0);
                p.getWorld().spawnParticle(Particle.DUST, l, 10, 0.4, 0.05, 0.4, 0, new Particle.DustOptions(ACID, 1.1f));
                task.cancel();
            }
        }, 1L, 1L);
    }

    private static Location groundBelow(Location from) {
        RayTraceResult hit = from.getWorld().rayTraceBlocks(from.clone().add(0, 0.2, 0), new Vector(0, -1, 0), EmpWave.VERTICAL, FluidCollisionMode.NEVER, true);
        if (hit == null) return null;
        Vector at = hit.getHitPosition();
        return new Location(from.getWorld(), from.getX(), at.getY(), from.getZ());
    }

    // ------------------------------------------------------------------ the look (display entities move smoothly on the client)

    /**
     * A transformation for a block display whose middle sits at (x, y, z): the block model's corner is at its origin,
     * so it's shifted back by half its rotated size. With the rotation held fixed, moving between two of these keeps
     * the block centred the whole way through the client's interpolation.
     */
    private static Transformation centred(double x, double y, double z, Quaternionf rot, float sx, float sy, float sz) {
        Vector3f half = new Vector3f(sx / 2, sy / 2, sz / 2).rotate(rot);
        return new Transformation(new Vector3f((float) x - half.x, (float) y - half.y, (float) z - half.z), new Quaternionf(rot),
                new Vector3f(sx, sy, sz), new Quaternionf());
    }

    private BlockDisplay block(Location at, BlockData data, Transformation start, Color glow) {
        BlockDisplay d = at.getWorld().spawn(at, BlockDisplay.class, e -> {
            e.setBlock(data);
            e.setPersistent(false);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setViewRange(2.0f);
            e.setTransformation(start);
            if (glow != null) { e.setGlowing(true); e.setGlowColorOverride(glow); }
        });
        live.add(d);
        return d;
    }

    /** {@code delay} ticks from now, glide to {@code to} over {@code ticks} (starting the same tick as the spawn can be skipped by the client). */
    private void animate(Display d, long delay, int ticks, Transformation to) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!d.isValid()) return;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(ticks);
            d.setTransformation(to);
        }, delay);
    }

    private void removeLater(Display d, long delay) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> { d.remove(); live.remove(d); }, delay);
    }

    /** Two crossed glass cubes, lime and yellow, swell out of the caster in 3 ticks and collapse to nothing. */
    private void coreFlash(Location chest) {
        Quaternionf ra = new Quaternionf().rotateAxis((float) Math.toRadians(45), new Vector3f(1, 1, 0).normalize());
        Quaternionf rb = new Quaternionf().rotateAxis((float) Math.toRadians(45), new Vector3f(0, 1, 1).normalize());
        Object[][] cubes = {{Material.LIME_STAINED_GLASS, ra, 2.2f, LIME}, {Material.YELLOW_STAINED_GLASS, rb, 1.7f, VOLT}};
        for (Object[] c : cubes) {
            Quaternionf rot = (Quaternionf) c[1];
            float big = (float) c[2];
            BlockDisplay d = block(chest, ((Material) c[0]).createBlockData(), centred(0, 0, 0, rot, 0.1f, 0.1f, 0.1f), (Color) c[3]);
            animate(d, 1, 3, centred(0, 0, 0, rot, big, big, big));
            animate(d, 4, 6, centred(0, 0, 0, rot, 0.01f, 0.01f, 0.01f));
            removeLater(d, 11);
        }
    }

    /**
     * A sphere of thin glass plates, each turned to face outward, flies out from the caster: the bubble of the EMP.
     * It slows as it grows (two glides), then the plates shrink away.
     */
    private void shardBurst(Location chest) {
        double[][] dirs = EmpWave.sphere(36);
        double reach = Math.min(radius * 0.75, 7.5);
        BlockData lime = Material.LIME_STAINED_GLASS.createBlockData(), yellow = Material.YELLOW_STAINED_GLASS.createBlockData();
        for (int i = 0; i < dirs.length; i++) {
            double[] d = dirs[i];
            Quaternionf face = new Quaternionf().rotationTo(0, 0, 1, (float) d[0], (float) d[1], (float) d[2]);
            boolean even = i % 2 == 0;
            BlockDisplay s = block(chest, even ? lime : yellow, centred(d[0] * 0.3, d[1] * 0.3, d[2] * 0.3, face, 0.05f, 0.05f, 0.02f), even ? LIME : VOLT);
            animate(s, 1, 4, centred(d[0] * reach * 0.7, d[1] * reach * 0.7, d[2] * reach * 0.7, face, 0.5f, 0.5f, 0.06f));
            animate(s, 5, 4, centred(d[0] * reach, d[1] * reach, d[2] * reach, face, 0.4f, 0.4f, 0.05f));
            animate(s, 9, 5, centred(d[0] * (reach + 1.2), d[1] * (reach + 1.2), d[2] * (reach + 1.2), face, 0.01f, 0.01f, 0.01f));
            removeLater(s, 15);
        }
    }

    /**
     * The shockwave on the ground: a ring of glowing froglight segments (green and yellow) racing out to the full
     * radius, following {@link EmpWave#radius}'s ease-out in 2-tick glides, then flattening away.
     */
    private void groundRing(Location ground) {
        int segs = 40;
        BlockData green = Material.VERDANT_FROGLIGHT.createBlockData(), yellow = Material.OCHRE_FROGLIGHT.createBlockData();
        for (int i = 0; i < segs; i++) {
            double a = 2 * Math.PI * i / segs;
            Quaternionf rot = new Quaternionf().rotationY((float) -(a + Math.PI / 2));   // the block's x-axis along the ring
            BlockDisplay d = block(ground, i % 2 == 0 ? green : yellow, segment(a, 0.6, rot, 0.1f, 0.06f), null);
            for (int k = 0; k < EmpWave.EXPAND_TICKS; k += 2)
                animate(d, k + 1, 2, segment(a, EmpWave.radius(k + 2, radius), rot, 0.3f, 0.07f));
            animate(d, EmpWave.EXPAND_TICKS + 1, 6, segment(a, radius * 1.04, rot, 0.0f, 0.0f));
            removeLater(d, EmpWave.EXPAND_TICKS + 8);
        }
    }

    /** One ring segment at radius {@code r}: as long as its share of the circle, {@code width} across, {@code height} tall. */
    private static Transformation segment(double a, double r, Quaternionf rot, float width, float height) {
        float len = (float) (2 * Math.PI * r / 40 * 0.85);
        return centred(r * Math.cos(a), height / 2 + 0.02, r * Math.sin(a), rot, Math.max(0.001f, len), Math.max(0.001f, height), Math.max(0.001f, width));
    }

    /** The instant of the blast: a pale flash, a spray of sparks and light streaks. */
    private void centerBurst(Location chest) {
        World w = chest.getWorld();
        w.spawnParticle(Particle.DUST, chest, 30, 0.5, 0.5, 0.5, 0, new Particle.DustOptions(PALE, 2.4f));
        w.spawnParticle(Particle.DUST, chest, 20, 0.7, 0.7, 0.7, 0, new Particle.DustOptions(VOLT, 2.0f));
        w.spawnParticle(Particle.END_ROD, chest, 40, 0.2, 0.2, 0.2, 0.5);
        w.spawnParticle(Particle.ELECTRIC_SPARK, chest, 60, 0.6, 0.6, 0.6, 0.6);
        w.spawnParticle(Particle.WAX_OFF, chest, 20, 0.8, 0.8, 0.8, 1.0);
    }

    /** The wave's front as a thin shell of lime-to-volt dust, thinning as it grows. */
    private void shellFx(World w, Location c, double r, int k) {
        int pts = (int) (30 + r * 14);
        float size = (float) Math.max(0.7, 1.6 - k * 0.1);
        Particle.DustTransition dust = new Particle.DustTransition(k < EmpWave.EXPAND_TICKS / 2 ? VOLT : LIME, ACID, size);
        double off = rnd.nextDouble() * Math.PI;
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < pts; i++) {   // Fibonacci shell, turned a little each tick so it shimmers
            double y = 1 - 2.0 * (i + 0.5) / pts, rr = Math.sqrt(1 - y * y), a = golden * i + off;
            w.spawnParticle(Particle.DUST_COLOR_TRANSITION, c.getX() + r * rr * Math.cos(a), c.getY() + r * y, c.getZ() + r * rr * Math.sin(a), 1, 0, 0, 0, 0, dust);
        }
    }

    /** Dust and sparks along the ground ring, filling between the segments. */
    private void groundFx(Location g, double r) {
        World w = g.getWorld();
        int pts = (int) (24 + r * 5);
        Particle.DustOptions dust = new Particle.DustOptions(LIME, 1.2f);
        for (int i = 0; i < pts; i++) {
            double a = 2 * Math.PI * i / pts;
            double x = g.getX() + r * Math.cos(a), z = g.getZ() + r * Math.sin(a);
            w.spawnParticle(Particle.DUST, x, g.getY() + 0.15, z, 1, 0, 0, 0, 0, dust);
            if (i % 6 == 0) w.spawnParticle(Particle.ELECTRIC_SPARK, x, g.getY() + 0.2, z, 1, 0.05, 0.1, 0.05, 0.05);
        }
    }

    /** Static crackling over the whole disc for half a second after the wave, fading out. */
    private void lingerFx(Location g, int k) {
        World w = g.getWorld();
        int n = Math.max(2, 14 - k);
        for (int i = 0; i < n; i++) {
            double a = rnd.nextDouble() * 2 * Math.PI, r = Math.sqrt(rnd.nextDouble()) * radius;
            Location at = g.clone().add(r * Math.cos(a), 0.2 + rnd.nextDouble() * 0.6, r * Math.sin(a));
            w.spawnParticle(Particle.ELECTRIC_SPARK, at, 1, 0.05, 0.05, 0.05, 0.02);
            if (i % 2 == 0) w.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, new Particle.DustOptions(rnd.nextBoolean() ? ACID : DEEP, 0.9f));
        }
    }

    /** A jagged bolt from the blast to a player it hacks. */
    private void arc(Location from, Location to) {
        World w = from.getWorld();
        Vector d = to.toVector().subtract(from.toVector());
        double len = d.length();
        if (len < 0.5) return;
        int steps = (int) Math.max(6, len * 3);
        Vector side = d.clone().crossProduct(new Vector(0, 1, 0));
        if (side.lengthSquared() < 1e-6) side = new Vector(1, 0, 0);
        side.normalize();
        Vector up = side.clone().crossProduct(d).normalize();
        Particle.DustOptions core = new Particle.DustOptions(VOLT, 0.9f);
        for (int i = 0; i <= steps; i++) {
            double f = i / (double) steps;
            double jitter = Math.sin(f * Math.PI) * 0.45;   // pinned at both ends
            Vector off = side.clone().multiply((rnd.nextDouble() - 0.5) * 2 * jitter).add(up.clone().multiply((rnd.nextDouble() - 0.5) * 2 * jitter));
            Location at = from.clone().add(d.clone().multiply(f)).add(off);
            w.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, core);
            if (i % 3 == 0) w.spawnParticle(Particle.ELECTRIC_SPARK, at, 1, 0, 0, 0, 0.02);
        }
    }

    /**
     * The HACKED mark: a lime skull riding above the player's head for 2 s. It pops in, flickers into static now
     * and then, and shrinks away. It's a passenger, so it follows them perfectly.
     */
    private void tag(Player q) {
        TextDisplay old = tags.remove(q.getUniqueId());
        if (old != null) { old.remove(); live.remove(old); }
        Component skull = Ui.mm("<" + HEX + "><bold>☠</bold>"), glitch = Ui.mm("<#E8FF4A><obfuscated>☠</obfuscated>");
        TextDisplay d = q.getWorld().spawn(q.getLocation().add(0, 2.2, 0), TextDisplay.class, e -> {
            e.text(skull);
            e.setPersistent(false);
            e.setBillboard(Display.Billboard.CENTER);
            e.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            e.setShadowed(true);
            e.setBrightness(new Display.Brightness(15, 15));
            e.setTransformation(new Transformation(new Vector3f(0, 0.35f, 0), new Quaternionf(), new Vector3f(0.01f), new Quaternionf()));
        });
        live.add(d);
        tags.put(q.getUniqueId(), d);
        q.addPassenger(d);
        animate(d, 1, 3, new Transformation(new Vector3f(0, 0.4f, 0), new Quaternionf(), new Vector3f(1.6f), new Quaternionf()));
        int[] n = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int k = ++n[0];
            if (!d.isValid() || !q.isOnline() || q.isDead() || k > TAG_TICKS + 5) {
                task.cancel();
                d.remove();
                live.remove(d);
                tags.remove(q.getUniqueId(), d);
                return;
            }
            if (k < TAG_TICKS) d.text(rnd.nextInt(6) == 0 ? glitch : skull);
            if (k == TAG_TICKS) {
                d.text(skull);
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(4);
                d.setTransformation(new Transformation(new Vector3f(0, 0.9f, 0), new Quaternionf(), new Vector3f(0.01f), new Quaternionf()));
            }
        }, 1L, 1L);
    }

    // ------------------------------------------------------------------ cleanup

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFall(EntityDamageEvent e) {
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL && e.getEntity() instanceof Player p
                && noFallUntil.getOrDefault(p.getUniqueId(), 0L) > System.currentTimeMillis()) e.setCancelled(true);
    }

    private void clear(Player p) {
        stopRise(p.getUniqueId());
        TextDisplay t = tags.remove(p.getUniqueId());
        if (t != null) { t.remove(); live.remove(t); }
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) { clear(e.getPlayer()); noFallUntil.remove(e.getPlayer().getUniqueId()); }
    /** Stop {@code p}'s EMP rise and take its tag down, as a death would. For game modes with fake deaths (the Arcade). */
    public void endFor(Player p) { clear(p); }

    @EventHandler public void onDeath(PlayerDeathEvent e) { clear(e.getPlayer()); }
    @EventHandler public void onWorld(PlayerChangedWorldEvent e) { clear(e.getPlayer()); }

    /** Plugin disable: stop every rise and take every display down. */
    public void shutdown() {
        rising.values().forEach(BukkitTask::cancel);
        rising.clear();
        live.forEach(d -> { if (d.isValid()) d.remove(); });
        live.clear();
        tags.clear();
    }
}
