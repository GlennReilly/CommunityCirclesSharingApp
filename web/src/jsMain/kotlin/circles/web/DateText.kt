package circles.web

import circles.app.DateFormat
import circles.app.DateFormats

/**
 * The one way a page or a notice writes a date (ADR 0080). The chosen format is a preference of this browser; [current] is set
 * at the start of every render from the state, and when the saved choice is read, so views and notices agree. Nothing else may print a day
 * number as text: `DateFormatSourceScanTest` fails for a view that calls the raw ISO formatter outside the marked places
 * (values of date inputs and file names).
 */
object DateText {
    var current: DateFormat = DateFormat.DEFAULT

    fun show(day: Int): String = DateFormats.show(day, current)

    fun showRange(from: Int, to: Int): String = DateFormats.showRange(from, to, current)
}
