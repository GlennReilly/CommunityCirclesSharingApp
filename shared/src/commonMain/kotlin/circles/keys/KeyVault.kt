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

const val DEFAULT_IDLE_LOCK_MINUTES = 15
const val MILLIS_PER_MINUTE = 60_000L
const val DEFAULT_IDLE_LOCK_MILLIS = DEFAULT_IDLE_LOCK_MINUTES * MILLIS_PER_MINUTE

/** The choices the settings page offers, in minutes; 0 means only when the tab closes. */
val AUTO_LOCK_CHOICES_MINUTES: List<Int> = listOf(5, DEFAULT_IDLE_LOCK_MINUTES, 60, 0)

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

sealed interface ChangePassphraseResult {
    data object Changed : ChangePassphraseResult
    data object WrongPassphrase : ChangePassphraseResult
    data class TooShort(val minimum: Int) : ChangePassphraseResult
    data object NotUnlocked : ChangePassphraseResult
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
        val stored = store.load()
        stored?.autoLockMinutes?.let { autoLock = ruleOf(it) }
        mutableState.value = stored?.let { VaultState.Locked(it.memberId, it.deviceId) } ?: VaultState.Empty
    }

    /** The idle time in force, in minutes (0: only when the tab closes). */
    val autoLockMinutes: Int get() = (autoLock as? AutoLock.AfterIdle)?.let { (it.millis / MILLIS_PER_MINUTE).toInt() } ?: 0

    /** Changes and remembers the idle time (minutes; 0 means only when the tab closes), and restarts the timer. */
    suspend fun setAutoLockMinutes(minutes: Int) {
        autoLock = ruleOf(minutes)
        store.load()?.let { store.save(it.copy(autoLockMinutes = minutes)) }
        touch()
    }

    /**
     * Re-wraps the device seed under a new passphrase. Needs the vault unlocked and the old passphrase typed again,
     * so a borrowed unlocked browser cannot lock its owner out.
     */
    suspend fun changePassphrase(old: String, new: String): ChangePassphraseResult {
        val current = (mutableState.value as? VaultState.Unlocked)?.identity ?: return ChangePassphraseResult.NotUnlocked
        val stored = store.load() ?: return ChangePassphraseResult.NotUnlocked
        (SeedWrapping.checkPassphrase(new) as? PassphraseCheck.TooShort)?.let { return ChangePassphraseResult.TooShort(it.minimum) }
        if (SeedWrapping.unwrapWithPassphrase(cipher, hasher, stored.wrapped, stored.deviceId, old) !is UnwrapResult.Opened) return ChangePassphraseResult.WrongPassphrase
        val seed = current.seed ?: return ChangePassphraseResult.NotUnlocked
        store.save(stored.copy(wrapped = SeedWrapping.wrapWithPassphrase(crypto, cipher, hasher, seed, stored.deviceId, new, params)))
        return ChangePassphraseResult.Changed
    }

    private fun ruleOf(minutes: Int): AutoLock = if (minutes <= 0) AutoLock.UntilTabCloses else AutoLock.AfterIdle(minutes * MILLIS_PER_MINUTE)

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

    /** Saves an identity made from a known [seed] (a device made by pairing or restoring) under a new passphrase, and unlocks it. */
    suspend fun createFromSeed(passphrase: String, identity: DeviceIdentity): CreateResult {
        if (store.load() != null) return CreateResult.AlreadyHasIdentity
        (SeedWrapping.checkPassphrase(passphrase) as? PassphraseCheck.TooShort)?.let { return CreateResult.PassphraseTooShort(it.minimum) }
        val seed = identity.seed ?: return CreateResult.PassphraseTooShort(MIN_PASSPHRASE_CHARS)
        val wrapped = SeedWrapping.wrapWithPassphrase(crypto, cipher, hasher, seed, identity.device, passphrase, params)
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

    /** Whether a passkey can unlock this identity (ADR 0063). */
    suspend fun hasPasskey(): Boolean = store.load()?.passkey != null

    /**
     * Turns on unlocking with a passkey: the person is already unlocked, so the seed is in memory; a passkey is made, asked for its
     * secret once, and the seed is sealed under it next to the passphrase wrapping, which stays as the way back in.
     */
    suspend fun enablePasskey(passkeys: PasskeyProvider): PasskeyResult {
        val identity = (mutableState.value as? VaultState.Unlocked)?.identity ?: return PasskeyResult.NotUnlocked
        val stored = store.load() ?: return PasskeyResult.NotUnlocked
        val seed = identity.seed ?: return PasskeyResult.NotUnlocked
        if (!passkeys.available) return PasskeyResult.Unavailable
        val created = passkeys.create(identity.device) ?: return PasskeyResult.Unavailable
        val secret = passkeys.secret(created.credential, created.salt) ?: return PasskeyResult.Unavailable
        val wrap = PasskeyWrapping.seal(crypto, cipher, secret, seed, identity.device, created) ?: return PasskeyResult.Unavailable
        store.save(stored.copy(passkey = wrap))
        return PasskeyResult.Done
    }

    suspend fun disablePasskey() {
        store.load()?.let { store.save(it.copy(passkey = null)) }
    }

    /** Unlocks with the passkey: the authenticator is asked for its secret, which opens the seed. */
    suspend fun unlockWithPasskey(passkeys: PasskeyProvider): UnlockResult {
        val stored = store.load() ?: return UnlockResult.NoIdentity
        val wrap = stored.passkey ?: return UnlockResult.Unsupported("this browser has no passkey for this identity")
        val credential = circles.encoding.ByteString.fromBase64Url(wrap.credential) ?: return UnlockResult.Unsupported("the passkey record is damaged")
        val salt = circles.encoding.ByteString.fromBase64Url(wrap.salt) ?: return UnlockResult.Unsupported("the passkey record is damaged")
        val secret = passkeys.secret(credential, salt) ?: return UnlockResult.WrongPassphrase
        val seed = PasskeyWrapping.open(crypto, cipher, secret, wrap, stored.deviceId) ?: return UnlockResult.WrongPassphrase
        unlocked(DeviceIdentity.fromSeed(crypto, cipher, seed, stored.memberId, stored.deviceId))
        return UnlockResult.Unlocked
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
