package circles.event

import circles.encoding.CborMap
import circles.encoding.CborText
import circles.encoding.Parsed
import circles.policy.MemberId

/** A display name is 1 to this many Unicode code points (ADR 0068). */
const val MAX_DISPLAY_NAME_CODE_POINTS = 40

/** A name counts as confirmed once this many current members confirmed it; a circle setting may replace it later (ADR 0068). */
const val NAME_CONFIRMATIONS_NEEDED = 1

/** A name made only of hex digits (and spaces) this long or longer looks like a member id or a fingerprint, so it is refused. */
const val ID_LIKE_HEX_DIGITS = 8

/** The largest UTF-16 length a name of [MAX_DISPLAY_NAME_CODE_POINTS] code points can have; a payload longer than this is refused unread. */
private const val MAX_DISPLAY_NAME_UTF16 = MAX_DISPLAY_NAME_CODE_POINTS * 2

private const val LAST_C0_CONTROL = 0x1F
private const val DELETE = 0x7F
private const val LAST_C1_CONTROL = 0x9F
private const val SPACE = ' '

/**
 * Characters a name may not hold anywhere: invisible or direction-changing marks (which can make one name look like
 * another), and every space other than the plain one. The list is fixed here, not taken from the platform's Unicode
 * tables, so the JVM and the browser judge a name the same way.
 */
private val FORBIDDEN_IN_NAME: Set<Int> = buildSet {
    add(0x00A0); add(0x00AD); add(0x034F); add(0x061C); add(0x115F); add(0x1160); add(0x1680); add(0x180E)
    addAll(0x2000..0x200F) // spaces of other widths, zero-width spaces and joiners, direction marks
    addAll(0x2028..0x202F) // line and paragraph separators, direction embeddings and overrides, narrow no-break space
    addAll(0x205F..0x206F) // medium space, word joiner, invisible operators, direction isolates
    add(0x3000); add(0x3164); add(0xFEFF); add(0xFFA0)
    addAll(0xFFF0..0xFFFF)
}

/** Whitespace that [DisplayNames.tidy] turns into a plain space (a fixed list, for the same reason). */
private val SPACE_LIKE: Set<Int> = setOf(0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x85, 0xA0, 0x1680, 0x2028, 0x2029, 0x202F, 0x205F, 0x3000) + (0x2000..0x200A)

/** `member.profile`: the author's display name (ADR 0068). Sealed, like every payload the server does not need. */
data class MemberProfile(val name: String)

/** How the confirmer checked the person (ADR 0068). Only one way exists; the field keeps the statement explicit. */
enum class NameCheck { FINGERPRINT_IN_PERSON }

/**
 * `member.name.confirm`: the author says that [member]'s name [name], as set by the `member.profile` event [profile], is
 * right, and that they checked it [how]. Bound to that one event, so any later change of name leaves it behind.
 */
data class NameConfirmation(val member: MemberId, val profile: EventId, val name: String, val how: NameCheck)

/** A member's current name, the event that set it, and who confirmed it (member to day) since then. */
data class NameState(val member: MemberId, val name: String, val profile: EventId, val day: Int, val confirmations: Map<MemberId, Int> = emptyMap())

/** The rules for display names (ADR 0068), the same on every client. */
object DisplayNames {
    /**
     * Tidies what a person typed into the form that is stored: Unicode NFKC (by [normalize], which each platform
     * supplies), runs of whitespace made one plain space, and no space at either end. The fold does not normalise
     * (platforms may differ in their Unicode version); it only checks [problem].
     */
    fun tidy(typed: String, normalize: (String) -> String): String =
        normalize(typed).map { if (it.code in SPACE_LIKE) SPACE else it }.joinToString("").split(SPACE).filter { it.isNotEmpty() }.joinToString(SPACE.toString())

    /** Why [name] is not acceptable as stored, or null if it is. Platform-independent: it looks only at code points. */
    fun problem(name: String): String? {
        val points = codePoints(name) ?: return "a name must be valid text"
        return when {
            points.isEmpty() -> "a name cannot be empty"
            points.size > MAX_DISPLAY_NAME_CODE_POINTS -> "a name is at most $MAX_DISPLAY_NAME_CODE_POINTS characters"
            points.any { isControl(it) || it in FORBIDDEN_IN_NAME } -> "a name cannot hold control characters, invisible marks or unusual spaces"
            points.first() == SPACE.code || points.last() == SPACE.code -> "a name cannot start or end with a space"
            name.contains("$SPACE$SPACE") -> "a name cannot hold two spaces in a row"
            looksLikeAnId(name) -> "a name cannot be just a number or look like a member code"
            else -> null
        }
    }

    private fun isControl(point: Int): Boolean = point <= LAST_C0_CONTROL || point in DELETE..LAST_C1_CONTROL

    /** Only digits; or only hex digits, at least [ID_LIKE_HEX_DIGITS] of them (spaces ignored), like "2b69 a182". Short hex words such as "Bea" or "Ada" are names. */
    private fun looksLikeAnId(name: String): Boolean {
        val compact = name.replace(SPACE.toString(), "")
        if (compact.all { it in '0'..'9' }) return true
        return compact.length >= ID_LIKE_HEX_DIGITS && compact.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    /** The code points of [text], or null if it holds a lone surrogate. */
    private fun codePoints(text: String): List<Int>? {
        val points = ArrayList<Int>(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isHighSurrogate() -> {
                    val low = text.getOrNull(i + 1)?.takeIf { it.isLowSurrogate() } ?: return null
                    points += 0x10000 + ((c.code - 0xD800) shl 10) + (low.code - 0xDC00)
                    i += 2
                }
                c.isLowSurrogate() -> return null
                else -> {
                    points += c.code
                    i += 1
                }
            }
        }
        return points
    }
}

/** Why the fold refuses a `member.profile` whose name another current member already holds (ADR 0069). */
const val NAME_TAKEN_REASON = "that name is already used by another member of the circle"

/**
 * The key two names are compared by to decide whether a name is taken (ADR 0069). The text is already NFKC (done when it
 * was saved); this folds case with a fixed table, never the platform's, so the JVM and the browser always agree.
 * Covered: ASCII, Latin-1, Latin Extended-A, Greek (with final sigma) and Cyrillic including its extensions up to U+052F.
 * Left unchanged on purpose: the Turkish dotted capital I (U+0130) and dotless small i (U+0131), the German sharp s, and
 * every other script. Look-alike letters from different scripts (Cyrillic a for Latin a) are NOT detected.
 */
object NameKey {
    private const val ASCII_CASE_GAP = 0x20
    private const val CYRILLIC_EXTRA_GAP = 0x50

    fun of(name: String): String = buildString(name.length) { name.forEach { append(fold(it.code).toChar()) } }

    private fun fold(c: Int): Int = when (c) {
        in 0x41..0x5A -> c + ASCII_CASE_GAP
        in 0xC0..0xDE -> if (c == 0xD7) c else c + ASCII_CASE_GAP
        0x130, 0x131 -> c
        in 0x100..0x137 -> if (c % 2 == 0) c + 1 else c
        in 0x139..0x148 -> if (c % 2 == 1) c + 1 else c
        in 0x14A..0x177 -> if (c % 2 == 0) c + 1 else c
        0x178 -> 0xFF
        in 0x179..0x17E -> if (c % 2 == 1) c + 1 else c
        0x386 -> 0x3AC
        in 0x388..0x38A -> c + 37
        0x38C -> 0x3CC
        0x38E, 0x38F -> c + 63
        in 0x391..0x3AB -> if (c == 0x3A2) c else c + ASCII_CASE_GAP
        0x3C2 -> 0x3C3 // final sigma
        in 0x400..0x40F -> c + CYRILLIC_EXTRA_GAP
        in 0x410..0x42F -> c + ASCII_CASE_GAP
        in 0x460..0x481, in 0x48A..0x4BF, in 0x4D0..0x52F -> if (c % 2 == 0) c + 1 else c
        in 0x4C1..0x4CE -> if (c % 2 == 1) c + 1 else c
        0x4C0 -> 0x4CF
        else -> c
    }
}

private const val K_NAME = "name"
private const val K_MEMBER = "member"
private const val K_PROFILE = "profile"
private const val K_HOW = "how"

/** Builders and strict readers for the two name payloads (ADR 0068): unknown keys, wrong types and bad names are refused. */
object NamePayloads {
    fun profile(v: MemberProfile): CborMap = CborMap.of(K_NAME to CborText(v.name))

    fun confirmation(v: NameConfirmation): CborMap = CborMap.of(
        K_MEMBER to CborText(v.member.v), K_PROFILE to CborText(v.profile.v), K_NAME to CborText(v.name), K_HOW to CborText(v.how.name),
    )

    fun parseProfile(map: CborMap): Parsed<MemberProfile> {
        if (map.entries.keys != setOf(K_NAME)) return Parsed.Invalid("expected the key name")
        val name = name(map) ?: return Parsed.Invalid("name must be text")
        DisplayNames.problem(name)?.let { return Parsed.Invalid(it) }
        return Parsed.Ok(MemberProfile(name))
    }

    fun parseConfirmation(map: CborMap): Parsed<NameConfirmation> {
        if (map.entries.keys != setOf(K_MEMBER, K_PROFILE, K_NAME, K_HOW)) return Parsed.Invalid("expected the keys how, member, name and profile")
        val member = (map[K_MEMBER] as? CborText)?.value?.takeIf { isValidRandomId(it) } ?: return Parsed.Invalid("member must be 32 lowercase hex characters")
        val profile = (map[K_PROFILE] as? CborText)?.value?.takeIf { isLowerHex(it, EVENT_ID_HEX_LENGTH) } ?: return Parsed.Invalid("profile must be an event id")
        val name = name(map)?.takeIf { DisplayNames.problem(it) == null } ?: return Parsed.Invalid("name is not an acceptable name")
        val how = (map[K_HOW] as? CborText)?.value?.let { wire -> NameCheck.entries.firstOrNull { it.name == wire } } ?: return Parsed.Invalid("how is not a known check")
        return Parsed.Ok(NameConfirmation(MemberId(member), EventId(profile), name, how))
    }

    private fun name(map: CborMap): String? = (map[K_NAME] as? CborText)?.value?.takeIf { it.length <= MAX_DISPLAY_NAME_UTF16 }
}
