package eu.kanade.tachiyomi.readingstats.comics.online.metron

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.parseAs
import eu.kanade.tachiyomi.readingstats.comics.online.ComicDatabase
import eu.kanade.tachiyomi.readingstats.comics.online.ComicDatabaseException
import eu.kanade.tachiyomi.readingstats.comics.online.IssueMatcher
import eu.kanade.tachiyomi.readingstats.comics.online.IssueRef
import eu.kanade.tachiyomi.readingstats.comics.online.OnlineSuggestions
import eu.kanade.tachiyomi.readingstats.comics.online.RequestLimiter
import eu.kanade.tachiyomi.readingstats.comics.online.RequestPolicy
import eu.kanade.tachiyomi.readingstats.comics.online.SeriesCandidate
import eu.kanade.tachiyomi.readingstats.comics.online.limitedGet
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.readingstats.comics.ChapterName
import tachiyomi.domain.readingstats.comics.MetadataOrigin
import tachiyomi.domain.readingstats.service.ReadingStatsPreferences
import java.io.File

/**
 * Metron (metron.cloud), with the user's own token. Requests go out one at a time within Metron's
 * limits, and only when the user asks: nothing here runs in the background.
 */
@Inject
@SingleIn(AppScope::class)
class MetronApi(
    context: Context,
    private val network: NetworkHelper,
    private val json: Json,
    private val preferences: ReadingStatsPreferences,
) : ComicDatabase {

    private val policy = RequestPolicy(
        limiter = RequestLimiter(MetronConfig.REQUESTS_PER_MINUTE, MetronConfig.WINDOW_MS),
        defaultRetryAfterS = MetronConfig.DEFAULT_RETRY_AFTER_S,
        maxWaitS = MetronConfig.MAX_WAIT_S,
        retries = MetronConfig.MAX_RETRIES,
    )
    private val cacheDir = File(context.cacheDir, MetronConfig.CACHE_DIR)

    override val origin = MetadataOrigin.METRON

    override val available: Boolean get() = preferences.metronToken.get().isNotBlank()

    override suspend fun search(name: String, year: Int?): List<SeriesCandidate> {
        val url = "${MetronConfig.BASE_URL}series/".toHttpUrl().newBuilder()
            .addQueryParameter("name", name)
            .apply { if (year != null) addQueryParameter("year_began", year.toString()) }
            .build()
        return get<MetronPage<MetronSeriesSummary>>(url.toString()).results
            .take(MetronConfig.SEARCH_RESULTS)
            .map {
                SeriesCandidate(
                    origin = origin,
                    id = it.id,
                    name = it.displayName,
                    yearBegan = it.yearBegan,
                    yearEnd = it.yearEnd,
                    publisher = it.publisher?.name,
                    format = it.seriesType?.name,
                    issueCount = it.issueCount,
                )
            }
    }

    override suspend fun suggestions(
        candidate: SeriesCandidate,
        chapters: List<ChapterName>,
        labels: Map<Long, String>,
        onProgress: (Int, Int) -> Unit,
    ): OnlineSuggestions {
        val series = get<MetronSeries>("${MetronConfig.BASE_URL}series/${candidate.id}/")
        val summaries = issues(candidate.id).associateBy { it.id }
        val matched = IssueMatcher.match(chapters, labels, summaries.values.map { IssueRef(it.id, it.number) })
        val values = MetronMapping.seriesValues(series).toMutableList()
        var fetched = 0
        onProgress(0, matched.size)
        try {
            matched.forEach { (chapterId, ref) ->
                values += MetronMapping.issueValues(issue(summaries.getValue(ref.id)), chapterId)
                onProgress(++fetched, matched.size)
            }
        } catch (e: ComicDatabaseException.LimitReached) {
            logcat(LogPriority.WARN, e) { "Metron stopped after $fetched of ${matched.size} issues" }
        }
        return OnlineSuggestions(values, matched.size, fetched)
    }

    /** Every issue of a series, page after page. */
    private suspend fun issues(seriesId: Long): List<MetronIssueSummary> {
        val issues = mutableListOf<MetronIssueSummary>()
        var url: String? = "${MetronConfig.BASE_URL}series/$seriesId/issue_list/"
        while (url != null) {
            val page = get<MetronPage<MetronIssueSummary>>(url)
            issues += page.results
            url = page.next
        }
        return issues
    }

    /** An issue's details, from the disk cache while Metron says it has not changed. */
    private suspend fun issue(summary: MetronIssueSummary): MetronIssue {
        val file = File(cacheDir, "issue_${summary.id}.json")
        val cached = runCatching { json.decodeFromString<MetronIssue>(file.readText()) }.getOrNull()
        if (cached != null && cached.modified == summary.modified) return cached
        val issue = get<MetronIssue>("${MetronConfig.BASE_URL}issue/${summary.id}/")
        runCatching {
            cacheDir.mkdirs()
            file.writeText(json.encodeToString(MetronIssue.serializer(), issue))
        }.onFailure { logcat(LogPriority.WARN, it) { "Could not cache Metron issue ${summary.id}" } }
        return issue
    }

    private suspend inline fun <reified T> get(url: String): T {
        val token = preferences.metronToken.get().trim()
        if (token.isEmpty()) throw ComicDatabaseException.NoToken()
        val headers = Headers.Builder()
            .add("Authorization", "Bearer $token")
            .add("Accept", "application/json")
            .add("User-Agent", "Mihon/${BuildConfig.VERSION_NAME}")
            .build()
        val response = network.client.limitedGet(url, headers, policy)
        return with(json) { response.use { it.parseAs<T>() } }
    }
}
