package circles.web

/** The v1 screens (spec section 6). Routing is by URL fragment, so no server-side routes are needed. */
enum class Screen(val path: String, val title: String) {
    SignIn("sign-in", "Join or sign in"),
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

        /** The screen for a URL fragment such as `#/items`; anything unknown or empty is [Home]. */
        fun fromFragment(fragment: String): Screen =
            entries.firstOrNull { FRAGMENT_PREFIX + it.path == fragment } ?: Home

        /** Screens shown in the navigation bar once signed in. */
        val navigation: List<Screen> = entries.filter { it != SignIn }
    }

    val fragment: String get() = FRAGMENT_PREFIX + path
}
