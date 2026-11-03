package circles.web

/** The v1 screens (spec section 6). Routing is by URL fragment, so no server-side routes are needed. */
enum class Screen(val path: String, val title: String) {
    Welcome("welcome", "Welcome to Circles"),
    StartCircle("start", "Start a circle"),
    JoinCircle("join", "Join a circle"),
    SignIn("sign-in", "Sign in"),
    Home("home", "Circle home"),
    Items("items", "Items"),
    Skills("skills", "Skills"),
    Borrow("borrow", "Borrow"),
    Standing("standing", "My standing"),
    Members("members", "Members"),
    Stewards("stewards", "Stewards"),
    Settings("settings", "Settings");

    companion object {
        const val FRAGMENT_PREFIX = "#/"

        /** The screen for a URL fragment such as `#/items`; anything unknown or empty is [Home] (the guard in `reduce` then decides what a signed-out visitor sees). */
        fun fromFragment(fragment: String): Screen =
            entries.firstOrNull { FRAGMENT_PREFIX + it.path == fragment } ?: Home

        /** Pages for a browser with no identity yet: a choice, then one page per choice. */
        val newcomer: Set<Screen> = setOf(Welcome, StartCircle, JoinCircle)

        /** Pages that are not part of the circle itself: the newcomer pages and the unlock page. */
        val beforeSignIn: Set<Screen> = newcomer + SignIn

        /** Screens shown in the navigation bar once signed in. */
        val navigation: List<Screen> = entries.filter { it !in beforeSignIn }
    }

    val fragment: String get() = FRAGMENT_PREFIX + path
}
