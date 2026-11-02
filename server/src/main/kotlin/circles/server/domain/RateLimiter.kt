package circles.server.domain

import java.time.Clock

private const val WINDOW_MILLIS = 60_000L
private const val MILLIS_PER_SECOND = 1000.0
private const val MAX_TRACKED_KEYS = 10_000
private const val LRU_LOAD_FACTOR = 0.75f
private const val ONE_TOKEN = 1.0

/** The limits of ADR 0015, each per minute. */
enum class Bucket(val label: String, val perMinute: Int) {
    AUTH("auth", 10),
    PUSH("push", 60),
    JOIN("join", 5),
    CREATE("create", 5),
}

sealed interface Limit {
    data object Allowed : Limit

    data class Limited(val retryAfterSeconds: Long) : Limit
}

fun interface RateLimits {
    /** Counts one request against [bucket] for [key] and says whether it may go ahead. */
    fun check(bucket: Bucket, key: String): Limit
}

/** Lets everything through (`CIRCLES_RATE_LIMIT=false`). */
object Unlimited : RateLimits {
    override fun check(bucket: Bucket, key: String): Limit = Limit.Allowed
}

/**
 * Token buckets in memory: a bucket holds up to its per-minute allowance, spends one token per request and refills
 * steadily. Tracks at most [maxKeys] keys; the least recently used is forgotten first.
 */
class RateLimiter(private val clock: Clock, private val maxKeys: Int = MAX_TRACKED_KEYS) : RateLimits {
    private class State(var tokens: Double, var lastMillis: Long)

    private val states = object : LinkedHashMap<String, State>(INITIAL_CAPACITY, LRU_LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, State>?): Boolean = size > maxKeys
    }

    @Synchronized
    override fun check(bucket: Bucket, key: String): Limit {
        val now = clock.millis()
        val capacity = bucket.perMinute.toDouble()
        val ratePerMilli = capacity / WINDOW_MILLIS
        val state = states.getOrPut("${bucket.label}:$key") { State(capacity, now) }
        state.tokens = minOf(capacity, state.tokens + (now - state.lastMillis).coerceAtLeast(0) * ratePerMilli)
        state.lastMillis = now
        if (state.tokens >= ONE_TOKEN) {
            state.tokens -= ONE_TOKEN
            return Limit.Allowed
        }
        val seconds = kotlin.math.ceil((ONE_TOKEN - state.tokens) / ratePerMilli / MILLIS_PER_SECOND).toLong()
        return Limit.Limited(seconds.coerceAtLeast(1))
    }

    @Synchronized
    fun trackedKeys(): Int = states.size

    private companion object {
        const val INITIAL_CAPACITY = 16
    }
}
