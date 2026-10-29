package net.alloymc.core;

import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;

import java.io.File;
import java.util.*;

/**
 * /cosmetics: kill effects (when you kill a player anywhere on the network) and victory effects (when you win an
 * Arcade game), bought with Scrap. Purely visual - nothing here changes how strong anyone is. Owned and equipped
 * cosmetics follow the player across servers in shared/cosmetics/&lt;uuid&gt;.yml. Some can't be bought, only earned
 * (event rewards such as the Hallowforge Pumpkin Burst).
 */
public final class Cosmetics implements Listener {
    public enum Kind { KILL, VICTORY }

    public record Cosmetic(String id, Kind kind, String name, Material icon, int price, String lore) {}

    public static final List<Cosmetic> ALL = List.of(
            new Cosmetic("flames", Kind.KILL, "<#ff7a1a>Flame Burst", Material.BLAZE_POWDER, 300, "A burst of fire where they fell"),
            new Cosmetic("hearts", Kind.KILL, "<#ff6b9d>Heartbreaker", Material.PINK_DYE, 300, "Hearts float up from the victim"),
            new Cosmetic("souls", Kind.KILL, "<#4fd1c5>Soul Harvest", Material.SOUL_LANTERN, 600, "Their soul rises out of the body"),
            new Cosmetic("lightning", Kind.KILL, "<yellow>Thunderclap", Material.LIGHTNING_ROD, 900, "A harmless bolt of lightning"),
            new Cosmetic("anvil", Kind.KILL, "<gray>Forge Strike", Material.ANVIL, 1200, "An anvil clang and a shower of sparks"),
            new Cosmetic("pumpkin", Kind.KILL, "<gold>Pumpkin Burst", Material.JACK_O_LANTERN, -1, "Hallowforge event exclusive"),
            new Cosmetic("frost", Kind.KILL, "<#9fe6ff>Frost Nova", Material.BLUE_ICE, -1, "Frostforge event exclusive"),
            new Cosmetic("forgemaster", Kind.KILL, "<gradient:#ffd27a:#ff4d00>Forge Master", Material.ANVIL, -1, "Unlock " + Achievements.FORGE_MASTER_AT + " achievements"),
            new Cosmetic("fireworks", Kind.VICTORY, "<aqua>Firework Show", Material.FIREWORK_ROCKET, 500, "A volley of fireworks when you win"),
            new Cosmetic("roar", Kind.VICTORY, "<dark_purple>Dragon's Roar", Material.DRAGON_HEAD, 1000, "A dragon roars for your victory"),
            new Cosmetic("goldrain", Kind.VICTORY, "<gold>Gold Rain", Material.GOLD_INGOT, 1500, "It rains gold around you"),
            new Cosmetic("totem", Kind.VICTORY, "<green>Totem of Glory", Material.TOTEM_OF_UNDYING, 2000, "The totem animation, but for winning"),
            new Cosmetic("completionist", Kind.VICTORY, "<gradient:#ffd27a:#ff4d00>Completionist", Material.NETHER_STAR, -1, "Unlock every achievement"));

    private final AlloyCore plugin;
    private final File dir;

    public Cosmetics(AlloyCore plugin, File shared) {
        this.plugin = plugin;
        this.dir = new File(shared, "cosmetics");
        dir.mkdirs();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Hooks.onKill((killer, victim, where) -> {
            String id = equipped(killer, Kind.KILL);
            if (id != null) killEffect(id, victim.getLocation());
        });
        Hooks.onArcadeEnd((p, game, won) -> {
            String id = equipped(p, Kind.VICTORY);
            if (won && id != null) Bukkit.getScheduler().runTaskLater(plugin, () -> { if (p.isOnline()) victory(id, p); }, 10L);
        });
        Bukkit.getCommandMap().register("alloy", new Command("cosmetics", "BlackForgeMC cosmetics", "/cosmetics", List.of("cosmetic", "effects")) {
            @Override public boolean execute(CommandSender s, String label, String[] a) {
                if (s instanceof Player p) open(p); else s.sendMessage("Players only.");
                return true;
            }
        });
    }

    public static Cosmetic byId(String id) { for (Cosmetic c : ALL) if (c.id.equals(id)) return c; return null; }

    // ------------------------------------------------------------------ storage

    private File file(UUID u) { return new File(dir, u + ".yml"); }
    private YamlConfiguration load(UUID u) { return YamlConfiguration.loadConfiguration(file(u)); }
    private void save(UUID u, YamlConfiguration y) { try { y.save(file(u)); } catch (Exception ignored) { } }

    public boolean owns(Player p, String id) { return load(p.getUniqueId()).getStringList("owned").contains(id); }

    /** Give a cosmetic for free (event rewards). */
    public void unlock(Player p, String id) {
        YamlConfiguration y = load(p.getUniqueId());
        List<String> owned = new ArrayList<>(y.getStringList("owned"));
        if (!owned.contains(id)) owned.add(id);
        y.set("owned", owned);
        save(p.getUniqueId(), y);
    }

    private String equipped(Player p, Kind k) { return load(p.getUniqueId()).getString("equipped." + k.name().toLowerCase()); }

    // ------------------------------------------------------------------ menu

    private static final class Menu implements InventoryHolder {
        Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    public void open(Player p) {
        Menu m = new Menu();
        m.inv = Bukkit.createInventory(m, 27, Ui.mm("<dark_gray>Cosmetics <gray>- " + plugin.economy().scrap(p.getUniqueId()) + " Scrap"));
        YamlConfiguration y = load(p.getUniqueId());
        List<String> owned = y.getStringList("owned");
        int kill = 0, vic = 18;
        for (Cosmetic c : ALL) {
            boolean has = owned.contains(c.id), on = c.id.equals(y.getString("equipped." + c.kind.name().toLowerCase()));
            List<String> lore = new ArrayList<>();
            lore.add("<gray>" + c.lore);
            lore.add("<dark_gray>" + (c.kind == Kind.KILL ? "Kill effect - plays when you kill a player" : "Victory effect - plays when you win an Arcade game"));
            lore.add("");
            if (on) lore.add("<green>✔ Equipped <gray>- click to take it off");
            else if (has) lore.add("<yellow>Click to equip");
            else if (c.price < 0) lore.add("<red>Event exclusive - can't be bought");
            else lore.add("<gray>Price: <#c6a15b>" + c.price + " Scrap <gray>- click to buy");
            ItemStack it = Ui.item(c.icon, c.name + (on ? " <green>(equipped)" : ""), lore.toArray(new String[0]));
            if (on) it.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
            m.inv.setItem(c.kind == Kind.KILL ? kill++ : vic++, it);
        }
        m.inv.setItem(13, Ui.item(Material.BOOK, "<white>Cosmetics", "<gray>Top row: kill effects", "<gray>Bottom row: Arcade victory effects",
                "<gray>Earn Scrap from Arcade wins, kills, /daily and challenges."));
        p.openInventory(m.inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu) || !(e.getWhoClicked() instanceof Player p)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getSlot();
        List<Cosmetic> kills = ALL.stream().filter(c -> c.kind == Kind.KILL).toList(), vics = ALL.stream().filter(c -> c.kind == Kind.VICTORY).toList();
        Cosmetic c = slot < kills.size() ? kills.get(slot) : slot >= 18 && slot - 18 < vics.size() ? vics.get(slot - 18) : null;
        if (c == null) return;
        YamlConfiguration y = load(p.getUniqueId());
        List<String> owned = new ArrayList<>(y.getStringList("owned"));
        String key = "equipped." + c.kind.name().toLowerCase();
        if (owned.contains(c.id)) {
            y.set(key, c.id.equals(y.getString(key)) ? null : c.id);
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.4f);
        } else {
            if (c.price < 0) { p.sendMessage(Ui.mm("<red>That one is an event exclusive.")); return; }
            if (!plugin.economy().takeScrap(p, c.price)) { p.sendMessage(Ui.mm("<red>You need <#c6a15b>" + c.price + " Scrap<red> for that.")); return; }
            owned.add(c.id);
            y.set("owned", owned);
            y.set(key, c.id);
            p.sendMessage(Ui.mm("<green>Unlocked and equipped " + c.name + "<green>!"));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
            if (c.kind == Kind.KILL) killEffect(c.id, p.getLocation().add(p.getLocation().getDirection().multiply(2)));
            else victory(c.id, p);
        }
        save(p.getUniqueId(), y);
        open(p);
    }

    // ------------------------------------------------------------------ effects

    public void killEffect(String id, Location at) {
        World w = at.getWorld();
        Location c = at.clone().add(0, 1, 0);
        switch (id) {
            case "flames" -> { w.spawnParticle(Particle.FLAME, c, 60, 0.3, 0.6, 0.3, 0.08); w.playSound(c, Sound.ITEM_FIRECHARGE_USE, 1f, 0.8f); }
            case "hearts" -> { w.spawnParticle(Particle.HEART, c, 12, 0.5, 0.6, 0.5, 0); w.playSound(c, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 2f); }
            case "souls" -> {
                w.spawnParticle(Particle.SOUL, c, 25, 0.3, 0.5, 0.3, 0.05);
                w.spawnParticle(Particle.SCULK_SOUL, c, 10, 0.2, 0.8, 0.2, 0.08);
                w.playSound(c, Sound.PARTICLE_SOUL_ESCAPE, 1.5f, 0.8f);
            }
            case "lightning" -> w.strikeLightningEffect(at);
            case "anvil" -> {
                w.spawnParticle(Particle.LAVA, c, 15, 0.3, 0.3, 0.3, 0);
                w.spawnParticle(Particle.FIREWORK, c, 30, 0.2, 0.4, 0.2, 0.15);
                w.playSound(c, Sound.BLOCK_ANVIL_LAND, 0.8f, 1.2f);
            }
            case "pumpkin" -> {
                w.spawnParticle(Particle.BLOCK, c, 50, 0.4, 0.5, 0.4, Material.CARVED_PUMPKIN.createBlockData());
                w.spawnParticle(Particle.DUST, c, 30, 0.5, 0.6, 0.5, new Particle.DustOptions(Color.ORANGE, 1.6f));
                w.playSound(c, Sound.ENTITY_WITCH_CELEBRATE, 1f, 1f);
            }
            case "frost" -> {
                w.spawnParticle(Particle.SNOWFLAKE, c, 70, 0.2, 0.3, 0.2, 0.25);
                w.spawnParticle(Particle.BLOCK, c, 40, 0.4, 0.5, 0.4, Material.BLUE_ICE.createBlockData());
                w.playSound(c, Sound.BLOCK_GLASS_BREAK, 1f, 0.7f);
                w.playSound(c, Sound.BLOCK_POWDER_SNOW_BREAK, 1f, 0.6f);
            }
            case "forgemaster" -> {
                w.spawnParticle(Particle.LAVA, c, 20, 0.3, 0.3, 0.3, 0);
                w.spawnParticle(Particle.DUST, c, 50, 0.5, 0.8, 0.5, new Particle.DustOptions(Color.fromRGB(255, 160, 40), 1.8f));
                w.spawnParticle(Particle.FLAME, c, 40, 0.1, 0.6, 0.1, 0.12);
                w.playSound(c, Sound.BLOCK_ANVIL_USE, 0.9f, 0.6f);
                w.playSound(c, Sound.ENTITY_BLAZE_SHOOT, 0.8f, 0.7f);
            }
            default -> {}
        }
    }

    public void victory(String id, Player p) {
        World w = p.getWorld();
        Location l = p.getLocation();
        switch (id) {
            case "fireworks" -> {
                for (int i = 0; i < 4; i++) {
                    int k = i;
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (!p.isOnline()) return;
                        Location at = p.getLocation().add(Math.cos(k * 1.57) * 2, 1, Math.sin(k * 1.57) * 2);
                        w.spawn(at, Firework.class, fw -> {
                            FireworkMeta m = fw.getFireworkMeta();
                            m.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.STAR).withColor(Color.ORANGE, Color.YELLOW).withFade(Color.RED).trail(true).build());
                            m.setPower(1);
                            fw.setFireworkMeta(m);
                        });
                    }, i * 8L);
                }
            }
            case "roar" -> {
                w.playSound(l, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.5f, 1f);
                w.spawnParticle(Particle.DRAGON_BREATH, l.clone().add(0, 1, 0), 120, 1.2, 0.6, 1.2, 0.02);
            }
            case "goldrain" -> {
                for (int i = 0; i < 20; i++) Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (p.isOnline()) w.spawnParticle(Particle.ITEM, p.getLocation().add(0, 4, 0), 12, 2, 0.3, 2, 0.02, new ItemStack(Material.GOLD_NUGGET));
                }, i * 3L);
                w.playSound(l, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 0.8f);
            }
            case "totem" -> { w.spawnParticle(Particle.TOTEM_OF_UNDYING, l.clone().add(0, 1, 0), 120, 0.5, 1, 0.5, 0.5); w.playSound(l, Sound.ITEM_TOTEM_USE, 0.8f, 1.1f); }
            case "completionist" -> {
                // a ring of forge-fire that climbs around you, then a burst of stars
                for (int i = 0; i < 24; i++) {
                    int k = i;
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (!p.isOnline()) return;
                        Location at = p.getLocation().add(Math.cos(k * 0.52) * 1.4, k * 0.12, Math.sin(k * 0.52) * 1.4);
                        w.spawnParticle(Particle.FLAME, at, 4, 0.05, 0.05, 0.05, 0.01);
                        w.spawnParticle(Particle.DUST, at, 3, 0.05, 0.05, 0.05, new Particle.DustOptions(Color.fromRGB(255, 210, 120), 1.4f));
                    }, k);
                }
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (!p.isOnline()) return;
                    w.spawnParticle(Particle.END_ROD, p.getLocation().add(0, 3, 0), 80, 0.3, 0.3, 0.3, 0.25);
                    w.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
                }, 26L);
            }
            default -> {}
        }
    }
}
