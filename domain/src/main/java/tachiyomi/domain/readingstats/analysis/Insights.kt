package tachiyomi.domain.readingstats.analysis

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import tachiyomi.domain.readingstats.habits.Streaks
import tachiyomi.domain.readingstats.metrics.Coverage
import tachiyomi.domain.readingstats.metrics.PeriodSummary
import tachiyomi.domain.readingstats.metrics.ReadingCalendar
import tachiyomi.domain.readingstats.metrics.ReadingMetrics
import tachiyomi.domain.readingstats.metrics.ReadingPeriod
import tachiyomi.domain.readingstats.model.ReadingDayStats

/**
 * A short observation about reading, carrying every number it was drawn from so the screen can
 * show why it was said. Each one follows a single rule of [InsightRules].
 */
sealed interface Insight {

    /** Reading days in a row, up to today or yesterday. */
    data class Streak(val days: Int, val since: LocalDate, val record: Int) : Insight

    /** New pages this week against the same stretch of last week, both fully recorded. */
    data class WeekChange(
        val percent: Int,
        val pagesNow: Int,
        val pagesBefore: Int,
        val thisWeek: ReadingPeriod,
        val lastWeek: ReadingPeriod,
    ) : Insight

    /** This week has the most reading time of any recorded week so far. */
    data class BestWeek(
        val activeMs: Long,
        val previousBestMs: Long,
        val previousBest: ReadingPeriod,
        val weeksCompared: Int,
    ) : Insight

    /** One weekday holds a clear share of the reading time of the last weeks. */
    data class FavoriteWeekday(
        val weekday: DayOfWeek,
        val activeMs: Long,
        val totalMs: Long,
        val period: ReadingPeriod,
    ) : Insight {
        val percent: Int get() = (activeMs * 100 / totalMs).toInt()
    }

    /** Most of the recent reading time falls in one part of the day. */
    data class FavoriteDayPart(
        val part: DayPart,
        val activeMs: Long,
        val totalMs: Long,
        val sessions: Int,
        val period: ReadingPeriod,
    ) : Insight {
        val percent: Int get() = (activeMs * 100 / totalMs).toInt()
    }

    /** One series takes a large share of the recent reading time. */
    data class TopSeries(
        val title: String,
        val activeMs: Long,
        val totalMs: Long,
        val period: ReadingPeriod,
    ) : Insight {
        val percent: Int get() = (activeMs * 100 / totalMs).toInt()
    }

    /** At the recent pace, the unread chapters of the library would take this long. Conditional. */
    data class BacklogPace(val backlog: Int, val rate: WeeklyRate, val weeks: Int) : Insight
}

/** Everything the rules look at. */
data class InsightInput(
    val today: LocalDate,
    val firstDayOfWeek: DayOfWeek,
    val recordingStartedOn: LocalDate,
    /** Every recorded day, with completions. */
    val days: List<ReadingDayStats>,
    val streaks: Streaks,
    val thisWeek: PeriodSummary,
    /** The same stretch of last week. */
    val lastWeek: PeriodSummary,
    /** Reader sessions of the last [InsightRules.RECENT_DAYS] days. */
    val recentSessions: List<SessionFacts>,
    /** Time per chapter over the last [InsightRules.RECENT_DAYS] days. */
    val recentReadings: List<ChapterReading>,
    /** Unread chapters of the library, variants aside. */
    val backlog: Int,
)

object InsightRules {

    const val RECENT_DAYS = 28
    const val MAX_SHOWN = 3

    const val STREAK_MIN_DAYS = 3
    const val WEEK_CHANGE_MIN_PERCENT = 25
    const val WEEK_CHANGE_MIN_PAGES = 20
    const val BEST_WEEK_MIN_WEEKS = 3
    const val WEEKDAY_MIN_PERCENT = 30
    const val WEEKDAY_MIN_WEEKS = 4
    const val DAY_PART_MIN_PERCENT = 50
    const val DAY_PART_MIN_SESSIONS = 5
    const val TOP_SERIES_MIN_PERCENT = 40
    const val RECENT_MIN_ACTIVE_MS = 30L * 60 * 1000

    /** Every rule that holds, most telling first. */
    fun of(input: InsightInput): List<Insight> = listOfNotNull(
        bestWeek(input),
        weekChange(input),
        streak(input),
        topSeries(input),
        favoriteDayPart(input),
        favoriteWeekday(input),
        backlogPace(input),
    )

    fun streak(input: InsightInput): Insight.Streak? {
        val streaks = input.streaks
        if (streaks.current < STREAK_MIN_DAYS) return null
        val last = if (streaks.readToday) input.today else input.today.minus(1, DateTimeUnit.DAY)
        return Insight.Streak(streaks.current, last.minus(streaks.current - 1, DateTimeUnit.DAY), streaks.record)
    }

    fun weekChange(input: InsightInput): Insight.WeekChange? {
        val now = input.thisWeek
        val before = input.lastWeek
        if (before.pagesNew < WEEK_CHANGE_MIN_PAGES) return null
        val trend = ReadingMetrics.trend(now.pagesNew.toLong(), before.pagesNew.toLong(), before.coverage)
            ?: return null
        if (kotlin.math.abs(trend.percent) < WEEK_CHANGE_MIN_PERCENT) return null
        return Insight.WeekChange(trend.percent, now.pagesNew, before.pagesNew, now.period, before.period)
    }

    fun bestWeek(input: InsightInput): Insight.BestWeek? {
        val thisWeek = ReadingCalendar.weekOf(input.today, input.firstDayOfWeek)
        val weeks = input.days
            .filter { it.date >= input.recordingStartedOn }
            .groupBy { ReadingCalendar.weekOf(it.date, input.firstDayOfWeek) }
            .mapValues { (_, days) -> days.sumOf { it.activeMs } }
        val current = weeks[thisWeek] ?: return null
        val earlier = weeks.filterKeys { it != thisWeek && it.to < thisWeek.from }.filterValues { it > 0 }
        if (earlier.size < BEST_WEEK_MIN_WEEKS) return null
        val best = earlier.maxBy { it.value }
        if (current <= best.value) return null
        return Insight.BestWeek(current, best.value, best.key, earlier.size)
    }

    fun favoriteWeekday(input: InsightInput): Insight.FavoriteWeekday? {
        val period = recent(input)
        // Four whole weeks, each weekday four times, all of them recorded.
        if (period.days / 7 < WEEKDAY_MIN_WEEKS || !fullyRecorded(period, input.recordingStartedOn)) return null
        val byWeekday = ReadingPace.byWeekday(input.days, period)
        val total = byWeekday.sum()
        if (total < RECENT_MIN_ACTIVE_MS) return null
        val (index, top) = byWeekday.withIndex().maxBy { it.value }
        if (top * 100 / total < WEEKDAY_MIN_PERCENT) return null
        return Insight.FavoriteWeekday(DayOfWeek.entries[index], top, total, period)
    }

    fun favoriteDayPart(input: InsightInput): Insight.FavoriteDayPart? {
        val sessions = input.recentSessions.filter { it.activeMs > 0 }
        if (sessions.size < DAY_PART_MIN_SESSIONS) return null
        val parts = ReadingPace.byDayPart(ReadingPace.byHour(sessions))
        val total = parts.values.sum()
        if (total < RECENT_MIN_ACTIVE_MS) return null
        val (part, top) = parts.maxBy { it.value }
        if (top * 100 / total < DAY_PART_MIN_PERCENT) return null
        return Insight.FavoriteDayPart(part, top, total, sessions.size, recent(input))
    }

    fun topSeries(input: InsightInput): Insight.TopSeries? {
        val total = input.recentReadings.sumOf { it.activeMs }
        if (total < RECENT_MIN_ACTIVE_MS) return null
        val ranking = Rankings.of(RankingDimension.SERIES, input.recentReadings, emptyMap(), top = 1)
        val top = ranking.entries.firstOrNull() ?: return null
        if (top.activeMs * 100 / total < TOP_SERIES_MIN_PERCENT) return null
        // Reading a single series is not news; a clear favourite among several is.
        if (input.recentReadings.map { it.title }.distinct().size < 2) return null
        return Insight.TopSeries(top.value, top.activeMs, total, recent(input))
    }

    fun backlogPace(input: InsightInput): Insight.BacklogPace? {
        if (input.backlog <= 0) return null
        val rate = ReadingPace.weeklyRate(input.days, input.today, input.recordingStartedOn) ?: return null
        return Insight.BacklogPace(input.backlog, rate, rate.weeksFor(input.backlog))
    }

    /** The last [RECENT_DAYS] days, today included. */
    fun recent(input: InsightInput): ReadingPeriod =
        ReadingPeriod(input.today.minus(RECENT_DAYS - 1, DateTimeUnit.DAY), input.today)

    /** Whether the whole of [period] was recorded. */
    fun fullyRecorded(period: ReadingPeriod, recordingStartedOn: LocalDate): Boolean =
        ReadingMetrics.coverage(period, recordingStartedOn) == Coverage.FULL
}
