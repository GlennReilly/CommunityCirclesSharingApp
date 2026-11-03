package circles.keys

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What every [KeyStore] must do, so the IndexedDB one runs the same tests. */
abstract class KeyStoreContract {
    protected abstract suspend fun newStore(): KeyStore

    private fun identity(device: String = "d".repeat(32), sealed: String = "AAAA") =
        StoredIdentity("m".repeat(32), device, WrappedSeed(1, SEED_WRAP_METHOD_PASSPHRASE, 1_024, 1, 1, "salt", sealed))

    @Test fun anEmptyStoreHasNoIdentity() = runTest { assertNull(newStore().load()) }

    @Test fun savedIdentitiesComeBackUnchanged() = runTest {
        val store = newStore()
        store.save(identity())
        assertEquals(identity(), store.load())
    }

    @Test fun savingAgainReplacesTheIdentity() = runTest {
        val store = newStore()
        store.save(identity())
        store.save(identity(device = "e".repeat(32), sealed = "BBBB"))
        assertEquals(identity(device = "e".repeat(32), sealed = "BBBB"), store.load())
    }

    @Test fun eraseForgetsEverything() = runTest {
        val store = newStore()
        store.save(identity())
        store.erase()
        assertNull(store.load())
        store.erase()
    }
}
