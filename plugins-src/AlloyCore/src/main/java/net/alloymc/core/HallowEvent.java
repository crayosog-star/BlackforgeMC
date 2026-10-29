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
import org.bukkit.persistence.PersistentDataType;

import java.time.Duration;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Consumer;

/**
 * Hallowforge, the Halloween event (config event.hallowforge.start / end, default 28 Oct - 10 Nov).
 * <ul>
 *   <li>Survival, Skyblock and Factions: hostile mobs killed by players can drop Soul Candy (more at night),
 *       and player kills always drop one.</li>
 *   <li>/hallow: trade Soul Candy for Scrap, Ingots, a Jack o' Crown hat, crate keys and the event-only
 *       Pumpkin Burst kill effect.</li>
 *   <li>The hub turns spooky: drifting orange and purple embers, bats' wings, a title on join.</li>
 * </ul>
 * Outside the dates it does nothing, so it can stay installed and come back next year by changing the dates.
 */
public final class HallowEvent implements Listener {
    private record Offer(int cost, Material icon, String name, String lore, Consumer<Player> give) {}

    private final AlloyCore plugin;
    private final String mode;
    private final Cosmetics cosmetics;
    private final NamespacedKey candyKey;
    private final LocalDate start, end;
    private final Random rnd = new Random();
    private final List<Offer> shop = new ArrayList<>();

    public HallowEvent(AlloyCore plugin, String mode, Cosmetics cosmetics) {
        this.plugin = plugin;
        this.mode = mode;
        this.cosmetics = cosmetics;
        this.candyKey = new NamespacedKey(plugin, "soul_candy");
        this.start = LocalDate.parse(plugin.getConfig().getString("event.hallowforge.start", "2026-10-28"));
        this.end = LocalDate.parse(plugin.getConfig().getString("event.hallowforge.end", "2026-11-10"));
        defineShop();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getCommandMap().register("alloy", new Command("hallow", "Hallowforge event shop", "/hallow", List.of("hallowforge", "candy", "halloween")) {
            @Override public boolean execute(CommandSender s, String label, String[] a) {
                if (!(s instanceof Player p)) return true;
                if (!active()) p.sendMessage(Ui.mm("<gray>Hallowforge isn't on right now - it runs from " + start + " to " + end + "."));
                else if (!openWorld()) p.sendMessage(Ui.mm("<gold>🎃 <gray>Soul Candy drops on <white>Survival, Skyblock and Factions<gray> - spend it there with <white>/hallow<gray>."));
                else open(p);
                return true;
            }
        });
        if (mode.equals("hub")) Bukkit.getScheduler().runTaskTimer(plugin, this::spookyHub, 40L, 10L);
    }

    public boolean active() {
        LocalDate d = LocalDate.now();
        return !d.isBefore(start) && !d.isAfter(end);
    }

    private boolean openWorld() { return mode.equals("survival") || mode.equals("skyblock") || mode.equals("factions"); }

    // ------------------------------------------------------------------ candy

    public ItemStack candy(int n) {
        ItemStack it = Ui.item(Material.POPPED_CHORUS_FRUIT, "<gradient:#ff9a1f:#9b4dff>Soul Candy</gradient>",
                "<gray>Hallowforge event currency", "<gray>Spend it with <white>/hallow");
        it.setAmount(n);
        it.editMeta(m -> { m.setEnchantmentGlintOverride(true); m.getPersistentDataContainer().set(candyKey, PersistentDataType.BYTE, (byte) 1); });
        return it;
    }

    private boolean isCandy(ItemStack it) {
        return it != null && it.getType() == Material.POPPED_CHORUS_FRUIT && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(candyKey);
    }

    private int count(Player p) {
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) if (isCandy(it)) n += it.getAmount();
        return n;
    }

    private boolean take(Player p, int n) {
        if (count(p) < n) return false;
        for (ItemStack it : p.getInventory().getContents()) {
            if (n <= 0) break;
            if (!isCandy(it)) continue;
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
        long time = e.getEntity().getWorld().getTime();
        double chance = player ? 1 : (time > 13000 && time < 23000 ? 0.16 : 0.08);
        if (rnd.nextDouble() >= chance) return;
        e.getDrops().add(candy(1));
        Location l = e.getEntity().getLocation().add(0, 1, 0);
        l.getWorld().spawnParticle(Particle.DUST, l, 15, 0.3, 0.4, 0.3, new Particle.DustOptions(Color.ORANGE, 1.4f));
        l.getWorld().spawnParticle(Particle.WITCH, l, 10, 0.3, 0.4, 0.3, 0);
    }

    // ------------------------------------------------------------------ shop

    private void defineShop() {
        shop.add(new Offer(5, Material.IRON_NUGGET, "<#c6a15b>75 Scrap", "", p -> plugin.economy().addScrap(p, 75)));
        shop.add(new Offer(12, Material.GOLD_NUGGET, "<green>$2,500", "", p -> plugin.economy().deposit(p, 2500)));
        shop.add(new Offer(20, Material.TRIPWIRE_HOOK, "<#e0895a>Cinder Key", "<gray>(Survival and Skyblock)", p -> crate(p, "iron")));
        shop.add(new Offer(30, Material.CARVED_PUMPKIN, "<gold>Jack o' Crown", "<gray>A wearable pumpkin hat", p -> {
            ItemStack hat = Ui.item(Material.CARVED_PUMPKIN, "<gradient:#ff9a1f:#ffd27a><bold>Jack o' Crown</bold></gradient>", "<gray>Hallowforge " + start.getYear());
            hat.editMeta(m -> m.setEnchantmentGlintOverride(true));
            p.getInventory().addItem(hat).values().forEach(l -> p.getWorld().dropItemNaturally(p.getLocation(), l));
        }));
        shop.add(new Offer(45, Material.TRIPWIRE_HOOK, "<#ffb52e>Molten Key", "<gray>(Survival and Skyblock)", p -> crate(p, "gold")));
        shop.add(new Offer(60, Material.JACK_O_LANTERN, "<gold>Pumpkin Burst <gray>kill effect", "<gray>Event exclusive - equip it in /cosmetics", p -> {
            cosmetics.unlock(p, "pumpkin");
            Bukkit.broadcast(Ui.mm("<gold>🎃 <white>" + p.getName() + " <gray>unlocked the <gold>Pumpkin Burst<gray>! <yellow>/hallow"));
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
        m.inv = Bukkit.createInventory(m, 9, Ui.mm("<dark_gray>Hallowforge <gray>- " + have + " Soul Candy"));
        for (int i = 0; i < shop.size(); i++) {
            Offer o = shop.get(i);
            List<String> lore = new ArrayList<>();
            if (!o.lore.isEmpty()) lore.add(o.lore);
            lore.add("<gray>Costs <gradient:#ff9a1f:#9b4dff>" + o.cost + " Soul Candy</gradient>");
            lore.add(have >= o.cost ? "<yellow>Click to trade" : "<red>You need " + (o.cost - have) + " more");
            m.inv.setItem(i, Ui.item(o.icon, o.name, lore.toArray(new String[0])));
        }
        m.inv.setItem(8, Ui.item(Material.POPPED_CHORUS_FRUIT, "<gradient:#ff9a1f:#9b4dff>Soul Candy</gradient>",
                "<gray>Hostile mobs drop it (twice as often at night)", "<gray>and every player kill drops one.", "<gray>Event ends " + end));
        p.openInventory(m.inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu) || !(e.getWhoClicked() instanceof Player p)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory() || e.getSlot() >= shop.size()) return;
        Offer o = shop.get(e.getSlot());
        if (o.give == null || !take(p, o.cost)) { p.sendMessage(Ui.mm("<red>Not enough Soul Candy.")); return; }
        o.give.accept(p);
        Hooks.stat(p, "event_trade", 1);
        p.sendMessage(Ui.mm("<gold>🎃 <gray>Traded <gradient:#ff9a1f:#9b4dff>" + o.cost + " Soul Candy</gradient> <gray>for " + o.name));
        p.playSound(p.getLocation(), Sound.ENTITY_WITCH_CELEBRATE, 0.8f, 1.2f);
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
                p.showTitle(Title.title(Ui.mm("<gradient:#ff9a1f:#9b4dff><bold>HALLOWFORGE</bold></gradient>"), Ui.mm("<gray>Soul Candy drops in every open world - <white>/hallow"),
                        Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(3), Duration.ofMillis(800))));
            else if (openWorld()) p.sendMessage(Ui.mm("<gold>🎃 <gradient:#ff9a1f:#9b4dff><bold>Hallowforge</bold></gradient> <gray>is on until " + end + ": mobs drop Soul Candy - spend it with <white>/hallow"));
        }, 60L);
    }

    private void spookyHub() {
        if (!active()) return;
        World w = Bukkit.getWorlds().getFirst();
        if (w.getPlayers().isEmpty()) return;
        for (int i = 0; i < 8; i++) {
            double a = rnd.nextDouble() * Math.PI * 2, r = 6 + rnd.nextDouble() * 38;
            Location l = new Location(w, Math.cos(a) * r, 101.5 + rnd.nextDouble() * 6, Math.sin(a) * r);
            w.spawnParticle(Particle.DUST_COLOR_TRANSITION, l, 1, 0.2, 0.2, 0.2, 0,
                    new Particle.DustTransition(rnd.nextBoolean() ? Color.fromRGB(255, 140, 20) : Color.fromRGB(150, 60, 255), Color.fromRGB(30, 10, 40), 1.6f));
        }
        if (rnd.nextInt(40) == 0) {
            Player p = w.getPlayers().get(rnd.nextInt(w.getPlayers().size()));
            p.playSound(p.getLocation().add(rnd.nextGaussian() * 6, 3, rnd.nextGaussian() * 6), Sound.ENTITY_BAT_AMBIENT, 0.6f, 0.8f);
        }
    }
}
