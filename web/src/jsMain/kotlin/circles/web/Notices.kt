package circles.web

import circles.app.HomeData
import circles.policy.MemberId

/**
 * Messages that name a person (ADR 0077). A notice is read with no earlier context, so it says who, never "they" or "them".
 * [name] is the member's display name, or "the new member" / "that member" when they have not chosen one.
 */
object Notices {
    const val NEW_MEMBER = "the new member"
    const val THAT_MEMBER = "that member"

    /** The name to print for [member] in a notice; [fallback] when this device knows no name for them. */
    fun nameFor(home: HomeData?, member: MemberId?, fallback: String): String =
        member?.let { home?.names?.get(it)?.name }?.takeIf { it.isNotBlank() } ?: fallback

    /** "'s" always follows the name, including names that end in s (ADR 0078). The fallback phrase starts the sentence, so it gets a capital. */
    /**
     * A refusal reason from the circle rules as a sentence for a person (ADR 0082, 0083): any date inside it is written in the
     * chosen format, it starts with a capital, and it ends with a full stop.
     */
    fun sentenceOf(reason: String): String {
        val text = circles.app.ReasonDates.format(reason.trim(), DateText.current).replaceFirstChar { it.uppercase() }
        return if (text.endsWith(".") || text.endsWith("!") || text.endsWith("?")) text else "$text."
    }

    fun letIn(name: String): String =
        (if (name == NEW_MEMBER) name.replaceFirstChar { it.uppercase() } else name) + "'s access has now been approved. The circle opens for their device once it syncs."

    fun letInFailed(name: String): String = "Could not approve $name's access just now. Try again after the next sync."

    fun vouched(name: String): String = "You vouched for $name."

    fun vouchRefused(name: String): String = "You cannot vouch for $name right now."

    fun memberRemoved(name: String): String = "$name has been removed from the circle. The circle's key has been changed."
}
