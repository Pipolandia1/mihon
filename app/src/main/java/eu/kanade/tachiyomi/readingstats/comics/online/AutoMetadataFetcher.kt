package eu.kanade.tachiyomi.readingstats.comics.online

import android.os.SystemClock
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.readingstats.comics.online.metron.MetronApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.readingstats.comics.AutoMetadata
import tachiyomi.domain.readingstats.comics.AutoPick
import tachiyomi.domain.readingstats.comics.AutoState
import tachiyomi.domain.readingstats.comics.AutoStatus
import tachiyomi.domain.readingstats.comics.AutoWork
import tachiyomi.domain.readingstats.comics.CatalogCandidate
import tachiyomi.domain.readingstats.comics.ChapterName
import tachiyomi.domain.readingstats.comics.ComicField
import tachiyomi.domain.readingstats.comics.MetadataOrigin
import tachiyomi.domain.readingstats.comics.SeriesTitles
import tachiyomi.domain.readingstats.repository.AutoMetadataRepository
import tachiyomi.domain.readingstats.repository.ComicMetadataRepository
import tachiyomi.domain.readingstats.service.ReadingStatsPreferences
import tachiyomi.domain.start.CatalogSeriesDetail
import tachiyomi.domain.start.Discovery
import tachiyomi.domain.start.StartRepository

/**
 * Comic metadata without a tap (docs/HOME_PLAN.md §14, H13): every work in the library or read gets
 * its series' and issues' values from Metron, as "Buscar online" and "Aceitar" would give them — the
 * series chosen by [AutoMetadata.pick], confirmed only when certain, and only where nothing is
 * confirmed. Metron only, with the reader's token; the GCD stays a search by hand.
 *
 * One run at a time; up to [AutoMetadata.PER_RUN] works, the most recent first; a run stops when
 * Metron's allowance is spent, and what it did not reach is the next run's.
 */
@Inject
@SingleIn(AppScope::class)
class AutoMetadataFetcher(
    private val metron: MetronApi,
    private val metadata: ComicMetadataRepository,
    private val states: AutoMetadataRepository,
    private val startRepository: StartRepository,
    private val getChapters: GetChaptersByMangaId,
    private val preferences: ReadingStatsPreferences,
) {

    data class Summary(val confirmed: Int, val suggested: Int, val notFound: Int, val left: Int)

    private val queue = Mutex()

    /** Whether a run can ask anything: the switch is on and there is a Metron token. */
    val enabled: Boolean get() = preferences.autoMetadata.get() && metron.available

    /** One run over the works due; null when it is off. */
    suspend fun run(): Summary? = queue.withLock {
        if (!enabled) {
            logcat(LogPriority.INFO) { "ComicAuto: off (switch or Metron token)" }
            return@withLock null
        }
        val started = SystemClock.elapsedRealtime()
        val now = System.currentTimeMillis()
        val works = startRepository.subscribeWorks().first()
            .filter { it.inLibrary || it.chaptersRead > 0 || it.sessions > 0 }
            .map { AutoWork(it.mangaId, it.title, it.chapters, it.lastReadAt, it.dateAdded) }
        val kept = states.all().associateBy { it.mangaId }
        val todo = AutoMetadata.todo(works, kept, now)
        val done = mutableMapOf<AutoStatus, Int>()
        for (work in todo.take(AutoMetadata.PER_RUN)) {
            try {
                val (status, cutShort) = one(work, kept[work.mangaId], now)
                done.merge(status, 1, Int::plus)
                logcat(LogPriority.INFO) { "ComicAuto: \"${work.title}\" $status" }
                if (cutShort) break
            } catch (e: CancellationException) {
                throw e
            } catch (e: ComicDatabaseException) {
                // The allowance spent, or the token refused: nothing more can be asked in this run.
                logcat(LogPriority.WARN, e) { "ComicAuto: Metron stopped the run at \"${work.title}\"" }
                break
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "ComicAuto: \"${work.title}\" failed" }
            }
        }
        val summary = Summary(
            confirmed = done[AutoStatus.CONFIRMED] ?: 0,
            suggested = done[AutoStatus.SUGGESTED] ?: 0,
            notFound = done[AutoStatus.NOT_FOUND] ?: 0,
            left = (todo.size - done.values.sum()).coerceAtLeast(0),
        )
        preferences.autoMetadataLastRunAt.set(now)
        logcat(LogPriority.INFO) {
            "ComicAuto: ${todo.size} due -> $summary in ${SystemClock.elapsedRealtime() - started}ms"
        }
        summary
    }

    /** What was done for [work], and whether Metron's allowance ran out on it. */
    private suspend fun one(work: AutoWork, state: AutoState?, now: Long): Pair<AutoStatus, Boolean> {
        val stored = metadata.get(work.mangaId)
        val confirmedSeries = stored.series(ComicField.METRON_ID)?.values?.firstOrNull()?.toLongOrNull()
        val suggestedSeries = state?.seriesId?.takeIf { state.status == AutoStatus.SUGGESTED }
        // Matched to the GCD by hand: Metron's values fill what is empty only when its series is certain.
        val byHand = stored.series(ComicField.GCD_ID) != null
        val pick = when {
            confirmedSeries != null -> AutoPick(confirmedSeries, AutoStatus.CONFIRMED)
            suggestedSeries != null -> AutoPick(suggestedSeries, AutoStatus.SUGGESTED)
            else -> search(work.title)?.takeIf { !byHand || it.status == AutoStatus.CONFIRMED }
        }
        if (pick == null) {
            val status = if (byHand) AutoStatus.LEFT else AutoStatus.NOT_FOUND
            states.put(AutoState(work.mangaId, status, null, work.chapters, now))
            return status to false
        }

        val chapters = getChapters.await(work.mangaId, applyScanlatorFilter = true)
            .map { ChapterName(it.id, it.name, it.chapterNumber) }
        val labels = stored.confirmed
            .filter { it.field == ComicField.ISSUE_LABEL && it.chapterId != null && it.values.isNotEmpty() }
            .associate { it.chapterId!! to it.values.first() }
        val candidate = SeriesCandidate(MetadataOrigin.METRON, pick.seriesId, "", null, null, null, null, null)
        val found = metron.suggestions(candidate, chapters, labels) { _, _ -> }
        metadata.replaceSuggestions(work.mangaId, MetadataOrigin.METRON, found.values)
        if (pick.status == AutoStatus.CONFIRMED) {
            metadata.confirm(work.mangaId, AutoMetadata.toConfirm(metadata.get(work.mangaId), MetadataOrigin.METRON))
        }
        // Cut short by the allowance: the chapter count is not kept, so the next run finishes it.
        val counted = if (found.complete) work.chapters else state?.chapters ?: 0
        states.put(AutoState(work.mangaId, pick.status, pick.seriesId, counted, now))
        return pick.status to !found.complete
    }

    /** The work's series in Metron: by its name and year, by its name alone when that finds nothing. */
    private suspend fun search(title: String): AutoPick? {
        val parsed = SeriesTitles.parse(title)
        val found = metron.search(parsed.name, parsed.startYear)
            .ifEmpty { if (parsed.startYear != null) metron.search(parsed.name, null) else emptyList() }
        val candidates = found.map {
            CatalogCandidate(
                id = it.id,
                title = it.name,
                yearBegan = it.yearBegan,
                collected = Discovery.isCollected(CatalogSeriesDetail(it.id, it.issueCount, false, it.format)),
            )
        }
        return AutoMetadata.pick(title, candidates)
    }
}
