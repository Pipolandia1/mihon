package eu.kanade.tachiyomi.ui.start.catalog

import android.os.SystemClock
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.readingstats.comics.online.metron.MetronApi
import eu.kanade.tachiyomi.readingstats.comics.online.metron.MetronIssueListItem
import eu.kanade.tachiyomi.readingstats.comics.online.metron.MetronItem
import eu.kanade.tachiyomi.readingstats.comics.online.metron.MetronNamed
import eu.kanade.tachiyomi.readingstats.comics.online.metron.MetronSeries
import eu.kanade.tachiyomi.ui.start.StartConfig
import eu.kanade.tachiyomi.ui.start.StartPreferences
import eu.kanade.tachiyomi.ui.start.sources.SourceFinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.start.Affinity
import tachiyomi.domain.start.CatalogAnswer
import tachiyomi.domain.start.CatalogCache
import tachiyomi.domain.start.CatalogIssue
import tachiyomi.domain.start.CatalogName
import tachiyomi.domain.start.CatalogPick
import tachiyomi.domain.start.CatalogSeries
import tachiyomi.domain.start.CatalogSeriesDetail
import tachiyomi.domain.start.CatalogSeriesRef
import tachiyomi.domain.start.Discovery
import tachiyomi.domain.start.EntityAsk
import tachiyomi.domain.start.EntityKind
import tachiyomi.domain.start.Feedback
import tachiyomi.domain.start.OutsideWork
import tachiyomi.domain.start.WorkFacts
import java.time.LocalDate

/**
 * The Início's discovery beyond the library (docs/HOME_PLAN.md §7.5, H5): asks Metron, with the
 * reader's token, for the series of the writers, artists, characters and teams they read most, and
 * looks for the best of them in the reader's sources ([SourceFinder], H4).
 *
 * Metron sees only those names and ids, never the library. Its answers are kept on the device —
 * a name's id and a series' details for [StartConfig.CATALOG_NAME_DAYS], an entity's series for
 * [StartConfig.CATALOG_SERIES_DAYS] — so the Início asks a few dozen times a week at most.
 */
@Inject
@SingleIn(AppScope::class)
class CatalogDiscovery(
    private val metron: MetronApi,
    private val cache: CatalogCache,
    private val finder: SourceFinder,
    private val preferences: StartPreferences,
    private val json: Json,
) {

    sealed interface State {
        /** Not asked yet. */
        data object Idle : State

        /** The switch is off: the Início recommends only from the library. */
        data object Off : State

        /** No Metron token: nothing to ask the catalogue with. */
        data object NoToken : State

        /** Series worth looking for, and no source to look in yet. */
        data class NeedsPrimary(val series: Int) : State

        data class Ready(val works: List<OutsideWork>) : State
    }

    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState.asStateFlow()

    /** What the catalogue listed for each name asked about, for the releases' reasons (H6). */
    private val mutableAnswers = MutableStateFlow<List<CatalogAnswer>>(emptyList())
    val answers: StateFlow<List<CatalogAnswer>> = mutableAnswers.asStateFlow()

    private val queue = Mutex()

    /** Asks again, for the reader as [works] and [feedback] describe them. */
    suspend fun refresh(works: List<WorkFacts>, feedback: List<Feedback>) = queue.withLock {
        if (!preferences.searchSources.get()) return@withLock set(State.Off)
        if (!metron.available) return@withLock set(State.NoToken)
        val started = SystemClock.elapsedRealtime()
        val now = System.currentTimeMillis()
        val model = Affinity.model(works, now)
        val asked = Discovery.entitiesToAsk(model, feedback, now)
        val answers = asked.mapNotNull { ask -> answer(ask, now) }
        mutableAnswers.value = answers
        val picks = Discovery.picks(answers, model, works)
            .take(StartConfig.CATALOG_DETAILS)
            .mapNotNull { pick -> detailed(pick, now) }
            .take(StartConfig.SOURCE_SEARCHES_PER_SESSION)
        if (preferences.recommendationSources.get().isEmpty()) {
            log(started, asked, answers, picks.size, found = null)
            return@withLock set(if (picks.isEmpty()) State.Ready(emptyList()) else State.NeedsPrimary(picks.size))
        }
        val found = picks.mapNotNull { (pick, detail) -> outside(pick, detail, works) }
        log(started, asked, answers, picks.size, found.size)
        set(State.Ready(found))
    }

    private fun set(state: State) {
        mutableState.value = state
    }

    /** What the catalogue lists for [ask]: its series since [StartConfig.CATALOG_YEARS] ago. */
    private suspend fun answer(ask: EntityAsk, now: Long): CatalogAnswer? {
        val named = named(ask.key.kind) ?: return null
        return try {
            val id = idOf(named, ask, now) ?: return null
            val fromYear = LocalDate.now().year - StartConfig.CATALOG_YEARS
            val key = "metron:${named.path}:$id:series:$fromYear"
            val series = cache.get(key, now)?.let { json.decodeFromString<List<CatalogSeriesRef>>(it) }
                ?: fetchSeries(named, id, fromYear, ask).also {
                    cache.put(key, json.encodeToString(it), now, StartConfig.CATALOG_SERIES_DAYS * DAY_MS)
                }
            CatalogAnswer(ask.key, series)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "StartFeed catalog could not ask about ${ask.name}" }
            null
        }
    }

    private suspend fun idOf(named: MetronNamed, ask: EntityAsk, now: Long): Long? {
        val key = "metron:${named.path}:name:${ask.key.name}"
        cache.get(key, now)?.let { return it.toLongOrNull() }
        val id = Discovery.exactId(catalogNames(metron.names(named, ask.name)), ask.name)
        // "Not in Metron by this name" is kept too, for less long: a name may be added.
        val keep = if (id != null) StartConfig.CATALOG_NAME_DAYS else StartConfig.CATALOG_SERIES_DAYS
        cache.put(key, id?.toString().orEmpty(), now, keep * DAY_MS)
        return id
    }

    private suspend fun fetchSeries(
        named: MetronNamed,
        id: Long,
        fromYear: Int,
        ask: EntityAsk,
    ): List<CatalogSeriesRef> {
        val (issues, count) = metron.issuesOf(named, id, "$fromYear-01-01", StartConfig.CATALOG_PAGES)
        // No silent cap: a list cut short is said.
        if (issues.size < count) {
            logcat(LogPriority.INFO) {
                "StartFeed catalog ${ask.name}: ${issues.size} of $count issues since $fromYear"
            }
        }
        return Discovery.seriesOf(catalogIssues(issues))
    }

    /** [pick] with the catalogue's details of its series; null when it is a collected edition. */
    private suspend fun detailed(pick: CatalogPick, now: Long): Pair<CatalogPick, CatalogSeriesDetail?>? {
        val key = "metron:series:${pick.series.id}"
        val detail = try {
            cache.get(key, now)?.let { json.decodeFromString<CatalogSeriesDetail>(it) }
                ?: seriesDetail(metron.series(pick.series.id)).also {
                    cache.put(key, json.encodeToString(it), now, StartConfig.CATALOG_NAME_DAYS * DAY_MS)
                }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Without its details the series is still worth looking for; its format just stays unknown.
            logcat(LogPriority.WARN, e) { "StartFeed catalog no details for ${pick.series.name}" }
            null
        }
        if (detail != null && Discovery.isCollected(detail)) return null
        return pick to detail
    }

    /** [pick] as found in a source, unless it is in none, or it is an entry the reader has or read. */
    private suspend fun outside(pick: CatalogPick, detail: CatalogSeriesDetail?, works: List<WorkFacts>): OutsideWork? {
        val series = pick.series
        val found = finder.find(CatalogSeries("metron:series:${series.id}", series.name, series.yearBegan))
            ?: return null
        val own = works.any { it.mangaId == found.mangaId && (it.inLibrary || it.chaptersRead > 0) }
        if (found.inLibrary || own) return null
        return OutsideWork(
            mangaId = found.mangaId,
            title = found.title,
            cover = found.cover,
            sourceName = found.sourceName,
            pick = pick,
            total = detail?.issueCount,
            finished = detail?.finished ?: false,
        )
    }

    private fun log(started: Long, asked: List<EntityAsk>, answers: List<CatalogAnswer>, picks: Int, found: Int?) {
        logcat(LogPriority.INFO) {
            val names = asked.joinToString { it.name }
            val series = answers.sumOf { it.series.size }
            val result = found?.let { "found $it in the sources" } ?: "no source chosen"
            "StartFeed catalog asked [$names], $series series, $picks picks, $result " +
                "in ${SystemClock.elapsedRealtime() - started}ms"
        }
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}

/** What Metron is asked by for an entity of this kind; genres and publishers are not. */
internal fun named(kind: EntityKind): MetronNamed? = when (kind) {
    EntityKind.WRITER, EntityKind.ARTIST -> MetronNamed.CREATOR
    EntityKind.CHARACTER -> MetronNamed.CHARACTER
    EntityKind.TEAM -> MetronNamed.TEAM
    else -> null
}

internal fun catalogNames(items: List<MetronItem>) = items.map { CatalogName(it.id, it.name) }

internal fun catalogIssues(items: List<MetronIssueListItem>) = items.mapNotNull { item ->
    val series = item.series ?: return@mapNotNull null
    CatalogIssue(series.id, series.name, series.volume, series.yearBegan)
}

/** A series' details as the Início keeps them: its length, whether it ended, its format. */
internal fun seriesDetail(series: MetronSeries) = CatalogSeriesDetail(
    id = series.id,
    issueCount = series.issueCount,
    finished = series.status.lowercase() in FINISHED_STATUSES,
    seriesType = series.seriesType?.name,
)

private val FINISHED_STATUSES = setOf("completed", "cancelled")
