package circles.server.backup

import circles.api.ADMIN_MAX_SKEW_SECONDS
import circles.api.AdminAuth
import circles.api.AdminCredentials
import circles.api.ImportSummary
import circles.crypto.Crypto
import circles.encoding.Parsed
import circles.event.DeviceRegistration
import circles.event.EnvelopeDto
import circles.event.EventType
import circles.event.PublicParts
import circles.export.EVENTS_ENTRY
import circles.export.MAX_EXPORT_EVENTS_BYTES
import circles.api.ApiJson
import circles.encoding.ByteString
import circles.policy.CircleId
import circles.server.domain.Store
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.SerializationException

/** How long a nonce is remembered (ADR 0033). */
const val NONCE_MEMORY_MILLIS = 600_000L
private const val MAX_REMEMBERED_NONCES = 100_000
private const val MILLIS_PER_SECOND = 1000L

/** Remembers each admin nonce for ten minutes so a captured request cannot be replayed (ADR 0033). In memory only. */
class NonceMemory(private val clock: Clock) {
    private val seen = LinkedHashMap<String, Long>()

    /** True the first time [nonce] is offered, false if it was already used in the last ten minutes. */
    @Synchronized
    fun firstUse(nonce: String): Boolean {
        val now = clock.millis()
        val iterator = seen.entries.iterator()
        while (iterator.hasNext() && (seen.size >= MAX_REMEMBERED_NONCES || iterator.next().value < now - NONCE_MEMORY_MILLIS)) iterator.remove()
        if (seen.containsKey(nonce)) return false
        seen[nonce] = now
        return true
    }
}

sealed interface AdminExport {
    data object Unauthorized : AdminExport

    data object Forbidden : AdminExport

    data object NotFound : AdminExport

    /** The finished zip; the caller sends it and then deletes it. */
    data class Ready(val file: Path) : AdminExport
}

sealed interface AdminImport {
    data object Unauthorized : AdminImport

    data class Forbidden(val reason: String) : AdminImport

    data object Busy : AdminImport

    data class Invalid(val problems: List<String>) : AdminImport

    data class Done(val summary: ImportSummary) : AdminImport
}

/** Export and import for stewards over HTTP, authorised by a signed request and nothing else (ADR 0033). */
class AdminService(
    private val store: Store,
    private val crypto: Crypto,
    private val clock: Clock,
    private val exporter: CircleExporter,
    private val importer: CircleImporter,
    private val creationAllowed: Boolean,
    private val tempDir: Path,
    private val nonces: NonceMemory = NonceMemory(clock),
) {
    private val importing = AtomicBoolean(false)

    fun export(header: String?, path: String, circle: CircleId): AdminExport {
        val credentials = AdminAuth.parse(header)?.takeIf { fresh(it) } ?: return AdminExport.Unauthorized
        val device = store.transaction { it.device(credentials.device) } ?: return AdminExport.Unauthorized
        if (!AdminAuth.verify(crypto, credentials, "GET", path, ByteArray(0), device.signPub) || !nonces.firstUse(credentials.nonce)) return AdminExport.Unauthorized
        val allowed = device.circle == circle && !device.isRevoked && store.transaction { it.isSteward(circle, device.member) }
        if (!allowed) return AdminExport.Forbidden
        if (!store.transaction { it.circleExists(circle) }) return AdminExport.NotFound
        val target = Files.createTempFile(tempDir, ".admin-export-", ".zip")
        Files.delete(target) // the exporter writes the file itself and refuses to overwrite
        return when (exporter.export(circle, target)) {
            is ExportResult.Done -> AdminExport.Ready(target)
            else -> AdminExport.NotFound.also { Files.deleteIfExists(target) }
        }
    }

    fun import(header: String?, path: String, body: ByteArray): AdminImport {
        val credentials = AdminAuth.parse(header)?.takeIf { fresh(it) } ?: return AdminImport.Unauthorized
        if (!importing.compareAndSet(false, true)) return AdminImport.Busy
        val file = Files.createTempFile(tempDir, ".admin-import-", ".zip")
        try {
            Files.write(file, body)
            return ZipExportSource(file).use { source -> importFrom(credentials, path, body, source) }
        } catch (_: java.util.zip.ZipException) {
            return AdminImport.Unauthorized // not a zip, so there is no key to check the signature against
        } finally {
            Files.deleteIfExists(file)
            importing.set(false)
        }
    }

    private fun importFrom(credentials: AdminCredentials, path: String, body: ByteArray, source: ZipExportSource): AdminImport {
        // The host may not know this circle, so the signing key is looked up in the file's own registrations.
        val signPub = registrationIn(source, credentials.device)?.signPub ?: return AdminImport.Unauthorized
        if (!AdminAuth.verify(crypto, credentials, "POST", path, body, signPub) || !nonces.firstUse(credentials.nonce)) return AdminImport.Unauthorized
        val report = circles.export.ExportVerifier.verify(source, crypto)
        val view = report.view
        val manifest = report.manifest
        if (!report.ok || view == null || manifest == null) return AdminImport.Invalid(report.problems)
        val device = view.devices[credentials.device]
        val isSteward = device != null && !device.revoked && device.registration.member in view.stewards
        if (!isSteward) return AdminImport.Forbidden("the signing device is not a steward in this circle")
        val circle = CircleId(manifest.circle)
        if (store.transaction { it.circleExists(circle) }) {
            if (!store.transaction { it.isSteward(circle, device!!.registration.member) }) return AdminImport.Forbidden("the signing device is not a steward of this circle on this host")
        } else if (!creationAllowed) {
            return AdminImport.Forbidden("this host does not accept new circles")
        }
        return when (val result = importer.import(source, report)) {
            is ImportResult.Done -> AdminImport.Done(ImportSummary(result.report.eventsAccepted, result.report.eventsAlready, result.report.blobsStored, result.report.blobsAlready))
            is ImportResult.Refused -> AdminImport.Invalid(result.problems)
            is ImportResult.Stopped -> AdminImport.Invalid(result.problems)
        }
    }

    private fun fresh(credentials: AdminCredentials): Boolean =
        kotlin.math.abs(clock.millis() / MILLIS_PER_SECOND - credentials.ts) <= ADMIN_MAX_SKEW_SECONDS

    /** The device's public registration, read from the file's `circle.create`, `member.join` and `device.add` events. */
    private fun registrationIn(source: ZipExportSource, device: circles.event.DeviceId): DeviceRegistration? {
        val bytes = source.read(EVENTS_ENTRY, MAX_EXPORT_EVENTS_BYTES) ?: return null
        bytes.decodeToString().lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val dto = try {
                ApiJson.decodeFromString(EnvelopeDto.serializer(), line)
            } catch (_: SerializationException) {
                return@forEach
            } catch (_: IllegalArgumentException) {
                return@forEach
            }
            if (dto.type !in REGISTERING) return@forEach
            val envelope = (dto.toEnvelope() as? Parsed.Ok)?.value ?: return@forEach
            val registration = when (envelope.type) {
                EventType.CIRCLE_CREATE.wire -> (PublicParts.circleCreate(envelope.public) as? Parsed.Ok)?.value?.registration
                EventType.MEMBER_JOIN.wire -> (PublicParts.join(envelope.public) as? Parsed.Ok)?.value?.registration
                else -> (PublicParts.registration(envelope.public) as? Parsed.Ok)?.value
            }
            if (registration?.device == device) return registration
        }
        return null
    }

    private companion object {
        val REGISTERING = setOf(EventType.CIRCLE_CREATE.wire, EventType.MEMBER_JOIN.wire, EventType.DEVICE_ADD.wire)
    }
}
