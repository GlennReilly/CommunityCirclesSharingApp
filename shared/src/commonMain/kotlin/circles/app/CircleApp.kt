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
import circles.event.newDetailKeyId
import circles.event.newItemId
import circles.encoding.CborMap
import circles.encoding.CborText
import circles.policy.ItemClass
import circles.policy.ItemId
import circles.event.KeyRing
import circles.event.newCircleId
import circles.keys.CreateResult
import circles.keys.KeyStore
import circles.keys.KeyVault
import circles.keys.UnlockResult
import circles.keys.VaultState
import circles.policy.CircleId
import circles.policy.MemberId
import circles.sync.Replica
import circles.sync.SyncApi
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
private const val DETAIL_DESCRIPTION = "description"

/** An item as the Items screen shows it. [description] is null unless this device holds the item's key (owner or approved borrower). */
data class ItemSummary(
    val item: ItemId,
    val name: String,
    val itemClass: ItemClass,
    val owner: MemberId,
    val ownedByMe: Boolean,
    val description: String?,
)

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
) {
    private val mutableModel = MutableStateFlow<AppModel>(AppModel.Loading)
    val model: StateFlow<AppModel> = mutableModel.asStateFlow()

    private val mutableSync = MutableStateFlow<SyncState>(SyncState.Offline(changesWaiting = 0))
    val sync: StateFlow<SyncState> = mutableSync.asStateFlow()

    private var session: Session? = null

    private class Session(val identity: DeviceIdentity, val circle: CircleId, val author: EventAuthor, val engine: SyncEngine, val scheduler: SyncScheduler)

    suspend fun open() {
        vault.open()
        val stored = keyStore.load()
        mutableModel.value = if (stored == null) AppModel.NoIdentity else AppModel.Locked(stored.circle?.let(::CircleId))
        // The vault locks itself after the idle time; the session must stop with it.
        scope.launch {
            vault.state.collect { state ->
                if (state is VaultState.Locked && session != null) {
                    val circle = session?.circle
                    session?.scheduler?.stop()
                    session = null
                    mutableModel.value = AppModel.Locked(circle)
                }
            }
        }
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

    /** Drops the keys from memory and stops syncing; the saved identity stays. */
    fun lock() {
        vault.lock() // the watcher started in open() tears the session down
    }

    /** User activity, for the idle lock. */
    fun touch() = vault.touch()

    /** "Delete my data on this device": identity, wrapped keys and the local copy of the log are forgotten. */
    suspend fun erase() {
        session?.scheduler?.stop()
        session = null
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
        val wrapped = replica.entries(s.circle).mapNotNull { entry ->
            if (EventType.fromWire(entry.envelope.type) != EventType.KEY_WRAP) null
            else (CircleKeys.keyWrap(entry.envelope.public) as? Parsed.Ok)?.value?.let { it.device to it.epoch }
        }.toSet()
        val waiting = if (view.currentEpoch < FIRST_EPOCH) emptyList() else view.devices.values
            .filter { !it.revoked && (it.registration.device to view.currentEpoch) !in wrapped }
            .map { WaitingDevice(it.registration.device, it.registration.member) }
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
                items = view.market.items.values.filter { !it.retired }.map { state ->
                    ItemSummary(
                        item = state.item,
                        name = state.name,
                        itemClass = state.itemClass,
                        owner = state.owner,
                        ownedByMe = state.owner == s.identity.member,
                        description = ((view.market.details[state.item] as? circles.encoding.Parsed.Ok)?.value?.get(DETAIL_DESCRIPTION) as? CborText)?.value,
                    )
                },
            ),
        )
    }

    // ---- internals ----

    private suspend fun rememberCircle(circle: CircleId) {
        keyStore.load()?.let { keyStore.save(it.copy(circle = circle.v)) }
    }

    private suspend fun activate(identity: DeviceIdentity, circle: CircleId): Session {
        session?.scheduler?.stop()
        val engine = SyncEngine(crypto, apiFor(identity), replica)
        val syncer = Syncer { c -> engine.sync(c).also { report -> afterSync(report) } }
        val scheduler = SyncScheduler(scope, syncer, circle)
        val author = EventAuthor(crypto, identity, cipher, firstCounter = nextCounter(identity, circle))
        return Session(identity, circle, author, engine, scheduler).also {
            session = it
            scheduler.start()
        }
    }

    private suspend fun afterSync(report: SyncReport) {
        mutableSync.value = report.state
        refresh()
    }

    /** The device counter continues after the highest one already used here, in the log or the outbox. */
    private suspend fun nextCounter(identity: DeviceIdentity, circle: CircleId): Long {
        val logged = replica.entries(circle).filter { it.envelope.device == identity.device }.maxOfOrNull { it.envelope.ctr }
        val queued = replica.outbox(circle).filter { it.device == identity.device }.maxOfOrNull { it.ctr }
        return maxOf(logged ?: -1L, queued ?: -1L) + 1
    }
}
