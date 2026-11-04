package net.blackforge.core.survival;

import net.blackforge.core.BlackForgeCore;
import net.blackforge.core.NetEconomy;
import net.blackforge.core.Ui;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Allay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private static final String[] TIER_JOB = {"Carries loose drops to the Station", "Also sorts the Station into chests: put an item in a frame on a chest", "Sells from an emerald-framed chest, less a fee"};
    private static final int TRIP_BLOCKS = 12, MAX_TEST = 6, TEST_LIFE_TICKS = 5 * 60 * 20;
    /** The highest tier with its job built so far. Higher cards say so rather than sell nothing. */
    private static final int BUILT_TIER = 2;
    private static final int[] CARD_SLOTS = {11, 13, 15};
    private static final int BACK_SLOT = 22, MENU_SIZE = 27;
    private static final int RETRY_TICKS = 40, NEVER_PICKED_UP = 1000, WORK_CHECK_TICKS = 10;
    /** An item frame holding this marks a sell chest, not a sorting chest. */
    private static final Material SELL_MARK = Material.EMERALD;
    private static final double REACH_SQ = 9;
    /** The game itself draws a mob with this exact name upside down, as it does on a name tag. */
    private static final String FLIP_NAME = "Dinnerbone";
    private static final int NAME_REACH = 8;
    private static final double TAG_HEIGHT = 0.9;

    /** A test allay flying back and forth between two points. */
    private static final class Flyer {
        final Allay entity; final double[] home, away;
        boolean out = true; int tick, ticks, rest, life;
        Flyer(Allay entity, double[] home, double[] away, int ticks) { this.entity = entity; this.home = home; this.away = away; this.ticks = ticks; }
    }

    /** One allay of a flock. The record outlives its entity, which is gone whenever the chunk unloads. */
    private static final class Worker {
        final int index; Allay entity;
        State state = State.IDLE;
        /** The trip under way. */
        double[] from, to; int tick, ticks;
        /** What it's fetching, and what it's holding. Holding is kept in memory, because the item has left the world. */
        UUID job; ItemStack carried;
        int rest; String label;
        /** Where a load is going, other than the Station. */
        Block dest;
        /** What the player called it, or null. */
        String name;
        /** Shows the name of a flipped allay, whose real name has to be the one the game flips. */
        TextDisplay tag;
        Worker(int index) { this.index = index; }
    }

    private enum State { IDLE, FETCH, DELIVER, RETURN, PAUSE, WAIT }

    /** A chest that takes this material, from an item frame on it. */
    private record Link(Material material, Block chest) {}

    /** One claim or island's upgrade. */
    private static final class Base {
        final String region; final UUID buyer; int tier;
        final List<Worker> workers = new ArrayList<>();
        final List<AllayJobs.Job> jobs = new ArrayList<>();
        final List<Link> links = new ArrayList<>();
        long lastScan = Long.MIN_VALUE / 2, lastLinkScan = Long.MIN_VALUE / 2;
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
    private final int pauseTicks, carry, minDropAge, scanTicks, maxScans, linkScanTicks;
    private final NamespacedKey deathDrop;
    private org.bukkit.World deathWorld;
    private double deathX, deathY, deathZ;
    private int deathTick = -1, scansThisTick;
    private final List<Flyer> flyers = new ArrayList<>();
    private final Map<String, Base> bases = new HashMap<>();
    private long now;
    private int sandboxLimit = Integer.MAX_VALUE;
    private final boolean sausageFlip;

    /** {@code zone}: where staff may test flight. {@code sandbox}: the Arena's Foundry, where everything is free and nothing is saved. */
    Allays(BlackForgeCore plugin, BaseStation station, Predicate<Location> zone, boolean sandbox) {
        this.plugin = plugin;
        this.station = station;
        this.zone = zone;
        this.sandbox = sandbox;
        this.marker = new NamespacedKey(BlackForgeCore.NS, "forge_allay");
        this.deathDrop = new NamespacedKey(BlackForgeCore.NS, "death_drop");
        var c = plugin.getConfig();
        this.table = table(c);
        this.sausageFlip = c.getBoolean("allays.sausage-flip", true);
        this.carry = Math.max(1, c.getInt("allays.carry", 16));
        this.minDropAge = c.getInt("allays.min-drop-age-ticks", 60);
        this.scanTicks = Math.max(20, c.getInt("allays.scan-seconds", 5) * 20);
        this.maxScans = Math.max(1, c.getInt("allays.max-scans-per-tick", 4));
        this.linkScanTicks = Math.max(20, c.getInt("allays.link-scan-seconds", 30) * 20);
        this.speed = Math.max(0.5, c.getDouble("allays.speed", 6.0));
        this.pauseTicks = (int) Math.max(0, Math.round(c.getDouble("allays.pause-seconds", 1.0) * 20));
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        if (sandbox) registerCommand();
    }

    void setSandboxLimit(int total) { this.sandboxLimit = total; }

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
                else if (what.equals("name") && a.length > 1 && s instanceof Player p) nameNearest(p, String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)));
                else s.sendMessage(Ui.mm("<gray>Stand in the Foundry and run <white>/allay start<gray>, then <white>/allay stop<gray>. <white>/allay name <text><gray> names the nearest summoned allay."));
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

    /** Staff test for naming, so nobody needs an anvil: the nearest summoned allay in reach. */
    private void nameNearest(Player p, String name) {
        Worker best = null;
        double bestDist = NAME_REACH * NAME_REACH;
        for (Base b : bases.values()) for (Worker w : b.workers) {
            if (w.entity == null || !w.entity.isValid() || w.entity.getWorld() != p.getWorld()) continue;
            double d = w.entity.getLocation().distanceSquared(p.getLocation());
            if (d <= bestDist) { bestDist = d; best = w; }
        }
        if (best == null) { p.sendMessage(Ui.mm("<gray>No summoned allay within " + NAME_REACH + " blocks. Summon one from slot 34 of the Forge.")); return; }
        rename(p, best, name);
    }

    /** Refuses a bad name out loud and returns false, so a name tag isn't used up on it. */
    private boolean rename(Player p, Worker w, String name) {
        if (!AllayRules.validName(name)) { p.sendActionBar(Ui.mm("<red>Allay names are up to 16 letters, numbers and spaces.")); return false; }
        w.name = name;
        w.label = null;
        p.playSound(w.entity.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1f);
        p.sendActionBar(Ui.mm("<#4fd1b5>Named your allay <white>" + name + "<#4fd1b5>."));
        return true;
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
    public void onTouch(PlayerInteractEntityEvent e) {
        if (!isOurs(e.getRightClicked())) return;
        e.setCancelled(true);
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        ItemStack held = p.getInventory().getItemInMainHand();
        if (held.getType() == Material.NAME_TAG) useNameTag(p, held, e.getRightClicked());
    }

    private void useNameTag(Player p, ItemStack tag, Entity allay) {
        for (Base b : bases.values()) for (Worker w : b.workers) {
            if (!allay.equals(w.entity)) continue;
            Location at = station.stationIn(b.region);
            if (at == null || !station.usable(p, at)) { p.sendActionBar(Ui.mm("<red>That's someone else's allay.")); return; }
            if (!tag.hasItemMeta() || !tag.getItemMeta().hasCustomName()) { p.sendActionBar(Ui.mm("<gray>Rename this Name Tag in an anvil first.")); return; }
            String name = PlainTextComponentSerializer.plainText().serialize(tag.getItemMeta().customName()).trim();
            if (rename(p, w, name)) tag.setAmount(tag.getAmount() - 1);
            return;
        }
        p.sendActionBar(Ui.mm("<gray>This one's only a test. Name a summoned allay."));
    }

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
                tier == 0 ? "<gray>Not summoned yet" : "<gray>Tier: <white>" + Spawners.roman(tier) + " <gray>" + TIER_NAME[tier - 1],
                "<dark_gray>Use a Name Tag on one to name it", "", "<yellow>Click to open");
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
        if (sandbox) {
            int all = table.flock(t) - (b == null ? 0 : b.workers.size());
            for (Base o : bases.values()) all += o.workers.size();
            if (all > sandboxLimit) { p.sendActionBar(Ui.mm("<red>The Foundry has enough allays flying right now. Try again soon.")); return; }
        }
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
        scansThisTick = 0;
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
        scan(b, at);
        scanLinks(b, at);
        Set<UUID> taken = new HashSet<>();
        for (Worker w : b.workers) if (w.job != null) taken.add(w.job);
        for (Worker w : b.workers) {
            if (w.entity == null || !w.entity.isValid()) wake(w, at, flock);
            work(b, w, at, flock, taken);
            label(w, b.tier, w.state == State.WAIT ? "<red>Station full" : null);
            moveTag(w);
        }
    }

    /** A new entity for this allay. If it was carrying something when its last one went, it takes that home first. */
    private void wake(Worker w, Location at, int flock) {
        double[] spot = hover(at, w.index, flock, now);
        w.entity = spawn(new Location(at.getWorld(), spot[0], spot[1], spot[2]));
        w.label = null;
        w.job = null;
        w.dest = null;
        if (w.carried != null) goHome(w, at, flock);
        else w.state = State.IDLE;
    }

    private void work(Base b, Worker w, Location at, int flock, Set<UUID> taken) {
        switch (w.state) {
            case IDLE -> {
                idle(w, at, flock);
                if ((now + w.index) % WORK_CHECK_TICKS != 0) return;
                Inventory store = station.storageIn(b.region);
                if (store == null) return;
                if (AllayRules.canSort(b.tier) && startSort(b, w, store)) return;
                var job = store.firstEmpty() < 0 ? Optional.<AllayJobs.Job>empty()
                        : AllayJobs.nearest(xyz(w.entity.getLocation()), b.jobs, taken);
                if (job.isPresent()) {
                    w.job = job.get().id();
                    taken.add(w.job);
                    w.state = State.FETCH;
                    fly(w, new double[]{job.get().x(), job.get().y() + 0.4, job.get().z()});
                }
            }
            case DELIVER -> {
                if (!advance(w, at, flock)) return;
                dropOff(w);
                goHome(w, at, flock);
            }
            case FETCH -> {
                if (!advance(w, at, flock)) return;
                grab(b, w);
                goHome(w, at, flock);
            }
            case RETURN -> {
                if (!advance(w, at, flock)) return;
                if (w.carried == null) { rest(w); return; }
                unload(b, w, at);
            }
            case PAUSE -> {
                idle(w, at, flock);
                if (--w.rest <= 0) w.state = State.IDLE;
            }
            case WAIT -> {
                idle(w, at, flock);
                if (--w.rest <= 0) unload(b, w, at);
            }
        }
    }

    private void idle(Worker w, Location at, int flock) { moveTo(w.entity, hover(at, w.index, flock, now), 0, 0); }

    private void fly(Worker w, double[] to) {
        w.from = xyz(w.entity.getLocation());
        w.to = to;
        w.tick = 0;
        double dx = to[0] - w.from[0], dy = to[1] - w.from[1], dz = to[2] - w.from[2];
        w.ticks = AllayFlight.ticks(Math.sqrt(dx * dx + dy * dy + dz * dz), speed);
    }

    /** Takes a load out of the Station for the nearest linked chest with room. False if there's nothing to sort. */
    private boolean startSort(Base b, Worker w, Inventory store) {
        ItemStack[] all = store.getContents();
        for (int i = 0; i < all.length; i++) {
            ItemStack it = all[i];
            if (it == null || it.getType().isAir() || Robots.fuelTier(it) > 0) continue;   // Fuel Cells stay for the Agents
            Block chest = chestFor(b, w, it);
            if (chest == null) continue;
            int n = Math.min(it.getAmount(), carry);
            ItemStack load = it.clone();
            load.setAmount(n);
            if (n >= it.getAmount()) store.setItem(i, null);
            else { it.setAmount(it.getAmount() - n); store.setItem(i, it); }
            w.carried = load;
            w.dest = chest;
            w.state = State.DELIVER;
            Location c = chest.getLocation();
            fly(w, new double[]{c.getX() + 0.5, c.getY() + 1.4, c.getZ() + 0.5});
            return true;
        }
        return false;
    }

    /** The nearest chest linked to this item that still has room for some of it. */
    private Block chestFor(Base b, Worker w, ItemStack it) {
        Map<double[], Block> places = new HashMap<>();
        for (Link l : b.links) {
            if (l.material() != it.getType() || !l.chest().getWorld().isChunkLoaded(l.chest().getX() >> 4, l.chest().getZ() >> 4)) continue;
            if (!(l.chest().getState(false) instanceof Container c) || !hasRoom(c.getInventory(), it)) continue;
            Location at = l.chest().getLocation();
            places.put(new double[]{at.getX(), at.getY(), at.getZ()}, l.chest());
        }
        return AllayJobs.nearestPlace(xyz(w.entity.getLocation()), places.keySet()).map(places::get).orElse(null);
    }

    private static boolean hasRoom(Inventory inv, ItemStack it) {
        if (inv.firstEmpty() >= 0) return true;
        for (ItemStack s : inv.getContents()) if (s != null && s.isSimilar(it) && s.getAmount() < s.getMaxStackSize()) return true;
        return false;
    }

    /** Puts the load in its chest. Whatever doesn't fit, or a load whose chest has gone, goes back to the Station. */
    private void dropOff(Worker w) {
        Block chest = w.dest;
        w.dest = null;
        if (w.carried == null || chest == null || !(chest.getState(false) instanceof Container c)) return;
        Map<Integer, ItemStack> left = c.getInventory().addItem(w.carried);
        w.carried = left.isEmpty() ? null : left.values().iterator().next();
        chest.getWorld().playSound(chest.getLocation(), Sound.BLOCK_AMETHYST_CLUSTER_PLACE, 0.4f, 1.3f);
    }

    /** Back to its place in the circle, aiming for where that will be when it arrives so it doesn't snap. */
    private void goHome(Worker w, Location at, int flock) {
        w.job = null;
        w.state = State.RETURN;
        double[] here = xyz(w.entity.getLocation()), spot = hover(at, w.index, flock, now);
        int eta = AllayFlight.ticks(Math.sqrt(Math.pow(spot[0] - here[0], 2) + Math.pow(spot[1] - here[1], 2) + Math.pow(spot[2] - here[2], 2)), speed);
        fly(w, hover(at, w.index, flock, now + eta));
    }

    /** One tick of the trip. True on arrival. If the way ahead isn't loaded, it gives up and turns for home, so it never loads a chunk. */
    private boolean advance(Worker w, Location at, int flock) {
        w.tick++;
        double[] p = AllayFlight.at(w.from, w.to, w.tick, w.ticks);
        World world = w.entity.getWorld();
        if (!world.isChunkLoaded((int) Math.floor(p[0]) >> 4, (int) Math.floor(p[2]) >> 4)) {
            if (w.state == State.FETCH || w.state == State.DELIVER) goHome(w, at, flock);
            else { w.tick = w.ticks; return true; }   // home is the Station's own area: being unloaded here means we're about to sleep anyway
            return false;
        }
        moveTo(w.entity, p, w.to[0] - w.from[0], w.to[2] - w.from[2]);
        return w.tick >= w.ticks;
    }

    /** Takes up to a load from the drop, if it's still about where it was. The items leave the world here, so a crash can lose them but can't double them. */
    private void grab(Base b, Worker w) {
        UUID id = w.job;
        b.jobs.removeIf(j -> j.id().equals(id));
        if (id == null || !(Bukkit.getEntity(id) instanceof Item drop) || !drop.isValid() || drop.getWorld() != w.entity.getWorld()
                || drop.getLocation().distanceSquared(w.entity.getLocation()) > REACH_SQ) return;
        ItemStack stack = drop.getItemStack();
        int n = Math.min(stack.getAmount(), carry);
        ItemStack load = stack.clone();
        load.setAmount(n);
        if (n >= stack.getAmount()) drop.remove();
        else { stack.setAmount(stack.getAmount() - n); drop.setItemStack(stack); }
        w.carried = load;
        w.entity.getWorld().playSound(w.entity.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 1.4f);
    }

    private void unload(Base b, Worker w, Location at) {
        Inventory store = station.storageIn(b.region);
        Map<Integer, ItemStack> left = store == null ? Map.of(0, w.carried) : store.addItem(w.carried);
        if (left.isEmpty()) {
            w.carried = null;
            at.getWorld().playSound(at, Sound.BLOCK_AMETHYST_CLUSTER_PLACE, 0.4f, 1.6f);
            rest(w);
        } else {
            w.carried = left.values().iterator().next();
            w.state = State.WAIT;
            w.rest = RETRY_TICKS;
        }
    }

    private void rest(Worker w) {
        w.state = State.PAUSE;
        w.rest = pauseTicks;
    }

    private void label(Worker w, int tier, String status) {
        String title = w.name == null ? NAME : "<#4fd1b5><bold>" + w.name + "</bold>";   // validName keeps tags out
        String text = title + " <gray>" + TIER_NAME[tier - 1] + (status == null ? "" : " " + status);
        if (text.equals(w.label)) return;
        w.label = text;
        if (sausageFlip && AllayRules.flipsUpsideDown(w.name)) {
            // the game flips it by its real name, so that's hidden and a floating tag shows the name the player gave it
            w.entity.customName(Component.text(FLIP_NAME));
            w.entity.setCustomNameVisible(false);
            if (w.tag == null || !w.tag.isValid()) {
                w.tag = Ui.hologram(w.entity.getLocation().add(0, TAG_HEIGHT, 0), text, 1f);
                w.tag.setTeleportDuration(1);
            } else w.tag.text(Ui.mm(text));
        } else {
            removeTag(w);
            w.entity.customName(Ui.mm(text));
            w.entity.setCustomNameVisible(true);
        }
    }

    private void moveTag(Worker w) {
        if (w.tag == null) return;
        if (!w.tag.isValid()) { w.tag = null; w.label = null; return; }   // it went with its chunk: the next label makes a new one
        w.tag.teleport(w.entity.getLocation().add(0, TAG_HEIGHT, 0));
    }

    private static void removeTag(Worker w) {
        if (w.tag != null) w.tag.remove();
        w.tag = null;
    }

    // ------------------------------------------------------------------ finding work

    /** Looks for loose drops, a few bases a tick across the server, so many bases can't all search at once. */
    private void scan(Base b, Location at) {
        if (scansThisTick >= maxScans || !AllayJobs.due(b.lastScan, now, scanTicks)) return;
        scansThisTick++;
        b.lastScan = now;
        b.jobs.clear();
        int leash = table.leash(b.tier);
        for (Entity e : at.getWorld().getNearbyEntities(at, leash, leash, leash, x -> x instanceof Item)) {
            Item drop = (Item) e;
            if (!inBase(drop.getLocation(), b.region) || !collectable(drop, at)) continue;
            Location l = drop.getLocation();
            b.jobs.add(new AllayJobs.Job(drop.getUniqueId(), l.getX(), l.getY(), l.getZ()));
        }
    }

    /** Finds the chests with an item in a frame on them. It's slow work that rarely changes, so a Courier's base does it now and then. */
    private void scanLinks(Base b, Location at) {
        if (!AllayRules.canSort(b.tier) || scansThisTick >= maxScans || !AllayJobs.due(b.lastLinkScan, now, linkScanTicks)) return;
        scansThisTick++;
        b.lastLinkScan = now;
        b.links.clear();
        int leash = table.leash(b.tier);
        for (Entity e : at.getWorld().getNearbyEntities(at, leash, leash, leash, x -> x instanceof ItemFrame)) {
            ItemFrame frame = (ItemFrame) e;
            Material m = frame.getItem().getType();
            if (m.isAir() || m == SELL_MARK) continue;
            Block chest = frame.getLocation().getBlock().getRelative(frame.getAttachedFace());
            if (chest.equals(at.getBlock()) || !inBase(chest.getLocation(), b.region)) continue;
            if (chest.getState(false) instanceof Chest || chest.getState(false) instanceof Barrel) b.links.add(new Link(m, chest));
        }
    }

    private boolean inBase(Location l, String region) {
        return sandbox ? zone.test(l) : region.equals(station.regionAt(l));   // the Foundry's regions are a chunk each, too small to work in
    }

    private boolean collectable(Item drop, Location at) {
        if (!drop.canMobPickup() || !drop.canPlayerPickup() || drop.getPickupDelay() >= NEVER_PICKED_UP) return false;   // displays, not loot
        UUID thrower = drop.getThrower();
        Player who = thrower == null ? null : Bukkit.getPlayer(thrower);
        boolean visitor = thrower != null && (who == null || !station.usable(who, at));
        return AllayRules.skipDrop(drop.getTicksLived(), minDropAge, drop.getPersistentDataContainer().has(deathDrop), visitor).isEmpty();
    }

    /** Remembers where a player just died, so {@link #onItemSpawn} can mark what falls. It marks the drop, not the stack, so the items stay normal. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent e) {
        Location l = e.getEntity().getLocation();
        deathWorld = l.getWorld(); deathX = l.getX(); deathY = l.getY(); deathZ = l.getZ();
        deathTick = Bukkit.getCurrentTick();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent e) {
        if (Bukkit.getCurrentTick() != deathTick) return;
        Location l = e.getLocation();
        if (l.getWorld() == deathWorld && Math.abs(l.getX() - deathX) < 4 && Math.abs(l.getY() - deathY) < 4 && Math.abs(l.getZ() - deathZ) < 4)
            e.getEntity().getPersistentDataContainer().set(deathDrop, PersistentDataType.BYTE, (byte) 1);
    }

    private static double[] hover(Location station, int index, int flock, long tick) {
        double[] h = AllayFlight.hover(index, flock, tick);
        return new double[]{station.getX() + 0.5 + h[0], station.getY() + 2.2 + h[1], station.getZ() + 0.5 + h[2]};
    }

    /** No Station, or it's unloaded: the flock goes away until it's back, dropping what it holds. The tier stays. */
    private void sleep(Base b) {
        b.jobs.clear();
        for (Worker w : b.workers) {
            if (w.entity != null && w.entity.isValid()) {
                if (w.carried != null) { w.entity.getWorld().dropItem(w.entity.getLocation(), w.carried); w.carried = null; }
                w.entity.remove();
            }
            removeTag(w);
            // an entity that's already gone went with its chunk, so a load stays held until it's back
            w.entity = null;
            w.job = null;
            w.dest = null;
            w.state = State.IDLE;
        }
    }
}
