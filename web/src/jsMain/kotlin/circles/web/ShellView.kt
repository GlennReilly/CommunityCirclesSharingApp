package circles.web

import circles.app.AppModel
import circles.app.HomeData
import circles.app.MIN_RECOVERY_THRESHOLD
import circles.app.NoteStatus
import circles.app.SkillSummary
import circles.event.MAX_SKILL_DETAILS_CHARS
import circles.event.MAX_SKILL_NAME_CHARS
import circles.event.OpenTo
import circles.app.PauseRole
import circles.event.ContributionKind
import circles.event.MAX_NOTE_TEXT_CHARS
import circles.app.PauseSummary
import circles.event.MAX_PAUSE_APPEAL_CHARS
import circles.event.MAX_PAUSE_DAYS
import circles.event.MAX_PAUSE_REASON_CHARS
import circles.event.MIN_PAUSE_DAYS
import circles.event.PauseStatus
import circles.app.INVITE_VALID_DAYS
import circles.app.CalendarDay
import circles.app.DeviceLink
import circles.app.DeviceSummary
import circles.app.MAX_DEVICE_NAME_CHARS
import circles.app.ProposalSummary
import circles.event.StewardChange
import circles.event.MAX_STEWARD_FALLBACK_DELAY_DAYS
import circles.event.MAX_STEWARD_INACTIVE_DAYS
import circles.event.MAX_STEWARD_QUORUM
import circles.event.MAX_STEWARD_RECOVERY_THRESHOLD
import circles.event.MIN_STEWARD_FALLBACK_DELAY_DAYS
import circles.event.MIN_STEWARD_INACTIVE_DAYS
import circles.event.MIN_STEWARD_QUORUM
import circles.app.DisputeStatus
import circles.event.DAMAGE_CLAIM_DAYS
import circles.event.DisputeClaim
import circles.app.StandingSummary
import circles.policy.Tier
import circles.app.ItemSummary
import circles.app.LoanRole
import circles.app.LoanSummary
import circles.event.LoanStatus
import circles.event.MAX_LOAN_NOTE_CHARS
import circles.event.ReturnOutcome
import circles.app.MAX_ITEM_DESCRIPTION_CHARS
import circles.event.MAX_ITEM_NAME_CHARS
import circles.policy.ItemClass
import circles.keys.AUTO_LOCK_CHOICES_MINUTES
import circles.keys.MIN_PASSPHRASE_CHARS
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.InputType
import kotlinx.html.ol
import kotlinx.html.option
import kotlinx.html.select
import kotlinx.html.textArea
import kotlinx.html.fieldSet
import kotlinx.html.legend
import kotlinx.html.classes
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.h3
import kotlinx.html.header
import kotlinx.html.id
import kotlinx.html.input
import kotlinx.html.label
import kotlinx.html.li
import kotlinx.html.main
import kotlinx.html.nav
import kotlinx.html.p
import kotlinx.html.role
import kotlinx.html.section
import kotlinx.html.summary
import kotlinx.html.ul

const val UPDATE_BUTTON_ID = "update-now"
const val ACTION_UPDATE = "update-now"
const val MAIN_ID = "main"
const val NOTICE_ID = "notice"

// Form and control ids the browser glue wires up; kept here so the views and the glue cannot drift apart.
const val FORM_START = "start-form"
const val FORM_JOIN = "join-form"
const val FORM_LINK = "link-form"
const val FORM_UNLOCK = "unlock-form"
const val FIELD_PASSPHRASE = "passphrase"
const val FORM_CHANGE_PASSPHRASE = "change-passphrase-form"
const val FORM_AUTOLOCK = "autolock-form"
const val FIELD_CURRENT = "current"
const val FIELD_NEW = "new"
const val FIELD_MINUTES = "minutes"
const val FIELD_LINK = "invite-link"
const val SUBMIT_ID = "submit"
const val ACTION_INVITE = "invite"
const val ACTION_COPY = "copy-invite"
const val ACTION_ADMIT = "admit"
const val ACTION_LOCK = "lock"
const val ACTION_SYNC = "sync"
const val ACTION_ERASE = "erase"
const val ACTION_DISMISS = "dismiss"
const val ACTION_EDIT_ITEM = "edit-item"
const val ACTION_RETIRE_ITEM = "retire-item"
const val FORM_ITEM_ADD = "item-add-form"
const val FORM_ITEM_EDIT = "item-edit-form"
const val FIELD_NAME = "name"
const val FIELD_KIND = "kind"
const val FIELD_MIN_TIER = "min-tier"
const val FORM_RULES = "circle-rules-form"
const val ACTION_RULES_AGREE = "rules-agree"
const val ATTR_RULES = "data-rules"
const val FIELD_DETAILS = "details"
const val ATTR_ITEM = "data-item"
const val ATTR_MIN = "data-min"
const val ATTR_LOAN = "data-loan"
const val ATTR_MEMBER = "data-member"
const val ATTR_SUBJECT = "data-subject"
const val ACTION_STEWARD_ADD = "steward-add"
const val ACTION_STEWARD_REMOVE = "steward-remove"
const val ACTION_STEWARD_SETTINGS = "steward-settings"
const val FORM_STEWARD_RULES = "steward-rules-form"
const val FIELD_QUORUM = "quorum"
const val FIELD_INACTIVE = "inactive-days"
const val FIELD_FALLBACK = "fallback-days"
const val FIELD_RECOVERY = "recovery-threshold"
const val ACTION_VOUCH = "vouch"
const val ACTION_TOGGLE_SECRET = "toggle-secret"
const val ATTR_TARGET = "data-target"
const val USERNAME_FOR_PASSWORD_MANAGERS = "Circles on this device"
const val FORM_SKILL_ADD = "skill-add-form"
const val FORM_SKILL_EDIT = "skill-edit-form"
const val FIELD_SKILL_NAME = "skill-name"
const val FIELD_SKILL_DETAILS = "skill-details"
const val FIELD_SKILL_GIFT = "skill-open-gift"
const val FIELD_SKILL_SWAP = "skill-open-swap"
const val FIELD_SKILL_THANKS = "skill-open-thanks"
const val ID_SKILL_SEARCH = "skill-search"
const val ID_SKILL_COUNT = "skill-count"
const val ATTR_SKILL_TEXT = "data-skill-text"
const val ACTION_EDIT_SKILL = "edit-skill"
const val ACTION_RETIRE_SKILL = "retire-skill"
const val ATTR_SKILL = "data-skill"
const val FORM_NOTE_ADD = "contribution-form"
const val FIELD_NOTE_KIND = "contribution-kind"
const val FIELD_NOTE_WITH = "contribution-with"
const val FIELD_NOTE_TEXT = "contribution-text"
const val ACTION_NOTE_CONFIRM = "contribution-confirm"
const val ATTR_NOTE_ID = "data-note-id"
const val ACTION_PAUSE_REVIEW = "pause-review"
const val ACTION_PAUSE_LIFT = "pause-lift"
const val ATTR_PAUSE = "data-pause"
const val ATTR_AGREE = "data-agree"
const val FORM_PAUSE_PROPOSE = "pause-propose-form"
const val FORM_PAUSE_APPEAL = "pause-appeal-form"
const val FIELD_PAUSE_SUBJECT = "pause-subject"
const val FIELD_PAUSE_REASON = "pause-reason"
const val FIELD_PAUSE_DAYS = "pause-days"
const val FIELD_PAUSE_NOTE = "pause-note"
const val DEFAULT_PAUSE_FORM_DAYS = 30
const val ACTION_EXPORT = "export-circle"
const val FORM_MAKE_SHARES = "make-shares-form"
const val FORM_REBUILD_KEY = "rebuild-key-form"
const val FIELD_THRESHOLD = "share-threshold"
const val FIELD_SHARE_CODES = "share-codes"
const val ACTION_SHARE_SHOW = "share-show"
const val ACTION_SHARE_HIDE = "share-hide"
const val ATTR_EPOCH = "data-epoch"
const val ACTION_RECOVERY_START = "recovery-start"
const val ACTION_RECOVERY_CANCEL = "recovery-cancel"
const val FORM_RECOVERY_CHECK = "recovery-check-form"
const val FORM_RESTORE = "restore-form"
const val FIELD_WORDS = "words"
const val ID_RESTORE_LINK = "recovery-restore-link"
const val ACTION_REMOVE_DEVICE = "remove-device"
const val ACTION_CHECK_NOW = "check-now"
const val ACTION_COPY_FIELD = "copy-field"
const val ATTR_FROM = "data-from"
const val ATTR_DEVICE_ID = "data-device-id"
const val FORM_PAIR = "pair-form"
const val FORM_ADD_DEVICE = "add-device-form"
const val FIELD_DEVICE_CODE = "device-code"
const val FIELD_DEVICE_NAME = "device-name"
const val FIELD_CHECKED = "fingerprints-match"
const val ID_DEVICE_PREVIEW = "device-preview"
const val ID_DEVICE_LINK = "device-link"
const val ID_THIS_DEVICE_CODE = "this-device-code"
const val ACTION_REMOVE_MEMBER = "remove-member"
const val ACTION_ROTATE_KEY = "rotate-key"
const val ACTION_LEAVE = "leave-circle"
const val ACTION_BORROW_ITEM = "borrow-item"
const val ACTION_APPROVE_LOAN = "approve-loan"
const val ACTION_DECLINE_LOAN = "decline-loan"
const val ACTION_HANDOVER = "handover-loan"
const val ACTION_CANCEL_LOAN = "cancel-loan"
const val ACTION_CONFIRM_RETURN = "confirm-return"
const val ACTION_UPHOLD = "uphold-dispute"
const val ACTION_DISMISS_DISPUTE = "dismiss-dispute"
const val FORM_DISPUTE_PREFIX = "dispute-form-"
const val FIELD_CLAIM = "claim"
const val FORM_LOAN_REQUEST = "loan-request-form"
const val FORM_RETURN_PREFIX = "return-form-"
const val FIELD_FROM = "from"
const val FIELD_TO = "to"
const val FIELD_NOTE = "note"
const val FIELD_OUTCOME = "outcome"
private const val ISO_DATE_CHARS = 10
const val ATTR_ACTION = "data-action"
const val ATTR_DEVICE = "data-device"
private const val ID_INVITE_OUTPUT = "invite-output"
private const val MEMBER_SHORT_CHARS = 8

/**
 * The page as a pure function of [AppState]. It writes through any kotlinx-html consumer, so the browser mounts it
 * into the DOM and the tests render it to a string. Focus goes to the `main` landmark after a screen change.
 */
fun FlowContent.shell(state: AppState) {
    header {
        // The app's name is not a heading: each page has exactly one h1 of its own.
        p {
            classes = setOf("app-name")
            +"Circles"
        }
        div {
            id = "sync-status"
            role = "status"
            +describe(state.sync)
        }
        if (state.signedIn) {
            button(type = ButtonType.button) {
                attributes[ATTR_ACTION] = ACTION_SYNC
                +"Sync now"
            }
            button(type = ButtonType.button) {
                attributes[ATTR_ACTION] = ACTION_LOCK
                +"Lock now"
            }
        }
        if (state.updateAvailable) {
            div {
                role = "alert"
                +"A new version is ready. "
                button(type = ButtonType.button) {
                    id = UPDATE_BUTTON_ID
                    attributes[ATTR_ACTION] = ACTION_UPDATE
                    +"Update now"
                }
            }
        }
    }
    if (state.signedIn) {
        nav {
            attributes["aria-label"] = "Main"
            ul {
                for (screen in Screen.navigation) {
                    li {
                        a(href = screen.fragment) {
                            if (screen == state.screen) attributes["aria-current"] = "page"
                            +screen.title
                        }
                    }
                }
            }
        }
    }
    div {
        id = NOTICE_ID
        role = "status"
        state.notice?.let {
            +it
            +" "
            button(type = ButtonType.button) {
                attributes[ATTR_ACTION] = ACTION_DISMISS
                +"Dismiss"
            }
        }
    }
    main {
        id = MAIN_ID
        attributes["tabindex"] = "-1"
        when (val model = state.model) {
            AppModel.Loading -> {
                h1 { +"Circles" }
                p { +"Opening your circle..." }
            }
            AppModel.NoIdentity -> newcomer(state)
            is AppModel.Locked -> signIn()
            is AppModel.AwaitingApproval -> awaiting(model)
            is AppModel.Open -> when (state.screen) {
                Screen.Home -> home(model.home)
                Screen.Members -> members(model.home, state)
                Screen.Settings -> settings(model.home, state)
                Screen.RecoveryCode -> recoveryCodePage(state)
                Screen.Standing -> standing(model.home.standing, model.home)
                Screen.Pauses -> pausesPage(model.home)
                Screen.Stewards -> stewards(model.home)
                Screen.RecoveryShares -> recoverySharesPage(model.home, state)
                Screen.Items -> items(model.home)
                Screen.Skills -> skills(model.home)
                Screen.AddSkill -> skillForm(null)
                Screen.EditSkill -> skillForm(model.home.skills.firstOrNull { it.skill == state.editingSkill })
                Screen.AddItem -> itemForm(null)
                Screen.EditItem -> itemForm(model.home.items.firstOrNull { it.item == state.editing })
                Screen.Borrow -> borrow(model.home)
                Screen.RequestLoan -> requestForm(model.home.items.firstOrNull { it.item == state.borrowing }, model.home)
                else -> comingSoon(state.screen)
            }
        }
    }
}

/**
 * The submit button of a form: hidden until every field marked with [ATTR_MIN] has at least that many characters (the
 * glue shows it as the user types). [ready] is true for a form that starts filled in.
 */
private fun FlowContent.formSubmit(text: String, ready: Boolean = false) {
    button(type = ButtonType.submit) {
        id = SUBMIT_ID
        if (!ready) attributes["hidden"] = "hidden"
        +text
    }
}

/** A button that shows or hides what was typed in the password field [fieldId]; the page script flips it (ADR 0056). */
private fun FlowContent.showHideButton(fieldId: String) {
    button(type = ButtonType.button) {
        attributes[ATTR_ACTION] = ACTION_TOGGLE_SECRET
        attributes[ATTR_TARGET] = fieldId
        attributes["aria-label"] = "Show what I typed"
        attributes["aria-controls"] = fieldId
        +"Show"
    }
}

/**
 * Password managers save a password only next to a username. This one is invisible and never changes: the browser then offers
 * to remember the passphrase, which is the user's choice and kept by the browser, not by Circles (ADR 0056).
 */
private fun FlowContent.usernameForPasswordManagers() {
    input(type = InputType.text, name = "username") {
        classes = setOf("visually-hidden")
        value = USERNAME_FOR_PASSWORD_MANAGERS
        attributes["autocomplete"] = "username"
        attributes["tabindex"] = "-1"
        attributes["aria-hidden"] = "true"
        attributes["readonly"] = "readonly"
    }
}

private fun FlowContent.passphraseField(autocomplete: String, hint: Boolean) {
    usernameForPasswordManagers()
    label {
        attributes["for"] = FIELD_PASSPHRASE
        +"Passphrase: "
    }
    input(type = InputType.password, name = FIELD_PASSPHRASE) {
        id = FIELD_PASSPHRASE
        required = true
        attributes["minlength"] = MIN_PASSPHRASE_CHARS.toString()
        attributes[ATTR_MIN] = MIN_PASSPHRASE_CHARS.toString()
        attributes["autocomplete"] = autocomplete
        attributes["aria-describedby"] = "passphrase-help"
    }
    showHideButton(FIELD_PASSPHRASE)
    if (hint) {
        p {
            id = "passphrase-help"
            +"At least $MIN_PASSPHRASE_CHARS characters; a few random words is good. Circles never stores it: you type it when you open Circles, or let your browser's password manager fill it in if you choose to save it there. "
            +"It protects your keys on this device, and it cannot be recovered if you forget it."
        }
    } else {
        p {
            id = "passphrase-help"
            +"The passphrase you chose on this device."
        }
    }
}

private fun FlowContent.backToWelcome() {
    p {
        a(href = Screen.Welcome.fragment) { +"Back" }
    }
}

/** A browser with no identity: a choice first, then one page for each choice. */
private fun FlowContent.newcomer(state: AppState) {
    when (state.screen) {
        Screen.StartCircle -> startCircle()
        Screen.JoinCircle -> joinCircle(state)
        Screen.PairDevice -> pairDevice(state)
        Screen.Restore -> restorePage(state)
        else -> welcome()
    }
}

private fun FlowContent.welcome() {
    h1 { +Screen.Welcome.title }
    p { +"Circles is a way for neighbours to share things and skills. Everything is encrypted on your own device." }
    ul {
        li {
            a(href = Screen.StartCircle.fragment) { +"Start a circle" }
            +" if you are the first."
        }
        li {
            a(href = Screen.JoinCircle.fragment) { +"Join a circle" }
            +" if someone sent you an invite link."
        }
    }
}

private fun FlowContent.startCircle() {
    h1 { +Screen.StartCircle.title }
    p { +"You become its first steward and can invite others. Choose a passphrase to protect your keys on this device." }
    form {
        id = FORM_START
        passphraseField("new-password", hint = true)
        formSubmit("Start a circle")
    }
    backToWelcome()
}

private fun FlowContent.joinCircle(state: AppState) {
    h1 { +Screen.JoinCircle.title }
    if (state.pendingInvite == null) {
        p { +"Paste the invite link you were sent." }
        form {
            id = FORM_LINK
            label {
                attributes["for"] = FIELD_LINK
                +"Invite link: "
            }
            input(type = InputType.text, name = FIELD_LINK) {
                id = FIELD_LINK
                required = true
                attributes[ATTR_MIN] = "1"
                attributes["autocomplete"] = "off"
                attributes["spellcheck"] = "false"
            }
            formSubmit("Continue")
        }
    } else {
        p { +"You were invited to a circle on this server. Choose a passphrase to protect your keys on this device." }
        form {
            id = FORM_JOIN
            passphraseField("new-password", hint = true)
            formSubmit("Join")
        }
    }
    backToWelcome()
}

private fun FlowContent.signIn() {
    h1 { +Screen.SignIn.title }
    form {
        id = FORM_UNLOCK
        passphraseField("current-password", hint = false)
        formSubmit("Unlock")
    }
    details {
        summary { +"Forget this device" }
        p { +"This removes your keys and the local copy of the circle from this browser. You can join again with a new invite." }
        button(type = ButtonType.button) {
            attributes[ATTR_ACTION] = ACTION_ERASE
            +"Forget this device"
        }
    }
}

private fun FlowContent.home(home: HomeData) {
    h1 { +Screen.Home.title }
    if (!home.canRead) {
        p {
            role = "status"
            +"You are in the circle. A steward needs to let you in before you can see anything."
        }
    }
    ul {
        li {
            a(href = Screen.Members.fragment) { +(if (home.members == 1) "1 member" else "${home.members} members") }
        }
        if (home.canRead) {
            li {
                a(href = Screen.Items.fragment) { +(if (home.items.size == 1) "1 item shared" else "${home.items.size} items shared") }
            }
        }
        val toAnswer = home.loans.count { it.role == LoanRole.OWNER && it.status == LoanStatus.REQUESTED }
        if (toAnswer > 0) {
            li {
                a(href = Screen.Borrow.fragment) { +(if (toAnswer == 1) "1 request waiting for your answer" else "$toAnswer requests waiting for your answer") }
            }
        }
        home.pauses.firstOrNull { it.role == PauseRole.SUBJECT }?.let {
            li { a(href = Screen.Pauses.fragment) { +(if (it.status == PauseStatus.LIVE) "Your access is paused" else "A pause has been proposed for you") } }
        }
        val toReview = home.pauses.count { it.canReview }
        if (toReview > 0) {
            li { a(href = Screen.Pauses.fragment) { +(if (toReview == 1) "1 pause proposal waiting for your answer" else "$toReview pause proposals waiting for your answer") } }
        }
        if (home.isSteward && home.waiting.isNotEmpty()) {
            li {
                a(href = Screen.Members.fragment) { +(if (home.waiting.size == 1) "1 person waiting to be let in" else "${home.waiting.size} people waiting to be let in") }
            }
        }
        if (home.canRead && !home.hasRecoveryCode) {
            li {
                a(href = Screen.Settings.fragment) { +"Make a recovery code, in case you lose every device" }
            }
        }
    }
    if (home.refusedChanges > 0) {
        p { +"${home.refusedChanges} of your changes could not be sent: the server refused them." }
    }
}

/** Who is in the circle, how to bring someone in, and (for stewards) who is waiting at the door. */
private fun FlowContent.members(home: HomeData, state: AppState) {
    h1 { +Screen.Members.title }
    ul {
        for (person in home.people) {
            li {
                h2 { +(if (person.isMe) "You" else "Member ${person.member.v.take(MEMBER_SHORT_CHARS)}") }
                p {
                    +buildList {
                        if (person.isSteward) add("Steward")
                        add(if (person.devices == 1) "1 device" else "${person.devices} devices")
                    }.joinToString(", ")
                }
                p { +"Key fingerprint: ${person.fingerprint}" }
                if (!person.isMe) {
                    val vouchedUntil = person.vouchedByMeUntil
                    if (vouchedUntil != null) {
                        p { +"You vouch for them until ${CalendarDay.format(vouchedUntil)}." }
                    } else if (home.canVouch) {
                        button(type = ButtonType.button) {
                            attributes[ATTR_ACTION] = ACTION_VOUCH
                            attributes[ATTR_MEMBER] = person.member.v
                            +"Vouch for this person"
                        }
                    }
                }
                if (person.canRemove) {
                    details {
                        summary { +"Remove from the circle" }
                        p { +"This cuts off all their devices at once and changes the circle's key, so they cannot read anything new. What they already read stays with them. They can be invited again." }
                        button(type = ButtonType.button) {
                            attributes[ATTR_ACTION] = ACTION_REMOVE_MEMBER
                            attributes[ATTR_MEMBER] = person.member.v
                            +"Remove this person"
                        }
                    }
                }
            }
        }
    }
    p { +"To check someone really is who they say, read the fingerprints out to each other in person." }
    p { +"Vouching tells the circle you know this person. Two neighbours who vouch open neighbour access for a newcomer. Only vouch for people you know." }
    if (home.needsRotation) {
        section {
            h2 { +"Change the key" }
            p {
                role = "status"
                +"Someone left the circle. Change the circle's key now, so they cannot read anything new."
            }
            button(type = ButtonType.button) {
                attributes[ATTR_ACTION] = ACTION_ROTATE_KEY
                +"Change the key"
            }
        }
    }
    if (home.isSteward && home.waiting.isNotEmpty()) {
        section {
            h2 { +"Waiting to be let in" }
            ul {
                for (waiting in home.waiting) {
                    li {
                        +"Member ${waiting.member.v.take(MEMBER_SHORT_CHARS)} "
                        button(type = ButtonType.button) {
                            attributes[ATTR_ACTION] = ACTION_ADMIT
                            attributes[ATTR_DEVICE] = waiting.device.v
                            +"Let in"
                        }
                    }
                }
            }
        }
    }
    section {
        h2 { +"Invite someone" }
        p { +"An invite link works once and for $INVITE_VALID_DAYS days. Send it only to the person you are inviting." }
        button(type = ButtonType.button) {
            attributes[ATTR_ACTION] = ACTION_INVITE
            +"Make an invite link"
        }
        state.newInvite?.let { link ->
            label {
                attributes["for"] = ID_INVITE_OUTPUT
                +"Invite link (shown once): "
            }
            input(type = InputType.text) {
                id = ID_INVITE_OUTPUT
                value = link
                readonly = true
            }
            button(type = ButtonType.button) {
                attributes[ATTR_ACTION] = ACTION_COPY
                +"Copy"
            }
        }
    }
}

private fun FlowContent.comingSoon(screen: Screen) {
    h1 { +screen.title }
    p { +"This screen is not built yet." }
}

private fun itemClassLabel(itemClass: ItemClass): String = when (itemClass) {
    ItemClass.ESSENTIAL -> "Essential: always open to every member"
    ItemClass.EVERYDAY -> "Everyday: simple things like a crockpot"
    ItemClass.VALUABLE -> "Valuable: power tools, camping gear"
    ItemClass.HIGH_VALUE -> "High value: a trailer, a chainsaw"
}

private fun FlowContent.items(home: HomeData) {
    h1 { +Screen.Items.title }
    if (!home.canRead) {
        p {
            role = "status"
            +"A steward needs to let you in before you can see or add items."
        }
        return
    }
    p {
        a(href = Screen.AddItem.fragment) { +"Add an item" }
    }
    if (home.items.isEmpty()) {
        p { +"Nothing has been shared yet." }
        return
    }
    ul {
        for (item in home.items) {
            li {
                h2 { +item.name }
                p { +itemClassLabel(item.itemClass).substringBefore(":") }
                p { +(if (item.ownedByMe) "Yours" else "Shared by member ${item.owner.v.take(MEMBER_SHORT_CHARS)}") }
                item.description?.takeIf { it.isNotBlank() }?.let { p { +"Details: $it" } }
                if (!item.ownedByMe) {
                    val access = item.access
                    if (access == null || access.canAsk) {
                        button(type = ButtonType.button) {
                            attributes[ATTR_ACTION] = ACTION_BORROW_ITEM
                            attributes[ATTR_ITEM] = item.item.v
                            +"Ask to borrow"
                        }
                    } else {
                        p { +"Not yet. This is not open to you at the moment." }
                        details {
                            summary { +"Why, and how to unlock" }
                            ul { for (reason in access.reasons) li { +reason } }
                            if (access.howToUnlock.isNotEmpty()) {
                                h3 { +"How to unlock" }
                                ul { for (step in access.howToUnlock) li { +step } }
                            }
                        }
                    }
                }
                if (item.ownedByMe) {
                    button(type = ButtonType.button) {
                        attributes[ATTR_ACTION] = ACTION_EDIT_ITEM
                        attributes[ATTR_ITEM] = item.item.v
                        +"Change"
                    }
                    details {
                        summary { +"Remove" }
                        p { +"This takes the item off everyone's list. It cannot be put back, but you can add it again." }
                        button(type = ButtonType.button) {
                            attributes[ATTR_ACTION] = ACTION_RETIRE_ITEM
                            attributes[ATTR_ITEM] = item.item.v
                            +"Remove from the list"
                        }
                    }
                }
            }
        }
    }
}

/** Adding an item ([existing] null) or changing one: the same fields, on a page of their own. */
private fun FlowContent.itemForm(existing: ItemSummary?) {
    h1 { +(if (existing == null) Screen.AddItem.title else Screen.EditItem.title) }
    form {
        id = if (existing == null) FORM_ITEM_ADD else FORM_ITEM_EDIT
        label {
            attributes["for"] = FIELD_NAME
            +"Name: "
        }
        input(type = InputType.text, name = FIELD_NAME) {
            id = FIELD_NAME
            required = true
            value = existing?.name.orEmpty()
            attributes["maxlength"] = MAX_ITEM_NAME_CHARS.toString()
            attributes[ATTR_MIN] = "1"
            attributes["autocomplete"] = "off"
        }
        label {
            attributes["for"] = FIELD_KIND
            +"Kind: "
        }
        select {
            id = FIELD_KIND
            name = FIELD_KIND
            for (kind in ItemClass.entries) {
                option {
                    value = kind.name
                    selected = kind == (existing?.itemClass ?: ItemClass.EVERYDAY)
                    +itemClassLabel(kind)
                }
            }
        }
        label {
            attributes["for"] = FIELD_MIN_TIER
            +"Who may ask: "
        }
        select {
            id = FIELD_MIN_TIER
            name = FIELD_MIN_TIER
            option {
                value = ""
                selected = existing?.ownerMinTier == null
                +"Whoever the circle's rules allow for this kind"
            }
            for (tier in listOf(Tier.NEIGHBOUR, Tier.TRUSTED)) option {
                value = tier.name
                selected = existing?.ownerMinTier == tier
                +"${tierLabel(tier)} and above"
            }
        }
        label {
            attributes["for"] = FIELD_DETAILS
            +"Details: "
        }
        textArea {
            id = FIELD_DETAILS
            name = FIELD_DETAILS
            attributes["maxlength"] = MAX_ITEM_DESCRIPTION_CHARS.toString()
            attributes["aria-describedby"] = "details-help"
            +existing?.description.orEmpty()
        }
        p {
            id = "details-help"
            +"Only you and people you approve to borrow it can read the details, for example where to collect it."
        }
        formSubmit(if (existing == null) "Add item" else "Save changes", ready = existing != null)
    }
    p {
        a(href = Screen.Items.fragment) { +"Back to items" }
    }
}

private fun outcomeLabel(outcome: ReturnOutcome): String = when (outcome) {
    ReturnOutcome.OK -> "Returned, all good"
    ReturnOutcome.LATE -> "Returned late"
    ReturnOutcome.DAMAGED_REPORTED -> "Returned, damage reported (no penalty for telling)"
    ReturnOutcome.LOST_REPORTED -> "Lost, reported (no penalty for telling)"
}

private fun window(loan: LoanSummary): String = "${CalendarDay.format(loan.fromDay)} to ${CalendarDay.format(loan.toDay)}"

private fun who(loan: LoanSummary): String = "member ${loan.other.v.take(MEMBER_SHORT_CHARS)}"

private fun FlowContent.loanHeading(loan: LoanSummary) {
    h3 { +loan.itemName }
    p { +"${if (loan.role == LoanRole.OWNER) "Asked by" else "Owned by"} ${who(loan)}, ${window(loan)}" }
    loan.note?.let { p { +"Note: $it" } }
}

private fun FlowContent.loanButton(action: String, loan: LoanSummary, text: String) {
    button(type = ButtonType.button) {
        attributes[ATTR_ACTION] = action
        attributes[ATTR_LOAN] = loan.loan.v
        +text
    }
}

/** Everything about loans, grouped by what needs doing next. */
private fun FlowContent.borrow(home: HomeData) {
    h1 { +Screen.Borrow.title }
    if (!home.canRead) {
        p {
            role = "status"
            +"A steward needs to let you in before you can borrow or lend."
        }
        return
    }
    if (home.loans.isEmpty()) {
        p { +"No loans yet. " }
        p { a(href = Screen.Items.fragment) { +"Find something on the Items page." } }
        return
    }
    val mine = home.loans.filter { it.role != LoanRole.STEWARD }
    val toAnswer = mine.filter { it.role == LoanRole.OWNER && it.status == LoanStatus.REQUESTED }
    val waiting = mine.filter { it.role == LoanRole.BORROWER && it.status == LoanStatus.REQUESTED }
    val handovers = mine.filter { it.status == LoanStatus.APPROVED }
    val out = mine.filter { it.status == LoanStatus.ACTIVE }
    val finished = mine.filter { it.status == LoanStatus.RETURNED || it.status == LoanStatus.DECLINED || it.status == LoanStatus.CANCELLED }
    val toResolve = home.loans.filter { it.canResolve }
    if (toResolve.isNotEmpty()) {
        section {
            h2 { +"Disputes to resolve" }
            for (loan in toResolve) {
                div {
                    h3 { +loan.itemName }
                    p { +"The owner says the borrower did not report ${if (loan.dispute?.claim == DisputeClaim.LOSS) "a loss" else "damage"}. The borrower reported: ${loan.reported?.let { outcomeLabel(it) } ?: "nothing"}." }
                    loan.dispute?.note?.let { p { +"Owner's note: $it" } }
                    loanButton(ACTION_UPHOLD, loan, "Uphold: the borrower did not report it")
                    loanButton(ACTION_DISMISS_DISPUTE, loan, "Dismiss: the return was as reported")
                }
            }
        }
    }
    if (toAnswer.isNotEmpty()) {
        section {
            h2 { +"Requests to answer" }
            for (loan in toAnswer) {
                div {
                    loanHeading(loan)
                    loanButton(ACTION_APPROVE_LOAN, loan, "Approve and share the details")
                    loanButton(ACTION_DECLINE_LOAN, loan, "Decline")
                }
            }
        }
    }
    if (handovers.isNotEmpty()) {
        section {
            h2 { +"Ready to hand over" }
            for (loan in handovers) {
                div {
                    loanHeading(loan)
                    loan.description?.takeIf { loan.role == LoanRole.BORROWER && it.isNotBlank() }?.let { p { +"Details: $it" } }
                    if (loan.role == LoanRole.BORROWER && !loan.iConfirmedHandover) loanButton(ACTION_CANCEL_LOAN, loan, "Cancel this loan")
                    if (loan.iConfirmedHandover) {
                        p { +"You have confirmed. Waiting for ${who(loan)} to confirm too." }
                    } else {
                        if (loan.otherConfirmedHandover) p { +"${who(loan).replaceFirstChar { it.uppercase() }} has confirmed." }
                        loanButton(ACTION_HANDOVER, loan, "We handed it over")
                    }
                }
            }
        }
    }
    if (out.isNotEmpty()) {
        section {
            h2 { +"Out now" }
            for (loan in out) {
                div {
                    loanHeading(loan)
                    if (loan.role == LoanRole.BORROWER) {
                        loan.description?.takeIf { it.isNotBlank() }?.let { p { +"Details: $it" } }
                        form {
                            id = FORM_RETURN_PREFIX + loan.loan.v
                            attributes[ATTR_LOAN] = loan.loan.v
                            label {
                                attributes["for"] = "$FIELD_OUTCOME-${loan.loan.v}"
                                +"How did it go: "
                            }
                            select {
                                id = "$FIELD_OUTCOME-${loan.loan.v}"
                                name = FIELD_OUTCOME
                                for (outcome in ReturnOutcome.entries) {
                                    option {
                                        value = outcome.name
                                        +outcomeLabel(outcome)
                                    }
                                }
                            }
                            formSubmit("Report it back", ready = true)
                        }
                    } else {
                        p { +"With ${who(loan)}. They will report it back." }
                    }
                }
            }
        }
    }
    if (waiting.isNotEmpty()) {
        section {
            h2 { +"Waiting for an answer" }
            for (loan in waiting) div {
                loanHeading(loan)
                loanButton(ACTION_CANCEL_LOAN, loan, "Cancel my request")
            }
        }
    }
    if (finished.isNotEmpty()) {
        section {
            h2 { +"Finished" }
            for (loan in finished) {
                div {
                    loanHeading(loan)
                    p {
                        +(if (loan.status == LoanStatus.DECLINED) "Declined." else if (loan.status == LoanStatus.CANCELLED) "Cancelled." else loan.outcome?.let { outcomeLabel(it) + "." } ?: "Returned.")
                    }
                    loan.dispute?.let { dispute ->
                        val claim = if (dispute.claim == DisputeClaim.LOSS) "loss" else "damage"
                        p {
                            +when (dispute.status) {
                                DisputeStatus.PENDING -> "Pending review: the owner reported $claim. A steward who is not part of this loan will decide. Credit for this loan waits."
                                DisputeStatus.UPHELD -> "The owner's report of $claim was upheld."
                                DisputeStatus.DISMISSED -> "The owner's report of $claim was dismissed, so the return counts as reported."
                            }
                        }
                        dispute.note?.let { p { +"Owner's note: $it" } }
                        if (dispute.status == DisputeStatus.PENDING && home.isSteward && home.stewardCount == 1) {
                            p { +"You are the only steward, so nobody else can decide this. It closes after $DAMAGE_CLAIM_DAYS days as if the return had been fine." }
                        }
                    }
                    if (loan.canConfirm) {
                        loanButton(ACTION_CONFIRM_RETURN, loan, "It came back fine")
                    }
                    if (loan.canDispute) {
                        details {
                            summary { +"Report a problem" }
                            p { +"Use this only if the borrower did not tell you about damage or loss. A steward who is not part of this loan will decide." }
                            form {
                                id = FORM_DISPUTE_PREFIX + loan.loan.v
                                attributes[ATTR_LOAN] = loan.loan.v
                                label {
                                    attributes["for"] = "$FIELD_CLAIM-${loan.loan.v}"
                                    +"What went wrong: "
                                }
                                select {
                                    id = "$FIELD_CLAIM-${loan.loan.v}"
                                    name = FIELD_CLAIM
                                    option {
                                        value = DisputeClaim.DAMAGE.name
                                        +"It came back damaged"
                                    }
                                    option {
                                        value = DisputeClaim.LOSS.name
                                        +"It did not come back"
                                    }
                                }
                                label {
                                    attributes["for"] = "$FIELD_NOTE-${loan.loan.v}"
                                    +"Note: "
                                }
                                input(type = InputType.text, name = FIELD_NOTE) {
                                    id = "$FIELD_NOTE-${loan.loan.v}"
                                    attributes["maxlength"] = MAX_LOAN_NOTE_CHARS.toString()
                                    attributes["autocomplete"] = "off"
                                }
                                formSubmit("Send report", ready = true)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The page for asking to borrow one item. */
private fun FlowContent.requestForm(item: ItemSummary?, home: HomeData) {
    h1 { +Screen.RequestLoan.title }
    if (item == null) {
        p { +"That item is no longer on the list." }
        p { a(href = Screen.Items.fragment) { +"Back to items" } }
        return
    }
    p { +"You are asking to borrow: ${item.name}" }
    form {
        id = FORM_LOAN_REQUEST
        for ((field, text) in listOf(FIELD_FROM to "From: ", FIELD_TO to "To: ")) {
            label {
                attributes["for"] = field
                +text
            }
            input(type = InputType.date, name = field) {
                id = field
                required = true
                attributes["min"] = CalendarDay.format(home.today)
                attributes[ATTR_MIN] = ISO_DATE_CHARS.toString()
            }
        }
        label {
            attributes["for"] = FIELD_NOTE
            +"Note for the owner: "
        }
        input(type = InputType.text, name = FIELD_NOTE) {
            id = FIELD_NOTE
            attributes["maxlength"] = MAX_LOAN_NOTE_CHARS.toString()
            attributes["autocomplete"] = "off"
        }
        formSubmit("Send request")
    }
    p { a(href = Screen.Items.fragment) { +"Back to items" } }
}

private fun lockChoiceLabel(minutes: Int): String = when (minutes) {
    0 -> "Only when I close the tab"
    1 -> "After 1 minute without use"
    else -> "After $minutes minutes without use"
}

private fun FlowContent.passwordInput(field: String, label: String, autocomplete: String, min: Int) {
    label {
        attributes["for"] = field
        +label
    }
    input(type = InputType.password, name = field) {
        id = field
        required = true
        attributes[ATTR_MIN] = min.toString()
        attributes["autocomplete"] = autocomplete
    }
    showHideButton(field)
}

/** This browser's own settings: passphrase, locking, and forgetting the device. */
private fun FlowContent.settings(home: HomeData, state: AppState) {
    h1 { +Screen.Settings.title }
    devicesSection(home, state)
    section {
        h2 { +"Passphrase" }
        p { +"Your passphrase protects your keys on this device. Changing it does not change your identity in the circle." }
        form {
            id = FORM_CHANGE_PASSPHRASE
            passwordInput(FIELD_CURRENT, "Current passphrase: ", "current-password", min = 1)
            passwordInput(FIELD_NEW, "New passphrase: ", "new-password", min = MIN_PASSPHRASE_CHARS)
            formSubmit("Change passphrase")
        }
    }
    section {
        h2 { +"Locking" }
        form {
            id = FORM_AUTOLOCK
            label {
                attributes["for"] = FIELD_MINUTES
                +"Lock this device: "
            }
            select {
                id = FIELD_MINUTES
                name = FIELD_MINUTES
                for (minutes in AUTO_LOCK_CHOICES_MINUTES) {
                    option {
                        value = minutes.toString()
                        selected = minutes == home.autoLockMinutes
                        +lockChoiceLabel(minutes)
                    }
                }
            }
            formSubmit("Save", ready = true)
        }
        p { +"Locking drops your keys from memory; you type your passphrase to continue." }
    }
    section {
        h2 { +"Export the circle" }
        p { +"Download a copy of the circle's log as it is on this device, so a new host can be started from it if the old one is lost. It holds only scrambled text and public information: nobody can read the circle's words from it without a member's keys. Keep it private all the same." }
        button(type = ButtonType.button) {
            attributes[ATTR_ACTION] = ACTION_EXPORT
            +"Download an export"
        }
    }
    section {
        h2 { +"Leave the circle" }
        if (home.isSteward) {
            p { +"You are a steward. Step down on the Stewards page first, then you can leave." }
        } else {
            details {
                summary { +"Leave the circle" }
                p { +"This cuts off all your devices and forgets the circle on this one. A steward will change the circle's key. You can be invited again." }
                button(type = ButtonType.button) {
                    attributes[ATTR_ACTION] = ACTION_LEAVE
                    +"Leave the circle"
                }
            }
        }
    }
    section {
        h2 { +"Recovery code" }
        p {
            +(if (home.hasRecoveryCode) "A recovery code is set up. If you lose every device, the 12 words on your paper bring everything back."
            else "No recovery code yet. Without one, losing every device means starting again as a new member.")
        }
        button(type = ButtonType.button) {
            attributes[ATTR_ACTION] = ACTION_RECOVERY_START
            +(if (home.hasRecoveryCode) "Make a new recovery code" else "Make a recovery code")
        }
        if (home.hasRecoveryCode) p { +"A new code replaces the old one, which stops working." }
    }
    section {
        h2 { +"Forget this device" }
        details {
            summary { +"Forget this device" }
            p { +"This removes your keys and the local copy of the circle from this browser. Others in the circle keep what they have. You can join again with a new invite." }
            button(type = ButtonType.button) {
                attributes[ATTR_ACTION] = ACTION_ERASE
                +"Forget this device"
            }
        }
    }
}

private fun tierLabel(tier: Tier): String = when (tier) {
    Tier.NEWCOMER -> "Newcomer"
    Tier.NEIGHBOUR -> "Neighbour"
    Tier.TRUSTED -> "Trusted"
    Tier.STEWARD -> "Steward level"
}

/** Only your own standing, in plain words with the reasons; nobody else's tier or points are ever shown (spec 6). */
private fun FlowContent.standing(standing: StandingSummary?, home: HomeData? = null) {
    h1 { +Screen.Standing.title }
    if (standing == null) {
        p { +"Your standing appears once the circle has been read." }
        return
    }
    p { +"Your tier: ${tierLabel(standing.tier)}" }
    p { +"Standing points: ${standing.points}" }
    p { +"Standing is local to this circle. It rises as you lend and borrow well, and fades slowly if you are quiet. It never drops below newcomer, and nobody is ever banned for low standing." }
    section {
        h2 { +"Why" }
        ul { for (reason in standing.reasons) li { +reason } }
    }
    section {
        h2 { +"How to unlock more" }
        ul { for (step in standing.howToUnlock) li { +step } }
    }
    if (home != null) contributions(home)
    if (home != null && (home.canProposePause || home.pauses.isNotEmpty())) {
        p { a(href = Screen.Pauses.fragment) { +"Pauses" } }
    }
}

private fun FlowContent.agreeButton(proposal: ProposalSummary) {
    val (action, subject) = when (proposal.change) {
        StewardChange.ADD -> ACTION_STEWARD_ADD to proposal.member?.v
        StewardChange.REMOVE -> ACTION_STEWARD_REMOVE to proposal.member?.v
        StewardChange.POLICY -> ACTION_STEWARD_SETTINGS to proposal.settings?.let { "${it.quorum}:${it.inactiveDays}:${it.fallbackDelayDays}:${it.recoveryThreshold}" }
    }
    button(type = ButtonType.button) {
        attributes[ATTR_ACTION] = action
        attributes[ATTR_SUBJECT] = subject.orEmpty()
        +"Agree"
    }
}

private fun proposalText(proposal: ProposalSummary): String = when (proposal.change) {
    StewardChange.ADD -> "Make member ${proposal.member?.v?.take(MEMBER_SHORT_CHARS)} a steward"
    StewardChange.REMOVE -> "Remove member ${proposal.member?.v?.take(MEMBER_SHORT_CHARS)} as a steward"
    StewardChange.POLICY -> proposal.settings?.let {
        "Change the rules: ${it.quorum} to agree, ${it.inactiveDays} quiet days, ${it.fallbackDelayDays} days to wait, " +
            (if (it.recoveryThreshold == 0) "no recovery shares" else "${it.recoveryThreshold} stewards to rebuild the key")
    } ?: "Change the rules"
}

/** Who looks after the circle, what is waiting for their agreement, and the rules they follow (ADR 0027). */
private fun FlowContent.stewards(home: HomeData) {
    h1 { +Screen.Stewards.title }
    p { +"Stewards let people in, decide disputes, and decide who the stewards are." }
    if (home.stewardCount == 1) {
        p {
            role = "status"
            +"This circle has one steward. If they are away, nobody can let people in or decide disputes that involve them. Consider adding another."
        }
    }
    section {
        h2 { +"Current stewards" }
        ul {
            for (steward in home.stewardList) {
                li {
                    h3 { +(if (steward.isMe) "You" else "Member ${steward.member.v.take(MEMBER_SHORT_CHARS)}") }
                    p {
                        +(steward.sinceDay?.let { "Steward since ${CalendarDay.format(it)}" } ?: "Founding steward")
                        steward.votesFromDay?.let { +". Can vote from ${CalendarDay.format(it)}" }
                    }
                    if (home.isSteward && home.stewardCount > 1) {
                        details {
                            summary { +(if (steward.isMe) "Step down" else "Remove") }
                            p { +(if (steward.isMe) "You stop being a steward at once; others remain." else "Other stewards must agree, as the circle's rules say.") }
                            button(type = ButtonType.button) {
                                attributes[ATTR_ACTION] = ACTION_STEWARD_REMOVE
                                attributes[ATTR_SUBJECT] = steward.member.v
                                +(if (steward.isMe) "Step down as steward" else "Propose removing")
                            }
                        }
                    }
                }
            }
        }
    }
    if (home.proposals.isNotEmpty()) {
        section {
            h2 { +"Waiting for agreement" }
            ul {
                for (proposal in home.proposals) {
                    li {
                        p { +proposalText(proposal) }
                        p { +"${proposal.agreeing} of ${proposal.needed} stewards agree." }
                        if (proposal.iAgree) p { +"You agree." }
                        if (proposal.canAgree) agreeButton(proposal)
                    }
                }
            }
        }
    }
    if (home.isSteward) {
        val candidates = home.people.filter { person -> !person.isMe && home.stewardList.none { it.member == person.member } }
        if (candidates.isNotEmpty()) {
            section {
                h2 { +"Propose a new steward" }
                ul {
                    for (person in candidates) {
                        li {
                            +"Member ${person.member.v.take(MEMBER_SHORT_CHARS)} "
                            button(type = ButtonType.button) {
                                attributes[ATTR_ACTION] = ACTION_STEWARD_ADD
                                attributes[ATTR_SUBJECT] = person.member.v
                                +"Propose as steward"
                            }
                        }
                    }
                }
            }
        }
    }
    if (home.isSteward && home.stewardCount >= MIN_RECOVERY_THRESHOLD) {
        section {
            h2 { +"Recovery shares" }
            p { a(href = Screen.RecoveryShares.fragment) { +"Share the circle key between stewards, in case every device is lost" } }
        }
    }
    section {
        h2 { +"Circle rules" }
        val rules = home.rules
        val now = rules.current
        ul {
            li { +"Points for Neighbour, Trusted and Steward level: ${tenthsText(now.neighbourTenths)}, ${tenthsText(now.trustedTenths)} and ${tenthsText(now.stewardTenths)}" }
            li { +"Points fade by half every ${now.halfLifeDays} days" }
            li { +"Vouches needed to become a Neighbour: ${now.vouchesForNeighbour}, each lasting up to ${now.vouchValidityDays} days" }
            li { +"Reviewers needed for a pause: ${now.reviewersForPause}; a pause lasts ${now.defaultPauseDays} days unless chosen otherwise" }
            li { +"Days higher-value items are on hold after an unreported incident: ${now.incidentCooldownDays}" }
        }
        p { +"A change applies from the day enough stewards agree. Things decided before then keep the rules they were decided under." }
        for (proposal in rules.proposals) {
            val s = proposal.settings
            p {
                +"Proposed: thresholds ${tenthsText(s.neighbourTenths)} / ${tenthsText(s.trustedTenths)} / ${tenthsText(s.stewardTenths)}, half-life ${s.halfLifeDays}, vouches ${s.vouchesForNeighbour} for ${s.vouchValidityDays} days, pause reviewers ${s.reviewersForPause} for ${s.defaultPauseDays} days, cooldown ${s.incidentCooldownDays}. ${proposal.agreeing} of ${proposal.needed} stewards agree."
            }
            if (proposal.canAgree) {
                button(type = ButtonType.button) {
                    attributes[ATTR_ACTION] = ACTION_RULES_AGREE
                    attributes[ATTR_RULES] = listOf(s.neighbourTenths, s.trustedTenths, s.stewardTenths, s.halfLifeDays, s.vouchesForNeighbour, s.vouchValidityDays, s.reviewersForPause, s.defaultPauseDays, s.incidentCooldownDays).joinToString(":")
                    +"Agree to these rules"
                }
            }
        }
        if (home.isSteward) {
            details {
                summary { +"Propose different rules" }
                p { +"Fill in every number. Other stewards must send the same numbers for it to take effect." }
                form {
                    id = FORM_RULES
                    for ((field, label, step, value) in listOf(
                        Quad("rules-neighbour", "Points for Neighbour: ", "0.5", tenthsText(now.neighbourTenths)),
                        Quad("rules-trusted", "Points for Trusted: ", "0.5", tenthsText(now.trustedTenths)),
                        Quad("rules-steward", "Points for Steward level: ", "0.5", tenthsText(now.stewardTenths)),
                        Quad("rules-halflife", "Days for points to fade by half: ", "1", now.halfLifeDays.toString()),
                        Quad("rules-vouches", "Vouches needed for Neighbour: ", "1", now.vouchesForNeighbour.toString()),
                        Quad("rules-vouch-days", "Days a vouch lasts: ", "1", now.vouchValidityDays.toString()),
                        Quad("rules-reviewers", "Reviewers needed for a pause: ", "1", now.reviewersForPause.toString()),
                        Quad("rules-pause-days", "Days a pause lasts: ", "1", now.defaultPauseDays.toString()),
                        Quad("rules-cooldown", "Days on hold after an incident: ", "1", now.incidentCooldownDays.toString()),
                    )) {
                        label {
                            attributes["for"] = field
                            +label
                        }
                        input(type = InputType.number, name = field) {
                            id = field
                            required = true
                            attributes["step"] = step
                            attributes["min"] = "0"
                            attributes[ATTR_MIN] = "1"
                            this.value = value
                        }
                    }
                    formSubmit("Propose these rules", ready = true)
                }
            }
        }
    }
    section {
        h2 { +"Rules for stewards" }
        ul {
            li { +"Stewards who must agree: ${home.stewardSettings.quorum}" }
            li { +"Days a steward must be quiet before the others may act without them: ${home.stewardSettings.inactiveDays}" }
            li { +"Days the others must wait first: ${home.stewardSettings.fallbackDelayDays}" }
            li {
                +(
                    if (home.stewardSettings.recoveryThreshold == 0) "Recovery shares of the circle key: none"
                    else "Recovery shares of the circle key: any ${home.stewardSettings.recoveryThreshold} stewards can rebuild it"
                    )
            }
        }
        if (home.isSteward) {
            p { +"To change the rules, fill in all four and propose. Other stewards must agree." }
            form {
                id = FORM_STEWARD_RULES
                for ((field, label, range) in listOf(
                    Triple(FIELD_QUORUM, "Stewards who must agree: ", MIN_STEWARD_QUORUM..MAX_STEWARD_QUORUM),
                    Triple(FIELD_INACTIVE, "Quiet days: ", MIN_STEWARD_INACTIVE_DAYS..MAX_STEWARD_INACTIVE_DAYS),
                    Triple(FIELD_FALLBACK, "Days to wait: ", MIN_STEWARD_FALLBACK_DELAY_DAYS..MAX_STEWARD_FALLBACK_DELAY_DAYS),
                    Triple(FIELD_RECOVERY, "Stewards to rebuild the key (0 for none): ", 0..MAX_STEWARD_RECOVERY_THRESHOLD),
                )) {
                    label {
                        attributes["for"] = field
                        +label
                    }
                    input(type = InputType.number, name = field) {
                        id = field
                        required = true
                        attributes["min"] = range.first.toString()
                        attributes["max"] = range.last.toString()
                        attributes[ATTR_MIN] = "1"
                        attributes["placeholder"] = "${range.first} to ${range.last}"
                    }
                }
                formSubmit("Propose these rules")
            }
        }
    }
}

/** The page a link to add another device opens in a browser that has no identity yet. */
private fun FlowContent.pairDevice(state: AppState) {
    h1 { +Screen.PairDevice.title }
    if (state.pendingDevice == null) {
        p { +"To add this browser to your account, open the link shown on your other device: Settings, then Add another device." }
    } else {
        p { +"This browser will become another device of your account. Choose a passphrase to protect its keys here. Your other device finishes the job." }
        form {
            id = FORM_PAIR
            passphraseField("new-password", hint = true)
            formSubmit("Continue")
        }
    }
    backToWelcome()
}

/** A new device of an existing member, waiting for that member to add it. */
private fun FlowContent.awaiting(model: AppModel.AwaitingApproval) {
    h1 { +Screen.PairDevice.title }
    p { +"On your other device, open Settings and Add another device, then paste this code there." }
    label {
        attributes["for"] = ID_THIS_DEVICE_CODE
        +"Device code: "
    }
    input(type = InputType.text) {
        id = ID_THIS_DEVICE_CODE
        value = model.code
        readonly = true
    }
    button(type = ButtonType.button) {
        attributes[ATTR_ACTION] = ACTION_COPY_FIELD
        attributes[ATTR_FROM] = ID_THIS_DEVICE_CODE
        +"Copy"
    }
    p { +"Fingerprint: ${model.fingerprint}" }
    p { +"Your other device will show a fingerprint too. Check that the two match before you add this device there." }
    p {
        role = "status"
        +"Waiting for your other device. This page checks every few seconds."
    }
    button(type = ButtonType.button) {
        attributes[ATTR_ACTION] = ACTION_CHECK_NOW
        +"Check now"
    }
    details {
        summary { +"Forget this device" }
        p { +"This removes the keys made here. Start again from the link on your other device." }
        button(type = ButtonType.button) {
            attributes[ATTR_ACTION] = ACTION_ERASE
            +"Forget this device"
        }
    }
}

private fun FlowContent.devicesSection(home: HomeData, state: AppState) {
    section {
        h2 { +"Your devices" }
        ul {
            for (device in home.myDevices) {
                li {
                    +(device.name ?: "Unnamed device")
                    if (device.isThisOne) +" (this device)"
                    if (!device.isThisOne) {
                        details {
                            summary { +"Remove this device" }
                            p { +"It is cut off at once and the circle's key is changed, so it cannot read anything new. What it already read stays on it." }
                            button(type = ButtonType.button) {
                                attributes[ATTR_ACTION] = ACTION_REMOVE_DEVICE
                                attributes[ATTR_DEVICE_ID] = device.device.v
                                +"Remove it"
                            }
                        }
                    }
                }
            }
        }
    }
    section {
        h2 { +"Add another device" }
        p { +"Open this link in the other browser. It holds no secrets." }
        val link = DeviceLink(home.circle, home.me).format(state.origin)
        label {
            attributes["for"] = ID_DEVICE_LINK
            +"Link: "
        }
        input(type = InputType.text) {
            id = ID_DEVICE_LINK
            value = link
            readonly = true
        }
        button(type = ButtonType.button) {
            attributes[ATTR_ACTION] = ACTION_COPY_FIELD
            attributes[ATTR_FROM] = ID_DEVICE_LINK
            +"Copy"
        }
        p { +"Then paste the code the other browser shows, and check that its fingerprint matches the one below." }
        form {
            id = FORM_ADD_DEVICE
            label {
                attributes["for"] = FIELD_DEVICE_CODE
                +"Device code: "
            }
            input(type = InputType.text, name = FIELD_DEVICE_CODE) {
                id = FIELD_DEVICE_CODE
                required = true
                attributes[ATTR_MIN] = "1"
                attributes["autocomplete"] = "off"
                attributes["spellcheck"] = "false"
            }
            p {
                id = ID_DEVICE_PREVIEW
                attributes["aria-live"] = "polite"
            }
            label {
                attributes["for"] = FIELD_DEVICE_NAME
                +"Name of the device: "
            }
            input(type = InputType.text, name = FIELD_DEVICE_NAME) {
                id = FIELD_DEVICE_NAME
                attributes["maxlength"] = MAX_DEVICE_NAME_CHARS.toString()
                attributes["autocomplete"] = "off"
            }
            label {
                attributes["for"] = FIELD_CHECKED
                +"The fingerprint above matches the one on the new device: "
            }
            input(type = InputType.checkBox, name = FIELD_CHECKED) {
                id = FIELD_CHECKED
                required = true
            }
            formSubmit("Add this device")
        }
    }
}

/** Writing down a new recovery code, then proving it was copied (ADR 0047). */
private fun FlowContent.recoveryCodePage(state: AppState) {
    val draft = state.recoveryDraft
    h1 { +Screen.RecoveryCode.title }
    if (draft == null) {
        p { +"There is no recovery code being made. Start from Settings." }
        return
    }
    p { +"Write these 12 words on paper, in this order. Keep the paper somewhere safe, like a house key. Anyone who has them can act as you, and nobody can look them up for you." }
    ol {
        for (word in draft.words) li { +word }
    }
    label {
        attributes["for"] = ID_RESTORE_LINK
        +"Restore link: "
    }
    input(type = InputType.text) {
        id = ID_RESTORE_LINK
        value = draft.restoreLink
        readonly = true
    }
    button(type = ButtonType.button) {
        attributes[ATTR_ACTION] = ACTION_COPY_FIELD
        attributes[ATTR_FROM] = ID_RESTORE_LINK
        +"Copy"
    }
    p { +"Keep the link with the paper. It holds no secrets. To restore, open it in any browser and type the words." }
    h2 { +"Check your copy" }
    p { +"Type these words from your paper. The code is only saved when they match." }
    form {
        id = FORM_RECOVERY_CHECK
        for (place in draft.askFor) {
            label {
                attributes["for"] = "word-$place"
                +"Word $place: "
            }
            input(type = InputType.text, name = "word-$place") {
                id = "word-$place"
                required = true
                attributes[ATTR_MIN] = "1"
                attributes["autocomplete"] = "off"
                attributes["autocapitalize"] = "off"
                attributes["spellcheck"] = "false"
            }
        }
        formSubmit("Check and finish")
    }
    button(type = ButtonType.button) {
        attributes[ATTR_ACTION] = ACTION_RECOVERY_CANCEL
        +"Cancel, do not make a code"
    }
}

/** The page a restore link opens in a browser that has no identity yet. */
private fun FlowContent.restorePage(state: AppState) {
    h1 { +Screen.Restore.title }
    if (state.pendingRestore == null) {
        p { +"To restore from a recovery code, open the restore link that was kept with it." }
    } else {
        p { +"Type the 12 words from your paper and choose a passphrase for this browser. This browser becomes a new device of your account, and the paper stops working." }
        form {
            id = FORM_RESTORE
            label {
                attributes["for"] = FIELD_WORDS
                +"Your 12 words: "
            }
            textArea {
                id = FIELD_WORDS
                name = FIELD_WORDS
                required = true
                attributes[ATTR_MIN] = "1"
                attributes["autocomplete"] = "off"
                attributes["autocapitalize"] = "off"
                attributes["spellcheck"] = "false"
                attributes["rows"] = "3"
            }
            passphraseField("new-password", hint = true)
            formSubmit("Restore")
        }
    }
    backToWelcome()
}

/** Splitting the circle key between stewards, showing this steward's share, and rebuilding the key from shares (ADR 0048). */
private fun FlowContent.recoverySharesPage(home: HomeData, state: AppState) {
    val shares = home.recoveryShares
    h1 { +Screen.RecoveryShares.title }
    if (!home.isSteward) {
        p { +"Only stewards hold recovery shares." }
        a(href = Screen.Stewards.fragment) { +"Back to stewards" }
        return
    }
    p { +"The circle key can be split so that no steward holds it alone, and any number of stewards you choose can rebuild it together. Use this only if every device that held the key is lost." }
    p {
        +(
            if (shares.automaticThreshold == 0) "The circle's rules do not ask for shares, so they are only made when you press the button below. Stewards can propose the rule on the Stewards page."
            else "The circle's rules ask for shares: after the key changes or the stewards change, the first steward's device sends a new set by itself, so any ${shares.automaticThreshold} stewards (or all of them if there are fewer) can rebuild the key."
            )
    }
    section {
        h2 { +"Make the shares" }
        p {
            role = "status"
            +(if (shares.currentSetSent) "Every steward has a share of the current key." else "Not every steward has a share of the current key yet.")
        }
        if (shares.mayMake) {
            p { +"Choose how many stewards must come together to rebuild the key (${shares.minThreshold} to ${shares.maxThreshold}). Each steward is sent one share, for all of their devices." }
            form {
                id = FORM_MAKE_SHARES
                label {
                    attributes["for"] = FIELD_THRESHOLD
                    +"Stewards needed: "
                }
                input(type = InputType.number, name = FIELD_THRESHOLD) {
                    id = FIELD_THRESHOLD
                    required = true
                    attributes["min"] = shares.minThreshold.toString()
                    attributes["max"] = shares.maxThreshold.toString()
                    attributes[ATTR_MIN] = "1"
                }
                formSubmit("Make and send the shares")
            }
        } else {
            p { +"This device cannot make shares now: it needs the current key and at least two stewards." }
        }
    }
    if (shares.heldEpochs.isNotEmpty()) {
        section {
            h2 { +"Your share" }
            p { +"Give your share code to the steward who is rebuilding the key, in person or by a call. Anyone who gathers enough share codes holds the circle key, so never post one." }
            for (epoch in shares.heldEpochs) {
                val code = state.shareCodes[epoch]
                p { +"Key number $epoch" }
                if (code == null) {
                    button(type = ButtonType.button) {
                        attributes[ATTR_ACTION] = ACTION_SHARE_SHOW
                        attributes[ATTR_EPOCH] = epoch.toString()
                        +"Show my share code"
                    }
                } else {
                    val fieldId = "share-code-$epoch"
                    label {
                        attributes["for"] = fieldId
                        +"Share code: "
                    }
                    input(type = InputType.text) {
                        id = fieldId
                        value = code
                        readonly = true
                    }
                    button(type = ButtonType.button) {
                        attributes[ATTR_ACTION] = ACTION_COPY_FIELD
                        attributes[ATTR_FROM] = fieldId
                        +"Copy"
                    }
                    button(type = ButtonType.button) {
                        attributes[ATTR_ACTION] = ACTION_SHARE_HIDE
                        attributes[ATTR_EPOCH] = epoch.toString()
                        +"Hide"
                    }
                }
            }
        }
    }
    if (shares.missingKeyEpochs.isNotEmpty()) {
        section {
            h2 { +"Rebuild the key" }
            p { +"This device does not have the key for ${shares.missingKeyEpochs.joinToString(", ") { "key number $it" }}. Type or paste share codes from enough stewards, one on each line (yours too). Nothing is changed unless they make the right key." }
            form {
                id = FORM_REBUILD_KEY
                label {
                    attributes["for"] = FIELD_SHARE_CODES
                    +"Share codes: "
                }
                textArea {
                    id = FIELD_SHARE_CODES
                    name = FIELD_SHARE_CODES
                    required = true
                    attributes[ATTR_MIN] = "1"
                    attributes["autocomplete"] = "off"
                    attributes["autocapitalize"] = "off"
                    attributes["spellcheck"] = "false"
                    attributes["rows"] = "5"
                }
                formSubmit("Rebuild the key")
            }
        }
    }
    a(href = Screen.Stewards.fragment) { +"Back to stewards" }
}

/** Pauses (ADR 0050): what the subject sees, what reviewers may do, and the form to propose one. */
private fun FlowContent.pausesPage(home: HomeData) {
    h1 { +Screen.Pauses.title }
    p { +"A pause is a last resort. While one is in force, that member cannot ask to borrow anything except essentials. Loans already agreed carry on. A pause ends on its date, or sooner if it is lifted." }
    if (home.pauses.isEmpty()) p { +"No pauses are proposed or in force that you can see." }
    for (pause in home.pauses) {
        section {
            if (pause.role == PauseRole.SUBJECT) {
                h2 { +(if (pause.status == PauseStatus.LIVE) "Your access is paused until ${CalendarDay.format(pause.untilDay ?: 0)}" else "A pause has been proposed for you") }
                p { +"Reason given: ${pause.reason}" }
                p { +"Essentials stay open to you. ${pause.agreeing} of ${pause.needed} reviewers have agreed." }
                if (pause.reviewers.isNotEmpty()) p { +"Agreed so far: ${pause.reviewers.joinToString(", ") { "member ${it.v.take(MEMBER_SHORT_CHARS)}" }}" }
                if (pause.appeal != null) p { +"Your appeal: ${pause.appeal}" }
                if (pause.canAppeal) {
                    p { +"You can write one note to the reviewers. Any of them may then lift the pause." }
                    form {
                        id = FORM_PAUSE_APPEAL
                        attributes[ATTR_PAUSE] = pause.pause.v
                        label {
                            attributes["for"] = FIELD_PAUSE_NOTE
                            +"Your note: "
                        }
                        textArea {
                            id = FIELD_PAUSE_NOTE
                            name = FIELD_PAUSE_NOTE
                            required = true
                            attributes[ATTR_MIN] = "1"
                            attributes["maxlength"] = MAX_PAUSE_APPEAL_CHARS.toString()
                        }
                        formSubmit("Send my appeal")
                    }
                }
            } else {
                h2 { +"Member ${pause.subject.v.take(MEMBER_SHORT_CHARS)}: ${if (pause.status == PauseStatus.LIVE) "paused until ${CalendarDay.format(pause.untilDay ?: 0)}" else "pause proposed for ${pause.days} days"}" }
                p { +"Reason: ${pause.reason}" }
                p { +"${pause.agreeing} of ${pause.needed} reviewers have agreed." }
                if (pause.appeal != null) p { +"The member's appeal: ${pause.appeal}" }
                if (pause.iAgree == true) p { +"You agreed." } else if (pause.iAgree == false) p { +"You did not agree." }
                if (pause.canReview) {
                    pauseButton(ACTION_PAUSE_REVIEW, pause, "Agree to the pause", agree = true)
                    pauseButton(ACTION_PAUSE_REVIEW, pause, "Do not agree", agree = false)
                }
                if (pause.canStepBack) pauseButton(ACTION_PAUSE_REVIEW, pause, "Step back from this pause", agree = false)
                if (pause.canLift) {
                    details {
                        summary { +(if (pause.status == PauseStatus.LIVE) "Lift this pause" else "Cancel this proposal") }
                        p { +"Their access to everything opens again at once." }
                        pauseButton(ACTION_PAUSE_LIFT, pause, if (pause.status == PauseStatus.LIVE) "Lift the pause" else "Cancel the proposal", agree = null)
                    }
                }
            }
        }
    }
    if (home.canProposePause && home.pauseCandidates.isNotEmpty()) {
        section {
            h2 { +"Propose a pause" }
            p { +"You count as the first reviewer. It takes effect when enough other trusted members agree within 14 days, and the member is told at once." }
            form {
                id = FORM_PAUSE_PROPOSE
                label {
                    attributes["for"] = FIELD_PAUSE_SUBJECT
                    +"Member: "
                }
                select {
                    id = FIELD_PAUSE_SUBJECT
                    name = FIELD_PAUSE_SUBJECT
                    for (member in home.pauseCandidates) option {
                        value = member.v
                        +"Member ${member.v.take(MEMBER_SHORT_CHARS)}"
                    }
                }
                label {
                    attributes["for"] = FIELD_PAUSE_REASON
                    +"Reason: "
                }
                textArea {
                    id = FIELD_PAUSE_REASON
                    name = FIELD_PAUSE_REASON
                    required = true
                    attributes[ATTR_MIN] = "1"
                    attributes["maxlength"] = MAX_PAUSE_REASON_CHARS.toString()
                }
                label {
                    attributes["for"] = FIELD_PAUSE_DAYS
                    +"Days: "
                }
                input(type = InputType.number, name = FIELD_PAUSE_DAYS) {
                    id = FIELD_PAUSE_DAYS
                    required = true
                    value = DEFAULT_PAUSE_FORM_DAYS.toString()
                    attributes["min"] = MIN_PAUSE_DAYS.toString()
                    attributes["max"] = MAX_PAUSE_DAYS.toString()
                    attributes[ATTR_MIN] = "1"
                }
                formSubmit("Propose the pause")
            }
        }
    }
}

private fun FlowContent.pauseButton(action: String, pause: PauseSummary, text: String, agree: Boolean?) {
    button(type = ButtonType.button) {
        attributes[ATTR_ACTION] = action
        attributes[ATTR_PAUSE] = pause.pause.v
        if (agree != null) attributes[ATTR_AGREE] = agree.toString()
        +text
    }
}

private data class Quad(val field: String, val label: String, val step: String, val value: String)

/** Tenths of a point as text with one decimal: 25 is "2.5". */
private fun tenthsText(tenths: Int): String = "${tenths / 10}.${tenths % 10}"

private fun contributionPhrase(kind: ContributionKind, mine: Boolean): String = when (kind) {
    ContributionKind.SKILL_SHARE_HELPED -> if (mine) "helped them with a skill" else "helped you with a skill"
    ContributionKind.HOSTED_OR_ORGANISED -> if (mine) "hosted or organised something with them" else "hosted or organised something with you"
    ContributionKind.MENTORED_NEWCOMER -> if (mine) "mentored them as a newcomer" else "mentored you as a newcomer"
}

/** Contribution notes (ADR 0053): thank-yous that count once the other person confirms them. */
private fun FlowContent.contributions(home: HomeData) {
    val c = home.contributions
    if (c.toConfirm.isNotEmpty()) {
        section {
            h2 { +"Waiting for your confirmation" }
            for (note in c.toConfirm) {
                div {
                    p { +"Member ${note.other.v.take(MEMBER_SHORT_CHARS)} says they ${contributionPhrase(note.kind, mine = false)}.${if (note.text.isNotBlank()) " \"${note.text}\"" else ""}" }
                    button(type = ButtonType.button) {
                        attributes[ATTR_ACTION] = ACTION_NOTE_CONFIRM
                        attributes[ATTR_NOTE_ID] = note.note.v
                        +"Yes, that happened"
                    }
                }
            }
        }
    }
    if (home.canRead && c.candidates.isNotEmpty()) {
        section {
            h2 { +"Log a contribution" }
            p { +"Helped someone? It counts towards your standing once they confirm it. You can make ${c.notesLeft} more in the next 30 days." }
            if (c.notesLeft > 0) {
                form {
                    id = FORM_NOTE_ADD
                    label {
                        attributes["for"] = FIELD_NOTE_KIND
                        +"What you did: "
                    }
                    select {
                        id = FIELD_NOTE_KIND
                        name = FIELD_NOTE_KIND
                        for (kind in ContributionKind.entries) option {
                            value = kind.name
                            +"I ${contributionPhrase(kind, mine = true)}"
                        }
                    }
                    label {
                        attributes["for"] = FIELD_NOTE_WITH
                        +"Who: "
                    }
                    select {
                        id = FIELD_NOTE_WITH
                        name = FIELD_NOTE_WITH
                        for (member in c.candidates) option {
                            value = member.v
                            +"Member ${member.v.take(MEMBER_SHORT_CHARS)}"
                        }
                    }
                    label {
                        attributes["for"] = FIELD_NOTE_TEXT
                        +"A few words (optional): "
                    }
                    input(type = InputType.text, name = FIELD_NOTE_TEXT) {
                        id = FIELD_NOTE_TEXT
                        attributes["maxlength"] = MAX_NOTE_TEXT_CHARS.toString()
                    }
                    formSubmit("Send for confirmation", ready = true)
                }
            }
        }
    }
    if (c.mine.isNotEmpty()) {
        section {
            h2 { +"Your recent contributions" }
            ul {
                for (note in c.mine) li {
                    val what = contributionPhrase(note.kind, mine = true).replace("them", "member ${note.other.v.take(MEMBER_SHORT_CHARS)}")
                    val state = when (note.status) {
                        NoteStatus.COUNTED -> "confirmed and counted."
                        NoteStatus.WAITING -> "waiting for them to confirm."
                        NoteStatus.LAPSED -> "not confirmed in time, so it did not count."
                    }
                    +"You $what: $state"
                }
            }
        }
    }
}

private fun openToLabel(term: OpenTo): String = when (term) {
    OpenTo.GIFT -> "Happy to help for nothing"
    OpenTo.SWAP -> "Would like a favour or a trade back"
    OpenTo.THANKS -> "A thank-you is enough"
}

/** Skill offers (ADR 0055): a directory every member can read, filtered on this device as you type. */
private fun FlowContent.skills(home: HomeData) {
    h1 { +Screen.Skills.title }
    if (!home.canRead) {
        p {
            role = "status"
            +"A steward needs to let you in before you can see or offer skills."
        }
        return
    }
    p { a(href = Screen.AddSkill.fragment) { +"Offer a skill" } }
    if (home.skills.isEmpty()) {
        p { +"Nobody has offered a skill yet." }
        return
    }
    p { +"Find someone to ask. The app does not send messages: check who they are below, then ask them in person or wherever your group talks." }
    label {
        attributes["for"] = ID_SKILL_SEARCH
        +"Search: "
    }
    input(type = InputType.text) {
        id = ID_SKILL_SEARCH
        attributes["autocomplete"] = "off"
    }
    p {
        id = ID_SKILL_COUNT
        role = "status"
        +(if (home.skills.size == 1) "1 offer" else "${home.skills.size} offers")
    }
    ul {
        for (skill in home.skills) {
            li {
                attributes[ATTR_SKILL_TEXT] = (skill.name + " " + skill.details).lowercase()
                h2 { +skill.name }
                if (skill.details.isNotBlank()) p { +skill.details }
                if (skill.openTo.isNotEmpty()) p { +(skill.openTo.joinToString(". ") { openToLabel(it) } + ".") }
                if (skill.ownedByMe) {
                    p { +"Offered by you" }
                    button(type = ButtonType.button) {
                        attributes[ATTR_ACTION] = ACTION_EDIT_SKILL
                        attributes[ATTR_SKILL] = skill.skill.v
                        +"Change"
                    }
                    details {
                        summary { +"Remove" }
                        p { +"This takes the offer off everyone's list." }
                        button(type = ButtonType.button) {
                            attributes[ATTR_ACTION] = ACTION_RETIRE_SKILL
                            attributes[ATTR_SKILL] = skill.skill.v
                            +"Remove this offer"
                        }
                    }
                } else {
                    val person = home.people.firstOrNull { it.member == skill.owner }
                    p { +"Offered by member ${skill.owner.v.take(MEMBER_SHORT_CHARS)}${person?.let { ". Check who they are: ${it.fingerprint}" } ?: ""}" }
                }
            }
        }
    }
}

private fun FlowContent.skillForm(existing: SkillSummary?) {
    h1 { +(if (existing == null) Screen.AddSkill.title else Screen.EditSkill.title) }
    form {
        id = if (existing == null) FORM_SKILL_ADD else FORM_SKILL_EDIT
        label {
            attributes["for"] = FIELD_SKILL_NAME
            +"What you can help with: "
        }
        input(type = InputType.text, name = FIELD_SKILL_NAME) {
            id = FIELD_SKILL_NAME
            required = true
            value = existing?.name.orEmpty()
            attributes["maxlength"] = MAX_SKILL_NAME_CHARS.toString()
            attributes[ATTR_MIN] = "1"
            attributes["autocomplete"] = "off"
        }
        label {
            attributes["for"] = FIELD_SKILL_DETAILS
            +"More about it: "
        }
        textArea {
            id = FIELD_SKILL_DETAILS
            name = FIELD_SKILL_DETAILS
            attributes["maxlength"] = MAX_SKILL_DETAILS_CHARS.toString()
            +existing?.details.orEmpty()
        }
        fieldSet {
            legend { +"What would be welcome in return (optional)" }
            for ((field, term) in listOf(FIELD_SKILL_GIFT to OpenTo.GIFT, FIELD_SKILL_SWAP to OpenTo.SWAP, FIELD_SKILL_THANKS to OpenTo.THANKS)) {
                div {
                    input(type = InputType.checkBox, name = field) {
                        id = field
                        checked = existing != null && term in existing.openTo
                    }
                    label {
                        attributes["for"] = field
                        +openToLabel(term)
                    }
                }
            }
        }
        formSubmit(if (existing == null) "Offer it" else "Save changes", ready = existing != null)
    }
    p { a(href = Screen.Skills.fragment) { +"Back to skills" } }
}
