package net.alloymc.core;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.function.Consumer;

/**
 * Files in MinecraftNetwork/shared that more than one server may write at once (parties, friend lists): every change
 * is a read-modify-write under an OS file lock, so two servers never overwrite each other's change.
 */
public final class Shared {
    private Shared() {}

    public static YamlConfiguration read(File f) { return YamlConfiguration.loadConfiguration(f); }

    /** Load, change and save {@code f} while holding its lock (other servers wait). */
    public static synchronized void update(File f, Consumer<YamlConfiguration> change) {
        f.getParentFile().mkdirs();
        File lock = new File(f.getPath() + ".lock");
        try (RandomAccessFile raf = new RandomAccessFile(lock, "rw"); FileChannel ch = raf.getChannel(); FileLock ignored = ch.lock()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            change.accept(y);
            y.save(f);
        } catch (Exception e) {
            throw new IllegalStateException("shared file " + f.getName() + ": " + e.getMessage(), e);
        }
    }

    /** Throttled action-bar explanation for something the game just blocked ("why can't I?"): at most once every 2 s each. */
    private static final java.util.Map<java.util.UUID, Long> told = new java.util.concurrent.ConcurrentHashMap<>();

    public static void why(Player p, String mm) {
        long now = System.currentTimeMillis();
        Long last = told.get(p.getUniqueId());
        if (last != null && now - last < 2000) return;
        told.put(p.getUniqueId(), now);
        p.sendActionBar(Ui.mm(mm));
    }
}
