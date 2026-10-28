package net.alloymc.core;

import java.io.File;
import java.nio.file.Files;
import java.util.*;

/**
 * /alloy restart <server|all|this> [now]: ask the host to restart one or more BlackForgeMC servers.
 *
 * A plugin can't relaunch its own server (something outside it has to start the process again), so this only writes
 * a small request file into the network's tools/restart-requests folder. tools/autoupdate.py, which already runs every
 * few minutes on the host, picks it up and restarts the servers: empty ones right away, ones with players after the
 * usual in-game countdown (or straight away with "now").
 */
public final class RestartRequest {
    private RestartRequest() {}

    public static final List<String> SERVERS = List.of("hub", "skyblock", "survival", "factions", "arcade");

    /** Parsed request: which servers, and whether to skip the countdown. */
    public record Parsed(List<String> servers, boolean now) {}

    /**
     * Parse the arguments after "restart". {@code self} is this server's name (for "this").
     * Returns null if the target is missing or unknown.
     */
    public static Parsed parse(String[] args, String self) {
        if (args.length < 1) return null;
        String target = args[0].toLowerCase(Locale.ROOT);
        boolean now = args.length > 1 && args[1].equalsIgnoreCase("now");
        if (target.equals("all")) return new Parsed(SERVERS, now);
        if (target.equals("this")) target = self == null ? "" : self.toLowerCase(Locale.ROOT);
        if (!SERVERS.contains(target)) return null;
        return new Parsed(List.of(target), now);
    }

    /** The line written to the request file: "survival,hub countdown Colin 1730000000000". */
    public static String line(Parsed p, String requester, long when) {
        String who = requester == null || requester.isBlank() ? "console" : requester.replaceAll("\\s+", "_");
        return String.join(",", p.servers()) + " " + (p.now() ? "now" : "countdown") + " " + who + " " + when;
    }

    /**
     * The network folder: this server runs in <root>/<server>/ and the plugin's data folder is
     * <root>/<server>/plugins/AlloyCore. Returns null if the layout doesn't look like the BlackForgeMC network.
     */
    public static File networkRoot(File dataFolder) {
        File f = dataFolder.getAbsoluteFile();
        for (int i = 0; i < 3 && f != null; i++) f = f.getParentFile();
        if (f == null || !new File(f, "tools/netctl.py").isFile()) return null;
        return f;
    }

    /** Write the request for the host to pick up. Returns the file, or throws if it can't be written. */
    public static File write(File dataFolder, Parsed p, String requester) throws Exception {
        File root = networkRoot(dataFolder);
        if (root == null) throw new IllegalStateException("can't find the network folder (tools/netctl.py) above " + dataFolder);
        File dir = new File(root, "tools/restart-requests");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("can't create " + dir);
        long now = System.currentTimeMillis();
        File out = new File(dir, "restart-" + now + "-" + Math.abs(new Random().nextInt(1000)) + ".txt");
        Files.writeString(out.toPath(), line(p, requester, now) + "\n");
        return out;
    }
}
