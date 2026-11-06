package circles.text

actual fun nfkc(text: String): String = text.asDynamic().normalize("NFKC").unsafeCast<String>()
