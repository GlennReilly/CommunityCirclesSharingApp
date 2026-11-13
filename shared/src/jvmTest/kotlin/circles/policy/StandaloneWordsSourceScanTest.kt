package circles.policy

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A guard for ADR 0077: a message people read has no earlier context, so no string literal in the shared and web code may
 * start with a pronoun that has no referent ("They ", "Them ", "It ", "He ", "She "), and the old catch-all sentences
 * ("That cannot be done right now.", "You cannot do that right now.") are gone. Plain confirmations such as "Saved." are fine.
 * Only text that starts with the word is caught; a pronoun later in a sentence that names its subject first is fine.
 * Button labels about the loan card in front of the reader ("It came back fine") are the allow-list.
 */
class StandaloneWordsSourceScanTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() && File(it, "web").isDirectory }

    private val scanned = listOf("shared/src/commonMain", "shared/src/jsMain", "web/src/jsMain")
    private val literal = Regex(""""((?:[^"\\]|\\.)*)"""")
    private val startsWithPronoun = Regex("""^(They|Them|It|He|She)\b""")
    private val vague = listOf("That cannot be done right now", "You cannot do that right now", "Let in. They")
    private val allowed = setOf("It came back fine", "It came back damaged", "It did not come back")

    private fun problemsIn(line: String): List<String> = literal.findAll(line).flatMap { match ->
        val text = match.groupValues[1]
        buildList {
            if (text !in allowed && startsWithPronoun.containsMatchIn(text)) add("starts with a pronoun: \"$text\"")
            vague.filter { it in text }.forEach { add("vague sentence: \"$text\"") }
        }
    }.toList()

    @Test
    fun noMessageStartsWithAPronounOrIsTheOldVagueSentence() {
        val files = scanned.map { File(root, it) }.filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        assertTrue(files.size > 20, "too few source files found under $root: ${files.size}")
        val found = files.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> line.trimStart().let { !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/**") } }
                .flatMap { (index, line) -> problemsIn(line).map { "${file.relativeTo(root)}:${index + 1}: $it" } }
        }
        assertTrue(found.isEmpty(), "Write messages that make sense on their own (ADR 0077):\n" + found.joinToString("\n"))
    }

    /** ADR 0081: the UI says "approve access" and "waiting for approval", never "let in". Comments and identifiers are not scanned. */
    private val letInWords = Regex("""(?i)\blet(s|ting)?\b( (you|them|him|her|people|someone|anyone|newcomers))? in\b|\b(been|be|being) let in\b""")

    @Test
    fun noMessageOrLabelSaysLetInAnyMore() {
        val files = scanned.map { File(root, it) }.filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        val found = files.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> line.trimStart().let { !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/**") } }
                .flatMap { (index, line) -> literal.findAll(line).filter { letInWords.containsMatchIn(it.groupValues[1]) }.map { "${file.relativeTo(root)}:${index + 1}: ${it.value}" }.toList() }
        }
        assertTrue(found.isEmpty(), "Say approve access, not let in (ADR 0081):\n" + found.joinToString("\n"))
        assertTrue(letInWords.containsMatchIn("A steward needs to let you in") && letInWords.containsMatchIn("Waiting to be let in") && letInWords.containsMatchIn("Check and let in") && letInWords.containsMatchIn("Letting them in gives"))
        assertTrue(!letInWords.containsMatchIn("Approve access") && !letInWords.containsMatchIn("let-in-checked") && !letInWords.containsMatchIn("Waiting for approval"))
    }

    @Test
    fun theScanCatchesWhatItIsFor() {
        assertTrue(problemsIn("""dispatch(AppAction.Notice("They can read the circle."))""").isNotEmpty())
        assertTrue(problemsIn("""x("It counts once they confirm it.")""").isNotEmpty())
        assertTrue(problemsIn("""x("That cannot be done right now.")""").isNotEmpty())
        assertTrue(problemsIn("""x("Saved.")""").isEmpty())
        assertTrue(problemsIn("""x("Alice has been approved. They can read it.")""").isEmpty(), "a pronoun after its subject is allowed")
        assertTrue(problemsIn("""loanButton(ACTION_CONFIRM_RETURN, loan, "It came back fine")""").isEmpty())
    }
}
