package eu.kanade.tachiyomi.ui.start.releases

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.start.ReleasesCalendar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.releases.FollowOption
import eu.kanade.tachiyomi.releases.ReleaseFeed
import eu.kanade.tachiyomi.releases.ReleaseFollowing
import eu.kanade.tachiyomi.releases.ReleaseOpener
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.start.catalog.CatalogDiscovery
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.start.CatalogAnswer
import tachiyomi.domain.start.Feedback
import tachiyomi.domain.start.Follow
import tachiyomi.domain.start.ReleaseItem
import tachiyomi.domain.start.ReleaseReason
import tachiyomi.domain.start.ReleaseRepository
import tachiyomi.domain.start.ReleaseWeek
import tachiyomi.domain.start.ReleaseWeekList
import tachiyomi.domain.start.Releases
import tachiyomi.domain.start.RelevantRelease
import tachiyomi.domain.start.StartRepository
import tachiyomi.domain.start.WorkFacts
import tachiyomi.i18n.MR
import java.time.LocalDate

/**
 * The releases calendar (docs/HOME_PLAN.md §9.2, H6): a week at a time, what matters to the reader
 * first with the reason, the rest of the week by day below, folded.
 */
class ReleasesScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val model = metroViewModel<ReleasesModel>()
        val state by model.state.collectAsStateWithLifecycle()
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()

        ReleasesCalendar(
            state = state,
            snackbarHostState = snackbar,
            navigateUp = navigator::pop,
            onWeek = model::showWeek,
            onOnlyRelevant = model::setOnlyRelevant,
            loadFollowOptions = model::followOptions,
            onSaveFollows = model::saveFollows,
            onOpen = { item, reason ->
                model.open(
                    item = item,
                    reason = reason,
                    onOpen = { navigator.push(MangaScreen(it)) },
                    onNotFound = {
                        scope.launch {
                            snackbar.showSnackbar(context.stringResource(MR.strings.start_release_not_found))
                        }
                    },
                )
            },
        )
    }
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class ReleasesModel(
    private val feed: ReleaseFeed,
    private val repository: ReleaseRepository,
    private val opener: ReleaseOpener,
    private val following: ReleaseFollowing,
    startRepository: StartRepository,
    discovery: CatalogDiscovery,
) : ViewModel() {

    private val thisWeek = ReleaseWeek.of(LocalDate.now())
    private val week = MutableStateFlow(thisWeek)
    private val fetch = MutableStateFlow(Fetch())
    private val onlyRelevant = MutableStateFlow(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<State> = combine(
        week.flatMapLatest { repository.subscribeWeek(it) },
        combine(
            startRepository.subscribeWorks(),
            startRepository.subscribeFeedback(),
            discovery.answers,
            following.follows,
            ::Reader,
        ),
        fetch,
        onlyRelevant,
        week,
    ) { list, (works, feedback, answers, follows), fetch, only, week ->
        val items = list?.items.orEmpty()
        val relevant = Releases.relevant(
            items,
            works,
            feedback = feedback,
            answers = answers,
            nowMs = System.currentTimeMillis(),
        )
        val relevantKeys = relevant.map { it.item.key }.toSet()
        val others = Releases.grouped(items).filter { it.key !in relevantKeys }
        State(
            week = week,
            isThisWeek = week == thisWeek,
            list = list,
            relevant = relevant,
            others = others.groupBy { day(it) }.toList().sortedBy { it.first ?: LocalDate.MAX },
            onlyRelevant = only,
            loading = fetch.loading,
            failed = fetch.failed,
            follows = follows,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        State(week = thisWeek, isThisWeek = true),
    )

    init {
        showWeek(0)
    }

    /** Moves [weeks] from the week shown (0: this week again), and fetches it when needed. */
    fun showWeek(weeks: Long) {
        val target = if (weeks == 0L) thisWeek else week.value.plus(weeks)
        week.value = target
        fetch.value = Fetch(loading = true)
        viewModelScope.launchIO {
            val outcome = feed.refresh(target)
            if (week.value == target) fetch.value = Fetch(failed = outcome is ReleaseFeed.Outcome.Failed)
        }
    }

    suspend fun followOptions(item: ReleaseItem): List<FollowOption> = following.options(item)

    fun saveFollows(options: List<FollowOption>) {
        viewModelScope.launchIO { following.save(options) }
    }

    fun setOnlyRelevant(only: Boolean) {
        onlyRelevant.update { only }
    }

    fun open(item: ReleaseItem, reason: ReleaseReason?, onOpen: (Long) -> Unit, onNotFound: () -> Unit) {
        viewModelScope.launch { opener.entryFor(item, reason)?.let(onOpen) ?: onNotFound() }
    }

    private fun day(item: ReleaseItem): LocalDate? = item.storeDate?.let {
        runCatching { LocalDate.parse(it) }.getOrNull()
    }

    private data class Fetch(val loading: Boolean = false, val failed: Boolean = false)

    /** What the reader has, said and follows: the relevance's and the bells' inputs. */
    private data class Reader(
        val works: List<WorkFacts>,
        val feedback: List<Feedback>,
        val answers: List<CatalogAnswer>,
        val follows: List<Follow>,
    )

    @Immutable
    data class State(
        val week: ReleaseWeek,
        val isThisWeek: Boolean,
        /** The week as kept on the device; null until it has been fetched once. */
        val list: ReleaseWeekList? = null,
        val relevant: List<RelevantRelease> = emptyList(),
        /** The rest of the week by day; a null day when the list says only the week (the GCD's). */
        val others: List<Pair<LocalDate?, List<ReleaseItem>>> = emptyList(),
        val onlyRelevant: Boolean = false,
        val loading: Boolean = false,
        /** The last fetch failed: what is shown, if anything, is the list kept from before. */
        val failed: Boolean = false,
        /** What the reader follows (H7): the bells show it. */
        val follows: List<Follow> = emptyList(),
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
