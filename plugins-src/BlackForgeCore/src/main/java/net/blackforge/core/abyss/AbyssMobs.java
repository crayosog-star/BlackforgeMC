package net.blackforge.core.abyss;

import net.blackforge.core.BlackForgeCore;
import net.blackforge.core.Ui;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Random;

/**
 * What haunts the Abyssal Sea besides the serpent. Mob spawning is off in that world, so this places them itself around each
 * player: Ghost Pirates (invisible skeletons, so only a floating coat and cutlass show), Ghost Gunners (the same with a bow),
 * the rare Ghost Captain, and Drowned Deckhands in the water. All are skeleton-tier (20 health) bar the captain. They stand on
 * wrecks, islands and lighthouses, never near the dock, and vanish on restart. Config: abyss.mobs (true), abyss.mobs-per-player (6).
 */
final class AbyssMobs implements Listener {
    static final String TAG = "abyss_mob";
    private final BlackForgeCore plugin;
    private final World world;
    private final Random rnd = new Random();

    AbyssMobs(BlackForgeCore plugin, World world) {
        this.plugin = plugin;
        this.world = world;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 100L, 40L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::wisps, 100L, 10L);
    }

    private void tick() {
        if (!plugin.getConfig().getBoolean("abyss.mobs", true)) return;
        int cap = plugin.getConfig().getInt("abyss.mobs-per-player", 6);
        for (Entity e : world.getEntities()) {                                   // drop the ones nobody is near
            if (!e.getScoreboardTags().contains(TAG)) continue;
            boolean near = false;
            for (Player p : world.getPlayers()) if (p.getLocation().distanceSquared(e.getLocation()) < 90 * 90) { near = true; break; }
            if (!near) e.remove();
        }
        for (Player p : world.getPlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE) continue;
            Location pl = p.getLocation();
            if (Math.hypot(pl.getX(), pl.getZ()) < 70) continue;                  // the dock stays safe
            int have = 0;
            for (Entity e : p.getNearbyEntities(48, 32, 48)) if (e.getScoreboardTags().contains(TAG)) have++;
            if (have >= cap || rnd.nextDouble() > 0.4) continue;
            for (int tries = 0; tries < 6; tries++) {
                double a = rnd.nextDouble() * Math.PI * 2, d = 16 + rnd.nextDouble() * 20;
                int x = (int) Math.floor(pl.getX() + Math.cos(a) * d), z = (int) Math.floor(pl.getZ() + Math.sin(a) * d);
                if (!world.isChunkLoaded(x >> 4, z >> 4) || Math.hypot(x, z) < 60) continue;
                if (spawnAt(x, z)) break;
            }
        }
    }

    private boolean spawnAt(int x, int z) {
        int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        Block top = world.getBlockAt(x, y, z);
        if (top.getType() == Material.WATER) {                                   // open water: a drowned deckhand, rarely
            if (rnd.nextInt(4) != 0) return false;
            deckhand(new Location(world, x + 0.5, y - 1, z + 0.5));
            return true;
        }
        if (!top.getType().isSolid() || top.getType().name().contains("GLASS") || y < AbyssGenerator.SEA - 3) return false;
        Block a = top.getRelative(0, 1, 0), b = top.getRelative(0, 2, 0);
        if (!a.isPassable() || !b.isPassable() || a.isLiquid()) return false;
        Location at = new Location(world, x + 0.5, y + 1, z + 0.5, rnd.nextFloat() * 360f, 0f);
        int r = rnd.nextInt(12);
        if (r == 0) pirate(at, true, false); else if (r < 5) pirate(at, false, true); else pirate(at, false, false);
        return true;
    }

    private static ItemStack dyed(Material m, int rgb) {
        ItemStack it = new ItemStack(m);
        LeatherArmorMeta lm = (LeatherArmorMeta) it.getItemMeta();
        lm.setColor(Color.fromRGB(rgb));
        it.setItemMeta(lm);
        return it;
    }

    private void ghost(Skeleton s, String name, boolean gunner, boolean captain) {
        s.addScoreboardTag(TAG);
        s.setPersistent(false);
        s.setRemoveWhenFarAway(true);
        s.setShouldBurnInDay(false);
        s.customName(Ui.mm(captain ? "<#7ff0e0><bold>" + name : "<#9fe8e0>" + name));
        s.setCustomNameVisible(false);
        s.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, PotionEffect.INFINITE_DURATION, 0, false, false));
        EntityEquipment eq = s.getEquipment();
        eq.setHelmet(dyed(Material.LEATHER_HELMET, captain ? 0x14141c : 0x1b3d42));
        eq.setChestplate(dyed(Material.LEATHER_CHESTPLATE, captain ? 0x1e5c62 : 0x2a6f75));
        eq.setLeggings(dyed(Material.LEATHER_LEGGINGS, 0x2c2638));
        eq.setBoots(dyed(Material.LEATHER_BOOTS, 0x22180f));
        eq.setItemInMainHand(new ItemStack(gunner ? Material.BOW : captain ? Material.IRON_SWORD : Material.STONE_SWORD));
        for (EquipmentSlot sl : EquipmentSlot.values()) eq.setDropChance(sl, 0f);
        if (captain) {
            s.getAttribute(Attribute.MAX_HEALTH).setBaseValue(40);
            s.setHealth(40);
        }
        world.spawnParticle(Particle.SOUL, s.getLocation().add(0, 1, 0), 14, 0.3, 0.6, 0.3, 0.02);
        world.playSound(s.getLocation(), Sound.PARTICLE_SOUL_ESCAPE, 1f, 0.7f);
    }

    private void pirate(Location at, boolean captain, boolean gunner) {
        world.spawn(at, Skeleton.class, s -> ghost(s, captain ? "Ghost Captain" : gunner ? "Ghost Gunner" : "Ghost Pirate", gunner, captain));
    }

    private void deckhand(Location at) {
        world.spawn(at, Drowned.class, d -> {
            d.addScoreboardTag(TAG);
            d.setPersistent(false);
            d.setRemoveWhenFarAway(true);
            d.setBaby(false);
            d.customName(Ui.mm("<#5fb8c0>Drowned Deckhand"));
            d.setCustomNameVisible(false);
            EntityEquipment eq = d.getEquipment();
            eq.setHelmet(dyed(Material.LEATHER_HELMET, 0x14141c));
            eq.setItemInMainHand(new ItemStack(Material.IRON_SWORD));
            for (EquipmentSlot sl : EquipmentSlot.values()) eq.setDropChance(sl, 0f);
        });
    }

    /** Soul wisps drifting off the ghosts, so they read as ghosts. */
    private void wisps() {
        for (Entity e : world.getEntities())
            if (e instanceof Skeleton s && s.getScoreboardTags().contains(TAG))
                world.spawnParticle(Particle.SOUL_FIRE_FLAME, s.getLocation().add(0, 1.0, 0), 1, 0.25, 0.5, 0.25, 0.005);
    }

    @EventHandler
    public void onCombust(EntityCombustEvent e) {
        if (e.getEntity().getScoreboardTags().contains(TAG) && e.getEntity() instanceof Skeleton) e.setCancelled(true);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        LivingEntity m = e.getEntity();
        if (!m.getScoreboardTags().contains(TAG)) return;
        e.getDrops().clear();
        e.setDroppedExp(m instanceof Skeleton ? 8 : 6);
        boolean captain = m.getAttribute(Attribute.MAX_HEALTH).getBaseValue() > 30;
        if (rnd.nextInt(3) == 0) e.getDrops().add(new ItemStack(Material.BONE, 1 + rnd.nextInt(2)));
        if (rnd.nextInt(2) == 0) e.getDrops().add(new ItemStack(Material.GOLD_NUGGET, 2 + rnd.nextInt(5)));
        if (rnd.nextInt(5) == 0) e.getDrops().add(new ItemStack(Material.PRISMARINE_SHARD, 1 + rnd.nextInt(2)));
        if (rnd.nextInt(7) == 0) e.getDrops().add(new ItemStack(Material.GLOW_INK_SAC, 1 + rnd.nextInt(2)));
        if (captain) {
            e.getDrops().add(new ItemStack(Material.GOLD_INGOT, 2 + rnd.nextInt(4)));
            if (rnd.nextInt(4) == 0) e.getDrops().add(new ItemStack(Material.DIAMOND));
            if (rnd.nextInt(10) == 0) e.getDrops().add(new ItemStack(Material.HEART_OF_THE_SEA));
        }
        world.spawnParticle(Particle.SOUL, m.getLocation().add(0, 1, 0), 18, 0.3, 0.6, 0.3, 0.04);
    }
}
