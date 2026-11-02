package circles.event

import circles.encoding.ByteString
import circles.encoding.CborBytes
import circles.encoding.CborInt
import circles.encoding.CborMap
import circles.encoding.CborText
import circles.encoding.Parsed
import circles.policy.MemberId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class PublicPartsTest {
    private val registration = DeviceRegistration(
        EnvelopeVectors.member,
        EnvelopeVectors.device,
        EnvelopeVectors.signPub,
        EnvelopeVectors.boxPub,
    )
    private val invite = InviteTerms(ByteString.fromHex("77".repeat(32))!!, expiresDay = 20010, maxUses = 3)
    private val join = JoinTerms(registration, EventId("ab".repeat(32)), ByteString.fromHex("88".repeat(64))!!)

    private fun invalid(parsed: Parsed<*>) = assertIs<Parsed.Invalid>(parsed)

    @Test
    fun registrationRoundTrips() {
        assertEquals(Parsed.Ok(registration), PublicParts.registration(registration.toPublic()))
    }

    @Test
    fun registrationRefusesExtraMissingAndMistypedFields() {
        val good = registration.toPublic().entries
        invalid(PublicParts.registration(CborMap(good + ("extra" to CborInt(1)))))
        invalid(PublicParts.registration(CborMap(good - "boxPub")))
        invalid(PublicParts.registration(CborMap(good + ("member" to CborText("short")))))
        invalid(PublicParts.registration(CborMap(good + ("member" to CborText("AB".repeat(16))))))
        invalid(PublicParts.registration(CborMap(good + ("signPub" to CborBytes(ByteString.of(ByteArray(31)))))))
        invalid(PublicParts.registration(CborMap(good + ("boxPub" to CborText("x")))))
        invalid(PublicParts.registration(CborMap.EMPTY))
    }

    @Test
    fun inviteRoundTripsAndChecksRanges() {
        assertEquals(Parsed.Ok(invite), PublicParts.invite(invite.toPublic()))
        val good = invite.toPublic().entries
        invalid(PublicParts.invite(CborMap(good + ("maxUses" to CborInt(0)))))
        invalid(PublicParts.invite(CborMap(good + ("maxUses" to CborInt(MAX_INVITE_USES + 1L)))))
        invalid(PublicParts.invite(CborMap(good + ("expiresDay" to CborInt(-1)))))
        invalid(PublicParts.invite(CborMap(good + ("expiresDay" to CborInt(Long.MAX_VALUE)))))
        invalid(PublicParts.invite(CborMap(good + ("invitePub" to CborBytes(ByteString.EMPTY)))))
        invalid(PublicParts.invite(CborMap(good - "expiresDay")))
        assertEquals(Parsed.Ok(invite.copy(maxUses = MAX_INVITE_USES)), PublicParts.invite(invite.copy(maxUses = MAX_INVITE_USES).toPublic()))
    }

    @Test
    fun joinRoundTripsAndChecksInviteAndProof() {
        assertEquals(Parsed.Ok(join), PublicParts.join(join.toPublic()))
        val good = join.toPublic().entries
        invalid(PublicParts.join(CborMap(good + ("invite" to CborText("abc")))))
        invalid(PublicParts.join(CborMap(good + ("proof" to CborBytes(ByteString.of(ByteArray(63)))))))
        invalid(PublicParts.join(CborMap(good - "proof")))
        invalid(PublicParts.join(registration.toPublic()))
    }

    @Test
    fun optionalTextAcceptsAbsentAndShortText() {
        assertEquals(Parsed.Ok(null), PublicParts.optionalText(CborMap.EMPTY, PublicParts.NOTE))
        assertEquals(Parsed.Ok("hello"), PublicParts.optionalText(CborMap.of("note" to CborText("hello")), PublicParts.NOTE))
        assertEquals(Parsed.Ok("a".repeat(MAX_TEXT_CHARS)), PublicParts.optionalText(CborMap.of("note" to CborText("a".repeat(MAX_TEXT_CHARS))), PublicParts.NOTE))
    }

    @Test
    fun optionalTextRefusesLongWrongTypeAndOtherKeys() {
        invalid(PublicParts.optionalText(CborMap.of("note" to CborText("a".repeat(MAX_TEXT_CHARS + 1))), PublicParts.NOTE))
        invalid(PublicParts.optionalText(CborMap.of("note" to CborInt(1)), PublicParts.NOTE))
        invalid(PublicParts.optionalText(CborMap.of("label" to CborText("x")), PublicParts.NOTE))
    }

    @Test
    fun domainMessagesDependOnEveryInput() {
        val message = joinProofMessage(EnvelopeVectors.circle, registration)
        val otherCircle = joinProofMessage(circles.policy.CircleId("12".repeat(16)), registration)
        val otherMember = joinProofMessage(EnvelopeVectors.circle, registration.copy(member = MemberId("23".repeat(16))))
        val otherKey = joinProofMessage(EnvelopeVectors.circle, registration.copy(boxPub = ByteString.fromHex("56".repeat(32))!!))
        listOf(otherCircle, otherMember, otherKey).forEach { assertFalse(it.contentEquals(message)) }
        val nonce = ByteString.of(ByteArray(32))
        assertFalse(authMessage(EnvelopeVectors.device, nonce).contentEquals(authMessage(DeviceId("34".repeat(16)), nonce)))
    }
}
