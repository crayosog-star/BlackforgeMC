package net.alloymc.core;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-logic tests for the daily calendar, the event calendar and achievements (no server needed). */
class ProgressionTest {

    private static YamlConfiguration cal(String last, int next) {
        YamlConfiguration y = new YamlConfiguration();
        if (last != null) y.set("calendar-last", last);
        y.set("calendar-next", next);
        return y;
    }

    @Test void calendarStartsAtDayOne() {
        assertEquals(1, Journey.calendarDay(new YamlConfiguration()));
        assertTrue(Journey.calendarReady(new YamlConfiguration()));
    }

    @Test void calendarContinuesFromYesterday() {
        assertEquals(4, Journey.calendarDay(cal(LocalDate.now().minusDays(1).toString(), 4)));
    }

    @Test void calendarResetsAfterAMissedDay() {
        assertEquals(1, Journey.calendarDay(cal(LocalDate.now().minusDays(2).toString(), 5)));
    }

    @Test void calendarShowsTodaysClaimedDay() {
        YamlConfiguration y = cal(LocalDate.now().toString(), 1);   // claimed day 7 today, so day 1 is next
        assertFalse(Journey.calendarReady(y));
        assertEquals(7, Journey.calendarDay(y));
        assertEquals(3, Journey.calendarDay(cal(LocalDate.now().toString(), 4)));
    }

    @Test void calendarRewardsClimb() {
        assertEquals(7, Journey.CAL_MONEY.length);
        assertEquals(7, Journey.CAL_SCRAP.length);
        for (int i = 1; i < 7; i++) assertTrue(Journey.CAL_MONEY[i] > Journey.CAL_MONEY[i - 1]);
    }

    @Test void weeklyEventsAreAlwaysComingUp() {
        long now = System.currentTimeMillis();
        var list = Events.upcoming(now, 7);
        for (Events.Weekly w : Events.WEEKLY) assertTrue(list.stream().anyMatch(s -> s.id().equals(w.id())), w.id() + " within a week");
        for (int i = 1; i < list.size(); i++) assertTrue(list.get(i).start() >= list.get(i - 1).start(), "soonest first");
        for (var s : list) assertTrue(s.end() > now, s.id() + " not over");
    }

    @Test void doubleScrapOnlyDuringItsWindow() {
        double m = Events.scrapMultiplier("survival");
        assertTrue(m == 1 || m == 2);
        boolean live = Events.upcoming(System.currentTimeMillis(), 1).stream()
                .anyMatch(s -> s.live(System.currentTimeMillis()) && s.id().startsWith("scrap"));
        assertEquals(live ? 2.0 : 1.0, m);
    }

    @Test void achievementsAreUniqueAndFitTheMenu() {
        Set<String> ids = new HashSet<>();
        for (Achievements.A a : Achievements.ALL) {
            assertTrue(ids.add(a.id()), "duplicate " + a.id());
            assertTrue(a.goal() > 0 && a.money() > 0 && a.scrap() > 0, a.id());
        }
        for (Achievements.Cat c : Achievements.Cat.values())
            assertTrue(Achievements.ALL.stream().filter(a -> a.cat() == c).count() <= 45, c + " fits one page");
        assertTrue(Achievements.FORGE_MASTER_AT < Achievements.ALL.size());
    }

    @Test void whereLinesCoverEveryAbility() {
        for (String id : new String[]{"gale_bellows", "surge_item", "darkness_potion", "gaia_potion", "rimelock", "ashen_chain", "ashen_bonfire", "gods_eye", "emp_item", "last_rite"})
            assertTrue(ItemSkins.WHERE.containsKey(id), id);
    }
}
