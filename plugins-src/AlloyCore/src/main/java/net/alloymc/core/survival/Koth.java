package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;

import java.util.*;

/**
 * King of the Hill. Every few hours the hill in the KOTH arena goes live: stand on the gold
 * capture pad alone for the full capture time to win. Leaving resets you; sharing it pauses.
 */
public final class Koth {
    private final AlloyCore plugin;
    private final World world;
    private final Crates crates;
    private final int intervalMin, captureSec, maxMin;
    private final BossBar bar = BossBar.bossBar(Ui.mm("KOTH"), 0f, BossBar.Color.RED, BossBar.Overlay.NOTCHED_20);
    private boolean active;
    private UUID capper;
    private int held, elapsed;
    private long nextStart;
    private Scrap scrap;

    void setScrap(Scrap s) { this.scrap = s; }

    Koth(AlloyCore plugin, World world, Crates crates) {
        this.plugin = plugin;
        this.world = world;
        this.crates = crates;
        var cfg = plugin.getConfig();
        intervalMin = cfg.getInt("koth.interval-minutes", 120);
        captureSec = cfg.getInt("koth.capture-seconds", 180);
        maxMin = cfg.getInt("koth.max-minutes", 20);
        nextStart = System.currentTimeMillis() + cfg.getInt("koth.first-after-minutes", 30) * 60_000L;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public boolean active() { return active; }

    /** When the next KOTH starts (epoch ms), for the network event calendar. */
    public long nextStart() { return nextStart; }

    public String status() {
        if (active) return "<#e5484d>KOTH is LIVE! <gray>(" + (capper == null ? "nobody holding" : name(capper) + " " + fmt(captureSec - held) + " left") + ")";
        long mins = Math.max(0, (nextStart - System.currentTimeMillis()) / 60_000);
        return "<gray>Next KOTH in <white>" + (mins >= 60 ? mins / 60 + "h " + mins % 60 + "m" : mins + "m");
    }

    private static String fmt(int s) { return String.format("%d:%02d", s / 60, s % 60); }
    private static String name(UUID id) { Player p = Bukkit.getPlayer(id); return p == null ? "?" : p.getName(); }

    private int warned;

    public void start() {
        if (active) return;
        active = true;
        warned = 0;
        capper = null;
        held = 0;
        elapsed = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(Title.title(Ui.mm("<#e5484d><bold>KOTH"), Ui.mm("<gray>The hill is live! <white>/warp koth")));
            p.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 0.8f, 1f);
            p.showBossBar(bar);
        }
        Bukkit.broadcast(Ui.mm("<#e5484d><bold>⚔ KOTH has started!</bold> <gray>Hold the gold pad for <white>" + fmt(captureSec)
                + "<gray> to win. <yellow><u>Click here</u> <gray>or use <white>/warp koth")
                .clickEvent(ClickEvent.runCommand("/warp koth")));
    }

    public void stop(String reason) {
        active = false;
        capper = null;
        nextStart = System.currentTimeMillis() + intervalMin * 60_000L;
        for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar);
        if (reason != null) Bukkit.broadcast(Ui.mm("<#e5484d>⚔ <gray>" + reason));
    }

    private void tick() {
        long now = System.currentTimeMillis();
        if (!active) {
            long left = nextStart - now;
            // stage flags, not a 1-second window: a lagging tick could land in the window twice (double announcements)
            if (left <= 300_000 && left > 60_000 && warned < 1) { warned = 1; Bukkit.broadcast(Ui.mm("<#e5484d>⚔ <gray>KOTH starts in <white>5 minutes<gray>! Gear up.")); }
            if (left <= 60_000 && left > 0 && warned < 2) { warned = 2; Bukkit.broadcast(Ui.mm("<#e5484d>⚔ <gray>KOTH starts in <white>1 minute<gray>!")); }
            if (left <= 0) start();
            return;
        }
        elapsed++;
        List<Player> on = new ArrayList<>();
        for (Player p : world.getPlayers())
            if (p.getGameMode() == GameMode.SURVIVAL && !p.isDead() && SurvivalLayout.inCapture(p.getX(), p.getY(), p.getZ())) on.add(p);
        if (on.size() == 1) {
            Player p = on.getFirst();
            if (!p.getUniqueId().equals(capper)) {
                capper = p.getUniqueId();
                held = 0;
                Bukkit.broadcast(Ui.mm("<#e5484d>⚔ <white>" + p.getName() + " <gray>is capturing the hill!"));
            }
            held++;
            world.spawnParticle(Particle.FLAME, p.getLocation().add(0, 0.2, 0), 8, 0.6, 0.1, 0.6, 0.01);
            if (held % 30 == 0 && held < captureSec) Bukkit.broadcast(Ui.mm("<#e5484d>⚔ <white>" + p.getName() + " <gray>has <white>" + fmt(captureSec - held) + " <gray>left to win KOTH!"));
            if (held >= captureSec) { win(p); return; }
        } else if (on.isEmpty() && capper != null) {
            Bukkit.broadcast(Ui.mm("<#e5484d>⚔ <white>" + name(capper) + " <gray>was knocked off the hill!"));
            capper = null;
            held = 0;
        }
        boolean contested = on.size() > 1;
        String who = capper == null ? "<gray>Nobody holds the hill" : "<white>" + name(capper) + (contested ? " <yellow>(contested!)" : "");
        bar.name(Ui.mm("<#e5484d><bold>KOTH</bold> <dark_gray>| " + who + " <dark_gray>| <white>" + fmt(captureSec - held)));
        bar.progress(Math.min(1f, held / (float) captureSec));
        bar.color(contested ? BossBar.Color.YELLOW : BossBar.Color.RED);
        for (Player p : Bukkit.getOnlinePlayers()) p.showBossBar(bar);
        if (elapsed >= maxMin * 60) stop("Nobody captured the hill in time. KOTH is over.");
    }

    private void win(Player p) {
        double money = plugin.getConfig().getDouble("koth.reward-money", 5000);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "eco give " + p.getName() + " " + (long) money);
        crates.give(p, "koth", 1);
        crates.give(p, "netherite", 1);
        net.alloymc.core.Hooks.stat(p, "koth", 1);
        if (scrap != null) scrap.add(p, 200, "won KOTH");
        for (Player o : Bukkit.getOnlinePlayers())
            o.showTitle(Title.title(Ui.mm("<gold><bold>" + p.getName()), Ui.mm("<gray>is the <#e5484d>King of the Hill<gray>!")));
        Bukkit.broadcast(Ui.mm("<#e5484d><bold>⚔ " + p.getName() + " won KOTH!</bold> <gray>Reward: <green>$" + (long) money + "<gray>, a <#c084fc>Conqueror Key<gray> and a <#ff4422>Forgeheart Key<gray>."));
        for (int i = 0; i < 4; i++)
            Bukkit.getScheduler().runTaskLater(plugin, () -> world.spawn(p.getLocation().add(0, 1, 0), Firework.class, fw -> {
                FireworkMeta m = fw.getFireworkMeta();
                m.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.STAR).withColor(Color.RED, Color.ORANGE).withFade(Color.YELLOW).trail(true).build());
                m.setPower(1);
                fw.setFireworkMeta(m);
            }), i * 10L);
        stop(null);
    }

    /** Players drop in from above at a random spot around the hill. */
    private final Random dropRnd = new Random();
    private final SafeDrop.Pool landing = new SafeDrop.Pool(() -> SafeDrop.find(world(), () -> {
        double a = dropRnd.nextDouble() * Math.PI * 2, dist = 12 + dropRnd.nextDouble() * 16;
        return new Location(world(), SurvivalLayout.KOTH_X + 0.5 + Math.cos(a) * dist, 0, SurvivalLayout.KOTH_Z + 0.5 + Math.sin(a) * dist);
    }, SurvivalLayout.KOTH_Y + 28, SurvivalLayout.KOTH_Y + 2, 30, dropRnd));

    private World world() { return world; }

    public Location arrival() {
        Location l = landing.next(dropRnd);
        l.setDirection(new org.bukkit.util.Vector(SurvivalLayout.KOTH_X + 0.5, SurvivalLayout.HILL_TOP, SurvivalLayout.KOTH_Z + 0.5).subtract(l.toVector()));
        return l;
    }
}
