package tachiyomi.domain.readingstats.analysis

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import tachiyomi.domain.readingstats.comics.EntryProgress
import tachiyomi.domain.readingstats.comics.LibraryStatus
import tachiyomi.domain.readingstats.metrics.PeriodSummary
import tachiyomi.domain.readingstats.metrics.ReadingPeriod
import tachiyomi.domain.readingstats.model.ReadingDayStats
import kotlin.time.Instant

/** A reader session as analysis sees it. */
data class SessionFacts(
    val id: Long,
    val startedAt: Long,
    val endedAt: Long,
    val activeMs: Long,
    val pagesNew: Int,
    /** The time zone in force when it was read. */
    val zoneId: String,
    val mangaId: Long?,
    val chapterId: Long?,
    val title: String,
    val chapterName: String,
    val chapterNumber: Double,
)

/** How fast and how much, over the recorded part of a period. */
data class PaceSummary(
    /** New pages per hour of active reading; null below [ReadingPace.MIN_ACTIVE_FOR_RATE_MS]. */
    val pagesPerHour: Int?,
    /** Active minutes per reading day; null without reading days. */
    val minutesPerReadingDay: Long?,
    /** Chapters finished per week; null when less than a week of the period was recorded. */
    val chaptersPerWeek: Double?,
    /** Days of the period that were recorded and have passed. */
    val recordedDays: Int,
)

data class SessionSummary(
    val count: Int,
    val averageMs: Long,
    val medianMs: Long,
    val longest: SessionFacts?,
    /** Sessions per length band: under 5 min, 5–15, 15–30, 30–60, an hour or more. */
    val byLength: List<Int>,
)

/** Parts of the day, for "when do you read": 5–12, 12–18, 18–23 and 23–5. */
enum class DayPart {
    MORNING,
    AFTERNOON,
    EVENING,
    NIGHT,
    ;

    companion object {
        fun of(hour: Int): DayPart = when (hour) {
            in 5..11 -> MORNING
            in 12..17 -> AFTERNOON
            in 18..22 -> EVENING
            else -> NIGHT
        }
    }
}

/** Finishing a series at the recent pace: an estimate that always says what it assumes. */
data class FinishEstimate(val progress: EntryProgress, val weeks: Int)

/** Chapters finished per week over the last [ReadingPace.RATE_WINDOW_DAYS] recorded days. */
data class WeeklyRate(val chapters: Int, val windowDays: Int) {
    val perWeek: Double get() = chapters * DAYS_PER_WEEK / windowDays

    /** Weeks to finish [remaining] at this rate, rounded up, in whole numbers. */
    fun weeksFor(remaining: Int): Int =
        ((remaining.toLong() * windowDays + chapters * 7L - 1) / (chapters * 7L)).toInt()

    private companion object {
        const val DAYS_PER_WEEK = 7.0
    }
}

object ReadingPace {

    const val MIN_ACTIVE_FOR_RATE_MS = 10L * 60 * 1000
    const val MIN_DAYS_FOR_WEEKLY = 7

    /** Upper bounds of the session length bands, in minutes. */
    val LENGTH_BANDS_MINUTES = listOf(5, 15, 30, 60)

    /** The recent pace is the last four weeks, once two of them are recorded and two chapters finished. */
    const val RATE_WINDOW_DAYS = 28
    const val RATE_MIN_DAYS = 14
    const val RATE_MIN_CHAPTERS = 2

    private const val MS_PER_HOUR = 60L * 60 * 1000
    private const val MS_PER_MINUTE = 60L * 1000
    private const val HOURS_PER_DAY = 24

    fun pace(summary: PeriodSummary, today: LocalDate, recordingStartedOn: LocalDate): PaceSummary {
        val period = summary.period
        val from = maxOf(period.from, recordingStartedOn)
        val to = minOf(period.to, today)
        val recordedDays = if (from > to) 0 else ReadingPeriod(from, to).days
        val completed = summary.completedInReader + summary.completedManually
        return PaceSummary(
            pagesPerHour = summary.activeMs.takeIf { it >= MIN_ACTIVE_FOR_RATE_MS }
                ?.let { (summary.pagesNew * MS_PER_HOUR / it).toInt() },
            minutesPerReadingDay = summary.readingDays.takeIf { it > 0 }
                ?.let { summary.activeMs / it / MS_PER_MINUTE },
            chaptersPerWeek = recordedDays.takeIf { it >= MIN_DAYS_FOR_WEEKLY }
                ?.let { completed * 7.0 / it },
            recordedDays = recordedDays,
        )
    }

    fun sessions(sessions: List<SessionFacts>): SessionSummary {
        val counted = sessions.filter { it.activeMs > 0 }
        val lengths = counted.map { it.activeMs }.sorted()
        val median = when {
            lengths.isEmpty() -> 0
            lengths.size % 2 == 1 -> lengths[lengths.size / 2]
            else -> (lengths[lengths.size / 2 - 1] + lengths[lengths.size / 2]) / 2
        }
        val bands = IntArray(LENGTH_BANDS_MINUTES.size + 1)
        lengths.forEach { ms ->
            val band = LENGTH_BANDS_MINUTES.indexOfFirst { ms < it * MS_PER_MINUTE }
            bands[if (band == -1) LENGTH_BANDS_MINUTES.size else band]++
        }
        return SessionSummary(
            count = counted.size,
            averageMs = if (lengths.isEmpty()) 0 else lengths.sum() / lengths.size,
            medianMs = median,
            longest = counted.maxByOrNull { it.activeMs },
            byLength = bands.toList(),
        )
    }

    /**
     * Active time by hour of the day (0 to 23), in each session's own time zone. A session's active
     * time is spread over the clock hours it spanned, in proportion to how long it spent in each.
     */
    fun byHour(sessions: List<SessionFacts>): List<Long> {
        val hours = LongArray(HOURS_PER_DAY)
        sessions.filter { it.activeMs > 0 }.forEach { session ->
            val zone = runCatching { TimeZone.of(session.zoneId) }.getOrDefault(TimeZone.UTC)
            val span = session.endedAt - session.startedAt
            if (span <= 0) {
                hours[hourOf(session.startedAt, zone)] += session.activeMs
                return@forEach
            }
            var shared = 0L
            var cursor = session.startedAt
            while (cursor < session.endedAt) {
                val next = minOf(session.endedAt, nextHour(cursor, zone))
                val part = if (next == session.endedAt) {
                    session.activeMs - shared
                } else {
                    session.activeMs * (next - cursor) / span
                }
                hours[hourOf(cursor, zone)] += part
                shared += part
                cursor = next
            }
        }
        return hours.toList()
    }

    /** Active time by [DayPart], from [byHour]. */
    fun byDayPart(hours: List<Long>): Map<DayPart, Long> =
        hours.withIndex().groupBy({ DayPart.of(it.index) }, { it.value }).mapValues { (_, values) -> values.sum() }

    /** Active time by weekday, Monday first, from the days' own dates. */
    fun byWeekday(days: List<ReadingDayStats>, period: ReadingPeriod): List<Long> {
        val weekdays = LongArray(DayOfWeek.entries.size)
        days.filter { it.date in period }.forEach { weekdays[it.date.dayOfWeek.isoDayNumber - 1] += it.activeMs }
        return weekdays.toList()
    }

    /**
     * Chapters finished (in the reader or by hand) per week over the last [RATE_WINDOW_DAYS] days that
     * were recorded; null with too little of either to say.
     */
    fun weeklyRate(days: List<ReadingDayStats>, today: LocalDate, recordingStartedOn: LocalDate): WeeklyRate? {
        val windowStart = maxOf(today.minus(RATE_WINDOW_DAYS - 1, DateTimeUnit.DAY), recordingStartedOn)
        if (windowStart > today) return null
        val windowDays = ReadingPeriod(windowStart, today).days
        val chapters = days.filter { it.date in windowStart..today }
            .sumOf { it.completedInReader + it.completedManually }
        if (windowDays < RATE_MIN_DAYS || chapters < RATE_MIN_CHAPTERS) return null
        return WeeklyRate(chapters, windowDays)
    }

    /** For each series in progress with a known total, the weeks it would take at [rate], soonest first. */
    fun finishEstimates(progress: List<EntryProgress>, rate: WeeklyRate): List<FinishEstimate> =
        progress
            .filter { it.status == LibraryStatus.IN_PROGRESS && (it.remaining ?: 0) > 0 }
            .map { FinishEstimate(it, rate.weeksFor(it.remaining!!)) }
            .sortedWith(compareBy({ it.weeks }, { it.progress.entry.title }))

    private fun hourOf(epochMs: Long, zone: TimeZone): Int =
        Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone).hour

    /** The next full hour of the local clock after [epochMs]. */
    private fun nextHour(epochMs: Long, zone: TimeZone): Long {
        val local = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone)
        val intoHour = (local.minute * 60L + local.second) * 1000 + local.nanosecond / 1_000_000
        return epochMs - intoHour + MS_PER_HOUR
    }
}
