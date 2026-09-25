package tachiyomi.data.readingstats

import kotlinx.datetime.LocalDate
import tachiyomi.domain.readingstats.model.ChapterKey
import tachiyomi.domain.readingstats.model.ChapterProgress
import tachiyomi.domain.readingstats.model.DayTotals
import tachiyomi.domain.readingstats.model.ReadingOrigin
import tachiyomi.domain.readingstats.model.SessionSnapshot
import tachiyomi.domain.readingstats.model.SessionState
import tachiyomi.domain.readingstats.model.SessionWrite

object ReadingStatsFixtures {

    val DAY = LocalDate(2026, 9, 24)

    /** A closed reader session of entry 1 on [chapterId], completing it on [completedOn] if given. */
    fun session(
        uuid: String,
        chapterId: Long,
        activeMs: Long = 1_000,
        endedAt: Long = activeMs,
        pagesNew: Int = 1,
        completedOn: LocalDate? = null,
    ): SessionWrite {
        val snapshot = SessionSnapshot(
            uuid = uuid,
            mangaId = 1,
            chapterId = chapterId,
            key = ChapterKey(
                1,
                "/manga/1",
                "/chapter/$chapterId",
                "Title 1",
                "Chapter $chapterId",
                chapterId.toDouble(),
            ),
            startedAt = endedAt - activeMs,
            endedAt = endedAt,
            activeMs = activeMs,
            firstPage = 0,
            lastPage = 0,
            pagesVisited = pagesNew,
            pagesNew = pagesNew,
            pagesReread = 0,
            pageAdvances = 0,
            totalPages = 10,
            pagesBits = byteArrayOf(1),
            completedAt = completedOn?.let { 1L },
            completedOn = completedOn,
            firstCompletion = completedOn != null,
            origin = ReadingOrigin.READER,
            state = SessionState.CLOSED,
            zoneId = "UTC",
            days = listOf(DayTotals(DAY, activeMs, pagesNew, pagesNew)),
        )
        val progress = ChapterProgress(
            chapterId = chapterId,
            pagesSeen = byteArrayOf(1),
            maxPageReached = 0,
            totalPages = 10,
            completedAt = completedOn?.let { 1L },
            completedOn = completedOn,
            completionOrigin = completedOn?.let { ReadingOrigin.READER },
        )
        return SessionWrite(snapshot, progress)
    }
}
