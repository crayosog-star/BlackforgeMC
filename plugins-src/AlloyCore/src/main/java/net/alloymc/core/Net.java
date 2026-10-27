package net.alloymc.core;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Server switching and network player counts over the BungeeCord channel (Velocity supports it). */
public final class Net implements PluginMessageListener {
    public static final String[] SERVERS = {"hub", "skyblock", "survival", "factions", "arcade"};
    private static final String CHANNEL = "BungeeCord";
    private final AlloyCore plugin;
    private final Map<String, Integer> counts = new ConcurrentHashMap<>();

    Net(AlloyCore plugin) {
        this.plugin = plugin;
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
        Bukkit.getScheduler().runTaskTimer(plugin, this::requestCounts, 40L, 60L);
    }

    public void send(Player p, String server) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("Connect");
        out.writeUTF(server);
        p.sendPluginMessage(plugin, CHANNEL, out.toByteArray());
    }

    /** Send a chat message to every player on every server (via the proxy). */
    public void broadcastAll(Player via, net.kyori.adventure.text.Component msg) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("MessageRaw");
        out.writeUTF("ALL");
        out.writeUTF(net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().serialize(msg));
        via.sendPluginMessage(plugin, CHANNEL, out.toByteArray());
    }

    private void requestCounts() {
        Player any = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
        if (any == null) return;
        for (String s : SERVERS) ask(any, s);
        ask(any, "ALL");
    }

    private void ask(Player via, String server) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("PlayerCount");
        out.writeUTF(server);
        via.sendPluginMessage(plugin, CHANNEL, out.toByteArray());
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!CHANNEL.equals(channel)) return;
        ByteArrayDataInput in = ByteStreams.newDataInput(message);
        if (!"PlayerCount".equals(in.readUTF())) return;
        String server = in.readUTF();
        counts.put(server, in.readInt());
    }

    /** Last known player count; "ALL" for the whole network. */
    public int count(String server) {
        return counts.getOrDefault(server, server.equals("ALL") ? Bukkit.getOnlinePlayers().size() : 0);
    }
}
