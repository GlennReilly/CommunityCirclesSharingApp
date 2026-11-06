package circles.policy

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A guard for ADR 0070: no string in the code that builds what people read may print a tier by itself. It scans the
 * string literals of the shared common code and the web client for the old labels ("Regular", "Trusted", "Steward
 * level") and for templates that interpolate a tier value directly (`${standing.tier}`, `$minTier`,
 * `${cfg.voucherMinTier.name.lowercase()}`), which would show an enum name. Words for a tier come from [TierText].
 */
class TierWordsSourceScanTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() && File(it, "web").isDirectory }

    private val scanned = listOf("shared/src/commonMain", "shared/src/jsMain", "web/src/jsMain")

    private val literal = Regex(""""(?:[^"\\]|\\.)*"""")
    private val oldLabel = Regex("""\b(Regular|Trusted)\b|Steward level""")
    private val braced = Regex("""\$\{([^{}]*)}""")
    private val tierValue = Regex("""(?i)tier(\.name(\.lowercase\(\))?)?$""")
    private val bare = Regex("""\$[a-zA-Z_]*[tT]ier\b""")

    private fun problemsIn(line: String): List<String> = literal.findAll(line).flatMap { match ->
        val text = match.value
        buildList {
            oldLabel.find(text)?.let { add("old label \"${it.value}\"") }
            braced.findAll(text).map { it.groupValues[1].trim() }
                .filter { tierValue.containsMatchIn(it) && "TierText" !in it }
                .forEach { add("tier interpolated directly: \${$it}") }
            bare.find(text)?.let { add("tier interpolated directly: ${it.value}") }
        }
    }.toList()

    @Test
    fun noUserFacingStringPrintsATierWithoutTierText() {
        val files = scanned.map { File(root, it) }.filter { it.isDirectory }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        assertTrue(files.size > 20, "too few source files found under $root: ${files.size}")
        val found = files.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> line.trimStart().let { !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/**") } }
                .flatMap { (index, line) -> problemsIn(line).map { "${file.relativeTo(root)}:${index + 1}: $it" } }
        }
        assertTrue(found.isEmpty(), "Use TierText for words about levels (ADR 0070):\n" + found.joinToString("\n"))
    }

    @Test
    fun theScanCatchesWhatItIsFor() {
        val tier = "tier"
        assertTrue(problemsIn("""p { +"Your tier: ${'$'}{standing.$tier}" }""").isNotEmpty())
        assertTrue(problemsIn("""add("needs ${'$'}minTier now")""").isNotEmpty())
        assertTrue(problemsIn("""x("at least ${'$'}{cfg.voucherMinTier.name.lowercase()}")""").isNotEmpty())
        assertTrue(problemsIn("""x("members at Regular or above")""").isNotEmpty())
        assertTrue(problemsIn("""x("Points for ${'$'}{TierText.label(Tier.REGULAR)}")""").isEmpty())
        assertTrue(problemsIn("""val k = "regularTenths"""").isEmpty())
    }
}
