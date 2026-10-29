package net.alloymc.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-logic tests for milestones and the guide (no server needed). */
class JourneyTest {

    @Test void milestonesClimb() {
        for (int i = 1; i < Journey.MILESTONES.size(); i++) {
            Journey.Milestone a = Journey.MILESTONES.get(i - 1), b = Journey.MILESTONES.get(i);
            assertTrue(b.minutes() > a.minutes(), b.name() + " comes later");
            assertTrue(b.money() > a.money() && b.scrap() > a.scrap(), b.name() + " pays more");
        }
    }

    @Test void nextMilestone() {
        assertEquals("Spark", Journey.next(0).name());
        assertEquals("Kindled", Journey.next(30).name());   // reached exactly: the next one is up
        assertNull(Journey.next(1_000_000));
    }

    @Test void timeFormat() {
        assertEquals("45m", Journey.time(45));
        assertEquals("2h", Journey.time(120));
        assertEquals("5h 12m", Journey.time(312));
    }

    @Test void menuFitsEveryMilestoneAndItem() {
        assertTrue(Journey.MILESTONES.size() <= 8, "the milestones menu has 8 slots");
        assertTrue(Journey.ITEMS.size() <= 14, "the items page has two rows of 7");
        for (Journey.Guided g : Journey.ITEMS) assertFalse(g.where().isBlank(), g.name());
    }
}
