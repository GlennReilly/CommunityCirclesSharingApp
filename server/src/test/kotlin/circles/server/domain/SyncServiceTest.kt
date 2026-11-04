package circles.server.domain

import circles.server.support.testCipher
import circles.api.PushResult
import circles.api.PushStatus
import circles.encoding.ByteString
import circles.encoding.CborMap
import circles.encoding.CborText
import circles.event.DeviceIdentity
import circles.event.Envelope
import circles.event.EventAuthor
import circles.event.EventId
import circles.event.MAX_ENCRYPTED_BYTES
import circles.event.RejectionCode
import circles.event.toDto
import circles.policy.CircleId
import circles.server.support.Person
import circles.server.support.TODAY
import circles.server.support.TestServer
import circles.server.support.newInviteKey
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncServiceTest {
    private val server = TestServer()
    private val crypto = server.crypto
    private val sync = server.services.sync
    private val circle = circles.event.newCircleId(crypto)
    private val founder = Person(crypto)

    @AfterTest
    fun close() = server.close()

    private fun push(vararg events: Envelope, in_: CircleId = circle): List<PushResult> = sync.submit(events.map { it.toDto() }, Entrance.Push(in_))

    private fun accepted(result: PushResult): Long {
        assertEquals(PushStatus.ACCEPTED, result.status, "expected accepted but got ${result.reason}: ${result.detail}")
        return assertNotNull(result.serverSeq)
    }

    private fun assertRejected(code: RejectionCode, result: PushResult) {
        assertEquals(PushStatus.REJECTED, result.status, "expected ${code.wire} but the event was ${result.status}")
        assertEquals(code.wire, result.reason, result.detail)
    }

    private fun createCircle(): Long = accepted(push(founder.author.circleCreate(circle, TODAY)).single())

    private fun invite(by: Person = founder, maxUses: Int = 2, expiresDay: Int = TODAY + 30): Triple<Envelope, circles.event.InviteKey, EventId> {
        val key = newInviteKey(crypto)
        val event = by.author.invite(circle, key, expiresDay, maxUses, TODAY)
        accepted(push(event).single())
        return Triple(event, key, event.eventId(crypto))
    }

    // ---- circle.create ----

    @Test
    fun createdCircleRegistersTheFounderDeviceAndStewardAtSeqOne() {
        assertEquals(1L, createCircle())
        assertEquals(listOf(listOf(circle.v, TODAY)), server.rows("SELECT id, created_day FROM circles").map { listOf(it[0], (it[1] as Number).toInt()) })
        assertEquals(1, server.rows("SELECT 1 FROM devices WHERE device_id = '${founder.identity.device.v}' AND added_seq = 1").size)
        assertEquals(1, server.rows("SELECT 1 FROM stewards WHERE member_id = '${founder.identity.member.v}'").size)
    }

    @Test
    fun pushingTheSameEventAgainIsADuplicateWithTheSameSeq() {
        val create = founder.author.circleCreate(circle, TODAY)
        val first = push(create).single()
        val again = push(create).single()
        assertEquals(PushStatus.DUPLICATE, again.status)
        assertEquals(first.serverSeq, again.serverSeq)
        assertEquals(first.id, again.id)
        assertEquals(1, server.rows("SELECT 1 FROM events").size)
    }

    @Test
    fun aSecondCreateForTheSameCircleIsRefused() {
        createCircle()
        assertRejected(RejectionCode.CIRCLE_EXISTS, push(Person(crypto).author.circleCreate(circle, TODAY)).single())
    }

    @Test
    fun createIsRefusedWhenTheServerDoesNotAllowIt() {
        TestServer(allowCreate = false).use { closed ->
            val result = closed.services.sync.submit(listOf(founder.author.circleCreate(circle, TODAY).toDto()), Entrance.Create).single()
            assertRejected(RejectionCode.CREATION_DISABLED, result)
        }
    }

    @Test
    fun createMustRegisterItsOwnAuthorAndDevice() {
        val other = DeviceIdentity.generate(crypto, testCipher)
        val forged = founder.author.circleCreate(circle, TODAY).copy(public = other.registration.toPublic())
        assertRejected(RejectionCode.AUTHOR_MISMATCH, push(forged).single())
    }

    @Test
    fun createSignedByAnotherKeyIsRefused() {
        val real = founder.author.circleCreate(circle, TODAY)
        val forged = real.signedBy(crypto, crypto.generateSigningKeyPair().secretSeed)
        assertRejected(RejectionCode.BAD_SIGNATURE, push(forged).single())
    }

    @Test
    fun createWithASecretPartIsRefused() {
        // circle.create has no secret part, and no circle key exists yet for a sealed one to use.
        val sealed = founder.author.circleCreate(circle, TODAY).copy(epoch = 1, nonce = ByteString.of(ByteArray(24)), encrypted = ByteString.of(ByteArray(32)))
            .signedBy(crypto, founder.identity.signing.secretSeed)
        assertRejected(RejectionCode.MALFORMED, push(sealed).single())
    }

    // ---- stateless checks and the day rule ----

    @Test
    fun todayPlusOneIsAcceptedAndLaterIsNot() {
        assertEquals(PushStatus.ACCEPTED, push(founder.author.circleCreate(circle, TODAY + 1)).single().status)
        val other = Person(crypto)
        val otherCircle = circles.event.newCircleId(crypto)
        assertRejected(RejectionCode.FUTURE_DAY, push(other.author.circleCreate(otherCircle, TODAY + 2), in_ = otherCircle).single())
    }

    @Test
    fun oldDaysAreFine() {
        assertEquals(PushStatus.ACCEPTED, push(founder.author.circleCreate(circle, 0)).single().status)
    }

    @Test
    fun otherVersionsTypesAndEpochsAreRefused() {
        val create = founder.author.circleCreate(circle, TODAY)
        assertRejected(RejectionCode.UNSUPPORTED_VERSION, push(create.copy(v = 2).signedBy(crypto, founder.identity.signing.secretSeed)).single())
        assertRejected(RejectionCode.UNSUPPORTED_TYPE, push(create.copy(type = "member.profile").signedBy(crypto, founder.identity.signing.secretSeed)).single())
        assertRejected(RejectionCode.UNSUPPORTED_TYPE, push(create.copy(type = "made.up").signedBy(crypto, founder.identity.signing.secretSeed)).single())
        assertRejected(RejectionCode.MALFORMED, push(create.copy(epoch = 1).signedBy(crypto, founder.identity.signing.secretSeed)).single())
    }

    @Test
    fun oversizedPayloadIsRefused() {
        val event = founder.author.circleCreate(circle, TODAY)
            .copy(epoch = 1, nonce = ByteString.of(ByteArray(24)), encrypted = ByteString.of(ByteArray(MAX_ENCRYPTED_BYTES + 1)))
            .signedBy(crypto, founder.identity.signing.secretSeed)
        assertRejected(RejectionCode.TOO_LARGE, push(event).single())
    }

    @Test
    fun aMalformedEnvelopeIsRefusedWithoutAnId() {
        val result = sync.submit(listOf(founder.author.circleCreate(circle, TODAY).toDto().copy(sig = "!!")), Entrance.Push(circle)).single()
        assertRejected(RejectionCode.MALFORMED, result)
        assertNull(result.id)
    }

    @Test
    fun anEventForAnotherCircleThanTheEntranceIsRefused() {
        val result = sync.submit(listOf(founder.author.circleCreate(circle, TODAY).toDto()), Entrance.Push(circles.event.newCircleId(crypto))).single()
        assertRejected(RejectionCode.WRONG_CIRCLE, result)
    }

    @Test
    fun publicEntrancesTakeOnlyTheirOwnType() {
        createCircle()
        val (event, _, _) = Triple(founder.author.invite(circle, newInviteKey(crypto), TODAY + 5, 1, TODAY), 0, 0)
        assertRejected(RejectionCode.UNSUPPORTED_TYPE, sync.submit(listOf(event.toDto()), Entrance.Create).single())
        assertRejected(RejectionCode.UNSUPPORTED_TYPE, sync.submit(listOf(event.toDto()), Entrance.Join(circle)).single())
        assertRejected(RejectionCode.UNSUPPORTED_TYPE, sync.submit(listOf(Person(crypto).author.circleCreate(circle, TODAY).toDto()), Entrance.Join(circle)).single())
    }

    // ---- member.invite ----

    @Test
    fun aMemberCanInviteAndTheInviteIsRecorded() {
        createCircle()
        val (_, key, id) = invite(maxUses = 3, expiresDay = TODAY + 10)
        val rows = server.rows("SELECT event_id, author, max_uses, used, expires_day FROM invites")
        assertEquals(listOf(id.v, founder.identity.member.v), rows.single().take(2))
        assertEquals(listOf(3, 0, TODAY + 10), rows.single().drop(2).map { (it as Number).toInt() })
        assertNotNull(key)
    }

    @Test
    fun anInviteFromAnUnknownDeviceIsRefused() {
        createCircle()
        val stranger = Person(crypto)
        assertRejected(RejectionCode.UNKNOWN_DEVICE, push(stranger.author.invite(circle, newInviteKey(crypto), TODAY + 5, 1, TODAY)).single())
    }

    @Test
    fun anInviteNeedsAValidSignatureFromTheRegisteredKey() {
        createCircle()
        val real = founder.author.invite(circle, newInviteKey(crypto), TODAY + 5, 1, TODAY)
        val forged = real.signedBy(crypto, crypto.generateSigningKeyPair().secretSeed)
        assertRejected(RejectionCode.BAD_SIGNATURE, push(forged).single())
        val tampered = real.copy(day = TODAY - 1)
        assertRejected(RejectionCode.BAD_SIGNATURE, push(tampered).single())
    }

    @Test
    fun aDeviceCannotSpeakForAnotherAuthor() {
        createCircle()
        val real = founder.author.invite(circle, newInviteKey(crypto), TODAY + 5, 1, TODAY)
        val wrongAuthor = real.copy(author = circles.event.newMemberId(crypto)).signedBy(crypto, founder.identity.signing.secretSeed)
        assertRejected(RejectionCode.AUTHOR_MISMATCH, push(wrongAuthor).single())
    }

    @Test
    fun aDeviceOfAnotherCircleCannotInvite() {
        createCircle()
        val otherCircle = circles.event.newCircleId(crypto)
        val other = Person(crypto)
        accepted(push(other.author.circleCreate(otherCircle, TODAY), in_ = otherCircle).single())
        val event = other.author.invite(circle, newInviteKey(crypto), TODAY + 5, 1, TODAY)
        assertRejected(RejectionCode.WRONG_CIRCLE, push(event).single())
    }

    @Test
    fun aRevokedDeviceCannotInvite() {
        createCircle()
        server.exec("UPDATE devices SET revoked_seq = 2 WHERE device_id = '${founder.identity.device.v}'")
        assertRejected(RejectionCode.DEVICE_REVOKED, push(founder.author.invite(circle, newInviteKey(crypto), TODAY + 5, 1, TODAY)).single())
    }

    @Test
    fun inviteTermsAreChecked() {
        createCircle()
        assertRejected(RejectionCode.INVALID_PUBLIC, push(founder.author.invite(circle, newInviteKey(crypto), TODAY - 1, 1, TODAY)).single())
        assertRejected(RejectionCode.INVALID_PUBLIC, push(founder.author.invite(circle, newInviteKey(crypto), TODAY + 1, 0, TODAY)).single())
    }

    // ---- member.join ----

    private fun joiner(): Person = Person(crypto)

    @Test
    fun anInviteeCanJoinAndTheirDeviceIsRegistered() {
        createCircle()
        val (_, key, id) = invite()
        val bea = joiner()
        val seq = accepted(sync.submit(listOf(bea.author.join(circle, id, key, TODAY).toDto()), Entrance.Join(circle)).single())
        assertTrue(seq > 1)
        assertEquals(1, server.rows("SELECT 1 FROM devices WHERE device_id = '${bea.identity.device.v}' AND circle_id = '${circle.v}'").size)
        assertEquals(1, (server.rows("SELECT used FROM invites").single().single() as Number).toInt())
        assertEquals(0, server.rows("SELECT 1 FROM stewards WHERE member_id = '${bea.identity.member.v}'").size)
    }

    private fun join(person: Person, id: EventId, key: circles.event.InviteKey, day: Int = TODAY): PushResult =
        sync.submit(listOf(person.author.join(circle, id, key, day).toDto()), Entrance.Join(circle)).single()

    @Test
    fun joinToAnUnknownCircleOrInviteIsRefused() {
        createCircle()
        val (_, key, _) = invite()
        assertRejected(RejectionCode.UNKNOWN_INVITE, join(joiner(), EventId("ab".repeat(32)), key))
        val elsewhere = circles.event.newCircleId(crypto)
        val result = sync.submit(listOf(joiner().author.join(elsewhere, EventId("ab".repeat(32)), key, TODAY).toDto()), Entrance.Join(elsewhere)).single()
        assertRejected(RejectionCode.UNKNOWN_CIRCLE, result)
    }

    @Test
    fun anInviteFromAnotherCircleCannotBeRedeemedHere() {
        createCircle()
        val otherCircle = circles.event.newCircleId(crypto)
        val otherFounder = Person(crypto)
        accepted(push(otherFounder.author.circleCreate(otherCircle, TODAY), in_ = otherCircle).single())
        val key = newInviteKey(crypto)
        val foreign = otherFounder.author.invite(otherCircle, key, TODAY + 5, 1, TODAY)
        accepted(push(foreign, in_ = otherCircle).single())
        assertRejected(RejectionCode.UNKNOWN_INVITE, join(joiner(), foreign.eventId(crypto), key))
    }

    @Test
    fun joinNeedsTheInviteKeysProof() {
        createCircle()
        val (_, _, id) = invite()
        assertRejected(RejectionCode.BAD_INVITE_PROOF, join(joiner(), id, newInviteKey(crypto)))
    }

    @Test
    fun aRefusedJoinDoesNotUseUpTheInvite() {
        createCircle()
        val (_, key, id) = invite(maxUses = 1)
        assertRejected(RejectionCode.BAD_INVITE_PROOF, join(joiner(), id, newInviteKey(crypto)))
        assertEquals(PushStatus.ACCEPTED, join(joiner(), id, key).status)
    }

    @Test
    fun inviteUsesAreLimited() {
        createCircle()
        val (_, key, id) = invite(maxUses = 2)
        assertEquals(PushStatus.ACCEPTED, join(joiner(), id, key).status)
        assertEquals(PushStatus.ACCEPTED, join(joiner(), id, key).status)
        assertRejected(RejectionCode.INVITE_EXHAUSTED, join(joiner(), id, key))
    }

    @Test
    fun invitesExpire() {
        createCircle()
        val (_, key, id) = invite(expiresDay = TODAY)
        assertRejected(RejectionCode.INVITE_EXPIRED, join(joiner(), id, key, day = TODAY + 1))
        assertEquals(PushStatus.ACCEPTED, join(joiner(), id, key, day = TODAY).status)
    }

    @Test
    fun aJoinerCannotReuseAMemberOrDeviceId() {
        createCircle()
        val (_, key, id) = invite(maxUses = 5)
        val bea = joiner()
        assertEquals(PushStatus.ACCEPTED, join(bea, id, key).status)
        val sameMember = Person(crypto, EventAuthor(crypto, DeviceIdentity.generate(crypto, testCipher, bea.identity.member), testCipher))
        assertRejected(RejectionCode.MEMBER_EXISTS, join(sameMember, id, key))
        val sameDevice = Person(crypto, EventAuthor(crypto, DeviceIdentity(circles.event.newMemberId(crypto), bea.identity.device, crypto.generateSigningKeyPair(), bea.identity.boxPub, bea.identity.boxSecret), testCipher))
        assertRejected(RejectionCode.DEVICE_EXISTS, join(sameDevice, id, key))
    }

    @Test
    fun aJoinMustBeSignedByTheKeyItRegisters() {
        createCircle()
        val (_, key, id) = invite()
        val bea = joiner()
        val forged = bea.author.join(circle, id, key, TODAY).signedBy(crypto, crypto.generateSigningKeyPair().secretSeed)
        assertRejected(RejectionCode.BAD_SIGNATURE, sync.submit(listOf(forged.toDto()), Entrance.Join(circle)).single())
    }

    @Test
    fun aJoinMustRegisterItsOwnAuthorAndDevice() {
        createCircle()
        val (_, key, id) = invite()
        val bea = joiner()
        val forged = bea.author.join(circle, id, key, TODAY).copy(author = circles.event.newMemberId(crypto)).signedBy(crypto, bea.identity.signing.secretSeed)
        assertRejected(RejectionCode.AUTHOR_MISMATCH, sync.submit(listOf(forged.toDto()), Entrance.Join(circle)).single())
    }

    // ---- device.add ----

    private fun joined(): Person {
        val (_, key, id) = invite(maxUses = 5)
        return joiner().also { assertEquals(PushStatus.ACCEPTED, join(it, id, key).status) }
    }

    @Test
    fun aMemberCanAddTheirOwnDevice() {
        createCircle()
        val phone = DeviceIdentity.generate(crypto, testCipher, founder.identity.member)
        accepted(push(founder.author.deviceAdd(circle, phone, TODAY)).single())
        assertEquals(1, server.rows("SELECT 1 FROM devices WHERE device_id = '${phone.device.v}' AND member_id = '${founder.identity.member.v}'").size)
    }

    @Test
    fun aStewardCanAddADeviceForAnotherMember() {
        createCircle()
        val bea = joined()
        val beaPhone = DeviceIdentity.generate(crypto, testCipher, bea.identity.member)
        accepted(push(founder.author.deviceAdd(circle, beaPhone, TODAY)).single())
    }

    @Test
    fun aNonStewardCannotAddADeviceForAnotherMember() {
        createCircle()
        val bea = joined()
        val foundersPhone = DeviceIdentity.generate(crypto, testCipher, founder.identity.member)
        assertRejected(RejectionCode.NOT_STEWARD, push(bea.author.deviceAdd(circle, foundersPhone, TODAY)).single())
    }

    @Test
    fun aStewardCannotAddADeviceForAMemberWhoIsNotInTheCircle() {
        createCircle()
        val nobody = DeviceIdentity.generate(crypto, testCipher)
        assertRejected(RejectionCode.INVALID_PUBLIC, push(founder.author.deviceAdd(circle, nobody, TODAY)).single())
    }

    @Test
    fun aDeviceIdCannotBeRegisteredTwice() {
        createCircle()
        assertRejected(RejectionCode.DEVICE_EXISTS, push(founder.author.deviceAdd(circle, founder.identity, TODAY)).single())
    }

    @Test
    fun deviceAddNeedsAnExistingSigner() {
        createCircle()
        val stranger = Person(crypto)
        assertRejected(RejectionCode.UNKNOWN_DEVICE, push(stranger.author.deviceAdd(circle, DeviceIdentity.generate(crypto, testCipher, stranger.identity.member), TODAY)).single())
    }

    // ---- ordering, batches, ADR 0011 ----

    @Test
    fun eventsInOneBatchCanDependOnEarlierOnes() {
        val key = newInviteKey(crypto)
        val create = founder.author.circleCreate(circle, TODAY)
        val invite = founder.author.invite(circle, key, TODAY + 5, 1, TODAY)
        val results = push(create, invite)
        assertEquals(listOf(1L, 2L), results.map { accepted(it) })
    }

    @Test
    fun aRefusedEventInABatchDoesNotStopTheOthersOrWriteAnything() {
        createCircle()
        val good = founder.author.invite(circle, newInviteKey(crypto), TODAY + 5, 1, TODAY)
        val bad = Person(crypto).author.invite(circle, newInviteKey(crypto), TODAY + 5, 1, TODAY)
        val results = push(bad, good)
        assertEquals(listOf(PushStatus.REJECTED, PushStatus.ACCEPTED), results.map { it.status })
        assertEquals(listOf(1L, 2L), server.rows("SELECT server_seq FROM events ORDER BY server_seq").map { (it.single() as Number).toLong() })
    }

    @Test
    fun counterReuseIsAcceptedBecauseTheIdAlreadyMakesPushesIdempotent() {
        createCircle()
        val first = EventAuthor(crypto, founder.identity, testCipher, firstCounter = 5).invite(circle, newInviteKey(crypto), TODAY + 5, 1, TODAY)
        val second = EventAuthor(crypto, founder.identity, testCipher, firstCounter = 5).invite(circle, newInviteKey(crypto), TODAY + 5, 1, TODAY)
        assertEquals(first.ctr, second.ctr)
        assertEquals(listOf(PushStatus.ACCEPTED, PushStatus.ACCEPTED), push(first, second).map { it.status })
    }

    @Test
    fun serverSeqIsPerCircle() {
        createCircle()
        val other = Person(crypto)
        val otherCircle = circles.event.newCircleId(crypto)
        assertEquals(1L, accepted(push(other.author.circleCreate(otherCircle, TODAY), in_ = otherCircle).single()))
        assertEquals(2L, accepted(push(founder.author.invite(circle, newInviteKey(crypto), TODAY + 5, 1, TODAY)).single()))
    }

    @Test
    fun concurrentPushesGetDistinctContiguousSeqs() {
        createCircle()
        val threads = 4
        val perThread = 10
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        try {
            val futures = List(threads) {
                pool.submit<List<Long>> {
                    val author = EventAuthor(crypto, founder.identity, testCipher, firstCounter = 100L * it)
                    List(perThread) { accepted(push(author.invite(circle, newInviteKey(crypto), TODAY + 5, 1, TODAY)).single()) }
                }
            }
            val seqs = futures.flatMap { it.get() }
            assertEquals((2L..(1L + threads * perThread)).toList(), seqs.sorted())
        } finally {
            pool.shutdown()
        }
    }

    // ---- pull ----

    @Test
    fun pullReturnsEventsInOrderWithPaging() {
        createCircle()
        repeat(4) { accepted(push(founder.author.invite(circle, newInviteKey(crypto), TODAY + 5, 1, TODAY)).single()) }
        val first = sync.pull(circle, after = 0, limit = 2)
        assertEquals(listOf(1L, 2L), first.events.map { it.serverSeq })
        assertTrue(first.hasMore)
        assertEquals(2L, first.next)
        val second = sync.pull(circle, after = first.next, limit = 2)
        assertEquals(listOf(3L, 4L), second.events.map { it.serverSeq })
        assertTrue(second.hasMore)
        val last = sync.pull(circle, after = second.next, limit = 2)
        assertEquals(listOf(5L), last.events.map { it.serverSeq })
        assertEquals(false, last.hasMore)
        val empty = sync.pull(circle, after = 5, limit = 2)
        assertEquals(emptyList(), empty.events)
        assertEquals(5L, empty.next)
    }

    @Test
    fun pullOnlyReturnsTheRequestedCirclesEvents() {
        createCircle()
        val other = Person(crypto)
        val otherCircle = circles.event.newCircleId(crypto)
        accepted(push(other.author.circleCreate(otherCircle, TODAY), in_ = otherCircle).single())
        assertEquals(1, sync.pull(circle, 0, 10).events.size)
        assertEquals(otherCircle.v, sync.pull(otherCircle, 0, 10).events.single().envelope.circle)
    }

    @Test
    fun storedEventsComeBackByteForByteAndWithTheSameId() {
        val create = founder.author.circleCreate(circle, TODAY)
        push(create)
        val stored = sync.pull(circle, 0, 10).events.single().envelope
        assertEquals(create.toDto(), stored)
        val back = (stored.toEnvelope() as circles.encoding.Parsed.Ok).value
        assertEquals(create.eventId(crypto), back.eventId(crypto))
        assertTrue(back.hasValidSignature(crypto, founder.identity.signing.publicKey))
    }
}
