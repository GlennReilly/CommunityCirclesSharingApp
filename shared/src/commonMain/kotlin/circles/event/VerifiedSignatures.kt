package circles.event

import circles.encoding.ByteString

/**
 * Which event signatures were already checked, and against which public key. A client verifies each event once when it
 * arrives (ADR 0036) and the fold asks here before repeating the work. The key is part of the question, so a recorded
 * check never vouches for a different key.
 */
fun interface VerifiedSignatures {
    fun isVerified(id: EventId, key: ByteString): Boolean

    companion object {
        /** Nothing is known: the fold verifies every signature itself (the default, and what the server does). */
        val NONE: VerifiedSignatures = VerifiedSignatures { _, _ -> false }

        fun of(checked: Map<EventId, ByteString>): VerifiedSignatures = VerifiedSignatures { id, key -> checked[id] == key }
    }
}
