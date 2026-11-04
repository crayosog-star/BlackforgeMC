package net.blackforge.core.raft;

import net.blackforge.core.Ui;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Thirst (the sea gives salt water that dries you out; a purifier makes fresh), scooping water with a Cup, and the purifiers' work.
 * Thirst runs 0..20 and falls by one about every 70 seconds; at zero you start to take damage (never fatal on its own).
 */
final class RaftNeeds implements Listener {
    static final int MAX = 20, START = 14;

    private final RaftModule mod;
    private final Map<UUID, Long> nextDrop = new HashMap<>(), nextHurt = new HashMap<>();

    RaftNeeds(RaftModule mod) {
        this.mod = mod;
        Bukkit.getPluginManager().registerEvents(this, mod.plugin());
        Bukkit.getScheduler().runTaskTimer(mod.plugin(), this::tick, 40L, 20L);
    }

    int thirst(RaftTeam t, UUID id) { return t.thirst.getOrDefault(id, START); }

    void add(RaftTeam t, UUID id, int d) { t.thirst.put(id, Math.max(0, Math.min(MAX, thirst(t, id) + d))); }

    private void tick() {
        World w = mod.world();
        if (w == null) return;
        long now = System.currentTimeMillis();
        for (Player p : w.getPlayers()) {
            if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR) continue;
            RaftTeam t = mod.teamOf(p);
            if (t == null) continue;
            UUID id = p.getUniqueId();
            if (now >= nextDrop.getOrDefault(id, now + 70_000)) { add(t, id, -1); nextDrop.put(id, now + 70_000 - (p.isSprinting() ? 20_000 : 0)); }
            nextDrop.putIfAbsent(id, now + 70_000);
            int th = thirst(t, id);
            if (th <= 0 && now >= nextHurt.getOrDefault(id, 0L)) {
                nextHurt.put(id, now + 4000);
                if (p.getHealth() > 2.5) p.damage(1.0);
                p.sendActionBar(Ui.mm("<red>You are parched. <gray>Drink fresh water."));
            } else if (p.getTicksLived() % 40 == 0 || th <= 4) p.sendActionBar(bar(th));
        }
        // purifiers
        for (RaftTeam t : mod.activeTeams()) {
            for (RaftTeam.Station s : t.stations.values()) {
                if (!s.type.equals("purifier") || s.salt <= 0 || s.fuel <= 0) continue;
                s.progress++;
                Location at = new Location(w, s.x + 0.5, s.y + 1.1, s.z + 0.5);
                w.spawnParticle(Particle.CLOUD, at, 1, 0.15, 0.1, 0.15, 0.01);
                if (s.progress >= 15) {
                    s.progress = 0; s.salt--; s.fuel--; s.fresh++;
                    w.playSound(at, Sound.BLOCK_BREWING_STAND_BREW, 0.6f, 1.4f);
                    w.spawnParticle(Particle.SPLASH, at, 8, 0.2, 0.2, 0.2, 0.05);
                }
            }
        }
    }

    static net.kyori.adventure.text.Component bar(int th) {
        StringBuilder sb = new StringBuilder("<#7fd6ff>Thirst <white>");
        for (int i = 0; i < 10; i++) sb.append(th >= (i + 1) * 2 ? "●" : th == i * 2 + 1 ? "◐" : "<dark_gray>○<white>");
        return Ui.mm(sb.toString() + (th <= 4 ? " <red>low" : ""));
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        RaftTeam t = mod.teamOf(e.getPlayer());
        if (t == null) return;
        t.thirst.put(e.getPlayer().getUniqueId(), START);
        Location spot = mod.arrival(t);
        if (spot != null) e.setRespawnLocation(spot);
    }

    @EventHandler
    public void onConsume(PlayerItemConsumeEvent e) {
        String id = RaftItems.id(e.getItem());
        if (id == null) return;
        RaftTeam t = mod.teamOf(e.getPlayer());
        if (t == null) return;
        if (id.equals("fresh")) {
            add(t, e.getPlayer().getUniqueId(), 8);
            e.setReplacement(RaftItems.make("cup"));
            e.getPlayer().sendActionBar(bar(thirst(t, e.getPlayer().getUniqueId())));
        } else if (id.equals("salt")) {
            add(t, e.getPlayer().getUniqueId(), -4);
            e.setReplacement(RaftItems.make("cup"));
            e.getPlayer().addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 160, 0));
            e.getPlayer().sendMessage(Ui.mm("<#3f6f9a>Salt water. <gray>That only made it worse."));
        }
    }

    /** Right-click the sea with a Cup. */
    @EventHandler
    public void onCup(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!RaftItems.is(hand, "cup") || p.getWorld() != mod.world()) return;
        e.setCancelled(true);
        RayTraceResult r = p.rayTraceBlocks(5, FluidCollisionMode.SOURCE_ONLY);
        if (r == null || r.getHitBlock() == null || r.getHitBlock().getType() != Material.WATER) return;
        hand.setAmount(hand.getAmount() - 1);
        p.getInventory().addItem(RaftItems.make("salt")).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        p.playSound(p.getLocation(), Sound.ITEM_BOTTLE_FILL, 1f, 1f);
        p.sendActionBar(Ui.mm("<#3f6f9a>Salt water. <gray>Put it through a purifier."));
    }
}
