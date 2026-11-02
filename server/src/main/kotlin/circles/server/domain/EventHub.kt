package circles.server.domain

import circles.event.DeviceId
import circles.policy.CircleId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** Streams one device may hold open, and streams in the whole process (ADR 0026). */
const val MAX_STREAMS_PER_DEVICE = 3
const val MAX_STREAMS_TOTAL = 500

/** One open stream. [next] waits for a newer sequence number; it returns null if none arrives within the timeout. */
interface HintSubscription : AutoCloseable {
    suspend fun next(timeoutMillis: Long): Long?
}

sealed interface SubscribeResult {
    data class Subscribed(val subscription: HintSubscription) : SubscribeResult

    data object DeviceLimit : SubscribeResult

    data object ServerLimit : SubscribeResult
}

/** Tells open streams that a circle has a newer event (ADR 0026). */
interface EventHints {
    fun publish(circle: CircleId, serverSeq: Long)
}

/** Does nothing; used where no one listens. */
object NoHints : EventHints {
    override fun publish(circle: CircleId, serverSeq: Long) = Unit
}

/**
 * In-memory fan-out of "the circle is now at [serverSeq]" hints. Each subscriber keeps only the newest number, so a
 * slow reader never builds a backlog. Limits per device and in total are enforced here.
 */
class EventHub(
    private val maxPerDevice: Int = MAX_STREAMS_PER_DEVICE,
    private val maxTotal: Int = MAX_STREAMS_TOTAL,
) : EventHints {
    private class Subscriber(val circle: CircleId, val device: DeviceId, val channel: Channel<Long> = Channel(Channel.CONFLATED))

    private val subscribers = ConcurrentHashMap.newKeySet<Subscriber>()
    private val total = AtomicInteger(0)
    private val lock = Any()

    val openStreams: Int get() = total.get()

    fun subscribe(circle: CircleId, device: DeviceId): SubscribeResult {
        val subscriber = Subscriber(circle, device)
        synchronized(lock) {
            if (total.get() >= maxTotal) return SubscribeResult.ServerLimit
            if (subscribers.count { it.device == device } >= maxPerDevice) return SubscribeResult.DeviceLimit
            subscribers += subscriber
            total.incrementAndGet()
        }
        return SubscribeResult.Subscribed(Handle(subscriber))
    }

    override fun publish(circle: CircleId, serverSeq: Long) {
        subscribers.filter { it.circle == circle }.forEach { it.channel.trySend(serverSeq) }
    }

    private inner class Handle(private val subscriber: Subscriber) : HintSubscription {
        override suspend fun next(timeoutMillis: Long): Long? = withTimeoutOrNull(timeoutMillis) { subscriber.channel.receive() }

        override fun close() {
            synchronized(lock) {
                if (subscribers.remove(subscriber)) total.decrementAndGet()
            }
            subscriber.channel.close()
        }
    }
}
