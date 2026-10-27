package net.alloymc.core.survival;

import net.alloymc.core.AlloyCore;
import net.alloymc.core.Ui;
import org.bukkit.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.VillagerAcquireTradeEvent;
import org.bukkit.event.entity.VillagerCareerChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

import static net.alloymc.core.survival.SurvivalLayout.*;

/**
 * The Trading Hall north of spawn: a villager in each stall with fixed, fair trades that never change or lock
 * up (they restock every 10 minutes). Only players with the Gold rank or higher (or staff) can go inside.
 */
final class TradingHall implements Listener {
    static final int MIN_RANK = 2;   // Ranks.RANKS index: copper, iron, gold, diamond, netherite
    private final AlloyCore plugin;
    private final World world;
    private final Ranks ranks;
    private final NamespacedKey tag;
    private final Map<UUID, Long> warned = new HashMap<>();

    TradingHall(AlloyCore plugin, World world, Ranks ranks) {
        this.plugin = plugin;
        this.world = world;
        this.ranks = ranks;
        this.tag = new NamespacedKey(plugin, "hall_villager");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::ensureVillagers, 100L, 1200L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::restock, 12000L, 12000L);
    }

    boolean allowed(Player p) {
        if (p.hasPermission("alloy.admin")) return true;
        Ranks.Rank r = ranks.of(p);
        return r != null && Ranks.RANKS.indexOf(r) >= MIN_RANK;
    }

    // ------------------------------------------------------------------ villagers

    /** Make sure every stall has exactly one of our villagers (spawn missing ones, remove duplicates). */
    void ensureVillagers() {
        if (!world.isChunkLoaded(0 >> 4, TH_Z1 >> 4) && Bukkit.getOnlinePlayers().isEmpty()) return;
        Map<Integer, Villager> found = new HashMap<>();
        Location c = new Location(world, 0, Y + 2, (TH_Z1 + TH_Z2) / 2.0);
        for (Entity e : world.getNearbyEntities(c, 20, 10, 14)) {
            if (!(e instanceof Villager v)) continue;
            Integer i = v.getPersistentDataContainer().get(tag, PersistentDataType.INTEGER);
            if (i == null) continue;
            if (i < 0 || i >= STALLS.size() || found.containsKey(i)) { v.remove(); continue; }
            found.put(i, v);
        }
        for (int i = 0; i < STALLS.size(); i++) {
            TradeStall st = STALLS.get(i);
            Location at = new Location(world, st.x() + 0.5, Y + 1, st.z() + 0.5, yaw(st.facing()), 0);
            Villager v = found.get(i);
            if (v == null) {
                int idx = i;
                v = world.spawn(at, Villager.class, nv -> setup(nv, idx));
            } else if (v.getLocation().distanceSquared(at) > 0.5) v.teleport(at);
            if (v.getRecipes().size() != trades(st.profession()).size()) v.setRecipes(trades(st.profession()));
        }
    }

    private void setup(Villager v, int i) {
        TradeStall st = STALLS.get(i);
        v.getPersistentDataContainer().set(tag, PersistentDataType.INTEGER, i);
        Villager.Profession prof = Registry.VILLAGER_PROFESSION.get(NamespacedKey.minecraft(st.profession()));
        if (prof != null) v.setProfession(prof);
        v.setVillagerLevel(5);
        v.setVillagerExperience(250);
        v.setAI(false);
        v.setInvulnerable(true);
        v.setPersistent(true);
        v.setRemoveWhenFarAway(false);
        v.setCollidable(false);
        v.setSilent(true);
        v.customName(Ui.mm("<gold>" + title(st.profession())));
        v.setCustomNameVisible(true);
        v.setRecipes(trades(st.profession()));
    }

    private static float yaw(String facing) {
        return switch (facing) { case "east" -> -90f; case "west" -> 90f; case "north" -> 180f; default -> 0f; };
    }

    private static String title(String prof) {
        return switch (prof) {
            case "librarian" -> "Librarian • Enchanted Books";
            case "armorer" -> "Armorer • Armor";
            case "toolsmith" -> "Toolsmith • Tools";
            case "weaponsmith" -> "Weaponsmith • Weapons";
            case "cleric" -> "Cleric • Pearls & XP";
            case "fletcher" -> "Fletcher • Bows & Arrows";
            case "farmer" -> "Farmer • Food";
            case "butcher" -> "Butcher • Meat";
            case "mason" -> "Mason • Building Blocks";
            default -> "Shepherd • Wool & Beds";
        };
    }

    private void restock() {
        Location c = new Location(world, 0, Y + 2, (TH_Z1 + TH_Z2) / 2.0);
        for (Entity e : world.getNearbyEntities(c, 20, 10, 14))
            if (e instanceof Villager v && v.getPersistentDataContainer().has(tag)) {
                List<MerchantRecipe> rs = new ArrayList<>(v.getRecipes());
                rs.forEach(r -> r.setUses(0));
                v.setRecipes(rs);
            }
    }

    // ------------------------------------------------------------------ trades

    private static MerchantRecipe sell(Material m, int n, int emeralds) { return sell(new ItemStack(m, n), emeralds); }

    private static MerchantRecipe sell(ItemStack it, int emeralds) {
        MerchantRecipe r = new MerchantRecipe(it, 0, 16, false, 0, 0f);
        if (emeralds > 64) { r.addIngredient(new ItemStack(Material.EMERALD, 64)); r.addIngredient(new ItemStack(Material.EMERALD, emeralds - 64)); }
        else r.addIngredient(new ItemStack(Material.EMERALD, emeralds));
        return r;
    }

    private static MerchantRecipe buy(Material m, int n, int emeralds) {
        MerchantRecipe r = new MerchantRecipe(new ItemStack(Material.EMERALD, emeralds), 0, 16, false, 0, 0f);
        r.addIngredient(new ItemStack(m, n));
        return r;
    }

    private static ItemStack book(Enchantment e, int level) {
        ItemStack it = new ItemStack(Material.ENCHANTED_BOOK);
        it.editMeta(EnchantmentStorageMeta.class, m -> m.addStoredEnchant(e, level, true));
        return it;
    }

    static List<MerchantRecipe> trades(String prof) {
        return switch (prof) {
            case "librarian" -> List.of(sell(book(Enchantment.MENDING, 1), 24), sell(book(Enchantment.UNBREAKING, 3), 14), sell(book(Enchantment.EFFICIENCY, 5), 18),
                    sell(book(Enchantment.FORTUNE, 3), 26), sell(book(Enchantment.SHARPNESS, 5), 18), sell(book(Enchantment.PROTECTION, 4), 16),
                    sell(book(Enchantment.LOOTING, 3), 22), sell(book(Enchantment.SILK_TOUCH, 1), 16), sell(book(Enchantment.FEATHER_FALLING, 4), 12),
                    sell(book(Enchantment.POWER, 5), 16), buy(Material.PAPER, 24, 1), buy(Material.BOOK, 4, 1));
            case "armorer" -> List.of(sell(Material.DIAMOND_HELMET, 1, 14), sell(Material.DIAMOND_CHESTPLATE, 1, 24), sell(Material.DIAMOND_LEGGINGS, 1, 20),
                    sell(Material.DIAMOND_BOOTS, 1, 12), sell(Material.IRON_CHESTPLATE, 1, 8), sell(Material.SHIELD, 1, 4), buy(Material.IRON_INGOT, 6, 1), buy(Material.COAL, 15, 1));
            case "toolsmith" -> List.of(sell(Material.DIAMOND_PICKAXE, 1, 16), sell(Material.DIAMOND_AXE, 1, 14), sell(Material.DIAMOND_SHOVEL, 1, 8),
                    sell(Material.DIAMOND_HOE, 1, 6), sell(Material.IRON_PICKAXE, 1, 5), buy(Material.COAL, 15, 1), buy(Material.FLINT, 26, 1));
            case "weaponsmith" -> List.of(sell(Material.DIAMOND_SWORD, 1, 14), sell(Material.IRON_SWORD, 1, 4), sell(Material.IRON_AXE, 1, 4),
                    sell(Material.BELL, 1, 10), buy(Material.IRON_INGOT, 6, 1), buy(Material.STICK, 32, 1));
            case "cleric" -> List.of(sell(Material.ENDER_PEARL, 4, 6), sell(Material.EXPERIENCE_BOTTLE, 8, 4), sell(Material.GLOWSTONE, 4, 2),
                    sell(Material.LAPIS_LAZULI, 6, 1), sell(Material.REDSTONE, 8, 1), buy(Material.ROTTEN_FLESH, 24, 1), buy(Material.GOLD_INGOT, 3, 1));
            case "fletcher" -> List.of(sell(Material.ARROW, 16, 1), sell(Material.BOW, 1, 3), sell(Material.CROSSBOW, 1, 4), sell(Material.SPECTRAL_ARROW, 8, 3),
                    buy(Material.STICK, 32, 1), buy(Material.STRING, 14, 1), buy(Material.FEATHER, 24, 1));
            case "farmer" -> List.of(sell(Material.BREAD, 6, 1), sell(Material.GOLDEN_CARROT, 8, 3), sell(Material.GLISTERING_MELON_SLICE, 4, 2), sell(Material.CAKE, 1, 2),
                    buy(Material.WHEAT, 20, 1), buy(Material.POTATO, 22, 1), buy(Material.CARROT, 15, 1), buy(Material.PUMPKIN, 6, 1));
            case "butcher" -> List.of(sell(Material.COOKED_PORKCHOP, 8, 1), sell(Material.COOKED_BEEF, 8, 1), sell(Material.COOKED_CHICKEN, 8, 1),
                    buy(Material.CHICKEN, 14, 1), buy(Material.PORKCHOP, 10, 1), buy(Material.BEEF, 10, 1));
            case "mason" -> List.of(sell(Material.POLISHED_ANDESITE, 16, 1), sell(Material.QUARTZ_BLOCK, 4, 1), sell(Material.TERRACOTTA, 8, 1),
                    sell(Material.DEEPSLATE_BRICKS, 16, 1), buy(Material.CLAY_BALL, 20, 1), buy(Material.STONE, 32, 1));
            default -> List.of(sell(Material.WHITE_WOOL, 8, 1), sell(Material.WHITE_BED, 1, 3), sell(Material.PAINTING, 2, 2), sell(Material.SHEARS, 1, 2),
                    buy(Material.WHITE_WOOL, 18, 1), buy(Material.STRING, 14, 1));
        };
    }

    // ------------------------------------------------------------------ the door

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        if (!e.hasChangedBlock() || e.getTo().getWorld() != world) return;
        Location to = e.getTo();
        if (!inTradingHall(to.getX(), to.getY(), to.getZ()) || allowed(e.getPlayer())) return;
        Player p = e.getPlayer();
        e.setCancelled(true);
        if (inTradingHall(p.getX(), p.getY(), p.getZ()))   // already inside somehow (rank changed, teleported in)
            p.teleport(new Location(world, TH_DOOR_X, Y + 1, TH_DOOR_Z, 0, 0));
        long now = System.currentTimeMillis();
        if (now - warned.getOrDefault(p.getUniqueId(), 0L) > 3000) {
            warned.put(p.getUniqueId(), now);
            p.sendMessage(Ui.mm("<gold>⚒ The Trading Hall <gray>is for <#ffc53d><bold>GOLD</bold><gray> rank and up. <white>/ranks <gray>to see how to rank up."));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1f);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onTrade(PlayerInteractEntityEvent e) {
        if (e.getRightClicked() instanceof Villager v && v.getPersistentDataContainer().has(tag) && !allowed(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler public void onNewTrade(VillagerAcquireTradeEvent e) { if (e.getEntity().getPersistentDataContainer().has(tag)) e.setCancelled(true); }
    @EventHandler public void onCareer(VillagerCareerChangeEvent e) { if (e.getEntity().getPersistentDataContainer().has(tag)) e.setCancelled(true); }
}
