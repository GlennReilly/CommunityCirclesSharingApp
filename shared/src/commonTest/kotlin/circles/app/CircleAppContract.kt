package circles.app

import circles.api.PullResponse
import circles.api.PushResult
import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.event.Envelope
import circles.keys.Argon2Params
import circles.keys.AutoLock
import circles.keys.InMemoryKeyStore
import circles.keys.KeyVault
import circles.keys.PasswordHasher
import circles.keys.UnlockResult
import circles.policy.CircleId
import circles.sync.ApiOutcome
import circles.sync.InMemoryReplica
import circles.sync.SyncApi
import circles.sync.SyncState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val PASSPHRASE = "correct horse battery staple"
private const val IDLE = 60_000L
private const val DAY = 20_000

/** The app's timers and locking on virtual time, with a server that is never reachable. */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class CircleAppContract {
    protected abstract suspend fun newCrypto(): Crypto
    protected abstract suspend fun newCipher(): Cipher
    protected abstract fun newHasher(): PasswordHasher

    private class Unreachable : SyncApi {
        var pushes = 0
        override suspend fun push(circle: CircleId, events: List<Envelope>): ApiOutcome<List<PushResult>> {
            pushes++
            return ApiOutcome.Offline
        }

        override suspend fun pull(circle: CircleId, after: Long, limit: Int): ApiOutcome<PullResponse> = ApiOutcome.Offline
    }

    private suspend fun TestScope.fixture(): Triple<CircleApp, Unreachable, InMemoryKeyStore> {
        val crypto = newCrypto()
        val cipher = newCipher()
        val store = InMemoryKeyStore()
        val api = Unreachable()
        val vault = KeyVault(backgroundScope, crypto, cipher, newHasher(), store, AutoLock.AfterIdle(IDLE), Argon2Params(1_024, 1, 1))
        val app = CircleApp(backgroundScope, crypto, cipher, vault, store, InMemoryReplica { it.eventId(crypto) }, { api }, { DAY })
        app.open()
        return Triple(app, api, store)
    }

    @Test fun startingACircleSchedulesASyncAndReportsOfflineWithTheChangesWaiting() = runTest {
        val (app, api, _) = fixture()
        app.startCircle(PASSPHRASE)
        runCurrent()
        advanceTimeBy(1_000)
        assertTrue(api.pushes >= 1, "the scheduler pushed without being asked")
        assertEquals(SyncState.Offline(2), app.sync.value)
        assertEquals(2, assertIs<AppModel.Open>(app.model.value).home.changesWaiting)
    }

    @Test fun theIdleLockEndsTheSessionAndShowsTheLockScreen() = runTest {
        val (app, api, store) = fixture()
        app.startCircle(PASSPHRASE)
        runCurrent()
        advanceTimeBy(IDLE + 1)
        runCurrent()
        val locked = assertIs<AppModel.Locked>(app.model.value)
        assertEquals(store.load()?.circle, locked.circle?.v)
        val pushesAtLock = api.pushes
        advanceTimeBy(60 * IDLE)
        assertEquals(pushesAtLock, api.pushes, "no syncing while locked")
        assertEquals(null, app.syncOnce())
        assertEquals(null, app.makeInvite())
    }

    @Test fun activityKeepsTheSessionOpen() = runTest {
        val (app, _, _) = fixture()
        app.startCircle(PASSPHRASE)
        advanceTimeBy(IDLE - 1)
        app.touch()
        advanceTimeBy(IDLE - 1)
        runCurrent()
        assertIs<AppModel.Open>(app.model.value)
    }

    @Test fun unlockingAfterTheLockRestartsTheSession() = runTest {
        val (app, api, _) = fixture()
        app.startCircle(PASSPHRASE)
        advanceTimeBy(IDLE + 1)
        runCurrent()
        assertEquals(UnlockResult.WrongPassphrase, app.unlock("definitely not it"))
        assertIs<AppModel.Locked>(app.model.value)
        val before = api.pushes
        assertEquals(UnlockResult.Unlocked, app.unlock(PASSPHRASE))
        runCurrent()
        advanceTimeBy(1_000)
        assertIs<AppModel.Open>(app.model.value)
        assertTrue(api.pushes > before, "opening synced again")
    }

    @Test fun manualLockingGoesStraightToTheLockScreen() = runTest {
        val (app, _, _) = fixture()
        app.startCircle(PASSPHRASE)
        app.lock()
        runCurrent()
        assertIs<AppModel.Locked>(app.model.value)
    }
}
