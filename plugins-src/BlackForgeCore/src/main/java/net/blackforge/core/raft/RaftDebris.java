package net.blackforge.core.raft;

import net.blackforge.core.Ui;
import org.bukkit.*;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.*;

import static net.blackforge.core.raft.RaftStructures.SEA;

/**
 * Debris drifting past the raft with the wind: planks, plastic, scrap, thatch and barrels. It spawns upstream of each raft that has
 * someone aboard, floats by, and is hauled in with a Hook (or picked up by anyone swimming through it).
 */
final class RaftDebris {
    static final String TAG = "raft_debris", BARREL = "raft_barrel";
    private static final double DRIFT = 0.16;           // blocks per tick

    private final RaftModule mod;
    private final Random rnd = new Random();
    private final Map<UUID, UUID> pulled = new HashMap<>();   // debris -> the player pulling it
    private final Map<UUID, Long> cooldown = new HashMap<>();

    RaftDebris(RaftModule mod) {
        this.mod = mod;
        Bukkit.getScheduler().runTaskTimer(mod.plugin(), this::spawnTick, 60L, 50L);
        Bukkit.getScheduler().runTaskTimer(mod.plugin(), this::moveTick, 40L, 2L);
    }

    static Vector windVector(RaftTeam t) { int[] d = RaftTeam.DIRS[t.wind]; return new Vector(d[0], 0, d[1]); }

    private List<Item> debrisOf(RaftTeam t) {
        World w = mod.world();
        int[] c = t.centre();
        List<Item> out = new ArrayList<>();
        for (Entity e : w.getNearbyEntities(new Location(w, c[0] + 0.5, SEA, c[1] + 0.5), 110, 12, 110))
            if (e instanceof Item it && it.getScoreboardTags().contains(TAG)) out.add(it);
        return out;
    }

    private void spawnTick() {
        World w = mod.world();
        if (w == null) return;
        for (RaftTeam t : mod.activeTeams()) {
            if (t.tiles.isEmpty()) continue;
            if (debrisOf(t).size() >= 16 || rnd.nextInt(100) >= 70) continue;
            int[] c = t.centre();
            Vector wind = windVector(t), side = new Vector(-wind.getZ(), 0, wind.getX());
            double back = 60 + rnd.nextInt(16), off = (rnd.nextDouble() - 0.5) * 36;
            Location at = new Location(w, c[0] + 0.5 - wind.getX() * back + side.getX() * off, SEA + 0.05, c[1] + 0.5 - wind.getZ() * back + side.getZ() * off);
            if (!w.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4) || w.getBlockAt(at.getBlockX(), SEA - 1, at.getBlockZ()).getType() != Material.WATER) continue;
            int roll = rnd.nextInt(100);
            ItemStack stack;
            boolean barrel = false;
            if (roll < 30) stack = new ItemStack(Material.OAK_PLANKS, 1 + rnd.nextInt(2));
            else if (roll < 50) stack = RaftItems.make("plastic", 1 + rnd.nextInt(2));
            else if (roll < 68) stack = RaftItems.make("scrap", 1 + rnd.nextInt(2));
            else if (roll < 82) stack = RaftItems.make("thatch", 1 + rnd.nextInt(3));
            else if (roll < 94) { stack = new ItemStack(Material.BARREL); barrel = true; }
            else stack = new ItemStack(rnd.nextBoolean() ? Material.COD : Material.SALMON);
            boolean isBarrel = barrel;
            Item it = w.dropItem(at, stack, i -> {
                i.setGravity(false);
                i.setPickupDelay(Short.MAX_VALUE);
                i.setPersistent(false);
                i.setUnlimitedLifetime(true);
                i.setVelocity(new Vector(0, 0, 0));
                i.addScoreboardTag(TAG);
                if (isBarrel) { i.addScoreboardTag(BARREL); i.customName(Ui.mm("<#c8a165>Floating Barrel")); i.setCustomNameVisible(true); }
            });
            it.setTicksLived(1);
        }
    }

    private void moveTick() {
        World w = mod.world();
        if (w == null) return;
        for (RaftTeam t : mod.activeTeams()) {
            int[] c = t.centre();
            Vector wind = windVector(t).multiply(DRIFT * 2);
            for (Item it : debrisOf(t)) {
                if (pulled.containsKey(it.getUniqueId())) continue;
                Location l = it.getLocation();
                double dist = Math.hypot(l.getX() - c[0], l.getZ() - c[1]);
                if (dist > 95 || it.getTicksLived() > 20 * 200) { it.remove(); continue; }
                l.add(wind);
                l.setY(SEA + 0.05);
                it.teleport(l);
                for (Player p : w.getPlayers()) if (p.getLocation().distanceSquared(l) < 1.6 * 1.6) { collect(p, it); break; }
            }
        }
        // hauling
        for (var e : new ArrayList<>(pulled.entrySet())) {
            Entity de = Bukkit.getEntity(e.getKey());
            Player p = Bukkit.getPlayer(e.getValue());
            if (!(de instanceof Item it) || !it.isValid() || p == null || !p.isOnline() || p.getWorld() != w) { pulled.remove(e.getKey()); continue; }
            Location to = p.getEyeLocation().add(0, -0.7, 0);
            Vector dir = to.toVector().subtract(it.getLocation().toVector());
            double len = dir.length();
            if (len < 1.7) { pulled.remove(e.getKey()); collect(p, it); continue; }
            double speed = RaftItems.is(p.getInventory().getItemInMainHand(), "adv_hook") || RaftItems.is(p.getInventory().getItemInOffHand(), "adv_hook") ? 1.5 : 1.0;
            Location nl = it.getLocation().add(dir.normalize().multiply(Math.min(speed, len)));
            it.teleport(nl);
            for (int i = 1; i <= 6; i++) {
                Location pt = p.getEyeLocation().add(0, -0.4, 0).add(nl.toVector().subtract(p.getEyeLocation().toVector()).multiply(i / 7.0));
                w.spawnParticle(Particle.CRIT, pt, 1, 0, 0, 0, 0);
            }
        }
    }

    /** Right-click with a hook: haul in the debris you are looking toward. */
    boolean cast(Player p, boolean advanced) {
        long now = System.currentTimeMillis();
        if (cooldown.getOrDefault(p.getUniqueId(), 0L) > now) return true;
        RaftTeam t = mod.teamOf(p);
        if (t == null) return true;
        cooldown.put(p.getUniqueId(), now + 600);
        double reach = advanced ? 40 : 24;
        Vector look = p.getEyeLocation().getDirection();
        Item best = null; double bestDot = 0.93;
        for (Item it : debrisOf(t)) {
            if (pulled.containsKey(it.getUniqueId())) continue;
            Vector to = it.getLocation().toVector().subtract(p.getEyeLocation().toVector());
            if (to.length() > reach || to.length() < 1) continue;
            double dot = to.clone().normalize().dot(look);
            if (dot > bestDot) { bestDot = dot; best = it; }
        }
        if (best == null) { p.sendActionBar(Ui.mm("<gray>Nothing in reach that way.")); return true; }
        pulled.put(best.getUniqueId(), p.getUniqueId());
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_FISHING_BOBBER_THROW, 1f, 1.1f);
        return true;
    }

    private void collect(Player p, Item it) {
        if (!it.isValid()) return;
        pulled.remove(it.getUniqueId());
        if (it.getScoreboardTags().contains(BARREL)) {
            List<ItemStack> loot = RaftLoot.roll("barrel", rnd);
            for (ItemStack s : loot) p.getInventory().addItem(s).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
            p.sendActionBar(Ui.mm("<#c8a165>A floating barrel! <gray>" + loot.size() + " finds."));
            p.playSound(p.getLocation(), Sound.BLOCK_BARREL_OPEN, 1f, 1f);
            p.getWorld().spawnParticle(Particle.SPLASH, it.getLocation(), 20, 0.4, 0.2, 0.4, 0.1);
        } else {
            ItemStack s = it.getItemStack();
            p.getInventory().addItem(s).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.7f, 1.2f);
            p.getWorld().spawnParticle(Particle.SPLASH, it.getLocation(), 6, 0.2, 0.1, 0.2, 0.05);
        }
        it.remove();
    }

    /** Right after a restart the old drifting items are gone (they are not saved); this clears strays. */
    void clearAll() {
        World w = mod.world();
        if (w == null) return;
        for (Item it : w.getEntitiesByClass(Item.class)) if (it.getScoreboardTags().contains(TAG)) it.remove();
    }
}
