package tachiyomi.domain.readingstats.interactor

import dev.zacsweers.metro.Inject
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.readingstats.repository.ReadingStatsRepository

/**
 * Carries reading over when an entry is replaced by one from another source: each chapter's
 * sessions and progress follow it to the chapter with the same number, the way read marks do.
 */
@Inject
class MoveReadingStats(
    private val repository: ReadingStatsRepository,
) {

    suspend fun await(targetMangaId: Long, from: List<Chapter>, to: List<Chapter>) {
        val targets = to.filter { it.isRecognizedNumber }.associateBy { it.chapterNumber }
        val moves = from
            .filter { it.isRecognizedNumber }
            .mapNotNull { chapter -> targets[chapter.chapterNumber]?.let { chapter.id to it.id } }
            .toMap()
        if (moves.isEmpty()) return
        try {
            repository.moveChapters(targetMangaId, moves)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }
}
