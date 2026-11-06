package net.blackforge.core.survival;

import net.blackforge.core.BlackForgeCore;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * An upgraded hopper's colour. A block can't be recoloured by itself, so a display of the pack's hopper shape sits over the
 * real one, a hair bigger: Tier II glimmering orange, Tier III a flowing rainbow. It never saves, and the hopper underneath
 * still does all the work, so turning this off changes nothing but the look. Its movement is in the texture, not particles.
 */
final class HopperLook {
    private static final float SHELL_SCALE = 1.01f;

    private record Shell(ItemDisplay display, int tier) {}

    private final boolean active;
    private final Map<Block, Shell> shells = new HashMap<>();

    HopperLook(BlackForgeCore plugin) {
        active = plugin.getConfig().getBoolean("resource-pack-live", false) && plugin.getConfig().getBoolean("hopper-looks", true);
    }

    boolean active() { return active; }

    void dress(Block b, int tier) {
        Shell s = shells.get(b);
        if (s != null && s.display().isValid() && s.tier() == tier) return;
        remove(b);
        BlockFace facing = b.getBlockData() instanceof Directional d ? d.getFacing() : BlockFace.DOWN;
        boolean side = facing != BlockFace.DOWN;
        String model = (tier >= 3 ? "prism" : "ember") + (side ? "_side" : "_down");
        ItemStack item = new ItemStack(Material.PAPER);
        item.editMeta(m -> {
            m.setItemModel(new NamespacedKey("blackforge", "hopper/" + model));
            m.setEnchantmentGlintOverride(true);
        });
        // the side model's spout is on its north, which a display turns to face +z
        float turn = switch (facing) { case EAST -> (float) (Math.PI / 2); case WEST -> (float) (-Math.PI / 2); case NORTH -> (float) Math.PI; default -> 0f; };
        Location at = b.getLocation().add(0.5, 0.5, 0.5);
        shells.put(b, new Shell(at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setViewRange(0.5f);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setItemStack(item);
            d.setBrightness(new Display.Brightness(12, 15));
            d.setTransformationMatrix(new Matrix4f().rotateY(turn).scale(SHELL_SCALE));
        }), tier));
    }

    void remove(Block b) {
        Shell s = shells.remove(b);
        if (s != null) s.display().remove();
    }

    /** Takes off the shell of anything not in this set: broken, or its chunk has gone. */
    void keepOnly(Set<Block> keep) {
        for (Iterator<Map.Entry<Block, Shell>> it = shells.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (!keep.contains(e.getKey())) { e.getValue().display().remove(); it.remove(); }
        }
    }

    void shutdown() {
        shells.values().forEach(s -> s.display().remove());
        shells.clear();
    }
}
