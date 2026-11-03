package circles.sync

import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.event.CircleFold
import circles.event.CircleView
import circles.event.DeviceIdentity
import circles.event.VerifiedSignatures
import circles.policy.CircleId

/**
 * Folds the stored log of [circle] into a view without repeating signature checks: the engine already did them when
 * the events arrived (ADR 0036). Events that could not be checked then are checked by the fold itself. Changes still
 * waiting in the outbox are folded after the log, so a member sees their own changes at once, offline too; when the
 * server's copy arrives it takes their place.
 *
 * This still replays the whole log. Folding from a saved snapshot is the next step of ADR 0036 and is not built.
 */
suspend fun Replica.fold(circle: CircleId, crypto: Crypto, cipher: Cipher, me: DeviceIdentity?): CircleView {
    val entries = entries(circle)
    val checked = entries.mapNotNull { entry -> entry.verifiedWith?.let { entry.id to it } }.toMap()
    val stored = entries.map { it.id }.toSet()
    val pending = outbox(circle).filter { it.eventId(crypto) !in stored }
    return CircleFold(crypto, cipher, me, signatures = VerifiedSignatures.of(checked)).fold(circle, entries.map { it.envelope } + pending)
}
