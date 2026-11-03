package circles.export

import circles.api.ApiJson
import circles.api.MAX_BLOB_BYTES
import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.encoding.CborMap
import circles.encoding.CborText
import circles.event.DeviceIdentity
import circles.event.Envelope
import circles.event.EnvelopeDto
import circles.event.EventAuthor
import circles.event.FIRST_EPOCH
import circles.event.InviteKey
import circles.event.ItemKeys
import circles.event.KeyRing
import circles.event.newCircleId
import circles.event.newDetailKeyId
import circles.event.newItemId
import circles.event.toDto
import circles.policy.CircleId
import circles.policy.ItemClass
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val DAY = 20_000

/** `circles-export/1` verification (ADR 0030), on every Crypto/Cipher pair. */
abstract class ExportVerifierContract {
    protected abstract suspend fun newCrypto(): Crypto
    protected abstract suspend fun newCipher(): Cipher

    private class World(val crypto: Crypto, val cipher: Cipher) {
        val circle: CircleId = newCircleId(crypto)
        val circleKey = KeyRing.newCircleKey(crypto)
        val founder = person().also { it.keyRing = KeyRing.EMPTY.with(FIRST_EPOCH, circleKey) }
        val bea = person()
        val log = mutableListOf<Envelope>()
        val blobA = ByteArray(300) { it.toByte() }
        val blobB = ByteArray(77) { (it * 3).toByte() }

        fun person() = EventAuthor(crypto, DeviceIdentity.generate(crypto, cipher), cipher)

        init {
            log += founder.circleCreate(circle, DAY)
            log += founder.keyWrap(circle, founder.identity.registration, FIRST_EPOCH, circleKey, DAY)
            val invite = InviteKey(crypto.generateSigningKeyPair())
            val inviteEvent = founder.invite(circle, invite, DAY + 30, 1, DAY, "welcome")
            log += inviteEvent
            log += bea.join(circle, inviteEvent.eventId(crypto), invite, DAY)
            log += founder.keyWrap(circle, bea.identity.registration, FIRST_EPOCH, circleKey, DAY)
            val item = newItemId(crypto)
            log += founder.itemUpsert(circle, DAY, item, "Ladder", ItemClass.ESSENTIAL, newDetailKeyId(crypto), ItemKeys.newKey(crypto), listOf(founder.identity.registration))
            log += founder.stewardAdd(circle, bea.identity.member, DAY)
        }

        fun refs() = listOf(ExportBlobRef(crypto.hash256(blobA).toHex(), blobA.size.toLong()), ExportBlobRef(crypto.hash256(blobB).toHex(), blobB.size.toLong()))

        fun blobBytes() = mapOf(refs()[0].hash to blobA, refs()[1].hash to blobB)

        fun export(events: List<Envelope> = log, refs: List<ExportBlobRef> = refs()): Map<String, ByteArray> =
            CircleExport.build(crypto, circle, events.map { it.toDto() }, refs).entries(blobBytes())

        fun verify(entries: Map<String, ByteArray>) = ExportVerifier.verify(MapExportSource(entries), crypto)

        fun rebuilt(entries: Map<String, ByteArray>, edit: (ExportManifest) -> ExportManifest): Map<String, ByteArray> {
            val manifest = ApiJson.decodeFromString(ExportManifest.serializer(), entries.getValue(MANIFEST_ENTRY).decodeToString())
            return entries + (MANIFEST_ENTRY to ApiJson.encodeToString(ExportManifest.serializer(), edit(manifest)).encodeToByteArray())
        }
    }

    private suspend fun world() = World(newCrypto(), newCipher())

    private fun List<String>.anyContains(text: String) = any { it.contains(text) }

    @Test
    fun aGoodExportVerifies() = runTest {
        val w = world()
        val report = w.verify(w.export())
        assertEquals(emptyList(), report.problems)
        assertTrue(report.ok)
        assertEquals(w.log.size, report.manifest!!.eventCount)
        assertEquals(w.log.size.toLong(), report.manifest!!.lastSeq)
        assertEquals(EXPORT_FORMAT, report.manifest!!.format)
        assertEquals(w.log.size, report.events.size)
        assertEquals(2, report.manifest!!.blobs.size)
    }

    @Test
    fun aCircleWithNoBlobsVerifiesToo() = runTest {
        val w = world()
        assertTrue(w.verify(w.export(refs = emptyList())).ok)
    }

    @Test
    fun theExportOnlyHoldsCiphertextAndPublicParts() = runTest {
        val w = world()
        val text = w.export().getValue(EVENTS_ENTRY).decodeToString()
        assertFalse(text.contains("welcome"))
        assertFalse(text.contains("Ladder"))
        assertFalse(text.contains(w.circleKey.toBase64Url()))
    }

    @Test
    fun aChangedEventsFileFailsTheHash() = runTest {
        val w = world()
        val entries = w.export()
        val bytes = entries.getValue(EVENTS_ENTRY).copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        val report = w.verify(entries + (EVENTS_ENTRY to bytes))
        assertTrue(report.problems.anyContains("does not match eventsHash"), report.problems.toString())
    }

    @Test
    fun aForgedEventWithAMatchingHashIsStillRefusedByTheCirclesRules() = runTest {
        val w = world()
        val forged = w.log.toMutableList().also { it[2] = it[2].copy(day = it[2].day + 1) } // signature no longer matches
        val report = w.verify(w.export(forged))
        assertTrue(report.problems.anyContains("event 3 (member.invite) is refused by the circle's rules: bad signature"), report.problems.toString())
    }

    @Test
    fun reorderedEventsAreRefused() = runTest {
        val w = world()
        val swapped = w.log.toMutableList().also { val t = it[2]; it[2] = it[3]; it[3] = t } // the join before its invite
        val report = w.verify(w.export(swapped))
        assertTrue(report.problems.anyContains("no such invite"), report.problems.toString())
    }

    @Test
    fun aLogThatDoesNotStartWithCircleCreateIsRefused() = runTest {
        val w = world()
        val report = w.verify(w.export(w.log.drop(1)))
        assertTrue(report.problems.anyContains("the log must start with circle.create"), report.problems.toString())
    }

    @Test
    fun aMissingMiddleEventBreaksTheEventsAfterIt() = runTest {
        val w = world()
        val report = w.verify(w.export(w.log.filterIndexed { i, _ -> i != 2 })) // no invite, so the join is refused
        assertTrue(report.problems.anyContains("no such invite"), report.problems.toString())
    }

    @Test
    fun aRepeatedEventAnEventOfAnotherCircleAndMalformedLinesAreReported() = runTest {
        val w = world()
        val repeated = w.verify(w.export(w.log + w.log[1]))
        assertTrue(repeated.problems.anyContains("appears more than once"), repeated.problems.toString())
        val stranger = EventAuthor(w.crypto, DeviceIdentity.generate(w.crypto, w.cipher), w.cipher).circleCreate(newCircleId(w.crypto), DAY)
        assertTrue(w.verify(w.export(w.log + stranger)).problems.anyContains("belongs to another circle"))
        val entries = w.export()
        val broken = (entries.getValue(EVENTS_ENTRY).decodeToString() + "this is not json\n").encodeToByteArray()
        val report = w.verify(w.rebuilt(entries + (EVENTS_ENTRY to broken)) { it.copy(eventCount = it.eventCount + 1, lastSeq = it.lastSeq + 1, eventsHash = w.crypto.hash256(broken).toHex()) })
        assertTrue(report.problems.anyContains("is not valid JSON"), report.problems.toString())
    }

    @Test
    fun theManifestMustMatchTheEventsFile() = runTest {
        val w = world()
        val entries = w.export()
        assertTrue(w.verify(w.rebuilt(entries) { it.copy(eventCount = it.eventCount + 1) }).problems.anyContains("lines but the manifest says"))
        assertTrue(w.verify(w.rebuilt(entries) { it.copy(lastSeq = it.lastSeq + 5) }).problems.anyContains("lastSeq must equal eventCount"))
        assertTrue(w.verify(w.rebuilt(entries) { it.copy(format = "circles-export/2") }).problems.anyContains("unsupported format"))
        assertTrue(w.verify(w.rebuilt(entries) { it.copy(circle = "nope") }).problems.anyContains("circle id is not valid"))
        assertTrue(w.verify(w.rebuilt(entries) { it.copy(eventsHash = "zz") }).problems.anyContains("not a BLAKE2b-256 hash"))
        assertTrue(w.verify(w.rebuilt(entries) { it.copy(eventCount = 0) }).problems.anyContains("eventCount must be from 1"))
    }

    @Test
    fun aMissingManifestOrEventsFileOrAnUnreadableManifestIsReported() = runTest {
        val w = world()
        val entries = w.export()
        assertTrue(w.verify(entries - MANIFEST_ENTRY).problems.anyContains("$MANIFEST_ENTRY is missing"))
        assertTrue(w.verify(entries + (MANIFEST_ENTRY to "{ not json".encodeToByteArray())).problems.anyContains("$MANIFEST_ENTRY is not valid"))
        assertTrue(w.verify(entries - EVENTS_ENTRY).problems.anyContains("$EVENTS_ENTRY is missing"))
    }

    @Test
    fun blobProblemsAreAllReported() = runTest {
        val w = world()
        val entries = w.export()
        val a = w.refs()[0].hash
        val b = w.refs()[1].hash
        assertTrue(w.verify(entries - (BLOB_ENTRY_PREFIX + a)).problems.anyContains("is listed but missing"))
        val flipped = w.blobA.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertTrue(w.verify(entries + (BLOB_ENTRY_PREFIX + a to flipped)).problems.anyContains("does not match its hash"))
        assertTrue(w.verify(entries + (BLOB_ENTRY_PREFIX + a to w.blobA + byteArrayOf(1))).problems.anyContains("but the manifest says"))
        val extra = w.verify(entries + ("blobs/" + "ab".repeat(32) to byteArrayOf(1)))
        assertTrue(extra.problems.anyContains("unexpected entry"), extra.problems.toString())
        assertTrue(w.verify(entries + ("notes.txt" to byteArrayOf(1))).problems.anyContains("unexpected entry 'notes.txt'"))
        val twice = w.verify(w.rebuilt(entries) { it.copy(blobs = it.blobs + it.blobs.first()) })
        assertTrue(twice.problems.anyContains("lists a blob twice"), twice.problems.toString())
        val bad = w.verify(w.rebuilt(entries) { it.copy(blobs = it.blobs + ExportBlobRef("NOT-HEX", 1)) })
        assertTrue(bad.problems.anyContains("malformed hash"), bad.problems.toString())
        assertNotNull(b)
    }

    @Test
    fun anOversizedBlobIsRefused() = runTest {
        val w = world()
        val big = ByteArray(MAX_BLOB_BYTES + 1) { 7 }
        val ref = ExportBlobRef(w.crypto.hash256(big).toHex(), big.size.toLong())
        val entries = CircleExport.build(w.crypto, w.circle, w.log.map { it.toDto() }, listOf(ref)).entries(mapOf(ref.hash to big))
        assertTrue(w.verify(entries).problems.anyContains("larger than $MAX_BLOB_BYTES"))
    }

    @Test
    fun everyProblemIsReportedNotJustTheFirst() = runTest {
        val w = world()
        val entries = w.export() - (BLOB_ENTRY_PREFIX + w.refs()[0].hash) + ("notes.txt" to byteArrayOf(1))
        val report = w.verify(w.rebuilt(entries) { it.copy(format = "circles-export/9") })
        assertTrue(report.problems.size >= 3, report.problems.toString())
    }

    @Test
    fun duplicateEntryNamesAreReported() = runTest {
        val w = world()
        val entries = w.export()
        val source = object : ExportSource {
            override val entryNames: List<String> = entries.keys.toList() + MANIFEST_ENTRY
            override fun read(name: String, maxBytes: Long): ByteArray? = entries[name]
            override fun sizeOf(name: String): Long? = entries[name]?.size?.toLong()
        }
        assertTrue(ExportVerifier.verify(source, w.crypto).problems.anyContains("appears more than once"))
    }
}
