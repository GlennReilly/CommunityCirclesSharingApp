package circles.sync

import circles.encoding.ByteString
import circles.encoding.Parsed
import circles.event.Envelope
import circles.event.EventType
import circles.event.PublicParts

/**
 * The signing key each device registered, read from the log as it arrives, so a signature can be checked once when
 * the event is ingested (ADR 0036). It only answers "which key must have signed this event"; whether the event is
 * allowed (invites, roles, revocation, epochs) is still the fold's job. The first registration of a device id wins,
 * as in the fold; if the fold ends up accepting a different one, the recorded check names the wrong key and the fold
 * simply verifies for itself.
 */
class DeviceKeyDirectory {
    private val keys = mutableMapOf<circles.event.DeviceId, ByteString>()

    /** The key [e] must be signed with, or null when this device has not been registered (yet). */
    fun keyFor(e: Envelope): ByteString? = when (EventType.fromWire(e.type)) {
        EventType.CIRCLE_CREATE -> (PublicParts.circleCreate(e.public) as? Parsed.Ok)?.value?.registration?.takeIf { it.device == e.device }?.signPub
        EventType.MEMBER_JOIN -> (PublicParts.join(e.public) as? Parsed.Ok)?.value?.registration?.takeIf { it.device == e.device }?.signPub
        else -> keys[e.device]
    }

    /** Remembers the device an event registers. Call only for events whose signature checked out. */
    fun learn(e: Envelope) {
        val registration = when (EventType.fromWire(e.type)) {
            EventType.CIRCLE_CREATE -> (PublicParts.circleCreate(e.public) as? Parsed.Ok)?.value?.registration
            EventType.MEMBER_JOIN -> (PublicParts.join(e.public) as? Parsed.Ok)?.value?.registration
            EventType.DEVICE_ADD -> (PublicParts.registration(e.public) as? Parsed.Ok)?.value
            else -> null
        } ?: return
        keys.getOrPut(registration.device) { registration.signPub }
    }

    companion object {
        /** Rebuilds the directory from stored entries without any cryptography: only checked entries count. */
        fun from(entries: List<LogEntry>): DeviceKeyDirectory = DeviceKeyDirectory().also { directory ->
            entries.filter { it.verifiedWith != null }.forEach { directory.learn(it.envelope) }
        }
    }
}
