package circles.web

import circles.app.AppModel
import circles.app.CircleApp
import circles.app.InviteLink
import circles.app.CalendarDay
import circles.app.ItemResult
import circles.app.LoanResult
import circles.event.LoanId
import circles.event.ReturnOutcome
import circles.policy.ItemClass
import circles.policy.ItemId
import circles.app.StartResult
import circles.crypto.JsCipher
import circles.crypto.JsCrypto
import circles.event.DeviceId
import circles.keys.ChangePassphraseResult
import circles.keys.JsPasswordHasher
import circles.keys.KeyVault
import circles.keys.UnlockResult
import circles.sync.FetchStreamTransport
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
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.Event

private const val ROOT_ID = "app"
private const val MILLIS_PER_DAY = 86_400_000.0
private const val POLL_MILLIS = 60_000
private const val UPDATE_CHECK_MILLIS = 60 * 60 * 1000
private const val WORKER_PATH = "/sw.js"
private const val MIN_PASSPHRASE_NOTICE = "Choose a longer passphrase: at least %d characters."

/**
 * Browser glue only: holds the state, re-renders the page, and turns events into [AppAction]s and calls on
 * [CircleApp]. Everything that decides anything lives in `shared` (CircleApp) or in [reduce] and the views.
 */
private class Page(private val root: HTMLElement, private val scope: CoroutineScope) {
    private var state = AppState()
    private var app: CircleApp? = null
    private var registration: dynamic = null

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
        document.title = if (state.model is AppModel.Loading) "Circles" else "${state.screen.title} - Circles"
        if (screenChanged) document.getElementById(MAIN_ID)?.let { (it as HTMLElement).focus() }
    }

    suspend fun start() {
        render(screenChanged = false)
        registerServiceWorker()
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
        root.addEventListener("input", { event -> onInput(event) })
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
            FetchStreamTransport(),
        )
        app = circleApp
        scope.launch { circleApp.model.collect { dispatch(AppAction.ModelChanged(it)) } }
        scope.launch { circleApp.sync.collect { dispatch(AppAction.SyncChanged(it)) } }
        circleApp.open()
    }

    /**
     * Registers the offline shell (ADR 0041). A new version waits until the member presses "Update now"; the page then
     * reloads once the new worker has taken over. The very first install is silent.
     */
    private fun registerServiceWorker() {
        val container = window.navigator.asDynamic().serviceWorker ?: return
        val hadController = container.controller != null
        container.addEventListener("controllerchange", { if (hadController) window.location.reload() })
        container.register(WORKER_PATH).then { reg: dynamic ->
            registration = reg
            fun watch(worker: dynamic) {
                worker.addEventListener("statechange", { if (worker.state == "installed" && container.controller != null) dispatch(AppAction.UpdateReady) })
            }
            if (reg.waiting != null && container.controller != null) dispatch(AppAction.UpdateReady)
            reg.addEventListener("updatefound", { val installing = reg.installing; if (installing != null) watch(installing) })
            window.setInterval({ reg.update() }, UPDATE_CHECK_MILLIS)
            window.addEventListener("online", { reg.update() })
        }
    }

    /** A form's button appears only once every field that needs text has enough of it (`data-min`, in the views). */
    private fun onInput(event: Event) {
        val form = (event.target as? Element)?.closest("form") as? HTMLFormElement ?: return
        val button = form.querySelector("#$SUBMIT_ID") as? HTMLElement ?: return
        var ready = true
        val needy = form.querySelectorAll("[$ATTR_MIN]")
        for (index in 0 until needy.length) {
            val field = needy.item(index) as HTMLInputElement
            val min = field.getAttribute(ATTR_MIN)?.toIntOrNull() ?: continue
            // Passphrases count every character; other text ignores spaces at the ends.
            val length = if (field.type == "password") field.value.length else field.value.trim().length
            if (length < min) ready = false
        }
        button.hidden = !ready
    }

    private fun onSubmit(event: Event) {
        val form = event.target as? HTMLFormElement ?: return
        event.preventDefault()
        val circleApp = app ?: return
        fun field(name: String): String = when (val element = form.elements.namedItem(name)) {
            is HTMLInputElement -> element.value
            is HTMLTextAreaElement -> element.value
            is HTMLSelectElement -> element.value
            else -> ""
        }
        scope.launch {
            if (form.id.startsWith(FORM_RETURN_PREFIX)) {
                val loan = form.getAttribute(ATTR_LOAN)?.let(::LoanId)
                val outcome = ReturnOutcome.entries.firstOrNull { it.name == field(FIELD_OUTCOME) }
                if (loan != null && outcome != null) reportLoan(circleApp.returnLoan(loan, outcome), "Thank you. Reported back.", null)
                return@launch
            }
            when (form.id) {
                FORM_START -> report(circleApp.startCircle(field(FIELD_PASSPHRASE)))
                FORM_JOIN -> state.pendingInvite?.let { report(circleApp.join(it, field(FIELD_PASSPHRASE))) }
                FORM_LINK -> {
                    val link = InviteLink.parse(field(FIELD_LINK))
                    if (link == null) dispatch(AppAction.Notice("That does not look like an invite link. Paste the whole link you were sent."))
                    else dispatch(AppAction.InviteSeen(link))
                }
                FORM_ITEM_ADD -> reportItem(circleApp.addItem(field(FIELD_NAME), kind(field(FIELD_KIND)), field(FIELD_DETAILS)))
                FORM_ITEM_EDIT -> state.editing?.let { reportItem(circleApp.editItem(it, field(FIELD_NAME), kind(field(FIELD_KIND)), field(FIELD_DETAILS))) }
                FORM_LOAN_REQUEST -> {
                    val from = CalendarDay.parse(field(FIELD_FROM))
                    val to = CalendarDay.parse(field(FIELD_TO))
                    val item = state.borrowing
                    if (from == null || to == null || item == null) dispatch(AppAction.Notice("Choose both dates."))
                    else reportLoan(circleApp.requestLoan(item, from, to, field(FIELD_NOTE)), "Request sent.", Screen.Borrow)
                }
                FORM_CHANGE_PASSPHRASE -> dispatch(
                    AppAction.Notice(
                        when (val result = circleApp.changePassphrase(field(FIELD_CURRENT), field(FIELD_NEW))) {
                            ChangePassphraseResult.Changed -> "Passphrase changed. Use the new one next time you unlock."
                            ChangePassphraseResult.WrongPassphrase -> "The current passphrase was not right."
                            is ChangePassphraseResult.TooShort -> "The new passphrase must be at least ${result.minimum} characters."
                            ChangePassphraseResult.NotUnlocked -> "Unlock first."
                        },
                    ),
                )
                FORM_AUTOLOCK -> field(FIELD_MINUTES).toIntOrNull()?.let {
                    circleApp.setAutoLockMinutes(it)
                    dispatch(AppAction.Notice("Saved."))
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

    private fun kind(name: String): ItemClass = ItemClass.entries.firstOrNull { it.name == name } ?: ItemClass.EVERYDAY

    private fun reportItem(result: ItemResult) {
        when (result) {
            is ItemResult.Saved -> {
                dispatch(AppAction.Notice("Saved."))
                window.location.hash = Screen.Items.fragment
            }
            ItemResult.BadName -> dispatch(AppAction.Notice("Give the item a name of 1 to 80 characters."))
            is ItemResult.DescriptionTooLong -> dispatch(AppAction.Notice("Keep the details to ${result.maximum} characters or fewer."))
            ItemResult.NotLetInYet -> dispatch(AppAction.Notice("A steward needs to let you in before you can add items."))
            ItemResult.NotAllowed -> dispatch(AppAction.Notice("That item cannot be changed here."))
        }
    }

    private fun reportLoan(result: LoanResult, doneText: String, thenGo: Screen?) {
        when (result) {
            is LoanResult.Done -> {
                dispatch(AppAction.Notice(doneText))
                thenGo?.let { window.location.hash = it.fragment }
            }
            is LoanResult.BadDates -> dispatch(AppAction.Notice("Choose dates from today on, with the end not before the start and at most ${result.maximumDays} days."))
            is LoanResult.NoteTooLong -> dispatch(AppAction.Notice("Keep the note to ${result.maximum} characters or fewer."))
            LoanResult.NotLetInYet -> dispatch(AppAction.Notice("A steward needs to let you in before you can borrow."))
            LoanResult.NotAllowed -> dispatch(AppAction.Notice("That cannot be done right now. Try syncing, then again."))
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
                ACTION_UPDATE -> registration?.waiting?.postMessage(js("({type: 'SKIP_WAITING'})"))
                ACTION_DISMISS -> dispatch(AppAction.Notice(null))
                ACTION_LOCK -> circleApp?.lock()
                ACTION_SYNC -> circleApp?.syncNow()
                ACTION_BORROW_ITEM -> target.getAttribute(ATTR_ITEM)?.let {
                    dispatch(AppAction.BorrowItem(ItemId(it)))
                    window.location.hash = Screen.RequestLoan.fragment
                }
                ACTION_APPROVE_LOAN -> target.getAttribute(ATTR_LOAN)?.let { reportLoan(circleApp?.respondToLoan(LoanId(it), true, "") ?: LoanResult.NotAllowed, "Approved. They can now read the details.", null) }
                ACTION_DECLINE_LOAN -> target.getAttribute(ATTR_LOAN)?.let { reportLoan(circleApp?.respondToLoan(LoanId(it), false, "") ?: LoanResult.NotAllowed, "Declined.", null) }
                ACTION_HANDOVER -> target.getAttribute(ATTR_LOAN)?.let { reportLoan(circleApp?.confirmHandover(LoanId(it)) ?: LoanResult.NotAllowed, "Confirmed.", null) }
                ACTION_EDIT_ITEM -> target.getAttribute(ATTR_ITEM)?.let {
                    dispatch(AppAction.EditItem(ItemId(it)))
                    window.location.hash = Screen.EditItem.fragment
                }
                ACTION_RETIRE_ITEM -> target.getAttribute(ATTR_ITEM)?.let { id ->
                    reportItem(circleApp?.retireItem(ItemId(id)) ?: ItemResult.NotAllowed)
                }
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
