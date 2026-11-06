package net.blackforge.core.survival;

import net.blackforge.core.survival.AllayRules.Cost;
import net.blackforge.core.survival.AllayRules.DropSkip;
import net.blackforge.core.survival.AllayRules.Table;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-logic tests for Forge Allay tiers, drop skipping, sale fees and trip speed (no server needed). */
class AllayRulesTest {
    private static final double EPS = 1e-9;

    @Test void defaultTable() {
        assertEquals(1, Table.DEFAULT.flock(1));
        assertEquals(3, Table.DEFAULT.flock(3));
        assertEquals(12, Table.DEFAULT.leash(1));
        assertEquals(28, Table.DEFAULT.leash(3));
        assertEquals(new Cost(75_000, 256), Table.DEFAULT.cost(1));
        assertEquals(new Cost(250_000, 1_024), Table.DEFAULT.cost(2));
        assertEquals(new Cost(750_000, 4_096), Table.DEFAULT.cost(3));
    }

    @Test void tiersOutOfRangeThrow() {
        assertThrows(IllegalArgumentException.class, () -> Table.DEFAULT.flock(0));
        assertThrows(IllegalArgumentException.class, () -> Table.DEFAULT.leash(4));
        assertThrows(IllegalArgumentException.class, () -> Table.DEFAULT.cost(0));
        assertThrows(IllegalArgumentException.class, () -> AllayRules.canSort(0));
        assertThrows(IllegalArgumentException.class, () -> AllayRules.canSell(4));
    }

    @Test void tableNeedsOneEntryPerTier() {
        assertThrows(IllegalArgumentException.class, () -> new Table(new int[]{1, 2}, new int[3], new int[3], new int[3]));
    }

    @Test void jobsUnlockByTier() {
        assertFalse(AllayRules.canSort(1));
        assertFalse(AllayRules.canRefuel(1));
        assertFalse(AllayRules.canSell(1));
        assertTrue(AllayRules.canSort(2));
        assertTrue(AllayRules.canRefuel(2));
        assertFalse(AllayRules.canSell(2));
        assertTrue(AllayRules.canSort(3));
        assertTrue(AllayRules.canRefuel(3));
        assertTrue(AllayRules.canSell(3));
    }

    @Test void dropSkipping() {
        assertEquals(Optional.empty(), AllayRules.skipDrop(60, 60, false, false));
        assertEquals(Optional.of(DropSkip.TOO_NEW), AllayRules.skipDrop(59, 60, false, false));
        assertEquals(Optional.of(DropSkip.DEATH_DROP), AllayRules.skipDrop(6_000, 60, true, false));
        assertEquals(Optional.of(DropSkip.THROWN_BY_VISITOR), AllayRules.skipDrop(6_000, 60, false, true));
        assertEquals(Optional.of(DropSkip.DEATH_DROP), AllayRules.skipDrop(0, 60, true, true));
    }

    @Test void names() {
        assertTrue(AllayRules.validName("Bob"));
        assertTrue(AllayRules.validName("Smoked Sausage"));
        assertFalse(AllayRules.validName(""));
        assertFalse(AllayRules.validName(null));
        assertFalse(AllayRules.validName("<red>Bob"));
        assertFalse(AllayRules.validName(" Bob"));
        assertFalse(AllayRules.validName("Seventeen letters"));
    }

    @Test void smokedSausageFlips() {
        assertTrue(AllayRules.flipsUpsideDown("smoked sausage"));
        assertTrue(AllayRules.flipsUpsideDown("Smoked Sausage"));
        assertTrue(AllayRules.flipsUpsideDown("SMOKED SAUSAGE"));
        assertTrue(AllayRules.flipsUpsideDown("smokedsausage"));
        assertTrue(AllayRules.flipsUpsideDown("SmokedSausage"));
        assertFalse(AllayRules.flipsUpsideDown("smoked sausages"));
        assertFalse(AllayRules.flipsUpsideDown("sausage"));
        assertFalse(AllayRules.flipsUpsideDown(null));
    }

    @Test void saleFee() {
        assertEquals(90.0, AllayRules.netSale(100, 0.10), EPS);
        assertEquals(100.0, AllayRules.netSale(100, 0), EPS);
        assertEquals(100.0, AllayRules.netSale(100, -0.5), EPS);   // a negative fee can't pay extra
        assertEquals(0.0, AllayRules.netSale(100, 1.5), EPS);      // nor can a big one cost more than the sale
    }

    @Test void feeComesOffASaleCrossingTheDailyCap() {
        // 8 at full price and 8 at the over-cap rate make 10, and the fee takes 10% of that
        double gross = SellCatalog.payout(1.0, 1_720, 16, 1_728, 0.25);
        assertEquals(10.0, gross, EPS);
        assertEquals(9.0, AllayRules.netSale(gross, 0.10), EPS);
    }

    @Test void defaultSpeedOnATwelveBlockRun() {
        double trip = AllayRules.tripSeconds(12, 6.0, 1.0);
        assertEquals(5.0, trip, EPS);
        assertEquals(3.2, AllayRules.itemsPerSecond(16, trip), EPS);
    }

    @Test void speedMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> AllayRules.tripSeconds(12, 0, 1));
    }

    @Test void brokenFileNeverLandsOnAnOlderCopy() {
        assertEquals("allays.yml.broken", AllayRules.brokenName("allays.yml", 42, n -> false));
        assertEquals("allays.yml.broken-42", AllayRules.brokenName("allays.yml", 42, "allays.yml.broken"::equals));
        java.util.Set<String> taken = java.util.Set.of("allays.yml.broken", "allays.yml.broken-42", "allays.yml.broken-42-2");
        assertEquals("allays.yml.broken-42-3", AllayRules.brokenName("allays.yml", 42, taken::contains));
    }
}
