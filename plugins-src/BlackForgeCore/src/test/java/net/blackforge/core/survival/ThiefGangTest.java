package net.blackforge.core.survival;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-logic tests for the Night Thieves (no server needed). */
class ThiefGangTest {
    private static final double EPS = 1e-9;

    @Test void nightIsDuskToDawn() {
        assertFalse(ThiefGang.isNight(6000));
        assertFalse(ThiefGang.isNight(12999));
        assertTrue(ThiefGang.isNight(13000));
        assertTrue(ThiefGang.isNight(18000));
        assertFalse(ThiefGang.isNight(23000));
        assertTrue(ThiefGang.isNight(24000L * 50 + 18000));   // full time keeps counting past a day
    }

    @Test void fivePercentRoll() {
        assertTrue(ThiefGang.ambush(0.049, 0.05));
        assertFalse(ThiefGang.ambush(0.05, 0.05));
        assertFalse(ThiefGang.ambush(0.9, 0.05));
    }

    @Test void gearScoreRanksMaterials() {
        double none = ThiefGang.gearScore(new String[]{null, null, null, null}, null, 0);
        double leather = ThiefGang.gearScore(new String[]{"LEATHER_HELMET", "LEATHER_CHESTPLATE", "LEATHER_LEGGINGS", "LEATHER_BOOTS"}, "WOODEN_SWORD", 0);
        double iron = ThiefGang.gearScore(new String[]{"IRON_HELMET", "IRON_CHESTPLATE", "IRON_LEGGINGS", "IRON_BOOTS"}, "IRON_SWORD", 0);
        double neth = ThiefGang.gearScore(new String[]{"NETHERITE_HELMET", "NETHERITE_CHESTPLATE", "NETHERITE_LEGGINGS", "NETHERITE_BOOTS"}, "NETHERITE_SWORD", 0);
        assertEquals(0, none, EPS);
        assertTrue(none < leather && leather < iron && iron < neth);
        assertEquals(27.5, neth, EPS);
        assertEquals(27.5 + 10 * ThiefGang.PER_ENCHANT_LEVEL, ThiefGang.gearScore(new String[]{"NETHERITE_HELMET", "NETHERITE_CHESTPLATE", "NETHERITE_LEGGINGS", "NETHERITE_BOOTS"}, "NETHERITE_SWORD", 10), EPS);
        assertEquals(0, ThiefGang.gearScore(null, "DIAMOND", 0), EPS);   // a diamond in hand is not a weapon
    }

    @Test void weaponsAreRecognised() {
        assertTrue(ThiefGang.isWeapon("DIAMOND_SWORD"));
        assertTrue(ThiefGang.isWeapon("NETHERITE_AXE"));
        assertTrue(ThiefGang.isWeapon("MACE"));
        assertTrue(ThiefGang.isWeapon("TRIDENT"));
        assertFalse(ThiefGang.isWeapon("DIAMOND_PICKAXE"));
        assertFalse(ThiefGang.isWeapon("BOW"));
        assertFalse(ThiefGang.isWeapon(null));
    }

    @Test void soloIsAlwaysOneThiefAtOneAndAHalf() {
        assertEquals(1, ThiefGang.gangSize(1, 0, 12, 8));
        assertEquals(1, ThiefGang.gangSize(1, 40, 12, 8));
        assertEquals(1.5, ThiefGang.multiplier(1, 0, 1.5, 0.08, 0.015, 2.5), EPS);
        assertEquals(1.5, ThiefGang.multiplier(1, 40, 1.5, 0.08, 0.015, 2.5), EPS);
    }

    @Test void moreAndBetterGearedPlayersMeanABiggerTougherGang() {
        assertEquals(2, ThiefGang.gangSize(2, 5, 12, 8));
        assertEquals(3, ThiefGang.gangSize(2, 16.5, 12, 8));   // iron
        assertEquals(4, ThiefGang.gangSize(2, 27.5, 12, 8));   // netherite
        assertEquals(8, ThiefGang.gangSize(10, 40, 12, 8));    // capped
        double few = ThiefGang.multiplier(2, 10, 1.5, 0.08, 0.015, 2.5);
        double many = ThiefGang.multiplier(5, 10, 1.5, 0.08, 0.015, 2.5);
        double geared = ThiefGang.multiplier(5, 35, 1.5, 0.08, 0.015, 2.5);
        assertTrue(1.5 < few && few < many && many < geared);
        assertEquals(2.5, ThiefGang.multiplier(20, 60, 1.5, 0.08, 0.015, 2.5), EPS);
    }

    @Test void aThirdAreMages() {
        assertEquals(1, ThiefGang.mages(3, 3, 0.99));
        assertEquals(1, ThiefGang.mages(3, 3, 0.0));
        assertEquals(2, ThiefGang.mages(6, 3, 0.99));
        assertEquals(1, ThiefGang.mages(1, 3, 0.2));     // a lone thief is a mage a third of the time
        assertEquals(0, ThiefGang.mages(1, 3, 0.4));
        assertEquals(2, ThiefGang.mages(4, 3, 0.1));     // 1 whole + the leftover third
        assertEquals(1, ThiefGang.mages(4, 3, 0.5));
        assertEquals(0, ThiefGang.mages(0, 3, 0.0));
    }

    @Test void rewardsScaleWithTheFight() {
        assertEquals(225, ThiefGang.rewardEach(150, 1, 1.5, 1), EPS);
        assertTrue(ThiefGang.rewardEach(150, 4, 2.0, 2) > ThiefGang.rewardEach(150, 1, 1.5, 1));
        assertEquals(0, ThiefGang.rewardEach(150, 1, 1.5, 0), EPS);
        assertEquals(0.04, ThiefGang.keyChance(0.04, 1.5, 1.5), EPS);
        assertTrue(ThiefGang.keyChance(0.04, 2.0, 1.5) > 0.04);
        assertEquals(0.12, ThiefGang.keyChance(0.04, 10, 1.5), EPS);   // at most triple
    }

    @Test void thievesGrowInWithoutPopping() {
        assertEquals(0.1, ThiefGang.growIn(0), EPS);
        assertEquals(1, ThiefGang.growIn(ThiefGang.GROW_TICKS), EPS);
        assertEquals(1, ThiefGang.growIn(ThiefGang.GROW_TICKS + 4), EPS);
        double prev = ThiefGang.growIn(0), prevStep = Double.MAX_VALUE;
        for (int t = 1; t <= ThiefGang.GROW_TICKS; t++) {
            double s = ThiefGang.growIn(t), step = s - prev;
            assertTrue(step > 0 && step < prevStep, "grows, easing out");
            assertTrue(step < 0.5, "no jump between ticks");
            prev = s; prevStep = step;
        }
    }

    @Test void fleeSpeedRampsSmoothly() {
        assertEquals(0, ThiefGang.fleeBoost(0, 0.4), EPS);
        assertEquals(0.4, ThiefGang.fleeBoost(ThiefGang.FLEE_RAMP, 0.4), EPS);
        assertEquals(0.4, ThiefGang.fleeBoost(100, 0.4), EPS);   // no overshoot
        double prev = 0;
        for (int t = 1; t <= ThiefGang.FLEE_RAMP; t++) {
            double b = ThiefGang.fleeBoost(t, 0.4);
            assertTrue(b > prev);
            assertTrue(b - prev < 0.1, "no jump between ticks");
            prev = b;
        }
    }

    @Test void hexBoltChargesThenWhips() {
        assertEquals(0, ThiefGang.charge(0), EPS);
        assertEquals(1, ThiefGang.charge(ThiefGang.CHARGE), EPS);
        assertTrue(ThiefGang.charge(ThiefGang.CHARGE / 2) < 0.2, "builds slowly at first");
        assertTrue(ThiefGang.boltSpeed(0) > 0 && ThiefGang.boltSpeed(0) < 0.1, "crawls off");
        double prev = 0;
        for (int t = 0; t <= ThiefGang.RAMP + 5; t++) {
            double v = ThiefGang.boltSpeed(t);
            assertTrue(v >= prev, "never slows down");
            assertTrue(v <= ThiefGang.TOP_SPEED + EPS, "never past top speed");
            prev = v;
        }
        assertEquals(ThiefGang.TOP_SPEED, ThiefGang.boltSpeed(ThiefGang.RAMP), EPS);
    }

    @Test void travelTimeMatchesTheRamp() {
        assertEquals(0, ThiefGang.travelTicks(0), EPS);
        double t = ThiefGang.travelTicks(20);
        double covered = 0;
        int whole = (int) Math.floor(t);
        for (int i = 0; i < whole; i++) covered += ThiefGang.boltSpeed(i);
        covered += (t - whole) * ThiefGang.boltSpeed(whole);
        assertEquals(20, covered, 1e-6);
        assertTrue(ThiefGang.travelTicks(30) > ThiefGang.travelTicks(10));
    }

    @Test void leadsAMovingTarget() {
        double[] still = ThiefGang.lead(0, 0, 15, 1);
        assertEquals(0, still[0], EPS);
        assertEquals(0, still[1], EPS);
        double[] full = ThiefGang.lead(0.2, 0, 15, 1), half = ThiefGang.lead(0.2, 0, 15, 0.5);
        assertTrue(full[0] > half[0] && half[0] > 0);
        assertEquals((ThiefGang.travelTicks(15) + ThiefGang.LOCK) * 0.2, full[0], EPS);
    }
}
