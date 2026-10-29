package net.alloymc.core.hub;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Nicks;
import net.alloymc.core.Ui;
import net.alloymc.core.arcade.core.BuiltMap;
import net.alloymc.core.arcade.core.Pt;
import net.alloymc.core.build.Canvas;
import net.alloymc.core.build.Paster;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.*;

/**
 * Hub parkour: /parkour (or the sign by spawn) takes you to a timed jump course in its own world, built from the Arcade's
 * physics-checked Foundry Climb. The clock starts when you leave the start pad, checkpoints catch your falls, and the
 * best times go on a board in the hub. Times in hub-parkour.yml.
 */
public final class HubParkour implements Listener {
    private static final int OY = 100;
    private final AlloyCore plugin;
    private final HubModule hub;
    private final World world;
    private final Location start;
    private final List<Location> checkpoints = new ArrayList<>();
    private final int[] finish;   // x1 y1 z1 x2 y2 z2
    private final int voidY;
    private final File file;
    private final YamlConfiguration data;
    private final Map<UUID, Long> started = new HashMap<>();
    private final Map<UUID, Integer> reached = new HashMap<>();
    private TextDisplay board, sign;

    HubParkour(AlloyCore plugin, HubModule hub, World hubWorld) {
        this.plugin = plugin;
        this.hub = hub;
        this.file = new File(plugin.getDataFolder(), "hub-parkour.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        World w = Bukkit.getWorld("hub_parkour");
        if (w == null) w = new WorldCreator("hub_parkour").generator(new net.alloymc.core.survival.SkyHub.Void()).generateStructures(false).createWorld();
        world = w;
        for (String[] r : new String[][]{{"spawn_mobs", "false"}, {"advance_weather", "false"}, {"advance_time", "false"}}) {
            @SuppressWarnings("deprecation") boolean ignored = world.setGameRuleValue(r[0], r[1]);
        }
        world.setTime(18000);
        BuiltMap course = new net.alloymc.core.arcade.games.parkour.Parkour().builtInMaps().stream()
                .filter(b -> b.def().id.equals("foundry_climb")).findFirst().orElseThrow();
        start = at(course.def().point("start"));
        for (Pt p : course.def().list("checkpoints")) checkpoints.add(at(p));
        var f = course.def().region("finish");
        finish = new int[]{f.x1(), f.y1() + OY, f.z1(), f.x2(), f.y2() + OY, f.z2()};
        voidY = course.def().voidY + OY;
        if (!new File(plugin.getDataFolder(), "hub-parkour-built").exists()) {
            Canvas c = new Canvas();
            course.canvas().blocks().forEach((k, b) -> c.set(Canvas.kx(k), Canvas.ky(k) + OY, Canvas.kz(k), b));
            course.canvas().lateBlocks().forEach((k, b) -> c.setLate(Canvas.kx(k), Canvas.ky(k) + OY, Canvas.kz(k), b));
            Paster.paste(plugin, world, c, 4000, () -> { try { new File(plugin.getDataFolder(), "hub-parkour-built").createNewFile(); } catch (Exception ignored) { } });
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getCommandMap().register("alloy", new Command("parkour", "Hub parkour", "/parkour", List.of("pk")) {
            @Override public boolean execute(CommandSender s, String l, String[] a) { if (s instanceof Player p) enter(p); return true; }
        });
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, 2L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::boards, 80L, 600L);
    }

    private Location at(Pt p) { return new Location(world, p.x(), p.y() + OY, p.z(), p.yaw(), p.pitch()); }

    public void enter(Player p) {
        started.remove(p.getUniqueId());
        reached.remove(p.getUniqueId());
        p.teleport(start);
        p.setGameMode(GameMode.ADVENTURE);
        p.getInventory().clear();
        p.getInventory().setItem(3, Ui.item(Material.HEAVY_WEIGHTED_PRESSURE_PLATE, "<yellow>Back to checkpoint"));
        p.getInventory().setItem(4, Ui.item(Material.BARRIER, "<red>Restart"));
        p.getInventory().setItem(5, Ui.item(Material.RED_BED, "<gray>Back to the hub"));
        p.sendMessage(Ui.mm("<gold>⏱ <bold>Hub Parkour</bold> <gray>- the clock starts when you leave the start. Checkpoints catch your falls."));
        long best = data.getLong(p.getUniqueId() + ".best");
        if (best > 0) p.sendMessage(Ui.mm("<gray>Your best: <white>" + time(best)));
    }

    private void leave(Player p) {
        started.remove(p.getUniqueId());
        reached.remove(p.getUniqueId());
        hub.toSpawn(p);
    }

    private static String time(long ms) { return String.format("%d:%02d.%02d", ms / 60000, ms / 1000 % 60, ms / 10 % 100); }

    private boolean inFinish(Location l) {
        return l.getX() >= finish[0] && l.getX() <= finish[3] + 1 && l.getY() >= finish[1] && l.getY() <= finish[4] + 1 && l.getZ() >= finish[2] && l.getZ() <= finish[5] + 1;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (p.getWorld() != world) return;
        Location to = e.getTo();
        if (to.getY() < voidY) { back(p); return; }
        UUID id = p.getUniqueId();
        if (!started.containsKey(id) && to.distanceSquared(start) > 4) { started.put(id, System.currentTimeMillis()); p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1.6f); }
        for (int i = reached.getOrDefault(id, -1) + 1; i < checkpoints.size(); i++)
            if (to.distanceSquared(checkpoints.get(i)) < 2.5) {
                reached.put(id, i);
                p.sendActionBar(Ui.mm("<green>Checkpoint " + (i + 1) + "/" + checkpoints.size()));
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.4f);
            }
        if (started.containsKey(id) && inFinish(to)) finish(p);
    }

    private void back(Player p) {
        Integer cp = reached.get(p.getUniqueId());
        p.teleport(cp == null ? start : checkpoints.get(cp));
        p.setFallDistance(0);
    }

    private void finish(Player p) {
        long ms = System.currentTimeMillis() - started.remove(p.getUniqueId());
        reached.remove(p.getUniqueId());
        String id = p.getUniqueId().toString();
        long best = data.getLong(id + ".best");
        boolean pb = best == 0 || ms < best;
        if (pb) { data.set(id + ".best", ms); data.set(id + ".name", Nicks.of(p)); try { data.save(file); } catch (Exception ignored) { } }
        p.showTitle(net.kyori.adventure.title.Title.title(Ui.mm("<gold><bold>" + time(ms)), Ui.mm(pb ? "<green>New personal best!" : "<gray>Best: " + time(best))));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        if (pb) Bukkit.broadcast(Ui.mm("<gold>⏱ <white>" + Nicks.of(p) + " <gray>ran the hub parkour in <gold>" + time(ms) + " <dark_gray>(/parkour)"));
        boards();
        Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline() && p.getWorld() == world) p.teleport(start); }, 60L);
    }

    private void tick() {
        for (Player p : world.getPlayers()) {
            Long t = started.get(p.getUniqueId());
            if (t != null) p.sendActionBar(Ui.mm("<gold>⏱ " + time(System.currentTimeMillis() - t) + " <gray>• checkpoint " + (reached.getOrDefault(p.getUniqueId(), -1) + 1) + "/" + checkpoints.size()));
        }
    }

    private void boards() {
        List<String> ids = new ArrayList<>(data.getKeys(false));
        ids.sort(Comparator.comparingLong(k -> data.getLong(k + ".best")));
        StringBuilder sb = new StringBuilder("<gold><bold>⏱ HUB PARKOUR</bold>\n<gray>/parkour\n<dark_gray>" + "-".repeat(14));
        if (ids.isEmpty()) sb.append("\n<gray>Be the first!");
        for (int i = 0; i < Math.min(10, ids.size()); i++)
            sb.append("\n").append(i == 0 ? "<gold>" : "<gray>").append(i + 1).append(". <white>").append(data.getString(ids.get(i) + ".name")).append(" <gray>").append(time(data.getLong(ids.get(i) + ".best")));
        World hw = hub.spawn().getWorld();
        Location at = new Location(hw, 0.5, HubLayout.Y + 5.5, -12.5);
        if (board == null || !board.isValid()) board = Ui.hologram(at, sb.toString(), 1.0f);
        else board.text(Ui.mm(sb.toString()));
        if (sign == null || !sign.isValid()) sign = Ui.hologram(start.clone().add(0, 3, 0), "<gold><bold>HUB PARKOUR</bold>\n<gray>Leave the pad to start the clock", 1.2f);
    }

    void shutdown() { if (board != null) board.remove(); if (sign != null) sign.remove(); }

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (p.getWorld() != world || e.getItem() == null) return;
        e.setCancelled(true);
        switch (e.getItem().getType()) {
            case HEAVY_WEIGHTED_PRESSURE_PLATE -> back(p);
            case BARRIER -> enter(p);
            case RED_BED -> leave(p);
            default -> {}
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) { if (e.getEntity().getWorld() == world) e.setCancelled(true); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { started.remove(e.getPlayer().getUniqueId()); reached.remove(e.getPlayer().getUniqueId()); }
}
