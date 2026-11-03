package circles.web

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.dom.clear
import kotlinx.html.div
import kotlinx.html.dom.append
import org.w3c.dom.HTMLElement

private const val ROOT_ID = "app"

/** Browser glue only: holds the state, re-renders the shell, and turns events into [AppAction]s. */
private class Shell(private val root: HTMLElement) {
    private var state = AppState()

    fun dispatch(action: AppAction) {
        val before = state
        state = reduce(state, action)
        if (state != before) render(screenChanged = state.screen != before.screen)
    }

    private fun render(screenChanged: Boolean) {
        root.clear()
        root.append { div { shell(state) } }
        document.title = "${state.screen.title} - Circles"
        if (screenChanged) document.getElementById(MAIN_ID)?.let { (it as HTMLElement).focus() }
    }

    fun start() {
        window.addEventListener("hashchange", { dispatch(AppAction.Navigate(Screen.fromFragment(window.location.hash))) })
        window.addEventListener("online", { dispatch(AppAction.SyncChanged(SyncStatus.Syncing)) })
        window.addEventListener("offline", { dispatch(AppAction.SyncChanged(SyncStatus.Offline(changesWaiting = 0))) })
        render(screenChanged = false)
    }
}

fun main() {
    val root = document.getElementById(ROOT_ID) as? HTMLElement ?: error("index.html has no #$ROOT_ID element")
    Shell(root).start()
}
