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
private const val KEY_STEWARD_QUORUM = "stewardQuorum"
private const val KEY_STEWARD_INACTIVE_DAYS = "stewardInactiveDays"
private const val KEY_STEWARD_DELAY_DAYS = "stewardFallbackDelayDays"
private const val KEY_QUORUM = "quorum"
private const val KEY_INACTIVE_DAYS = "inactiveDays"
private const val KEY_FALLBACK_DELAY_DAYS = "fallbackDelayDays"

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

/** What `circle.create` declares: the founder's device and, optionally, the circle's steward settings (ADR 0027). */
data class CircleCreateTerms(val registration: DeviceRegistration, val settings: StewardSettings)

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

    /** `public` of `circle.create`: the registration plus three optional steward settings; absent ones take the defaults. */
    fun circleCreate(map: CborMap): Parsed<CircleCreateTerms> {
        val registrationKeys = setOf(KEY_MEMBER, KEY_DEVICE, KEY_SIGN_PUB, KEY_BOX_PUB)
        val settingKeys = setOf(KEY_STEWARD_QUORUM, KEY_STEWARD_INACTIVE_DAYS, KEY_STEWARD_DELAY_DAYS)
        if (!map.entries.keys.containsAll(registrationKeys) || !(registrationKeys + settingKeys).containsAll(map.entries.keys)) {
            return Parsed.Invalid("expected the keys ${registrationKeys.sorted()} and optionally ${settingKeys.sorted()}")
        }
        val registration = when (val r = readRegistration(map)) {
            is Parsed.Ok -> r.value
            is Parsed.Invalid -> return r
        }
        val defaults = StewardSettings()
        val settings = StewardSettings(
            quorum = optionalInt(map, KEY_STEWARD_QUORUM, defaults.quorum) ?: return Parsed.Invalid("stewardQuorum must be a whole number"),
            inactiveDays = optionalInt(map, KEY_STEWARD_INACTIVE_DAYS, defaults.inactiveDays) ?: return Parsed.Invalid("stewardInactiveDays must be a whole number"),
            fallbackDelayDays = optionalInt(map, KEY_STEWARD_DELAY_DAYS, defaults.fallbackDelayDays) ?: return Parsed.Invalid("stewardFallbackDelayDays must be a whole number"),
        )
        settings.problem()?.let { return Parsed.Invalid(it) }
        return Parsed.Ok(CircleCreateTerms(registration, settings))
    }

    /** `public` of `circle.create`; the settings are written only when [settings] is given. */
    fun circleCreatePublic(registration: DeviceRegistration, settings: StewardSettings?): CborMap =
        CborMap(
            registration.fields() + if (settings == null) emptyMap() else mapOf(
                KEY_STEWARD_QUORUM to CborInt(settings.quorum.toLong()),
                KEY_STEWARD_INACTIVE_DAYS to CborInt(settings.inactiveDays.toLong()),
                KEY_STEWARD_DELAY_DAYS to CborInt(settings.fallbackDelayDays.toLong()),
            ),
        )

    /** `public` of `steward.policy`: the full new settings. */
    fun stewardSettings(map: CborMap): Parsed<StewardSettings> {
        exactKeys(map, setOf(KEY_QUORUM, KEY_INACTIVE_DAYS, KEY_FALLBACK_DELAY_DAYS))?.let { return Parsed.Invalid(it) }
        val settings = StewardSettings(
            quorum = smallInt(map, KEY_QUORUM) ?: return Parsed.Invalid("quorum must be a whole number"),
            inactiveDays = smallInt(map, KEY_INACTIVE_DAYS) ?: return Parsed.Invalid("inactiveDays must be a whole number"),
            fallbackDelayDays = smallInt(map, KEY_FALLBACK_DELAY_DAYS) ?: return Parsed.Invalid("fallbackDelayDays must be a whole number"),
        )
        return settings.problem()?.let { Parsed.Invalid(it) } ?: Parsed.Ok(settings)
    }

    fun stewardSettingsPublic(settings: StewardSettings): CborMap = CborMap.of(
        KEY_QUORUM to CborInt(settings.quorum.toLong()),
        KEY_INACTIVE_DAYS to CborInt(settings.inactiveDays.toLong()),
        KEY_FALLBACK_DELAY_DAYS to CborInt(settings.fallbackDelayDays.toLong()),
    )

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

    /** `public` of `device.revoke`: just the device being revoked. */
    fun revoke(map: CborMap): Parsed<DeviceId> {
        exactKeys(map, setOf(KEY_DEVICE))?.let { return Parsed.Invalid(it) }
        val device = (map[KEY_DEVICE] as? CborText)?.value?.takeIf { isValidRandomId(it) } ?: return Parsed.Invalid("device must be 32 lowercase hex characters")
        return Parsed.Ok(DeviceId(device))
    }

    /** `public` of `steward.add` and `steward.remove`: the member whose role changes. */
    fun steward(map: CborMap): Parsed<MemberId> {
        exactKeys(map, setOf(KEY_MEMBER))?.let { return Parsed.Invalid(it) }
        val member = (map[KEY_MEMBER] as? CborText)?.value?.takeIf { isValidRandomId(it) } ?: return Parsed.Invalid("member must be 32 lowercase hex characters")
        return Parsed.Ok(MemberId(member))
    }

    fun stewardPublic(member: MemberId): CborMap = CborMap.of(KEY_MEMBER to CborText(member.v))

    fun revokePublic(device: DeviceId): CborMap = CborMap.of(KEY_DEVICE to CborText(device.v))

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
    const val REASON: String = "reason"

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

    /** The integer under [name], [default] if the key is absent, or null if it is present but not a usable integer. */
    private fun optionalInt(map: CborMap, name: String, default: Int): Int? = if (map[name] == null) default else smallInt(map, name)

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
