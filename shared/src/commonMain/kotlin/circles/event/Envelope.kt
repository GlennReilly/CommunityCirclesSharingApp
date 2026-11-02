package circles.event

import circles.crypto.Crypto
import circles.encoding.ByteString
import circles.encoding.CborBytes
import circles.encoding.CborEncoder
import circles.encoding.CborInt
import circles.encoding.CborMap
import circles.encoding.CborText
import circles.policy.CircleId
import circles.policy.MemberId

/** Envelope layout name; part of every signed and hashed byte string (ADR 0012). */
const val EVENT_DOMAIN = "circles/event/v1"

/** `v` of the plaintext development format used by M1 (ADR 0014). */
const val PAYLOAD_VERSION_PLAINTEXT = 0

/** The signed envelope of spec section 3.1. [type] is kept as text so unknown types can be refused, not crash. */
data class Envelope(
    val v: Int,
    val circle: CircleId,
    val author: MemberId,
    val device: DeviceId,
    val type: String,
    val day: Int,
    val ctr: Long,
    val epoch: Int,
    val public: CborMap,
    val nonce: ByteString,
    val encrypted: ByteString,
    val sig: ByteString,
) {
    /** The bytes that are signed: the domain string, then the CBOR map of every field except `sig`. */
    fun signingBytes(): ByteArray = EVENT_DOMAIN.encodeToByteArray() + CborEncoder.encode(toCbor(includeSig = false))

    /** The bytes that are hashed to make the id: the same, with `sig` included. */
    fun canonicalBytes(): ByteArray = EVENT_DOMAIN.encodeToByteArray() + CborEncoder.encode(toCbor(includeSig = true))

    fun eventId(crypto: Crypto): EventId = EventId(crypto.hash256(canonicalBytes()).toHex())

    /** Canonical CBOR of the `public` map, as stored and sent on the wire. */
    fun publicBytes(): ByteArray = CborEncoder.encode(public)

    fun signedBy(crypto: Crypto, secretSeed: ByteString): Envelope = copy(sig = crypto.sign(secretSeed, copy(sig = ByteString.EMPTY).signingBytes()))

    fun hasValidSignature(crypto: Crypto, publicKey: ByteString): Boolean = crypto.verify(publicKey, signingBytes(), sig)

    private fun toCbor(includeSig: Boolean): CborMap {
        val fields = mutableMapOf(
            "v" to CborInt(v.toLong()),
            "circle" to CborText(circle.v),
            "author" to CborText(author.v),
            "device" to CborText(device.v),
            "type" to CborText(type),
            "day" to CborInt(day.toLong()),
            "ctr" to CborInt(ctr),
            "epoch" to CborInt(epoch.toLong()),
            "public" to public,
            "nonce" to CborBytes(nonce),
            "encrypted" to CborBytes(encrypted),
        )
        if (includeSig) fields["sig"] = CborBytes(sig)
        return CborMap(fields)
    }
}

/** Joins byte pieces; used to build the domain-separated messages signed outside the envelope. */
fun domainMessage(domain: String, vararg parts: ByteArray): ByteArray = parts.fold(domain.encodeToByteArray()) { acc, part -> acc + part }

