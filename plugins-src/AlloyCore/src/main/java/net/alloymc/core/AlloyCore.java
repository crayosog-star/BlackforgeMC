package net.alloymc.core;

import java.io.File;

import net.alloymc.core.arcade.ArcadeModule;
import net.alloymc.core.hub.HubModule;
import net.alloymc.core.hub.WelcomeShow;
import net.alloymc.core.moderation.AutoMod;
import net.alloymc.core.moderation.Guardian;
import net.alloymc.core.survival.SkyblockModule;
import net.alloymc.core.survival.SurvivalModule;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class AlloyCore extends JavaPlugin implements Listener {
    private String mode;
    private Net net;
    private NetEconomy economy;
    private HubModule hub;
    private ArcadeModule arcade;
    private SurvivalModule survival;
    private SkyblockModule skyblock;
    private net.alloymc.core.factions.FactionsModule factions;
    private Supporters supporters;
    private File shared;
    private SharedStats sharedStats;
    private Cosmetics cosmetics;
    private Challenges challenges;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        mode = getConfig().getString("mode", "backend");
        net = new Net(this);
        economy = new NetEconomy(this);
        new LocatorRange(this);
        try { shared = new File(getServer().getWorldContainer().getCanonicalFile().getParentFile(), "shared"); }
        catch (java.io.IOException e) { shared = new File(getServer().getWorldContainer().getAbsoluteFile().getParentFile(), "shared"); }
        Bukkit.getPluginManager().registerEvents(new TestGuard(shared), this);
        // network-wide stats and cosmetics listen to the kill/arcade hooks, so they exist before the game modes
        sharedStats = new SharedStats(shared);
        cosmetics = new Cosmetics(this, shared);
        // moderation and cheat checks run on every server
        AutoMod automod = new AutoMod(this);
        new Guardian(this, automod.store());
        new net.alloymc.core.moderation.Punish(this, automod.store(), shared);
        // store supporter ranks: badge, trails, /store - on every server
        supporters = new Supporters(this, mode);
        switch (mode) {
            case "hub" -> hub = new HubModule(this);
            case "arcade" -> arcade = new ArcadeModule(this);
            case "survival" -> { survival = new SurvivalModule(this); Bukkit.getPluginManager().registerEvents(this, this); }
            case "skyblock" -> { skyblock = new SkyblockModule(this); Bukkit.getPluginManager().registerEvents(this, this); }
            case "factions" -> { factions = new net.alloymc.core.factions.FactionsModule(this); Bukkit.getPluginManager().registerEvents(this, this); }
            default -> Bukkit.getPluginManager().registerEvents(this, this);
        }
        if (survival != null || skyblock != null || factions != null) Bukkit.getPluginManager().registerEvents(new KillFeed(mode), this);
        if (arcade != null) supporters.setTrailBlocked(arcade::inGame);
        // /discord, chat tips, /report and the Discord feed - on every server
        new Community(this, mode, shared);
        if (survival != null || skyblock != null || factions != null || arcade != null) challenges = new Challenges(this, mode);
        new HallowEvent(this, mode, cosmetics);
        getLogger().info("AlloyCore enabled in " + mode + " mode");
    }

    @Override
    public void onDisable() {
        if (hub != null) hub.shutdown();
        if (arcade != null) arcade.shutdown();
        if (survival != null) survival.shutdown();
        if (skyblock != null) skyblock.shutdown();
        if (factions != null) factions.shutdown();
        if (challenges != null) challenges.save();
    }

    public Net net() { return net; }
    public NetEconomy economy() { return economy; }
    public SharedStats sharedStats() { return sharedStats; }
    public Cosmetics cosmetics() { return cosmetics; }

    @EventHandler
    public void onBackendJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        String name = getConfig().getString("display-name", mode);
        p.sendMessage(Ui.mm("<gray>Welcome to <gradient:#ffb347:#ff6a00><bold>BlackForgeMC</bold></gradient> <white>" + name
                + "<gray>. Type <gold>/hub</gold> to return to the lobby."));
        p.sendPlayerListHeaderAndFooter(Ui.mm("\n" + Ui.TITLE + "\n<gray>" + name + "\n"), Ui.mm("\n<gray>/hub to return to the lobby\n"));
        // e.g. skyblock sends players straight to (or creates) their island
        String cmd = getConfig().getString("join-command", "");
        if (!cmd.isEmpty()) Bukkit.getScheduler().runTaskLater(this, () -> { if (p.isOnline()) p.performCommand(cmd); }, 20L);
    }

    /**
     * Suggests /alloy's core subcommands. Only the first word is completed; everything else keeps the
     * default (online player names), and the survival/skyblock/factions subcommands still work when typed.
     */
    @Override
    public java.util.List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("alloy") || args.length != 1) return null;
        return java.util.stream.Stream.of("buildhub", "buildarena", "buildarcade", "spawn", "welcomeshow", "thanks")
                .filter(s -> s.startsWith(args[0].toLowerCase())).toList();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase()) {
            case "hub" -> {
                if (!(sender instanceof Player p)) return true;
                if (hub != null) hub.toSpawn(p);
                else if (arcade != null && arcade.inGame(p)) { arcade.leave(p); net.send(p, "hub"); }
                else net.send(p, "hub");
            }
            case "leave" -> {
                if (sender instanceof Player p && arcade != null) arcade.leave(p);
            }
            case "arcade" -> {
                if (arcade != null) arcade.command(sender, args);
                else if (sender instanceof Player p) net.send(p, "arcade");
            }
            case "alloy" -> {
                if (supporters.admin(sender, args)) return true;
                if (survival != null && survival.admin(sender, args)) return true;
                if (skyblock != null && skyblock.admin(sender, args)) return true;
                if (factions != null && factions.admin(sender, args)) return true;
                String sub = args.length > 0 ? args[0].toLowerCase() : "";
                switch (sub) {
                    case "buildhub" -> { if (hub != null) hub.build(sender); else sender.sendMessage(Component.text("Not a hub server.")); }
                    case "buildarena" -> { if (hub != null) hub.arena().build(sender); else sender.sendMessage(Component.text("Not a hub server.")); }
                    case "buildarcade" -> { if (arcade != null) arcade.buildAll(sender); else sender.sendMessage(Component.text("Not the arcade server.")); }
                    case "spawn" -> { if (sender instanceof Player p && hub != null) hub.toSpawn(p); }
                    case "restart" -> {   // /alloy restart <server|all|this> [now]: the host's auto-updater does the restart
                        String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);
                        RestartRequest.Parsed req = RestartRequest.parse(rest, mode);
                        if (req == null) {
                            sender.sendMessage(Component.text("/alloy restart <hub|skyblock|survival|factions|arcade|all|this> [now]"));
                            break;
                        }
                        try {
                            RestartRequest.write(getDataFolder(), req, sender.getName());
                            String what = String.join(", ", req.servers()) + (req.now() ? " (now, no countdown)" : " (countdown if players are on)");
                            sender.sendMessage(Component.text("Restart requested: " + what + ". The host picks it up within a few minutes."));
                            getLogger().info(sender.getName() + " requested a restart of " + what);
                            for (Player staff : Bukkit.getOnlinePlayers())
                                if (staff != sender && staff.hasPermission("alloy.admin")) staff.sendMessage(Component.text("[Staff] " + sender.getName() + " requested a restart: " + what));
                        } catch (Exception ex) {
                            sender.sendMessage(Component.text("Couldn't request a restart: " + ex.getMessage()));
                            getLogger().warning("restart request failed: " + ex);
                        }
                    }
                    case "welcomeshow" -> {   // replay the first-join show: /alloy welcomeshow [player]
                        Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : sender instanceof Player p ? p : null;
                        if (hub == null) sender.sendMessage(Component.text("Not a hub server."));
                        else if (target == null) sender.sendMessage(Component.text("Player not found."));
                        else if (!WelcomeShow.play(this, target)) sender.sendMessage(Component.text("A show is already playing for " + target.getName() + "."));
                        else sender.sendMessage(Component.text("Welcome show for " + target.getName() + " started (or queued if one is already playing)."));
                    }
                    default -> {
                        if (!sub.isEmpty()) sender.sendMessage(Component.text("Unknown subcommand: " + args[0]));
                        sender.sendMessage(Component.text("/alloy buildhub | buildarena | buildarcade | spawn | welcomeshow [player] | restart <server|all|this> [now]"));
                    }
                }
            }
        }
        return true;
    }
}
