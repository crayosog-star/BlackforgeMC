package net.blackforge.core.survival;

import net.blackforge.core.BlackForgeCore;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
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
 * still does all the work, so turning this off changes nothing but the look.
 */
final class HopperLook {
    private static final float SHELL_SCALE = 1.01f;
    private static final int SPARKLE_EVERY = 3;

    private record Shell(ItemDisplay display, int tier) {}

    private final boolean active;
    private final Map<Block, Shell> shells = new HashMap<>();
    private int seconds;

    HopperLook(BlackForgeCore plugin) {
        active = plugin.getConfig().getBoolean("resource-pack-live", false) && plugin.getConfig().getBoolean("hopper-looks", true);
    }

    boolean active() { return active; }

    void dress(Block b, int tier) {
        Shell s = shells.get(b);
        if (s != null && s.display().isValid() && s.tier() == tier) { sparkle(b, tier); return; }
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

    private void sparkle(Block b, int tier) {
        if (Math.floorMod(++seconds + b.getX() + b.getZ(), SPARKLE_EVERY) != 0) return;
        Location at = b.getLocation().add(0.5, 1.05, 0.5);
        if (tier == 2) at.getWorld().spawnParticle(Particle.WAX_ON, at, 1, 0.3, 0.05, 0.3, 0);
        else at.getWorld().spawnParticle(Particle.DUST, at, 2, 0.3, 0.05, 0.3, 0, new Particle.DustOptions(Color.fromRGB(AllayFlight.rainbow(seconds * 0.03)), 0.8f));
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
