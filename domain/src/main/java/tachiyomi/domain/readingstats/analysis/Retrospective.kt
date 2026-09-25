package tachiyomi.domain.readingstats.analysis

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import tachiyomi.domain.readingstats.comics.ComicMetadata
import tachiyomi.domain.readingstats.comics.ComicUnit
import tachiyomi.domain.readingstats.comics.UnitCounts
import tachiyomi.domain.readingstats.habits.DayRecord
import tachiyomi.domain.readingstats.habits.ReadingHabits
import tachiyomi.domain.readingstats.habits.WeekRecord
import tachiyomi.domain.readingstats.metrics.PeriodSummary
import tachiyomi.domain.readingstats.metrics.ReadingMetrics
import tachiyomi.domain.readingstats.metrics.ReadingPeriod
import tachiyomi.domain.readingstats.model.ReadingDayStats

enum class RetrospectiveKind { MONTH, YEAR }

/** A chapter finished in a period: first completed in the reader, or marked read by hand. */
data class CompletedChapter(val chapterId: Long, val mangaId: Long)

/** A month or a year of reading, told with the same definitions as everything else. */
data class Retrospective(
    val kind: RetrospectiveKind,
    val period: ReadingPeriod,
    val summary: PeriodSummary,
    val completedUnits: UnitCounts,
    val longestDay: DayRecord?,
    val bestWeek: WeekRecord?,
    /** The longest run of reading days inside the period. */
    val longestStreak: Int,
    val sessions: SessionSummary,
    val series: Ranking,
    val writers: Ranking,
    val publishers: Ranking,
) {
    val isEmpty: Boolean get() = summary.activeMs == 0L && summary.completedInReader + summary.completedManually == 0
}

object Retrospectives {

    /** The whole month or year holding [anchor]. */
    fun periodOf(kind: RetrospectiveKind, anchor: LocalDate): ReadingPeriod = when (kind) {
        RetrospectiveKind.MONTH -> {
            val first = LocalDate(anchor.year, anchor.month, 1)
            ReadingPeriod(first, first.plus(DatePeriod(months = 1)).minus(1, DateTimeUnit.DAY))
        }
        RetrospectiveKind.YEAR -> ReadingPeriod(
            LocalDate(anchor.year, Month.JANUARY, 1),
            LocalDate(anchor.year, Month.DECEMBER, 31),
        )
    }

    /** The month or year before or after [period]. */
    fun step(kind: RetrospectiveKind, period: ReadingPeriod, by: Int): ReadingPeriod = when (kind) {
        RetrospectiveKind.MONTH -> periodOf(kind, period.from.plus(DatePeriod(months = by)))
        RetrospectiveKind.YEAR -> periodOf(kind, period.from.plus(DatePeriod(years = by)))
    }

    fun build(
        kind: RetrospectiveKind,
        period: ReadingPeriod,
        days: List<ReadingDayStats>,
        sessions: List<SessionFacts>,
        readings: List<ChapterReading>,
        completed: List<CompletedChapter>,
        metadata: Map<Long, ComicMetadata>,
        firstDayOfWeek: DayOfWeek,
        recordingStartedOn: LocalDate,
    ): Retrospective {
        val inPeriod = days.filter { it.date in period }
        val records = ReadingHabits.records(inPeriod, firstDayOfWeek, longestSession = null)
        return Retrospective(
            kind = kind,
            period = period,
            summary = ReadingMetrics.summarize(period, inPeriod, recordingStartedOn),
            completedUnits = completedUnits(completed, metadata),
            longestDay = records.longestDay,
            bestWeek = records.bestWeek,
            longestStreak = ReadingHabits.streaks(inPeriod, period.to).record,
            sessions = ReadingPace.sessions(sessions),
            series = Rankings.of(RankingDimension.SERIES, readings, metadata),
            writers = Rankings.of(RankingDimension.WRITER, readings, metadata),
            publishers = Rankings.of(RankingDimension.PUBLISHER, readings, metadata),
        )
    }

    /** Finished chapters by unit; a chapter without a confirmed unit is counted as unknown. */
    fun completedUnits(completed: List<CompletedChapter>, metadata: Map<Long, ComicMetadata>): UnitCounts =
        completed.distinctBy { it.chapterId }.fold(UnitCounts()) { counts, chapter ->
            val meta = metadata[chapter.mangaId]
            counts + when {
                meta == null -> UnitCounts(unknown = 1)
                meta.isVariant(chapter.chapterId) -> UnitCounts(variants = 1)
                else -> when (meta.unitOf(chapter.chapterId)) {
                    null -> UnitCounts(unknown = 1)
                    ComicUnit.ISSUE -> UnitCounts(issues = 1)
                    ComicUnit.OTHER -> UnitCounts(other = 1)
                    else -> UnitCounts(collected = 1)
                }
            }
        }
}
