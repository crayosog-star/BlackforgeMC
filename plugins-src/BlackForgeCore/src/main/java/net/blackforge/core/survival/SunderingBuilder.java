package net.blackforge.core.survival;

import net.blackforge.core.build.Canvas;

import java.util.Random;

import static net.blackforge.core.survival.SunderingMath.*;

/** The Sundering's arena: a huge floating forge floor with five sunken pits, each ringed by four chain pillars. */
public final class SunderingBuilder {
    private SunderingBuilder() {}

    public static Canvas build() {
        Canvas c = new Canvas();
        Random rnd = new Random(1313);
        for (int dx = -RADIUS; dx <= RADIUS; dx++)
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                double r = Math.hypot(dx, dz);
                if (r > RADIUS + 0.5) continue;
                int x = CX + dx, z = CZ + dz;
                boolean pit = inPit(x, z);
                c.set(x, pit ? Y - 1 : Y, z, pit ? pitFloor(rnd) : floor(r, rnd));
                c.set(x, Y - (pit ? 2 : 1), z, "minecraft:blackstone");
                int depth = 2 + (int) ((RADIUS - r) / 12) + rnd.nextInt(3);
                c.fill(x, Y - depth - 2, z, x, Y - 3, z, "minecraft:basalt");
                if (r > RADIUS - 1.5) {   // a rim too tall to hop over; a hard knock can still throw you off, which lands you back in the plaza
                    c.set(x, Y + 1, z, "minecraft:polished_blackstone_bricks");
                    c.set(x, Y + 2, z, "minecraft:polished_blackstone_wall");
                }
            }
        for (int i = 0; i < PITS; i++) for (int j = 0; j < CHAINS; j++) pillar(c, anchor(i, j));
        return c;
    }

    private static String floor(double r, Random rnd) {
        if (r >= 13 && r < 15) return "minecraft:gold_block";   // the plaza ring, where everyone arrives
        if (r < 13) return rnd.nextInt(5) == 0 ? "minecraft:gilded_blackstone" : "minecraft:polished_blackstone_bricks";
        int roll = rnd.nextInt(40);
        return roll == 0 ? "minecraft:shroomlight" : roll < 8 ? "minecraft:cracked_polished_blackstone_bricks" : roll < 16 ? "minecraft:basalt" : "minecraft:blackstone";
    }

    private static String pitFloor(Random rnd) {
        return rnd.nextInt(7) == 0 ? "minecraft:shroomlight" : "minecraft:crimson_nylium";
    }

    private static void pillar(Canvas c, int[] a) {
        c.fill(a[0] - 1, Y, a[1] - 1, a[0] + 1, Y + PILLAR_HEIGHT - 1, a[1] + 1, "minecraft:polished_blackstone_bricks");
        c.fill(a[0] - 1, Y + PILLAR_HEIGHT, a[1] - 1, a[0] + 1, Y + PILLAR_HEIGHT, a[1] + 1, "minecraft:gold_block");
        c.setLate(a[0], Y + PILLAR_HEIGHT + 1, a[1], "minecraft:soul_lantern");
    }
}
