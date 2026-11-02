package circles.recovery

import circles.crypto.JvmCrypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ShamirVerifiedTest {
    private val crypto = JvmCrypto()

    @Test
    fun combineVerifiedAcceptsTheRightSetAndRejectsWrongOnes() {
        val secret = crypto.randomBytes(32)
        val expected = crypto.hash256(secret.toByteArray())
        val shares = assertIs<SplitResult.Success>(Shamir.split(secret, 3, 5, crypto::randomBytes)).shares
        val ok = assertIs<CombineResult.Success>(Shamir.combineVerified(shares.take(3), expected, crypto))
        assertEquals(secret, ok.secret)
        val corrupted = shares[2].toByteArray().also { it[5] = (it[5].toInt() xor 1).toByte() }
        val bad = Shamir.combineVerified(listOf(shares[0], shares[1], circles.encoding.ByteString.of(corrupted)), expected, crypto)
        assertEquals(ShamirFailure.WrongSecret, assertIs<CombineResult.Failure>(bad).reason)
        val few = Shamir.combineVerified(shares.take(2), expected, crypto)
        assertEquals(ShamirFailure.TooFewShares, assertIs<CombineResult.Failure>(few).reason)
    }
}
