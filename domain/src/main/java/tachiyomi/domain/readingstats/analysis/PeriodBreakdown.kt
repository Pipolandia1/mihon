package tachiyomi.domain.readingstats.analysis

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import tachiyomi.domain.readingstats.metrics.Coverage
import tachiyomi.domain.readingstats.metrics.ReadingCalendar
import tachiyomi.domain.readingstats.metrics.ReadingMetrics
import tachiyomi.domain.readingstats.metrics.ReadingPeriod
import tachiyomi.domain.readingstats.model.ReadingDayStats

/** The periods the details screen offers. Rolling ones end today. */
enum class PeriodChoice {
    LAST_7_DAYS,
    LAST_30_DAYS,
    LAST_12_MONTHS,

    /** From the first day anything was recorded or carried over. */
    ALL,
    CUSTOM,
}

/** How a chart splits a period. */
enum class BucketSize { DAY, WEEK, MONTH }

/** One bar of a chart: a stretch of the period and what was read in it. */
data class ChartBucket(
    val period: ReadingPeriod,
    val activeMs: Long,
    val pagesNew: Int,
    /** Chapters finished in the reader or marked read by hand; carried-over ones are not dated reading. */
    val completed: Int,
    /** NONE for a stretch before recording began: its bar is unknown, not empty. */
    val coverage: Coverage,
)

object PeriodBreakdown {

    /** A chart shows days up to a month, weeks up to half a year, and months beyond. */
    const val MAX_DAY_BUCKETS = 31
    const val MAX_WEEK_BUCKETS = 26

    /**
     * The period a choice means today. [firstActivity] is the earliest day with anything recorded
     * or carried over; [custom] is the picked range.
     */
    fun periodOf(
        choice: PeriodChoice,
        today: LocalDate,
        firstActivity: LocalDate?,
        custom: ReadingPeriod?,
    ): ReadingPeriod = when (choice) {
        PeriodChoice.LAST_7_DAYS -> ReadingPeriod(today.minus(6, DateTimeUnit.DAY), today)
        PeriodChoice.LAST_30_DAYS -> ReadingPeriod(today.minus(29, DateTimeUnit.DAY), today)
        PeriodChoice.LAST_12_MONTHS ->
            ReadingPeriod(today.minus(DatePeriod(years = 1)).plus(1, DateTimeUnit.DAY), today)
        PeriodChoice.ALL -> ReadingPeriod(minOf(firstActivity ?: today, today), today)
        PeriodChoice.CUSTOM -> custom ?: ReadingPeriod(today, today)
    }

    /** The period of the same length just before [period], to compare with. */
    fun previous(period: ReadingPeriod): ReadingPeriod = period.shift(-period.days)

    fun bucketSize(period: ReadingPeriod): BucketSize = when {
        period.days <= MAX_DAY_BUCKETS -> BucketSize.DAY
        period.days <= MAX_WEEK_BUCKETS * 7 -> BucketSize.WEEK
        else -> BucketSize.MONTH
    }

    /**
     * The period cut into days, weeks or months, each with its totals. Weeks and months are
     * clipped to the period, so the first and last bars may be shorter.
     */
    fun buckets(
        period: ReadingPeriod,
        days: List<ReadingDayStats>,
        firstDayOfWeek: DayOfWeek,
        recordingStartedOn: LocalDate,
    ): List<ChartBucket> {
        val byDate = days.filter { it.date in period }.groupBy { it.date }
        return stretches(period, bucketSize(period), firstDayOfWeek).map { stretch ->
            val inStretch = byDate.filterKeys { it in stretch }.values.flatten()
            ChartBucket(
                period = stretch,
                activeMs = inStretch.sumOf { it.activeMs },
                pagesNew = inStretch.sumOf { it.pagesNew },
                completed = inStretch.sumOf { it.completedInReader + it.completedManually },
                coverage = ReadingMetrics.coverage(stretch, recordingStartedOn),
            )
        }
    }

    private fun stretches(period: ReadingPeriod, size: BucketSize, firstDayOfWeek: DayOfWeek): List<ReadingPeriod> {
        val result = mutableListOf<ReadingPeriod>()
        var start = period.from
        while (start <= period.to) {
            val end = when (size) {
                BucketSize.DAY -> start
                BucketSize.WEEK -> ReadingCalendar.weekOf(start, firstDayOfWeek).to
                BucketSize.MONTH -> LocalDate(start.year, start.month, 1).plus(DatePeriod(months = 1))
                    .minus(1, DateTimeUnit.DAY)
            }
            val clipped = minOf(end, period.to)
            result += ReadingPeriod(start, clipped)
            start = clipped.plus(1, DateTimeUnit.DAY)
        }
        return result
    }
}
