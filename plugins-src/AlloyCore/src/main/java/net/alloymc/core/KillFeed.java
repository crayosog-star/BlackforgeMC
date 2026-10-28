package net.alloymc.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Kill feed for the open-world servers (survival, skyblock, factions): every player killed by another player is announced in
 * chat with what they were killed with, by its custom name if it has one (hover it to see the item, like vanilla).
 * Other deaths keep the vanilla message, marked with the same skull.
 */
public final class KillFeed implements Listener {
    private static final Component SKULL = Component.text("☠ ", NamedTextColor.DARK_RED);
    private final String mode;

    public KillFeed(String mode) { this.mode = mode; }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent e) {
        Player victim = e.getPlayer(), killer = victim.getKiller();
        if (killer == null || killer == victim) {
            Component vanilla = e.deathMessage();
            if (vanilla != null) e.deathMessage(SKULL.append(vanilla.colorIfAbsent(NamedTextColor.GRAY)));
            return;
        }
        e.deathMessage(message(victim, killer, killer.getInventory().getItemInMainHand()));
        Hooks.kill(killer, victim, mode);
    }

    /** "☠ Victim was slain by Killer using [Item]" - the item shows its custom name and can be hovered. */
    public static Component message(Player victim, Player killer, ItemStack weapon) {
        Component msg = SKULL
                .append(Component.text(victim.getName(), NamedTextColor.WHITE))
                .append(Component.text(" was slain by ", NamedTextColor.GRAY))
                .append(Component.text(killer.getName(), NamedTextColor.RED));
        return msg.append(weapon(weapon));
    }

    /** " using [Item]", or " with their fists" when the killer's hand is empty. */
    public static Component weapon(ItemStack weapon) {
        if (weapon == null || weapon.getType() == Material.AIR) return Component.text(" with their fists", NamedTextColor.GRAY);
        return Component.text(" using ", NamedTextColor.GRAY).append(weapon.displayName());
    }
}
