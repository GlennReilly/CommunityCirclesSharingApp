package circles.event

import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.encoding.CborMap
import circles.encoding.CborText
import circles.encoding.Parsed
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val day = 20_000

/** The client fold re-validates a log and opens what it can (spec 3.1, 3.4, ADR 0021), on every Crypto/Cipher pair. */
abstract class CircleFoldContract {
    protected abstract suspend fun newCrypto(): Crypto
    protected abstract suspend fun newCipher(): Cipher

    private class World(val crypto: Crypto, val cipher: Cipher) {
        val circle = newCircleId(crypto)
        val key1 = KeyRing.newCircleKey(crypto)
        val founder = person().also { it.keyRing = KeyRing.EMPTY.with(FIRST_EPOCH, key1) }
        val bea = person()
        val carol = person()
        val log = mutableListOf<Envelope>()
        lateinit var beaInvite: Envelope

        fun person() = EventAuthor(crypto, DeviceIdentity.generate(crypto, cipher = cipher), cipher = cipher)

        fun fold(who: EventAuthor?, events: List<Envelope> = log): CircleView =
            CircleFold(crypto, cipher, who?.identity).fold(circle, events)

        fun note(by: EventAuthor, text: String): Envelope =
            by.invite(circle, InviteKey(crypto.generateSigningKeyPair()), day + 30, 1, day, text)

        fun noteOf(view: CircleView, env: Envelope): String? {
            val opened = view.events.single { it.id == env.eventId(crypto) }.payload
            return ((opened as? Parsed.Ok<CborMap>)?.value?.get("note") as? CborText)?.value
        }

        /** Carol joins through a fresh invite, so she can be the target of steward changes. */
        fun joinCarol() {
            val key = InviteKey(crypto.generateSigningKeyPair())
            val invite = founder.invite(circle, key, day + 30, 1, day)
            log += invite
            log += carol.join(circle, invite.eventId(crypto), key, day)
        }

        /** Founder creates the circle and the first key, invites bea, bea joins, key wrapped to bea. */
        fun start() {
            log += founder.circleCreate(circle, day)
            log += founder.keyWrap(circle, founder.identity.registration, FIRST_EPOCH, key1, day)
            val inviteKey = InviteKey(crypto.generateSigningKeyPair())
            beaInvite = founder.invite(circle, inviteKey, day + 30, 1, day)
            log += beaInvite
            log += bea.join(circle, beaInvite.eventId(crypto), inviteKey, day)
            log += founder.keyWrap(circle, bea.identity.registration, FIRST_EPOCH, key1, day)
        }
    }

    private suspend fun world() = World(newCrypto(), newCipher())

    @Test
    fun aMemberOpensEarlierNotesOnceTheKeyArrives() = runTest {
        val w = world()
        w.log += w.founder.circleCreate(w.circle, day)
        w.log += w.founder.keyWrap(w.circle, w.founder.identity.registration, FIRST_EPOCH, w.key1, day)
        val note = w.note(w.founder, "bring the ladder")
        w.log += note
        val inviteKey = InviteKey(w.crypto.generateSigningKeyPair())
        val invite = w.founder.invite(w.circle, inviteKey, day + 30, 1, day)
        w.log += invite
        w.log += w.bea.join(w.circle, invite.eventId(w.crypto), inviteKey, day)
        // Before the wrap, bea sees the note event but cannot open it.
        assertIs<Parsed.Invalid>(w.fold(w.bea).events.single { it.id == note.eventId(w.crypto) }.payload)
        w.log += w.founder.keyWrap(w.circle, w.bea.identity.registration, FIRST_EPOCH, w.key1, day)
        val view = w.fold(w.bea)
        assertEquals("bring the ladder", w.noteOf(view, note))
        assertEquals(FIRST_EPOCH, view.currentEpoch)
        assertEquals(emptyList(), view.rejected)
        assertEquals(setOf(w.founder.identity.member), view.stewards)
        assertEquals(2, view.devices.size)
    }

    @Test
    fun aDeviceWithoutAWrapSeesTheCircleButCannotReadSecrets() = runTest {
        val w = world()
        w.start()
        val note = w.note(w.founder, "private")
        w.log += note
        val view = w.fold(w.carol)
        assertIs<Parsed.Invalid>(view.events.single { it.id == note.eventId(w.crypto) }.payload)
        assertEquals(2, view.devices.size)
        assertEquals("private", w.noteOf(w.fold(w.founder), note))
    }

    @Test
    fun forgedAndMisplacedEventsAreRejectedAndListed() = runTest {
        val w = world()
        w.start()
        val good = w.note(w.founder, "ok")
        val forged = good.copy(day = good.day + 1) // signature no longer matches
        val stranger = w.person()
        val fromStranger = stranger.invite(w.circle, InviteKey(w.crypto.generateSigningKeyPair()), day + 30, 1, day)
        val view = w.fold(w.bea, w.log + listOf(forged, fromStranger, good))
        val reasons = view.rejected.associate { it.id to it.reason }
        assertEquals("bad signature", reasons[forged.eventId(w.crypto)])
        assertEquals("sender device is unknown or revoked", reasons[fromStranger.eventId(w.crypto)])
        assertTrue(view.events.any { it.id == good.eventId(w.crypto) })
        assertEquals(2, view.rejected.size)
    }

    @Test
    fun aRevokedDeviceCannotSpeakAfterTheRevokeButCouldBefore() = runTest {
        val w = world()
        w.start()
        val before = w.bea.invite(w.circle, InviteKey(w.crypto.generateSigningKeyPair()), day + 30, 1, day)
        w.log += before
        w.log += w.founder.deviceRevoke(w.circle, w.bea.identity.device, day)
        val after = w.bea.invite(w.circle, InviteKey(w.crypto.generateSigningKeyPair()), day + 30, 1, day)
        w.log += after
        val view = w.fold(w.founder)
        assertTrue(view.events.any { it.id == before.eventId(w.crypto) })
        assertEquals(listOf(after.eventId(w.crypto)), view.rejected.map { it.id })
        assertTrue(view.devices.getValue(w.bea.identity.device).revoked)
    }

    @Test
    fun rotationLocksOutARevokedDeviceFromNewSecretsOnly() = runTest {
        val w = world()
        w.start()
        val old = w.note(w.founder, "old secret")
        w.log += old
        w.log += w.founder.deviceRevoke(w.circle, w.bea.identity.device, day)
        w.log += w.founder.rotateKeys(w.circle, 2, listOf(w.founder.identity.registration), day)
        val fresh = w.note(w.founder, "new secret")
        w.log += fresh
        assertEquals(2, fresh.epoch)
        val founderView = w.fold(w.founder)
        assertEquals("old secret", w.noteOf(founderView, old))
        assertEquals("new secret", w.noteOf(founderView, fresh))
        val beaView = w.fold(w.bea)
        assertEquals("old secret", w.noteOf(beaView, old))
        assertIs<Parsed.Invalid>(beaView.events.single { it.id == fresh.eventId(w.crypto) }.payload)
        assertEquals(2, beaView.currentEpoch)
    }

    @Test
    fun roleAndInviteRulesAreEnforcedByTheClientToo() = runTest {
        val w = world()
        w.start()
        w.bea.keyRing = w.bea.keyRing.with(FIRST_EPOCH, w.key1)
        val notSteward = w.bea.keyRotate(w.circle, 2, day)
        val replayedJoin = w.bea.join(w.circle, w.beaInvite.eventId(w.crypto), InviteKey(w.crypto.generateSigningKeyPair()), day)
        val exhaustedJoin = w.carol.join(w.circle, w.beaInvite.eventId(w.crypto), InviteKey(w.crypto.generateSigningKeyPair()), day)
        val freshInvite = w.founder.invite(w.circle, InviteKey(w.crypto.generateSigningKeyPair()), day + 30, 1, day)
        val dave = w.person()
        val wrongKeyJoin = dave.join(w.circle, freshInvite.eventId(w.crypto), InviteKey(w.crypto.generateSigningKeyPair()), day)
        val view = w.fold(w.founder, w.log + listOf(notSteward, replayedJoin, exhaustedJoin, freshInvite, wrongKeyJoin))
        val reasons = view.rejected.map { it.reason }
        assertTrue("only a steward may rotate the circle key" in reasons, reasons.toString())
        assertTrue("device already registered" in reasons, reasons.toString())
        assertTrue("invite exhausted" in reasons, reasons.toString())
        assertTrue("bad invite proof" in reasons, reasons.toString())
        assertEquals(1, view.currentEpoch)
    }

    @Test
    fun stewardChangesNeedAQuorumOfDistinctStewardsAndTheLastStewardStays() = runTest {
        val w = world()
        w.start()
        w.joinCarol()
        val unknown = w.founder.stewardAdd(w.circle, w.person().identity.member, day)
        val removeLast = w.founder.stewardRemove(w.circle, w.founder.identity.member, day)
        val notYet = w.bea.stewardAdd(w.circle, w.carol.identity.member, day) // bea is not a steward
        val bad = w.fold(w.founder, w.log + listOf(unknown, removeLast, notYet))
        assertEquals(setOf("member is not in the circle", "a circle keeps at least one steward", "only a steward may change stewards"), bad.rejected.map { it.reason }.toSet())

        // A lone steward adds bea alone; the next addition needs both, and bea waits out the cool-off.
        val addBea = w.founder.stewardAdd(w.circle, w.bea.identity.member, day)
        val pending = w.founder.stewardAdd(w.circle, w.carol.identity.member, day)
        val tooSoon = w.bea.stewardAdd(w.circle, w.carol.identity.member, day + 6)
        val view = w.fold(w.founder, w.log + listOf(addBea, pending, tooSoon))
        assertEquals(setOf(w.founder.identity.member, w.bea.identity.member), view.stewards)
        assertEquals(listOf("a new steward cannot vote until day ${day + 7}"), view.rejected.map { it.reason })

        val approve = w.bea.stewardAdd(w.circle, w.carol.identity.member, day + 7)
        val done = w.fold(w.founder, w.log + listOf(addBea, pending, approve))
        assertEquals(setOf(w.founder.identity.member, w.bea.identity.member, w.carol.identity.member), done.stewards)
        assertEquals(emptyList(), done.rejected)
    }

    @Test
    fun theSameStewardsSecondDeviceDoesNotMakeAQuorum() = runTest {
        val w = world()
        w.start()
        w.joinCarol()
        val addBea = w.founder.stewardAdd(w.circle, w.bea.identity.member, day)
        val phone = EventAuthor(w.crypto, DeviceIdentity.generate(w.crypto, w.founder.identity.member, w.cipher), cipher = w.cipher)
        val addPhone = w.founder.deviceAdd(w.circle, phone.identity, day)
        val first = w.founder.stewardAdd(w.circle, w.carol.identity.member, day + 7)
        val second = phone.stewardAdd(w.circle, w.carol.identity.member, day + 7)
        val view = w.fold(w.founder, w.log + listOf(addBea, addPhone, first, second))
        assertEquals(setOf(w.founder.identity.member, w.bea.identity.member), view.stewards)
        assertEquals(emptyList(), view.rejected)
    }

    @Test
    fun theStandingGateIsAClientOnlyRuleForNewStewards() = runTest {
        val w = world()
        w.start()
        val addBea = w.founder.stewardAdd(w.circle, w.bea.identity.member, day)
        val gated = CircleFold(w.crypto, w.cipher, w.founder.identity, mayBecomeSteward = { false }).fold(w.circle, w.log + addBea)
        assertEquals(setOf(w.founder.identity.member), gated.stewards)
        assertEquals(listOf("member has not reached the steward standing tier"), gated.rejected.map { it.reason })
    }

    @Test
    fun aLogThatDoesNotStartWithCircleCreateIsRejected() = runTest {
        val w = world()
        w.start()
        val view = w.fold(w.founder, w.log.drop(1))
        assertTrue(view.events.isEmpty())
        assertEquals(w.log.size - 1, view.rejected.size)
        assertEquals("the log must start with circle.create", view.rejected.first().reason)
    }

    @Test
    fun aWrapWithTheWrongContentIsReportedNotUsed() = runTest {
        val w = world()
        w.log += w.founder.circleCreate(w.circle, day)
        val wrongKeyFor = w.founder.keyWrap(newCircleId(w.crypto), w.founder.identity.registration, FIRST_EPOCH, w.key1, day)
            .let { forged ->
                // wrap sealed for another circle but posted to this one
                val terms = (CircleKeys.keyWrap(forged.public) as Parsed.Ok).value
                forged.copy(circle = w.circle, public = KeyWrapTerms(terms.device, terms.epoch, terms.wrapped).toPublic())
                    .signedBy(w.crypto, w.founder.identity.signing.secretSeed)
            }
        w.log += wrongKeyFor
        val view = w.fold(w.founder)
        assertEquals(0, view.keys.latestEpoch)
        assertTrue(view.rejected.single().reason.startsWith("the key wrapped to this device is not usable"))
    }
}
