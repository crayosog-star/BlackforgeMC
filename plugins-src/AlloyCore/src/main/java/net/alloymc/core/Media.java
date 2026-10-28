package net.alloymc.core;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.PermissionAttachment;

import java.util.*;

/**
 * The Media rank, for content creators. Staff grant it by hand (LuckPerms group "media", permission alloy.media) after an
 * application in the Discord. Perks: a 🎥 MEDIA tag before the name, +3 homes on top of the player's rank (Essentials
 * sethome-multiple groups media_&lt;rank&gt;), and the Media role in Discord. /media lists the requirements.
 */
public final class Media implements Listener {
    public static final String PERM = "alloy.media";
    public static final String BADGE = "<#ff5fd2><bold>🎥 MEDIA</bold> ";
    private static final List<String> RANKS = List.of("netherite", "diamond", "gold", "iron", "copper");

    private final AlloyCore plugin;
    private final Map<UUID, PermissionAttachment> homes = new HashMap<>();

    public Media(AlloyCore plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getCommandMap().register("alloy", new Command("media", "The BlackForgeMC Media rank", "/media", List.of("youtube", "creator")) {
            @Override public boolean execute(CommandSender s, String label, String[] a) { info(s); return true; }
        });
    }

    public static boolean has(Player p) { return p.hasPermission(PERM); }

    /** Media players get the Essentials home group one step up from their rank: media_&lt;rank&gt; = rank homes + 3. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!has(p)) return;
        String rank = RANKS.stream().filter(r -> p.hasPermission("alloy.rank." + r)).findFirst().orElse("default");
        PermissionAttachment att = p.addAttachment(plugin);
        att.setPermission("essentials.sethome.multiple.media_" + rank, true);
        homes.put(p.getUniqueId(), att);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        PermissionAttachment att = homes.remove(e.getPlayer().getUniqueId());
        if (att != null) try { e.getPlayer().removeAttachment(att); } catch (IllegalArgumentException ignored) { }
    }

    private void info(CommandSender s) {
        String invite = plugin.getConfig().getString("discord-invite", Community.DEFAULT_INVITE);
        for (String line : List.of(
                BADGE + "<gray>rank - for people who make BlackForgeMC content",
                "<white>Requirements (any one):",
                "<gray> • <red>YouTube<gray>: 100+ subscribers and a BlackForgeMC video with 500+ views",
                "<gray> • <aqua>TikTok<gray>: 500+ followers and a BlackForgeMC clip with 2,000+ views",
                "<gray> • <light_purple>Twitch / Kick<gray>: 5+ average viewers streaming BlackForgeMC for 2+ hours",
                "<yellow>Every video and stream must show the server IP <white>play.blackforgemc.com <yellow>on screen the entire time.",
                "<gray>Keep it by posting at least one BlackForgeMC video or stream a month.",
                "<white>Perks: <gray>🎥 MEDIA tag, <white>+3 homes<gray> on top of your rank, the Media role in Discord.",
                "<gray>Apply in <white>#media-apply<gray> on our Discord: <click:open_url:'" + invite + "'><white><u>" + invite + "</u></click>"))
            s.sendMessage(Ui.mm(line));
    }
}
