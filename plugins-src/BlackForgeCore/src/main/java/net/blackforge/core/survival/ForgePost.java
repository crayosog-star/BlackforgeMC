package net.blackforge.core.survival;

import net.blackforge.core.Lab;
import net.blackforge.core.NetEconomy;
import net.blackforge.core.Ui;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.*;

/**
 * Forge Post (Survival 2 experiment "forge-post"): send items and money to anyone, online or not.
 * /post send <player> [money] opens a parcel: what you put in goes to their mailbox when you close it (a small
 * postage fee is charged). Money arrives at once. /post opens your own mailbox; you're told on join when mail waits.
 */
final class ForgePost implements Listener {
    static final String NAME = "<gradient:#c9a86a:#8b5a2b><bold>Forge Post</bold></gradient>";
    private static final String ID = "forge-post";
    private static final int BOX = 54;

    private final LabExperiments.Ctx c;
    private final NetEconomy eco;
    private final File file;
    private final YamlConfiguration data;
    private final double postage;

    private static final class Parcel implements InventoryHolder {
        UUID to; String toName; Inventory inv;
        public Inventory getInventory() { return inv; }
    }

    private static final class Mailbox implements InventoryHolder {
        Inventory inv;
        public Inventory getInventory() { return inv; }
    }

    ForgePost(LabExperiments.Ctx c) {
        this.c = c;
        this.eco = c.plugin().economy();
        this.file = new File(c.plugin().getDataFolder(), "lab/post.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        this.postage = c.plugin().getConfig().getDouble("lab-experiments.forge-post.postage", 10);
        Bukkit.getPluginManager().registerEvents(this, c.plugin());
        LabExperiments.command(c.plugin(), ID, "post", "Forge Post: send and collect mail", List.of("mail", "mailbox"), this::command);
    }

    private void command(CommandSender s, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Players only."); return; }
        if (a.length == 0) { openBox(p); return; }
        if (!a[0].equalsIgnoreCase("send") || a.length < 2) {
            p.sendMessage(Ui.mm(NAME + " <gray>/post <dark_gray>- <white>your mailbox  <gray>/post send <player> [money] <dark_gray>- <white>send a parcel"));
            return;
        }
        OfflinePlayer to = Bukkit.getOfflinePlayerIfCached(a[1]);
        if (to == null || (!to.hasPlayedBefore() && !to.isOnline())) { p.sendMessage(Ui.mm("<red>Nobody called " + a[1] + " has played here.")); return; }
        if (to.getUniqueId().equals(p.getUniqueId())) { p.sendMessage(Ui.mm("<red>You can't post to yourself.")); return; }
        String toName = to.getName() == null ? a[1] : to.getName();
        if (a.length > 2) {
            double money = NetEconomy.parseAmount(a[2]);
            if (!NetEconomy.validPrice(money)) { p.sendMessage(Ui.mm("<red>That amount doesn't work.")); return; }
            if (!eco.withdraw(p, money)) { p.sendMessage(Ui.mm("<red>You don't have " + NetEconomy.money(money) + ".")); return; }
            if (!eco.deposit(to, money)) { eco.deposit(p, money); p.sendMessage(Ui.mm("<red>The money couldn't be sent.")); return; }
            note(to.getUniqueId(), p.getName() + " sent you " + NetEconomy.money(money));
            p.sendMessage(Ui.mm(NAME + " <gray>Sent <green>" + NetEconomy.money(money) + "<gray> to " + toName + "."));
            ping(to, p.getName());
        }
        Parcel parcel = new Parcel();
        parcel.to = to.getUniqueId(); parcel.toName = toName;
        parcel.inv = Bukkit.createInventory(parcel, 27, Ui.mm(NAME + " <dark_gray>to " + toName));
        p.openInventory(parcel.inv);
        LabExperiments.bar(p, "<gray>Put items in, then close to send. Postage <white>" + NetEconomy.money(postage));
    }

    // ------------------------------------------------------------------ sending

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Parcel parcel) || !(e.getPlayer() instanceof Player p)) return;
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack s : parcel.inv.getContents()) if (s != null && !s.getType().isAir()) items.add(s);
        parcel.inv.clear();
        if (items.isEmpty()) return;
        if (!Lab.enabled(ID) || !eco.withdraw(p, postage)) {
            giveBack(p, items);
            p.sendMessage(Ui.mm("<red>" + (Lab.enabled(ID) ? "Postage is " + NetEconomy.money(postage) + "." : "The post is closed.") + " Your items were returned."));
            return;
        }
        List<ItemStack> box = box(parcel.to);
        List<ItemStack> back = new ArrayList<>();
        for (ItemStack s : items) { if (box.size() < BOX) box.add(s); else back.add(s); }
        data.set("mail." + parcel.to + ".items", box);
        note(parcel.to, p.getName() + " sent you a parcel");
        save();
        giveBack(p, back);
        p.sendMessage(Ui.mm(NAME + " <gray>Parcel sent to <white>" + parcel.toName + "<gray>." + (back.isEmpty() ? "" : " <yellow>Their mailbox was full: " + back.size() + " stacks came back.")));
        p.playSound(p.getLocation(), Sound.BLOCK_BARREL_CLOSE, 1f, 1.1f);
        ping(Bukkit.getOfflinePlayer(parcel.to), p.getName());
    }

    private void ping(OfflinePlayer to, String from) {
        Player t = to.getPlayer();
        if (t != null) t.sendMessage(Ui.mm(NAME + " <gray>Mail from <white>" + from + "<gray>. <u><click:run_command:'/post'><#c9a86a>[Open mailbox]</click></u>"));
    }

    // ------------------------------------------------------------------ the mailbox

    private void openBox(Player p) {
        Mailbox m = new Mailbox();
        m.inv = Bukkit.createInventory(m, BOX, Ui.mm(NAME + " <dark_gray>mailbox"));
        List<ItemStack> box = box(p.getUniqueId());
        for (int i = 0; i < box.size() && i < BOX; i++) m.inv.setItem(i, box.get(i));
        List<String> notes = data.getStringList("mail." + p.getUniqueId() + ".notes");
        for (String n : notes) p.sendMessage(Ui.mm("<dark_gray>• <gray>" + n));
        data.set("mail." + p.getUniqueId() + ".notes", null);
        save();
        if (box.isEmpty() && notes.isEmpty()) p.sendMessage(Ui.mm(NAME + " <gray>Your mailbox is empty."));
        p.openInventory(m.inv);
    }

    /** Click a stack to take it. Nothing can be put into a mailbox. */
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Mailbox m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != m.inv) return;
        int slot = e.getRawSlot();
        List<ItemStack> box = box(p.getUniqueId());
        if (slot < 0 || slot >= box.size()) return;
        ItemStack s = box.get(slot);
        Map<Integer, ItemStack> left = p.getInventory().addItem(s.clone());
        if (left.isEmpty()) box.remove(slot); else box.set(slot, left.values().iterator().next());
        data.set("mail." + p.getUniqueId() + ".items", box);
        save();
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.6f, 1.2f);
        Bukkit.getScheduler().runTask(c.plugin(), () -> { if (p.isOnline()) openBox(p); });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof Mailbox) e.setCancelled(true); }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!Lab.enabled(ID)) return;
        int n = box(p.getUniqueId()).size(), notes = data.getStringList("mail." + p.getUniqueId() + ".notes").size();
        if (n + notes > 0) Bukkit.getScheduler().runTaskLater(c.plugin(), () -> {
            if (p.isOnline()) p.sendMessage(Ui.mm(NAME + " <gray>You have mail" + (n > 0 ? " (<white>" + n + "<gray> stacks)" : "")
                    + ". <u><click:run_command:'/post'><#c9a86a>[Open mailbox]</click></u>"));
        }, 60L);
    }

    // ------------------------------------------------------------------ storage

    private List<ItemStack> box(UUID id) {
        List<ItemStack> out = new ArrayList<>();
        for (Object o : data.getList("mail." + id + ".items", List.of())) if (o instanceof ItemStack s) out.add(s);
        return out;
    }

    private void note(UUID to, String line) {
        List<String> notes = new ArrayList<>(data.getStringList("mail." + to + ".notes"));
        notes.add(line);
        data.set("mail." + to + ".notes", new ArrayList<>(notes.subList(Math.max(0, notes.size() - 20), notes.size())));
        save();
    }

    private static void giveBack(Player p, List<ItemStack> items) {
        for (ItemStack s : items) p.getInventory().addItem(s).values().forEach(r -> p.getWorld().dropItemNaturally(p.getLocation(), r));
    }

    private void save() {
        try { file.getParentFile().mkdirs(); data.save(file); }
        catch (Exception ex) { c.plugin().getLogger().warning("post.yml save failed: " + ex.getMessage()); }
    }
}
