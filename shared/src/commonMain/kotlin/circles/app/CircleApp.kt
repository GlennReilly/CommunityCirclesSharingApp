package circles.app

import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.encoding.Parsed
import circles.event.CircleKeys
import circles.event.DeviceId
import circles.event.DeviceIdentity
import circles.event.EventAuthor
import circles.event.EventType
import circles.event.FIRST_EPOCH
import circles.event.InviteKey
import circles.event.ItemKeys
import circles.event.MAX_ITEM_NAME_CHARS
import circles.event.LoanId
import circles.event.StandingRules
import circles.event.LoanStatus
import circles.event.MAX_LOAN_DAYS
import circles.event.MAX_LOAN_NOTE_CHARS
import circles.event.ReturnOutcome
import circles.event.newDetailKeyId
import circles.event.newLoanId
import circles.event.newItemId
import circles.encoding.CborMap
import circles.encoding.CborText
import circles.policy.AccessDecision
import circles.policy.ItemClass
import circles.policy.Standing
import circles.policy.Tier
import circles.policy.ItemId
import circles.event.KeyRing
import circles.event.newCircleId
import circles.keys.ChangePassphraseResult
import circles.keys.CreateResult
import circles.keys.KeyStore
import circles.keys.KeyVault
import circles.keys.UnlockResult
import circles.keys.VaultState
import circles.policy.CircleId
import circles.policy.MemberId
import circles.sync.Replica
import circles.sync.HintListener
import circles.sync.StreamTransport
import circles.sync.SyncApi
import circles.sync.TokenSource
import circles.sync.SyncEngine
import circles.sync.SyncReport
import circles.sync.SyncScheduler
import circles.sync.SyncState
import circles.sync.SyncTrigger
import circles.sync.Syncer
import circles.sync.fold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

const val INVITE_VALID_DAYS = 14
const val INVITE_USES = 1

/** A device that has joined but has no circle key yet (a steward admits it by wrapping the key to it). */
data class WaitingDevice(val device: DeviceId, val member: MemberId)

const val MAX_ITEM_DESCRIPTION_CHARS = 500

/** How long a vouch made here lasts; the circle's rules cap it too (ADR 0042). */
const val VOUCH_DAYS = 180
private const val FINGERPRINT_HEX_CHARS = 20
private const val FINGERPRINT_GROUP = 4
private const val DETAIL_DESCRIPTION = "description"

/** An item as the Items screen shows it. [description] is null unless this device holds the item's key (owner or approved borrower). */
data class ItemSummary(
    val item: ItemId,
    val name: String,
    val itemClass: ItemClass,
    val owner: MemberId,
    val ownedByMe: Boolean,
    val description: String?,
    /** For items that are not mine: may I ask to borrow it? */
    val access: AccessSummary? = null,
)

/** Your own standing in the circle (ADR 0042): never shown to anyone else. */
data class StandingSummary(
    val tier: Tier,
    /** Points rounded to a tenth for display. */
    val points: String,
    val reasons: List<String>,
    val howToUnlock: List<String>,
)

/** Whether this member may ask for an item, and if not why and how to change that. */
data class AccessSummary(val canAsk: Boolean, val reasons: List<String>, val howToUnlock: List<String>)

/** A person in the circle, as the Members screen shows them. */
data class MemberSummary(
    val member: MemberId,
    val isMe: Boolean,
    val isSteward: Boolean,
    val devices: Int,
    /** A short code from the first device's signing key, for comparing in person ("is this really you?"). */
    val fingerprint: String,
    /** Until when my vouch for this person lasts, if I have a live one. */
    val vouchedByMeUntil: Int? = null,
)

enum class LoanRole { BORROWER, OWNER }

/** A loan this member is part of, as the Borrow screen shows it. */
data class LoanSummary(
    val loan: LoanId,
    val item: ItemId,
    val itemName: String,
    val role: LoanRole,
    /** The other member: the owner when I borrow, the borrower when I lend. */
    val other: MemberId,
    val fromDay: Int,
    val toDay: Int,
    val status: LoanStatus,
    val iConfirmedHandover: Boolean,
    val otherConfirmedHandover: Boolean,
    val outcome: ReturnOutcome?,
    /** The item's private details, once this device can read them (the owner always; a borrower after approval). */
    val description: String?,
    val note: String? = null,
)

sealed interface LoanResult {
    data class Done(val loan: LoanId) : LoanResult

    /** Dates in the past, the end before the start, or a window longer than [MAX_LOAN_DAYS] days. */
    data class BadDates(val maximumDays: Int) : LoanResult

    data class NoteTooLong(val maximum: Int) : LoanResult

    data object NotLetInYet : LoanResult

    /** The circle's access rules say not yet; [reasons] and [howToUnlock] are for the member to read. */
    data class NotYet(val reasons: List<String>, val howToUnlock: List<String>) : LoanResult

    /** Your own item, a retired item, someone else's loan, or a step the loan is not ready for. */
    data object NotAllowed : LoanResult
}

sealed interface VouchResult {
    data class Done(val expiresDay: Int) : VouchResult

    data object NotLetInYet : VouchResult

    /** You may not vouch (below the needed tier), or the person is you, or not in the circle. */
    data object NotAllowed : VouchResult
}

sealed interface ItemResult {
    data class Saved(val item: ItemId) : ItemResult

    /** The name is empty or longer than [MAX_ITEM_NAME_CHARS]. */
    data object BadName : ItemResult

    data class DescriptionTooLong(val maximum: Int) : ItemResult

    /** No circle key has been wrapped to this device yet, so nothing can be sealed. */
    data object NotLetInYet : ItemResult

    /** Locked, or the item is not this member's, or it is retired. */
    data object NotAllowed : ItemResult
}

/** What the home screen shows, computed from the local copy of the log. */
data class HomeData(
    val circle: CircleId,
    val me: MemberId,
    val isSteward: Boolean,
    /** True once a circle key has been wrapped to this device; until then nothing can be read. */
    val canRead: Boolean,
    val members: Int,
    val waiting: List<WaitingDevice>,
    val changesWaiting: Int,
    val refusedChanges: Int,
    /** Items that are not retired, newest events last. */
    val items: List<ItemSummary> = emptyList(),
    /** Loans this member borrows or lends, in the order they were asked for. */
    val loans: List<LoanSummary> = emptyList(),
    /** Today as a day number, for date pickers. */
    val today: Int = 0,
    /** My own standing; null until the circle has been read. */
    val standing: StandingSummary? = null,
    /** May I vouch for others right now? */
    val canVouch: Boolean = false,
    /** Everyone in the circle, in the order they joined. */
    val people: List<MemberSummary> = emptyList(),
    /** Minutes of inactivity before this browser locks; 0 means only when the tab closes. */
    val autoLockMinutes: Int = circles.keys.DEFAULT_IDLE_LOCK_MINUTES,
)

sealed interface AppModel {
    data object Loading : AppModel

    /** No identity in this browser: it can start a circle or join one. */
    data object NoIdentity : AppModel

    data class Locked(val circle: CircleId?) : AppModel

    data class Open(val home: HomeData) : AppModel
}

sealed interface StartResult {
    data class Started(val circle: CircleId) : StartResult
    data class PassphraseTooShort(val minimum: Int) : StartResult
    data object AlreadyHasIdentity : StartResult
}

/**
 * The client's whole behaviour except drawing (spec 6): sign-in states, starting or joining a circle, keeping the sync
 * engine and its scheduler running while unlocked, and the few actions the home screen offers. The page only renders
 * [model] and [sync] and calls these methods, so all of it is tested without a browser, and on the JVM against the real
 * server.
 */
class CircleApp(
    private val scope: CoroutineScope,
    private val crypto: Crypto,
    private val cipher: Cipher,
    private val vault: KeyVault,
    private val keyStore: KeyStore,
    private val replica: Replica,
    private val apiFor: (DeviceIdentity) -> SyncApi,
    /** Today as days since 1970-01-01 (events carry a day, never a time). */
    private val today: () -> Int,
    /** The event stream of ADR 0026, if the platform has one; without it the app polls (the scheduler's timers). */
    private val streams: StreamTransport? = null,
    /** Randomness for delays; tests pass a fixed value. */
    private val random: () -> Double = { kotlin.random.Random.nextDouble() },
) {
    private val mutableModel = MutableStateFlow<AppModel>(AppModel.Loading)
    val model: StateFlow<AppModel> = mutableModel.asStateFlow()

    private val mutableSync = MutableStateFlow<SyncState>(SyncState.Offline(changesWaiting = 0))
    val sync: StateFlow<SyncState> = mutableSync.asStateFlow()

    private var session: Session? = null

    private class Session(val identity: DeviceIdentity, val circle: CircleId, val author: EventAuthor, val engine: SyncEngine, val scheduler: SyncScheduler, val hints: HintListener?)

    suspend fun open() {
        vault.open()
        val stored = keyStore.load()
        mutableModel.value = if (stored == null) AppModel.NoIdentity else AppModel.Locked(stored.circle?.let(::CircleId))
        // The vault locks itself after the idle time; the session must stop with it.
        scope.launch {
            vault.state.collect { state ->
                if (state is VaultState.Locked && session != null) {
                    val circle = session?.circle
                    stopSession()
                    mutableModel.value = AppModel.Locked(circle)
                }
            }
        }
    }

    /** True while the event stream is open, so changes by others arrive within moments instead of by polling. */
    fun isLive(): Boolean = session?.hints?.connected?.value == true

    private fun stopSession() {
        session?.scheduler?.stop()
        session?.hints?.stop()
        session = null
    }

    /** Syncs now and waits for the result (a "sync now" button, tests). Null while locked. */
    suspend fun syncOnce(): SyncReport? {
        val s = session ?: return null
        return s.engine.sync(s.circle).also { afterSync(it) }
    }

    /** Creates this browser's identity and a new circle with it as the founding steward. */
    suspend fun startCircle(passphrase: String): StartResult {
        val identity = when (val created = vault.create(passphrase)) {
            is CreateResult.Created -> created.identity
            is CreateResult.PassphraseTooShort -> return StartResult.PassphraseTooShort(created.minimum)
            CreateResult.AlreadyHasIdentity -> return StartResult.AlreadyHasIdentity
        }
        val circle = newCircleId(crypto)
        rememberCircle(circle)
        val key = KeyRing.newCircleKey(crypto)
        val s = activate(identity, circle)
        val day = today()
        s.engine.enqueue(circle, s.author.circleCreate(circle, day))
        s.engine.enqueue(circle, s.author.keyWrap(circle, identity.registration, FIRST_EPOCH, key, day))
        s.scheduler.trigger(SyncTrigger.LOCAL_WRITE)
        refresh()
        return StartResult.Started(circle)
    }

    /** Creates this browser's identity and asks to join the circle of [link]. */
    suspend fun join(link: InviteLink, passphrase: String): StartResult {
        val identity = when (val created = vault.create(passphrase)) {
            is CreateResult.Created -> created.identity
            is CreateResult.PassphraseTooShort -> return StartResult.PassphraseTooShort(created.minimum)
            CreateResult.AlreadyHasIdentity -> return StartResult.AlreadyHasIdentity
        }
        rememberCircle(link.circle)
        val s = activate(identity, link.circle)
        val inviteKey = InviteKey(crypto.signingKeyPairFromSeed(link.secretSeed))
        s.engine.enqueue(link.circle, s.author.join(link.circle, link.invite, inviteKey, today()))
        s.scheduler.trigger(SyncTrigger.LOCAL_WRITE)
        refresh()
        return StartResult.Started(link.circle)
    }

    suspend fun unlock(passphrase: String): UnlockResult {
        val result = vault.unlock(passphrase)
        if (result is UnlockResult.Unlocked) {
            val identity = (vault.state.value as VaultState.Unlocked).identity
            val circle = keyStore.load()?.circle?.let(::CircleId) ?: return UnlockResult.NoIdentity
            activate(identity, circle).scheduler.trigger(SyncTrigger.OPEN)
            refresh()
        }
        return result
    }

    /** Changes this browser's passphrase; the old one must be typed again. */
    suspend fun changePassphrase(old: String, new: String): ChangePassphraseResult = vault.changePassphrase(old, new)

    /** How long this browser may sit idle before it locks (minutes; 0: only when the tab closes). */
    suspend fun setAutoLockMinutes(minutes: Int) {
        vault.setAutoLockMinutes(minutes)
        refresh()
    }

    /** Drops the keys from memory and stops syncing; the saved identity stays. */
    fun lock() {
        vault.lock() // the watcher started in open() tears the session down
    }

    /** User activity, for the idle lock. */
    fun touch() = vault.touch()

    /** "Delete my data on this device": identity, wrapped keys and the local copy of the log are forgotten. */
    suspend fun erase() {
        stopSession()
        vault.erase()
        mutableModel.value = AppModel.NoIdentity
    }

    /** Syncs now, whatever the timers say (a "sync now" button, or the browser coming back online). */
    fun syncNow(reason: SyncTrigger = SyncTrigger.MANUAL) {
        session?.scheduler?.trigger(reason)
    }

    /** Makes a one-use invite for the circle and queues it. Null while locked. The link is shown once. */
    suspend fun makeInvite(): InviteLink? {
        val s = session ?: return null
        val key = InviteKey(crypto.generateSigningKeyPair())
        val day = today()
        val event = s.author.invite(s.circle, key, day + INVITE_VALID_DAYS, INVITE_USES, day)
        s.engine.enqueue(s.circle, event)
        s.scheduler.trigger(SyncTrigger.LOCAL_WRITE)
        refresh()
        return InviteLink(s.circle, event.eventId(crypto), key.keys.secretSeed)
    }

    /** A steward lets a waiting device in by wrapping the current circle key to it. False if it cannot be done now. */
    suspend fun admit(device: DeviceId): Boolean {
        val s = session ?: return false
        val view = replica.fold(s.circle, crypto, cipher, s.identity)
        val registration = view.devices[device]?.takeIf { !it.revoked }?.registration ?: return false
        val key = view.keys[view.currentEpoch] ?: return false
        if (s.identity.member !in view.stewards) return false
        s.engine.enqueue(s.circle, s.author.keyWrap(s.circle, registration, view.currentEpoch, key, today()))
        s.scheduler.trigger(SyncTrigger.LOCAL_WRITE)
        refresh()
        return true
    }

    /** Adds an item owned by this member. Its description is sealed with a key of its own; only approved borrowers will get it. */
    suspend fun addItem(name: String, itemClass: ItemClass, description: String): ItemResult {
        val s = session ?: return ItemResult.NotAllowed
        checkFields(name, description)?.let { return it }
        val view = replica.fold(s.circle, crypto, cipher, s.identity)
        if (view.currentEpoch < FIRST_EPOCH || view.keys[view.currentEpoch] == null) return ItemResult.NotLetInYet
        s.author.keyRing = view.keys
        return saveItem(s, view, newItemId(crypto), newDetailKeyId(crypto), ItemKeys.newKey(crypto), name.trim(), itemClass, description.trim())
    }

    /** Changes an item's name, kind or description. Only its owner can, and only while this device holds the item's key. */
    suspend fun editItem(item: ItemId, name: String, itemClass: ItemClass, description: String): ItemResult {
        val s = session ?: return ItemResult.NotAllowed
        checkFields(name, description)?.let { return it }
        val view = replica.fold(s.circle, crypto, cipher, s.identity)
        val state = view.market.items[item]?.takeIf { it.owner == s.identity.member && !it.retired } ?: return ItemResult.NotAllowed
        val key = view.market.itemKeys[item to state.detailKeyId] ?: return ItemResult.NotAllowed
        s.author.keyRing = view.keys
        return saveItem(s, view, item, state.detailKeyId, key, name.trim(), itemClass, description.trim())
    }

    /** Takes an item off the list (its owner only). Loans already active can still be returned. */
    suspend fun retireItem(item: ItemId): ItemResult {
        val s = session ?: return ItemResult.NotAllowed
        val view = replica.fold(s.circle, crypto, cipher, s.identity)
        if (view.market.items[item]?.let { it.owner == s.identity.member && !it.retired } != true) return ItemResult.NotAllowed
        s.author.keyRing = view.keys
        s.engine.enqueue(s.circle, s.author.itemRetire(s.circle, today(), item))
        s.scheduler.trigger(SyncTrigger.LOCAL_WRITE)
        refresh()
        return ItemResult.Saved(item)
    }

    /** Vouches for another member for [VOUCH_DAYS] days (ADR 0042). Only members at Neighbour or above can. */
    suspend fun vouchFor(member: MemberId): VouchResult {
        val s = session ?: return VouchResult.NotAllowed
        val view = replica.fold(s.circle, crypto, cipher, s.identity)
        if (view.currentEpoch < FIRST_EPOCH || view.keys[view.currentEpoch] == null) return VouchResult.NotLetInYet
        val cfg = StandingRules.defaultConfig(s.circle)
        val inCircle = view.devices.values.any { !it.revoked && it.registration.member == member }
        if (member == s.identity.member || !inCircle) return VouchResult.NotAllowed
        if (!StandingRules.mayVouch(s.identity.member, cfg, view.market.loans.values, view.market.vouches, view.stewards, today())) return VouchResult.NotAllowed
        s.author.keyRing = view.keys
        val expires = today() + VOUCH_DAYS
        s.engine.enqueue(s.circle, s.author.vouch(s.circle, today(), member, expires))
        s.scheduler.trigger(SyncTrigger.LOCAL_WRITE)
        refresh()
        return VouchResult.Done(expires)
    }

    /** Asks to borrow someone else's item from [fromDay] to [toDay] (day numbers; see [CalendarDay]). */
    suspend fun requestLoan(item: ItemId, fromDay: Int, toDay: Int, note: String): LoanResult {
        val s = session ?: return LoanResult.NotAllowed
        windowProblem(fromDay, toDay, mustStartTodayOrLater = true)?.let { return it }
        if (note.trim().length > MAX_LOAN_NOTE_CHARS) return LoanResult.NoteTooLong(MAX_LOAN_NOTE_CHARS)
        val view = replica.fold(s.circle, crypto, cipher, s.identity)
        if (view.currentEpoch < FIRST_EPOCH || view.keys[view.currentEpoch] == null) return LoanResult.NotLetInYet
        val state = view.market.items[item]?.takeIf { !it.retired && it.owner != s.identity.member } ?: return LoanResult.NotAllowed
        // The same rule every client applies when folding (ADR 0042); asking first spares the member a request that would be ignored.
        val decision = StandingRules.decide(s.identity.member, state, StandingRules.defaultConfig(s.circle), view.market.loans.values, view.market.vouches, view.stewards, today())
        if (decision is AccessDecision.NotYet) return LoanResult.NotYet(decision.reasons, decision.howToUnlock)
        if (decision !is AccessDecision.Eligible) return LoanResult.NotAllowed
        s.author.keyRing = view.keys
        val loan = newLoanId(crypto)
        s.engine.enqueue(s.circle, s.author.loanRequest(s.circle, today(), loan, state.item, fromDay, toDay, note.trim().ifEmpty { null }))
        s.scheduler.trigger(SyncTrigger.LOCAL_WRITE)
        refresh()
        return LoanResult.Done(loan)
    }

    /** The owner answers a request. Approving wraps the item's key to the borrower's devices, so they can read its details. */
    suspend fun respondToLoan(loan: LoanId, approve: Boolean, note: String): LoanResult {
        val s = session ?: return LoanResult.NotAllowed
        if (note.trim().length > MAX_LOAN_NOTE_CHARS) return LoanResult.NoteTooLong(MAX_LOAN_NOTE_CHARS)
        val view = replica.fold(s.circle, crypto, cipher, s.identity)
        val state = view.market.loans[loan]?.takeIf { it.owner == s.identity.member && it.status == LoanStatus.REQUESTED } ?: return LoanResult.NotAllowed
        s.author.keyRing = view.keys
        val reply = note.trim().ifEmpty { null }
        val event = if (approve) {
            val item = view.market.items[state.item] ?: return LoanResult.NotAllowed
            val key = view.market.itemKeys[state.item to item.detailKeyId] ?: return LoanResult.NotAllowed
            val borrowerDevices = view.devices.values.filter { !it.revoked && it.registration.member == state.borrower }.map { it.registration }
            s.author.loanApprove(s.circle, today(), loan, state.item, state.fromDay, state.toDay, key, borrowerDevices, reply)
        } else {
            s.author.loanDecline(s.circle, today(), loan, state.fromDay, state.toDay, reply)
        }
        s.engine.enqueue(s.circle, event)
        s.scheduler.trigger(SyncTrigger.LOCAL_WRITE)
        refresh()
        return LoanResult.Done(loan)
    }

    /** Either side confirms the item changed hands; the loan becomes active when both have. */
    suspend fun confirmHandover(loan: LoanId): LoanResult {
        val s = session ?: return LoanResult.NotAllowed
        val view = replica.fold(s.circle, crypto, cipher, s.identity)
        val state = view.market.loans[loan]?.takeIf { it.status == LoanStatus.APPROVED } ?: return LoanResult.NotAllowed
        val me = s.identity.member
        val already = (me == state.owner && state.ownerConfirmedHandover) || (me == state.borrower && state.borrowerConfirmedHandover)
        if ((me != state.owner && me != state.borrower) || already) return LoanResult.NotAllowed
        s.author.keyRing = view.keys
        s.engine.enqueue(s.circle, s.author.loanHandover(s.circle, today(), loan))
        s.scheduler.trigger(SyncTrigger.LOCAL_WRITE)
        refresh()
        return LoanResult.Done(loan)
    }

    /** The borrower reports the item back. Reporting damage or loss carries no penalty (spec 6). */
    suspend fun returnLoan(loan: LoanId, outcome: ReturnOutcome): LoanResult {
        val s = session ?: return LoanResult.NotAllowed
        val view = replica.fold(s.circle, crypto, cipher, s.identity)
        view.market.loans[loan]?.takeIf { it.borrower == s.identity.member && it.status == LoanStatus.ACTIVE } ?: return LoanResult.NotAllowed
        s.author.keyRing = view.keys
        s.engine.enqueue(s.circle, s.author.loanReturn(s.circle, today(), loan, outcome))
        s.scheduler.trigger(SyncTrigger.LOCAL_WRITE)
        refresh()
        return LoanResult.Done(loan)
    }

    private fun windowProblem(fromDay: Int, toDay: Int, mustStartTodayOrLater: Boolean): LoanResult.BadDates? =
        if (fromDay > toDay || toDay - fromDay > MAX_LOAN_DAYS || (mustStartTodayOrLater && fromDay < today())) LoanResult.BadDates(MAX_LOAN_DAYS) else null

    private fun checkFields(name: String, description: String): ItemResult? = when {
        name.isBlank() || name.trim().length > MAX_ITEM_NAME_CHARS -> ItemResult.BadName
        description.trim().length > MAX_ITEM_DESCRIPTION_CHARS -> ItemResult.DescriptionTooLong(MAX_ITEM_DESCRIPTION_CHARS)
        else -> null
    }

    private suspend fun saveItem(
        s: Session, view: circles.event.CircleView, item: ItemId, detailKeyId: String, itemKey: circles.encoding.ByteString,
        name: String, itemClass: ItemClass, description: String,
    ): ItemResult {
        val mine = view.devices.values.filter { !it.revoked && it.registration.member == s.identity.member }.map { it.registration }
        val day = today()
        s.engine.enqueue(s.circle, s.author.itemUpsert(s.circle, day, item, name, itemClass, detailKeyId, itemKey, mine))
        s.engine.enqueue(s.circle, s.author.itemDetail(s.circle, day, item, detailKeyId, itemKey, CborMap.of(DETAIL_DESCRIPTION to CborText(description))))
        s.scheduler.trigger(SyncTrigger.LOCAL_WRITE)
        refresh()
        return ItemResult.Saved(item)
    }

    /** Recomputes the home data from the local log. Called after every sync and every local action. */
    suspend fun refresh() {
        val s = session ?: return
        val view = replica.fold(s.circle, crypto, cipher, s.identity)
        s.author.keyRing = view.keys
        // Wraps still in the outbox count, so a steward's list updates the moment they let someone in.
        val wrapped = (replica.entries(s.circle).map { it.envelope } + replica.outbox(s.circle)).mapNotNull { envelope ->
            if (EventType.fromWire(envelope.type) != EventType.KEY_WRAP) null
            else (CircleKeys.keyWrap(envelope.public) as? Parsed.Ok)?.value?.let { it.device to it.epoch }
        }.toSet()
        val waiting = if (view.currentEpoch < FIRST_EPOCH) emptyList() else view.devices.values
            .filter { !it.revoked && (it.registration.device to view.currentEpoch) !in wrapped }
            .map { WaitingDevice(it.registration.device, it.registration.member) }
        val cfg = StandingRules.defaultConfig(s.circle)
        val now = today()
        val loansNow = view.market.loans.values
        val vouchesNow = view.market.vouches
        val me = s.identity.member
        val myStanding = StandingRules.standingOf(me, cfg, loansNow, vouchesNow, view.stewards, now)
        mutableModel.value = AppModel.Open(
            HomeData(
                circle = s.circle,
                me = s.identity.member,
                isSteward = s.identity.member in view.stewards,
                canRead = view.currentEpoch >= FIRST_EPOCH && view.keys[view.currentEpoch] != null,
                members = view.devices.values.filter { !it.revoked }.map { it.registration.member }.toSet().size,
                waiting = waiting,
                changesWaiting = replica.outbox(s.circle).size,
                refusedChanges = replica.refused(s.circle).size,
                today = now,
                standing = StandingSummary(
                    tier = myStanding.tier,
                    points = circles.policy.format1(myStanding.points),
                    reasons = myStanding.reasons,
                    howToUnlock = unlockHints(myStanding, cfg),
                ),
                canVouch = StandingRules.mayVouch(me, cfg, loansNow, vouchesNow, view.stewards, now),
                autoLockMinutes = vault.autoLockMinutes,
                people = view.devices.values.filter { !it.revoked }.groupBy { it.registration.member }.map { (member, devices) ->
                    MemberSummary(
                        member = member,
                        isMe = member == s.identity.member,
                        isSteward = member in view.stewards,
                        devices = devices.size,
                        fingerprint = fingerprint(devices.first().registration.signPub),
                        vouchedByMeUntil = vouchesNow.filter { it.voucher == me && it.vouchee == member && it.isLiveOn(now, cfg) }.maxOfOrNull { it.effectiveExpiry(cfg) },
                    )
                },
                loans = view.market.loans.values.filter { it.owner == s.identity.member || it.borrower == s.identity.member }.map { loan ->
                    val item = view.market.items[loan.item]
                    val iAmOwner = loan.owner == s.identity.member
                    LoanSummary(
                        loan = loan.loan,
                        item = loan.item,
                        itemName = item?.name.orEmpty(),
                        role = if (iAmOwner) LoanRole.OWNER else LoanRole.BORROWER,
                        other = if (iAmOwner) loan.borrower else loan.owner,
                        fromDay = loan.fromDay,
                        toDay = loan.toDay,
                        status = loan.status,
                        iConfirmedHandover = if (iAmOwner) loan.ownerConfirmedHandover else loan.borrowerConfirmedHandover,
                        otherConfirmedHandover = if (iAmOwner) loan.borrowerConfirmedHandover else loan.ownerConfirmedHandover,
                        outcome = loan.outcome,
                        note = loan.requestNote,
                        description = ((view.market.details[loan.item] as? circles.encoding.Parsed.Ok)?.value?.get(DETAIL_DESCRIPTION) as? CborText)?.value,
                    )
                },
                items = view.market.items.values.filter { !it.retired }.map { state ->
                    ItemSummary(
                        item = state.item,
                        name = state.name,
                        itemClass = state.itemClass,
                        owner = state.owner,
                        ownedByMe = state.owner == s.identity.member,
                        description = ((view.market.details[state.item] as? circles.encoding.Parsed.Ok)?.value?.get(DETAIL_DESCRIPTION) as? CborText)?.value,
                        access = if (state.owner == me) null else when (val decision = StandingRules.decide(me, state, cfg, loansNow, vouchesNow, view.stewards, now)) {
                            is AccessDecision.Eligible -> AccessSummary(true, emptyList(), emptyList())
                            is AccessDecision.NotYet -> AccessSummary(false, decision.reasons, decision.howToUnlock)
                            is AccessDecision.Paused -> AccessSummary(false, decision.reasons, emptyList())
                            is AccessDecision.OwnItem -> null
                        },
                    )
                },
            ),
        )
    }

    /** Plain next steps for the standing page: what moves a member up (ADR 0042). */
    private fun unlockHints(standing: Standing, cfg: circles.policy.CircleConfig): List<String> = buildList {
        val next = Tier.entries.firstOrNull { it.rank > standing.tier.rank && it != Tier.STEWARD }
        if (next == null) {
            add("You have the highest tier that borrowing needs. Keep lending and returning well.")
            return@buildList
        }
        val gap = ((cfg.thresholds[next] ?: 0.0) - standing.points).coerceAtLeast(0.0)
        add("About ${circles.policy.format1(gap)} more standing points opens ${next.name.lowercase()} access: lend items and get them back, or borrow and return them on time.")
        if (next.rank <= Tier.NEIGHBOUR.rank) add("Or get ${cfg.vouchesForNeighbour} neighbours to vouch for you (on the Members page).")
    }

    /** Twenty hex characters of the key's hash in groups of four, easy to read out loud. */
    private fun fingerprint(signPub: circles.encoding.ByteString): String =
        crypto.hash256(signPub.toByteArray()).toHex().take(FINGERPRINT_HEX_CHARS).chunked(FINGERPRINT_GROUP).joinToString(" ")

    // ---- internals ----

    private suspend fun rememberCircle(circle: CircleId) {
        keyStore.load()?.let { keyStore.save(it.copy(circle = circle.v)) }
    }

    private suspend fun activate(identity: DeviceIdentity, circle: CircleId): Session {
        stopSession()
        val api = apiFor(identity)
        val engine = SyncEngine(crypto, api, replica)
        val syncer = Syncer { c -> engine.sync(c).also { report -> afterSync(report) } }
        val scheduler = SyncScheduler(scope, syncer, circle, random)
        val hints = streams?.let { transport ->
            (api as? TokenSource)?.let { tokens ->
                // A hint at or below what is already stored (our own push coming back) needs no pull.
                HintListener(scope, transport, tokens, circle, random) { newest -> if (newest > replica.cursor(circle)) scheduler.trigger(SyncTrigger.HINT) }
            }
        }
        val author = EventAuthor(crypto, identity, cipher, firstCounter = nextCounter(identity, circle))
        return Session(identity, circle, author, engine, scheduler, hints).also {
            session = it
            scheduler.start()
        }
    }

    private suspend fun afterSync(report: SyncReport) {
        mutableSync.value = report.state
        // The stream needs a logged-in device, which a new one is only once its first events are accepted.
        if (report.state == SyncState.UpToDate) session?.hints?.start()
        refresh()
    }

    /** The device counter continues after the highest one already used here, in the log or the outbox. */
    private suspend fun nextCounter(identity: DeviceIdentity, circle: CircleId): Long {
        val logged = replica.entries(circle).filter { it.envelope.device == identity.device }.maxOfOrNull { it.envelope.ctr }
        val queued = replica.outbox(circle).filter { it.device == identity.device }.maxOfOrNull { it.ctr }
        return maxOf(logged ?: -1L, queued ?: -1L) + 1
    }
}
