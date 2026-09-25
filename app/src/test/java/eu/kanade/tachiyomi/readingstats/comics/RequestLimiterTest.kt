package eu.kanade.tachiyomi.readingstats.comics

import eu.kanade.tachiyomi.readingstats.comics.online.ComicDatabaseException
import eu.kanade.tachiyomi.readingstats.comics.online.RequestLimiter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class RequestLimiterTest {

    /** A clock that only moves when the limiter sleeps. */
    private class FakeTime {
        var now = 0L
        val slept = mutableListOf<Long>()
        fun limiter(limit: Int, windowMs: Long) = RequestLimiter(limit, windowMs, { now }) { ms ->
            slept += ms
            now += ms
        }
    }

    @Test
    fun `the twenty-first request in a minute waits for the first to leave the window`() = runTest {
        val time = FakeTime()
        val limiter = time.limiter(limit = 20, windowMs = 60_000)

        repeat(20) {
            limiter.acquire()
            time.now += 1_000
        }
        limiter.acquire()

        time.slept shouldBe listOf(40_000L)
        time.now shouldBe 60_000
    }

    @Test
    fun `a 429 holds every request back for as long as asked`() = runTest {
        val time = FakeTime()
        val limiter = time.limiter(limit = 20, windowMs = 60_000)

        limiter.backOff(seconds = 30)
        limiter.acquire()

        time.slept shouldBe listOf(30_000L)
    }

    @Test
    fun `a wait longer than allowed gives up instead of sleeping`() = runTest {
        val time = FakeTime()
        val limiter = time.limiter(limit = 30, windowMs = 60 * 60 * 1000L)

        repeat(30) { limiter.acquire(maxWaitMs = 180_000) }

        shouldThrow<ComicDatabaseException.LimitReached> { limiter.acquire(maxWaitMs = 180_000) }
        time.slept shouldBe emptyList()
    }
}
