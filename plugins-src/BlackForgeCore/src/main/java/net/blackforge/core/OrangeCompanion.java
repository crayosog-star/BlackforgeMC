package net.blackforge.core;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** One player session. The model is built entirely from displays, so it has no mob AI or interactive hitbox. */
final class OrangeCompanion {
    static final String TAG = "bf_orange_companion";
    static final String INTRO = "blackforge:pet.orange_companion";
    private static final int HOP_FRAMES = 8;

    private record VoiceCue(long delayNanos, String sound) {}
    private static final List<VoiceCue> VOICE_CUES = List.of(
            new VoiceCue(Duration.ofSeconds(10).toNanos(), INTRO),
            new VoiceCue(Duration.ofSeconds(30).toNanos(), "blackforge:pet.vickie"),
            new VoiceCue(Duration.ofSeconds(40).toNanos(), "blackforge:pet.hesitant_gulp")
    );

    enum Toggle {
        ON, OFF;

        static Toggle parse(String[] args) {
            if (args.length != 1) return null;
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "on" -> ON;
                case "off" -> OFF;
                default -> null;
            };
        }
    }

    /** Local coordinates with +Z facing forward. Swing is positive on the left foot and right arm. */
    private record Piece(Material material, float x, float y, float z, float w, float h, float d, int swing) {}
    private static final List<Piece> MODEL = List.of(
            new Piece(Material.ORANGE_TERRACOTTA, -.18f, .15f, -.135f, .36f, .28f, .27f, 0),
            new Piece(Material.ORANGE_CONCRETE, -.245f, .375f, -.17f, .49f, .34f, .34f, 0),
            new Piece(Material.ORANGE_TERRACOTTA, -.26f, .43f, -.145f, .52f, .22f, .29f, 0),
            new Piece(Material.ORANGE_CONCRETE, -.28f, .21f, -.08f, .095f, .19f, .15f, -1),
            new Piece(Material.ORANGE_CONCRETE, .185f, .21f, -.08f, .095f, .19f, .15f, 1),
            new Piece(Material.BROWN_TERRACOTTA, -.155f, 0, -.06f, .12f, .155f, .19f, 1),
            new Piece(Material.BROWN_TERRACOTTA, .035f, 0, -.06f, .12f, .155f, .19f, -1),
            new Piece(Material.BLACK_CONCRETE, -.148f, .535f, .17f, .055f, .078f, .018f, 0),
            new Piece(Material.BLACK_CONCRETE, .093f, .535f, .17f, .055f, .078f, .018f, 0),
            new Piece(Material.BROWN_CONCRETE, -.042f, .453f, .17f, .084f, .058f, .018f, 0)
    );

    private final List<BlockDisplay> displays = new ArrayList<>(MODEL.size());
    private Location anchor;
    private float facing;
    private int hopFrame, blockedFrames;
    private Long firstSpawnAt;
    private int nextVoiceCue;

    List<BlockDisplay> displays() { return displays; }

    void startVoiceClock(long now) {
        if (firstSpawnAt == null) firstSpawnAt = now;
    }

    String voiceDue(long now) {
        if (firstSpawnAt == null || nextVoiceCue == VOICE_CUES.size()) return null;
        long elapsed = now - firstSpawnAt;
        if (elapsed < VOICE_CUES.get(nextVoiceCue).delayNanos()) return null;

        // If the pet was hidden across multiple cues, only play the latest one.
        while (nextVoiceCue + 1 < VOICE_CUES.size()
                && elapsed >= VOICE_CUES.get(nextVoiceCue + 1).delayNanos()) {
            nextVoiceCue++;
        }
        return VOICE_CUES.get(nextVoiceCue++).sound();
    }

    static int nextHopFrame(int frame, boolean moving) {
        return moving || frame != 0 ? (frame + 1) % HOP_FRAMES : 0;
    }

    static float hopHeight(int frame) {
        return .25f * (float) Math.sin(Math.PI * frame / HOP_FRAMES);
    }

    /** Called by the cosmetics loop every two ticks while the owner is eligible to show a pet. */
    void follow(Location ownerAt, Consumer<BlockDisplay> hideFromOptedOut, BiConsumer<Location, String> playVoice) {
        double yaw = Math.toRadians(ownerAt.getYaw());
        Location behind = ownerAt.clone().add(1.55 * Math.sin(yaw), 0, -1.55 * Math.cos(yaw));
        Location target = grounded(behind, 2.2);
        if (target == null) target = grounded(ownerAt, 2.2);
        if (target == null) {
            removeModel();
            return;
        }
        if (anchor == null || anchor.getWorld() != ownerAt.getWorld()
                || anchor.distanceSquared(ownerAt) > 81 || Math.abs(anchor.getY() - target.getY()) > 3
                || displays.size() != MODEL.size() || displays.stream().anyMatch(d -> !d.isValid())) {
            spawn(target, ownerAt, hideFromOptedOut);
            return;
        }

        Location step = stepToward(target);
        // Give ordinary turns and stairs a moment, then catch up if a wall blocks the direct route.
        if (step == null && anchor.distanceSquared(target) > .20) {
            if (++blockedFrames >= 10) {
                spawn(target, ownerAt, hideFromOptedOut);
                return;
            }
        } else blockedFrames = 0;

        int nextFrame = nextHopFrame(hopFrame, step != null);
        float nextFacing = face(step == null ? anchor : step, ownerAt);
        String voice = voiceDue(System.nanoTime());
        if (voice != null) {
            playVoice.accept(anchor.clone().add(0, .5, 0), voice);
            if (nextFrame == 0) nextFrame = 1;
        }
        if (step == null && nextFrame == hopFrame && nextFacing == facing) return;
        if (step != null) anchor = step;
        hopFrame = nextFrame;
        facing = nextFacing;
        for (int i = 0; i < displays.size(); i++) {
            BlockDisplay part = displays.get(i);
            if (step != null) part.teleport(anchor);
            part.setInterpolationDelay(0);
            part.setTransformation(pose(MODEL.get(i)));
        }
    }

    private void spawn(Location target, Location ownerAt, Consumer<BlockDisplay> hideFromOptedOut) {
        removeModel();
        anchor = target;
        facing = face(anchor, ownerAt);
        hopFrame = 1;
        blockedFrames = 0;
        for (Piece piece : MODEL) {
            BlockDisplay part = target.getWorld().spawn(target, BlockDisplay.class, display -> {
                display.setBlock(piece.material().createBlockData());
                display.addScoreboardTag(TAG);
                display.setPersistent(false);
                display.setInvulnerable(true);
                display.setTeleportDuration(2);
                display.setInterpolationDuration(2);
                display.setShadowRadius(0);
                display.setTransformation(pose(piece));
            });
            displays.add(part);
            hideFromOptedOut.accept(part);
        }
        startVoiceClock(System.nanoTime());
    }

    void removeModel() {
        displays.forEach(Entity::remove);
        displays.clear();
        anchor = null;
    }

    private Location stepToward(Location target) {
        double dx = target.getX() - anchor.getX(), dz = target.getZ() - anchor.getZ();
        double distance = Math.hypot(dx, dz);
        if (distance < .16) return null;
        double fraction = Math.min(1, (distance > 2 ? .60 : .36) / distance);
        return grounded(anchor.clone().add(dx * fraction, 0, dz * fraction), 1.08);
    }

    /** Find the nearest usable floor beneath the desired point, including slabs and one-block steps. */
    private static Location grounded(Location desired, double maxDrop) {
        World world = desired.getWorld();
        int x = desired.getBlockX(), z = desired.getBlockZ();
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return null;
        int top = Math.min(world.getMaxHeight() - 1, desired.getBlockY() + 1);
        int bottom = Math.max(world.getMinHeight(), desired.getBlockY() - (int) Math.ceil(maxDrop) - 1);
        for (int y = top; y >= bottom; y--) {
            Block floor = world.getBlockAt(x, y, z);
            if (floor.isPassable() || floor.isLiquid()) continue;
            BoundingBox surface = floor.getBoundingBox();
            double px = desired.getX(), pz = desired.getZ(), feet = surface.getMaxY();
            if (feet > desired.getY() + 1.05 || feet < desired.getY() - maxDrop
                    || px < surface.getMinX() || px > surface.getMaxX()
                    || pz < surface.getMinZ() || pz > surface.getMaxZ()) continue;
            if (!world.hasCollisionsIn(new BoundingBox(px - .29, feet + .01, pz - .29, px + .29, feet + .98, pz + .29))) {
                return new Location(world, px, feet, pz);
            }
        }
        return null;
    }

    private static float face(Location from, Location toward) {
        return (float) Math.atan2(toward.getX() - from.getX(), toward.getZ() - from.getZ());
    }

    private Transformation pose(Piece piece) {
        float arc = (float) Math.sin(2 * Math.PI * hopFrame / HOP_FRAMES);
        Quaternionf turn = new Quaternionf().rotateY(facing);
        Vector3f offset = new Vector3f(piece.x(), piece.y() + hopHeight(hopFrame), piece.z() + arc * piece.swing() * .07f);
        turn.transform(offset);
        return new Transformation(offset, turn, new Vector3f(piece.w(), piece.h(), piece.d()), new Quaternionf());
    }
}
