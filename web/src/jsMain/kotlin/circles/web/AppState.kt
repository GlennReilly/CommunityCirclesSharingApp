package circles.web

import circles.sync.SyncState

data class AppState(
    val screen: Screen = Screen.SignIn,
    val signedIn: Boolean = false,
    val sync: SyncState = SyncState.Offline(changesWaiting = 0),
    val updateAvailable: Boolean = false,
)

sealed interface AppAction {
    data class Navigate(val screen: Screen) : AppAction
    data class SignedIn(val value: Boolean) : AppAction
    data class SyncChanged(val state: SyncState) : AppAction
    data object UpdateReady : AppAction
    data object UpdateAccepted : AppAction
}

/** Pure state transition. Signed-out users can only see the sign-in screen; signed-in users never land on it. */
fun reduce(state: AppState, action: AppAction): AppState = when (action) {
    is AppAction.Navigate -> state.copy(screen = allowed(state.signedIn, action.screen))
    is AppAction.SignedIn ->
        state.copy(signedIn = action.value, screen = allowed(action.value, if (action.value) Screen.Home else Screen.SignIn))
    is AppAction.SyncChanged -> state.copy(sync = action.state)
    AppAction.UpdateReady -> state.copy(updateAvailable = true)
    AppAction.UpdateAccepted -> state.copy(updateAvailable = false)
}

private fun allowed(signedIn: Boolean, screen: Screen): Screen = when {
    !signedIn -> Screen.SignIn
    screen == Screen.SignIn -> Screen.Home
    else -> screen
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
