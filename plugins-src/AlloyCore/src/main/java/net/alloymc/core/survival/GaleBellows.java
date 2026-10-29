package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.components.UseCooldownComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Gale Bellows: a BlackForgeMC PvP item. Right-click to slash a wave of wind that starts at your hand and rolls out to
 * 3 blocks, hitting everything it reaches for 2 hearts and throwing it back at least 3 blocks. Aim at the ground close
 * by instead and the gust launches you up about 5 blocks (a Gale jump, no fall damage). Cooldown comes from
 * {@code gale-bellows.cooldown-seconds} (default 7). The cone and wave maths live in {@link WindCone}.
 *
 * Every hit goes through a normal damage event first, so spawn protection, claims and the "PvP only in the arena"
 * rule decide who can be pushed - the bellows never bypasses them.
 */
public final class GaleBellows implements Listener {
    public static final double DAMAGE = 4.0;                // 2 hearts before armour (about 1-1.5 through armour)
    static final double BOOST_PITCH = 50;                   // look at least this far down...
    static final double BOOST_REACH = 3.0;                  // ...at ground this close to your eyes: a Gale jump
    static final double BOOST_UP = 0.95;                    // upward speed: about 5 blocks
    static final long BOOST_SAFE_MS = 5000;                 // no fall damage this long after a Gale jump
    public static final int USES = 3;                       // blasts before it breaks

    // palette (see the plan's visual guide)
    private static final Color GALE_WHITE = Color.fromRGB(0xEAF6FF), GUST_CYAN = Color.fromRGB(0x9EE6FF), DRAFT_TEAL = Color.fromRGB(0x4FC3D9);
    public static final String NAME = "<gradient:#EAF6FF:#4FC3D9><bold>Gale Bellows</bold></gradient>";

    private final AlloyCore plugin;
    private final boolean enabled, customModel;
    private final int cooldownTicks;   // 0 = no cooldown
    private final NamespacedKey tagKey, uidKey, modelKey, usesKey;
    private final java.io.File pendingFile;
    private final Set<UUID> pending = new HashSet<>();   // owed a bellows the next time they join (given while offline)
    private final Map<UUID, Long> readyAt = new HashMap<>();       // caster -> when the bellows can fire again
    private final Map<UUID, Long> lastPushed = new HashMap<>();    // target -> when they were last pushed (juggle rule)
    private final Map<UUID, Long> boosted = new HashMap<>();       // player -> until when a Gale jump's landing is safe
    private final Random rnd = new Random();

    private final java.util.function.Predicate<Location> freeUseAt;   // where blasts use no charge (the hub Arena); the cooldown still applies
    private final java.util.function.Predicate<Entity> alsoTarget;     // AlloyCore-tagged entities it may still hit (the Arena Test Dummy)

    public GaleBellows(AlloyCore plugin) { this(plugin, l -> false, e -> false); }

    /**
     * {@code freeUseAt}: where a blast costs no charge, so the bellows never breaks there (the cooldown still applies).
     * {@code alsoTarget}: AlloyCore's own entities that it may still hit (normally it never touches them).
     */
    public GaleBellows(AlloyCore plugin, java.util.function.Predicate<Location> freeUseAt, java.util.function.Predicate<Entity> alsoTarget) {
        this.plugin = plugin;
        this.freeUseAt = freeUseAt;
        this.alsoTarget = alsoTarget;
        this.enabled = plugin.getConfig().getBoolean("gale-bellows.enabled", true);
        this.cooldownTicks = Math.max(0, (int) Math.round(plugin.getConfig().getDouble("gale-bellows.cooldown-seconds", 7) * 20));
        // only once the BlackForgeMC resource pack is live: without it the model id renders as the purple/black missing model
        this.customModel = plugin.getConfig().getBoolean("gale-bellows.custom-model", plugin.getConfig().getBoolean("resource-pack-live", false));
        this.tagKey = new NamespacedKey(plugin, "gale_bellows");
        this.uidKey = new NamespacedKey(plugin, "gb_uid");
        this.modelKey = new NamespacedKey("blackforge", "gale_bellows");   // the resource pack's item model
        this.usesKey = new NamespacedKey(plugin, "gb_uses");
        this.pendingFile = new java.io.File(plugin.getDataFolder(), "bellows-pending.txt");
        try { if (pendingFile.exists()) for (String l : java.nio.file.Files.readAllLines(pendingFile.toPath())) if (!l.isBlank()) pending.add(UUID.fromString(l.trim())); }
        catch (Exception ex) { plugin.getLogger().warning("bellows-pending.txt unreadable: " + ex.getMessage()); }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::dupeScan, 600L, 600L);
        Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getOnlinePlayers().forEach(p -> { repair(p); deliver(p); }));
    }

    public boolean enabled() { return enabled; }

    // ------------------------------------------------------------------ the item

    /** A new Gale Bellows with its own id. Until the resource pack is live (gale-bellows.custom-model) it is a named, glinting breeze rod. */
    public ItemStack item() {
        ItemStack it = new ItemStack(Material.BREEZE_ROD);
        it.editMeta(m -> {
            m.displayName(Ui.mm(NAME));
            m.lore(lore(USES));
            m.setMaxStackSize(1);
            if (customModel) m.setItemModel(modelKey);
            else m.setEnchantmentGlintOverride(true);
            if (cooldownTicks > 0) {
                UseCooldownComponent cd = m.getUseCooldown();
                cd.setCooldownSeconds(cooldownTicks / 20f);
                cd.setCooldownGroup(modelKey);   // only the bellows greys out, not every breeze rod
                m.setUseCooldown(cd);
            }
            m.getPersistentDataContainer().set(tagKey, PersistentDataType.BYTE, (byte) 1);
            m.getPersistentDataContainer().set(uidKey, PersistentDataType.STRING, UUID.randomUUID().toString());
            m.getPersistentDataContainer().set(usesKey, PersistentDataType.INTEGER, USES);
        });
        return it;
    }

    private List<net.kyori.adventure.text.Component> lore(int uses) {
        return List.of(
                Ui.mm("<gray>Right-click to slash a wave of wind"),
                Ui.mm("<gray>Hits everyone up to <white>3 blocks <gray>in front"),
                Ui.mm("<gray>for <white>2 hearts <gray>and blows them back"),
                Ui.mm("<gray>Aim at the ground to <white>launch yourself up"),
                Ui.mm(""),
                Ui.mm(cooldownTicks > 0 ? "<gray>Cooldown: <white>" + String.format(Locale.ROOT, "%.0f", cooldownTicks / 20.0) + "s" : "<gray>Cooldown: <white>none"),
                Ui.mm("<gray>Uses left: " + (uses <= 1 ? "<red>" : "<white>") + uses + "<gray>/" + USES),
                Ui.mm("<dark_gray>BlackForgeMC PvP item"));
    }

    private int uses(ItemStack it) {
        Integer u = it.getItemMeta().getPersistentDataContainer().get(usesKey, PersistentDataType.INTEGER);
        return u == null ? USES : u;   // bellows from before uses existed start full
    }

    /** Has this bellows used any of its charges? (The hub Arena swaps worn ones for fresh ones: unlimited uses there.) */
    public boolean isWorn(ItemStack it) { return isBellows(it) && uses(it) < USES; }

    public boolean isBellows(ItemStack it) {
        return it != null && it.getType() == Material.BREEZE_ROD && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(tagKey, PersistentDataType.BYTE);
    }

    private static String uid(ItemStack it, NamespacedKey key) {
        return it.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    public void give(Player p) {
        p.getInventory().addItem(item()).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        p.sendMessage(Ui.mm("<#9EE6FF>✦ <gray>You received a " + NAME + "<gray>! Right-click to blast the wind."));
    }

    /** True if the player already carries a bellows anywhere in their inventory (hotbar, offhand, armour slots). */
    public boolean hasOne(Player p) {
        for (ItemStack it : p.getInventory().getContents()) if (isBellows(it)) return true;
        return isBellows(p.getItemOnCursor());
    }

    /** Staff gave a bellows to someone who wasn't on Survival: hand it over the next time they join. */
    public void queue(UUID id) {
        pending.add(id);
        savePending();
    }

    private void deliver(Player p) {
        if (!pending.remove(p.getUniqueId())) return;
        savePending();
        give(p);
    }

    private void savePending() {
        try { java.nio.file.Files.write(pendingFile.toPath(), pending.stream().map(UUID::toString).toList()); }
        catch (Exception ex) { plugin.getLogger().warning("Couldn't save bellows-pending.txt: " + ex.getMessage()); }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) { repair(p); deliver(p); } }, 20L);   // after other join handlers move/fill them
    }

    /** Bellows handed out before the resource pack was live point at a model nobody has: swap them back to the breeze rod look. */
    private void repair(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (!isBellows(it)) continue;
            // pack live: older bellows get the texture; pack off: drop the model so they aren't purple/black
            if (customModel && !it.getItemMeta().hasItemModel()) it.editMeta(m -> { m.setItemModel(modelKey); m.setEnchantmentGlintOverride(null); });
            else if (!customModel && it.getItemMeta().hasItemModel()) it.editMeta(m -> { m.setItemModel(null); m.setEnchantmentGlintOverride(true); });
        }
    }

    // ------------------------------------------------------------------ casting

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;   // the event fires once per hand
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        ItemStack held = p.getInventory().getItemInMainHand();
        if (!isBellows(held)) return;
        // right-clicking a door, chest or button still uses it, unless you're sneaking
        Block b = e.getClickedBlock();
        if (a == Action.RIGHT_CLICK_BLOCK && b != null && b.getType().isInteractable() && !p.isSneaking()) return;
        e.setCancelled(true);
        if (!enabled) { p.sendActionBar(Ui.mm("<gray>The Gale Bellows isn't switched on yet.")); return; }
        if (p.getGameMode() == GameMode.SPECTATOR) return;

        long now = System.currentTimeMillis();
        long ready = readyAt.getOrDefault(p.getUniqueId(), 0L);
        if (ready > now) {
            p.sendActionBar(Ui.mm("<#9EE6FF>🌬 <gray>Refilling... <white>" + String.format(Locale.ROOT, "%.1f", (ready - now) / 1000.0) + "s"));
            return;
        }
        cast(p, held, now);
    }

    private void cast(Player p, ItemStack held, long now) {
        Location eye = p.getEyeLocation();
        Vector look = eye.getDirection();
        // aiming at the ground close by: the gust bursts under your own feet and launches you up instead
        if (eye.getPitch() >= BOOST_PITCH) {
            RayTraceResult ground = p.getWorld().rayTraceBlocks(eye, look, BOOST_REACH, FluidCollisionMode.NEVER, true);
            if (ground != null && ground.getHitBlock() != null) {
                spend(p, held, now);
                boost(p, ground.getHitPosition().toLocation(p.getWorld()));
                return;
            }
        }
        // who the wave reaches, and on which tick: it starts at your hand and rolls out to WindCone.RANGE
        Map<LivingEntity, Integer> reach = new LinkedHashMap<>();
        for (Entity en : p.getNearbyEntities(4, 4, 4)) {
            if (!(en instanceof LivingEntity t) || !canTarget(p, t)) continue;
            var box = t.getBoundingBox();
            double dx = box.getCenterX() - eye.getX(), dz = box.getCenterZ() - eye.getZ(), half = box.getWidthX() / 2;
            if (!WindCone.hits(look.getX(), look.getY(), look.getZ(), dx, dz, box.getMinY() - eye.getY(), box.getMaxY() - eye.getY(), half)) continue;
            if (blocked(eye, t)) continue;
            if (!alsoTarget.test(t) && !allowed(p, t)) continue;   // the dummy counts every damage event, so no probe for it
            reach.put(t, WindCone.waveTick(Math.max(0, Math.hypot(dx, dz) - half)));
        }
        spend(p, held, now);
        wave(p, eye, look, reach);
    }

    /** The cooldown (if any) and a charge (none in the Arena). Shared by the slash and the Gale jump. */
    private void spend(Player p, ItemStack held, long now) {
        if (cooldownTicks > 0) {
            readyAt.put(p.getUniqueId(), now + cooldownTicks * 50L);
            p.setCooldown(held, cooldownTicks);
            UUID id = p.getUniqueId();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {   // a quiet "ready" cue when it refills, to the owner only
                Player q = Bukkit.getPlayer(id);
                if (q == null || readyAt.getOrDefault(id, 0L) > System.currentTimeMillis() + 50) return;
                q.playSound(q, Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.6f, 1.2f);
                q.sendActionBar(Ui.mm("<#9EE6FF>🌬 <gray>Gale Bellows <white>ready"));
            }, cooldownTicks);
        }
        useUp(p, held);
    }

    /**
     * Throws back and deals the 2-heart hit to everyone in {@code hit} (the targets the wave reached this tick).
     * Anyone who has since died, left or changed world is skipped. Returns how many it landed on.
     */
    private int blast(Player p, List<LivingEntity> hit, Vector look) {
        long now = System.currentTimeMillis();
        int landed = 0;
        for (LivingEntity t : hit) {
            if (!t.isValid() || t.isDead() || t.getWorld() != p.getWorld()) continue;
            landed++;
            Long last = lastPushed.get(t.getUniqueId());
            boolean juggled = last != null && now - last < WindCone.JUGGLE_WINDOW_MS;
            lastPushed.put(t.getUniqueId(), now);
            AttributeInstance kb = t.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
            double[] v = WindCone.push(t.getX() - p.getX(), t.getZ() - p.getZ(), look.getX(), look.getZ(),
                    kb == null ? 0 : kb.getValue(), !t.isOnGround(), juggled);
            // the hit: a real damage event, so kill credit, combat tags and the arena rules all apply.
            // Clear the vanilla hit cooldown first, or a sword hit just before would swallow the gust's damage.
            t.setNoDamageTicks(0);
            t.damage(DAMAGE, p);
            // vanilla adds its own knockback for that hit, so set ours on the next tick to replace it
            // (never on the Test Dummy: it's pinned to its post)
            if (!alsoTarget.test(t)) Bukkit.getScheduler().runTask(plugin, () -> {
                if (!t.isValid() || t.isDead()) return;
                double vy = v[1] > 0 ? v[1] : t.getVelocity().getY();
                t.setVelocity(new Vector(v[0], vy, v[2]));
            });
            hitEffects(t);
            if (!alsoTarget.test(t)) trail(t);
            if (t instanceof Player tp) tp.sendActionBar(Ui.mm("<#9EE6FF>🌬 <gray>Blown back by <white>" + p.getName()));
        }
        return landed;
    }

    /** Aiming at the ground: the gust bursts under your own feet and throws you up about 5 blocks, keeping some momentum. */
    private void boost(Player p, Location at) {
        World w = p.getWorld();
        p.swingMainHand();
        Vector v = p.getVelocity();
        double hx = v.getX(), hz = v.getZ(), hs = Math.hypot(hx, hz), cap = 0.6;
        if (hs > cap) { hx *= cap / hs; hz *= cap / hs; }
        p.setVelocity(new Vector(hx, BOOST_UP, hz));
        p.setFallDistance(0f);
        boosted.put(p.getUniqueId(), System.currentTimeMillis() + BOOST_SAFE_MS);
        // the burst: cloud blown outward along the ground, a white-and-cyan shockwave ring and gusts
        Location c = at.clone().add(0, 0.1, 0);
        for (int i = 0; i < 28; i++) {
            double a = 2 * Math.PI * i / 28;
            w.spawnParticle(Particle.CLOUD, c, 0, Math.cos(a), 0.05, Math.sin(a), 0.35);
            w.spawnParticle(Particle.DUST, c.clone().add(1.2 * Math.cos(a), 0, 1.2 * Math.sin(a)), 1, 0, 0, 0, 0,
                    new Particle.DustOptions(i % 2 == 0 ? GALE_WHITE : GUST_CYAN, 1.6f));
        }
        w.spawnParticle(Particle.GUST, c.clone().add(0, 0.3, 0), 2, 0.3, 0.1, 0.3, 0);
        w.spawnParticle(Particle.SMALL_GUST, c, 6, 0.6, 0.1, 0.6, 0);
        w.playSound(c, Sound.ENTITY_WIND_CHARGE_WIND_BURST, SoundCategory.PLAYERS, 1.2f, 1.1f);
        w.playSound(c, Sound.ENTITY_BREEZE_JUMP, SoundCategory.PLAYERS, 1.0f, 1.0f);
        trail(p);
        p.sendActionBar(Ui.mm("<#9EE6FF>🌬 <gray>Gale jump!"));
    }

    /** A Gale jump's landing never hurts (once, within {@link #BOOST_SAFE_MS}). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFall(EntityDamageEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL || !(e.getEntity() instanceof Player p)) return;
        Long until = boosted.remove(p.getUniqueId());
        if (until != null && until > System.currentTimeMillis()) e.setCancelled(true);
    }

    /** Players and hostile mobs only - never pets, villagers, armor stands, displays or anything AlloyCore tags (robots etc). */
    private boolean canTarget(Player caster, LivingEntity t) {
        if (t == caster || t.isDead() || t instanceof ArmorStand) return false;
        if (alsoTarget.test(t)) return true;   // e.g. the Arena Test Dummy: damage numbers, but it's pinned and won't fly
        if (t.isInvulnerable()) return false;
        if (t instanceof Player tp) {
            GameMode gm = tp.getGameMode();
            if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) return false;
            if (tp.isInsideVehicle()) return false;
        } else if (!(t instanceof Enemy)) return false;
        if (t.isInsideVehicle()) return false;
        for (NamespacedKey k : t.getPersistentDataContainer().getKeys())
            if (k.getNamespace().equals(tagKey.getNamespace())) return false;
        return true;
    }

    /** No pushing through walls: a solid block between your eyes and the target's body blocks the gust. */
    private boolean blocked(Location eye, LivingEntity t) {
        Location to = t.getLocation().add(0, t.getHeight() * 0.5, 0);
        Vector dir = to.toVector().subtract(eye.toVector());
        double dist = dir.length();
        if (dist < 0.1) return false;
        RayTraceResult r = eye.getWorld().rayTraceBlocks(eye, dir.normalize(), dist, FluidCollisionMode.NEVER, true);
        return r != null && r.getHitBlock() != null;
    }

    /**
     * Ask the server's own damage rules whether the caster may hit this target here. Spawn, claims and the
     * Survival "PvP only in KOTH and /pvp" rule all cancel this probe the same way they cancel a sword hit.
     */
    private boolean allowed(Player caster, LivingEntity t) {
        DamageSource src = DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(caster).withDirectEntity(caster).build();
        EntityDamageByEntityEvent probe = new EntityDamageByEntityEvent(caster, t, EntityDamageEvent.DamageCause.ENTITY_ATTACK, src, DAMAGE);
        Bukkit.getPluginManager().callEvent(probe);
        return !probe.isCancelled();
    }

    // ------------------------------------------------------------------ effects

    /**
     * The slash: a crescent of wind that starts right at your hand and rolls out to {@link WindCone#RANGE} over
     * {@link WindCone#WAVE_TICKS} ticks (1.2, 1.8, 2.4, 3.0 blocks), tilted like a diagonal cut - bright white cutting
     * edge, cyan body, teal inner rim, with a fading afterimage behind the front. Each target is hit on the tick the
     * front reaches it, so close ones are hit at once. As it reaches full range, clouds burst out through the cone.
     */
    private void wave(Player p, Location eye, Vector look, Map<LivingEntity, Integer> reach) {
        World w = p.getWorld();
        p.swingMainHand();
        Vector f = look.clone().normalize();
        Vector side = new Vector(-f.getZ(), 0, f.getX());   // facing +Z (south), this is -X (west): your right
        if (side.lengthSquared() < 1e-6) side = new Vector(1, 0, 0);
        Vector rt = side.normalize();
        Vector u = rt.clone().crossProduct(f).normalize();
        Location base = eye.clone().add(0, -0.3, 0);
        double half = Math.toRadians(WindCone.HALF_ANGLE_DEG + 15);
        w.playSound(eye, Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1.2f, 0.8f);
        w.playSound(eye, Sound.ITEM_TRIDENT_RIPTIDE_1, SoundCategory.PLAYERS, 0.6f, 1.7f);
        w.playSound(eye, Sound.ENTITY_BREEZE_SHOOT, SoundCategory.PLAYERS, 0.8f, 1.3f);
        UUID caster = p.getUniqueId();
        int[] t = {0}, landed = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int k = t[0]++;
            if (k >= WindCone.WAVE_TICKS) {   // one last faint afterimage, then done
                crescent(w, base, f, rt, u, WindCone.waveRadius(WindCone.WAVE_TICKS - 1), half, 0.3);
                task.cancel();
                return;
            }
            crescent(w, base, f, rt, u, WindCone.waveRadius(k), half, 1.0);
            if (k > 0) crescent(w, base, f, rt, u, WindCone.waveRadius(k - 1), half, 0.45);
            Player q = Bukkit.getPlayer(caster);
            if (q == null || !q.isOnline()) return;
            List<LivingEntity> reached = new ArrayList<>();
            for (var e : reach.entrySet()) if (e.getValue() == k) reached.add(e.getKey());
            if (!reached.isEmpty()) landed[0] += blast(q, reached, look);
            if (k < WindCone.WAVE_TICKS - 1) return;
            // full range: clouds blowing out through the cone and a gust at the front
            Location mid = base.clone().add(f.clone().multiply(1.2)), front = base.clone().add(f.clone().multiply(WindCone.RANGE));
            for (int i = 0; i < 24; i++) {
                double ang = -half + 2 * half * rnd.nextDouble();
                Vector d = f.clone().multiply(Math.cos(ang)).add(rt.clone().multiply(Math.sin(ang))).add(new Vector(0, rnd.nextGaussian() * 0.1, 0));
                w.spawnParticle(Particle.CLOUD, mid, 0, d.getX(), d.getY(), d.getZ(), 0.45 + rnd.nextDouble() * 0.3);
            }
            w.spawnParticle(Particle.GUST, front, 2, 0.4, 0.25, 0.4, 0);
            w.playSound(front, Sound.ENTITY_WIND_CHARGE_WIND_BURST, SoundCategory.PLAYERS, 1.2f, 0.9f);
            w.playSound(front, Sound.ENTITY_BREEZE_WIND_BURST, SoundCategory.PLAYERS, 0.9f, 0.7f);
            if (landed[0] > 0) q.sendActionBar(Ui.mm("<#9EE6FF>🌬 <gray>Gust hit <white>" + landed[0] + (landed[0] == 1 ? " target" : " targets")));
        }, 0L, 1L);
    }

    /** One crescent of the wave at radius {@code r}; {@code strength} below 1 draws just a faint afterimage of the edge. */
    private void crescent(World w, Location base, Vector f, Vector rt, Vector u, double r, double half, double strength) {
        int pts = (int) Math.round(8 + 6 * r);
        double tilt = 0.35 * r / WindCone.RANGE;   // high on your left, low on your right; flatter close in
        boolean full = strength >= 1;
        for (int i = 0; i <= pts; i++) {
            double ang = -half + 2 * half * i / pts, h = -tilt * (ang / half);
            double taper = 1 - Math.abs(ang / half) * 0.5;   // thick in the middle, thin at the tips
            Location edge = arc(base, f, rt, u, r, ang, h);
            w.spawnParticle(Particle.DUST, edge, 1, 0.02, 0.02, 0.02, 0, new Particle.DustOptions(GALE_WHITE, (float) (1.8 * strength * taper + 0.4)));
            if (!full) continue;
            w.spawnParticle(Particle.DUST, arc(base, f, rt, u, r - 0.18, ang, h), 1, 0.03, 0.03, 0.03, 0, new Particle.DustOptions(GUST_CYAN, (float) (1.5 * taper + 0.3)));
            w.spawnParticle(Particle.DUST, arc(base, f, rt, u, r - 0.36, ang, h), 1, 0.03, 0.03, 0.03, 0, new Particle.DustOptions(DRAFT_TEAL, (float) (1.1 * taper + 0.3)));
            if (i % 5 == 0) w.spawnParticle(Particle.SWEEP_ATTACK, edge, 1, 0, 0, 0, 0);
        }
        if (!full) return;
        w.spawnParticle(Particle.SMALL_GUST, arc(base, f, rt, u, r, -half, tilt), 1, 0, 0, 0, 0);   // the tips
        w.spawnParticle(Particle.SMALL_GUST, arc(base, f, rt, u, r, half, -tilt), 1, 0, 0, 0, 0);
        w.spawnParticle(Particle.CLOUD, arc(base, f, rt, u, r, 0, 0), 2, 0.1, 0.05, 0.1, 0.02);
    }

    /** A point on the slash: {@code ang} radians round from straight ahead (negative = left), {@code h} blocks up or down. */
    private static Location arc(Location base, Vector f, Vector rt, Vector u, double r, double ang, double h) {
        return base.clone().add(f.clone().multiply(r * Math.cos(ang))).add(rt.clone().multiply(r * Math.sin(ang))).add(u.clone().multiply(h));
    }

    /** Each blast uses one of its 3 charges; the last one breaks it. Free where {@link #freeUseAt} says so. */
    private void useUp(Player p, ItemStack held) {
        if (freeUseAt.test(p.getLocation())) {   // test area: unlimited uses
            p.sendActionBar(Ui.mm("<#9EE6FF>🌬 <gray>Gale Bellows: <white>unlimited uses <gray>here"));
            return;
        }
        int left = uses(held) - 1;
        if (left <= 0) {
            p.getInventory().setItemInMainHand(null);
            p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, SoundCategory.PLAYERS, 1f, 0.8f);
            p.getWorld().spawnParticle(Particle.ITEM, p.getEyeLocation().add(p.getLocation().getDirection().multiply(0.6)), 14, 0.15, 0.15, 0.15, 0.06, held.clone());
            p.sendMessage(Ui.mm("<#9EE6FF>🌬 <gray>Your " + NAME + " <gray>blew itself apart. Find another in <white>crates <gray>or <white>Scrapyard supply drops<gray>."));
            return;
        }
        held.editMeta(m -> {
            m.getPersistentDataContainer().set(usesKey, PersistentDataType.INTEGER, left);
            m.lore(lore(left));
        });
        p.sendActionBar(Ui.mm("<#9EE6FF>🌬 <gray>Gale Bellows: <white>" + left + "<gray>/" + USES + " uses left"));
    }

    private void hitEffects(LivingEntity t) {
        Location chest = t.getLocation().add(0, t.getHeight() * 0.6, 0);
        World w = t.getWorld();
        w.spawnParticle(Particle.GUST, chest, 2, 0.2, 0.2, 0.2, 0);
        w.spawnParticle(Particle.SWEEP_ATTACK, chest, 2, 0.3, 0.2, 0.3, 0);
        w.spawnParticle(Particle.CLOUD, chest, 12, 0.2, 0.3, 0.2, 0.12);
        w.playSound(chest, Sound.ENTITY_WIND_CHARGE_WIND_BURST, SoundCategory.PLAYERS, 1.0f, 0.9f);
        w.playSound(chest, Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, SoundCategory.PLAYERS, 1.0f, 0.8f);
    }

    /** A streak of wind behind whoever got hit while they fly (0.75 s). */
    private void trail(LivingEntity t) {
        int[] n = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (n[0]++ >= 15 || !t.isValid() || t.isDead()) { task.cancel(); return; }
            Location at = t.getLocation().add(0, t.getHeight() * 0.5, 0);
            t.getWorld().spawnParticle(Particle.CLOUD, at, 2, 0.15, 0.25, 0.15, 0.01);
            t.getWorld().spawnParticle(Particle.DUST, at, 2, 0.2, 0.3, 0.2, 0, new Particle.DustOptions(GALE_WHITE, 1.4f));
        }, 1L, 1L);
    }

    // ------------------------------------------------------------------ housekeeping

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        readyAt.remove(id);
        lastPushed.remove(id);
        boosted.remove(id);
    }

    /** Every 30 s: two bellows with the same id online at once means a dupe - tell staff and log it. */
    private void dupeScan() {
        long now = System.currentTimeMillis();
        lastPushed.values().removeIf(t -> now - t > WindCone.JUGGLE_WINDOW_MS);   // mobs never "quit", so expire old entries
        Map<String, String> seen = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            for (ItemStack it : p.getInventory().getContents()) {
                if (!isBellows(it)) continue;
                String id = uid(it, uidKey);
                if (id == null) continue;
                String other = seen.putIfAbsent(id, p.getName());
                if (other != null) {
                    String msg = "Possible Gale Bellows dupe: id " + id.substring(0, 8) + " held by " + other + " and " + p.getName();
                    plugin.getLogger().warning(msg);
                    for (Player s : Bukkit.getOnlinePlayers()) if (s.hasPermission("alloy.staff")) s.sendMessage(Ui.mm("<red>⚠ <gray>" + msg));
                }
            }
        }
    }
}
