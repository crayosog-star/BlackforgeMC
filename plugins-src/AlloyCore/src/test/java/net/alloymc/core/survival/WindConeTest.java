package net.alloymc.core.survival;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-logic tests for the Gale Bellows cone and push maths (no server needed). */
class WindConeTest {
    // Looking straight along +X (east), level.
    private static final double LX = 1, LY = 0, LZ = 0;
    // A player relative to the caster's eyes when both stand on the same floor: feet 1.62 below, head ~0.18 above.
    private static final double FEET = -1.62, HEAD = 0.18;

    private static double[] at(double distance, double angleDeg) {
        double r = Math.toRadians(angleDeg);
        return new double[] {distance * Math.cos(r), distance * Math.sin(r)};
    }

    @Test void pushesPlayerTwoBlocksAhead() {
        assertTrue(WindCone.hits(LX, LY, LZ, 2.0, 0, FEET, HEAD));
    }

    @Test void pushesPlayerRightInFront() {
        // centre-only checks miss this one: from 1 block away the body centre is ~36 degrees below eye level
        assertTrue(WindCone.hits(LX, LY, LZ, 1.0, 0, FEET, HEAD));
    }

    @Test void missesPlayerThreeBlocksAhead() {
        assertFalse(WindCone.hits(LX, LY, LZ, 3.0, 0, FEET, HEAD));
    }

    @Test void coneEdgeIsThirtyFiveDegrees() {
        double[] in = at(2.0, 34), out = at(2.0, 36);
        assertTrue(WindCone.inCone(LX, LY, LZ, in[0], 0, in[1]));
        assertFalse(WindCone.inCone(LX, LY, LZ, out[0], 0, out[1]));
    }

    @Test void rangeEdge() {
        assertTrue(WindCone.inCone(LX, LY, LZ, 2.5, 0, 0));
        assertFalse(WindCone.inCone(LX, LY, LZ, 2.7, 0, 0));
    }

    @Test void missesPlayerFortyFiveDegreesToTheSide() {
        double[] p = at(1.5, 45);
        assertFalse(WindCone.hits(LX, LY, LZ, p[0], p[1], FEET, HEAD));
    }

    @Test void neverHitsBehind() {
        assertFalse(WindCone.hits(LX, LY, LZ, -1.0, 0, FEET, HEAD));
    }

    @Test void offCentreTargetsFanOutward() {
        double[] p = at(2.0, 20);
        double[] v = WindCone.push(p[0], p[1], LX, LZ, 0, false, false);
        assertTrue(v[2] > 0, "a target to the right is pushed further right, not straight ahead");
        assertEquals(WindCone.PUSH, Math.hypot(v[0], v[2]), 1e-9);
        assertEquals(WindCone.LIFT, v[1], 1e-9);
    }

    @Test void knockbackResistanceScalesPush() {
        double[] full = WindCone.push(2, 0, LX, LZ, 0, false, false);
        double[] tank = WindCone.push(2, 0, LX, LZ, 0.4, false, false);
        assertEquals(full[0] * 0.6, tank[0], 1e-9);
        assertEquals(full[1] * 0.6, tank[1], 1e-9);
        double[] immune = WindCone.push(2, 0, LX, LZ, 1.0, false, false);
        assertEquals(full[0] * WindCone.MIN_SCALE, immune[0], 1e-9);   // still knocked back at least 3 blocks
    }

    @Test void neverBelowTheFloor() {
        double[] full = WindCone.push(2, 0, LX, LZ, 0, false, false);
        double[] worst = WindCone.push(2, 0, LX, LZ, 0.4, false, true);   // netherite + juggled = 0.3 before the floor
        assertEquals(full[0] * WindCone.MIN_SCALE, worst[0], 1e-9);
        assertEquals(full[1] * WindCone.MIN_SCALE, worst[1], 1e-9);
    }

    @Test void juggledTargetsTakeHalf() {
        double[] first = WindCone.push(2, 0, LX, LZ, 0, false, false);
        double[] again = WindCone.push(2, 0, LX, LZ, 0, false, true);
        assertEquals(first[0] * WindCone.JUGGLE_FACTOR, again[0], 1e-9);
    }

    @Test void airborneTargetsGetNoLift() {
        assertEquals(0, WindCone.push(2, 0, LX, LZ, 0, true, false)[1], 1e-9);
    }

    @Test void targetDirectlyAboveUsesLookDirection() {
        double[] v = WindCone.push(0, 0, 0, 1, 0, false, false);
        assertEquals(0, v[0], 1e-9);
        assertEquals(WindCone.PUSH, v[2], 1e-9);
    }
}
