package tachiyomi.domain.readingstats.habits

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import tachiyomi.domain.readingstats.metrics.Coverage
import tachiyomi.domain.readingstats.metrics.ReadingCalendar
import tachiyomi.domain.readingstats.metrics.ReadingMetrics
import tachiyomi.domain.readingstats.metrics.ReadingPeriod
import tachiyomi.domain.readingstats.model.ReadingDayStats
import kotlin.time.Duration.Companion.minutes

data class Streaks(
    /** Reading days in a row up to today, or up to yesterday while today has no reading yet. */
    val current: Int,
    val record: Int,
    val readToday: Boolean,
)

/** Average reading days per week over the last [weeks] fully recorded weeks. */
data class Frequency(val daysPerWeek: Double, val weeks: Int)

data class DayRecord(val date: LocalDate, val value: Long)

data class WeekRecord(val week: ReadingPeriod, val activeMs: Long)

data class SessionRecord(
    val title: String,
    val chapterName: String,
    val chapterNumber: Double,
    val activeMs: Long,
    val startedAt: Long,
)

data class ReadingRecords(
    val longestDay: DayRecord?,
    val mostPagesDay: DayRecord?,
    val bestWeek: WeekRecord?,
    val longestSession: SessionRecord?,
)

/** Reading habits over all recorded days. A day counts only if it is a reading day. */
object ReadingHabits {

    /** Weeks averaged by [frequency]. */
    const val FREQUENCY_WEEKS = 4

    /** Upper bounds, in minutes, of the heatmap levels 1 to 3; level 4 is anything above. */
    private val INTENSITY_STEPS = listOf(15.minutes, 30.minutes, 60.minutes)

    fun streaks(days: List<ReadingDayStats>, today: LocalDate): Streaks {
        val reading = days.filter(ReadingMetrics::isReadingDay).map { it.date }.toSortedSet()
        var record = 0
        var run = 0
        var previous: LocalDate? = null
        for (date in reading) {
            run = if (previous != null && date.minus(1, DateTimeUnit.DAY) == previous) run + 1 else 1
            record = maxOf(record, run)
            previous = date
        }
        val readToday = today in reading
        // A day not read yet is not a missed one: until it ends, the streak runs to yesterday.
        var cursor = if (readToday) today else today.minus(1, DateTimeUnit.DAY)
        var current = 0
        while (cursor in reading) {
            current++
            cursor = cursor.minus(1, DateTimeUnit.DAY)
        }
        return Streaks(current = current, record = record, readToday = readToday)
    }

    /**
     * Reading days per week over the last [FREQUENCY_WEEKS] weeks before this one that were
     * recorded from their first day; null until one such week exists.
     */
    fun frequency(
        days: List<ReadingDayStats>,
        today: LocalDate,
        firstDayOfWeek: DayOfWeek,
        recordingStartedOn: LocalDate,
    ): Frequency? {
        val thisWeek = ReadingCalendar.weekOf(today, firstDayOfWeek)
        val weeks = (1..FREQUENCY_WEEKS)
            .map { thisWeek.shift(-7 * it) }
            .filter { ReadingMetrics.coverage(it, recordingStartedOn) == Coverage.FULL }
        if (weeks.isEmpty()) return null
        val readingDays = days.filter(ReadingMetrics::isReadingDay).map { it.date }
        val total = weeks.sumOf { week -> readingDays.count { it in week } }
        return Frequency(daysPerWeek = total.toDouble() / weeks.size, weeks = weeks.size)
    }

    fun records(
        days: List<ReadingDayStats>,
        firstDayOfWeek: DayOfWeek,
        longestSession: SessionRecord?,
    ): ReadingRecords {
        val longestDay = days.filter { it.activeMs > 0 }.maxByOrNull { it.activeMs }
        val mostPages = days.filter { it.pagesNew > 0 }.maxByOrNull { it.pagesNew }
        val bestWeek = days
            .groupBy { ReadingCalendar.weekOf(it.date, firstDayOfWeek) }
            .map { (week, weekDays) -> WeekRecord(week, weekDays.sumOf { it.activeMs }) }
            .filter { it.activeMs > 0 }
            .maxByOrNull { it.activeMs }
        return ReadingRecords(
            longestDay = longestDay?.let { DayRecord(it.date, it.activeMs) },
            mostPagesDay = mostPages?.let { DayRecord(it.date, it.pagesNew.toLong()) },
            bestWeek = bestWeek,
            longestSession = longestSession?.takeIf { it.activeMs > 0 },
        )
    }

    /** The heatmap level of a day, 0 to 4. A day below a reading day's minimum is level 0. */
    fun intensity(activeMs: Long): Int {
        if (activeMs < ReadingMetrics.READING_DAY_MIN_ACTIVE.inWholeMilliseconds) return 0
        val below = INTENSITY_STEPS.indexOfFirst { activeMs < it.inWholeMilliseconds }
        return if (below == -1) INTENSITY_STEPS.size + 1 else below + 1
    }
}
