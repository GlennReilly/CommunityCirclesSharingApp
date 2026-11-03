package circles.app

import circles.encoding.ByteString
import circles.event.EventId
import circles.event.isValidRandomId
import circles.policy.CircleId

private const val JOIN_PREFIX = "#/join/"
private const val INVITE_SECRET_BYTES = 32
private const val EVENT_ID_HEX_CHARS = 64

/** What an invite link carries (ADR 0040): the circle, the invite event, and the invite key's secret seed. */
class InviteLink(val circle: CircleId, val invite: EventId, val secretSeed: ByteString) {
    /** The link for [origin] (for example `https://circles.example`), without a trailing slash. */
    fun format(origin: String): String = "${origin.trimEnd('/')}/$JOIN_PREFIX${circle.v}/${invite.v}/${secretSeed.toBase64Url()}"

    companion object {
        /** A full URL or just the `#/join/...` fragment; null for anything that is not exactly an invite link. */
        fun parse(text: String): InviteLink? {
            val trimmed = text.trim()
            val start = trimmed.indexOf(JOIN_PREFIX).takeIf { it >= 0 } ?: return null
            if (start != 0 && trimmed[start - 1] != '/') return null
            val parts = trimmed.substring(start + JOIN_PREFIX.length).split('/')
            if (parts.size != 3) return null
            val (circle, invite, secret) = parts
            if (!isValidRandomId(circle)) return null
            if (invite.length != EVENT_ID_HEX_CHARS || !invite.all { it in '0'..'9' || it in 'a'..'f' }) return null
            val seed = ByteString.fromBase64Url(secret)?.takeIf { it.size == INVITE_SECRET_BYTES } ?: return null
            if (seed.toBase64Url() != secret) return null
            return InviteLink(CircleId(circle), EventId(invite), seed)
        }
    }
}
