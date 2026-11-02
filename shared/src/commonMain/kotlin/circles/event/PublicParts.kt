package circles.event

import circles.crypto.ED25519_KEY_BYTES
import circles.crypto.ED25519_SIGNATURE_BYTES
import circles.encoding.ByteString
import circles.encoding.CborInt
import circles.encoding.CborMap
import circles.encoding.CborBytes
import circles.encoding.CborText
import circles.encoding.Parsed
import circles.policy.CircleId
import circles.policy.MemberId

/** Longest optional note or label, in characters. */
const val MAX_TEXT_CHARS = 256

/** Most uses one invite may allow. */
const val MAX_INVITE_USES = 100

private const val KEY_MEMBER = "member"
private const val KEY_DEVICE = "device"
private const val KEY_SIGN_PUB = "signPub"
private const val KEY_BOX_PUB = "boxPub"
private const val KEY_INVITE = "invite"
private const val KEY_PROOF = "proof"
private const val KEY_INVITE_PUB = "invitePub"
private const val KEY_EXPIRES_DAY = "expiresDay"
private const val KEY_MAX_USES = "maxUses"
private const val KEY_NOTE = "note"
private const val KEY_LABEL = "label"

/** A device and its public keys, as registered by `circle.create`, `member.join` and `device.add` (spec 3.2). */
data class DeviceRegistration(val member: MemberId, val device: DeviceId, val signPub: ByteString, val boxPub: ByteString) {
    internal fun fields(): Map<String, circles.encoding.CborValue> =
        mapOf(
            KEY_MEMBER to CborText(member.v),
            KEY_DEVICE to CborText(device.v),
            KEY_SIGN_PUB to CborBytes(signPub),
            KEY_BOX_PUB to CborBytes(boxPub),
        )

    fun toPublic(): CborMap = CborMap(fields())
}

/** `public` of `member.invite`. */
data class InviteTerms(val invitePub: ByteString, val expiresDay: Int, val maxUses: Int) {
    fun toPublic(): CborMap =
        CborMap.of(KEY_INVITE_PUB to CborBytes(invitePub), KEY_EXPIRES_DAY to CborInt(expiresDay.toLong()), KEY_MAX_USES to CborInt(maxUses.toLong()))
}

/** `public` of `member.join`: the registration, the invite being redeemed and the invite key's proof. */
data class JoinTerms(val registration: DeviceRegistration, val invite: EventId, val proof: ByteString) {
    fun toPublic(): CborMap = CborMap(registration.fields() + mapOf(KEY_INVITE to CborText(invite.v), KEY_PROOF to CborBytes(proof)))
}

/** Strict readers for the `public` maps: exact key sets, exact types and lengths. Unknown keys are refused. */
object PublicParts {
    fun registration(map: CborMap): Parsed<DeviceRegistration> {
        exactKeys(map, setOf(KEY_MEMBER, KEY_DEVICE, KEY_SIGN_PUB, KEY_BOX_PUB))?.let { return Parsed.Invalid(it) }
        return readRegistration(map)
    }

    fun invite(map: CborMap): Parsed<InviteTerms> {
        exactKeys(map, setOf(KEY_INVITE_PUB, KEY_EXPIRES_DAY, KEY_MAX_USES))?.let { return Parsed.Invalid(it) }
        val pub = key(map, KEY_INVITE_PUB) ?: return Parsed.Invalid("invitePub must be $ED25519_KEY_BYTES bytes")
        val expires = smallInt(map, KEY_EXPIRES_DAY) ?: return Parsed.Invalid("expiresDay must be a non-negative integer")
        val uses = smallInt(map, KEY_MAX_USES)?.takeIf { it in 1..MAX_INVITE_USES }
            ?: return Parsed.Invalid("maxUses must be between 1 and $MAX_INVITE_USES")
        return Parsed.Ok(InviteTerms(pub, expires, uses))
    }

    fun join(map: CborMap): Parsed<JoinTerms> {
        exactKeys(map, setOf(KEY_MEMBER, KEY_DEVICE, KEY_SIGN_PUB, KEY_BOX_PUB, KEY_INVITE, KEY_PROOF))?.let { return Parsed.Invalid(it) }
        val registration = when (val r = readRegistration(map)) {
            is Parsed.Ok -> r.value
            is Parsed.Invalid -> return r
        }
        val invite = (map[KEY_INVITE] as? CborText)?.value?.takeIf { isLowerHex(it, EVENT_ID_HEX_LENGTH) }
            ?: return Parsed.Invalid("invite must be an event id")
        val proof = (map[KEY_PROOF] as? CborBytes)?.value?.takeIf { it.size == ED25519_SIGNATURE_BYTES }
            ?: return Parsed.Invalid("proof must be $ED25519_SIGNATURE_BYTES bytes")
        return Parsed.Ok(JoinTerms(registration, EventId(invite), proof))
    }

    /** The optional text of a payload (`note` for invites, `label` for devices); an empty payload gives null. */
    fun optionalText(payload: CborMap, name: String): Parsed<String?> {
        exactKeysAtMost(payload, setOf(name))?.let { return Parsed.Invalid(it) }
        val value = payload[name] ?: return Parsed.Ok(null)
        val text = (value as? CborText)?.value?.takeIf { it.length <= MAX_TEXT_CHARS }
            ?: return Parsed.Invalid("$name must be text of at most $MAX_TEXT_CHARS characters")
        return Parsed.Ok(text)
    }

    const val NOTE: String = KEY_NOTE
    const val LABEL: String = KEY_LABEL

    private fun readRegistration(map: CborMap): Parsed<DeviceRegistration> {
        val member = (map[KEY_MEMBER] as? CborText)?.value?.takeIf { isValidRandomId(it) }
            ?: return Parsed.Invalid("member must be 32 lowercase hex characters")
        val device = (map[KEY_DEVICE] as? CborText)?.value?.takeIf { isValidRandomId(it) }
            ?: return Parsed.Invalid("device must be 32 lowercase hex characters")
        val signPub = key(map, KEY_SIGN_PUB) ?: return Parsed.Invalid("signPub must be $ED25519_KEY_BYTES bytes")
        val boxPub = key(map, KEY_BOX_PUB) ?: return Parsed.Invalid("boxPub must be $ED25519_KEY_BYTES bytes")
        return Parsed.Ok(DeviceRegistration(MemberId(member), DeviceId(device), signPub, boxPub))
    }

    private fun key(map: CborMap, name: String): ByteString? = (map[name] as? CborBytes)?.value?.takeIf { it.size == ED25519_KEY_BYTES }

    private fun smallInt(map: CborMap, name: String): Int? = (map[name] as? CborInt)?.value?.takeIf { it in 0..Int.MAX_VALUE }?.toInt()

    private fun exactKeys(map: CborMap, expected: Set<String>): String? =
        if (map.entries.keys == expected) null else "expected exactly the keys ${expected.sorted()}"

    private fun exactKeysAtMost(map: CborMap, allowed: Set<String>): String? =
        if (allowed.containsAll(map.entries.keys)) null else "only the keys ${allowed.sorted()} are allowed"
}

/** Message the invite key signs to authorise one join (ADR 0013). */
const val JOIN_PROOF_DOMAIN = "circles/join/v1"

fun joinProofMessage(circle: CircleId, registration: DeviceRegistration): ByteArray =
    domainMessage(
        JOIN_PROOF_DOMAIN,
        circle.v.encodeToByteArray(),
        registration.member.v.encodeToByteArray(),
        registration.device.v.encodeToByteArray(),
        registration.signPub.toByteArray(),
        registration.boxPub.toByteArray(),
    )

/** Message a device signs to answer a login challenge. */
const val AUTH_DOMAIN = "circles/auth/v1"

fun authMessage(device: DeviceId, nonce: ByteString): ByteArray = domainMessage(AUTH_DOMAIN, nonce.toByteArray(), device.v.encodeToByteArray())
