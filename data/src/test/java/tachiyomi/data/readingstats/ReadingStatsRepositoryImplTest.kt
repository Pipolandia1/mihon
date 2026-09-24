package tachiyomi.data.readingstats

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.synchronous
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.domain.readingstats.model.ChapterKey
import tachiyomi.domain.readingstats.model.ChapterProgress
import tachiyomi.domain.readingstats.model.DayTotals
import tachiyomi.domain.readingstats.model.PageBits
import tachiyomi.domain.readingstats.model.ReadingDayEntry
import tachiyomi.domain.readingstats.model.ReadingOrigin
import tachiyomi.domain.readingstats.model.SessionSnapshot
import tachiyomi.domain.readingstats.model.SessionState
import tachiyomi.domain.readingstats.model.SessionWrite
import java.util.BitSet

class ReadingStatsRepositoryImplTest {

    private val db = TestDatabase()
    private val driver = db.driver
    private val database = db.database
    private val repository = ReadingStatsRepositoryImpl(database)

    @Test
    fun `writing the same session twice stores it once, with absolute figures`() = runTest {
        insertManga(1)
        insertChapter(10, mangaId = 1)
        val write = write(uuid = "a", chapterId = 10, activeMs = 60_000, pagesNew = 5)

        repository.saveSession(write)
        repository.saveSession(write)

        val days = repository.subscribeDays(DAY, DAY).first()
        days shouldHaveSize 1
        days[0].activeMs shouldBe 60_000
        days[0].pagesNew shouldBe 5
        days[0].sessions shouldBe 1
        repository.getChapterProgress(10).shouldNotBeNull().maxPageReached shouldBe 4
    }

    @Test
    fun `a session outlives its chapter`() = runTest {
        insertManga(1)
        insertChapter(10, mangaId = 1)
        repository.saveSession(write(uuid = "a", chapterId = 10, activeMs = 60_000, pagesNew = 5))

        exec("DELETE FROM chapters WHERE _id = 10")

        repository.subscribeDays(DAY, DAY).first()[0].activeMs shouldBe 60_000
        val entry = repository.subscribeDayEntries(DAY, 0, Long.MAX_VALUE).first().single()
        (entry as ReadingDayEntry.Session).chapterId.shouldBeNull()
        entry.title shouldBe "Title 1"
        repository.getChapterProgress(10).shouldBeNull()
    }

    @Test
    fun `a session of a chapter deleted before the write is still saved`() = runTest {
        insertManga(1)
        repository.saveSession(write(uuid = "a", chapterId = 10, activeMs = 1_000, pagesNew = 1))

        repository.subscribeDays(DAY, DAY).first()[0].activeMs shouldBe 1_000
    }

    @Test
    fun `deleting a first completion hands it to the next session that completed the chapter`() = runTest {
        insertManga(1)
        insertChapter(10, mangaId = 1)
        repository.saveSession(write(uuid = "a", chapterId = 10, completedAt = 100, firstCompletion = true))
        repository.saveSession(write(uuid = "b", chapterId = 10, completedAt = 200, firstCompletion = false))
        repository.subscribeDays(DAY, DAY).first()[0].completedInReader shouldBe 1

        repository.deleteSession(sessionId("a"))

        repository.subscribeDays(DAY, DAY).first()[0].completedInReader shouldBe 1
        repository.getChapterProgress(10).shouldNotBeNull().completedAt shouldBe 200

        repository.deleteSession(sessionId("b"))

        repository.subscribeDays(DAY, DAY).first().shouldBeEmpty()
        repository.getChapterProgress(10).shouldNotBeNull().completionOrigin.shouldBeNull()
    }

    @Test
    fun `marking read by hand never overrides a completion and unmarking only undoes its own`() = runTest {
        insertManga(1)
        insertChapter(10, mangaId = 1)
        insertChapter(11, mangaId = 1)
        repository.saveSession(write(uuid = "a", chapterId = 10, completedAt = 100, firstCompletion = true))

        repository.markCompletedManually(listOf(10, 11), at = 500, on = DAY)

        repository.getChapterProgress(10).shouldNotBeNull().completionOrigin shouldBe ReadingOrigin.READER
        repository.getChapterProgress(11).shouldNotBeNull().completionOrigin shouldBe ReadingOrigin.MANUAL
        val day = repository.subscribeDays(DAY, DAY).first().single()
        day.completedInReader shouldBe 1
        day.completedManually shouldBe 1

        repository.clearManualCompletion(listOf(10, 11))

        repository.getChapterProgress(10).shouldNotBeNull().completionOrigin shouldBe ReadingOrigin.READER
        repository.getChapterProgress(11).shouldNotBeNull().completionOrigin.shouldBeNull()
    }

    @Test
    fun `a first completion written by the reader is not replaced by a later write`() = runTest {
        insertManga(1)
        insertChapter(10, mangaId = 1)
        repository.markCompletedManually(listOf(10), at = 50, on = DAY)

        repository.saveSession(write(uuid = "a", chapterId = 10, completedAt = 100, firstCompletion = false))

        val progress = repository.getChapterProgress(10).shouldNotBeNull()
        progress.completionOrigin shouldBe ReadingOrigin.MANUAL
        progress.completedAt shouldBe 50
    }

    @Test
    fun `only sessions left open and silent are recovered`() = runTest {
        insertManga(1)
        insertChapter(10, mangaId = 1)
        repository.saveSession(write(uuid = "old", chapterId = 10, endedAt = 1_000, state = SessionState.OPEN))
        repository.saveSession(write(uuid = "new", chapterId = 10, endedAt = 9_000, state = SessionState.OPEN))

        repository.closeStaleSessions(endedBefore = 5_000)

        state("old") shouldBe SessionState.RECOVERED.id
        state("new") shouldBe SessionState.OPEN.id
    }

    @Test
    fun `only the latest session of the same chapter can be resumed`() = runTest {
        insertManga(1)
        insertChapter(10, mangaId = 1)
        insertChapter(11, mangaId = 1)
        repository.saveSession(write(uuid = "a", chapterId = 10, endedAt = 1_000))
        repository.saveSession(write(uuid = "b", chapterId = 11, endedAt = 2_000))

        repository.getResumableSession(10, endedAfter = 0).shouldBeNull()
        repository.getResumableSession(11, endedAfter = 2_500).shouldBeNull()
        val resumed = repository.getResumableSession(11, endedAfter = 1_500).shouldNotBeNull()
        resumed.uuid shouldBe "b"
        resumed.days.single().activeMs shouldBe 1_000
    }

    @Test
    fun `migrating a chapter moves its sessions and progress`() = runTest {
        insertManga(1)
        insertManga(2)
        insertChapter(10, mangaId = 1)
        insertChapter(20, mangaId = 2)
        repository.saveSession(write(uuid = "a", chapterId = 10, pagesNew = 3))

        repository.moveChapters(toMangaId = 2, moves = mapOf(10L to 20L))

        repository.getChapterProgress(20).shouldNotBeNull().maxPageReached shouldBe 2
        repository.getChapterProgress(10).shouldBeNull()
        val exported = repository.exportSessions().single()
        exported.chapterId shouldBe 20
        exported.key.mangaUrl shouldBe "/manga/2"
        exported.key.chapterUrl shouldBe "/chapter/20"
    }

    @Test
    fun `importing a backup twice adds its sessions once`() = runTest {
        insertManga(1)
        insertChapter(10, mangaId = 1)
        repository.saveSession(write(uuid = "a", chapterId = 10, activeMs = 60_000, pagesNew = 5))
        val sessions = repository.exportSessions()
        val progress = repository.exportProgress()

        val target = ReadingStatsRepositoryImplTest()
        target.insertManga(7, url = "/manga/1")
        target.insertChapter(70, mangaId = 7, url = "/chapter/10")
        repeat(2) {
            target.repository.importSessions(sessions)
            target.repository.importProgress(progress)
        }

        val day = target.repository.subscribeDays(DAY, DAY).first().single()
        day.activeMs shouldBe 60_000
        day.sessions shouldBe 1
        target.repository.exportSessions().single().chapterId shouldBe 70
        target.repository.getChapterProgress(70).shouldNotBeNull().maxPageReached shouldBe 4
    }

    @Test
    fun `a backup's first completion of a chapter already completed here is not counted again`() = runTest {
        insertManga(1)
        insertChapter(10, mangaId = 1)
        repository.saveSession(write(uuid = "a", chapterId = 10, completedAt = 100, firstCompletion = true))
        val sessions = repository.exportSessions()

        val target = ReadingStatsRepositoryImplTest()
        target.insertManga(1)
        target.insertChapter(10, mangaId = 1)
        target.repository.saveSession(write(uuid = "b", chapterId = 10, completedAt = 150, firstCompletion = true))
        target.repository.importSessions(sessions)

        target.repository.subscribeDays(DAY, DAY).first().single().completedInReader shouldBe 1
    }

    @Test
    fun `migration 15 carries over what Mihon knew without touching it`() = runTest {
        listOf("reading_day_segments", "reading_sessions", "chapter_reading_progress", "reading_stats_meta")
            .forEach { exec("DROP TABLE $it") }
        insertManga(1)
        insertChapter(10, mangaId = 1, read = true, lastPageRead = 0)
        insertChapter(11, mangaId = 1, read = false, lastPageRead = 7)
        insertChapter(12, mangaId = 1, read = false, lastPageRead = 0)
        insertChapter(13, mangaId = 1, read = true, lastPageRead = 0)
        // Noon UTC, so the local date is the same in any time zone within eleven hours of UTC.
        val lastRead = 1_758_715_200_000L
        exec("INSERT INTO history(chapter_id, last_read, time_read) VALUES (10, $lastRead, 5000)")
        exec("INSERT INTO history(chapter_id, last_read, time_read) VALUES (11, $lastRead, 7000)")

        Database.Schema.synchronous().migrate(driver, 15, 16)

        val read = repository.getChapterProgress(10).shouldNotBeNull()
        read.completionOrigin shouldBe ReadingOrigin.IMPORTED
        read.completedAt shouldBe lastRead
        read.completedOn shouldBe LocalDate(2025, 9, 24)
        read.pagesSeen.shouldBeNull()
        val partial = repository.getChapterProgress(11).shouldNotBeNull()
        partial.maxPageReached shouldBe 7
        partial.completionOrigin.shouldBeNull()
        repository.getChapterProgress(12).shouldBeNull()
        val undated = repository.getChapterProgress(13).shouldNotBeNull()
        undated.completionOrigin shouldBe ReadingOrigin.IMPORTED
        undated.completedAt.shouldBeNull()
        database.historyQueries.getReadDuration().awaitAsOne() shouldBe 12_000
        repository.getRecordingStartedAt() shouldBeGreaterThan lastRead
    }

    private fun write(
        uuid: String,
        chapterId: Long,
        activeMs: Long = 1_000,
        pagesNew: Int = 1,
        endedAt: Long = 1_000,
        completedAt: Long? = null,
        firstCompletion: Boolean = false,
        state: SessionState = SessionState.CLOSED,
    ): SessionWrite {
        val bits = BitSet().apply { set(0, pagesNew) }
        val session = SessionSnapshot(
            uuid = uuid,
            mangaId = 1,
            chapterId = chapterId,
            key = ChapterKey(
                sourceId = 1,
                mangaUrl = "/manga/1",
                chapterUrl = "/chapter/$chapterId",
                mangaTitle = "Title 1",
                chapterName = "Chapter $chapterId",
                chapterNumber = chapterId.toDouble(),
            ),
            startedAt = 0,
            endedAt = endedAt,
            activeMs = activeMs,
            firstPage = 0,
            lastPage = pagesNew - 1,
            pagesVisited = pagesNew,
            pagesNew = pagesNew,
            pagesReread = 0,
            pageAdvances = pagesNew - 1,
            totalPages = 20,
            pagesBits = PageBits.encode(bits),
            completedAt = completedAt,
            completedOn = completedAt?.let { DAY },
            firstCompletion = firstCompletion,
            origin = ReadingOrigin.READER,
            state = state,
            zoneId = "UTC",
            days = listOf(DayTotals(DAY, activeMs, pagesNew, pagesNew)),
        )
        val progress = ChapterProgress(
            chapterId = chapterId,
            pagesSeen = PageBits.encode(bits),
            maxPageReached = pagesNew - 1,
            totalPages = 20,
            completedAt = completedAt,
            completedOn = completedAt?.let { DAY },
            completionOrigin = completedAt?.let { ReadingOrigin.READER },
        )
        return SessionWrite(session, progress)
    }

    private fun insertManga(id: Long, url: String = "/manga/$id") = db.insertManga(id, url)

    private fun insertChapter(
        id: Long,
        mangaId: Long,
        url: String = "/chapter/$id",
        read: Boolean = false,
        lastPageRead: Int = 0,
    ) = db.insertChapter(id, mangaId, url, read, lastPageRead)

    private fun exec(sql: String) = db.exec(sql)

    private suspend fun sessionId(uuid: String): Long =
        database.reading_statsQueries.getSessionId(uuid).awaitAsOne()

    private suspend fun state(uuid: String): Long = database.reading_statsQueries.allSessions().awaitAsList()
        .single { it.session_uuid == uuid }.state

    companion object {
        private val DAY = LocalDate(2026, 9, 24)
    }
}
