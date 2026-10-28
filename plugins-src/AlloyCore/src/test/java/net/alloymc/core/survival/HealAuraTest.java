package net.alloymc.core.survival;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-logic tests for the Ashen Bonfire healing ring (no server needed). */
class HealAuraTest {
    private static final double EPS = 1e-9;

    @Test void healsInsideTheRing() {
        assertTrue(HealAura.inRange(0, 0, 0, 4));
        assertTrue(HealAura.inRange(3.9, 0, 0, 4));
        assertTrue(HealAura.inRange(2.8, 0, 2.8, 4));   // 3.96 away diagonally
    }

    @Test void notOutsideIt() {
        assertFalse(HealAura.inRange(4.1, 0, 0, 4));
        assertFalse(HealAura.inRange(2.9, 0, 2.9, 4));  // 4.1 away diagonally
    }

    @Test void heightLimits() {
        assertTrue(HealAura.inRange(1, 2.9, 0, 4));
        assertFalse(HealAura.inRange(1, 3.1, 0, 4));    // up on a pillar above it
        assertTrue(HealAura.inRange(1, -1.4, 0, 4));
        assertFalse(HealAura.inRange(1, -1.6, 0, 4));   // down a hole
    }

    @Test void healNeverOverfills() {
        assertEquals(2.0, HealAura.heal(10, 20, 2), EPS);
        assertEquals(1.0, HealAura.heal(19, 20, 2), EPS);
        assertEquals(0.0, HealAura.heal(20, 20, 2), EPS);
    }

    @Test void neverHealsTheDead() {
        assertEquals(0.0, HealAura.heal(0, 20, 2), EPS);
    }

    @Test void hitBarShowsLeftAndTaken() {
        String bar = HealAura.hitBar(3, 5);
        assertEquals(3, bar.split("<#FFB347>", -1).length - 1);
        assertEquals(2, bar.split("<dark_gray>", -1).length - 1);
    }
}
