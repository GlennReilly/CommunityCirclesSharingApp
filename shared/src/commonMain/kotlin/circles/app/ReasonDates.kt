package circles.app

/**
 * Dates inside a refusal reason (ADR 0082). The circle rules live in shared code that runs the same on the JVM and in the browser
 * and does not know which date format a person chose, so a reason carries a placeholder, `{date:20766}` (the day number), and the
 * place that shows it fills it in: the web page through [format] with the chosen format (ADR 0080), the server and logs through
 * [plain] (ISO). The fold itself never formats, so it stays identical everywhere.
 */
object ReasonDates {
    private val placeholder = Regex("""\{date:(-?\d+)\}""")

    /** The placeholder for [day], to put inside a reason. */
    fun token(day: Int): String = "{date:$day}"

    /** [reason] with every placeholder written as an ISO date (for the server and logs). */
    fun plain(reason: String): String = placeholder.replace(reason) { CalendarDay.format(it.groupValues[1].toInt()) }

    /** [reason] with every placeholder written in the person's chosen [format]. */
    fun format(reason: String, format: DateFormat): String = placeholder.replace(reason) { DateFormats.show(it.groupValues[1].toInt(), format) }

    /** True if [reason] still holds a placeholder. */
    fun hasToken(reason: String): Boolean = placeholder.containsMatchIn(reason)
}
