package circles.keys

import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.event.DeviceId
import circles.event.DeviceIdentity
import circles.policy.MemberId
import circles.event.newMemberId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

const val DEFAULT_IDLE_LOCK_MILLIS = 15L * 60 * 1000

/** When unlocked keys are dropped from memory (ADR 0039). Keys are never written anywhere in the clear either way. */
sealed interface AutoLock {
    /** After this long without [KeyVault.touch]. */
    data class AfterIdle(val millis: Long) : AutoLock

    /** Only when the vault is locked by hand or the tab closes (memory then goes with it). */
    data object UntilTabCloses : AutoLock
}

sealed interface VaultState {
    /** This browser has no identity yet: show join / sign in. */
    data object Empty : VaultState

    data class Locked(val member: MemberId, val device: DeviceId) : VaultState

    /** The identity is in memory. */
    class Unlocked(val identity: DeviceIdentity) : VaultState
}

sealed interface CreateResult {
    class Created(val identity: DeviceIdentity) : CreateResult
    data class PassphraseTooShort(val minimum: Int) : CreateResult
    data object AlreadyHasIdentity : CreateResult
}

sealed interface UnlockResult {
    data object Unlocked : UnlockResult
    data object WrongPassphrase : UnlockResult
    data object NoIdentity : UnlockResult
    data class Unsupported(val reason: String) : UnlockResult
}

/**
 * Holds this browser's device identity (ADR 0039): created once, stored wrapped, unlocked with the passphrase into
 * memory only, locked again by hand or after the idle time. [touch] is called on user activity. Time is coroutine
 * `delay`, so tests run on virtual time.
 */
class KeyVault(
    private val scope: CoroutineScope,
    private val crypto: Crypto,
    private val cipher: Cipher,
    private val hasher: PasswordHasher,
    private val store: KeyStore,
    var autoLock: AutoLock = AutoLock.AfterIdle(DEFAULT_IDLE_LOCK_MILLIS),
    private val params: Argon2Params = Argon2Params.DEFAULT,
) {
    private val mutableState = MutableStateFlow<VaultState>(VaultState.Empty)
    val state: StateFlow<VaultState> = mutableState.asStateFlow()
    private var idleJob: Job? = null

    /** Reads the store at start-up: [VaultState.Locked] if an identity is saved, otherwise [VaultState.Empty]. */
    suspend fun open() {
        mutableState.value = store.load()?.let { VaultState.Locked(it.memberId, it.deviceId) } ?: VaultState.Empty
    }

    /** Makes a new identity (new member, or [member] for another device of an existing one), saves it wrapped and unlocks. */
    suspend fun create(passphrase: String, member: MemberId = newMemberId(crypto)): CreateResult {
        if (store.load() != null) return CreateResult.AlreadyHasIdentity
        (SeedWrapping.checkPassphrase(passphrase) as? PassphraseCheck.TooShort)?.let { return CreateResult.PassphraseTooShort(it.minimum) }
        val identity = DeviceIdentity.generate(crypto, cipher, member)
        val wrapped = SeedWrapping.wrapWithPassphrase(crypto, cipher, hasher, identity.seed!!, identity.device, passphrase, params)
        store.save(StoredIdentity(identity.member.v, identity.device.v, wrapped))
        unlocked(identity)
        return CreateResult.Created(identity)
    }

    suspend fun unlock(passphrase: String): UnlockResult {
        val stored = store.load() ?: return UnlockResult.NoIdentity
        return when (val result = SeedWrapping.unwrapWithPassphrase(cipher, hasher, stored.wrapped, stored.deviceId, passphrase)) {
            is UnwrapResult.Opened -> {
                unlocked(DeviceIdentity.fromSeed(crypto, cipher, result.seed, stored.memberId, stored.deviceId))
                UnlockResult.Unlocked
            }
            UnwrapResult.WrongPassphraseOrDamaged -> UnlockResult.WrongPassphrase
            is UnwrapResult.Unsupported -> UnlockResult.Unsupported(result.reason)
        }
    }

    /** Drops the keys from memory; the saved identity stays. */
    fun lock() {
        idleJob?.cancel()
        val current = mutableState.value
        if (current is VaultState.Unlocked) mutableState.value = VaultState.Locked(current.identity.member, current.identity.device)
    }

    /** User activity: restarts the idle timer. Cheap enough to call on every input. */
    fun touch() {
        if (mutableState.value is VaultState.Unlocked) startIdleTimer()
    }

    /** Forgets the identity on this device ("delete my data on this device", spec 6). */
    suspend fun erase() {
        idleJob?.cancel()
        store.erase()
        mutableState.value = VaultState.Empty
    }

    private fun unlocked(identity: DeviceIdentity) {
        mutableState.value = VaultState.Unlocked(identity)
        startIdleTimer()
    }

    private fun startIdleTimer() {
        idleJob?.cancel()
        val rule = autoLock as? AutoLock.AfterIdle ?: return
        idleJob = scope.launch {
            delay(rule.millis)
            lock()
        }
    }
}
