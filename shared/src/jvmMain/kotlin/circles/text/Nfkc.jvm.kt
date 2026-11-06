package circles.text

import java.text.Normalizer

actual fun nfkc(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)
