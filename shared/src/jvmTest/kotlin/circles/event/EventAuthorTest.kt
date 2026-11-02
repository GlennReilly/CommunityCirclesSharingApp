package circles.event

import circles.crypto.JvmCrypto
import circles.crypto.LibsodiumCipher
import circles.encoding.ByteString
import circles.encoding.Parsed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventAuthorTest {
    private val crypto = JvmCrypto()
    private val cipher = LibsodiumCipher()
    private val founder = EventAuthor(crypto, DeviceIdentity.generate(crypto, cipher), cipher).also {
        it.keyRing = KeyRing.EMPTY.with(FIRST_EPOCH, KeyRing.newCircleKey(crypto))
    }
    private val circle = newCircleId(crypto)

    private fun registration(envelope: Envelope): DeviceRegistration {
        val parsed = PublicParts.registration(envelope.public)
        assertIs<Parsed.Ok<DeviceRegistration>>(parsed)
        return parsed.value
    }

    @Test
    fun generatedIdsAreRandomLowercaseHex() {
        val id = newMemberId(crypto)
        assertTrue(isValidRandomId(id.v))
        assertTrue(isValidRandomId(newDeviceId(crypto).v))
        assertTrue(isValidRandomId(circle.v))
    }

    @Test
    fun circleCreateRegistersTheFoundersDeviceAndIsSigned() {
        val event = founder.circleCreate(circle, day = 100)
        assertEquals(EventType.CIRCLE_CREATE.wire, event.type)
        assertEquals(founder.identity.registration, registration(event))
        assertNull(EnvelopeChecks.check(event))
        assertTrue(event.hasValidSignature(crypto, founder.identity.signing.publicKey))
    }

    @Test
    fun counterGoesUpByOnePerEvent() {
        val first = founder.circleCreate(circle, 100)
        val second = founder.circleCreate(circle, 100)
        assertEquals(listOf(0L, 1L), listOf(first.ctr, second.ctr))
        assertEquals(7L, EventAuthor(crypto, founder.identity, cipher, firstCounter = 7).circleCreate(circle, 1).ctr)
    }

    @Test
    fun inviteCarriesThePublicKeyAndAnOptionalNote() {
        val key = InviteKey(crypto.generateSigningKeyPair())
        val event = founder.invite(circle, key, expiresDay = 130, maxUses = 2, day = 100, note = "for Sam")
        assertIs<Parsed.Ok<InviteTerms>>(PublicParts.invite(event.public)).also {
            assertEquals(InviteTerms(key.keys.publicKey, 130, 2), it.value)
        }
        assertEquals(Parsed.Ok("for Sam"), PublicParts.optionalText((PayloadSealing.open(cipher, founder.keyRing, event) as Parsed.Ok).value, PublicParts.NOTE))
        assertTrue(founder.invite(circle, key, 130, 2, 100).encrypted.isEmpty())
    }

    @Test
    fun joinProofVerifiesWithTheInvitePublicKey() {
        val key = InviteKey(crypto.generateSigningKeyPair())
        val joiner = EventAuthor(crypto, DeviceIdentity.generate(crypto, cipher), cipher)
        val inviteId = EventId("ab".repeat(32))
        val event = joiner.join(circle, inviteId, key, day = 101)
        val terms = (PublicParts.join(event.public) as Parsed.Ok).value
        assertEquals(inviteId, terms.invite)
        assertEquals(joiner.identity.registration, terms.registration)
        assertTrue(crypto.verify(key.keys.publicKey, joinProofMessage(circle, terms.registration), terms.proof))
        assertTrue(!crypto.verify(key.keys.publicKey, joinProofMessage(newCircleId(crypto), terms.registration), terms.proof))
        assertTrue(event.hasValidSignature(crypto, joiner.identity.signing.publicKey))
    }

    @Test
    fun deviceAddRegistersTheNewDeviceButIsSignedByTheExistingOne() {
        val newDevice = DeviceIdentity.generate(crypto, cipher, founder.identity.member)
        val event = founder.deviceAdd(circle, newDevice, day = 102, label = "phone")
        assertEquals(newDevice.registration, registration(event))
        assertEquals(founder.identity.device, event.device)
        assertTrue(event.hasValidSignature(crypto, founder.identity.signing.publicKey))
        assertEquals(Parsed.Ok("phone"), PublicParts.optionalText((PayloadSealing.open(cipher, founder.keyRing, event) as Parsed.Ok).value, PublicParts.LABEL))
    }

    @Test
    fun builtEventsSurviveTheWireRoundTrip() {
        val event = founder.invite(circle, InviteKey(crypto.generateSigningKeyPair()), 130, 1, 100, note = "x")
        val back = (event.toDto().toEnvelope() as Parsed.Ok).value
        assertEquals(event, back)
        assertEquals(event.eventId(crypto), back.eventId(crypto))
        assertEquals(ByteString.of(event.canonicalBytes()), ByteString.of(back.canonicalBytes()))
    }
}
