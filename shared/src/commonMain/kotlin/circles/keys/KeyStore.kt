package circles.keys

import circles.event.DeviceId
import circles.policy.MemberId
import kotlinx.serialization.Serializable

/**
 * The passkey that can unlock this identity (ADR 0063): its credential id, the salt given to its secret-making function, and the
 * seed sealed under a key made from that secret. Holds nothing that opens without the authenticator.
 */
@Serializable
data class PasskeyWrap(val credential: String, val salt: String, val sealed: String)

/** What a browser keeps about its own identity: public ids plus the wrapped seed. Nothing here lets anyone act as the member. */
@Serializable
data class StoredIdentity(
    val member: String,
    val device: String,
    val wrapped: WrappedSeed,
    /** The circle this browser is part of (v1 shows one circle); null until the device starts or joins one. */
    val circle: String? = null,
    /** Minutes of inactivity before locking; 0 means only when the tab closes; null means the default. */
    val autoLockMinutes: Int? = null,
    /** True for a browser added as another device of an existing member: it waits until that member registers it (ADR 0046). */
    val pairing: Boolean = false,
    /** The seed sealed a second time under this device's passkey, if the member turned that on (ADR 0063). */
    val passkey: PasskeyWrap? = null,
) {
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
