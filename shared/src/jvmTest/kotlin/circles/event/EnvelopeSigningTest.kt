package circles.event

import circles.crypto.JvmCrypto
import circles.encoding.ByteString
import circles.encoding.CborInt
import circles.encoding.CborMap
import circles.policy.CircleId
import circles.policy.MemberId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EnvelopeSigningTest {
    private val crypto = JvmCrypto()

    // From the Python reference: seed 0x77 repeated, signing over EnvelopeVectors.SIGNING_HEX.
    private val seed = ByteString.fromHex("77".repeat(32))!!
    private val publicKey = ByteString.fromHex("c853ad0f0cd2b619aea92ceec4fd56a24d6499d584ce79257e45cfd8139b60a7")!!
    private val signature = ByteString.fromHex(
        "ec483f32a85fe7d44e818db5cf7cf23f07acac4c7fec4d8327a03694114c8e7cb69000d1e265afe8cb26a222bdf6191deaf6f7f38909cc027d41b035e9e1a903",
    )!!
    private val signedId = "40eefcc4df2f202ef75e61aea15182c86c75682cb69c3f02c49bc31d7b3dca18"

    @Test
    fun signatureMatchesTheReferenceVector() {
        val signed = EnvelopeVectors.unsigned.signedBy(crypto, seed)
        assertEquals(signature, signed.sig)
        assertTrue(signed.hasValidSignature(crypto, publicKey))
    }

    @Test
    fun idMatchesTheReferenceVector() {
        val signed = EnvelopeVectors.unsigned.copy(sig = signature)
        assertEquals(signedId, signed.eventId(crypto).v)
        assertEquals(EnvelopeVectors.CANONICAL_ID, EnvelopeVectors.withFakeSig.eventId(crypto).v)
    }

    @Test
    fun idChangesWhenOnlyTheSignatureChanges() {
        val a = EnvelopeVectors.unsigned.copy(sig = signature)
        val b = a.copy(sig = EnvelopeVectors.fakeSig)
        assertNotEquals(a.eventId(crypto), b.eventId(crypto))
    }

    @Test
    fun changingAnySignedFieldInvalidatesTheSignature() {
        val signed = EnvelopeVectors.unsigned.signedBy(crypto, seed)
        val tampered = listOf(
            signed.copy(v = 1),
            signed.copy(circle = CircleId("12".repeat(16))),
            signed.copy(author = MemberId("23".repeat(16))),
            signed.copy(device = DeviceId("34".repeat(16))),
            signed.copy(type = "member.join"),
            signed.copy(day = signed.day + 1),
            signed.copy(ctr = signed.ctr + 1),
            signed.copy(epoch = 1),
            signed.copy(public = CborMap(signed.public.entries + ("extra" to CborInt(1)))),
            signed.copy(nonce = ByteString.fromHex("00")!!),
            signed.copy(encrypted = ByteString.fromHex("a0")!!),
        )
        tampered.forEach { assertFalse(it.hasValidSignature(crypto, publicKey), "tampered $it must not verify") }
    }

    @Test
    fun aSignatureByAnotherKeyDoesNotVerify() {
        val other = crypto.generateSigningKeyPair()
        val signed = EnvelopeVectors.unsigned.signedBy(crypto, other.secretSeed)
        assertFalse(signed.hasValidSignature(crypto, publicKey))
        assertTrue(signed.hasValidSignature(crypto, other.publicKey))
    }
}
