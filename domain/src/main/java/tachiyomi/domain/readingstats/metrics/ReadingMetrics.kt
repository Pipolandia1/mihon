package tachiyomi.domain.readingstats.metrics

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import tachiyomi.domain.readingstats.model.ReadingDayStats
import kotlin.time.Duration.Companion.seconds

/** How much of a period fell after reading began to be recorded. */
enum class Coverage {
    /** Every day of the period was recorded from its start. */
    FULL,

    /** Recording began during the period: its totals miss what came before. */
    PARTIAL,

    /** The period ended before recording began: time and pages are unknown, not zero. */
    NONE,
}

/** A period's reading, as every card, goal and insight must count it. */
data class PeriodSummary(
    val period: ReadingPeriod,
    val activeMs: Long,
    val pagesNew: Int,
    val pagesVisited: Int,
    val completedInReader: Int,
    val completedManually: Int,
    val completedImported: Int,
    /** Days with at least [ReadingMetrics.READING_DAY_MIN_ACTIVE] of reading. */
    val readingDays: Int,
    val coverage: Coverage,
)

/** A change against a comparable earlier period. */
data class Trend(val percent: Int)

/** The single definition of each reading figure. */
object ReadingMetrics {

    /** Active reading that makes a day count as a reading day. */
    val READING_DAY_MIN_ACTIVE = 60.seconds

    fun isReadingDay(day: ReadingDayStats): Boolean = day.activeMs >= READING_DAY_MIN_ACTIVE.inWholeMilliseconds

    /** [recordingStartedOn] is the day reading began to be recorded; that day itself is partial. */
    fun coverage(period: ReadingPeriod, recordingStartedOn: LocalDate): Coverage = when {
        period.from > recordingStartedOn -> Coverage.FULL
        period.to >= recordingStartedOn -> Coverage.PARTIAL
        else -> Coverage.NONE
    }

    fun summarize(period: ReadingPeriod, days: List<ReadingDayStats>, recordingStartedOn: LocalDate): PeriodSummary {
        val inPeriod = days.filter { it.date in period }
        return PeriodSummary(
            period = period,
            activeMs = inPeriod.sumOf { it.activeMs },
            pagesNew = inPeriod.sumOf { it.pagesNew },
            pagesVisited = inPeriod.sumOf { it.pagesVisited },
            completedInReader = inPeriod.sumOf { it.completedInReader },
            completedManually = inPeriod.sumOf { it.completedManually },
            completedImported = inPeriod.sumOf { it.completedImported },
            readingDays = inPeriod.count(::isReadingDay),
            coverage = coverage(period, recordingStartedOn),
        )
    }

    /**
     * The same stretch one week earlier: a week in progress on its third day compares with the
     * first three days of last week, not with all of it.
     */
    fun sameStretchLastWeek(period: ReadingPeriod, today: LocalDate): ReadingPeriod {
        val lastWeek = period.shift(-7)
        val elapsedEnd = minOf(period.to, today).minus(7, DateTimeUnit.DAY)
        return ReadingPeriod(lastWeek.from, maxOf(lastWeek.from, elapsedEnd))
    }

    /**
     * The change from [previous] to [current], only when the earlier figure was fully recorded and
     * not zero: a percentage of nothing, or of a half-recorded week, would mean nothing.
     */
    fun trend(current: Long, previous: Long, previousCoverage: Coverage): Trend? {
        if (previousCoverage != Coverage.FULL || previous <= 0) return null
        return Trend(((current - previous) * 100 / previous).toInt())
    }
}
