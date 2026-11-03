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
 * the events arrived (ADR 0036). Events that could not be checked then are checked by the fold itself.
 *
 * This still replays the whole log. Folding from a saved snapshot is the next step of ADR 0036 and is not built.
 */
suspend fun Replica.fold(circle: CircleId, crypto: Crypto, cipher: Cipher, me: DeviceIdentity?): CircleView {
    val entries = entries(circle)
    val checked = entries.mapNotNull { entry -> entry.verifiedWith?.let { entry.id to it } }.toMap()
    return CircleFold(crypto, cipher, me, signatures = VerifiedSignatures.of(checked)).fold(circle, entries.map { it.envelope })
}
