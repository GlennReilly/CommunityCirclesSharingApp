package circles.recovery

import circles.crypto.Crypto
import circles.encoding.ByteString

const val SHAMIR_FORMAT_VERSION = 1
const val SHAMIR_MIN_THRESHOLD = 2
const val SHAMIR_MAX_SHARES = 255
private const val HEADER_BYTES = 3 // version, threshold, x
private const val VERSION_AT = 0
private const val THRESHOLD_AT = 1
private const val X_AT = 2
private const val BYTE_MASK = 0xFF

/** Why a split or combine was refused. Expected states, so a sealed type rather than exceptions. */
sealed interface ShamirFailure {
    data object InvalidParameters : ShamirFailure
    data object EmptySecret : ShamirFailure
    data object MalformedShare : ShamirFailure
    data object MixedShares : ShamirFailure
    data object DuplicateShare : ShamirFailure
    data object TooFewShares : ShamirFailure
    data object WrongSecret : ShamirFailure
}

sealed interface SplitResult {
    class Success(val shares: List<ByteString>) : SplitResult
    data class Failure(val reason: ShamirFailure) : SplitResult
}

sealed interface CombineResult {
    class Success(val secret: ByteString) : CombineResult
    data class Failure(val reason: ShamirFailure) : CombineResult
}

/**
 * k-of-n Shamir secret sharing over GF(256), one polynomial per secret byte (ADR 0020).
 * Share format: `version(1) || k(1) || x(1) || y(secret length)`, x in 1..n.
 */
object Shamir {
    /**
     * Splits [secret] into [shareCount] shares of which any [threshold] reconstruct it.
     * [randomBytes] supplies `(threshold - 1) * secret.size` coefficient bytes; pass `Crypto::randomBytes`.
     */
    fun split(
        secret: ByteString,
        threshold: Int,
        shareCount: Int,
        randomBytes: (Int) -> ByteString,
    ): SplitResult {
        if (secret.isEmpty()) return SplitResult.Failure(ShamirFailure.EmptySecret)
        if (threshold < SHAMIR_MIN_THRESHOLD || threshold > shareCount || shareCount > SHAMIR_MAX_SHARES) {
            return SplitResult.Failure(ShamirFailure.InvalidParameters)
        }
        val degree = threshold - 1
        val coefficients = randomBytes(degree * secret.size)
        if (coefficients.size != degree * secret.size) return SplitResult.Failure(ShamirFailure.InvalidParameters)
        val shares = (1..shareCount).map { x ->
            val out = ByteArray(HEADER_BYTES + secret.size)
            out[VERSION_AT] = SHAMIR_FORMAT_VERSION.toByte()
            out[THRESHOLD_AT] = threshold.toByte()
            out[X_AT] = x.toByte()
            for (i in 0 until secret.size) {
                // Horner: ((c[k-1] x + c[k-2]) x + ...) x + secret
                var acc = 0
                for (d in degree downTo 1) acc = Gf256.multiply(acc xor (coefficients[i * degree + d - 1].toInt() and BYTE_MASK), x)
                out[HEADER_BYTES + i] = (acc xor (secret[i].toInt() and BYTE_MASK)).toByte()
            }
            ByteString.of(out)
        }
        return SplitResult.Success(shares)
    }

    /** Reconstructs the secret from at least k shares of one split. It cannot tell a wrong set from a right one: use [combineVerified]. */
    fun combine(shares: List<ByteString>): CombineResult {
        if (shares.isEmpty()) return CombineResult.Failure(ShamirFailure.TooFewShares)
        val parsed = shares.map { parse(it) ?: return CombineResult.Failure(ShamirFailure.MalformedShare) }
        val first = parsed.first()
        if (parsed.any { it.threshold != first.threshold || it.y.size != first.y.size }) {
            return CombineResult.Failure(ShamirFailure.MixedShares)
        }
        if (parsed.map { it.x }.toSet().size != parsed.size) return CombineResult.Failure(ShamirFailure.DuplicateShare)
        if (parsed.size < first.threshold) return CombineResult.Failure(ShamirFailure.TooFewShares)
        val used = parsed.take(first.threshold)
        val secret = ByteArray(first.y.size)
        for (i in secret.indices) {
            var total = 0
            for (j in used.indices) {
                var numerator = 1
                var denominator = 1
                for (m in used.indices) {
                    if (m == j) continue
                    numerator = Gf256.multiply(numerator, used[m].x)
                    denominator = Gf256.multiply(denominator, used[m].x xor used[j].x)
                }
                total = total xor Gf256.multiply(used[j].y[i].toInt() and BYTE_MASK, Gf256.divide(numerator, denominator))
            }
            secret[i] = total.toByte()
        }
        return CombineResult.Success(ByteString.of(secret))
    }

    /** As [combine], but fails with [ShamirFailure.WrongSecret] unless the BLAKE2b-256 of the result equals [expectedHash]. */
    fun combineVerified(shares: List<ByteString>, expectedHash: ByteString, crypto: Crypto): CombineResult {
        val result = combine(shares)
        if (result !is CombineResult.Success) return result
        return if (crypto.hash256(result.secret.toByteArray()) == expectedHash) result
        else CombineResult.Failure(ShamirFailure.WrongSecret)
    }

    private class ParsedShare(val threshold: Int, val x: Int, val y: ByteArray)

    private fun parse(share: ByteString): ParsedShare? {
        if (share.size <= HEADER_BYTES) return null
        val bytes = share.toByteArray()
        val threshold = bytes[THRESHOLD_AT].toInt() and BYTE_MASK
        val x = bytes[X_AT].toInt() and BYTE_MASK
        val valid = bytes[VERSION_AT].toInt() == SHAMIR_FORMAT_VERSION && threshold >= SHAMIR_MIN_THRESHOLD && x != 0
        return if (valid) ParsedShare(threshold, x, bytes.copyOfRange(HEADER_BYTES, bytes.size)) else null
    }
}
