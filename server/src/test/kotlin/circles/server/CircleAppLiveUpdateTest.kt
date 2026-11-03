package circles.server

import circles.app.AppModel
import circles.app.CircleApp
import circles.app.HomeData
import circles.crypto.JvmCrypto
import circles.keys.Argon2Params
import circles.keys.AutoLock
import circles.keys.InMemoryKeyStore
import circles.keys.JvmPasswordHasher
import circles.keys.KeyVault
import circles.policy.ItemClass
import circles.server.support.LiveServer
import circles.server.support.SocketTransport
import circles.server.support.TestServer
import circles.server.support.testCipher
import circles.sync.HttpReply
import circles.sync.HttpSyncApi
import circles.sync.HttpTransport
import circles.sync.InMemoryReplica
import circles.sync.StreamPiece
import circles.sync.StreamTransport
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val PASSPHRASE = "correct horse battery staple"
private const val WAIT_MILLIS = 15_000L

/** The event stream over a real socket, as a browser would read it. */
private class SocketStreamTransport(private val baseUrl: String) : StreamTransport {
    private val client = HttpClient.newHttpClient()

    override fun open(path: String, token: String): Flow<StreamPiece> = flow {
        val request = HttpRequest.newBuilder(URI.create(baseUrl + path)).header("Authorization", "Bearer $token").build()
        val response = try {
            runInterruptible { client.send(request, HttpResponse.BodyHandlers.ofInputStream()) }
        } catch (_: java.io.IOException) {
            return@flow
        }
        emit(StreamPiece.Opened(response.statusCode()))
        if (response.statusCode() != 200) return@flow
        response.body().use { input ->
            val buffer = ByteArray(1_024)
            while (true) {
                val count = try { runInterruptible { input.read(buffer) } } catch (_: java.io.IOException) { -1 }
                if (count < 0) break
                emit(StreamPiece.Text(String(buffer, 0, count, Charsets.UTF_8)))
            }
        }
    }.flowOn(Dispatchers.IO)
}

/**
 * Real timers, a real socket and the real event stream: when Alice adds an item, Bea's screen data changes by itself,
 * with Bea's code never asking to sync. Everything runs on one thread so the in-memory stores need no locking.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CircleAppLiveUpdateTest {
    private fun home(app: CircleApp): HomeData = assertIs<AppModel.Open>(app.model.value).home

    private suspend fun until(what: String, check: () -> Boolean) {
        try {
            withTimeout(WAIT_MILLIS) { while (!check()) delay(50) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for $what")
        }
    }

    @Test
    fun anItemAliceAddsAppearsForBeaWithoutBeaSyncing() {
        LiveServer(TestServer()).use { live ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
            try {
                runBlocking(scope.coroutineContext) {
                    val crypto = JvmCrypto()
                    val transport = SocketTransport(live.baseUrl)
                    val http = HttpTransport { method, path, body, token ->
                        transport.send(method, path, body, token).let { HttpReply(it.status, it.body, it.retryAfter) }
                    }
                    fun browser(): CircleApp {
                        val store = InMemoryKeyStore()
                        val vault = KeyVault(scope, crypto, testCipher, JvmPasswordHasher(), store, AutoLock.UntilTabCloses, Argon2Params(1_024, 1, 1))
                        val replica = InMemoryReplica { it.eventId(crypto) }
                        return CircleApp(
                            scope, crypto, testCipher, vault, store, replica, { identity -> HttpSyncApi(http, crypto, identity) },
                            { 20_759 }, SocketStreamTransport(live.baseUrl), { 0.0 },
                        )
                    }
                    val alice = browser().also { it.open() }
                    alice.startCircle(PASSPHRASE)
                    until("alice's circle to be created") { home(alice).changesWaiting == 0 && home(alice).isSteward }
                    val link = assertNotNull(alice.makeInvite())
                    until("the invite to reach the server") { home(alice).changesWaiting == 0 } // a join before that would be refused
                    val bea = browser().also { it.open() }
                    bea.join(link, PASSPHRASE)
                    until("alice to see bea waiting") { alice.syncNow(); home(alice).waiting.isNotEmpty() }
                    alice.admit(home(alice).waiting.single().device)
                    until("bea to be let in") { bea.syncNow(); home(bea).canRead }
                    until("both streams to open") { alice.isLive() && bea.isLive() }

                    // From here on nobody asks Bea to sync: the hint on her stream does it.
                    alice.addItem("Ladder", ItemClass.EVERYDAY, "Garage at the back")
                    until("the ladder to reach bea by hint") { home(bea).items.any { it.name == "Ladder" } }
                    assertEquals(null, home(bea).items.single().description, "she sees the item, not its private details")
                    assertTrue(bea.isLive())

                    // And back again, the other way.
                    val ladder = home(bea).items.single().item
                    bea.requestLoan(ladder, 20_760, 20_762, "for the fence")
                    until("the request to reach alice by hint") { home(alice).loans.any { it.note == "for the fence" } }
                    alice.lock()
                    bea.lock()
                }
            } finally {
                scope.cancel()
            }
        }
    }
}
