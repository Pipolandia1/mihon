package tachiyomi.domain.readingstats.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate
import tachiyomi.domain.readingstats.model.ChapterKey
import tachiyomi.domain.readingstats.model.ChapterProgress
import tachiyomi.domain.readingstats.model.ReadingDayEntry
import tachiyomi.domain.readingstats.model.ReadingDayStats
import tachiyomi.domain.readingstats.model.SessionSnapshot
import tachiyomi.domain.readingstats.model.SessionWrite

interface ReadingStatsRepository {

    /** Writes a session, its days and its chapter's progress in one transaction. Idempotent. */
    suspend fun saveSession(write: SessionWrite)

    suspend fun getChapterProgress(chapterId: Long): ChapterProgress?

    /** The most recent reader session, if it belongs to [chapterId] and ended at or after [endedAfter]. */
    suspend fun getResumableSession(chapterId: Long, endedAfter: Long): SessionSnapshot?

    /** Marks sessions still open but silent since before [endedBefore] as recovered. */
    suspend fun closeStaleSessions(endedBefore: Long)

    /** Records chapters marked as read by hand, unless they were already completed. */
    suspend fun markCompletedManually(chapterIds: List<Long>, at: Long, on: LocalDate)

    /** Undoes [markCompletedManually] for chapters marked as unread; measured completions stay. */
    suspend fun clearManualCompletion(chapterIds: List<Long>)

    /**
     * Deletes a session with its days. If it was its chapter's first completion, the next session
     * that completed the chapter becomes the first one.
     */
    suspend fun deleteSession(sessionId: Long)

    /** Deletes every session, day and chapter progress, and restarts recording now. */
    suspend fun deleteAll(now: Long)

    /** Points the sessions and progress of migrated chapters at their new entry. */
    suspend fun moveChapters(toMangaId: Long, moves: Map<Long, Long>)

    /** Epoch milliseconds from which reading has been recorded. */
    suspend fun getRecordingStartedAt(): Long

    /** One row per day from [from] to [to] that has any reading or completion. */
    fun subscribeDays(from: LocalDate, to: LocalDate): Flow<List<ReadingDayStats>>

    /** Everything read on [date]: sessions, hand-marked and carried-over completions. */
    fun subscribeDayEntries(date: LocalDate, dayStart: Long, dayEnd: Long): Flow<List<ReadingDayEntry>>

    // region Backup

    suspend fun exportSessions(): List<SessionSnapshot>

    suspend fun exportProgress(): List<Pair<ChapterKey, ChapterProgress>>

    /** Adds sessions not already present, matching entries and chapters by [ChapterKey]. */
    suspend fun importSessions(sessions: List<SessionSnapshot>)

    /** Merges progress into the chapters that [ChapterKey] finds; others are skipped. */
    suspend fun importProgress(progress: List<Pair<ChapterKey, ChapterProgress>>)

    /** Moves the recording start back to [startedAt] if that is earlier. */
    suspend fun importRecordingStartedAt(startedAt: Long)

    // endregion
}
