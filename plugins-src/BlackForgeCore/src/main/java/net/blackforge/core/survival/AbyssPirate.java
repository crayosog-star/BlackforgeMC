package net.blackforge.core.survival;

import net.blackforge.core.BlackForgeCore;
import net.blackforge.core.Ui;
import net.blackforge.core.abyss.AbyssWorld;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.title.Title;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.time.Duration;
import java.util.*;

/**
 * Captain Salt-Eye, a pirate beside the auction bell in the market hall, and the way to the Abyssal Sea. There is no warp: he gossips about the
 * sea (how to survive it, what lives in it, where Atlantis hides), and if you bring him what a ship needs to go where the
 * lights end (24 prismarine shards, a heart of the sea, 12 glow ink sacs) he charts the route and will sail you out.
 * Progress is kept per player in abyss-quest.yml. Until abyss.public is true only staff can actually sail.
 */
final class AbyssPirate implements Listener {
    private static final int Y = SurvivalLayout.Y;
    private record Task(Material item, int need, String what, String why) {}
    private static final List<Task> TASKS = List.of(
            new Task(Material.PRISMARINE_SHARD, 24, "24 Prismarine Shards", "to caulk a hull that will sail where the water is thick with the dark"),
            new Task(Material.HEART_OF_THE_SEA, 1, "1 Heart of the Sea", "a compass that points down instead of north"),
            new Task(Material.GLOW_INK_SAC, 12, "12 Glow Ink Sacs", "lamp oil - the sea out there swallows an ordinary flame"));
    private static final String[] RUMORS = {
            "<gray>\"The Abyssal Sea. Black water, black sky, and the lamps of the dock are the last honest light ye'll see.\"",
            "<gray>\"Sail far from the dock and something takes notice. A coil on the horizon, then another - a ring, tightening. By the time ye see its scales, it has already chosen ye.\"",
            "<gray>\"Jörmungandr. Older than the sea it lives in. It comes up under the boat, not at it. Armour helps. Golden apples help more. A fast boat helps most.\"",
            "<gray>\"Hit the head. Nothing else out there bleeds. Four or five good swords make it flinch; a crowd makes it reconsider.\"",
            "<gray>\"The bone islands, with the ribs standing like arches? Those are what's left of the ones who didn't run. There's treasure under the skulls, if ye've the stomach.\"",
            "<gray>\"Lighthouses still burn out there. Keep to them and ye'll find buried gold, but a lit tower is a lure - never forget who lit it.\"",
            "<gray>\"Atlantis is real. A city of gold and prismarine on the sea floor, a long swim from anywhere. Bring water breathing and a good reason to be brave. Elders guard the vault.\"",
            "<gray>\"Whatever ye lose out there stays out there. Gear sinks. Bring only what ye can afford to feed the sea.\"",
            "<gray>\"To come home, say /spawn. The sea lets go of those who ask nicely - it only keeps the ones who don't make it back.\""};

    private final BlackForgeCore plugin;
    private final World world;
    private final AbyssWorld abyss;
    private final NamespacedKey tag;
    private final File file;
    private YamlConfiguration data;
    private final Map<UUID, Integer> rumor = new HashMap<>();

    AbyssPirate(BlackForgeCore plugin, World world, AbyssWorld abyss) {
        this.plugin = plugin;
        this.world = world;
        this.abyss = abyss;
        this.tag = new NamespacedKey(BlackForgeCore.NS, "abyss_pirate");
        this.file = new File(plugin.getDataFolder(), "abyss-quest.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::ensure, 260L, 600L);
        Bukkit.getCommandMap().register("blackforge", new Command("bfpirate", "Talk to the pirate", "/bfpirate", List.of()) {
            @Override public boolean execute(org.bukkit.command.CommandSender s, String label, String[] a) {
                if (s instanceof Player p) command(p, a.length > 0 ? a[0].toLowerCase() : "");
                return true;
            }
        });
    }

    private Location spot() {
        var c = plugin.getConfig();
        return new Location(world, c.getDouble("abyss.pirate-x", SurvivalLayout.BELL_X - 4.5), c.getDouble("abyss.pirate-y", Y + 1), c.getDouble("abyss.pirate-z", SurvivalLayout.BELL_Z - 0.5), 180f, 0f);
    }

    private Mannequin find() {
        for (Entity e : world.getNearbyEntities(spot(), 6, 6, 6)) if (e instanceof Mannequin v && v.getPersistentDataContainer().has(tag, PersistentDataType.STRING)) return v;
        return null;
    }

    private void ensure() {
        if (!new File(plugin.getDataFolder(), "spawn-v2").exists()) return;   // only once the spawn is built
        Location at = spot();
        world.getChunkAt(at).load();
        Mannequin found = null;
        for (Entity e : world.getNearbyEntities(at, 6, 6, 6)) {
            if (!e.getPersistentDataContainer().has(tag, PersistentDataType.STRING)) continue;
            if (e instanceof Villager) { e.remove(); continue; }   // the old villager stand-in, before the custom skin
            if (!(e instanceof Mannequin m)) continue;
            if (found == null) found = m; else m.remove();
        }
        if (found != null) return;
        world.spawn(at, Mannequin.class, m -> {
            m.getPersistentDataContainer().set(tag, PersistentDataType.STRING, "pirate");
            m.setProfile(ResolvableProfile.resolvableProfile().skinPatch(sp -> sp.body(Key.key("blackforge", "entity/pirate")).model(PlayerTextures.SkinModel.CLASSIC)).build());
            m.setDescription(Ui.mm("<gray>Sea-grey eyes, salt-white beard"));
            m.setImmovable(true);
            m.setInvulnerable(true);
            m.setPersistent(true);
            m.setRemoveWhenFarAway(false);
            m.setCollidable(false);
            m.setSilent(true);
            m.customName(Ui.mm("<#2bd9a5>Captain Salt-Eye"));
            m.setCustomNameVisible(true);
        });
    }

    // ------------------------------------------------------------------ talking

    private int stage(Player p) { return data.getInt(p.getUniqueId() + ".stage", 0); }
    private int got(Player p, int i) { return data.getInt(p.getUniqueId() + ".t" + i, 0); }
    private void save() { try { data.save(file); } catch (Exception e) { plugin.getLogger().warning("abyss quest save failed: " + e); } }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent e) {
        if (!(e.getRightClicked() instanceof Mannequin v) || !v.getPersistentDataContainer().has(tag, PersistentDataType.STRING)) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (stage(p) == 1) {
            ItemStack hand = p.getInventory().getItemInMainHand();
            for (int i = 0; i < TASKS.size(); i++) if (hand.getType() == TASKS.get(i).item() && got(p, i) < TASKS.get(i).need()) { deliver(p, hand, i); return; }
        }
        menu(p);
    }

    private static String btn(String label, String cmd, String color) { return "<" + color + "><click:run_command:'/bfpirate " + cmd + "'><u>[" + label + "]</u></click>"; }

    private void menu(Player p) {
        int st = stage(p);
        p.sendMessage(Ui.mm(" "));
        p.sendMessage(Ui.mm("<#2bd9a5><bold>Captain Salt-Eye</bold> <dark_gray>- a pirate with sea-grey eyes"));
        if (st == 0) {
            p.sendMessage(Ui.mm("<gray>\"Ye've the look of someone who wants to see what's past the lights. Most don't ask. Most don't come back.\""));
            p.sendMessage(Ui.mm("  " + btn("Ask about the sea", "rumor", "aqua") + "  " + btn("Offer to chart the route", "begin", "gold")));
        } else if (st == 1) {
            p.sendMessage(Ui.mm("<gray>\"Bring me what the ship needs, and hold it in yer hand when ye speak to me.\""));
            status(p);
            p.sendMessage(Ui.mm("  " + btn("Ask about the sea", "rumor", "aqua")));
        } else {
            p.sendMessage(Ui.mm("<gray>\"The Black Gull's ready, and so's the chart. Say the word.\""));
            p.sendMessage(Ui.mm("  " + btn("Set sail", "sail", "green") + "  " + btn("Ask about the sea", "rumor", "aqua")));
        }
    }

    private void status(Player p) {
        for (int i = 0; i < TASKS.size(); i++) {
            Task t = TASKS.get(i);
            int g = got(p, i);
            p.sendMessage(Ui.mm((g >= t.need() ? "<green>✔ " : "<yellow>• ") + "<white>" + t.what() + " <gray>(" + Math.min(g, t.need()) + "/" + t.need() + ") <dark_gray>- " + t.why()));
        }
    }

    private void deliver(Player p, ItemStack hand, int i) {
        Task t = TASKS.get(i);
        int take = Math.min(hand.getAmount(), t.need() - got(p, i));
        hand.setAmount(hand.getAmount() - take);
        data.set(p.getUniqueId() + ".t" + i, got(p, i) + take);
        save();
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_YES, 1f, 0.8f);
        p.sendMessage(Ui.mm("<#2bd9a5>Captain Salt-Eye: <gray>\"Aye, " + take + " " + t.item().name().toLowerCase().replace('_', ' ') + ". " + (got(p, i) >= t.need() ? "That'll do for that." : "Keep them coming.") + "\""));
        for (int k = 0; k < TASKS.size(); k++) if (got(p, k) < TASKS.get(k).need()) return;
        data.set(p.getUniqueId() + ".stage", 2);
        save();
        ItemStack chart = new ItemStack(Material.PAPER);
        ItemMeta m = chart.getItemMeta();
        m.displayName(Ui.mm("<#2bd9a5><bold>Abyssal Chart"));
        m.lore(List.of(Ui.mm("<gray>A route drawn by Captain Salt-Eye."), Ui.mm("<gray>It ends where the map does."), Ui.mm("<dark_gray>Charted by " + p.getName())));
        chart.setItemMeta(m);
        p.getInventory().addItem(chart).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        p.sendMessage(Ui.mm("<#2bd9a5>Captain Salt-Eye: <gray>\"That's the lot. The Black Gull's yers to ride - and the chart's yers to keep.\""));
        p.sendMessage(Ui.mm("  " + btn("Set sail", "sail", "green")));
        p.showTitle(Title.title(Ui.mm("<#2bd9a5><bold>ROUTE CHARTED"), Ui.mm("<gray>Speak to the captain to set sail"), Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofSeconds(1))));
    }

    // ------------------------------------------------------------------ /bfpirate

    private void command(Player p, String sub) {
        switch (sub) {
            case "rumor" -> {
                int i = rumor.merge(p.getUniqueId(), 1, Integer::sum) - 1;
                p.sendMessage(Ui.mm("<#2bd9a5>Captain Salt-Eye: " + RUMORS[Math.floorMod(i, RUMORS.length)]));
                p.sendMessage(Ui.mm("  " + btn("Another rumor", "rumor", "aqua")));
            }
            case "begin" -> {
                if (stage(p) >= 1) { menu(p); return; }
                data.set(p.getUniqueId() + ".stage", 1);
                save();
                p.sendMessage(Ui.mm("<#2bd9a5>Captain Salt-Eye: <gray>\"Hah. Brave, or daft. A ship can't sail the Abyssal Sea on hope. I'll need:\""));
                status(p);
                p.sendMessage(Ui.mm("<gray>\"Hold what I ask for and speak to me - I'll take what's owed.\""));
            }
            case "status" -> status(p);
            case "sail" -> {
                if (stage(p) < 2) { menu(p); return; }
                if (!near(p)) { p.sendMessage(Ui.mm("<gray>Find Captain Salt-Eye in the market hall, by the auction bell, to set sail.")); return; }
                p.sendMessage(Ui.mm("<#2bd9a5>Captain Salt-Eye: <red>\"Last chance to think it over.\" <gray>Whatever ye lose out there stays out there, and something very large lives past the lights."));
                p.sendMessage(Ui.mm("  " + btn("I'm ready - set sail", "confirm", "green")));
            }
            case "confirm" -> {
                if (stage(p) < 2 || !near(p)) { menu(p); return; }
                if (!abyss.open(p)) { p.sendMessage(Ui.mm("<#2bd9a5>Captain Salt-Eye: <gray>\"The Black Gull's still being fitted out. Soon.\"")); return; }
                p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 70, 0, false, false, false));
                p.showTitle(Title.title(Ui.mm("<dark_aqua><bold>SETTING SAIL"), Ui.mm("<gray>The lights fall behind you..."), Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(800))));
                p.playSound(p.getLocation(), Sound.AMBIENT_UNDERWATER_ENTER, 1f, 0.6f);
                p.playSound(p.getLocation(), Sound.ENTITY_BOAT_PADDLE_WATER, 1f, 0.7f);
                Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) abyss.enter(p); }, 60L);
            }
            default -> menu(p);
        }
    }

    private boolean near(Player p) {
        Mannequin v = find();
        return v != null && v.getWorld() == p.getWorld() && v.getLocation().distance(p.getLocation()) < 14;
    }

    void shutdown() { Mannequin v = find(); if (v != null) v.remove(); }
}
