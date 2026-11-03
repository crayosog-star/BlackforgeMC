package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.*;
import java.util.function.Function;

/**
 * Player vaults: /pv opens a menu of the vaults you own, /pv 2 opens vault 2 (54 slots, kept between sessions).
 * Vaults are won from crates or bought in the store: {@code /alloy vault give <player> [amount]} adds vaults
 * (up to {@link #MAX}); the permission {@code alloycore.pv.<n>} also grants n vaults (ranks, perks).
 * How many you own is shared (vaults/owned); what's inside is saved per game group in vaults/&lt;group&gt;/&lt;uuid&gt;.yml, so Skyblock and OneBlock items stay separate.
 */
public final class PlayerVaults implements Listener {
    static final int MAX = 9;

    private final AlloyCore plugin;
    private final Function<Player, String> groupOf;

    private static final class Holder implements InventoryHolder {
        final UUID owner; final String group; final int n; Inventory inv;
        Holder(UUID owner, String group, int n) { this.owner = owner; this.group = group; this.n = n; }
        public Inventory getInventory() { return inv; }
    }

    private static final class MenuHolder implements InventoryHolder {
        Inventory inv;
        public Inventory getInventory() { return inv; }
    }

    /** groupOf: which vault set a player is in right now (null = none, e.g. the hub lobby). */
    PlayerVaults(AlloyCore plugin, Function<Player, String> groupOf) {
        this.plugin = plugin;
        this.groupOf = groupOf;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getCommandMap().register("alloy", new Command("pv", "Your player vaults", "/pv [number]", List.of("playervault", "vault", "vaults")) {
            @Override public boolean execute(CommandSender s, String label, String[] args) {
                if (s instanceof Player p) command(p, args); else s.sendMessage("Players only.");
                return true;
            }
        });
    }

    private File file(String group, UUID id) {
        File dir = new File(plugin.getDataFolder(), "vaults/" + group);
        dir.mkdirs();
        return new File(dir, id + ".yml");
    }

    private int owned(Player p, String group) {
        int n = YamlConfiguration.loadConfiguration(file("owned", p.getUniqueId())).getInt("owned", 0);
        for (int i = MAX; i > n; i--) if (p.hasPermission("alloycore.pv." + i)) { n = i; break; }
        return Math.min(MAX, n);
    }

    /** Crates, the store and staff: /alloy vault give <player> [amount]. */
    public boolean admin(CommandSender s, String[] a) {
        if (a.length < 3 || !a[0].equalsIgnoreCase("vault") || !a[1].equalsIgnoreCase("give")) return false;
        OfflinePlayer t = Bukkit.getPlayerExact(a[2]);
        if (t == null) t = Bukkit.getOfflinePlayerIfCached(a[2]);
        if (t == null) { s.sendMessage("Unknown player"); return true; }
        int amount = a.length > 3 ? Integer.parseInt(a[3]) : 1;
        File f = file("owned", t.getUniqueId());
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        int now = Math.min(MAX, y.getInt("owned", 0) + amount);
        y.set("owned", now);
        try { y.save(f); } catch (Exception e) { plugin.getLogger().warning("vault save: " + e); }
        s.sendMessage("Gave " + amount + " vault(s) to " + a[2] + " (now " + now + ")");
        if (t instanceof Player online) {
            online.sendMessage(Ui.mm("<#ffb52e>✦ <white>You unlocked a <gold>Player Vault<white>! <gray>You now have <white>" + now + "<gray> - open them with <white>/pv"));
            online.playSound(online.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
        }
        return true;
    }

    private void command(Player p, String[] a) {
        String group = groupOf.apply(p);
        if (group == null) { p.sendMessage(Ui.mm("<gray>Pick a game first - vaults are kept per game.")); return; }
        int have = owned(p, group);
        if (have == 0) {
            p.sendMessage(Ui.mm("<#ffb52e>You don't have a Player Vault yet. <gray>Win one from the <white>crates <gray>(low chance) or buy one in the <white>/store<gray>."));
            return;
        }
        if (a.length > 0) {
            int n;
            try { n = Integer.parseInt(a[0]); } catch (NumberFormatException e) { n = 0; }
            if (n < 1 || n > have) { p.sendMessage(Ui.mm("<red>You have vaults <white>1-" + have + "<red>.")); return; }
            open(p, group, n);
            return;
        }
        if (have == 1) { open(p, group, 1); return; }
        MenuHolder h = new MenuHolder();
        Inventory inv = Bukkit.createInventory(h, 9, Ui.mm("<dark_gray>Your Player Vaults"));
        h.inv = inv;
        for (int i = 1; i <= have; i++)
            inv.setItem(i - 1, Ui.item(Material.ENDER_CHEST, "<#ffb52e><bold>Vault #" + i, "<gray>54 slots, kept safe", "<yellow>» Click to open"));
        p.openInventory(inv);
    }

    private void open(Player p, String group, int n) {
        Holder h = new Holder(p.getUniqueId(), group, n);
        Inventory inv = Bukkit.createInventory(h, 54, Ui.mm("<dark_gray>Vault #" + n));
        h.inv = inv;
        String raw = YamlConfiguration.loadConfiguration(file(group, p.getUniqueId())).getString("v" + n);
        if (raw != null) {
            try {
                ItemStack[] items = ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(raw));
                for (int i = 0; i < items.length && i < 54; i++) inv.setItem(i, items[i]);
            } catch (Exception e) { plugin.getLogger().warning("vault load " + p.getName() + " #" + n + ": " + e); }
        }
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_ENDER_CHEST_OPEN, 0.6f, 1f);
    }

    private void save(Holder h) {
        File f = file(h.group, h.owner);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        y.set("v" + h.n, Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(h.inv.getContents())));
        try { y.save(f); } catch (Exception e) { plugin.getLogger().warning("vault save: " + e); }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof Holder h) {
            save(h);
            if (e.getPlayer() instanceof Player p) p.playSound(p.getLocation(), Sound.BLOCK_ENDER_CHEST_CLOSE, 0.6f, 1f);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof MenuHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getRawSlot() < 0 || e.getRawSlot() >= 9) return;
        ItemStack it = e.getCurrentItem();
        if (it == null || it.getType() != Material.ENDER_CHEST) return;
        String group = groupOf.apply(p);
        if (group == null) return;
        int n = e.getRawSlot() + 1;
        p.closeInventory();
        if (n <= owned(p, group)) open(p, group, n);
    }

    /** Vault holders are saved on close; this covers a quit with one open. */
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (e.getPlayer().getOpenInventory().getTopInventory().getHolder() instanceof Holder h) save(h);
    }
}
