package net.blackforge.core.raft;

import net.blackforge.core.BlackForgeCore;
import net.blackforge.core.Ui;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.LinkedHashMap;
import java.util.Map;

/** The custom items of Raft, each a vanilla material with a name and a tag (so they stack and never mix with the plain item). */
public final class RaftItems {
    private RaftItems() {}

    public static final NamespacedKey KEY = new NamespacedKey(BlackForgeCore.NS, "raft_item");

    private record Def(String id, Material mat, String name, String lore) {}
    private static final Map<String, Def> DEFS = new LinkedHashMap<>();
    private static void def(String id, Material m, String name, String lore) { DEFS.put(id, new Def(id, m, name, lore)); }
    static {
        def("plastic", Material.WHITE_DYE, "<white>Plastic", "<gray>Washed up from somewhere. Useful for nearly everything.");
        def("scrap", Material.IRON_NUGGET, "<#9aa3ad>Scrap", "<gray>Bent metal. The start of every machine.");
        def("thatch", Material.WHEAT, "<#d9c27a>Thatch", "<gray>Dry palm fibre. Roofs, sails and rope.");
        def("foundation", Material.OAK_PLANKS, "<#c8a165>Foundation", "<gray>Right-click the water beside your raft<newline><gray>to add a tile to it.");
        def("cup", Material.GLASS_BOTTLE, "<aqua>Cup", "<gray>Right-click the sea to fill it with salt water.");
        def("salt", Material.POTION, "<#3f6f9a>Cup of Salt Water", "<gray>Drinking this dries you out. Purify it first.");
        def("fresh", Material.POTION, "<#7fd6ff>Fresh Water", "<gray>Restores thirst.");
        def("hook", Material.TRIPWIRE_HOOK, "<#c8a165>Hook", "<gray>Right-click toward floating debris to pull it in.<newline><dark_gray>Reach 24 blocks");
        def("adv_hook", Material.TRIPWIRE_HOOK, "<#ffd27a>Advanced Hook", "<gray>Right-click toward floating debris to pull it in.<newline><dark_gray>Reach 40 blocks, pulls faster");
        def("workbench", Material.CRAFTING_TABLE, "<#c8a165>Raft Workbench", "<gray>Place it on your raft. Research blueprints and<newline><gray>build everything a raft needs.");
        def("purifier", Material.CAULDRON, "<#7fd6ff>Water Purifier", "<gray>Place it. Right-click with salt water to fill it,<newline><gray>with planks or thatch to fuel it, empty-handed to collect.");
        def("plot", Material.DIRT, "<#8bd36a>Crop Plot", "<gray>Place it on your raft. It stays watered, so seeds grow.");
        def("sail", Material.WHITE_WOOL, "<white>Sail", "<gray>Place it on your raft. With a Steering Wheel it carries<newline><gray>the raft with the wind.");
        def("wheel", Material.GRINDSTONE, "<#c8a165>Steering Wheel", "<gray>Place it. Right-click to choose where the raft goes.");
        def("motor", Material.BLAST_FURNACE, "<#ff9f43>Motor", "<gray>Place it. Fuel it with planks, thatch or coal, then steer<newline><gray>any direction, wind or not.");
        def("receiver", Material.RECOVERY_COMPASS, "<#7fff9f>Receiver", "<gray>Right-click: it listens for the next radio tower.");
    }

    public static boolean exists(String id) { return DEFS.containsKey(id); }
    public static String displayName(String id) { Def d = DEFS.get(id); return d == null ? id : d.name(); }

    public static ItemStack make(String id, int amount) {
        Def d = DEFS.get(id);
        if (d == null) throw new IllegalArgumentException("no raft item " + id);
        ItemStack it = new ItemStack(d.mat(), amount);
        it.editMeta(m -> {
            m.displayName(Ui.mm(d.name()));
            m.lore(java.util.Arrays.stream(d.lore().split("<newline>")).map(Ui::mm).toList());
            m.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, id);
            m.setItemModel(new NamespacedKey("blackforge", "raft_" + id));
            if (m instanceof PotionMeta pm) pm.setColor(id.equals("fresh") ? Color.fromRGB(0x7fd6ff) : Color.fromRGB(0x3f6f9a));
        });
        return it;
    }

    public static ItemStack make(String id) { return make(id, 1); }

    /** The raft item id of a stack, or null for any other item. */
    public static String id(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
    }

    public static boolean is(ItemStack it, String id) { return id.equals(id(it)); }
}
