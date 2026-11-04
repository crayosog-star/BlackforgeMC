package net.blackforge.core.survival;

import net.blackforge.core.BlackForgeCore;
import net.blackforge.core.Ui;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;
import java.util.function.Predicate;

/**
 * Nightrend: a pair of black, jagged daggers with a faint blood-red glow. A weapon, not a spell: left-click melee only.
 *
 * <ul>
 *   <li>One dagger in your main hand hits for {@code damage} (5) at {@code attack-speed} (2.0 a second) with
 *   {@code reach-change} (-0.5) blocks of reach. It isn't a sword, so there's no sweep: one target per hit.</li>
 *   <li>With a Nightrend in <b>each</b> hand you attack at {@code pair-attack-speed} (2.6), and every second hit
 *   the blades take turns: a red slash off the main-hand blade, then the off arm swings and its blade slashes back the
 *   other way (cosmetic; same damage). Drop either and the bonus is gone.</li>
 *   <li>Hits from the arc behind the target ({@code behind-arc-degrees}, 120) only do {@code behind-damage} (60%),
 *   with a duller sound. Players and mobs alike. "Behind" is where the target is facing, not where it's walking.</li>
 *   <li>A slight aura: a few dark-red embers drift off each blade while you hold it. Off while invisible or vanished.</li>
 *   <li>The EMP shuts the pair bonus off for {@code emp-seconds} (4); the daggers still hit at the single speed.</li>
 *   <li>Equip flourish: the moment you hold the pair, both blades flip up out of your hands and settle in your grip
 *   (display entities; at most once per {@code flip-cooldown-seconds}). Other players see the flipping copies with the
 *   real held daggers hidden (a fake equipment packet), then the real ones come back the tick the copies go. The
 *   flipper never sees the copies: their own view keeps their real daggers and both arms swing instead. Putting the pair away ends it at once.</li>
 * </ul>
 *
 * Two variants are the same dagger: {@link #item()} and {@link #offItem()} differ only in a tag the Arena kit uses
 * to put one in each hand. Every hit goes through the normal damage event, so spawn protection and PvP rules decide
 * who can be hit. Only where {@code allowedAt} says (the hub Arena for now). Maths lives in {@link NightrendMath}.
 */
public final class Nightrend implements Listener, Hackable {
    public static final String NAME = "<gradient:#5A0710:#D81E3A:#5A0710><bold>Nightrend</bold></gradient>";

    // palette: obsidian black to a cold blood red (no orange: that's Ashen; no pink: that's Surge)
    static final Color OBSIDIAN = Color.fromRGB(0x14090B), BLOOD = Color.fromRGB(0xB3122B), DRIED = Color.fromRGB(0x5A0710);
    private static final Particle.DustTransition EMBER = new Particle.DustTransition(BLOOD, OBSIDIAN, 0.55f);
    private static final Particle.DustTransition EDGE = new Particle.DustTransition(Color.fromRGB(0xD81E3A), DRIED, 0.8f);

    private static final byte MAIN = 1, OFF = 2;     // tag values: which hand the Arena kit puts it in
    private static final int TICK_EVERY = 2;         // pair / aura check interval
    private static final int AURA_EVERY = 10;        // aura puff every half second

    private final BlackForgeCore plugin;
    private final Predicate<Location> allowedAt;
    private final boolean enabled, customModel;
    private final double damage, singleSpeed, pairSpeed, reachChange, behindArc, behindDamage;
    private final long empMillis, flipCooldownMillis;
    private final NamespacedKey tagKey, damageKey, speedKey, reachKey, pairKey, modelKey;
    private final Set<UUID> pairOn = new HashSet<>();
    private final Map<UUID, Integer> hits = new HashMap<>();
    private final Map<UUID, Long> hackedUntil = new HashMap<>(), lastFlip = new HashMap<>();
    private final Set<ItemDisplay> live = new HashSet<>();
    private final Set<UUID> flipping = new HashSet<>();   // a flip is playing for them
    private final BukkitTask task;
    private long tick;

    public Nightrend(BlackForgeCore plugin, Predicate<Location> allowedAt) {
        this.plugin = plugin;
        this.allowedAt = allowedAt;
        FileConfiguration c = plugin.getConfig();
        this.enabled = c.getBoolean("nightrend.enabled", true);
        this.customModel = c.getBoolean("nightrend.custom-model", c.getBoolean("resource-pack-live", false));   // only once the resource pack is live
        this.damage = Math.max(1, Math.min(20, c.getDouble("nightrend.damage", 5)));
        this.singleSpeed = Math.max(0.5, Math.min(4, c.getDouble("nightrend.attack-speed", 2.0)));
        this.pairSpeed = Math.max(singleSpeed, Math.min(4, c.getDouble("nightrend.pair-attack-speed", 2.6)));
        this.reachChange = Math.max(-2, Math.min(1, c.getDouble("nightrend.reach-change", -0.5)));
        this.behindArc = Math.max(0, Math.min(360, c.getDouble("nightrend.behind-arc-degrees", 120)));
        this.behindDamage = Math.max(0, Math.min(1, c.getDouble("nightrend.behind-damage", 0.6)));
        this.empMillis = Math.round(Math.max(0, c.getDouble("nightrend.emp-seconds", 4)) * 1000);
        this.flipCooldownMillis = Math.round(Math.max(0, c.getDouble("nightrend.flip-cooldown-seconds", 3)) * 1000);
        this.tagKey = new NamespacedKey(BlackForgeCore.NS, "nightrend");
        this.modelKey = new NamespacedKey("blackforge", "nightrend");
        this.damageKey = new NamespacedKey(BlackForgeCore.NS, "nightrend_damage");
        this.speedKey = new NamespacedKey(BlackForgeCore.NS, "nightrend_speed");
        this.reachKey = new NamespacedKey(BlackForgeCore.NS, "nightrend_reach");
        this.pairKey = new NamespacedKey(BlackForgeCore.NS, "nightrend_pair");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, TICK_EVERY);
    }

    // ------------------------------------------------------------------ the item

    /** The main-hand dagger. */
    public ItemStack item() { return make(MAIN); }

    /** The same dagger, tagged for the off hand (the Arena kit puts it there). */
    public ItemStack offItem() { return make(OFF); }

    private ItemStack make(byte hand) {
        ItemStack it = new ItemStack(Material.FLINT);
        it.editMeta(m -> {
            m.displayName(Ui.mm(NAME));
            m.lore(List.of(
                    Ui.mm("<gray>A black, jagged dagger. Fast and close."),
                    Ui.mm("<gray>Hold one in <white>each hand<gray> to strike faster:"),
                    Ui.mm("<white>" + fmt(singleSpeed) + "<gray> → <white>" + fmt(pairSpeed) + "<gray> attacks a second"),
                    Ui.mm("<gray>Short reach, no sweep: <white>one target<gray> per hit"),
                    Ui.mm("<gray>Hits from <white>behind<gray> do only <white>" + Math.round(behindDamage * 100) + "%<gray> damage"),
                    Ui.mm(""),
                    Ui.mm("<dark_gray>BlackForgeMC PvP item")));
            if (customModel) m.setItemModel(modelKey);   // from the first tick: kits, the wall and the flip copies, not just after ItemSkins' next pass
            m.setMaxStackSize(1);
            m.addAttributeModifier(Attribute.ATTACK_DAMAGE, new AttributeModifier(damageKey,
                    NightrendMath.damageModifier(damage), AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
            m.addAttributeModifier(Attribute.ATTACK_SPEED, new AttributeModifier(speedKey,
                    NightrendMath.speedModifier(singleSpeed), AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
            if (reachChange != 0) m.addAttributeModifier(Attribute.ENTITY_INTERACTION_RANGE, new AttributeModifier(reachKey,
                    reachChange, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
            m.getPersistentDataContainer().set(tagKey, PersistentDataType.BYTE, hand);
        });
        return it;
    }

    public boolean isNightrend(ItemStack it) {
        return it != null && it.getType() == Material.FLINT && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(tagKey, PersistentDataType.BYTE);
    }

    /** The main-hand variant only (the Arena refill tells the two apart with this). */
    public boolean isMainBlade(ItemStack it) { return isNightrend(it) && hand(it) != OFF; }

    /** The off-hand variant only. */
    public boolean isOffBlade(ItemStack it) { return isNightrend(it) && hand(it) == OFF; }

    private byte hand(ItemStack it) {
        Byte b = it.getItemMeta().getPersistentDataContainer().get(tagKey, PersistentDataType.BYTE);
        return b == null ? MAIN : b;
    }

    private static String fmt(double d) { return d == Math.rint(d) ? String.valueOf((int) d) : String.format(Locale.ROOT, "%.1f", d); }

    // ------------------------------------------------------------------ pair bonus and aura

    private void tick() {
        tick += TICK_EVERY;
        long now = System.currentTimeMillis();
        boolean auraNow = tick % AURA_EVERY == 0;
        for (Player p : Bukkit.getOnlinePlayers()) refresh(p, now, auraNow);
        hackedUntil.values().removeIf(t -> t <= now);
    }

    private void refresh(Player p, long now, boolean auraNow) {
        var inv = p.getInventory();
        boolean main = isNightrend(inv.getItemInMainHand()), off = isNightrend(inv.getItemInOffHand());
        boolean here = enabled && allowedAt.test(p.getLocation());
        boolean hacked = hackedUntil.getOrDefault(p.getUniqueId(), 0L) > now;
        setPair(p, here && main && off && !hacked && !p.isDead());
        if (!main) hits.remove(p.getUniqueId());
        if (auraNow && here && (main || off) && !flipping.contains(p.getUniqueId()) && visible(p)) aura(p, main, off);
    }

    /** Equipping reacts on the very next tick instead of waiting for the 2-tick check, so the flip starts at once. */
    private void soon(Player p) { Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) refresh(p, System.currentTimeMillis(), false); }); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onHeld(PlayerItemHeldEvent e) { soon(e.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onSwap(PlayerSwapHandItemsEvent e) { soon(e.getPlayer()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) { if (e.getWhoClicked() instanceof Player p) soon(p); }

    // ------------------------------------------------------------------ who sees what during the flip

    /**
     * Other players see the flipper's hands empty while the copies flip, so nobody sees two pairs. Only a fake
     * equipment packet to those viewers: the flipper's own items, inventory and attack charge are never touched,
     * and any real equipment change (or {@link #showHands}) puts the true items back on their screens.
     */
    private static void hideHands(Player p, Collection<Player> viewers) {
        ItemStack air = new ItemStack(Material.AIR);
        for (Player v : viewers) {
            if (v == p || !v.isOnline()) continue;
            v.sendEquipmentChange(p, EquipmentSlot.HAND, air);
            v.sendEquipmentChange(p, EquipmentSlot.OFF_HAND, air);
        }
    }

    private static void showHands(Player p, Collection<Player> viewers) {
        if (!p.isOnline()) return;
        var inv = p.getInventory();
        for (Player v : viewers) {
            if (v == p || !v.isOnline()) continue;
            v.sendEquipmentChange(p, EquipmentSlot.HAND, inv.getItemInMainHand());
            v.sendEquipmentChange(p, EquipmentSlot.OFF_HAND, inv.getItemInOffHand());
        }
    }

    private void setPair(Player p, boolean on) {
        UUID id = p.getUniqueId();
        if (on == pairOn.contains(id)) return;
        AttributeInstance ai = p.getAttribute(Attribute.ATTACK_SPEED);
        if (ai == null) return;
        ai.removeModifier(pairKey);
        if (on) {
            ai.addTransientModifier(new AttributeModifier(pairKey, NightrendMath.pairBonus(singleSpeed, pairSpeed),
                    AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
            pairOn.add(id);
            long now = System.currentTimeMillis();
            if (now - lastFlip.getOrDefault(id, 0L) >= flipCooldownMillis && visible(p)) { lastFlip.put(id, now); flipUp(p); }
        } else {
            pairOn.remove(id);
            hits.remove(id);
        }
    }

    /** Nobody sees the aura of a player who shouldn't be seen. */
    private static boolean visible(Player p) {
        if (p.getGameMode() == GameMode.SPECTATOR || p.isInvisible() || p.hasPotionEffect(PotionEffectType.INVISIBILITY)) return false;
        for (MetadataValue v : p.getMetadata("vanished")) if (v.asBoolean()) return false;   // common vanish plugins
        return true;
    }

    /** A couple of dark-red embers off each held blade, drifting up and fading to black. */
    private void aura(Player p, boolean main, boolean off) {
        Location base = p.getLocation();
        double yaw = Math.toRadians(base.getYaw());
        Vector fwd = new Vector(-Math.sin(yaw), 0, Math.cos(yaw)), right = new Vector(-Math.cos(yaw), 0, -Math.sin(yaw));
        double y = p.isSneaking() ? 0.55 : 0.75;
        World w = p.getWorld();
        // right hand is the main hand unless the player has set left-handed
        double side = p.getMainHand() == org.bukkit.inventory.MainHand.LEFT ? -1 : 1;
        if (main) puff(w, base.clone().add(0, y, 0).add(right.clone().multiply(0.38 * side)).add(fwd.clone().multiply(0.22)));
        if (off) puff(w, base.clone().add(0, y, 0).add(right.clone().multiply(-0.38 * side)).add(fwd.clone().multiply(0.22)));
    }

    private static void puff(World w, Location at) {
        w.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 2, 0.06, 0.12, 0.06, 0, EMBER);
    }

    // ------------------------------------------------------------------ equip flip

    /** Where a hand is, from the body's facing (not the camera, so the blades don't swing round with the view). */
    private static Location hand(Player p, double side, double lift) {
        Location base = p.getLocation();
        double yaw = Math.toRadians(p.getBodyYaw());
        Vector fwd = new Vector(-Math.sin(yaw), 0, Math.cos(yaw)), right = new Vector(-Math.cos(yaw), 0, -Math.sin(yaw));
        Location at = base.clone().add(0, (p.isSneaking() ? 0.55 : 0.75) + lift, 0)
                .add(right.multiply(0.38 * side)).add(fwd.multiply(0.22));
        at.setYaw(p.getBodyYaw() + 90);   // the blade's flat side faces sideways, so the flip reads from the side
        at.setPitch(0);
        return at;
    }

    /**
     * Both blades start pointing down at the hips, flip forward and up (a snappy half-turn with a small settle),
     * hold for a beat, then shrink into the held items. Curves in {@link NightrendMath}; one display per hand.
     */
    private void flipUp(Player p) {
        double side = p.getMainHand() == org.bukkit.inventory.MainHand.LEFT ? -1 : 1;
        ItemDisplay[] d = new ItemDisplay[2];
        double[] sides = {side, -side};
        for (int i = 0; i < 2; i++) {
            // show the daggers actually in hand, so the flip wears the same pack texture as the held items
            ItemStack held = i == 0 ? p.getInventory().getItemInMainHand() : p.getInventory().getItemInOffHand();
            ItemStack shown = isNightrend(held) ? held.asOne() : (i == 0 ? item() : offItem());
            d[i] = p.getWorld().spawn(hand(p, sides[i], 0), ItemDisplay.class, e -> {
                e.setItemStack(shown);
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                e.setPersistent(false);
                e.setTeleportDuration(1);
                e.setInterpolationDuration(1);
                e.setTransformation(frame(0));
            });
            p.hideEntity(plugin, d[i]);   // not for the flipper: your own camera keeps your real daggers, no copy mid-screen
            live.add(d[i]);
        }
        flipping.add(p.getUniqueId());
        // the flipper's own cue: both arms swing, since the flip copies are hidden from their camera
        p.swingMainHand();
        p.swingOffHand();
        Set<Player> viewers = new HashSet<>(p.getTrackedBy());
        hideHands(p, viewers);   // everyone else sees only the flipping copies, never two pairs
        World w = p.getWorld();
        w.playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.3f, 1.8f);
        int[] t = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, run -> {
            t[0]++;
            // put away mid-flip (the pair broke), or gone: stop at once, nothing left floating
            boolean gone = !p.isOnline() || p.isDead() || p.getWorld() != w || !d[0].isValid() || !d[1].isValid()
                    || !pairOn.contains(p.getUniqueId());
            if (gone || t[0] >= NightrendMath.FLIP_TOTAL) {
                // the handover, all in one tick: copies gone, real daggers back on everyone's screen
                for (ItemDisplay x : d) { if (x.isValid()) x.remove(); live.remove(x); }
                flipping.remove(p.getUniqueId());
                showHands(p, viewers);
                run.cancel();
                return;
            }
            for (int i = 0; i < 2; i++) {
                d[i].teleport(hand(p, sides[i], NightrendMath.flipLift(t[0])));
                d[i].setInterpolationDelay(0);
                d[i].setTransformation(frame(t[0]));
            }
            if (t[0] == NightrendMath.FLIP_TICKS - 6) {   // the blades click into the grip as they come upright
                w.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_NETHERITE, 0.6f, 1.5f);
                w.playSound(p.getLocation(), Sound.ITEM_TRIDENT_HIT, 0.3f, 1.9f);
            }
            if (t[0] == NightrendMath.FLIP_TICKS)
                for (ItemDisplay x : d) w.spawnParticle(Particle.DUST_COLOR_TRANSITION, x.getLocation(), 4, 0.08, 0.15, 0.08, 0, EDGE);
        }, 1L, 1L);
    }

    /** The display's local transform at tick {@code t}: pitch from the flip curve (in the blade's own plane), scale in and out. */
    private static Transformation frame(int t) {
        float s = (float) (0.75 * NightrendMath.flipScale(t));
        // the sprite points up-right at 45 degrees; -45 stands it upright, then the flip turns it from pointing down to up
        float rot = (float) Math.toRadians(-45 + NightrendMath.flipPitch(t));
        return new Transformation(new Vector3f(), new Quaternionf().rotateZ(rot), new Vector3f(s, s, s), new Quaternionf());
    }

    // ------------------------------------------------------------------ hits

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p) || e.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;
        if (!isNightrend(p.getInventory().getItemInMainHand()) || !(e.getEntity() instanceof LivingEntity target)) return;
        if (!enabled || !allowedAt.test(p.getLocation())) {
            e.setCancelled(true);
            p.sendActionBar(Ui.mm(enabled ? "<gray>The " + NAME + " <gray>only works in the <white>hub Arena<gray> for now"
                    : "<gray>The " + NAME + " <gray>is switched off right now"));
            return;
        }
        Location t = target.getLocation(), a = p.getLocation();
        Vector face = t.getDirection();
        boolean fromBehind = NightrendMath.behind(face.getX(), face.getZ(), a.getX() - t.getX(), a.getZ() - t.getZ(), behindArc);
        e.setDamage(e.getDamage() * NightrendMath.hitMultiplier(fromBehind, behindDamage));

        World w = p.getWorld();
        Location at = target.getLocation().add(0, target.getHeight() * 0.55, 0);
        if (fromBehind) {
            w.playSound(at, Sound.ENTITY_PLAYER_ATTACK_WEAK, 0.7f, 0.7f);   // the dull "glanced off" hit
        } else {
            w.playSound(at, Sound.ITEM_TRIDENT_HIT, 0.45f, 1.75f);           // a sharp, thin blade
            w.spawnParticle(Particle.DUST_COLOR_TRANSITION, at, 4, 0.15, 0.2, 0.15, 0, EDGE);
        }

        if (!pairOn.contains(p.getUniqueId())) return;
        int n = hits.merge(p.getUniqueId(), 1, Integer::sum);
        if (n >= 1000) hits.put(p.getUniqueId(), 0);
        // the blades take turns: main-hand slash on odd hits, the off hand swings and slashes on even ones
        if (NightrendMath.offHandHit(n)) Bukkit.getScheduler().runTaskLater(plugin, () -> slash(p, true), 1L);
        else slash(p, false);
    }

    /**
     * One blade's slash: a thin red arc sweeps across in front of you, starting on that blade's side. The off-hand
     * blade also swings the off arm (the main arm already swings with every attack). Left-handed players are mirrored.
     */
    private void slash(Player p, boolean offHand) {
        if (!p.isOnline() || p.isDead() || !pairOn.contains(p.getUniqueId())) return;
        if (offHand) p.swingOffHand();
        World w = p.getWorld();
        Location eye = p.getEyeLocation();
        double yaw = eye.getYaw();                                  // fixed at the start: the arc doesn't follow the camera
        Vector origin = eye.toVector().add(new Vector(0, -0.45, 0));
        double mainSide = p.getMainHand() == org.bukkit.inventory.MainHand.LEFT ? -1 : 1;
        double side = offHand ? -mainSide : mainSide;               // +1 = the right hand
        if (offHand) w.playSound(eye, Sound.PARTICLE_SOUL_ESCAPE, 0.6f, 1.5f);   // the quiet whisper of the second blade
        w.playSound(eye, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.25f, offHand ? 1.9f : 1.7f);
        double[] last = {NightrendMath.slashAngle(0)};
        int[] t = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, run -> {
            if (++t[0] > NightrendMath.SLASH_TICKS || !p.isOnline() || p.getWorld() != w) { run.cancel(); return; }
            double to = NightrendMath.slashAngle(t[0]);
            for (double a = last[0]; a <= to; a += 9) {
                double rad = Math.toRadians(yaw - side * a);        // starts on the blade's own side and crosses over
                double drop = (a + NightrendMath.SLASH_ARC_DEGREES / 2) / NightrendMath.SLASH_ARC_DEGREES * 0.35;   // high to low
                Vector pt = origin.clone().add(new Vector(-Math.sin(rad) * 1.5, 0.15 - drop, Math.cos(rad) * 1.5));
                w.spawnParticle(Particle.DUST_COLOR_TRANSITION, pt.getX(), pt.getY(), pt.getZ(), 1, 0, 0, 0, 0, EDGE);
            }
            last[0] = to;
        }, 0L, 1L);
    }

    // ------------------------------------------------------------------ EMP

    /** The EMP shuts off the pair speed bonus of everyone it reaches (never the caster's) for {@code emp-seconds}. */
    @Override
    public int hack(Location center, double radius, Player caster) {
        int n = 0;
        long until = System.currentTimeMillis() + empMillis;
        for (UUID id : new ArrayList<>(pairOn)) {
            Player p = Bukkit.getPlayer(id);
            if (p == null || id.equals(caster.getUniqueId()) || !EmpWave.reaches(center, p.getLocation(), radius)) continue;
            hackedUntil.put(id, until);
            setPair(p, false);
            p.getWorld().spawnParticle(Particle.SMOKE, p.getLocation().add(0, 0.8, 0), 10, 0.35, 0.3, 0.35, 0.01);
            p.playSound(p.getLocation(), Sound.BLOCK_FIRE_EXTINGUISH, 0.5f, 1.4f);
            p.sendActionBar(Ui.mm("<#B6FF3B>⚡ <gray>Your " + NAME + " <gray>pair bonus was <white>shut down <gray>by an EMP"));
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ safety and cleanup

    /** Flint is a crafting ingredient: a dagger never gets eaten by a recipe. */
    @EventHandler
    public void onCraft(PrepareItemCraftEvent e) {
        for (ItemStack it : e.getInventory().getMatrix()) if (isNightrend(it)) { e.getInventory().setResult(null); return; }
    }

    @EventHandler public void onQuit(PlayerQuitEvent e) { forget(e.getPlayer()); }
    @EventHandler public void onDeath(PlayerDeathEvent e) { forget(e.getPlayer()); }
    @EventHandler public void onWorld(PlayerChangedWorldEvent e) { forget(e.getPlayer()); }

    private void forget(Player p) {
        setPair(p, false);
        AttributeInstance ai = p.getAttribute(Attribute.ATTACK_SPEED);
        if (ai != null) ai.removeModifier(pairKey);   // belt and braces: never leave the bonus behind
        hits.remove(p.getUniqueId());
        if (!p.isOnline()) lastFlip.remove(p.getUniqueId());
        if (!p.isOnline() || p.isDead()) hackedUntil.remove(p.getUniqueId());
    }

    /** On plugin disable: take the pair bonus off everyone. */
    public void shutdown() {
        task.cancel();
        for (ItemDisplay x : live) if (x.isValid()) x.remove();
        live.clear();
        flipping.clear();
        for (Player p : Bukkit.getOnlinePlayers()) forget(p);
    }
}
