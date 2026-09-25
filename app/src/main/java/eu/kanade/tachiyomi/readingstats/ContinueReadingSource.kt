package eu.kanade.tachiyomi.readingstats

import androidx.compose.runtime.Immutable
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.readingstats.repository.ReadingStatsRepository

/**
 * What the reader was reading most recently, and where to pick each one up.
 *
 * Shared by the Stats tab and the Início tab (amendment H1), so the two never disagree about
 * which chapter "continue" opens.
 */
@Inject
class ContinueReadingSource(
    private val repository: ReadingStatsRepository,
    private val getHistory: GetHistory,
    private val getNextChapters: GetNextChapters,
) {

    /** Up to [limit] works, most recently read first, each with the chapter to open. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun subscribe(limit: Int): Flow<List<ContinueItem>> = getHistory.subscribe("")
        .distinctUntilChanged()
        .mapLatest { history ->
            // Entries with nothing left to read are skipped, so look further than the first few.
            buildList {
                for (entry in history) {
                    if (size == limit) break
                    continueItem(entry.mangaId, entry.chapterId, entry.title, entry.coverData)?.let(::add)
                }
            }
        }
        .catch { logcat(LogPriority.ERROR, it) }
        .flowOn(Dispatchers.IO)

    private suspend fun continueItem(mangaId: Long, chapterId: Long, title: String, cover: MangaCover): ContinueItem? {
        val next = getNextChapters.await(mangaId, chapterId, onlyUnread = false).firstOrNull() ?: return null
        // The chapter last opened and not finished is resumed where it was left, even on page one.
        val resuming = next.id == chapterId && !next.read
        return ContinueItem(
            mangaId = mangaId,
            chapterId = next.id,
            title = title,
            cover = cover,
            chapterNumber = next.chapterNumber,
            chapterName = next.name,
            resumePage = if (resuming) next.lastPageRead.toInt() + 1 else null,
            totalPages = if (resuming) repository.getChapterProgress(next.id)?.totalPages else null,
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
)
