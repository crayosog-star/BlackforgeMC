package net.blackforge.core.survival;

import net.blackforge.core.BlackForgeCore;
import net.blackforge.core.NetEconomy;
import net.blackforge.core.Ui;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Forge Allays: a base upgrade that stays at the Base Station and moves items. Vanilla AI is off, so they can't bond to a
 * player, follow anyone, pick items up by themselves or duplicate; this class flies them. The upgrade belongs to the base,
 * so there's no allay item to drop, sell or dupe. Bought from slot 34 of the Forge.
 * Staff test flight with {@code /allay start} and {@code /allay stop} in the Foundry. Plan: docs/plans/forge-allays.md
 */
public final class Allays implements Listener {
    private static final String NAME = "<gradient:#c8743a:#4fd1b5><bold>Forge Allay</bold></gradient>";
    private static final String[] TIER_NAME = {"Gatherer", "Courier", "Steward"};
    private static final String[] TIER_JOB = {"Carries loose drops to the Station", "Sorts the Station into framed chests and refuels Agents", "Sells from an emerald-framed chest, less a fee"};
    private static final int TRIP_BLOCKS = 12, MAX_TEST = 6, TEST_LIFE_TICKS = 5 * 60 * 20;
    /** The highest tier with its job built so far. Higher cards say so rather than sell nothing. */
    private static final int BUILT_TIER = 1;
    private static final int[] CARD_SLOTS = {11, 13, 15};
    private static final int BACK_SLOT = 22, MENU_SIZE = 27;

    /** A test allay flying back and forth between two points. */
    private static final class Flyer {
        final Allay entity; final double[] home, away;
        boolean out = true; int tick, ticks, rest, life;
        Flyer(Allay entity, double[] home, double[] away, int ticks) { this.entity = entity; this.home = home; this.away = away; this.ticks = ticks; }
    }

    /** One allay of a flock. The record outlives its entity, which is gone whenever the chunk unloads. */
    private static final class Worker {
        final int index; Allay entity;
        Worker(int index) { this.index = index; }
    }

    /** One claim or island's upgrade. */
    private static final class Base {
        final String region; final UUID buyer; int tier;
        final List<Worker> workers = new ArrayList<>();
        Base(String region, UUID buyer) { this.region = region; this.buyer = buyer; }
    }

    private static final class Menu implements InventoryHolder {
        final Location at; Inventory inv;
        Menu(Location at) { this.at = at; }
        public Inventory getInventory() { return inv; }
    }

    private final BlackForgeCore plugin;
    private final BaseStation station;
    private final NamespacedKey marker;
    private final Predicate<Location> zone;
    private final boolean sandbox;
    private final AllayRules.Table table;
    private final double speed;
    private final int pauseTicks;
    private final List<Flyer> flyers = new ArrayList<>();
    private final Map<String, Base> bases = new HashMap<>();
    private long now;

    /** {@code zone}: where staff may test flight. {@code sandbox}: the Arena's Foundry, where everything is free and nothing is saved. */
    Allays(BlackForgeCore plugin, BaseStation station, Predicate<Location> zone, boolean sandbox) {
        this.plugin = plugin;
        this.station = station;
        this.zone = zone;
        this.sandbox = sandbox;
        this.marker = new NamespacedKey(BlackForgeCore.NS, "forge_allay");
        var c = plugin.getConfig();
        this.table = table(c);
        this.speed = Math.max(0.5, c.getDouble("allays.speed", 6.0));
        this.pauseTicks = (int) Math.max(0, Math.round(c.getDouble("allays.pause-seconds", 1.0) * 20));
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        if (sandbox) registerCommand();
    }

    private static AllayRules.Table table(org.bukkit.configuration.file.FileConfiguration c) {
        AllayRules.Table d = AllayRules.Table.DEFAULT;
        return new AllayRules.Table(list(c, "allays.flock", d.flock()), list(c, "allays.leash", d.leash()),
                list(c, "allays.price", d.price()), list(c, "allays.essence", d.essence()));
    }

    private static int[] list(org.bukkit.configuration.file.FileConfiguration c, String key, int[] fallback) {
        List<Integer> v = c.getIntegerList(key);
        return v.size() == AllayRules.MAX_TIER ? v.stream().mapToInt(Integer::intValue).toArray() : fallback;
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
        flyers.add(new Flyer(spawn(home), xyz(home), xyz(away), AllayFlight.ticks(reach, speed)));
        p.sendMessage(Ui.mm("<green>Test allay started, flying " + reach + " blocks and back. <gray>Try right-clicking it, holding items near it and playing a jukebox. It should ignore all of that. <white>/allay stop<gray> removes it."));
    }

    /** Removes every test allay. Returns how many, and is safe when there are none. */
    int stop() {
        int n = flyers.size();
        for (Flyer f : flyers) f.entity.remove();
        flyers.clear();
        return n;
    }

    void shutdown() {
        stop();
        for (Base b : bases.values()) sleep(b);
        bases.clear();
    }

    /** The Foundry, when someone leaves: their purchases go with them. */
    void clear(UUID buyer) {
        bases.values().removeIf(b -> {
            if (!b.buyer.equals(buyer)) return false;
            sleep(b);
            return true;
        });
    }

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

    private boolean isLive(Entity e) {
        for (Flyer f : flyers) if (f.entity.equals(e)) return true;
        for (Base b : bases.values()) for (Worker w : b.workers) if (e.equals(w.entity)) return true;
        return false;
    }

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
        for (Entity x : e.getEntities()) if (isOurs(x) && !isLive(x)) x.remove();
    }

    // ------------------------------------------------------------------ the Forge's Allay menu

    /** Should the Forge offer this player the Allay menu? */
    boolean available(Player p) { return sandbox; }

    ItemStack forgeIcon(String region) {
        Base b = bases.get(region);
        int tier = b == null ? 0 : b.tier;
        ItemStack it = Ui.item(Material.ALLAY_SPAWN_EGG, NAME, "<gray>A crew that stays at your Station", "<gray>and moves items for you.", "",
                tier == 0 ? "<gray>Not summoned yet" : "<gray>Tier: <white>" + Spawners.roman(tier) + " <gray>" + TIER_NAME[tier - 1], "", "<yellow>Click to open");
        it.setAmount(Math.max(1, tier));
        return it;
    }

    void openMenu(Player p, Location at) {
        Base b = bases.get(station.regionAt(at));
        int tier = b == null ? 0 : b.tier;
        Menu m = new Menu(at);
        m.inv = Bukkit.createInventory(m, MENU_SIZE, Ui.mm("<dark_gray>Forge Allays"));
        ItemStack pane = Ui.item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < MENU_SIZE; i++) m.inv.setItem(i, pane);
        for (int t = 1; t <= AllayRules.MAX_TIER; t++) m.inv.setItem(CARD_SLOTS[t - 1], card(t, tier));
        m.inv.setItem(BACK_SLOT, Ui.item(Material.ARROW, "<yellow>Back to the Forge"));
        p.openInventory(m.inv);
    }

    private ItemStack card(int t, int have) {
        AllayRules.Cost cost = table.cost(t);
        List<String> lore = new ArrayList<>(List.of("<gray>" + TIER_JOB[t - 1] + ".", "",
                "<gray>Allays: <white>" + table.flock(t), "<gray>Reach: <white>" + table.leash(t) + " blocks", ""));
        if (have >= t) lore.add("<green><bold>Summoned");
        else if (t > BUILT_TIER) lore.add("<dark_gray>Not ready yet");
        else if (t > have + 1) lore.add("<dark_gray>Needs Tier " + Spawners.roman(t - 1) + " first");
        else {
            lore.add(sandbox ? "<green>Free in the Foundry" : "<green>" + NetEconomy.money(cost.money()) + " <dark_gray>+ <#9d7bff>" + cost.essence() + " Mob Essence");
            lore.add("");
            lore.add("<yellow>Click to summon");
        }
        Material icon = have >= t ? Material.ALLAY_SPAWN_EGG : t == have + 1 && t <= BUILT_TIER ? Material.AMETHYST_SHARD : Material.GRAY_DYE;
        return Ui.item(icon, "<#4fd1b5><bold>Tier " + Spawners.roman(t) + ": " + TIER_NAME[t - 1], lore.toArray(String[]::new));
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        // the station can be broken, or the player's access lost, while a menu stays open
        if (!station.usable(p, m.at)) { p.closeInventory(); return; }
        int slot = e.getRawSlot();
        if (slot == BACK_SLOT) { station.openForge(p, m.at); return; }
        for (int t = 1; t <= AllayRules.MAX_TIER; t++) if (slot == CARD_SLOTS[t - 1]) buy(p, m.at, t);
    }

    @EventHandler
    public void onMenuDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true); }

    private void buy(Player p, Location at, int t) {
        String region = station.regionAt(at);
        Base b = bases.get(region);
        // read from the base, not the card: two members can have this menu open at once
        if ((b == null ? 0 : b.tier) != t - 1 || t > BUILT_TIER) return;
        AllayRules.Cost cost = table.cost(t);
        if (!station.pay(p, cost.money(), cost.essence())) return;
        if (b == null) { b = new Base(region, p.getUniqueId()); bases.put(region, b); }
        b.tier = t;
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1f, 1.2f);
        p.sendMessage(Ui.mm("<#4fd1b5>Your base now has a Tier " + Spawners.roman(t) + " " + TIER_NAME[t - 1] + ". <gray>" + TIER_JOB[t - 1] + "."));
        openMenu(p, at);
    }

    // ------------------------------------------------------------------ flying

    private void tick() {
        now++;
        flyers.removeIf(f -> {
            if (!f.entity.isValid()) return true;
            if (++f.life > TEST_LIFE_TICKS) { f.entity.remove(); return true; }   // nobody has to remember to clean up
            step(f);
            return false;
        });
        for (Base b : bases.values()) tickBase(b);
    }

    private void step(Flyer f) {
        if (f.rest > 0) { f.rest--; return; }
        double[] from = f.out ? f.home : f.away, to = f.out ? f.away : f.home;
        f.tick++;
        double[] p = AllayFlight.at(from, to, f.tick, f.ticks);
        // face where it's going, turning from the motion and never from a player's camera
        moveTo(f.entity, p, to[0] - from[0], to[2] - from[2]);
        if (f.tick >= f.ticks) { f.tick = 0; f.out = !f.out; f.rest = pauseTicks; }
    }

    private static void moveTo(Allay a, double[] p, double headX, double headZ) {
        Location now = a.getLocation(), next = new Location(now.getWorld(), p[0], p[1], p[2]);
        Vector dir = new Vector(headX, 0, headZ);
        next.setDirection(dir.lengthSquared() < 1e-4 ? now.getDirection() : dir);
        next.setPitch(0);
        a.teleport(next);
    }

    private void tickBase(Base b) {
        Location at = station.stationIn(b.region);
        if (at == null) { sleep(b); return; }
        int flock = table.flock(b.tier);
        while (b.workers.size() < flock) b.workers.add(new Worker(b.workers.size()));
        for (Worker w : b.workers) {
            double[] spot = hover(at, w.index, flock, now);
            if (w.entity == null || !w.entity.isValid()) w.entity = spawn(new Location(at.getWorld(), spot[0], spot[1], spot[2]));
            else moveTo(w.entity, spot, 0, 0);
        }
    }

    private static double[] hover(Location station, int index, int flock, long tick) {
        double[] h = AllayFlight.hover(index, flock, tick);
        return new double[]{station.getX() + 0.5 + h[0], station.getY() + 2.2 + h[1], station.getZ() + 0.5 + h[2]};
    }

    /** No Station, or it's unloaded: the flock goes away until it's back. The tier stays. */
    private void sleep(Base b) {
        for (Worker w : b.workers) {
            if (w.entity != null) w.entity.remove();
            w.entity = null;
        }
    }
}
