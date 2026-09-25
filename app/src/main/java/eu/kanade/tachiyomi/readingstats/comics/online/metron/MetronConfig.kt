package eu.kanade.tachiyomi.readingstats.comics.online.metron

object MetronConfig {

    const val BASE_URL = "https://metron.cloud/api/"

    /** Metron allows 20 requests a minute (and 5,000 a day) per account. */
    const val REQUESTS_PER_MINUTE = 20
    const val WINDOW_MS = 60_000L

    /** How long to wait after a 429 that carries no Retry-After, in seconds. */
    const val DEFAULT_RETRY_AFTER_S = 60L

    /** A 429 asking to wait longer than this means the daily allowance is spent: give up for now. */
    const val MAX_WAIT_S = 5 * 60L

    const val MAX_RETRIES = 3

    /** Issue details kept on disk, so a second fetch asks only for what changed. */
    const val CACHE_DIR = "metron"

    const val SEARCH_RESULTS = 20
}
