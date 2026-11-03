package circles.sync

/** How long to wait before the next sync attempt after failures (spec 6: retry with backoff). Pure, so it is tested without a clock. */
object Backoff {
    const val BASE_MILLIS = 2_000L
    const val MAX_MILLIS = 5L * 60 * 1000
    const val FACTOR = 2L
    /** Delays are spread by up to this fraction either way, so a restarted server is not hit by every client at once. */
    const val JITTER_FRACTION = 0.2
    private const val MAX_DOUBLINGS = 20

    /** [attempt] counts failures so far (0 for the first retry); [jitter] is a random number in 0 until 1. */
    fun delayMillis(attempt: Int, jitter: Double, serverAskedSeconds: Long? = null): Long {
        require(attempt >= 0) { "attempt must not be negative" }
        require(jitter >= 0.0 && jitter < 1.0) { "jitter must be in 0 until 1" }
        var delay = BASE_MILLIS
        repeat(minOf(attempt, MAX_DOUBLINGS)) { delay = minOf(delay * FACTOR, MAX_MILLIS) }
        val spread = (delay * JITTER_FRACTION * (2 * jitter - 1)).toLong()
        val own = (delay + spread).coerceIn(BASE_MILLIS / 2, MAX_MILLIS)
        val asked = serverAskedSeconds?.coerceIn(0, MAX_MILLIS / 1000)?.times(1000) ?: 0L
        return maxOf(own, asked)
    }
}
