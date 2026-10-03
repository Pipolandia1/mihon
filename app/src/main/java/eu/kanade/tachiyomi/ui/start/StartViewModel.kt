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
import eu.kanade.tachiyomi.readingstats.comics.online.AutoMetadataScheduler
import eu.kanade.tachiyomi.releases.FollowOption
import eu.kanade.tachiyomi.releases.ReleaseFeed
import eu.kanade.tachiyomi.releases.ReleaseFollowing
import eu.kanade.tachiyomi.releases.ReleaseOpener
import eu.kanade.tachiyomi.ui.start.catalog.CatalogDiscovery
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.start.EntityKey
import tachiyomi.domain.start.EntityKind
import tachiyomi.domain.start.Feedback
import tachiyomi.domain.start.FeedbackSubject
import tachiyomi.domain.start.FeedbackVerdict
import tachiyomi.domain.start.Follow
import tachiyomi.domain.start.ReleaseItem
import tachiyomi.domain.start.ReleaseWeek
import tachiyomi.domain.start.RelevantRelease
import tachiyomi.domain.start.StartFeedResult
import tachiyomi.domain.start.StartRepository
import java.time.LocalDate
import kotlin.time.Clock

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class StartViewModel(
    continueReadingSource: ContinueReadingSource,
    feedSource: StartFeedSource,
    private val startRepository: StartRepository,
    private val discovery: CatalogDiscovery,
    private val preferences: StartPreferences,
    private val releaseFeed: ReleaseFeed,
    private val opener: ReleaseOpener,
    private val following: ReleaseFollowing,
    private val autoMetadata: AutoMetadataScheduler,
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
    private val outside = discovery.state
        .map { (it as? CatalogDiscovery.State.Ready)?.works.orEmpty() }
        .distinctUntilChanged()

    private val feed = feedSource.subscribe(continueReading, outside, discovery.answers)
        .onEach { result ->
            if (!feedLogged) {
                feedLogged = true
                logElapsed("blocks ${result.blocks.map { it.kind }}")
            }
        }
        .map<StartFeedResult, StartFeedResult?> { it }
        .onStart { emit(null) }

    val state: StateFlow<State> = combine(
        continueReading,
        feed,
        discovery.state,
        following.follows,
    ) { continueItems, feed, catalog, follows ->
        State(loading = false, continueReading = continueItems, feed = feed, catalog = catalog, follows = follows)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(StartConfig.STOP_TIMEOUT_MS), State())

    init {
        // This week's releases (H6): kept on the device, fetched at most once a day.
        viewModelScope.launchIO { releaseFeed.refresh(ReleaseWeek.of(LocalDate.now())) }
        // The alerts' daily check while something is followed (H7), again after a restore.
        viewModelScope.launchIO { following.keepScheduled() }
        // Comic metadata without a tap (H13): a run now, daily, and for each work added or opened.
        viewModelScope.launchIO { autoMetadata.keepUp() }
        // The catalogue and the sources are asked in the background, after the library, and again
        // when the switch or the order of sources changes; leaving the Início cancels it.
        viewModelScope.launchIO {
            combine(preferences.searchSources.changes(), preferences.recommendationSources.changes(), ::Pair)
                .collectLatest {
                    val works = startRepository.subscribeWorks().first()
                    val feedback = startRepository.subscribeFeedback().first()
                    discovery.refresh(works, feedback)
                }
        }
    }

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

    /**
     * Opens a release: the reader's own entry for a series they have; otherwise the series looked
     * for in their sources, by the tap's own search ([onNotFound] when no source has it).
     */
    fun openRelease(pick: RelevantRelease, onOpen: (Long) -> Unit, onNotFound: () -> Unit) {
        viewModelScope.launch {
            opener.entryFor(pick.item, pick.reason)?.let(onOpen) ?: onNotFound()
        }
    }

    suspend fun followOptions(item: ReleaseItem): List<FollowOption> = following.options(item)

    fun saveFollows(options: List<FollowOption>) {
        viewModelScope.launchIO { following.save(options) }
    }

    /** "Não quero recomendações de …": a creator or a character, by name, until undone. */
    fun setEntityFeedback(kind: EntityKind, name: String) {
        val now = Clock.System.now().toEpochMilliseconds()
        viewModelScope.launchIO {
            startRepository.setFeedback(
                Feedback(subjectOf(kind), EntityKey.normalize(name), FeedbackVerdict.NOT_INTERESTED, null, now),
            )
        }
    }

    fun undoEntityFeedback(kind: EntityKind, name: String) {
        viewModelScope.launchIO { startRepository.removeFeedback(subjectOf(kind), EntityKey.normalize(name)) }
    }

    /** "Não tenho interesse neste arco" (H9): the arc stays off the Início until undone. */
    fun setArcFeedback(arcKey: String) {
        val now = Clock.System.now().toEpochMilliseconds()
        viewModelScope.launchIO {
            startRepository.setFeedback(
                Feedback(FeedbackSubject.ARC, arcKey, FeedbackVerdict.NOT_INTERESTED, null, now),
            )
        }
    }

    fun undoArcFeedback(arcKey: String) {
        viewModelScope.launchIO { startRepository.removeFeedback(FeedbackSubject.ARC, arcKey) }
    }

    private fun subjectOf(kind: EntityKind) = when (kind) {
        EntityKind.CHARACTER, EntityKind.TEAM -> FeedbackSubject.CHARACTER
        else -> FeedbackSubject.CREATOR
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
        /** Where discovery beyond the library stands (H5). */
        val catalog: CatalogDiscovery.State = CatalogDiscovery.State.Idle,
        /** What the reader follows among the releases (H7): the bells show it. */
        val follows: List<Follow> = emptyList(),
    )
}
