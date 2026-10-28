package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;

import java.io.File;
import java.util.*;

/**
 * Jelly Legs (Survival only): no fall damage while it's switched on. Unlocked for good from the Scrap Shop or as a
 * rare crate reward - never sold for real money, it changes gameplay. /jl or /jellylegs toggles it.
 * Saved in jellylegs.yml (owned + switched on).
 */
public final class JellyLegs implements Listener {
    public static final int SCRAP_PRICE = 2000, DUPLICATE_REFUND = 300;

    private final AlloyCore plugin;
    private final File file;
    private final Set<UUID> owned = new HashSet<>(), on = new HashSet<>();
    private Scrap scrap;

    JellyLegs(AlloyCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "jellylegs.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        y.getStringList("owned").forEach(s -> owned.add(UUID.fromString(s)));
        y.getStringList("on").forEach(s -> on.add(UUID.fromString(s)));
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    void setScrap(Scrap s) { this.scrap = s; }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("owned", owned.stream().map(UUID::toString).toList());
        y.set("on", on.stream().map(UUID::toString).toList());
        try { y.save(file); } catch (Exception ignored) { }
    }

    public boolean owns(Player p) { return owned.contains(p.getUniqueId()); }

    /** Unlock it (Scrap Shop or crate). Returns false if they already had it - the caller refunds or swaps the reward. */
    public boolean unlock(Player p) {
        if (!owned.add(p.getUniqueId())) return false;
        on.add(p.getUniqueId());
        save();
        p.sendMessage(Ui.mm("<#7ddc4a>🦵 <bold>Jelly Legs unlocked!</bold> <gray>No more fall damage. Toggle it any time with <white>/jl"));
        p.playSound(p.getLocation(), Sound.BLOCK_SLIME_BLOCK_PLACE, 1f, 0.8f);
        return true;
    }

    /** Crate reward: unlock, or Scrap if they already have it. */
    public void giveFromCrate(Player p) {
        if (unlock(p)) return;
        if (scrap != null) scrap.add(p, DUPLICATE_REFUND, null);
        p.sendMessage(Ui.mm("<gray>You already have Jelly Legs - here's <#c6a15b>" + DUPLICATE_REFUND + " Scrap <gray>instead."));
    }

    public void toggle(Player p) {
        if (!owns(p)) {
            p.sendMessage(Ui.mm("<#7ddc4a>🦵 Jelly Legs <gray>stops all fall damage. Unlock it in the <white>/scrap <gray>shop for <#c6a15b>"
                    + SCRAP_PRICE + " Scrap <gray>or win it from a crate."));
            return;
        }
        boolean now = on.add(p.getUniqueId()) || !on.remove(p.getUniqueId());
        save();
        p.sendMessage(Ui.mm("<#7ddc4a>🦵 Jelly Legs " + (now ? "<green><bold>ON</bold> <gray>- no fall damage" : "<red><bold>OFF")));
        p.playSound(p.getLocation(), Sound.BLOCK_SLIME_BLOCK_STEP, 1f, now ? 1.4f : 0.7f);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFall(EntityDamageEvent e) {
        if (e.getCause() == EntityDamageEvent.DamageCause.FALL && e.getEntity() instanceof Player p && on.contains(p.getUniqueId()) && owned.contains(p.getUniqueId()))
            e.setCancelled(true);
    }
}
