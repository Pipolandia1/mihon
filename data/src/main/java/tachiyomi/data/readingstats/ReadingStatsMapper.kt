package tachiyomi.data.readingstats

import kotlinx.datetime.LocalDate
import tachiyomi.data.AllProgress
import tachiyomi.data.CompletionsOnDay
import tachiyomi.data.DaySums
import tachiyomi.data.LegacyHistoryBetween
import tachiyomi.data.OtherCompletionsByDay
import tachiyomi.data.ReaderCompletionsByDay
import tachiyomi.data.Reading_day_segments
import tachiyomi.data.Reading_sessions
import tachiyomi.data.SessionsOnDay
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.readingstats.model.ChapterKey
import tachiyomi.domain.readingstats.model.ChapterProgress
import tachiyomi.domain.readingstats.model.DayTotals
import tachiyomi.domain.readingstats.model.ReadingDayEntry
import tachiyomi.domain.readingstats.model.ReadingDayStats
import tachiyomi.domain.readingstats.model.ReadingOrigin
import tachiyomi.domain.readingstats.model.SessionSnapshot
import tachiyomi.domain.readingstats.model.SessionState

object ReadingStatsMapper {

    fun mapProgress(
        chapterId: Long,
        pagesSeen: ByteArray?,
        maxPageReached: Long,
        totalPages: Long?,
        completedAt: Long?,
        completedOn: String?,
        completionOrigin: Long?,
    ): ChapterProgress = ChapterProgress(
        chapterId = chapterId,
        pagesSeen = pagesSeen,
        maxPageReached = maxPageReached.toInt(),
        totalPages = totalPages?.toInt(),
        completedAt = completedAt,
        completedOn = completedOn?.let(LocalDate::parse),
        completionOrigin = ReadingOrigin.of(completionOrigin),
    )

    fun mapSession(row: Reading_sessions, days: List<Reading_day_segments>): SessionSnapshot = SessionSnapshot(
        uuid = row.session_uuid,
        mangaId = row.manga_id,
        chapterId = row.chapter_id,
        key = ChapterKey(
            sourceId = row.source_id,
            mangaUrl = row.manga_url,
            chapterUrl = row.chapter_url,
            mangaTitle = row.manga_title,
            chapterName = row.chapter_name,
            chapterNumber = row.chapter_number,
        ),
        startedAt = row.started_at,
        endedAt = row.ended_at,
        activeMs = row.active_ms,
        firstPage = row.first_page.toInt(),
        lastPage = row.last_page.toInt(),
        pagesVisited = row.pages_visited.toInt(),
        pagesNew = row.pages_new.toInt(),
        pagesReread = row.pages_reread.toInt(),
        pageAdvances = row.page_advances.toInt(),
        totalPages = row.total_pages?.toInt(),
        pagesBits = row.pages_bits,
        completedAt = row.completed_at,
        completedOn = row.completed_on?.let(LocalDate::parse),
        firstCompletion = row.first_completion,
        origin = ReadingOrigin.of(row.origin) ?: ReadingOrigin.READER,
        state = SessionState.of(row.state),
        zoneId = row.zone_id,
        days = days.map {
            DayTotals(
                date = LocalDate.parse(it.local_date),
                activeMs = it.active_ms,
                pagesNew = it.pages_new.toInt(),
                pagesVisited = it.pages_visited.toInt(),
            )
        },
    )

    fun mapExportedProgress(row: AllProgress): Pair<ChapterKey, ChapterProgress> {
        val key = ChapterKey(
            sourceId = row.source,
            mangaUrl = row.manga_url,
            chapterUrl = row.chapter_url,
            mangaTitle = row.title,
            chapterName = row.chapter_name,
            chapterNumber = row.chapter_number,
        )
        return key to mapProgress(
            chapterId = row.chapter_id,
            pagesSeen = row.pages_seen,
            maxPageReached = row.max_page_reached,
            totalPages = row.total_pages,
            completedAt = row.completed_at,
            completedOn = row.completed_on,
            completionOrigin = row.completion_origin,
        )
    }

    fun mapDays(
        sums: List<DaySums>,
        readerCompletions: List<ReaderCompletionsByDay>,
        otherCompletions: List<OtherCompletionsByDay>,
    ): List<ReadingDayStats> {
        val days = HashMap<String, ReadingDayStats>()
        fun day(date: String) = days.getOrPut(date) { ReadingDayStats(LocalDate.parse(date)) }
        sums.forEach {
            days[it.local_date] = day(it.local_date).copy(
                activeMs = it.active_ms ?: 0,
                pagesNew = (it.pages_new ?: 0).toInt(),
                pagesVisited = (it.pages_visited ?: 0).toInt(),
                sessions = it.sessions.toInt(),
            )
        }
        readerCompletions.forEach {
            val date = it.completed_on ?: return@forEach
            days[date] = day(date).copy(completedInReader = it.completed.toInt())
        }
        otherCompletions.forEach {
            val date = it.completed_on ?: return@forEach
            days[date] = when (ReadingOrigin.of(it.completion_origin)) {
                ReadingOrigin.MANUAL -> day(date).copy(completedManually = it.completed.toInt())
                ReadingOrigin.IMPORTED -> day(date).copy(completedImported = it.completed.toInt())
                else -> day(date)
            }
        }
        return days.values.sortedBy { it.date }
    }

    fun mapDayEntries(
        date: LocalDate,
        sessions: List<SessionsOnDay>,
        completions: List<CompletionsOnDay>,
        legacy: List<LegacyHistoryBetween>,
    ): List<ReadingDayEntry> {
        val entries = ArrayList<ReadingDayEntry>(sessions.size + completions.size + legacy.size)
        sessions.mapTo(entries) {
            ReadingDayEntry.Session(
                sessionId = it._id,
                mangaId = it.manga_id,
                chapterId = it.chapter_id,
                title = it.manga_title,
                chapterName = it.chapter_name,
                chapterNumber = it.chapter_number,
                cover = it.manga_id?.let { mangaId ->
                    MangaCover(
                        mangaId = mangaId,
                        sourceId = it.source ?: -1,
                        isMangaFavorite = it.favorite ?: false,
                        url = it.thumbnail_url,
                        lastModified = it.cover_last_modified ?: 0,
                    )
                },
                startedAt = it.started_at,
                endedAt = it.ended_at,
                dayActiveMs = it.day_active_ms,
                dayPagesNew = it.day_pages_new.toInt(),
                dayPagesVisited = it.day_pages_visited.toInt(),
                totalPages = it.total_pages?.toInt(),
                finishedChapter = it.completed_on == date.toString(),
                firstCompletion = it.first_completion && it.completed_on == date.toString(),
                recovered = SessionState.of(it.state) == SessionState.RECOVERED,
            )
        }
        completions.mapTo(entries) {
            ReadingDayEntry.Completion(
                mangaId = it.manga_id,
                chapterId = it.chapter_id,
                title = it.title,
                chapterName = it.chapter_name,
                chapterNumber = it.chapter_number,
                cover = MangaCover(it.manga_id, it.source, it.favorite, it.thumbnail_url, it.cover_last_modified),
                at = it.completed_at ?: 0,
                origin = ReadingOrigin.of(it.completion_origin) ?: ReadingOrigin.MANUAL,
            )
        }
        // A chapter already listed as completed that day needs no second, vaguer line.
        val completed = completions.mapTo(HashSet()) { it.chapter_id }
        legacy.filterNot { it.chapter_id in completed }.mapTo(entries) {
            ReadingDayEntry.Legacy(
                mangaId = it.manga_id,
                chapterId = it.chapter_id,
                title = it.title,
                chapterName = it.chapter_name,
                chapterNumber = it.chapter_number,
                cover = MangaCover(it.manga_id, it.source, it.favorite, it.thumbnail_url, it.cover_last_modified),
                at = it.last_read?.time ?: 0,
            )
        }
        return entries.sortedByDescending { it.at }
    }
}
