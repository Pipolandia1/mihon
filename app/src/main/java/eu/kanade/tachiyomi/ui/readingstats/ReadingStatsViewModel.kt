package eu.kanade.tachiyomi.ui.readingstats

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.readingstats.ReadingStatsConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
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
import tachiyomi.domain.history.interactor.GetHistory
import tachiyomi.domain.history.interactor.GetNextChapters
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.readingstats.metrics.Coverage
import tachiyomi.domain.readingstats.metrics.PeriodSummary
import tachiyomi.domain.readingstats.metrics.ReadingCalendar
import tachiyomi.domain.readingstats.metrics.ReadingMetrics
import tachiyomi.domain.readingstats.metrics.ReadingPeriod
import tachiyomi.domain.readingstats.metrics.Trend
import tachiyomi.domain.readingstats.model.ReadingDayEntry
import tachiyomi.domain.readingstats.model.ReadingDayStats
import tachiyomi.domain.readingstats.repository.ReadingStatsRepository
import tachiyomi.domain.readingstats.service.ReadingStatsPreferences
import java.time.temporal.WeekFields
import java.util.Locale
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class ReadingStatsViewModel(
    private val repository: ReadingStatsRepository,
    private val preferences: ReadingStatsPreferences,
    private val getHistory: GetHistory,
    private val getNextChapters: GetNextChapters,
) : ViewModel() {

    private val events = Channel<Event>(Channel.UNLIMITED)
    val eventFlow: Flow<Event> = events.receiveAsFlow()

    private val selectedDay = MutableStateFlow<LocalDate?>(null)
    private val dialog = MutableStateFlow<Dialog?>(null)

    /** Bumped when recording restarts, so the recording start is read again. */
    private val resets = MutableStateFlow(0)

    private val firstDayOfWeek: DayOfWeek = DayOfWeek(WeekFields.of(Locale.getDefault()).firstDayOfWeek.value)

    /** Today, re-emitted when the date changes while the screen is open. */
    private val today: Flow<LocalDate> = flow {
        while (true) {
            emit(ReadingCalendar.dateOf(Clock.System.now().toEpochMilliseconds(), TimeZone.currentSystemDefault()))
            delay(1.minutes)
        }
    }.distinctUntilChanged()

    private val recordingStartedOn: Flow<LocalDate> = resets.mapLatest {
        ReadingCalendar.dateOf(repository.getRecordingStartedAt(), TimeZone.currentSystemDefault())
    }

    private val week: Flow<WeekState> = combine(today, recordingStartedOn, ::Pair)
        .flatMapLatest { (today, startedOn) ->
            val week = ReadingCalendar.weekOf(today, firstDayOfWeek)
            val lastWeek = ReadingMetrics.sameStretchLastWeek(week, today)
            repository.subscribeDays(lastWeek.from, week.to).map { days ->
                weekState(today, week, lastWeek, startedOn, days)
            }
        }
        .catch { logcat(LogPriority.ERROR, it) }
        .flowOn(Dispatchers.IO)

    private val continueReading: Flow<List<ContinueItem>> = getHistory.subscribe("")
        .distinctUntilChanged()
        .mapLatest { history ->
            // Entries with nothing left to read are skipped, so look further than the first few.
            buildList {
                for (entry in history) {
                    if (size == ReadingStatsConfig.CONTINUE_READING_COUNT) break
                    continueItem(entry.mangaId, entry.chapterId, entry.title, entry.coverData)?.let(::add)
                }
            }
        }
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

    val state: StateFlow<State> = combine(
        week,
        continueReading,
        combine(selectedDay, dayEntries, ::Pair),
        preferences.recordingEnabled.changes(),
        dialog,
    ) { week, continueReading, (day, entries), recording, dialog ->
        State(
            week = week,
            continueReading = continueReading,
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

    fun setRecordingEnabled(enabled: Boolean) {
        preferences.recordingEnabled.set(enabled)
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

    private fun weekState(
        today: LocalDate,
        week: ReadingPeriod,
        lastWeek: ReadingPeriod,
        startedOn: LocalDate,
        days: List<ReadingDayStats>,
    ): WeekState {
        val byDate = days.associateBy { it.date }
        val summary = ReadingMetrics.summarize(week, days, startedOn)
        val previous = ReadingMetrics.summarize(lastWeek, days, startedOn)
        return WeekState(
            today = today,
            recordingStartedOn = startedOn,
            summary = summary,
            days = week.dates().map { byDate[it] ?: ReadingDayStats(it) },
            pagesTrend = ReadingMetrics.trend(
                current = summary.pagesNew.toLong(),
                previous = previous.pagesNew.toLong(),
                previousCoverage = previous.coverage,
            ),
            comparable = previous.coverage == Coverage.FULL,
            hasAnyReading = days.any { it.sessions > 0 },
        )
    }

    private suspend fun continueItem(mangaId: Long, chapterId: Long, title: String, cover: MangaCover): ContinueItem? {
        val next = getNextChapters.await(mangaId, chapterId, onlyUnread = false).firstOrNull() ?: return null
        // The chapter last opened and not finished is resumed where it was left, even on page one.
        val resuming = next.id == chapterId && !next.read
        return ContinueItem(
            mangaId = mangaId,
            chapterId = next.id,
            title = title,
            cover = cover,
            chapterNumber = next.chapterNumber,
            chapterName = next.name,
            resumePage = if (resuming) next.lastPageRead.toInt() + 1 else null,
            totalPages = if (resuming) repository.getChapterProgress(next.id)?.totalPages else null,
        )
    }

    @Immutable
    data class State(
        val week: WeekState? = null,
        val continueReading: List<ContinueItem> = emptyList(),
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
    data class ContinueItem(
        val mangaId: Long,
        val chapterId: Long,
        val title: String,
        val cover: MangaCover,
        val chapterNumber: Double,
        val chapterName: String,
        /** The 1-based page to resume on, when the chapter was left part way. */
        val resumePage: Int?,
        val totalPages: Int?,
    )

    sealed interface Dialog {
        data class DeleteSession(val sessionId: Long) : Dialog
        data object DeleteAll : Dialog
        data object Definitions : Dialog
        data object PickDay : Dialog
    }

    sealed interface Event {
        data class OpenChapter(val mangaId: Long, val chapterId: Long) : Event
    }
}
