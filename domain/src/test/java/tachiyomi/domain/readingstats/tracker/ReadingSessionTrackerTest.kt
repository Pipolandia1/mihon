package tachiyomi.domain.readingstats.tracker

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlinx.datetime.toInstant
import org.junit.jupiter.api.Test
import tachiyomi.domain.readingstats.model.ChapterKey
import tachiyomi.domain.readingstats.model.ChapterProgress
import tachiyomi.domain.readingstats.model.ChapterRef
import tachiyomi.domain.readingstats.model.LegacyProgress
import tachiyomi.domain.readingstats.model.ReadingOrigin
import tachiyomi.domain.readingstats.model.SessionSnapshot
import tachiyomi.domain.readingstats.model.SessionState
import tachiyomi.domain.readingstats.model.SessionWrite
import kotlin.time.Instant

class ReadingSessionTrackerTest {

    private var zone: TimeZone = TimeZone.UTC
    private var uuids = 0
    private val tracker = ReadingSessionTracker(ReadingTrackerConfig(), { zone }) { "session-${++uuids}" }

    /** Noon on 24 September 2026, UTC. */
    private val base = LocalDateTime(2026, 9, 24, 12, 0).toInstant(TimeZone.UTC)

    private fun at(seconds: Int): Instant = Instant.fromEpochMilliseconds(base.toEpochMilliseconds() + seconds * 1000L)

    private val writes = ArrayList<SessionWrite>()

    private fun last(): SessionSnapshot = drain().last().session

    private fun drain(): List<SessionWrite> {
        writes += tracker.drainWrites()
        return writes
    }

    /** Opens chapter 1 with [totalPages] pages at [second], in the foreground, on a loaded page 0. */
    private fun startReading(
        second: Int = 0,
        totalPages: Int = 20,
        progress: ChapterProgress? = null,
        legacy: LegacyProgress? = LegacyProgress(read = false, lastPageRead = 0),
        chapterId: Long = 1,
        firstPage: Int = 0,
    ) {
        tracker.setForeground(at(second), true)
        tracker.open(at(second), ref(chapterId), totalPages, progress, legacy)
        tracker.setPageReady(at(second), true)
        tracker.pageShown(at(second), firstPage)
    }

    @Test
    fun `time counts only while the reader is in front, focused and on a loaded page`() {
        startReading()
        tracker.interaction(at(10))
        tracker.setForeground(at(30), false)
        tracker.setForeground(at(100), true)
        tracker.interaction(at(110))
        tracker.setFocused(at(120), false)
        tracker.setFocused(at(200), true)
        tracker.setPageReady(at(205), false)
        tracker.setPageReady(at(215), true)
        tracker.interaction(at(220))
        tracker.close(at(220))

        // 0..30 + 100..120 + 200..205 + 215..220
        last().activeMs shouldBe 60_000
    }

    @Test
    fun `a long silence counts only its grace, then counting resumes at the next touch`() {
        startReading()
        tracker.tick(at(30))
        tracker.tick(at(200))
        tracker.interaction(at(300))
        tracker.interaction(at(310))
        tracker.close(at(310))

        last().activeMs shouldBe 70_000
    }

    @Test
    fun `a silence shorter than the timeout counts in full`() {
        startReading()
        tracker.tick(at(50))
        tracker.tick(at(100))
        tracker.interaction(at(110))
        tracker.close(at(110))

        last().activeMs shouldBe 110_000
    }

    @Test
    fun `a long silence ended by leaving without any tick still counts only its grace`() {
        startReading()
        tracker.setForeground(at(600), false)

        last().activeMs shouldBe 60_000
    }

    @Test
    fun `going back is rereading, and only moving forward counts as an advance`() {
        startReading()
        listOf(1, 2, 1, 0, 2, 3).forEachIndexed { i, page -> tracker.pageShown(at(i + 1), page) }
        tracker.close(at(10))

        val first = last()
        first.pagesVisited shouldBe 4
        first.pagesNew shouldBe 4
        first.pagesReread shouldBe 0
        first.pageAdvances shouldBe 4
        first.firstPage shouldBe 0
        first.lastPage shouldBe 3

        val progress = drain().last().progress
        startReading(second = 20_000, progress = progress)
        tracker.pageShown(at(20_001), 1)
        tracker.pageShown(at(20_002), 4)
        tracker.close(at(20_003))

        val second = last()
        second.pagesVisited shouldBe 3
        second.pagesNew shouldBe 1
        second.pagesReread shouldBe 2
    }

    @Test
    fun `flicking through pages counts the visits but not more time`() {
        startReading()
        (1..10).forEach { tracker.pageShown(at(0), it) }
        tracker.close(at(1))

        last().pagesVisited shouldBe 11
        last().activeMs shouldBe 1_000
    }

    @Test
    fun `a chapter is completed once, the first time its last page is reached`() {
        startReading(totalPages = 3)
        tracker.pageShown(at(1), 1)
        tracker.pageShown(at(2), 2)
        tracker.pageShown(at(3), 1)
        tracker.pageShown(at(4), 2)
        tracker.close(at(5))

        val first = last()
        first.completedAt shouldBe at(2).toEpochMilliseconds()
        first.firstCompletion shouldBe true
        val progress = drain().last().progress
        progress.completionOrigin shouldBe ReadingOrigin.READER

        startReading(second = 20_000, totalPages = 3, progress = progress, firstPage = 2)
        tracker.close(at(20_001))

        val reread = last()
        reread.completedAt shouldNotBe null
        reread.firstCompletion shouldBe false
        drain().last().progress.completedAt shouldBe at(2).toEpochMilliseconds()
    }

    @Test
    fun `rereading a chapter Mihon had as read is rereading, never a new completion`() {
        startReading(totalPages = 3, legacy = LegacyProgress(read = true, lastPageRead = 2))
        tracker.pageShown(at(1), 1)
        tracker.pageShown(at(2), 2)
        tracker.close(at(3))

        val session = last()
        session.pagesNew shouldBe 0
        session.pagesReread shouldBe 3
        session.firstCompletion shouldBe false
        drain().last().progress.completionOrigin shouldBe ReadingOrigin.IMPORTED
    }

    @Test
    fun `a chapter left on a page before recording counts up to that page as seen`() {
        startReading(legacy = LegacyProgress(read = false, lastPageRead = 5), firstPage = 5)
        (6..7).forEach { tracker.pageShown(at(it), it) }
        tracker.close(at(8))

        last().pagesNew shouldBe 2
        last().pagesReread shouldBe 1
    }

    @Test
    fun `a chapter marked read by hand is seen whole`() {
        val manual = ChapterProgress(1, null, -1, null, 5, LocalDate(2026, 9, 1), ReadingOrigin.MANUAL)
        startReading(totalPages = 3, progress = manual)
        tracker.pageShown(at(1), 1)
        tracker.pageShown(at(2), 2)
        tracker.close(at(3))

        last().pagesNew shouldBe 0
        last().firstCompletion shouldBe false
    }

    @Test
    fun `coming back within the resume window continues the same session`() {
        startReading()
        tracker.setForeground(at(10), false)
        tracker.setForeground(at(300), true)
        tracker.interaction(at(305))
        tracker.close(at(305))

        drain().map { it.session.uuid }.distinct() shouldBe listOf("session-1")
        last().activeMs shouldBe 15_000
    }

    @Test
    fun `coming back after the resume window starts a new session on the same page`() {
        startReading()
        tracker.pageShown(at(5), 1)
        tracker.setForeground(at(10), false)
        tracker.setForeground(at(10 + 11 * 60), true)
        tracker.interaction(at(10 + 11 * 60 + 5))
        tracker.close(at(10 + 11 * 60 + 5))

        val sessions = drain().groupBy { it.session.uuid }.mapValues { it.value.last().session }
        sessions.keys shouldBe setOf("session-1", "session-2")
        sessions.getValue("session-1").state shouldBe SessionState.CLOSED
        sessions.getValue("session-1").activeMs shouldBe 10_000
        val second = sessions.getValue("session-2")
        second.activeMs shouldBe 5_000
        second.firstPage shouldBe 1
        second.pagesReread shouldBe 1
    }

    @Test
    fun `a saved session resumes without counting its pages twice`() {
        startReading()
        tracker.pageShown(at(5), 1)
        tracker.close(at(10))
        val saved = last()
        val progress = drain().last().progress

        val resumed = ReadingSessionTracker(ReadingTrackerConfig(), { zone }) { "unused" }
        resumed.setForeground(at(60), true)
        resumed.open(at(60), ref(1), 20, progress, null, resume = saved)
        resumed.setPageReady(at(60), true)
        resumed.pageShown(at(60), 1)
        resumed.pageShown(at(65), 2)
        resumed.close(at(65))

        val session = resumed.drainWrites().last().session
        session.uuid shouldBe saved.uuid
        session.pagesVisited shouldBe 3
        session.pagesNew shouldBe 3
        session.activeMs shouldBe 15_000
    }

    @Test
    fun `turning to another chapter closes the session of the first`() {
        startReading()
        tracker.pageShown(at(5), 19)
        tracker.open(at(8), ref(2), 20, null, LegacyProgress(false, 0))
        tracker.pageShown(at(8), 0)
        tracker.close(at(10))

        val sessions = drain().groupBy { it.session.chapterId }.mapValues { it.value.last().session }
        sessions.getValue(1).state shouldBe SessionState.CLOSED
        sessions.getValue(1).activeMs shouldBe 8_000
        sessions.getValue(2).activeMs shouldBe 2_000
    }

    @Test
    fun `progress is saved while reading, so a crash loses little`() {
        startReading()
        drain()
        writes.clear()
        (1..8).forEach {
            tracker.interaction(at(it * 5))
            tracker.tick(at(it * 5))
        }

        val saved = drain()
        saved shouldHaveSize 2
        saved.last().session.state shouldBe SessionState.OPEN
        saved.last().session.activeMs shouldBe 30_000
    }

    @Test
    fun `nothing is written while no page has been read`() {
        tracker.setForeground(at(0), true)
        tracker.tick(at(20))
        tracker.interaction(at(25))

        drain().shouldBeEmpty()
        tracker.chapterId.shouldBeNull()
    }

    @Test
    fun `reading across midnight is split between the two days`() {
        val beforeMidnight = LocalDateTime(2026, 9, 24, 23, 59, 30).toInstant(TimeZone.UTC)
        val offset = (beforeMidnight.toEpochMilliseconds() - base.toEpochMilliseconds()).toInt() / 1000
        startReading(second = offset)
        tracker.pageShown(at(offset + 20), 1)
        tracker.pageShown(at(offset + 40), 2)
        tracker.close(at(offset + 60))

        val days = last().days
        days.map { it.date } shouldBe listOf(LocalDate(2026, 9, 24), LocalDate(2026, 9, 25))
        days.map { it.activeMs } shouldBe listOf(30_000L, 30_000L)
        days.map { it.pagesNew } shouldBe listOf(2, 1)
    }

    @Test
    fun `time is dated in the time zone in force when it was spent`() {
        // 22:30 UTC: still the 24th in UTC, already the 25th three hours east.
        val late = LocalDateTime(2026, 9, 24, 22, 30).toInstant(TimeZone.UTC)
        val offset = (late.toEpochMilliseconds() - base.toEpochMilliseconds()).toInt() / 1000
        startReading(second = offset)
        tracker.interaction(at(offset + 10))
        zone = UtcOffset(hours = 3).asTimeZone()
        tracker.interaction(at(offset + 20))
        tracker.close(at(offset + 20))

        val days = last().days
        days.map { it.date } shouldBe listOf(LocalDate(2026, 9, 24), LocalDate(2026, 9, 25))
        days.map { it.activeMs } shouldBe listOf(10_000L, 10_000L)
    }

    private fun ref(chapterId: Long) = ChapterRef(
        mangaId = 1,
        chapterId = chapterId,
        key = ChapterKey(1, "/m", "/c/$chapterId", "Title", "Chapter $chapterId", chapterId.toDouble()),
    )
}
