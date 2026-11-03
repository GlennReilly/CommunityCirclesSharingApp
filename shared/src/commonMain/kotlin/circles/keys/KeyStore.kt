package circles.keys

import circles.event.DeviceId
import circles.policy.MemberId
import kotlinx.serialization.Serializable

/** What a browser keeps about its own identity: public ids plus the wrapped seed. Nothing here lets anyone act as the member. */
@Serializable
data class StoredIdentity(val member: String, val device: String, val wrapped: WrappedSeed) {
    val memberId: MemberId get() = MemberId(member)
    val deviceId: DeviceId get() = DeviceId(device)
}

/** Where the wrapped identity lives (IndexedDB in the browser). One identity per browser profile. */
interface KeyStore {
    suspend fun load(): StoredIdentity?

    suspend fun save(identity: StoredIdentity)

    /** Forgets the identity ("delete my data on this device", spec 6). */
    suspend fun erase()
}

class InMemoryKeyStore : KeyStore {
    private var stored: StoredIdentity? = null

    override suspend fun load(): StoredIdentity? = stored

    override suspend fun save(identity: StoredIdentity) {
        stored = identity
    }

    override suspend fun erase() {
        stored = null
    }
}
