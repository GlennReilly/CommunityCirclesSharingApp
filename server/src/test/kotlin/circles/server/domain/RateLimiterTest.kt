package circles.server.domain

import circles.server.support.MutableClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RateLimiterTest {
    private val clock = MutableClock()
    private val limiter = RateLimiter(clock)

    private fun allowed(bucket: Bucket, key: String): Boolean = limiter.check(bucket, key) == Limit.Allowed

    private fun spend(bucket: Bucket, key: String): Int = generateSequence { allowed(bucket, key).takeIf { it } }.count()

    @Test
    fun theLimitsAreThoseOfAdr0015() {
        assertEquals(mapOf("auth" to 10, "push" to 60, "join" to 5, "create" to 5), Bucket.entries.associate { it.label to it.perMinute })
    }

    @Test
    fun aBucketAllowsItsWholeAllowanceThenRefuses() {
        assertEquals(10, spend(Bucket.AUTH, "a"))
        assertIs<Limit.Limited>(limiter.check(Bucket.AUTH, "a"))
        assertEquals(60, spend(Bucket.PUSH, "d"))
        assertEquals(5, spend(Bucket.JOIN, "a"))
        assertEquals(5, spend(Bucket.CREATE, "a"))
    }

    @Test
    fun theRetryAfterTellsHowLongUntilOneRequestFits() {
        spend(Bucket.AUTH, "a")
        assertEquals(Limit.Limited(6), limiter.check(Bucket.AUTH, "a"))
        clock.advanceSeconds(3)
        assertEquals(Limit.Limited(3), limiter.check(Bucket.AUTH, "a"))
        spend(Bucket.PUSH, "d")
        assertEquals(Limit.Limited(1), limiter.check(Bucket.PUSH, "d"))
    }

    @Test
    fun tokensComeBackSteadily() {
        spend(Bucket.AUTH, "a")
        clock.advanceSeconds(6)
        assertEquals(1, spend(Bucket.AUTH, "a"))
        clock.advanceSeconds(30)
        assertEquals(5, spend(Bucket.AUTH, "a"))
    }

    @Test
    fun idleTimeNeverBuildsUpMoreThanTheAllowance() {
        spend(Bucket.JOIN, "a")
        clock.advanceSeconds(60L * 60 * 24)
        assertEquals(5, spend(Bucket.JOIN, "a"))
    }

    @Test
    fun keysAndBucketsAreIndependent() {
        spend(Bucket.AUTH, "a")
        assertEquals(true, allowed(Bucket.AUTH, "b"))
        assertEquals(true, allowed(Bucket.JOIN, "a"))
    }

    @Test
    fun aClockThatGoesBackwardsDoesNotGiveFreeTokens() {
        spend(Bucket.AUTH, "a")
        clock.millis -= 60_000
        assertIs<Limit.Limited>(limiter.check(Bucket.AUTH, "a"))
    }

    @Test
    fun onlyTheMostRecentlyUsedKeysAreRemembered() {
        val small = RateLimiter(clock, maxKeys = 3)
        listOf("a", "b", "c", "d").forEach { small.check(Bucket.JOIN, it) }
        assertEquals(3, small.trackedKeys())
        // "a" was forgotten, so it starts again with a full allowance.
        repeat(5) { small.check(Bucket.JOIN, "b") }
        assertIs<Limit.Limited>(small.check(Bucket.JOIN, "b"))
        assertEquals(Limit.Allowed, small.check(Bucket.JOIN, "a"))
    }

    @Test
    fun concurrentRequestsNeverSpendMoreThanTheAllowance() {
        val pool = Executors.newFixedThreadPool(8)
        val allowedCount = AtomicInteger()
        val start = CountDownLatch(1)
        try {
            val futures = List(8) {
                pool.submit {
                    start.await()
                    repeat(20) { if (allowed(Bucket.AUTH, "shared")) allowedCount.incrementAndGet() }
                }
            }
            start.countDown()
            futures.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        assertEquals(10, allowedCount.get())
    }

    @Test
    fun unlimitedAlwaysAllows() {
        repeat(1000) { assertEquals(Limit.Allowed, Unlimited.check(Bucket.JOIN, "a")) }
    }
}
