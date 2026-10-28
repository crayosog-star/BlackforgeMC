package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.inventory.meta.components.UseCooldownComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.*;
import java.util.function.Predicate;

/**
 * Surge: right-click the Surge item and lightning strikes you (harmless), your screen edges flush crimson, and for up
 * to 6 s you move like a ghost (+65% speed, higher jumps, walk up 1-block steps, a little hang time). The price is
 * a rising drain that lowers health directly (no hurt animation, so movement stays smooth) and never below half your
 * max health. One use: right-clicking consumes the item and plays a short activation animation (the rose lifts out
 * of your hand, spins, swells and bursts into the lightning). Drain maths lives in {@link SurgeCurve}.
 *
 * Every change is temporary and removed on end, death, quit, world change and shutdown (and checked again on join).
 */
public final class Surge implements Listener {
    public static final String NAME = "<gradient:#B3122E:#FF2E88><bold>Surge</bold></gradient>";
    public static final int COOLDOWN_SECONDS = 30;

    // palette (see the plan's visual guide)
    static final Color CRIMSON = Color.fromRGB(0xB3122E), EMBER = Color.fromRGB(0xE8364F), MAGENTA = Color.fromRGB(0xFF2E88),
            ASH = Color.fromRGB(0x3B3438), WITHER_BLACK = Color.fromRGB(0x120A0E), PALE = Color.fromRGB(0xEADFD6);
    static final Color BLOOD = Color.fromRGB(0x5C0011), DEEP_RED = Color.fromRGB(0x8A0A1E);   // activation burst

    private record Boost(Attribute attribute, NamespacedKey key, double amount, AttributeModifier.Operation op) {}

    private final class Active {
        final long startedAt = System.currentTimeMillis();
        BukkitTask task;
        int ticks;
        double drainedTo;          // seconds of drain already charged
        boolean wasOnGround = true;
        Location last;
        // the player's own sky before the surge, put back exactly afterwards (Factions sets its own night sky)
        long timeOffset;
        boolean timeRelative;
        WeatherType weather;
        WorldBorder border;        // the personal border behind the red screen edges
    }

    private final AlloyCore plugin;
    private final boolean enabled, customModel, screenDarkness;
    private final Predicate<Location> allowedAt;   // where it can be used (supplied by each server)
    private final boolean staffSkipCooldown;       // the hub Arena: staff can test it again and again
    public static final double LAUNCH_SPEED = 1.8; // the one-off dash in the look direction at the strike
    private final NamespacedKey tagKey, modelKey;
    private final List<Boost> boosts;
    private final Map<UUID, Active> active = new HashMap<>();
    private final Map<UUID, ItemDisplay> channeling = new HashMap<>();   // mid activation animation
    private final Map<UUID, Long> readyAt = new HashMap<>(), noFallUntil = new HashMap<>();
    private final Random rnd = new Random();

    public Surge(AlloyCore plugin, Predicate<Location> allowedAt) { this(plugin, allowedAt, false); }

    public Surge(AlloyCore plugin, Predicate<Location> allowedAt, boolean staffSkipCooldown) {
        this.plugin = plugin;
        this.allowedAt = allowedAt;
        this.staffSkipCooldown = staffSkipCooldown;
        this.enabled = plugin.getConfig().getBoolean("surge.enabled", true);
        this.customModel = plugin.getConfig().getBoolean("surge.custom-model", false);   // only once the resource pack is live
        this.screenDarkness = plugin.getConfig().getBoolean("surge.screen-darkness", true);
        this.tagKey = new NamespacedKey(plugin, "surge_item");
        this.modelKey = new NamespacedKey("blackforge", "surge");
        this.boosts = List.of(
                new Boost(Attribute.MOVEMENT_SPEED, new NamespacedKey(plugin, "surge_speed"), 0.65, AttributeModifier.Operation.MULTIPLY_SCALAR_1),
                new Boost(Attribute.JUMP_STRENGTH, new NamespacedKey(plugin, "surge_jump"), 0.20, AttributeModifier.Operation.ADD_NUMBER),
                new Boost(Attribute.STEP_HEIGHT, new NamespacedKey(plugin, "surge_step"), 0.5, AttributeModifier.Operation.ADD_NUMBER),
                new Boost(Attribute.GRAVITY, new NamespacedKey(plugin, "surge_gravity"), -0.015, AttributeModifier.Operation.ADD_NUMBER),
                new Boost(Attribute.SAFE_FALL_DISTANCE, new NamespacedKey(plugin, "surge_safefall"), 9.0, AttributeModifier.Operation.ADD_NUMBER));
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getOnlinePlayers().forEach(this::clearLeftovers));
    }

    // ------------------------------------------------------------------ the item

    public ItemStack item() {
        ItemStack it = new ItemStack(Material.WITHER_ROSE);
        it.editMeta(m -> {
            m.displayName(Ui.mm(NAME));
            m.lore(List.of(
                    Ui.mm("<gray>Right-click to <white>surge<gray> for up to <white>6s"),
                    Ui.mm("<gray>Much faster, higher jumps, glide up steps"),
                    Ui.mm("<gray>Drains your health while it lasts"),
                    Ui.mm("<gray>(never below half)"),
                    Ui.mm(""),
                    Ui.mm("<white>One use<gray> - consumed when you activate it"),
                    Ui.mm("<gray>Cooldown: <white>" + COOLDOWN_SECONDS + "s"),
                    Ui.mm("<dark_gray>BlackForgeMC ability")));
            m.setMaxStackSize(1);
            if (customModel) m.setItemModel(modelKey);
            else m.setEnchantmentGlintOverride(true);
            UseCooldownComponent cd = m.getUseCooldown();
            cd.setCooldownSeconds(COOLDOWN_SECONDS);
            cd.setCooldownGroup(modelKey);   // only Surge greys out, not every wither rose
            m.setUseCooldown(cd);
            m.getPersistentDataContainer().set(tagKey, PersistentDataType.BYTE, (byte) 1);
        });
        return it;
    }

    public boolean isSurge(ItemStack it) {
        return it != null && it.getType() == Material.WITHER_ROSE && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(tagKey, PersistentDataType.BYTE);
    }

    public void give(Player p) {
        p.getInventory().addItem(item()).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        p.sendMessage(Ui.mm("<#FF2E88>✦ <gray>You received " + NAME + "<gray>. Right-click to surge."));
    }

    /** The resource pack shows the ignited model while this flag is on. */
    private void setActiveLook(ItemStack it, boolean on) {
        if (!isSurge(it)) return;
        it.editMeta(m -> {
            CustomModelDataComponent c = m.getCustomModelDataComponent();
            c.setFlags(List.of(on));
            m.setCustomModelDataComponent(c);
        });
    }

    // ------------------------------------------------------------------ activation

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        if (!isSurge(e.getItem())) return;
        e.setCancelled(true);   // never plant the rose
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if (active.containsKey(p.getUniqueId()) || channeling.containsKey(p.getUniqueId())) return;   // already surging
        if (!enabled) { p.sendActionBar(Ui.mm("<gray>Surge isn't switched on yet.")); return; }
        if (p.getGameMode() == GameMode.SPECTATOR) return;
        if (!allowedAt.test(p.getLocation())) { p.sendActionBar(Ui.mm("<#FF2E88>✦ <gray>You can't surge here.")); return; }
        long left = readyAt.getOrDefault(p.getUniqueId(), 0L) - System.currentTimeMillis();
        if (left > 0 && !testing(p)) { p.sendActionBar(Ui.mm("<#FF2E88>✦ <gray>Surge is recharging... <white>" + (left / 1000 + 1) + "s")); return; }
        double max = maxHealth(p);
        if (!SurgeCurve.canStart(p.getHealth(), max)) {
            p.sendActionBar(Ui.mm("<#FF2E88>✦ <gray>Too weak to surge - you need more than <white>half health<gray>."));
            return;
        }
        // one use: take it now (before the animation), so it can't be used twice
        ItemStack held = p.getInventory().getItemInMainHand();
        if (!isSurge(held)) return;
        held.setAmount(held.getAmount() - 1);
        p.getInventory().setItemInMainHand(held.getAmount() > 0 ? held : null);
        readyAt.put(p.getUniqueId(), System.currentTimeMillis() + COOLDOWN_SECONDS * 1000L);
        channel(p);
    }

    // ------------------------------------------------------------------ the activation animation

    private static final int CHANNEL_TICKS = 12;   // 0.6 s from right-click to the strike

    /** Where the rose floats: just in front of the player's chest, following their view. */
    private static Location floatAt(Player p) {
        Location eye = p.getEyeLocation();
        return eye.clone().add(eye.getDirection().multiply(1.1)).add(0, -0.35, 0);
    }

    private static Transformation pose(float scale, float spinDegrees, float lift) {
        return new Transformation(new Vector3f(0, lift, 0), new AxisAngle4f((float) Math.toRadians(spinDegrees), 0, 1, 0),
                new Vector3f(scale, scale, scale), new AxisAngle4f());
    }

    /**
     * The rose lifts out of the hand, spins and swells while petals spiral into it (the surger sees the spiral), then
     * bursts into the lightning strike that starts the surge. Movement stays free the whole time.
     */
    private void channel(Player p) {
        p.swingMainHand();
        ItemStack look = item();
        setActiveLook(look, true);
        ItemDisplay rose = p.getWorld().spawn(floatAt(p), ItemDisplay.class, d -> {
            d.setItemStack(look);
            d.setBillboard(Display.Billboard.FIXED);
            d.setPersistent(false);
            d.setTeleportDuration(1);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setGlowing(true);
            d.setGlowColorOverride(MAGENTA);
            d.setTransformation(pose(0.35f, 0, 0));
        });
        channeling.put(p.getUniqueId(), rose);
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, SoundCategory.PLAYERS, 0.9f, 0.7f);
        int[] t = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int n = ++t[0];
            if (!p.isOnline() || p.isDead() || !rose.isValid()) { rose.remove(); channeling.remove(p.getUniqueId()); task.cancel(); return; }
            Location at = floatAt(p);
            rose.teleport(at);
            if (n == 1) {        // lift and spin up
                rose.setInterpolationDelay(0); rose.setInterpolationDuration(6);
                rose.setTransformation(pose(0.9f, 200, 0.15f));
            } else if (n == 7) { // swell, spinning faster
                rose.setInterpolationDelay(0); rose.setInterpolationDuration(5);
                rose.setTransformation(pose(1.5f, 560, 0.25f));
                p.playSound(p.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, SoundCategory.PLAYERS, 0.8f, 1.3f);
            }
            // petals spiral inward to the rose (only the surger sees them)
            double progress = n / (double) CHANNEL_TICKS;
            for (int i = 0; i < 6; i++) {
                double a = (n * 0.9) + i * Math.PI / 3, r = 1.8 * (1 - progress) + 0.2;
                Location pt = at.clone().add(r * Math.cos(a), (i % 2 == 0 ? 0.4 : -0.4) * (1 - progress), r * Math.sin(a));
                p.spawnParticle(Particle.DUST_COLOR_TRANSITION, pt, 1, 0, 0, 0, 0, new Particle.DustTransition(CRIMSON, MAGENTA, 1.2f));
            }
            if (n % 3 == 0) p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.6f, (float) (0.8 + progress));
            if (n >= CHANNEL_TICKS) {   // burst: the bolt comes down on the rose, right in front of you
                p.getWorld().strikeLightningEffect(at);
                p.playSound(p.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.PLAYERS, 0.8f, 1.2f);
                p.getWorld().spawnParticle(Particle.DUST, at, 30, 0.3, 0.3, 0.3, 0, new Particle.DustOptions(MAGENTA, 1.6f));
                p.getWorld().spawnParticle(Particle.DUST, at, 20, 0.3, 0.3, 0.3, 0, new Particle.DustOptions(WITHER_BLACK, 1.4f));
                rose.remove();
                channeling.remove(p.getUniqueId());
                task.cancel();
                start(p);
            }
        }, 1L, 1L);
    }

    private static double maxHealth(Player p) {
        AttributeInstance a = p.getAttribute(Attribute.MAX_HEALTH);
        return a == null ? 20 : a.getValue();
    }

    private void start(Player p) {
        World w = p.getWorld();
        Active s = new Active();
        s.last = p.getLocation();
        active.put(p.getUniqueId(), s);

        w.strikeLightningEffect(p.getLocation());   // visual only: no damage, no fire
        launch(p);
        for (Boost b : boosts) {
            AttributeInstance ai = p.getAttribute(b.attribute());
            if (ai == null) continue;
            ai.removeModifier(b.key());
            ai.addTransientModifier(new AttributeModifier(b.key(), b.amount(), b.op(), EquipmentSlotGroup.ANY));
        }
        // black wither hearts for the look; its real damage is cancelled in onDamage
        p.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, (int) (SurgeCurve.MAX_SECONDS * 20) + 20, 0, false, false, true));
        s.border = edges(p, true);
        darkWorld(p, s, true);
        p.showTitle(Title.title(Ui.mm("<gradient:#5C0011:#B3122E:#5C0011><bold>SURGE"), Ui.mm(""),
                Title.Times.times(Duration.ofMillis(50), Duration.ofMillis(350), Duration.ofMillis(250))));
        p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SHOOT, SoundCategory.PLAYERS, 0.8f, 1.6f);
        p.playSound(p.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, SoundCategory.PLAYERS, 1.0f, 1.4f);
        setActiveLook(p.getInventory().getItemInMainHand(), true);

        activationFx(p);

        s.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(p, s), 1L, 1L);
    }

    /**
     * Activation, all dark red: a burst of dust right in front of the surger's eyes (a screen flash only they see),
     * a full sphere of petals, then a shockwave rolling out along the ground from the launch spot and a double
     * spiral climbing round the player for a second. The sphere, shockwave and spiral are seen by everyone nearby.
     */
    private void activationFx(Player p) {
        World w = p.getWorld();
        Location eye = p.getEyeLocation();
        Vector f = eye.getDirection();
        for (int i = 0; i < 70; i++) {   // the screen burst
            Vector off = new Vector(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()).multiply(0.35);
            Location at = eye.clone().add(f.clone().multiply(0.7 + rnd.nextDouble() * 0.6)).add(off);
            p.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, new Particle.DustOptions(rnd.nextBoolean() ? BLOOD : DEEP_RED, 2.0f));
        }
        Location c = p.getLocation().add(0, 1, 0);
        for (int i = 0; i < 60; i++) {   // the sphere
            Vector v = new Vector(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()).normalize().multiply(2.5);
            w.spawnParticle(Particle.DUST_COLOR_TRANSITION, c.clone().add(v), 1, 0, 0, 0, 0, new Particle.DustTransition(DEEP_RED, BLOOD, 1.5f));
        }
        w.spawnParticle(Particle.DUST, c, 25, 0.6, 0.6, 0.6, 0, new Particle.DustOptions(BLOOD, 1.8f));
        w.spawnParticle(Particle.DUST, c, 12, 0.5, 0.5, 0.5, 0, new Particle.DustOptions(WITHER_BLACK, 1.5f));
        w.spawnParticle(Particle.DAMAGE_INDICATOR, c, 8, 0.4, 0.4, 0.4, 0.1);

        Location ground = p.getLocation().add(0, 0.15, 0);   // the shockwave stays where the surge started
        UUID id = p.getUniqueId();
        int[] t = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int k = t[0]++;
            Player v = Bukkit.getPlayer(id);
            if (k >= 20 || v == null || !v.isOnline()) { task.cancel(); return; }
            if (k < 10) {   // shockwave: 0.6 -> 4.6 blocks over half a second
                double r = 0.6 + k * 0.45;
                int pts = 24 + k * 4;
                Particle.DustOptions dust = new Particle.DustOptions(k < 5 ? DEEP_RED : BLOOD, 1.6f - k * 0.08f);
                for (int i = 0; i < pts; i++) {
                    double a = 2 * Math.PI * i / pts;
                    w.spawnParticle(Particle.DUST, ground.getX() + r * Math.cos(a), ground.getY(), ground.getZ() + r * Math.sin(a), 1, 0, 0, 0, 0, dust);
                }
                w.spawnParticle(Particle.CRIMSON_SPORE, ground, 6, r * 0.5, 0.1, r * 0.5, 0);
            }
            // spiral: two strands climbing the player, fading from deep red to blood
            Location base = v.getLocation();
            Particle.DustOptions dust = new Particle.DustOptions(k < 10 ? DEEP_RED : BLOOD, 1.2f);
            for (int strand = 0; strand < 2; strand++) {
                for (int j = 0; j < 3; j++) {
                    double step = k * 3 + j;
                    double a = step * 0.35 + strand * Math.PI;
                    double y = (step % 30) / 30.0 * 2.2;
                    w.spawnParticle(Particle.DUST, base.getX() + 0.9 * Math.cos(a), base.getY() + y, base.getZ() + 0.9 * Math.sin(a), 1, 0, 0, 0, 0, dust);
                }
            }
        }, 1L, 1L);
    }

    /** Staff in a test area (the hub Arena) skip the cooldown so they can try it again and again. */
    private boolean testing(Player p) { return staffSkipCooldown && p.hasPermission("alloy.admin"); }

    /**
     * The strike throws you forward: one big instant push the way you're looking (a dash, not flight), with a little
     * lift so it clears the ground. Fall damage is off for the whole surge, so the landing never hurts.
     */
    private void launch(Player p) {
        Vector look = p.getLocation().getDirection();
        Vector flat = look.clone().setY(0);
        Vector push = flat.lengthSquared() < 1e-6 ? new Vector() : flat.normalize().multiply(LAUNCH_SPEED);
        push.setY(Math.max(0.35, Math.min(0.9, look.getY() * 1.2 + 0.35)));
        p.setVelocity(push);
        p.setFallDistance(0f);
    }

    private static final double EDGE_SIZE = 400;

    /**
     * The personal world border trick: a border only this player sees, with a huge warning distance, tints the screen
     * edges red. While surging, {@link #pulseEdges} makes the red throb with the heartbeat.
     */
    private WorldBorder edges(Player p, boolean on) {
        if (!on) { p.setWorldBorder(null); return null; }
        WorldBorder wb = Bukkit.createWorldBorder();
        wb.setCenter(p.getLocation());
        wb.setSize(EDGE_SIZE);
        wb.setWarningDistance(1_000_000);   // 200 blocks from the edge out of a million: the red tint is at full strength
        p.setWorldBorder(wb);
        return wb;
    }

    /** Throb the red edges: set the warning distance so the client's tint matches {@link SurgeCurve#edgeGlow} this tick. */
    private void pulseEdges(Player p, Active s, boolean warn) {
        WorldBorder wb = s.border;
        if (wb == null) return;
        Location here = p.getLocation(), c = wb.getCenter();
        double dist = EDGE_SIZE / 2 - Math.max(Math.abs(here.getX() - c.getX()), Math.abs(here.getZ() - c.getZ()));
        wb.setWarningDistance(SurgeCurve.warningDistance(dist, SurgeCurve.edgeGlow(s.ticks, warn)));
    }

    // ------------------------------------------------------------------ while surging

    private void tick(Player p, Active s) {
        if (!p.isOnline() || p.isDead()) { end(p, null); return; }
        double t = ++s.ticks / 20.0;
        if (t >= SurgeCurve.MAX_SECONDS) { end(p, "time"); return; }
        boolean warn = SurgeCurve.warning(t);
        pulseEdges(p, s, warn);

        if (s.ticks % 10 == 0 && drain(p, s, t)) { end(p, "drained"); return; }
        if (s.ticks % 2 == 0)
            p.sendActionBar(Ui.mm((warn ? "<#8a7f85>" : "<#8A0A1E>") + "☠ " + SurgeCurve.meter(SurgeCurve.MAX_SECONDS - t)));

        Location here = p.getLocation();
        double moved = s.last.getWorld() == here.getWorld() ? Math.hypot(here.getX() - s.last.getX(), here.getZ() - s.last.getZ()) : 0;
        s.last = here;
        Color from = warn ? ASH : DEEP_RED, to = warn ? ASH : WITHER_BLACK;

        // only the surger sees this layer: blood-red dust darkening to black, drifting in a 6-block bubble
        if (s.ticks % 2 == 0)
            for (int i = 0; i < 8; i++) {
                Vector v = new Vector(rnd.nextGaussian(), rnd.nextGaussian() * 0.6, rnd.nextGaussian()).normalize().multiply(1.5 + rnd.nextDouble() * 4.5);
                p.spawnParticle(Particle.DUST_COLOR_TRANSITION, here.clone().add(0, 1.2, 0).add(v), 1, 0, 0, 0, 0, new Particle.DustTransition(from, to, 1.0f));
            }
        // speed lines: sparks streaming past from ahead, denser the faster you go
        if (moved > 0.2 && !warn) {
            Vector dir = here.getDirection().setY(0);
            if (dir.lengthSquared() > 1e-6) {
                dir.normalize();
                int n = moved > 0.35 ? 3 : 1;
                for (int i = 0; i < n; i++) {
                    Location at = here.clone().add(dir.clone().multiply(4)).add(rnd.nextDouble() * 3 - 1.5, 0.3 + rnd.nextDouble() * 1.8, rnd.nextDouble() * 3 - 1.5);
                    p.spawnParticle(Particle.END_ROD, at, 0, -dir.getX(), 0, -dir.getZ(), 0.6);
                }
            }
        }
        // heartbeat ring along the ground, faster in the last stretch
        if (s.ticks % (warn ? 10 : 20) == 0) {
            p.playSound(here, Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 1.0f, warn ? 1.3f : 0.9f);
            ring(p, here, 1.6, warn ? ASH : DEEP_RED);
        }
        // landing a jump: a crimson bloom where you touch down
        boolean onGround = p.isOnGround();
        if (onGround && !s.wasOnGround) {
            ring(p, here, 1.2, CRIMSON);
            p.getWorld().spawnParticle(Particle.DUST, here.clone().add(0, 0.1, 0), 6, 0.4, 0.05, 0.4, 0, new Particle.DustOptions(CRIMSON, 1.2f));
        }
        s.wasOnGround = onGround;
        // what everyone else sees: a dark aura that makes it obvious you're surging
        aura(p, here, s.ticks, warn);
        if (s.ticks % 40 == 0) {
            p.playSound(here, Sound.ENTITY_WITHER_AMBIENT, SoundCategory.PLAYERS, 0.15f, 1.5f);
            p.playSound(here, Sound.BLOCK_BEACON_AMBIENT, SoundCategory.PLAYERS, 0.9f, 0.5f);   // a low drone, surger only
        }
    }

    private static final int SURGE_TICKS = (int) (SurgeCurve.MAX_SECONDS * 20);

    /**
     * The surger's own world goes dark for the surge: frozen midnight, rain, and Darkness for the whole surge (it pulses
     * on its own and fades out as the surge ends). With surge.screen-darkness off it is only a 1.5 s pulse at the start.
     * Only they see it. {@code on = false} puts their sky back exactly as it was, and takes the Darkness off if the surge
     * ended early, but only if it is ours (a longer real Potion of Darkness hit is left alone).
     */
    private void darkWorld(Player p, Active s, boolean on) {
        if (on) {
            s.timeOffset = p.getPlayerTimeOffset();
            s.timeRelative = p.isPlayerTimeRelative();
            s.weather = p.getPlayerWeather();
            p.setPlayerTime(18000, false);
            p.setPlayerWeather(WeatherType.DOWNFALL);
            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, screenDarkness ? SURGE_TICKS : 30, 0, false, false, false));
            return;
        }
        PotionEffect dark = p.getPotionEffect(PotionEffectType.DARKNESS);
        if (screenDarkness && dark != null && !dark.isInfinite() && dark.getAmplifier() == 0 && dark.getDuration() <= SURGE_TICKS - s.ticks + 2)
            p.removePotionEffect(PotionEffectType.DARKNESS);
        if (s.timeRelative && s.timeOffset == 0) p.resetPlayerTime();
        else p.setPlayerTime(s.timeOffset, s.timeRelative);
        if (s.weather == null) p.resetPlayerWeather();
        else p.setPlayerWeather(s.weather);
    }

    /**
     * What everyone sees while you surge: two blood-red rings spinning opposite ways round your body, dark red drips
     * falling off you, black smoke trailing behind and crimson spores. Turns grey in the last stretch.
     */
    private void aura(Player p, Location here, int tick, boolean warn) {
        World w = p.getWorld();
        Particle.DustOptions ringA = new Particle.DustOptions(warn ? ASH : DEEP_RED, 1.3f), ringB = new Particle.DustOptions(warn ? ASH : BLOOD, 1.3f);
        double spin = tick * 0.35;
        for (int i = 0; i < 3; i++) {
            double a = spin + i * 2 * Math.PI / 3, b = -spin + i * 2 * Math.PI / 3 + Math.PI / 3;
            w.spawnParticle(Particle.DUST, here.getX() + 0.85 * Math.cos(a), here.getY() + 0.5, here.getZ() + 0.85 * Math.sin(a), 1, 0, 0, 0, 0, ringA);
            w.spawnParticle(Particle.DUST, here.getX() + 0.75 * Math.cos(b), here.getY() + 1.4, here.getZ() + 0.75 * Math.sin(b), 1, 0, 0, 0, 0, ringB);
        }
        if (tick % 2 == 0) {
            w.spawnParticle(Particle.LARGE_SMOKE, here.clone().add(0, 0.6, 0), 2, 0.25, 0.4, 0.25, 0.005);
            w.spawnParticle(Particle.DUST, here.clone().add(0, 1.0, 0), 3, 0.3, 0.5, 0.3, 0, new Particle.DustOptions(WITHER_BLACK, 1.6f));
        }
        if (!warn && tick % 3 == 0) {
            w.spawnParticle(Particle.FALLING_DUST, here.clone().add(0, 1.6, 0), 2, 0.3, 0.3, 0.3, 0, Material.NETHER_WART_BLOCK.createBlockData());
            w.spawnParticle(Particle.CRIMSON_SPORE, here.clone().add(0, 1.0, 0), 4, 0.6, 0.6, 0.6, 0);
        }
    }

    private void ring(Player p, Location at, double r, Color c) {
        Particle.DustOptions dust = new Particle.DustOptions(c, 1.1f);
        for (int i = 0; i < 18; i++) {
            double a = 2 * Math.PI * i / 18;
            p.spawnParticle(Particle.DUST, at.getX() + r * Math.cos(a), at.getY() + 0.1, at.getZ() + r * Math.sin(a), 1, 0, 0, 0, 0, dust);
        }
    }

    /**
     * Take this tick's share of the drain by setting health directly: no damage event, so no camera tilt, hurt sound
     * or knockback. Absorption goes first; never below half max health. Returns true if the floor was reached.
     */
    private boolean drain(Player p, Active s, double t) {
        double amount = SurgeCurve.drainBetween(s.drainedTo, t);
        s.drainedTo = t;
        double absorb = p.getAbsorptionAmount();
        if (absorb > 0) {
            double used = Math.min(absorb, amount);
            p.setAbsorptionAmount(absorb - used);
            amount -= used;
        }
        double floor = SurgeCurve.floor(maxHealth(p));
        double next = Math.max(floor, p.getHealth() - amount);
        if (next < p.getHealth()) p.setHealth(next);
        p.getWorld().spawnParticle(Particle.DUST, p.getLocation().add(0, 0.1, 0), 2, 0.2, 0.05, 0.2, 0, new Particle.DustOptions(CRIMSON, 0.9f));
        return next <= floor + 1e-6;
    }

    // ------------------------------------------------------------------ ending

    /** End a surge. reason: "time", "drained", or null for a silent cleanup (death, quit, shutdown). */
    private void end(Player p, String reason) {
        Active s = active.remove(p.getUniqueId());
        if (s == null) return;
        if (s.task != null) s.task.cancel();
        clearBoosts(p);
        if (p.hasPotionEffect(PotionEffectType.WITHER)) p.removePotionEffect(PotionEffectType.WITHER);
        edges(p, false);
        darkWorld(p, s, false);
        for (ItemStack it : p.getInventory().getContents()) setActiveLook(it, false);
        long now = System.currentTimeMillis();
        readyAt.put(p.getUniqueId(), now + COOLDOWN_SECONDS * 1000L);   // the 30 s runs from the end of the surge
        noFallUntil.put(p.getUniqueId(), now + 1000);   // land safely from a last surge jump
        if (testing(p)) readyAt.remove(p.getUniqueId());
        else p.setCooldown(item(), COOLDOWN_SECONDS * 20);
        if (reason == null || !p.isOnline()) return;
        Location c = p.getLocation().add(0, 1, 0);
        for (int i = 0; i < 24; i++) {   // petals fall inward and vanish
            Vector v = new Vector(rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()).normalize();
            p.spawnParticle(Particle.DUST, c.clone().add(v.clone().multiply(2)), 1, 0, 0, 0, 0, new Particle.DustOptions(ASH, 1.0f));
        }
        p.playSound(p.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, SoundCategory.PLAYERS, 0.6f, 1.4f);
        switch (reason) {
            case "drained" -> p.sendActionBar(Ui.mm("<#8a7f85>✦ Surge burned out at half health"));
            default -> p.sendActionBar(Ui.mm("<#8a7f85>✦ Surge faded"));
        }
    }

    private void clearBoosts(Player p) {
        for (Boost b : boosts) {
            AttributeInstance ai = p.getAttribute(b.attribute());
            if (ai != null) ai.removeModifier(b.key());
        }
    }

    /** In case a crash ever left anything behind: no boosts, no red border, no ignited item on join. */
    private void clearLeftovers(Player p) {
        if (active.containsKey(p.getUniqueId())) return;
        clearBoosts(p);
        p.setWorldBorder(null);
        for (ItemStack it : p.getInventory().getContents()) setActiveLook(it, false);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        UUID id = p.getUniqueId();
        if (e.getCause() == EntityDamageEvent.DamageCause.WITHER && active.containsKey(id)) e.setCancelled(true);   // looks only
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL
                && (active.containsKey(id) || noFallUntil.getOrDefault(id, 0L) > System.currentTimeMillis())) e.setCancelled(true);
    }

    @EventHandler public void onDeath(PlayerDeathEvent e) { end(e.getPlayer(), null); }
    @EventHandler public void onQuit(PlayerQuitEvent e) { end(e.getPlayer(), null); }
    @EventHandler public void onWorld(PlayerChangedWorldEvent e) { end(e.getPlayer(), null); }
    @EventHandler public void onJoin(PlayerJoinEvent e) { clearLeftovers(e.getPlayer()); }

    /** Server stopping: end every surge cleanly. */
    public void shutdown() {
        channeling.values().forEach(d -> { if (d.isValid()) d.remove(); });
        channeling.clear();
        for (UUID id : new ArrayList<>(active.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) end(p, null);
        }
        active.clear();
    }
}
