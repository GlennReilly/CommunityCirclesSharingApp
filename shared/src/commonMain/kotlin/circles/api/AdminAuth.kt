package circles.api

import circles.crypto.Crypto
import circles.crypto.ED25519_SIGNATURE_BYTES
import circles.encoding.ByteString
import circles.event.DeviceId
import circles.event.isValidRandomId
import kotlinx.serialization.Serializable

/** Domain string at the start of every signed admin message (ADR 0033). */
const val ADMIN_DOMAIN = "circles/admin/v1"
const val ADMIN_SCHEME = "Circles-Signature"
const val ADMIN_MAX_SKEW_SECONDS = 300L
const val ADMIN_NONCE_BYTES = 16

/** What a signed admin request carries in its `Authorization` header. */
data class AdminCredentials(val device: DeviceId, val ts: Long, val nonce: String, val sig: ByteString)

/** Answer of `POST /admin/import`. */
@Serializable
data class ImportSummary(val eventsAccepted: Int, val eventsAlready: Int, val blobsStored: Int, val blobsAlready: Int)

/**
 * Signed requests for the two admin endpoints (ADR 0033): an Ed25519 signature by a device key over the method, path,
 * timestamp, nonce and body hash, so a captured header cannot be used for anything else. Shared by the server and clients.
 */
object AdminAuth {
    fun message(method: String, path: String, ts: Long, nonce: String, bodyHashHex: String): ByteArray =
        listOf(ADMIN_DOMAIN, method, path, ts.toString(), nonce, bodyHashHex).joinToString("\n").encodeToByteArray()

    /** The `Authorization` header value for a request made now by [device] with its [signingSeed]. */
    fun sign(crypto: Crypto, device: DeviceId, signingSeed: ByteString, method: String, path: String, nowSeconds: Long, body: ByteArray = ByteArray(0)): String {
        val nonce = crypto.randomBytes(ADMIN_NONCE_BYTES).toBase64Url()
        val signature = crypto.sign(signingSeed, message(method, path, nowSeconds, nonce, crypto.hash256(body).toHex()))
        return "$ADMIN_SCHEME device=${device.v}, ts=$nowSeconds, nonce=$nonce, sig=${signature.toBase64Url()}"
    }

    /** Strict reader: exactly the four fields, each well formed; null for anything else. */
    fun parse(header: String?): AdminCredentials? {
        val text = header?.takeIf { it.startsWith("$ADMIN_SCHEME ") }?.removePrefix("$ADMIN_SCHEME ") ?: return null
        val fields = text.split(",").map { it.trim() }.map { part -> part.substringBefore("=", "") to part.substringAfter("=", "\u0000") }
        if (fields.map { it.first }.sorted() != listOf("device", "nonce", "sig", "ts")) return null
        val byName = fields.toMap()
        val device = byName.getValue("device").takeIf { isValidRandomId(it) } ?: return null
        val ts = byName.getValue("ts").takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } && it.length <= MAX_TS_DIGITS }?.toLongOrNull() ?: return null
        val nonce = byName.getValue("nonce").takeIf { ByteString.fromBase64Url(it)?.size == ADMIN_NONCE_BYTES } ?: return null
        val sig = ByteString.fromBase64Url(byName.getValue("sig"))?.takeIf { it.size == ED25519_SIGNATURE_BYTES } ?: return null
        return AdminCredentials(DeviceId(device), ts, nonce, sig)
    }

    /** True if [credentials] are a valid signature by [signPub] over this method, path and body. Freshness is the caller's job. */
    fun verify(crypto: Crypto, credentials: AdminCredentials, method: String, path: String, body: ByteArray, signPub: ByteString): Boolean =
        crypto.verify(signPub, message(method, path, credentials.ts, credentials.nonce, crypto.hash256(body).toHex()), credentials.sig)

    private const val MAX_TS_DIGITS = 12
}
