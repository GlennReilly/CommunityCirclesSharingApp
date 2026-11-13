package circles.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReasonDatesTest {
    private val day = checkNotNull(CalendarDay.parse("2026-11-13"))

    @Test fun aPlaceholderIsWrittenInEveryFormat() {
        val reason = "A new steward cannot vote until ${ReasonDates.token(day)}."
        assertEquals("A new steward cannot vote until {date:$day}.", reason)
        assertEquals("A new steward cannot vote until 2026-11-13.", ReasonDates.plain(reason))
        assertEquals("A new steward cannot vote until 13 Nov 2026.", ReasonDates.format(reason, DateFormat.LONG))
        assertEquals("A new steward cannot vote until 13/11/2026.", ReasonDates.format(reason, DateFormat.DAY_FIRST))
        assertEquals("A new steward cannot vote until 11/13/2026.", ReasonDates.format(reason, DateFormat.MONTH_FIRST))
        assertEquals("A new steward cannot vote until 2026-11-13.", ReasonDates.format(reason, DateFormat.ISO))
    }

    @Test fun severalPlaceholdersAndNoPlaceholderAreFine() {
        val two = "From ${ReasonDates.token(day)} to ${ReasonDates.token(day + 1)}."
        assertEquals("From 13 Nov 2026 to 14 Nov 2026.", ReasonDates.format(two, DateFormat.LONG))
        assertEquals("Nothing to fill in.", ReasonDates.format("Nothing to fill in.", DateFormat.LONG))
        assertEquals("1 Jan 1970", ReasonDates.format("{date:0}", DateFormat.LONG))
        assertEquals("31 Dec 1969", ReasonDates.format("{date:-1}", DateFormat.LONG))
    }

    @Test fun onlyAWellFormedPlaceholderIsReplaced() {
        assertEquals("{date:abc} {date:}", ReasonDates.plain("{date:abc} {date:}"))
        assertTrue(ReasonDates.hasToken("until {date:5}"))
        assertFalse(ReasonDates.hasToken("until day 5"))
    }
}
