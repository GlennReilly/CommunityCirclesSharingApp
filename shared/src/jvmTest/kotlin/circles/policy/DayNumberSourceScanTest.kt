package circles.policy

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A guard for ADR 0082: no text a person can read says "until day 20766". A date in a reason is the placeholder from
 * `ReasonDates.token`, which the page writes in the chosen format. Counts such as "30 days" are fine; a day *number* after a
 * preposition is not. The server's own API messages (EventRules) are not scanned: they are for logs and clients, and stay as they are.
 */
class DayNumberSourceScanTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() && File(it, "web").isDirectory }

    private val literal = Regex(""""((?:[^"\\]|\\.)*)"""")
    private val dayNumber = Regex("""(?i)\b(until|on|from|by|before|after|since|at|of) day\b""")

    private fun problems(line: String): List<String> = literal.findAll(line).map { it.groupValues[1] }.filter { dayNumber.containsMatchIn(it) }.toList()

    @Test
    fun noReasonOrMessageMentionsADayNumber() {
        val dirs = listOf("shared/src/commonMain", "shared/src/jsMain", "web/src/jsMain")
        val files = dirs.map { File(root, it) }.filter { it.isDirectory }.flatMap { d -> d.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        assertTrue(files.size > 20)
        val found = files.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> line.trimStart().let { !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/**") } }
                .flatMap { (index, line) -> problems(line).map { "${file.relativeTo(root)}:${index + 1}: $it" } }
        }
        assertTrue(found.isEmpty(), "Use ReasonDates.token(day) instead of a day number (ADR 0082):\n" + found.joinToString("\n"))
    }

    @Test
    fun theScanCatchesWhatItIsFor() {
        assertTrue(problems("""return "a new steward cannot vote until day ${'$'}{x}"""").isNotEmpty())
        assertTrue(problems("""x("expired on day 5")""").isNotEmpty())
        assertTrue(problems("""x("within 30 days of the return")""").isEmpty())
        assertTrue(problems("""x("until ${'$'}{ReasonDates.token(5)}")""").isEmpty())
    }
}
