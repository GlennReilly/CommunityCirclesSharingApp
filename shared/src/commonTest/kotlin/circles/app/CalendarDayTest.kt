package circles.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CalendarDayTest {
    @Test fun theEpochIsDayZero() {
        assertEquals(0, CalendarDay.parse("1970-01-01"))
        assertEquals("1970-01-01", CalendarDay.format(0))
    }

    @Test fun knownDates() {
        // 20_759 is 2026-11-02, the day the tests' clock is set to.
        assertEquals(20_759, CalendarDay.parse("2026-11-02"))
        assertEquals("2026-11-02", CalendarDay.format(20_759))
        assertEquals(19_782, CalendarDay.parse("2024-02-29"))
        assertEquals(11_016, CalendarDay.parse("2000-02-29"))
        assertEquals(-1, CalendarDay.parse("1969-12-31"))
        assertEquals(47_541, CalendarDay.parse("2100-03-01"))
    }

    @Test fun everyDayForSeveralCenturiesRoundTrips() {
        for (day in -40_000..90_000 step 7) assertEquals(day, CalendarDay.parse(CalendarDay.format(day)), "day $day")
        for (day in 20_000..21_500) assertEquals(day, CalendarDay.parse(CalendarDay.format(day)), "day $day")
    }

    @Test fun consecutiveDaysAreConsecutiveDates() {
        assertEquals("2024-02-29", CalendarDay.format(CalendarDay.parse("2024-02-28")!! + 1))
        assertEquals("2024-03-01", CalendarDay.format(CalendarDay.parse("2024-02-28")!! + 2))
        assertEquals("2025-03-01", CalendarDay.format(CalendarDay.parse("2025-02-28")!! + 1))
        assertEquals("2027-01-01", CalendarDay.format(CalendarDay.parse("2026-12-31")!! + 1))
        assertEquals("2100-03-01", CalendarDay.format(CalendarDay.parse("2100-02-28")!! + 1), "2100 is not a leap year")
    }

    @Test fun textThatIsNotARealDateIsRefused() {
        for (bad in listOf("", "2026-1-2", "2026-13-01", "2026-00-10", "2026-02-29", "2023-02-29", "1900-02-29", "2026-04-31", "2026-11-00", "2026-11-31",
            "26-11-02", "2026/11/02", "2026-11-02 ", " 2026-11-02", "20261102", "abcd-ef-gh", "+026-11-02", "2026-+1-02", "2026-11-+2")) {
            assertNull(CalendarDay.parse(bad), bad)
        }
    }
}
