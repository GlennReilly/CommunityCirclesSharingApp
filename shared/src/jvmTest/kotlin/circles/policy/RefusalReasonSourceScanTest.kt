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

    /** One place that produces reasons a person can read: its file, which lines hold a reason, and optionally the part of the file to look in. */
    private class Source(val path: String, val marker: Regex, val from: String? = null, val to: String? = null)

    private val sources = listOf(
        Source("shared/src/commonMain/kotlin/circles/event/MarketFold.kt", Regex("""(\breturn "|Refused\(")""")),
        Source("shared/src/commonMain/kotlin/circles/event/StewardQuorum.kt", Regex("""Refused\("|->\s*"[A-Za-z]|^\s+"[A-Za-z]""")),
        Source("shared/src/commonMain/kotlin/circles/event/MemberNames.kt", Regex("""(->|\breturn|_REASON =)\s*"[A-Za-z]""")),
        Source("shared/src/commonMain/kotlin/circles/event/ConfigUpdate.kt", Regex("""->\s*"[A-Za-z]""")),
        Source("shared/src/commonMain/kotlin/circles/event/CircleFold.kt", Regex("""IDENTITY_NOT_CONFIRMED_REASON =""")),
        Source("shared/src/commonMain/kotlin/circles/app/CircleApp.kt", Regex("""\w+Result\.Refused\("|UnlockResult\.Unsupported\("|Problem\(.*\) = when|^\s+(name|details)\..*->\s*"[A-Za-z]""")),
        // ADR 0077 widened (task Q): what the sign-in, unlock, recovery and database code tells a person
        Source("shared/src/commonMain/kotlin/circles/sync/HttpSyncApi.kt", Regex("""ApiOutcome\.Refused\([^"]*"""")),
        Source("shared/src/commonMain/kotlin/circles/keys/KeyVault.kt", Regex("""UnlockResult\.Unsupported\("""")),
        Source("shared/src/commonMain/kotlin/circles/keys/SeedWrapping.kt", Regex("""UnwrapResult\.Unsupported\("""")),
        Source("shared/src/jsMain/kotlin/circles/sync/IndexedDbReplica.kt", Regex("""IdbOpenResult\.Unavailable\("""")),
        Source("shared/src/commonMain/kotlin/circles/event/RecoveryShares.kt", Regex("""Parsed\.Invalid\("""), from = "fun rebuild(", to = "fun open("),
        Source("shared/src/commonMain/kotlin/circles/event/RecoveryShares.kt", Regex("""Parsed\.Invalid\("""), from = "fun recover("),
    )

    /** What a reason may be: a capital letter (or an interpolation or a placeholder) to start, a full stop to end. */
    private fun isSentence(text: String): Boolean = text.length > 10 && (text[0].isUpperCase() || text[0] == '$' || text[0] == '{') && (text.trimEnd().endsWith(".") || (text.trimEnd().endsWith("}") && ". " in text))

    private fun reasonsIn(line: String): List<String> = literal.findAll(line).map { it.groupValues[1] }.toList()

    @Test
    fun everyReasonShownToAPersonIsACompleteSentence() {
        val found = mutableListOf<String>()
        var checked = 0
        for (source in sources) {
            val path = source.path
            val marker = source.marker
            val file = File(root, path)
            assertTrue(file.isFile, path)
            val all = file.readLines().withIndex().toList()
            val start = source.from?.let { f -> all.indexOfFirst { f in it.value } } ?: 0
            val end = source.to?.let { t -> all.indexOfFirst { it.index > start && t in it.value } }?.takeIf { it >= 0 } ?: all.size
            assertTrue(start >= 0, "$path has no ${source.from}")
            all.subList(start, end)
                .filter { (_, line) -> line.trimStart().let { !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/**") } && marker.containsMatchIn(line) }
                .forEach { (index, line) ->
                    // the first literal after the marker is the reason; "access rules. " + ... is a sentence start joined to the rules' own sentences
                    val first = reasonsIn(line.substring(marker.find(line)!!.range.first)).firstOrNull() ?: return@forEach
                    checked++
                    if (!isSentence(first) && !first.endsWith(". ")) found += "$path:${index + 1}: \"$first\""
                }
        }
        assertTrue(checked > 120, "the scan found only $checked reasons; its markers need updating")
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
