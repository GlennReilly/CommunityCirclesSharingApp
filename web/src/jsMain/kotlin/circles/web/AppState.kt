package circles.web

import circles.app.AppModel
import circles.app.InviteLink
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
) {
    val signedIn: Boolean get() = model is AppModel.Open
}

sealed interface AppAction {
    data class Navigate(val screen: Screen) : AppAction
    data class ModelChanged(val model: AppModel) : AppAction
    data class SyncChanged(val state: SyncState) : AppAction
    data class InviteSeen(val link: InviteLink?) : AppAction
    data class Notice(val text: String?) : AppAction
    data class InviteMade(val link: String?) : AppAction
    data class EditItem(val item: ItemId) : AppAction
    data object UpdateReady : AppAction
    data object UpdateAccepted : AppAction
}

/** Pure state transition. Signed-out users never leave the sign-in pages; signed-in users never land on them. */
fun reduce(state: AppState, action: AppAction): AppState = when (action) {
    is AppAction.Navigate -> state.copy(screen = allowed(state.model, action.screen).let { if (it == Screen.EditItem && state.editing == null) Screen.Items else it })
    is AppAction.EditItem -> if (state.signedIn) state.copy(editing = action.item, screen = Screen.EditItem) else state
    is AppAction.ModelChanged -> {
        val open = action.model is AppModel.Open
        state.copy(
            model = action.model,
            screen = allowed(action.model, state.screen),
            // Leaving the open state forgets anything that was only meant for the open screens.
            newInvite = if (open) state.newInvite else null,
            // An invite only matters to a browser without an identity; the Loading model arrives first and must not drop it.
            pendingInvite = if (action.model is AppModel.Locked || action.model is AppModel.Open) null else state.pendingInvite,
        )
    }
    is AppAction.SyncChanged -> state.copy(sync = action.state)
    // A link in the address goes straight to the join page, for a browser that has no identity (or is still loading).
    is AppAction.InviteSeen -> state.copy(
        pendingInvite = action.link,
        screen = if (action.link != null && (state.model is AppModel.NoIdentity || state.model is AppModel.Loading)) Screen.JoinCircle else state.screen,
    )
    is AppAction.Notice -> state.copy(notice = action.text)
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
}

/** The sentence for the status line (spec section 6): "Up to date", "Syncing", "Offline, 3 changes waiting", ... */
fun describe(state: SyncState): String = when (state) {
    SyncState.UpToDate -> "Up to date"
    SyncState.Syncing -> "Syncing"
    is SyncState.Offline -> "Offline" + waiting(state.changesWaiting)
    is SyncState.Busy -> "The server is busy, trying again soon" + waiting(state.changesWaiting)
    SyncState.SignedOut -> "Signed out, sign in again to sync"
    is SyncState.Problem -> state.message + waiting(state.changesWaiting)
}

private fun waiting(count: Int): String = when (count) {
    0 -> ""
    1 -> ", 1 change waiting"
    else -> ", $count changes waiting"
}
