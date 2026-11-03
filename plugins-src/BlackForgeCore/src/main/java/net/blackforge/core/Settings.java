package net.blackforge.core;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;
import java.util.function.Consumer;

/**
 * /settings: one menu for everything about how you look and how the network shows things to you - name colour, name icon,
 * trails, auras, capes, pets, kill and victory effects, the Limited Time page and the crate - plus a few switches.
 * It works on every server; buttons for features that live on one server (name colours) say so when you're elsewhere.
 */
public final class Settings implements Listener {
    private final BlackForgeCore plugin;
    private final Cosmetics cos;

    private static final class Hub implements InventoryHolder {
        Inventory inv;
        final Map<Integer, Consumer<Player>> actions = new HashMap<>();
        @Override public Inventory getInventory() { return inv; }
    }

    public Settings(BlackForgeCore plugin, Cosmetics cos) {
        this.plugin = plugin;
        this.cos = cos;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        cos.setSettingsMenu(this::open);
        Bukkit.getCommandMap().register("blackforge", new Command("settings", "Your look and your options", "/settings", List.of("setting", "options", "prefs")) {
            @Override public boolean execute(CommandSender s, String label, String[] a) {
                if (s instanceof Player p) open(p); else s.sendMessage("Players only.");
                return true;
            }
        });
    }

private void extra(Hub h, int slot, Material m, String name, String command, String line1, String line2) {
    if (!has(command)) return;
    h.inv.setItem(slot, Ui.item(m, name, "<gray>" + line1, "<gray>" + line2, "", "<yellow>Click to open"));
    h.actions.put(slot, pl -> pl.performCommand(command));
}

private static boolean has(String command) { return Bukkit.getCommandMap().getCommand(command) != null; }

    private ItemStack category(Player p, Cosmetics.Kind k, String extra) {
        String eq = cos.equippedName(p, k);
        int owned = cos.ownedCount(p, k), total = Cosmetics.of(k).size();
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + k.blurb);
        lore.add("");
        lore.add("<gray>Wearing: " + (eq == null ? "<dark_gray>nothing" : eq));
        lore.add("<gray>Owned: <white>" + owned + "<dark_gray>/<white>" + total);
        if (extra != null) lore.add(extra);
        lore.add("");
        lore.add("<yellow>Click to open");
        ItemStack it = Ui.item(k.icon, "<white><bold>" + k.title, lore.toArray(new String[0]));
        if (eq != null) it.editMeta(m -> m.setEnchantmentGlintOverride(true));
        return it;
    }

    public void open(Player p) {
        Hub h = new Hub();
        h.inv = Bukkit.createInventory(h, 54, Ui.mm("<dark_gray>Settings"));
        Runnable back = () -> open(p);
        ItemStack pane = Ui.item(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < 54; i++) h.inv.setItem(i, pane);

        // you
        ItemStack head = Ui.item(Material.PLAYER_HEAD, "<white><bold>" + p.getName(), "<gray>Your look and your options", "",
                "<gray>Icon: " + (cos.equippedName(p, Cosmetics.Kind.ICON) == null ? "<dark_gray>none" : cos.equippedName(p, Cosmetics.Kind.ICON)),
                "<gray>Keys: <white>" + cos.keyCount(p) + " <dark_gray>- <gray>Forge Dust: <white>" + cos.dust(p.getUniqueId()));
        head.editMeta(SkullMeta.class, m -> m.setOwningPlayer(p));
        h.inv.setItem(4, head);

        // your look
        h.inv.setItem(11, Ui.item(Material.MAGENTA_DYE, "<white><bold>Name Colour",
                "<gray>Colour your name in chat and the tab list.", "",
                has("tags") ? "<yellow>Click to open your name colours" : "<gray>Name colours are chosen on <white>Survival<gray> and <white>Skyblock<gray>.",
                has("tags") ? "" : "<gray>Use <white>/tags<gray> there."));
        h.actions.put(11, pl -> {
            if (has("tags")) pl.performCommand("tags");
            else { pl.closeInventory(); pl.sendMessage(Ui.mm("<gray>Name colours are chosen on <white>Survival<gray> or <white>Skyblock<gray> with <white>/tags<gray>. Your colour then shows everywhere.")); }
        });

        int[] kindSlots = {12, 13, 14, 15, 16, 19, 20};
        Cosmetics.Kind[] order = {Cosmetics.Kind.ICON, Cosmetics.Kind.TRAIL, Cosmetics.Kind.CAPE, Cosmetics.Kind.PET, Cosmetics.Kind.AURA, Cosmetics.Kind.KILL, Cosmetics.Kind.VICTORY};
        for (int i = 0; i < order.length; i++) {
            Cosmetics.Kind k = order[i];
            h.inv.setItem(kindSlots[i], category(p, k, k == Cosmetics.Kind.TRAIL && has("trails") ? "<gray>Supporter trails have their own button." : null));
            h.actions.put(kindSlots[i], pl -> cos.openCategory(pl, k, back));
        }
        h.inv.setItem(10, Ui.item(Material.NETHER_STAR, "<gradient:#ffd27a:#ff4d00><bold>All Cosmetics", "<gray>Everything in one place, with your", "<gray>collection and the crate.", "", "<yellow>Click to open"));
        h.actions.put(10, cos::open);

        // earning and collecting
        h.inv.setItem(21, Ui.item(Material.CLOCK, "<gold><bold>Limited Time", "<gray>Event cosmetics you can only get right now,", "<gray>and the season leaderboard.", "", "<yellow>Click to open"));
        h.actions.put(21, pl -> cos.openLimited(pl, back));
        int keys = cos.keyCount(p);
        ItemStack crate = Ui.item(Material.TRIAL_KEY, "<gradient:#ffd27a:#ff4d00><bold>Cosmetic Crate", "<gray>Your keys: " + (keys > 0 ? "<green>" : "<red>") + keys, "",
                keys > 0 ? "<yellow>Click to open one" : "<gray>Get keys in the store: <white>/store");
        if (keys > 0) crate.editMeta(m -> m.setEnchantmentGlintOverride(true));
        h.inv.setItem(22, crate);
        h.actions.put(22, cos::openCrate);
extra(h, 24, Material.WRITABLE_BOOK, "<yellow><bold>Quests", "quests", "Daily and weekly jobs that pay out", "keys, Forge Dust and Season Points.");
extra(h, 25, Material.EXPERIENCE_BOTTLE, "<light_purple><bold>Season Pass", "pass", "Climb tiers with Season Points for", "keys, dust and exclusive cosmetics.");
extra(h, 33, Material.CHEST_MINECART, "<aqua><bold>Trading Post", "market", "Buy and sell cosmetics with other", "players for Forge Dust.");
extra(h, 34, Material.WHITE_BANNER, "<white><bold>Crew", "crew", "Your crew, its colours and the", "crew leaderboard.");
extra(h, 39, Material.SPYGLASS, "<green><bold>Build Showcase", "showcase", "Nominate your build and vote for", "this week's best.");
if (has("trails")) {
    h.inv.setItem(23, Ui.item(Material.BLAZE_POWDER, "<white><bold>Supporter Trails", "<gray>The particle trails that come with", "<gray>a supporter rank or the Trail Pack.", "", "<yellow>Click to open"));
            h.actions.put(23, pl -> pl.performCommand("trails"));
        }

        // options
        boolean hide = cos.setting(p.getUniqueId(), "hide-others", false);
        h.inv.setItem(29, Ui.item(hide ? Material.ENDER_EYE : Material.ENDER_PEARL, "<white><bold>Other players' pets and capes",
                "<gray>Currently: " + (hide ? "<red>hidden" : "<green>shown"), "",
                "<gray>Turn them off to cut clutter or lag.", "<gray>Trails, auras and effects still show.", "",
                "<yellow>Click to " + (hide ? "show them" : "hide them")));
        h.actions.put(29, pl -> { cos.setSetting(pl.getUniqueId(), "hide-others", !hide); open(pl); });
        if (has("link")) {
            h.inv.setItem(30, Ui.item(Material.PAPER, "<white><bold>Link Discord", "<gray>Connect your Discord account to get", "<gray>your roles and chat both ways.", "", "<yellow>Click to run /link"));
            h.actions.put(30, pl -> pl.performCommand("link"));
        }
        if (has("achievements")) {
            h.inv.setItem(31, Ui.item(Material.GOLDEN_APPLE, "<white><bold>Achievements", "<gray>Your progress, and the cosmetics", "<gray>they unlock.", "", "<yellow>Click to open"));
            h.actions.put(31, pl -> pl.performCommand("achievements"));
        }
        if (has("store")) {
            h.inv.setItem(32, Ui.item(Material.EMERALD, "<green><bold>Store", "<gray>Supporter ranks, trail packs and", "<gray>Cosmetic Keys.", "", "<yellow>Click for the link"));
            h.actions.put(32, pl -> pl.performCommand("store"));
        }

        h.inv.setItem(45, Ui.item(Material.BARRIER, "<red>Take everything off", "<gray>Unequips every cosmetic and icon.", "<gray>You keep all of them."));
        h.actions.put(45, pl -> { cos.unequipAll(pl); pl.sendMessage(Ui.mm("<gray>Every cosmetic taken off.")); open(pl); });
        h.inv.setItem(49, Ui.item(Material.OAK_DOOR, "<white>Close"));
        h.actions.put(49, Player::closeInventory);
        p.openInventory(h.inv);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) { if (e.getView().getTopInventory().getHolder() instanceof Hub) e.setCancelled(true); }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Hub h) || !(e.getWhoClicked() instanceof Player p)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        Consumer<Player> a = h.actions.get(e.getSlot());
        if (a == null) return;
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
        a.accept(p);
    }
}
