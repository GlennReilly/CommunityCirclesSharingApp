package circles.event

import circles.crypto.BoxKeyPair
import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.crypto.SigningKeyPair
import circles.encoding.ByteString
import circles.encoding.CborMap
import circles.encoding.CborText
import circles.policy.CircleId
import circles.policy.ItemClass
import circles.policy.ItemId
import circles.policy.MemberId
import circles.policy.Tier

/** A device's identity: ids plus keys. In M1 the box key is random bytes, because X25519 arrives with M2. */
class DeviceIdentity(
    val member: MemberId,
    val device: DeviceId,
    val signing: SigningKeyPair,
    val boxPub: ByteString,
    /** The X25519 secret key behind [boxPub]; it never leaves the device. */
    val boxSecret: ByteString,
    /** The seed both secrets were derived from (ADR 0039), or null for an identity built from loose keys. */
    val seed: ByteString? = null,
) {
    val registration: DeviceRegistration get() = DeviceRegistration(member, device, signing.publicKey, boxPub)

    val boxKeys: BoxKeyPair get() = BoxKeyPair(boxSecret, boxPub)

    companion object {
        /** A new device: a fresh random seed and fresh ids. */
        fun generate(crypto: Crypto, cipher: Cipher, member: MemberId = newMemberId(crypto)): DeviceIdentity =
            fromSeed(crypto, cipher, DeviceSeed.newSeed(crypto), member, newDeviceId(crypto))

        /** The identity for [seed]: the same seed always gives the same keys, which is what a recovery restores (ADR 0039). */
        fun fromSeed(crypto: Crypto, cipher: Cipher, seed: ByteString, member: MemberId, device: DeviceId): DeviceIdentity {
            val box = DeviceSeed.boxKeyPair(crypto, cipher, seed)
            return DeviceIdentity(member, device, DeviceSeed.signingKeyPair(crypto, seed), box.publicKey, box.secretKey, seed)
        }
    }
}

/** A single-use invite key pair: the public half goes in the invite, the secret half travels to the invitee. */
class InviteKey(val keys: SigningKeyPair)

/**
 * Builds and signs events for one device. Keeps the per-device counter (`ctr`). Secret parts are always sealed with
 * the newest key in [keyRing] (ADR 0021, 0024); an event with a secret part needs a circle key first.
 */
class EventAuthor(
    private val crypto: Crypto,
    val identity: DeviceIdentity,
    private val cipher: Cipher,
    firstCounter: Long = 0,
    var keyRing: KeyRing = KeyRing.EMPTY,
) {
    private var nextCounter = firstCounter

    /** Creates the circle; [settings] are the steward settings of ADR 0027, left out (so the defaults apply) when null. */
    fun circleCreate(circle: CircleId, day: Int, settings: StewardSettings? = null): Envelope =
        build(circle, EventType.CIRCLE_CREATE, day, PublicParts.circleCreatePublic(identity.registration, settings), CborMap.EMPTY)

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

    /**
     * Splits the circle key of [epoch] k-of-n over [stewards] and returns one `recovery.share` per steward device
     * (ADR 0020). Null if the parameters are invalid (threshold below 2 or above the number of stewards).
     */
    fun recoveryShares(circle: CircleId, epoch: Int, stewards: List<DeviceRegistration>, threshold: Int, day: Int): List<Envelope>? {
        val key = checkNotNull(keyRing[epoch]) { "no circle key for epoch $epoch" }
        val sealed = RecoveryShares.split(crypto, cipher, circle, epoch, key, stewards, threshold) ?: return null
        return sealed.map { (device, wrapped) ->
            build(circle, EventType.RECOVERY_SHARE, day, KeyWrapTerms(device.device, epoch, wrapped).toPublic(), CborMap.EMPTY, epoch)
        }
    }

    /** Creates or changes an item. [itemKey] is wrapped to [ownerDevices] so every device of the owner can read the detail. */
    fun itemUpsert(
        circle: CircleId,
        day: Int,
        item: ItemId,
        name: String,
        itemClass: ItemClass,
        detailKeyId: String,
        itemKey: ByteString,
        ownerDevices: List<DeviceRegistration>,
        minTier: Tier? = null,
    ): Envelope {
        val wraps = ownerDevices.map { DeviceKeyWrap(it.device, ItemKeys.wrap(cipher, it.boxPub, circle, item.v, itemKey)) }
        return build(circle, EventType.ITEM_UPSERT, day, CborMap.EMPTY, MarketPayloads.itemUpsert(ItemUpsert(item, name, itemClass, minTier, detailKeyId, wraps)))
    }

    fun itemRetire(circle: CircleId, day: Int, item: ItemId): Envelope =
        build(circle, EventType.ITEM_RETIRE, day, CborMap.EMPTY, MarketPayloads.itemRetire(ItemRetire(item)))

    /** Seals [detail] with the item key, so only the owner and approved borrowers can read it (spec 5). */
    fun itemDetail(circle: CircleId, day: Int, item: ItemId, detailKeyId: String, itemKey: ByteString, detail: CborMap): Envelope {
        val sealed = ItemKeys.sealDetail(cipher, itemKey, circle, item.v, detail)
        return build(circle, EventType.ITEM_DETAIL, day, CborMap.EMPTY, MarketPayloads.itemDetail(ItemDetail(item, detailKeyId, sealed)))
    }

    fun loanRequest(circle: CircleId, day: Int, loan: LoanId, item: ItemId, fromDay: Int, toDay: Int, note: String? = null): Envelope =
        build(circle, EventType.LOAN_REQUEST, day, CborMap.EMPTY, MarketPayloads.loanRequest(LoanRequest(loan, item, fromDay, toDay, note)))

    /** Approves a loan, wrapping [itemKey] to each of the borrower's devices in [borrowerDevices]. */
    fun loanApprove(
        circle: CircleId,
        day: Int,
        loan: LoanId,
        item: ItemId,
        fromDay: Int,
        toDay: Int,
        itemKey: ByteString,
        borrowerDevices: List<DeviceRegistration>,
        note: String? = null,
    ): Envelope {
        val wraps = borrowerDevices.map { DeviceKeyWrap(it.device, ItemKeys.wrap(cipher, it.boxPub, circle, item.v, itemKey)) }
        return build(circle, EventType.LOAN_RESPOND, day, CborMap.EMPTY, MarketPayloads.loanRespond(LoanRespond(loan, true, fromDay, toDay, note, wraps)))
    }

    fun loanDecline(circle: CircleId, day: Int, loan: LoanId, fromDay: Int, toDay: Int, note: String? = null): Envelope =
        build(circle, EventType.LOAN_RESPOND, day, CborMap.EMPTY, MarketPayloads.loanRespond(LoanRespond(loan, false, fromDay, toDay, note, emptyList())))

    fun loanHandover(circle: CircleId, day: Int, loan: LoanId): Envelope =
        build(circle, EventType.LOAN_HANDOVER, day, CborMap.EMPTY, MarketPayloads.loanHandover(LoanHandover(loan)))

    fun loanReturn(circle: CircleId, day: Int, loan: LoanId, outcome: ReturnOutcome): Envelope =
        build(circle, EventType.LOAN_RETURN, day, CborMap.EMPTY, MarketPayloads.loanReturn(LoanReturn(loan, outcome)))

    fun stewardAdd(circle: CircleId, member: MemberId, day: Int, note: String? = null): Envelope =
        build(circle, EventType.STEWARD_ADD, day, PublicParts.stewardPublic(member), textPayload(PublicParts.NOTE, note))

    /** Proposes new steward settings (quorum and the two fallback times); takes effect when the quorum of stewards agree. */
    fun stewardPolicy(circle: CircleId, settings: StewardSettings, day: Int, note: String? = null): Envelope =
        build(circle, EventType.STEWARD_POLICY, day, PublicParts.stewardSettingsPublic(settings), textPayload(PublicParts.NOTE, note))

    fun stewardRemove(circle: CircleId, member: MemberId, day: Int, note: String? = null): Envelope =
        build(circle, EventType.STEWARD_REMOVE, day, PublicParts.stewardPublic(member), textPayload(PublicParts.NOTE, note))

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
        val wrapped = CircleKeys.wrap(cipher, target.boxPub, circle, epoch, key)
        return build(circle, EventType.KEY_WRAP, day, KeyWrapTerms(target.device, epoch, wrapped).toPublic(), CborMap.EMPTY, epoch)
    }

    /** Starts [epoch] (the current epoch plus one); the caller then wraps the new key to each current device. */
    fun keyRotate(circle: CircleId, epoch: Int, day: Int): Envelope =
        build(circle, EventType.KEY_ROTATE, day, CircleKeys.rotatePublic(epoch), CborMap.EMPTY, epoch)

    private fun textPayload(name: String, text: String?): CborMap = if (text == null) CborMap.EMPTY else CborMap.of(name to CborText(text))

    private fun build(circle: CircleId, type: EventType, day: Int, public: CborMap, payload: CborMap, epoch: Int = 0): Envelope {
        val secret = payload.entries.isNotEmpty()
        val eventEpoch = if (secret) keyRing.latestEpoch.also { check(it >= FIRST_EPOCH) { "no circle key yet: wrap one to this device first" } } else epoch
        val unsigned = Envelope(
            v = PAYLOAD_VERSION_SEALED,
            circle = circle,
            author = identity.member,
            device = identity.device,
            type = type.wire,
            day = day,
            ctr = nextCounter++,
            epoch = eventEpoch,
            public = public,
            nonce = ByteString.EMPTY,
            encrypted = ByteString.EMPTY,
            sig = ByteString.EMPTY,
        )
        val sealed = PayloadSealing.seal(cipher, keyRing[eventEpoch] ?: ByteString.EMPTY, unsigned, payload)
        return sealed.signedBy(crypto, identity.signing.secretSeed)
    }
}
