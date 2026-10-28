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

    public GaleBellows(AlloyCore plugin) {
        this.plugin = plugin;
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
            if (!allowed(p, t)) continue;
            hit.add(t);
        }

        readyAt.put(p.getUniqueId(), now + COOLDOWN_TICKS * 50L);
        p.setCooldown(held, COOLDOWN_TICKS);
        castEffects(p, eye, look);
        useUp(p, held);

        for (LivingEntity t : hit) {
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
        if (!hit.isEmpty()) p.sendActionBar(Ui.mm("<#9EE6FF>🌬 <gray>Gust hit <white>" + hit.size() + (hit.size() == 1 ? " target" : " targets")));

        // a quiet "ready" cue when the bellows refills, to the owner only
        UUID id = p.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player q = Bukkit.getPlayer(id);
            if (q != null && readyAt.getOrDefault(id, 0L) <= System.currentTimeMillis() + 50) q.playSound(q, Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.6f, 1.2f);
        }, COOLDOWN_TICKS);
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

    private void castEffects(Player p, Location eye, Vector look) {
        World w = p.getWorld();
        Vector side = new Vector(-look.getZ(), 0, look.getX());
        if (side.lengthSquared() < 1e-6) side = new Vector(1, 0, 0);
        side.normalize();
        Location base = eye.clone().add(0, -0.25, 0);
        // three rays fanning out across the cone, streaked with small gusts
        for (double spread : new double[] {-0.45, 0, 0.45}) {
            Vector ray = look.clone().add(side.clone().multiply(spread)).normalize();
            for (double d : new double[] {0.5, 1.0, 1.5, 2.0}) {
                Location at = base.clone().add(ray.clone().multiply(d));
                w.spawnParticle(Particle.SMALL_GUST, at, 1, 0.05, 0.05, 0.05, 0);
                Color c = d < 1.0 ? GALE_WHITE : d < 1.8 ? GUST_CYAN : DRAFT_TEAL;
                w.spawnParticle(Particle.DUST, at, 2, 0.12, 0.12, 0.12, 0, new Particle.DustOptions(c, 1.2f));
            }
        }
        // puffs fired forward (count 0 makes the offset a direction)
        for (int i = 0; i < 12; i++) {
            Vector v = look.clone().add(new Vector(rnd.nextGaussian() * 0.15, rnd.nextGaussian() * 0.1, rnd.nextGaussian() * 0.15));
            w.spawnParticle(Particle.CLOUD, base.clone().add(look.clone().multiply(0.4)), 0, v.getX(), v.getY(), v.getZ(), 0.35);
        }
        w.spawnParticle(Particle.CLOUD, base.clone().add(look.clone().multiply(0.3)), 4, 0.08, 0.08, 0.08, 0.01);   // the bellows' breath
        w.playSound(eye, Sound.ENTITY_WIND_CHARGE_WIND_BURST, SoundCategory.PLAYERS, 1.0f, 1.2f);
        w.playSound(eye, Sound.ENTITY_BREEZE_WIND_BURST, SoundCategory.PLAYERS, 0.8f, 0.8f);
        w.playSound(eye, Sound.ENTITY_ENDER_DRAGON_FLAP, SoundCategory.PLAYERS, 0.4f, 1.6f);
    }

    /** Each blast uses one of its 3 charges; the last one breaks it. */
    private void useUp(Player p, ItemStack held) {
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
