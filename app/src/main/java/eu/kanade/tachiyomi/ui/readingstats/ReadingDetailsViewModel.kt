package eu.kanade.tachiyomi.ui.readingstats

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.readingstats.ReadingAnalysisSource
import eu.kanade.tachiyomi.readingstats.ReadingDays
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.readingstats.analysis.BucketSize
import tachiyomi.domain.readingstats.analysis.ChartBucket
import tachiyomi.domain.readingstats.analysis.FinishEstimate
import tachiyomi.domain.readingstats.analysis.PaceSummary
import tachiyomi.domain.readingstats.analysis.PeriodBreakdown
import tachiyomi.domain.readingstats.analysis.PeriodChoice
import tachiyomi.domain.readingstats.analysis.Ranking
import tachiyomi.domain.readingstats.analysis.RankingDimension
import tachiyomi.domain.readingstats.analysis.Rankings
import tachiyomi.domain.readingstats.analysis.ReadingPace
import tachiyomi.domain.readingstats.analysis.Retrospectives
import tachiyomi.domain.readingstats.analysis.SessionSummary
import tachiyomi.domain.readingstats.analysis.WeeklyRate
import tachiyomi.domain.readingstats.comics.ComicLibrary
import tachiyomi.domain.readingstats.comics.UnitCounts
import tachiyomi.domain.readingstats.metrics.Coverage
import tachiyomi.domain.readingstats.metrics.PeriodSummary
import tachiyomi.domain.readingstats.metrics.ReadingMetrics
import tachiyomi.domain.readingstats.metrics.ReadingPeriod
import tachiyomi.domain.readingstats.metrics.Trend
import tachiyomi.domain.readingstats.repository.ComicMetadataRepository
import kotlin.time.Duration.Companion.seconds

/** Reading over a chosen period: totals, a chart, pace, habits, estimates and rankings. */
@OptIn(ExperimentalCoroutinesApi::class)
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class ReadingDetailsViewModel(
    private val source: ReadingAnalysisSource,
    private val metadataRepository: ComicMetadataRepository,
    readingDays: ReadingDays,
) : ViewModel() {

    private val firstDayOfWeek = readingDays.firstDayOfWeek
    private val choice = MutableStateFlow(Choice(PeriodChoice.LAST_30_DAYS, null))

    val state: StateFlow<State?> = combine(readingDays.today, readingDays.recordingStartedOn(), choice, ::Triple)
        .mapLatest { (today, startedOn, choice) -> load(today, startedOn, choice) }
        .catch { logcat(LogPriority.ERROR, it) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), null)

    fun choose(period: PeriodChoice) = choice.update { it.copy(choice = period) }

    fun chooseRange(from: LocalDate, to: LocalDate) =
        choice.update { Choice(PeriodChoice.CUSTOM, ReadingPeriod(minOf(from, to), maxOf(from, to))) }

    private suspend fun load(today: LocalDate, recordingStartedOn: LocalDate, choice: Choice): State {
        val period = PeriodBreakdown.periodOf(choice.choice, today, source.firstActivity(), choice.custom)
        val data = source.load(period)
        val summary = ReadingMetrics.summarize(period, data.days, recordingStartedOn)
        // "All time" has nothing before it to compare with.
        val previous = if (choice.choice == PeriodChoice.ALL) {
            null
        } else {
            val before = PeriodBreakdown.previous(period)
            ReadingMetrics.summarize(before, source.days(before), recordingStartedOn)
        }
        val recent = ReadingPeriod(today.minus(ReadingPace.RATE_WINDOW_DAYS - 1, DateTimeUnit.DAY), today)
        val rate = ReadingPace.weeklyRate(source.days(recent), today, recordingStartedOn)
        val progress = metadataRepository.subscribeLibrary().first().map(ComicLibrary::progress)
        return State(
            choice = choice.choice,
            period = period,
            today = today,
            recordingStartedOn = recordingStartedOn,
            firstDayOfWeek = firstDayOfWeek,
            summary = summary,
            previous = previous,
            timeTrend = previous?.let { ReadingMetrics.trend(summary.activeMs, it.activeMs, it.coverage) },
            pagesTrend = previous?.let {
                ReadingMetrics.trend(summary.pagesNew.toLong(), it.pagesNew.toLong(), it.coverage)
            },
            completedUnits = Retrospectives.completedUnits(data.completed, data.metadata),
            bucketSize = PeriodBreakdown.bucketSize(period),
            buckets = PeriodBreakdown.buckets(period, data.days, firstDayOfWeek, recordingStartedOn),
            pace = ReadingPace.pace(summary, today, recordingStartedOn),
            sessions = ReadingPace.sessions(data.sessions),
            byHour = ReadingPace.byHour(data.sessions),
            byWeekday = ReadingPace.byWeekday(data.days, period),
            rate = rate,
            estimates = rate?.let { ReadingPace.finishEstimates(progress, it) }.orEmpty(),
            rankings = RankingDimension.entries.associateWith { Rankings.of(it, data.readings, data.metadata) },
        )
    }

    private data class Choice(val choice: PeriodChoice, val custom: ReadingPeriod?)

    @Immutable
    data class State(
        val choice: PeriodChoice,
        val period: ReadingPeriod,
        val today: LocalDate,
        val recordingStartedOn: LocalDate,
        val firstDayOfWeek: DayOfWeek,
        val summary: PeriodSummary,
        /** The period of the same length before; null for all time. */
        val previous: PeriodSummary?,
        val timeTrend: Trend?,
        val pagesTrend: Trend?,
        val completedUnits: UnitCounts,
        val bucketSize: BucketSize,
        val buckets: List<ChartBucket>,
        val pace: PaceSummary,
        val sessions: SessionSummary,
        /** Active time by hour of the day, 0 to 23. */
        val byHour: List<Long>,
        /** Active time by weekday, Monday first. */
        val byWeekday: List<Long>,
        /** Chapters per week over the last four weeks; null without enough to say. */
        val rate: WeeklyRate?,
        val estimates: List<FinishEstimate>,
        val rankings: Map<RankingDimension, Ranking>,
    ) {
        val comparable: Boolean get() = previous?.coverage == Coverage.FULL
    }
}
