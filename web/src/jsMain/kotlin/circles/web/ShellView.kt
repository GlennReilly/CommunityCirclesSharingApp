package circles.web

import circles.app.AppModel
import circles.app.HomeData
import circles.app.INVITE_VALID_DAYS
import circles.app.ItemSummary
import circles.app.MAX_ITEM_DESCRIPTION_CHARS
import circles.event.MAX_ITEM_NAME_CHARS
import circles.policy.ItemClass
import circles.keys.MIN_PASSPHRASE_CHARS
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.InputType
import kotlinx.html.option
import kotlinx.html.select
import kotlinx.html.textArea
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.details
import kotlinx.html.div
import kotlinx.html.form
import kotlinx.html.h1
import kotlinx.html.h2
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
const val MAIN_ID = "main"
const val NOTICE_ID = "notice"

// Form and control ids the browser glue wires up; kept here so the views and the glue cannot drift apart.
const val FORM_START = "start-form"
const val FORM_JOIN = "join-form"
const val FORM_LINK = "link-form"
const val FORM_UNLOCK = "unlock-form"
const val FIELD_PASSPHRASE = "passphrase"
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
const val FIELD_DETAILS = "details"
const val ATTR_ITEM = "data-item"
const val ATTR_MIN = "data-min"
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
        h1 { +"Circles" }
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
        }
        if (state.updateAvailable) {
            div {
                role = "alert"
                +"A new version is ready. "
                button(type = ButtonType.button) {
                    id = UPDATE_BUTTON_ID
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
            is AppModel.Open -> when (state.screen) {
                Screen.Home -> home(model.home, state)
                Screen.Items -> items(model.home)
                Screen.AddItem -> itemForm(null)
                Screen.EditItem -> itemForm(model.home.items.firstOrNull { it.item == state.editing })
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

private fun FlowContent.passphraseField(autocomplete: String, hint: Boolean) {
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
    if (hint) {
        p {
            id = "passphrase-help"
            +"At least $MIN_PASSPHRASE_CHARS characters; a few random words is good. You type it each time you open Circles. "
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

private fun FlowContent.home(home: HomeData, state: AppState) {
    h1 { +Screen.Home.title }
    if (!home.canRead) {
        p {
            role = "status"
            +"You are in the circle. A steward needs to let you in before you can see anything."
        }
    }
    p { +(if (home.members == 1) "1 member" else "${home.members} members") }
    if (home.refusedChanges > 0) {
        p { +"${home.refusedChanges} of your changes could not be sent: the server refused them." }
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
    button(type = ButtonType.button) {
        attributes[ATTR_ACTION] = ACTION_LOCK
        +"Lock now"
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
