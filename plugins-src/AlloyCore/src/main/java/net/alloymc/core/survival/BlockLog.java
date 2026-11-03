package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Files;
import java.util.*;

/**
 * Anti-grief inspect: every block a player breaks or places is recorded (player, action, block, time) and kept for
 * two weeks. Staff run /inspect, then click a block to see its history (left-click: that block, right-click: the
 * block next to it), or /inspect <player> [hours] for what someone has been doing. Read-only: nothing is rolled back.
 */
final class BlockLog implements Listener {
    private record Entry(long time, String player, boolean placed, String material, String world, int x, int y, int z) {}

    private static final long KEEP_MS = 14L * 24 * 3600_000L;
    private static final int PER_BLOCK = 8, MAX_RECENT = 250_000;

    private final AlloyCore plugin;
    private final File file;
    private final Map<String, ArrayDeque<Entry>> byBlock = new HashMap<>();
    private final ArrayDeque<Entry> recent = new ArrayDeque<>();
    private final List<String> pending = new ArrayList<>();
    private final Set<UUID> inspecting = new HashSet<>();

    BlockLog(AlloyCore plugin) {
        this.plugin = plugin;
        File dir = new File(plugin.getDataFolder(), "blocklog");
        dir.mkdirs();
        this.file = new File(dir, "blocks.log");
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getCommandMap().register("alloy", new Command("inspect", "Look up who changed a block", "/inspect [player [hours]]", List.of("blocklog")) {
            @Override public boolean execute(CommandSender s, String label, String[] a) { command(s, a); return true; }
        });
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flush, 200L, 100L);
    }

    private static String key(String world, int x, int y, int z) { return world + "|" + x + "|" + y + "|" + z; }

    private void load() {
        if (!file.exists()) return;
        long cutoff = System.currentTimeMillis() - KEEP_MS;
        List<String> keep = new ArrayList<>();
        try {
            for (String l : Files.readAllLines(file.toPath())) {
                String[] p = l.split("\\|");
                if (p.length < 8) continue;
                long t = Long.parseLong(p[0]);
                if (t < cutoff) continue;
                keep.add(l);
                add(new Entry(t, p[1], p[2].equals("P"), p[3], p[4], Integer.parseInt(p[5]), Integer.parseInt(p[6]), Integer.parseInt(p[7])));
            }
            Files.write(file.toPath(), keep);   // drop anything older than two weeks
        } catch (Exception e) { plugin.getLogger().warning("blocklog load: " + e); }
        plugin.getLogger().info("Block log: " + recent.size() + " recent changes");
    }

    private void add(Entry e) {
        ArrayDeque<Entry> q = byBlock.computeIfAbsent(key(e.world(), e.x(), e.y(), e.z()), k -> new ArrayDeque<>());
        q.addFirst(e);
        while (q.size() > PER_BLOCK) q.removeLast();
        recent.addFirst(e);
        while (recent.size() > MAX_RECENT) recent.removeLast();
    }

    private void record(Player p, Block b, boolean placed) {
        Entry e = new Entry(System.currentTimeMillis(), p.getName(), placed, b.getType().name(), b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        add(e);
        synchronized (pending) { pending.add(e.time() + "|" + e.player() + "|" + (placed ? "P" : "B") + "|" + e.material() + "|" + e.world() + "|" + e.x() + "|" + e.y() + "|" + e.z()); }
    }

    private void flush() {
        List<String> out;
        synchronized (pending) { if (pending.isEmpty()) return; out = new ArrayList<>(pending); pending.clear(); }
        try (FileWriter w = new FileWriter(file, true)) { for (String l : out) w.write(l + System.lineSeparator()); } catch (Exception e) { plugin.getLogger().warning("blocklog write: " + e); }
    }

    void shutdown() { flush(); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) { record(e.getPlayer(), e.getBlock(), false); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) { record(e.getPlayer(), e.getBlockPlaced(), true); }

    @EventHandler public void onQuit(PlayerQuitEvent e) { inspecting.remove(e.getPlayer().getUniqueId()); }

    // ------------------------------------------------------------------ inspecting

    private void command(CommandSender s, String[] a) {
        if (!s.hasPermission("alloy.staff")) { s.sendMessage(Ui.mm("<red>Staff only.")); return; }
        if (a.length == 0) {
            if (!(s instanceof Player p)) { s.sendMessage("Players only."); return; }
            boolean on = inspecting.add(p.getUniqueId());
            if (!on) inspecting.remove(p.getUniqueId());
            p.sendMessage(Ui.mm(on ? "<#ffb347>🔍 Inspect mode <green>ON<gray>: left-click a block for its history, right-click for the block beside it. <white>/inspect<gray> again to leave." : "<#ffb347>🔍 Inspect mode <red>OFF"));
            return;
        }
        long hours = 24;
        try { if (a.length > 1) hours = Math.min(336, Long.parseLong(a[1])); } catch (NumberFormatException ignored) { }
        long cutoff = System.currentTimeMillis() - hours * 3600_000L;
        int breaks = 0, places = 0;
        Map<String, Integer> mats = new HashMap<>();
        List<Entry> last = new ArrayList<>();
        for (Entry e : recent) {
            if (e.time() < cutoff) break;
            if (!e.player().equalsIgnoreCase(a[0])) continue;
            if (e.placed()) places++; else breaks++;
            mats.merge((e.placed() ? "+" : "-") + e.material(), 1, Integer::sum);
            if (last.size() < 5) last.add(e);
        }
        s.sendMessage(Ui.mm("<#ffb347>🔍 <white>" + a[0] + " <gray>in the last " + hours + "h: <red>" + breaks + " broken<gray>, <green>" + places + " placed"));
        mats.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(6).forEach(en -> s.sendMessage(Ui.mm("<gray>  " + en.getKey() + " x" + en.getValue())));
        for (Entry e : last) s.sendMessage(Ui.mm("<dark_gray>  latest: " + (e.placed() ? "placed " : "broke ") + e.material() + " at " + e.world() + " " + e.x() + " " + e.y() + " " + e.z() + " (" + ago(e.time()) + ")"));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInspect(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!inspecting.contains(p.getUniqueId()) || e.getClickedBlock() == null || e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        if (e.getAction() != Action.LEFT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        e.setCancelled(true);
        e.setUseInteractedBlock(Event.Result.DENY);
        e.setUseItemInHand(Event.Result.DENY);
        Block b = e.getAction() == Action.RIGHT_CLICK_BLOCK ? e.getClickedBlock().getRelative(e.getBlockFace()) : e.getClickedBlock();
        Location l = b.getLocation();
        ArrayDeque<Entry> q = byBlock.get(key(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ()));
        p.sendMessage(Ui.mm("<#ffb347>🔍 <gray>" + b.getType() + " at <white>" + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ()));
        if (q == null || q.isEmpty()) { p.sendMessage(Ui.mm("<gray>  No recorded changes (the last 14 days).")); return; }
        for (Entry en : q) p.sendMessage(Ui.mm("<gray>  " + ago(en.time()) + " <white>" + en.player() + (en.placed() ? " <green>placed " : " <red>broke ") + "<white>" + en.material()));
    }

    private static String ago(long t) {
        long m = (System.currentTimeMillis() - t) / 60_000L;
        return m < 60 ? m + "m ago" : m < 1440 ? m / 60 + "h ago" : m / 1440 + "d ago";
    }
}
