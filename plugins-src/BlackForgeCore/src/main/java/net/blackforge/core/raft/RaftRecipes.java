package net.blackforge.core.raft;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Workbench's blueprints: what a crew can build, what it costs, and the research that unlocks it. Costs are written as
 * ingredient key -> amount; a key is a raft item id (plastic, scrap, thatch) or "plank", "iron", "coal".
 */
public final class RaftRecipes {
    private RaftRecipes() {}

    /** research: what unlocking costs (empty = known from the start). needTowers: radio towers that must be switched on first. */
    public record Recipe(String id, String result, int amount, Map<String, Integer> cost, Map<String, Integer> research, int needTowers, String blurb) {}

    private static Map<String, Integer> m(Object... kv) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], (Integer) kv[i + 1]);
        return out;
    }

    public static final List<Recipe> ALL = List.of(
            new Recipe("foundation", "foundation", 1, m("plank", 2, "thatch", 1), m(), 0, "Grows your raft by one tile."),
            new Recipe("cup", "cup", 2, m("plastic", 1), m(), 0, "Scoop up sea water."),
            new Recipe("hook", "hook", 1, m("plank", 2, "plastic", 1, "scrap", 1), m(), 0, "A spare hook."),
            new Recipe("workbench", "workbench", 1, m("plank", 4, "plastic", 2), m(), 0, "Another workbench."),
            new Recipe("purifier", "purifier", 1, m("plank", 6, "plastic", 3, "scrap", 2), m("plank", 3, "plastic", 3), 0, "Turns salt water into fresh."),
            new Recipe("plot", "plot", 1, m("plank", 3, "thatch", 3), m("thatch", 4, "plank", 2), 0, "A watered square to grow crops on."),
            new Recipe("sail", "sail", 1, m("plank", 4, "thatch", 6), m("plank", 6, "thatch", 4), 0, "Catches the wind."),
            new Recipe("wheel", "wheel", 1, m("plank", 6, "scrap", 3, "plastic", 1), m("plank", 6, "scrap", 3), 0, "Steer the raft."),
            new Recipe("receiver", "receiver", 1, m("scrap", 4, "plastic", 3, "iron", 1), m("scrap", 4, "plastic", 4), 0, "Listens for radio towers."),
            new Recipe("motor", "motor", 1, m("iron", 6, "scrap", 6, "plastic", 4), m("iron", 4, "scrap", 4, "plastic", 3), 1, "Sail in any direction, wind or not."),
            new Recipe("adv_hook", "adv_hook", 1, m("iron", 4, "plastic", 4, "scrap", 3), m("iron", 3, "plastic", 3), 2, "Longer, faster hook."));

    public static Recipe byId(String id) {
        for (Recipe r : ALL) if (r.id().equals(id)) return r;
        return null;
    }

    private static boolean matches(ItemStack it, String key) {
        if (it == null || it.getType().isAir()) return false;
        String id = RaftItems.id(it);
        if (id != null) return id.equals(key);
        return switch (key) {
            case "plank" -> Tag.PLANKS.isTagged(it.getType()) && !it.hasItemMeta();
            case "iron" -> it.getType() == Material.IRON_INGOT && !it.hasItemMeta();
            case "coal" -> (it.getType() == Material.COAL || it.getType() == Material.CHARCOAL) && !it.hasItemMeta();
            default -> false;
        };
    }

    public static int count(PlayerInventory inv, String key) {
        int n = 0;
        for (ItemStack it : inv.getStorageContents()) if (matches(it, key)) n += it.getAmount();
        return n;
    }

    public static boolean canAfford(Player p, Map<String, Integer> cost) {
        for (var e : cost.entrySet()) if (count(p.getInventory(), e.getKey()) < e.getValue()) return false;
        return true;
    }

    public static void take(Player p, Map<String, Integer> cost) {
        for (var e : cost.entrySet()) {
            int left = e.getValue();
            ItemStack[] st = p.getInventory().getStorageContents();
            for (int i = 0; i < st.length && left > 0; i++) {
                if (!matches(st[i], e.getKey())) continue;
                int t = Math.min(left, st[i].getAmount());
                left -= t;
                if (t == st[i].getAmount()) st[i] = null; else st[i].setAmount(st[i].getAmount() - t);
            }
            p.getInventory().setStorageContents(st);
        }
    }

    /** Whether a stack counts as fuel for a motor or purifier, and how many burns it is worth. */
    public static int fuelValue(ItemStack it) {
        if (it == null) return 0;
        if (matches(it, "thatch")) return 1;
        if (matches(it, "plank")) return 2;
        if (matches(it, "coal")) return 8;
        return 0;
    }

    public static String describe(Map<String, Integer> cost) {
        StringBuilder sb = new StringBuilder();
        for (var e : cost.entrySet()) {
            if (sb.length() > 0) sb.append("<gray>, ");
            sb.append("<white>").append(e.getValue()).append(" ").append(label(e.getKey()));
        }
        return sb.toString();
    }

    public static String label(String key) {
        return switch (key) {
            case "plank" -> "Planks"; case "iron" -> "Iron"; case "coal" -> "Coal";
            default -> net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(net.blackforge.core.Ui.mm(RaftItems.displayName(key)));
        };
    }
}
