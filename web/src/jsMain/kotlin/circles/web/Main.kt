package circles.web

import circles.app.AppModel
import circles.app.CircleApp
import circles.app.InviteLink
import circles.app.ItemLink
import circles.app.VerifyLink
import circles.app.AddDeviceResult
import circles.app.CalendarDay
import circles.app.DeviceLink
import circles.app.DeviceRemoval
import circles.app.ItemResult
import circles.app.LoanResult
import circles.app.VouchResult
import circles.app.NameResult
import circles.policy.MemberId
import circles.event.DisputeClaim
import circles.event.DisputeOutcome
import circles.event.LoanId
import circles.event.ReturnOutcome
import circles.policy.ItemClass
import circles.policy.ItemId
import circles.app.LeaveResult
import circles.app.MAX_DEVICE_NAME_CHARS
import circles.app.ExportResult
import circles.event.PauseId
import circles.app.PauseResult
import circles.app.PhotoResult
import circles.app.RulesResult
import circles.app.SkillResult
import circles.app.RebuildResult
import circles.app.RecoveryCodeResult
import circles.app.ShareMakingResult
import circles.app.RemoveResult
import circles.app.RestoreLink
import circles.app.RestoreResult
import circles.keys.RecoveryParse
import circles.app.StartResult
import circles.app.StewardResult
import circles.crypto.JsCipher
import circles.crypto.JsCrypto
import circles.event.DeviceId
import circles.keys.ChangePassphraseResult
import circles.keys.JsPasswordHasher
import circles.keys.KeyVault
import circles.keys.UnlockResult
import circles.sync.FetchBlobTransport
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.await
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
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import org.w3c.dom.url.URL

private const val ROOT_ID = "app"
private const val MILLIS_PER_DAY = 86_400_000.0
private const val POLL_MILLIS = 60_000
private const val UPDATE_CHECK_MILLIS = 60 * 60 * 1000
private const val WORKER_PATH = "/sw.js"
private const val PAIRING_CHECK_MILLIS = 15_000
private const val MIN_PASSPHRASE_NOTICE = "Choose a longer passphrase: at least %d characters."

/**
 * Browser glue only: holds the state, re-renders the page, and turns events into [AppAction]s and calls on
 * [CircleApp]. Everything that decides anything lives in `shared` (CircleApp) or in [reduce] and the views.
 */
private var cachedWordlist: List<String>? = null

/** The recovery wordlist, fetched once from the server's own files (it is not part of the script; ADR 0047). */
private suspend fun loadWordlist(): List<String> {
    cachedWordlist?.let { return it }
    val text = try {
        window.fetch("/wordlist.txt").await().text().await()
    } catch (_: Throwable) {
        return emptyList()
    }
    return text.lines().map { it.trim() }.filter { it.isNotEmpty() }.also { cachedWordlist = it }
}

private class Page(private val root: HTMLElement, private val scope: CoroutineScope) {
    private var state = AppState()
    private var app: CircleApp? = null
    private var registration: dynamic = null

    fun dispatch(action: AppAction) {
        // A message goes away by itself after a while (Dismiss is still there), so it never lingers on another page.
        if (action is AppAction.Notice && action.text != null) {
            val text = action.text
            scope.launch {
                delay(NOTICE_MILLIS)
                if (state.notice == text) dispatch(AppAction.Notice(null))
            }
        }
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
        val due = (state.model as? AppModel.Open)?.let { dueCount(it.home) } ?: 0
        document.title = (if (due > 0) "($due due) " else "") + if (state.model is AppModel.Loading) "Lendger" else "${state.screen.title} - Lendger"
        if (screenChanged) document.getElementById(MAIN_ID)?.let { (it as HTMLElement).focus() }
        loadImages()
    }

    /** True if the address held a verify or item link: it is dispatched and the address is replaced by the page's own (ADR 0062). */
    private fun openLink(): Boolean {
        VerifyLink.parse(window.location.hash)?.let { link ->
            dispatch(AppAction.VerifyLinkSeen(link))
            window.history.replaceState(null, "", window.location.pathname + window.location.search + Screen.Verify.fragment)
            return true
        }
        ItemLink.parse(window.location.hash)?.let { link ->
            dispatch(AppAction.ItemLinkSeen(link.item))
            window.history.replaceState(null, "", window.location.pathname + window.location.search + Screen.Items.fragment)
            return true
        }
        return false
    }

    private val imageUrls = HashMap<String, String>()

    /** Fills in every photo on the page that is on show: fetch, open with the circle key, show from an object URL on this device (ADR 0060). */
    fun loadImages() {
        val images = document.querySelectorAll("img[$ATTR_HASH]")
        for (index in 0 until images.length) {
            val image = images.item(index) as? HTMLElement ?: continue
            if (image.getAttribute("src") != null || image.closest("details:not([open])") != null) continue
            val hash = image.getAttribute(ATTR_HASH) ?: continue
            val epoch = image.getAttribute(ATTR_EPOCH_OF)?.toIntOrNull() ?: continue
            imageUrls[hash]?.let { image.setAttribute("src", it); continue }
            scope.launch {
                val bytes = app?.photoBytes(hash, epoch) ?: return@launch
                val raw = bytes.unsafeCast<Int8Array>()
                val blob = Blob(arrayOf(Uint8Array(raw.buffer, raw.byteOffset, raw.length)), BlobPropertyBag(type = imageType(bytes)))
                val url = imageUrls.getOrPut(hash) { URL.createObjectURL(blob) }
                image.setAttribute("src", url)
            }
        }
    }

    suspend fun start() {
        val saved = loadAppearance()
        applyAppearance(saved)
        dispatch(AppAction.AppearanceChanged(saved))
        render(screenChanged = false)
        dispatch(AppAction.OriginKnown(window.location.origin))
        registerServiceWorker()
        openLink()
        InviteLink.parse(window.location.hash)?.let { link ->
            dispatch(AppAction.InviteSeen(link))
            // The secret in the link must not stay in the address bar or the history entry (ADR 0040).
            window.history.replaceState(null, "", window.location.pathname + window.location.search)
        }
        RestoreLink.parse(window.location.hash)?.let { link ->
            dispatch(AppAction.RestoreLinkSeen(link))
            window.history.replaceState(null, "", window.location.pathname + window.location.search)
        }
        DeviceLink.parse(window.location.hash)?.let { link ->
            dispatch(AppAction.DeviceLinkSeen(link))
            window.history.replaceState(null, "", window.location.pathname + window.location.search)
        }
        // A device waiting to be added looks again every 15 seconds (the server limits logins, so not faster).
        window.setInterval({ if (state.model is AppModel.AwaitingApproval) app?.syncNow(SyncTrigger.MANUAL) }, PAIRING_CHECK_MILLIS)
        // A closed "Larger photos" list is not fetched until it is opened (the toggle event does not bubble, so listen while it travels down).
        root.addEventListener("toggle", { loadImages() }, true)
        // A QR code's link (a member to check, an item's tag) is handled where it lands, and the address is tidied to its page.
        window.addEventListener("hashchange", { if (!openLink()) dispatch(AppAction.Navigate(Screen.fromFragment(window.location.hash))) })
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
                dispatch(AppAction.Notice("This browser cannot keep your data (${opened.reason}), so Lendger cannot run here."))
                return
            }
        }
        val vault = KeyVault(scope, crypto, cipher, JsPasswordHasher(), replica)
        val circleApp = CircleApp(
            scope, crypto, cipher, vault, replica, replica,
            { identity -> HttpSyncApi(FetchTransport(), crypto, identity) },
            { (kotlin.js.Date.now() / MILLIS_PER_DAY).toInt() },
            FetchStreamTransport(),
            wordlist = { loadWordlist() },
            blobTransport = FetchBlobTransport(),
            passkeys = circles.keys.WebAuthnPasskeys { crypto.randomBytes(it) },
        )
        app = circleApp
        scope.launch {
            var wasOpen = false
            circleApp.model.collect {
                dispatch(AppAction.ModelChanged(it))
                // After signing in, show the page the address bar names (a bookmark, a reload), not always Home.
                if (it is AppModel.Open && !wasOpen && state.verifying == null && state.focusItem == null) dispatch(AppAction.Navigate(Screen.fromFragment(window.location.hash)))
                wasOpen = it is AppModel.Open
            }
        }
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
    /**
     * Runs [work] for a click or a submit: the button shows a spinner and ignores further clicks until it ends, so a slow step
     * (unlocking runs Argon2, syncing waits for the network) never looks like nothing happened (ADR 0057).
     */
    private fun busyLaunch(button: Element?, work: suspend () -> Unit) {
        if (button?.getAttribute("aria-busy") == "true") return
        button?.setAttribute("aria-busy", "true")
        scope.launch {
            try {
                work()
            } finally {
                button?.removeAttribute("aria-busy")
            }
        }
    }

    private fun onInput(event: Event) {
        (event.target as? HTMLInputElement)?.takeIf { it.id == ID_SKILL_SEARCH }?.let { field ->
            // Filtering happens here, on this device, as you type: nothing is sent anywhere.
            val query = field.value.trim().lowercase()
            val rows = document.querySelectorAll("[$ATTR_SKILL_TEXT]")
            var shown = 0
            for (index in 0 until rows.length) {
                val row = rows.item(index) as? HTMLElement ?: continue
                val match = query.isEmpty() || (row.getAttribute(ATTR_SKILL_TEXT) ?: "").contains(query)
                row.hidden = !match
                if (match) shown++
            }
            document.getElementById(ID_SKILL_COUNT)?.textContent = if (shown == 1) "1 offer" else "$shown offers"
        }
        (event.target as? HTMLInputElement)?.takeIf { it.id == FIELD_DEVICE_CODE }?.let { field ->
            val preview = app?.previewDeviceCode(field.value)
            document.getElementById(ID_DEVICE_PREVIEW)?.textContent = when {
                field.value.isBlank() -> ""
                preview == null -> "That does not look like a device code."
                else -> "Fingerprint of this code: ${preview.fingerprint}"
            }
        }
        val form = (event.target as? Element)?.closest("form") as? HTMLFormElement ?: return
        val button = form.querySelector("#$SUBMIT_ID") as? HTMLElement ?: return
        var ready = true
        val needy = form.querySelectorAll("[$ATTR_MIN]")
        for (index in 0 until needy.length) {
            val field = needy.item(index) as? Element ?: continue
            val min = field.getAttribute(ATTR_MIN)?.toIntOrNull() ?: continue
            val value = field.asDynamic().value as String
            // Passphrases count every character; other text ignores spaces at the ends.
            val length = if (field.getAttribute("type") == "password") value.length else value.trim().length
            if (length < min) ready = false
        }
        val boxes = form.querySelectorAll("[$ATTR_MUST_CHECK]")
        for (index in 0 until boxes.length) {
            if ((boxes.item(index) as? HTMLInputElement)?.checked != true) ready = false
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
        busyLaunch((event.asDynamic().submitter as? Element) ?: form.querySelector("#$SUBMIT_ID")) {
            if (form.id.startsWith(FORM_CONFIRM_NAME_PREFIX)) {
                val member = form.getAttribute(ATTR_MEMBER)?.let(::MemberId)
                if (member != null) reportName(circleApp.confirmName(member), "Confirmed. Others now see that you checked this name.")
                return@busyLaunch
            }
            if (form.id.startsWith(FORM_DISPUTE_PREFIX)) {
                val loan = form.getAttribute(ATTR_LOAN)?.let(::LoanId)
                val claim = DisputeClaim.entries.firstOrNull { it.name == field(FIELD_CLAIM) }
                if (loan != null && claim != null) reportLoan(circleApp.disputeReturn(loan, claim, field(FIELD_NOTE)), "Reported. A steward who is not part of this loan will decide.", null)
                return@busyLaunch
            }
            if (form.id.startsWith(FORM_LOAN_CHANGE_PREFIX)) {
                val loan = form.getAttribute(ATTR_LOAN)?.let(::LoanId)
                val from = CalendarDay.parse(field(FIELD_FROM))
                val to = CalendarDay.parse(field(FIELD_TO))
                if (loan == null || from == null || to == null) dispatch(AppAction.Notice("Choose both dates."))
                else reportLoan(circleApp.respondToLoan(loan, true, "", from, to), "Approved with the new dates. They can now read the details.", null)
                return@busyLaunch
            }
            if (form.id.startsWith(FORM_RETURN_PREFIX)) {
                val loan = form.getAttribute(ATTR_LOAN)?.let(::LoanId)
                val outcome = ReturnOutcome.entries.firstOrNull { it.name == field(FIELD_OUTCOME) }
                if (loan != null && outcome != null) reportLoan(circleApp.returnLoan(loan, outcome), "Thank you. Reported back.", null)
                return@busyLaunch
            }
            when (form.id) {
                FORM_MY_NAME -> reportName(circleApp.setMyName(field(FIELD_DISPLAY_NAME)), "Thank you. Your name is saved.")
                FORM_RENAME -> reportName(circleApp.setMyName(field(FIELD_DISPLAY_NAME)), "Your name is changed.")
                FORM_START -> report(circleApp.startCircle(field(FIELD_PASSPHRASE)))
                FORM_JOIN -> state.pendingInvite?.let { report(circleApp.join(it, field(FIELD_PASSPHRASE))) }
                FORM_LINK -> {
                    val link = InviteLink.parse(field(FIELD_LINK))
                    if (link == null) dispatch(AppAction.Notice("That does not look like an invite link. Paste the whole link you were sent."))
                    else dispatch(AppAction.InviteSeen(link))
                }
                FORM_ITEM_ADD -> reportItem(circleApp.addItem(field(FIELD_NAME), kind(field(FIELD_KIND)), field(FIELD_DETAILS), tierOrNull(field(FIELD_MIN_TIER))))
                FORM_ITEM_EDIT -> state.editing?.let { reportItem(circleApp.editItem(it, field(FIELD_NAME), kind(field(FIELD_KIND)), field(FIELD_DETAILS), tierOrNull(field(FIELD_MIN_TIER)))) }
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
                FORM_APPEARANCE -> {
                    val chosen = Appearance.fromKeys(field(FIELD_TEXT_SIZE), field(FIELD_THEME))
                    saveAppearance(chosen)
                    dispatch(AppAction.AppearanceChanged(chosen))
                    dispatch(AppAction.Notice("Saved."))
                }
                FORM_AUTOLOCK -> field(FIELD_MINUTES).toIntOrNull()?.let {
                    circleApp.setAutoLockMinutes(it)
                    dispatch(AppAction.Notice("Saved."))
                }
                FORM_STEWARD_RULES -> {
                    val quorum = field(FIELD_QUORUM).toIntOrNull()
                    val inactive = field(FIELD_INACTIVE).toIntOrNull()
                    val fallback = field(FIELD_FALLBACK).toIntOrNull()
                    val recovery = field(FIELD_RECOVERY).toIntOrNull()
                    if (quorum == null || inactive == null || fallback == null || recovery == null) dispatch(AppAction.Notice("Fill in all four numbers."))
                    else reportSteward(circleApp.proposeStewardSettings(quorum, inactive, fallback, recovery))
                }
                FORM_PHOTO_ADD -> state.editing?.let { item ->
                    val file = (form.elements.namedItem(FIELD_PHOTO_FILE) as? HTMLInputElement)?.files?.item(0)
                    if (file == null) dispatch(AppAction.Notice("Choose a photo first."))
                    else when (val prepared = prepareImage(file)) {
                        is PrepareResult.Ready -> reportPhoto(circleApp.addPhoto(item, field(FIELD_PHOTO_ALT), prepared.image.photo, prepared.image.thumb))
                        PrepareResult.NotAnImage -> dispatch(AppAction.Notice("That file could not be read as a picture."))
                        PrepareResult.TooLarge -> dispatch(AppAction.Notice("That picture is too large to use. Try a smaller one."))
                        PrepareResult.CannotShrink -> dispatch(AppAction.Notice("That picture cannot be made small enough. Try another."))
                        is PrepareResult.NotClean -> dispatch(AppAction.Notice("That picture could not be cleaned of its location details, so it was not used. (${prepared.why})"))
                    }
                }
                FORM_SKILL_ADD, FORM_SKILL_EDIT -> {
                    fun checked(name: String) = (form.elements.namedItem(name) as? HTMLInputElement)?.checked == true
                    val openTo = buildSet {
                        if (checked(FIELD_SKILL_GIFT)) add(circles.event.OpenTo.GIFT)
                        if (checked(FIELD_SKILL_SWAP)) add(circles.event.OpenTo.SWAP)
                        if (checked(FIELD_SKILL_THANKS)) add(circles.event.OpenTo.THANKS)
                    }
                    val result = if (form.id == FORM_SKILL_ADD) circleApp.addSkill(field(FIELD_SKILL_NAME), field(FIELD_SKILL_DETAILS), openTo)
                    else state.editingSkill?.let { circleApp.editSkill(it, field(FIELD_SKILL_NAME), field(FIELD_SKILL_DETAILS), openTo) } ?: SkillResult.NotAllowed
                    reportSkill(result)
                }
                FORM_NOTE_ADD -> {
                    val kind = circles.event.ContributionKind.entries.firstOrNull { it.name == field(FIELD_NOTE_KIND) }
                    val result = if (kind == null) null else circleApp.addContribution(kind, MemberId(field(FIELD_NOTE_WITH)), field(FIELD_NOTE_TEXT))
                    dispatch(
                        AppAction.Notice(
                            when (result) {
                                circles.app.NoteResult.Sent -> "Sent. It counts once they confirm it."
                                is circles.app.NoteResult.Refused -> result.reason.replaceFirstChar { it.uppercase() } + "."
                                else -> "That cannot be done right now."
                            },
                        ),
                    )
                }
                FORM_RULES -> {
                    fun tenths(name: String): Int? = field(name).toDoubleOrNull()?.let { (it * 10 + 0.5).toInt() }
                    val numbers = listOf(tenths("rules-regular"), tenths("rules-trusted"), tenths("rules-steward")) +
                        listOf("rules-halflife", "rules-vouches", "rules-vouch-days", "rules-reviewers", "rules-pause-days", "rules-cooldown").map { field(it).toIntOrNull() }
                    if (numbers.any { it == null }) dispatch(AppAction.Notice("Fill in every number."))
                    else reportRules(circleApp.proposeRules(rulesOf(numbers.map { it!! })))
                }
                FORM_PAUSE_PROPOSE -> dispatch(
                    AppAction.Notice(
                        pauseText(
                            circleApp.proposePause(MemberId(field(FIELD_PAUSE_SUBJECT)), field(FIELD_PAUSE_REASON), field(FIELD_PAUSE_DAYS).toIntOrNull() ?: 0),
                            "The pause is proposed. The member can see it now. It takes effect when enough other trusted members agree.",
                        ),
                    ),
                )
                FORM_PAUSE_APPEAL -> form.getAttribute(ATTR_PAUSE)?.let { id ->
                    dispatch(AppAction.Notice(pauseText(circleApp.appealPause(PauseId(id), field(FIELD_PAUSE_NOTE)), "Your appeal was sent to the reviewers.")))
                }
                FORM_MAKE_SHARES -> {
                    val threshold = field(FIELD_THRESHOLD).toIntOrNull()
                    dispatch(
                        AppAction.Notice(
                            when (val result = if (threshold == null) null else circleApp.makeRecoveryShares(threshold)) {
                                null -> "Enter a whole number."
                                is ShareMakingResult.Sent -> "Sent. Each of the ${result.stewards} stewards now has a share; any ${result.threshold} can rebuild the key."
                                is ShareMakingResult.BadThreshold -> "Choose a number from ${result.min} to ${result.max}."
                                ShareMakingResult.NoKey -> "This device does not have the current key."
                                ShareMakingResult.NotAllowed -> "Only a steward can make shares."
                            },
                        ),
                    )
                }
                FORM_REBUILD_KEY -> dispatch(
                    AppAction.Notice(
                        when (val result = circleApp.rebuildKey(field(FIELD_SHARE_CODES).lines())) {
                            is RebuildResult.Rebuilt -> "The key is rebuilt and this device can read the circle again (key number ${result.epoch})."
                            RebuildResult.NoCodes -> "Paste at least one share code."
                            is RebuildResult.BadCode -> if (result.mistyped) "Code ${result.position} has a mistake in it. Check it with the steward who read it out." else "Code ${result.position} is not a share code."
                            RebuildResult.DifferentEpochs -> "These codes are for different keys. Use codes for the same key number."
                            RebuildResult.AlreadyHaveKey -> "This device already has that key."
                            is RebuildResult.Wrong -> result.reason.replaceFirstChar { it.uppercase() } + "."
                            RebuildResult.NotAllowed -> "Only a steward can rebuild the key."
                        },
                    ),
                )
                FORM_PAIR -> state.pendingDevice?.let { report(circleApp.startOnNewDevice(it, field(FIELD_PASSPHRASE))) }
                FORM_ADD_DEVICE -> dispatch(
                    AppAction.Notice(
                        when (val result = circleApp.addDevice(field(FIELD_DEVICE_CODE), field(FIELD_DEVICE_NAME))) {
                            is AddDeviceResult.Added -> "Added. The new device will open as soon as it checks in."
                            AddDeviceResult.BadCode -> "That does not look like a device code. Copy the whole code shown on the new device."
                            AddDeviceResult.AlreadyKnown -> "That device is already part of the circle."
                            AddDeviceResult.NameTooLong -> "Keep the name to $MAX_DEVICE_NAME_CHARS characters or fewer."
                            AddDeviceResult.NotLetInYet -> "A steward needs to let you in before you can add a device."
                            AddDeviceResult.NotAllowed -> "That cannot be done right now."
                        },
                    ),
                )
                FORM_RECOVERY_CHECK -> {
                    val draft = state.recoveryDraft
                    if (draft == null) dispatch(AppAction.Notice("There is no recovery code being made."))
                    else when (circleApp.confirmRecoveryCode(state.origin, draft.askFor.map { field("word-$it") })) {
                        is RecoveryCodeResult.Created -> {
                            dispatch(AppAction.RecoveryDraftReady(null))
                            dispatch(AppAction.Notice("Your recovery code is saved. Keep the paper safe."))
                            window.location.hash = Screen.Settings.fragment
                        }
                        RecoveryCodeResult.WrongWords -> dispatch(AppAction.Notice("Those words do not match the ones shown. Check your paper and try again."))
                        RecoveryCodeResult.NotLetInYet -> dispatch(AppAction.Notice("A steward needs to let you in before you can make a recovery code."))
                        else -> dispatch(AppAction.Notice("That cannot be done right now."))
                    }
                }
                FORM_RESTORE -> state.pendingRestore?.let { link ->
                    dispatch(AppAction.Notice("Restoring..."))
                    dispatch(
                        AppAction.Notice(
                            when (val result = circleApp.restoreFromCode(link, field(FIELD_WORDS), field(FIELD_PASSPHRASE))) {
                                RestoreResult.Restored -> "Restored. This browser is now a device of your account."
                                is RestoreResult.BadWords -> when (val problem = result.problem) {
                                    is RecoveryParse.WrongCount -> "A recovery code has 12 words; you typed ${problem.count}."
                                    is RecoveryParse.UnknownWord -> "Word ${problem.position} (\"${problem.word}\") is not one of the recovery words. Check the spelling."
                                    else -> "Those 12 words do not make a recovery code. A word may be wrong or out of order."
                                }
                                is RestoreResult.PassphraseTooShort -> "Choose a longer passphrase: at least ${result.minimum} characters."
                                RestoreResult.AlreadyHasIdentity -> "This browser already has an identity. Unlock it instead."
                                RestoreResult.NotFound -> "Nothing in this circle matches those words. Check the words and the link, or the code may have been replaced or used."
                                RestoreResult.WordlistUnavailable -> "The word list could not be loaded. Check the connection and try again."
                            },
                        ),
                    )
                }
                FORM_UNLOCK -> when (val result = circleApp.unlock(field(FIELD_PASSPHRASE))) {
                    UnlockResult.Unlocked -> dispatch(AppAction.Notice(null))
                    UnlockResult.WrongPassphrase -> dispatch(AppAction.Notice("That passphrase did not work. Try again."))
                    UnlockResult.NoIdentity -> dispatch(AppAction.Notice("There is nothing saved on this device."))
                    is UnlockResult.Unsupported -> dispatch(AppAction.Notice("This device's saved keys need a newer version of Lendger (${result.reason})."))
                }
            }
        }
    }

    private fun kind(name: String): ItemClass = ItemClass.entries.firstOrNull { it.name == name } ?: ItemClass.EVERYDAY

    private fun reportName(result: NameResult, done: String) {
        dispatch(
            AppAction.Notice(
                when (result) {
                    NameResult.Saved -> done
                    is NameResult.Refused -> result.reason.replaceFirstChar { it.uppercase() } + "."
                    NameResult.NotLetInYet -> "A steward needs to let you in before you can set your name."
                    NameResult.NotAllowed -> "That cannot be done right now. Try syncing, then again."
                },
            ),
        )
    }

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
            is LoanResult.NotYet -> dispatch(AppAction.Notice("Not yet: " + (result.howToUnlock.firstOrNull() ?: result.reasons.lastOrNull().orEmpty())))
            LoanResult.AlreadyBooked -> dispatch(AppAction.Notice("Someone else has been promised that item for some of those days. Try other dates."))
            is LoanResult.Paused -> dispatch(AppAction.Notice((result.reasons + "You can still borrow essentials. Open Pauses to appeal.").joinToString(" ")))
            LoanResult.NotLetInYet -> dispatch(AppAction.Notice("A steward needs to let you in before you can borrow."))
            LoanResult.NotAllowed -> dispatch(AppAction.Notice("That cannot be done right now. Try syncing, then again."))
        }
    }

    private fun reportSkill(result: SkillResult, done: String = "Saved.") {
        dispatch(
            AppAction.Notice(
                when (result) {
                    is SkillResult.Saved -> done
                    is SkillResult.Refused -> result.reason.replaceFirstChar { it.uppercase() } + "."
                    SkillResult.NotAllowed -> "That cannot be done right now."
                },
            ),
        )
        if (result is SkillResult.Saved) window.location.hash = Screen.Skills.fragment
    }

    private fun reportPhoto(result: PhotoResult, done: String = "Photo added.") {
        dispatch(
            AppAction.Notice(
                when (result) {
                    PhotoResult.Saved -> done
                    PhotoResult.Offline -> "Could not reach the server, so the photo was not added. Try again when you are online."
                    PhotoResult.Full -> "The circle's photo storage is full."
                    PhotoResult.TooMany -> "An item can have ${circles.event.MAX_PHOTOS_PER_ITEM} photos. Remove one first."
                    PhotoResult.TooBig -> "That photo is too large once prepared."
                    is PhotoResult.Refused -> result.reason.replaceFirstChar { it.uppercase() } + "."
                    PhotoResult.NotAllowed -> "That cannot be done right now."
                },
            ),
        )
    }

    private fun reportRules(result: RulesResult) {
        dispatch(
            AppAction.Notice(
                when (result) {
                    is RulesResult.Sent -> if (result.takesEffect) "Done. The new rules apply from today." else "Sent. The rules change when enough stewards agree."
                    is RulesResult.Refused -> result.reason.replaceFirstChar { it.uppercase() } + "."
                    RulesResult.NotAllowed -> "Only a steward can do that."
                },
            ),
        )
    }

    private fun reportSteward(result: StewardResult) {
        dispatch(
            AppAction.Notice(
                when (result) {
                    is StewardResult.Sent -> if (result.takesEffect) "Done. It takes effect now." else "Sent. It takes effect when enough stewards agree."
                    is StewardResult.Refused -> result.reason.replaceFirstChar { it.uppercase() } + "."
                    StewardResult.NotAllowed -> "Only a steward can do that."
                },
            ),
        )
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
        busyLaunch(target) {
            when (target.getAttribute(ATTR_ACTION)) {
                ACTION_UPDATE -> registration?.waiting?.postMessage(js("({type: 'SKIP_WAITING'})"))
                ACTION_DISMISS -> dispatch(AppAction.Notice(null))
                ACTION_LOCK -> circleApp?.lock()
                ACTION_UNLOCK_PASSKEY -> when (val result = circleApp?.unlockWithPasskey()) {
                    UnlockResult.Unlocked -> dispatch(AppAction.Notice(null))
                    is UnlockResult.Unsupported -> dispatch(AppAction.Notice("This device's saved keys need a newer version of Lendger (${result.reason})."))
                    else -> dispatch(AppAction.Notice("That did not unlock it. Try again, or type your passphrase."))
                }
                ACTION_PASSKEY_ON -> dispatch(
                    AppAction.Notice(
                        when (circleApp?.enablePasskey()) {
                            circles.keys.PasskeyResult.Done -> "Done. Next time you can unlock with this device."
                            circles.keys.PasskeyResult.NotUnlocked -> "Unlock with your passphrase first."
                            else ->
                                if (Regex("""^[0-9.]+$|:""").containsMatchIn(window.location.hostname)) "Passkeys need Lendger to be opened by a name such as circles.example.org, not by a number like ${window.location.hostname}. Nothing changed; your passphrase still works."
                                else "This browser or device could not set that up, so nothing changed. Your passphrase still works."
                        },
                    ),
                )
                ACTION_PASSKEY_OFF -> { circleApp?.disablePasskey(); dispatch(AppAction.Notice("Turned off. Your passphrase still works.")) }
                ACTION_SYNC -> circleApp?.syncNow()
                ACTION_RECOVERY_START -> {
                    val draft = circleApp?.prepareRecoveryCode(state.origin)
                    if (draft == null) dispatch(AppAction.Notice("The word list could not be loaded. Check the connection and try again."))
                    else {
                        dispatch(AppAction.RecoveryDraftReady(draft))
                        window.location.hash = Screen.RecoveryCode.fragment
                    }
                }
                ACTION_RECOVERY_CANCEL -> {
                    circleApp?.discardRecoveryDraft()
                    dispatch(AppAction.RecoveryDraftReady(null))
                    window.location.hash = Screen.Settings.fragment
                }
                ACTION_REMOVE_MEMBER -> target.getAttribute(ATTR_MEMBER)?.let { id ->
                    dispatch(
                        AppAction.Notice(
                            when (circleApp?.removeMember(MemberId(id))) {
                                RemoveResult.Removed -> "Removed. The circle's key has been changed."
                                RemoveResult.IsSteward -> "A steward must first step down on the Stewards page."
                                RemoveResult.NotInCircle -> "That person is not in the circle."
                                else -> "Only a steward can remove someone."
                            },
                        ),
                    )
                }
                ACTION_ROTATE_KEY -> dispatch(AppAction.Notice(if (circleApp?.rotateKeyNow() == RemoveResult.Removed) "The circle's key has been changed." else "There is nothing to change right now."))
                ACTION_LEAVE -> dispatch(
                    AppAction.Notice(
                        when (circleApp?.leaveCircle()) {
                            LeaveResult.Left -> "You have left the circle. This device has forgotten it."
                            LeaveResult.StewardMustStepDown -> "Step down as a steward first."
                            else -> "Could not send your departure. Check the connection and try again; nothing was forgotten."
                        },
                    ),
                )
                ACTION_REMOVE_DEVICE -> target.getAttribute(ATTR_DEVICE_ID)?.let { id ->
                    dispatch(AppAction.Notice(if (circleApp?.removeDevice(DeviceId(id)) == DeviceRemoval.Removed) "Removed. The circle's key is being changed." else "That device cannot be removed."))
                }
                ACTION_EXPORT -> when (val result = circleApp?.exportCircle()) {
                    is ExportResult.Ready -> {
                        download(result.fileName, result.bytes)
                        dispatch(AppAction.Notice(exportNotice(result)))
                    }
                    ExportResult.Incomplete -> dispatch(AppAction.Notice("This device does not have the whole log yet. Press Sync now, then try again."))
                    ExportResult.NotAllowed, null -> dispatch(AppAction.Notice("Open the circle first."))
                }
                ACTION_RULES_AGREE -> target.getAttribute(ATTR_RULES)?.split(':')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 9 }?.let {
                    reportRules(circleApp?.proposeRules(rulesOf(it)) ?: RulesResult.NotAllowed)
                }
                ACTION_NOTE_CONFIRM -> target.getAttribute(ATTR_NOTE_ID)?.let { id ->
                    dispatch(
                        AppAction.Notice(
                            when (val result = circleApp?.confirmContribution(circles.event.NoteId(id))) {
                                circles.app.NoteResult.Sent -> "Confirmed. Thank you."
                                is circles.app.NoteResult.Refused -> result.reason.replaceFirstChar { it.uppercase() } + "."
                                else -> "That cannot be done right now."
                            },
                        ),
                    )
                }
                ACTION_EDIT_SKILL -> target.getAttribute(ATTR_SKILL)?.let {
                    dispatch(AppAction.EditSkill(circles.event.SkillId(it)))
                    window.location.hash = Screen.EditSkill.fragment
                }
                ACTION_RETIRE_SKILL -> target.getAttribute(ATTR_SKILL)?.let { reportSkill(circleApp?.retireSkill(circles.event.SkillId(it)) ?: SkillResult.NotAllowed, "Removed.") }
                ACTION_PAUSE_REVIEW -> target.getAttribute(ATTR_PAUSE)?.let { id ->
                    val agree = target.getAttribute(ATTR_AGREE) == "true"
                    dispatch(AppAction.Notice(pauseText(circleApp?.reviewPause(PauseId(id), agree) ?: PauseResult.NotAllowed, if (agree) "You agreed." else "Your answer was recorded.")))
                }
                ACTION_PAUSE_LIFT -> target.getAttribute(ATTR_PAUSE)?.let { id ->
                    dispatch(AppAction.Notice(pauseText(circleApp?.liftPause(PauseId(id)) ?: PauseResult.NotAllowed, "Done. The pause is lifted.")))
                }
                ACTION_SHARE_SHOW -> target.getAttribute(ATTR_EPOCH)?.toIntOrNull()?.let { epoch ->
                    dispatch(AppAction.ShareCodeShown(epoch, circleApp?.shareCode(epoch)))
                }
                ACTION_SHARE_HIDE -> target.getAttribute(ATTR_EPOCH)?.toIntOrNull()?.let { dispatch(AppAction.ShareCodeShown(it, null)) }
                ACTION_TOGGLE_SECRET -> target.getAttribute(ATTR_TARGET)?.let { id ->
                    (document.getElementById(id) as? HTMLInputElement)?.let { field ->
                        val show = field.type == "password"
                        field.type = if (show) "text" else "password"
                        // The name stays "Show passphrase"; the pressed state says it is showing (the icon follows aria-pressed).
                        target.setAttribute("aria-pressed", if (show) "true" else "false")
                    }
                }
                ACTION_CHECK_NOW -> circleApp?.syncNow(SyncTrigger.MANUAL)
                ACTION_COPY_FIELD -> target.getAttribute(ATTR_FROM)?.let { id ->
                    (document.getElementById(id) as? HTMLInputElement)?.let {
                        window.navigator.asDynamic().clipboard.writeText(it.value)
                        // Feedback on the button itself (a page-top notice can be off screen, and re-drawing would wipe what is typed).
                        val original = target.textContent
                        target.setAttribute("aria-live", "polite")
                        target.textContent = "Copied"
                        scope.launch {
                            delay(COPIED_MILLIS)
                            target.textContent = original
                        }
                    }
                }
                ACTION_VOUCH -> target.getAttribute(ATTR_MEMBER)?.let { id ->
                    dispatch(
                        AppAction.Notice(
                            when (circleApp?.vouchFor(MemberId(id))) {
                                is VouchResult.Done -> "You vouched for them."
                                VouchResult.NotLetInYet -> "A steward needs to let you in before you can vouch."
                                else -> "You cannot vouch for them right now."
                            },
                        ),
                    )
                }
                ACTION_STEWARD_ADD -> target.getAttribute(ATTR_SUBJECT)?.let { reportSteward(circleApp?.proposeSteward(MemberId(it)) ?: StewardResult.NotAllowed) }
                ACTION_STEWARD_REMOVE -> target.getAttribute(ATTR_SUBJECT)?.let { reportSteward(circleApp?.removeSteward(MemberId(it)) ?: StewardResult.NotAllowed) }
                ACTION_STEWARD_SETTINGS -> target.getAttribute(ATTR_SUBJECT)?.split(':')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 3 || it.size == 4 }?.let {
                    reportSteward(circleApp?.proposeStewardSettings(it[0], it[1], it[2], it.getOrElse(3) { 0 }) ?: StewardResult.NotAllowed)
                }
                ACTION_BORROW_ITEM -> target.getAttribute(ATTR_ITEM)?.let {
                    dispatch(AppAction.BorrowItem(ItemId(it)))
                    window.location.hash = Screen.RequestLoan.fragment
                }
                ACTION_APPROVE_LOAN -> target.getAttribute(ATTR_LOAN)?.let { reportLoan(circleApp?.respondToLoan(LoanId(it), true, "") ?: LoanResult.NotAllowed, "Approved. They can now read the details.", null) }
                ACTION_DECLINE_LOAN -> target.getAttribute(ATTR_LOAN)?.let { reportLoan(circleApp?.respondToLoan(LoanId(it), false, "") ?: LoanResult.NotAllowed, "Declined.", null) }
                ACTION_CONFIRM_RETURN -> target.getAttribute(ATTR_LOAN)?.let { reportLoan(circleApp?.confirmReturn(LoanId(it)) ?: LoanResult.NotAllowed, "Confirmed. Thank you.", null) }
                ACTION_UPHOLD -> target.getAttribute(ATTR_LOAN)?.let { reportLoan(circleApp?.resolveDispute(LoanId(it), DisputeOutcome.UPHELD) ?: LoanResult.NotAllowed, "Decided: the owner's report is upheld.", null) }
                ACTION_DISMISS_DISPUTE -> target.getAttribute(ATTR_LOAN)?.let { reportLoan(circleApp?.resolveDispute(LoanId(it), DisputeOutcome.DISMISSED) ?: LoanResult.NotAllowed, "Decided: the return counts as reported.", null) }
                ACTION_CANCEL_LOAN -> target.getAttribute(ATTR_LOAN)?.let { reportLoan(circleApp?.cancelLoan(LoanId(it)) ?: LoanResult.NotAllowed, "Cancelled.", null) }
                ACTION_HANDOVER -> target.getAttribute(ATTR_LOAN)?.let { reportLoan(circleApp?.confirmHandover(LoanId(it)) ?: LoanResult.NotAllowed, "Confirmed.", null) }
                ACTION_EDIT_ITEM -> target.getAttribute(ATTR_ITEM)?.let {
                    dispatch(AppAction.EditItem(ItemId(it)))
                    window.location.hash = Screen.EditItem.fragment
                }
                ACTION_TAG_ITEM -> target.getAttribute(ATTR_ITEM)?.let {
                    dispatch(AppAction.TagItem(ItemId(it)))
                    window.location.hash = Screen.ItemTag.fragment
                }
                ACTION_SHOW_ALL_ITEMS -> dispatch(AppAction.ShowAllItems)
                ACTION_PRINT -> window.print()
                ACTION_REMOVE_PHOTO -> {
                    val item = target.getAttribute(ATTR_ITEM)
                    val photo = target.getAttribute(ATTR_PHOTO)
                    if (item != null && photo != null) reportPhoto(circleApp?.removePhoto(ItemId(item), photo) ?: PhotoResult.NotAllowed, "Removed.")
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

private const val STORE_TEXT_SIZE = "circles.textSize"
private const val STORE_THEME = "circles.theme"

/** Reads the saved choice (local storage can be unavailable, then the defaults hold). */
private fun loadAppearance(): Appearance = try {
    Appearance.fromKeys(window.localStorage.getItem(STORE_TEXT_SIZE), window.localStorage.getItem(STORE_THEME))
} catch (e: Throwable) {
    Appearance()
}

/** Remembers the choice and applies it to the page: `data-text` and `data-theme` on `<html>` are what styles.css reads. */
private fun saveAppearance(appearance: Appearance) {
    applyAppearance(appearance)
    try {
        window.localStorage.setItem(STORE_TEXT_SIZE, appearance.textSize.key)
        window.localStorage.setItem(STORE_THEME, appearance.theme.key)
    } catch (e: Throwable) {
        // Not saved for next time; still applied now.
    }
}

private fun applyAppearance(appearance: Appearance) {
    document.documentElement?.setAttribute("data-text", appearance.textSize.key)
    document.documentElement?.setAttribute("data-theme", appearance.theme.key)
}

fun main() {
    val root = document.getElementById(ROOT_ID) as? HTMLElement ?: error("index.html has no #$ROOT_ID element")
    val scope = MainScope()
    scope.launch { Page(root, scope).start() }
}


/** Offers [bytes] to the user as a file download without sending them anywhere (an object URL on this page only). */
private fun download(fileName: String, bytes: ByteArray) {
    val raw = bytes.unsafeCast<Int8Array>()
    val blob = Blob(arrayOf(Uint8Array(raw.buffer, raw.byteOffset, raw.length)), BlobPropertyBag(type = "application/zip"))
    val url = URL.createObjectURL(blob)
    val link = document.createElement("a") as HTMLElement
    link.setAttribute("href", url)
    link.setAttribute("download", fileName)
    document.body?.appendChild(link)
    link.click()
    link.remove()
    window.setTimeout({ URL.revokeObjectURL(url) }, DOWNLOAD_URL_LIFETIME_MILLIS)
}

private const val DOWNLOAD_URL_LIFETIME_MILLIS = 10_000

/** What to tell the user after an export: the events, the photos that went in, and any that could not be fetched. */
internal fun exportNotice(result: ExportResult.Ready): String {
    val photos = if (result.photos > 0) " and ${result.photos} photo files" else ""
    val missing = if (result.photosMissing > 0) " ${result.photosMissing} photo files could not be fetched, so they are not in it; try again when online." else ""
    return "Downloaded ${result.fileName} with ${result.events} events$photos.$missing"
}

private fun pauseText(result: PauseResult, done: String): String = when (result) {
    PauseResult.Sent -> done
    is PauseResult.Refused -> result.reason.replaceFirstChar { it.uppercase() } + "."
    PauseResult.NotAllowed -> "You cannot do that right now."
}

private fun rulesOf(n: List<Int>) = circles.event.ConfigSettings(n[0], n[1], n[2], n[3], n[4], n[5], n[6], n[7], n[8])

private fun tierOrNull(name: String): circles.policy.Tier? = circles.policy.Tier.entries.firstOrNull { it.name == name }

private const val NOTICE_MILLIS = 8_000L
private const val COPIED_MILLIS = 2_000L
