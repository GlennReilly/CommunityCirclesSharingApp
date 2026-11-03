package circles.server

import circles.app.AppModel
import circles.app.CircleApp
import circles.app.HomeData
import circles.app.InviteLink
import circles.app.ItemResult
import circles.app.MAX_ITEM_DESCRIPTION_CHARS
import circles.app.StartResult
import circles.crypto.JvmCrypto
import circles.keys.Argon2Params
import circles.keys.AutoLock
import circles.keys.InMemoryKeyStore
import circles.keys.JvmPasswordHasher
import circles.keys.KeyStore
import circles.keys.KeyVault
import circles.keys.UnlockResult
import circles.server.support.TODAY
import circles.server.support.apiTest
import circles.server.support.testCipher
import circles.sync.HttpReply
import circles.sync.HttpSyncApi
import circles.sync.HttpTransport
import circles.sync.InMemoryReplica
import circles.sync.SyncState
import circles.policy.ItemClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PASSPHRASE = "correct horse battery staple"
private val FAST = Argon2Params(1_024, 1, 1)

/**
 * Two browsers' worth of `CircleApp` against the real server rules: start a circle, make an invite, join with the link,
 * be admitted by the steward, lock and unlock, and watch the home data follow. The scheduler is not run (the scope is
 * dead); the tests drive sync with `syncOnce()` so the steps are deterministic.
 */
class CircleAppEndToEndTest {
    private val deadScope = CoroutineScope(Job().also { it.cancel() })

    private class Browser(val app: CircleApp, val store: KeyStore, val replica: InMemoryReplica)

    private fun browser(transport: HttpTransport, crypto: JvmCrypto = JvmCrypto(), store: KeyStore = InMemoryKeyStore(), replica: InMemoryReplica? = null): Browser {
        val hasher = JvmPasswordHasher()
        val vault = KeyVault(deadScope, crypto, testCipher, hasher, store, AutoLock.UntilTabCloses, FAST)
        val local = replica ?: InMemoryReplica { it.eventId(crypto) }
        val app = CircleApp(deadScope, crypto, testCipher, vault, store, local, { identity -> HttpSyncApi(transport, crypto, identity) }, { TODAY })
        return Browser(app, store, local)
    }

    private fun home(app: CircleApp): HomeData = assertIs<AppModel.Open>(app.model.value).home

    @Test
    fun aCircleIsStartedJoinedAdmittedAndLocked() = apiTest {
        val transport = HttpTransport { method, path, body, token ->
            api.raw(method, path, body, token).let { HttpReply(it.status, it.body, it.retryAfter) }
        }
        val crypto = JvmCrypto()

        // 1. A fresh browser has nothing; a short passphrase is refused and creates nothing.
        val alice = browser(transport, crypto)
        alice.app.open()
        assertEquals(AppModel.NoIdentity, alice.app.model.value)
        assertEquals(StartResult.PassphraseTooShort(12), alice.app.startCircle("short"))
        assertNull(alice.store.load())

        // 2. Alice starts a circle; her first sync sends circle.create and the first key.
        val started = assertIs<StartResult.Started>(alice.app.startCircle(PASSPHRASE))
        assertEquals(2, home(alice.app).changesWaiting)
        val first = assertNotNull(alice.app.syncOnce())
        assertEquals(SyncState.UpToDate, first.state)
        val aliceHome = home(alice.app)
        assertTrue(aliceHome.isSteward)
        assertTrue(aliceHome.canRead)
        assertEquals(1, aliceHome.members)
        assertEquals(0, aliceHome.changesWaiting)
        assertEquals(started.circle, aliceHome.circle)

        // 3. Alice makes an invite and syncs; the link round-trips as text.
        val link = assertNotNull(alice.app.makeInvite())
        alice.app.syncOnce()
        val text = link.format("https://circles.example")
        val parsed = assertNotNull(InviteLink.parse(text))

        // 4. Bea joins from the link in another browser; she is in, but cannot read yet.
        val bea = browser(transport, crypto)
        bea.app.open()
        assertIs<StartResult.Started>(bea.app.join(parsed, PASSPHRASE))
        bea.app.syncOnce()
        val beaHome = home(bea.app)
        assertFalse(beaHome.isSteward)
        assertFalse(beaHome.canRead)
        assertEquals(2, beaHome.members)

        // 5. Alice sees Bea waiting and admits her; Bea then reads.
        alice.app.syncOnce()
        val waiting = home(alice.app).waiting.single()
        assertEquals(beaHome.me, waiting.member)
        assertTrue(alice.app.admit(waiting.device))
        alice.app.syncOnce()
        assertEquals(emptyList(), home(alice.app).waiting)
        bea.app.syncOnce()
        assertTrue(home(bea.app).canRead)

        // 6. An invite link works once: a second person using the same link is refused by the server and shows as a refused change.
        val carol = browser(transport, crypto)
        carol.app.open()
        carol.app.join(parsed, PASSPHRASE)
        carol.app.syncOnce()
        assertEquals(1, home(carol.app).refusedChanges)

        // 7. Locking drops the session; a wrong passphrase stays locked; the right one brings the same home back.
        bea.app.lock()
        // The watcher runs in the dead scope, so lock the model by reopening from the stored identity as a new session would.
        val reopened = browser(transport, crypto, bea.store, bea.replica)
        reopened.app.open()
        assertIs<AppModel.Locked>(reopened.app.model.value)
        assertEquals(UnlockResult.WrongPassphrase, reopened.app.unlock("not the passphrase"))
        assertIs<AppModel.Locked>(reopened.app.model.value)
        assertEquals(UnlockResult.Unlocked, reopened.app.unlock(PASSPHRASE))
        val back = home(reopened.app)
        assertEquals(beaHome.me, back.me)
        assertTrue(back.canRead)

        // 8. Erasing leaves nothing behind.
        reopened.app.erase()
        assertEquals(AppModel.NoIdentity, reopened.app.model.value)
        assertNull(reopened.store.load())
    }

    @Test
    fun theDeviceCounterContinuesAfterARestart() = apiTest {
        val transport = HttpTransport { method, path, body, token ->
            api.raw(method, path, body, token).let { HttpReply(it.status, it.body, it.retryAfter) }
        }
        val crypto = JvmCrypto()
        val first = browser(transport, crypto)
        first.app.open()
        first.app.startCircle(PASSPHRASE)
        first.app.syncOnce()
        first.app.makeInvite()
        first.app.syncOnce()
        val before = first.replica.entries((home(first.app)).circle).maxOf { it.envelope.ctr }
        val second = browser(transport, crypto, first.store, first.replica)
        second.app.open()
        second.app.unlock(PASSPHRASE)
        second.app.makeInvite()
        val circle = home(second.app).circle
        val queued = second.replica.outbox(circle).single()
        assertEquals(before + 1, queued.ctr)
        val report = assertNotNull(second.app.syncOnce())
        assertEquals(SyncState.UpToDate, report.state)
        assertEquals(0, report.refused)
    }

    @Test
    fun itemsAreAddedEditedKeptPrivateAndRetired() = apiTest {
        val transport = HttpTransport { method, path, body, token ->
            api.raw(method, path, body, token).let { HttpReply(it.status, it.body, it.retryAfter) }
        }
        val crypto = JvmCrypto()
        val alice = browser(transport, crypto)
        alice.app.open(); alice.app.startCircle(PASSPHRASE); alice.app.syncOnce()
        val link = assertNotNull(alice.app.makeInvite()); alice.app.syncOnce()
        val bea = browser(transport, crypto)
        bea.app.open(); bea.app.join(link, PASSPHRASE); bea.app.syncOnce()

        // Before a steward lets her in, Bea cannot seal anything.
        assertEquals(ItemResult.NotLetInYet, bea.app.addItem("Ladder", ItemClass.EVERYDAY, "x"))

        alice.app.syncOnce()
        assertTrue(alice.app.admit(home(alice.app).waiting.single().device))
        alice.app.syncOnce(); bea.app.syncOnce()

        // Bad input is refused before anything is queued.
        assertEquals(ItemResult.BadName, alice.app.addItem("   ", ItemClass.EVERYDAY, ""))
        assertEquals(ItemResult.BadName, alice.app.addItem("x".repeat(81), ItemClass.EVERYDAY, ""))
        assertEquals(ItemResult.DescriptionTooLong(MAX_ITEM_DESCRIPTION_CHARS), alice.app.addItem("Ladder", ItemClass.EVERYDAY, "d".repeat(MAX_ITEM_DESCRIPTION_CHARS + 1)))
        assertEquals(0, home(alice.app).changesWaiting)

        // Alice adds a ladder; she sees her own description.
        val saved = assertIs<ItemResult.Saved>(alice.app.addItem("  Ladder  ", ItemClass.EVERYDAY, "Garage at the back, ring first"))
        assertEquals(2, home(alice.app).changesWaiting)
        val first = assertNotNull(alice.app.syncOnce())
        assertEquals(SyncState.UpToDate, first.state)
        assertEquals(0, first.refused)
        val mine = home(alice.app).items.single()
        assertEquals("Ladder", mine.name)
        assertEquals("Garage at the back, ring first", mine.description)
        assertTrue(mine.ownedByMe)

        // Bea sees the item but not the private description, and cannot change or retire it.
        bea.app.syncOnce()
        val theirs = home(bea.app).items.single()
        assertEquals("Ladder", theirs.name)
        assertEquals(ItemClass.EVERYDAY, theirs.itemClass)
        assertNull(theirs.description)
        assertFalse(theirs.ownedByMe)
        assertEquals(ItemResult.NotAllowed, bea.app.editItem(saved.item, "Mine now", ItemClass.EVERYDAY, ""))
        assertEquals(ItemResult.NotAllowed, bea.app.retireItem(saved.item))

        // Alice edits it; Bea sees the new name and kind, still not the description.
        assertIs<ItemResult.Saved>(alice.app.editItem(saved.item, "Long ladder", ItemClass.VALUABLE, "Side gate code is private"))
        assertEquals(0, assertNotNull(alice.app.syncOnce()).refused)
        assertEquals("Side gate code is private", home(alice.app).items.single().description)
        bea.app.syncOnce()
        assertEquals("Long ladder", home(bea.app).items.single().name)
        assertEquals(ItemClass.VALUABLE, home(bea.app).items.single().itemClass)
        assertNull(home(bea.app).items.single().description)

        // The server never saw the words: nothing readable is in the database.
        val dump = server.rows("SELECT public, encrypted FROM events").joinToString { row -> row.joinToString { (it as? ByteArray)?.decodeToString().orEmpty() } }
        assertFalse("Long ladder" in dump || "Garage" in dump || "gate code" in dump)

        // Retiring takes it off both lists.
        assertIs<ItemResult.Saved>(alice.app.retireItem(saved.item))
        alice.app.syncOnce(); bea.app.syncOnce()
        assertEquals(emptyList(), home(alice.app).items)
        assertEquals(emptyList(), home(bea.app).items)
        assertEquals(ItemResult.NotAllowed, alice.app.editItem(saved.item, "Back again", ItemClass.EVERYDAY, ""))
    }
}
