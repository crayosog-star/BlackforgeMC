package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.components.UseCooldownComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.function.Predicate;

/**
 * Potion of Darkness: a goth splash potion. Players it hits (where PvP is on) get a black flash, then the Warden's
 * total darkness for 5.5 s (Blindness + Darkness), plus an audio glitch: phantom footsteps and swings played on the WRONG side (the nearest
 * enemy's position mirrored left/right) and a heartbeat. Mobs it hits anywhere are confused for 2.5 s: they lose
 * their target, can't pick a new one, and stagger around. Does no damage.
 */
public final class DarknessPotion implements Listener {
    public static final String NAME = "<gradient:#6E2C83:#8C1C2E><bold>Potion of Darkness</bold></gradient>";
    public static final int DARK_TICKS = 110;         // 5.5 s of solid dark for players
    public static final int MOB_CONFUSE_TICKS = 50;   // 2.5 s of confusion for mobs
    public static final int COOLDOWN_SECONDS = 15;
    private static final long IMMUNE_MS = 4000;       // after the dark lifts: no chain-blinding

    // palette (see the plan's visual guide)
    static final Color VOID_BLACK = Color.fromRGB(0x0B0710), CRYPT_PLUM = Color.fromRGB(0x2A1033), GRAVE_PURPLE = Color.fromRGB(0x6E2C83);

    private final AlloyCore plugin;
    private final boolean enabled;
    private final NamespacedKey tagKey, cooldownGroup;
    private final Predicate<Location> pvpAt;          // where players can be darkened (supplied by each server)
    private final Map<UUID, Long> readyAt = new HashMap<>(), darkUntil = new HashMap<>(), immuneUntil = new HashMap<>(), confusedUntil = new HashMap<>();
    private final Random rnd = new Random();

    public DarknessPotion(AlloyCore plugin, Predicate<Location> pvpAt) {
        this.plugin = plugin;
        this.pvpAt = pvpAt;
        this.enabled = plugin.getConfig().getBoolean("darkness-potion.enabled", true);
        this.tagKey = new NamespacedKey(plugin, "darkness_potion");
        this.cooldownGroup = new NamespacedKey("blackforge", "darkness_potion");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::expire, 200L, 200L);
    }

    // ------------------------------------------------------------------ the item

    public ItemStack item() {
        ItemStack it = new ItemStack(Material.SPLASH_POTION);
        it.editMeta(PotionMeta.class, m -> {
            m.setColor(CRYPT_PLUM);   // pure black would make the bottle look empty
            m.displayName(Ui.mm(NAME));
            m.lore(List.of(
                    Ui.mm("<gray>Throw it to steal their sight"),
                    Ui.mm("<gray>Total darkness for <white>5.5s <gray>to everyone it hits"),
                    Ui.mm("<gray>Mobs lose their target and stagger"),
                    Ui.mm("<gray>Only darkens players where PvP is on"),
                    Ui.mm(""),
                    Ui.mm("<gray>Cooldown: <white>" + COOLDOWN_SECONDS + "s"),
                    Ui.mm("<dark_gray>BlackForgeMC PvP item")));
            m.addItemFlags(ItemFlag.values());
            UseCooldownComponent cd = m.getUseCooldown();
            cd.setCooldownSeconds(COOLDOWN_SECONDS);
            cd.setCooldownGroup(cooldownGroup);
            m.setUseCooldown(cd);
            m.getPersistentDataContainer().set(tagKey, PersistentDataType.BYTE, (byte) 1);
        });
        return it;
    }

    public boolean isDarkness(ItemStack it) {
        return it != null && it.getType() == Material.SPLASH_POTION && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(tagKey, PersistentDataType.BYTE);
    }

    public void give(Player p) {
        p.getInventory().addItem(item()).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        p.sendMessage(Ui.mm("<#6E2C83>☠ <gray>You received a " + NAME + "<gray>."));
    }

    // ------------------------------------------------------------------ throwing

    @EventHandler(priority = EventPriority.HIGH)
    public void onThrow(PlayerInteractEvent e) {
        Action a = e.getAction();
        if ((a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) || !isDarkness(e.getItem())) return;
        Player p = e.getPlayer();
        if (!enabled) { e.setCancelled(true); p.sendActionBar(Ui.mm("<gray>The Potion of Darkness isn't switched on yet.")); return; }
        long left = readyAt.getOrDefault(p.getUniqueId(), 0L) - System.currentTimeMillis();
        if (left > 0) {
            e.setCancelled(true);
            p.sendActionBar(Ui.mm("<#6E2C83>☠ <gray>The dark is still gathering... <white>" + (left / 1000 + 1) + "s"));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof ThrownPotion pot) || !isDarkness(pot.getItem()) || !(pot.getShooter() instanceof Player p)) return;
        readyAt.put(p.getUniqueId(), System.currentTimeMillis() + COOLDOWN_SECONDS * 1000L);
        // a thin trail of black smoke while it flies
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (!pot.isValid()) { task.cancel(); return; }
            pot.getWorld().spawnParticle(Particle.SMOKE, pot.getLocation(), 2, 0.03, 0.03, 0.03, 0.005);
            pot.getWorld().spawnParticle(Particle.DUST, pot.getLocation(), 1, 0.05, 0.05, 0.05, 0, new Particle.DustOptions(CRYPT_PLUM, 1.0f));
        }, 1L, 1L);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSplash(PotionSplashEvent e) {
        ThrownPotion pot = e.getPotion();
        if (!isDarkness(pot.getItem())) return;
        Location at = pot.getLocation();
        List<LivingEntity> hit = new ArrayList<>(e.getAffectedEntities());
        for (LivingEntity le : hit) e.setIntensity(le, 0);   // vanilla applies nothing; we do it all below
        splashEffects(at);
        long now = System.currentTimeMillis();
        for (LivingEntity le : hit) {
            if (le instanceof Player p) darken(p, now);
            else if (le instanceof Mob m) confuse(m, now);
        }
    }

    // ------------------------------------------------------------------ players: the dark + the audio glitch

    private void darken(Player p, long now) {
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) return;
        if (!pvpAt.test(p.getLocation())) return;
        boolean stillDark = darkUntil.getOrDefault(p.getUniqueId(), 0L) > now;
        if (!stillDark && immuneUntil.getOrDefault(p.getUniqueId(), 0L) > now) {
            p.sendActionBar(Ui.mm("<gray>You shake off the dark."));
            return;
        }
        // reset, never extend: two potions are not 5 s of blindness
        // Blindness for the whole time keeps it fully dark (Darkness alone pulses); Darkness on top adds the pulsing edges
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, DARK_TICKS, 0, false, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, DARK_TICKS, 0, false, false, true));
        darkUntil.put(p.getUniqueId(), now + DARK_TICKS * 50L);
        immuneUntil.put(p.getUniqueId(), now + DARK_TICKS * 50L + IMMUNE_MS);
        p.sendActionBar(Ui.mm("<#8C1C2E>☠ <#6E2C83>The dark takes you"));
        if (stillDark) return;   // the glitch below is already running for them

        UUID id = p.getUniqueId();
        int[] t = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            Player v = Bukkit.getPlayer(id);
            if (v == null || !v.isOnline() || darkUntil.getOrDefault(id, 0L) < System.currentTimeMillis()) {
                if (v != null && v.isOnline()) lift(v);
                task.cancel();
                return;
            }
            int n = t[0]++;
            if (n % 15 == 0) v.playSound(v.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 1.0f, 1.0f);
            if (n % 6 == 0) phantomSound(v);
            if (n % 5 == 0) v.getWorld().spawnParticle(Particle.SCULK_SOUL, v.getEyeLocation().add(0, 0.4, 0), 1, 0.3, 0.1, 0.3, 0.01);
        }, 0L, 1L);
    }

    /**
     * The audio glitch: a footstep or swing played on the WRONG side. The nearest other player's position is mirrored
     * across the victim's line of sight (left becomes right); with nobody near, it comes from a random spot. Only the
     * victim hears it.
     */
    private void phantomSound(Player v) {
        Location me = v.getLocation();
        Vector facing = me.getDirection().setY(0);
        if (facing.lengthSquared() < 1e-6) facing = new Vector(0, 0, 1);
        facing.normalize();
        Vector right = new Vector(-facing.getZ(), 0, facing.getX());
        Player near = null;
        double best = 16 * 16;
        for (Player o : v.getWorld().getPlayers()) {
            if (o == v) continue;
            double d = o.getLocation().distanceSquared(me);
            if (d < best) { best = d; near = o; }
        }
        Vector off;
        if (near != null) {
            off = near.getLocation().toVector().subtract(me.toVector()).setY(0);
            off.subtract(right.clone().multiply(2 * off.dot(right)));   // mirror: left <-> right
        } else {
            off = right.clone().multiply(rnd.nextBoolean() ? 3 : -3).add(facing.clone().multiply(rnd.nextDouble() * 4 - 2));
        }
        Location at = me.clone().add(off);
        Sound s = switch (rnd.nextInt(4)) {
            case 0 -> Sound.ENTITY_PLAYER_ATTACK_SWEEP;
            case 1 -> Sound.ENTITY_PLAYER_ATTACK_STRONG;
            default -> Sound.BLOCK_STONE_STEP;
        };
        v.playSound(at, s, SoundCategory.PLAYERS, 0.8f, 0.9f + rnd.nextFloat() * 0.2f);
    }

    private void lift(Player v) {
        v.playSound(v.getLocation(), Sound.BLOCK_SCULK_SHRIEKER_SHRIEK, SoundCategory.PLAYERS, 0.15f, 1.6f);
        v.getWorld().spawnParticle(Particle.SCULK_CHARGE_POP, v.getEyeLocation(), 4, 0.3, 0.2, 0.3, 0.01);
    }

    // ------------------------------------------------------------------ mobs: confusion

    private static boolean immuneMob(Mob m) {
        return m instanceof Boss || m instanceof Warden || m instanceof EnderDragon;
    }

    private void confuse(Mob m, long now) {
        if (immuneMob(m)) return;
        m.setTarget(null);
        confusedUntil.put(m.getUniqueId(), now + MOB_CONFUSE_TICKS * 50L);
        m.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, MOB_CONFUSE_TICKS, 1, false, false, false));
        UUID id = m.getUniqueId();
        int[] t = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (!m.isValid() || m.isDead() || confusedUntil.getOrDefault(id, 0L) < System.currentTimeMillis()) { task.cancel(); return; }
            int n = t[0]++;
            if (n % 10 == 0) {   // stagger somewhere new every half second
                Location to = m.getLocation().add(rnd.nextDouble() * 6 - 3, 0, rnd.nextDouble() * 6 - 3);
                m.getPathfinder().moveTo(to, 0.9);
            }
            if (n % 5 == 0) m.getWorld().spawnParticle(Particle.SCULK_SOUL, m.getEyeLocation().add(0, 0.5, 0), 1, 0.25, 0.05, 0.25, 0.01);
        }, 0L, 1L);
    }

    /** A confused mob can't pick anyone to chase until it clears. */
    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        if (e.getTarget() == null) return;
        Long until = confusedUntil.get(e.getEntity().getUniqueId());
        if (until != null && until > System.currentTimeMillis()) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ effects + housekeeping

    private void splashEffects(Location at) {
        World w = at.getWorld();
        Location c = at.clone().add(0, 0.5, 0);
        w.spawnParticle(Particle.SQUID_INK, c, 60, 1.2, 0.5, 1.2, 0.05);
        w.spawnParticle(Particle.DUST, c, 40, 1.4, 0.5, 1.4, 0, new Particle.DustOptions(VOID_BLACK, 2.2f));
        w.spawnParticle(Particle.DUST, c, 25, 1.2, 0.4, 1.2, 0, new Particle.DustOptions(CRYPT_PLUM, 1.8f));
        for (int i = 0; i < 16; i++) {
            double a = 2 * Math.PI * i / 16;
            Location edge = c.clone().add(2 * Math.cos(a), 0, 2 * Math.sin(a));
            w.spawnParticle(Particle.SCULK_CHARGE_POP, edge, 1, 0.1, 0.1, 0.1, 0.01);
            w.spawnParticle(Particle.SCULK_SOUL, edge, 1, 0.1, 0.1, 0.1, 0.03);
        }
        w.playSound(at, Sound.ENTITY_SPLASH_POTION_BREAK, SoundCategory.PLAYERS, 1.0f, 0.6f);
        w.playSound(at, Sound.ENTITY_WARDEN_HEARTBEAT, SoundCategory.PLAYERS, 1.0f, 0.9f);
        // the cloud lingers, thinning out over the 2.5 s
        int[] t = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            int n = t[0]++;
            if (n >= MOB_CONFUSE_TICKS / 5) { task.cancel(); return; }
            int count = Math.max(1, 12 - n);
            w.spawnParticle(Particle.DUST, c, count, 1.3, 0.4, 1.3, 0, new Particle.DustOptions(VOID_BLACK, 1.8f));
            w.spawnParticle(Particle.SMOKE, c, count / 2 + 1, 1.2, 0.3, 1.2, 0.005);
        }, 5L, 5L);
    }

    /** Forget old entries so the maps don't grow forever. */
    private void expire() {
        long now = System.currentTimeMillis();
        readyAt.values().removeIf(t -> t < now);
        darkUntil.values().removeIf(t -> t < now);
        immuneUntil.values().removeIf(t -> t < now);
        confusedUntil.values().removeIf(t -> t < now);
    }
}
