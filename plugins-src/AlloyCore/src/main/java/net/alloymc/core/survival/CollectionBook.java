package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.NetEconomy;
import net.alloymc.core.Ui;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.*;

/**
 * The Collection Book: every item you have ever held in survival is ticked off. /collection opens the book (45 items a
 * page); milestones pay money, and the full book pays a Forgeheart Key. Items are noticed on pickup, on crafting and by
 * a light inventory sweep every 20 seconds.
 */
final class CollectionBook implements Listener {
    private static final List<Material> ALL;
    static {
        List<Material> l = new ArrayList<>();
        for (Material m : Material.values()) if (collectible(m)) l.add(m);
        l.sort(Comparator.comparing(Enum::name));
        ALL = List.copyOf(l);
    }

    private static boolean collectible(Material m) {
        if (!m.isItem() || m.isAir() || m.isLegacy()) return false;
        String n = m.name();
        return !(n.contains("COMMAND_BLOCK") || n.equals("BARRIER") || n.startsWith("STRUCTURE_") || n.equals("JIGSAW") || n.equals("DEBUG_STICK") || n.equals("LIGHT")
                || n.equals("KNOWLEDGE_BOOK") || n.equals("BEDROCK") || n.endsWith("_SPAWN_EGG") || n.equals("END_PORTAL_FRAME") || n.equals("REINFORCED_DEEPSLATE")
                || n.startsWith("TEST_") || n.equals("SPAWNER") || n.equals("TRIAL_SPAWNER") || n.equals("PETRIFIED_OAK_SLAB") || n.equals("FARMLAND")
                || n.equals("CHORUS_PLANT") || n.equals("BUDDING_AMETHYST") || n.equals("INFESTED_STONE") || n.startsWith("INFESTED_"));
    }

    private static final int[] MILESTONES = {50, 100, 250, 500, 750, 1000};
    private static final double[] PAY = {500, 1500, 4000, 10000, 20000, 40000};

    private final AlloyCore plugin;
    private final File file;
    private final YamlConfiguration data;
    private final Map<UUID, Set<Material>> found = new HashMap<>();
    private boolean dirty;

    private static final class Book implements InventoryHolder {
        final int page; Inventory inv;
        Book(int page) { this.page = page; }
        public Inventory getInventory() { return inv; }
    }

    CollectionBook(AlloyCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "collection.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getCommandMap().register("alloy", new Command("collection", "Your Collection Book", "/collection [page]", List.of("book", "collections")) {
            @Override public boolean execute(CommandSender s, String label, String[] a) {
                if (s instanceof Player p) { int pg = 0; try { if (a.length > 0) pg = Integer.parseInt(a[0]) - 1; } catch (NumberFormatException ignored) { } open(p, pg); }
                else s.sendMessage("Players only.");
                return true;
            }
        });
        Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, 400L, 400L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::save, 6000L, 6000L);
    }

    private Set<Material> of(UUID u) {
        return found.computeIfAbsent(u, k -> {
            Set<Material> s = EnumSet.noneOf(Material.class);
            for (String n : data.getStringList(u + ".items")) { Material m = Material.matchMaterial(n); if (m != null) s.add(m); }
            return s;
        });
    }

    void save() {
        if (!dirty) return;
        dirty = false;
        found.forEach((u, set) -> data.set(u + ".items", set.stream().map(Enum::name).sorted().toList()));
        try { data.save(file); } catch (Exception e) { plugin.getLogger().warning("collection save: " + e); }
    }

    private void add(Player p, Material m) {
        if (!collectible(m)) return;
        Set<Material> s = of(p.getUniqueId());
        if (!s.add(m)) return;
        dirty = true;
        int n = s.size();
        p.sendActionBar(Ui.mm("<#ffb347>📖 New in your collection: <white>" + pretty(m) + " <gray>(" + n + "/" + ALL.size() + ")"));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.4f, 1.6f);
        for (int i = 0; i < MILESTONES.length; i++) if (n == MILESTONES[i]) {
            plugin.economy().deposit(p, PAY[i]);
            p.sendMessage(Ui.mm("<#ffb347>📖 <white>Collection milestone: " + n + " items! <gray>You earned <green>" + NetEconomy.money(PAY[i]) + "<gray>."));
        }
        if (n == ALL.size()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "alloy crate give " + p.getName() + " netherite 1");
            Bukkit.broadcast(Ui.mm("<#ffd27a>📖 <white>" + p.getName() + " <gray>completed the entire <#ffd27a><bold>Collection Book</bold><gray>!"));
        }
    }

    private static String pretty(Material m) {
        StringBuilder sb = new StringBuilder();
        for (String w : m.name().toLowerCase().split("_")) sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
        return sb.toString().trim();
    }

    private void sweep() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld() != Bukkit.getWorlds().getFirst()) continue;
            for (ItemStack it : p.getInventory().getContents()) if (it != null && !it.getType().isAir()) add(p, it.getType());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { Bukkit.getScheduler().runTaskLater(plugin, () -> { if (e.getPlayer().isOnline()) for (ItemStack it : e.getPlayer().getInventory().getContents()) if (it != null) add(e.getPlayer(), it.getType()); }, 60L); }

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) { if (e.getEntity() instanceof Player p) add(p, e.getItem().getItemStack().getType()); }

    @EventHandler
    public void onCraft(CraftItemEvent e) { if (e.getWhoClicked() instanceof Player p && e.getRecipe().getResult() != null) add(p, e.getRecipe().getResult().getType()); }

    // ------------------------------------------------------------------ the book

    void open(Player p, int page) {
        int pages = (ALL.size() + 44) / 45;
        page = Math.max(0, Math.min(pages - 1, page));
        Set<Material> mine = of(p.getUniqueId());
        Book h = new Book(page);
        Inventory inv = Bukkit.createInventory(h, 54, Ui.mm("<dark_gray>📖 Collection Book " + (page + 1) + "/" + pages));
        h.inv = inv;
        for (int i = 0; i < 45; i++) {
            int idx = page * 45 + i;
            if (idx >= ALL.size()) break;
            Material m = ALL.get(idx);
            inv.setItem(i, mine.contains(m) ? Ui.item(m, "<white>" + pretty(m), "<green>Collected") : Ui.item(Material.GRAY_DYE, "<dark_gray>???", "<gray>Not collected yet"));
        }
        ItemStack pane = Ui.item(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 45; i < 54; i++) inv.setItem(i, pane);
        int next = 0;
        for (int mls : MILESTONES) if (mls > mine.size()) { next = mls; break; }
        inv.setItem(49, Ui.item(Material.BOOK, "<#ffb347><bold>" + mine.size() + " / " + ALL.size() + " collected", "<gray>" + (ALL.isEmpty() ? 0 : 100 * mine.size() / ALL.size()) + "% complete",
                next == 0 ? "<green>All milestones reached!" : "<gray>Next reward at <white>" + next + " <gray>items"));
        if (page > 0) inv.setItem(45, Ui.item(Material.ARROW, "<yellow>« Previous page"));
        if (page < pages - 1) inv.setItem(53, Ui.item(Material.ARROW, "<yellow>Next page »"));
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Book b)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        if (e.getRawSlot() == 45 && b.page > 0) open(p, b.page - 1);
        else if (e.getRawSlot() == 53) open(p, b.page + 1);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Book) e.setCancelled(true); }
}
