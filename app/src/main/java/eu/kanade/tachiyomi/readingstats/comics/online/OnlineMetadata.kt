package eu.kanade.tachiyomi.readingstats.comics.online

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.readingstats.comics.online.gcd.GcdApi
import eu.kanade.tachiyomi.readingstats.comics.online.metron.MetronApi
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.readingstats.comics.ChapterName
import tachiyomi.domain.readingstats.comics.MetadataOrigin
import tachiyomi.domain.readingstats.comics.SeriesTitles
import java.io.IOException

/** Why a search went to the GCD instead of Metron. */
enum class FallbackReason {
    NO_TOKEN,
    NOT_FOUND,
    REFUSED,
    LIMIT_REACHED,
    FAILED,
}

data class OnlineSearch(
    /** What was sent: the series name, and its start year if the title gave one. */
    val query: String,
    val year: Int?,
    val candidates: List<SeriesCandidate>,
    /** Null when Metron answered. */
    val fallback: FallbackReason?,
)

/**
 * Asks Metron first, and the Grand Comics Database when Metron cannot be asked, fails or finds
 * nothing. Sends the series' name and year only.
 */
@Inject
@SingleIn(AppScope::class)
class OnlineMetadata(
    private val metron: MetronApi,
    private val gcd: GcdApi,
) {

    /** Series matching an entry's title, "Batman (2025-)" searched as "Batman" begun in 2025. */
    suspend fun search(title: String): OnlineSearch {
        val parsed = SeriesTitles.parse(title)
        val reason = if (metron.available) {
            try {
                val found = searchIn(metron, parsed.name, parsed.startYear)
                if (found.isNotEmpty()) return OnlineSearch(parsed.name, parsed.startYear, found, null)
                FallbackReason.NOT_FOUND
            } catch (e: IOException) {
                logcat(LogPriority.WARN, e) { "Metron search failed, asking the GCD" }
                when (e) {
                    is ComicDatabaseException.Unauthorized, is ComicDatabaseException.NoToken -> FallbackReason.REFUSED
                    is ComicDatabaseException.LimitReached -> FallbackReason.LIMIT_REACHED
                    else -> FallbackReason.FAILED
                }
            }
        } else {
            FallbackReason.NO_TOKEN
        }
        return OnlineSearch(parsed.name, parsed.startYear, searchIn(gcd, parsed.name, parsed.startYear), reason)
    }

    suspend fun suggestions(
        candidate: SeriesCandidate,
        chapters: List<ChapterName>,
        labels: Map<Long, String>,
        onProgress: (Int, Int) -> Unit,
    ): OnlineSuggestions {
        val database = if (candidate.origin == MetadataOrigin.METRON) metron else gcd
        return database.suggestions(candidate, chapters, labels, onProgress)
    }

    /** By name and year; by name alone when the year finds nothing. */
    private suspend fun searchIn(database: ComicDatabase, name: String, year: Int?): List<SeriesCandidate> =
        database.search(name, year).ifEmpty {
            if (year != null) database.search(name, null) else emptyList()
        }
}
