package eu.kanade.tachiyomi.readingstats

import androidx.compose.runtime.Immutable
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.history.model.HistoryWithRelations
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.readingstats.repository.ComicMetadataRepository
import tachiyomi.domain.readingstats.repository.ReadingStatsRepository
import tachiyomi.domain.start.ContinueCandidate
import tachiyomi.domain.start.ContinueChapter
import tachiyomi.domain.start.ContinuePick
import tachiyomi.domain.start.ContinueRules

/**
 * What the reader was reading most recently, and where to pick each one up.
 *
 * Shared by the Stats tab and the Início tab (amendment H1), so the two never disagree about
 * which chapter "continue" opens. Since amendment H2 a work read to the end leaves the list and a
 * new issue brings it back, at the top ([ContinueRules]).
 */
@Inject
class ContinueReadingSource(
    private val repository: ReadingStatsRepository,
    private val metadata: ComicMetadataRepository,
    private val getHistory: GetHistory,
    private val getNextChapters: GetNextChapters,
) {

    /** Up to [limit] works, the most recent first, each with the chapter to open. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun subscribe(limit: Int): Flow<List<ContinueItem>> =
        combine(getHistory.subscribe("").distinctUntilChanged(), cameBack(), ::Pair)
            .mapLatest { (history, cameBack) ->
                // The most recent works, and any work a new issue brought back however far down the
                // history it is. Each costs a query, so the rest of a long history is not looked at.
                val looked = history.filterIndexed { index, entry ->
                    index < ReadingStatsConfig.CONTINUE_READING_SCAN || entry.mangaId in cameBack
                }
                val loaded = looked.associate {
                    it.mangaId to
                        (it to getNextChapters.await(it.mangaId, onlyUnread = false))
                }
                ContinueRules.pick(loaded.values.map { (entry, chapters) -> candidate(entry, chapters) }, limit)
                    .map { pick ->
                        val (entry, chapters) = loaded.getValue(pick.mangaId)
                        continueItem(entry, chapters.first { it.id == pick.chapter.id }, pick)
                    }
            }
            .catch { logcat(LogPriority.ERROR, it) }
            .flowOn(Dispatchers.IO)

    /** Library works with an unread chapter that arrived after they were last read. */
    private fun cameBack(): Flow<Set<Long>> = metadata.subscribeLibrary()
        .map { entries ->
            entries.filter { entry ->
                ContinueRules.hasNewSinceRead(
                    entry.lastReadAt,
                    entry.chapters.map { ContinueChapter(it.id, it.read, it.dateFetch) },
                )
            }.mapTo(HashSet()) { it.mangaId }
        }
        .distinctUntilChanged()

    private fun candidate(entry: HistoryWithRelations, chapters: List<Chapter>) = ContinueCandidate(
        mangaId = entry.mangaId,
        lastChapterId = entry.chapterId,
        lastReadAt = entry.readAt?.time ?: 0,
        chapters = chapters.map { ContinueChapter(it.id, it.read, it.dateFetch) },
    )

    private suspend fun continueItem(entry: HistoryWithRelations, chapter: Chapter, pick: ContinuePick): ContinueItem {
        return ContinueItem(
            mangaId = entry.mangaId,
            chapterId = chapter.id,
            title = entry.title,
            cover = entry.coverData,
            chapterNumber = chapter.chapterNumber,
            chapterName = chapter.name,
            // The chapter last opened and not finished is resumed where it was left, even on page one.
            resumePage = if (pick.resuming) chapter.lastPageRead.toInt() + 1 else null,
            totalPages = if (pick.resuming) repository.getChapterProgress(chapter.id)?.totalPages else null,
            newChapter = pick.newSinceRead,
            lastReadAt = entry.readAt?.time ?: 0L,
        )
    }
}

/** A work to continue: the chapter to open and, when it was left part way, where. */
@Immutable
data class ContinueItem(
    val mangaId: Long,
    val chapterId: Long,
    val title: String,
    val cover: MangaCover,
    val chapterNumber: Double,
    val chapterName: String,
    /** The 1-based page to resume on, when the chapter was left part way. */
    val resumePage: Int?,
    val totalPages: Int?,
    /** The chapter arrived after the work was last read: a new issue. */
    val newChapter: Boolean = false,
    /** When the work was last read, epoch ms: followed arcs go among the works by it (H10). */
    val lastReadAt: Long = 0L,
)
