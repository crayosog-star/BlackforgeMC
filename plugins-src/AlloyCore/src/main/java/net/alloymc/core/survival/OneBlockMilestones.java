package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Rewards for OneBlock progress: break enough blocks on your OneBlock island and a key (or a Player Vault) arrives.
 * Checked every few minutes for players who are in OneBlock; each milestone pays once per player.
 */
final class OneBlockMilestones {
    private record Milestone(long blocks, String label, String command) {}

    private static final List<Milestone> MILESTONES = List.of(
            new Milestone(1_000, "<#e0895a>Cinder Key", "alloy crate give {player} iron 1"),
            new Milestone(5_000, "<#ffb52e>Molten Key", "alloy crate give {player} gold 1"),
            new Milestone(10_000, "<#ffb52e>Player Vault", "alloy vault give {player} 1"),
            new Milestone(25_000, "<#ff4422>Forgeheart Key", "alloy crate give {player} netherite 1"),
            new Milestone(50_000, "<#ffb52e>Player Vault <gray>and a <#ff4422>Forgeheart Key", "alloy vault give {player} 1;alloy crate give {player} netherite 1"));

    private final AlloyCore plugin;
    private final Function<UUID, Long> lifetime;
    private final Function<Player, Boolean> inOneBlock;
    private final File file;

    OneBlockMilestones(AlloyCore plugin, Function<UUID, Long> lifetime, Function<Player, Boolean> inOneBlock) {
        this.plugin = plugin;
        this.lifetime = lifetime;
        this.inOneBlock = inOneBlock;
        this.file = new File(plugin.getDataFolder(), "oneblock-milestones.yml");
        Bukkit.getScheduler().runTaskTimer(plugin, this::check, 20L * 90, 20L * 300);
    }

    private void check() {
        YamlConfiguration y = null;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!inOneBlock.apply(p)) continue;
            Long done = lifetime.apply(p.getUniqueId());
            if (done == null) continue;
            for (Milestone m : MILESTONES) {
                if (done < m.blocks()) continue;
                if (y == null) y = YamlConfiguration.loadConfiguration(file);
                String key = p.getUniqueId() + "." + m.blocks();
                if (y.getBoolean(key)) continue;
                y.set(key, true);
                try { y.save(file); } catch (Exception e) { plugin.getLogger().warning("oneblock milestones save: " + e); continue; }
                for (String cmd : m.command().split(";")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", p.getName()));
                p.sendMessage(Ui.mm("<#b06bff>✦ <white>OneBlock milestone! <gray>" + String.format("%,d", m.blocks()) + " blocks broken - you earned a " + m.label() + "<gray>."));
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.1f);
            }
        }
    }
}
