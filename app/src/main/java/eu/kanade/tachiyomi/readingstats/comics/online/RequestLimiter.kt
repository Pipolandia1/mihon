package eu.kanade.tachiyomi.readingstats.comics.online

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps requests within [limit] per [windowMs], and holds everything back while the server has asked
 * to wait. Callers queue on [acquire]; nothing is dropped.
 */
class RequestLimiter(
    private val limit: Int,
    private val windowMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {

    private val mutex = Mutex()
    private val sent = ArrayDeque<Long>()
    private val blockedUntil = AtomicLong(0)

    /**
     * Waits until a request may go out, and counts it. Throws [ComicDatabaseException.LimitReached]
     * instead of waiting longer than [maxWaitMs].
     */
    suspend fun acquire(maxWaitMs: Long = Long.MAX_VALUE) = mutex.withLock {
        while (true) {
            val time = now()
            while (sent.isNotEmpty() && sent.first() <= time - windowMs) sent.removeFirst()
            val forWindow = if (sent.size >= limit) sent.first() + windowMs - time else 0
            val wait = maxOf(blockedUntil.get() - time, forWindow)
            if (wait <= 0) {
                sent.addLast(time)
                return@withLock
            }
            if (wait > maxWaitMs) throw ComicDatabaseException.LimitReached()
            sleep(wait)
        }
    }

    /** The server answered 429: nothing goes out for [seconds]. */
    fun backOff(seconds: Long) {
        val until = now() + seconds * 1000
        blockedUntil.getAndUpdate { maxOf(it, until) }
    }
}
