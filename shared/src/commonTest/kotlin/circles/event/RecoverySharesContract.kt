package circles.event

import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.encoding.ByteString
import circles.encoding.Parsed
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

/** `recovery.share`: split the circle key over stewards and rebuild it from k of them (ADR 0020), on every Crypto/Cipher pair. */
abstract class RecoverySharesContract {
    protected abstract suspend fun newCrypto(): Crypto
    protected abstract suspend fun newCipher(): Cipher

    private class Setup(val crypto: Crypto, val cipher: Cipher) {
        val circle = newCircleId(crypto)
        val key = KeyRing.newCircleKey(crypto)
        val founder = EventAuthor(crypto, DeviceIdentity.generate(crypto, cipher = cipher), cipher = cipher).also { it.keyRing = KeyRing.EMPTY.with(FIRST_EPOCH, key) }
        val stewards = List(5) { DeviceIdentity.generate(crypto, cipher = cipher) }

        fun events(threshold: Int = 3) = founder.recoveryShares(circle, FIRST_EPOCH, stewards.map { it.registration }, threshold, 20_000)!!

        fun opened(events: List<Envelope>, index: Int): RecoveryShare {
            val terms = (CircleKeys.keyWrap(events[index].public) as Parsed.Ok).value
            assertEquals(stewards[index].device, terms.device)
            return assertIs<Parsed.Ok<RecoveryShare>>(RecoveryShares.open(cipher, stewards[index].boxKeys, circle, FIRST_EPOCH, terms.wrapped)).value
        }
    }

    private suspend fun setup() = Setup(newCrypto(), newCipher())

    @Test
    fun anyThreeOfFiveStewardsRebuildTheKey() = runTest {
        val s = setup()
        val events = s.events()
        assertEquals(5, events.size)
        events.forEach { assertEquals(EventType.RECOVERY_SHARE.wire, it.type); assertNull(EnvelopeChecks.check(it)) }
        val shares = List(5) { s.opened(events, it) }
        for (subset in listOf(listOf(0, 1, 2), listOf(4, 2, 0), listOf(1, 3, 4), listOf(0, 1, 2, 3, 4))) {
            val key = assertIs<Parsed.Ok<ByteString>>(RecoveryShares.recover(s.crypto, subset.map { shares[it] })).value
            assertEquals(s.key, key)
        }
    }

    @Test
    fun tooFewWrongOrMixedSharesDoNotGiveAKey() = runTest {
        val s = setup()
        val shares = s.events().indices.map { s.opened(s.events(), it) }
        val first = s.events()
        val fromFirst = List(5) { s.opened(first, it) }
        assertIs<Parsed.Invalid>(RecoveryShares.recover(s.crypto, fromFirst.take(2)))
        assertIs<Parsed.Invalid>(RecoveryShares.recover(s.crypto, emptyList()))
        // Shares from two different splits of the same key combine to garbage; the hash check refuses it.
        assertIs<Parsed.Invalid>(RecoveryShares.recover(s.crypto, listOf(fromFirst[0], fromFirst[1], shares[2])))
        // A corrupted share is caught too.
        val bad = RecoveryShare(ByteString.of(fromFirst[2].share.toByteArray().also { it[5] = (it[5].toInt() xor 1).toByte() }), fromFirst[2].check)
        assertIs<Parsed.Invalid>(RecoveryShares.recover(s.crypto, listOf(fromFirst[0], fromFirst[1], bad)))
        // Shares of a different key have a different check.
        val other = Setup(s.crypto, s.cipher)
        val otherShares = List(5) { other.opened(other.events(), it) }
        assertIs<Parsed.Invalid>(RecoveryShares.recover(s.crypto, listOf(fromFirst[0], fromFirst[1], otherShares[2])))
    }

    @Test
    fun aShareOpensOnlyOnItsStewardsDeviceAndOnlyForItsCircleAndEpoch() = runTest {
        val s = setup()
        val events = s.events()
        val terms = (CircleKeys.keyWrap(events[0].public) as Parsed.Ok).value
        assertIs<Parsed.Invalid>(RecoveryShares.open(s.cipher, s.stewards[1].boxKeys, s.circle, FIRST_EPOCH, terms.wrapped))
        assertIs<Parsed.Invalid>(RecoveryShares.open(s.cipher, s.stewards[0].boxKeys, s.circle, 2, terms.wrapped))
        assertIs<Parsed.Invalid>(RecoveryShares.open(s.cipher, s.stewards[0].boxKeys, newCircleId(s.crypto), FIRST_EPOCH, terms.wrapped))
        assertFalse(terms.wrapped.toHex().contains(s.key.toHex()))
    }

    @Test
    fun invalidThresholdsGiveNoEvents() = runTest {
        val s = setup()
        val regs = s.stewards.map { it.registration }
        assertNull(s.founder.recoveryShares(s.circle, FIRST_EPOCH, regs, 1, 20_000))
        assertNull(s.founder.recoveryShares(s.circle, FIRST_EPOCH, regs, 6, 20_000))
    }

    // ---- ADR 0048: one share per steward, share codes, and the fold ----

    /** A circle with three stewards (quorum 1 so they join at once); the second steward has two devices. */
    private class Circle(val crypto: Crypto, val cipher: Cipher) {
        val id = newCircleId(crypto)
        val key = KeyRing.newCircleKey(crypto)
        val day = 20_000
        fun person() = EventAuthor(crypto, DeviceIdentity.generate(crypto, cipher = cipher), cipher = cipher)
        val a = person().also { it.keyRing = KeyRing.EMPTY.with(FIRST_EPOCH, key) }
        val b = person()
        val c = person()
        val b2 = DeviceIdentity.generate(crypto, cipher = cipher)
        /** The second device registered under the second steward's member id. */
        val b2Registration = DeviceRegistration(b.identity.member, b2.device, b2.registration.signPub, b2.registration.boxPub)
        val log = mutableListOf<Envelope>()

        init {
            log += a.circleCreate(id, day, StewardSettings(quorum = 1))
            log += a.keyWrap(id, a.identity.registration, FIRST_EPOCH, key, day)
            for (p in listOf(b, c)) {
                val invite = InviteKey(crypto.generateSigningKeyPair())
                val inviteEvent = a.invite(id, invite, day + 30, 1, day)
                log += inviteEvent
                log += p.join(id, inviteEvent.eventId(crypto), invite, day)
                log += a.keyWrap(id, p.identity.registration, FIRST_EPOCH, key, day)
                log += a.stewardAdd(id, p.identity.member, day)
            }
            log += b.deviceAdd(id, b2Registration, day)
        }

        fun fold(who: DeviceIdentity?): CircleView = CircleFold(crypto, cipher, who).fold(id, log)

        val stewardDevices get() = listOf(listOf(a.identity.registration), listOf(b.identity.registration, b2Registration), listOf(c.identity.registration))
    }

    @Test
    fun oneSharePerStewardIsSealedToEachOfTheirDevicesAndAnyKRebuildTheKey() = runTest {
        val circle = Circle(newCrypto(), newCipher())
        val events = circle.a.recoverySharesPerSteward(circle.id, FIRST_EPOCH, circle.stewardDevices, 2, circle.day)!!
        assertEquals(4, events.size) // a, b, b2, c
        events.forEach { assertNull(EnvelopeChecks.check(it)) }
        val byDevice = events.associate { e -> (CircleKeys.keyWrap(e.public) as Parsed.Ok).value.let { it.device to it.wrapped } }
        fun share(owner: DeviceIdentity) = assertIs<Parsed.Ok<RecoveryShare>>(RecoveryShares.open(circle.cipher(), owner.boxKeys, circle.id, FIRST_EPOCH, byDevice.getValue(owner.device))).value
        // The two devices of the second steward hold the same share, so they do not make a quorum together.
        assertEquals(share(circle.b.identity).share, share(circle.b2).share)
        val commitment = CircleKeys.commitment(circle.crypto, circle.id, FIRST_EPOCH, circle.key)
        val rebuilt = RecoveryShares.rebuild(circle.crypto, circle.id, FIRST_EPOCH, commitment, listOf(share(circle.a.identity).share, share(circle.c.identity).share))
        assertEquals(circle.key, assertIs<Parsed.Ok<ByteString>>(rebuilt).value)
        assertIs<Parsed.Invalid>(RecoveryShares.rebuild(circle.crypto, circle.id, FIRST_EPOCH, commitment, listOf(share(circle.b.identity).share, share(circle.b2).share)))
        assertIs<Parsed.Invalid>(RecoveryShares.rebuild(circle.crypto, circle.id, FIRST_EPOCH, commitment, listOf(share(circle.a.identity).share)))
        assertIs<Parsed.Invalid>(RecoveryShares.rebuild(circle.crypto, circle.id, 2, commitment, listOf(share(circle.a.identity).share, share(circle.c.identity).share)))
        assertNull(circle.a.recoverySharesPerSteward(circle.id, FIRST_EPOCH, listOf(emptyList()), 2, circle.day))
        assertNull(circle.a.recoverySharesPerSteward(circle.id, FIRST_EPOCH, circle.stewardDevices, 4, circle.day))
    }

    private fun Circle.cipher(): Cipher = cipher

    @Test
    fun theFoldKeepsTheShareThisDeviceHoldsAndWhoWasSentOne() = runTest {
        val circle = Circle(newCrypto(), newCipher())
        circle.log += circle.a.recoverySharesPerSteward(circle.id, FIRST_EPOCH, circle.stewardDevices, 2, circle.day)!!
        val view = circle.fold(circle.c.identity)
        assertEquals(emptyList(), view.rejected)
        assertEquals(setOf(circle.a.identity.member, circle.b.identity.member, circle.c.identity.member), view.shareHolders.getValue(FIRST_EPOCH))
        val held = view.heldShares.getValue(FIRST_EPOCH)
        assertEquals(circle.a.identity.member, held.from)
        // Another device of nobody's holds none; the log still lists who has one.
        assertEquals(emptyMap(), circle.fold(DeviceIdentity.generate(circle.crypto, cipher = circle.cipher)).heldShares)
        // The newest set replaces the older one for the same epoch.
        circle.log += circle.a.recoverySharesPerSteward(circle.id, FIRST_EPOCH, circle.stewardDevices, 2, circle.day + 1)!!
        assertTrue(circle.fold(circle.c.identity).heldShares.getValue(FIRST_EPOCH).share != held.share)
    }

    @Test
    fun aShareToANonStewardIsRejected() = runTest {
        val circle = Circle(newCrypto(), newCipher())
        val outsider = circle.person()
        circle.log += circle.a.keyWrap(circle.id, outsider.identity.registration, FIRST_EPOCH, circle.key, circle.day)
        val toNonSteward = circle.a.recoverySharesPerSteward(circle.id, FIRST_EPOCH, listOf(listOf(circle.a.identity.registration), listOf(outsider.identity.registration)), 2, circle.day)!!
        val view = CircleFold(circle.crypto, circle.cipher, circle.a.identity).fold(circle.id, circle.log + toNonSteward)
        assertTrue(view.rejected.any { it.reason.contains("only go to a steward") || it.reason.contains("unknown") })
    }

    @Test
    fun shareCodesRoundTripAndCatchMistypingOnEveryCryptoPair() = runTest {
        val crypto = newCrypto()
        val share = crypto.randomBytes(35)
        val text = ShareCode.format(crypto, 3, share)
        assertTrue(text.startsWith("rs1 "))
        val ok = assertIs<ShareCodeResult.Valid>(ShareCode.parse(crypto, text))
        assertEquals(3, ok.content.epoch)
        assertEquals(share, ok.content.share)
        // Case, spacing and dashes do not matter.
        assertEquals(share, assertIs<ShareCodeResult.Valid>(ShareCode.parse(crypto, text.uppercase().replace(' ', '-'))).content.share)
        // One changed character is caught.
        val lastDigit = text.last()
        val changed = text.dropLast(1) + (if (lastDigit == '0') '1' else '0')
        assertIs<ShareCodeResult.Mistyped>(ShareCode.parse(crypto, changed))
        assertIs<ShareCodeResult.NotACode>(ShareCode.parse(crypto, "hello world"))
        assertIs<ShareCodeResult.NotACode>(ShareCode.parse(crypto, "rs1 abc"))
        assertIs<ShareCodeResult.NotACode>(ShareCode.parse(crypto, ""))
    }
}
