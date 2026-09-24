package tachiyomi.domain.readingstats.metrics

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** A run of calendar days, both ends included. */
data class ReadingPeriod(val from: LocalDate, val to: LocalDate) {
    init {
        require(from <= to) { "$from is after $to" }
    }

    val days: Int get() = (to.toEpochDays() - from.toEpochDays()).toInt() + 1

    operator fun contains(date: LocalDate): Boolean = date in from..to

    fun dates(): List<LocalDate> = List(days) { from.plus(it, DateTimeUnit.DAY) }

    fun shift(days: Int): ReadingPeriod =
        ReadingPeriod(from.plus(days, DateTimeUnit.DAY), to.plus(days, DateTimeUnit.DAY))
}

/** Calendar arithmetic for reading time: which day a moment belongs to, and where days split. */
object ReadingCalendar {

    fun dateOf(epochMs: Long, zone: TimeZone): LocalDate =
        Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(zone).date

    fun startOf(date: LocalDate, zone: TimeZone): Long = date.atStartOfDayIn(zone).toEpochMilliseconds()

    /**
     * Splits the span from [from] to [to] (epoch milliseconds, end excluded) at each local midnight
     * in [zone], giving each day its milliseconds. A day that lasts 23 or 25 hours because of a
     * clock change gets exactly the time that passed in it.
     */
    fun splitByDay(from: Long, to: Long, zone: TimeZone): List<Pair<LocalDate, Long>> {
        if (to <= from) return emptyList()
        val parts = ArrayList<Pair<LocalDate, Long>>(1)
        var start = from
        while (start < to) {
            val date = dateOf(start, zone)
            val nextMidnight = startOf(date.plus(1, DateTimeUnit.DAY), zone)
            val end = minOf(to, nextMidnight)
            parts += date to (end - start)
            start = end
        }
        return parts
    }

    /** The week holding [date], starting on [firstDayOfWeek]. */
    fun weekOf(date: LocalDate, firstDayOfWeek: DayOfWeek): ReadingPeriod {
        val back = (date.dayOfWeek.isoDayNumber - firstDayOfWeek.isoDayNumber + 7) % 7
        val start = date.minus(back, DateTimeUnit.DAY)
        return ReadingPeriod(start, start.plus(6, DateTimeUnit.DAY))
    }
}
