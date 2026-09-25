package tachiyomi.domain.readingstats.analysis

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import org.junit.jupiter.api.Test
import tachiyomi.domain.readingstats.habits.ReadingHabits
import tachiyomi.domain.readingstats.metrics.ReadingCalendar
import tachiyomi.domain.readingstats.metrics.ReadingMetrics
import tachiyomi.domain.readingstats.model.ReadingDayStats

class InsightsTest {

    // A Thursday; weeks start on Sunday.
    private val today = LocalDate(2026, 9, 24)
    private val minute = 60_000L

    private fun input(
        days: List<ReadingDayStats> = emptyList(),
        recording: LocalDate = LocalDate(2026, 6, 1),
        sessions: List<SessionFacts> = emptyList(),
        readings: List<ChapterReading> = emptyList(),
        backlog: Int = 0,
    ): InsightInput {
        val week = ReadingCalendar.weekOf(today, DayOfWeek.SUNDAY)
        val lastWeek = ReadingMetrics.sameStretchLastWeek(week, today)
        return InsightInput(
            today = today,
            firstDayOfWeek = DayOfWeek.SUNDAY,
            recordingStartedOn = recording,
            days = days,
            streaks = ReadingHabits.streaks(days, today),
            thisWeek = ReadingMetrics.summarize(week, days, recording),
            lastWeek = ReadingMetrics.summarize(lastWeek, days, recording),
            recentSessions = sessions,
            recentReadings = readings,
            backlog = backlog,
        )
    }

    private fun day(daysAgo: Int, minutes: Long = 10, pages: Int = 0, completed: Int = 0) =
        ReadingDayStats(today.minus(daysAgo, DateTimeUnit.DAY), minutes * minute, pages, completedInReader = completed)

    private fun session(hour: Int, minutes: Long) = SessionFacts(
        id = hour.toLong(),
        startedAt = LocalDateTime(2026, 9, 20, hour, 0).toInstant(TimeZone.UTC).toEpochMilliseconds(),
        endedAt = LocalDateTime(2026, 9, 20, hour, 0).toInstant(TimeZone.UTC).toEpochMilliseconds() + minutes * minute,
        activeMs = minutes * minute,
        pagesNew = 1,
        zoneId = "UTC",
        mangaId = 1,
        chapterId = 1,
        title = "T",
        chapterName = "#1",
        chapterNumber = 1.0,
    )

    @Test
    fun `a streak of three days or more`() {
        InsightRules.streak(input(listOf(day(0), day(1)))).shouldBeNull()

        val streak = InsightRules.streak(input(listOf(day(1), day(2), day(3))))!!

        streak.days shouldBe 3
        streak.since shouldBe LocalDate(2026, 9, 21)
    }

    @Test
    fun `a week change needs a fully recorded base, enough pages and a clear difference`() {
        // This week Sunday to Thursday against last Sunday to Thursday.
        val lastWeek = listOf(day(8, pages = 10), day(9, pages = 20))
        val thisWeek = listOf(day(1, pages = 45))

        val change = InsightRules.weekChange(input(lastWeek + thisWeek))!!

        change.percent shouldBe 50
        change.pagesBefore shouldBe 30
        InsightRules.weekChange(input(lastWeek + listOf(day(1, pages = 33)))).shouldBeNull()
        InsightRules.weekChange(input(listOf(day(8, pages = 10)) + thisWeek)).shouldBeNull()
        // Last week half recorded: no percentage of it.
        InsightRules.weekChange(input(lastWeek + thisWeek, recording = LocalDate(2026, 9, 16))).shouldBeNull()
    }

    @Test
    fun `the best week needs three earlier weeks to beat`() {
        val earlier = listOf(day(8, 20), day(15, 30), day(22, 25))

        InsightRules.bestWeek(input(earlier + day(0, 31)))!!.previousBestMs shouldBe 30 * minute
        InsightRules.bestWeek(input(earlier + day(0, 30))).shouldBeNull()
        InsightRules.bestWeek(input(earlier.drop(1) + day(0, 60))).shouldBeNull()
    }

    @Test
    fun `a favourite weekday over four recorded weeks`() {
        // The four Saturdays of the last 28 days (19, 12 and 5 September, 29 August) hold most of the time.
        val saturdays = listOf(5, 12, 19, 26).map { day(it, 30) }
        val others = listOf(1, 3, 8, 10).map { day(it, 10) }

        val favourite = InsightRules.favoriteWeekday(input(saturdays + others))!!

        favourite.weekday shouldBe DayOfWeek.SATURDAY
        favourite.percent shouldBe 75
        InsightRules.favoriteWeekday(input(saturdays + others, recording = LocalDate(2026, 9, 1))).shouldBeNull()
    }

    @Test
    fun `a favourite part of the day needs five sessions and half the time`() {
        val evening = List(4) { session(20, 15) }
        val morning = listOf(session(8, 10))

        val part = InsightRules.favoriteDayPart(input(sessions = evening + morning))!!

        part.part shouldBe DayPart.EVENING
        part.percent shouldBe 85
        InsightRules.favoriteDayPart(input(sessions = evening)).shouldBeNull()
    }

    @Test
    fun `a top series among several`() {
        val readings = listOf(ChapterReading(1, 1, "Saga", 30 * minute), ChapterReading(2, 2, "Batman", 10 * minute))

        InsightRules.topSeries(input(readings = readings))!!.percent shouldBe 75
        InsightRules.topSeries(input(readings = readings.take(1))).shouldBeNull()
    }

    @Test
    fun `backlog at the recent pace is only said with a pace to go by`() {
        val days = listOf(day(3, completed = 2), day(10, completed = 2))

        val backlog = InsightRules.backlogPace(input(days, backlog = 10))!!

        backlog.rate.perWeek shouldBe 1.0
        backlog.weeks shouldBe 10
        InsightRules.backlogPace(input(days, backlog = 0)).shouldBeNull()
        InsightRules.backlogPace(input(listOf(day(3, completed = 1)), backlog = 10)).shouldBeNull()
    }

    @Test
    fun `rules come most telling first`() {
        val days = listOf(day(0, 40), day(1, 10), day(2, 10), day(8, 10), day(15, 10), day(22, 10))

        val insights = InsightRules.of(input(days))

        insights.first().shouldBeInstanceOf<Insight.BestWeek>()
        insights[1].shouldBeInstanceOf<Insight.Streak>()
    }
}
