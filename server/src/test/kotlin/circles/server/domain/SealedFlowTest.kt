package circles.server.domain

import circles.server.support.testCipher
import circles.api.PushResult
import circles.api.PushStatus
import circles.crypto.LibsodiumCipher
import circles.encoding.ByteString
import circles.encoding.CborMap
import circles.encoding.CborText
import circles.encoding.Parsed
import circles.event.CircleKeys
import circles.event.DeviceIdentity
import circles.event.Envelope
import circles.event.EventAuthor
import circles.event.FIRST_EPOCH
import circles.event.InviteKey
import circles.event.KeyRing
import circles.event.KeyWrapTerms
import circles.event.PAYLOAD_VERSION_SEALED
import circles.event.PayloadSealing
import circles.event.RejectionCode
import circles.event.newCircleId
import circles.event.toDto
import circles.policy.CircleId
import circles.server.support.TODAY
import circles.server.support.TestServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** M2 end to end against a server that refuses the plaintext format (ADR 0021). */
class SealedFlowTest {
    private val server = TestServer()
    private val crypto = server.crypto
    private val cipher = LibsodiumCipher()
    private val sync = server.services.sync
    private val circle: CircleId = newCircleId(crypto)
    private val circleKey = KeyRing.newCircleKey(crypto)

    private class Member(val author: EventAuthor) {
        val identity: DeviceIdentity get() = author.identity
    }

    private fun member(): Member = Member(EventAuthor(crypto, DeviceIdentity.generate(crypto, cipher = cipher), cipher = cipher))

    private val founder = member().also { it.author.keyRing = KeyRing.EMPTY.with(FIRST_EPOCH, circleKey) }

    @AfterTest
    fun close() = server.close()

    /** Re-signs a deliberately altered envelope so a test reaches the rule it targets rather than the signature check. */
    private fun resign(envelope: Envelope, who: Member): Envelope = envelope.signedBy(crypto, who.identity.signing.secretSeed)

    private fun push(vararg events: Envelope): List<PushResult> = sync.submit(events.map { it.toDto() }, Entrance.Push(circle))

    private fun accepted(result: PushResult) {
        assertEquals(PushStatus.ACCEPTED, result.status, "expected accepted but got ${result.reason}: ${result.detail}")
    }

    private fun assertRejected(code: RejectionCode, result: PushResult) {
        assertEquals(PushStatus.REJECTED, result.status, "expected ${code.wire} but the event was ${result.status}")
        assertEquals(code.wire, result.reason, result.detail)
    }

    private fun create() = accepted(sync.submit(listOf(founder.author.circleCreate(circle, TODAY).toDto()), Entrance.Create).single())

    private fun startKeys() = accepted(push(founder.author.keyWrap(circle, founder.identity.registration, FIRST_EPOCH, circleKey, TODAY)).single())

    private fun join(invite: Envelope, key: InviteKey, who: Member) =
        accepted(sync.submit(listOf(who.author.join(circle, invite.eventId(crypto), key, TODAY).toDto()), Entrance.Join(circle)).single())

    @Test
    fun theFullFlowKeepsEveryPlaintextOutOfTheDatabase() {
        create()
        startKeys()
        val inviteKey = InviteKey(crypto.generateSigningKeyPair())
        val note = "SECRET-NOTE-bring the good ladder"
        val invite = founder.author.invite(circle, inviteKey, TODAY + 30, 1, TODAY, note = note)
        accepted(push(invite).single())
        val bea = member()
        join(invite, inviteKey, bea)
        val wrap = founder.author.keyWrap(circle, bea.identity.registration, FIRST_EPOCH, circleKey, TODAY)
        accepted(push(wrap).single())
        val label = "SECRET-LABEL-founders phone"
        accepted(push(founder.author.deviceAdd(circle, DeviceIdentity.generate(crypto, cipher, founder.identity.member), TODAY, label)).single())

        // Bea pulls the log, opens her wrap, then reads the note.
        val events = sync.pull(circle, 0, 100).events.map { assertIs<Parsed.Ok<Envelope>>(it.envelope.toEnvelope()).value }
        assertEquals(setOf(PAYLOAD_VERSION_SEALED), events.map { it.v }.toSet())
        val beaWrap = events.single { it.type == "key.wrap" && (CircleKeys.keyWrap(it.public) as Parsed.Ok).value.device == bea.identity.device }
        val terms: KeyWrapTerms = (CircleKeys.keyWrap(beaWrap.public) as Parsed.Ok).value
        val key = assertIs<Parsed.Ok<ByteString>>(CircleKeys.unwrap(cipher, bea.identity.boxKeys, circle, beaWrap.epoch, terms.wrapped)).value
        val ring = KeyRing.EMPTY.with(beaWrap.epoch, key)
        val noteEvent = events.single { it.type == "member.invite" }
        val payload = assertIs<Parsed.Ok<CborMap>>(PayloadSealing.open(cipher, ring, noteEvent)).value
        assertEquals(note, (payload["note"] as CborText).value)

        // The M2 acceptance test: a dump of the database holds no plaintext payload and no circle key.
        val dump = dumpEveryTable()
        assertFalse(dump.contains(ByteString.of("SECRET-NOTE".encodeToByteArray()).toHex()), "note leaked")
        assertFalse(dump.contains(ByteString.of("SECRET-LABEL".encodeToByteArray()).toHex()), "label leaked")
        assertFalse(dump.contains(circleKey.toHex()), "circle key leaked")
        assertTrue(dump.contains(ByteString.of(noteEvent.encrypted.toByteArray()).toHex()), "the ciphertext should be stored")
    }

    private fun dumpEveryTable(): String {
        val tables = server.rows("SELECT name FROM sqlite_master WHERE type = 'table'").map { it[0] as String }
        return tables.joinToString("\n") { table ->
            server.rows("SELECT * FROM $table").joinToString("\n") { row ->
                row.joinToString("|") { cell ->
                    when (cell) {
                        is ByteArray -> ByteString.of(cell).toHex()
                        else -> cell.toString().let { text -> text + "|" + ByteString.of(text.encodeToByteArray()).toHex() }
                    }
                }
            }
        }
    }

    @Test
    fun everyFormatVersionButOneIsRefused() {
        val create = founder.author.circleCreate(circle, TODAY)
        listOf(0, 2, -1).forEach { v ->
            val other = create.copy(v = v).signedBy(crypto, founder.identity.signing.secretSeed)
            assertRejected(RejectionCode.UNSUPPORTED_VERSION, sync.submit(listOf(other.toDto()), Entrance.Create).single())
        }
    }

    @Test
    fun aSealedSecretBeforeAnyKeyExistsIsRefused() {
        create()
        val early = founder.author.invite(circle, InviteKey(crypto.generateSigningKeyPair()), TODAY + 30, 1, TODAY, note = "too early")
        assertRejected(RejectionCode.MALFORMED, push(early).single())
    }

    @Test
    fun aSealedSecretForAnEpochBeyondTheCurrentOneIsRefused() {
        create()
        startKeys()
        founder.author.keyRing = founder.author.keyRing.with(2, KeyRing.newCircleKey(crypto))
        val ahead = founder.author.invite(circle, InviteKey(crypto.generateSigningKeyPair()), TODAY + 30, 1, TODAY, note = "from the future")
        assertEquals(2, ahead.epoch)
        assertRejected(RejectionCode.MALFORMED, push(ahead).single())
    }

    @Test
    fun anEventWithoutASecretPartKeepsEpochZero() {
        create()
        val noNote = founder.author.invite(circle, InviteKey(crypto.generateSigningKeyPair()), TODAY + 30, 1, TODAY)
        assertEquals(0, noNote.epoch)
        accepted(push(noNote).single())
        assertRejected(RejectionCode.MALFORMED, push(noNote.copy(epoch = 1).let { resign(it, founder) }).single())
    }

    @Test
    fun theFirstKeyMustBeEpochOneFromAStewardToARegisteredDevice() {
        create()
        val stranger = member()
        // Epoch 2 as the first key.
        assertRejected(RejectionCode.MALFORMED, push(founder.author.keyWrap(circle, founder.identity.registration, 2, circleKey, TODAY)).single())
        // A wrap to a device the circle does not know.
        assertRejected(RejectionCode.UNKNOWN_DEVICE, push(founder.author.keyWrap(circle, stranger.identity.registration, FIRST_EPOCH, circleKey, TODAY)).single())
        startKeys()
        assertEquals(FIRST_EPOCH, (server.rows("SELECT current_epoch FROM circles")[0][0] as Number).toInt())
    }

    @Test
    fun aNonStewardCannotStartTheFirstKeyOrRotate() {
        create()
        val inviteKey = InviteKey(crypto.generateSigningKeyPair())
        val invite = founder.author.invite(circle, inviteKey, TODAY + 30, 1, TODAY)
        accepted(push(invite).single())
        val bea = member()
        join(invite, inviteKey, bea)
        bea.author.keyRing = KeyRing.EMPTY.with(FIRST_EPOCH, circleKey)
        assertRejected(RejectionCode.NOT_STEWARD, push(bea.author.keyWrap(circle, bea.identity.registration, FIRST_EPOCH, circleKey, TODAY)).single())
        startKeys()
        assertRejected(RejectionCode.NOT_STEWARD, push(bea.author.keyRotate(circle, 2, TODAY)).single())
        // Once epoch 1 exists, any member device may pass it on.
        accepted(push(bea.author.keyWrap(circle, founder.identity.registration, FIRST_EPOCH, circleKey, TODAY)).single())
    }

    @Test
    fun rotationMustBeTheNextEpochAndKeepsOldEpochsWrappable() {
        create()
        assertRejected(RejectionCode.MALFORMED, push(founder.author.keyRotate(circle, 2, TODAY)).single()) // no first key yet
        startKeys()
        assertRejected(RejectionCode.MALFORMED, push(founder.author.keyRotate(circle, 3, TODAY)).single())
        assertRejected(RejectionCode.MALFORMED, push(founder.author.keyRotate(circle, 1, TODAY)).single())
        accepted(push(founder.author.keyRotate(circle, 2, TODAY)).single())
        val second = KeyRing.newCircleKey(crypto)
        accepted(push(founder.author.keyWrap(circle, founder.identity.registration, 2, second, TODAY)).single())
        accepted(push(founder.author.keyWrap(circle, founder.identity.registration, FIRST_EPOCH, circleKey, TODAY)).single())
        assertRejected(RejectionCode.MALFORMED, push(founder.author.keyWrap(circle, founder.identity.registration, 3, second, TODAY)).single())
        founder.author.keyRing = founder.author.keyRing.with(2, second)
        accepted(push(founder.author.invite(circle, InviteKey(crypto.generateSigningKeyPair()), TODAY + 30, 1, TODAY, note = "epoch two")).single())
    }

    @Test
    fun aWrapWhoseEpochDisagreesWithItsPublicPartIsRefused() {
        create()
        startKeys()
        val wrap = founder.author.keyWrap(circle, founder.identity.registration, FIRST_EPOCH, circleKey, TODAY)
        val skewed = wrap.copy(epoch = 2)
        assertRejected(RejectionCode.INVALID_PUBLIC, push(resign(skewed, founder)).single())
    }
}
