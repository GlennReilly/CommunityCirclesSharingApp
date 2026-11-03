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
}
