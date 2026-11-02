package circles.server.support

import circles.event.DeviceIdentity
import circles.event.EventAuthor
import circles.event.InviteKey
import circles.event.newCircleId
import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.crypto.LibsodiumCipher
import circles.policy.CircleId

/** One libsodium instance for all server tests; loading the native library is slow. */
val testCipher: Cipher = LibsodiumCipher()

/** A test person with one device and an event builder. */
class Person(crypto: Crypto, val author: EventAuthor = EventAuthor(crypto, DeviceIdentity.generate(crypto, testCipher), testCipher)) {
    val identity: DeviceIdentity get() = author.identity
}

fun newInviteKey(crypto: Crypto): InviteKey = InviteKey(crypto.generateSigningKeyPair())

fun freshCircle(crypto: Crypto): CircleId = newCircleId(crypto)
