package circles.keys

import circles.crypto.Cipher
import circles.crypto.Crypto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PASSPHRASE = "correct horse battery staple"
private val FAST = Argon2Params(1_024, 1, 1)
private const val IDLE = 60_000L

/** The vault's life cycle on virtual time (ADR 0039), on every Crypto/Cipher/hasher combination. */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class KeyVaultContract {
    protected abstract suspend fun newCrypto(): Crypto
    protected abstract suspend fun newCipher(): Cipher
    protected abstract fun newHasher(): PasswordHasher

    private suspend fun TestScope.vault(store: KeyStore = InMemoryKeyStore(), autoLock: AutoLock = AutoLock.AfterIdle(IDLE)) =
        KeyVault(backgroundScope, newCrypto(), newCipher(), newHasher(), store, autoLock, FAST).also { it.open() }

    @Test fun anEmptyBrowserHasNoIdentity() = runTest {
        assertEquals(VaultState.Empty, vault().state.value)
    }

    @Test fun creatingStoresTheIdentityWrappedAndUnlocksIt() = runTest {
        val store = InMemoryKeyStore()
        val vault = vault(store)
        val created = assertIs<CreateResult.Created>(vault.create(PASSPHRASE))
        val state = assertIs<VaultState.Unlocked>(vault.state.value)
        assertEquals(created.identity.device, state.identity.device)
        val saved = assertNotNull(store.load())
        assertEquals(created.identity.device.v, saved.device)
        assertTrue(created.identity.seed!!.toBase64Url() !in saved.toString())
    }

    @Test fun aShortPassphraseCreatesNothing() = runTest {
        val store = InMemoryKeyStore()
        val vault = vault(store)
        assertEquals(CreateResult.PassphraseTooShort(MIN_PASSPHRASE_CHARS), vault.create("short"))
        assertEquals(VaultState.Empty, vault.state.value)
        assertNull(store.load())
    }

    @Test fun anExistingIdentityIsNeverOverwrittenByCreate() = runTest {
        val vault = vault()
        vault.create(PASSPHRASE)
        assertEquals(CreateResult.AlreadyHasIdentity, vault.create("another long passphrase here"))
    }

    @Test fun lockingDropsTheKeysAndUnlockingBringsTheSameIdentityBack() = runTest {
        val vault = vault()
        val created = assertIs<CreateResult.Created>(vault.create(PASSPHRASE))
        vault.lock()
        assertEquals(VaultState.Locked(created.identity.member, created.identity.device), vault.state.value)
        assertEquals(UnlockResult.Unlocked, vault.unlock(PASSPHRASE))
        val back = assertIs<VaultState.Unlocked>(vault.state.value).identity
        assertEquals(created.identity.signing.publicKey, back.signing.publicKey)
        assertEquals(created.identity.boxPub, back.boxPub)
    }

    @Test fun aWrongPassphraseKeepsItLocked() = runTest {
        val vault = vault()
        vault.create(PASSPHRASE)
        vault.lock()
        assertEquals(UnlockResult.WrongPassphrase, vault.unlock("not the passphrase"))
        assertIs<VaultState.Locked>(vault.state.value)
    }

    @Test fun aNewSessionStartsLockedWhenAnIdentityIsSaved() = runTest {
        val store = InMemoryKeyStore()
        val first = vault(store)
        first.create(PASSPHRASE)
        val second = vault(store)
        assertIs<VaultState.Locked>(second.state.value)
        assertEquals(UnlockResult.Unlocked, second.unlock(PASSPHRASE))
    }

    @Test fun unlockingWithNothingSavedSaysSo() = runTest {
        assertEquals(UnlockResult.NoIdentity, vault().unlock(PASSPHRASE))
    }

    @Test fun idleTimeLocksAndActivityPostponesIt() = runTest {
        val vault = vault()
        vault.create(PASSPHRASE)
        advanceTimeBy(IDLE - 1)
        vault.touch()
        advanceTimeBy(IDLE - 1)
        assertIs<VaultState.Unlocked>(vault.state.value, "touching restarted the timer")
        advanceTimeBy(2)
        assertIs<VaultState.Locked>(vault.state.value)
    }

    @Test fun touchingWhileLockedDoesNothing() = runTest {
        val vault = vault()
        vault.create(PASSPHRASE)
        vault.lock()
        vault.touch()
        advanceTimeBy(10 * IDLE)
        assertIs<VaultState.Locked>(vault.state.value)
    }

    @Test fun untilTheTabClosesNeverLocksByTimer() = runTest {
        val vault = vault(autoLock = AutoLock.UntilTabCloses)
        vault.create(PASSPHRASE)
        advanceTimeBy(100 * IDLE)
        assertIs<VaultState.Unlocked>(vault.state.value)
        vault.lock()
        assertIs<VaultState.Locked>(vault.state.value)
    }

    @Test fun erasingForgetsTheIdentityEverywhere() = runTest {
        val store = InMemoryKeyStore()
        val vault = vault(store)
        vault.create(PASSPHRASE)
        vault.erase()
        assertEquals(VaultState.Empty, vault.state.value)
        assertNull(store.load())
        advanceTimeBy(10 * IDLE)
        assertEquals(VaultState.Empty, vault.state.value)
    }

    @Test fun changingThePassphraseNeedsTheOldOneAndTheNewOneWorksAfterwards() = runTest {
        val store = InMemoryKeyStore()
        // No idle timer here: on JS the hashing is real asynchronous work, during which virtual time would jump ahead and lock the vault.
        val vault = vault(store, autoLock = AutoLock.UntilTabCloses)
        val created = assertIs<CreateResult.Created>(vault.create(PASSPHRASE))
        assertEquals(ChangePassphraseResult.WrongPassphrase, vault.changePassphrase("not the old one", "a brand new passphrase"))
        assertEquals(ChangePassphraseResult.TooShort(MIN_PASSPHRASE_CHARS), vault.changePassphrase(PASSPHRASE, "short"))
        assertEquals(ChangePassphraseResult.Changed, vault.changePassphrase(PASSPHRASE, "a brand new passphrase"))
        vault.lock()
        assertEquals(UnlockResult.WrongPassphrase, vault.unlock(PASSPHRASE), "the old passphrase no longer works")
        assertEquals(UnlockResult.Unlocked, vault.unlock("a brand new passphrase"))
        val back = assertIs<VaultState.Unlocked>(vault.state.value).identity
        assertEquals(created.identity.signing.publicKey, back.signing.publicKey, "same identity, new protection")
    }

    @Test fun thePassphraseCannotBeChangedWhileLockedOrWithNothingSaved() = runTest {
        val vault = vault()
        assertEquals(ChangePassphraseResult.NotUnlocked, vault.changePassphrase(PASSPHRASE, "a brand new passphrase"))
        vault.create(PASSPHRASE)
        vault.lock()
        assertEquals(ChangePassphraseResult.NotUnlocked, vault.changePassphrase(PASSPHRASE, "a brand new passphrase"))
    }

    @Test fun theChosenIdleTimeIsRememberedAndUsed() = runTest {
        val store = InMemoryKeyStore()
        val first = vault(store)
        first.create(PASSPHRASE)
        assertEquals(1, first.autoLockMinutes, "the test vault was built with a one minute rule")
        first.setAutoLockMinutes(5)
        assertEquals(5, first.autoLockMinutes)
        advanceTimeBy(4 * 60_000L)
        assertIs<VaultState.Unlocked>(first.state.value)
        advanceTimeBy(61_000L)
        assertIs<VaultState.Locked>(first.state.value)
        // A new session in the same browser picks the saved choice up.
        val second = vault(store)
        assertEquals(5, second.autoLockMinutes)
        second.setAutoLockMinutes(0)
        assertEquals(0, second.autoLockMinutes)
        assertEquals(0, vault(store).autoLockMinutes)
    }

    @Test fun zeroMinutesMeansUntilTheTabCloses() = runTest {
        val vault = vault()
        vault.create(PASSPHRASE)
        vault.setAutoLockMinutes(0)
        advanceTimeBy(100 * IDLE)
        assertIs<VaultState.Unlocked>(vault.state.value)
    }

    // ---- passkeys (ADR 0063) ----

    /** An authenticator that gives a fixed secret per credential and salt, can be told to cancel, and counts its calls. */
    private class FakePasskeys(val crypto: Crypto, var cancel: Boolean = false, override var available: Boolean = true, var wrongSecret: Boolean = false) : PasskeyProvider {
        var creates = 0
        override suspend fun create(device: circles.event.DeviceId): PasskeyCreated? {
            if (cancel) return null
            creates++
            return PasskeyCreated(crypto.randomBytes(16), crypto.randomBytes(PASSKEY_SALT_BYTES))
        }
        override suspend fun secret(credential: circles.encoding.ByteString, salt: circles.encoding.ByteString): circles.encoding.ByteString? {
            if (cancel) return null
            val base = crypto.hash256(credential.toByteArray() + salt.toByteArray())
            return if (wrongSecret) crypto.hash256(base.toByteArray()) else base
        }
    }

    @Test fun aPasskeyUnlocksTheSameIdentityAndThePassphraseStillWorks() = runTest {
        val crypto = newCrypto()
        val store = InMemoryKeyStore()
        val vault = vault(store)
        val created = assertIs<CreateResult.Created>(vault.create(PASSPHRASE))
        val passkeys = FakePasskeys(crypto)
        assertEquals(false, vault.hasPasskey())
        assertEquals(PasskeyResult.Done, vault.enablePasskey(passkeys))
        assertTrue(vault.hasPasskey())
        val saved = assertNotNull(store.load()).passkey!!
        assertTrue(created.identity.seed!!.toBase64Url() !in saved.toString(), "the seed is not in the record")
        vault.lock()
        assertEquals(UnlockResult.Unlocked, vault.unlockWithPasskey(passkeys))
        assertEquals(created.identity.signing.publicKey, assertIs<VaultState.Unlocked>(vault.state.value).identity.signing.publicKey)
        vault.lock()
        assertEquals(UnlockResult.Unlocked, vault.unlock(PASSPHRASE))
    }

    @Test fun aWrongSecretACancelAndALockedVaultChangeNothing() = runTest {
        val crypto = newCrypto()
        val store = InMemoryKeyStore()
        val vault = vault(store)
        vault.create(PASSPHRASE)
        val passkeys = FakePasskeys(crypto)
        vault.lock()
        assertEquals(PasskeyResult.NotUnlocked, vault.enablePasskey(passkeys), "needs the passphrase first")
        assertEquals(UnlockResult.Unlocked, vault.unlock(PASSPHRASE))
        passkeys.cancel = true
        assertEquals(PasskeyResult.Unavailable, vault.enablePasskey(passkeys))
        assertNull(assertNotNull(store.load()).passkey)
        passkeys.cancel = false
        assertEquals(PasskeyResult.Done, vault.enablePasskey(passkeys))
        vault.lock()
        passkeys.cancel = true
        assertEquals(UnlockResult.WrongPassphrase, vault.unlockWithPasskey(passkeys), "cancelled: stays locked")
        assertIs<VaultState.Locked>(vault.state.value)
        passkeys.cancel = false; passkeys.wrongSecret = true
        assertEquals(UnlockResult.WrongPassphrase, vault.unlockWithPasskey(passkeys), "another secret opens nothing")
        assertIs<VaultState.Locked>(vault.state.value)
    }

    @Test fun turningThePasskeyOffAndALackOfSupportAreHandled() = runTest {
        val crypto = newCrypto()
        val store = InMemoryKeyStore()
        val vault = vault(store)
        vault.create(PASSPHRASE)
        val none = FakePasskeys(crypto, available = false)
        assertEquals(PasskeyResult.Unavailable, vault.enablePasskey(none))
        val passkeys = FakePasskeys(crypto)
        vault.enablePasskey(passkeys)
        vault.disablePasskey()
        assertEquals(false, vault.hasPasskey())
        vault.lock()
        assertIs<UnlockResult.Unsupported>(vault.unlockWithPasskey(passkeys))
        assertEquals(UnlockResult.Unlocked, vault.unlock(PASSPHRASE))
    }

    @Test fun aPasskeyRecordFromAnotherDeviceOrAlteredDoesNotOpen() = runTest {
        val crypto = newCrypto(); val cipher = newCipher()
        val vault = vault()
        val created = assertIs<CreateResult.Created>(vault.create(PASSPHRASE))
        val made = PasskeyCreated(crypto.randomBytes(16), crypto.randomBytes(PASSKEY_SALT_BYTES))
        val secret = crypto.randomBytes(32)
        val wrap = assertNotNull(PasskeyWrapping.seal(crypto, cipher, secret, created.identity.seed!!, created.identity.device, made))
        assertEquals(created.identity.seed, PasskeyWrapping.open(crypto, cipher, secret, wrap, created.identity.device))
        assertNull(PasskeyWrapping.open(crypto, cipher, secret, wrap, circles.event.newDeviceId(crypto)), "bound to its device")
        assertNull(PasskeyWrapping.open(crypto, cipher, crypto.randomBytes(32), wrap, created.identity.device), "wrong secret")
        assertNull(PasskeyWrapping.open(crypto, cipher, secret, wrap.copy(credential = crypto.randomBytes(16).toBase64Url()), created.identity.device), "bound to its credential")
        assertNull(PasskeyWrapping.open(crypto, cipher, crypto.randomBytes(16), wrap, created.identity.device), "a secret of the wrong length")
    }
}
