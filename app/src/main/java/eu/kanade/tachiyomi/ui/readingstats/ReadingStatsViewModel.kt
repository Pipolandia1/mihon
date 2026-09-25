package eu.kanade.tachiyomi.ui.readingstats

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.readingstats.PlanProgressSource
import eu.kanade.tachiyomi.readingstats.ReadingAnalysisSource
import eu.kanade.tachiyomi.readingstats.ReadingDays
import eu.kanade.tachiyomi.readingstats.ReadingStatsConfig
import eu.kanade.tachiyomi.readingstats.comics.LibrarySummary
import eu.kanade.tachiyomi.readingstats.comics.LibrarySummarySource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.readingstats.analysis.Insight
import tachiyomi.domain.readingstats.goals.GoalProgress
import tachiyomi.domain.readingstats.goals.GoalRules
import tachiyomi.domain.readingstats.habits.Frequency
import tachiyomi.domain.readingstats.habits.ReadingHabits
import tachiyomi.domain.readingstats.habits.ReadingRecords
import tachiyomi.domain.readingstats.habits.Streaks
import tachiyomi.domain.readingstats.metrics.Coverage
import tachiyomi.domain.readingstats.metrics.PeriodSummary
import tachiyomi.domain.readingstats.metrics.ReadingCalendar
import tachiyomi.domain.readingstats.metrics.ReadingMetrics
import tachiyomi.domain.readingstats.metrics.ReadingPeriod
import tachiyomi.domain.readingstats.metrics.Trend
import tachiyomi.domain.readingstats.model.ReadingDayEntry
import tachiyomi.domain.readingstats.model.ReadingDayStats
import tachiyomi.domain.readingstats.plans.NextStep
import tachiyomi.domain.readingstats.plans.NextSteps
import tachiyomi.domain.readingstats.plans.PlanProgress
import tachiyomi.domain.readingstats.repository.ReadingPlanRepository
import tachiyomi.domain.readingstats.repository.ReadingStatsRepository
import tachiyomi.domain.readingstats.service.ReadingStatsPreferences
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class ReadingStatsViewModel(
    private val repository: ReadingStatsRepository,
    private val planRepository: ReadingPlanRepository,
    readingDays: ReadingDays,
    planProgress: PlanProgressSource,
    librarySummary: LibrarySummarySource,
    analysis: ReadingAnalysisSource,
    private val preferences: ReadingStatsPreferences,
) : ViewModel() {

    private val events = Channel<Event>(Channel.UNLIMITED)
    val eventFlow: Flow<Event> = events.receiveAsFlow()

    private val selectedDay = MutableStateFlow<LocalDate?>(null)
    private val dialog = MutableStateFlow<Dialog?>(null)

    /** The first day of the month the calendar shows; null for the current month. */
    private val shownMonth = MutableStateFlow<LocalDate?>(null)

    /** Bumped when recording restarts, so the recording start is read again. */
    private val resets = MutableStateFlow(0)

    private val firstDayOfWeek: DayOfWeek = readingDays.firstDayOfWeek
    private val today: Flow<LocalDate> = readingDays.today
    private val recordingStartedOn: Flow<LocalDate> = readingDays.recordingStartedOn(resets)

    private val calendar: Flow<Calendar> = combine(today, recordingStartedOn) { today, startedOn ->
        Calendar(today, startedOn, ReadingCalendar.weekOf(today, firstDayOfWeek))
    }.distinctUntilChanged()

    private val week: Flow<WeekState> = calendar
        .flatMapLatest { calendar ->
            val lastWeek = ReadingMetrics.sameStretchLastWeek(calendar.week, calendar.today)
            repository.subscribeDays(lastWeek.from, calendar.week.to).map { days ->
                weekState(calendar, lastWeek, days)
            }
        }
        .catch { logcat(LogPriority.ERROR, it) }
        .flowOn(Dispatchers.IO)

    private val plans: Flow<List<PlanProgress>> = planProgress
        .subscribe(today, recordingStartedOn, firstDayOfWeek)
        .catch { logcat(LogPriority.ERROR, it) }
        .flowOn(Dispatchers.IO)

    private val objectives: Flow<ObjectivesState> = combine(week, planRepository.subscribeGoals(), plans) {
            week,
            goals,
            plans,
        ->
        val progress = GoalRules.ordered(goals).map {
            GoalRules.progress(it, week.today, week.summary.period, week.days, week.recordingStartedOn)
        }
        ObjectivesState(goals = progress, plans = plans, nextSteps = NextSteps.of(progress, plans))
    }
        .catch { logcat(LogPriority.ERROR, it) }
        .flowOn(Dispatchers.IO)

    private val activity: Flow<ActivityState> = combine(calendar, repository.subscribeAllDays(), shownMonth, ::Triple)
        .mapLatest { (calendar, days, month) ->
            val firstOfMonth = month ?: LocalDate(calendar.today.year, calendar.today.month, 1)
            ActivityState(
                today = calendar.today,
                recordingStartedOn = calendar.recordingStartedOn,
                firstDayOfWeek = firstDayOfWeek,
                month = firstOfMonth,
                days = days.associateBy { it.date },
                streaks = ReadingHabits.streaks(days, calendar.today),
                frequency = ReadingHabits.frequency(days, calendar.today, firstDayOfWeek, calendar.recordingStartedOn),
                records = ReadingHabits.records(days, firstDayOfWeek, repository.getLongestSession()),
            )
        }
        .catch { logcat(LogPriority.ERROR, it) }
        .flowOn(Dispatchers.IO)

    private val insights: Flow<List<Insight>> = analysis.insights(today, recordingStartedOn, firstDayOfWeek)
        .catch { logcat(LogPriority.ERROR, it) }
        .flowOn(Dispatchers.IO)

    private val library: Flow<LibrarySummary?> = librarySummary.subscribe(today, firstDayOfWeek)
        .catch { logcat(LogPriority.ERROR, it) }
        .flowOn(Dispatchers.IO)

    private val dayEntries: Flow<List<ReadingDayEntry>?> = selectedDay
        .flatMapLatest { date ->
            if (date == null) {
                flowOf(null)
            } else {
                val zone = TimeZone.currentSystemDefault()
                repository.subscribeDayEntries(
                    date = date,
                    dayStart = ReadingCalendar.startOf(date, zone),
                    dayEnd = ReadingCalendar.startOf(date.plus(1, DateTimeUnit.DAY), zone),
                )
            }
        }
        .catch { logcat(LogPriority.ERROR, it) }
        .flowOn(Dispatchers.IO)

    private val summaries = combine(
        combine(week, objectives, activity, ::Triple),
        library.onStart { emit(null) },
        insights.onStart { emit(emptyList()) },
    ) { (week, objectives, activity), library, insights ->
        Summaries(week, objectives, activity, library, insights)
    }

    val state: StateFlow<State> = combine(
        summaries,
        combine(selectedDay, dayEntries, ::Pair),
        preferences.recordingEnabled.changes(),
        dialog,
    ) { summaries, (day, entries), recording, dialog ->
        State(
            week = summaries.week,
            objectives = summaries.objectives,
            activity = summaries.activity,
            library = summaries.library,
            insights = summaries.insights,
            selectedDay = day,
            dayEntries = entries?.takeIf { day != null },
            recordingEnabled = recording,
            dialog = dialog,
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), State())

    fun selectDay(date: LocalDate?) {
        selectedDay.value = date
    }

    fun showMonth(firstOfMonth: LocalDate) {
        shownMonth.value = firstOfMonth
    }

    fun setRecordingEnabled(enabled: Boolean) {
        preferences.recordingEnabled.set(enabled)
    }

    fun setHideStalled(hide: Boolean) {
        preferences.hideStalledReminders.set(hide)
    }

    fun setDialog(dialog: Dialog?) {
        this.dialog.value = dialog
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launchIO { repository.deleteSession(sessionId) }
    }

    fun deleteAll() {
        viewModelScope.launchIO {
            repository.deleteAll(Clock.System.now().toEpochMilliseconds())
            resets.update { it + 1 }
        }
    }

    fun openChapter(mangaId: Long, chapterId: Long) {
        events.trySend(Event.OpenChapter(mangaId, chapterId))
    }

    private fun weekState(calendar: Calendar, lastWeek: ReadingPeriod, days: List<ReadingDayStats>): WeekState {
        val byDate = days.associateBy { it.date }
        val summary = ReadingMetrics.summarize(calendar.week, days, calendar.recordingStartedOn)
        val previous = ReadingMetrics.summarize(lastWeek, days, calendar.recordingStartedOn)
        return WeekState(
            today = calendar.today,
            recordingStartedOn = calendar.recordingStartedOn,
            summary = summary,
            days = calendar.week.dates().map { byDate[it] ?: ReadingDayStats(it) },
            pagesTrend = ReadingMetrics.trend(
                current = summary.pagesNew.toLong(),
                previous = previous.pagesNew.toLong(),
                previousCoverage = previous.coverage,
            ),
            comparable = previous.coverage == Coverage.FULL,
            hasAnyReading = days.any { it.sessions > 0 },
        )
    }

    private data class Calendar(val today: LocalDate, val recordingStartedOn: LocalDate, val week: ReadingPeriod)

    private data class Summaries(
        val week: WeekState,
        val objectives: ObjectivesState,
        val activity: ActivityState,
        val library: LibrarySummary?,
        val insights: List<Insight>,
    )

    @Immutable
    data class State(
        val week: WeekState? = null,
        val objectives: ObjectivesState? = null,
        val activity: ActivityState? = null,
        /** Null while loading. */
        val library: LibrarySummary? = null,
        val insights: List<Insight> = emptyList(),
        val selectedDay: LocalDate? = null,
        /** Null while the day is loading, or when no day is selected. */
        val dayEntries: List<ReadingDayEntry>? = null,
        val recordingEnabled: Boolean = true,
        val dialog: Dialog? = null,
    )

    @Immutable
    data class WeekState(
        val today: LocalDate,
        val recordingStartedOn: LocalDate,
        val summary: PeriodSummary,
        /** Every day of the week, in order, empty days included. */
        val days: List<ReadingDayStats>,
        val pagesTrend: Trend?,
        /** Whether last week's same stretch was fully recorded. */
        val comparable: Boolean,
        /** Whether anything was measured this week or the same stretch of last week. */
        val hasAnyReading: Boolean,
    )

    @Immutable
    data class ObjectivesState(
        /** The primary goal first. */
        val goals: List<GoalProgress>,
        val plans: List<PlanProgress>,
        val nextSteps: List<NextStep>,
    )

    @Immutable
    data class ActivityState(
        val today: LocalDate,
        val recordingStartedOn: LocalDate,
        val firstDayOfWeek: DayOfWeek,
        /** The first day of the month the calendar shows. */
        val month: LocalDate,
        val days: Map<LocalDate, ReadingDayStats>,
        val streaks: Streaks,
        val frequency: Frequency?,
        val records: ReadingRecords,
    )

    sealed interface Dialog {
        data class DeleteSession(val sessionId: Long) : Dialog
        data object DeleteAll : Dialog
        data object PickDay : Dialog
    }

    sealed interface Event {
        data class OpenChapter(val mangaId: Long, val chapterId: Long) : Event
    }
}
