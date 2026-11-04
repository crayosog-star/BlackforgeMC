package net.blackforge.core.survival;

import net.blackforge.core.BlackForgeCore;
import net.blackforge.core.Ui;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Allay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Forge Allays: a base upgrade that stays at the Base Station and moves items. So far this only proves they can be flown
 * under full control: vanilla AI is off, so they can't bond to a player, follow anyone, pick items up or duplicate.
 * Staff run {@code /allay start} and {@code /allay stop} in the Foundry. Plan: docs/plans/forge-allays.md
 */
public final class Allays implements Listener {
    private static final String NAME = "<gradient:#c8743a:#4fd1b5><bold>Forge Allay</bold></gradient>";
    private static final int TRIP_BLOCKS = 12, MAX_TEST = 6, TEST_LIFE_TICKS = 5 * 60 * 20;

    /** A test allay flying back and forth between two points. */
    private static final class Flyer {
        final Allay entity; final double[] home, away;
        boolean out = true; int tick, ticks, rest, life;
        Flyer(Allay entity, double[] home, double[] away, int ticks) { this.entity = entity; this.home = home; this.away = away; this.ticks = ticks; }
    }

    private final BlackForgeCore plugin;
    private final NamespacedKey marker;
    private final Predicate<Location> zone;
    private final double speed;
    private final int pauseTicks;
    private final List<Flyer> flyers = new ArrayList<>();
    private BukkitTask task;

    /** {@code zone}: where staff may test them. */
    Allays(BlackForgeCore plugin, Predicate<Location> zone) {
        this.plugin = plugin;
        this.zone = zone;
        this.marker = new NamespacedKey(BlackForgeCore.NS, "forge_allay");
        this.speed = Math.max(0.5, plugin.getConfig().getDouble("allays.speed", 6.0));
        this.pauseTicks = (int) Math.max(0, Math.round(plugin.getConfig().getDouble("allays.pause-seconds", 1.0) * 20));
        Bukkit.getPluginManager().registerEvents(this, plugin);
        registerCommand();
    }

    // ------------------------------------------------------------------ test command

    private void registerCommand() {
        Bukkit.getCommandMap().register("blackforge", new Command("allay", "Test Forge Allays (staff)", "/allay <start|stop>", List.of()) {
            @Override public boolean execute(CommandSender s, String label, String[] a) {
                if (!s.hasPermission("blackforge.admin")) { s.sendMessage(Ui.mm("<gray>Forge Allay tests are staff-only.")); return true; }
                String what = a.length == 0 ? "" : a[0].toLowerCase(java.util.Locale.ROOT);
                if (what.equals("stop")) {
                    int n = stop();
                    s.sendMessage(Ui.mm(n == 0 ? "<gray>No test allays were running." : "<green>Removed " + n + " test allay" + (n == 1 ? "" : "s") + "."));
                } else if (what.equals("start") && s instanceof Player p) start(p);
                else s.sendMessage(Ui.mm("<gray>Stand in the Foundry and run <white>/allay start<gray>, then <white>/allay stop<gray>."));
                return true;
            }
        });
    }

    private void start(Player p) {
        if (!zone.test(p.getLocation())) { p.sendMessage(Ui.mm("<gray>Go to the Foundry first: <white>/arena<gray>, then over the bridge.")); return; }
        if (flyers.size() >= MAX_TEST) { p.sendMessage(Ui.mm("<gray>That's " + MAX_TEST + " test allays already. <white>/allay stop<gray> clears them.")); return; }
        Location home = p.getLocation().add(0, 2, 0);
        Vector look = p.getLocation().getDirection().setY(0);
        if (look.lengthSquared() < 1e-4) look = new Vector(1, 0, 0);
        look.normalize();
        int reach = TRIP_BLOCKS;
        while (reach > 2 && !zone.test(home.clone().add(look.clone().multiply(reach)))) reach--;
        Location away = home.clone().add(look.clone().multiply(reach));
        Allay allay = spawn(home);
        flyers.add(new Flyer(allay, xyz(home), xyz(away), AllayFlight.ticks(reach, speed)));
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this::fly, 1L, 1L);
        p.sendMessage(Ui.mm("<green>Test allay started, flying " + reach + " blocks and back. <gray>Try right-clicking it, holding items near it and playing a jukebox. It should ignore all of that. <white>/allay stop<gray> removes it."));
    }

    /** Removes every test allay. Returns how many, and is safe when there are none. */
    int stop() {
        int n = flyers.size();
        for (Flyer f : flyers) f.entity.remove();
        flyers.clear();
        if (task != null) { task.cancel(); task = null; }
        return n;
    }

    void shutdown() { stop(); }

    private static double[] xyz(Location l) { return new double[]{l.getX(), l.getY(), l.getZ()}; }

    // ------------------------------------------------------------------ the entity

    private Allay spawn(Location at) {
        return at.getWorld().spawn(at, Allay.class, x -> {
            x.setAI(false);              // no bond, no following, no pickups, no dancing
            x.setGravity(false);
            x.setInvulnerable(true);
            x.setSilent(true);
            x.setCollidable(false);
            x.setCanPickupItems(false);
            x.setCanDuplicate(false);
            x.setPersistent(false);      // a crash or restart can't leave one behind
            x.setRemoveWhenFarAway(false);
            x.customName(Ui.mm(NAME));
            x.setCustomNameVisible(true);
            x.getPersistentDataContainer().set(marker, PersistentDataType.BYTE, (byte) 1);
        });
    }

    private boolean isOurs(Entity e) { return e.getPersistentDataContainer().has(marker); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTouch(PlayerInteractEntityEvent e) { if (isOurs(e.getRightClicked())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLeash(PlayerLeashEntityEvent e) { if (isOurs(e.getEntity())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) { if (isOurs(e.getEntity())) e.setCancelled(true); }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent e) { if (isOurs(e.getEntity())) e.setCancelled(true); }

    /** Anything that comes back from disk isn't one we're flying. */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity x : e.getEntities()) if (isOurs(x) && flyers.stream().noneMatch(f -> f.entity.equals(x))) x.remove();
    }

    // ------------------------------------------------------------------ flying

    private void fly() {
        flyers.removeIf(f -> {
            if (!f.entity.isValid()) return true;
            if (++f.life > TEST_LIFE_TICKS) { f.entity.remove(); return true; }   // nobody has to remember to clean up
            step(f);
            return false;
        });
        if (flyers.isEmpty() && task != null) { task.cancel(); task = null; }
    }

    private void step(Flyer f) {
        if (f.rest > 0) { f.rest--; return; }
        double[] from = f.out ? f.home : f.away, to = f.out ? f.away : f.home;
        f.tick++;
        double[] p = AllayFlight.at(from, to, f.tick, f.ticks);
        Location now = f.entity.getLocation(), next = new Location(now.getWorld(), p[0], p[1], p[2]);
        // face where it's going, turning from the motion and never from a player's camera
        Vector dir = new Vector(to[0] - from[0], 0, to[2] - from[2]);
        next.setDirection(dir.lengthSquared() < 1e-4 ? now.getDirection() : dir);
        next.setPitch(0);
        f.entity.teleport(next);
        if (f.tick >= f.ticks) { f.tick = 0; f.out = !f.out; f.rest = pauseTicks; }
    }
}
