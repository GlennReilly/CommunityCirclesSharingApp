package circles.server.domain

import circles.event.DeviceId
import circles.event.newCircleId
import circles.crypto.JvmCrypto
import circles.event.newDeviceId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The in-memory hint fan-out of ADR 0026. */
class EventHubTest {
    private val crypto = JvmCrypto()
    private val circle = newCircleId(crypto)
    private val device: DeviceId = newDeviceId(crypto)

    private fun subscribe(hub: EventHub, in_: circles.policy.CircleId = circle, who: DeviceId = device): HintSubscription =
        assertIs<SubscribeResult.Subscribed>(hub.subscribe(in_, who)).subscription

    @Test
    fun aHintReachesOnlySubscribersOfThatCircle() = runTest {
        val hub = EventHub()
        val mine = subscribe(hub)
        val other = subscribe(hub, newCircleId(crypto), newDeviceId(crypto))
        hub.publish(circle, 7)
        assertEquals(7L, mine.next(1_000))
        assertNull(other.next(50))
    }

    @Test
    fun aSlowReaderOnlyGetsTheNewestNumber() = runTest {
        val hub = EventHub()
        val sub = subscribe(hub)
        listOf(1L, 2L, 3L).forEach { hub.publish(circle, it) }
        assertEquals(3L, sub.next(1_000))
        assertNull(sub.next(20))
    }

    @Test
    fun everySubscriberOfTheCircleGetsTheHint() = runTest {
        val hub = EventHub()
        val a = subscribe(hub, circle, newDeviceId(crypto))
        val b = subscribe(hub, circle, newDeviceId(crypto))
        hub.publish(circle, 9)
        assertEquals(9L, a.next(1_000))
        assertEquals(9L, b.next(1_000))
    }

    @Test
    fun aDeviceMayHoldOnlyAFewStreamsAndClosingFreesASlot() {
        val hub = EventHub(maxPerDevice = 2, maxTotal = 10)
        val first = subscribe(hub)
        subscribe(hub)
        assertIs<SubscribeResult.DeviceLimit>(hub.subscribe(circle, device))
        assertIs<SubscribeResult.Subscribed>(hub.subscribe(circle, newDeviceId(crypto)), "another device is unaffected")
        first.close()
        assertIs<SubscribeResult.Subscribed>(hub.subscribe(circle, device))
    }

    @Test
    fun theWholeProcessHasAStreamLimit() {
        val hub = EventHub(maxPerDevice = 5, maxTotal = 3)
        val held = List(3) { subscribe(hub, circle, newDeviceId(crypto)) }
        assertEquals(3, hub.openStreams)
        assertIs<SubscribeResult.ServerLimit>(hub.subscribe(circle, newDeviceId(crypto)))
        held.first().close()
        assertEquals(2, hub.openStreams)
        assertIs<SubscribeResult.Subscribed>(hub.subscribe(circle, newDeviceId(crypto)))
    }

    @Test
    fun closingTwiceAndPublishingToAClosedStreamAreHarmless() {
        val hub = EventHub()
        val sub = subscribe(hub)
        sub.close()
        sub.close()
        hub.publish(circle, 1)
        assertEquals(0, hub.openStreams)
    }
}
