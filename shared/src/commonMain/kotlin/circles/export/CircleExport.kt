package circles.export

import circles.api.ApiJson
import circles.api.MAX_BLOB_BYTES
import circles.crypto.Crypto
import circles.crypto.RefusingCipher
import circles.encoding.Parsed
import circles.event.CircleFold
import circles.event.Envelope
import circles.event.EnvelopeChecks
import circles.event.EnvelopeDto
import circles.event.isLowerHex
import circles.event.isValidRandomId
import circles.policy.CircleId
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/** The format name written in every manifest (ADR 0030). A change to the format needs `circles-export/2`. */
const val EXPORT_FORMAT = "circles-export/1"
const val MANIFEST_ENTRY = "manifest.json"
const val EVENTS_ENTRY = "events.ndjson"
const val BLOB_ENTRY_PREFIX = "blobs/"

/** Limits that keep a hostile file from costing more than time and disk (ADR 0030). */
const val MAX_EXPORT_EVENTS = 1_000_000
const val MAX_EXPORT_BLOBS = 200_000
const val MAX_EXPORT_MANIFEST_BYTES = 32L * 1024 * 1024
const val MAX_EXPORT_EVENTS_BYTES = 512L * 1024 * 1024
const val MAX_EXPORT_EVENT_LINE_BYTES = 256 * 1024
const val MAX_EXPORT_TOTAL_BYTES = 64L * 1024 * 1024 * 1024
private const val BLOB_HASH_HEX = 64

@Serializable
data class ExportBlobRef(val hash: String, val size: Long)

/** `manifest.json`: nothing secret and no timestamp, only what is needed to check the rest. */
@Serializable
data class ExportManifest(
    val format: String,
    val circle: String,
    val eventCount: Int,
    val lastSeq: Long,
    val eventsHash: String,
    val blobs: List<ExportBlobRef>,
)

/** The files of an export, whatever holds them (a zip, a map in a test). */
interface ExportSource {
    /** Every entry name in the container, in any order; duplicates are reported by the verifier. */
    val entryNames: List<String>

    /** Bytes of the entry [name], or null if absent. Implementations must not read more than [maxBytes] (they return null if larger). */
    fun read(name: String, maxBytes: Long): ByteArray?

    /** The declared size of an entry, or null if absent. */
    fun sizeOf(name: String): Long?
}

/** An [ExportSource] over a map, for tests and for clients that assemble an export in memory. */
class MapExportSource(private val entries: Map<String, ByteArray>) : ExportSource {
    override val entryNames: List<String> get() = entries.keys.toList()

    override fun read(name: String, maxBytes: Long): ByteArray? = entries[name]?.takeIf { it.size <= maxBytes }

    override fun sizeOf(name: String): Long? = entries[name]?.size?.toLong()
}

/** The events file and manifest for [events], ready to be put in a container. */
class BuiltExport(val manifest: ExportManifest, val eventsBytes: ByteArray) {
    /** The whole export as named entries, with the blob bytes the caller holds. */
    fun entries(blobBytes: Map<String, ByteArray>): Map<String, ByteArray> =
        buildMap {
            put(MANIFEST_ENTRY, ApiJson.encodeToString(ExportManifest.serializer(), manifest).encodeToByteArray())
            put(EVENTS_ENTRY, eventsBytes)
            manifest.blobs.forEach { put(BLOB_ENTRY_PREFIX + it.hash, blobBytes.getValue(it.hash)) }
        }
}

object CircleExport {
    /** [events] must be in `serverSeq` order starting at 1 with no gaps. */
    fun build(crypto: Crypto, circle: CircleId, events: List<EnvelopeDto>, blobs: List<ExportBlobRef>): BuiltExport {
        val bytes = events.joinToString(separator = "") { ApiJson.encodeToString(EnvelopeDto.serializer(), it) + "\n" }.encodeToByteArray()
        val manifest = ExportManifest(EXPORT_FORMAT, circle.v, events.size, events.size.toLong(), crypto.hash256(bytes).toHex(), blobs.sortedBy { it.hash })
        return BuiltExport(manifest, bytes)
    }
}

/** What [ExportVerifier] found; [events] and [manifest] are filled when they could be read. */
class VerifyReport(val problems: List<String>, val manifest: ExportManifest?, val events: List<EnvelopeDto>) {
    val ok: Boolean get() = problems.isEmpty()
}

/**
 * Checks an export without touching any database (ADR 0030): manifest, hash, line count, every envelope, the claimed
 * sequence, every blob, extra entries, and a structural fold of the whole log. It reports every problem it finds.
 */
object ExportVerifier {
    fun verify(source: ExportSource, crypto: Crypto): VerifyReport {
        val problems = mutableListOf<String>()
        val names = source.entryNames
        names.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { problems += "entry '$it' appears more than once" }
        val manifest = readManifest(source, problems) ?: return VerifyReport(problems, null, emptyList())
        checkEntryNames(names, manifest, problems)
        checkTotalSize(source, names, problems)
        val events = readEvents(source, manifest, crypto, problems)
        checkBlobs(source, manifest, crypto, problems)
        return VerifyReport(problems, manifest, events)
    }

    private fun readManifest(source: ExportSource, problems: MutableList<String>): ExportManifest? {
        val bytes = source.read(MANIFEST_ENTRY, MAX_EXPORT_MANIFEST_BYTES)
        if (bytes == null) {
            problems += if (source.sizeOf(MANIFEST_ENTRY) == null) "$MANIFEST_ENTRY is missing" else "$MANIFEST_ENTRY is too large"
            return null
        }
        val manifest = try {
            ApiJson.decodeFromString(ExportManifest.serializer(), bytes.decodeToString())
        } catch (_: SerializationException) {
            problems += "$MANIFEST_ENTRY is not valid"
            return null
        } catch (_: IllegalArgumentException) {
            problems += "$MANIFEST_ENTRY is not valid"
            return null
        }
        if (manifest.format != EXPORT_FORMAT) problems += "unsupported format '${manifest.format}' (this reads $EXPORT_FORMAT)"
        if (!isValidRandomId(manifest.circle)) problems += "the manifest's circle id is not valid"
        if (manifest.eventCount !in 1..MAX_EXPORT_EVENTS) problems += "eventCount must be from 1 to $MAX_EXPORT_EVENTS"
        if (manifest.lastSeq != manifest.eventCount.toLong()) problems += "lastSeq must equal eventCount (the log is unbroken from 1)"
        if (manifest.blobs.size > MAX_EXPORT_BLOBS) problems += "more than $MAX_EXPORT_BLOBS blobs"
        if (!isLowerHex(manifest.eventsHash, BLOB_HASH_HEX)) problems += "eventsHash is not a BLAKE2b-256 hash"
        return manifest
    }

    private fun checkEntryNames(names: List<String>, manifest: ExportManifest, problems: MutableList<String>) {
        val allowedBlobs = manifest.blobs.map { BLOB_ENTRY_PREFIX + it.hash }.toSet()
        if (manifest.blobs.map { it.hash }.toSet().size != manifest.blobs.size) problems += "the manifest lists a blob twice"
        names.toSet().forEach { name ->
            val known = name == MANIFEST_ENTRY || name == EVENTS_ENTRY || name in allowedBlobs
            if (!known) problems += "unexpected entry '$name'"
        }
    }

    private fun checkTotalSize(source: ExportSource, names: List<String>, problems: MutableList<String>) {
        val total = names.sumOf { source.sizeOf(it) ?: 0L }
        if (total > MAX_EXPORT_TOTAL_BYTES) problems += "the export is larger than $MAX_EXPORT_TOTAL_BYTES bytes"
    }

    private fun readEvents(source: ExportSource, manifest: ExportManifest, crypto: Crypto, problems: MutableList<String>): List<EnvelopeDto> {
        val bytes = source.read(EVENTS_ENTRY, MAX_EXPORT_EVENTS_BYTES)
        if (bytes == null) {
            problems += if (source.sizeOf(EVENTS_ENTRY) == null) "$EVENTS_ENTRY is missing" else "$EVENTS_ENTRY is too large"
            return emptyList()
        }
        if (crypto.hash256(bytes).toHex() != manifest.eventsHash) problems += "$EVENTS_ENTRY does not match eventsHash"
        val lines = bytes.decodeToString().split("\n").let { if (it.lastOrNull().isNullOrEmpty()) it.dropLast(1) else it }
        if (lines.size != manifest.eventCount) problems += "$EVENTS_ENTRY has ${lines.size} lines but the manifest says ${manifest.eventCount}"
        val dtos = mutableListOf<EnvelopeDto>()
        val envelopes = mutableListOf<Envelope>()
        lines.forEachIndexed { index, line ->
            val label = "event ${index + 1}"
            if (line.length > MAX_EXPORT_EVENT_LINE_BYTES) {
                problems += "$label is longer than $MAX_EXPORT_EVENT_LINE_BYTES bytes"
                return@forEachIndexed
            }
            val dto = try {
                ApiJson.decodeFromString(EnvelopeDto.serializer(), line)
            } catch (_: SerializationException) {
                problems += "$label is not valid JSON"
                return@forEachIndexed
            } catch (_: IllegalArgumentException) {
                problems += "$label is not valid JSON"
                return@forEachIndexed
            }
            when (val parsed = dto.toEnvelope()) {
                is Parsed.Invalid -> problems += "$label is malformed: ${parsed.reason}"
                is Parsed.Ok -> {
                    val envelope = parsed.value
                    val shape = EnvelopeChecks.check(envelope)
                    when {
                        shape != null -> problems += "$label is refused: ${shape.detail}"
                        envelope.circle.v != manifest.circle -> problems += "$label belongs to another circle"
                        else -> {
                            dtos += dto
                            envelopes += envelope
                        }
                    }
                }
            }
        }
        val ids = envelopes.map { it.eventId(crypto) }
        ids.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { problems += "event ${it.v.take(SHORT_ID)}… appears more than once" }
        if (dtos.size == lines.size && problems.none { it.startsWith("event ") || it.startsWith(EVENTS_ENTRY) }) foldLog(manifest, envelopes, crypto, ids, problems)
        return dtos
    }

    /** A structural fold with no keys: signatures, devices, roles, quorum, invites and epochs must all hold. */
    private fun foldLog(manifest: ExportManifest, envelopes: List<Envelope>, crypto: Crypto, ids: List<circles.event.EventId>, problems: MutableList<String>) {
        val view = CircleFold(crypto, RefusingCipher, null).fold(CircleId(manifest.circle), envelopes)
        val position = ids.withIndex().associate { it.value to it.index + 1 }
        view.rejected.forEach { problems += "event ${position[it.id] ?: 0} (${it.type}) is refused by the circle's rules: ${it.reason}" }
    }

    private fun checkBlobs(source: ExportSource, manifest: ExportManifest, crypto: Crypto, problems: MutableList<String>) {
        manifest.blobs.forEach { ref ->
            val name = BLOB_ENTRY_PREFIX + ref.hash
            if (!isLowerHex(ref.hash, BLOB_HASH_HEX)) {
                problems += "blob '${ref.hash.take(SHORT_ID)}' has a malformed hash"
                return@forEach
            }
            val declared = source.sizeOf(name)
            when {
                declared == null -> problems += "blob ${ref.hash.take(SHORT_ID)}… is listed but missing"
                declared > MAX_BLOB_BYTES -> problems += "blob ${ref.hash.take(SHORT_ID)}… is larger than $MAX_BLOB_BYTES bytes"
                declared != ref.size -> problems += "blob ${ref.hash.take(SHORT_ID)}… is $declared bytes but the manifest says ${ref.size}"
                else -> {
                    val bytes = source.read(name, MAX_BLOB_BYTES.toLong())
                    if (bytes == null || crypto.hash256(bytes).toHex() != ref.hash) problems += "blob ${ref.hash.take(SHORT_ID)}… does not match its hash"
                }
            }
        }
    }

    private const val SHORT_ID = 12
}
