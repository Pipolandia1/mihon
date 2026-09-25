package eu.kanade.tachiyomi.readingstats.comics.online.gcd

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.decodeFromJsonResponse
import eu.kanade.tachiyomi.readingstats.comics.online.ComicDatabase
import eu.kanade.tachiyomi.readingstats.comics.online.ComicDatabaseException
import eu.kanade.tachiyomi.readingstats.comics.online.IssueMatcher
import eu.kanade.tachiyomi.readingstats.comics.online.OnlineSuggestions
import eu.kanade.tachiyomi.readingstats.comics.online.RequestLimiter
import eu.kanade.tachiyomi.readingstats.comics.online.RequestPolicy
import eu.kanade.tachiyomi.readingstats.comics.online.SeriesCandidate
import eu.kanade.tachiyomi.readingstats.comics.online.limitedGet
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import logcat.LogPriority
import okhttp3.Credentials
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.readingstats.comics.ChapterName
import tachiyomi.domain.readingstats.comics.MetadataOrigin
import tachiyomi.domain.readingstats.comics.MetadataValue
import tachiyomi.domain.readingstats.service.ReadingStatsPreferences
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The Grand Comics Database (comics.org), Metron's fallback. It answers without a login, 30
 * requests an hour; the user's own login raises that. Only asked when the user asks.
 */
@Inject
@SingleIn(AppScope::class)
class GcdApi(
    context: Context,
    private val network: NetworkHelper,
    private val json: Json,
    private val preferences: ReadingStatsPreferences,
) : ComicDatabase {

    private val anonymous = policy(RequestLimiter(GcdConfig.ANONYMOUS_REQUESTS, GcdConfig.ANONYMOUS_WINDOW_MS))
    private val loggedIn = policy(RequestLimiter(GcdConfig.USER_REQUESTS, GcdConfig.USER_WINDOW_MS))
    private val cacheDir = File(context.cacheDir, GcdConfig.CACHE_DIR)

    /** Series from the last searches, so picking one costs no request. */
    private val searched = ConcurrentHashMap<Long, GcdSeries>()

    override val origin = MetadataOrigin.GCD

    override val available: Boolean = true

    override suspend fun search(name: String, year: Int?): List<SeriesCandidate> {
        val url = GcdConfig.BASE_URL.toHttpUrl().newBuilder()
            .addPathSegments("series/name")
            .addPathSegment(name)
            .apply { if (year != null) addPathSegment("year").addPathSegment(year.toString()) }
            .addPathSegment("")
            .addQueryParameter("format", "json")
            .build()
        val series = get(url.toString(), GcdPage.serializer(GcdSeries.serializer())).results
        return series
            .sortedWith(
                compareByDescending<GcdSeries> { it.name.equals(name, ignoreCase = true) }
                    .thenByDescending { GcdMapping.issuesOf(it).map { issue -> issue.number }.distinct().size },
            )
            .take(GcdConfig.SEARCH_RESULTS)
            .mapNotNull { found ->
                val id = GcdMapping.idOf(found.apiUrl) ?: return@mapNotNull null
                searched[id] = found
                SeriesCandidate(
                    origin = origin,
                    id = id,
                    name = found.name,
                    yearBegan = found.yearBegan,
                    yearEnd = found.yearEnded,
                    publisher = null,
                    format = listOf(found.publishingFormat, found.binding).filter(String::isNotBlank)
                        .joinToString(" · "),
                    issueCount = GcdMapping.issuesOf(found).map { it.number }.distinct().size,
                )
            }
    }

    override suspend fun suggestions(
        candidate: SeriesCandidate,
        chapters: List<ChapterName>,
        labels: Map<Long, String>,
        onProgress: (Int, Int) -> Unit,
    ): OnlineSuggestions {
        val series = searched[candidate.id]
            ?: get("${GcdConfig.BASE_URL}series/${candidate.id}/?format=json", GcdSeries.serializer())
        val matched = IssueMatcher.match(chapters, labels, GcdMapping.issuesOf(series))
        var fetched = 0
        val values = mutableListOf<MetadataValue>()
        onProgress(0, matched.size)
        try {
            values += GcdMapping.seriesValues(series, publisherName(series.publisher))
            matched.forEach { (chapterId, ref) ->
                val url = "${GcdConfig.BASE_URL}issue/${ref.id}/?format=json"
                val issue = cached("issue_${ref.id}", url, GcdIssue.serializer())
                values += GcdMapping.issueValues(issue, chapterId)
                onProgress(++fetched, matched.size)
            }
        } catch (e: ComicDatabaseException.LimitReached) {
            logcat(LogPriority.WARN, e) { "The GCD stopped after $fetched of ${matched.size} issues" }
            if (values.isEmpty()) throw e
        }
        return OnlineSuggestions(values, matched.size, fetched)
    }

    private suspend fun publisherName(url: String?): String? {
        val id = GcdMapping.idOf(url) ?: return null
        return cached("publisher_$id", "${GcdConfig.BASE_URL}publisher/$id/?format=json", GcdPublisher.serializer())
            .name.takeIf(String::isNotBlank)
    }

    /** A record from the disk cache while it is recent enough, else from the GCD. */
    private suspend fun <T> cached(key: String, url: String, serializer: KSerializer<T>): T {
        val file = File(cacheDir, "$key.json")
        val fresh = file.exists() &&
            System.currentTimeMillis() - file.lastModified() < TimeUnit.DAYS.toMillis(GcdConfig.CACHE_DAYS)
        if (fresh) {
            runCatching { json.decodeFromString(serializer, file.readText()) }.getOrNull()?.let { return it }
        }
        val value = get(url, serializer)
        runCatching {
            cacheDir.mkdirs()
            file.writeText(json.encodeToString(serializer, value))
        }.onFailure { logcat(LogPriority.WARN, it) { "Could not cache GCD $key" } }
        return value
    }

    private suspend fun <T> get(url: String, serializer: KSerializer<T>): T {
        val username = preferences.gcdUsername.get().trim()
        val password = preferences.gcdPassword.get()
        val login = username.isNotEmpty() && password.isNotEmpty()
        val headers = Headers.Builder()
            .add("Accept", "application/json")
            .add("User-Agent", "Mihon/${BuildConfig.VERSION_NAME}")
            .apply { if (login) add("Authorization", Credentials.basic(username, password)) }
            .build()
        val response = network.client.limitedGet(url, headers, if (login) loggedIn else anonymous)
        return with(json) { response.use { decodeFromJsonResponse(serializer, it) } }
    }

    private fun policy(limiter: RequestLimiter) = RequestPolicy(
        limiter = limiter,
        defaultRetryAfterS = GcdConfig.DEFAULT_RETRY_AFTER_S,
        maxWaitS = GcdConfig.MAX_WAIT_S,
        retries = GcdConfig.MAX_RETRIES,
    )
}
