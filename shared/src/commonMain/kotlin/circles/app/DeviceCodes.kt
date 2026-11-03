package circles.app

import circles.crypto.Crypto
import circles.encoding.ByteString
import circles.event.DeviceId
import circles.event.isValidRandomId
import circles.policy.CircleId
import circles.policy.MemberId

private const val DEVICE_PREFIX = "#/device/"
private const val KEY_BYTES = 32
private const val FINGERPRINT_HEX_CHARS = 20
private const val FINGERPRINT_GROUP = 4

/** A short code a person can compare out loud: 20 hex characters of the key's hash in groups of four (the Members page uses it too). */
object Fingerprint {
    fun of(crypto: Crypto, signPub: ByteString): String =
        crypto.hash256(signPub.toByteArray()).toHex().take(FINGERPRINT_HEX_CHARS).chunked(FINGERPRINT_GROUP).joinToString(" ")
}

/**
 * What a new device shows so the existing device can register it (ADR 0046): its device id and its two public keys.
 * Nothing secret is in it. Text form: `device.signPub.boxPub`, with the keys in base64url.
 */
class DeviceCode(val device: DeviceId, val signPub: ByteString, val boxPub: ByteString) {
    fun format(): String = "${device.v}.${signPub.toBase64Url()}.${boxPub.toBase64Url()}"

    companion object {
        /** Exactly the shape of [format]; anything else (extra parts, wrong lengths, other spellings) is null. Whitespace around it is ignored. */
        fun parse(text: String): DeviceCode? {
            val parts = text.trim().split('.')
            if (parts.size != 3 || !isValidRandomId(parts[0])) return null
            val keys = parts.drop(1).map { part -> ByteString.fromBase64Url(part)?.takeIf { it.size == KEY_BYTES && it.toBase64Url() == part } ?: return null }
            return DeviceCode(DeviceId(parts[0]), keys[0], keys[1])
        }
    }
}

/** The link an existing device shows for a new browser to open (ADR 0046): `{origin}/#/device/{circle}/{member}`; public ids only. */
class DeviceLink(val circle: CircleId, val member: MemberId) {
    fun format(origin: String): String = "${origin.trimEnd('/')}/$DEVICE_PREFIX${circle.v}/${member.v}"

    companion object {
        fun parse(text: String): DeviceLink? {
            val trimmed = text.trim()
            val start = trimmed.indexOf(DEVICE_PREFIX).takeIf { it >= 0 } ?: return null
            if (start != 0 && trimmed[start - 1] != '/') return null
            val parts = trimmed.substring(start + DEVICE_PREFIX.length).split('/')
            if (parts.size != 2 || !parts.all { isValidRandomId(it) }) return null
            return DeviceLink(CircleId(parts[0]), MemberId(parts[1]))
        }
    }
}
