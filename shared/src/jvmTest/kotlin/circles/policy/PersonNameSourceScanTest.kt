package circles.policy

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A guard for ADR 0076: every member's name a page shows is a link, made by the one helper `personName` in ShellView.kt,
 * so a view cannot print a plain-text name by accident. The plain-text path, `nameOf`, and direct reads of the name
 * table are allowed only on a line that says why in a `plain-name:` comment, in the helpers themselves, and in
 * Notices.kt (notices clear themselves, so they stay plain text, ADR 0076). Comparisons such as `displayName != null`
 * print nothing and are fine.
 */
class PersonNameSourceScanTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() && File(it, "web").isDirectory }

    private val risky = Regex("""\bnameOf\(|\.names\[|names\?\.get|names\.get\(|\.displayName\b|\bmyName\b""")
    private val harmless = Regex("""displayName\s*[!=]=\s*null|myName\s*[!=]=\s*null""")
    private val allowedLine = listOf("plain-name:", "fun nameOf(", "val named = home.names[member]")

    private fun problemsIn(line: String): Boolean =
        risky.containsMatchIn(line) && !harmless.containsMatchIn(line) && allowedLine.none { it in line }

    @Test
    fun noViewPrintsAMemberNameWithoutThePersonNameHelper() {
        val dir = File(root, "web/src/jsMain/kotlin")
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "Notices.kt" }.toList()
        assertTrue(files.size >= 5, "too few source files found under $root: ${files.size}")
        val found = files.flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> line.trimStart().let { !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/**") } }
                .filter { (_, line) -> problemsIn(line) }
                .map { (index, line) -> "${file.relativeTo(root)}:${index + 1}: ${line.trim()}" }
        }
        assertTrue(found.isEmpty(), "Show a member's name with personName (ADR 0076), or say why plain text is needed with a plain-name: comment:\n" + found.joinToString("\n"))
    }

    /**
     * ADR 0078: a menu option holds plain text, so every menu of members goes through the one `memberMenu` helper, which also
     * writes a "View <name>" link per member with `personName`. The option line with its justification must exist once,
     * inside that helper, and the helper must carry the link.
     */
    @Test
    fun plainNamesInMenuOptionsAreJustifiedByTheLinkBesideTheMenu() {
        val view = File(root, "web/src/jsMain/kotlin/circles/web/ShellView.kt").readLines()
        val optionLines = view.withIndex().filter { (_, line) -> "an option in a menu cannot hold a link" in line }.map { it.index }
        assertTrue(optionLines.size == 1, "exactly one plain-text menu option (in memberMenu), found ${optionLines.size}")
        val start = view.indexOfLast { it.contains("fun FlowContent.memberMenu(") }
        assertTrue(start in 0 until optionLines.single(), "the option must sit inside memberMenu")
        val end = (start + 1 until view.size).first { view[it] == "}" }
        val body = view.subList(start, end).joinToString("\n")
        assertTrue(optionLines.single() < end && "personName(" in body && "ATTR_VIEW_OF" in body && "ATTR_MEMBER_MENU" in body, "memberMenu must write a View link per member with personName")
        val users = view.count { it.contains("memberMenu(home,") }
        assertTrue(users >= 2, "the pause and thank-you menus use memberMenu, found $users")
    }

    @Test
    fun theScanCatchesWhatItIsFor() {
        assertTrue(problemsIn("""p { +"Owned by ${'$'}{nameOf(home, owner)}" }"""))
        assertTrue(problemsIn("""h2 { +person.displayName.orEmpty() }"""))
        assertTrue(problemsIn("""+home.names[member]!!.name"""))
        assertTrue(!problemsIn("""+nameOf(home, member) // plain-name: an option in a menu cannot hold a link"""))
        assertTrue(!problemsIn("""if (person.displayName != null) p { identityLine(home, person) }"""))
        assertTrue(!problemsIn("""personName(home, person.member)"""))
    }
}
