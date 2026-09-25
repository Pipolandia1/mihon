package eu.kanade.tachiyomi.ui.start

import android.os.SystemClock
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.readingstats.ContinueItem
import eu.kanade.tachiyomi.readingstats.ContinueReadingSource
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.start.Feedback
import tachiyomi.domain.start.FeedbackSubject
import tachiyomi.domain.start.FeedbackVerdict
import tachiyomi.domain.start.StartFeedResult
import tachiyomi.domain.start.StartRepository
import kotlin.time.Clock

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class StartViewModel(
    continueReadingSource: ContinueReadingSource,
    feedSource: StartFeedSource,
    private val startRepository: StartRepository,
) : ViewModel() {

    // When each part first arrived, for the timing the plan asks to be measured, not guessed.
    private val created = SystemClock.elapsedRealtime()
    private var continueLogged = false
    private var feedLogged = false

    private val continueReading = continueReadingSource.subscribe(StartConfig.CONTINUE_READING_COUNT)
        .onEach {
            if (!continueLogged) {
                continueLogged = true
                logElapsed("continue (${it.size})")
            }
        }
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(StartConfig.STOP_TIMEOUT_MS), replay = 1)

    // "Continuar lendo" is shown as soon as it is known; the other blocks, which need the whole
    // library, arrive after it and never hold it back (docs/HOME_PLAN.md §2, rule 4).
    private val feed = feedSource.subscribe(continueReading)
        .onEach { result ->
            if (!feedLogged) {
                feedLogged = true
                logElapsed("blocks ${result.blocks.map { it.kind }}")
            }
        }
        .map<StartFeedResult, StartFeedResult?> { it }
        .onStart { emit(null) }

    val state: StateFlow<State> = combine(continueReading, feed) { continueItems, feed ->
        State(loading = false, continueReading = continueItems, feed = feed)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(StartConfig.STOP_TIMEOUT_MS), State())

    /**
     * What the reader said of a recommendation. "Agora não" lasts [StartConfig.LATER_DAYS]; the
     * others until undone. The Início recomputes from the stored feedback, so the next pick follows.
     */
    fun setFeedback(mangaId: Long, verdict: FeedbackVerdict) {
        val now = Clock.System.now().toEpochMilliseconds()
        val until = if (verdict == FeedbackVerdict.LATER) now + StartConfig.LATER_DAYS * DAY_MS else null
        viewModelScope.launchIO {
            startRepository.setFeedback(Feedback(FeedbackSubject.WORK, mangaId.toString(), verdict, until, now))
        }
    }

    fun undoFeedback(mangaId: Long) {
        viewModelScope.launchIO { startRepository.removeFeedback(FeedbackSubject.WORK, mangaId.toString()) }
    }

    private fun logElapsed(what: String) {
        logcat(LogPriority.INFO) { "StartFeed $what in ${SystemClock.elapsedRealtime() - created}ms" }
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }

    @Immutable
    data class State(
        val loading: Boolean = true,
        /** The first is the one to continue; the rest are other works in progress. */
        val continueReading: List<ContinueItem> = emptyList(),
        /** The other blocks; null until the library has been read. */
        val feed: StartFeedResult? = null,
    )
}
