package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.util.*;

/**
 * Name colour tags. A tag item unlocks a colour for your name (chat, tab list and above your
 * head); /tags lets you buy, switch between or remove the colours you own.
 */
public final class NameTags implements Listener {
    /** id, display name, MiniMessage open tag, colour used for the above-head nametag, price (0 = crate/event only). */
    public record Tag(String id, String name, String mm, NamedTextColor team, int price) {}

    public static final List<Tag> TAGS = List.of(
        new Tag("red", "Red", "<red>", NamedTextColor.RED, 2500),
        new Tag("orange", "Orange", "<gold>", NamedTextColor.GOLD, 2500),
        new Tag("yellow", "Yellow", "<yellow>", NamedTextColor.YELLOW, 2500),
        new Tag("lime", "Lime", "<green>", NamedTextColor.GREEN, 2500),
        new Tag("aqua", "Aqua", "<aqua>", NamedTextColor.AQUA, 2500),
        new Tag("blue", "Blue", "<blue>", NamedTextColor.BLUE, 2500),
        new Tag("pink", "Pink", "<light_purple>", NamedTextColor.LIGHT_PURPLE, 2500),
        new Tag("purple", "Purple", "<dark_purple>", NamedTextColor.DARK_PURPLE, 5000),
        new Tag("molten", "Molten", "<gradient:#ffd27a:#ff4d00>", NamedTextColor.GOLD, 25000),
        new Tag("oxidized", "Oxidized", "<gradient:#7ff0c8:#2a8f6b>", NamedTextColor.DARK_AQUA, 25000),
        new Tag("netherite", "Netherite", "<gradient:#b9a7ff:#4a4043>", NamedTextColor.DARK_PURPLE, 0),
        new Tag("rainbow", "Rainbow", "<rainbow>", NamedTextColor.LIGHT_PURPLE, 0),
        new Tag("kingslayer", "Kingslayer", "<gradient:#ff5f5f:#7a0000>", NamedTextColor.DARK_RED, 0),
        // store supporter colours: unlocked by the alloy.tag.<id> permission of the supporter group
        new Tag("ember", "Ember", "<gradient:#ffd8a8:#ffb347>", NamedTextColor.GOLD, -1),
        new Tag("blaze", "Blaze", "<gradient:#ffb347:#ff6a1f>", NamedTextColor.GOLD, -1),
        new Tag("inferno", "Inferno", "<gradient:#ff8a3d:#d4001a>", NamedTextColor.RED, -1),
        new Tag("forgemaster", "Forgemaster", "<gradient:#fff1b8:#ffb347:#ff4d00>", NamedTextColor.GOLD, -1),
        // rank tags (price -2): yours for as long as you hold that rank or higher; the 10-day /kit hands out the item
        new Tag("rank_copper", "Copper Rank", "<gradient:#f0a070:#b5582c>", NamedTextColor.GOLD, -2),
        new Tag("rank_iron", "Iron Rank", "<gradient:#ffffff:#9aa3ad>", NamedTextColor.WHITE, -2),
        new Tag("rank_gold", "Gold Rank", "<gradient:#fff1a8:#e0a400>", NamedTextColor.YELLOW, -2),
        new Tag("rank_diamond", "Diamond Rank", "<gradient:#c8fff9:#2fc4c4>", NamedTextColor.AQUA, -2),
        new Tag("rank_netherite", "Netherite Rank", "<gradient:#c9b8ff:#5b4a7a>", NamedTextColor.DARK_PURPLE, -2));

    /** Owns a colour: unlocked with a tag item or in-game money, a supporter colour, or the store's Name Colour Bundle (all priced colours). */
    private boolean has(Player p, Tag t) {
        if (t.price() == -2) {   // rank tag: unlocked by holding that rank (or a higher one)
            Ranks.Rank r = ranks == null ? null : ranks.of(p), need = Ranks.byId(t.id().substring(5));
            if (r != null && need != null && Ranks.RANKS.indexOf(r) >= Ranks.RANKS.indexOf(need)) return true;
        }
        return owned.getOrDefault(p.getUniqueId(), Set.of()).contains(t.id()) || p.hasPermission("alloy.admin")
            || p.hasPermission("alloy.tag." + t.id()) || t.price() > 0 && p.hasPermission("alloy.tag.bundle");
    }

    private final AlloyCore plugin;
    private final NamespacedKey itemKey;
    private final File file;
    private final Map<UUID, Set<String>> owned = new HashMap<>();
    private final Map<UUID, String> equipped = new HashMap<>();
    private Economy eco;
    private Ranks ranks;

    void setRanks(Ranks r) { this.ranks = r; }

    NameTags(AlloyCore plugin) {
        this.plugin = plugin;
        this.itemKey = new NamespacedKey(plugin, "name_tag");
        this.file = new File(plugin.getDataFolder(), "nametags.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        var os = y.getConfigurationSection("owned");
        if (os != null) for (String u : os.getKeys(false)) owned.put(UUID.fromString(u), new HashSet<>(os.getStringList(u)));
        var es = y.getConfigurationSection("equipped");
        if (es != null) for (String u : es.getKeys(false)) equipped.put(UUID.fromString(u), es.getString(u));
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::syncTeams, 40L, 40L);
    }

    void setEconomy(Economy eco) { this.eco = eco; }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        owned.forEach((u, s) -> y.set("owned." + u, new ArrayList<>(s)));
        equipped.forEach((u, t) -> y.set("equipped." + u, t));
        try { y.save(file); } catch (Exception e) { plugin.getLogger().warning("nametags save failed: " + e.getMessage()); }
    }

    public static Tag tag(String id) { return TAGS.stream().filter(t -> t.id().equalsIgnoreCase(id)).findFirst().orElse(null); }

    // ------------------------------------------------------------------ items

    public ItemStack item(Tag t) {
        ItemStack it = new ItemStack(Material.NAME_TAG);
        it.editMeta(m -> {
            m.displayName(Ui.mm(t.mm() + "<bold>" + t.name() + " Name Tag"));
            m.lore(List.of(Ui.mm("<gray>Right-click to colour your name"), Ui.mm("<gray>like this: " + t.mm() + "YourName"), Ui.mm("<dark_gray>Switch any time with /tags")));
            m.setEnchantmentGlintOverride(true);
            m.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, t.id());
        });
        return it;
    }

    public boolean give(Player p, String id) {
        Tag t = tag(id);
        if (t == null) return false;
        p.getInventory().addItem(item(t)).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        p.sendMessage(Ui.mm("<gold>✦ <gray>You received a " + t.mm() + t.name() + " Name Tag<gray>! Right-click it to use it."));
        return true;
    }

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
        ItemStack it = e.getItem();
        if (it == null || it.getType() != Material.NAME_TAG || !it.hasItemMeta()) return;
        String id = it.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
        if (id == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        Tag t = tag(id);
        if (t == null) return;
        if (!owned.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>()).add(id)) {
            p.sendMessage(Ui.mm("<yellow>You already own " + t.mm() + t.name() + "<yellow> - equipping it."));
        } else {
            it.setAmount(it.getAmount() - 1);
            p.sendMessage(Ui.mm("<green>✔ Unlocked the " + t.mm() + t.name() + "<green> name colour!"));
        }
        equip(p, id);
    }

    // ------------------------------------------------------------------ applying

    void equip(Player p, String id) {
        if (id == null) equipped.remove(p.getUniqueId()); else equipped.put(p.getUniqueId(), id);
        save();
        apply(p);
        syncTeams();
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
    }

    public void apply(Player p) {
        Tag t = tag(equipped.getOrDefault(p.getUniqueId(), ""));
        Ranks.Rank r = ranks == null ? null : ranks.of(p);
        if (t != null && !has(p, t)) t = null;   // e.g. a supporter colour after a refund
        Component name = Ui.mm(net.alloymc.core.Supporters.badge(p) + (r == null ? "" : r.prefix()) + (t == null ? "<white>" : t.mm()) + p.getName());
        p.displayName(name);
        p.playerListName(name);
    }

    /** Above-head nametags come from scoreboard teams, and every player has their own scoreboard (sidebar). */
    private void syncTeams() {
        for (Player p : Bukkit.getOnlinePlayers()) apply(p);   // picks up rank changes too
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            Scoreboard sb = viewer.getScoreboard();
            for (Player p : Bukkit.getOnlinePlayers()) {
                Tag t = tag(equipped.getOrDefault(p.getUniqueId(), ""));
                if (t != null && !has(p, t)) t = null;
                Ranks.Rank r = ranks == null ? null : ranks.of(p);
                if (t == null && r == null) {
                    Team cur = sb.getEntryTeam(p.getName());
                    if (cur != null && cur.getName().startsWith("nc_")) cur.removeEntry(p.getName());
                    continue;
                }
                // one team per rank+colour combination: the prefix shows above heads, the colour tints the name
                String name = "nc_" + (r == null ? "none" : r.id()) + "_" + (t == null ? "white" : t.id());
                Team want = sb.getTeam(name);
                if (want == null) {
                    want = sb.registerNewTeam(name);
                    want.color(t == null ? NamedTextColor.WHITE : t.team());
                    if (r != null) want.prefix(Ui.mm(r.prefix()));
                }
                Team cur = sb.getEntryTeam(p.getName());
                if (cur == want) continue;
                if (cur != null && cur.getName().startsWith("nc_")) cur.removeEntry(p.getName());
                want.addEntry(p.getName());
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) { Bukkit.getScheduler().runTaskLater(plugin, () -> apply(e.getPlayer()), 10L); }

    // ------------------------------------------------------------------ /tags menu

    private static final class Menu implements InventoryHolder {
        Inventory inv;
        public Inventory getInventory() { return inv; }
    }

    public void open(Player p) {
        Menu m = new Menu();
        m.inv = Bukkit.createInventory(m, 27, Ui.mm("<dark_gray>Name Colours"));
        String eq = equipped.get(p.getUniqueId());
        for (int i = 0; i < TAGS.size(); i++) {
            Tag t = TAGS.get(i);
            boolean has = has(p, t);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Preview: " + t.mm() + p.getName());
            lore.add("");
            if (t.id().equals(eq)) lore.add("<green>✔ Equipped <gray>(click to remove)");
            else if (has) lore.add("<yellow>» Click to equip");
            else if (t.price() > 0) lore.add("<gray>Price: <gold>" + AuctionHouse.money(t.price()) + " <yellow>» Click to buy");
            else if (t.price() == -2) lore.add("<gray>Unlocked by the " + t.mm() + t.name() + "<gray> - /ranks");
            else if (t.price() < 0) lore.add("<#ffb347>" + t.name() + " supporter colour <gray>- /store");
            else lore.add("<red>Crate / event exclusive");
            ItemStack icon = Ui.item(has ? Material.NAME_TAG : Material.PAPER, t.mm() + "<bold>" + t.name(), lore.toArray(String[]::new));
            if (t.id().equals(eq)) icon.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
            m.inv.setItem(i, icon);
        }
        m.inv.setItem(26, Ui.item(Material.BARRIER, "<red>Remove name colour"));
        p.openInventory(m.inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        int slot = e.getRawSlot();
        if (slot == 26) { equip(p, null); p.closeInventory(); p.sendMessage(Ui.mm("<gray>Name colour removed.")); return; }
        if (slot >= TAGS.size()) return;
        Tag t = TAGS.get(slot);
        Set<String> mine = owned.computeIfAbsent(p.getUniqueId(), k -> new HashSet<>());
        if (t.id().equals(equipped.get(p.getUniqueId()))) { equip(p, null); open(p); return; }
        if (!has(p, t)) {
            if (t.price() == -2) { p.sendMessage(Ui.mm("<red>The " + t.mm() + t.name() + " <red>tag comes with that rank. <gray>See <white>/ranks")); return; }
            if (t.price() < 0) { p.sendMessage(Ui.mm("<red>" + t.name() + " is a supporter colour. <gray>See <white>/store")); return; }
            if (t.price() == 0) { p.sendMessage(Ui.mm("<red>" + t.name() + " can only be won from crates and events.")); return; }
            if (eco == null || !eco.has(p, t.price())) { p.sendMessage(Ui.mm("<red>You need " + AuctionHouse.money(t.price()) + " for that colour.")); return; }
            eco.withdrawPlayer(p, t.price());
            mine.add(t.id());
            p.sendMessage(Ui.mm("<green>Bought the " + t.mm() + t.name() + "<green> name colour for <gold>" + AuctionHouse.money(t.price()) + "<green>!"));
        }
        equip(p, t.id());
        open(p);
    }
}
