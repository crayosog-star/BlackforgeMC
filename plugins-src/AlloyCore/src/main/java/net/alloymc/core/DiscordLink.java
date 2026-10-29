package net.alloymc.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.Consumer;

/**
 * Discord account linking, the game half. /link gives a 6-character code; typing /link &lt;code&gt; in the BlackForgeMC
 * Discord (the bot in tools/discord_bot.py) links the accounts, records it in shared/discord/links.json and gives the
 * player their rank roles there. The first time a linked player is seen in game they're paid once ($1,000 + 50 Scrap).
 *
 * For the bot's role sync every server writes shared/discord/players/&lt;uuid&gt;.json on join: name, supporter tier
 * and (on Survival) earned rank. The files are JSON (the bot is plain Python) and changed under the same lock files
 * the bot uses.
 */
public final class DiscordLink implements Listener {
    static final long CODE_MS = 10 * 60_000L;
    static final double MONEY = 1000;
    static final long SCRAP = 50;
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final AlloyCore plugin;
    private final File dir, codes, links, paid;
    private final SecureRandom rnd = new SecureRandom();
    private java.util.function.Function<Player, String> rank = p -> null;   // Survival supplies the earned rank

    public DiscordLink(AlloyCore plugin, String mode, File shared) {
        this.plugin = plugin;
        this.dir = new File(shared, "discord");
        this.codes = new File(dir, "codes.json");
        this.links = new File(dir, "links.json");
        this.paid = new File(dir, "paid.json");   // ours alone: the bot never writes it
        new File(dir, "players").mkdirs();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> { for (Player p : Bukkit.getOnlinePlayers()) reward(p); }, 200L, 200L);
        Bukkit.getCommandMap().register("alloy", new Command("link", "Link your Discord account", "/link", List.of("discordlink", "verify")) {
            @Override public boolean execute(CommandSender s, String label, String[] a) {
                if (s instanceof Player p) link(p); else s.sendMessage("Players only.");
                return true;
            }
        });
    }

    public void setRank(java.util.function.Function<Player, String> rank) { this.rank = rank; }

    // ------------------------------------------------------------------ JSON files shared with the bot

    static JsonObject read(File f) {
        try { return f.exists() ? GSON.fromJson(Files.readString(f.toPath(), StandardCharsets.UTF_8), JsonObject.class) : new JsonObject(); }
        catch (Exception e) { return new JsonObject(); }
    }

    static synchronized void update(File f, Consumer<JsonObject> change) {
        f.getParentFile().mkdirs();
        try (RandomAccessFile raf = new RandomAccessFile(new File(f.getPath() + ".lock"), "rw"); FileChannel ch = raf.getChannel(); FileLock ignored = ch.lock()) {
            JsonObject o = read(f);
            if (o == null) o = new JsonObject();
            change.accept(o);
            Files.writeString(f.toPath(), GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("discord file " + f.getName() + ": " + e.getMessage(), e);
        }
    }

    /** True once this player has been paid for linking (achievements check it on join). */
    public static boolean linkedAndPaid(File shared, UUID id) { return read(new File(shared, "discord/paid.json")).has(id.toString()); }

    // ------------------------------------------------------------------ /link

    private void link(Player p) {
        JsonObject mine = read(links).getAsJsonObject(p.getUniqueId().toString());
        if (mine != null && mine.has("discord_name")) {
            p.sendMessage(Ui.mm("<#8c9eff>✦ <gray>Your account is linked to <white>" + mine.get("discord_name").getAsString() + "<gray> on Discord."));
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) sb.append(ALPHABET.charAt(rnd.nextInt(ALPHABET.length())));
        String code = sb.toString();
        long now = System.currentTimeMillis();
        update(codes, o -> {
            for (String k : new ArrayList<>(o.keySet())) {
                JsonObject c = o.getAsJsonObject(k);
                if (now - c.get("at").getAsLong() > CODE_MS || p.getUniqueId().toString().equals(c.get("uuid").getAsString())) o.remove(k);
            }
            JsonObject c = new JsonObject();
            c.addProperty("uuid", p.getUniqueId().toString());
            c.addProperty("name", p.getName());
            c.addProperty("at", now);
            o.add(code, c);
        });
        p.sendMessage(Ui.mm("<#8c9eff>✦ <white><bold>Link your Discord</bold>"));
        p.sendMessage(Ui.mm("<gray>  1. Join the Discord: <u><click:open_url:'" + Community.DEFAULT_INVITE + "'><#8c9eff>" + Community.DEFAULT_INVITE + "</click></u>"));
        p.sendMessage(Ui.mm("<gray>  2. In any channel type <white>/link " + code + " <dark_gray>(code works for 10 minutes)"));
        p.sendMessage(Ui.mm("<gray>  You get your rank roles there, plus <green>" + NetEconomy.money(MONEY) + " <gray>and <#c6a15b>" + SCRAP + " Scrap <gray>here."));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            File f = new File(dir, "players/" + p.getUniqueId() + ".json");
            Supporters.Tier t = Supporters.tierOf(p);
            String r = rank.apply(p);
            update(f, o -> {
                o.addProperty("name", p.getName());
                if (t != null) o.addProperty("supporter", t.name()); else o.remove("supporter");
                if (r != null) o.addProperty("rank", r);
                o.addProperty("updated", System.currentTimeMillis());
            });
            reward(p);
        }, 40L);
    }

    /** Linked on Discord but not paid yet: pay once (and let achievements know). */
    private void reward(Player p) {
        String k = p.getUniqueId().toString();
        JsonObject l = read(links).getAsJsonObject(k);
        if (l == null || !l.has("discord_id") || read(paid).has(k)) return;
        update(paid, o -> o.addProperty(k, System.currentTimeMillis()));
        plugin.economy().deposit(p, MONEY);
        plugin.economy().addScrap(p, SCRAP);
        Hooks.statMax(p, "discord", 1);
        p.sendMessage(Ui.mm("<#8c9eff>✦ <gray>Discord linked as <white>" + (l.has("discord_name") ? l.get("discord_name").getAsString() : "?") + "<gray>! <green>+"
                + NetEconomy.money(MONEY) + " <gray>and <#c6a15b>+" + SCRAP + " Scrap"));
    }
}
