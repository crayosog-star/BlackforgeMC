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
 * Gale Bellows: a BlackForgeMC PvP item. Right-click to slash a short cone of wind (left to right) that hits everything about
 * 2 blocks in front of you for 2.5 hearts and throws it back at least 3 blocks. Cooldown comes from
 * {@code gale-bellows.cooldown-seconds} (0 = none). The cone maths lives in {@link WindCone}.
 *
 * Every hit goes through a normal damage event first, so spawn protection, claims and the "PvP only in the arena"
 * rule decide who can be pushed - the bellows never bypasses them.
 */
public final class GaleBellows implements Listener {
    public static final double DAMAGE = 5.0;                // 2.5 hearts before armour
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
    private final Random rnd = new Random();

    private final java.util.function.Predicate<Location> freeUseAt;   // where blasts have no cooldown and use no charge (the hub Arena)
    private final java.util.function.Predicate<Entity> alsoTarget;     // AlloyCore-tagged entities it may still hit (the Arena Test Dummy)

    public GaleBellows(AlloyCore plugin) { this(plugin, l -> false, e -> false); }

    /**
     * {@code freeUseAt}: where a blast has no cooldown and costs no charge, so the bellows never breaks there.
     * {@code alsoTarget}: AlloyCore's own entities that it may still hit (normally it never touches them).
     */
    public GaleBellows(AlloyCore plugin, java.util.function.Predicate<Location> freeUseAt, java.util.function.Predicate<Entity> alsoTarget) {
        this.plugin = plugin;
        this.freeUseAt = freeUseAt;
        this.alsoTarget = alsoTarget;
        this.enabled = plugin.getConfig().getBoolean("gale-bellows.enabled", true);
        this.cooldownTicks = Math.max(0, (int) Math.round(plugin.getConfig().getDouble("gale-bellows.cooldown-seconds", 0) * 20));
        // only once the BlackForgeMC resource pack is live: without it the model id renders as the purple/black missing model
        this.customModel = plugin.getConfig().getBoolean("gale-bellows.custom-model", false);
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
                Ui.mm("<gray>Right-click to slash a gust of wind"),
                Ui.mm("<gray>Hits everyone <white>2 blocks <gray>in front of you"),
                Ui.mm("<gray>for <white>2.5 hearts <gray>and blows them back"),
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
        if (customModel) return;
        for (ItemStack it : p.getInventory().getContents())
            if (isBellows(it) && it.getItemMeta().hasItemModel())
                it.editMeta(m -> { m.setItemModel(null); m.setEnchantmentGlintOverride(true); });
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
        List<LivingEntity> hit = new ArrayList<>();
        for (Entity en : p.getNearbyEntities(3, 3, 3)) {
            if (!(en instanceof LivingEntity t) || !canTarget(p, t)) continue;
            var box = t.getBoundingBox();
            double dx = box.getCenterX() - eye.getX(), dz = box.getCenterZ() - eye.getZ();
            if (!WindCone.hits(look.getX(), look.getY(), look.getZ(), dx, dz, box.getMinY() - eye.getY(), box.getMaxY() - eye.getY())) continue;
            if (blocked(eye, t)) continue;
            if (!alsoTarget.test(t) && !allowed(p, t)) continue;   // the dummy counts every damage event, so no probe for it
            hit.add(t);
        }

        boolean noCooldown = cooldownTicks <= 0 || freeUseAt.test(p.getLocation());   // test area: no cooldown, no charge used
        if (!noCooldown) {
            readyAt.put(p.getUniqueId(), now + cooldownTicks * 50L);
            p.setCooldown(held, cooldownTicks);
        }
        useUp(p, held);
        castEffects(p, eye, look);   // one left-to-right slash; the gust lands as it finishes
        UUID caster = p.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player q = Bukkit.getPlayer(caster);
            if (q != null && q.isOnline()) blast(q, hit, look);
        }, SLASH_TICKS);

        if (noCooldown) return;   // nothing to refill
        // a quiet "ready" cue when the bellows refills, to the owner only
        UUID id = p.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player q = Bukkit.getPlayer(id);
            if (q != null && readyAt.getOrDefault(id, 0L) <= System.currentTimeMillis() + 50) q.playSound(q, Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.6f, 1.2f);
        }, cooldownTicks);
    }

    /**
     * The gust at the end of the slash: throws back and deals the 2.5-heart hit to everyone who was in the cone when
     * the bellows was swung. Anyone who has since died, left or changed world is skipped.
     */
    private void blast(Player p, List<LivingEntity> hit, Vector look) {
        long now = System.currentTimeMillis();
        List<LivingEntity> landed = new ArrayList<>();
        for (LivingEntity t : hit) {
            if (!t.isValid() || t.isDead() || t.getWorld() != p.getWorld()) continue;
            landed.add(t);
            Long last = lastPushed.get(t.getUniqueId());
            boolean juggled = last != null && now - last < WindCone.JUGGLE_WINDOW_MS;
            lastPushed.put(t.getUniqueId(), now);
            AttributeInstance kb = t.getAttribute(Attribute.KNOCKBACK_RESISTANCE);
            double[] v = WindCone.push(t.getX() - p.getX(), t.getZ() - p.getZ(), look.getX(), look.getZ(),
                    kb == null ? 0 : kb.getValue(), !t.isOnGround(), juggled);
            // the hit: a real damage event, so kill credit, combat tags and the arena rules all apply
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
        if (!landed.isEmpty()) p.sendActionBar(Ui.mm("<#9EE6FF>🌬 <gray>Gust hit <white>" + landed.size() + (landed.size() == 1 ? " target" : " targets")));
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

    /** The slash sweeps left to right over this many ticks; the gust and the hit land as it finishes (0.15 s). */
    static final int SLASH_TICKS = 3;

    /**
     * One big diagonal slash of air, sweeping from your left to your right (high on the left, low on the right) -
     * a thick crescent with a bright white cutting edge, sweep marks along it and an afterimage that hangs for a
     * moment - then a shockwave of cloud and gust bursts forward as the hit lands. The arc follows the hit cone
     * ({@link WindCone#RANGE}, {@link WindCone#HALF_ANGLE_DEG}), so what you see is roughly what you hit.
     */
    private void castEffects(Player p, Location eye, Vector look) {
        World w = p.getWorld();
        p.swingMainHand();
        Vector fwd = look.clone().normalize();
        Vector right = new Vector(-fwd.getZ(), 0, fwd.getX());   // facing +Z (south), this is -X (west): your right
        if (right.lengthSquared() < 1e-6) right = new Vector(1, 0, 0);
        right.normalize();
        Vector up = right.clone().crossProduct(fwd).normalize();
        Location base = eye.clone().add(0, -0.3, 0);
        double r = WindCone.RANGE * 0.85, half = Math.toRadians(WindCone.HALF_ANGLE_DEG + 20), tilt = 0.7;
        double[] band = {-0.35, 0, 0.35};   // the crescent's thickness: inner, middle, outer (cutting edge)
        Vector f = fwd, rt = right, u = up;
        w.playSound(eye, Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 1.2f, 0.7f);
        w.playSound(eye, Sound.ITEM_TRIDENT_RIPTIDE_1, SoundCategory.PLAYERS, 0.7f, 1.6f);
        w.playSound(eye, Sound.ENTITY_BREEZE_SHOOT, SoundCategory.PLAYERS, 0.8f, 1.2f);
        int[] t = {0};
        int linger = 4;   // afterimage ticks after the sweep
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int k = t[0]++;
            if (k < SLASH_TICKS) {
                // this tick's share of the arc, left -> right; earlier parts are redrawn as the trail
                int pts = 16;
                for (int i = 0; i <= pts; i++) {
                    double s = (k + i / (double) pts) / SLASH_TICKS;   // 0..1 along the whole sweep
                    double ang = -half + 2 * half * s;               // negative = your left
                    double h = -tilt * (ang / half);                  // high on the left, low on the right
                    for (int b = 0; b < band.length; b++) {
                        Location at = arc(base, f, rt, u, r + band[b], ang, h);
                        Color c = b == 2 ? GALE_WHITE : b == 1 ? GUST_CYAN : DRAFT_TEAL;
                        w.spawnParticle(Particle.DUST, at, 2, 0.04, 0.04, 0.04, 0, new Particle.DustOptions(c, b == 2 ? 2.0f : 1.6f));
                    }
                    if (i % 4 == 0) w.spawnParticle(Particle.SWEEP_ATTACK, arc(base, f, rt, u, r, ang, h), 1, 0, 0, 0, 0);
                    if (i == pts) {   // the leading tip
                        Location tip = arc(base, f, rt, u, r + 0.2, ang, h);
                        w.spawnParticle(Particle.SMALL_GUST, tip, 2, 0.1, 0.1, 0.1, 0);
                        w.spawnParticle(Particle.CLOUD, tip, 3, 0.05, 0.05, 0.05, 0.03);
                    }
                }
                return;
            }
            if (k == SLASH_TICKS) {
                // the shockwave: clouds blasting outward through the cone (count 0 makes the offset a direction)
                Location mid = base.clone().add(f.clone().multiply(0.8));
                for (int i = 0; i < 40; i++) {
                    double ang = -half + 2 * half * rnd.nextDouble();
                    Vector d = f.clone().multiply(Math.cos(ang)).add(rt.clone().multiply(Math.sin(ang))).add(new Vector(0, rnd.nextGaussian() * 0.12, 0));
                    w.spawnParticle(Particle.CLOUD, mid, 0, d.getX(), d.getY(), d.getZ(), 0.55 + rnd.nextDouble() * 0.35);
                }
                w.spawnParticle(Particle.GUST, base.clone().add(f.clone().multiply(1.4)), 2, 0.3, 0.2, 0.3, 0);
                w.spawnParticle(Particle.GUST, base.clone().add(f.clone().multiply(2.4)), 3, 0.5, 0.3, 0.5, 0);
                w.playSound(eye, Sound.ENTITY_WIND_CHARGE_WIND_BURST, SoundCategory.PLAYERS, 1.3f, 0.9f);
                w.playSound(eye, Sound.ENTITY_BREEZE_WIND_BURST, SoundCategory.PLAYERS, 1.0f, 0.6f);
            }
            if (k >= SLASH_TICKS + linger) { task.cancel(); return; }
            // afterimage: the whole crescent hangs for a moment, thinning out
            int pts = 24 - (k - SLASH_TICKS) * 5;
            for (int i = 0; i <= pts; i++) {
                double ang = -half + 2 * half * i / pts, h = -tilt * (ang / half);
                w.spawnParticle(Particle.DUST, arc(base, f, rt, u, r + 0.35, ang, h), 1, 0.02, 0.02, 0.02, 0, new Particle.DustOptions(GUST_CYAN, 1.0f));
            }
        }, 0L, 1L);
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
