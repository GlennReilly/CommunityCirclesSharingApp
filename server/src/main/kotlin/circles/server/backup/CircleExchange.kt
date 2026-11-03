package circles.server.backup

import circles.api.PushStatus
import circles.crypto.Crypto
import circles.export.BLOB_ENTRY_PREFIX
import circles.export.CircleExport
import circles.export.ExportBlobRef
import circles.export.ExportSource
import circles.export.ExportVerifier
import circles.event.EnvelopeDto
import circles.api.MAX_BLOB_BYTES
import circles.policy.CircleId
import circles.server.domain.BlobPutResult
import circles.server.domain.BlobService
import circles.server.domain.Entrance
import circles.server.domain.Store
import circles.server.domain.SyncService
import java.nio.file.Path

private const val EXPORT_PAGE = 500

sealed interface ExportResult {
    data class Done(val events: Int, val blobs: Int) : ExportResult

    data object UnknownCircle : ExportResult

    data class Failed(val problems: List<String>) : ExportResult
}

/** Writes a circle's whole log and blobs as `circles-export/1` (ADR 0030). Read-only. */
class CircleExporter(private val store: Store, private val blobs: BlobService, private val crypto: Crypto) {
    fun export(circle: CircleId, target: Path): ExportResult {
        if (!store.transaction { it.circleExists(circle) }) return ExportResult.UnknownCircle
        val events = mutableListOf<EnvelopeDto>()
        var after = 0L
        while (true) {
            val page = store.transaction { it.eventsAfter(circle, after, EXPORT_PAGE) }
            if (page.isEmpty()) break
            events += page.map { it.envelope }
            after = page.last().serverSeq
        }
        if (after != events.size.toLong()) return ExportResult.Failed(listOf("the stored log has gaps: ${events.size} events end at sequence $after"))
        val refs = store.transaction { it.blobList(circle) }.map { ExportBlobRef(it.first, it.second) }
        val bytes = mutableMapOf<String, ByteArray>()
        val missing = refs.filter { ref -> blobs.get(circle, ref.hash)?.also { bytes[ref.hash] = it } == null }
        if (missing.isNotEmpty()) return ExportResult.Failed(missing.map { "blob ${it.hash} is recorded but its file is missing or unreadable" })
        val built = CircleExport.build(crypto, circle, events, refs)
        ZipExportWriter.write(target, built) { bytes.getValue(it) }
        return ExportResult.Done(events.size, refs.size)
    }
}

data class ImportReport(val eventsAccepted: Int, val eventsAlready: Int, val blobsStored: Int, val blobsAlready: Int)

sealed interface ImportResult {
    data class Done(val report: ImportReport) : ImportResult

    /** The file did not verify; nothing was imported. */
    data class Refused(val problems: List<String>) : ImportResult

    /** The import stopped part-way; what was accepted before is kept and the same file can resume it. */
    data class Stopped(val problems: List<String>, val report: ImportReport) : ImportResult
}

/**
 * Replays an export through the same server rules that accept pushed events (ADR 0030): signatures, roles, invites and
 * steward rules are re-checked, and devices, stewards, epochs and sequence numbers are rebuilt in log order.
 */
class CircleImporter(private val sync: SyncService, private val blobs: BlobService, private val crypto: Crypto) {
    fun import(source: ExportSource): ImportResult {
        val verified = ExportVerifier.verify(source, crypto)
        val manifest = verified.manifest
        if (!verified.ok || manifest == null) return ImportResult.Refused(verified.problems)
        val circle = CircleId(manifest.circle)
        var accepted = 0
        var already = 0
        for ((index, dto) in verified.events.withIndex()) {
            val expectedSeq = index + 1L
            // One event at a time, so the import stops at the first refusal and never leaves a gap.
            val result = sync.submit(listOf(dto), Entrance.Push(circle), allowCreate = true).single()
            val problem = when {
                result.status == PushStatus.REJECTED -> "event $expectedSeq was refused: ${result.reason}: ${result.detail}"
                result.serverSeq != expectedSeq -> "event $expectedSeq got sequence ${result.serverSeq}: the circle already has a different history"
                else -> null
            }
            if (problem != null) return ImportResult.Stopped(listOf(problem), ImportReport(accepted, already, 0, 0))
            if (result.status == PushStatus.ACCEPTED) accepted++ else already++
        }
        var stored = 0
        var blobsAlready = 0
        for (ref in manifest.blobs) {
            val bytes = source.read(BLOB_ENTRY_PREFIX + ref.hash, MAX_BLOB_BYTES.toLong())
            val outcome = if (bytes == null) null else blobs.put(circle, ref.hash, bytes)
            when (outcome) {
                BlobPutResult.Created -> stored++
                BlobPutResult.AlreadyThere -> blobsAlready++
                else -> return ImportResult.Stopped(listOf("blob ${ref.hash} could not be stored: ${outcome ?: "unreadable"}"), ImportReport(accepted, already, stored, blobsAlready))
            }
        }
        return ImportResult.Done(ImportReport(accepted, already, stored, blobsAlready))
    }
}
