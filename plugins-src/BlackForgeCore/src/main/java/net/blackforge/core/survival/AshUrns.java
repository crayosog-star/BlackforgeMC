package net.blackforge.core.survival;

import net.blackforge.core.Lab;
import net.blackforge.core.Ui;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.util.*;

/**
 * Ash Urns (Survival 2 experiment "ash-urns"): when you die (not to a player, and not in a PvP zone) your items and XP
 * go into an urn where you fell. It is yours alone for a few minutes, then anyone may open it; after an hour it
 * crumbles and spills what's left. Urns are saved, so a restart never loses them. The pot, label and click box are
 * display entities that aren't saved: they are put back whenever the urn's chunk is loaded.
 */
final class AshUrns implements Listener {
    static final String NAME = "<gradient:#bdb6a8:#e0c48a><bold>Ash Urns</bold></gradient>";
    private static final String ID = "ash-urns";

    private static final class Urn {
        UUID id, owner; String ownerName; String world; double x, y, z; long diedAt; List<ItemStack> items; int xp;
        ItemDisplay pot; TextDisplay label; Interaction box;
        Location at() { World w = Bukkit.getWorld(world); return w == null ? null : new Location(w, x, y, z); }
    }

    private final LabExperiments.Ctx c;
    private final Map<UUID, Urn> urns = new LinkedHashMap<>();
    private final NamespacedKey tag;
    private final File file;
    private final long lockMillis, lifeMillis;

    AshUrns(LabExperiments.Ctx c) {
        this.c = c;
        this.tag = new NamespacedKey(c.plugin(), "ash_urn");
        this.file = new File(c.plugin().getDataFolder(), "lab/urns.yml");
        this.lockMillis = c.plugin().getConfig().getLong("lab-experiments.ash-urns.lock-minutes", 10) * 60_000L;
        this.lifeMillis = c.plugin().getConfig().getLong("lab-experiments.ash-urns.life-minutes", 60) * 60_000L;
        load();
        Bukkit.getPluginManager().registerEvents(this, c.plugin());
        Bukkit.getScheduler().runTaskTimer(c.plugin(), this::tick, 40L, 20L);
    }

    // ------------------------------------------------------------------ dying

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent e) {
        if (!Lab.enabled(ID) || e.getKeepInventory() || e.getDrops().isEmpty()) return;
        Player p = e.getEntity();
        if (p.getKiller() != null) return;                        // PvP loot drops as normal
        if (c.protectedArea().test(p.getLocation())) return;      // spawn, KOTH, Scrapyard: unchanged
        Location at = safeSpot(p.getLocation());
        Urn u = new Urn();
        u.id = UUID.randomUUID(); u.owner = p.getUniqueId(); u.ownerName = p.getName();
        u.world = at.getWorld().getName(); u.x = at.getBlockX() + 0.5; u.y = at.getBlockY(); u.z = at.getBlockZ() + 0.5;
        u.diedAt = System.currentTimeMillis();
        u.items = new ArrayList<>(e.getDrops());
        u.xp = e.getDroppedExp();
        e.getDrops().clear();
        e.setDroppedExp(0);
        urns.put(u.id, u);
        save();
        p.sendMessage(Ui.mm(NAME + " <gray>Your things are in an urn at <white>" + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ()
                + "<gray>. Only you can open it for <white>" + lockMillis / 60_000 + " minutes<gray>."));
    }

    /** Where the urn goes: the death spot, or the nearest safe air above solid ground if that's lava, water or the void. */
    private Location safeSpot(Location died) {
        World w = died.getWorld();
        int x = died.getBlockX(), z = died.getBlockZ();
        int y = Math.max(w.getMinHeight() + 1, Math.min(w.getMaxHeight() - 2, died.getBlockY()));
        for (int dy = 0; dy < 48 && y + dy < w.getMaxHeight() - 1; dy++) {
            Block b = w.getBlockAt(x, y + dy, z);
            if (b.isPassable() && !b.isLiquid() && b.getRelative(0, -1, 0).getType().isSolid()) return b.getLocation();
        }
        Block top = w.getHighestBlockAt(x, z);
        if (top.getType().isSolid() && top.getY() > w.getMinHeight()) return top.getLocation().add(0, 1, 0);
        return c.spawn().get().getBlock().getLocation();          // fell into the void: the urn waits at spawn
    }

    // ------------------------------------------------------------------ opening

    @EventHandler
    public void onClick(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !(e.getRightClicked() instanceof Interaction i)) return;
        String id = i.getPersistentDataContainer().get(tag, PersistentDataType.STRING);
        if (id == null) return;
        e.setCancelled(true);
        Urn u = urns.get(UUID.fromString(id));
        if (u == null) { i.remove(); return; }
        Player p = e.getPlayer();
        long now = System.currentTimeMillis();
        boolean owner = p.getUniqueId().equals(u.owner);
        if (!LabMath.urnOpenFor(owner, u.diedAt, now, lockMillis)) {
            LabExperiments.bar(p, "<gray>" + u.ownerName + "'s urn opens to everyone in <white>" + LabMath.clock(LabMath.urnLockLeft(u.diedAt, now, lockMillis)));
            p.playSound(p.getLocation(), Sound.BLOCK_DECORATED_POT_INSERT_FAIL, 0.8f, 0.8f);
            return;
        }
        Map<Integer, ItemStack> left = p.getInventory().addItem(u.items.toArray(ItemStack[]::new));
        if (u.xp > 0) { p.giveExp(u.xp); u.xp = 0; }
        u.items = new ArrayList<>(left.values());
        Location at = u.at();
        if (at != null) {
            at.getWorld().spawnParticle(Particle.WHITE_ASH, at.clone().add(0, 0.6, 0), 40, 0.3, 0.4, 0.3, 0.02);
            at.getWorld().playSound(at, Sound.BLOCK_DECORATED_POT_SHATTER, 0.9f, owner ? 1.1f : 0.8f);
        }
        if (u.items.isEmpty()) {
            despawn(u);
            urns.remove(u.id);
            if (!owner) {
                Player o = Bukkit.getPlayer(u.owner);
                if (o != null) o.sendMessage(Ui.mm(NAME + " <white>" + p.getName() + " <gray>emptied your urn."));
            }
        } else {
            LabExperiments.bar(p, "<gray>Your inventory is full: <white>" + u.items.size() + "<gray> stacks are still in the urn.");
        }
        save();
    }

    // ------------------------------------------------------------------ every second

    private void tick() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Iterator<Urn> it = urns.values().iterator(); it.hasNext(); ) {
            Urn u = it.next();
            Location at = u.at();
            if (at == null) continue;
            if (LabMath.urnExpired(u.diedAt, now, lifeMillis)) {
                for (ItemStack s : u.items) at.getWorld().dropItemNaturally(at.clone().add(0, 0.5, 0), s);
                if (u.xp > 0) at.getWorld().spawn(at, ExperienceOrb.class, o -> o.setExperience(u.xp));
                despawn(u);
                it.remove();
                changed = true;
                continue;
            }
            if (!at.getWorld().isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) continue;
            if (u.pot == null || !u.pot.isValid()) spawn(u, at);
            long left = LabMath.urnLockLeft(u.diedAt, now, lockMillis);
            Ui.setText(u.label, "<gradient:#bdb6a8:#e0c48a>" + u.ownerName + "'s urn</gradient>\n"
                    + (left > 0 ? "<gray>Locked <white>" + LabMath.clock(left) : "<#e0c48a>Open to anyone")
                    + "\n<dark_gray>crumbles in " + LabMath.clock((u.diedAt + lifeMillis - now) / 1000));
            if (now / 1000 % 3 == 0) at.getWorld().spawnParticle(Particle.SMOKE, at.clone().add(0, 0.9, 0), 2, 0.05, 0.05, 0.05, 0.005);
        }
        if (changed) save();
    }

    private void spawn(Urn u, Location at) {
        despawn(u);
        World w = at.getWorld();
        u.pot = w.spawn(at.clone().add(0, 0.4, 0), ItemDisplay.class, d -> {
            d.setItemStack(new ItemStack(Material.DECORATED_POT));
            d.setPersistent(false);
            d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(0.8f, 0.8f, 0.8f), new AxisAngle4f()));
        });
        u.label = Ui.hologram(at.clone().add(0, 1.3, 0), "", 0.7f);
        u.label.setPersistent(false);
        u.box = w.spawn(at, Interaction.class, i -> {
            i.setInteractionWidth(0.9f);
            i.setInteractionHeight(0.9f);
            i.setResponsive(true);
            i.setPersistent(false);
            i.getPersistentDataContainer().set(tag, PersistentDataType.STRING, u.id.toString());
        });
    }

    private static void despawn(Urn u) {
        if (u.pot != null) u.pot.remove();
        if (u.label != null) u.label.remove();
        if (u.box != null) u.box.remove();
        u.pot = null; u.label = null; u.box = null;
    }

    // ------------------------------------------------------------------ saving

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Urn u : urns.values()) {
            String k = "urns." + u.id;
            y.set(k + ".owner", u.owner.toString()); y.set(k + ".name", u.ownerName);
            y.set(k + ".world", u.world); y.set(k + ".x", u.x); y.set(k + ".y", u.y); y.set(k + ".z", u.z);
            y.set(k + ".died", u.diedAt); y.set(k + ".xp", u.xp); y.set(k + ".items", u.items);
        }
        try { file.getParentFile().mkdirs(); y.save(file); }
        catch (Exception ex) { c.plugin().getLogger().warning("urns.yml save failed: " + ex.getMessage()); }
    }

    @SuppressWarnings("unchecked")
    private void load() {
        var sec = YamlConfiguration.loadConfiguration(file).getConfigurationSection("urns");
        if (sec == null) return;
        for (String k : sec.getKeys(false)) {
            try {
                Urn u = new Urn();
                u.id = UUID.fromString(k);
                u.owner = UUID.fromString(sec.getString(k + ".owner"));
                u.ownerName = sec.getString(k + ".name", "?");
                u.world = sec.getString(k + ".world");
                u.x = sec.getDouble(k + ".x"); u.y = sec.getDouble(k + ".y"); u.z = sec.getDouble(k + ".z");
                u.diedAt = sec.getLong(k + ".died"); u.xp = sec.getInt(k + ".xp");
                List<?> raw = sec.getList(k + ".items", List.of());
                u.items = new ArrayList<>();
                for (Object o : raw) if (o instanceof ItemStack s) u.items.add(s);
                urns.put(u.id, u);
            } catch (Exception ex) { c.plugin().getLogger().warning("bad urn " + k + ": " + ex.getMessage()); }
        }
    }
}
