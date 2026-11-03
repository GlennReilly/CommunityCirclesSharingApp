package circles.app

/**
 * Days since 1970-01-01 (what events carry) and the `YYYY-MM-DD` text of a date input. Plain integer arithmetic (Howard
 * Hinnant's civil-date algorithms), so it gives the same answers on the JVM and in the browser (spec: integer days).
 */
object CalendarDay {
    private const val DAYS_PER_ERA = 146_097
    private const val YEARS_PER_ERA = 400
    private const val DAYS_BEFORE_1970_FROM_YEAR_0_MARCH = 719_468
    private const val ISO_LENGTH = 10
    private const val MONTHS = 12

    /** The day number of an ISO date like `2026-11-03`, or null if the text is not exactly a real calendar date. */
    fun parse(iso: String): Int? {
        if (iso.length != ISO_LENGTH || iso[4] != '-' || iso[7] != '-') return null
        val year = iso.substring(0, 4).toIntOrNull()?.takeIf { iso.substring(0, 4).all { c -> c in '0'..'9' } } ?: return null
        val month = iso.substring(5, 7).toIntOrNull()?.takeIf { iso.substring(5, 7).all { c -> c in '0'..'9' } } ?: return null
        val day = iso.substring(8, 10).toIntOrNull()?.takeIf { iso.substring(8, 10).all { c -> c in '0'..'9' } } ?: return null
        if (month !in 1..MONTHS || day !in 1..daysInMonth(year, month)) return null
        return fromCivil(year, month, day)
    }

    /** `YYYY-MM-DD` for a day number (years 0000 to 9999). */
    fun format(days: Int): String {
        val (y, m, d) = toCivil(days)
        return "${y.toString().padStart(4, '0')}-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
    }

    private fun daysInMonth(year: Int, month: Int): Int = when (month) {
        2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    private fun fromCivil(year: Int, month: Int, day: Int): Int {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - (YEARS_PER_ERA - 1)) / YEARS_PER_ERA
        val yoe = y - era * YEARS_PER_ERA
        val doy = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * DAYS_PER_ERA + doe - DAYS_BEFORE_1970_FROM_YEAR_0_MARCH
    }

    private fun toCivil(days: Int): Triple<Int, Int, Int> {
        val z = days + DAYS_BEFORE_1970_FROM_YEAR_0_MARCH
        val era = (if (z >= 0) z else z - (DAYS_PER_ERA - 1)) / DAYS_PER_ERA
        val doe = z - era * DAYS_PER_ERA
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val day = doy - (153 * mp + 2) / 5 + 1
        val month = mp + (if (mp < 10) 3 else -9)
        val year = yoe + era * YEARS_PER_ERA + (if (month <= 2) 1 else 0)
        return Triple(year, month, day)
    }
}
