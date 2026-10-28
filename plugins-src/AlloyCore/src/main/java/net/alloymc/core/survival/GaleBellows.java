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
 * Gale Bellows: a BlackForgeMC PvP item (Survival only). Right-click to blast a short cone of wind that hits everything about
 * 2 blocks in front of you for 1 heart and shoves it back. 10 second cooldown. The cone maths lives in {@link WindCone}.
 *
 * Every hit goes through a normal damage event first, so spawn protection, claims and the "PvP only in the arena"
 * rule decide who can be pushed - the bellows never bypasses them.
 */
public final class GaleBellows implements Listener {
    public static final int COOLDOWN_TICKS = 200;          // 10 s
    public static final double DAMAGE = 2.0;                // 1 heart
    public static final int USES = 3;                       // blasts before it breaks

    // palette (see the plan's visual guide)
    private static final Color GALE_WHITE = Color.fromRGB(0xEAF6FF), GUST_CYAN = Color.fromRGB(0x9EE6FF), DRAFT_TEAL = Color.fromRGB(0x4FC3D9);
    public static final String NAME = "<gradient:#EAF6FF:#4FC3D9><bold>Gale Bellows</bold></gradient>";

    private final AlloyCore plugin;
    private final boolean enabled, customModel;
    private final NamespacedKey tagKey, uidKey, modelKey, usesKey;
    private final java.io.File pendingFile;
    private final Set<UUID> pending = new HashSet<>();   // owed a bellows the next time they join (given while offline)
    private final Map<UUID, Long> readyAt = new HashMap<>();       // caster -> when the bellows can fire again
    private final Map<UUID, Long> lastPushed = new HashMap<>();    // target -> when they were last pushed (juggle rule)
    private final Random rnd = new Random();

    private final java.util.function.Predicate<Location> freeUseAt;   // where blasts have no cooldown and use no charge (the hub Arena)

    public GaleBellows(AlloyCore plugin) { this(plugin, l -> false); }

    /** {@code freeUseAt}: where a blast has no cooldown and costs no charge, so the bellows never breaks there. */
    public GaleBellows(AlloyCore plugin, java.util.function.Predicate<Location> freeUseAt) {
        this.plugin = plugin;
        this.freeUseAt = freeUseAt;
        this.enabled = plugin.getConfig().getBoolean("gale-bellows.enabled", true);
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
            UseCooldownComponent cd = m.getUseCooldown();
            cd.setCooldownSeconds(COOLDOWN_TICKS / 20f);
            cd.setCooldownGroup(modelKey);   // only the bellows greys out, not every breeze rod
            m.setUseCooldown(cd);
            m.getPersistentDataContainer().set(tagKey, PersistentDataType.BYTE, (byte) 1);
            m.getPersistentDataContainer().set(uidKey, PersistentDataType.STRING, UUID.randomUUID().toString());
            m.getPersistentDataContainer().set(usesKey, PersistentDataType.INTEGER, USES);
        });
        return it;
    }

    private static List<net.kyori.adventure.text.Component> lore(int uses) {
        return List.of(
                Ui.mm("<gray>Right-click to blast a gust of wind"),
                Ui.mm("<gray>Hits everyone <white>2 blocks <gray>in front of you"),
                Ui.mm("<gray>for <white>1 heart <gray>and blows them back"),
                Ui.mm(""),
                Ui.mm("<gray>Cooldown: <white>10s"),
                Ui.mm("<gray>Uses left: " + (uses <= 1 ? "<red>" : "<white>") + uses + "<gray>/" + USES),
                Ui.mm("<dark_gray>BlackForgeMC PvP item"));
    }

    private int uses(ItemStack it) {
        Integer u = it.getItemMeta().getPersistentDataContainer().get(usesKey, PersistentDataType.INTEGER);
        return u == null ? USES : u;   // bellows from before uses existed start full
    }

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
            if (!allowed(p, t)) continue;
            hit.add(t);
        }

        boolean free = freeUseAt.test(p.getLocation());   // test area: no cooldown, no charge used
        if (!free) {
            readyAt.put(p.getUniqueId(), now + COOLDOWN_TICKS * 50L);
            p.setCooldown(held, COOLDOWN_TICKS);
        }
        useUp(p, held);
        castEffects(p, eye, look);   // three quick slashes; the gust lands on the last one
        UUID caster = p.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player q = Bukkit.getPlayer(caster);
            if (q != null && q.isOnline()) blast(q, hit, look);
        }, SLASH_TICKS);

        if (free) return;   // no cooldown, so nothing to refill
        // a quiet "ready" cue when the bellows refills, to the owner only
        UUID id = p.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player q = Bukkit.getPlayer(id);
            if (q != null && readyAt.getOrDefault(id, 0L) <= System.currentTimeMillis() + 50) q.playSound(q, Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.6f, 1.2f);
        }, COOLDOWN_TICKS);
    }

    /**
     * The gust at the end of the slashes: pushes back and deals the 1-heart hit to everyone who was in the cone when
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
            // the 1-heart hit: a real damage event, so kill credit, combat tags and the arena rules all apply
            t.damage(DAMAGE, p);
            // vanilla adds its own knockback for that hit, so set ours on the next tick to replace it
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!t.isValid() || t.isDead()) return;
                double vy = v[1] > 0 ? v[1] : t.getVelocity().getY();
                t.setVelocity(new Vector(v[0], vy, v[2]));
            });
            hitEffects(t);
            if (t instanceof Player tp) tp.sendActionBar(Ui.mm("<#9EE6FF>🌬 <gray>Blown back by <white>" + p.getName()));
        }
        if (!landed.isEmpty()) p.sendActionBar(Ui.mm("<#9EE6FF>🌬 <gray>Gust hit <white>" + landed.size() + (landed.size() == 1 ? " target" : " targets")));
    }

    /** Players and hostile mobs only - never pets, villagers, armor stands, displays or anything AlloyCore tags (robots etc). */
    private boolean canTarget(Player caster, LivingEntity t) {
        if (t == caster || t.isDead() || t.isInvulnerable() || t instanceof ArmorStand) return false;
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

    /** Ticks per slash; three slashes (right, left, right), then the gust. */
    static final int SLASH_STROKE = 2, SLASH_TICKS = 3 * SLASH_STROKE;

    /**
     * Three quick slashes of air across the cone - sweeping right, then left, then right, each tilted like a blade
     * cut - then the gust cloud bursts forward as the push lands. The arcs run along the edge of the hit cone
     * ({@link WindCone#RANGE}, {@link WindCone#HALF_ANGLE_DEG}), so what you see is what you hit.
     */
    private void castEffects(Player p, Location eye, Vector look) {
        World w = p.getWorld();
        Vector fwd = look.clone().normalize();
        Vector right = new Vector(-fwd.getZ(), 0, fwd.getX());   // facing +Z (south), this is -X (west): your right
        if (right.lengthSquared() < 1e-6) right = new Vector(1, 0, 0);
        right.normalize();
        Vector up = right.clone().crossProduct(fwd).normalize();
        Location base = eye.clone().add(0, -0.25, 0);
        double r = WindCone.RANGE * 0.8, half = Math.toRadians(WindCone.HALF_ANGLE_DEG + 5);
        double[] tilt = {0.45, -0.45, 0.3};   // high-to-low, low-to-high, high-to-low across the swing
        Vector f = fwd, rt = right, u = up;
        int[] t = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int k = t[0]++;
            if (k < SLASH_TICKS) {
                int stroke = k / SLASH_STROKE, part = k % SLASH_STROKE;
                int dir = stroke % 2 == 0 ? 1 : -1;   // 1 sweeps left -> right
                // this tick draws its share of the arc; the leading edge is bright, the tail fades to teal
                int pts = 9;
                for (int i = 0; i <= pts; i++) {
                    double s = (part + i / (double) pts) / SLASH_STROKE;   // 0..1 along the whole stroke
                    double a = dir * (-half + 2 * half * s);
                    double h = tilt[stroke] * dir * (a / half) * -1;
                    Location at = base.clone().add(f.clone().multiply(r * Math.cos(a))).add(rt.clone().multiply(r * Math.sin(a))).add(u.clone().multiply(h));
                    Color c = i > pts - 3 ? GALE_WHITE : i > pts / 2 ? GUST_CYAN : DRAFT_TEAL;
                    w.spawnParticle(Particle.DUST, at, 1, 0.03, 0.03, 0.03, 0, new Particle.DustOptions(c, 1.3f));
                    if (i == pts) w.spawnParticle(Particle.SMALL_GUST, at, 1, 0, 0, 0, 0);
                    if (i == pts / 2 && part == 0) w.spawnParticle(Particle.SWEEP_ATTACK, at, 1, 0, 0, 0, 0);
                }
                if (part == 0) {
                    w.playSound(eye, Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.8f, 1.3f + stroke * 0.2f);
                    w.playSound(eye, Sound.ENTITY_BREEZE_SHOOT, SoundCategory.PLAYERS, 0.35f, 1.6f + stroke * 0.1f);
                }
                return;
            }
            task.cancel();
            // the gust: a cloud burst forward as the push lands (count 0 makes the offset a direction)
            for (int i = 0; i < 16; i++) {
                Vector v = f.clone().add(new Vector(rnd.nextGaussian() * 0.2, rnd.nextGaussian() * 0.1, rnd.nextGaussian() * 0.2));
                w.spawnParticle(Particle.CLOUD, base.clone().add(f.clone().multiply(0.6)), 0, v.getX(), v.getY(), v.getZ(), 0.4);
            }
            w.spawnParticle(Particle.GUST, base.clone().add(f.clone().multiply(1.6)), 1, 0, 0, 0, 0);
            w.playSound(eye, Sound.ENTITY_WIND_CHARGE_WIND_BURST, SoundCategory.PLAYERS, 1.0f, 1.2f);
            w.playSound(eye, Sound.ENTITY_BREEZE_WIND_BURST, SoundCategory.PLAYERS, 0.8f, 0.8f);
        }, 0L, 1L);
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
        t.getWorld().spawnParticle(Particle.GUST, chest, 1, 0, 0, 0, 0);
        t.getWorld().playSound(chest, Sound.ENTITY_WIND_CHARGE_WIND_BURST, SoundCategory.PLAYERS, 0.7f, 1.0f);
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
