package eu.kanade.tachiyomi.readingstats.comics.online

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.await
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Response

/** How a service wants to be asked: its pace, and how long a 429 may make us wait before giving up. */
data class RequestPolicy(
    val limiter: RequestLimiter,
    val defaultRetryAfterS: Long,
    val maxWaitS: Long,
    val retries: Int,
)

/**
 * GET [url] within [policy]: waits its turn, waits out a 429 that asks for a short pause, and turns
 * refusals into [ComicDatabaseException]s. The caller closes the response.
 */
suspend fun OkHttpClient.limitedGet(url: String, headers: Headers, policy: RequestPolicy): Response {
    repeat(policy.retries) {
        policy.limiter.acquire(maxWaitMs = policy.maxWaitS * 1000)
        val response = newCall(GET(url, headers)).await()
        when (response.code) {
            in 200..299 -> return response
            401, 403 -> {
                response.close()
                throw ComicDatabaseException.Unauthorized()
            }
            429 -> {
                val wait = response.header("Retry-After")?.toLongOrNull() ?: policy.defaultRetryAfterS
                response.close()
                if (wait > policy.maxWaitS) throw ComicDatabaseException.LimitReached()
                policy.limiter.backOff(wait)
            }
            else -> {
                response.close()
                throw ComicDatabaseException.Failed(response.code)
            }
        }
    }
    throw ComicDatabaseException.LimitReached()
}
