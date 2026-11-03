package circles.web

import circles.app.AppModel
import circles.app.CircleApp
import circles.app.InviteLink
import circles.app.StartResult
import circles.crypto.JsCipher
import circles.crypto.JsCrypto
import circles.event.DeviceId
import circles.keys.JsPasswordHasher
import circles.keys.KeyVault
import circles.keys.UnlockResult
import circles.sync.FetchTransport
import circles.sync.HttpSyncApi
import circles.sync.IdbOpenResult
import circles.sync.IndexedDbReplica
import circles.sync.SyncTrigger
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.dom.clear
import kotlinx.html.div
import kotlinx.html.dom.append
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLFormElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.events.Event

private const val ROOT_ID = "app"
private const val MILLIS_PER_DAY = 86_400_000.0
private const val POLL_MILLIS = 60_000
private const val MIN_PASSPHRASE_NOTICE = "Choose a longer passphrase: at least %d characters."

/**
 * Browser glue only: holds the state, re-renders the page, and turns events into [AppAction]s and calls on
 * [CircleApp]. Everything that decides anything lives in `shared` (CircleApp) or in [reduce] and the views.
 */
private class Page(private val root: HTMLElement, private val scope: CoroutineScope) {
    private var state = AppState()
    private var app: CircleApp? = null

    fun dispatch(action: AppAction) {
        val before = state
        state = reduce(state, action)
        if (state == before) return
        // A sync status change must not rebuild the page: it would wipe a passphrase being typed.
        if (state.copy(sync = before.sync) == before) {
            document.getElementById("sync-status")?.textContent = describe(state.sync)
        } else {
            render(screenChanged = state.screen != before.screen || state.model::class != before.model::class)
        }
    }

    private fun render(screenChanged: Boolean) {
        root.clear()
        root.append { div { shell(state) } }
        document.title = if (state.signedIn) "${state.screen.title} - Circles" else "Circles"
        if (screenChanged) document.getElementById(MAIN_ID)?.let { (it as HTMLElement).focus() }
    }

    suspend fun start() {
        render(screenChanged = false)
        InviteLink.parse(window.location.hash)?.let { link ->
            dispatch(AppAction.InviteSeen(link))
            // The secret in the link must not stay in the address bar or the history entry (ADR 0040).
            window.history.replaceState(null, "", window.location.pathname + window.location.search)
        }
        window.addEventListener("hashchange", { dispatch(AppAction.Navigate(Screen.fromFragment(window.location.hash))) })
        // Until the event stream is read (ADR 0026), look for news once a minute; the scheduler spreads and backs off.
        window.setInterval({ app?.syncNow(SyncTrigger.HINT) }, POLL_MILLIS)
        window.addEventListener("online", { app?.syncNow(SyncTrigger.ONLINE) })
        for (type in listOf("keydown", "pointerdown")) document.addEventListener(type, { app?.touch() })
        root.addEventListener("submit", { event -> onSubmit(event) })
        root.addEventListener("click", { event -> onClick(event) })

        val crypto = JsCrypto()
        val cipher = JsCipher()
        val replica = when (val opened = IndexedDbReplica.open({ it.eventId(crypto) })) {
            is IdbOpenResult.Opened -> opened.replica
            is IdbOpenResult.Unavailable -> {
                dispatch(AppAction.Notice("This browser cannot keep your data (${opened.reason}), so Circles cannot run here."))
                return
            }
        }
        val vault = KeyVault(scope, crypto, cipher, JsPasswordHasher(), replica)
        val circleApp = CircleApp(
            scope, crypto, cipher, vault, replica, replica,
            { identity -> HttpSyncApi(FetchTransport(), crypto, identity) },
            { (kotlin.js.Date.now() / MILLIS_PER_DAY).toInt() },
        )
        app = circleApp
        scope.launch { circleApp.model.collect { dispatch(AppAction.ModelChanged(it)) } }
        scope.launch { circleApp.sync.collect { dispatch(AppAction.SyncChanged(it)) } }
        circleApp.open()
    }

    private fun onSubmit(event: Event) {
        val form = event.target as? HTMLFormElement ?: return
        event.preventDefault()
        val circleApp = app ?: return
        fun field(name: String): String = (form.elements.namedItem(name) as? HTMLInputElement)?.value.orEmpty()
        scope.launch {
            when (form.id) {
                FORM_START -> report(circleApp.startCircle(field(FIELD_PASSPHRASE)))
                FORM_JOIN -> state.pendingInvite?.let { report(circleApp.join(it, field(FIELD_PASSPHRASE))) }
                FORM_LINK -> {
                    val link = InviteLink.parse(field(FIELD_LINK))
                    if (link == null) dispatch(AppAction.Notice("That does not look like an invite link. Paste the whole link you were sent."))
                    else dispatch(AppAction.InviteSeen(link))
                }
                FORM_UNLOCK -> when (val result = circleApp.unlock(field(FIELD_PASSPHRASE))) {
                    UnlockResult.Unlocked -> dispatch(AppAction.Notice(null))
                    UnlockResult.WrongPassphrase -> dispatch(AppAction.Notice("That passphrase did not work. Try again."))
                    UnlockResult.NoIdentity -> dispatch(AppAction.Notice("There is nothing saved on this device."))
                    is UnlockResult.Unsupported -> dispatch(AppAction.Notice("This device's saved keys need a newer version of Circles (${result.reason})."))
                }
            }
        }
    }

    private fun report(result: StartResult) {
        when (result) {
            is StartResult.Started -> dispatch(AppAction.Notice(null))
            is StartResult.PassphraseTooShort -> dispatch(AppAction.Notice(MIN_PASSPHRASE_NOTICE.replace("%d", result.minimum.toString())))
            StartResult.AlreadyHasIdentity -> dispatch(AppAction.Notice("This device already has an identity. Unlock it instead."))
        }
    }

    private fun onClick(event: Event) {
        val target = (event.target as? Element)?.closest("[$ATTR_ACTION]") ?: return
        val circleApp = app
        scope.launch {
            when (target.getAttribute(ATTR_ACTION)) {
                ACTION_DISMISS -> dispatch(AppAction.Notice(null))
                ACTION_LOCK -> circleApp?.lock()
                ACTION_SYNC -> circleApp?.syncNow()
                ACTION_ERASE -> circleApp?.erase()
                ACTION_INVITE -> circleApp?.makeInvite()?.let { dispatch(AppAction.InviteMade(it.format(window.location.origin))) }
                ACTION_COPY -> state.newInvite?.let {
                    window.navigator.asDynamic().clipboard.writeText(it)
                    dispatch(AppAction.Notice("Link copied."))
                }
                ACTION_ADMIT -> target.getAttribute(ATTR_DEVICE)?.let { device ->
                    if (circleApp?.admit(DeviceId(device)) != true) dispatch(AppAction.Notice("Could not let them in just now. Try again after the next sync."))
                }
            }
        }
    }
}

fun main() {
    val root = document.getElementById(ROOT_ID) as? HTMLElement ?: error("index.html has no #$ROOT_ID element")
    val scope = MainScope()
    scope.launch { Page(root, scope).start() }
}
