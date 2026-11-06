package circles.text

/**
 * Unicode NFKC normalisation from the platform (the JDK's `Normalizer`, the browser's `String.normalize`). Used where a
 * person's typing is turned into stored text (display names, ADR 0068), never inside the fold: platforms may know
 * different Unicode versions, and every client must judge the log the same way.
 */
expect fun nfkc(text: String): String
