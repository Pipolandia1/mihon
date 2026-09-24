package tachiyomi.domain.readingstats.metrics

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.junit.jupiter.api.Test
import tachiyomi.domain.readingstats.model.ReadingDayStats
import kotlin.time.Duration.Companion.hours

class ReadingMetricsTest {

    @Test
    fun `a span is split at local midnight`() {
        val zone = TimeZone.of("America/Sao_Paulo")
        val from = LocalDateTime(2026, 9, 24, 23, 0).toInstant(zone).toEpochMilliseconds()
        val to = LocalDateTime(2026, 9, 25, 1, 30).toInstant(zone).toEpochMilliseconds()

        ReadingCalendar.splitByDay(from, to, zone) shouldBe listOf(
            LocalDate(2026, 9, 24) to 1.hours.inWholeMilliseconds,
            LocalDate(2026, 9, 25) to 90 * 60_000L,
        )
    }

    @Test
    fun `a day shortened by a clock change gets only the time that passed`() {
        val zone = TimeZone.of("Europe/Berlin")
        // Clocks go forward at 02:00 on 29 March 2026: that day lasts 23 hours.
        val from = LocalDateTime(2026, 3, 29, 0, 0).toInstant(zone).toEpochMilliseconds()
        val to = LocalDateTime(2026, 3, 30, 0, 0).toInstant(zone).toEpochMilliseconds()

        ReadingCalendar.splitByDay(from, to, zone) shouldBe
            listOf(LocalDate(2026, 3, 29) to 23.hours.inWholeMilliseconds)
    }

    @Test
    fun `weeks start on the given day`() {
        val thursday = LocalDate(2026, 9, 24)

        ReadingCalendar.weekOf(thursday, DayOfWeek.MONDAY) shouldBe
            ReadingPeriod(LocalDate(2026, 9, 21), LocalDate(2026, 9, 27))
        ReadingCalendar.weekOf(thursday, DayOfWeek.SUNDAY) shouldBe
            ReadingPeriod(LocalDate(2026, 9, 20), LocalDate(2026, 9, 26))
        ReadingCalendar.weekOf(LocalDate(2026, 9, 20), DayOfWeek.SUNDAY).from shouldBe LocalDate(2026, 9, 20)
    }

    @Test
    fun `coverage tells recorded periods from partly and not recorded ones`() {
        val start = LocalDate(2026, 9, 23)
        val week = ReadingPeriod(LocalDate(2026, 9, 21), LocalDate(2026, 9, 27))

        ReadingMetrics.coverage(week, start) shouldBe Coverage.PARTIAL
        ReadingMetrics.coverage(week.shift(7), start) shouldBe Coverage.FULL
        ReadingMetrics.coverage(week.shift(-7), start) shouldBe Coverage.NONE
        ReadingMetrics.coverage(ReadingPeriod(start, start), start) shouldBe Coverage.PARTIAL
    }

    @Test
    fun `a period sums its days and counts reading days by the threshold`() {
        val week = ReadingPeriod(LocalDate(2026, 9, 21), LocalDate(2026, 9, 27))
        val days = listOf(
            ReadingDayStats(LocalDate(2026, 9, 20), activeMs = 999_999, pagesNew = 99),
            ReadingDayStats(LocalDate(2026, 9, 21), activeMs = 60_000, pagesNew = 10, completedInReader = 1),
            ReadingDayStats(LocalDate(2026, 9, 22), activeMs = 59_999, pagesNew = 1, completedManually = 4),
        )

        val summary = ReadingMetrics.summarize(week, days, recordingStartedOn = LocalDate(2026, 1, 1))

        summary.activeMs shouldBe 119_999
        summary.pagesNew shouldBe 11
        summary.readingDays shouldBe 1
        summary.completedInReader shouldBe 1
        summary.completedManually shouldBe 4
        summary.coverage shouldBe Coverage.FULL
    }

    @Test
    fun `a week in progress compares with the same stretch of last week`() {
        val week = ReadingPeriod(LocalDate(2026, 9, 21), LocalDate(2026, 9, 27))

        ReadingMetrics.sameStretchLastWeek(week, today = LocalDate(2026, 9, 23)) shouldBe
            ReadingPeriod(LocalDate(2026, 9, 14), LocalDate(2026, 9, 16))
        ReadingMetrics.sameStretchLastWeek(week, today = LocalDate(2026, 10, 1)) shouldBe
            ReadingPeriod(LocalDate(2026, 9, 14), LocalDate(2026, 9, 20))
    }

    @Test
    fun `a trend needs a fully recorded, non-zero base`() {
        ReadingMetrics.trend(150, 100, Coverage.FULL)?.percent shouldBe 50
        ReadingMetrics.trend(50, 100, Coverage.FULL)?.percent shouldBe -50
        ReadingMetrics.trend(150, 0, Coverage.FULL).shouldBeNull()
        ReadingMetrics.trend(150, 100, Coverage.PARTIAL).shouldBeNull()
        ReadingMetrics.trend(150, 100, Coverage.NONE).shouldBeNull()
    }
}
