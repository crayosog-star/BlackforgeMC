package net.blackforge.core.survival;

import net.blackforge.core.BlackForgeCore;
import net.blackforge.core.Ui;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.util.List;
import java.util.UUID;

/**
 * Copper / Iron / Gold / Netherite ranks. Membership lives in LuckPerms groups (each grants
 * blackforge.rank.&lt;id&gt; and inherits the rank below); BlackForgeCore turns that into prefixes and perks.
 */
public final class Ranks implements Listener {
    /**
     * keys = the crate keys in the 10-day /kit (with the rank tag). fly = /fly in your claims / on your island.
     * repair = /repairhand every 2 hours. Every rank also has everything from the ranks below it.
     */
    public record Rank(String id, String name, String color, int homes, int bonusClaims, double scrapBoost, String[][] keys, boolean fly, boolean repair, List<String> perks) {
        public String prefix() { return color + "<bold>" + name.toUpperCase() + "</bold> "; }
    }

    public static final long REPAIR_COOLDOWN = 2 * 3_600_000L;

    public static final List<Rank> RANKS = List.of(
        new Rank("copper", "Copper", "<#e07a45>", 4, 10, 0.10, new String[][]{{"iron", "1"}}, false, false,
            List.of("Copper prefix + Copper name tag", "/kit every 10 days: Copper tag + 1 Cinder Key", "4 homes", "+10 claim chunks", "/hat and /craft", "+10% Scrap")),
        new Rank("iron", "Iron", "<#d9dde3>", 6, 20, 0.20, new String[][]{{"iron", "2"}}, true, false,
            List.of("Iron prefix + Iron name tag", "/kit every 10 days: Iron tag + 2 Cinder Keys", "6 homes", "+20 claim chunks", "/fly in your claims or on your island", "/enderchest and /feed", "+20% Scrap")),
        new Rank("gold", "Gold", "<#ffc53d>", 9, 35, 0.30, new String[][]{{"gold", "1"}}, true, true,
            List.of("Gold prefix + Gold name tag", "/kit every 10 days: Gold tag + 1 Molten Key", "9 homes", "+35 claim chunks", "/fly in your claims or on your island", "/repairhand every 2 hours", "/anvil", "+30% Scrap")),
        new Rank("diamond", "Diamond", "<#5fe0e0>", 13, 55, 0.40, new String[][]{{"gold", "2"}}, true, true,
            List.of("Diamond prefix + Diamond name tag", "/kit every 10 days: Diamond tag + 2 Molten Keys", "13 homes", "+55 claim chunks", "/fly in your claims or on your island", "/repairhand every 2 hours", "+40% Scrap")),
        new Rank("netherite", "Netherite", "<#9c7cff>", 18, 80, 0.50, new String[][]{{"netherite", "1"}}, true, true,
            List.of("Netherite prefix + Netherite name tag", "/kit every 10 days: Netherite tag + 1 Forgeheart Key", "18 homes", "+80 claim chunks", "/fly in your claims or on your island", "/repairhand every 2 hours", "+50% Scrap")));

    private final BlackForgeCore plugin;
    private final Crates crates;
    private final NamespacedKey voucherKey;
    private final File file;
    private final YamlConfiguration data;
    private Claims claims;
    private java.util.function.Predicate<Player> flyRule;

    /** Where rank flight is allowed (Skyblock: on your own island). Default: inside your claims. */
    void setFlyRule(java.util.function.Predicate<Player> rule) { this.flyRule = rule; }

    Ranks(BlackForgeCore plugin, Crates crates) {
        this.plugin = plugin;
        this.crates = crates;
        this.voucherKey = new NamespacedKey(BlackForgeCore.NS, "rank_voucher");
        this.file = new File(plugin.getDataFolder(), "ranks.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    void setClaims(Claims c) { this.claims = c; }

    public static Rank byId(String id) { return RANKS.stream().filter(r -> r.id().equalsIgnoreCase(id)).findFirst().orElse(null); }

    /** Highest rank the player holds, or null. */
    public Rank of(Player p) {
        // only a rank that is really given (LuckPerms group) counts - ops have every permission and would all show Netherite
        for (int i = RANKS.size() - 1; i >= 0; i--) { String perm = "blackforge.rank." + RANKS.get(i).id(); if (p.isPermissionSet(perm) && p.hasPermission(perm)) return RANKS.get(i); }
        return null;
    }

    public int bonusClaims(Player p) { Rank r = of(p); return r == null ? 0 : r.bonusClaims(); }
    public double scrapMultiplier(Player p) { Rank r = of(p); return r == null ? 1.0 : 1.0 + r.scrapBoost(); }

    // ------------------------------------------------------------------ vouchers

    public ItemStack voucher(Rank r) {
        ItemStack it = new ItemStack(Material.PAPER);
        it.editMeta(m -> {
            m.displayName(Ui.mm(r.color() + "<bold>" + r.name() + " Rank Voucher"));
            List<net.kyori.adventure.text.Component> lore = new java.util.ArrayList<>();
            lore.add(Ui.mm("<gray>Right-click to redeem the " + r.color() + r.name() + " <gray>rank"));
            lore.add(Ui.mm(""));
            for (String perk : r.perks()) lore.add(Ui.mm("<dark_gray>• <white>" + perk));
            m.lore(lore);
            m.setEnchantmentGlintOverride(true);
            m.getPersistentDataContainer().set(voucherKey, PersistentDataType.STRING, r.id());
        });
        return it;
    }

    public boolean giveVoucher(Player p, String id) {
        Rank r = byId(id);
        if (r == null) return false;
        p.getInventory().addItem(voucher(r)).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        p.sendMessage(Ui.mm("<gold>✦ <gray>You received a " + r.color() + "<bold>" + r.name() + " Rank Voucher<gray>! Right-click it to redeem."));
        return true;
    }

    @EventHandler
    public void onRedeem(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK)) return;
        ItemStack it = e.getItem();
        if (it == null || it.getType() != Material.PAPER || !it.hasItemMeta()) return;
        String id = it.getItemMeta().getPersistentDataContainer().get(voucherKey, PersistentDataType.STRING);
        if (id == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        Rank r = byId(id), cur = of(p);
        if (r == null) return;
        if (cur != null && RANKS.indexOf(cur) >= RANKS.indexOf(r)) {
            p.sendMessage(Ui.mm("<yellow>You already have " + cur.color() + cur.name() + "<yellow> - trade or sell this voucher instead!"));
            return;
        }
        it.setAmount(it.getAmount() - 1);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "lp user " + p.getName() + " parent add " + r.id());
        Bukkit.broadcast(Ui.mm("<gold>✦ <white>" + p.getName() + " <gray>is now " + r.color() + "<bold>" + r.name() + "<gray>! <white>/ranks"));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        p.getWorld().strikeLightningEffect(p.getLocation());
    }

    // ------------------------------------------------------------------ /ranks, /repairhand, /fly

    private static final class PerksHolder implements org.bukkit.inventory.InventoryHolder {
        org.bukkit.inventory.Inventory inv;
        public org.bukkit.inventory.Inventory getInventory() { return inv; }
    }

    @EventHandler
    public void onPerksClick(org.bukkit.event.inventory.InventoryClickEvent e) { if (e.getInventory().getHolder() instanceof PerksHolder) e.setCancelled(true); }

    @EventHandler
    public void onPerksDrag(org.bukkit.event.inventory.InventoryDragEvent e) { if (e.getInventory().getHolder() instanceof PerksHolder) e.setCancelled(true); }

    /** /ranks: every rank with its perks, your own highlighted, and what is above it. */
    public void info(Player p) {
        Rank cur = of(p);
        PerksHolder h = new PerksHolder();
        org.bukkit.inventory.Inventory inv = Bukkit.createInventory(h, 27, Ui.mm("<dark_gray>BlackForgeMC Ranks"));
        h.inv = inv;
        ItemStack pane = Ui.item(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 27; i++) inv.setItem(i, pane);
        int curIdx = cur == null ? -1 : RANKS.indexOf(cur);
        for (int i = 0; i < RANKS.size(); i++) {
            Rank r = RANKS.get(i);
            Material icon = switch (r.id()) { case "copper" -> Material.COPPER_INGOT; case "iron" -> Material.IRON_INGOT; case "gold" -> Material.GOLD_INGOT; case "diamond" -> Material.DIAMOND; case "netherite" -> Material.NETHERITE_INGOT; default -> Material.PAPER; };
            List<String> lore = new java.util.ArrayList<>();
            lore.add(i == curIdx ? "<green>✔ Your rank" : i < curIdx ? "<gray>Included in your rank" : "<yellow>Above your rank");
            lore.add("");
            for (String perk : r.perks()) lore.add("<gray>• <white>" + perk);
            lore.add("");
            lore.add("<gray>Homes <white>" + r.homes() + " <gray>• Claim chunks <white>+" + r.bonusClaims() + " <gray>• Scrap <white>+" + Math.round(r.scrapBoost() * 100) + "%");
            ItemStack it = Ui.item(icon, r.color() + "<bold>" + r.name().toUpperCase() + "</bold>", lore.toArray(String[]::new));
            if (i == curIdx) it.editMeta(m -> m.setEnchantmentGlintOverride(true));
            inv.setItem(10 + i, it);
        }
        inv.setItem(4, Ui.item(Material.NETHER_STAR, "<gold><bold>Your rank: " + (cur == null ? "<white>Member" : cur.color() + cur.name()), "<gray>Ranks come from <white>Rank Vouchers<gray> (KOTH crates, events)", "<gray>Support the server: <white>/supporter"));
        p.openInventory(inv);
    }

    /** The same list in chat. */
    public void infoChat(Player p) {
        Rank cur = of(p);
        p.sendMessage(Ui.mm("<gold><bold>BlackForgeMC Ranks</bold> <gray>- your rank: " + (cur == null ? "<white>Member" : cur.color() + "<bold>" + cur.name())));
        for (Rank r : RANKS)
            p.sendMessage(Ui.mm(r.color() + "<bold>" + r.name() + "</bold> <dark_gray>» <gray>" + String.join(", ", r.perks())));
        p.sendMessage(Ui.mm("<gray>Ranks are earned in game from <white>Rank Vouchers<gray> (KOTH crates, events). Want to support the server? <white>/supporter"));
    }

    /** /repairhand: fully repair the item you're holding, once every 2 hours (Gold rank and up). */
    public void repairHand(Player p) {
        Rank r = of(p);
        if (r == null || !r.repair()) { p.sendMessage(Ui.mm("<red>/repairhand is a <#ffc53d>Gold<red> rank perk and up. <gray>See /ranks")); return; }
        ItemStack it = p.getInventory().getItemInMainHand();
        if (it.getType().isAir() || !(it.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable d) || it.getType().getMaxDurability() <= 0) { p.sendMessage(Ui.mm("<red>Hold the item you want to repair.")); return; }
        if (d.getDamage() == 0) { p.sendMessage(Ui.mm("<gray>That item isn't damaged.")); return; }
        String path = p.getUniqueId() + ".repairhand";
        long next = data.getLong(path, 0), now = System.currentTimeMillis();
        if (next > now) { long m = (next - now) / 60_000L; p.sendMessage(Ui.mm("<yellow>/repairhand is ready again in <white>" + (m / 60) + "h " + (m % 60) + "m<yellow>.")); return; }
        it.editMeta(org.bukkit.inventory.meta.Damageable.class, x -> x.setDamage(0));
        data.set(path, now + REPAIR_COOLDOWN);
        try { data.save(file); } catch (Exception ignored) { }
        p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_ANVIL_USE, 0.7f, 1.3f);
        p.sendMessage(Ui.mm(r.color() + "<bold>Repaired!</bold> <gray>Next /repairhand in 2 hours."));
    }

    private boolean canFlyHere(Player p) {
        Rank r = of(p);
        if (r == null || !r.fly()) return false;
        if (flyRule != null) return flyRule.test(p);
        if (claims == null) return false;
        UUID owner = claims.ownerAt(p.getLocation());
        return owner != null && claims.canUse(p, p.getLocation());
    }

    public void fly(Player p) {
        Rank r = of(p);
        if (r == null || !r.fly()) { p.sendMessage(Ui.mm("<red>/fly is an <#d9dde3>Iron<red> rank perk and up. <gray>See /ranks")); return; }
        if (p.getAllowFlight() && p.getGameMode() == org.bukkit.GameMode.SURVIVAL) { p.setFlying(false); p.setAllowFlight(false); p.sendMessage(Ui.mm("<gray>Flight off.")); return; }
        if (!canFlyHere(p)) { p.sendMessage(Ui.mm(flyRule != null ? "<red>You can only fly on your own island." : "<red>You can only fly inside your own (or trusted) claims.")); return; }
        p.setAllowFlight(true);
        p.sendMessage(Ui.mm("<green>Flight on <gray>- it switches off when you leave your claims."));
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (!p.getAllowFlight() || p.getGameMode() != org.bukkit.GameMode.SURVIVAL) return;
        if (e.getFrom().getChunk() == e.getTo().getChunk()) return;
        if (canFlyHere(p)) return;
        p.setFlying(false);
        p.setAllowFlight(false);
        p.setFallDistance(0);
        p.sendActionBar(Ui.mm(flyRule != null ? "<yellow>Flight disabled - you left your island" : "<yellow>Flight disabled - you left your claim"));
    }

    /** Called when PvP starts for a player: no flying in fights. */
    public void dropFlight(Player p) {
        if (p.getAllowFlight() && p.getGameMode() == org.bukkit.GameMode.SURVIVAL) { p.setFlying(false); p.setAllowFlight(false); }
    }
}
