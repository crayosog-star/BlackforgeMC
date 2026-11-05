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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** One player session. The model is built entirely from displays, so it has no mob AI or interactive hitbox. */
final class OrangeCompanion {
    static final String TAG = "bf_orange_companion";
    static final String INTRO = "blackforge:pet.orange_companion";
    private static final float TURN_RATE = .45f, TURN_DEADBAND = .12f;
    private static final double GAG_CHANCE = .4;
    private static final PetAnim.Gag[] IDLE_GAGS = {PetAnim.Gag.WIGGLE, PetAnim.Gag.BACKFLIP, PetAnim.Gag.SPIN, PetAnim.Gag.FACEPLANT, PetAnim.Gag.FLAIL};

    private static final long INTRO_DELAY = Duration.ofSeconds(10).toNanos();
    private static final long MIN_GAP = Duration.ofSeconds(30).toNanos(), MAX_GAP = Duration.ofSeconds(100).toNanos();
    static final List<String> LINES = List.of(
            INTRO,
            "blackforge:pet.in_the_smoke",
            "blackforge:pet.you_good_gang",
            "blackforge:pet.cooked",
            "blackforge:pet.on_my_mama",
            "blackforge:pet.run_it_back",
            "blackforge:pet.let_bro_cook",
            "blackforge:pet.lock_in",
            "blackforge:pet.got_your_back",
            "blackforge:pet.thats_crazy",
            "blackforge:pet.we_outside",
            "blackforge:pet.say_less",
            "blackforge:pet.check_the_corner"
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

    private enum Part { BODY, ARM, FOOT, EYE }

    /** Local coordinates with +Z facing forward. Swing is positive on the left foot and right arm. */
    private record Piece(Material material, float x, float y, float z, float w, float h, float d, int swing, Part part) {}
    private static final List<Piece> MODEL = List.of(
            new Piece(Material.ORANGE_TERRACOTTA, -.18f, .15f, -.135f, .36f, .28f, .27f, 0, Part.BODY),
            new Piece(Material.ORANGE_CONCRETE, -.245f, .375f, -.17f, .49f, .34f, .34f, 0, Part.BODY),
            new Piece(Material.ORANGE_TERRACOTTA, -.26f, .43f, -.145f, .52f, .22f, .29f, 0, Part.BODY),
            new Piece(Material.ORANGE_CONCRETE, -.28f, .21f, -.08f, .095f, .19f, .15f, -1, Part.ARM),
            new Piece(Material.ORANGE_CONCRETE, .185f, .21f, -.08f, .095f, .19f, .15f, 1, Part.ARM),
            new Piece(Material.BROWN_TERRACOTTA, -.155f, 0, -.06f, .12f, .155f, .19f, 1, Part.FOOT),
            new Piece(Material.BROWN_TERRACOTTA, .035f, 0, -.06f, .12f, .155f, .19f, -1, Part.FOOT),
            new Piece(Material.BLACK_CONCRETE, -.148f, .535f, .17f, .055f, .078f, .018f, 0, Part.EYE),
            new Piece(Material.BLACK_CONCRETE, .093f, .535f, .17f, .055f, .078f, .018f, 0, Part.EYE),
            new Piece(Material.BROWN_CONCRETE, -.042f, .453f, .17f, .084f, .058f, .018f, 0, Part.BODY)
    );

    private enum Mode { REST, HOP, GAG }

    private final List<BlockDisplay> displays = new ArrayList<>(MODEL.size());
    private Location anchor;
    private Location goal;
    private float facing;
    private Mode mode = Mode.REST;
    private PetAnim.Gag gag;
    private PetAnim.Pose current = PetAnim.Pose.REST;
    private int frame, restLeft, idleClock, blockedFrames;
    private boolean chasing, leaping, flailNext, facingChanged;
    private final Random rng;
    private Long nextVoiceAt;
    private String lastLine;
    private final ArrayDeque<String> bag = new ArrayDeque<>();

    List<BlockDisplay> displays() { return displays; }

    OrangeCompanion() { this(new Random()); }

    OrangeCompanion(Random rng) { this.rng = rng; }

    void startVoiceClock(long now) {
        if (nextVoiceAt == null) nextVoiceAt = now + INTRO_DELAY;
    }

    /** The intro first, then a random line every 30-100 seconds. A hidden pet catches up with just one line. */
    String voiceDue(long now) {
        if (nextVoiceAt == null || now < nextVoiceAt) return null;
        nextVoiceAt = now + MIN_GAP + rng.nextLong(MAX_GAP - MIN_GAP + 1);
        lastLine = lastLine == null ? INTRO : nextLine();
        return lastLine;
    }

    /** Works through every line before repeating, and never says the same one twice in a row. */
    private String nextLine() {
        if (bag.isEmpty()) {
            List<String> shuffled = new ArrayList<>(LINES);
            Collections.shuffle(shuffled, rng);
            if (shuffled.getFirst().equals(lastLine)) Collections.swap(shuffled, 0, shuffled.size() - 1);
            bag.addAll(shuffled);
        }
        return bag.poll();
    }

    /** Called by the cosmetics loop every two ticks while the owner is eligible to show a pet. */
    void follow(Location ownerAt, Consumer<BlockDisplay> hideFromOptedOut, BiConsumer<Entity, String> playVoice) {
        boolean rebuild = anchor == null || anchor.getWorld() != ownerAt.getWorld()
                || displays.size() != MODEL.size() || displays.stream().anyMatch(d -> !d.isValid());
        if (rebuild || anchor.distanceSquared(ownerAt) > PetAnim.LEASH * PetAnim.LEASH
                || Math.abs(anchor.getY() - ownerAt.getY()) > 4) {
            Location at = spotAround(ownerAt, PetAnim.ROAM_MIN, 5);
            if (at == null) at = grounded(ownerAt, 2.2);
            if (at == null) removeModel();
            else spawn(at, ownerAt, hideFromOptedOut);
            return;
        }

        String voice = voiceDue(System.nanoTime());
        if (voice != null) {
            playVoice.accept(displays.getFirst(), voice);
            flailNext = true;
        }

        chasing = PetAnim.chasing(chasing, anchor.distance(ownerAt));
        Location step = null;
        PetAnim.Pose next = switch (mode) {
            case REST -> rest(ownerAt);
            case GAG -> gagFrame(ownerAt);
            case HOP -> null;
        };
        if (mode == Mode.HOP) {
            if (chasing && !leaping) {   // fell behind mid-hop: don't wait for the landing to speed up
                startHop(ownerAt, Math.min(frame, PetAnim.hopFrames(true) - 1));
            }
            if (PetAnim.airborne(frame, leaping)) step = hopStep(ownerAt);
            next = PetAnim.hop(frame, leaping);
            faceTravel(step);
            if (++frame >= PetAnim.hopFrames(leaping)) landed(ownerAt);
        }

        if (step == null && next.equals(current) && !facingChanged) return;
        facingChanged = false;
        if (step != null) anchor = step;
        current = next;
        for (int i = 0; i < displays.size(); i++) {
            BlockDisplay part = displays.get(i);
            if (step != null) part.teleport(anchor);
            part.setInterpolationDelay(0);
            part.setTransformation(pose(MODEL.get(i)));
        }
    }

    private PetAnim.Pose rest(Location ownerAt) {
        if (chasing) {
            startHop(ownerAt, 0);
            return null;
        }
        if (flailNext) return startGag(PetAnim.Gag.FLAIL);
        if (--restLeft <= 0) {
            if (rng.nextDouble() < GAG_CHANCE) return startGag(IDLE_GAGS[rng.nextInt(IDLE_GAGS.length)]);
            goal = spotAround(ownerAt, PetAnim.ROAM_MIN, PetAnim.ROAM_MAX);
            if (goal != null) {
                startHop(ownerAt, 0);
                return null;
            }
            restLeft = 5;
        }
        float toOwner = face(anchor, ownerAt);
        if (Math.abs(Math.IEEEremainder(toOwner - facing, 2 * Math.PI)) > TURN_DEADBAND) turnTo(toOwner);
        return PetAnim.idle(idleClock++);
    }

    private PetAnim.Pose startGag(PetAnim.Gag chosen) {
        flailNext = false;
        gag = chosen;
        frame = 1;
        mode = Mode.GAG;
        return PetAnim.gag(gag, 0);
    }

    private PetAnim.Pose gagFrame(Location ownerAt) {
        if (chasing) {
            startHop(ownerAt, 0);
            return null;
        }
        PetAnim.Pose pose = PetAnim.gag(gag, frame);
        if (++frame >= gag.frames) restFor(10 + rng.nextInt(25));
        return pose;
    }

    private void startHop(Location ownerAt, int fromFrame) {
        leaping = chasing;
        if (chasing) goal = spotAround(ownerAt, PetAnim.ROAM_MIN, 4.5);
        frame = fromFrame;
        mode = Mode.HOP;
    }

    private Location hopStep(Location ownerAt) {
        if (goal == null || goal.getWorld() != anchor.getWorld()) goal = grounded(ownerAt, 2.2);
        if (goal == null) return null;
        Location step = stepToward(goal, leaping ? PetAnim.LEAP_STEP : PetAnim.STEP);
        blockedFrames = step == null ? blockedFrames + 1 : 0;
        return step;
    }

    private void faceTravel(Location step) {
        if (step != null) turnTo((float) Math.atan2(step.getX() - anchor.getX(), step.getZ() - anchor.getZ()));
    }

    /** Called on touchdown. Bound on, carry on to the roam point, or stop and hang out. */
    private void landed(Location ownerAt) {
        frame = 0;
        boolean blocked = blockedFrames >= 3;
        if (blocked) blockedFrames = 0;
        if (chasing) {
            leaping = true;
            goal = spotAround(ownerAt, PetAnim.ROAM_MIN, 4.5);
            return;
        }
        leaping = false;
        boolean arrived = goal == null || flat(anchor, goal) < .5;
        boolean stale = goal != null && flat(goal, ownerAt) > PetAnim.ROAM_MAX + 1;
        if (arrived || blocked || stale) restFor(10 + rng.nextInt(35));
    }

    private void restFor(int frames) {
        mode = Mode.REST;
        restLeft = frames;
    }

    private void turnTo(float angle) {
        float turned = PetAnim.turnToward(facing, angle, TURN_RATE);
        if (turned != facing) facingChanged = true;
        facing = turned;
    }

    private static double flat(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    /** A random standable spot around the owner, or null if a few tries find no floor. */
    private Location spotAround(Location ownerAt, double min, double max) {
        for (int i = 0; i < 6; i++) {
            double[] offset = PetAnim.roamOffset(rng, min, max);
            Location spot = grounded(ownerAt.clone().add(offset[0], 0, offset[1]), 2.2);
            if (spot != null) return spot;
        }
        return null;
    }

    private void spawn(Location target, Location ownerAt, Consumer<BlockDisplay> hideFromOptedOut) {
        removeModel();
        anchor = target;
        facing = face(anchor, ownerAt);
        goal = null;
        chasing = leaping = false;
        current = PetAnim.Pose.REST;
        blockedFrames = 0;
        restFor(12);
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

    private Location stepToward(Location to, double size) {
        double dx = to.getX() - anchor.getX(), dz = to.getZ() - anchor.getZ();
        double distance = Math.hypot(dx, dz);
        if (distance < .05) return null;
        double fraction = Math.min(1, size / distance);
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

    /** Squash and stretch about the feet, then lean and turn the whole body about its pivot. */
    private Transformation pose(Piece piece) {
        PetAnim.Pose p = current;
        float wide = 1 / (float) Math.sqrt(p.stretch());
        float x = piece.x() * wide, y = piece.y() * p.stretch(), z = piece.z() * wide;
        float h = piece.h() * p.stretch();
        switch (piece.part()) {
            case ARM -> {
                x += Math.signum(piece.x()) * p.armRaise() * .3f;
                y += p.armRaise() + piece.swing() * p.arm() * .05f;
                z += piece.swing() * p.arm() * .07f;
            }
            case FOOT -> z += piece.swing() * p.foot() * .07f;
            case EYE -> {
                y += h * (1 - p.eye()) / 2;
                h *= p.eye();
            }
            case BODY -> { }
        }
        Quaternionf turn = new Quaternionf().rotateY(facing + p.spin()).rotateX(p.pitch()).rotateZ(p.roll());
        Vector3f offset = new Vector3f(x, y - p.pivot(), z);
        turn.transform(offset);
        offset.add(0, p.pivot() + p.lift(), 0);
        return new Transformation(offset, turn, new Vector3f(piece.w() * wide, h, piece.d() * wide), new Quaternionf());
    }
}
