package net.blackforge.core.survival;

import net.blackforge.core.Lab;
import net.blackforge.core.Ui;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * Ashfall Nights (Survival 2 experiment "ashfall-nights"): every few nights ash falls. Monsters that spawn outside
 * claims are ember-touched (burning look, more health, a little faster) and drop Ashmarks when a player kills them.
 * Ashmarks buy money, crate keys and rare items at /ashmarks. Players are warned the night before, claims stay safe,
 * and sleeping still skips the night. Staff: /ashfall start|stop.
 */
final class AshfallNights implements Listener {
    static final String NAME = "<gradient:#a83232:#c9c2b8><bold>Ashfall</bold></gradient>";
    static final String MARK = "<gradient:#c9c2b8:#a83232>Ashmark</gradient>";
    private static final String ID = "ashfall-nights";

    private record Offer(int cost, ItemStack icon, Consumer<Player> give) {}

    private final LabExperiments.Ctx c;
    private final NamespacedKey ember, mark;
    private final int every;
    private final double healthBoost;
    private boolean active, forced;
    private long warnedDay = -1;
    private List<Offer> offers;

    AshfallNights(LabExperiments.Ctx c) {
        this.c = c;
        this.ember = new NamespacedKey(c.plugin(), "ashfall_ember");
        this.mark = new NamespacedKey(c.plugin(), "ashmark");
        this.every = c.plugin().getConfig().getInt("lab-experiments.ashfall-nights.every-nights", 5);
        this.healthBoost = c.plugin().getConfig().getDouble("lab-experiments.ashfall-nights.health-multiplier", 1.5);
        offers = List.of(
                new Offer(1, Ui.item(Material.GOLD_NUGGET, "<green>$150", "<gray>Cost: <white>1 " + MARK), p -> c.plugin().economy().deposit(p, 150)),
                new Offer(8, c.crates().key("iron", 1), p -> c.crates().give(p, "iron", 1)),
                new Offer(16, new ItemStack(Material.GOLDEN_APPLE, 2), p -> give(p, new ItemStack(Material.GOLDEN_APPLE, 2))),
                new Offer(48, new ItemStack(Material.NETHERITE_SCRAP), p -> give(p, new ItemStack(Material.NETHERITE_SCRAP))),
                new Offer(96, new ItemStack(Material.ENCHANTED_GOLDEN_APPLE), p -> give(p, new ItemStack(Material.ENCHANTED_GOLDEN_APPLE))));
        Bukkit.getPluginManager().registerEvents(this, c.plugin());
        Bukkit.getScheduler().runTaskTimer(c.plugin(), this::check, 100L, 100L);
        Bukkit.getScheduler().runTaskTimer(c.plugin(), this::ash, 20L, 10L);
        LabExperiments.command(c.plugin(), ID, "ashmarks", "Spend Ashmarks", List.of("ashmark", "ashshop"), (s, a) -> { if (s instanceof Player p) shop(p); });
        LabExperiments.command(c.plugin(), ID, "ashfall", "Ashfall: when is the next one", List.of(), this::command);
    }

    // ------------------------------------------------------------------ the night itself

    private void command(CommandSender s, String[] a) {
        if (a.length > 0 && s.hasPermission("blackforge.staff")) {
            if (a[0].equalsIgnoreCase("start")) { forced = true; check(); return; }
            if (a[0].equalsIgnoreCase("stop")) { forced = false; check(); return; }
        }
        long day = c.world().getFullTime() / 24000;
        long wait = 0;
        while (!LabMath.ashfallNight(day + wait, every) && wait < 100) wait++;
        s.sendMessage(Ui.mm(NAME + " <gray>" + (active ? "is falling now." : wait == 0 ? "falls tonight." : "falls in <white>" + wait + "<gray> nights.")
                + (s.hasPermission("blackforge.staff") ? " <dark_gray>(/ashfall start|stop)" : "")));
    }

    private void check() {
        World w = c.world();
        long day = w.getFullTime() / 24000;
        boolean night = LabMath.isNight(w.getTime());
        boolean should = Lab.enabled(ID) && (forced || night && LabMath.ashfallNight(day, every));
        if (Lab.enabled(ID) && night && warnedDay != day && LabMath.ashfallTomorrow(day, every)) {
            warnedDay = day;
            for (Player p : w.getPlayers()) p.sendMessage(Ui.mm(NAME + " <gray>The air smells of smoke. <white>Ash falls tomorrow night."));
        }
        if (should == active) return;
        active = should;
        for (Player p : w.getPlayers()) {
            if (active) {
                p.setPlayerWeather(WeatherType.DOWNFALL);
                p.showTitle(Title.title(Ui.mm(NAME), Ui.mm("<gray>Ember-touched mobs roam. Claims are safe."),
                        Title.Times.times(Duration.ofMillis(800), Duration.ofSeconds(3), Duration.ofSeconds(1))));
                p.playSound(p.getLocation(), Sound.AMBIENT_BASALT_DELTAS_MOOD, SoundCategory.AMBIENT, 1f, 0.6f);
            } else {
                p.resetPlayerWeather();
                p.sendMessage(Ui.mm(NAME + " <gray>Dawn. The ash settles."));
            }
        }
    }

    /** Ash drifting around everyone outside a claim while it's on. */
    private void ash() {
        if (!active) return;
        for (Player p : c.world().getPlayers()) {
            if (c.claimed().test(p.getLocation())) continue;
            Location at = p.getLocation().add(0, 4, 0);
            p.spawnParticle(Particle.ASH, at, 25, 8, 3, 8, 0);
            p.spawnParticle(Particle.WHITE_ASH, at, 10, 8, 3, 8, 0);
            // ember-touched mobs smoulder so they read from a distance
            for (var en : p.getNearbyEntities(24, 12, 24))
                if (en instanceof Monster m && m.getPersistentDataContainer().has(ember, PersistentDataType.BYTE))
                    p.spawnParticle(Particle.FLAME, m.getLocation().add(0, m.getHeight() * 0.6, 0), 3, 0.2, 0.3, 0.2, 0.01);
        }
    }

    /** Joining, respawning into or coming back to the world mid-Ashfall gets the ash sky; anywhere else is reset. */
    private void weather(Player p) {
        if (active && p.getWorld() == c.world()) p.setPlayerWeather(WeatherType.DOWNFALL);
        else p.resetPlayerWeather();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { weather(e.getPlayer()); }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) { weather(e.getPlayer()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!active || !(e.getEntity() instanceof Monster m) || e.getLocation().getWorld() != c.world()) return;
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL || c.claimed().test(e.getLocation())) return;
        m.getPersistentDataContainer().set(ember, PersistentDataType.BYTE, (byte) 1);
        AttributeInstance hp = m.getAttribute(Attribute.MAX_HEALTH);
        if (hp != null) { hp.setBaseValue(hp.getBaseValue() * healthBoost); m.setHealth(hp.getBaseValue()); }
        AttributeInstance speed = m.getAttribute(Attribute.MOVEMENT_SPEED);
        if (speed != null) speed.setBaseValue(speed.getBaseValue() * 1.1);
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        LivingEntity en = e.getEntity();
        if (!en.getPersistentDataContainer().has(ember, PersistentDataType.BYTE) || en.getKiller() == null || !Lab.enabled(ID)) return;
        int n = 1 + ThreadLocalRandom.current().nextInt(3);
        e.getDrops().add(ashmarks(n));
        en.getWorld().spawnParticle(Particle.WHITE_ASH, en.getLocation().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0.02);
    }

    // ------------------------------------------------------------------ Ashmarks

    private ItemStack ashmarks(int n) {
        ItemStack it = Ui.item(Material.GUNPOWDER, MARK, "<gray>Dropped by ember-touched mobs", "<gray>during an Ashfall. Spend at <white>/ashmarks", "<dark_gray>Survival 2 experiment");
        it.editMeta(meta -> { meta.setEnchantmentGlintOverride(true); meta.getPersistentDataContainer().set(mark, PersistentDataType.BYTE, (byte) 1); });
        it.setAmount(n);
        return it;
    }

    private boolean isMark(ItemStack s) {
        return s != null && s.hasItemMeta() && s.getItemMeta().getPersistentDataContainer().has(mark, PersistentDataType.BYTE);
    }

    /** Ashmarks are gunpowder underneath: never let them be crafted into anything. */
    @EventHandler
    public void onCraft(PrepareItemCraftEvent e) {
        for (ItemStack s : e.getInventory().getMatrix()) if (isMark(s)) { e.getInventory().setResult(null); return; }
    }

    private static final class Shop implements InventoryHolder {
        Inventory inv;
        public Inventory getInventory() { return inv; }
    }

    private void shop(Player p) {
        Shop s = new Shop();
        s.inv = Bukkit.createInventory(s, 9, Ui.mm(MARK + " <dark_gray>trades"));
        int have = count(p);
        for (int i = 0; i < offers.size(); i++) {
            Offer o = offers.get(i);
            ItemStack icon = o.icon().clone();
            icon.editMeta(meta -> {
                List<net.kyori.adventure.text.Component> lore = new java.util.ArrayList<>();
                lore.add(Ui.mm("<gray>Cost: <white>" + o.cost() + " " + MARK));
                lore.add(Ui.mm(have >= o.cost() ? "<#7bd88f>Click to trade" : "<#e5484d>You have " + have));
                meta.lore(lore);
            });
            s.inv.setItem(i * 2, icon);
        }
        p.openInventory(s.inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Shop)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory() || e.getRawSlot() % 2 != 0) return;
        int i = e.getRawSlot() / 2;
        if (i >= offers.size() || !Lab.enabled(ID)) return;
        Offer o = offers.get(i);
        if (count(p) < o.cost()) { p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f); return; }
        take(p, o.cost());
        o.give().accept(p);
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1f, 0.7f);
        Bukkit.getScheduler().runTask(c.plugin(), () -> { if (p.isOnline()) shop(p); });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Shop) e.setCancelled(true); }

    private int count(Player p) {
        int n = 0;
        for (ItemStack s : p.getInventory().getStorageContents()) if (isMark(s)) n += s.getAmount();
        return n;
    }

    private void take(Player p, int n) {
        ItemStack[] inv = p.getInventory().getStorageContents();
        for (int i = 0; i < inv.length && n > 0; i++) {
            if (!isMark(inv[i])) continue;
            int t = Math.min(n, inv[i].getAmount());
            inv[i].setAmount(inv[i].getAmount() - t);
            n -= t;
        }
        p.getInventory().setStorageContents(inv);
    }

    private static void give(Player p, ItemStack s) {
        p.getInventory().addItem(s).values().forEach(r -> p.getWorld().dropItemNaturally(p.getLocation(), r));
    }

}
