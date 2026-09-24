package tachiyomi.domain.readingstats.habits

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import tachiyomi.domain.readingstats.metrics.ReadingPeriod
import tachiyomi.domain.readingstats.model.ReadingDayStats

class ReadingHabitsTest {

    private fun day(d: Int, minutes: Int, pages: Int = 0, month: Int = 9) =
        ReadingDayStats(LocalDate(2026, month, d), activeMs = minutes * 60_000L, pagesNew = pages)

    @Test
    fun `today not read yet does not break the streak`() {
        val days = listOf(day(20, 5), day(21, 5), day(22, 5))

        val streaks = ReadingHabits.streaks(days, today = LocalDate(2026, 9, 23))

        streaks.current shouldBe 3
        streaks.readToday shouldBe false
    }

    @Test
    fun `reading today extends the streak, a day off ends it`() {
        val days = listOf(day(18, 5), day(20, 5), day(21, 5), day(22, 5), day(23, 5))

        ReadingHabits.streaks(days, today = LocalDate(2026, 9, 23)).current shouldBe 4
        ReadingHabits.streaks(days, today = LocalDate(2026, 9, 25)).current shouldBe 0
    }

    @Test
    fun `the record is the longest run ever, and short days are not reading days`() {
        val days = listOf(
            day(1, 5),
            day(2, 5),
            day(3, 5),
            day(4, 5),
            day(5, 5),
            day(10, 5),
            day(11, 0),
            day(12, 5),
        ).map { if (it.date.day == 11) it.copy(activeMs = 59_999) else it }

        val streaks = ReadingHabits.streaks(days, today = LocalDate(2026, 9, 12))

        streaks.record shouldBe 5
        streaks.current shouldBe 1
    }

    @Test
    fun `a month change does not break a run`() {
        val days = listOf(day(30, 5, month = 9), day(1, 5, month = 10))

        ReadingHabits.streaks(days, today = LocalDate(2026, 10, 1)).current shouldBe 2
    }

    @Test
    fun `frequency averages only whole recorded weeks before this one`() {
        // Weeks start on Sunday: 6-12, 13-19, 20-26 (this week).
        val days = listOf(day(7, 5), day(8, 5), day(14, 5), day(15, 5), day(16, 5), day(17, 5), day(21, 5))

        val frequency = ReadingHabits.frequency(
            days = days,
            today = LocalDate(2026, 9, 23),
            firstDayOfWeek = DayOfWeek.SUNDAY,
            recordingStartedOn = LocalDate(2026, 9, 5),
        ).shouldNotBeNull()

        frequency.weeks shouldBe 2
        frequency.daysPerWeek shouldBe 3.0
    }

    @Test
    fun `no frequency before a whole week was recorded`() {
        ReadingHabits.frequency(
            days = listOf(day(22, 5)),
            today = LocalDate(2026, 9, 23),
            firstDayOfWeek = DayOfWeek.SUNDAY,
            recordingStartedOn = LocalDate(2026, 9, 18),
        ).shouldBeNull()
    }

    @Test
    fun `records pick the longest day, the most pages and the best week`() {
        val days = listOf(day(7, 30, 10), day(8, 40, 5), day(14, 20, 50), day(15, 20, 0), day(16, 20, 0))

        val records = ReadingHabits.records(days, DayOfWeek.SUNDAY, longestSession = null)

        records.longestDay shouldBe DayRecord(LocalDate(2026, 9, 8), 40 * 60_000L)
        records.mostPagesDay shouldBe DayRecord(LocalDate(2026, 9, 14), 50)
        records.bestWeek shouldBe WeekRecord(ReadingPeriod(LocalDate(2026, 9, 6), LocalDate(2026, 9, 12)), 70 * 60_000L)
        records.longestSession.shouldBeNull()
    }

    @Test
    fun `no records without reading`() {
        val records = ReadingHabits.records(emptyList(), DayOfWeek.MONDAY, longestSession = null)

        records.longestDay.shouldBeNull()
        records.bestWeek.shouldBeNull()
    }

    @Test
    fun `heatmap levels follow the time read, and a day below a minute is empty`() {
        listOf(0, 59_999, 60_000, 14 * 60_000, 15 * 60_000, 45 * 60_000, 60 * 60_000, 300 * 60_000)
            .map { ReadingHabits.intensity(it.toLong()) } shouldBe listOf(0, 0, 1, 1, 2, 3, 4, 4)
    }
}
