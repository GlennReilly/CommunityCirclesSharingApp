package circles.event

import circles.crypto.Crypto
import circles.encoding.ByteString

private const val SHARE_CODE_PREFIX = "rs1"
private const val SHARE_CODE_DOMAIN = "circles/share-code/v1"
private const val EPOCH_BYTES = 4
private const val CHECKSUM_BYTES = 2
private const val GROUP_CHARS = 4
private const val BYTE_MASK = 0xFF
private const val BITS_PER_BYTE = 8

/** A recovery share written as text for a steward to read out or send privately (ADR 0048): its epoch and the share bytes. */
class ShareCodeContent(val epoch: Int, val share: ByteString)

/** Why a share code was not accepted; the page words each one plainly. */
sealed interface ShareCodeResult {
    data class Valid(val content: ShareCodeContent) : ShareCodeResult

    /** The text is not a share code at all (wrong start, not hex, wrong length). */
    data object NotACode : ShareCodeResult

    /** It looks like a code but a character was mistyped. */
    data object Mistyped : ShareCodeResult
}

/**
 * The text form of a recovery share: `rs1`, then hex of epoch (4 bytes), the share and a 2-byte check, in groups of four.
 * The check catches a mistyped or misheard character before the codes are combined; it is not secret-bearing.
 */
object ShareCode {
    fun format(crypto: Crypto, epoch: Int, share: ByteString): String {
        val body = body(epoch, share)
        val hex = ByteString.of(body + checksum(crypto, body)).toHex()
        return SHARE_CODE_PREFIX + " " + hex.chunked(GROUP_CHARS).joinToString(" ")
    }

    /** Reads a code typed or pasted with any spacing, dashes or capitals. */
    fun parse(crypto: Crypto, text: String): ShareCodeResult {
        val cleaned = text.lowercase().filter { it.isLetterOrDigit() }
        if (!cleaned.startsWith(SHARE_CODE_PREFIX)) return ShareCodeResult.NotACode
        val bytes = ByteString.fromHex(cleaned.removePrefix(SHARE_CODE_PREFIX))?.toByteArray() ?: return ShareCodeResult.NotACode
        if (bytes.size <= EPOCH_BYTES + CHECKSUM_BYTES) return ShareCodeResult.NotACode
        val body = bytes.copyOfRange(0, bytes.size - CHECKSUM_BYTES)
        if (!checksum(crypto, body).contentEquals(bytes.copyOfRange(bytes.size - CHECKSUM_BYTES, bytes.size))) return ShareCodeResult.Mistyped
        val epoch = body.take(EPOCH_BYTES).fold(0) { acc, b -> (acc shl BITS_PER_BYTE) or (b.toInt() and BYTE_MASK) }
        return ShareCodeResult.Valid(ShareCodeContent(epoch, ByteString.of(body.copyOfRange(EPOCH_BYTES, body.size))))
    }

    private fun body(epoch: Int, share: ByteString): ByteArray =
        ByteArray(EPOCH_BYTES) { index -> (epoch shr (BITS_PER_BYTE * (EPOCH_BYTES - 1 - index))).toByte() } + share.toByteArray()

    private fun checksum(crypto: Crypto, body: ByteArray): ByteArray =
        crypto.hash256(SHARE_CODE_DOMAIN.encodeToByteArray() + body).toByteArray().copyOf(CHECKSUM_BYTES)
}
