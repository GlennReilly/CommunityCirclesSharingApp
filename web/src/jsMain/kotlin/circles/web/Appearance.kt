package circles.web

/** How big the text is. The percent scales the root font size, so everything sized in rem follows. */
enum class TextSize(val key: String, val label: String) {
    STANDARD("standard", "Standard"),
    LARGE("large", "Large"),
    LARGER("larger", "Largest"),
}

/** Which colours to use: follow the device, or always light or dark. */
enum class ThemeChoice(val key: String, val label: String) {
    SYSTEM("system", "Same as this device"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark"),
}

/** Display preferences of this browser (text size, colours, how dates are written) (not secret, not part of the circle): kept in local storage, applied as attributes on `<html>`. */
data class Appearance(
    val textSize: TextSize = TextSize.STANDARD,
    val theme: ThemeChoice = ThemeChoice.SYSTEM,
    val dateFormat: circles.app.DateFormat = circles.app.DateFormat.DEFAULT,
) {
    companion object {
        /** Unknown or missing stored values fall back to the defaults. */
        fun fromKeys(textSize: String?, theme: String?, dateFormat: String? = null): Appearance = Appearance(
            TextSize.entries.firstOrNull { it.key == textSize } ?: TextSize.STANDARD,
            ThemeChoice.entries.firstOrNull { it.key == theme } ?: ThemeChoice.SYSTEM,
            circles.app.DateFormat.fromKey(dateFormat),
        )
    }
}
