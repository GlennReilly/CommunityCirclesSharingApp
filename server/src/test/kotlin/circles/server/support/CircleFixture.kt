package circles.server.support

import circles.api.PushStatus
import circles.crypto.Crypto
import circles.encoding.CborMap
import circles.encoding.CborText
import circles.event.DeviceIdentity
import circles.event.Envelope
import circles.event.EventAuthor
import circles.event.FIRST_EPOCH
import circles.event.InviteKey
import circles.event.ItemKeys
import circles.event.KeyRing
import circles.event.ReturnOutcome
import circles.event.newCircleId
import circles.event.newDetailKeyId
import circles.event.newItemId
import circles.event.newLoanId
import circles.event.toDto
import circles.policy.CircleId
import circles.policy.ItemClass
import circles.server.domain.Entrance
import circles.server.web.Services
import kotlin.test.assertEquals

/**
 * A realistic circle pushed through the real rules of one server: a founder and a member, the circle key wrapped to
 * both, a sealed invite note, an item with sealed detail, a finished loan, a second steward, and two blobs.
 */
class CircleFixture(val services: Services, val crypto: Crypto) {
    val circle: CircleId = newCircleId(crypto)
    private val circleKey = KeyRing.newCircleKey(crypto)
    val founder = EventAuthor(crypto, DeviceIdentity.generate(crypto, testCipher), testCipher).also { it.keyRing = KeyRing.EMPTY.with(FIRST_EPOCH, circleKey) }
    val bea = EventAuthor(crypto, DeviceIdentity.generate(crypto, testCipher), testCipher).also { it.keyRing = KeyRing.EMPTY.with(FIRST_EPOCH, circleKey) }
    val blobA = ByteArray(4_000) { (it * 7).toByte() }
    val blobB = ByteArray(1_500) { (it * 13 + 1).toByte() }
    val events = mutableListOf<Envelope>()

    private fun push(event: Envelope) {
        val result = services.sync.submit(listOf(event.toDto()), if (events.isEmpty()) Entrance.Create else Entrance.Push(circle)).single()
        assertEquals(PushStatus.ACCEPTED, result.status, "${event.type}: ${result.reason} ${result.detail}")
        events += event
    }

    /** Pushes the whole circle; returns the number of events. */
    fun build(): Int {
        push(founder.circleCreate(circle, TODAY))
        push(founder.keyWrap(circle, founder.identity.registration, FIRST_EPOCH, circleKey, TODAY))
        val invite = InviteKey(crypto.generateSigningKeyPair())
        val inviteEvent = founder.invite(circle, invite, TODAY + 30, 1, TODAY, "welcome to the circle")
        push(inviteEvent)
        val join = bea.join(circle, inviteEvent.eventId(crypto), invite, TODAY)
        assertEquals(PushStatus.ACCEPTED, services.sync.submit(listOf(join.toDto()), Entrance.Join(circle)).single().status)
        events += join
        push(founder.keyWrap(circle, bea.identity.registration, FIRST_EPOCH, circleKey, TODAY))
        val item = newItemId(crypto)
        val keyId = newDetailKeyId(crypto)
        val itemKey = ItemKeys.newKey(crypto)
        push(founder.itemUpsert(circle, TODAY, item, "Extension ladder", ItemClass.ESSENTIAL, keyId, itemKey, listOf(founder.identity.registration)))
        push(founder.itemDetail(circle, TODAY, item, keyId, itemKey, CborMap.of("address" to CborText("12 Example St"))))
        val loan = newLoanId(crypto)
        push(bea.loanRequest(circle, TODAY, loan, item, TODAY + 1, TODAY + 3))
        push(founder.loanApprove(circle, TODAY, loan, item, TODAY + 1, TODAY + 3, itemKey, listOf(bea.identity.registration)))
        push(founder.loanHandover(circle, TODAY, loan))
        push(bea.loanHandover(circle, TODAY, loan))
        push(bea.loanReturn(circle, TODAY, loan, ReturnOutcome.OK))
        push(founder.stewardAdd(circle, bea.identity.member, TODAY))
        listOf(blobA, blobB).forEach { putBlob(it) }
        return events.size
    }

    /** More events on top of what [build] made, to test that a bigger export extends a smaller import. */
    fun addMore(count: Int) {
        repeat(count) { push(founder.invite(circle, InviteKey(crypto.generateSigningKeyPair()), TODAY + 30, 1, TODAY)) }
    }

    fun hash(bytes: ByteArray): String = crypto.hash256(bytes).toHex()

    private fun putBlob(bytes: ByteArray) {
        services.blobs!!.put(circle, hash(bytes), bytes)
    }
}

/**
 * The circle of the spec's handover test 1 (section 8): three stewards and five members, created with a quorum of one
 * so the founder can appoint the other stewards, with the circle key wrapped to every device and one blob.
 */
class HandoverFixture(val services: Services, val crypto: Crypto) {
    val circle: CircleId = newCircleId(crypto)
    private val circleKey = KeyRing.newCircleKey(crypto)
    val members: List<EventAuthor> = List(5) {
        EventAuthor(crypto, DeviceIdentity.generate(crypto, testCipher), testCipher).also { m -> m.keyRing = KeyRing.EMPTY.with(FIRST_EPOCH, circleKey) }
    }
    val founder get() = members.first()
    val events = mutableListOf<Envelope>()
    val blob = ByteArray(2_048) { (it * 5).toByte() }

    private fun push(event: Envelope, entrance: Entrance = Entrance.Push(circle)) {
        val result = services.sync.submit(listOf(event.toDto()), entrance).single()
        assertEquals(PushStatus.ACCEPTED, result.status, "${event.type}: ${result.reason} ${result.detail}")
        events += event
    }

    /** Returns the number of events pushed. */
    fun build(): Int {
        push(founder.circleCreate(circle, TODAY, circles.event.StewardSettings(quorum = 1)), Entrance.Create)
        push(founder.keyWrap(circle, founder.identity.registration, FIRST_EPOCH, circleKey, TODAY))
        members.drop(1).forEach { joiner ->
            val invite = InviteKey(crypto.generateSigningKeyPair())
            val inviteEvent = founder.invite(circle, invite, TODAY + 30, 1, TODAY)
            push(inviteEvent)
            push(joiner.join(circle, inviteEvent.eventId(crypto), invite, TODAY), Entrance.Join(circle))
            push(founder.keyWrap(circle, joiner.identity.registration, FIRST_EPOCH, circleKey, TODAY))
        }
        push(founder.stewardAdd(circle, members[1].identity.member, TODAY))
        push(founder.stewardAdd(circle, members[2].identity.member, TODAY))
        services.blobs!!.put(circle, crypto.hash256(blob).toHex(), blob)
        return events.size
    }
}
