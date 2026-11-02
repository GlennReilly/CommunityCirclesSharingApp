package circles.server.domain

import circles.server.support.testCipher
import circles.api.PushResult
import circles.api.PushStatus
import circles.crypto.LibsodiumCipher
import circles.encoding.ByteString
import circles.encoding.Parsed
import circles.event.CircleKeys
import circles.event.DeviceIdentity
import circles.event.Envelope
import circles.event.EventAuthor
import circles.event.FIRST_EPOCH
import circles.event.InviteKey
import circles.event.KeyRing
import circles.event.PayloadSealing
import circles.event.RejectionCode
import circles.event.newCircleId
import circles.event.toDto
import circles.server.support.TODAY
import circles.server.support.TestServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** `device.revoke` and the key rotation that must follow it (spec 5, ADR 0021). */
class RevokeAndRotateTest {
    private val server = TestServer()
    private val crypto = server.crypto
    private val cipher = LibsodiumCipher()
    private val sync = server.services.sync
    private val circle = newCircleId(crypto)
    private val key1 = KeyRing.newCircleKey(crypto)

    private fun author(identity: DeviceIdentity = DeviceIdentity.generate(crypto, cipher = cipher)) = EventAuthor(crypto, identity, cipher = cipher)

    private val founder = author().also { it.keyRing = KeyRing.EMPTY.with(FIRST_EPOCH, key1) }

    @AfterTest
    fun close() = server.close()

    private fun push(vararg events: Envelope): PushResult = sync.submit(events.map { it.toDto() }, Entrance.Push(circle)).single()

    private fun accept(vararg events: Envelope) = events.forEach {
        val r = push(it)
        assertEquals(PushStatus.ACCEPTED, r.status, "${it.type}: ${r.reason} ${r.detail}")
    }

    private fun assertRejected(code: RejectionCode, result: PushResult) {
        assertEquals(PushStatus.REJECTED, result.status, "expected ${code.wire}")
        assertEquals(code.wire, result.reason, result.detail)
    }

    private fun setup(): EventAuthor {
        accept1(founder.circleCreate(circle, TODAY))
        accept(founder.keyWrap(circle, founder.identity.registration, FIRST_EPOCH, key1, TODAY))
        val invite = InviteKey(crypto.generateSigningKeyPair())
        val inviteEvent = founder.invite(circle, invite, TODAY + 30, 1, TODAY)
        accept(inviteEvent)
        val bea = author()
        val joined = sync.submit(listOf(bea.join(circle, inviteEvent.eventId(crypto), invite, TODAY).toDto()), Entrance.Join(circle)).single()
        assertEquals(PushStatus.ACCEPTED, joined.status, joined.detail)
        accept(founder.keyWrap(circle, bea.identity.registration, FIRST_EPOCH, key1, TODAY))
        bea.keyRing = bea.keyRing.with(FIRST_EPOCH, key1)
        return bea
    }

    private fun accept1(create: Envelope) {
        assertEquals(PushStatus.ACCEPTED, sync.submit(listOf(create.toDto()), Entrance.Create).single().status)
    }

    @Test
    fun aMemberRevokesTheirOwnOtherDeviceAndItCanNoLongerPush() {
        val bea = setup()
        val phone = author(DeviceIdentity.generate(crypto, cipher, bea.identity.member))
        accept(bea.deviceAdd(circle, phone.identity, TODAY, "old phone"))
        accept(bea.deviceRevoke(circle, phone.identity.device, TODAY, "lost it"))
        assertNotNull(server.rows("SELECT revoked_seq FROM devices WHERE device_id = '${phone.identity.device.v}'").single()[0])
        assertRejected(RejectionCode.DEVICE_REVOKED, push(phone.invite(circle, InviteKey(crypto.generateSigningKeyPair()), TODAY + 30, 1, TODAY)))
        assertRejected(RejectionCode.DEVICE_REVOKED, push(bea.deviceRevoke(circle, phone.identity.device, TODAY)))
        assertRejected(RejectionCode.DEVICE_REVOKED, push(founder.keyWrap(circle, phone.identity.registration, FIRST_EPOCH, key1, TODAY)))
    }

    @Test
    fun onlyAStewardMayRevokeAnotherMembersDevice() {
        val bea = setup()
        assertRejected(RejectionCode.NOT_STEWARD, push(bea.deviceRevoke(circle, founder.identity.device, TODAY)))
        accept(founder.deviceRevoke(circle, bea.identity.device, TODAY))
        assertRejected(RejectionCode.DEVICE_REVOKED, push(bea.invite(circle, InviteKey(crypto.generateSigningKeyPair()), TODAY + 30, 1, TODAY)))
    }

    @Test
    fun revokingAnUnknownDeviceOrOneInAnotherCircleIsRefused() {
        setup()
        assertRejected(RejectionCode.UNKNOWN_DEVICE, push(founder.deviceRevoke(circle, DeviceIdentity.generate(crypto, testCipher).device, TODAY)))
    }

    @Test
    fun afterARotationTheRevokedDeviceCannotReadNewEventsButKeepsTheOldEpoch() {
        val bea = setup()
        val ex = author(DeviceIdentity.generate(crypto, cipher, bea.identity.member))
        accept(bea.deviceAdd(circle, ex.identity, TODAY))
        accept(bea.keyWrap(circle, ex.identity.registration, FIRST_EPOCH, key1, TODAY))
        ex.keyRing = ex.keyRing.with(FIRST_EPOCH, key1)

        accept(founder.deviceRevoke(circle, ex.identity.device, TODAY, "left the circle"))
        val current = listOf(founder.identity.registration, bea.identity.registration)
        accept(*founder.rotateKeys(circle, 2, current, TODAY).toTypedArray())
        assertRejected(RejectionCode.DEVICE_REVOKED, push(founder.keyWrap(circle, ex.identity.registration, 2, key1, TODAY)))

        // Bea unwraps epoch 2; the revoked device has no wrap for it.
        val events = sync.pull(circle, 0, 100).events.map { assertIs<Parsed.Ok<Envelope>>(it.envelope.toEnvelope()).value }
        val wrapForBea = events.single { it.type == "key.wrap" && it.epoch == 2 && (CircleKeys.keyWrap(it.public) as Parsed.Ok).value.device == bea.identity.device }
        val terms = (CircleKeys.keyWrap(wrapForBea.public) as Parsed.Ok).value
        val key2 = assertIs<Parsed.Ok<ByteString>>(CircleKeys.unwrap(cipher, bea.identity.boxKeys, circle, 2, terms.wrapped)).value
        bea.keyRing = bea.keyRing.with(2, key2)
        assertEquals(2, bea.keyRing.latestEpoch)

        val secret = founder.invite(circle, InviteKey(crypto.generateSigningKeyPair()), TODAY + 30, 1, TODAY, note = "after the revoke")
        assertEquals(2, secret.epoch)
        accept(secret)
        assertTrue(PayloadSealing.open(cipher, bea.keyRing, secret) is Parsed.Ok)
        assertTrue(PayloadSealing.open(cipher, ex.keyRing, secret) is Parsed.Invalid)
        // The old epoch stays readable for the revoked device's own earlier history.
        assertTrue(PayloadSealing.open(cipher, ex.keyRing, secretAt(FIRST_EPOCH)) is Parsed.Ok)
    }

    // ---- recovery.share (the server only has one steward until steward.add arrives, so the split is 2-of-2 over two devices of the founder) ----

    @Test
    fun recoverySharesGoOnlyFromAStewardToAStewardsDevice() {
        val bea = setup()
        val founderPhone = author(DeviceIdentity.generate(crypto, cipher, founder.identity.member))
        accept(founder.deviceAdd(circle, founderPhone.identity, TODAY))
        val stewardDevices = listOf(founder.identity.registration, founderPhone.identity.registration)
        val shares = checkNotNull(founder.recoveryShares(circle, FIRST_EPOCH, stewardDevices, 2, TODAY))
        accept(*shares.toTypedArray())

        bea.keyRing = bea.keyRing.with(FIRST_EPOCH, key1)
        assertRejected(RejectionCode.NOT_STEWARD, push(checkNotNull(bea.recoveryShares(circle, FIRST_EPOCH, stewardDevices, 2, TODAY)).first()))
        val toBea = checkNotNull(founder.recoveryShares(circle, FIRST_EPOCH, listOf(founder.identity.registration, bea.identity.registration), 2, TODAY))
        assertEquals(PushStatus.ACCEPTED, push(toBea[0]).status)
        assertRejected(RejectionCode.NOT_STEWARD, push(toBea[1]))
    }

    @Test
    fun aRecoveryShareForAnEpochNotStartedIsRefused() {
        setup()
        founder.keyRing = founder.keyRing.with(2, KeyRing.newCircleKey(crypto))
        val ahead = checkNotNull(founder.recoveryShares(circle, 2, listOf(founder.identity.registration, founder.identity.registration), 2, TODAY))
        assertRejected(RejectionCode.MALFORMED, push(ahead[0]))
    }

    private fun secretAt(epoch: Int): Envelope {
        val older = author(DeviceIdentity.generate(crypto, cipher = cipher))
        older.keyRing = KeyRing.EMPTY.with(epoch, key1)
        return older.invite(circle, InviteKey(crypto.generateSigningKeyPair()), TODAY + 30, 1, TODAY, note = "history")
    }
}
