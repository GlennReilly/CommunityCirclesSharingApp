package circles.event

import circles.crypto.BoxKeyPair
import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.crypto.SigningKeyPair
import circles.encoding.ByteString
import circles.encoding.CborMap
import circles.encoding.CborEncoder
import circles.encoding.CborText
import circles.policy.CircleId
import circles.policy.MemberId

/** A device's identity: ids plus keys. In M1 the box key is random bytes, because X25519 arrives with M2. */
class DeviceIdentity(
    val member: MemberId,
    val device: DeviceId,
    val signing: SigningKeyPair,
    val boxPub: ByteString,
    /** The X25519 secret key behind [boxPub]; null for M1-style identities whose box key is random bytes. */
    val boxSecret: ByteString? = null,
) {
    val registration: DeviceRegistration get() = DeviceRegistration(member, device, signing.publicKey, boxPub)

    val boxKeys: BoxKeyPair? get() = boxSecret?.let { BoxKeyPair(it, boxPub) }

    companion object {
        /** Without a [cipher] the box key is random bytes (M1 tests); with one it is a real X25519 key pair (M2). */
        fun generate(crypto: Crypto, member: MemberId = newMemberId(crypto), cipher: Cipher? = null): DeviceIdentity {
            val box = cipher?.generateBoxKeyPair()
            return DeviceIdentity(
                member,
                newDeviceId(crypto),
                crypto.generateSigningKeyPair(),
                box?.publicKey ?: crypto.randomBytes(circles.crypto.ED25519_KEY_BYTES),
                box?.secretKey,
            )
        }
    }
}

/** A single-use invite key pair: the public half goes in the invite, the secret half travels to the invitee. */
class InviteKey(val keys: SigningKeyPair)

/**
 * Builds and signs events for one device. Keeps the per-device counter (`ctr`). Without a [cipher] it writes the
 * plaintext `v = 0` development format; with one it writes sealed `v = 1` events using [keyRing] (ADR 0021).
 */
class EventAuthor(
    private val crypto: Crypto,
    val identity: DeviceIdentity,
    firstCounter: Long = 0,
    private val cipher: Cipher? = null,
    var keyRing: KeyRing = KeyRing.EMPTY,
) {
    private var nextCounter = firstCounter

    fun circleCreate(circle: CircleId, day: Int): Envelope =
        build(circle, EventType.CIRCLE_CREATE, day, identity.registration.toPublic(), CborMap.EMPTY)

    fun invite(circle: CircleId, key: InviteKey, expiresDay: Int, maxUses: Int, day: Int, note: String? = null): Envelope =
        build(circle, EventType.MEMBER_INVITE, day, InviteTerms(key.keys.publicKey, expiresDay, maxUses).toPublic(), textPayload(PublicParts.NOTE, note))

    /** The invitee's own join: the registration of this author's device, proved with the invite key. */
    fun join(circle: CircleId, invite: EventId, key: InviteKey, day: Int): Envelope {
        val registration = identity.registration
        val proof = crypto.sign(key.keys.secretSeed, joinProofMessage(circle, registration))
        return build(circle, EventType.MEMBER_JOIN, day, JoinTerms(registration, invite, proof).toPublic(), CborMap.EMPTY)
    }

    fun deviceAdd(circle: CircleId, newDevice: DeviceIdentity, day: Int, label: String? = null): Envelope =
        build(circle, EventType.DEVICE_ADD, day, newDevice.registration.toPublic(), textPayload(PublicParts.LABEL, label))

    /** Revokes [target]. Allowed for a member's own devices and, for any device, to a steward. */
    fun deviceRevoke(circle: CircleId, target: DeviceId, day: Int, reason: String? = null): Envelope =
        build(circle, EventType.DEVICE_REVOKE, day, PublicParts.revokePublic(target), textPayload(PublicParts.REASON, reason))

    /**
     * Starts [epoch] with a fresh key and wraps it to each of [devices] (the circle's current, non-revoked devices,
     * this one included if it should keep reading). Required after a revoke or a member leaving (spec 5). Returns
     * the `key.rotate` followed by one `key.wrap` per device, and adds the new key to [keyRing].
     */
    fun rotateKeys(circle: CircleId, epoch: Int, devices: List<DeviceRegistration>, day: Int): List<Envelope> {
        val key = KeyRing.newCircleKey(crypto)
        val events = listOf(keyRotate(circle, epoch, day)) + devices.map { keyWrap(circle, it, epoch, key, day) }
        keyRing = keyRing.with(epoch, key)
        return events
    }

    /** Wraps the circle key of [epoch] to [target]'s box key. The author must be able to send it (steward rules are the server's). */
    fun keyWrap(circle: CircleId, target: DeviceRegistration, epoch: Int, key: ByteString, day: Int): Envelope {
        val sealer = requireNotNull(cipher) { "key.wrap needs a cipher" }
        val wrapped = CircleKeys.wrap(sealer, target.boxPub, circle, epoch, key)
        return build(circle, EventType.KEY_WRAP, day, KeyWrapTerms(target.device, epoch, wrapped).toPublic(), CborMap.EMPTY, epoch)
    }

    /** Starts [epoch] (the current epoch plus one); the caller then wraps the new key to each current device. */
    fun keyRotate(circle: CircleId, epoch: Int, day: Int): Envelope =
        build(circle, EventType.KEY_ROTATE, day, CircleKeys.rotatePublic(epoch), CborMap.EMPTY, epoch)

    private fun textPayload(name: String, text: String?): CborMap = if (text == null) CborMap.EMPTY else CborMap.of(name to CborText(text))

    private fun build(circle: CircleId, type: EventType, day: Int, public: CborMap, payload: CborMap, epoch: Int = 0): Envelope {
        val sealer = cipher
        val secret = payload.entries.isNotEmpty()
        val sealedEpoch = if (sealer != null && secret) keyRing.latestEpoch.also { check(it >= FIRST_EPOCH) { "no circle key yet: wrap one to this device first" } } else epoch
        val unsigned = Envelope(
            v = PAYLOAD_VERSION_PLAINTEXT,
            circle = circle,
            author = identity.member,
            device = identity.device,
            type = type.wire,
            day = day,
            ctr = nextCounter++,
            epoch = sealedEpoch,
            public = public,
            nonce = ByteString.EMPTY,
            encrypted = if (!secret || sealer != null) ByteString.EMPTY else ByteString.of(CborEncoder.encode(payload)),
            sig = ByteString.EMPTY,
        )
        val ready = if (sealer == null) unsigned else PayloadSealing.seal(sealer, keyRing[sealedEpoch] ?: ByteString.EMPTY, unsigned, payload)
        return ready.signedBy(crypto, identity.signing.secretSeed)
    }
}
