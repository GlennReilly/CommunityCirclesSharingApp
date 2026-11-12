package circles.app

/**
 * How dates are written for people (ADR 0080). A preference of the browser, not part of the circle. [example] is the same fixed
 * date written each way, for the choice list.
 */
enum class DateFormat(val key: String, val label: String) {
    LONG("long", "13 Nov 2026 (default)"),
    DAY_FIRST("day-first", "13/11/2026 (day first)"),
    MONTH_FIRST("month-first", "11/13/2026 (month first)"),
    ISO("iso", "2026-11-13 (year first)");

    companion object {
        val DEFAULT: DateFormat = LONG

        /** The format a stored key names; an unknown or missing key gives the default. */
        fun fromKey(key: String?): DateFormat = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * The one place a day number becomes text for a page (ADR 0080). Month names are a fixed English table, so the JVM and the
 * browser write the same thing whatever the device's language. [CalendarDay.format] (always `YYYY-MM-DD`) stays for what must
 * not change with the setting: stored and sent values, file names and the value of a date input.
 */
object DateFormats {
    private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private const val YEAR_DIGITS = 4
    private const val PART_DIGITS = 2

    private fun two(n: Int) = n.toString().padStart(PART_DIGITS, '0')

    fun show(day: Int, format: DateFormat): String {
        val (year, month, d) = CalendarDay.civil(day)
        val y = year.toString().padStart(YEAR_DIGITS, '0')
        return when (format) {
            DateFormat.LONG -> "$d ${MONTHS[month - 1]} $y"
            DateFormat.DAY_FIRST -> "${two(d)}/${two(month)}/$y"
            DateFormat.MONTH_FIRST -> "${two(month)}/${two(d)}/$y"
            DateFormat.ISO -> "$y-${two(month)}-${two(d)}"
        }
    }

    /** "13 Nov 2026 to 15 Nov 2026". */
    fun showRange(from: Int, to: Int, format: DateFormat): String = "${show(from, format)} to ${show(to, format)}"
}
