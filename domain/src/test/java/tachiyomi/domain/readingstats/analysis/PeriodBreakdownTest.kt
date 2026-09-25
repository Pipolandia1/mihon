package tachiyomi.domain.readingstats.analysis

import io.kotest.matchers.shouldBe
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import org.junit.jupiter.api.Test
import tachiyomi.domain.readingstats.metrics.Coverage
import tachiyomi.domain.readingstats.metrics.ReadingPeriod
import tachiyomi.domain.readingstats.model.ReadingDayStats

class PeriodBreakdownTest {

    private val today = LocalDate(2026, 9, 24)
    private val recording = LocalDate(2026, 9, 10)

    @Test
    fun `rolling periods end today`() {
        PeriodBreakdown.periodOf(PeriodChoice.LAST_7_DAYS, today, null, null) shouldBe
            ReadingPeriod(LocalDate(2026, 9, 18), today)
        PeriodBreakdown.periodOf(PeriodChoice.LAST_30_DAYS, today, null, null).days shouldBe 30
        PeriodBreakdown.periodOf(PeriodChoice.LAST_12_MONTHS, today, null, null) shouldBe
            ReadingPeriod(LocalDate(2025, 9, 25), today)
        PeriodBreakdown.periodOf(PeriodChoice.ALL, today, LocalDate(2024, 1, 5), null) shouldBe
            ReadingPeriod(LocalDate(2024, 1, 5), today)
        PeriodBreakdown.periodOf(PeriodChoice.ALL, today, null, null) shouldBe ReadingPeriod(today, today)
        val custom = ReadingPeriod(LocalDate(2026, 1, 1), LocalDate(2026, 1, 31))
        PeriodBreakdown.periodOf(PeriodChoice.CUSTOM, today, null, custom) shouldBe custom
        PeriodBreakdown.previous(custom) shouldBe ReadingPeriod(LocalDate(2025, 12, 1), LocalDate(2025, 12, 31))
    }

    @Test
    fun `days up to a month, weeks up to half a year, then months`() {
        fun size(days: Int) = PeriodBreakdown.bucketSize(ReadingPeriod(today.minus(days - 1, DateTimeUnit.DAY), today))
        size(7) shouldBe BucketSize.DAY
        size(31) shouldBe BucketSize.DAY
        size(32) shouldBe BucketSize.WEEK
        size(182) shouldBe BucketSize.WEEK
        size(183) shouldBe BucketSize.MONTH
    }

    @Test
    fun `weeks are clipped to the period and unrecorded ones are unknown`() {
        // Sunday weeks; the period runs Wednesday 2 Sep to Thursday 8 Oct (37 days).
        val period = ReadingPeriod(LocalDate(2026, 9, 2), LocalDate(2026, 10, 8))
        val days = listOf(
            ReadingDayStats(LocalDate(2026, 9, 15), activeMs = 600_000, pagesNew = 20, completedInReader = 1),
            ReadingDayStats(LocalDate(2026, 9, 16), activeMs = 300_000, pagesNew = 5, completedManually = 2),
            ReadingDayStats(LocalDate(2026, 9, 17), completedImported = 7),
        )

        val buckets = PeriodBreakdown.buckets(period, days, DayOfWeek.SUNDAY, recording)

        buckets.first().period shouldBe ReadingPeriod(LocalDate(2026, 9, 2), LocalDate(2026, 9, 5))
        buckets.last().period shouldBe ReadingPeriod(LocalDate(2026, 10, 4), LocalDate(2026, 10, 8))
        buckets.first().coverage shouldBe Coverage.NONE
        val week = buckets.single { LocalDate(2026, 9, 15) in it.period }
        week.activeMs shouldBe 900_000
        week.pagesNew shouldBe 25
        // Carried-over completions are not reading done that week.
        week.completed shouldBe 3
        buckets.sumOf { it.period.days } shouldBe period.days
    }

    @Test
    fun `a year is cut into calendar months`() {
        val period = ReadingPeriod(LocalDate(2025, 9, 25), today)

        val buckets = PeriodBreakdown.buckets(period, emptyList(), DayOfWeek.MONDAY, recording)

        buckets.size shouldBe 13
        buckets.first().period shouldBe ReadingPeriod(LocalDate(2025, 9, 25), LocalDate(2025, 9, 30))
        buckets[1].period shouldBe ReadingPeriod(LocalDate(2025, 10, 1), LocalDate(2025, 10, 31))
        buckets.last().period shouldBe ReadingPeriod(LocalDate(2026, 9, 1), today)
    }
}
