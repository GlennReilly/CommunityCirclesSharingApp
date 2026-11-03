package circles.web

import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.button
import kotlinx.html.div
import kotlinx.html.h1
import kotlinx.html.header
import kotlinx.html.id
import kotlinx.html.li
import kotlinx.html.main
import kotlinx.html.nav
import kotlinx.html.p
import kotlinx.html.role
import kotlinx.html.ul

const val UPDATE_BUTTON_ID = "update-now"
const val MAIN_ID = "main"

/**
 * The page shell as a pure function of [AppState]. It writes through any kotlinx-html consumer, so the browser mounts
 * it into the DOM and the tests render it to a string. Focus goes to the `main` landmark after a screen change.
 */
fun FlowContent.shell(state: AppState) {
    header {
        h1 { +"Circles" }
        div {
            id = "sync-status"
            role = "status"
            +describe(state.sync)
        }
        if (state.updateAvailable) {
            div {
                role = "alert"
                +"A new version is ready. "
                button(type = kotlinx.html.ButtonType.button) {
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
    main {
        id = MAIN_ID
        attributes["tabindex"] = "-1"
        h1 { +state.screen.title }
        p { +"This screen is not built yet." }
    }
}
