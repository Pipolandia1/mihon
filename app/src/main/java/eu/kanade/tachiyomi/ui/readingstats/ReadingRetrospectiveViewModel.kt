package eu.kanade.tachiyomi.ui.readingstats

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDate
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.readingstats.analysis.Retrospective
import tachiyomi.domain.readingstats.analysis.RetrospectiveKind
import tachiyomi.domain.readingstats.analysis.Retrospectives
import kotlin.time.Duration.Companion.seconds

/** A month's or a year's reading, stepping back and forth; nothing leaves the device unless shared. */
@OptIn(ExperimentalCoroutinesApi::class)
@AssistedInject
class ReadingRetrospectiveViewModel(
    @Assisted private val kind: RetrospectiveKind,
    private val source: ReadingAnalysisSource,
    readingDays: ReadingDays,
) : ViewModel() {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(kind: RetrospectiveKind): ReadingRetrospectiveViewModel
    }

    private val firstDayOfWeek = readingDays.firstDayOfWeek

    /** How many months or years back from the current one. */
    private val offset = MutableStateFlow(0)

    val state: StateFlow<State?> = combine(readingDays.today, readingDays.recordingStartedOn(), offset, ::Triple)
        .mapLatest { (today, startedOn, offset) ->
            val current = Retrospectives.periodOf(kind, today)
            val period = Retrospectives.step(kind, current, -offset)
            val data = source.load(period)
            val firstActivity = source.firstActivity()
            State(
                today = today,
                retrospective = Retrospectives.build(
                    kind = kind,
                    period = period,
                    days = data.days,
                    sessions = data.sessions,
                    readings = data.readings,
                    completed = data.completed,
                    metadata = data.metadata,
                    firstDayOfWeek = firstDayOfWeek,
                    recordingStartedOn = startedOn,
                ),
                canGoBack = firstActivity != null && period.from > firstActivity,
                canGoForward = offset > 0,
                inProgress = today in period,
            )
        }
        .catch { logcat(LogPriority.ERROR, it) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), null)

    fun step(by: Int) = offset.update { (it - by).coerceAtLeast(0) }

    @Immutable
    data class State(
        val today: LocalDate,
        val retrospective: Retrospective,
        val canGoBack: Boolean,
        val canGoForward: Boolean,
        /** The month or year is not over yet. */
        val inProgress: Boolean,
    )
}
