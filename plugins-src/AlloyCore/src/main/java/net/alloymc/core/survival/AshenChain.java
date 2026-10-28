package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import org.bukkit.*;
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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.function.Predicate;

/**
 * Ashen Chain: a BlackForgeMC ability item with a dark, ashen look - a rusted chain wreathed in soul fire.
 * Right-click a block (up to 24 away) and the chain bites into it and drags you there. Right-click a player or hostile
 * mob (up to 12 away) and it hooks them for 1 heart and drags them to about 2 blocks in front of you. Sneak to let go
 * of a block early. No fall damage while you're being reeled in, or for 2 s after. The pull maths lives in {@link ChainPull}.
 *
 * Hooking a target goes through a normal damage event first, so spawn protection and PvP rules decide who can be
 * dragged. Only where {@code allowedAt} says (the hub Arena for now). Never used up.
 */
public final class AshenChain implements Listener {
    public static final String NAME = "<gradient:#8C8580:#C2541B:#6E0F0F><bold>Ashen Chain</bold></gradient>";
    public static final double DAMAGE = 2.0;                 // 1 heart
    private static final int THROW_SPEED = 4;                // blocks per tick the hook flies
    private static final int YANK_DRAW_TICKS = 8;            // chain stays drawn to a dragged target this long
    private static final long NO_FALL_MS = 2000;             // after a reel ends

    static final Color ASH = Color.fromRGB(0x4A4542), RUST = Color.fromRGB(0x7A3B1E), EMBER = Color.fromRGB(0xE0702A),
            BONE = Color.fromRGB(0xB8B0A2), BLOOD = Color.fromRGB(0x6E0F0F);

    private final AlloyCore plugin;
    private final boolean enabled;
    private final int cooldownTicks;
    private final Predicate<Location> allowedAt;
    private final Predicate<Entity> alsoTarget;   // AlloyCore entities it may still hook (the Arena Test Dummy)
    private final NamespacedKey tagKey;
    private final Map<UUID, BukkitTask> active = new HashMap<>();   // caster -> their chain in flight or reeling
    private final Map<UUID, Long> readyAt = new HashMap<>();
    private final Map<UUID, Long> noFallUntil = new HashMap<>();
    private final Random rnd = new Random();

    public AshenChain(AlloyCore plugin, Predicate<Location> allowedAt, Predicate<Entity> alsoTarget) {
        this.plugin = plugin;
        this.allowedAt = allowedAt;
        this.alsoTarget = alsoTarget;
        this.enabled = plugin.getConfig().getBoolean("ashen-chain.enabled", true);
        this.cooldownTicks = Math.max(0, (int) Math.round(plugin.getConfig().getDouble("ashen-chain.cooldown-seconds", 3) * 20));
        this.tagKey = new NamespacedKey(plugin, "ashen_chain");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    // ------------------------------------------------------------------ the item

    public ItemStack item() {
        ItemStack it = new ItemStack(Material.IRON_CHAIN);
        it.editMeta(m -> {
            m.displayName(Ui.mm(NAME));
            m.lore(List.of(
                    Ui.mm("<dark_gray><italic>Forged from the ash of a fallen knight."),
                    Ui.mm("<dark_gray><italic>It drags the living into the dark."),
                    Ui.mm(""),
                    Ui.mm("<gray>Right-click a <white>block <gray>to drag yourself to it"),
                    Ui.mm("<gray>Right-click a <white>player <gray>to drag them to you <dark_gray>(1❤)"),
                    Ui.mm("<gray>Reach: <white>" + (int) ChainPull.GRAPPLE_RANGE + " <gray>blocks, <white>" + (int) ChainPull.YANK_RANGE + " <gray>at players"),
                    Ui.mm("<gray>Sneak to let go · no fall damage while chained"),
                    Ui.mm(cooldownTicks > 0 ? "<gray>Cooldown: <white>" + String.format(Locale.ROOT, "%.0f", cooldownTicks / 20.0) + "s" : "<gray>Cooldown: <white>none"),
                    Ui.mm(""),
                    Ui.mm("<dark_gray>BlackForgeMC ability")));
            m.setMaxStackSize(1);
            m.setEnchantmentGlintOverride(true);
            m.getPersistentDataContainer().set(tagKey, PersistentDataType.BYTE, (byte) 1);
        });
        return it;
    }

    public boolean isChain(ItemStack it) {
        return it != null && it.getType() == Material.IRON_CHAIN && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(tagKey, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------ casting

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        if (!isChain(p.getInventory().getItemInMainHand())) return;
        Block clicked = e.getClickedBlock();
        if (a == Action.RIGHT_CLICK_BLOCK && clicked != null && clicked.getType().isInteractable() && !p.isSneaking()) return;
        e.setCancelled(true);   // never place it as a chain block
        if (p.getGameMode() == GameMode.SPECTATOR) return;
        if (!enabled) { p.sendActionBar(Ui.mm("<gray>The Ashen Chain isn't switched on yet.")); return; }
        if (!allowedAt.test(p.getLocation())) { p.sendActionBar(Ui.mm("<gray>The " + NAME + " <gray>only works in the <white>Arena <gray>for now.")); return; }
        if (active.containsKey(p.getUniqueId())) return;   // one chain at a time
        long now = System.currentTimeMillis(), ready = readyAt.getOrDefault(p.getUniqueId(), 0L);
        if (ready > now) {
            p.sendActionBar(Ui.mm("<#C2541B>⛓ <gray>The embers are still cooling... <white>" + String.format(Locale.ROOT, "%.1f", (ready - now) / 1000.0) + "s"));
            return;
        }
        if (cooldownTicks > 0) {
            readyAt.put(p.getUniqueId(), now + cooldownTicks * 50L);
            p.setCooldown(Material.IRON_CHAIN, cooldownTicks);
        }
        cast(p);
    }

    private void cast(Player p) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        World w = p.getWorld();
        p.swingMainHand();

        RayTraceResult blockHit = w.rayTraceBlocks(eye, dir, ChainPull.GRAPPLE_RANGE, FluidCollisionMode.NEVER, true);
        double blockDist = blockHit == null ? Double.MAX_VALUE : blockHit.getHitPosition().distance(eye.toVector());
        RayTraceResult entHit = w.rayTraceEntities(eye, dir, ChainPull.YANK_RANGE, 0.5,
                en -> en instanceof LivingEntity le && canTarget(p, le));
        LivingEntity target = null;
        if (entHit != null && entHit.getHitEntity() instanceof LivingEntity le
                && entHit.getHitPosition().distance(eye.toVector()) < blockDist) target = le;   // never through a wall

        throwSounds(p);
        if (target != null) {
            if (!alsoTarget.test(target) && !allowed(p, target)) {   // the dummy counts every damage event: no probe for it
                p.sendActionBar(Ui.mm("<#C2541B>⛓ <gray>The chain won't bite here."));
                fly(p, target.getLocation().add(0, target.getHeight() * 0.6, 0), () -> slack(p));
                return;
            }
            LivingEntity t = target;
            fly(p, t.getLocation().add(0, t.getHeight() * 0.6, 0), () -> hook(p, t));
        } else if (blockHit != null) {
            Location anchor = blockHit.getHitPosition().toLocation(w);
            Block b = blockHit.getHitBlock();
            fly(p, anchor, () -> anchor(p, anchor, b));
        } else {
            fly(p, eye.clone().add(dir.multiply(ChainPull.YANK_RANGE)), () -> slack(p));   // into the void: it falls slack
        }
    }

    /** The hook flies out from the hand to {@code to} at {@link #THROW_SPEED}, drawing the chain behind it, then {@code then}. */
    private void fly(Player p, Location to, Runnable then) {
        UUID id = p.getUniqueId();
        int ticks = Math.max(1, (int) Math.ceil(hand(p).distance(to) / THROW_SPEED));
        int[] n = {0};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!p.isOnline() || p.isDead() || p.getWorld() != to.getWorld()) { stop(id); return; }
            int k = ++n[0];
            Location from = hand(p);
            Vector d = to.toVector().subtract(from.toVector());
            Location head = from.clone().add(d.multiply(Math.min(1.0, k / (double) ticks)));
            drawChain(from, head);
            hookHead(head);
            if (k >= ticks) { stop(id); then.run(); }
        }, 0L, 1L);
        active.put(id, task);
    }

    // ------------------------------------------------------------------ hooking a target

    private void hook(Player p, LivingEntity t) {
        if (!t.isValid() || t.isDead() || t.getWorld() != p.getWorld()) { slack(p); return; }
        double dist = t.getLocation().distance(p.getLocation());
        if (dist > ChainPull.YANK_RANGE + 4) { slack(p); return; }   // they got away while the hook flew
        World w = t.getWorld();
        Location chest = t.getLocation().add(0, t.getHeight() * 0.6, 0);
        boolean dummy = alsoTarget.test(t);

        t.damage(DAMAGE, p);   // a real damage event: kill credit, combat tags and arena rules all apply
        if (!dummy) {
            double[] v = ChainPull.yank(t.getX() - p.getX(), t.getZ() - p.getZ(), dist);
            // vanilla knocks them away from us for that hit, so set the drag on the next tick to replace it
            Bukkit.getScheduler().runTask(plugin, () -> { if (t.isValid() && !t.isDead()) t.setVelocity(new Vector(v[0], v[1], v[2])); });
        }

        // the soul is torn loose: a burst of souls and ash, a wail, and the chain stays taut while they're dragged in
        w.spawnParticle(Particle.SCULK_SOUL, chest, 8, 0.3, 0.4, 0.3, 0.05);
        w.spawnParticle(Particle.SOUL, chest, 6, 0.3, 0.4, 0.3, 0.02);
        w.spawnParticle(Particle.SOUL_FIRE_FLAME, chest, 14, 0.25, 0.35, 0.25, 0.06);
        w.spawnParticle(Particle.WHITE_ASH, chest, 30, 0.5, 0.6, 0.5, 0.02);
        w.spawnParticle(Particle.DUST, chest, 10, 0.3, 0.4, 0.3, 0, new Particle.DustOptions(BLOOD, 1.4f));
        w.playSound(chest, Sound.PARTICLE_SOUL_ESCAPE, SoundCategory.PLAYERS, 2.0f, 0.7f);
        w.playSound(chest, Sound.ENTITY_WITHER_SHOOT, SoundCategory.PLAYERS, 0.6f, 0.5f);
        w.playSound(chest, Sound.ITEM_ARMOR_EQUIP_IRON, SoundCategory.PLAYERS, 1.2f, 0.6f);
        w.playSound(chest, Sound.ENTITY_PLAYER_ATTACK_STRONG, SoundCategory.PLAYERS, 1.0f, 0.7f);
        if (t instanceof Player tp) {
            tp.sendActionBar(Ui.mm("<#C2541B>⛓ <gray>Dragged into the dark by <white>" + p.getName()));
            tp.playSound(tp, Sound.AMBIENT_SOUL_SAND_VALLEY_MOOD, SoundCategory.PLAYERS, 0.8f, 1.2f);
        }
        p.sendActionBar(Ui.mm("<#C2541B>⛓ <gray>Hooked <white>" + (t instanceof Player tp ? tp.getName() : "a " + t.getType().name().toLowerCase(Locale.ROOT).replace('_', ' '))));

        UUID id = p.getUniqueId();
        int[] n = {0};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (n[0]++ >= YANK_DRAW_TICKS || !p.isOnline() || !t.isValid() || t.getWorld() != p.getWorld()) { stop(id); return; }
            Location c = t.getLocation().add(0, t.getHeight() * 0.6, 0);
            drawChain(hand(p), c);
            w.spawnParticle(Particle.WHITE_ASH, c, 4, 0.2, 0.3, 0.2, 0.01);
            w.spawnParticle(Particle.SMALL_FLAME, c, 1, 0.15, 0.2, 0.15, 0.01);
        }, 1L, 1L);
        active.put(id, task);
    }

    // ------------------------------------------------------------------ dragging yourself

    private void anchor(Player p, Location at, Block b) {
        World w = at.getWorld();
        // the hook bites: sparks, ash, a shower of the block, a metal clank
        w.spawnParticle(Particle.BLOCK, at, 16, 0.2, 0.2, 0.2, 0.1, b.getBlockData());
        w.spawnParticle(Particle.SOUL_FIRE_FLAME, at, 10, 0.15, 0.15, 0.15, 0.05);
        w.spawnParticle(Particle.WHITE_ASH, at, 20, 0.4, 0.4, 0.4, 0.02);
        w.spawnParticle(Particle.CRIT, at, 8, 0.1, 0.1, 0.1, 0.3);
        w.playSound(at, Sound.BLOCK_ANVIL_LAND, SoundCategory.PLAYERS, 0.5f, 1.7f);
        w.playSound(at, Sound.ITEM_ARMOR_EQUIP_IRON, SoundCategory.PLAYERS, 1.2f, 0.7f);
        w.playSound(p.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_1, SoundCategory.PLAYERS, 0.7f, 0.6f);

        UUID id = p.getUniqueId();
        int[] n = {0};
        double[] lastDist = {Double.MAX_VALUE};
        int[] stuck = {0};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!p.isOnline() || p.isDead() || p.getWorld() != w) { release(p, false); return; }
            noFallUntil.put(id, System.currentTimeMillis() + NO_FALL_MS);
            Location body = p.getLocation().add(0, 1.0, 0);
            Vector off = at.toVector().subtract(body.toVector());
            double dist = off.length();
            int k = n[0]++;
            if (ChainPull.arrived(dist)) { release(p, true); return; }
            if (p.isSneaking() && k > 2) { release(p, false); return; }
            if (k >= ChainPull.REEL_MAX_TICKS) { release(p, false); return; }
            stuck[0] = lastDist[0] - dist < 0.15 ? stuck[0] + 1 : 0;   // snagged on something
            if (stuck[0] >= 6) { release(p, false); return; }
            lastDist[0] = dist;
            double[] v = ChainPull.reel(off.getX(), off.getY(), off.getZ());
            p.setVelocity(new Vector(v[0], v[1], v[2]));
            p.setFallDistance(0);
            drawChain(hand(p), at);
            // you trail embers and ash as you're dragged through the air
            Location trail = p.getLocation().add(0, 0.9, 0);
            w.spawnParticle(Particle.WHITE_ASH, trail, 6, 0.3, 0.5, 0.3, 0.01);
            w.spawnParticle(Particle.SMALL_FLAME, trail, 2, 0.2, 0.4, 0.2, 0.01);
            w.spawnParticle(Particle.DUST, trail, 2, 0.25, 0.45, 0.25, 0, new Particle.DustOptions(ASH, 1.6f));
            if (k % 4 == 0) w.playSound(trail, Sound.ITEM_ARMOR_EQUIP_IRON, SoundCategory.PLAYERS, 0.4f, 0.5f + k * 0.03f);
        }, 1L, 1L);
        active.put(id, task);
    }

    /** Let go of the anchor. {@code arrived}: hop up over the ledge and land in a puff of ash, like stepping out of a bonfire. */
    private void release(Player p, boolean arrived) {
        stop(p.getUniqueId());
        if (!p.isOnline()) return;
        noFallUntil.put(p.getUniqueId(), System.currentTimeMillis() + NO_FALL_MS);
        Location l = p.getLocation();
        World w = l.getWorld();
        if (arrived) {
            Vector v = p.getVelocity().multiply(0.25);
            p.setVelocity(v.setY(Math.max(v.getY(), 0.45)));
            w.spawnParticle(Particle.WHITE_ASH, l, 30, 0.4, 0.3, 0.4, 0.03);
            w.spawnParticle(Particle.SMALL_FLAME, l, 8, 0.3, 0.2, 0.3, 0.02);
            w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, l, 2, 0.2, 0.1, 0.2, 0.01);
            w.playSound(l, Sound.BLOCK_FIRE_EXTINGUISH, SoundCategory.PLAYERS, 0.5f, 0.7f);
        }
        w.playSound(l, Sound.ITEM_ARMOR_EQUIP_GENERIC, SoundCategory.PLAYERS, 0.9f, 0.6f);
    }

    /** Nothing to bite: the chain drops slack and is pulled back in. */
    private void slack(Player p) {
        stop(p.getUniqueId());
        if (!p.isOnline()) return;
        Location h = hand(p);
        p.getWorld().spawnParticle(Particle.WHITE_ASH, h, 10, 0.2, 0.2, 0.2, 0.02);
        p.getWorld().playSound(h, Sound.ITEM_ARMOR_EQUIP_GENERIC, SoundCategory.PLAYERS, 0.9f, 0.5f);
    }

    private void stop(UUID id) {
        BukkitTask t = active.remove(id);
        if (t != null) t.cancel();
    }

    // ------------------------------------------------------------------ effects

    private static Location hand(Player p) {
        Location eye = p.getEyeLocation();
        Vector f = eye.getDirection().normalize();
        Vector right = new Vector(-f.getZ(), 0, f.getX());
        if (right.lengthSquared() < 1e-6) right = new Vector(1, 0, 0);
        return eye.add(f.multiply(0.4)).add(right.normalize().multiply(0.35)).add(0, -0.4, 0);
    }

    private void throwSounds(Player p) {
        Location l = p.getEyeLocation();
        World w = p.getWorld();
        w.playSound(l, Sound.ITEM_TRIDENT_THROW, SoundCategory.PLAYERS, 1.0f, 0.6f);
        w.playSound(l, Sound.ITEM_ARMOR_EQUIP_IRON, SoundCategory.PLAYERS, 1.0f, 0.5f);   // the chain rattling out
        w.playSound(l, Sound.BLOCK_SOUL_SAND_BREAK, SoundCategory.PLAYERS, 0.8f, 0.6f);
    }

    /**
     * The chain from {@code a} to {@code b}: links alternating rust and ash so it reads as a chain, ember sparks
     * along it, a sag in the middle and a little soul fire licking off it.
     */
    private void drawChain(Location a, Location b) {
        World w = a.getWorld();
        Vector d = b.toVector().subtract(a.toVector());
        double len = d.length();
        if (len < 0.2) return;
        int links = (int) (len / 0.3);
        double sag = Math.min(0.6, len * 0.03);
        for (int i = 0; i <= links; i++) {
            double s = i / (double) links;
            Location at = a.clone().add(d.clone().multiply(s)).add(0, -sag * 4 * s * (1 - s), 0);
            w.spawnParticle(Particle.DUST, at, 1, 0, 0, 0, 0, new Particle.DustOptions(i % 2 == 0 ? RUST : ASH, i % 2 == 0 ? 0.9f : 0.75f));
            if (i % 7 == 3 && rnd.nextInt(2) == 0) w.spawnParticle(Particle.SOUL_FIRE_FLAME, at, 1, 0.02, 0.02, 0.02, 0.005);
            if (rnd.nextInt(14) == 0) w.spawnParticle(Particle.DUST, at, 1, 0.05, 0.05, 0.05, 0, new Particle.DustOptions(EMBER, 0.6f));
        }
    }

    /** The hook's head: soul fire, a pale bone glint and smoke. */
    private void hookHead(Location h) {
        World w = h.getWorld();
        w.spawnParticle(Particle.SOUL_FIRE_FLAME, h, 3, 0.05, 0.05, 0.05, 0.01);
        w.spawnParticle(Particle.DUST, h, 2, 0.05, 0.05, 0.05, 0, new Particle.DustOptions(BONE, 1.3f));
        w.spawnParticle(Particle.SMOKE, h, 1, 0.05, 0.05, 0.05, 0.01);
        if (rnd.nextInt(3) == 0) w.spawnParticle(Particle.SCULK_SOUL, h, 1, 0, 0, 0, 0.01);
    }

    // ------------------------------------------------------------------ rules

    /** Players and hostile mobs only - never pets, villagers, armor stands, displays or anything AlloyCore tags. */
    private boolean canTarget(Player caster, LivingEntity t) {
        if (t == caster || t.isDead() || t instanceof ArmorStand) return false;
        if (alsoTarget.test(t)) return true;   // the Arena Test Dummy: damage numbers, but it's pinned and won't move
        if (t.isInvulnerable()) return false;
        if (t instanceof Player tp) {
            GameMode gm = tp.getGameMode();
            if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR || !caster.canSee(tp)) return false;
        } else if (!(t instanceof Enemy)) return false;
        if (t.isInsideVehicle()) return false;
        for (NamespacedKey k : t.getPersistentDataContainer().getKeys())
            if (k.getNamespace().equals(tagKey.getNamespace())) return false;
        return true;
    }

    /** Ask the server's own damage rules whether the caster may hit this target here (same check as a sword hit). */
    private boolean allowed(Player caster, LivingEntity t) {
        DamageSource src = DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(caster).withDirectEntity(caster).build();
        EntityDamageByEntityEvent probe = new EntityDamageByEntityEvent(caster, t, EntityDamageEvent.DamageCause.ENTITY_ATTACK, src, DAMAGE);
        Bukkit.getPluginManager().callEvent(probe);
        return !probe.isCancelled();
    }

    @EventHandler(ignoreCancelled = true)
    public void onFall(EntityDamageEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL || !(e.getEntity() instanceof Player p)) return;
        Long until = noFallUntil.get(p.getUniqueId());
        if (until != null && until >= System.currentTimeMillis()) e.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        stop(id);
        readyAt.remove(id);
        noFallUntil.remove(id);
    }

    /** Plugin disable: drop every chain. */
    public void shutdown() {
        for (BukkitTask t : active.values()) t.cancel();
        active.clear();
    }
}
