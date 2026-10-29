package net.alloymc.core;

import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Consumer;

/**
 * Frostforge, the winter event (config event.frostforge.start / end, default 1 Dec - 6 Jan). The same shape as
 * {@link HallowEvent}: hostile mobs killed by players in the open worlds drop Frost Shards (more in cold biomes and at
 * night), player kills always drop one, and /frost trades them for Scrap, money, keys, the Frostbound Crown and the
 * event-only Frost Nova kill effect. The hub gets drifting snow. Outside the dates it does nothing.
 */
public final class FrostEvent implements Listener {
    private record Offer(int cost, Material icon, String name, String lore, Consumer<Player> give) {}

    private final AlloyCore plugin;
    private final String mode;
    private final Cosmetics cosmetics;
    private final NamespacedKey shardKey;
    private final LocalDate start, end;
    private final boolean textures;
    private final Random rnd = new Random();
    private final List<Offer> shop = new ArrayList<>();

    public FrostEvent(AlloyCore plugin, String mode, Cosmetics cosmetics) {
        this.plugin = plugin;
        this.mode = mode;
        this.cosmetics = cosmetics;
        this.shardKey = new NamespacedKey(plugin, "frost_shard");
        this.start = LocalDate.parse(plugin.getConfig().getString("event.frostforge.start", "2026-12-01"));
        this.end = LocalDate.parse(plugin.getConfig().getString("event.frostforge.end", "2027-01-06"));
        this.textures = plugin.getConfig().getBoolean("resource-pack-live", false);
        defineShop();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getCommandMap().register("alloy", new Command("frost", "Frostforge event shop", "/frost", List.of("frostforge", "winter", "shards")) {
            @Override public boolean execute(CommandSender s, String label, String[] a) {
                if (!(s instanceof Player p)) return true;
                if (!active()) p.sendMessage(Ui.mm("<gray>Frostforge isn't on right now - it runs from " + start + " to " + end + "."));
                else if (!openWorld()) p.sendMessage(Ui.mm("<#9fe6ff>❄ <gray>Frost Shards drop on <white>Survival, Skyblock and Factions<gray> - spend them there with <white>/frost<gray>."));
                else open(p);
                return true;
            }
        });
        if (mode.equals("hub")) Bukkit.getScheduler().runTaskTimer(plugin, this::snowyHub, 40L, 10L);
    }

    public boolean active() {
        LocalDate d = LocalDate.now();
        return !d.isBefore(start) && !d.isAfter(end);
    }

    private boolean openWorld() { return mode.equals("survival") || mode.equals("skyblock") || mode.equals("factions"); }

    // ------------------------------------------------------------------ shards

    public ItemStack shard(int n) {
        ItemStack it = Ui.item(Material.PRISMARINE_SHARD, "<gradient:#e8fbff:#4fa3ff>Frost Shard</gradient>",
                "<gray>Frostforge event currency", "<gray>Spend it with <white>/frost");
        it.setAmount(n);
        it.editMeta(m -> {
            m.setEnchantmentGlintOverride(true);
            m.getPersistentDataContainer().set(shardKey, PersistentDataType.BYTE, (byte) 1);
            if (textures) m.setItemModel(new NamespacedKey("blackforge", "frost_shard"));
        });
        return it;
    }

    private boolean isShard(ItemStack it) {
        return it != null && it.getType() == Material.PRISMARINE_SHARD && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(shardKey);
    }

    private int count(Player p) {
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) if (isShard(it)) n += it.getAmount();
        return n;
    }

    private boolean take(Player p, int n) {
        if (count(p) < n) return false;
        for (ItemStack it : p.getInventory().getContents()) {
            if (n <= 0) break;
            if (!isShard(it)) continue;
            int t = Math.min(n, it.getAmount());
            it.setAmount(it.getAmount() - t);
            n -= t;
        }
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent e) {
        if (!active() || !openWorld()) return;
        Player killer = e.getEntity().getKiller();
        if (killer == null) return;
        boolean player = e.getEntity() instanceof Player;
        if (!player && !(e.getEntity() instanceof Enemy)) return;
        Location at = e.getEntity().getLocation();
        long time = at.getWorld().getTime();
        boolean cold = at.getBlock().getTemperature() < 0.2;
        double chance = player ? 1 : ((time > 13000 && time < 23000) ? 0.16 : 0.08) * (cold ? 1.5 : 1);
        if (rnd.nextDouble() >= chance) return;
        e.getDrops().add(shard(1));
        Location l = at.clone().add(0, 1, 0);
        l.getWorld().spawnParticle(Particle.SNOWFLAKE, l, 20, 0.3, 0.4, 0.3, 0.02);
        l.getWorld().spawnParticle(Particle.DUST, l, 12, 0.3, 0.4, 0.3, new Particle.DustOptions(Color.fromRGB(160, 230, 255), 1.4f));
    }

    // ------------------------------------------------------------------ shop

    private void defineShop() {
        shop.add(new Offer(5, Material.IRON_NUGGET, "<#c6a15b>75 Scrap", "", p -> plugin.economy().addScrap(p, 75)));
        shop.add(new Offer(12, Material.GOLD_NUGGET, "<green>$2,500", "", p -> plugin.economy().deposit(p, 2500)));
        shop.add(new Offer(20, Material.TRIPWIRE_HOOK, "<#e0895a>Cinder Key", "<gray>(Survival and Skyblock)", p -> crate(p, "iron")));
        shop.add(new Offer(30, Material.LEATHER_HELMET, "<#9fe6ff>Frostbound Crown", "<gray>A wearable crown of ice", p -> {
            ItemStack hat = Ui.item(Material.LEATHER_HELMET, "<gradient:#e8fbff:#4fa3ff><bold>Frostbound Crown</bold></gradient>", "<gray>Frostforge " + start.getYear());
            hat.editMeta(LeatherArmorMeta.class, m -> { m.setColor(Color.fromRGB(150, 220, 255)); m.setEnchantmentGlintOverride(true); });
            p.getInventory().addItem(hat).values().forEach(l -> p.getWorld().dropItemNaturally(p.getLocation(), l));
        }));
        shop.add(new Offer(45, Material.TRIPWIRE_HOOK, "<#ffb52e>Molten Key", "<gray>(Survival and Skyblock)", p -> crate(p, "gold")));
        shop.add(new Offer(60, Material.BLUE_ICE, "<#9fe6ff>Frost Nova <gray>kill effect", "<gray>Event exclusive - equip it in /cosmetics", p -> {
            cosmetics.unlock(p, "frost");
            Bukkit.broadcast(Ui.mm("<#9fe6ff>❄ <white>" + p.getName() + " <gray>unlocked the <#9fe6ff>Frost Nova<gray>! <yellow>/frost"));
        }));
    }

    private void crate(Player p, String crate) {
        if (mode.equals("factions")) { plugin.economy().addScrap(p, crate.equals("gold") ? 600 : 250); p.sendMessage(Ui.mm("<gray>(No crates on Factions - you got Scrap instead.)")); return; }
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "alloy crate give " + p.getName() + " " + crate + " 1");
    }

    private static final class Menu implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private void open(Player p) {
        Menu m = new Menu();
        int have = count(p);
        m.inv = Bukkit.createInventory(m, 9, Ui.mm("<dark_gray>Frostforge <gray>- " + have + " Frost Shards"));
        for (int i = 0; i < shop.size(); i++) {
            Offer o = shop.get(i);
            List<String> lore = new ArrayList<>();
            if (!o.lore.isEmpty()) lore.add(o.lore);
            lore.add("<gray>Costs <gradient:#e8fbff:#4fa3ff>" + o.cost + " Frost Shards</gradient>");
            lore.add(have >= o.cost ? "<yellow>Click to trade" : "<red>You need " + (o.cost - have) + " more");
            m.inv.setItem(i, Ui.item(o.icon, o.name, lore.toArray(new String[0])));
        }
        m.inv.setItem(8, shard(1));
        m.inv.getItem(8).editMeta(meta -> meta.lore(List.of(Ui.mm("<gray>Hostile mobs drop them (more at night"), Ui.mm("<gray>and in snowy places), and every"),
                Ui.mm("<gray>player kill drops one."), Ui.mm("<gray>Event ends " + end))));
        p.openInventory(m.inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu) || !(e.getWhoClicked() instanceof Player p)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory() || e.getSlot() >= shop.size()) return;
        Offer o = shop.get(e.getSlot());
        if (!take(p, o.cost)) { p.sendMessage(Ui.mm("<red>Not enough Frost Shards.")); return; }
        o.give.accept(p);
        Hooks.stat(p, "event_trade", 1);
        p.sendMessage(Ui.mm("<#9fe6ff>❄ <gray>Traded <gradient:#e8fbff:#4fa3ff>" + o.cost + " Frost Shards</gradient> <gray>for " + o.name));
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9f, 1.2f);
        open(p);
    }

    // ------------------------------------------------------------------ hub

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!active()) return;
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            if (mode.equals("hub"))
                p.showTitle(Title.title(Ui.mm("<gradient:#e8fbff:#4fa3ff><bold>FROSTFORGE</bold></gradient>"), Ui.mm("<gray>Frost Shards drop in every open world - <white>/frost"),
                        Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(3), Duration.ofMillis(800))));
            else if (openWorld()) p.sendMessage(Ui.mm("<#9fe6ff>❄ <gradient:#e8fbff:#4fa3ff><bold>Frostforge</bold></gradient> <gray>is on until " + end + ": mobs drop Frost Shards - spend them with <white>/frost"));
        }, 70L);
    }

    private void snowyHub() {
        if (!active()) return;
        World w = Bukkit.getWorlds().getFirst();
        for (Player p : w.getPlayers())
            w.spawnParticle(Particle.SNOWFLAKE, p.getLocation().add(rnd.nextGaussian() * 8, 6 + rnd.nextDouble() * 4, rnd.nextGaussian() * 8), 6, 4, 1, 4, 0.01);
    }
}
