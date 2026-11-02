package circles.recovery

import circles.encoding.ByteString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class Gf256Test {
    @Test
    fun matchesTheAesMultiplicationVector() {
        assertEquals(0xC1, Gf256.multiply(0x57, 0x83))
        assertEquals(0xFE, Gf256.multiply(0x57, 0x13)) // FIPS-197 section 4.2
    }

    @Test
    fun fieldAxiomsHoldForEveryPair() {
        for (a in 0..255) {
            assertEquals(a, Gf256.multiply(a, 1))
            assertEquals(0, Gf256.multiply(a, 0))
            if (a != 0) assertEquals(1, Gf256.multiply(a, Gf256.inverse(a)), "inverse of $a")
            for (b in 0..255) assertEquals(Gf256.multiply(a, b), Gf256.multiply(b, a))
        }
    }

    @Test
    fun multiplicationDistributesAndAssociates() {
        for (a in 0..255 step 7) for (b in 0..255 step 5) for (c in 0..255 step 3) {
            assertEquals(Gf256.multiply(a, b xor c), Gf256.multiply(a, b) xor Gf256.multiply(a, c))
            assertEquals(Gf256.multiply(Gf256.multiply(a, b), c), Gf256.multiply(a, Gf256.multiply(b, c)))
        }
    }
}

class ShamirTest {
    private fun hex(text: String): ByteString = ByteString.fromHex(text)!!

    // Vectors from an independent Python implementation using log tables (not the code under test).
    private val secret = hex("101112131415161718191a1b1c1d1e1f202122232425262728292a2b2c2d2e2f")
    private val coefficients = hex("0b30557a9fc4e90e33587da2c7ec11365b80a5caef14395e83a8cdf2173c6186abd0f51a3f6489aed3f81d42678cb1d6fb20456a8fb4d9fe23486d92b7dc0126")
    private val vectorShares = listOf(
        "0103012b3e49f47fca3d30c376e17c372235f85bce79040f7acd40f306110c47d24508",
        "010302c6480ae209511eed984d8f0a9779c0f300b8d7a4540cee2b4510c957518906b5",
        "010303fd675105628e35ca4322746dbc46eb147b578c837f53054c9e3ff2703a766d92",
        "01030411bfac7a2f34651eb7afd06848bdaf381563c43f5c1db91adfdc951b4c61ab7d",
        "0103052a90f79d44eb4e396cc02b0f638284df6e8c9f187742527d04f3ae3c279ec05a",
    ).map { hex(it) }

    private var counter = 0
    private fun pseudoRandom(count: Int): ByteString =
        ByteString.of(ByteArray(count) { (++counter * 131 + 17).toByte() })

    private fun combined(shares: List<ByteString>): ByteString =
        assertIs<CombineResult.Success>(Shamir.combine(shares)).secret

    private fun failure(result: CombineResult): ShamirFailure = assertIs<CombineResult.Failure>(result).reason

    private fun subsets(items: List<ByteString>, size: Int): List<List<ByteString>> =
        if (size == 0) listOf(emptyList())
        else if (items.size < size) emptyList()
        else subsets(items.drop(1), size - 1).map { listOf(items.first()) + it } + subsets(items.drop(1), size)

    @Test
    fun splitMatchesTheIndependentVectors() {
        val result = assertIs<SplitResult.Success>(Shamir.split(secret, 3, 5, { coefficients }))
        assertEquals(vectorShares, result.shares)
    }

    @Test
    fun everyKSubsetOfTheVectorSharesReconstructs() {
        subsets(vectorShares, 3).forEach { assertEquals(secret, combined(it)) }
        subsets(vectorShares, 4).forEach { assertEquals(secret, combined(it)) }
        assertEquals(secret, combined(vectorShares.reversed()))
    }

    @Test
    fun everyKSubsetReconstructsForSeveralParameters() {
        for ((k, n) in listOf(2 to 2, 2 to 5, 3 to 5, 4 to 7, 5 to 5, 3 to 8)) {
            val shares = assertIs<SplitResult.Success>(Shamir.split(secret, k, n, ::pseudoRandom)).shares
            assertEquals(n, shares.size)
            subsets(shares, k).forEach { assertEquals(secret, combined(it), "k=$k n=$n") }
        }
    }

    @Test
    fun fewerThanKSharesAreRefused() {
        subsets(vectorShares, 2).forEach { assertEquals(ShamirFailure.TooFewShares, failure(Shamir.combine(it))) }
        assertEquals(ShamirFailure.TooFewShares, failure(Shamir.combine(emptyList())))
    }

    @Test
    fun kMinusOneSharesRevealNothingEvenIfForcedToInterpolate() {
        // Pretend the two shares are a 2-of-n set by rewriting k: the answer is a different, unrelated value.
        val forged = vectorShares.take(2).map { ByteString.of(it.toByteArray().also { b -> b[1] = 2 }) }
        assertNotEquals(secret, combined(forged))
    }

    @Test
    fun twoSharesFromDifferentSecretsDoNotReconstructEither() {
        val other = assertIs<SplitResult.Success>(Shamir.split(hex("00".repeat(32)), 3, 5, ::pseudoRandom)).shares
        assertNotEquals(secret, combined(listOf(vectorShares[0], vectorShares[1], other[2])))
    }

    @Test
    fun badSharesAreRefusedWithAReason() {
        val s = vectorShares
        assertEquals(ShamirFailure.DuplicateShare, failure(Shamir.combine(listOf(s[0], s[0], s[1]))))
        assertEquals(ShamirFailure.MalformedShare, failure(Shamir.combine(listOf(s[0], s[1], ByteString.of(byteArrayOf(1, 3, 1))))))
        val wrongVersion = ByteString.of(s[2].toByteArray().also { it[0] = 2 })
        assertEquals(ShamirFailure.MalformedShare, failure(Shamir.combine(listOf(s[0], s[1], wrongVersion))))
        val zeroX = ByteString.of(s[2].toByteArray().also { it[2] = 0 })
        assertEquals(ShamirFailure.MalformedShare, failure(Shamir.combine(listOf(s[0], s[1], zeroX))))
        val mixedK = ByteString.of(s[2].toByteArray().also { it[1] = 4 })
        assertEquals(ShamirFailure.MixedShares, failure(Shamir.combine(listOf(s[0], s[1], mixedK))))
        val shorter = ByteString.of(s[2].toByteArray().copyOf(20))
        assertEquals(ShamirFailure.MixedShares, failure(Shamir.combine(listOf(s[0], s[1], shorter))))
    }

    @Test
    fun invalidSplitParametersAreRefused() {
        fun reason(r: SplitResult) = assertIs<SplitResult.Failure>(r).reason
        assertEquals(ShamirFailure.InvalidParameters, reason(Shamir.split(secret, 1, 3, ::pseudoRandom)))
        assertEquals(ShamirFailure.InvalidParameters, reason(Shamir.split(secret, 4, 3, ::pseudoRandom)))
        assertEquals(ShamirFailure.InvalidParameters, reason(Shamir.split(secret, 2, 256, ::pseudoRandom)))
        assertEquals(ShamirFailure.EmptySecret, reason(Shamir.split(ByteString.EMPTY, 2, 3, ::pseudoRandom)))
        assertEquals(ShamirFailure.InvalidParameters, reason(Shamir.split(secret, 2, 3) { ByteString.EMPTY }))
    }

    @Test
    fun sharesLookRandomAndDifferPerSplit() {
        val a = assertIs<SplitResult.Success>(Shamir.split(secret, 3, 5, ::pseudoRandom)).shares
        val b = assertIs<SplitResult.Success>(Shamir.split(secret, 3, 5, ::pseudoRandom)).shares
        assertNotEquals(a, b)
        assertTrue(a.none { it.toHex().contains(secret.toHex()) })
    }

    @Test
    fun supportsTheMaximumNumberOfShares() {
        val shares = assertIs<SplitResult.Success>(Shamir.split(secret, 2, SHAMIR_MAX_SHARES, ::pseudoRandom)).shares
        assertEquals(SHAMIR_MAX_SHARES, shares.size)
        assertEquals(secret, combined(listOf(shares.first(), shares.last())))
    }
}
