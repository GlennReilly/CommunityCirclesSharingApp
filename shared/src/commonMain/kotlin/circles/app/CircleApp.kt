package circles.app

import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.encoding.Parsed
import circles.event.toDto
import circles.event.CircleKeys
import circles.event.DeviceId
import circles.event.DeviceIdentity
import circles.event.EventAuthor
import circles.event.EventType
import circles.event.FIRST_EPOCH
import circles.event.InviteKey
import circles.event.ItemKeys
import circles.event.MAX_ITEM_NAME_CHARS
import circles.event.DAMAGE_CLAIM_DAYS
import circles.event.DEFAULT_STEWARD_FALLBACK_DELAY_DAYS
import circles.event.DEFAULT_STEWARD_INACTIVE_DAYS
import circles.event.DEFAULT_STEWARD_QUORUM
import circles.event.StewardChange
import circles.event.StewardOutcome
import circles.event.StewardPolicy
import circles.event.StewardRules
import circles.event.StewardSettings
import circles.event.StewardVote
import circles.event.DEFAULT_STEWARD_COOL_OFF_DAYS
import circles.event.DEFAULT_STEWARD_PROPOSAL_DAYS
import circles.event.DisputeClaim
import circles.event.DisputeOutcome
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
private const val DETAIL_DESCRIPTION = "description"

/** An item as the Items screen shows it. [description] is null unless this device holds the item's key (owner or approved borrower). */
data class ItemSummary(
    val item: ItemId,
    val name: String,
    val itemClass: ItemClass,
    val owner: MemberId,
    val ownedByMe: Boolean,
    val description: String?,
    /** The item's photos, thumbnails first by blob hash (ADR 0060). */
    val photos: List<circles.event.ItemPhoto> = emptyList(),
    /** The lowest tier the owner allows, if they raised it above the class default. */
    val ownerMinTier: Tier? = null,
    /** For items that are not mine: may I ask to borrow it? */
    val access: AccessSummary? = null,
)

/** The circle's rules and the proposals for new ones (ADR 0051). */
data class RulesSummary(
    val current: circles.event.ConfigSettings,
    val proposals: List<RulesProposal> = emptyList(),
)

data class RulesProposal(val settings: circles.event.ConfigSettings, val agreeing: Int, val needed: Int, val iAgree: Boolean, val canAgree: Boolean)

sealed interface RulesResult {
    data class Sent(val takesEffect: Boolean) : RulesResult

    data class Refused(val reason: String) : RulesResult

    data object NotAllowed : RulesResult
}

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

/** A steward as the Stewards screen shows them. [votesFromDay] is set while a new steward is still cooling off. */
data class StewardInfo(val member: MemberId, val isMe: Boolean, val sinceDay: Int?, val votesFromDay: Int?)

/** The three settings that govern stewards (ADR 0027). */
data class StewardSettingsSummary(val quorum: Int, val inactiveDays: Int, val fallbackDelayDays: Int, val recoveryThreshold: Int = circles.event.NO_RECOVERY_SHARES)

/** A steward change that is waiting for enough stewards to agree. */
data class ProposalSummary(
    val change: StewardChange,
    /** The member being added or removed; null for a settings change. */
    val member: MemberId?,
    val settings: StewardSettingsSummary?,
    val agreeing: Int,
    val needed: Int,
    val iAgree: Boolean,
    val canAgree: Boolean,
)

sealed interface RemoveResult {
    /** Done: the devices are revoked and the key has been rotated (queued together). */
    data object Removed : RemoveResult

    /** Only a steward can, and not for themselves or for another steward (they leave office first). */
    data object NotAllowed : RemoveResult

    data object IsSteward : RemoveResult

    data object NotInCircle : RemoveResult
}

sealed interface LeaveResult {
    /** The departure was sent; this device has forgotten the circle. */
    data object Left : LeaveResult

    /** A steward must step down on the Stewards page first. */
    data object StewardMustStepDown : LeaveResult

    /** Could not be sent (offline, or the server refused), so nothing was forgotten. */
    data object NotSent : LeaveResult
}

sealed interface StewardResult {
    /** Sent. [takesEffect] says whether it already completes the change (enough stewards agree). */
    data class Sent(val takesEffect: Boolean) : StewardResult

    /** The circle's rules refuse it now; [reason] is plain text to show. */
    data class Refused(val reason: String) : StewardResult

    data object NotAllowed : StewardResult
}

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
    /** May I, as a steward, remove this person from the circle? */
    val canRemove: Boolean = false,
    /** The name they gave themselves (ADR 0068); null until they set one. */
    val displayName: String? = null,
    /** Current members who confirmed that name and still count. */
    val nameConfirmedBy: List<MemberId> = emptyList(),
    /** Did I confirm their current name? */
    val iConfirmed: Boolean = false,
    /** May I confirm their name now (they have one, it is not mine, I have not yet, and I am Regular or above or a steward)? */
    val canConfirmName: Boolean = false,
) {
    /** True once enough current members confirmed the name ([circles.event.NAME_CONFIRMATIONS_NEEDED]). */
    val nameConfirmed: Boolean get() = nameConfirmedBy.size >= circles.event.NAME_CONFIRMATIONS_NEEDED
}

/** A member's display name as every screen shows it (ADR 0068). */
data class NameSummary(val name: String, val confirmed: Boolean)

sealed interface NameResult {
    data object Saved : NameResult

    data class Refused(val reason: String) : NameResult

    /** Names are sealed with the circle key, so a member must be let in first. */
    data object NotLetInYet : NameResult

    data object NotAllowed : NameResult
}

enum class LoanRole { BORROWER, OWNER, /** Neither party: a steward who may resolve this loan's dispute. */ STEWARD }

enum class DisputeStatus { PENDING, UPHELD, DISMISSED }

/** A dispute as the Borrow screen shows it. [DisputeStatus.DISMISSED] includes one that closed unresolved after 30 days. */
data class DisputeSummary(val claim: DisputeClaim, val note: String?, val day: Int, val status: DisputeStatus)

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
    /** What the borrower reported when returning it; null until returned. */
    val reported: ReturnOutcome? = null,
    val confirmed: Boolean = false,
    val dispute: DisputeSummary? = null,
    /** What this member may do about the return right now (ADR 0043). */
    val canConfirm: Boolean = false,
    val canDispute: Boolean = false,
    val canResolve: Boolean = false,
    /** The owner approved other dates than the borrower asked for (ADR 0061). */
    val datesChanged: Boolean = false,
)

sealed interface LoanResult {
    data class Done(val loan: LoanId) : LoanResult

    /** Dates in the past, the end before the start, or a window longer than [MAX_LOAN_DAYS] days. */
    data class BadDates(val maximumDays: Int) : LoanResult

    data class NoteTooLong(val maximum: Int) : LoanResult

    data object NotLetInYet : LoanResult

    /** The circle's access rules say not yet; [reasons] and [howToUnlock] are for the member to read. */
    data class NotYet(val reasons: List<String>, val howToUnlock: List<String>) : LoanResult

    /** Someone else has the item, or has been promised it, for some of those days (ADR 0052). */
    data object AlreadyBooked : LoanResult

    /** The member's access is paused (ADR 0050); [reasons] name the expiry and the appeal route. */
    data class Paused(val reasons: List<String>) : LoanResult

    /** Your own item, a retired item, someone else's loan, or a step the loan is not ready for. */
    data object NotAllowed : LoanResult
}

/** A skill offer as every member sees it (ADR 0055). */
data class SkillSummary(
    val skill: circles.event.SkillId,
    val name: String,
    val details: String,
    val openTo: Set<circles.event.OpenTo>,
    val owner: MemberId,
    val ownedByMe: Boolean,
)

sealed interface PhotoResult {
    data object Saved : PhotoResult

    /** No connection to upload with; nothing was changed. */
    data object Offline : PhotoResult

    /** The circle's photo storage is full. */
    data object Full : PhotoResult

    /** The item already has the most photos it may have. */
    data object TooMany : PhotoResult

    /** The prepared photo does not fit in one blob (the page prepares it to fit, so this is a safeguard). */
    data object TooBig : PhotoResult

    data class Refused(val reason: String) : PhotoResult

    data object NotAllowed : PhotoResult
}

sealed interface SkillResult {
    data class Saved(val skill: circles.event.SkillId) : SkillResult

    data class Refused(val reason: String) : SkillResult

    data object NotAllowed : SkillResult
}

enum class NoteStatus { WAITING, COUNTED, LAPSED }

/** A contribution note as the people it names see it (ADR 0053). [other] is the other person. */
data class NoteSummary(val note: circles.event.NoteId, val kind: circles.event.ContributionKind, val other: MemberId, val text: String, val day: Int, val status: NoteStatus)

data class ContributionsSummary(
    /** Notes that name me and wait for my confirmation. */
    val toConfirm: List<NoteSummary> = emptyList(),
    /** My own recent notes and where each stands. */
    val mine: List<NoteSummary> = emptyList(),
    /** The people I may name, and how many more notes I may make now. */
    val candidates: List<MemberId> = emptyList(),
    val notesLeft: Int = 0,
)

sealed interface NoteResult {
    data object Sent : NoteResult

    data class Refused(val reason: String) : NoteResult

    data object NotAllowed : NoteResult
}

enum class PauseRole { SUBJECT, REVIEWER }

/** A pause or proposal this member may see (ADR 0050): the subject and the trusted members and stewards who may review it. */
data class PauseSummary(
    val pause: circles.event.PauseId,
    val role: PauseRole,
    val subject: MemberId,
    val reason: String,
    val days: Int,
    val status: circles.event.PauseStatus,
    val agreeing: Int,
    val needed: Int,
    /** My own answer, if I gave one. */
    val iAgree: Boolean?,
    val untilDay: Int?,
    val appeal: String?,
    val proposedByMe: Boolean,
    /** Who agreed, shown to the subject only. */
    val reviewers: List<MemberId>,
    val canReview: Boolean,
    val canStepBack: Boolean,
    val canAppeal: Boolean,
    val canLift: Boolean,
)

/** A pause that is over (ended, lifted or lapsed), for the history on the Pauses page; visible to the same people as a live one. */
data class PauseHistoryEntry(
    val pause: circles.event.PauseId,
    val role: PauseRole,
    val subject: MemberId,
    val reason: String,
    val days: Int,
    val status: circles.event.PauseStatus,
    val proposedDay: Int,
    /** The day it took effect, or null if it never did (lapsed, or cancelled while only proposed). */
    val startedDay: Int?,
    /** The day it stopped: its last day, the day it was lifted, or the end of the proposal window. */
    val endedDay: Int,
    val appeal: String?,
)

sealed interface PauseResult {
    data object Sent : PauseResult

    /** The circle's rules refuse it now; [reason] is plain text to show. */
    data class Refused(val reason: String) : PauseResult

    data object NotAllowed : PauseResult
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
    /** How many stewards the circle has; one means disputes the steward is part of cannot be decided. */
    val stewardCount: Int = 0,
    /** A steward is told when someone left or was removed and the key has not been rotated since (ADR 0044). */
    val needsRotation: Boolean = false,
    /** This member's devices (ADR 0046). */
    val myDevices: List<DeviceSummary> = emptyList(),
    /** Whether this member has a recovery code registered (ADR 0047). */
    val hasRecoveryCode: Boolean = false,
    /** Recovery shares of the circle key (ADR 0048); only meaningful for a steward. */
    val recoveryShares: RecoverySharesSummary = RecoverySharesSummary(),
    /** Pauses and proposals I may see (ADR 0050), and whether I may propose one and about whom. */
    val pauses: List<PauseSummary> = emptyList(),
    /** The circle's rules in force today and the proposals for new ones (ADR 0051). */
    val rules: RulesSummary = RulesSummary(circles.event.ConfigSettings.of(circles.policy.CircleConfig(circles.policy.CircleId(""), ""))),
    /** Skill offers of everyone still in the circle, newest last (ADR 0055). */
    val skills: List<SkillSummary> = emptyList(),
    /** Contribution notes (ADR 0053). */
    val contributions: ContributionsSummary = ContributionsSummary(),
    /** Pauses that are over, newest first (same visibility as [pauses]). */
    val pauseHistory: List<PauseHistoryEntry> = emptyList(),
    val canProposePause: Boolean = false,
    val pauseCandidates: List<MemberId> = emptyList(),
    val stewardList: List<StewardInfo> = emptyList(),
    val stewardSettings: StewardSettingsSummary = StewardSettingsSummary(DEFAULT_STEWARD_QUORUM, DEFAULT_STEWARD_INACTIVE_DAYS, DEFAULT_STEWARD_FALLBACK_DELAY_DAYS),
    val proposals: List<ProposalSummary> = emptyList(),
    /** Everyone in the circle, in the order they joined. */
    val people: List<MemberSummary> = emptyList(),
    /** My own display name, or null if I have not set one yet: the app then asks for it first (ADR 0068). */
    val myName: String? = null,
    /** The display name of every member who has one, including members who have left (old loans still name them). */
    val names: Map<MemberId, NameSummary> = emptyMap(),
    /** Minutes of inactivity before this browser locks; 0 means only when the tab closes. */
    val autoLockMinutes: Int = circles.keys.DEFAULT_IDLE_LOCK_MINUTES,
    /** Whether unlocking with a passkey is offered here, and whether it is on (ADR 0063). */
    val passkeyOffered: Boolean = false,
    val passkeyOn: Boolean = false,
)

/**
 * What a steward needs to see about recovery shares (ADR 0048): the epochs this device holds a share for, whether the
 * shares of the current epoch went to every current steward, whether this device may make them, and the epochs whose key
 * this device does not have (which a set of share codes could rebuild).
 */
data class RecoverySharesSummary(
    val heldEpochs: List<Int> = emptyList(),
    val currentEpoch: Int = 0,
    val currentSetSent: Boolean = false,
    val mayMake: Boolean = false,
    val missingKeyEpochs: List<Int> = emptyList(),
    val minThreshold: Int = MIN_RECOVERY_THRESHOLD,
    val maxThreshold: Int = MIN_RECOVERY_THRESHOLD,
    /** The circle's setting (0 = off): stewards' devices send fresh shares by themselves after a rotation or steward change. */
    val automaticThreshold: Int = circles.event.NO_RECOVERY_SHARES,
)

/** The circle exported from this device's copy of the log (ADR 0030, point 4). */
sealed interface ExportResult {
    /** [photos] sealed photo files went in; [photosMissing] could not be fetched (offline, gone) or did not fit [EXPORT_PHOTO_BYTES], and are not in the file. */
    class Ready(val fileName: String, val bytes: ByteArray, val events: Int, val photos: Int = 0, val photosMissing: Int = 0) : ExportResult

    /** This device does not hold the whole log from the start (or none of it) yet; sync first. */
    data object Incomplete : ExportResult

    data object NotAllowed : ExportResult
}

private const val EXPORT_NAME_ID_CHARS = 8

/** The smallest number of stewards that must come together (a single steward needs no sharing). */
private const val MAX_NOTES_SHOWN = 8
private const val PHOTO_CACHE_ENTRIES = 40

const val MIN_RECOVERY_THRESHOLD = 2

sealed interface ShareMakingResult {
    data class Sent(val stewards: Int, val threshold: Int) : ShareMakingResult
    data object NotAllowed : ShareMakingResult
    data object NoKey : ShareMakingResult
    data class BadThreshold(val min: Int, val max: Int) : ShareMakingResult
}

sealed interface RebuildResult {
    data class Rebuilt(val epoch: Int) : RebuildResult
    data object NotAllowed : RebuildResult
    data object NoCodes : RebuildResult
    /** Code number [position] (from 1) is not a share code, or was mistyped when [mistyped] is true. */
    data class BadCode(val position: Int, val mistyped: Boolean) : RebuildResult
    data object DifferentEpochs : RebuildResult
    data object AlreadyHaveKey : RebuildResult
    data class Wrong(val reason: String) : RebuildResult
}

/** One of this member's devices, as Settings lists it. [name] is what was typed when it was added, if anything. */
data class DeviceSummary(val device: DeviceId, val isThisOne: Boolean, val name: String?)

sealed interface AddDeviceResult {
    data class Added(val keysWrapped: Int, val itemsShared: Int) : AddDeviceResult
    data object BadCode : AddDeviceResult
    data object AlreadyKnown : AddDeviceResult
    data object NameTooLong : AddDeviceResult
    data object NotLetInYet : AddDeviceResult
    data object NotAllowed : AddDeviceResult
}

sealed interface DeviceRemoval {
    data object Removed : DeviceRemoval
    data object NotAllowed : DeviceRemoval
}

/** The code pasted on an existing device, as it will be shown for checking. */
data class DevicePreview(val device: DeviceId, val fingerprint: String)

const val MAX_DEVICE_NAME_CHARS = 40

/** The name a recovery device is registered under (ADR 0047). */
const val RECOVERY_DEVICE_NAME = "Recovery code"
private const val RECOVERY_CHECK_WORDS = 3

/** A recovery code to write down: its [words] and the [askFor] places (1 to 12) the member must type back to prove they have a copy. */
data class RecoveryDraft(val words: List<String>, val askFor: List<Int>, val restoreLink: String)

sealed interface RecoveryCodeResult {
    data class Created(val restoreLink: String) : RecoveryCodeResult

    /** The typed words do not match the code that was shown. */
    data object WrongWords : RecoveryCodeResult
    data object NoDraft : RecoveryCodeResult
    data object WordlistUnavailable : RecoveryCodeResult
    data object NotLetInYet : RecoveryCodeResult
    data object NotAllowed : RecoveryCodeResult
}

sealed interface RestoreResult {
    data object Restored : RestoreResult
    data class BadWords(val problem: circles.keys.RecoveryParse) : RestoreResult
    data class PassphraseTooShort(val minimum: Int) : RestoreResult
    data object AlreadyHasIdentity : RestoreResult

    /** Nothing in this circle is registered for those words (wrong words, wrong link, or the code was replaced), or the server could not be reached. */
    data object NotFound : RestoreResult
    data object WordlistUnavailable : RestoreResult
}

sealed interface AppModel {
    data object Loading : AppModel

    /** No identity in this browser: it can start a circle or join one. */
    data object NoIdentity : AppModel

    /** [passkey]: this browser can also be unlocked with a passkey (ADR 0063). */
    data class Locked(val circle: CircleId?, val passkey: Boolean = false) : AppModel

    /** This browser has an identity for a member who has not added it yet: it shows its code and waits (ADR 0046). */
    data class AwaitingApproval(val circle: CircleId, val code: String, val fingerprint: String) : AppModel

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
    /** The 2048-word list for recovery codes, loaded when needed (the page fetches it as a file; ADR 0047). */
    private val wordlist: suspend () -> List<String> = { emptyList() },
    /** Binary uploads and downloads for item photos (ADR 0060); without it photos are not offered. */
    private val blobTransport: circles.sync.BlobTransport? = null,
    /** The browser's passkeys for unlocking without typing (ADR 0063); null where there are none. */
    private val passkeys: circles.keys.PasskeyProvider? = null,
    /** How many bytes of sealed photos an export holds at most; tests lower it. */
    private val exportPhotoBytes: Long = EXPORT_PHOTO_BYTES,
) {
    private var pendingRecovery: Triple<circles.encoding.ByteString, List<Int>, List<String>>? = null
    private val mutableModel = MutableStateFlow<AppModel>(AppModel.Loading)
    val model: StateFlow<AppModel> = mutableModel.asStateFlow()

    private val mutableSync = MutableStateFlow<SyncState>(SyncState.Offline(changesWaiting = 0))
    val sync: StateFlow<SyncState> = mutableSync.asStateFlow()

    private var session: Session? = null

    private class Session(val identity: DeviceIdentity, val circle: CircleId, val author: EventAuthor, val engine: SyncEngine, val scheduler: SyncScheduler, val hints: HintListener?, val pairing: Boolean, val blobs: circles.sync.HttpBlobApi? = null)

    suspend fun open() {
        vault.open()
        val stored = keyStore.load()
        mutableModel.value = if (stored == null) AppModel.NoIdentity else AppModel.Locked(stored.circle?.let(::CircleId), stored.passkey != null)
        // The vault locks itself after the idle time; the session must stop with it.
        scope.launch {
            vault.state.collect { state ->
                if (state is VaultState.Locked && session != null) {
                    val circle = session?.circle
                    stopSession()
                    mutableModel.value = AppModel.Locked(circle, keyStore.load()?.passkey != null)
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

    /** Proposes [member] as a steward, or agrees to an existing proposal (the same event). */
    suspend fun proposeSteward(member: MemberId): StewardResult = stewardChange(StewardChange.ADD, member.v)

    /** Proposes removing [member]; for yourself it is stepping down, which needs no vote. */
    suspend fun removeSteward(member: MemberId): StewardResult = stewardChange(StewardChange.REMOVE, member.v)

    /** Proposes new steward settings, or agrees to the same proposal (ADR 0027). */
    suspend fun proposeStewardSettings(quorum: Int, inactiveDays: Int, fallbackDelayDays: Int, recoveryThreshold: Int = circles.event.NO_RECOVERY_SHARES): StewardResult =
        stewardChange(StewardChange.POLICY, StewardSettings(quorum, inactiveDays, fallbackDelayDays, recoveryThreshold).subject())

    private suspend fun stewardChange(change: StewardChange, subject: String): StewardResult {
        val s = session ?: return StewardResult.NotAllowed
        val view = replica.fold(s.circle, crypto, cipher, s.identity)
        val me = s.identity.member
        val policy = StewardPolicy().with(view.stewardSettings)
        val prior = view.pendingStewardVotes.filter { it.change == change && it.subject == subject }.map { it.vote }
        val outcome = when (change) {
            StewardChange.POLICY -> {
                val proposed = StewardSettings.fromSubject(subject) ?: return StewardResult.NotAllowed
                StewardRules.decidePolicy(policy, proposed, me, today(), view.stewardSince, prior)
            }
            else -> {
                val target = MemberId(subject)
                if (view.devices.values.none { !it.revoked && it.registration.member == target }) return StewardResult.Refused("That person is not in the circle.")
                StewardRules.decide(policy, change, target, me, today(), view.stewardSince, prior)
            }
        }
        if (outcome is StewardOutcome.Refused) return StewardResult.Refused(outcome.reason)
        s.author.keyRing = view.keys
        val event = when (change) {
            StewardChange.ADD -> s.author.stewardAdd(s.circle, MemberId(subject), today())
            StewardChange.REMOVE -> s.author.stewardRemove(s.circle, MemberId(subject), today())
            StewardChange.POLICY -> s.author.stewardPolicy(s.circle, StewardSettings.fromSubject(subject)!!, today())
        }
        s.engine.enqueue(s.circle, event)
        s.scheduler.trigger(SyncTrigger.LOCAL_WRITE)
        refresh()
        return StewardResult.Sent(outcome is StewardOutcome.Effective)
    }

    /**
     * Removes a member (ADR 0044): their devices are revoked, the circle key is rotated, and the new key is wrapped to every
     * device that remains, all queued in this order as one batch.
     */
    suspend fun removeMember(member: MemberId): RemoveResult {
        val s = session ?: return RemoveResult.NotAllowed
        val view = replica.fold(s.circle, crypto, cipher, s.identity)
        val me = s.identity.member
        if (me !in view.stewards || member == me || view.currentEpoch < FIRST_EPOCH) return RemoveResult.NotAllowed
        if (member in view.stewards) return RemoveResult.IsSteward
        val theirs = view.devices.values.filter { !it.revoked && it.registration.member == member }
        if (theirs.isEmpty()) return RemoveResult.NotInCircle
        val day = today()
        s.author.keyRing = view.keys
        val revokes = theirs.map { s.author.deviceRevoke(s.circle, it.registration.device, day) }
        val remaining = view.devices.values.filter { !it.revoked && it.registration.member != member }.map { it.registration }
        val rotation = s.author.rotateKeys(s.circle, view.currentEpoch + 1, remaining, day) +
            automaticShares(s, view, view.currentEpoch + 1, remaining, day)
        (revokes + rotation).forEach { s.engine.enqueue(s.circle, it) }
        s.scheduler.trigger(SyncTrigger.LOCAL_WRITE)
        refresh()
        return RemoveResult.Removed
    }

    /** A steward rotates the circle key after someone left on their own, wrapping the new key to every live device. */
    suspend fun rotateKeyNow(): RemoveResult {
        val s = session ?: return RemoveResult.NotAllowed
        val view = replica.fold(s.circle, crypto, cipher, s.identity)
        if (s.identity.member !in view.stewards || view.currentEpoch < FIRST_EPOCH || view.unrotatedRemovals.isEmpty()) return RemoveResult.NotAllowed
        s.author.keyRing = view.keys
        val live = view.devices.values.filter { !it.revoked }.map { it.registration }
        val day = today()
        (s.author.rotateKeys(s.circle, view.currentEpoch + 1, live, day) + automaticShares(s, view, view.currentEpoch + 1, live, day))
            .forEach { s.engine.enqueue(s.circle, it) }
        s.scheduler.trigger(SyncTrigger.LOCAL_WRITE)
        refresh()
        return RemoveResult.Removed
    }

    /** Leaves the circle: this member's devices are revoked and sent, then this device forgets everything. */
    suspend fun leaveCircle(): LeaveResult {
        val s = session ?: return LeaveResult.NotSent
        val view = replica.fold(s.circle, crypto, cipher, s.identity)
        val me = s.identity.member
        if (me in view.stewards) return LeaveResult.StewardMustStepDown
        val day = today()
        s.author.keyRing = view.keys
        view.devices.values.filter { !it.revoked && it.registration.member == me }
            .forEach { s.engine.enqueue(s.circle, s.author.deviceRevoke(s.circle, it.registration.device, day)) }
        val report = s.engine.sync(s.circle)
        // Once the revocations are accepted this device can no longer pull, so the state afterwards says nothing; what
        // matters is that the push emptied the outbox and nothing was refused.
        if (replica.outbox(s.circle).isNotEmpty() || replica.refused(s.circle).isNotEmpty()) {
            afterSync(report)
            return LeaveResult.NotSent
        }
        erase()
        return LeaveResult.Left
    }

    /** A new browser on this member's account: makes its own keys for [link]'s member and waits to be added (ADR 0046). */
    suspend fun startOnNewDevice(link: DeviceLink, passphrase: String): StartResult {
        val identity = when (val created = vault.create(passphrase, link.member)) {
            is CreateResult.Created -> created.identity
            is CreateResult.PassphraseTooShort -> return StartResult.PassphraseTooShort(created.minimum)
            CreateResult.AlreadyHasIdentity -> return StartResult.AlreadyHasIdentity
        }
        keyStore.load()?.let { keyStore.save(it.copy(circle = link.circle.v, pairing = true)) }
        activate(identity, link.circle)
        refresh()
        return StartResult.Started(link.circle)
    }

    /** The link an existing device shows for a new browser to open (public ids only). Null while locked. */
    fun deviceLink(origin: String): String? = session?.let { DeviceLink(it.circle, it.identity.member).format(origin) }

    /** What a pasted device code names, for the member to compare with the other screen; null if it is not a device code. */
    fun previewDeviceCode(text: String): DevicePreview? =
        DeviceCode.parse(text)?.let { DevicePreview(it.device, Fingerprint.of(crypto, it.signPub)) }

    /**
     * Adds the device named by [codeText] to this member (ADR 0046): `device.add` with its name sealed, a wrap of every circle
     * key this device holds, and a wrap of the key of each item this member owns, all in one batch.
     */
    suspend fun addDevice(codeText: String, name: String): AddDeviceResult {
        val s = session ?: return AddDeviceResult.NotAllowed
        val code = DeviceCode.parse(codeText) ?: return AddDeviceResult.BadCode
        if (name.trim().length > MAX_DEVICE_NAME_CHARS) return AddDeviceResult.NameTooLong
        val view = replica.fold(s.circle, crypto, cipher, s.identity)
        if (view.currentEpoch < FIRST_EPOCH || view.keys[view.currentEpoch] == null) return AddDeviceResult.NotLetInYet
        if (view.devices.containsKey(code.device)) return AddDeviceResult.AlreadyKnown
        val registration = circles.event.DeviceRegistration(s.identity.member, code.device, code.signPub, code.boxPub)
        val batch = registrationEvents(s.author, s.circle, s.identity.member, view, registration, name.trim().ifEmpty { null })
        batch.events.forEach { s.engine.enqueue(s.circle, it) }
        s.scheduler.trigger(SyncTrigger.LOCAL_WRITE)
        refresh()
        return AddDeviceResult.Added(batch.keysWrapped, batch.itemsShared)
    }

    private class RegistrationBatch(val events: List<circles.event.Envelope>, val keysWrapped: Int, val itemsShared: Int)

    /** The events that make [registration] another device of [me]: `device.add`, a wrap of every epoch held, and the owned items' keys. */
    private fun registrationEvents(
        author: EventAuthor,
        circle: CircleId,
        me: MemberId,
        view: circles.event.CircleView,
        registration: circles.event.DeviceRegistration,
        name: String?,
    ): RegistrationBatch {
        val day = today()
        author.keyRing = view.keys
        val events = mutableListOf(author.deviceAdd(circle, registration, day, name))
        var wrapped = 0
        for (epoch in FIRST_EPOCH..view.keys.latestEpoch) {
            val key = view.keys[epoch] ?: continue
            events += author.keyWrap(circle, registration, epoch, key, day)
            wrapped++
        }
        var shared = 0
        for (item in view.market.items.values.filter { it.owner == me && !it.retired }) {
            val itemKey = view.market.itemKeys[item.item to item.detailKeyId] ?: continue
            events += author.itemUpsert(circle, day, item.item, item.name, item.itemClass, item.detailKeyId, itemKey, listOf(registration), item.minTier, item.photos)
            shared++
        }
        return RegistrationBatch(events, wrapped, shared)
    }

    // ---- item photos (ADR 0060) ----

    private val photoCache = LinkedHashMap<String, ByteArray>()

    /**
     * Adds a photo to one of this member's items. [photo] and [thumb] are the finished encoded images (the page prepares them
     * under ADR 0028); they are sealed with the circle key, uploaded, and only then does the item name them. A blank [alt] becomes the item's name.
     */
    suspend fun addPhoto(item: ItemId, alt: String, photo: ByteArray, thumb: ByteArray): PhotoResult {
        val s = session ?: return PhotoResult.NotAllowed
        val blobs = s.blobs ?: return PhotoResult.NotAllowed
        val view = replica.fold(s.circle, crypto, cipher, s.identity)
        val state = view.market.items[item]?.takeIf { it.owner == s.identity.member && !it.retired } ?: return PhotoResult.NotAllowed
        if (state.photos.size >= circles.event.MAX_PHOTOS_PER_ITEM) return PhotoResult.TooMany
        val epoch = view.currentEpoch
        val key = view.keys[epoch] ?: return PhotoResult.NotAllowed
        val sealedPhoto = circles.event.BlobSealing.seal(crypto, cipher, key, s.circle, photo) ?: return PhotoResult.TooBig
        val sealedThumb = circles.event.BlobSealing.seal(crypto, cipher, key, s.circle, thumb) ?: return PhotoResult.TooBig
        for (blob in listOf(sealedPhoto, sealedThumb)) {
            when (val put = blobs.put(s.circle, blob.hash, blob.bytes.toByteArray())) {
                circles.sync.BlobPut.Stored -> Unit
                circles.sync.BlobPut.Offline -> return PhotoResult.Offline
                circles.sync.BlobPut.Full -> return PhotoResult.Full
                is circles.sync.BlobPut.Refused -> return PhotoResult.Refused("the server refused the photo (${put.status})")
            }
        }
        val description = alt.trim().ifEmpty { state.name }.take(circles.event.MAX_PHOTO_ALT_CHARS)
        return savePhotos(s, view, state, state.photos + circles.event.ItemPhoto(sealedPhoto.hash, sealedThumb.hash, epoch, description))
    }

    /** Takes a photo off the item. The sealed file stays on the server until the circle is exported and imported (ADR 0026). */
    suspend fun removePhoto(item: ItemId, photoHash: String): PhotoResult {
        val s = session ?: return PhotoResult.NotAllowed
        val view = replica.fold(s.circle, crypto, cipher, s.identity)
        val state = view.market.items[item]?.takeIf { it.owner == s.identity.member && !it.retired } ?: return PhotoResult.NotAllowed
        if (state.photos.none { it.photo == photoHash }) return PhotoResult.NotAllowed
        return savePhotos(s, view, state, state.photos.filter { it.photo != photoHash })
    }

    private suspend fun savePhotos(s: Session, view: circles.event.CircleView, state: circles.event.ItemState, photos: List<circles.event.ItemPhoto>): PhotoResult {
        val key = view.market.itemKeys[state.item to state.detailKeyId] ?: return PhotoResult.NotAllowed
        val description = ((view.market.details[state.item] as? Parsed.Ok)?.value?.get(DETAIL_DESCRIPTION) as? CborText)?.value.orEmpty()
        s.author.keyRing = view.keys
        saveItem(s, view, state.item, state.detailKeyId, key, state.name, state.itemClass, description, state.minTier, photos)
        return PhotoResult.Saved
    }

    /** The decrypted image bytes of a photo or thumbnail, fetched on demand and remembered; null if offline or unreadable. */
    suspend fun photoBytes(hash: String, epoch: Int): ByteArray? {
        photoCache[hash]?.let { return it }
        val s = session ?: return null
        val blobs = s.blobs ?: return null
        val key = s.author.keyRing[epoch] ?: return null // the keys of the last refresh, so a page of thumbnails does not fold the log for each
        val sealed = circles.encoding.ByteString.of(blobs.get(s.circle, hash) ?: return null)
        if (!circles.event.BlobSealing.matchesHash(crypto, hash, sealed)) return null
        val plain = (circles.event.BlobSealing.open(cipher, key, s.circle, sealed) as? Parsed.Ok)?.value ?: return null
        photoCache[hash] = plain
        if (photoCache.size > PHOTO_CACHE_ENTRIES) photoCache.remove(photoCache.keys.first())
        return plain
    }

    // ---- skill offers (ADR 0055) ----

    private suspend fun sendSkill(event: circles.event.Envelope, skill: circles.event.SkillId): SkillResult {
        val (s, _) = pauseSession() ?: return SkillResult.NotAllowed
        return when (val result = sendPause(s, event)) {
            PauseResult.Sent -> SkillResult.Saved(skill)
            is PauseResult.Refused -> SkillResult.Refused(result.reason)
            PauseResult.NotAllowed -> SkillResult.NotAllowed
        }
    }

    private fun skillProblem(name: String, details: String): String? = when {
        name.isBlank() || name.trim().length > circles.event.MAX_SKILL_NAME_CHARS -> "give it a name of 1 to ${circles.event.MAX_SKILL_NAME_CHARS} characters"
        details.trim().length > circles.event.MAX_SKILL_DETAILS_CHARS -> "keep the details to ${circles.event.MAX_SKILL_DETAILS_CHARS} characters"
        else -> null
    }

    /** Offers a skill to the circle (ADR 0055); [openTo] says what in return would be welcome, and may be empty. */
    suspend fun addSkill(name: String, details: String, openTo: Set<circles.event.OpenTo>): SkillResult {
        skillProblem(name, details)?.let { return SkillResult.Refused(it) }
        val (s, _) = pauseSession() ?: return SkillResult.NotAllowed
        val id = circles.event.newSkillId(crypto)
        return sendSkill(s.author.skillUpsert(s.circle, today(), id, name.trim(), details.trim(), openTo), id)
    }

    /** Replaces the author's own offer. */
    suspend fun editSkill(skill: circles.event.SkillId, name: String, details: String, openTo: Set<circles.event.OpenTo>): SkillResult {
        skillProblem(name, details)?.let { return SkillResult.Refused(it) }
        val (s, _) = pauseSession() ?: return SkillResult.NotAllowed
        return sendSkill(s.author.skillUpsert(s.circle, today(), skill, name.trim(), details.trim(), openTo), skill)
    }

    suspend fun retireSkill(skill: circles.event.SkillId): SkillResult {
        val (s, _) = pauseSession() ?: return SkillResult.NotAllowed
        return sendSkill(s.author.skillRetire(s.circle, today(), skill), skill)
    }

    // ---- display names (ADR 0068) ----

    /** Sets or changes my display name. It is tidied first (NFKC, single spaces); a change clears its confirmations. */
    suspend fun setMyName(name: String): NameResult {
        val tidy = circles.event.DisplayNames.tidy(name) { circles.text.nfkc(it) }
        circles.event.DisplayNames.problem(tidy)?.let { return NameResult.Refused(it) }
        val (s, _) = pauseSession() ?: return if (session == null) NameResult.NotAllowed else NameResult.NotLetInYet
        return nameResult(sendPause(s, s.author.memberProfile(s.circle, today(), tidy)))
    }

    /** Confirms [member]'s current name, after checking their key fingerprint in person (ADR 0068). */
    suspend fun confirmName(member: MemberId): NameResult {
        val (s, view) = pauseSession() ?: return NameResult.NotAllowed
        val current = view.market.names[member] ?: return NameResult.NotAllowed
        return nameResult(sendPause(s, s.author.confirmName(s.circle, today(), member, current.profile, current.name)))
    }

    private fun nameResult(result: PauseResult): NameResult = when (result) {
        PauseResult.Sent -> NameResult.Saved
        is PauseResult.Refused -> NameResult.Refused(result.reason)
        PauseResult.NotAllowed -> NameResult.NotAllowed
    }

    // ---- contribution notes (ADR 0053) ----

    private fun contributionsFor(view: circles.event.CircleView, me: MemberId, now: Int): ContributionsSummary {
        fun summary(c: circles.event.ContributionState, other: MemberId) = NoteSummary(
            c.note, c.kind, other, c.text, c.day,
            when {
                c.confirmedDay != null -> NoteStatus.COUNTED
                now <= c.day + circles.event.NOTE_WINDOW_DAYS -> NoteStatus.WAITING
                else -> NoteStatus.LAPSED
            },
        )
        val all = view.market.contributions
        val recent = all.count { it.author == me && it.day > now - circles.event.NOTE_WINDOW_DAYS }
        return ContributionsSummary(
            toConfirm = all.filter { it.with == me && summary(it, it.author).status == NoteStatus.WAITING }.map { summary(it, it.author) },
            mine = all.filter { it.author == me }.takeLast(MAX_NOTES_SHOWN).map { summary(it, it.with) },
            candidates = view.devices.values.filter { !it.revoked }.map { it.registration.member }.distinct().filter { it != me },
            notesLeft = (circles.event.MAX_NOTES_PER_WINDOW - recent).coerceAtLeast(0),
        )
    }

    private suspend fun sendNote(event: circles.event.Envelope): NoteResult {
        val (s, _) = pauseSession() ?: return NoteResult.NotAllowed
        return when (val result = sendPause(s, event)) {
            PauseResult.Sent -> NoteResult.Sent
            is PauseResult.Refused -> NoteResult.Refused(result.reason)
            PauseResult.NotAllowed -> NoteResult.NotAllowed
        }
    }

    /** Notes that I did [kind] for or with [with]; it counts once they confirm (ADR 0053). */
    suspend fun addContribution(kind: circles.event.ContributionKind, with: MemberId, text: String): NoteResult {
        val (s, _) = pauseSession() ?: return NoteResult.NotAllowed
        if (text.trim().length > circles.event.MAX_NOTE_TEXT_CHARS) return NoteResult.Refused("keep it to ${circles.event.MAX_NOTE_TEXT_CHARS} characters")
        return sendNote(s.author.standingNote(s.circle, today(), circles.event.newNoteId(crypto), kind, with, text.trim()))
    }

    /** The named person confirms a note; it then counts for its author. */
    suspend fun confirmContribution(note: circles.event.NoteId): NoteResult {
        val (s, _) = pauseSession() ?: return NoteResult.NotAllowed
        return sendNote(s.author.confirmNote(s.circle, today(), note))
    }

    // ---- circle rules (ADR 0051) ----

    private fun rulesFor(circle: circles.policy.CircleId, view: circles.event.CircleView, me: MemberId, now: Int): RulesSummary {
        val needed = minOf(view.stewardSettings.quorum, view.stewards.size).coerceAtLeast(1)
        val since = view.stewardSince[me]
        val coolingOff = view.stewardSettings.quorum > 1 && since != null && since != circles.event.FOUNDING_STEWARD_DAY && now < since + circles.event.DEFAULT_STEWARD_COOL_OFF_DAYS
        return RulesSummary(
            circles.event.ConfigSettings.of(view.market.configOn(circle, now)),
            view.market.pendingConfigs.filter { now <= it.firstDay + circles.event.DEFAULT_STEWARD_PROPOSAL_DAYS }.map {
                RulesProposal(it.settings, it.voters.size, needed, me in it.voters, me in view.stewards && me !in it.voters && !coolingOff)
            },
        )
    }

    /** A steward's vote for these circle rules; the same call agrees to someone else's proposal (ADR 0051). */
    suspend fun proposeRules(settings: circles.event.ConfigSettings): RulesResult {
        val (s, view) = pauseSession() ?: return RulesResult.NotAllowed
        if (s.identity.member !in view.stewards) return RulesResult.NotAllowed
        val event = s.author.configUpdate(s.circle, today(), settings)
        val id = event.eventId(crypto)
        s.engine.enqueue(s.circle, event)
        val after = replica.fold(s.circle, crypto, cipher, s.identity)
        val refused = after.rejected.firstOrNull { it.id == id }
        if (refused != null) {
            replica.dropFromOutbox(s.circle, setOf(id))
            return RulesResult.Refused(refused.reason)
        }
        s.scheduler.trigger(SyncTrigger.LOCAL_WRITE)
        refresh()
        return RulesResult.Sent(takesEffect = after.market.configs.any { it.settings == settings && it.fromDay == today() })
    }

    // ---- pauses (ADR 0050) ----

    private fun pausesFor(circle: circles.policy.CircleId, view: circles.event.CircleView, me: MemberId, myTier: circles.policy.Tier, now: Int): List<PauseSummary> {
        val cfg = view.market.configOn(circle, now)
        val mayReview = myTier.rank >= cfg.pauseReviewerMinTier.rank
        return view.market.pauses.mapNotNull { pause ->
            val status = pause.statusOn(now)
            if (status != circles.event.PauseStatus.PROPOSED && status != circles.event.PauseStatus.LIVE) return@mapNotNull null
            val isSubject = pause.subject == me
            if (!isSubject && !mayReview) return@mapNotNull null
            val answer = pause.reviews[me]
            val isSteward = me in view.stewards
            PauseSummary(
                pause = pause.pause, role = if (isSubject) PauseRole.SUBJECT else PauseRole.REVIEWER, subject = pause.subject,
                reason = pause.reason, days = pause.days, status = status, agreeing = pause.agreeing.size, needed = pause.needed,
                iAgree = answer, untilDay = pause.untilDay, appeal = pause.appealNote, proposedByMe = pause.proposer == me,
                reviewers = if (isSubject) pause.agreeing.toList() else emptyList(),
                canReview = !isSubject && status == circles.event.PauseStatus.PROPOSED && answer != true,
                canStepBack = !isSubject && status == circles.event.PauseStatus.LIVE && answer == true,
                canAppeal = isSubject && pause.appealNote == null,
                canLift = !isSubject && ((answer == true && pause.appealNote != null) || (isSteward && pause.proposer != me) || (pause.proposer == me && status == circles.event.PauseStatus.PROPOSED)),
            )
        }
    }

    private fun pauseHistoryFor(circle: circles.policy.CircleId, view: circles.event.CircleView, me: MemberId, myTier: circles.policy.Tier, now: Int): List<PauseHistoryEntry> {
        val cfg = view.market.configOn(circle, now)
        val mayReview = myTier.rank >= cfg.pauseReviewerMinTier.rank
        return view.market.pauses.mapNotNull { pause ->
            val status = pause.statusOn(now)
            if (status == circles.event.PauseStatus.PROPOSED || status == circles.event.PauseStatus.LIVE) return@mapNotNull null
            // A proposal that never took effect leaves no mark on the member: it is not kept in the history.
            if (pause.liveFrom == null) return@mapNotNull null
            val isSubject = pause.subject == me
            if (!isSubject && !mayReview) return@mapNotNull null
            val endedDay = when (status) {
                circles.event.PauseStatus.LIFTED -> pause.liftedDay ?: pause.proposedDay
                circles.event.PauseStatus.ENDED -> (pause.untilDay ?: pause.proposedDay) - 1
                else -> pause.proposedDay + circles.event.PAUSE_PROPOSAL_WINDOW_DAYS
            }
            PauseHistoryEntry(
                pause = pause.pause, role = if (isSubject) PauseRole.SUBJECT else PauseRole.REVIEWER, subject = pause.subject,
                reason = pause.reason, days = pause.days, status = status, proposedDay = pause.proposedDay,
                startedDay = pause.liveFrom, endedDay = endedDay, appeal = pause.appealNote,
            )
        }.sortedByDescending { it.endedDay }
    }

    /** Sends a pause event if the circle's rules would accept it; otherwise takes it back out of the outbox and says why. */
    private suspend fun sendPause(s: Session, event: circles.event.Envelope): PauseResult {
        val id = event.eventId(crypto)
        s.engine.enqueue(s.circle, event)
        val refused = replica.fold(s.circle, crypto, cipher, s.identity).rejected.firstOrNull { it.id == id }
        if (refused != null) {
            replica.dropFromOutbox(s.circle, setOf(id))
            return PauseResult.Refused(refused.reason)
        }
        s.scheduler.trigger(SyncTrigger.LOCAL_WRITE)
        refresh()
        return PauseResult.Sent
    }

    private suspend fun pauseSession(): Pair<Session, circles.event.CircleView>? {
        val s = session ?: return null
        val view = replica.fold(s.circle, crypto, cipher, s.identity)
        if (view.currentEpoch < FIRST_EPOCH || view.keys[view.currentEpoch] == null) return null
        s.author.keyRing = view.keys
        return s to view
    }

    /** Proposes pausing [subject]'s access to anything but essentials for [days] days (ADR 0050). The proposer counts as the first reviewer. */
    suspend fun proposePause(subject: MemberId, reason: String, days: Int): PauseResult {
        val (s, _) = pauseSession() ?: return PauseResult.NotAllowed
        if (reason.isBlank() || reason.trim().length > circles.event.MAX_PAUSE_REASON_CHARS) return PauseResult.Refused("give a reason of 1 to ${circles.event.MAX_PAUSE_REASON_CHARS} characters")
        if (days !in circles.event.MIN_PAUSE_DAYS..circles.event.MAX_PAUSE_DAYS) return PauseResult.Refused("choose from ${circles.event.MIN_PAUSE_DAYS} to ${circles.event.MAX_PAUSE_DAYS} days")
        return sendPause(s, s.author.pausePropose(s.circle, today(), circles.event.newPauseId(crypto), subject, reason.trim(), days))
    }

    /** A reviewer agrees ([agree] true), or says they do not, or steps back from a pause already in force. */
    suspend fun reviewPause(pause: circles.event.PauseId, agree: Boolean): PauseResult {
        val (s, _) = pauseSession() ?: return PauseResult.NotAllowed
        return sendPause(s, s.author.pauseReview(s.circle, today(), pause, agree))
    }

    /** The subject's one note to the reviewers. */
    suspend fun appealPause(pause: circles.event.PauseId, note: String): PauseResult {
        val (s, _) = pauseSession() ?: return PauseResult.NotAllowed
        if (note.isBlank() || note.trim().length > circles.event.MAX_PAUSE_APPEAL_CHARS) return PauseResult.Refused("write a note of 1 to ${circles.event.MAX_PAUSE_APPEAL_CHARS} characters")
        return sendPause(s, s.author.pauseAppeal(s.circle, today(), pause, note.trim()))
    }

    suspend fun liftPause(pause: circles.event.PauseId): PauseResult {
        val (s, _) = pauseSession() ?: return PauseResult.NotAllowed
        return sendPause(s, s.author.pauseLift(s.circle, today(), pause))
    }

    // ---- export (ADR 0030) ----

    /**
     * Builds `circles-export/1` from the log this device holds: ciphertext, public parts and signatures only, no blobs (the
     * device keeps none), so a new host can be seeded from it. Refuses when the copy has gaps, because the importer replays
     * from the first event.
     */
    suspend fun exportCircle(): ExportResult {
        val s = session ?: return ExportResult.NotAllowed
        val entries = replica.entries(s.circle, 0).sortedBy { it.serverSeq }
        if (entries.isEmpty() || entries.withIndex().any { (index, entry) -> entry.serverSeq != index + 1L }) return ExportResult.Incomplete
        // Photos (ADR 0060): the sealed files the items now show, as the server holds them (still sealed; the export never holds a plain image).
        val wanted = replica.fold(s.circle, crypto, cipher, s.identity).market.items.values.filter { !it.retired }.flatMap { item -> item.photos.flatMap { listOf(it.photo, it.thumb) } }.distinct()
        val held = linkedMapOf<String, ByteArray>()
        var used = 0L
        var missing = 0
        for (hash in wanted) {
            val sealed = s.blobs?.get(s.circle, hash)
            val ok = sealed != null && circles.event.BlobSealing.matchesHash(crypto, hash, circles.encoding.ByteString.of(sealed)) && used + sealed.size <= exportPhotoBytes
            if (ok && sealed != null) { held[hash] = sealed; used += sealed.size } else missing++
        }
        val refs = held.map { (hash, bytes) -> circles.export.ExportBlobRef(hash, bytes.size.toLong()) }
        val built = circles.export.CircleExport.build(crypto, s.circle, entries.map { it.envelope.toDto() }, refs)
        val name = "circles-export-${s.circle.v.take(EXPORT_NAME_ID_CHARS)}-${CalendarDay.format(today())}.zip"
        val bytes = circles.export.ZipStore.write(built.entries(held))
        if (bytes != null) return ExportResult.Ready(name, bytes, entries.size, held.size, missing)
        // Too big for the zip format with photos: send the log alone rather than nothing, and say the photos are out.
        val bare = circles.export.CircleExport.build(crypto, s.circle, entries.map { it.envelope.toDto() }, emptyList())
        val logOnly = circles.export.ZipStore.write(bare.entries(emptyMap())) ?: return ExportResult.Incomplete
        return ExportResult.Ready(name, logOnly, entries.size, 0, wanted.size)
    }

    // ---- recovery shares (ADR 0048) ----

    /**
     * The shares to send with a new key (or when the set is out of date), when the circle asks for them: one per steward,
     * to each steward's devices in [devices]; any number of stewards below two, or a steward with no device, sends none.
     */
    private fun automaticShares(s: Session, view: circles.event.CircleView, epoch: Int, devices: List<circles.event.DeviceRegistration>, day: Int): List<circles.event.Envelope> {
        val threshold = view.stewardSettings.recoveryThreshold
        if (threshold == circles.event.NO_RECOVERY_SHARES || view.stewards.size < MIN_RECOVERY_THRESHOLD) return emptyList()
        val perSteward = view.stewards.sortedBy { it.v }.map { steward -> devices.filter { it.member == steward } }
        if (perSteward.any { it.isEmpty() }) return emptyList()
        return s.author.recoverySharesPerSteward(s.circle, epoch, perSteward, minOf(threshold, perSteward.size), day).orEmpty()
    }

    /**
     * After a sync (ADR 0049): for each of this member's items that a member who has since left was approved to borrow,
     * change the item's detail key, sending the new one to this member's devices and to borrowers whose loan is still open,
     * and seal the details again. Does nothing once the key has changed, and nothing after a refused `item.upsert`.
     */
    private suspend fun rotateItemKeysAfterRemovals(s: Session, view: circles.event.CircleView) {
        val departed = view.market.departed.keys
        if (departed.isEmpty() || replica.refused(s.circle).any { it.type == EventType.ITEM_UPSERT.wire }) return
        val me = s.identity.member
        val live = view.devices.values.filter { !it.revoked }.map { it.registration }
        val day = today()
        var changed = false
        for (item in view.market.items.values.filter { it.owner == me && !it.retired }) {
            val exposed = view.market.loans.values.any { it.item == item.item && it.borrower in departed && it.detailKeyId == item.detailKeyId }
            if (!exposed) continue
            val description = ((view.market.details[item.item] as? Parsed.Ok)?.value?.get(DETAIL_DESCRIPTION) as? CborText)?.value ?: continue
            val open = view.market.loans.values
                .filter { it.item == item.item && it.borrower !in departed && (it.status == LoanStatus.APPROVED || it.status == LoanStatus.ACTIVE) }
                .map { it.borrower }.toSet()
            val holders = live.filter { it.member == me || it.member in open }
            val keyId = newDetailKeyId(crypto)
            val key = ItemKeys.newKey(crypto)
            s.author.keyRing = view.keys
            s.engine.enqueue(s.circle, s.author.itemUpsert(s.circle, day, item.item, item.name, item.itemClass, keyId, key, holders, item.minTier, item.photos))
            s.engine.enqueue(s.circle, s.author.itemDetail(s.circle, day, item.item, keyId, key, CborMap.of(DETAIL_DESCRIPTION to CborText(description))))
            changed = true
        }
        if (changed) s.scheduler.trigger(SyncTrigger.LOCAL_WRITE)
    }

    /**
     * After a sync: if the circle asks for shares and the newest set does not cover exactly today's stewards, the steward
     * who sorts first sends a fresh set. Only that one steward acts, so two devices do not make competing sets; the
     * "make shares now" button is the way out if they are away. Does nothing after a refused share, so it cannot loop.
     */
    private suspend fun sendMissingShares(s: Session, view: circles.event.CircleView) {
        val epoch = view.currentEpoch
        if (view.stewardSettings.recoveryThreshold == circles.event.NO_RECOVERY_SHARES || epoch < FIRST_EPOCH || view.keys[epoch] == null) return
        if (s.identity.member != view.stewards.minByOrNull { it.v }) return
        if (view.shareHolders[epoch].orEmpty() == view.stewards) return
        if (replica.refused(s.circle).any { it.type == circles.event.EventType.RECOVERY_SHARE.wire }) return
        s.author.keyRing = view.keys
        val live = view.devices.values.filter { !it.revoked }.map { it.registration }
        val events = automaticShares(s, view, epoch, live, today())
        if (events.isEmpty()) return
        events.forEach { s.engine.enqueue(s.circle, it) }
        s.scheduler.trigger(SyncTrigger.LOCAL_WRITE)
    }

    private fun recoverySharesOf(view: circles.event.CircleView, me: MemberId): RecoverySharesSummary {
        val current = view.currentEpoch
        val isSteward = me in view.stewards
        return RecoverySharesSummary(
            heldEpochs = view.heldShares.keys.sorted(),
            currentEpoch = current,
            currentSetSent = current >= FIRST_EPOCH && view.shareHolders[current].orEmpty() == view.stewards,
            mayMake = isSteward && current >= FIRST_EPOCH && view.keys[current] != null && view.stewards.size >= MIN_RECOVERY_THRESHOLD,
            missingKeyEpochs = if (isSteward) (FIRST_EPOCH..current).filter { view.keys[it] == null && view.commitments[it] != null } else emptyList(),
            maxThreshold = maxOf(view.stewards.size, MIN_RECOVERY_THRESHOLD),
            automaticThreshold = view.stewardSettings.recoveryThreshold,
        )
    }

    /**
     * Splits the current circle key so that any [threshold] stewards can rebuild it, and sends one share to each steward,
     * sealed to each of their devices (ADR 0048).
     */
    suspend fun makeRecoveryShares(threshold: Int): ShareMakingResult {
        val s = session ?: return ShareMakingResult.NotAllowed
        val view = replica.fold(s.circle, crypto, cipher, s.identity)
        if (s.identity.member !in view.stewards) return ShareMakingResult.NotAllowed
        val epoch = view.currentEpoch
        if (epoch < FIRST_EPOCH || view.keys[epoch] == null) return ShareMakingResult.NoKey
        val perSteward = view.stewards.map { steward -> view.devices.values.filter { !it.revoked && it.registration.member == steward }.map { it.registration } }
        if (threshold !in MIN_RECOVERY_THRESHOLD..perSteward.size) return ShareMakingResult.BadThreshold(MIN_RECOVERY_THRESHOLD, perSteward.size)
        s.author.keyRing = view.keys
        val events = s.author.recoverySharesPerSteward(s.circle, epoch, perSteward, threshold, today()) ?: return ShareMakingResult.NotAllowed
        events.forEach { s.engine.enqueue(s.circle, it) }
        s.scheduler.trigger(SyncTrigger.LOCAL_WRITE)
        refresh()
        return ShareMakingResult.Sent(perSteward.size, threshold)
    }

    /** The text code of the share this device holds for [epoch], to read out to the steward who is rebuilding the key. */
    suspend fun shareCode(epoch: Int): String? {
        val s = session ?: return null
        val held = replica.fold(s.circle, crypto, cipher, s.identity).heldShares[epoch] ?: return null
        return circles.event.ShareCode.format(crypto, epoch, held.share)
    }

    /**
     * Combines share codes from enough stewards, checks the result against the epoch's public commitment, and wraps the
     * key to this device (ADR 0048). Nothing is sent unless the key is right.
     */
    suspend fun rebuildKey(codes: List<String>): RebuildResult {
        val s = session ?: return RebuildResult.NotAllowed
        val view = replica.fold(s.circle, crypto, cipher, s.identity)
        val me = s.identity.member
        if (me !in view.stewards) return RebuildResult.NotAllowed
        val typed = codes.filter { it.isNotBlank() }
        if (typed.isEmpty()) return RebuildResult.NoCodes
        val contents = typed.mapIndexed { index, text ->
            when (val parsed = circles.event.ShareCode.parse(crypto, text)) {
                is circles.event.ShareCodeResult.Valid -> parsed.content
                is circles.event.ShareCodeResult.Mistyped -> return RebuildResult.BadCode(index + 1, mistyped = true)
                is circles.event.ShareCodeResult.NotACode -> return RebuildResult.BadCode(index + 1, mistyped = false)
            }
        }
        val epoch = contents.first().epoch
        if (contents.any { it.epoch != epoch }) return RebuildResult.DifferentEpochs
        if (view.keys[epoch] != null) return RebuildResult.AlreadyHaveKey
        val commitment = view.commitments[epoch] ?: return RebuildResult.Wrong("This circle has no key for epoch $epoch.")
        val me0 = view.devices[s.identity.device]?.takeIf { !it.revoked }?.registration ?: return RebuildResult.NotAllowed
        val key = when (val rebuilt = circles.event.RecoveryShares.rebuild(crypto, s.circle, epoch, commitment, contents.map { it.share })) {
            is Parsed.Ok<circles.encoding.ByteString> -> rebuilt.value
            is Parsed.Invalid -> return RebuildResult.Wrong(rebuilt.reason)
        }
        s.engine.enqueue(s.circle, s.author.keyWrap(s.circle, me0, epoch, key, today(), commit = false))
        s.scheduler.trigger(SyncTrigger.LOCAL_WRITE)
        refresh()
        return RebuildResult.Rebuilt(epoch)
    }

    // ---- recovery code (ADR 0047) ----

    /** Step one of making a recovery code: new words to write down and the places to type back. Nothing is sent yet. */
    suspend fun prepareRecoveryCode(origin: String): RecoveryDraft? {
        val s = session ?: return null
        val list = wordlist().takeIf { it.size == circles.keys.RECOVERY_WORDLIST_SIZE } ?: return null
        val entropy = circles.keys.RecoveryCodes.newEntropy(crypto)
        val words = circles.keys.RecoveryCodes.wordsOf(crypto, list, entropy)
        val pool = (1..circles.keys.RECOVERY_WORDS).toMutableList()
        val bytes = crypto.randomBytes(RECOVERY_CHECK_WORDS)
        val places = List(RECOVERY_CHECK_WORDS) { index -> pool.removeAt((bytes[index].toInt() and 0xFF) % pool.size) }.sorted()
        pendingRecovery = Triple(entropy, places, words)
        return RecoveryDraft(words, places, RestoreLink(s.circle, s.identity.member).format(origin))
    }

    /**
     * Step two: the member types back the words asked for; if they match, the recovery device is registered like any other
     * device, and any earlier recovery device is removed in the same batch (a steward also changes the key at once).
     */
    suspend fun confirmRecoveryCode(origin: String, typedWords: List<String>): RecoveryCodeResult {
        val s = session ?: return RecoveryCodeResult.NotAllowed
        val (entropy, places, words) = pendingRecovery ?: return RecoveryCodeResult.NoDraft
        if (typedWords.size != places.size || places.indices.any { typedWords[it].trim().lowercase() != words[places[it] - 1] }) return RecoveryCodeResult.WrongWords
        val view = replica.fold(s.circle, crypto, cipher, s.identity)
        if (view.currentEpoch < FIRST_EPOCH || view.keys[view.currentEpoch] == null) return RecoveryCodeResult.NotLetInYet
        val me = s.identity.member
        val registration = circles.keys.RecoveryCodes.identityOf(crypto, cipher, entropy, me).registration
        val names = deviceNames(view)
        val old = view.devices.values.filter { !it.revoked && it.registration.member == me && names[it.registration.device] == RECOVERY_DEVICE_NAME }
        val batch = registrationEvents(s.author, s.circle, me, view, registration, RECOVERY_DEVICE_NAME)
        val events = batch.events.toMutableList()
        val day = today()
        old.forEach { events += s.author.deviceRevoke(s.circle, it.registration.device, day) }
        if (old.isNotEmpty() && me in view.stewards) {
            val remaining = view.devices.values.filter { state -> !state.revoked && old.none { it.registration.device == state.registration.device } }.map { it.registration } + registration
            events += s.author.rotateKeys(s.circle, view.currentEpoch + 1, remaining, day)
            events += automaticShares(s, view, view.currentEpoch + 1, remaining, day)
        }
        events.forEach { s.engine.enqueue(s.circle, it) }
        pendingRecovery = null
        s.scheduler.trigger(SyncTrigger.LOCAL_WRITE)
        refresh()
        return RecoveryCodeResult.Created(RestoreLink(s.circle, me).format(origin))
    }

    /** Forgets a recovery draft that was never confirmed. */
    fun discardRecoveryDraft() {
        pendingRecovery = null
    }

    /**
     * Restores a member from their recovery words in a browser with no identity (ADR 0047). It signs in as the recovery
     * device, registers a fresh ordinary device for this browser and removes the recovery device in one batch (so the used
     * paper is no longer a key), and then runs as the new device under [passphrase].
     */
    suspend fun restoreFromCode(link: RestoreLink, text: String, passphrase: String): RestoreResult {
        if (keyStore.load() != null) return RestoreResult.AlreadyHasIdentity
        (circles.keys.SeedWrapping.checkPassphrase(passphrase) as? circles.keys.PassphraseCheck.TooShort)?.let { return RestoreResult.PassphraseTooShort(it.minimum) }
        val list = wordlist().takeIf { it.size == circles.keys.RECOVERY_WORDLIST_SIZE } ?: return RestoreResult.WordlistUnavailable
        val entropy = when (val parsed = circles.keys.RecoveryCodes.parse(crypto, list, text)) {
            is circles.keys.RecoveryParse.Valid -> parsed.entropy
            else -> return RestoreResult.BadWords(parsed)
        }
        val recovery = circles.keys.RecoveryCodes.identityOf(crypto, cipher, entropy, link.member)
        val engine = SyncEngine(crypto, apiFor(recovery), replica)
        suspend fun fail(): RestoreResult {
            replica.clear()
            return RestoreResult.NotFound
        }
        if (engine.sync(link.circle).state != SyncState.UpToDate) return fail()
        val view = replica.fold(link.circle, crypto, cipher, recovery)
        val registered = view.devices[recovery.device]?.takeIf { !it.revoked && it.registration.member == link.member } ?: return fail()
        if (view.currentEpoch < FIRST_EPOCH || view.keys[view.currentEpoch] == null) return fail()
        // The recovery identity authors the hand-over: a new ordinary device for this browser, and the end of the recovery device.
        val author = EventAuthor(crypto, recovery, cipher, firstCounter = nextCounter(recovery, link.circle))
        val fresh = DeviceIdentity.generate(crypto, cipher, link.member)
        val batch = registrationEvents(author, link.circle, link.member, view, fresh.registration, null)
        val events = batch.events.toMutableList()
        val day = today()
        // The recovery device sends everything, so its own removal comes last: a revoked device can send nothing more.
        if (link.member in view.stewards) {
            val remaining = view.devices.values.filter { !it.revoked && it.registration.device != recovery.device }.map { it.registration } + fresh.registration
            events += author.rotateKeys(link.circle, view.currentEpoch + 1, remaining, day)
        }
        events += author.deviceRevoke(link.circle, registered.registration.device, day)
        events.forEach { engine.enqueue(link.circle, it) }
        engine.sync(link.circle)
        if (replica.outbox(link.circle).isNotEmpty() || replica.refused(link.circle).isNotEmpty()) return fail()
        // The hand-over is in the log. This browser now becomes the fresh device.
        when (vault.createFromSeed(passphrase, fresh)) {
            is CreateResult.Created -> Unit
            is CreateResult.PassphraseTooShort -> return RestoreResult.PassphraseTooShort(circles.keys.MIN_PASSPHRASE_CHARS)
            CreateResult.AlreadyHasIdentity -> return RestoreResult.AlreadyHasIdentity
        }
        rememberCircle(link.circle)
        activate(fresh, link.circle).scheduler.trigger(SyncTrigger.OPEN)
        refresh()
        return RestoreResult.Restored
    }

    /** Removes one of this member's other devices; a steward also changes the circle key in the same batch (ADR 0046, 0044). */
    suspend fun removeDevice(device: DeviceId): DeviceRemoval {
        val s = session ?: return DeviceRemoval.NotAllowed
        val view = replica.fold(s.circle, crypto, cipher, s.identity)
        val me = s.identity.member
        val target = view.devices[device]?.takeIf { !it.revoked && it.registration.member == me && device != s.identity.device } ?: return DeviceRemoval.NotAllowed
        val day = today()
        s.author.keyRing = view.keys
        val events = mutableListOf(s.author.deviceRevoke(s.circle, target.registration.device, day))
        if (me in view.stewards && view.currentEpoch >= FIRST_EPOCH) {
            val remaining = view.devices.values.filter { !it.revoked && it.registration.device != device }.map { it.registration }
            events += s.author.rotateKeys(s.circle, view.currentEpoch + 1, remaining, day)
        }
        events.forEach { s.engine.enqueue(s.circle, it) }
        s.scheduler.trigger(SyncTrigger.LOCAL_WRITE)
        refresh()
        return DeviceRemoval.Removed
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

    /** Unlocks with this browser's passkey (fingerprint, face or PIN) instead of the passphrase (ADR 0063). */
    suspend fun unlockWithPasskey(): UnlockResult {
        val provider = passkeys ?: return UnlockResult.Unsupported("this browser has no passkeys")
        val result = vault.unlockWithPasskey(provider)
        if (result is UnlockResult.Unlocked) {
            val identity = (vault.state.value as VaultState.Unlocked).identity
            val circle = keyStore.load()?.circle?.let(::CircleId) ?: return UnlockResult.NoIdentity
            activate(identity, circle).scheduler.trigger(SyncTrigger.OPEN)
            refresh()
        }
        return result
    }

    /** Turns on passkey unlocking for this browser; the passphrase keeps working. */
    suspend fun enablePasskey(): circles.keys.PasskeyResult =
        (passkeys?.let { vault.enablePasskey(it) } ?: circles.keys.PasskeyResult.Unavailable).also { refresh() }

    suspend fun disablePasskey() {
        vault.disablePasskey()
        refresh()
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
        replica.clear()
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
    suspend fun addItem(name: String, itemClass: ItemClass, description: String, minTier: Tier? = null): ItemResult {
        val s = session ?: return ItemResult.NotAllowed
        checkFields(name, description)?.let { return it }
        val view = replica.fold(s.circle, crypto, cipher, s.identity)
        if (view.currentEpoch < FIRST_EPOCH || view.keys[view.currentEpoch] == null) return ItemResult.NotLetInYet
        s.author.keyRing = view.keys
        return saveItem(s, view, newItemId(crypto), newDetailKeyId(crypto), ItemKeys.newKey(crypto), name.trim(), itemClass, description.trim(), minTier)
    }

    /** Changes an item's name, kind or description. Only its owner can, and only while this device holds the item's key. */
    suspend fun editItem(item: ItemId, name: String, itemClass: ItemClass, description: String, minTier: Tier? = null): ItemResult {
        val s = session ?: return ItemResult.NotAllowed
        checkFields(name, description)?.let { return it }
        val view = replica.fold(s.circle, crypto, cipher, s.identity)
        val state = view.market.items[item]?.takeIf { it.owner == s.identity.member && !it.retired } ?: return ItemResult.NotAllowed
        val key = view.market.itemKeys[item to state.detailKeyId] ?: return ItemResult.NotAllowed
        s.author.keyRing = view.keys
        return saveItem(s, view, item, state.detailKeyId, key, name.trim(), itemClass, description.trim(), minTier, state.photos)
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

    /** Vouches for another member for [VOUCH_DAYS] days (ADR 0042). Only members at Regular or above can. */
    suspend fun vouchFor(member: MemberId): VouchResult {
        val s = session ?: return VouchResult.NotAllowed
        val view = replica.fold(s.circle, crypto, cipher, s.identity)
        if (view.currentEpoch < FIRST_EPOCH || view.keys[view.currentEpoch] == null) return VouchResult.NotLetInYet
        val cfg = view.market.configOn(s.circle, today())
        val inCircle = view.devices.values.any { !it.revoked && it.registration.member == member }
        if (member == s.identity.member || !inCircle) return VouchResult.NotAllowed
        if (!StandingRules.mayVouch(s.identity.member, cfg, view.market.loans.values, view.market.vouchesOn(today()), view.stewards, today(), view.market.contributions)) return VouchResult.NotAllowed
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
        val state = view.market.items[item]?.takeIf { !it.retired && it.owner != s.identity.member && it.owner !in view.market.departed } ?: return LoanResult.NotAllowed
        // The same rule every client applies when folding (ADR 0042); asking first spares the member a request that would be ignored.
        val decision = StandingRules.decide(s.identity.member, state, view.market.configOn(s.circle, today()), view.market.loans.values, view.market.vouchesOn(today()), view.stewards, today(), view.market.livePausesOn(s.circle, today()), view.market.contributions)
        if (decision is AccessDecision.NotYet) return LoanResult.NotYet(decision.reasons, decision.howToUnlock)
        if (decision is AccessDecision.Paused) return LoanResult.Paused(decision.reasons)
        if (decision !is AccessDecision.Eligible) return LoanResult.NotAllowed
        if (view.market.loans.values.any { it.item == state.item && (it.status == LoanStatus.APPROVED || it.status == LoanStatus.ACTIVE) && it.fromDay <= toDay && fromDay <= it.toDay }) return LoanResult.AlreadyBooked
        s.author.keyRing = view.keys
        val loan = newLoanId(crypto)
        s.engine.enqueue(s.circle, s.author.loanRequest(s.circle, today(), loan, state.item, fromDay, toDay, note.trim().ifEmpty { null }))
        s.scheduler.trigger(SyncTrigger.LOCAL_WRITE)
        refresh()
        return LoanResult.Done(loan)
    }

    /** The owner answers a request. Approving wraps the item's key to the borrower's devices, so they can read its details. */
    suspend fun respondToLoan(loan: LoanId, approve: Boolean, note: String, fromDay: Int? = null, toDay: Int? = null): LoanResult {
        val s = session ?: return LoanResult.NotAllowed
        if (note.trim().length > MAX_LOAN_NOTE_CHARS) return LoanResult.NoteTooLong(MAX_LOAN_NOTE_CHARS)
        val view = replica.fold(s.circle, crypto, cipher, s.identity)
        val state = view.market.loans[loan]?.takeIf { it.owner == s.identity.member && it.status == LoanStatus.REQUESTED } ?: return LoanResult.NotAllowed
        // The owner may approve other dates than the ones asked for (ADR 0061); the borrower can cancel if they do not suit.
        val from = fromDay ?: state.fromDay
        val to = toDay ?: state.toDay
        if (approve && (fromDay != null || toDay != null)) windowProblem(from, to, mustStartTodayOrLater = true)?.let { return it }
        if (approve && view.market.loans.values.any { it.item == state.item && it.loan != loan && (it.status == LoanStatus.APPROVED || it.status == LoanStatus.ACTIVE) && it.fromDay <= to && from <= it.toDay }) return LoanResult.AlreadyBooked
        s.author.keyRing = view.keys
        val reply = note.trim().ifEmpty { null }
        val event = if (approve) {
            val item = view.market.items[state.item] ?: return LoanResult.NotAllowed
            val key = view.market.itemKeys[state.item to item.detailKeyId] ?: return LoanResult.NotAllowed
            val borrowerDevices = view.devices.values.filter { !it.revoked && it.registration.member == state.borrower }.map { it.registration }
            s.author.loanApprove(s.circle, today(), loan, state.item, from, to, key, borrowerDevices, reply)
        } else {
            s.author.loanDecline(s.circle, today(), loan, state.fromDay, state.toDay, reply)
        }
        s.engine.enqueue(s.circle, event)
        s.scheduler.trigger(SyncTrigger.LOCAL_WRITE)
        refresh()
        return LoanResult.Done(loan)
    }

    /** The borrower withdraws a request, or an approval before the hand-over (ADR 0052). */
    suspend fun cancelLoan(loan: LoanId): LoanResult {
        val s = session ?: return LoanResult.NotAllowed
        val view = replica.fold(s.circle, crypto, cipher, s.identity)
        view.market.loans[loan]?.takeIf { it.borrower == s.identity.member && (it.status == LoanStatus.REQUESTED || it.status == LoanStatus.APPROVED) } ?: return LoanResult.NotAllowed
        s.author.keyRing = view.keys
        s.engine.enqueue(s.circle, s.author.loanCancel(s.circle, today(), loan))
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

    /** The owner says the item came back as reported, so the return counts at once (ADR 0043). */
    suspend fun confirmReturn(loan: LoanId): LoanResult {
        val s = session ?: return LoanResult.NotAllowed
        val view = replica.fold(s.circle, crypto, cipher, s.identity)
        val state = view.market.loans[loan] ?: return LoanResult.NotAllowed
        if (!canConfirm(state, s.identity.member)) return LoanResult.NotAllowed
        s.author.keyRing = view.keys
        s.engine.enqueue(s.circle, s.author.confirmReturn(s.circle, today(), loan))
        s.scheduler.trigger(SyncTrigger.LOCAL_WRITE)
        refresh()
        return LoanResult.Done(loan)
    }

    /** The owner reports damage or loss the borrower did not mention; a steward who is not a party decides. */
    suspend fun disputeReturn(loan: LoanId, claim: DisputeClaim, note: String): LoanResult {
        val s = session ?: return LoanResult.NotAllowed
        if (note.trim().length > MAX_LOAN_NOTE_CHARS) return LoanResult.NoteTooLong(MAX_LOAN_NOTE_CHARS)
        val view = replica.fold(s.circle, crypto, cipher, s.identity)
        val state = view.market.loans[loan] ?: return LoanResult.NotAllowed
        if (!canDispute(state, s.identity.member, today())) return LoanResult.NotAllowed
        s.author.keyRing = view.keys
        s.engine.enqueue(s.circle, s.author.loanDispute(s.circle, today(), loan, claim, note.trim().ifEmpty { null }))
        s.scheduler.trigger(SyncTrigger.LOCAL_WRITE)
        refresh()
        return LoanResult.Done(loan)
    }

    /** A steward who is neither the owner nor the borrower decides a pending dispute. */
    suspend fun resolveDispute(loan: LoanId, outcome: DisputeOutcome): LoanResult {
        val s = session ?: return LoanResult.NotAllowed
        val view = replica.fold(s.circle, crypto, cipher, s.identity)
        val state = view.market.loans[loan] ?: return LoanResult.NotAllowed
        if (!canResolve(state, s.identity.member, view.stewards, today())) return LoanResult.NotAllowed
        s.author.keyRing = view.keys
        s.engine.enqueue(s.circle, s.author.loanResolve(s.circle, today(), loan, outcome))
        s.scheduler.trigger(SyncTrigger.LOCAL_WRITE)
        refresh()
        return LoanResult.Done(loan)
    }

    private fun returnedWithoutReportedProblem(loan: circles.event.LoanState): Boolean =
        loan.status == LoanStatus.RETURNED && (loan.outcome == ReturnOutcome.OK || loan.outcome == ReturnOutcome.LATE)

    private fun canConfirm(loan: circles.event.LoanState, me: MemberId): Boolean =
        loan.owner == me && returnedWithoutReportedProblem(loan) && loan.confirmedDay == null && loan.dispute == null

    private fun canDispute(loan: circles.event.LoanState, me: MemberId, day: Int): Boolean {
        val returned = loan.returnedDay ?: return false
        return loan.owner == me && returnedWithoutReportedProblem(loan) && loan.dispute == null && day >= returned && day - returned <= DAMAGE_CLAIM_DAYS
    }

    private fun canResolve(loan: circles.event.LoanState, me: MemberId, stewards: Set<MemberId>, day: Int): Boolean {
        val dispute = loan.dispute ?: return false
        return dispute.outcome == null && me in stewards && me != loan.owner && me != loan.borrower && day - dispute.day < DAMAGE_CLAIM_DAYS
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
        name: String, itemClass: ItemClass, description: String, minTier: Tier? = null, photos: List<circles.event.ItemPhoto> = emptyList(),
    ): ItemResult {
        // An owner may raise the lowest tier above the class default, never lower it (ADR 0025).
        val effectiveMin = minTier?.takeIf { it.rank > itemClass.minTier.rank }
        val mine = view.devices.values.filter { !it.revoked && it.registration.member == s.identity.member }.map { it.registration }
        val day = today()
        s.engine.enqueue(s.circle, s.author.itemUpsert(s.circle, day, item, name, itemClass, detailKeyId, itemKey, mine, effectiveMin, photos))
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
        if (s.pairing && !view.devices.containsKey(s.identity.device)) {
            // Not registered yet: a new device of an existing member waits here until that member adds it (ADR 0046).
            val registration = s.identity.registration
            mutableModel.value = AppModel.AwaitingApproval(
                s.circle, DeviceCode(registration.device, registration.signPub, registration.boxPub).format(), Fingerprint.of(crypto, registration.signPub),
            )
            return
        }
        val now = today()
        val cfg = view.market.configOn(s.circle, now)
        val loansNow = view.market.loans.values
        val vouchesNow = view.market.vouchesOn(now)
        val me = s.identity.member
        val myStanding = StandingRules.standingOf(me, cfg, loansNow, vouchesNow, view.stewards, now, view.market.contributions)
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
                canVouch = StandingRules.mayVouch(me, cfg, loansNow, vouchesNow, view.stewards, now, view.market.contributions),
                stewardCount = view.stewards.size,
                needsRotation = me in view.stewards && view.unrotatedRemovals.isNotEmpty(),
                recoveryShares = recoverySharesOf(view, s.identity.member),
                pauses = pausesFor(s.circle, view, me, myStanding.tier, now),
                pauseHistory = pauseHistoryFor(s.circle, view, me, myStanding.tier, now),
                rules = rulesFor(s.circle, view, me, now),
                skills = view.market.skills.filter { !it.retired && it.owner !in view.market.departed }.map {
                    SkillSummary(it.skill, it.name, it.details, it.openTo, it.owner, it.owner == me)
                },
                contributions = contributionsFor(view, me, now),
                canProposePause = myStanding.tier.rank >= cfg.pauseReviewerMinTier.rank,
                pauseCandidates = if (myStanding.tier.rank < cfg.pauseReviewerMinTier.rank) emptyList() else view.devices.values.filter { !it.revoked }
                    .map { it.registration.member }.distinct()
                    .filter { it != me && it !in view.stewards && view.market.pauses.none { p -> p.subject == it && p.statusOn(now).let { st -> st == circles.event.PauseStatus.PROPOSED || st == circles.event.PauseStatus.LIVE } } },
                hasRecoveryCode = view.devices.values.any { !it.revoked && it.registration.member == me && deviceNames(view)[it.registration.device] == RECOVERY_DEVICE_NAME },
                myDevices = view.devices.values.filter { !it.revoked && it.registration.member == me }.map { state ->
                    DeviceSummary(state.registration.device, state.registration.device == s.identity.device, deviceNames(view)[state.registration.device])
                },
                stewardList = view.stewardSince.map { (member, since) ->
                    val coolOffEnds = if (since == circles.event.FOUNDING_STEWARD_DAY || view.stewardSettings.quorum == 1) null else since + DEFAULT_STEWARD_COOL_OFF_DAYS
                    StewardInfo(member, member == me, since.takeIf { it != circles.event.FOUNDING_STEWARD_DAY }, coolOffEnds?.takeIf { it > now })
                },
                stewardSettings = StewardSettingsSummary(view.stewardSettings.quorum, view.stewardSettings.inactiveDays, view.stewardSettings.fallbackDelayDays, view.stewardSettings.recoveryThreshold),
                proposals = proposalsFrom(view, me, now),
                autoLockMinutes = vault.autoLockMinutes,
                passkeyOffered = passkeys?.available == true,
                passkeyOn = keyStore.load()?.passkey != null,
                myName = view.market.names[me]?.name,
                names = view.market.names.mapValues { (member, state) -> NameSummary(state.name, view.market.nameConfirmed(member)) },
                people = view.devices.values.filter { !it.revoked }.groupBy { it.registration.member }.map { (member, devices) ->
                    val confirmers = view.market.nameConfirmers(member)
                    val named = view.market.names[member]
                    MemberSummary(
                        member = member,
                        isMe = member == s.identity.member,
                        isSteward = member in view.stewards,
                        canRemove = me in view.stewards && member != me && member !in view.stewards,
                        devices = devices.size,
                        fingerprint = fingerprint(devices.first().registration.signPub),
                        vouchedByMeUntil = vouchesNow.filter { it.voucher == me && it.vouchee == member && it.isLiveOn(now, cfg) }.maxOfOrNull { it.effectiveExpiry(cfg) },
                        displayName = named?.name,
                        nameConfirmedBy = confirmers.toList(),
                        iConfirmed = me in confirmers,
                        canConfirmName = named != null && member != me && me !in named.confirmations &&
                            (me in view.stewards || myStanding.tier.rank >= Tier.REGULAR.rank),
                    )
                },
                loans = view.market.loans.values
                    .filter { loan -> loan.owner == me || loan.borrower == me || canResolve(loan, me, view.stewards, now) }
                    .map { loan ->
                        val item = view.market.items[loan.item]
                        val role = when (me) {
                            loan.owner -> LoanRole.OWNER
                            loan.borrower -> LoanRole.BORROWER
                            else -> LoanRole.STEWARD
                        }
                        val iAmOwner = role == LoanRole.OWNER
                        val dispute = loan.dispute
                        LoanSummary(
                            loan = loan.loan,
                            item = loan.item,
                            itemName = item?.name.orEmpty(),
                            role = role,
                            other = if (iAmOwner) loan.borrower else loan.owner,
                            fromDay = loan.fromDay,
                            toDay = loan.toDay,
                            status = loan.status,
                            iConfirmedHandover = if (iAmOwner) loan.ownerConfirmedHandover else loan.borrowerConfirmedHandover,
                            otherConfirmedHandover = if (iAmOwner) loan.borrowerConfirmedHandover else loan.ownerConfirmedHandover,
                            outcome = loan.outcome,
                            note = loan.requestNote,
                            description = ((view.market.details[loan.item] as? circles.encoding.Parsed.Ok)?.value?.get(DETAIL_DESCRIPTION) as? CborText)?.value,
                            reported = loan.outcome,
                            confirmed = loan.confirmedDay != null,
                            datesChanged = loan.fromDay != loan.requestedFromDay || loan.toDay != loan.requestedToDay,
                            dispute = dispute?.let {
                                DisputeSummary(
                                    it.claim, it.note, it.day,
                                    when {
                                        it.outcome == DisputeOutcome.UPHELD -> DisputeStatus.UPHELD
                                        it.outcome == DisputeOutcome.DISMISSED || now - it.day >= DAMAGE_CLAIM_DAYS -> DisputeStatus.DISMISSED
                                        else -> DisputeStatus.PENDING
                                    },
                                )
                            },
                            canConfirm = canConfirm(loan, me),
                            canDispute = canDispute(loan, me, now),
                            canResolve = canResolve(loan, me, view.stewards, now),
                        )
                    },
                items = view.market.items.values.filter { !it.retired && it.owner !in view.market.departed }.map { state ->
                    ItemSummary(
                        item = state.item,
                        name = state.name,
                        itemClass = state.itemClass,
                        owner = state.owner,
                        ownedByMe = state.owner == s.identity.member,
                        ownerMinTier = state.minTier,
                        photos = state.photos,
                        description = ((view.market.details[state.item] as? circles.encoding.Parsed.Ok)?.value?.get(DETAIL_DESCRIPTION) as? CborText)?.value,
                        access = if (state.owner == me) null else when (val decision = StandingRules.decide(me, state, cfg, loansNow, vouchesNow, view.stewards, now, view.market.livePausesOn(s.circle, now), view.market.contributions)) {
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

    private fun proposalsFrom(view: circles.event.CircleView, me: MemberId, now: Int): List<ProposalSummary> {
        val policy = StewardPolicy().with(view.stewardSettings)
        return view.pendingStewardVotes.groupBy { it.change to it.subject }.mapNotNull { (key, votes) ->
            val (change, subject) = key
            val latest = votes.maxOf { it.vote.day }
            val live = votes.map { it.vote }.filter { latest - it.day <= DEFAULT_STEWARD_PROPOSAL_DAYS && it.voter in view.stewards && it.voter.v != subject.takeIf { change == StewardChange.REMOVE } }
            if (now - latest > DEFAULT_STEWARD_PROPOSAL_DAYS) return@mapNotNull null // the window closed without enough agreement
            val needed = if (change == StewardChange.REMOVE) policy.quorum else minOf(policy.quorum, view.stewards.size)
            val iAgree = live.any { it.voter == me }
            val settings = if (change == StewardChange.POLICY) StewardSettings.fromSubject(subject)?.let { StewardSettingsSummary(it.quorum, it.inactiveDays, it.fallbackDelayDays, it.recoveryThreshold) } else null
            val cooling = view.stewardSince[me]?.let { since -> since != circles.event.FOUNDING_STEWARD_DAY && policy.quorum > 1 && now < since + policy.coolOffDays } ?: true
            ProposalSummary(
                change = change,
                member = if (change == StewardChange.POLICY) null else MemberId(subject),
                settings = settings,
                agreeing = live.map { it.voter }.toSet().size,
                needed = needed,
                iAgree = iAgree,
                canAgree = me in view.stewards && !iAgree && !cooling,
            )
        }
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
        if (next.rank <= Tier.REGULAR.rank) add("Or get ${cfg.vouchesForRegular} members (Regular or above) to vouch for you (on the Members page).")
    }

    /** The names typed when devices were added: from the sealed label of each `device.add`. */
    private fun deviceNames(view: circles.event.CircleView): Map<DeviceId, String> = buildMap {
        for (event in view.events) {
            if (event.envelope.type != EventType.DEVICE_ADD.wire) continue
            val device = (circles.event.PublicParts.registration(event.envelope.public) as? Parsed.Ok)?.value?.device ?: continue
            val label = ((event.payload as? Parsed.Ok)?.value?.get(circles.event.PublicParts.LABEL) as? CborText)?.value ?: continue
            put(device, label)
        }
    }

    /** Twenty hex characters of the key's hash in groups of four, easy to read out loud. */
    private fun fingerprint(signPub: circles.encoding.ByteString): String = Fingerprint.of(crypto, signPub)

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
        val blobs = blobTransport?.let { transport -> (api as? TokenSource)?.let { circles.sync.HttpBlobApi(transport, it) } }
        return Session(identity, circle, author, engine, scheduler, hints, keyStore.load()?.pairing == true, blobs).also {
            session = it
            scheduler.start()
        }
    }

    private suspend fun afterSync(report: SyncReport) {
        mutableSync.value = report.state
        // The stream needs a logged-in device, which a new one is only once its first events are accepted.
        if (report.state == SyncState.UpToDate) session?.hints?.start()
        session?.let { s ->
            // One fold serves both checks (a fold is the costly part of a sync).
            val view = replica.fold(s.circle, crypto, cipher, s.identity)
            if (view.market.departed.isNotEmpty()) rotateItemKeysAfterRemovals(s, view)
            sendMissingShares(s, view)
        }
        refresh()
    }

    /** The device counter continues after the highest one already used here, in the log or the outbox. */
    private suspend fun nextCounter(identity: DeviceIdentity, circle: CircleId): Long {
        val logged = replica.entries(circle).filter { it.envelope.device == identity.device }.maxOfOrNull { it.envelope.ctr }
        val queued = replica.outbox(circle).filter { it.device == identity.device }.maxOfOrNull { it.ctr }
        return maxOf(logged ?: -1L, queued ?: -1L) + 1
    }
}

/** How many bytes of sealed photos a browser export holds at most, so the file is built in memory without trouble. */
const val EXPORT_PHOTO_BYTES = 200L * 1024 * 1024
