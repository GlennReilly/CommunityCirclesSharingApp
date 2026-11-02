package circles.event

import circles.crypto.AEAD_NONCE_BYTES
import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.encoding.ByteString
import circles.encoding.CborMap
import circles.encoding.CborText
import circles.encoding.Parsed
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Sealed `v = 1` events, circle keys and key wrapping (ADR 0021), run on every Crypto/Cipher pair. */
abstract class SealedEventContract {
    protected abstract suspend fun newCrypto(): Crypto
    protected abstract suspend fun newCipher(): Cipher

    private val day = 20_000
    private val note = "bring the good ladder"

    private class Kit(val crypto: Crypto, val cipher: Cipher) {
        val circle = newCircleId(crypto)
        val founder = EventAuthor(crypto, DeviceIdentity.generate(crypto, cipher = cipher), cipher = cipher)
        val key = KeyRing.newCircleKey(crypto)

        init {
            founder.keyRing = KeyRing.EMPTY.with(FIRST_EPOCH, key)
        }

        fun note(author: EventAuthor, text: String?): Envelope =
            author.invite(circle, InviteKey(crypto.generateSigningKeyPair()), 20_030, 1, 20_000, text)

        fun noteText(envelope: Envelope, keys: KeyRing): String? {
            val payload = assertIs<Parsed.Ok<CborMap>>(PayloadSealing.open(cipher, keys, envelope)).value
            return (payload["note"] as? CborText)?.value
        }
    }

    private suspend fun kit() = Kit(newCrypto(), newCipher())

    @Test
    fun aSealedNoteIsNotInTheEnvelopeAndOpensWithTheKey() = runTest {
        val k = kit()
        val event = k.note(k.founder, note)
        assertEquals(PAYLOAD_VERSION_SEALED, event.v)
        assertEquals(FIRST_EPOCH, event.epoch)
        assertEquals(AEAD_NONCE_BYTES, event.nonce.size)
        assertFalse(event.encrypted.toHex().contains(ByteString.of(note.encodeToByteArray()).toHex()))
        assertTrue(event.hasValidSignature(k.crypto, k.founder.identity.signing.publicKey))
        assertEquals(note, k.noteText(event, k.founder.keyRing))
        assertEquals(null, EnvelopeChecks.check(event))
    }

    @Test
    fun anEventWithNoSecretPartHasEmptyNonceAndCiphertext() = runTest {
        val k = kit()
        val event = k.note(k.founder, null)
        assertEquals(PAYLOAD_VERSION_SEALED, event.v)
        assertTrue(event.nonce.isEmpty() && event.encrypted.isEmpty())
        assertEquals(CborMap.EMPTY, assertIs<Parsed.Ok<CborMap>>(PayloadSealing.open(k.cipher, KeyRing.EMPTY, event)).value)
    }

    @Test
    fun theSameNoteSealsToDifferentCiphertexts() = runTest {
        val k = kit()
        assertNotEquals(k.note(k.founder, note).encrypted, k.note(k.founder, note).encrypted)
    }

    @Test
    fun openingFailsWithoutTheKeyWithTheWrongKeyAndAfterTampering() = runTest {
        val k = kit()
        val event = k.note(k.founder, note)
        assertIs<Parsed.Invalid>(PayloadSealing.open(k.cipher, KeyRing.EMPTY, event))
        val wrong = KeyRing.EMPTY.with(FIRST_EPOCH, KeyRing.newCircleKey(k.crypto))
        assertIs<Parsed.Invalid>(PayloadSealing.open(k.cipher, wrong, event))
        val flipped = event.copy(encrypted = ByteString.of(event.encrypted.toByteArray().also { it[0] = (it[0].toInt() xor 1).toByte() }))
        assertIs<Parsed.Invalid>(PayloadSealing.open(k.cipher, k.founder.keyRing, flipped))
        val otherNonce = event.copy(nonce = ByteString.of(ByteArray(AEAD_NONCE_BYTES)))
        assertIs<Parsed.Invalid>(PayloadSealing.open(k.cipher, k.founder.keyRing, otherNonce))
    }

    @Test
    fun theSecretPartIsBoundToThePublicPartAndTheEnvelope() = runTest {
        val k = kit()
        val a = k.note(k.founder, note)
        val b = k.note(k.founder, "something else")
        // Moving a's ciphertext under b's public part (or changing any envelope field) must not open.
        assertIs<Parsed.Invalid>(PayloadSealing.open(k.cipher, k.founder.keyRing, a.copy(public = b.public)))
        assertIs<Parsed.Invalid>(PayloadSealing.open(k.cipher, k.founder.keyRing, a.copy(day = a.day + 1)))
        assertIs<Parsed.Invalid>(PayloadSealing.open(k.cipher, k.founder.keyRing, a.copy(type = "member.join")))
        assertIs<Parsed.Invalid>(PayloadSealing.open(k.cipher, k.founder.keyRing, a.copy(circle = newCircleId(k.crypto))))
        assertEquals(note, k.noteText(a, k.founder.keyRing))
    }

    @Test
    fun anAuthorWithoutACircleKeyCannotWriteASecretPart() = runTest {
        val k = kit()
        val keyless = EventAuthor(k.crypto, DeviceIdentity.generate(k.crypto, cipher = k.cipher), cipher = k.cipher)
        assertFailsWith<IllegalStateException> { k.note(keyless, note) }
        assertTrue(k.note(keyless, null).encrypted.isEmpty())
    }

    @Test
    fun theFounderWrapsTheKeyToAnotherDeviceAndOnlyThatDeviceCanOpenIt() = runTest {
        val k = kit()
        val bea = DeviceIdentity.generate(k.crypto, cipher = k.cipher)
        val carol = DeviceIdentity.generate(k.crypto, cipher = k.cipher)
        val wrap = k.founder.keyWrap(k.circle, bea.registration, FIRST_EPOCH, k.key, day)
        assertEquals(EventType.KEY_WRAP.wire, wrap.type)
        assertEquals(FIRST_EPOCH, wrap.epoch)
        assertTrue(wrap.encrypted.isEmpty())
        assertEquals(null, EnvelopeChecks.check(wrap))
        val terms = assertIs<Parsed.Ok<KeyWrapTerms>>(CircleKeys.keyWrap(wrap.public)).value
        assertEquals(bea.device, terms.device)
        assertEquals(FIRST_EPOCH, terms.epoch)
        assertEquals(k.key, assertIs<Parsed.Ok<ByteString>>(CircleKeys.unwrap(k.cipher, bea.boxKeys, k.circle, FIRST_EPOCH, terms.wrapped)).value)
        assertIs<Parsed.Invalid>(CircleKeys.unwrap(k.cipher, carol.boxKeys, k.circle, FIRST_EPOCH, terms.wrapped))
        assertFalse(wrap.public.toString().contains(k.key.toHex()))
    }

    @Test
    fun aWrapCannotBeReplayedForAnotherEpochOrCircle() = runTest {
        val k = kit()
        val bea = DeviceIdentity.generate(k.crypto, cipher = k.cipher)
        val wrapped = CircleKeys.wrap(k.cipher, bea.boxPub, k.circle, 1, k.key)
        assertIs<Parsed.Ok<ByteString>>(CircleKeys.unwrap(k.cipher, bea.boxKeys, k.circle, 1, wrapped))
        assertIs<Parsed.Invalid>(CircleKeys.unwrap(k.cipher, bea.boxKeys, k.circle, 2, wrapped))
        assertIs<Parsed.Invalid>(CircleKeys.unwrap(k.cipher, bea.boxKeys, newCircleId(k.crypto), 1, wrapped))
    }

    @Test
    fun aNewMemberReadsTheFoundersNoteAfterTheKeyIsWrappedToThem() = runTest {
        val k = kit()
        val bea = EventAuthor(k.crypto, DeviceIdentity.generate(k.crypto, cipher = k.cipher), cipher = k.cipher)
        val event = k.note(k.founder, note)
        assertIs<Parsed.Invalid>(PayloadSealing.open(k.cipher, bea.keyRing, event))
        val wrap = k.founder.keyWrap(k.circle, bea.identity.registration, FIRST_EPOCH, k.key, day)
        val terms = assertIs<Parsed.Ok<KeyWrapTerms>>(CircleKeys.keyWrap(wrap.public)).value
        val key = assertIs<Parsed.Ok<ByteString>>(CircleKeys.unwrap(k.cipher, bea.identity.boxKeys, k.circle, wrap.epoch, terms.wrapped)).value
        bea.keyRing = bea.keyRing.with(wrap.epoch, key)
        assertEquals(note, k.noteText(event, bea.keyRing))
    }

    @Test
    fun rotationStartsANewEpochAndOldEventsStayReadable() = runTest {
        val k = kit()
        val old = k.note(k.founder, note)
        val rotate = k.founder.keyRotate(k.circle, FIRST_EPOCH + 1, day)
        assertEquals(FIRST_EPOCH + 1, assertIs<Parsed.Ok<Int>>(CircleKeys.keyRotate(rotate.public)).value)
        assertEquals(rotate.epoch, FIRST_EPOCH + 1)
        k.founder.keyRing = k.founder.keyRing.with(FIRST_EPOCH + 1, KeyRing.newCircleKey(k.crypto))
        val fresh = k.note(k.founder, "after rotation")
        assertEquals(FIRST_EPOCH + 1, fresh.epoch)
        assertEquals(note, k.noteText(old, k.founder.keyRing))
        assertEquals("after rotation", k.noteText(fresh, k.founder.keyRing))
        val onlyOldKey = KeyRing.EMPTY.with(FIRST_EPOCH, k.key)
        assertIs<Parsed.Invalid>(PayloadSealing.open(k.cipher, onlyOldKey, fresh))
    }

    @Test
    fun malformedKeyWrapAndRotatePublicPartsAreRefused() = runTest {
        val k = kit()
        val ok = CircleKeys.wrap(k.cipher, k.founder.identity.boxPub, k.circle, 1, k.key)
        val good = KeyWrapTerms(k.founder.identity.device, 1, ok).toPublic()
        assertIs<Parsed.Ok<KeyWrapTerms>>(CircleKeys.keyWrap(good))
        assertIs<Parsed.Invalid>(CircleKeys.keyWrap(KeyWrapTerms(k.founder.identity.device, 0, ok).toPublic()))
        assertIs<Parsed.Invalid>(CircleKeys.keyWrap(CborMap(good.entries + ("extra" to CborText("x")))))
        assertIs<Parsed.Invalid>(CircleKeys.keyWrap(CborMap.EMPTY))
        assertIs<Parsed.Invalid>(CircleKeys.keyRotate(CircleKeys.rotatePublic(0)))
        assertIs<Parsed.Invalid>(CircleKeys.keyRotate(CborMap.EMPTY))
    }

    @Test
    fun sealedShapeChecksRefuseBadNoncesAndEpochs() = runTest {
        val k = kit()
        val event = k.note(k.founder, note)
        assertEquals(RejectionCode.MALFORMED, EnvelopeChecks.check(event.copy(nonce = ByteString.EMPTY))?.code)
        assertEquals(RejectionCode.MALFORMED, EnvelopeChecks.check(event.copy(epoch = 0))?.code)
        assertEquals(RejectionCode.MALFORMED, EnvelopeChecks.check(event.copy(encrypted = ByteString.of(ByteArray(3))))?.code)
        val empty = k.note(k.founder, null)
        assertEquals(RejectionCode.MALFORMED, EnvelopeChecks.check(empty.copy(nonce = event.nonce))?.code)
        assertEquals(RejectionCode.UNSUPPORTED_VERSION, EnvelopeChecks.check(event.copy(v = 2))?.code)
    }
}
