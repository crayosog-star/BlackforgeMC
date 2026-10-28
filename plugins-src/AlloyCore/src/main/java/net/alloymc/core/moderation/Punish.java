package net.alloymc.core.moderation;

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
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.meta.SkullMeta;

import java.io.File;
import java.util.*;

/**
 * Staff punishment menu and network-wide bans.
 * <ul>
 *   <li>/punish &lt;player&gt; (alloy.staff): one click to warn, mute (10m / 1h / 1d), kick, ban (1d / 7d) or ban for good.
 *       Reports from /report have a [Punish] button that opens it.</li>
 *   <li>Bans live in shared/bans.yml, so a ban on any server keeps the player out of every server.</li>
 *   <li>/unban &lt;name&gt; and /baninfo &lt;name&gt;.</li>
 * </ul>
 * Everything is written to the shared moderation log.
 */
public final class Punish implements Listener {
    private record Action(int slot, Material icon, String label, String kind, long millis) {}

    private static final List<Action> ACTIONS = List.of(
            new Action(10, Material.PAPER, "<yellow>Warn", "warn", 0),
            new Action(11, Material.BOOK, "<gold>Mute 10 minutes", "mute", 10 * 60_000L),
            new Action(12, Material.WRITABLE_BOOK, "<gold>Mute 1 hour", "mute", 60 * 60_000L),
            new Action(13, Material.WRITTEN_BOOK, "<gold>Mute 1 day", "mute", 24 * 60 * 60_000L),
            new Action(14, Material.LEATHER_BOOTS, "<red>Kick", "kick", 0),
            new Action(15, Material.IRON_BARS, "<red>Ban 1 day", "ban", 24 * 60 * 60_000L),
            new Action(16, Material.IRON_DOOR, "<red>Ban 7 days", "ban", 7 * 24 * 60 * 60_000L),
            new Action(22, Material.BARRIER, "<dark_red><bold>Ban permanently", "ban", -1));

    private final AlloyCore plugin;
    private final ModStore store;
    private final File bansFile;

    public Punish(AlloyCore plugin, ModStore store, File shared) {
        this.plugin = plugin;
        this.store = store;
        this.bansFile = new File(shared, "bans.yml");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        reg("punish", List.of("pun"), (s, a) -> {
            if (!(s instanceof Player p) || !p.hasPermission("alloy.staff")) { s.sendMessage(Ui.mm("<red>Staff only.")); return; }
            if (a.length < 1) { p.sendMessage(Ui.mm("<red>Usage: /punish <player> [reason]")); return; }
            String reason = a.length > 1 ? String.join(" ", Arrays.copyOfRange(a, 1, a.length)) : "Breaking the rules";
            open(p, a[0].replaceAll("[^A-Za-z0-9_]", ""), net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(reason));
        });
        reg("unban", List.of("pardon"), (s, a) -> {
            if (!s.hasPermission("alloy.staff")) { s.sendMessage(Ui.mm("<red>Staff only.")); return; }
            if (a.length < 1) { s.sendMessage(Ui.mm("<red>Usage: /unban <player>")); return; }
            s.sendMessage(Ui.mm(unban(a[0], s.getName()) ? "<green>Unbanned " + a[0] + " on every server." : "<red>" + a[0] + " isn't banned."));
        });
        reg("baninfo", List.of(), (s, a) -> {
            if (!s.hasPermission("alloy.staff") || a.length < 1) return;
            String b = banReason(Bukkit.getOfflinePlayer(a[0]).getUniqueId());
            s.sendMessage(Ui.mm(b == null ? "<gray>" + a[0] + " isn't banned." : "<red>" + a[0] + ": <gray>" + b));
        });
    }

    // ------------------------------------------------------------------ bans

    private synchronized YamlConfiguration bans() { return YamlConfiguration.loadConfiguration(bansFile); }

    /** Why this player is banned right now (with when it ends), or null. Expired bans are ignored. */
    public String banReason(UUID id) {
        YamlConfiguration y = bans();
        if (!y.contains(id.toString())) return null;
        long until = y.getLong(id + ".until");
        if (until > 0 && until < System.currentTimeMillis()) return null;
        return y.getString(id + ".reason", "Banned") + (until > 0 ? " (ends in " + ModStore.fmt(until - System.currentTimeMillis()) + ")" : " (permanent)");
    }

    private synchronized void ban(UUID id, String name, long millis, String reason, String by) {
        YamlConfiguration y = bans();
        y.set(id + ".name", name);
        y.set(id + ".until", millis < 0 ? 0 : System.currentTimeMillis() + millis);
        y.set(id + ".reason", reason);
        y.set(id + ".by", by);
        try { y.save(bansFile); } catch (Exception ignored) { }
        store.log("BAN " + name + " " + (millis < 0 ? "permanently" : "for " + ModStore.fmt(millis)) + " by " + by + ": " + reason);
    }

    private synchronized boolean unban(String name, String by) {
        YamlConfiguration y = bans();
        for (String k : y.getKeys(false))
            if (name.equalsIgnoreCase(y.getString(k + ".name"))) {
                y.set(k, null);
                try { y.save(bansFile); } catch (Exception ignored) { }
                store.log("UNBAN " + name + " by " + by);
                return true;
            }
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(AsyncPlayerPreLoginEvent e) {
        String why = banReason(e.getUniqueId());
        if (why != null) e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, Ui.mm("<red><bold>You are banned from BlackForgeMC</bold>\n\n<gray>" + why + "\n\n<gray>Appeal on our Discord: <white>/discord <gray>in #appeals"));
    }

    // ------------------------------------------------------------------ menu

    private static final class Menu implements InventoryHolder {
        final String target, reason;
        Inventory inv;
        Menu(String target, String reason) { this.target = target; this.reason = reason; }
        @Override public Inventory getInventory() { return inv; }
    }

    private void open(Player staff, String target, String reason) {
        Menu m = new Menu(target, reason);
        m.inv = Bukkit.createInventory(m, 27, Ui.mm("<dark_red>Punish <white>" + target));
        var head = Ui.item(Material.PLAYER_HEAD, "<white>" + target, "<gray>Reason: <white>" + reason, "<gray>(add it after the name: /punish " + target + " <reason>)",
                banReason(Bukkit.getOfflinePlayer(target).getUniqueId()) != null ? "<red>Currently banned" : "<green>Not banned");
        head.editMeta(SkullMeta.class, sm -> sm.setOwningPlayer(Bukkit.getOfflinePlayer(target)));
        m.inv.setItem(4, head);
        for (Action a : ACTIONS) m.inv.setItem(a.slot, Ui.item(a.icon, a.label, "<gray>Reason: <white>" + reason));
        staff.openInventory(m.inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu m) || !(e.getWhoClicked() instanceof Player staff)) return;
        e.setCancelled(true);
        Action a = ACTIONS.stream().filter(x -> x.slot == e.getRawSlot()).findFirst().orElse(null);
        if (a == null || !staff.hasPermission("alloy.staff")) return;
        staff.closeInventory();
        OfflinePlayer off = Bukkit.getOfflinePlayer(m.target);
        Player online = off.getPlayer();
        String by = staff.getName();
        switch (a.kind) {
            case "warn" -> {
                if (online == null) { staff.sendMessage(Ui.mm("<red>" + m.target + " isn't on this server.")); return; }
                online.sendMessage(Ui.mm("<red><bold>⚠ Warning from staff:</bold> <white>" + m.reason));
                online.playSound(online.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f);
                store.log("WARN " + m.target + " by " + by + ": " + m.reason);
            }
            case "mute" -> {
                store.mute(off.getUniqueId(), m.target, a.millis, m.reason, by);
                if (online != null) online.sendMessage(Ui.mm("<red>You were muted for " + ModStore.fmt(a.millis) + ": <white>" + m.reason));
            }
            case "kick" -> {
                if (online == null) { staff.sendMessage(Ui.mm("<red>" + m.target + " isn't on this server.")); return; }
                online.kick(Ui.mm("<red>Kicked by staff\n\n<gray>" + m.reason));
                store.log("KICK " + m.target + " by " + by + ": " + m.reason);
            }
            case "ban" -> {
                ban(off.getUniqueId(), m.target, a.millis, m.reason, by);
                if (online != null) online.kick(Ui.mm("<red><bold>You are banned from BlackForgeMC</bold>\n\n<gray>" + banReason(off.getUniqueId())));
            }
            default -> {}
        }
        for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("alloy.staff"))
            p.sendMessage(Ui.mm("<dark_red>⚖ <white>" + by + " <gray>→ " + a.label + " <white>" + m.target + " <gray>(" + m.reason + ")"));
    }

    private interface Cmd { void run(CommandSender s, String[] args); }

    private void reg(String name, List<String> aliases, Cmd cmd) {
        Bukkit.getCommandMap().register("alloy", new Command(name, "BlackForgeMC moderation", "/" + name, aliases) {
            @Override public boolean execute(CommandSender s, String label, String[] args) { cmd.run(s, args); return true; }
        });
    }
}
