package net.blackforge.core;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Survival 2: a staff-only copy of Survival where new ideas are tried before they go live.
 *
 * A server becomes Survival 2 with {@code lab.enabled: true} in its config (it still runs {@code mode: survival}).
 * There it keeps its own wallet and its own copy of the network's progress files (see {@link BlackForgeCore}), only
 * staff (or anyone with {@code blackforge.lab}) may stay, and every experiment is a switch in the {@code /lab} menu.
 *
 * Experiments call {@link #register} once and then check {@link #enabled} before doing anything. Off Survival 2 an
 * experiment is off, unless the server lists it under {@code lab.graduated}: that is how an idea moves to live
 * Survival without being rebuilt.
 */
public final class Lab implements Listener {
    public static final String TAG = "<gradient:#ffd27a:#8a94a6><bold>SURVIVAL 2</bold></gradient>";
    public static final String PERMISSION = "blackforge.lab";

    /** One idea under test. {@code about} lines are MiniMessage. */
    public record Experiment(String id, String name, Material icon, boolean defaultOn, List<String> about) {}

    private static final Map<String, Experiment> EXPERIMENTS = new LinkedHashMap<>();
    private static BlackForgeCore plugin;
    private static boolean on;
    private static String id = "survival2";
    private static File stateFile;
    private static YamlConfiguration state = new YamlConfiguration();
    private static final BossBar BAR = BossBar.bossBar(Ui.mm(TAG + " <gray>· test server · <white>/lab <gray>· progress here can be wiped"),
            1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);

    private Lab() {}

    /** Called once in onEnable, before the economy and the game modes. */
    static void init(BlackForgeCore p) {
        plugin = p;
        on = p.getConfig().getBoolean("lab.enabled", false);
        id = p.getConfig().getString("lab.id", "survival2");
        if (!on) return;
        stateFile = new File(p.getDataFolder(), "lab.yml");
        state = YamlConfiguration.loadConfiguration(stateFile);
        Bukkit.getPluginManager().registerEvents(new Lab(), p);
        Bukkit.getCommandMap().register("blackforge", new Command("lab", "Survival 2 experiments", "/lab", List.of("survival2")) {
            @Override public boolean execute(CommandSender s, String label, String[] a) { command(s, a); return true; }
            @Override public List<String> tabComplete(CommandSender s, String alias, String[] a) {
                if (a.length == 1) return LabRules.startingWith(List.of("on", "off", "good", "bad", "notes", "money"), a[0]);
                if (a.length == 2 && !a[0].equalsIgnoreCase("money")) return LabRules.startingWith(new ArrayList<>(EXPERIMENTS.keySet()), a[1]);
                return List.of();
            }
        });
        p.getLogger().info("Survival 2 (lab) is on: id=" + id + ", wallet and progress files are separate from live servers");
    }

    /** True on the Survival 2 server. */
    public static boolean on() { return on; }

    /** This lab's name: its wallet and its folder under shared/lab. */
    public static String id() { return id; }

    /** Experiments call this once when they start. Safe to call on any server. */
    public static void register(String id, String name, Material icon, boolean defaultOn, String... about) {
        EXPERIMENTS.put(id, new Experiment(id, name, icon, defaultOn, List.of(about)));
    }

    /** Is this experiment running here? On Survival 2: its switch. Elsewhere: only if listed under lab.graduated. */
    public static boolean enabled(String experiment) {
        if (plugin == null) return false;
        if (!on) return plugin.getConfig().getStringList("lab.graduated").contains(experiment);
        Experiment e = EXPERIMENTS.get(experiment);
        return state.getBoolean("on." + experiment, e == null || e.defaultOn());
    }

    public static boolean allowed(Player p) {
        return p.isOp() || p.hasPermission(PERMISSION) || p.hasPermission("blackforge.staff") || p.hasPermission("blackforge.admin");
    }

    // ------------------------------------------------------------------ joining

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!allowed(p)) {
            e.joinMessage(null);
            p.sendMessage(Ui.mm(TAG + " <gray>is a staff test server. Sending you back to the hub..."));
            Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) plugin.net().send(p, "hub"); }, 10L);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline()) p.kick(Ui.mm(TAG + "\n<gray>Survival 2 is for staff only."));
            }, 60L);
            return;
        }
        p.showBossBar(BAR);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            p.showTitle(Title.title(Ui.mm(TAG), Ui.mm("<gray>Experiments are live. <white>/lab <gray>to see them."),
                    Title.Times.times(java.time.Duration.ofMillis(400), java.time.Duration.ofSeconds(3), java.time.Duration.ofMillis(800))));
            long running = EXPERIMENTS.keySet().stream().filter(Lab::enabled).count();
            p.sendMessage(Ui.mm(TAG + " <gray>" + running + " of " + EXPERIMENTS.size() + " experiments on. "
                    + "<u><click:run_command:'/lab'><hover:show_text:'<gray>Open the experiment menu'><#ffd27a>[Open /lab]</hover></click></u>"));
        }, 40L);
    }

    // ------------------------------------------------------------------ /lab

    private static void command(CommandSender s, String[] a) {
        if (s instanceof Player p && !allowed(p)) { s.sendMessage(Ui.mm("<red>Survival 2 is for staff only.")); return; }
        if (a.length == 0) {
            if (s instanceof Player p) open(p); else list(s);
            return;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "on", "off" -> {
                Experiment x = a.length > 1 ? EXPERIMENTS.get(a[1].toLowerCase(Locale.ROOT)) : null;
                if (x == null) { s.sendMessage(Ui.mm("<red>Usage: /lab " + sub + " <experiment>")); list(s); return; }
                set(x, sub.equals("on"), s.getName());
            }
            case "good", "bad" -> {
                Experiment x = a.length > 1 ? EXPERIMENTS.get(a[1].toLowerCase(Locale.ROOT)) : null;
                if (x == null || !(s instanceof Player p)) { s.sendMessage(Ui.mm("<red>Usage: /lab " + sub + " <experiment> [note]")); return; }
                String note = a.length > 2 ? String.join(" ", Arrays.copyOfRange(a, 2, a.length)) : "";
                vote(p, x, sub.equals("good") ? 1 : -1, note);
            }
            case "notes" -> notes(s, a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : null);
            case "money" -> {
                if (!(s instanceof Player p)) { s.sendMessage("Players only."); return; }
                double amount = a.length > 1 ? NetEconomy.parseAmount(a[1]) : 100_000;
                if (!(amount > 0) || amount > 10_000_000) { s.sendMessage(Ui.mm("<red>Pick an amount between 1 and 10m.")); return; }
                plugin.economy().ensureAccount(p);
                plugin.economy().deposit(p, amount);
                p.sendMessage(Ui.mm(TAG + " <gray>Added <white>" + NetEconomy.money(amount) + "<gray> of test money (Survival 2 wallet only)."));
            }
            default -> s.sendMessage(Ui.mm("<gray>/lab <dark_gray>· <gray>/lab on|off <id> <dark_gray>· <gray>/lab good|bad <id> [note] <dark_gray>· <gray>/lab notes [id] <dark_gray>· <gray>/lab money [amount]"));
        }
    }

    private static void list(CommandSender s) {
        if (EXPERIMENTS.isEmpty()) { s.sendMessage(Ui.mm(TAG + " <gray>No experiments yet.")); return; }
        for (Experiment x : EXPERIMENTS.values())
            s.sendMessage(Ui.mm((enabled(x.id()) ? "<#7bd88f>● " : "<#e5484d>○ ") + "<white>" + x.id() + " <dark_gray>- " + x.name()));
    }

    private static void set(Experiment x, boolean value, String who) {
        state.set("on." + x.id(), value);
        log(x.id(), who + " switched it " + (value ? "on" : "off"));
        save();
        for (Player p : Bukkit.getOnlinePlayers())
            p.sendMessage(Ui.mm(TAG + " <white>" + who + " <gray>switched " + x.name() + " " + (value ? "<#7bd88f>on" : "<#e5484d>off") + "<gray>."));
    }

    private static void vote(Player p, Experiment x, int v, String note) {
        state.set("votes." + x.id() + "." + p.getUniqueId(), v);
        log(x.id(), p.getName() + " " + (v > 0 ? "+1" : "-1") + (note.isBlank() ? "" : ": " + note));
        save();
        p.sendMessage(Ui.mm(TAG + " <gray>Noted: " + (v > 0 ? "<#7bd88f>good" : "<#e5484d>bad") + "<gray> for " + x.name()
                + (note.isBlank() ? "<gray>. Add a note with <white>/lab " + (v > 0 ? "good " : "bad ") + x.id() + " <note>" : "<gray>.")));
        p.playSound(p.getLocation(), v > 0 ? Sound.BLOCK_NOTE_BLOCK_CHIME : Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 1.2f);
    }

    private static void notes(CommandSender s, String only) {
        List<String> out = new ArrayList<>();
        for (Experiment x : EXPERIMENTS.values()) {
            if (only != null && !x.id().equals(only)) continue;
            List<String> log = state.getStringList("log." + x.id());
            int[] t = tally(x.id());
            out.add("<white>" + x.name() + " <#7bd88f>+" + t[0] + " <#e5484d>-" + t[1]);
            for (String l : log.subList(Math.max(0, log.size() - (only == null ? 3 : 15)), log.size())) out.add("  <gray>" + l);
        }
        if (out.isEmpty()) out.add("<gray>No notes yet.");
        s.sendMessage(Ui.mm(TAG + " <gray>notes"));
        for (String l : out) s.sendMessage(Ui.mm(l));
    }

    private static int[] tally(String experiment) {
        var sec = state.getConfigurationSection("votes." + experiment);
        List<Integer> votes = new ArrayList<>();
        if (sec != null) for (String k : sec.getKeys(false)) votes.add(sec.getInt(k));
        return LabRules.tally(votes);
    }

    private static void log(String experiment, String line) {
        List<String> l = new ArrayList<>(state.getStringList("log." + experiment));
        l.add(LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm")) + " " + line);
        state.set("log." + experiment, LabRules.keepLast(l, 200));
    }

    private static void save() {
        try { state.save(stateFile); } catch (Exception e) { plugin.getLogger().warning("lab.yml save failed: " + e.getMessage()); }
    }

    // ------------------------------------------------------------------ menu

    private static final class Menu implements InventoryHolder {
        final List<String> slots = new ArrayList<>();
        Inventory inv;
        public Inventory getInventory() { return inv; }
    }

    private static void open(Player p) {
        Menu m = new Menu();
        int rows = Math.max(1, Math.min(6, (EXPERIMENTS.size() + 8) / 9));
        m.inv = Bukkit.createInventory(m, rows * 9, Ui.mm(TAG + " <dark_gray>experiments"));
        int slot = 0;
        for (Experiment x : EXPERIMENTS.values()) {
            if (slot >= rows * 9) break;
            boolean e = enabled(x.id());
            int[] t = tally(x.id());
            List<String> lore = new ArrayList<>(x.about());
            lore.add("");
            lore.add(e ? "<#7bd88f>● On" : "<#e5484d>○ Off");
            lore.add("<#7bd88f>+" + t[0] + " <#e5484d>-" + t[1] + " <dark_gray>(staff votes)");
            lore.add("");
            lore.add("<gray>Left-click: <white>switch " + (e ? "off" : "on"));
            lore.add("<gray>Right-click: <#7bd88f>good <dark_gray>· <gray>Shift-right: <#e5484d>bad");
            lore.add("<dark_gray>/lab notes " + x.id());
            var it = Ui.item(x.icon(), x.name(), lore.toArray(String[]::new));
            if (e) it.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
            m.inv.setItem(slot, it);
            m.slots.add(x.id());
            slot++;
        }
        if (EXPERIMENTS.isEmpty()) m.inv.setItem(4, Ui.item(Material.BARRIER, "<gray>No experiments yet"));
        p.openInventory(m.inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= m.slots.size()) return;
        Experiment x = EXPERIMENTS.get(m.slots.get(slot));
        if (x == null) return;
        ClickType c = e.getClick();
        if (c == ClickType.LEFT || c == ClickType.SHIFT_LEFT) set(x, !enabled(x.id()), p.getName());
        else if (c == ClickType.RIGHT) vote(p, x, 1, "");
        else if (c == ClickType.SHIFT_RIGHT) vote(p, x, -1, "");
        else return;
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) open(p); });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }
}
