package circles.policy

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A guard for ADR 0080: a date a person reads is written by `DateText` (and `DateFormats` under it), in the format the person
 * chose. The web code may call the raw ISO formatter `CalendarDay.format` only on a line that says why in an `iso:` comment
 * (the value of a date input, which is always YYYY-MM-DD), and may not write an ISO-looking date inside a string. Wire and
 * storage code, and the export file name in the shared app code, are not web view code and stay ISO.
 */
class DateFormatSourceScanTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() && File(it, "web").isDirectory }

    private val rawFormatter = Regex("""\bCalendarDay\.format\(""")
    private val isoInString = Regex(""""[^"]*\b\d{4}-\d{2}-\d{2}\b[^"]*"""")

    private fun problem(line: String): Boolean =
        (rawFormatter.containsMatchIn(line) && "// iso:" !in line) || isoInString.containsMatchIn(line)

    @Test
    fun noWebViewCodePrintsAnIsoDateOutsideTheHelper() {
        val files = File(root, "web/src/jsMain/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.size >= 5, "too few source files found under $root: ${files.size}")
        val found = files.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> line.trimStart().let { !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/**") } }
                .filter { (_, line) -> problem(line) }
                .map { (index, line) -> "${file.relativeTo(root)}:${index + 1}: ${line.trim()}" }
        }
        assertTrue(found.isEmpty(), "Write dates with DateText (ADR 0080), or say why ISO must stay with an iso: comment:\n" + found.joinToString("\n"))
    }

    @Test
    fun theScanCatchesWhatItIsFor() {
        assertTrue(problem("""p { +"Dates: ${'$'}{CalendarDay.format(loan.fromDay)}" }"""))
        assertTrue(problem("""p { +"Starts 2026-11-13" }"""))
        assertTrue(!problem("""value = CalendarDay.format(day) // iso: the value of a date input is always YYYY-MM-DD"""))
        assertTrue(!problem("""p { +"Dates: ${'$'}{DateText.show(loan.fromDay)}" }"""))
    }
}
