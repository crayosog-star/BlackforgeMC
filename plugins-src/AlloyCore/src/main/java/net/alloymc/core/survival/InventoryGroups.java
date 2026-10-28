package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;

import java.io.File;
import java.util.*;
import java.util.function.Function;

/**
 * Separate inventories per game on one server. Each group (Skyblock, OneBlock, the Skyblock Hub) has its own
 * inventory, armour, off hand, ender chest, XP, health, hunger and potion effects. Whenever a player teleports or
 * respawns into a different group, the old group's belongings are saved and the new group's are put back, so nothing
 * crosses over. Saved in plugins/AlloyCore/invgroups/&lt;uuid&gt;.yml.
 */
public final class InventoryGroups implements Listener {
    private final AlloyCore plugin;
    private final Function<Location, String> groupOf;
    private final File dir;

    public InventoryGroups(AlloyCore plugin, Function<Location, String> groupOf) {
        this.plugin = plugin;
        this.groupOf = groupOf;
        this.dir = new File(plugin.getDataFolder(), "invgroups");
        dir.mkdirs();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private File file(UUID u) { return new File(dir, u + ".yml"); }

    /** The group whose belongings the player is carrying right now. */
    public String current(Player p) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file(p.getUniqueId()));
        String c = y.getString("current");
        if (c == null) {
            // first time we see them: whatever they carry belongs where they're standing
            c = groupOf.apply(p.getLocation());
            y.set("current", c);
            try { y.save(file(p.getUniqueId())); } catch (Exception ignored) { }
        }
        return c;
    }

    /** Put away what the player carries for their current group and give them what they left in {@code to}. */
    public void switchTo(Player p, String to) {
        String from = current(p);
        if (from.equals(to)) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file(p.getUniqueId()));
        save(p, y, from);
        load(p, y, to);
        y.set("current", to);
        try { y.save(file(p.getUniqueId())); } catch (Exception e) { plugin.getLogger().warning("Could not save inventories for " + p.getName() + ": " + e.getMessage()); }
    }

    private static String enc(ItemStack[] items) {
        List<String> out = new ArrayList<>();
        for (ItemStack it : items) out.add(it == null || it.getType().isAir() ? "" : Base64.getEncoder().encodeToString(it.serializeAsBytes()));
        return String.join(",", out);
    }

    private static ItemStack[] dec(String s, int size) {
        ItemStack[] out = new ItemStack[size];
        if (s == null || s.isEmpty()) return out;
        String[] parts = s.split(",", -1);
        for (int i = 0; i < Math.min(size, parts.length); i++)
            if (!parts[i].isEmpty()) out[i] = ItemStack.deserializeBytes(Base64.getDecoder().decode(parts[i]));
        return out;
    }

    private void save(Player p, YamlConfiguration y, String g) {
        String b = "groups." + g + ".";
        y.set(b + "inv", enc(p.getInventory().getContents()));
        y.set(b + "ender", enc(p.getEnderChest().getContents()));
        y.set(b + "level", p.getLevel());
        y.set(b + "exp", (double) p.getExp());
        y.set(b + "health", p.getHealth());
        y.set(b + "food", p.getFoodLevel());
        y.set(b + "saturation", (double) p.getSaturation());
        y.set(b + "effects", new ArrayList<>(p.getActivePotionEffects()));
    }

    @SuppressWarnings("unchecked")
    private void load(Player p, YamlConfiguration y, String g) {
        String b = "groups." + g + ".";
        p.getInventory().setContents(dec(y.getString(b + "inv"), p.getInventory().getContents().length));
        p.getEnderChest().setContents(dec(y.getString(b + "ender"), p.getEnderChest().getSize()));
        p.setItemOnCursor(null);
        p.setLevel(y.getInt(b + "level"));
        p.setExp((float) y.getDouble(b + "exp"));
        var max = p.getAttribute(Attribute.MAX_HEALTH);
        double cap = max == null ? 20 : max.getValue();
        p.setHealth(Math.max(1, Math.min(cap, y.getDouble(b + "health", cap))));
        p.setFoodLevel(y.getInt(b + "food", 20));
        p.setSaturation((float) y.getDouble(b + "saturation", 5));
        for (PotionEffect e : p.getActivePotionEffects()) p.removePotionEffect(e.getType());
        for (Object o : y.getList(b + "effects", List.of())) if (o instanceof PotionEffect e) p.addPotionEffect(e);
        p.updateInventory();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (e.getTo() == null) return;
        switchTo(e.getPlayer(), groupOf.apply(e.getTo()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        String to = groupOf.apply(e.getRespawnLocation());
        Bukkit.getScheduler().runTask(plugin, () -> { if (p.isOnline()) switchTo(p, to); });
    }

    /** Joining puts them where they logged out: make sure they carry that place's belongings. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        current(p);
        switchTo(p, groupOf.apply(p.getLocation()));
    }
}
