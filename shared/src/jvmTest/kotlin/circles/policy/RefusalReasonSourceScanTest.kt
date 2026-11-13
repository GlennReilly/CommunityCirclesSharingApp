package circles.policy

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A guard for ADR 0077: a reason the circle rules give for refusing something is shown to a person, so it is a complete
 * sentence that names its subject: it starts with a capital letter (or an interpolation) and ends with a full stop. The scan
 * reads the places that produce those reasons (the market fold, the steward and rules checks, display-name and rules
 * validation, and the results the app builds). Reasons for the wire format and the log (`CircleFold`'s own rejections) are
 * protocol diagnostics that no page shows and are not scanned; the one `CircleFold` reason a person can meet,
 * `IDENTITY_NOT_CONFIRMED_REASON`, is checked by name.
 */
class RefusalReasonSourceScanTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() && File(it, "web").isDirectory }

    private val literal = Regex(""""((?:[^"\\]|\\.)*)"""")

    /** The files, and which lines in them hold a reason. */
    private val sources = mapOf(
        "shared/src/commonMain/kotlin/circles/event/MarketFold.kt" to Regex("""(\breturn "|Refused\(")"""),
        "shared/src/commonMain/kotlin/circles/event/StewardQuorum.kt" to Regex("""Refused\("|->\s*"[A-Za-z]|^\s+"[A-Za-z]"""),
        "shared/src/commonMain/kotlin/circles/event/MemberNames.kt" to Regex("""(->|\breturn|_REASON =)\s*"[A-Za-z]"""),
        "shared/src/commonMain/kotlin/circles/event/ConfigUpdate.kt" to Regex("""->\s*"[A-Za-z]"""),
        "shared/src/commonMain/kotlin/circles/event/CircleFold.kt" to Regex("""IDENTITY_NOT_CONFIRMED_REASON ="""),
        "shared/src/commonMain/kotlin/circles/app/CircleApp.kt" to Regex("""\w+Result\.Refused\("|Problem\(.*\) = when|^\s+(name|details)\..*->\s*"[A-Za-z]"""),
    )

    /** What a reason may be: a capital letter (or an interpolation or a placeholder) to start, a full stop to end. */
    private fun isSentence(text: String): Boolean = text.length > 10 && (text[0].isUpperCase() || text[0] == '$' || text[0] == '{') && text.trimEnd().endsWith(".")

    private fun reasonsIn(line: String): List<String> = literal.findAll(line).map { it.groupValues[1] }.toList()

    @Test
    fun everyReasonShownToAPersonIsACompleteSentence() {
        val found = mutableListOf<String>()
        var checked = 0
        for ((path, marker) in sources) {
            val file = File(root, path)
            assertTrue(file.isFile, path)
            file.readLines().withIndex()
                .filter { (_, line) -> line.trimStart().let { !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/**") } && marker.containsMatchIn(line) }
                .forEach { (index, line) ->
                    // the first literal after the marker is the reason; "access rules. " + ... is a sentence start joined to the rules' own sentences
                    val first = reasonsIn(line.substring(marker.find(line)!!.range.first)).firstOrNull() ?: return@forEach
                    checked++
                    if (!isSentence(first) && !first.endsWith(". ")) found += "$path:${index + 1}: \"$first\""
                }
        }
        assertTrue(checked > 100, "the scan found only $checked reasons; its markers need updating")
        assertTrue(found.isEmpty(), "A reason a person reads must be a complete sentence (ADR 0077):\n" + found.joinToString("\n"))
    }

    @Test
    fun theScanCatchesWhatItIsFor() {
        assertTrue(!isSentence("unknown item"))
        assertTrue(!isSentence("only the owner may change an item"))
        assertTrue(!isSentence("The item has been archived"))
        assertTrue(isSentence("The item has been archived, so it cannot be changed or asked for."))
        assertTrue(isSentence("A member can have at most ${'$'}MAX skill offers."))
    }
}
