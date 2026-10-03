package tachiyomi.domain.start

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A series from the catalogue, to be found in the reader's sources. [key]: "metron:series:1234". */
data class CatalogSeries(val key: String, val name: String, val year: Int?)

/** One result of a search in a source. */
data class SourceHit(val title: String, val url: String)

/** Why a source gave no answer. */
enum class FailureKind { CLOUDFLARE, SITE_DOWN, HTTP, TIMEOUT, NETWORK, NOT_INSTALLED, OTHER }

/** What a source answered to a search. */
sealed interface SearchResult {
    data class Hits(val hits: List<SourceHit>) : SearchResult
    data class Failure(val kind: FailureKind, val detail: String?) : SearchResult
}

fun interface SourceSearch {
    suspend fun search(sourceId: Long, query: String): SearchResult
}

/** What one source said about one series. */
sealed interface Attempt {
    val sourceId: Long

    data class Found(override val sourceId: Long, val hit: SourceHit) : Attempt
    data class NoResults(override val sourceId: Long) : Attempt

    /** Results, none of them this series; [first] is the source's own first result. */
    data class NoMatch(override val sourceId: Long, val results: Int, val first: String) : Attempt
    data class Failed(override val sourceId: Long, val kind: FailureKind, val detail: String?) : Attempt

    /** Not asked: it failed a moment ago, in this session. */
    data class Skipped(override val sourceId: Long, val kind: FailureKind) : Attempt
}

/**
 * Where a catalogue series was found, or that it was not, as the cache keeps it. [sourceId] null:
 * not found in any of [triedSources]. [mangaId] is the entry made for it outside the library.
 */
data class SourceMatch(
    val catalogKey: String,
    val sourceId: Long?,
    val mangaUrl: String?,
    val mangaId: Long?,
    val matchedTitle: String?,
    val triedSources: List<Long>,
    val checkedAt: Long,
    val expiresAt: Long,
) {
    /**
     * Still the answer for this order of sources: not expired, and asked of the same sources in
     * the same order — up to where it was found, or all of them when it was not.
     */
    fun validFor(order: List<Long>, nowMs: Long): Boolean {
        if (nowMs >= expiresAt) return false
        if (sourceId == null) return order == triedSources
        val at = order.indexOf(sourceId)
        return at >= 0 && order.subList(0, at + 1) == triedSources
    }
}

interface SourceMatchRepository {
    suspend fun get(catalogKey: String): SourceMatch?
    suspend fun put(match: SourceMatch)
}

data class Resolution(
    val status: Status,
    val match: SourceMatch?,
    val attempts: List<Attempt>,
    val fromCache: Boolean,
) {
    enum class Status {
        FOUND,
        NOT_FOUND,

        /** Not found where it was asked, and some source failed: nothing can be said. */
        FAILED,
        NO_SOURCES,

        /** Over the searches allowed in a session; asked again in the next one. */
        LIMITED,
    }
}

/**
 * Finds a catalogue series in the reader's sources: the primary first, the others in their order
 * only when it does not have it or fails (docs/HOME_PLAN.md §7.3, H4).
 *
 * A source is searched by the series' name; when it lists results and none is the series, and the
 * year is known, by the name with the year as sources title it ([withYear], H12).
 *
 * One series at a time, in a queue. The answer is kept: found for [FOUND_DAYS], not found for
 * [NOT_FOUND_DAYS]; found only after an earlier source failed for [RETRY_DAYS], so the primary is
 * asked again soon; nothing kept when a failure leaves the answer unknown. A source that fails is
 * not asked again for [SKIP_FAILED_MS], so one down source does not cost every series its timeout.
 * At most [maxSearches] series reach the network in a session ([resolve]); [test] asks every source
 * afresh, for the reader checking the list by hand, and neither reads nor keeps the cache.
 */
class SourceResolver(
    private val searcher: SourceSearch,
    private val cache: SourceMatchRepository,
    private val maxSearches: Int,
    private val clock: () -> Long,
) {

    private val queue = Mutex()
    private val failedAt = HashMap<Long, Pair<FailureKind, Long>>()
    private var searches = 0

    suspend fun resolve(series: CatalogSeries, order: List<Long>): Resolution =
        queue.withLock { resolveLocked(series, order, limited = true) }

    /** As [resolve], for a search the reader asked for by a tap: the session's limit neither applies nor counts. */
    suspend fun resolveByHand(series: CatalogSeries, order: List<Long>): Resolution =
        queue.withLock { resolveLocked(series, order, limited = false) }

    private suspend fun resolveLocked(series: CatalogSeries, order: List<Long>, limited: Boolean): Resolution {
        if (order.isEmpty()) return Resolution(Resolution.Status.NO_SOURCES, null, emptyList(), false)
        val now = clock()
        val cached = cache.get(series.key)
        if (cached != null && cached.validFor(order, now)) {
            val status = if (cached.sourceId != null) Resolution.Status.FOUND else Resolution.Status.NOT_FOUND
            return Resolution(status, cached, emptyList(), fromCache = true)
        }
        if (limited) {
            if (searches >= maxSearches) return Resolution(Resolution.Status.LIMITED, null, emptyList(), false)
            searches++
        }
        val resolution = ask(series, order, now, skipFailed = true)
        resolution.match?.let { cache.put(it) }
        return resolution
    }

    suspend fun test(series: CatalogSeries, order: List<Long>): Resolution = queue.withLock {
        if (order.isEmpty()) return@withLock Resolution(Resolution.Status.NO_SOURCES, null, emptyList(), false)
        ask(series, order, clock(), skipFailed = false)
    }

    private suspend fun ask(series: CatalogSeries, order: List<Long>, now: Long, skipFailed: Boolean): Resolution {
        val attempts = mutableListOf<Attempt>()
        for (sourceId in order) {
            val attempt = askOne(series, sourceId, now, skipFailed)
            attempts += attempt
            if (attempt is Attempt.Found) {
                val clean = attempts.dropLast(1).none { it is Attempt.Failed || it is Attempt.Skipped }
                val days = if (clean) FOUND_DAYS else RETRY_DAYS
                val match = SourceMatch(
                    catalogKey = series.key,
                    sourceId = sourceId,
                    mangaUrl = attempt.hit.url,
                    mangaId = null,
                    matchedTitle = attempt.hit.title,
                    triedSources = order.subList(0, attempts.size),
                    checkedAt = now,
                    expiresAt = now + days * DAY_MS,
                )
                return Resolution(Resolution.Status.FOUND, match, attempts, fromCache = false)
            }
        }
        if (attempts.any { it is Attempt.Failed || it is Attempt.Skipped }) {
            return Resolution(Resolution.Status.FAILED, null, attempts, fromCache = false)
        }
        val match = SourceMatch(
            catalogKey = series.key,
            sourceId = null,
            mangaUrl = null,
            mangaId = null,
            matchedTitle = null,
            triedSources = order,
            checkedAt = now,
            expiresAt = now + NOT_FOUND_DAYS * DAY_MS,
        )
        return Resolution(Resolution.Status.NOT_FOUND, match, attempts, fromCache = false)
    }

    private suspend fun askOne(series: CatalogSeries, sourceId: Long, now: Long, skipFailed: Boolean): Attempt {
        val failed = failedAt[sourceId]
        if (skipFailed && failed != null && now - failed.second < SKIP_FAILED_MS) {
            return Attempt.Skipped(sourceId, failed.first)
        }
        val byName = searchOnce(series, sourceId, series.name)
        if (byName !is Attempt.NoMatch || series.year == null) return byName
        // A name many series share ("Batman") fills the results with the others; the sources title a
        // series with its year, "Batman (2016)" or "Batman (2016-)", and searched that way list it (H12).
        return when (val byYear = searchOnce(series, sourceId, withYear(series.name, series.year))) {
            is Attempt.Found, is Attempt.Failed -> byYear
            else -> byName
        }
    }

    private suspend fun searchOnce(series: CatalogSeries, sourceId: Long, query: String): Attempt =
        when (val result = searcher.search(sourceId, query)) {
            is SearchResult.Failure -> {
                failedAt[sourceId] = result.kind to clock()
                Attempt.Failed(sourceId, result.kind, result.detail)
            }
            is SearchResult.Hits -> {
                failedAt.remove(sourceId)
                val hits = result.hits
                val hit = TitleMatch.best(series.name, series.year, hits) { it.title }
                when {
                    hit != null -> Attempt.Found(sourceId, hit)
                    hits.isEmpty() -> Attempt.NoResults(sourceId)
                    else -> Attempt.NoMatch(sourceId, hits.size, hits.first().title)
                }
            }
        }

    companion object {
        /**
         * The second search, for a name the first could not tell apart: "Batman (2016", left open so it
         * finds the titles "Batman (2016)" and "Batman (2016-)" alike.
         */
        fun withYear(name: String, year: Int) = "$name ($year"

        const val FOUND_DAYS = 7L
        const val NOT_FOUND_DAYS = 1L
        const val RETRY_DAYS = 1L
        const val SKIP_FAILED_MS = 30 * 60_000L

        private const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
