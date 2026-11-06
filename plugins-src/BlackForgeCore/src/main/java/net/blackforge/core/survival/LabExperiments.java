package net.blackforge.core.survival;

import net.blackforge.core.BlackForgeCore;
import net.blackforge.core.Lab;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Survival 2 experiments, wave 1. Each one registers a switch in /lab and checks {@link Lab#enabled} every time it
 * acts, so staff can turn it on and off live. Off Survival 2 they stay off unless listed under lab.graduated.
 */
final class LabExperiments {
    /** What the experiments need from Survival. */
    record Ctx(BlackForgeCore plugin, World world, Crates crates, Jobs jobs,
               BiPredicate<Player, Location> canBuild,   // may this player change blocks here (claims)
               Predicate<Location> claimed,                // is this inside anyone's claim
               Predicate<Location> protectedArea,          // spawn, KOTH arena, Scrapyard
               Supplier<Location> spawn) {}

    static void start(Ctx c) {
        Lab.register("ash-urns", AshUrns.NAME, Material.DECORATED_POT, true,
                "<gray>Dying (not to a player) puts your items", "<gray>in an urn where you fell, locked to",
                "<gray>you for <white>10 minutes<gray>, then open to all.");
        Lab.register("hearth-contracts", HearthContracts.NAME, Material.WRITABLE_BOOK, true,
                "<gray>Buy orders: <white>/contracts<gray>. Post what you", "<gray>want and pay up front; anyone can fill",
                "<gray>it and gets paid at once.");
        Lab.register("kindle-draw", KindleDraw.NAME, Material.SUNFLOWER, true,
                "<gray>Lottery: <white>/draw<gray>. Tickets add to the pot,", "<gray>the house keeps a cut. More tickets,", "<gray>better odds.");
        Lab.register("living-prices", LivingPrices.NAME, Material.GOLD_NUGGET, true,
                "<gray>The Grove Trader pays less for what", "<gray>everyone sells a lot of, and recovers", "<gray>over time. <white>/prices");
        Lab.register("ember-bonds", EmberBonds.NAME, Material.GOLD_INGOT, true,
                "<gray>Lock money away for 3 or 7 days", "<gray>and get it back with interest. <white>/bonds");
        Lab.register("ashfall-nights", AshfallNights.NAME, Material.WITHER_ROSE, true,
                "<gray>Every few nights: falling ash and", "<gray>ember-touched mobs that drop <white>Ashmarks<gray>.",
                "<gray>Claims stay safe. <white>/ashmarks <gray>to spend them.");
        Lab.register("forge-rifts", ForgeRifts.NAME, Material.CRYING_OBSIDIAN, true,
                "<gray>A rift tears open in the wild. Clear", "<gray>its 3 waves to make it burst with loot.", "<white>/rift <gray>shows where it is.");
        Lab.register("vein-timber", VeinTimber.NAME, Material.IRON_PICKAXE, true,
                "<gray>Sneak-break an ore vein (Miner 20) or", "<gray>a whole tree (Lumberjack 20) at once.", "<gray>Each block still costs durability.");
        Lab.register("quick-harvest", QuickHarvest.NAME, Material.WHEAT, true,
                "<gray>Right-click a grown crop to harvest", "<gray>and replant it in one go.");
        Lab.register("sit", Seats.NAME, Material.OAK_STAIRS, true,
                "<gray>Right-click a stair or slab with an", "<gray>empty hand to sit. Sneak to stand.");
        Lab.register("forge-post", ForgePost.NAME, Material.BARREL, true,
                "<gray>Send items and money to anyone, even", "<gray>offline: <white>/post<gray>. Collect from the post", "<gray>box at spawn or with /post.");

        // live Survival: nothing at all unless an idea has graduated there
        if (here("ash-urns")) new AshUrns(c);
        if (here("hearth-contracts")) new HearthContracts(c);
        if (here("kindle-draw")) new KindleDraw(c);
        if (here("living-prices")) LivingPrices.start(c);
        if (here("ember-bonds")) new EmberBonds(c);
        if (here("ashfall-nights")) new AshfallNights(c);
        if (here("forge-rifts")) new ForgeRifts(c);
        if (here("vein-timber")) new VeinTimber(c);
        if (here("quick-harvest")) new QuickHarvest(c);
        if (here("sit")) new Seats(c);
        if (here("forge-post")) new ForgePost(c);
    }

    /** Survival 2 always loads every experiment (so the /lab switch works live); other servers only graduated ones. */
    static boolean here(String id) { return Lab.on() || Lab.enabled(id); }

    /** A /command for an experiment: refuses politely while the experiment is switched off. */
    static void command(BlackForgeCore plugin, String experiment, String name, String desc, java.util.List<String> aliases,
                        java.util.function.BiConsumer<org.bukkit.command.CommandSender, String[]> run) {
        org.bukkit.Bukkit.getCommandMap().register("blackforge", new org.bukkit.command.Command(name, desc, "/" + name, aliases) {
            @Override public boolean execute(org.bukkit.command.CommandSender s, String label, String[] a) {
                if (!Lab.enabled(experiment)) { s.sendMessage(net.blackforge.core.Ui.mm(Lab.TAG + " <gray>That experiment is switched off right now. <white>/lab")); return true; }
                run.accept(s, a);
                return true;
            }
        });
    }

    /** Action-bar note, used when an experiment refuses something. */
    static void bar(Player p, String mm) { p.sendActionBar(net.blackforge.core.Ui.mm(mm)); }

    private LabExperiments() {}
}
