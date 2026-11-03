package circles.server.web

import circles.api.MAX_BLOB_BYTES
import circles.event.DeviceIdentity
import circles.event.newCircleId
import circles.policy.CircleId
import circles.server.domain.BYTES_PER_MIB
import circles.server.support.CapturedLog
import circles.server.support.LiveServer
import circles.server.support.Person
import circles.server.support.TODAY
import circles.server.support.TestServer
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** `PUT` and `GET /circles/{id}/blobs/{hash}` against a real server (ADR 0026). */
class BlobApiTest {
    private class Member(val live: LiveServer, val person: Person, val circle: CircleId, val token: String)

    private fun LiveServer.member(): Member {
        val person = Person(server.crypto)
        val circle = newCircleId(server.crypto)
        return runBlocking {
            api.createCircle(person.author.circleCreate(circle, TODAY))
            Member(this@member, person, circle, api.login(person.identity))
        }
    }

    private fun hashOf(live: LiveServer, bytes: ByteArray) = live.server.crypto.hash256(bytes).toHex()

    private fun blobPath(circle: CircleId, hash: String) = "/api/v1/circles/${circle.v}/blobs/$hash"

    private fun blob(size: Int, seed: Int = 1) = ByteArray(size) { (it * seed + seed).toByte() }

    @Test
    fun aBlobRoundTripsWithCachingHeaders() {
        LiveServer(TestServer()).use { live ->
            val m = live.member()
            val bytes = blob(5_000)
            val hash = hashOf(live, bytes)
            assertEquals(201, live.request("PUT", blobPath(m.circle, hash), m.token, bytes).status)
            assertEquals(200, live.request("PUT", blobPath(m.circle, hash), m.token, bytes).status, "the same blob again is not an error")
            val got = live.request("GET", blobPath(m.circle, hash), m.token)
            assertEquals(200, got.status)
            assertContentEquals(bytes, got.body)
            assertEquals("application/octet-stream", got.headers["content-type"])
            assertEquals("\"$hash\"", got.headers["etag"])
            assertTrue(got.headers.getValue("cache-control").contains("immutable"))
        }
    }

    @Test
    fun theBytesAreStoredUnderTheCircleAndNoTemporaryFileIsLeft() {
        LiveServer(TestServer()).use { live ->
            val m = live.member()
            val bytes = blob(100)
            val hash = hashOf(live, bytes)
            live.request("PUT", blobPath(m.circle, hash), m.token, bytes)
            val file = live.server.blobsDir.resolve(m.circle.v).resolve(hash.take(2)).resolve(hash)
            assertTrue(file.isRegularFile())
            assertContentEquals(bytes, Files.readAllBytes(file))
            assertEquals(listOf(hash), Files.list(file.parent).use { s -> s.map { it.fileName.toString() }.toList() })
            val row = live.server.rows("SELECT circle_id, hash, size FROM blobs").single()
            assertEquals(listOf(m.circle.v, hash, 100), row.mapIndexed { i, v -> if (i == 2) (v as Number).toInt() else v })
        }
    }

    @Test
    fun badHashesMismatchesAndOversizedBodiesAreRefused() {
        LiveServer(TestServer(blobQuotaBytes = 8L * BYTES_PER_MIB)).use { live ->
            val m = live.member()
            val bytes = blob(300)
            assertEquals(400, live.request("PUT", blobPath(m.circle, "NOT-A-HASH"), m.token, bytes).status)
            assertEquals(400, live.request("PUT", blobPath(m.circle, hashOf(live, bytes).uppercase()), m.token, bytes).status)
            assertEquals(422, live.request("PUT", blobPath(m.circle, hashOf(live, blob(301))), m.token, bytes).status)
            val tooBig = blob(MAX_BLOB_BYTES + 1)
            assertEquals(413, live.request("PUT", blobPath(m.circle, hashOf(live, tooBig)), m.token, tooBig).status)
            val biggest = blob(MAX_BLOB_BYTES)
            assertEquals(201, live.request("PUT", blobPath(m.circle, hashOf(live, biggest)), m.token, biggest).status)
            assertEquals(0, live.server.rows("SELECT 1 FROM blobs WHERE size = $MAX_BLOB_BYTES + 1").size)
        }
    }

    @Test
    fun aRefusedUploadLeavesNothingBehind() {
        LiveServer(TestServer()).use { live ->
            val m = live.member()
            live.request("PUT", blobPath(m.circle, hashOf(live, blob(10))), m.token, blob(11))
            assertEquals(0, live.server.rows("SELECT 1 FROM blobs").size)
            assertFalse(live.server.blobsDir.resolve(m.circle.v).exists())
        }
    }

    @Test
    fun theCircleQuotaStopsUploadsButNotReads() {
        LiveServer(TestServer(blobQuotaBytes = 10_000)).use { live ->
            val m = live.member()
            val first = blob(6_000, 3)
            val second = blob(6_000, 5)
            assertEquals(201, live.request("PUT", blobPath(m.circle, hashOf(live, first)), m.token, first).status)
            assertEquals(507, live.request("PUT", blobPath(m.circle, hashOf(live, second)), m.token, second).status)
            assertEquals(200, live.request("PUT", blobPath(m.circle, hashOf(live, first)), m.token, first).status, "re-sending a stored blob costs nothing")
            assertContentEquals(first, live.request("GET", blobPath(m.circle, hashOf(live, first)), m.token).body)
            assertEquals(404, live.request("GET", blobPath(m.circle, hashOf(live, second)), m.token).status)
        }
    }

    @Test
    fun onlyDevicesOfTheCircleCanUseItsBlobsAndCirclesDoNotShareThem() {
        LiveServer(TestServer()).use { live ->
            val a = live.member()
            val b = live.member()
            val bytes = blob(500)
            val hash = hashOf(live, bytes)
            assertEquals(401, live.request("PUT", blobPath(a.circle, hash), null, bytes).status)
            assertEquals(401, live.request("GET", blobPath(a.circle, hash), "not-a-token").status)
            assertEquals(403, live.request("PUT", blobPath(a.circle, hash), b.token, bytes).status)
            assertEquals(404, live.request("GET", "/api/v1/circles/not-a-circle/blobs/$hash", a.token).status)
            live.request("PUT", blobPath(a.circle, hash), a.token, bytes)
            assertEquals(403, live.request("GET", blobPath(a.circle, hash), b.token).status)
            assertEquals(201, live.request("PUT", blobPath(b.circle, hash), b.token, bytes).status, "the same bytes are stored separately in another circle")
            assertTrue(live.server.blobsDir.resolve(a.circle.v).resolve(hash.take(2)).resolve(hash).exists())
            assertTrue(live.server.blobsDir.resolve(b.circle.v).resolve(hash.take(2)).resolve(hash).exists())
        }
    }

    @Test
    fun aMissingBlobIsNotFoundAndAMalformedHashToo() {
        LiveServer(TestServer()).use { live ->
            val m = live.member()
            assertEquals(404, live.request("GET", blobPath(m.circle, "ab".repeat(32)), m.token).status)
            // A path with `..` is refused outright (400) before it can reach any file.
            assertEquals(400, live.request("GET", blobPath(m.circle, "../../etc/passwd"), m.token).status)
        }
    }

    @Test
    fun uploadsAreRateLimitedPerDevice() {
        LiveServer(TestServer(rateLimit = true)).use { live ->
            val m = live.member()
            val bytes = blob(50)
            val hash = hashOf(live, bytes)
            val statuses = List(32) { live.request("PUT", blobPath(m.circle, hash), m.token, bytes).status }
            assertEquals(201, statuses.first())
            assertTrue(statuses.count { it == 429 } >= 1, statuses.toString())
        }
    }

    @Test
    fun theLogNeverShowsABlobHashOrItsCircle() {
        val log = CapturedLog()
        LiveServer(TestServer(log = log)).use { live ->
            val m = live.member()
            val bytes = blob(80)
            val hash = hashOf(live, bytes)
            live.request("PUT", blobPath(m.circle, hash), m.token, bytes)
            live.request("GET", blobPath(m.circle, hash), m.token)
            val text = log.text()
            assertFalse(text.contains(hash), "the log must not contain the blob id")
            assertFalse(text.contains(m.circle.v))
            assertFalse(text.contains(m.token))
        }
    }
}
