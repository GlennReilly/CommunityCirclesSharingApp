package circles.web

/** What identifies a focusable control well enough to find it again after the page is rebuilt. */
data class FocusCandidate(val id: String, val name: String, val action: String, val tag: String, val label: String)

private const val LABEL_LIMIT = 40

/**
 * One key per candidate, unique within the list: its id, else name, else data-action, else tag and label; controls that
 * share a key are told apart by their order among the equals. Pure, so it is tested without a browser.
 */
fun focusKeys(candidates: List<FocusCandidate>): List<String> {
    val seen = mutableMapOf<String, Int>()
    return candidates.map { c ->
        val base = when {
            c.id.isNotEmpty() -> "id:${c.id}"
            c.name.isNotEmpty() -> "name:${c.tag}:${c.name}"
            c.action.isNotEmpty() -> "action:${c.action}:${c.label.take(LABEL_LIMIT)}"
            else -> "tag:${c.tag}:${c.label.take(LABEL_LIMIT)}"
        }
        val n = seen.getOrElse(base) { 0 }
        seen[base] = n + 1
        "$base#$n"
    }
}

/** Index of the control to focus again, or null: nothing was focused, or the control is gone. */
fun focusTarget(before: String?, after: List<FocusCandidate>): Int? =
    if (before == null) null else focusKeys(after).indexOf(before).takeIf { it >= 0 }
