package tachiyomi.domain.readingstats.plans

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import tachiyomi.domain.readingstats.metrics.ReadingPeriod
import kotlin.math.ceil

/** Each entry's [id] is what the database stores. */
enum class CollectionKind(val id: Long) {
    /** One entry, every chapter of it: what a plan for a series reads. */
    ENTRY(0),

    /** Entries and chapters the user put together, possibly from several series. */
    LIST(1),
    ;

    companion object {
        fun of(id: Long): CollectionKind = entries.firstOrNull { it.id == id } ?: LIST
    }
}

/** An entry, or one chapter of it, in a collection. */
data class CollectionItem(
    val mangaId: Long?,
    /** Null for the whole entry, now and as the source adds chapters. */
    val chapterId: Long?,
    val sourceId: Long,
    val mangaUrl: String,
    val chapterUrl: String?,
    val label: String,
) {
    val wholeEntry: Boolean get() = chapterUrl == null
}

data class ReadingPlan(
    val id: Long,
    val uuid: String,
    val collectionId: Long,
    val collectionUuid: String,
    val kind: CollectionKind,
    val name: String,
    val pacePerWeek: Int,
    val startedAt: Long,
    val startedOn: LocalDate,
    val paused: Boolean,
)

/** A chapter a plan covers, in the plan's reading order. */
data class PlanChapter(
    val chapterId: Long,
    val mangaId: Long,
    val read: Boolean,
    /** When it was first finished in the reader, or marked read by hand; null if unknown. */
    val finishedOn: LocalDate?,
)

sealed interface Forecast {
    data object Finished : Forecast

    /** Not enough reading of this plan yet to say. */
    data object Unknown : Forecast

    /** At the pace of the recent reading of this plan. */
    data class On(val date: LocalDate, val chaptersPerWeek: Double) : Forecast
}

data class PlanProgress(
    val plan: ReadingPlan,
    val total: Int,
    val done: Int,
    /** Chapters of the plan finished since it started. */
    val doneSinceStart: Int,
    val doneThisWeek: Int,
    /** Chapters still to finish this week to be on pace by its end; 0 when on or ahead of pace. */
    val neededThisWeek: Int,
    /** Chapters finished beyond what the pace asked for by today; negative when behind. */
    val aheadBy: Int,
    /** Weeks left at the planned pace; null when nothing is left. */
    val weeksAtPace: Int?,
    val forecast: Forecast,
    /** The first chapter left to read, in the plan's order. */
    val next: PlanChapter?,
) {
    val remaining: Int get() = total - done
}

object PlanRules {

    /** A forecast needs this much recorded time... */
    const val FORECAST_MIN_DAYS = 14

    /** ...and this many of the plan's chapters finished within it. */
    const val FORECAST_MIN_FINISHED = 2

    /** How far back the recent pace is measured. */
    const val FORECAST_WINDOW_DAYS = 28

    private const val DAYS_PER_WEEK = 7.0

    /**
     * Keeps the first of each chapter: a chapter that two items hold, say a whole series and one of
     * its issues listed again, counts once.
     */
    fun distinct(chapters: List<PlanChapter>): List<PlanChapter> = chapters.distinctBy { it.chapterId }

    fun progress(
        plan: ReadingPlan,
        chapters: List<PlanChapter>,
        today: LocalDate,
        week: ReadingPeriod,
        recordingStartedOn: LocalDate,
    ): PlanProgress {
        val unique = distinct(chapters)
        val finished = unique.filter { it.read }.mapNotNull { it.finishedOn }
        val done = unique.count { it.read }
        val remaining = unique.size - done
        val doneSinceStart = finished.count { it >= plan.startedOn }
        val doneThisWeek = finished.count { it in week }
        val pace = plan.pacePerWeek.coerceAtLeast(0)

        val expectedByWeekEnd = expected(pace, plan.startedOn, week.to)
        val expectedByToday = expected(pace, plan.startedOn, today)
        return PlanProgress(
            plan = plan,
            total = unique.size,
            done = done,
            doneSinceStart = doneSinceStart,
            doneThisWeek = doneThisWeek,
            neededThisWeek = (expectedByWeekEnd - doneSinceStart).coerceIn(0, remaining),
            aheadBy = doneSinceStart - expectedByToday,
            weeksAtPace = if (remaining > 0 && pace > 0) ceil(remaining / pace.toDouble()).toInt() else null,
            forecast = forecast(finished, remaining, today, recordingStartedOn),
            next = unique.firstOrNull { !it.read },
        )
    }

    /** What [pace] asks for from [start] to [until], both included, rounded up. */
    private fun expected(pace: Int, start: LocalDate, until: LocalDate): Int {
        if (until < start) return 0
        val days = (until.toEpochDays() - start.toEpochDays()).toInt() + 1
        return ceil(pace * days / DAYS_PER_WEEK).toInt()
    }

    private fun forecast(
        finished: List<LocalDate>,
        remaining: Int,
        today: LocalDate,
        recordingStartedOn: LocalDate,
    ): Forecast {
        if (remaining == 0) return Forecast.Finished
        val windowStart = maxOf(today.minus(FORECAST_WINDOW_DAYS - 1, DateTimeUnit.DAY), recordingStartedOn)
        val windowDays = (today.toEpochDays() - windowStart.toEpochDays()).toInt() + 1
        val inWindow = finished.count { it in windowStart..today }
        if (windowDays < FORECAST_MIN_DAYS || inWindow < FORECAST_MIN_FINISHED) return Forecast.Unknown
        // In whole numbers, so a pace of exactly one a week gives exactly the days it should.
        val days = (remaining * windowDays + inWindow - 1) / inWindow
        return Forecast.On(
            date = today.plus(days, DateTimeUnit.DAY),
            chaptersPerWeek = inWindow * DAYS_PER_WEEK / windowDays,
        )
    }
}
