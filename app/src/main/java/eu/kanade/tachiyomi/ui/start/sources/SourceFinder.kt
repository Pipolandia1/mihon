package eu.kanade.tachiyomi.ui.start.sources

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.interceptor.CloudflareInterceptor
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.start.StartConfig
import eu.kanade.tachiyomi.ui.start.StartPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
import mihon.domain.manga.model.toDomainManga
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.start.Attempt
import tachiyomi.domain.start.CatalogSeries
import tachiyomi.domain.start.FailureKind
import tachiyomi.domain.start.Resolution
import tachiyomi.domain.start.SearchResult
import tachiyomi.domain.start.SourceHit
import tachiyomi.domain.start.SourceMatchRepository
import tachiyomi.domain.start.SourceResolver
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * A recommended series found in a source, as the entry the Início opens: outside the library, or
 * [inLibrary] when the reader already has that very entry.
 */
data class FoundSeries(
    val mangaId: Long,
    val sourceId: Long,
    val sourceName: String,
    val title: String,
    val cover: MangaCover,
    val inLibrary: Boolean,
)

/**
 * Looks for the Início's recommendations from outside the library in the reader's sources, the
 * primary first (docs/HOME_PLAN.md §7.3, H4): the [SourceResolver] on Mihon's sources.
 *
 * A search sends the series name to the site, as a search by hand would. What is found becomes an
 * entry outside the library through [NetworkToLocalManga], as when a work is opened from a search;
 * nothing is ever added to the library.
 */
@Inject
@SingleIn(AppScope::class)
class SourceFinder(
    private val sourceManager: SourceManager,
    private val networkToLocalManga: NetworkToLocalManga,
    private val getManga: GetManga,
    private val matches: SourceMatchRepository,
    private val preferences: StartPreferences,
) {

    private val resolver = SourceResolver(
        searcher = { sourceId, query -> search(sourceId, query) },
        cache = matches,
        maxSearches = StartConfig.SOURCE_SEARCHES_PER_SESSION,
        clock = { System.currentTimeMillis() },
    )

    /** The latest results, by source and URL, to make an entry of the one chosen with its cover. */
    private val recent = object : LinkedHashMap<Pair<Long, String>, SManga>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Long, String>, SManga>?) =
            size > RECENT_RESULTS
    }

    /**
     * Where [series] is, as an entry to open; null when the search is off, it is in none of the
     * sources, or that is not known yet (a source failed, or the session's searches ran out).
     */
    suspend fun find(series: CatalogSeries): FoundSeries? {
        if (!preferences.searchSources.get()) return null
        return found(resolver.resolve(series, preferences.recommendationSources.get()), series)
    }

    /**
     * Where [series] is, for the reader who tapped it (a release outside the library, H6): the switch
     * and the session's limit are for what the Início does on its own, not for this.
     */
    suspend fun findByHand(series: CatalogSeries): FoundSeries? =
        found(resolver.resolveByHand(series, preferences.recommendationSources.get()), series)

    private suspend fun found(resolution: Resolution, series: CatalogSeries): FoundSeries? {
        log(series, resolution)
        val match = resolution.match?.takeIf { resolution.status == Resolution.Status.FOUND } ?: return null
        val sourceId = match.sourceId ?: return null
        val manga = match.mangaId?.let { getManga.await(it) }
            ?: entry(sourceId, match.mangaUrl.orEmpty(), match.matchedTitle.orEmpty())
                .also { matches.put(match.copy(mangaId = it.id)) }
        return FoundSeries(manga.id, sourceId, sourceName(sourceId), manga.title, manga.asMangaCover(), manga.favorite)
    }

    /** "Testar uma busca": every source in the list asked afresh, whatever the switch; nothing kept. */
    suspend fun test(series: CatalogSeries): Resolution {
        val resolution = resolver.test(series, preferences.recommendationSources.get())
        log(series, resolution)
        return resolution
    }

    /** The entry outside the library for what a source found, made when it is first opened. */
    suspend fun entryFor(found: Attempt.Found): Long = entry(found.sourceId, found.hit.url, found.hit.title).id

    suspend fun sourceName(sourceId: Long): String = sourceManager.get(sourceId)?.name ?: sourceId.toString()

    private suspend fun entry(sourceId: Long, url: String, title: String): Manga {
        val result = synchronized(recent) { recent[sourceId to url] }
        val manga = result?.toDomainManga(sourceId) ?: Manga.create().copy(source = sourceId, url = url, title = title)
        return networkToLocalManga(manga.copy(favorite = false))
    }

    private suspend fun search(sourceId: Long, query: String): SearchResult {
        val source = sourceManager.get(sourceId) as? CatalogueSource
            ?: return SearchResult.Failure(FailureKind.NOT_INSTALLED, null)
        return try {
            val page = withTimeout(StartConfig.SOURCE_SEARCH_TIMEOUT_MS) {
                withIOContext { source.getSearchManga(1, query, source.getFilterList()) }
            }
            synchronized(recent) { page.mangas.forEach { recent[sourceId to it.url] = it } }
            SearchResult.Hits(page.mangas.map { SourceHit(it.title, it.url) })
        } catch (e: TimeoutCancellationException) {
            SearchResult.Failure(FailureKind.TIMEOUT, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SearchResult.Failure(kindOf(source, e), e.message)
        }
    }

    private suspend fun kindOf(source: CatalogueSource, error: Throwable): FailureKind {
        val chain = generateSequence(error) { it.cause }.toList()
        val code = chain.firstNotNullOfOrNull { it as? HttpException }?.code
        return when {
            // The interceptor's own exception is private to it; its name is what reaches here.
            chain.any { it.javaClass.simpleName == "CloudflareBypassException" } -> FailureKind.CLOUDFLARE
            code in CHALLENGE_CODES && isChallenged(source) -> FailureKind.CLOUDFLARE
            code in ORIGIN_DOWN_CODES -> FailureKind.SITE_DOWN
            code != null -> FailureKind.HTTP
            chain.any { it is SocketTimeoutException } -> FailureKind.TIMEOUT
            chain.any { it is IOException } -> FailureKind.NETWORK
            else -> FailureKind.OTHER
        }
    }

    /**
     * Whether the source's site answers with Cloudflare's challenge. A search refused with 403 can
     * come back from the challenge after the WebView tried, as a plain HTTP error; one request to
     * the site's address, without the interceptor that would open the WebView again, tells.
     */
    private suspend fun isChallenged(source: CatalogueSource): Boolean {
        val http = source as? HttpSource ?: return false
        val client = http.client.newBuilder()
            .apply { interceptors().removeAll { it is CloudflareInterceptor } }
            .callTimeout(StartConfig.CHALLENGE_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
        return try {
            withIOContext {
                client.newCall(GET(http.baseUrl, http.headers)).await().use { it.header("cf-mitigated") == "challenge" }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /** "StartFeed source …": what each source answered, so a missing recommendation can be explained. */
    private suspend fun log(series: CatalogSeries, resolution: Resolution) {
        val names = resolution.attempts.map { sourceName(it.sourceId) }
        logcat(LogPriority.INFO) {
            val asked = resolution.attempts.zip(names).joinToString("; ") { (attempt, name) ->
                "$name: ${describe(attempt)}"
            }
            val cached = if (resolution.fromCache) " (cache)" else ""
            "StartFeed source \"${series.name}\" (${series.year}) ${resolution.status}$cached $asked"
        }
    }

    private fun describe(attempt: Attempt) = when (attempt) {
        is Attempt.Found -> "found \"${attempt.hit.title}\""
        is Attempt.NoResults -> "no results"
        is Attempt.NoMatch -> "${attempt.results} results, none this series (first \"${attempt.first}\")"
        is Attempt.Failed -> "failed ${attempt.kind}${attempt.detail?.let { " ($it)" }.orEmpty()}"
        is Attempt.Skipped -> "skipped, failed ${attempt.kind} a moment ago"
    }

    private companion object {
        const val RECENT_RESULTS = 200

        /** What Cloudflare's challenge answers with. */
        val CHALLENGE_CODES = setOf(403, 429, 503)

        /** Cloudflare's own codes for a site behind it that did not answer. */
        val ORIGIN_DOWN_CODES = 520..527
    }
}
