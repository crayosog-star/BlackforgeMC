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
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;
import java.util.function.Predicate;

/**
 * Ashen Chain: a BlackForgeMC ability item with a dark, ashen look - a rusted chain wreathed in soul fire.
 * Two inputs: right-click grapples, left-click strikes.
 *
 * Right-click (grapple): the chain bites into the block you're looking at (up to 24 away) and drags you there. It
 * passes through players and mobs. Sneak to let go early. No fall damage while you're being reeled in, or for 2 s
 * after. The pull maths lives in {@link ChainPull}.
 *
 * Left-click (strike): a skillshot along your aim, up to 12 blocks, stopped by blocks. Point-blank works too: hitting
 * a player or mob with the chain in hand fires it at them instead of punching. A miss costs the normal cooldown.
 * Hitting a player sets them on fire (real fire: no other damage, Fire Resistance ignores it) and the chain holds
 * them for a short window (pull-delay-seconds, 0.5 s). Break free inside it - Surge ({@link #breakFree}), grappling
 * away with their own Ashen Chain, water, a block between you, or {@link ChainPull#TETHER_SNAP} away - and there's no
 * pull. Otherwise they're yanked exactly 1 block towards you and the chain reels back in and is gone. Landing it puts
 * the chain on a longer cooldown. Hostile mobs are still hooked for 1 heart and dragged to you.
 *
 * The chain itself is real chain-block models with a small fire riding the hook head ({@link ChainLine}).
 *
 * Hooking a target goes through a normal damage event first, so spawn protection and PvP rules decide who can be
 * dragged. Only where {@code allowedAt} says (the hub Arena for now). Never used up.
 */
public final class AshenChain implements Listener {
    public static final String NAME = "<gradient:#8C8580:#C2541B:#6E0F0F><bold>Ashen Chain</bold></gradient>";
    public static final double DAMAGE = 2.0;                 // 1 heart (hostile mobs)
    private static final int YANK_DRAW_TICKS = 8;            // chain stays drawn to a dragged target this long
    private static final long NO_FALL_MS = 2000;             // after a reel ends
    private static final int PULL_TICKS = 5;                 // a hooked target is dragged its 1 block over this long
    private static final int RETRACT_TICKS = 5;              // then the chain reels back into your hand over this long
    private static final double LINK = 1.0;                  // length of one chain model (one chain block)
    private static final float LINK_WIDTH = 0.7f;            // chain models are drawn a bit thinner than a chain block
    private static final double START_OUT = 1.3;             // the chain starts this far in front of you, so it never fills your screen

    static final Color ASH = Color.fromRGB(0x4A4542), RUST = Color.fromRGB(0x7A3B1E), EMBER = Color.fromRGB(0xE0702A),
            BONE = Color.fromRGB(0xB8B0A2), BLOOD = Color.fromRGB(0x6E0F0F);

    private final AlloyCore plugin;
    private final boolean enabled;
    private final int cooldownTicks;
    private final Predicate<Location> allowedAt;
    private final Predicate<Entity> alsoTarget;   // AlloyCore entities it may still hook (the Arena's Test Dummy and practice zombie)
    private final Predicate<Entity> pinned;       // of those, ones that must never be moved (the Test Dummy)
    private final NamespacedKey tagKey;
    private final Map<UUID, BukkitTask> active = new HashMap<>();   // caster -> their chain in flight or reeling
    private final Map<UUID, Long> readyAt = new HashMap<>();
    private final Map<UUID, Long> noFallUntil = new HashMap<>();
    private final int playerCooldownTicks, burnTicks, holdTicks;
    private final double throwSpeed;   // blocks per tick the hook flies
    private final Map<UUID, Tether> tethers = new HashMap<>();      // chained target -> its burning tether
    private boolean dealing;   // true while the chain itself is hurting / probing someone, so onMelee lets it through
    private final Map<UUID, ChainLine> lines = new HashMap<>();     // caster -> the chain model currently out
    private final BukkitTask sweeper;                               // removes a chain the tick after nothing draws it

    /**
     * The visible chain: a row of real chain-block models from the caster's hand to the hook. Each tick something
     * draws it ({@link #drawChain}); the tick after nothing does, the sweeper removes it, so it can never be left
     * hanging. Every link is placed exactly, so nothing smears behind when either end moves.
     */
    private final class ChainLine {
        final List<BlockDisplay> links = new ArrayList<>();
        BlockDisplay tip;   // a small fire riding the hook head
        int drawnAt;

        void draw(Location a, Location b, int tick, boolean fireTip) {
            drawnAt = tick;
            placeTip(b, fireTip);
            Vector d = b.toVector().subtract(a.toVector());
            double len = d.length();
            if (len < 0.05) return;
            int n = (int) Math.ceil(len / LINK);
            while (links.size() > n) links.remove(links.size() - 1).remove();
            a = a.clone();
            a.setYaw(0);       // a display turns its model by its own yaw/pitch: copied from the player, every link
            a.setPitch(0);     // swung round with the camera. All the turning is in the transformation instead.
            Vector u = d.clone().multiply(1 / len);
            Quaternionf rot = new Quaternionf().rotationTo(0, 1, 0, (float) u.getX(), (float) u.getY(), (float) u.getZ());
            Vector3f centre = rot.transform(new Vector3f(-LINK_WIDTH / 2, 0, -LINK_WIDTH / 2));   // the chain runs up the middle of its block
            for (int i = 0; i < n; i++) {
                double from = i * LINK;
                float seg = (float) Math.min(LINK, len - from);
                Location at = a.clone().add(u.clone().multiply(from));
                Transformation tf = new Transformation(centre, rot, new Vector3f(LINK_WIDTH, seg, LINK_WIDTH), new Quaternionf());
                if (i < links.size()) {
                    BlockDisplay l = links.get(i);
                    if (!l.isValid()) continue;
                    l.teleport(at);
                    l.setInterpolationDelay(0);
                    l.setInterpolationDuration(1);
                    l.setTransformation(tf);
                } else {
                    links.add(a.getWorld().spawn(at, BlockDisplay.class, e -> {
                        e.setBlock(Material.IRON_CHAIN.createBlockData());
                        e.setPersistent(false);
                        e.setTeleportDuration(1);
                        e.setBrightness(new Display.Brightness(12, 15));
                        e.setShadowRadius(0);
                        e.setTransformation(tf);
                    }));
                }
            }
        }

        private void placeTip(Location at, boolean on) {
            if (!on) { if (tip != null) { tip.remove(); tip = null; } return; }
            at = at.clone();
            at.setYaw(0);
            at.setPitch(0);
            if (tip != null && tip.isValid()) { tip.teleport(at); return; }
            float sz = 0.6f;
            tip = at.getWorld().spawn(at, BlockDisplay.class, e -> {
                e.setBlock(Material.FIRE.createBlockData());
                e.setPersistent(false);
                e.setTeleportDuration(1);
                e.setBrightness(new Display.Brightness(15, 15));
                e.setShadowRadius(0);
                e.setTransformation(new Transformation(new Vector3f(-sz / 2, -sz / 2, -sz / 2), new Quaternionf(), new Vector3f(sz), new Quaternionf()));
            });
        }

        void remove() {
            links.forEach(Entity::remove);
            links.clear();
            if (tip != null) { tip.remove(); tip = null; }
        }
    }

    /** A burning chain between a caster and the player (or Test Dummy) they hooked. */
    private static final class Tether {
        final UUID caster;
        BukkitTask task;
        boolean pulling;   // past the break-free window: the pull can't be stopped any more
        Tether(UUID caster) { this.caster = caster; }
    }
    private final Random rnd = new Random();

    public AshenChain(AlloyCore plugin, Predicate<Location> allowedAt, Predicate<Entity> alsoTarget, Predicate<Entity> pinned) {
        this.plugin = plugin;
        this.allowedAt = allowedAt;
        this.alsoTarget = alsoTarget;
        this.pinned = pinned;
        this.enabled = plugin.getConfig().getBoolean("ashen-chain.enabled", true);
        this.cooldownTicks = Math.max(0, (int) Math.round(plugin.getConfig().getDouble("ashen-chain.cooldown-seconds", 3) * 20));
        this.playerCooldownTicks = Math.max(0, (int) Math.round(plugin.getConfig().getDouble("ashen-chain.player-hit-cooldown-seconds", 5) * 20));
        this.burnTicks = Math.max(0, (int) Math.round(plugin.getConfig().getDouble("ashen-chain.burn-seconds", 3) * 20));
        this.holdTicks = Math.max(1, (int) Math.round(plugin.getConfig().getDouble("ashen-chain.pull-delay-seconds", 0.5) * 20));
        this.throwSpeed = Math.max(0.2, plugin.getConfig().getDouble("ashen-chain.throw-speed", 30) / 20.0);
        this.tagKey = new NamespacedKey(plugin, "ashen_chain");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        this.sweeper = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int now = Bukkit.getCurrentTick();
            lines.values().removeIf(l -> { if (now - l.drawnAt > 1) { l.remove(); return true; } return false; });
        }, 1L, 1L);
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
                    Ui.mm("<white>Right-click<gray>: grapple to the block you aim at"),
                    Ui.mm("<white>Left-click<gray>: strike - a hit sets them on fire <dark_gray>(" + secs(burnTicks) + ")"),
                    Ui.mm("<gray>and yanks them <white>1 block <gray>closer after <white>" + secs(holdTicks)),
                    Ui.mm("<gray>Break free in time - <white>Surge<gray>, your own chain,"),
                    Ui.mm("<gray>water or cover - and you aren't pulled"),
                    Ui.mm("<gray>Reach: <white>" + (int) ChainPull.GRAPPLE_RANGE + " <gray>blocks, <white>" + (int) ChainPull.YANK_RANGE + " <gray>at players"),
                    Ui.mm("<gray>Sneak to let go · no fall damage while chained"),
                    Ui.mm(cooldownTicks > 0 ? "<gray>Cooldown: <white>" + secs(cooldownTicks) + "<gray>, <white>" + secs(playerCooldownTicks) + " <gray>after chaining a player" : "<gray>Cooldown: <white>none"),
                    Ui.mm(""),
                    Ui.mm("<dark_gray>BlackForgeMC ability")));
            m.setMaxStackSize(1);
            m.setEnchantmentGlintOverride(true);
            m.getPersistentDataContainer().set(tagKey, PersistentDataType.BYTE, (byte) 1);
        });
        return it;
    }

    private static String secs(int ticks) { return String.format(Locale.ROOT, ticks % 20 == 0 ? "%.0fs" : "%.1fs", ticks / 20.0); }

    public boolean isChain(ItemStack it) {
        return it != null && it.getType() == Material.IRON_CHAIN && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(tagKey, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------ casting

    /** Right-click grapples, left-click strikes (see the class notes). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action a = e.getAction();
        boolean right = a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK;
        boolean left = a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK;
        if (!right && !left) return;
        Player p = e.getPlayer();
        if (!isChain(p.getInventory().getItemInMainHand())) return;
        Block clicked = e.getClickedBlock();
        if (a == Action.RIGHT_CLICK_BLOCK && clicked != null && clicked.getType().isInteractable() && !p.isSneaking()) return;
        e.setCancelled(true);   // never place it as a chain block, never start mining with it
        if (!ready(p)) return;
        if (right) grapple(p); else strike(p, null);
    }

    /**
     * Point-blank: attacking a player or mob with the chain in hand fires it at them instead of punching. The swing
     * that comes with the attack also fires a left-click, but the one-chain-at-a-time check stops a second cast.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMelee(EntityDamageByEntityEvent e) {
        if (dealing || !(e.getDamager() instanceof Player p) || e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!isChain(p.getInventory().getItemInMainHand())) return;
        e.setCancelled(true);   // the chain isn't a club
        if (e.getEntity() instanceof LivingEntity t && canTarget(p, t) && ready(p)) strike(p, t);
    }

    /** All the checks before a cast; starts the cooldown when it says yes. */
    private boolean ready(Player p) {
        if (p.getGameMode() == GameMode.SPECTATOR) return false;
        if (!enabled) { p.sendActionBar(Ui.mm("<gray>The Ashen Chain isn't switched on yet.")); return false; }
        if (!allowedAt.test(p.getLocation())) { p.sendActionBar(Ui.mm("<gray>The " + NAME + " <gray>only works in the <white>Arena <gray>for now.")); return false; }
        if (active.containsKey(p.getUniqueId())) return false;   // one chain at a time
        long now = System.currentTimeMillis(), ready = readyAt.getOrDefault(p.getUniqueId(), 0L);
        if (ready > now) {
            p.sendActionBar(Ui.mm("<#C2541B>⛓ <gray>The embers are still cooling... <white>" + String.format(Locale.ROOT, "%.1f", (ready - now) / 1000.0) + "s"));
            return false;
        }
        if (cooldownTicks > 0) {
            readyAt.put(p.getUniqueId(), now + cooldownTicks * 50L);
            p.setCooldown(Material.IRON_CHAIN, cooldownTicks);
        }
        return true;
    }

    /** Right-click: bite into the block you're aiming at and get dragged there. Players and mobs don't stop it. */
    private void grapple(Player p) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        World w = p.getWorld();
        p.swingMainHand();
        throwSounds(p);
        RayTraceResult blockHit = w.rayTraceBlocks(eye, dir, ChainPull.GRAPPLE_RANGE, FluidCollisionMode.NEVER, true);
        if (blockHit == null) {   // into the open air: it falls slack
            Location far = eye.clone().add(dir.multiply(ChainPull.GRAPPLE_RANGE));
            fly(p, () -> far, () -> slack(p));
            return;
        }
        Location anchor = blockHit.getHitPosition().toLocation(w);
        Block b = blockHit.getHitBlock();
        fly(p, () -> anchor, () -> anchor(p, anchor, b));
    }

    /**
     * Left-click: throw the chain along your aim at a player or hostile mob, up to {@link ChainPull#YANK_RANGE}, never
     * through a wall. {@code point} is someone you just hit point-blank (skips the aim). A miss just falls slack.
     */
    private void strike(Player p, LivingEntity point) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        World w = p.getWorld();
        p.swingMainHand();
        throwSounds(p);
        LivingEntity target = point;
        RayTraceResult blockHit = w.rayTraceBlocks(eye, dir, ChainPull.YANK_RANGE, FluidCollisionMode.NEVER, true);
        if (target == null) {
            double blockDist = blockHit == null ? Double.MAX_VALUE : blockHit.getHitPosition().distance(eye.toVector());
            RayTraceResult entHit = w.rayTraceEntities(eye, dir, ChainPull.YANK_RANGE, 0.5,
                    en -> en instanceof LivingEntity le && canTarget(p, le));
            if (entHit != null && entHit.getHitEntity() instanceof LivingEntity le
                    && entHit.getHitPosition().distance(eye.toVector()) < blockDist) target = le;   // never through a wall
        }
        if (target == null) {   // missed: the chain clatters off the wall, or falls slack in the air
            Location end = blockHit != null ? blockHit.getHitPosition().toLocation(w) : eye.clone().add(dir.multiply(ChainPull.YANK_RANGE));
            fly(p, () -> end, () -> slack(p));
            return;
        }
        if (!alsoTarget.test(target) && !allowed(p, target)) {   // the dummy counts every damage event: no probe for it
            p.sendActionBar(Ui.mm("<#C2541B>⛓ <gray>The chain won't bite here."));
            LivingEntity miss = target;
            fly(p, () -> chest(miss), () -> slack(p));
            return;
        }
        LivingEntity t = target;
        fly(p, () -> chest(t), () -> hook(p, t));   // the hook homes on them: the hit was decided when you threw
    }

    /**
     * The hook flies out to wherever {@code to} says (asked every tick, so it follows a moving target) at
     * {@link #throwSpeed} (ashen-chain.throw-speed, blocks a second), drawing the chain behind it, then {@code then}.
     */
    private void fly(Player p, java.util.function.Supplier<Location> to, Runnable then) {
        UUID id = p.getUniqueId();
        double[] out = {0};   // how far the hook has travelled
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Location target = to.get();
            if (!p.isOnline() || p.isDead() || p.getWorld() != target.getWorld()) { stop(id); return; }
            out[0] += throwSpeed;
            Location from = chainStart(p, target);
            Vector d = target.toVector().subtract(from.toVector());
            double dist = d.length();
            boolean arrived = out[0] >= dist;
            Location head = arrived || dist < 1e-6 ? target.clone() : from.clone().add(d.multiply(out[0] / dist));
            drawChain(p, from, head, true);
            hookHead(head);
            if (arrived) { stop(id); then.run(); }
        }, 0L, 1L);
        active.put(id, task);
    }

    // ------------------------------------------------------------------ hooking a target

    private void hook(Player p, LivingEntity t) {
        if (!t.isValid() || t.isDead() || t.getWorld() != p.getWorld()) { slack(p); return; }
        if (t instanceof Player || alsoTarget.test(t)) { chainPlayer(p, t); return; }
        double dist = t.getLocation().distance(p.getLocation());
        if (dist > ChainPull.YANK_RANGE + 4) { slack(p); return; }   // they got away while the hook flew
        World w = t.getWorld();
        Location chest = t.getLocation().add(0, t.getHeight() * 0.6, 0);
        boolean dummy = alsoTarget.test(t);

        dealing = true;
        try { t.damage(DAMAGE, p); } finally { dealing = false; }   // a real damage event: kill credit, combat tags and arena rules all apply
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
            drawChain(p, chainStart(p, c), c, true);
            w.spawnParticle(Particle.WHITE_ASH, c, 4, 0.2, 0.3, 0.2, 0.01);
            w.spawnParticle(Particle.SMALL_FLAME, c, 1, 0.15, 0.2, 0.15, 0.01);
        }, 1L, 1L);
        active.put(id, task);
    }

    // ------------------------------------------------------------------ the burning tether (players)

    private static Location chest(LivingEntity t) { return t.getLocation().add(0, t.getHeight() * 0.6, 0); }

    /**
     * A player (or an Arena practice target) is hooked. Straight away they're set on fire (real fire: the only damage
     * is vanilla fire damage). Then the chain holds them for {@link #holdTicks} (0.5 s): if they break free in that
     * window (Surge, their own chain, water, cover, distance) they aren't pulled. If not, they're dragged 1 block to
     * the caster and the chain reels back in and is gone. The Test Dummy is pinned: it burns but is never moved.
     */
    private void chainPlayer(Player p, LivingEntity t) {
        stop(p.getUniqueId());
        if (!ChainPull.tetherHolds(t.getLocation().distance(p.getLocation()))) { slack(p); return; }   // got away while the hook flew
        World w = t.getWorld();
        UUID cid = p.getUniqueId(), tid = t.getUniqueId();
        endTether(tid);   // a fresh hook replaces anyone else's chain on them

        // landing it on a player costs a longer cooldown than grappling a block
        if (playerCooldownTicks > 0) {
            long until = System.currentTimeMillis() + playerCooldownTicks * 50L;
            if (until > readyAt.getOrDefault(cid, 0L)) {
                readyAt.put(cid, until);
                p.setCooldown(Material.IRON_CHAIN, playerCooldownTicks);
            }
        }

        t.setFireTicks(Math.max(t.getFireTicks(), burnTicks));
        Location c0 = chest(t);
        w.spawnParticle(Particle.FLAME, c0, 10, 0.25, 0.4, 0.25, 0.01);
        w.spawnParticle(Particle.WHITE_ASH, c0, 20, 0.4, 0.5, 0.4, 0.02);
        w.playSound(c0, Sound.ITEM_FIRECHARGE_USE, SoundCategory.PLAYERS, 1.0f, 0.6f);
        w.playSound(c0, Sound.ITEM_ARMOR_EQUIP_IRON, SoundCategory.PLAYERS, 1.2f, 0.6f);
        String targetName = t instanceof Player tp ? tp.getName() : pinned.test(t) ? "the dummy" : "the zombie";
        if (t instanceof Player tp) {
            tp.sendActionBar(Ui.mm("<#C2541B>⛓ <white>" + p.getName() + " <gray>hooked you - <white>break free now<gray>: Surge, your chain, water or cover"));
            tp.playSound(tp, Sound.BLOCK_CHAIN_HIT, SoundCategory.PLAYERS, 1.0f, 0.6f);
        }
        p.sendActionBar(Ui.mm("<#C2541B>⛓ <gray>Hooked <white>" + targetName));

        Tether tether = new Tether(cid);
        tethers.put(tid, tether);
        boolean moves = !pinned.test(t);
        int pullAt = holdTicks + 1, retractAt = pullAt + PULL_TICKS, endAt = retractAt + RETRACT_TICKS;
        int[] n = {0};
        tether.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int k = ++n[0];
            Player caster = Bukkit.getPlayer(cid);
            boolean casterOk = caster != null && caster.isOnline() && !caster.isDead() && caster.getWorld() == w;
            boolean targetOk = t.isValid() && !t.isDead() && t.getWorld() == w && !(t instanceof Player tp && !tp.isOnline());
            if (!casterOk || !targetOk) { endTether(tid); return; }
            Location c = chest(t);
            if (k < pullAt) {   // the break-free window: the chain holds, taut and burning
                if (t.isInWater()) { snap(caster, t, "the water"); return; }
                if (!ChainPull.tetherHolds(c.distance(caster.getLocation()))) { snap(caster, t, "distance"); return; }
                if (k % 2 == 0 && !caster.hasLineOfSight(t)) { snap(caster, t, "cover"); return; }
                drawChain(caster, chainStart(caster, c), c, true);
                if (k % 4 == 1) w.playSound(c, Sound.BLOCK_CHAIN_STEP, SoundCategory.PLAYERS, 0.9f, 0.6f + 0.1f * k / 4f);
                return;
            }
            if (k == pullAt) {   // too late to escape: yank
                tether.pulling = true;
                if (moves) pullOneBlock(caster, t);
                w.playSound(c, Sound.ITEM_TRIDENT_RIPTIDE_1, SoundCategory.PLAYERS, 0.9f, 0.7f);
                w.playSound(c, Sound.BLOCK_CHAIN_BREAK, SoundCategory.PLAYERS, 0.8f, 0.6f);
                w.spawnParticle(Particle.WHITE_ASH, c, 12, 0.3, 0.4, 0.3, 0.03);
            }
            if (k < retractAt) { drawChain(caster, chainStart(caster, c), c, true); return; }
            if (k < endAt) {   // the chain reels back into your hand
                Location from = chainStart(caster, c);
                double r = (k - retractAt + 1) / (double) RETRACT_TICKS;
                Location head = c.clone().add(from.toVector().subtract(c.toVector()).multiply(r));
                if (head.distanceSquared(from) > 0.04) drawChain(caster, from, head, true);
                return;
            }
            endTether(tid);   // nothing draws the chain any more: it's gone next tick
        }, 1L, 1L);
    }

    /**
     * Drags the target {@link ChainPull#PLAYER_PULL} blocks (1) straight towards the caster, a little each tick over
     * {@link #PULL_TICKS} so you see them being reeled in. Never into the caster (pullStep) and never into a wall: a
     * step that would hit something ends the pull there. Their view isn't turned.
     */
    private void pullOneBlock(Player caster, LivingEntity t) {
        Location start = t.getLocation();
        double dx = caster.getX() - start.getX(), dz = caster.getZ() - start.getZ();
        double h = Math.sqrt(dx * dx + dz * dz);
        double total = ChainPull.pullStep(h);
        if (total <= 0) return;
        double sx = dx / h * total / PULL_TICKS, sz = dz / h * total / PULL_TICKS;
        int[] n = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (n[0]++ >= PULL_TICKS || !t.isValid() || t.isDead() || !tethers.containsKey(t.getUniqueId())) { task.cancel(); return; }
            if (t.getWorld().hasCollisionsIn(t.getBoundingBox().shift(sx, 0, sz))) { task.cancel(); return; }
            Location now = t.getLocation();   // their latest position and view
            t.teleport(now.add(sx, 0, sz));
            t.setFallDistance(0);
        }, 0L, 1L);
    }

    /**
     * Breaks the burning chain on {@code target}, if there is one (Surge calls this when it's activated).
     * {@code how} is shown to both players, e.g. "Surge".
     */
    public void breakFree(Player target, String how) {
        Tether tether = tethers.get(target.getUniqueId());
        if (tether == null || tether.pulling) return;   // only inside the break-free window
        snap(Bukkit.getPlayer(tether.caster), target, how);
    }

    /** Broken free inside the window: the chain shatters and there's no pull (the fire keeps burning). */
    private void snap(Player caster, LivingEntity t, String how) {
        endTether(t.getUniqueId());
        if (!t.isValid()) return;
        World w = t.getWorld();
        Location c = chest(t);
        w.spawnParticle(Particle.WHITE_ASH, c, 40, 0.5, 0.6, 0.5, 0.04);
        w.spawnParticle(Particle.CAMPFIRE_COSY_SMOKE, c, 3, 0.2, 0.3, 0.2, 0.02);
        w.spawnParticle(Particle.DUST, c, 14, 0.4, 0.4, 0.4, 0, new Particle.DustOptions(RUST, 1.2f));
        w.spawnParticle(Particle.CRIT, c, 10, 0.3, 0.3, 0.3, 0.3);
        w.playSound(c, Sound.ENTITY_ITEM_BREAK, SoundCategory.PLAYERS, 1.0f, 0.6f);
        w.playSound(c, Sound.BLOCK_FIRE_EXTINGUISH, SoundCategory.PLAYERS, 0.8f, 0.9f);
        if (t instanceof Player tp) tp.sendActionBar(Ui.mm("<#C2541B>⛓ <gray>You broke free - no pull <dark_gray>(" + how + ")"));
        if (caster != null && caster.isOnline())
            caster.sendActionBar(Ui.mm("<#C2541B>⛓ <white>" + (t instanceof Player tp ? tp.getName() : "Your target") + " <gray>broke free <dark_gray>(" + how + ")"));
    }

    private void endTether(UUID target) {
        Tether t = tethers.remove(target);
        if (t != null && t.task != null) t.task.cancel();
    }

    // ------------------------------------------------------------------ dragging yourself

    private void anchor(Player p, Location at, Block b) {
        breakFree(p, "your own chain");   // grappling away tears you loose from anyone's chain
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
        Vector[] heading = {null};   // the pull's direction, turned gently each tick (no jitter from server lag)
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
            // snagged on something: only judged once it's up to speed (the slow start isn't a snag)
            stuck[0] = k > ChainPull.REEL_RAMP_TICKS / 2 && lastDist[0] - dist < 0.05 ? stuck[0] + 1 : 0;
            if (stuck[0] >= 6) { release(p, false); return; }
            lastDist[0] = dist;
            // ease in: slow first, then faster and faster (ChainPull.reelSpeed)
            Vector want = off.clone().normalize();
            heading[0] = heading[0] == null ? want : heading[0].multiply(0.7).add(want.multiply(0.3)).normalize();
            double speed = Math.min(ChainPull.reelSpeed(k), dist);
            p.setVelocity(heading[0].clone().multiply(speed).add(new Vector(0, ChainPull.REEL_ANTI_GRAVITY, 0)));
            p.setFallDistance(0);
            drawChain(p, chainStart(p, at), at, false);
            // a few embers and ash shed behind you, not in your face
            Location trail = p.getLocation().add(0, 0.9, 0).subtract(heading[0].clone().multiply(0.8));
            if (k % 2 == 0) w.spawnParticle(Particle.WHITE_ASH, trail, 2, 0.2, 0.3, 0.2, 0.005);
            if (k % 3 == 0) w.spawnParticle(Particle.SMALL_FLAME, trail, 1, 0.15, 0.3, 0.15, 0.005);
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

    /**
     * Where the visible chain leaves you when it's heading for {@code toward}: chest height, {@link #START_OUT} blocks
     * out along the chain. Starting at the eyes put the first links right in front of the camera, filling the screen.
     */
    private static Location chainStart(Player p, Location toward) {
        Location base = p.getLocation().add(0, 1.15, 0);
        Vector d = toward.toVector().subtract(base.toVector());
        double len = d.length();
        if (len < START_OUT + 0.5) return base;
        return base.add(d.multiply(START_OUT / len));
    }

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

    /** Draws {@code owner}'s chain from {@code a} to {@code b} this tick (real chain models), fire on the tip if asked. */
    private void drawChain(Player owner, Location a, Location b, boolean fireTip) {
        lines.computeIfAbsent(owner.getUniqueId(), k -> new ChainLine()).draw(a, b, Bukkit.getCurrentTick(), fireTip);
        if (fireTip && rnd.nextInt(2) == 0) b.getWorld().spawnParticle(Particle.SMALL_FLAME, b, 1, 0.08, 0.08, 0.08, 0.005);
        if (rnd.nextInt(3) == 0) {
            Vector d = b.toVector().subtract(a.toVector());
            a.getWorld().spawnParticle(Particle.DUST, a.clone().add(d.multiply(rnd.nextDouble())), 1, 0.03, 0.03, 0.03, 0,
                    new Particle.DustOptions(EMBER, 0.6f));
        }
    }

    /** The hook's head: soul fire, a pale bone glint and smoke. */
    private void hookHead(Location h) {
        World w = h.getWorld();
        w.spawnParticle(Particle.SMALL_FLAME, h, 1, 0.03, 0.03, 0.03, 0);
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
        dealing = true;
        try { Bukkit.getPluginManager().callEvent(probe); } finally { dealing = false; }
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
        endTether(id);
        readyAt.remove(id);
        noFallUntil.remove(id);
    }

    /** Plugin disable: drop every chain. */
    public void shutdown() {
        for (BukkitTask t : active.values()) t.cancel();
        active.clear();
        sweeper.cancel();
        lines.values().forEach(ChainLine::remove);
        lines.clear();
        for (Tether t : tethers.values()) if (t.task != null) t.task.cancel();
        tethers.clear();
    }
}
