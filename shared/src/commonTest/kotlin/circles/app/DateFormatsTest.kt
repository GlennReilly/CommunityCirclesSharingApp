package circles.app

import kotlin.test.Test
import kotlin.test.assertEquals

class DateFormatsTest {
    private fun day(iso: String) = checkNotNull(CalendarDay.parse(iso)) { iso }

    @Test fun everyFormatWritesTheSameDayItsOwnWay() {
        val d = day("2026-11-13")
        assertEquals("13 Nov 2026", DateFormats.show(d, DateFormat.LONG))
        assertEquals("13/11/2026", DateFormats.show(d, DateFormat.DAY_FIRST))
        assertEquals("11/13/2026", DateFormats.show(d, DateFormat.MONTH_FIRST))
        assertEquals("2026-11-13", DateFormats.show(d, DateFormat.ISO))
    }

    @Test fun singleDigitDaysArePaddedInTheNumberFormatsAndPlainInTheLongOne() {
        val d = day("2026-03-05")
        assertEquals("5 Mar 2026", DateFormats.show(d, DateFormat.LONG))
        assertEquals("05/03/2026", DateFormats.show(d, DateFormat.DAY_FIRST))
        assertEquals("03/05/2026", DateFormats.show(d, DateFormat.MONTH_FIRST))
        assertEquals("2026-03-05", DateFormats.show(d, DateFormat.ISO))
    }

    @Test fun leapDaysAndMonthEndsAreRight() {
        assertEquals("29 Feb 2028", DateFormats.show(day("2028-02-29"), DateFormat.LONG))
        assertEquals("1 Mar 2028", DateFormats.show(day("2028-02-29") + 1, DateFormat.LONG))
        assertEquals("28 Feb 2027", DateFormats.show(day("2027-02-28"), DateFormat.LONG))
        assertEquals("1 Mar 2027", DateFormats.show(day("2027-02-28") + 1, DateFormat.LONG))
        assertEquals("01/03/1900", DateFormats.show(day("1900-02-28") + 1, DateFormat.DAY_FIRST), "1900 was not a leap year")
        assertEquals("29/02/2000", DateFormats.show(day("2000-02-29"), DateFormat.DAY_FIRST), "2000 was")
        assertEquals("31 Dec 2026", DateFormats.show(day("2026-12-31"), DateFormat.LONG))
        assertEquals("1 Jan 2027", DateFormats.show(day("2026-12-31") + 1, DateFormat.LONG))
        assertEquals("30 Apr 2026", DateFormats.show(day("2026-04-30"), DateFormat.LONG))
    }

    @Test fun everyMonthHasItsEnglishName() {
        val names = (1..12).map { m -> DateFormats.show(day("2026-" + m.toString().padStart(2, '0') + "-15"), DateFormat.LONG).split(' ')[1] }
        assertEquals(listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"), names)
    }

    @Test fun theEpochAndEarlyYearsArePadded() {
        assertEquals("1 Jan 1970", DateFormats.show(0, DateFormat.LONG))
        assertEquals("1970-01-01", DateFormats.show(0, DateFormat.ISO))
        assertEquals("0999-12-31", DateFormats.show(day("0999-12-31"), DateFormat.ISO))
    }

    @Test fun aRangeJoinsTwoDatesInTheChosenFormat() {
        val from = day("2026-11-13"); val to = day("2026-11-15")
        assertEquals("13 Nov 2026 to 15 Nov 2026", DateFormats.showRange(from, to, DateFormat.LONG))
        assertEquals("13/11/2026 to 15/11/2026", DateFormats.showRange(from, to, DateFormat.DAY_FIRST))
    }

    @Test fun theIsoFormatMatchesTheRawFormatterForEveryDay() {
        for (d in listOf(-1, 0, 19_000, 20_000, 20_759, 50_000)) assertEquals(CalendarDay.format(d), DateFormats.show(d, DateFormat.ISO))
    }

    @Test fun keysRoundTripAndUnknownOnesGiveTheDefault() {
        for (f in DateFormat.entries) assertEquals(f, DateFormat.fromKey(f.key))
        assertEquals(DateFormat.LONG, DateFormat.fromKey(null))
        assertEquals(DateFormat.LONG, DateFormat.fromKey("klingon"))
        assertEquals(DateFormat.LONG, DateFormat.DEFAULT)
    }
}
