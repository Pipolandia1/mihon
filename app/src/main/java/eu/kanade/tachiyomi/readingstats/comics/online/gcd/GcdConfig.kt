package eu.kanade.tachiyomi.readingstats.comics.online.gcd

object GcdConfig {

    const val BASE_URL = "https://www.comics.org/api/"

    /** The GCD's limits (its settings.py): 30 requests an hour anonymously, 2,000 a day logged in. */
    const val ANONYMOUS_REQUESTS = 30
    const val ANONYMOUS_WINDOW_MS = 60 * 60 * 1000L
    const val USER_REQUESTS = 2000
    const val USER_WINDOW_MS = 24 * 60 * 60 * 1000L

    const val DEFAULT_RETRY_AFTER_S = 120L

    /** A 429 asking to wait longer than this stops the fetch; what came so far is kept. */
    const val MAX_WAIT_S = 3 * 60L

    const val MAX_RETRIES = 3

    /** Issue details and publisher names kept on disk; the GCD gives no change dates to compare. */
    const val CACHE_DIR = "gcd"
    const val CACHE_DAYS = 30L

    const val SEARCH_RESULTS = 25
}
