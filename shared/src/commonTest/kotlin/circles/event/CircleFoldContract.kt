package circles.event

import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.encoding.ByteString
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
        fun start(settings: StewardSettings? = null) {
            log += founder.circleCreate(circle, day, settings)
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
        assertEquals(setOf("member is not in the circle", "A circle must keep at least one steward.", "Only a steward can change who the stewards are."), bad.rejected.map { it.reason }.toSet())

        // A lone steward adds bea alone; the next addition needs both, and bea waits out the cool-off.
        val addBea = w.founder.stewardAdd(w.circle, w.bea.identity.member, day)
        val pending = w.founder.stewardAdd(w.circle, w.carol.identity.member, day)
        val tooSoon = w.bea.stewardAdd(w.circle, w.carol.identity.member, day + 6)
        val view = w.fold(w.founder, w.log + listOf(addBea, pending, tooSoon))
        assertEquals(setOf(w.founder.identity.member, w.bea.identity.member), view.stewards)
        assertEquals(listOf("A new steward cannot vote until ${circles.app.ReasonDates.token(day + 7)}."), view.rejected.map { it.reason })

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
        val phone = EventAuthor(w.crypto, DeviceIdentity.generate(w.crypto, w.cipher, w.founder.identity.member), cipher = w.cipher)
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
    fun aCircleCreatedWithSettingsKeepsThemAndAQuorumOfOneActsAlone() = runTest {
        val w = world()
        w.start(StewardSettings(quorum = 1, inactiveDays = 45, fallbackDelayDays = 10))
        w.joinCarol()
        val addBea = w.founder.stewardAdd(w.circle, w.bea.identity.member, day)
        val beaAddsCarol = w.bea.stewardAdd(w.circle, w.carol.identity.member, day) // new steward, acts today
        val carolRemovesBea = w.carol.stewardRemove(w.circle, w.bea.identity.member, day)
        val view = w.fold(w.founder, w.log + listOf(addBea, beaAddsCarol, carolRemovesBea))
        assertEquals(setOf(w.founder.identity.member, w.carol.identity.member), view.stewards)
        assertEquals(StewardSettings(1, 45, 10), view.stewardSettings)
        assertEquals(emptyList(), view.rejected)
    }

    @Test
    fun aCircleCreatedWithBadSettingsIsRejectedAsAWhole() = runTest {
        val w = world()
        val bad = w.founder.circleCreate(w.circle, day, StewardSettings(inactiveDays = 5))
        val view = w.fold(w.founder, listOf(bad))
        assertEquals(1, view.rejected.size)
        assertTrue(view.rejected.single().reason.startsWith("bad circle.create"), view.rejected.single().reason)
        assertTrue(view.devices.isEmpty())
    }

    @Test
    fun theStewardsChangeTheSettingsTogetherAndOutOfLimitValuesAreRejected() = runTest {
        val w = world()
        w.start()
        val addBea = w.founder.stewardAdd(w.circle, w.bea.identity.member, day)
        val relaxed = StewardSettings(quorum = 1, inactiveDays = 45, fallbackDelayDays = 10)
        val first = w.founder.stewardPolicy(w.circle, relaxed, day + 7)
        val pending = w.fold(w.founder, w.log + listOf(addBea, first))
        assertEquals(StewardSettings(), pending.stewardSettings, "one vote of two")
        val second = w.bea.stewardPolicy(w.circle, relaxed, day + 7)
        val done = w.fold(w.founder, w.log + listOf(addBea, first, second))
        assertEquals(relaxed, done.stewardSettings)
        assertEquals(emptyList(), done.rejected)
        val tooShort = w.founder.stewardPolicy(w.circle, StewardSettings(inactiveDays = 1), day + 7)
        assertTrue(w.fold(w.founder, w.log + listOf(addBea, tooShort)).rejected.single().reason.startsWith("bad steward.policy"))
    }

    @Test
    fun theInactivityFallbackLetsAnActiveStewardActAfterTheQuietPeriodAndTheDelay() = runTest {
        val w = world()
        w.start()
        w.joinCarol()
        val addBea = w.founder.stewardAdd(w.circle, w.bea.identity.member, day)
        val quiet = day + 60
        val proposal = w.founder.stewardAdd(w.circle, w.carol.identity.member, quiet)
        val tooSoon = w.founder.stewardAdd(w.circle, w.carol.identity.member, quiet + 13)
        assertEquals(setOf(w.founder.identity.member, w.bea.identity.member), w.fold(w.founder, w.log + listOf(addBea, proposal, tooSoon)).stewards)
        val after = w.founder.stewardAdd(w.circle, w.carol.identity.member, quiet + 14)
        val view = w.fold(w.founder, w.log + listOf(addBea, proposal, tooSoon, after))
        assertEquals(setOf(w.founder.identity.member, w.bea.identity.member, w.carol.identity.member), view.stewards)
        assertEquals(emptyList(), view.rejected)
    }

    @Test
    fun aHelperEventAfterTheProposalOrJustBeforeItBlocksTheFallback() = runTest {
        val w = world()
        w.start()
        w.joinCarol()
        val addBea = w.founder.stewardAdd(w.circle, w.bea.identity.member, day)
        val quiet = day + 60
        val proposal = w.founder.stewardRemove(w.circle, w.bea.identity.member, quiet)
        val back = w.bea.invite(w.circle, InviteKey(w.crypto.generateSigningKeyPair()), quiet + 30, 1, quiet + 3)
        val repeat = w.founder.stewardRemove(w.circle, w.bea.identity.member, quiet + 14)
        val vetoed = w.fold(w.founder, w.log + listOf(addBea, proposal, back, repeat))
        assertEquals(setOf(w.founder.identity.member, w.bea.identity.member), vetoed.stewards)
        // The same proposal with no event from bea does remove her.
        val removed = w.fold(w.founder, w.log + listOf(addBea, proposal, repeat))
        assertEquals(setOf(w.founder.identity.member), removed.stewards)
        // And an event of hers just before the quiet period was up keeps it from starting at all.
        val spoke = w.bea.invite(w.circle, InviteKey(w.crypto.generateSigningKeyPair()), quiet + 30, 1, quiet - 5)
        val early = w.fold(w.founder, w.log + listOf(addBea, spoke, proposal, repeat))
        assertEquals(setOf(w.founder.identity.member, w.bea.identity.member), early.stewards)
    }

    @Test
    fun theFallbackCannotChangeTheSettings() = runTest {
        val w = world()
        w.start()
        val addBea = w.founder.stewardAdd(w.circle, w.bea.identity.member, day)
        val proposed = StewardSettings(quorum = 1, inactiveDays = 30, fallbackDelayDays = 7)
        val first = w.founder.stewardPolicy(w.circle, proposed, day + 60)
        val again = w.founder.stewardPolicy(w.circle, proposed, day + 90)
        assertEquals(StewardSettings(), w.fold(w.founder, w.log + listOf(addBea, first, again)).stewardSettings)
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
                forged.copy(circle = w.circle, public = KeyWrapTerms(terms.device, terms.epoch, terms.wrapped, terms.keyHash).toPublic())
                    .signedBy(w.crypto, w.founder.identity.signing.secretSeed)
            }
        w.log += wrongKeyFor
        val view = w.fold(w.founder)
        assertEquals(0, view.keys.latestEpoch)
        assertTrue(view.rejected.single().reason.startsWith("the key wrapped to this device is not usable"))
    }

    // ---- key commitments (ADR 0045) ----

    private fun hex(text: String) = ByteString.of(ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() })

    // From tools/vectors/key_commitment_vectors.py (hashlib BLAKE2b), independent of the code under test.
    @Test
    fun theCommitmentMatchesTheIndependentPythonVectors() = runTest {
        val crypto = newCrypto()
        val circle = circles.policy.CircleId("0123456789abcdef0123456789abcdef")
        val key = ByteString.of(ByteArray(32) { it.toByte() })
        assertEquals(hex("f841b22309433a2e16296dd53e02298fd0383a609520ecbf43197ba830f1adf2"), CircleKeys.commitment(crypto, circle, 1, key))
        assertEquals(hex("5570af22020dd5f12a7d723288d99099dd931a4e0b40d85b9f903407ccbe48c6"), CircleKeys.commitment(crypto, circle, 2, key))
        assertEquals(hex("dde2ae718fcf1775eda143cde8cb3493f765471c4dac83dc44dff4a0bbe44dc0"), CircleKeys.commitment(crypto, circle, 258, key))
    }

    @Test
    fun theFirstKeyAndEveryRotationMustCarryACommitment() = runTest {
        val w = world()
        w.log += w.founder.circleCreate(w.circle, day)
        val bare = w.founder.keyWrap(w.circle, w.founder.identity.registration, FIRST_EPOCH, w.key1, day, commit = false)
        assertTrue(w.fold(w.founder, w.log + bare).rejected.single().reason.contains("must carry a key commitment"))
        w.log += w.founder.keyWrap(w.circle, w.founder.identity.registration, FIRST_EPOCH, w.key1, day)
        val noHash = w.founder.keyRotate(w.circle, 2, day)
        assertTrue(w.fold(w.founder, w.log + noHash).rejected.single().reason.contains("must carry a key commitment"))
        val view = w.fold(w.founder, w.log + w.founder.rotateKeys(w.circle, 2, listOf(w.founder.identity.registration), day))
        assertEquals(emptyList(), view.rejected)
        assertEquals(2, view.currentEpoch)
    }

    @Test
    fun aWrapOfAnotherKeyIsIgnoredAndTheHonestWrapStillWorks() = runTest {
        val w = world()
        w.start()
        w.joinCarol()
        val hostileKey = KeyRing.newCircleKey(w.crypto)
        val carol = w.carol
        // Bea, a plain member who holds the key, wraps a different key to carol before the steward does.
        val bad = w.bea.keyWrap(w.circle, carol.identity.registration, FIRST_EPOCH, hostileKey, day)
        val view1 = w.fold(carol, w.log + bad)
        assertEquals(null, view1.keys[FIRST_EPOCH], "carol does not take a key that fails the commitment")
        assertTrue(view1.rejected.single().reason.contains("does not match the epoch's commitment"))
        val good = w.founder.keyWrap(w.circle, carol.identity.registration, FIRST_EPOCH, w.key1, day)
        val view2 = w.fold(carol, w.log + bad + good)
        assertEquals(w.key1, view2.keys[FIRST_EPOCH], "the honest wrap after it works")
        // And in the other order the bad one cannot replace a good key either.
        val view3 = w.fold(carol, w.log + good + bad)
        assertEquals(w.key1, view3.keys[FIRST_EPOCH])
    }

    @Test
    fun aLaterWrapMayRepeatTheCommitmentButNotChangeIt() = runTest {
        val w = world()
        w.start()
        w.joinCarol()
        val same = w.founder.keyWrap(w.circle, w.carol.identity.registration, FIRST_EPOCH, w.key1, day, commit = true)
        assertEquals(emptyList(), w.fold(w.carol, w.log + same).rejected)
        val other = w.founder.keyWrap(w.circle, w.carol.identity.registration, FIRST_EPOCH, KeyRing.newCircleKey(w.crypto), day, commit = true)
        assertTrue(w.fold(w.carol, w.log + other).rejected.single().reason.contains("not the epoch's commitment"))
    }
}
