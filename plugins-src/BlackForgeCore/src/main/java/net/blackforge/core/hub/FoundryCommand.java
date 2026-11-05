package net.blackforge.core.hub;

import net.blackforge.core.Ui;
import net.blackforge.core.survival.FoundrySystems;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * /foundry: one place for every test in the Foundry. Anyone in the Arena can use it, because everything there is free and
 * nothing is saved. Running it alone lists every command with what it does, so nobody has to remember them.
 */
final class FoundryCommand {
    /** Command, what to fill in when it's clicked, and what it does. */
    private static final String[][] HELP = {
            {"/foundry start", "/foundry start", "Takes you to the island, swaps your hotbar to test tools and starts Tier I allays"},
            {"/foundry stop", "/foundry stop", "Removes your agents and blocks, and gives your combat kit back"},
            {"/foundry allays [1|2|3|off]", "/foundry allays ", "Sets the allay tier: 1 Gatherer carries drops, 2 Courier sorts and refuels, 3 Steward sells"},
            {"/foundry level", "/foundry level", "Shows the island's base level, its Agent deliveries and what's next"},
            {"/foundry level [1-" + FoundrySystems.maxLevel() + "]", "/foundry level ", "Sets the base level for the whole island"},
            {"/foundry level add [items]", "/foundry level add ", "Adds Agent deliveries, so you can test progress without waiting"},
            {"/foundry demo", "/foundry demo", "Places one fuelled agent at each bench, so the whole chain runs by itself"},
            {"/foundry tp [place]", "/foundry tp ", "Takes you to a bench: forge, supply, allays, courier, lanes, farm, pond, mine, pen or plot"},
            {"/foundry fuel low", "/foundry fuel low", "Runs your agents low and stocks the Forge with Fuel Cells, so a Courier comes to refuel them"},
            {"/foundry reset", "/foundry reset", "Resets the benches, the Forge's storage and the courier chests. Staff, or when you're alone here"},
            {"/foundry check", "/foundry check", "Tests every agent, hopper and allay job for you, then lists what passed and what failed"},
            {"/foundry status", "/foundry status", "Shows the allays, your agents and where they deliver, framed chests and hopper speeds"},
            {"/arena kit", "/arena kit", "Refills your test kit"},
            {"/hub", "/hub", "Leaves the Arena and clears your Foundry test right away"},
    };
    private static final String[][] STAFF_HELP = {
            {"/allay start", "/allay start", "Spawns a test allay that flies back and forth. It should ignore clicks, items and jukeboxes"},
            {"/allay stop", "/allay stop", "Removes the test allays"},
            {"/allay name [text]", "/allay name ", "Names the nearest summoned allay. Smoked Sausage flies upside down"},
            {"/allay drain", "/allay drain", "Runs your Agents within 20 blocks down to a minute of fuel"},
    };

    private static final long MAX_TEST_DELIVERIES = 1_000_000_000L;
    private final Foundry foundry;
    private final TestArena arena;

    FoundryCommand(Foundry foundry, TestArena arena) {
        this.foundry = foundry;
        this.arena = arena;
        Bukkit.getCommandMap().register("blackforge", new Command("foundry", "Test agents, hoppers and allays in the Arena", "/foundry [start|stop|allays|level|demo|tp|fuel|reset|check|status]", List.of()) {
            @Override public boolean execute(CommandSender s, String label, String[] a) {
                if (!(s instanceof Player p)) return true;
                run(p, a);
                return true;
            }
            @Override public List<String> tabComplete(CommandSender s, String alias, String[] a) {
                if (a.length == 1) return List.of("start", "stop", "allays", "level", "demo", "tp", "fuel", "reset", "status");
                if (a.length == 2 && a[0].equalsIgnoreCase("allays")) return List.of("1", "2", "3", "off");
                if (a.length == 2 && a[0].equalsIgnoreCase("level")) {
                    List<String> out = new ArrayList<>();
                    for (int l = 1; l <= FoundrySystems.maxLevel(); l++) out.add(String.valueOf(l));
                    out.add("add");
                    return out;
                }
                if (a.length == 2 && a[0].equalsIgnoreCase("tp")) return List.copyOf(new TreeSet<>(FoundryZone.SPOTS.keySet()));
                if (a.length == 2 && a[0].equalsIgnoreCase("fuel")) return List.of("low");
                return List.of();
            }
        });
    }

    private void run(Player p, String[] a) {
        String what = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
        if (what.equals("start")) { start(p); return; }
        if (what.isEmpty() || what.equals("help")) { help(p); return; }
        if (what.equals("stop")) {
            p.sendMessage(Ui.mm(foundry.end(p) ? "<green>Foundry test cleared. <gray>You're back at the Arena with your combat kit." : "<gray>You had no Foundry test running. <white>/foundry start<gray> begins one."));
            return;
        }
        if (!foundry.testing(p)) { p.sendMessage(Ui.mm("<gray>Start a test first: <white>/foundry start")); return; }
        switch (what) {
            case "allays" -> allays(p, a);
            case "level" -> level(p, a);
            case "demo" -> p.sendMessage(Ui.mm(foundry.demo(p)));
            case "tp" -> tp(p, a);
            case "fuel" -> {
                if (a.length > 1 && a[1].equalsIgnoreCase("low")) p.sendMessage(Ui.mm(foundry.systems().fuelLow(p)));
                else p.sendMessage(Ui.mm("<gray>Use <white>/foundry fuel low"));
            }
            case "reset" -> reset(p);
            case "check" -> foundry.check(p, line -> p.sendMessage(Ui.mm(line)));
            case "status" -> foundry.systems().status(p).forEach(line -> p.sendMessage(Ui.mm(line)));
            default -> help(p);
        }
    }

    private void start(Player p) {
        if (!arena.inArena(p)) {
            if (p.getWorld() != Bukkit.getWorlds().getFirst()) { p.sendMessage(Ui.mm("<gray>Go to the hub first: <white>/hub")); return; }
            arena.enter(p);   // it says so if the Arena is still being built
            if (!arena.inArena(p)) return;
        }
        foundry.begin(p);
        p.playSound(p.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.8f, 1.2f);
        help(p);
    }

    private void help(Player p) {
        p.sendMessage(Ui.mm("<gradient:#ffb347:#ff5a1a><bold>The Foundry</bold></gradient> <dark_gray>· <gray>test agents, hoppers and allays. Click a command to use it."));
        for (String[] row : HELP) line(p, row);
        if (p.hasPermission("blackforge.admin")) for (String[] row : STAFF_HELP) line(p, row);
    }

    private static void line(Player p, String[] row) {
        p.sendMessage(Ui.mm(" <click:suggest_command:'" + row[1] + "'><white>" + row[0] + "</white></click> <dark_gray>- <gray>" + row[2]));
    }

    private void allays(Player p, String[] a) {
        String want = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
        int tier = switch (want) { case "off", "0" -> 0; case "1" -> 1; case "2" -> 2; case "3" -> 3; default -> -1; };
        if (tier < 0) { p.sendMessage(Ui.mm("<gray>Use <white>/foundry allays 1<gray>, <white>2<gray>, <white>3<gray> or <white>off")); return; }
        if (!foundry.systems().setAllayTier(p.getUniqueId(), tier)) { p.sendMessage(Ui.mm("<red>The Foundry has enough allays flying right now. Try a lower tier.")); return; }
        foundry.tell("<#4fd1b5>" + p.getName() + " set the Forge's allays to " + foundry.systems().allayTierText(tier));
    }

    private void level(Player p, String[] a) {
        if (a.length > 1) {
            String want = a[1].toLowerCase(Locale.ROOT);
            if (want.equals("add")) {
                long items = a.length > 2 ? number(a[2]) : -1;
                if (items < 1 || items > MAX_TEST_DELIVERIES) { p.sendMessage(Ui.mm("<gray>Use <white>/foundry level add <gray>and a number from 1 to " + MAX_TEST_DELIVERIES)); return; }
                foundry.systems().addTestDeliveries(items);
                foundry.tell("<#4fd1b5>" + p.getName() + " added " + items + " Agent deliveries to the island's test base.");
            } else {
                long level = number(want);
                if (level < 1 || level > FoundrySystems.maxLevel()) { p.sendMessage(Ui.mm("<gray>Use <white>/foundry level 1 <gray>to <white>" + FoundrySystems.maxLevel() + "<gray>, or <white>/foundry level add <gray>and a number")); return; }
                foundry.systems().setTestLevel((int) level);
                foundry.tell("<#4fd1b5>" + p.getName() + " set the island to Base Level " + level + ".");
            }
        }
        foundry.systems().levelStatus().forEach(line -> p.sendMessage(Ui.mm(line)));
    }

    private static long number(String text) {
        try { return Long.parseLong(text); } catch (NumberFormatException ex) { return -1; }
    }

    private void tp(Player p, String[] a) {
        int[] spot = a.length > 1 ? FoundryZone.SPOTS.get(a[1].toLowerCase(Locale.ROOT)) : null;
        if (spot == null) { p.sendMessage(Ui.mm("<gray>Use <white>/foundry tp <gray>and one of: <white>" + String.join(", ", new TreeSet<>(FoundryZone.SPOTS.keySet())))); return; }
        p.teleport(new Location(arena.world(), spot[0] + 0.5, TestArenaBuilder.Y + 1, spot[1] + 0.5, 180f, 0f));
        p.setFallDistance(0);
        p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.4f, 1.3f);
    }

    private void reset(Player p) {
        if (!p.hasPermission("blackforge.admin") && foundry.testers() > 1) { p.sendMessage(Ui.mm("<gray>Others are testing here, so only staff can reset the benches right now.")); return; }
        foundry.systems().reset();
        foundry.tell("<#ffb347>" + p.getName() + " reset the Foundry's benches.");
    }
}
