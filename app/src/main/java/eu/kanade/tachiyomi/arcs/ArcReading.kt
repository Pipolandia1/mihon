package eu.kanade.tachiyomi.arcs

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.ui.start.sources.SourceFinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import logcat.LogPriority
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.start.ArcIssue
import tachiyomi.domain.start.ArcSeries
import tachiyomi.domain.start.Arcs
import tachiyomi.domain.start.CatalogSeries
import tachiyomi.domain.start.ChapterRef
import tachiyomi.domain.start.Releases
import tachiyomi.domain.start.StartRepository
import tachiyomi.domain.start.asRelease
import java.time.LocalDate

/**
 * Where an arc's series are read from (docs/HOME_PLAN.md §13, H8). A series the reader has stays in
 * the entry they have, whatever its source (§7.1); any other is looked for in their sources, the
 * primary first (H4), and its chapters brought in as opening its page would. Nothing is added to the
 * library.
 */
@Inject
class ArcReading(
    private val startRepository: StartRepository,
    private val finder: SourceFinder,
    private val getManga: GetManga,
    private val sourceManager: SourceManager,
    private val chapters: ChapterRepository,
    private val updateManga: UpdateMangaFromRemote,
) {

    /** Each series' entry, filled series by series in the arc's order: the library's first, no search. */
    fun series(issues: List<ArcIssue>): Flow<Map<String, ArcSeries>> = flow {
        val own = startRepository.subscribeWorks().first().filter { it.inLibrary || it.chaptersRead > 0 }
        val bySeries = issues.sortedBy { it.position }.groupBy { it.seriesKey }
        val state = LinkedHashMap<String, ArcSeries>()
        bySeries.forEach { (key, inSeries) ->
            val owner = Releases.ownerOf(inSeries.first().asRelease(), own)
            state[key] =
                owner?.let { ArcSeries.Found(it.mangaId, sourceName(it.mangaId), it.inLibrary) } ?: ArcSeries.Searching
        }
        emit(state.toMap())
        bySeries.filterKeys { state[it] == ArcSeries.Searching }.forEach { (key, inSeries) ->
            state[key] = found(inSeries)
            emit(state.toMap())
        }
    }

    /** The chapters of [mangaIds], kept current: reading an issue moves the arc along. */
    fun chapters(mangaIds: Set<Long>): Flow<Map<Long, List<ChapterRef>>> {
        if (mangaIds.isEmpty()) return flowOf(emptyMap())
        val perEntry: List<Flow<Pair<Long, List<ChapterRef>>>> = mangaIds.map { id ->
            flow { emitAll(chapters.getChapterByMangaIdAsFlow(id)) }
                .map { list -> id to list.map { ChapterRef(it.id, it.chapterNumber, it.read) } }
        }
        return combine(perEntry) { pairs -> pairs.toList().toMap() }
    }

    private suspend fun found(inSeries: List<ArcIssue>): ArcSeries = try {
        val first = inSeries.first()
        val entry = finder.findByHand(CatalogSeries(first.seriesKey, first.seriesName, first.seriesYear))
        if (entry == null) {
            ArcSeries.NotFound
        } else {
            bringChapters(entry.mangaId, inSeries)
            ArcSeries.Found(entry.mangaId, entry.sourceName, entry.inLibrary)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logcat(LogPriority.WARN, e) { "StartFeed arc: could not look for ${inSeries.first().seriesName}" }
        ArcSeries.NotFound
    }

    /** The entry's chapters from its source when an issue of the arc already out is not among them. */
    private suspend fun bringChapters(mangaId: Long, inSeries: List<ArcIssue>) {
        val today = LocalDate.now()
        val refs = chapters.getChapterByMangaId(mangaId).map { ChapterRef(it.id, it.chapterNumber, it.read) }
        val missing = inSeries.any { issue ->
            val out = issue.storeDate?.let { runCatching { LocalDate.parse(it) <= today }.getOrNull() } ?: true
            out && Arcs.chapterOf(issue, refs) == null
        }
        if (!missing) return
        val manga = getManga.await(mangaId) ?: return
        withIOContext { updateManga(manga, fetchDetails = false, fetchChapters = true) }
    }

    private suspend fun sourceName(mangaId: Long): String {
        val manga = getManga.await(mangaId) ?: return ""
        return sourceManager.getOrStub(manga.source).name
    }
}
