package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * /kit: the rank kit, once every 10 days - your rank's name tag plus your tier's crate keys
 * (cosmetic + keys; no gear). Only your own (highest) rank's kit can be claimed.
 */
public final class RankKits implements Listener {
    static final long COOLDOWN = 10L * 86_400_000L;
    private final AlloyCore plugin;
    private final Ranks ranks;
    private final NameTags tags;
    private final Crates crates;
    private final File file;
    private final YamlConfiguration data;

    RankKits(AlloyCore plugin, Ranks ranks, NameTags tags, Crates crates) {
        this.plugin = plugin;
        this.ranks = ranks;
        this.tags = tags;
        this.crates = crates;
        this.file = new File(plugin.getDataFolder(), "rank-kits.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /** The kit for rank #i (0 = Copper ... 4 = Netherite): the rank's name tag + its keys. */
    List<ItemStack> contents(int i) {
        Ranks.Rank r = Ranks.RANKS.get(i);
        List<ItemStack> items = new ArrayList<>();
        NameTags.Tag t = NameTags.tag("rank_" + r.id());
        if (t != null) items.add(tags.item(t));
        for (String[] k : r.keys()) if (crates.ids().contains(k[0])) items.add(crates.key(k[0], Integer.parseInt(k[1])));
        return items;
    }

    private String describe(int i) {
        Ranks.Rank r = Ranks.RANKS.get(i);
        List<String> parts = new ArrayList<>();
        for (String[] k : r.keys()) parts.add(k[1] + "x " + crates.keyName(k[0]) + "<reset>");
        return r.name() + " name tag + " + String.join(", ", parts);
    }

    private long readyAt(Player p, String kit) { return data.getLong(p.getUniqueId() + "." + kit, 0); }

    private static String fmt(long ms) {
        long d = ms / 86_400_000L, h = ms / 3_600_000L % 24, m = ms / 60_000L % 60;
        return d > 0 ? d + "d " + h + "h" : h > 0 ? h + "h " + m + "m" : Math.max(1, m) + "m";
    }

    // ------------------------------------------------------------------ menu

    private static final class Menu implements InventoryHolder {
        final int preview; Inventory inv;
        Menu(int preview) { this.preview = preview; }
        public Inventory getInventory() { return inv; }
    }

    private static final int[] SLOTS = {11, 12, 13, 14, 15};
    private static final Material[] ICONS = {Material.COPPER_INGOT, Material.IRON_INGOT, Material.GOLD_INGOT, Material.DIAMOND, Material.NETHERITE_INGOT};

    public void open(Player p) {
        Menu m = new Menu(-1);
        m.inv = Bukkit.createInventory(m, 27, Ui.mm("<dark_gray>Rank Kits"));
        ItemStack pane = Ui.item(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) m.inv.setItem(i, pane);
        Ranks.Rank mine = ranks.of(p);
        int myIdx = mine == null ? -1 : Ranks.RANKS.indexOf(mine);
        long now = System.currentTimeMillis();
        for (int i = 0; i < Ranks.RANKS.size(); i++) {
            Ranks.Rank r = Ranks.RANKS.get(i);
            long ready = readyAt(p, r.id());
            List<String> lore = new ArrayList<>(List.of("<gray>" + describe(i), "", "<gray>Cooldown: <white>10 days"));
            if (i > myIdx) lore.add("<red>🔒 Needs the " + r.color() + r.name() + " <red>rank");
            else if (i < myIdx) lore.add("<dark_gray>Your " + Ranks.RANKS.get(myIdx).name() + " kit replaces this one");
            else if (ready > now) lore.add("<red>Ready again in " + fmt(ready - now));
            else lore.add("<green>» Left-click to claim");
            lore.add("<yellow>» Right-click to preview");
            Material icon = ICONS[i];
            ItemStack it = Ui.item(icon == null ? Material.IRON_CHESTPLATE : icon, r.color() + "<bold>" + r.name().toUpperCase() + " KIT", lore.toArray(String[]::new));
            if (i == myIdx && ready <= now) it.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
            m.inv.setItem(SLOTS[i], it);
        }
        m.inv.setItem(22, Ui.item(Material.PAPER, "<gold>Get a rank", "<gray>Ranks come from Rank Vouchers", "<gray>(KOTH crates, events, the store). <white>/ranks"));
        p.openInventory(m.inv);
    }

    private void preview(Player p, int i) {
        Menu m = new Menu(i);
        m.inv = Bukkit.createInventory(m, 27, Ui.mm(Ranks.RANKS.get(i).color() + Ranks.RANKS.get(i).name() + " Kit <dark_gray>preview"));
        for (ItemStack it : contents(i)) m.inv.addItem(it);
        m.inv.setItem(26, Ui.item(Material.ARROW, "<yellow>« Back"));
        p.openInventory(m.inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        if (m.preview >= 0) { if (e.getRawSlot() == 26) open(p); return; }
        for (int i = 0; i < SLOTS.length; i++) {
            if (e.getRawSlot() != SLOTS[i]) continue;
            if (e.getClick() == ClickType.RIGHT || e.getClick() == ClickType.SHIFT_RIGHT) { preview(p, i); return; }
            claim(p, i);
        }
    }

    private void claim(Player p, int i) {
        Ranks.Rank r = Ranks.RANKS.get(i), mine = ranks.of(p);
        if (mine == null || Ranks.RANKS.indexOf(mine) < i) { p.sendMessage(Ui.mm("<red>The " + r.name() + " kit needs the " + r.color() + r.name() + "<red> rank. <gray>/ranks")); return; }
        if (Ranks.RANKS.indexOf(mine) > i) { p.sendMessage(Ui.mm("<gray>Claim your " + mine.color() + mine.name() + "<gray> kit instead - it replaces the lower ones.")); return; }
        long now = System.currentTimeMillis(), ready = readyAt(p, r.id());
        if (ready > now) { p.sendMessage(Ui.mm("<red>You can claim the " + r.name() + " kit again in " + fmt(ready - now) + ".")); return; }
        List<ItemStack> items = contents(i);
        int free = 0;
        for (ItemStack s : p.getInventory().getStorageContents()) if (s == null || s.getType().isAir()) free++;
        if (free < items.size()) { p.sendMessage(Ui.mm("<red>You need " + items.size() + " free inventory slots.")); return; }
        for (ItemStack it : items) p.getInventory().addItem(it);
        data.set(p.getUniqueId() + "." + r.id(), now + COOLDOWN);
        try { data.save(file); } catch (Exception ignored) { }
        p.closeInventory();
        p.sendMessage(Ui.mm(r.color() + "<bold>" + r.name() + " kit</bold> <gray>claimed! Next one in 10 days."));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
    }
}
