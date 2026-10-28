package net.alloymc.core;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.util.*;
import java.util.function.Predicate;

/**
 * Store supporter ranks (Ember / Blaze / Inferno / Forgemaster). Cosmetic only, so they stay within
 * Mojang's server rules: a badge before your name, particle trails, exclusive name colours (NameTags)
 * and a hub join message. Membership is the LuckPerms group supporter_&lt;id&gt;, which grants
 * alloy.supporter.&lt;id&gt; and inherits the tier below. Runs on every server.
 */
public final class Supporters implements Listener {
    public record Tier(String id, String name, String color, String glyph) {
        public String badge() { return color + glyph + " "; }
    }

    public static final List<Tier> TIERS = List.of(
        new Tier("ember", "Ember", "<#ffb347>", "✦"),
        new Tier("blaze", "Blaze", "<#ff7a1f>", "✦"),
        new Tier("inferno", "Inferno", "<#ff3d2e>", "✹"),
        new Tier("forgemaster", "Forgemaster", "<gradient:#ffd27a:#ff4d00>", "⚒"));

    /** id, name, particle, menu icon, lowest tier that unlocks it (index into TIERS). */
    public record Trail(String id, String name, Particle particle, Material icon, int tier) {}

    public static final List<Trail> TRAILS = List.of(
        new Trail("flame", "Flame", Particle.FLAME, Material.BLAZE_POWDER, 0),
        new Trail("hearts", "Hearts", Particle.HEART, Material.POPPY, 0),
        new Trail("notes", "Notes", Particle.NOTE, Material.NOTE_BLOCK, 1),
        new Trail("cherry", "Cherry Petals", Particle.CHERRY_LEAVES, Material.CHERRY_SAPLING, 1),
        new Trail("soul", "Soul Fire", Particle.SOUL_FIRE_FLAME, Material.SOUL_LANTERN, 2),
        new Trail("sparks", "Sparks", Particle.ELECTRIC_SPARK, Material.LIGHTNING_ROD, 2),
        new Trail("snow", "Snowfall", Particle.SNOWFLAKE, Material.SNOWBALL, 3),
        new Trail("starlight", "Starlight", Particle.END_ROD, Material.END_ROD, 3),
        new Trail("totem", "Totem", Particle.TOTEM_OF_UNDYING, Material.TOTEM_OF_UNDYING, 3));

    public static final String STORE_URL = "https://blackforgemc.com/store";

    private final AlloyCore plugin;
    private final String mode;
    private final File file;
    private final Map<UUID, String> chosen = new HashMap<>();
    private final Map<UUID, Location> last = new HashMap<>();
    private Predicate<Player> trailBlocked = p -> false;

    Supporters(AlloyCore plugin, String mode) {
        this.plugin = plugin;
        this.mode = mode;
        File serverDir;
        try { serverDir = plugin.getServer().getWorldContainer().getCanonicalFile(); }
        catch (Exception e) { serverDir = plugin.getServer().getWorldContainer().getAbsoluteFile(); }
        File dir = new File(new File(serverDir.getParentFile(), "shared"), "supporters");
        dir.mkdirs();
        file = new File(dir, "trails.yml");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickTrails, 20L, 3L);
        registerCommands();
    }

    /** e.g. arcade: no trails while in a match or queue (they would give hiders away). */
    public void setTrailBlocked(Predicate<Player> rule) { this.trailBlocked = rule; }

    public static Tier tierOf(Player p) {
        for (int i = TIERS.size() - 1; i >= 0; i--) if (p.hasPermission("alloy.supporter." + TIERS.get(i).id())) return TIERS.get(i);
        return null;
    }

    /** Badge to put before a player's name ("" for non-supporters). */
    public static String badge(Player p) {
        Tier t = tierOf(p);
        return (Media.has(p) ? Media.BADGE : "") + (t == null ? "" : t.badge());
    }

    public static Tier byId(String id) { return TIERS.stream().filter(t -> t.id().equalsIgnoreCase(id)).findFirst().orElse(null); }

    private static Trail trail(String id) { return TRAILS.stream().filter(t -> t.id().equalsIgnoreCase(id)).findFirst().orElse(null); }

    public boolean canUse(Player p, Trail t) {
        if (p.hasPermission("alloy.admin") || p.hasPermission("alloy.trails.all")) return true;
        Tier tier = tierOf(p);
        return tier != null && TIERS.indexOf(tier) >= t.tier();
    }

    // ------------------------------------------------------------------ storage (shared by every server)

    private void load(UUID id) {
        String t = YamlConfiguration.loadConfiguration(file).getString(id.toString());
        if (t == null) chosen.remove(id); else chosen.put(id, t);
    }

    private void store(UUID id, String trail) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        y.set(id.toString(), trail);
        try { y.save(file); } catch (Exception e) { plugin.getLogger().warning("trails save failed: " + e.getMessage()); }
        if (trail == null) chosen.remove(id); else chosen.put(id, trail);
    }

    // ------------------------------------------------------------------ trails

    private void tickTrails() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Trail t = trail(chosen.getOrDefault(p.getUniqueId(), ""));
            Location now = p.getLocation();
            Location prev = last.put(p.getUniqueId(), now);
            if (t == null || prev == null || prev.getWorld() != now.getWorld() || prev.distanceSquared(now) < 0.01) continue;
            if (p.getGameMode() == GameMode.SPECTATOR || p.hasPotionEffect(PotionEffectType.INVISIBILITY) || !canUse(p, t) || trailBlocked.test(p)) continue;
            Location at = now.clone().add(0, t.particle() == Particle.HEART || t.particle() == Particle.NOTE ? 2.2 : 0.15, 0);
            double spread = t.particle() == Particle.CHERRY_LEAVES || t.particle() == Particle.SNOWFLAKE ? 0.3 : 0.15;
            now.getWorld().spawnParticle(t.particle(), at, 2, spread, 0.05, spread, 0.01);
        }
    }

    // ------------------------------------------------------------------ join / quit

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        load(p.getUniqueId());
        // survival and skyblock build names in NameTags (rank prefix + colour); everywhere else we add the badge here
        if (!mode.equals("survival") && !mode.equals("skyblock"))
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) return;
                Component name = Ui.mm(badge(p) + "<white>" + Nicks.of(p));
                p.displayName(name);
                p.playerListName(name);
            }, 10L);
        Tier t = tierOf(p);
        if (mode.equals("hub") && t != null && TIERS.indexOf(t) >= 1)
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) return;
                Bukkit.broadcast(Ui.mm(t.badge() + t.color() + "<bold>" + t.name() + "</bold> <gray>supporter <white>" + p.getName() + " <gray>joined the hub!"));
            }, 15L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        chosen.remove(e.getPlayer().getUniqueId());
        last.remove(e.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ /trails menu

    private static final class Menu implements InventoryHolder {
        Inventory inv;
        public Inventory getInventory() { return inv; }
    }

    public void open(Player p) {
        Menu m = new Menu();
        m.inv = Bukkit.createInventory(m, 27, Ui.mm("<dark_gray>Particle Trails"));
        String cur = chosen.get(p.getUniqueId());
        for (int i = 0; i < TRAILS.size(); i++) {
            Trail t = TRAILS.get(i);
            Tier need = TIERS.get(t.tier());
            boolean has = canUse(p, t);
            String state = t.id().equals(cur) ? "<green>✔ Active <gray>(click to turn off)"
                    : has ? "<yellow>» Click to use" : "<red>Needs " + need.color() + need.name() + "<red> supporter or the Trail Pack";
            ItemStack icon = Ui.item(has ? t.icon() : Material.GRAY_DYE, (has ? "<gold>" : "<gray>") + "<bold>" + t.name(),
                    "<gray>Unlocked by " + need.color() + need.name() + "<gray> and above", "", state);
            if (t.id().equals(cur)) icon.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
            m.inv.setItem(i, icon);
        }
        m.inv.setItem(22, Ui.item(Material.BARRIER, "<red>Turn trail off"));
        m.inv.setItem(26, Ui.item(Material.EMERALD, "<green>Get trails", "<gray>" + STORE_URL));
        p.openInventory(m.inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p) || e.getClickedInventory() != e.getInventory()) return;
        int slot = e.getRawSlot();
        if (slot == 22) { store(p.getUniqueId(), null); p.closeInventory(); p.sendMessage(Ui.mm("<gray>Trail turned off.")); return; }
        if (slot == 26) { p.closeInventory(); storeLink(p); return; }
        if (slot >= TRAILS.size()) return;
        Trail t = TRAILS.get(slot);
        if (t.id().equals(chosen.get(p.getUniqueId()))) { store(p.getUniqueId(), null); open(p); return; }
        if (!canUse(p, t)) { p.sendMessage(Ui.mm("<red>That trail is a supporter perk. <gray>See <white>/store")); return; }
        store(p.getUniqueId(), t.id());
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
        p.sendMessage(Ui.mm("<green>✔ Trail set to <gold>" + t.name() + "<green>."));
        open(p);
    }

    // ------------------------------------------------------------------ commands

    public void storeLink(CommandSender s) {
        s.sendMessage(Ui.mm("<gold><bold>BlackForgeMC Store</bold> <dark_gray>» <click:open_url:'" + STORE_URL + "'><hover:show_text:'<gray>Open the store'><underlined><#ffb347>" + STORE_URL + "</underlined></hover></click>"));
        s.sendMessage(Ui.mm("<gray>Supporter ranks and cosmetics only - nothing that gives an advantage in game."));
    }

    public void info(CommandSender s) {
        Tier mine = s instanceof Player p ? tierOf(p) : null;
        s.sendMessage(Ui.mm("<gold><bold>Supporter Ranks</bold> <gray>- yours: " + (mine == null ? "<white>none" : mine.color() + "<bold>" + mine.name())));
        s.sendMessage(Ui.mm("<#ffb347>✦ Ember <dark_gray>» <gray>badge, Ember name colour, 2 trails, /hat"));
        s.sendMessage(Ui.mm("<#ff7a1f>✦ Blaze <dark_gray>» <gray>+ Blaze colour, 4 trails, hub join message"));
        s.sendMessage(Ui.mm("<#ff3d2e>✹ Inferno <dark_gray>» <gray>+ Inferno colour, 6 trails"));
        s.sendMessage(Ui.mm("<gradient:#ffd27a:#ff4d00>⚒ Forgemaster</gradient> <dark_gray>» <gray>+ Forgemaster colour, every trail"));
        storeLink(s);
    }

    private void registerCommands() {
        reg("trails", List.of("trail", "particles"), (s, a) -> { if (s instanceof Player p) open(p); else s.sendMessage("Players only."); });
        reg("store", List.of("buy", "shop", "donate"), (s, a) -> storeLink(s));
        reg("supporter", List.of("supporters", "support"), (s, a) -> info(s));
    }

    private interface Cmd { void run(CommandSender s, String[] args); }

    private void reg(String name, List<String> aliases, Cmd cmd) {
        Bukkit.getCommandMap().register("alloy", new Command(name, "BlackForgeMC " + name, "/" + name, aliases) {
            @Override public boolean execute(CommandSender s, String label, String[] args) { cmd.run(s, args); return true; }
        });
    }

    /** Console: /alloy thanks &lt;player&gt; &lt;what&gt; - broadcast run by the store after a purchase. */
    public boolean admin(CommandSender s, String[] a) {
        if (a.length >= 3 && a[0].equalsIgnoreCase("thanks")) {
            String what = String.join(" ", Arrays.copyOfRange(a, 2, a.length));
            Bukkit.broadcast(Ui.mm("<gold>✦ <white>" + a[1] + " <gray>just supported the server with <gold>" + what + "<gray>! Thank you! <white>/store"));
            for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.4f, 1.2f);
            return true;
        }
        return false;
    }
}
