package circles.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackoffTest {
    private val middle = 0.5

    @Test fun theFirstRetryWaitsTheBaseDelay() {
        assertEquals(Backoff.BASE_MILLIS, Backoff.delayMillis(0, middle))
    }

    @Test fun delaysDoubleThenStopAtTheCap() {
        assertEquals(Backoff.BASE_MILLIS * 2, Backoff.delayMillis(1, middle))
        assertEquals(Backoff.BASE_MILLIS * 4, Backoff.delayMillis(2, middle))
        assertEquals(Backoff.MAX_MILLIS, Backoff.delayMillis(50, middle))
        assertEquals(Backoff.MAX_MILLIS, Backoff.delayMillis(Int.MAX_VALUE, middle))
    }

    @Test fun jitterSpreadsTheDelayBothWays() {
        val low = Backoff.delayMillis(3, 0.0)
        val high = Backoff.delayMillis(3, 0.999)
        val centre = Backoff.delayMillis(3, middle)
        assertTrue(low < centre && centre < high)
        assertTrue(high <= (centre * (1 + Backoff.JITTER_FRACTION)).toLong() + 1)
    }

    @Test fun theServersRetryAfterIsAFloor() {
        assertEquals(30_000, Backoff.delayMillis(0, middle, serverAskedSeconds = 30))
        assertEquals(Backoff.BASE_MILLIS, Backoff.delayMillis(0, middle, serverAskedSeconds = 0))
        // A silly Retry-After is capped, not obeyed for hours.
        assertEquals(Backoff.MAX_MILLIS, Backoff.delayMillis(0, middle, serverAskedSeconds = 86_400))
    }

    @Test fun badArgumentsAreRefused() {
        assertFailsWith<IllegalArgumentException> { Backoff.delayMillis(-1, middle) }
        assertFailsWith<IllegalArgumentException> { Backoff.delayMillis(0, 1.0) }
        assertFailsWith<IllegalArgumentException> { Backoff.delayMillis(0, -0.1) }
    }
}
