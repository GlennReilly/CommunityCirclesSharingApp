package circles.server.backup

import circles.api.AdminAuth
import circles.api.ImportSummary
import circles.api.ApiJson
import circles.crypto.Crypto
import circles.event.DeviceIdentity
import circles.event.EventAuthor
import circles.event.toDto
import circles.export.CircleExport
import circles.export.ExportVerifier
import circles.export.MapExportSource
import circles.policy.CircleId
import circles.server.support.CapturedLog
import circles.server.support.RawResponse
import circles.server.support.HandoverFixture
import circles.server.support.LiveServer
import circles.server.support.MutableClock
import circles.server.support.TestServer
import circles.server.support.testCipher
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Signed steward export and import over HTTP (ADR 0033), against real servers. */
class AdminApiTest {
    private val toClose = mutableListOf<AutoCloseable>()
    private val tmp: Path = Files.createTempDirectory("circles-admin")

    @AfterTest
    fun cleanUp() {
        toClose.reversed().forEach { it.close() }
        tmp.toFile().deleteRecursively()
    }

    private fun live(server: TestServer = TestServer()) = LiveServer(server).also { toClose += it }

    private class Hosted(val live: LiveServer, val fixture: HandoverFixture, val count: Int)

    private fun hosted(server: TestServer = TestServer()): Hosted {
        val l = live(server)
        val fixture = HandoverFixture(l.server.services, l.server.crypto)
        return Hosted(l, fixture, fixture.build())
    }

    private fun nowSeconds(live: LiveServer) = live.server.clock.millis() / 1000

    private fun sign(live: LiveServer, who: DeviceIdentity, method: String, path: String, body: ByteArray = ByteArray(0), ts: Long = nowSeconds(live)) =
        AdminAuth.sign(live.server.crypto, who.device, who.signing.secretSeed, method, path, ts, body)

    private fun exportPath(circle: CircleId) = "/api/v1/admin/export/${circle.v}"

    private val importPath = "/api/v1/admin/import"

    private fun export(h: Hosted, who: DeviceIdentity): RawResponse = h.live.request("GET", exportPath(h.fixture.circle), authorization = sign(h.live, who, "GET", exportPath(h.fixture.circle)))

    /** The operator's side: issue a token for [circle] on [live], as `import-token` does; returns what the steward gets. */
    private fun issueToken(live: LiveServer, circle: CircleId, hours: Long = 24): String {
        val raw = live.server.crypto.randomBytes(32)
        live.server.services.store.transaction { it.putImportToken(live.server.crypto.hash256(raw.toByteArray()), circle, live.server.clock.millis() + hours * 3_600_000L) }
        return raw.toBase64Url()
    }

    private fun import(live: LiveServer, who: DeviceIdentity, zip: ByteArray, header: String = sign(live, who, "POST", importPath, zip), token: String? = null) =
        live.request("POST", importPath, body = zip, authorization = header, importToken = token)

    private fun zipOf(entries: Map<String, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return out.toByteArray()
    }

    private fun entriesOfZip(bytes: ByteArray): Map<String, ByteArray> {
        val file = Files.createTempFile(tmp, "in", ".zip").also { Files.write(it, bytes) }
        return ZipExportSource(file).use { s -> s.entryNames.associateWith { s.read(it, Long.MAX_VALUE)!! } }
    }

    private fun RawResponse.text() = body.decodeToString()

    @Test
    fun aStewardExportsOverHttpAndAnotherStewardImportsItOnANewHost() {
        val a = hosted()
        val exported = export(a, a.fixture.members[0].identity)
        assertEquals(200, exported.status)
        assertEquals("application/zip", exported.headers["content-type"])
        val report = ExportVerifier.verify(MapExportSource(entriesOfZip(exported.body)), a.live.server.crypto)
        assertEquals(emptyList(), report.problems)

        val b = live()
        val imported = import(b, a.fixture.members[1].identity, exported.body, token = issueToken(b, a.fixture.circle)) // a different steward does the import
        assertEquals(200, imported.status, imported.text())
        val summary = ApiJson.decodeFromString(ImportSummary.serializer(), imported.text())
        assertEquals(ImportSummary(a.count, 0, 1, 0), summary)
        assertEquals(a.live.server.services.sync.pull(a.fixture.circle, 0, 1_000).events, b.server.services.sync.pull(a.fixture.circle, 0, 1_000).events)
        assertContentEquals(a.fixture.blob, b.server.services.blobs!!.get(a.fixture.circle, a.live.server.crypto.hash256(a.fixture.blob).toHex()))
        val again = import(b, a.fixture.members[0].identity, exported.body)
        assertEquals(ImportSummary(0, a.count, 0, 1), ApiJson.decodeFromString(ImportSummary.serializer(), again.text()), "repeating is harmless")
        assertTrue(a.live.server.rows("SELECT 1 FROM sessions").isEmpty(), "no session was involved")
    }

    @Test
    fun onlyAStewardOfTheCircleMayExport() {
        val a = hosted()
        assertEquals(403, export(a, a.fixture.members[3].identity).status, "a member who is not a steward")
        assertEquals(200, export(a, a.fixture.members[2].identity).status, "a steward added later")
        val stranger = DeviceIdentity.generate(a.live.server.crypto, testCipher)
        assertEquals(401, export(a, stranger).status, "a device this host has never heard of")
        val other = hosted()
        val crossed = a.live.request("GET", exportPath(a.fixture.circle), authorization = sign(a.live, other.fixture.members[0].identity, "GET", exportPath(a.fixture.circle)))
        assertEquals(401, crossed.status, "a steward of a circle on another host is unknown here")
    }

    @Test
    fun aRevokedStewardDeviceCannotExport() {
        val a = hosted()
        // A steward's device can only be revoked by that steward (ADR 0044), so this steward revokes their own.
        val steward = a.fixture.members[2]
        val revoke = steward.deviceRevoke(a.fixture.circle, steward.identity.device, circles.server.support.TODAY)
        val r = a.live.server.services.sync.submit(listOf(revoke.toDto()), circles.server.domain.Entrance.Push(a.fixture.circle)).single()
        assertEquals("accepted", r.status.name.lowercase())
        assertEquals(403, export(a, a.fixture.members[2].identity).status)
    }

    @Test
    fun everyKindOfBadSignatureGetsTheSameBareUnauthorized() {
        val a = hosted()
        val path = exportPath(a.fixture.circle)
        val founder = a.fixture.members[0].identity
        val clock = a.live.server.clock
        val good = sign(a.live, founder, "GET", path)
        val bad = listOf(
            null,
            "Bearer abc",
            "Circles-Signature device=nope",
            good.replace(Regex("sig=.+$"), "sig=" + circles.encoding.ByteString.of(ByteArray(64)).toBase64Url()), // wrong signature
            sign(a.live, founder, "GET", path, ts = nowSeconds(a.live) - 301), // stale
            sign(a.live, founder, "GET", path, ts = nowSeconds(a.live) + 301), // from the future
            sign(a.live, founder, "POST", path), // signed for another method
            sign(a.live, founder, "GET", exportPath(CircleId("ab".repeat(16)))), // signed for another path
            AdminAuth.sign(a.live.server.crypto, founder.device, a.fixture.members[3].identity.signing.secretSeed, "GET", path, nowSeconds(a.live)), // wrong key
        )
        val answers = bad.map { a.live.request("GET", path, authorization = it) }
        assertEquals(setOf(401), answers.map { it.status }.toSet(), answers.map { it.status }.toString())
        assertEquals(1, answers.map { it.text() }.toSet().size, "the body does not say which check failed")
        assertEquals(200, a.live.request("GET", path, authorization = good).status, "and the untouched header does work")
        assertNotNull(clock)
    }

    @Test
    fun aCapturedRequestCannotBeReplayedButANewOneWorks() {
        val a = hosted()
        val path = exportPath(a.fixture.circle)
        val header = sign(a.live, a.fixture.members[0].identity, "GET", path)
        assertEquals(200, a.live.request("GET", path, authorization = header).status)
        assertEquals(401, a.live.request("GET", path, authorization = header).status, "the same header twice")
        assertEquals(200, export(a, a.fixture.members[0].identity).status, "a fresh nonce is fine")
    }

    @Test
    fun theNonceMemoryForgetsAfterTenMinutesAndStaysBounded() {
        val clock = MutableClock()
        val memory = NonceMemory(clock)
        assertTrue(memory.firstUse("n1"))
        assertFalse(memory.firstUse("n1"))
        clock.advanceSeconds(NONCE_MEMORY_MILLIS / 1000 - 1)
        assertFalse(memory.firstUse("n1"), "still remembered just under ten minutes")
        clock.advanceSeconds(2)
        assertTrue(memory.firstUse("n1"), "forgotten after ten minutes")
    }

    @Test
    fun importNeedsASignatureOverTheWholeBodyFromAStewardNamedInTheFile() {
        val a = hosted()
        val file = export(a, a.fixture.members[0].identity).body
        val b = live()
        val steward = a.fixture.members[0].identity
        val member = a.fixture.members[3].identity
        val token = issueToken(b, a.fixture.circle)

        assertEquals(403, import(b, member, file, token = token).status, "a member who is not a steward in the file")
        assertEquals(401, import(b, DeviceIdentity.generate(b.server.crypto, testCipher), file, token = token).status, "a device the file does not contain")
        val other = file.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }
        assertEquals(401, import(b, steward, other, header = sign(b, steward, "POST", importPath, file), token = token).status, "the body changed after signing")
        assertEquals(401, import(b, steward, file, header = sign(b, steward, "POST", "/api/v1/admin/export/${a.fixture.circle.v}", file), token = token).status, "signed for another path")
        assertEquals(401, import(b, steward, "not a zip".encodeToByteArray(), token = token).status, "not a zip")
        assertEquals(401, b.request("POST", importPath, body = file, importToken = token).status, "no signature at all")
        assertEquals(0, b.server.rows("SELECT 1 FROM events").size, "none of that imported anything")
        assertEquals(1, b.server.rows("SELECT 1 FROM import_tokens").size, "and none of it used the token up")
        assertEquals(200, import(b, steward, file, token = token).status)
    }

    @Test
    fun aFileThatDoesNotVerifyIsRefusedBeforeAnythingIsWritten() {
        val a = hosted()
        val crypto: Crypto = a.live.server.crypto
        val events = a.live.server.services.sync.pull(a.fixture.circle, 0, 1_000).events.map { it.envelope }.toMutableList()
        val forged = events.also { it[3] = it[3].copy(day = it[3].day + 1) } // a signature that no longer matches
        val built = CircleExport.build(crypto, a.fixture.circle, forged, emptyList())
        val zip = zipOf(built.entries(emptyMap()))
        val b = live()
        val result = import(b, a.fixture.members[0].identity, zip, token = issueToken(b, a.fixture.circle))
        assertEquals(422, result.status, result.text())
        assertTrue(result.text().contains("refused by the circle's rules"), result.text())
        assertEquals(0, b.server.rows("SELECT 1 FROM events").size)
        assertEquals(1, b.server.rows("SELECT 1 FROM import_tokens").size, "a refused file does not use the token up")
    }

    @Test
    fun aNewCircleNeedsTheOperatorsTokenEvenOnAnOpenHost() {
        val a = hosted()
        val file = export(a, a.fixture.members[0].identity).body
        val b = live()
        val none = import(b, a.fixture.members[0].identity, file)
        assertEquals(403, none.status)
        assertTrue(none.text().contains("import token"), none.text())
        assertEquals(0, b.server.rows("SELECT 1 FROM events").size)
        assertEquals(200, import(b, a.fixture.members[0].identity, file, token = issueToken(b, a.fixture.circle)).status)
    }

    @Test
    fun aClosedHostTakesANewCircleOnlyWithATokenAndThenAllowsStewardsToExtendItWithoutOne() {
        val a = hosted()
        val file = export(a, a.fixture.members[0].identity).body
        val closed = live(TestServer(allowCreate = false))
        assertEquals(403, import(closed, a.fixture.members[0].identity, file).status)
        assertEquals(0, closed.server.rows("SELECT 1 FROM events").size)
        val first = import(closed, a.fixture.members[0].identity, file, token = issueToken(closed, a.fixture.circle))
        assertEquals(200, first.status, first.text())
        val more = EventAuthor(a.live.server.crypto, a.fixture.founder.identity, testCipher, firstCounter = 1_000, keyRing = a.fixture.founder.keyRing)
        val extra = more.invite(a.fixture.circle, circles.server.support.newInviteKey(a.live.server.crypto), circles.server.support.TODAY + 5, 1, circles.server.support.TODAY)
        a.live.server.services.sync.submit(listOf(extra.toDto()), circles.server.domain.Entrance.Push(a.fixture.circle))
        val bigger = export(a, a.fixture.members[0].identity).body
        val extended = import(closed, a.fixture.members[0].identity, bigger) // no token: the circle is already here
        assertEquals(200, extended.status, extended.text())
        assertEquals(ImportSummary(1, a.count, 0, 1), ApiJson.decodeFromString(ImportSummary.serializer(), extended.text()))
    }

    @Test
    fun theOperatorCommandAndHttpAgreeOnWhoMayExtend() {
        val a = hosted()
        val file = export(a, a.fixture.members[0].identity).body
        val b = live()
        val operator = CircleImporter(b.server.services.sync, b.server.services.blobs!!, b.server.crypto)
        assertTrue(operator.import(ZipExportSource(Files.createTempFile(tmp, "seed", ".zip").also { Files.write(it, file) }).also { toClose += it }) is ImportResult.Done)
        assertEquals(200, import(b, a.fixture.members[0].identity, file).status, "a steward extends a circle the operator seeded, with no token")
    }

    @Test
    fun aTokenWorksOnceForOneCircleAndOnlyUntilItExpires() {
        val a = hosted()
        val file = export(a, a.fixture.members[0].identity).body
        val steward = a.fixture.members[0].identity

        val b = live()
        val other = issueToken(b, CircleId("ab".repeat(16)))
        assertEquals(403, import(b, steward, file, token = other).status, "a token for another circle")
        assertEquals(403, import(b, steward, file, token = "not-base64!!").status, "garbage")
        assertEquals(403, import(b, steward, file, token = circles.encoding.ByteString.of(ByteArray(32)).toBase64Url()).status, "a token nobody issued")
        assertEquals(1, b.server.rows("SELECT 1 FROM import_tokens").size, "the one token that exists was not used up by those refusals")

        val expired = live()
        val old = issueToken(expired, a.fixture.circle, hours = 1)
        expired.server.clock.advanceSeconds(2 * 3_600)
        assertEquals(403, import(expired, steward, file, token = old, header = sign(expired, steward, "POST", importPath, file)).status, "expired")

        val once = live()
        val token = issueToken(once, a.fixture.circle)
        assertEquals(200, import(once, steward, file, token = token).status)
        assertEquals(0, once.server.rows("SELECT 1 FROM import_tokens").size, "used up")
        val second = live()
        assertEquals(403, import(second, steward, file, token = token).status, "and it is no good on another host")
    }

    @Test
    fun theHostStoresOnlyAHashOfTheToken() {
        val b = live()
        val circle = CircleId("cd".repeat(16))
        val token = issueToken(b, circle)
        val stored = b.server.rows("SELECT hex(token_hash), circle_id FROM import_tokens").single()
        val raw = circles.encoding.ByteString.fromBase64Url(token)!!
        assertEquals(b.server.crypto.hash256(raw.toByteArray()).toHex().uppercase(), stored[0])
        assertEquals(circle.v, stored[1])
        assertFalse((stored[0] as String).contains(raw.toHex().uppercase()))
    }

    @Test
    fun anImportOverTheSizeCapIsRefused() {
        val a = hosted()
        val file = export(a, a.fixture.members[0].identity).body
        val small = live(TestServer(adminImportMaxBytes = 1_000))
        assertTrue(file.size > 1_000)
        assertEquals(413, import(small, a.fixture.members[0].identity, file, token = issueToken(small, a.fixture.circle)).status)
        assertEquals(0, small.server.rows("SELECT 1 FROM events").size)
    }

    @Test
    fun theAdminCallsAreRateLimitedPerClientAddress() {
        val a = hosted(TestServer(rateLimit = true))
        val statuses = List(8) { a.live.request("GET", exportPath(a.fixture.circle), authorization = "Circles-Signature junk").status }
        assertEquals(401, statuses.first())
        assertTrue(statuses.any { it == 429 }, statuses.toString())
    }

    @Test
    fun theLogShowsNoDeviceCircleNonceOrSignature() {
        val log = CapturedLog()
        val a = hosted(TestServer(log = log))
        val header = sign(a.live, a.fixture.members[0].identity, "GET", exportPath(a.fixture.circle))
        a.live.request("GET", exportPath(a.fixture.circle), authorization = header)
        val b = live(TestServer(log = log))
        val file = export(a, a.fixture.members[0].identity).body
        val token = issueToken(b, a.fixture.circle)
        import(b, a.fixture.members[0].identity, file, token = token)
        val text = log.text()
        val credentials = AdminAuth.parse(header)!!
        listOf(token, credentials.nonce, credentials.sig.toBase64Url(), credentials.device.v, a.fixture.circle.v, a.fixture.members[0].identity.device.v).forEach {
            assertFalse(text.contains(it), "the log must not contain '$it'")
        }
        assertTrue(text.contains("admin_import"))
    }
}
