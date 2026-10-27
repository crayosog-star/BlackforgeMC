package net.alloymc.core;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.io.File;

/**
 * While the proxy is briefly in offline mode for automated bot tests (tools/testbot/proxymode.py creates
 * shared/test-mode.flag), only the AlloyTester bots may log in. A real player joining in that window
 * would otherwise get an offline-mode UUID and a duplicate account.
 */
final class TestGuard implements Listener {
    private final File flag;

    TestGuard(File sharedDir) { this.flag = new File(sharedDir, "test-mode.flag"); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (!flag.exists() || e.getName().startsWith("AlloyTester")) return;
        // a stale flag (left by a crashed test) never locks people out for long
        if (System.currentTimeMillis() - flag.lastModified() > 20 * 60_000L) return;
        e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Ui.mm("<gold>BlackForgeMC <gray>is running a quick automated test.\n<white>Please try again in a minute or two."));
    }
}
