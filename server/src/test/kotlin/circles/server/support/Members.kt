package circles.server.support

import circles.event.DeviceIdentity
import circles.event.EventAuthor
import circles.event.InviteKey
import circles.event.newCircleId
import circles.crypto.Crypto
import circles.policy.CircleId

/** A test person with one device and an event builder. */
class Person(crypto: Crypto, val author: EventAuthor = EventAuthor(crypto, DeviceIdentity.generate(crypto))) {
    val identity: DeviceIdentity get() = author.identity
}

fun newInviteKey(crypto: Crypto): InviteKey = InviteKey(crypto.generateSigningKeyPair())

fun freshCircle(crypto: Crypto): CircleId = newCircleId(crypto)
