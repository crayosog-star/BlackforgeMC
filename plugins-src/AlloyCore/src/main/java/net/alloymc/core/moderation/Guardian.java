package net.alloymc.core.moderation;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;

/**
 * Cheat checks that sit alongside GrimAC (which covers movement and combat): x-ray mining
 * patterns and auto-clickers. Suspicious players are flagged to staff and logged, not banned,
 * so a human makes the final call.
 */
public final class Guardian implements Listener {
    private final AlloyCore plugin;
    private final ModStore store;
    private final Map<UUID, Deque<long[]>> mining = new HashMap<>();   // {time, kind}: 0 stone, 1 diamond, 2 debris
    private final Map<UUID, Deque<Long>> clicks = new HashMap<>();
    private final Map<UUID, Integer> highCpsSeconds = new HashMap<>();
    private final Map<String, Long> lastAlert = new HashMap<>();
    private final Set<UUID> alertsOff = new HashSet<>();

    static final long XRAY_WINDOW = 20 * 60_000L;
    static final int CPS_LIMIT = 20, CPS_SECONDS = 3;

    public Guardian(AlloyCore plugin, ModStore store) {
        this.plugin = plugin;
        this.store = store;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::cpsTick, 20L, 20L);
        Command c = new Command("alerts", "Toggle BlackForgeMC anti-cheat alerts", "/alerts", List.of("acalerts")) {
            @Override public boolean execute(CommandSender s, String label, String[] args) {
                if (!(s instanceof Player p) || !p.hasPermission("alloy.staff")) return true;
                if (!alertsOff.remove(p.getUniqueId())) alertsOff.add(p.getUniqueId());
                p.sendMessage(Ui.mm("<gray>BlackForgeMC anti-cheat alerts: " + (alertsOff.contains(p.getUniqueId()) ? "<red>off" : "<green>on")));
                return true;
            }
        };
        Bukkit.getCommandMap().register("alloy", c);
    }

    private void flag(Player p, String check, String detail) {
        String key = p.getUniqueId() + check;
        long now = System.currentTimeMillis();
        if (lastAlert.getOrDefault(key, 0L) > now - 300_000) return;   // at most every 5 minutes per check
        lastAlert.put(key, now);
        store.append("anticheat.log", p.getName() + " flagged " + check + ": " + detail);
        for (Player st : Bukkit.getOnlinePlayers())
            if (st.hasPermission("alloy.staff") && !alertsOff.contains(st.getUniqueId()))
                st.sendMessage(Ui.mm("<dark_red>[AC] <red>" + p.getName() + " <gray>flagged <white>" + check + " <gray>- " + detail));
        plugin.getLogger().warning("[AC] " + p.getName() + " flagged " + check + " - " + detail);
    }

    // ------------------------------------------------------------------ x-ray

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMine(BlockBreakEvent e) {
        Player p = e.getPlayer();
        if (p.getGameMode() != GameMode.SURVIVAL) return;
        Material m = e.getBlock().getType();
        int kind = switch (m) {
            case DIAMOND_ORE, DEEPSLATE_DIAMOND_ORE -> 1;
            case ANCIENT_DEBRIS -> 2;
            case STONE, DEEPSLATE, TUFF, NETHERRACK, BLACKSTONE, BASALT, GRANITE, DIORITE, ANDESITE -> 0;
            default -> -1;
        };
        if (kind < 0) return;
        long now = System.currentTimeMillis();
        Deque<long[]> d = mining.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>());
        d.addLast(new long[]{now, kind});
        while (!d.isEmpty() && d.peekFirst()[0] < now - XRAY_WINDOW) d.pollFirst();
        if (kind == 0) return;
        int stone = 0, diamonds = 0, debris = 0;
        for (long[] r : d) { if (r[1] == 0) stone++; else if (r[1] == 1) diamonds++; else debris++; }
        // Legit strip-mining finds roughly 1 diamond ore per 100-150 stone; x-rayers tunnel straight to them.
        if (diamonds >= 10 && stone < diamonds * 12)
            flag(p, "X-Ray (diamonds)", diamonds + " diamond ores but only " + stone + " stone mined in 20 min");
        if (debris >= 6 && stone < debris * 20)
            flag(p, "X-Ray (ancient debris)", debris + " ancient debris but only " + stone + " netherrack/stone mined in 20 min");
    }

    // ------------------------------------------------------------------ auto-clicker

    /*
     * Only real clicks count: a swing at the air or a hit on an entity. Holding the button down on a block (mining)
     * makes the client swing every tick - a steady 20 swings a second that looked exactly like an auto-clicker.
     */
    private void click(Player p) {
        clicks.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>()).addLast(System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAirClick(org.bukkit.event.player.PlayerInteractEvent e) {
        if (e.getAction() == org.bukkit.event.block.Action.LEFT_CLICK_AIR && e.getHand() == org.bukkit.inventory.EquipmentSlot.HAND) click(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHit(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && e.getCause() == org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK) click(p);
    }

    private void cpsTick() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Deque<Long> d = clicks.get(p.getUniqueId());
            if (d == null) continue;
            while (!d.isEmpty() && d.peekFirst() < now - 1000) d.pollFirst();
            int cps = d.size();
            if (cps >= CPS_LIMIT) {
                int secs = highCpsSeconds.merge(p.getUniqueId(), 1, Integer::sum);
                if (secs >= CPS_SECONDS) flag(p, "Auto-clicker", cps + " clicks/second for " + secs + "s");
            } else if (cps < CPS_LIMIT - 4) highCpsSeconds.remove(p.getUniqueId());
            // perfectly even click spacing is a machine, not a hand
            if (cps >= 12) {
                List<Long> times = new ArrayList<>(d);
                double mean = 0, var = 0;
                for (int i = 1; i < times.size(); i++) mean += times.get(i) - times.get(i - 1);
                mean /= Math.max(1, times.size() - 1);
                for (int i = 1; i < times.size(); i++) { double x = times.get(i) - times.get(i - 1) - mean; var += x * x; }
                double sd = Math.sqrt(var / Math.max(1, times.size() - 1));
                if (sd < 1.5) flag(p, "Auto-clicker (consistency)", cps + " CPS with only " + String.format("%.1f", sd) + "ms jitter");
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        clicks.remove(id); highCpsSeconds.remove(id); mining.remove(id);
    }
}
