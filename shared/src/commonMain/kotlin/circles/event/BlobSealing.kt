package circles.event

import circles.api.MAX_BLOB_BYTES
import circles.crypto.AEAD_NONCE_BYTES
import circles.crypto.AEAD_TAG_BYTES
import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.crypto.DecryptResult
import circles.encoding.ByteString
import circles.encoding.Parsed
import circles.policy.CircleId

/** Domain string of the associated data that binds a blob to its circle (ADR 0026). */
const val BLOB_AAD_DOMAIN = "circles/blob/v1"

/** Most plaintext bytes that still fit in one blob after the nonce and tag. */
const val MAX_BLOB_PLAINTEXT_BYTES = MAX_BLOB_BYTES - AEAD_NONCE_BYTES - AEAD_TAG_BYTES

/** An encrypted blob and its id: the lowercase hex BLAKE2b-256 of [bytes]. */
class SealedBlob(val hash: String, val bytes: ByteString)

/** Seals and opens attachments such as item photos (ADR 0026). The server only ever stores the sealed bytes. */
object BlobSealing {
    private fun aad(circle: CircleId): ByteArray = (BLOB_AAD_DOMAIN + circle.v).encodeToByteArray()

    /** Encrypts [plain] under [key] (the circle key or an item key); null if it would not fit in one blob. */
    fun seal(crypto: Crypto, cipher: Cipher, key: ByteString, circle: CircleId, plain: ByteArray): SealedBlob? {
        if (plain.size > MAX_BLOB_PLAINTEXT_BYTES) return null
        val bytes = cipher.encrypt(key, plain, aad(circle))
        return SealedBlob(crypto.hash256(bytes.toByteArray()).toHex(), bytes)
    }

    /** Invalid if the key is wrong, the blob belongs to another circle, or the bytes were altered. */
    fun open(cipher: Cipher, key: ByteString, circle: CircleId, sealed: ByteString): Parsed<ByteArray> =
        when (val result = cipher.decrypt(key, sealed, aad(circle))) {
            is DecryptResult.Success -> Parsed.Ok(result.plaintext)
            DecryptResult.Failure -> Parsed.Invalid("blob does not decrypt")
        }

    /** True if [sealed] really has the id [hash]; clients check this on download, as the server is not trusted. */
    fun matchesHash(crypto: Crypto, hash: String, sealed: ByteString): Boolean = crypto.hash256(sealed.toByteArray()).toHex() == hash
}
