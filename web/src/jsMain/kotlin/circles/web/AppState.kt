package circles.web

/** What the sync engine reports; the shell shows it as one plain sentence (spec section 6). */
sealed interface SyncStatus {
    data object UpToDate : SyncStatus
    data object Syncing : SyncStatus
    data class Offline(val changesWaiting: Int) : SyncStatus
}

data class AppState(
    val screen: Screen = Screen.SignIn,
    val signedIn: Boolean = false,
    val sync: SyncStatus = SyncStatus.Offline(changesWaiting = 0),
    val updateAvailable: Boolean = false,
)

sealed interface AppAction {
    data class Navigate(val screen: Screen) : AppAction
    data class SignedIn(val value: Boolean) : AppAction
    data class SyncChanged(val status: SyncStatus) : AppAction
    data object UpdateReady : AppAction
    data object UpdateAccepted : AppAction
}

/** Pure state transition. Signed-out users can only see the sign-in screen; signed-in users never land on it. */
fun reduce(state: AppState, action: AppAction): AppState = when (action) {
    is AppAction.Navigate -> state.copy(screen = allowed(state.signedIn, action.screen))
    is AppAction.SignedIn ->
        state.copy(signedIn = action.value, screen = allowed(action.value, if (action.value) Screen.Home else Screen.SignIn))
    is AppAction.SyncChanged -> state.copy(sync = action.status)
    AppAction.UpdateReady -> state.copy(updateAvailable = true)
    AppAction.UpdateAccepted -> state.copy(updateAvailable = false)
}

private fun allowed(signedIn: Boolean, screen: Screen): Screen = when {
    !signedIn -> Screen.SignIn
    screen == Screen.SignIn -> Screen.Home
    else -> screen
}

/** The sentence for the status line: "Up to date", "Syncing", or "Offline, 3 changes waiting". */
fun describe(status: SyncStatus): String = when (status) {
    SyncStatus.UpToDate -> "Up to date"
    SyncStatus.Syncing -> "Syncing"
    is SyncStatus.Offline -> when (status.changesWaiting) {
        0 -> "Offline"
        1 -> "Offline, 1 change waiting"
        else -> "Offline, ${status.changesWaiting} changes waiting"
    }
}
