package eu.kanade.tachiyomi.readingstats

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import tachiyomi.domain.readingstats.analysis.ChapterReading
import tachiyomi.domain.readingstats.analysis.CompletedChapter
import tachiyomi.domain.readingstats.analysis.Insight
import tachiyomi.domain.readingstats.analysis.InsightInput
import tachiyomi.domain.readingstats.analysis.InsightRules
import tachiyomi.domain.readingstats.analysis.SessionFacts
import tachiyomi.domain.readingstats.comics.ComicLibrary
import tachiyomi.domain.readingstats.comics.ComicMetadata
import tachiyomi.domain.readingstats.habits.ReadingHabits
import tachiyomi.domain.readingstats.metrics.ReadingCalendar
import tachiyomi.domain.readingstats.metrics.ReadingMetrics
import tachiyomi.domain.readingstats.metrics.ReadingPeriod
import tachiyomi.domain.readingstats.model.ReadingDayStats
import tachiyomi.domain.readingstats.repository.ComicMetadataRepository
import tachiyomi.domain.readingstats.repository.ReadingStatsRepository

/** Everything recorded for a period, with the confirmed metadata of what was read in it. */
data class PeriodData(
    val period: ReadingPeriod,
    val days: List<ReadingDayStats>,
    val sessions: List<SessionFacts>,
    val readings: List<ChapterReading>,
    val completed: List<CompletedChapter>,
    val metadata: Map<Long, ComicMetadata>,
)

/** Loads periods for the details screen, retrospectives and insights. */
@Inject
class ReadingAnalysisSource(
    private val stats: ReadingStatsRepository,
    private val metadata: ComicMetadataRepository,
) {

    suspend fun load(period: ReadingPeriod): PeriodData {
        val readings = stats.getReadingByChapter(period.from, period.to)
        val completed = stats.getCompletedChapters(period.from, period.to)
        val mangaIds = readings.mapNotNull { it.mangaId } + completed.map { it.mangaId }
        return PeriodData(
            period = period,
            days = days(period),
            sessions = stats.getSessions(period.from, period.to),
            readings = readings,
            completed = completed,
            metadata = metadata.getConfirmed(mangaIds.toSet()),
        )
    }

    suspend fun days(period: ReadingPeriod): List<ReadingDayStats> = stats.subscribeDays(period.from, period.to).first()

    suspend fun firstActivity(): LocalDate? = stats.getFirstActivityDate()

    /**
     * The insights that hold now, kept current as reading is recorded and the library changes. Only
     * rules with enough behind them say anything.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun insights(
        today: Flow<LocalDate>,
        recordingStartedOn: Flow<LocalDate>,
        firstDayOfWeek: DayOfWeek,
    ): Flow<List<Insight>> = combine(today, recordingStartedOn, ::Pair).flatMapLatest { (today, startedOn) ->
        combine(stats.subscribeDays(EARLIEST, today), metadata.subscribeLibrary(), ::Pair)
            .mapLatest { (days, library) ->
                val recent = ReadingPeriod(today.minus(InsightRules.RECENT_DAYS - 1, DateTimeUnit.DAY), today)
                val week = ReadingCalendar.weekOf(today, firstDayOfWeek)
                val lastWeek = ReadingMetrics.sameStretchLastWeek(week, today)
                val backlog = ComicLibrary.backlog(library)
                InsightRules.of(
                    InsightInput(
                        today = today,
                        firstDayOfWeek = firstDayOfWeek,
                        recordingStartedOn = startedOn,
                        days = days,
                        streaks = ReadingHabits.streaks(days, today),
                        thisWeek = ReadingMetrics.summarize(week, days, startedOn),
                        lastWeek = ReadingMetrics.summarize(lastWeek, days, startedOn),
                        recentSessions = stats.getSessions(recent.from, recent.to),
                        recentReadings = stats.getReadingByChapter(recent.from, recent.to),
                        backlog = backlog.total - backlog.variants,
                    ),
                )
            }
    }

    private companion object {
        /** Before anything Mihon could have recorded or carried over. */
        val EARLIEST = LocalDate(1970, 1, 1)
    }
}
