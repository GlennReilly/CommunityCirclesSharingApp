package circles.web

import circles.app.AppModel
import circles.app.DeviceLink
import circles.app.InviteLink
import circles.app.RecoveryDraft
import circles.app.RestoreLink
import circles.policy.ItemId
import circles.sync.SyncState

data class AppState(
    val screen: Screen = Screen.Welcome,
    /** What the client knows: loading, no identity, locked, or open with the home data (from `CircleApp`). */
    val model: AppModel = AppModel.Loading,
    val sync: SyncState = SyncState.Offline(changesWaiting = 0),
    val updateAvailable: Boolean = false,
    /** An invite link found in the address when the page opened; offered to the join form. */
    val pendingInvite: InviteLink? = null,
    /** A message for the user (an error, or a plain "done"), shown in a live region until dismissed. */
    val notice: String? = null,
    /** The invite link just made, shown once so it can be copied. */
    val newInvite: String? = null,
    /** The item being changed on the Change an item page. */
    val editing: ItemId? = null,
    /** A member to check, from a QR code the phone's camera opened (ADR 0062). */
    val verifying: circles.app.VerifyLink? = null,
    /** An item opened from its printed tag: the Items page shows just this one until told otherwise. */
    val focusItem: ItemId? = null,
    /** The item whose tag is being shown for printing. */
    val tagging: ItemId? = null,
    /** The skill offer being changed on the Change a skill offer page. */
    val editingSkill: circles.event.SkillId? = null,
    /** The item being asked for on the Ask to borrow page. */
    val borrowing: ItemId? = null,
    /** A link to add this browser as another device of a member, found in the address (ADR 0046). */
    val pendingDevice: DeviceLink? = null,
    /** The address this page was served from, for the links it shows. */
    val origin: String = "",
    /** A link to restore from a recovery code, found in the address (ADR 0047). */
    val pendingRestore: RestoreLink? = null,
    /** The recovery code being made: shown once, kept only in memory. */
    val recoveryDraft: RecoveryDraft? = null,
    /** Share codes this steward chose to show, by epoch: secrets, kept only in memory and only while signed in (ADR 0048). */
    val shareCodes: Map<Int, String> = emptyMap(),
    /** Text size and colour choice of this browser (shown on Settings). */
    val appearance: Appearance = Appearance(),
) {
    val signedIn: Boolean get() = model is AppModel.Open
}

sealed interface AppAction {
    data class Navigate(val screen: Screen) : AppAction
    data class ModelChanged(val model: AppModel) : AppAction
    data class SyncChanged(val state: SyncState) : AppAction
    data class InviteSeen(val link: InviteLink?) : AppAction
    data class DeviceLinkSeen(val link: DeviceLink?) : AppAction
    data class OriginKnown(val origin: String) : AppAction
    data class RestoreLinkSeen(val link: RestoreLink?) : AppAction
    data class RecoveryDraftReady(val draft: RecoveryDraft?) : AppAction
    data class ShareCodeShown(val epoch: Int, val code: String?) : AppAction
    data class Notice(val text: String?) : AppAction
    data class AppearanceChanged(val appearance: Appearance) : AppAction
    data class InviteMade(val link: String?) : AppAction
    data class EditItem(val item: ItemId) : AppAction
    data class VerifyLinkSeen(val link: circles.app.VerifyLink) : AppAction
    data class ItemLinkSeen(val item: ItemId) : AppAction
    data class TagItem(val item: ItemId) : AppAction
    data object ShowAllItems : AppAction
    data class EditSkill(val skill: circles.event.SkillId) : AppAction
    data class BorrowItem(val item: ItemId) : AppAction
    data object UpdateReady : AppAction
    data object UpdateAccepted : AppAction
}

/** Pure state transition. Signed-out users never leave the sign-in pages; signed-in users never land on them. */
fun reduce(state: AppState, action: AppAction): AppState = when (action) {
    is AppAction.Navigate -> state.copy(
        // A link's target only lives while its page is on show.
        verifying = if (!state.signedIn || action.screen == Screen.Verify) state.verifying else null,
        focusItem = if (!state.signedIn || action.screen == Screen.Items) state.focusItem else null,
        screen = allowed(state.model, action.screen).let {
        when {
            it == Screen.Verify && state.verifying == null -> Screen.Members
            it == Screen.ItemTag && state.tagging == null -> Screen.Items
            it == Screen.EditItem && state.editing == null -> Screen.Items
            it == Screen.EditSkill && state.editingSkill == null -> Screen.Skills
            it == Screen.RequestLoan && state.borrowing == null -> Screen.Items
            it == Screen.RecoveryCode && state.recoveryDraft == null -> Screen.Settings
            else -> it
        }
    },
    )
    is AppAction.BorrowItem -> if (state.signedIn) state.copy(borrowing = action.item, screen = Screen.RequestLoan) else state
    is AppAction.EditSkill -> if (state.signedIn) state.copy(editingSkill = action.skill, screen = Screen.EditSkill) else state
    is AppAction.EditItem -> if (state.signedIn) state.copy(editing = action.item, screen = Screen.EditItem) else state
    is AppAction.ModelChanged -> {
        val open = action.model is AppModel.Open
        state.copy(
            model = action.model,
            screen = when {
                // Signing in after a link was opened lands on what the link was for.
                open && state.model !is AppModel.Open && state.verifying != null -> Screen.Verify
                open && state.model !is AppModel.Open && state.focusItem != null -> Screen.Items
                else -> allowed(action.model, state.screen)
            },
            // Leaving the open state forgets anything that was only meant for the open screens.
            newInvite = if (open) state.newInvite else null,
            // An invite only matters to a browser without an identity; the Loading model arrives first and must not drop it.
            pendingInvite = if (action.model is AppModel.Locked || action.model is AppModel.Open || action.model is AppModel.AwaitingApproval) null else state.pendingInvite,
            pendingDevice = if (action.model is AppModel.Locked || action.model is AppModel.Open || action.model is AppModel.AwaitingApproval) null else state.pendingDevice,
            pendingRestore = if (action.model is AppModel.Locked || action.model is AppModel.Open || action.model is AppModel.AwaitingApproval) null else state.pendingRestore,
            recoveryDraft = if (open) state.recoveryDraft else null,
            shareCodes = if (open) state.shareCodes else emptyMap(),
        )
    }
    is AppAction.SyncChanged -> state.copy(sync = action.state)
    // A link in the address goes straight to the join page, for a browser that has no identity (or is still loading).
    is AppAction.InviteSeen -> state.copy(
        pendingInvite = action.link,
        screen = if (action.link != null && (state.model is AppModel.NoIdentity || state.model is AppModel.Loading)) Screen.JoinCircle else state.screen,
    )
    is AppAction.DeviceLinkSeen -> state.copy(
        pendingDevice = action.link,
        screen = if (action.link != null && (state.model is AppModel.NoIdentity || state.model is AppModel.Loading)) Screen.PairDevice else state.screen,
    )
    is AppAction.VerifyLinkSeen -> state.copy(verifying = action.link, screen = if (state.signedIn) Screen.Verify else state.screen)
    is AppAction.ItemLinkSeen -> state.copy(focusItem = action.item, screen = if (state.signedIn) Screen.Items else state.screen)
    is AppAction.TagItem -> if (state.signedIn) state.copy(tagging = action.item, screen = Screen.ItemTag) else state
    AppAction.ShowAllItems -> state.copy(focusItem = null)
    is AppAction.OriginKnown -> state.copy(origin = action.origin)
    is AppAction.RestoreLinkSeen -> state.copy(
        pendingRestore = action.link,
        screen = if (action.link != null && (state.model is AppModel.NoIdentity || state.model is AppModel.Loading)) Screen.Restore else state.screen,
    )
    is AppAction.RecoveryDraftReady -> state.copy(recoveryDraft = action.draft, screen = if (action.draft != null && state.signedIn) Screen.RecoveryCode else state.screen)
    is AppAction.ShareCodeShown -> state.copy(shareCodes = if (action.code == null) state.shareCodes - action.epoch else state.shareCodes + (action.epoch to action.code))
    is AppAction.Notice -> state.copy(notice = action.text)
    is AppAction.AppearanceChanged -> state.copy(appearance = action.appearance)
    is AppAction.InviteMade -> state.copy(newInvite = action.link)
    AppAction.UpdateReady -> state.copy(updateAvailable = true)
    AppAction.UpdateAccepted -> state.copy(updateAvailable = false)
}

/** Which screen a visitor may see: each state of the app has its own set of pages. */
private fun allowed(model: AppModel, screen: Screen): Screen = when (model) {
    is AppModel.Open -> if (screen in Screen.beforeSignIn) Screen.Home else screen
    is AppModel.Locked -> Screen.SignIn
    AppModel.NoIdentity -> if (screen in Screen.newcomer) screen else Screen.Welcome
    AppModel.Loading -> if (screen in Screen.newcomer) screen else Screen.Welcome
    is AppModel.AwaitingApproval -> Screen.PairDevice
}

/** The sentence for the status line (spec section 6): "Up to date", "Syncing", "Offline, 3 changes waiting", ... */
fun describe(state: SyncState): String = when (state) {
    SyncState.UpToDate -> "Up to date"
    SyncState.Syncing -> "Syncing"
    is SyncState.Offline -> "Offline" + waiting(state.changesWaiting)
    is SyncState.Busy -> "The server is busy, trying again soon" + waiting(state.changesWaiting)
    SyncState.SignedOut -> "Cannot sign in to the circle. If you were removed, ask a steward"
    is SyncState.Problem -> state.message + waiting(state.changesWaiting)
}

private fun waiting(count: Int): String = when (count) {
    0 -> ""
    1 -> ", 1 change waiting"
    else -> ", $count changes waiting"
}
