package tachiyomi.domain.readingstats.analysis

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.jupiter.api.Test
import tachiyomi.domain.readingstats.comics.ComicLibrary
import tachiyomi.domain.readingstats.comics.ComicMetadata
import tachiyomi.domain.readingstats.comics.LibraryChapter
import tachiyomi.domain.readingstats.comics.LibraryEntry
import tachiyomi.domain.readingstats.metrics.ReadingMetrics
import tachiyomi.domain.readingstats.metrics.ReadingPeriod
import tachiyomi.domain.readingstats.model.ReadingDayStats

class ReadingPaceTest {

    private val today = LocalDate(2026, 9, 24)
    private val minute = 60_000L

    private fun session(start: LocalDateTime, minutes: Long, zone: String = "UTC", active: Long = minutes * minute) =
        SessionFacts(
            id = 1,
            startedAt = start.toInstant(TimeZone.of(zone)).toEpochMilliseconds(),
            endedAt = start.toInstant(TimeZone.of(zone)).toEpochMilliseconds() + minutes * minute,
            activeMs = active,
            pagesNew = 1,
            zoneId = zone,
            mangaId = 1,
            chapterId = 1,
            title = "T",
            chapterName = "#1",
            chapterNumber = 1.0,
        )

    @Test
    fun `pace over the recorded part of the period`() {
        val period = ReadingPeriod(LocalDate(2026, 9, 1), LocalDate(2026, 9, 30))
        val days = listOf(
            ReadingDayStats(LocalDate(2026, 9, 20), activeMs = 30 * minute, pagesNew = 40, completedInReader = 2),
            ReadingDayStats(LocalDate(2026, 9, 21), activeMs = 30 * minute, pagesNew = 20, completedManually = 1),
            ReadingDayStats(LocalDate(2026, 9, 22), activeMs = 30_000, completedImported = 5),
        )
        val summary = ReadingMetrics.summarize(period, days, LocalDate(2026, 9, 11))

        val pace = ReadingPace.pace(summary, today, LocalDate(2026, 9, 11))

        // 60 new pages in 60.5 active minutes.
        pace.pagesPerHour shouldBe 59
        // The 30-second day is not a reading day.
        pace.minutesPerReadingDay shouldBe 30
        // 11 to 24 September: 14 days recorded and passed; 3 chapters, the carried-over ones aside.
        pace.recordedDays shouldBe 14
        pace.chaptersPerWeek shouldBe 1.5
    }

    @Test
    fun `too little to say is unknown, not zero`() {
        val period = ReadingPeriod(LocalDate(2026, 9, 20), today)
        val summary = ReadingMetrics.summarize(period, emptyList(), LocalDate(2026, 9, 20))

        val pace = ReadingPace.pace(summary, today, LocalDate(2026, 9, 20))

        pace.pagesPerHour.shouldBeNull()
        pace.minutesPerReadingDay.shouldBeNull()
        pace.chaptersPerWeek.shouldBeNull()
    }

    @Test
    fun `session lengths, median and bands`() {
        val start = LocalDateTime(2026, 9, 24, 10, 0)
        val sessions = listOf(2L, 10, 20, 45, 90).map { session(start, it) } + session(start, 5, active = 0)

        val summary = ReadingPace.sessions(sessions)

        summary.count shouldBe 5
        summary.medianMs shouldBe 20 * minute
        summary.averageMs shouldBe 33 * minute + 24_000
        summary.longest?.activeMs shouldBe 90 * minute
        summary.byLength shouldBe listOf(1, 1, 1, 1, 1)
        ReadingPace.sessions(listOf(session(start, 10), session(start, 20))).medianMs shouldBe 15 * minute
    }

    @Test
    fun `time spreads over the clock hours a session spanned, in its own zone`() {
        // 21:45 to 22:30 in São Paulo, 30 of its 45 minutes active: 10 before 22:00, 20 after.
        val hours = ReadingPace.byHour(
            listOf(session(LocalDateTime(2026, 9, 24, 21, 45), 45, "America/Sao_Paulo", active = 30 * minute)),
        )

        hours[21] shouldBe 10 * minute
        hours[22] shouldBe 20 * minute
        hours.sum() shouldBe 30 * minute
    }

    @Test
    fun `half-hour zones split at their own full hours`() {
        val hours = ReadingPace.byHour(listOf(session(LocalDateTime(2026, 9, 24, 7, 30), 60, "Asia/Kolkata")))

        hours[7] shouldBe 30 * minute
        hours[8] shouldBe 30 * minute
        ReadingPace.byDayPart(hours)[DayPart.MORNING] shouldBe 60 * minute
    }

    @Test
    fun `parts of the day`() {
        DayPart.of(4) shouldBe DayPart.NIGHT
        DayPart.of(5) shouldBe DayPart.MORNING
        DayPart.of(12) shouldBe DayPart.AFTERNOON
        DayPart.of(18) shouldBe DayPart.EVENING
        DayPart.of(23) shouldBe DayPart.NIGHT
    }

    @Test
    fun `time by weekday, Monday first`() {
        val days = listOf(
            ReadingDayStats(LocalDate(2026, 9, 21), activeMs = 5), // Monday
            ReadingDayStats(LocalDate(2026, 9, 27), activeMs = 7), // Sunday
            ReadingDayStats(LocalDate(2026, 9, 28), activeMs = 100), // outside
        )

        ReadingPace.byWeekday(days, ReadingPeriod(LocalDate(2026, 9, 21), LocalDate(2026, 9, 27))) shouldBe
            listOf(5L, 0, 0, 0, 0, 0, 7)
    }

    @Test
    fun `the recent rate needs two recorded weeks and two chapters`() {
        val days = listOf(
            ReadingDayStats(LocalDate(2026, 9, 1), completedInReader = 3),
            ReadingDayStats(LocalDate(2026, 9, 20), completedInReader = 2, completedManually = 1),
            ReadingDayStats(LocalDate(2026, 9, 21), completedImported = 9),
        )

        ReadingPace.weeklyRate(days, today, recordingStartedOn = LocalDate(2026, 9, 15)).shouldBeNull()
        val rate = ReadingPace.weeklyRate(days, today, recordingStartedOn = LocalDate(2026, 8, 1))!!
        rate.windowDays shouldBe 28
        rate.chapters shouldBe 6
        rate.perWeek shouldBe 1.5
        rate.weeksFor(3) shouldBe 2
        rate.weeksFor(6) shouldBe 4
    }

    @Test
    fun `finish estimates only for series in progress with a known total`() {
        fun entry(id: Long, read: Int, total: Int, finished: Boolean) = ComicLibrary.progress(
            LibraryEntry(
                mangaId = id,
                title = "Series $id",
                cover = null,
                publicationFinished = finished,
                dateAdded = 0,
                lastReadAt = null,
                chapters = List(total) { LibraryChapter(id * 100 + it, it < read, "#${it + 1}", -1.0, 0) },
                metadata = ComicMetadata.empty(id),
            ),
        )
        val rate = WeeklyRate(chapters = 4, windowDays = 28)

        val estimates = ReadingPace.finishEstimates(
            listOf(entry(1, read = 2, total = 10, finished = true), entry(2, 3, 5, true), entry(3, 1, 9, false)),
            rate,
        )

        estimates.map { it.progress.entry.mangaId to it.weeks } shouldBe listOf(2L to 2, 1L to 8)
    }
}
