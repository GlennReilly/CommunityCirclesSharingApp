package circles.web

/** The v1 screens (spec section 6). Routing is by URL fragment, so no server-side routes are needed. */
enum class Screen(val path: String, val title: String) {
    Welcome("welcome", "Welcome to Lendger"),
    StartCircle("start", "Start a circle"),
    JoinCircle("join", "Join a circle"),
    PairDevice("pair-device", "Add this device"),
    Restore("restore", "Restore from a recovery code"),
    RecoveryCode("recovery-code", "Your recovery code"),
    SignIn("sign-in", "Sign in"),
    YourName("your-name", "Your name"),
    Home("home", "Circle home"),
    Items("items", "Items"),
    AddItem("items/add", "Add an item"),
    EditItem("items/edit", "Change an item"),
    Skills("skills", "Skills"),
    Verify("verify", "Check identity"),
    CheckIdentity("members/check", "Check identity"),
    CheckDevice("settings/check-device", "Check the new device"),
    ItemTag("items/tag", "Item tag"),
    ItemHistory("items/history", "Loan history"),
    BorrowHistory("borrow/history", "My borrowing history"),
    AddSkill("skills/add", "Offer a skill"),
    EditSkill("skills/edit", "Change a skill offer"),
    Borrow("borrow", "Borrow"),
    RequestLoan("borrow/request", "Ask to borrow"),
    Standing("standing", "My standing"),
    Pauses("pauses", "Pauses"),
    Members("members", "Members"),
    Stewards("stewards", "Stewards"),
    RecoveryShares("stewards/recovery-shares", "Recovery shares of the circle key"),
    Settings("settings", "Settings");

    companion object {
        const val FRAGMENT_PREFIX = "#/"

        /** The screen for a URL fragment such as `#/items`; anything unknown or empty is [Home] (the guard in `reduce` then decides what a signed-out visitor sees). */
        fun fromFragment(fragment: String): Screen =
            entries.firstOrNull { FRAGMENT_PREFIX + it.path == fragment } ?: Home

        /** Pages for a browser with no identity yet: a choice, then one page per choice. */
        val signedOut: Set<Screen> = setOf(Welcome, StartCircle, JoinCircle, PairDevice, Restore)

        /** Pages that are not part of the circle itself: the signed-out pages and the unlock page. */
        val beforeSignIn: Set<Screen> = signedOut + SignIn

        /** Pages reached from another page, not from the navigation bar. */
        val subPages: Set<Screen> = setOf(AddItem, EditItem, RequestLoan, RecoveryCode, RecoveryShares, Pauses, AddSkill, EditSkill, Verify, CheckIdentity, CheckDevice, ItemTag, ItemHistory, BorrowHistory, YourName)

        /** Screens shown in the navigation bar once signed in. */
        val navigation: List<Screen> = entries.filter { it !in beforeSignIn && it !in subPages }
    }

    val fragment: String get() = FRAGMENT_PREFIX + path
}
