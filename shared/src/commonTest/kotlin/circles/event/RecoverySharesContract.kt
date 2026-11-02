package circles.event

import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.encoding.ByteString
import circles.encoding.Parsed
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
}
