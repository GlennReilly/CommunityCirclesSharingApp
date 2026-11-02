package circles.event

import circles.crypto.Crypto
import circles.crypto.SigningKeyPair
import circles.encoding.ByteString
import circles.encoding.CborMap
import circles.encoding.CborEncoder
import circles.encoding.CborText
import circles.policy.CircleId
import circles.policy.MemberId

/** A device's identity: ids plus keys. In M1 the box key is random bytes, because X25519 arrives with M2. */
class DeviceIdentity(val member: MemberId, val device: DeviceId, val signing: SigningKeyPair, val boxPub: ByteString) {
    val registration: DeviceRegistration get() = DeviceRegistration(member, device, signing.publicKey, boxPub)

    companion object {
        fun generate(crypto: Crypto, member: MemberId = newMemberId(crypto)): DeviceIdentity =
            DeviceIdentity(member, newDeviceId(crypto), crypto.generateSigningKeyPair(), crypto.randomBytes(circles.crypto.ED25519_KEY_BYTES))
    }
}

/** A single-use invite key pair: the public half goes in the invite, the secret half travels to the invitee. */
class InviteKey(val keys: SigningKeyPair)

/** Builds and signs events for one device. Keeps the per-device counter (`ctr`). */
class EventAuthor(private val crypto: Crypto, val identity: DeviceIdentity, firstCounter: Long = 0) {
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

    private fun textPayload(name: String, text: String?): CborMap = if (text == null) CborMap.EMPTY else CborMap.of(name to CborText(text))

    private fun build(circle: CircleId, type: EventType, day: Int, public: CborMap, payload: CborMap): Envelope {
        val unsigned = Envelope(
            v = PAYLOAD_VERSION_PLAINTEXT,
            circle = circle,
            author = identity.member,
            device = identity.device,
            type = type.wire,
            day = day,
            ctr = nextCounter++,
            epoch = 0,
            public = public,
            nonce = ByteString.EMPTY,
            encrypted = if (payload.entries.isEmpty()) ByteString.EMPTY else ByteString.of(CborEncoder.encode(payload)),
            sig = ByteString.EMPTY,
        )
        return unsigned.signedBy(crypto, identity.signing.secretSeed)
    }
}
