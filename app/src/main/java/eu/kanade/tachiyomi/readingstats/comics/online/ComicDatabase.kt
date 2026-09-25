package eu.kanade.tachiyomi.readingstats.comics.online

import tachiyomi.domain.readingstats.comics.ChapterName
import tachiyomi.domain.readingstats.comics.MetadataOrigin
import tachiyomi.domain.readingstats.comics.MetadataValue
import java.io.IOException

/**
 * An online comic database the user can ask for one series' metadata. It receives only the series'
 * title and year, and the ids of what the user picks; nothing from the library or from reading.
 */
interface ComicDatabase {

    val origin: MetadataOrigin

    /** Whether it can be asked now (Metron needs the user's token). */
    val available: Boolean

    suspend fun search(name: String, year: Int?): List<SeriesCandidate>

    /**
     * Suggestions for the series and for each chapter that pairs with one of its issues.
     * [onProgress] gets (done, total) as issue details arrive.
     */
    suspend fun suggestions(
        candidate: SeriesCandidate,
        chapters: List<ChapterName>,
        labels: Map<Long, String>,
        onProgress: (Int, Int) -> Unit,
    ): OnlineSuggestions
}

/** A series a search found, for the user to pick. */
data class SeriesCandidate(
    val origin: MetadataOrigin,
    val id: Long,
    val name: String,
    val yearBegan: Int?,
    val yearEnd: Int?,
    val publisher: String?,
    /** Metron's series type, the GCD's publishing format. */
    val format: String?,
    val issueCount: Int?,
)

/**
 * What a fetch brought. When the service's allowance ran out part way, [fetched] is less than
 * [matched]: what came is kept, and asking again later goes on from the cache.
 */
data class OnlineSuggestions(
    val values: List<MetadataValue>,
    /** Chapters paired with one of the series' issues. */
    val matched: Int,
    /** Of those, how many got their issue's details. */
    val fetched: Int,
) {
    val complete: Boolean get() = fetched == matched
}

sealed class ComicDatabaseException(message: String) : IOException(message) {
    class NoToken : ComicDatabaseException("No token")
    class Unauthorized : ComicDatabaseException("The service refused access")
    class LimitReached : ComicDatabaseException("The request allowance is spent for now")
    class Failed(code: Int) : ComicDatabaseException("The service answered $code")
}
