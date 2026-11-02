package circles.server.domain

import circles.api.PushResult
import circles.api.PushStatus
import circles.crypto.LibsodiumCipher
import circles.event.DeviceIdentity
import circles.event.Envelope
import circles.event.EventAuthor
import circles.event.FIRST_EPOCH
import circles.event.InviteKey
import circles.event.KeyRing
import circles.event.RejectionCode
import circles.event.newCircleId
import circles.event.toDto
import circles.server.support.TODAY
import circles.server.support.TestServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** `steward.add` and `steward.remove` (ADR 0022). */
class StewardTest {
    private val server = TestServer(allowPlaintext = false)
    private val crypto = server.crypto
    private val cipher = LibsodiumCipher()
    private val sync = server.services.sync
    private val circle = newCircleId(crypto)
    private val key = KeyRing.newCircleKey(crypto)

    private fun author() = EventAuthor(crypto, DeviceIdentity.generate(crypto, cipher = cipher), cipher = cipher)

    private val founder = author().also { it.keyRing = KeyRing.EMPTY.with(FIRST_EPOCH, key) }
    private val bea = author().also { it.keyRing = KeyRing.EMPTY.with(FIRST_EPOCH, key) }
    private val carol = author()

    @AfterTest
    fun close() = server.close()

    private fun push(e: Envelope): PushResult = sync.submit(listOf(e.toDto()), Entrance.Push(circle)).single()

    private fun accept(e: Envelope) {
        val r = push(e)
        assertEquals(PushStatus.ACCEPTED, r.status, "${e.type}: ${r.reason} ${r.detail}")
    }

    private fun reject(code: RejectionCode, e: Envelope) {
        val r = push(e)
        assertEquals(PushStatus.REJECTED, r.status, "expected ${code.wire}")
        assertEquals(code.wire, r.reason, r.detail)
    }

    private fun join(who: EventAuthor) {
        val invite = InviteKey(crypto.generateSigningKeyPair())
        val event = founder.invite(circle, invite, TODAY + 30, 1, TODAY)
        accept(event)
        val r = sync.submit(listOf(who.join(circle, event.eventId(crypto), invite, TODAY).toDto()), Entrance.Join(circle)).single()
        assertEquals(PushStatus.ACCEPTED, r.status, r.detail)
    }

    private fun setup() {
        assertEquals(PushStatus.ACCEPTED, sync.submit(listOf(founder.circleCreate(circle, TODAY).toDto()), Entrance.Create).single().status)
        accept(founder.keyWrap(circle, founder.identity.registration, FIRST_EPOCH, key, TODAY))
        join(bea)
        join(carol)
    }

    private fun stewards(): Set<String> = server.rows("SELECT member_id FROM stewards WHERE removed_seq IS NULL").map { it[0] as String }.toSet()

    @Test
    fun aStewardAddsAMemberAndTheNewStewardCanActAtOnce() {
        setup()
        accept(founder.stewardAdd(circle, bea.identity.member, TODAY, "second pair of hands"))
        assertEquals(setOf(founder.identity.member.v, bea.identity.member.v), stewards())
        accept(bea.stewardAdd(circle, carol.identity.member, TODAY))
        assertEquals(3, stewards().size)
    }

    @Test
    fun onlyAStewardMayChangeStewardsAndTheTargetMustBeAMember() {
        setup()
        reject(RejectionCode.NOT_STEWARD, bea.stewardAdd(circle, bea.identity.member, TODAY))
        reject(RejectionCode.NOT_STEWARD, carol.stewardRemove(circle, founder.identity.member, TODAY))
        reject(RejectionCode.INVALID_PUBLIC, founder.stewardAdd(circle, author().identity.member, TODAY))
    }

    @Test
    fun addingAStewardTwiceOrRemovingANonStewardIsRefused() {
        setup()
        reject(RejectionCode.INVALID_PUBLIC, founder.stewardAdd(circle, founder.identity.member, TODAY))
        reject(RejectionCode.INVALID_PUBLIC, founder.stewardRemove(circle, bea.identity.member, TODAY))
    }

    @Test
    fun theLastStewardCannotBeRemovedButOneMayLeaveWhileAnotherRemains() {
        setup()
        reject(RejectionCode.INVALID_PUBLIC, founder.stewardRemove(circle, founder.identity.member, TODAY))
        accept(founder.stewardAdd(circle, bea.identity.member, TODAY))
        accept(founder.stewardRemove(circle, founder.identity.member, TODAY))
        assertEquals(setOf(bea.identity.member.v), stewards())
        reject(RejectionCode.NOT_STEWARD, founder.stewardAdd(circle, carol.identity.member, TODAY))
        reject(RejectionCode.INVALID_PUBLIC, bea.stewardRemove(circle, bea.identity.member, TODAY))
    }

    @Test
    fun aRemovedStewardCanBeAddedBackAndRecoverySharesFollowTheRole() {
        setup()
        accept(founder.stewardAdd(circle, bea.identity.member, TODAY))
        val devices = listOf(founder.identity.registration, bea.identity.registration)
        checkNotNull(founder.recoveryShares(circle, FIRST_EPOCH, devices, 2, TODAY)).forEach { accept(it) }
        accept(founder.stewardRemove(circle, bea.identity.member, TODAY))
        val shares = checkNotNull(founder.recoveryShares(circle, FIRST_EPOCH, devices, 2, TODAY))
        accept(shares[0])
        reject(RejectionCode.NOT_STEWARD, shares[1])
        accept(founder.stewardAdd(circle, bea.identity.member, TODAY))
        accept(shares[1])
        assertEquals(2, stewards().size)
    }
}
