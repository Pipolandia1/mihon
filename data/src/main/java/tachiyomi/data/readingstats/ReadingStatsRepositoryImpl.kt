package tachiyomi.data.readingstats

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.readingstats.analysis.ChapterReading
import tachiyomi.domain.readingstats.analysis.CompletedChapter
import tachiyomi.domain.readingstats.analysis.SessionFacts
import tachiyomi.domain.readingstats.habits.SessionRecord
import tachiyomi.domain.readingstats.model.ChapterKey
import tachiyomi.domain.readingstats.model.ChapterPages
import tachiyomi.domain.readingstats.model.ChapterProgress
import tachiyomi.domain.readingstats.model.ReadingDayEntry
import tachiyomi.domain.readingstats.model.ReadingDayStats
import tachiyomi.domain.readingstats.model.SessionSnapshot
import tachiyomi.domain.readingstats.model.SessionWrite
import tachiyomi.domain.readingstats.repository.ReadingStatsRepository
import java.util.Date

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ReadingStatsRepositoryImpl(
    private val database: Database,
) : ReadingStatsRepository {

    private val queries get() = database.reading_statsQueries

    override suspend fun saveSession(write: SessionWrite) {
        val session = write.session
        database.transaction {
            // The entry or chapter may have been deleted while it was being read.
            val mangaId = session.mangaId?.takeIf { queries.mangaExists(it).awaitAsOne() > 0 }
            val chapterId = session.chapterId?.takeIf { queries.chapterExists(it).awaitAsOne() > 0 }
            queries.upsertSession(
                uuid = session.uuid,
                mangaId = mangaId,
                chapterId = chapterId,
                sourceId = session.key.sourceId,
                mangaUrl = session.key.mangaUrl,
                chapterUrl = session.key.chapterUrl,
                mangaTitle = session.key.mangaTitle,
                chapterName = session.key.chapterName,
                chapterNumber = session.key.chapterNumber,
                startedAt = session.startedAt,
                endedAt = session.endedAt,
                activeMs = session.activeMs,
                firstPage = session.firstPage.toLong(),
                lastPage = session.lastPage.toLong(),
                pagesVisited = session.pagesVisited.toLong(),
                pagesNew = session.pagesNew.toLong(),
                pagesReread = session.pagesReread.toLong(),
                pageAdvances = session.pageAdvances.toLong(),
                totalPages = session.totalPages?.toLong(),
                pagesBits = session.pagesBits,
                completedAt = session.completedAt,
                completedOn = session.completedOn?.toString(),
                firstCompletion = session.firstCompletion,
                origin = session.origin.id,
                state = session.state.id,
                zoneId = session.zoneId,
            )
            val sessionId = queries.getSessionId(session.uuid).awaitAsOne()
            session.days.forEach { day ->
                queries.upsertDay(
                    sessionId = sessionId,
                    localDate = day.date.toString(),
                    activeMs = day.activeMs,
                    pagesNew = day.pagesNew.toLong(),
                    pagesVisited = day.pagesVisited.toLong(),
                )
            }
            if (chapterId != null) {
                val progress = write.progress
                queries.upsertProgress(
                    chapterId = chapterId,
                    pagesSeen = progress.pagesSeen,
                    maxPageReached = progress.maxPageReached.toLong(),
                    totalPages = progress.totalPages?.toLong(),
                    completedAt = progress.completedAt,
                    completedOn = progress.completedOn?.toString(),
                    completionOrigin = progress.completionOrigin?.id,
                )
            }
        }
    }

    override suspend fun getChapterProgress(chapterId: Long): ChapterProgress? {
        return queries.getProgress(chapterId, ReadingStatsMapper::mapProgress).awaitAsOneOrNull()
    }

    override suspend fun getResumableSession(chapterId: Long, endedAfter: Long): SessionSnapshot? {
        val latest = queries.getLatestReaderSession().awaitAsOneOrNull() ?: return null
        if (latest.chapter_id != chapterId || latest.ended_at < endedAfter) return null
        val days = queries.getDaysOfSession(latest._id).awaitAsList()
        return ReadingStatsMapper.mapSession(latest, days)
    }

    override suspend fun closeStaleSessions(endedBefore: Long) {
        queries.closeStaleSessions(endedBefore)
    }

    override suspend fun markCompletedManually(chapterIds: List<Long>, at: Long, on: LocalDate) {
        database.transaction {
            chapterIds.forEach { queries.markCompletedManually(it, at, on.toString()) }
        }
    }

    override suspend fun clearManualCompletion(chapterIds: List<Long>) {
        queries.clearManualCompletion(chapterIds)
    }

    override suspend fun deleteSession(sessionId: Long) {
        database.transaction {
            val session = queries.getSessionById(sessionId).awaitAsOneOrNull() ?: return@transaction
            queries.deleteSession(sessionId)
            val chapterId = session.chapter_id
            if (!session.first_completion || chapterId == null) return@transaction
            val next = queries.getFirstCompletingSession(chapterId).awaitAsOneOrNull()
            if (next != null) {
                queries.setFirstCompletion(next._id)
                queries.setReaderCompletion(next.completed_at, next.completed_on, chapterId)
            } else {
                queries.clearReaderCompletion(chapterId)
            }
        }
    }

    override suspend fun deleteAll(now: Long) {
        database.transaction {
            queries.deleteAllSessions()
            queries.deleteAllProgress()
            queries.setRecordingStartedAt(now)
        }
    }

    override suspend fun moveChapters(toMangaId: Long, moves: Map<Long, Long>) {
        val manga = database.mangasQueries.getMangaById(toMangaId).awaitAsOneOrNull() ?: return
        database.transaction {
            moves.forEach { (fromChapterId, toChapterId) ->
                val chapter = database.chaptersQueries.getChapterById(toChapterId).awaitAsOneOrNull()
                    ?: return@forEach
                queries.moveSessionsOfChapter(
                    toMangaId = toMangaId,
                    toChapterId = toChapterId,
                    sourceId = manga.source,
                    mangaUrl = manga.url,
                    chapterUrl = chapter.url,
                    fromChapterId = fromChapterId,
                )
                queries.moveProgressOfChapter(toChapterId = toChapterId, fromChapterId = fromChapterId)
            }
        }
    }

    override suspend fun getRecordingStartedAt(): Long {
        return queries.getRecordingStartedAt().awaitAsOneOrNull() ?: 0L
    }

    override fun subscribeDays(from: LocalDate, to: LocalDate): Flow<List<ReadingDayStats>> {
        val fromText = from.toString()
        val toText = to.toString()
        return combine(
            queries.daySums(fromText, toText).subscribeToList(),
            queries.readerCompletionsByDay(fromText, toText).subscribeToList(),
            queries.otherCompletionsByDay(fromText, toText).subscribeToList(),
            ReadingStatsMapper::mapDays,
        )
    }

    override fun subscribeAllDays(): Flow<List<ReadingDayStats>> {
        return queries.allDaySums().subscribeToList().map { rows ->
            rows.map {
                ReadingDayStats(
                    date = LocalDate.parse(it.local_date),
                    activeMs = it.active_ms ?: 0,
                    pagesNew = (it.pages_new ?: 0).toInt(),
                    pagesVisited = (it.pages_visited ?: 0).toInt(),
                    sessions = it.sessions.toInt(),
                )
            }
        }
    }

    override suspend fun getLongestSession(): SessionRecord? {
        return queries.longestSession().awaitAsOneOrNull()?.let {
            SessionRecord(it.manga_title, it.chapter_name, it.chapter_number, it.active_ms, it.started_at)
        }
    }

    override suspend fun getFirstActivityDate(): LocalDate? =
        queries.firstActivityDate().awaitAsOneOrNull()?.first_date?.let(LocalDate::parse)

    override suspend fun getSessions(from: LocalDate, to: LocalDate): List<SessionFacts> =
        queries.sessionsInPeriod(from.toString(), to.toString()).awaitAsList().map {
            SessionFacts(
                id = it._id,
                startedAt = it.started_at,
                endedAt = it.ended_at,
                activeMs = it.active_ms,
                pagesNew = it.pages_new.toInt(),
                zoneId = it.zone_id,
                mangaId = it.manga_id,
                chapterId = it.chapter_id,
                title = it.manga_title,
                chapterName = it.chapter_name,
                chapterNumber = it.chapter_number,
            )
        }

    override suspend fun getReadingByChapter(from: LocalDate, to: LocalDate): List<ChapterReading> =
        queries.readingByChapter(from.toString(), to.toString()).awaitAsList().map {
            ChapterReading(it.manga_id, it.chapter_id, it.manga_title, it.active_ms ?: 0)
        }

    override suspend fun getCompletedChapters(from: LocalDate, to: LocalDate): List<CompletedChapter> =
        queries.completedChaptersInPeriod(from.toString(), to.toString()).awaitAsList().mapNotNull { row ->
            row.chapter_id?.let { CompletedChapter(it, row.manga_id) }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun subscribeDayEntries(date: LocalDate, dayStart: Long, dayEnd: Long): Flow<List<ReadingDayEntry>> {
        val dateText = date.toString()
        // Mihon's own history only speaks for the time before reading was recorded.
        val legacy = flow { emit(getRecordingStartedAt()) }.flatMapLatest { startedAt ->
            if (dayStart >= startedAt) {
                flowOf(emptyList())
            } else {
                queries.legacyHistoryBetween(Date(dayStart), Date(minOf(dayEnd, startedAt))).subscribeToList()
            }
        }
        return combine(
            queries.sessionsOnDay(dateText).subscribeToList(),
            queries.completionsOnDay(dateText).subscribeToList(),
            legacy,
        ) { sessions, completions, legacyEntries ->
            ReadingStatsMapper.mapDayEntries(date, sessions, completions, legacyEntries)
        }
    }

    override suspend fun exportSessions(): List<SessionSnapshot> {
        val days = queries.allDays().awaitAsList().groupBy { it.session_id }
        return queries.allSessions().awaitAsList().map { ReadingStatsMapper.mapSession(it, days[it._id].orEmpty()) }
    }

    override suspend fun exportProgress(): List<Pair<ChapterKey, ChapterProgress>> {
        return queries.allProgress().awaitAsList().map(ReadingStatsMapper::mapExportedProgress)
    }

    override suspend fun importSessions(sessions: List<SessionSnapshot>) {
        database.transaction {
            sessions.forEach { session ->
                if (queries.getSessionId(session.uuid).awaitAsOneOrNull() != null) return@forEach
                val mangaId = findManga(session.key)
                val chapterId = mangaId?.let { findChapter(session.key, it) }
                // A chapter already completed here keeps its own first completion.
                val completedHere = chapterId
                    ?.let { queries.getProgress(it).awaitAsOneOrNull() }
                    ?.completion_origin != null
                queries.upsertSession(
                    uuid = session.uuid,
                    mangaId = mangaId,
                    chapterId = chapterId,
                    sourceId = session.key.sourceId,
                    mangaUrl = session.key.mangaUrl,
                    chapterUrl = session.key.chapterUrl,
                    mangaTitle = session.key.mangaTitle,
                    chapterName = session.key.chapterName,
                    chapterNumber = session.key.chapterNumber,
                    startedAt = session.startedAt,
                    endedAt = session.endedAt,
                    activeMs = session.activeMs,
                    firstPage = session.firstPage.toLong(),
                    lastPage = session.lastPage.toLong(),
                    pagesVisited = session.pagesVisited.toLong(),
                    pagesNew = session.pagesNew.toLong(),
                    pagesReread = session.pagesReread.toLong(),
                    pageAdvances = session.pageAdvances.toLong(),
                    totalPages = session.totalPages?.toLong(),
                    pagesBits = session.pagesBits,
                    completedAt = session.completedAt,
                    completedOn = session.completedOn?.toString(),
                    firstCompletion = session.firstCompletion && !completedHere,
                    origin = session.origin.id,
                    state = session.state.id,
                    zoneId = session.zoneId,
                )
                val sessionId = queries.getSessionId(session.uuid).awaitAsOne()
                session.days.forEach { day ->
                    queries.upsertDay(
                        sessionId = sessionId,
                        localDate = day.date.toString(),
                        activeMs = day.activeMs,
                        pagesNew = day.pagesNew.toLong(),
                        pagesVisited = day.pagesVisited.toLong(),
                    )
                }
            }
        }
    }

    override suspend fun importProgress(progress: List<Pair<ChapterKey, ChapterProgress>>) {
        database.transaction {
            progress.forEach { (key, incoming) ->
                val mangaId = findManga(key) ?: return@forEach
                val chapterId = findChapter(key, mangaId) ?: return@forEach
                val local = queries.getProgress(chapterId, ReadingStatsMapper::mapProgress).awaitAsOneOrNull()
                val merged = ChapterPages.merge(local, incoming.copy(chapterId = chapterId))
                queries.replaceProgress(
                    chapterId = chapterId,
                    pagesSeen = merged.pagesSeen,
                    maxPageReached = merged.maxPageReached.toLong(),
                    totalPages = merged.totalPages?.toLong(),
                    completedAt = merged.completedAt,
                    completedOn = merged.completedOn?.toString(),
                    completionOrigin = merged.completionOrigin?.id,
                )
            }
        }
    }

    override suspend fun importRecordingStartedAt(startedAt: Long) {
        if (startedAt > 0) queries.moveRecordingStartBack(startedAt)
    }

    private suspend fun findManga(key: ChapterKey): Long? {
        return database.mangasQueries.getMangaByUrlAndSource(key.mangaUrl, key.sourceId).awaitAsOneOrNull()?._id
    }

    private suspend fun findChapter(key: ChapterKey, mangaId: Long): Long? {
        return database.chaptersQueries.getChapterByUrlAndMangaId(key.chapterUrl, mangaId).awaitAsOneOrNull()?._id
    }
}
