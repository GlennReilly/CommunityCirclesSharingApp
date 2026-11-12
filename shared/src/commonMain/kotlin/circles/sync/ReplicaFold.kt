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
    // The outbox is read before the log: an append that lands between the two reads moves events from the outbox into
    // the log, so reading in this order sees them at least once (the other order could miss them in both).
    val waiting = outbox(circle)
    val entries = entries(circle)
    val checked = entries.mapNotNull { entry -> entry.verifiedWith?.let { entry.id to it } }.toMap()
    val stored = entries.map { it.id }.toSet()
    val pending = waiting.filter { it.eventId(crypto) !in stored }
    return CircleFold(crypto, cipher, me, requireConfirmedStewards = true, signatures = VerifiedSignatures.of(checked)).fold(circle, entries.map { it.envelope } + pending)
}
