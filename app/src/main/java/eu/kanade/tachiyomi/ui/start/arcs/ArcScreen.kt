package eu.kanade.tachiyomi.ui.start.arcs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import eu.kanade.presentation.start.ArcContent
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.arcs.ArcCatalog
import eu.kanade.tachiyomi.arcs.ArcReading
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.start.Arc
import tachiyomi.domain.start.ArcIssueView
import tachiyomi.domain.start.ArcProgress
import tachiyomi.domain.start.ArcSeries
import tachiyomi.domain.start.Arcs
import java.time.LocalDate

/**
 * An arc's page (docs/HOME_PLAN.md §13, H8): its issues in order, each opened in the reader from the
 * entry its series is read in, and how much of the arc is read.
 */
class ArcScreen(private val arcKey: String, private val name: String) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val model = metroViewModel<ArcModel>()
        val state by model.state.collectAsStateWithLifecycle()
        LaunchedEffect(arcKey) { model.open(arcKey) }

        ArcContent(
            state = state,
            name = name,
            navigateUp = navigator::pop,
            onOpen = { view ->
                val mangaId = view.mangaId
                val chapterId = view.chapterId
                if (mangaId != null && chapterId != null) {
                    context.startActivity(ReaderActivity.newIntent(context, mangaId, chapterId))
                }
            },
        )
    }
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class ArcModel(
    private val catalog: ArcCatalog,
    private val reading: ArcReading,
) : ViewModel() {

    private val loaded = MutableStateFlow<ArcCatalog.Loaded?>(null)
    private val loading = MutableStateFlow(true)
    private val series = MutableStateFlow<Map<String, ArcSeries>>(emptyMap())
    private var key: String? = null
    private var job: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    private val chapters = series
        .map { found -> found.values.filterIsInstance<ArcSeries.Found>().map { it.mangaId }.toSet() }
        .distinctUntilChanged()
        .flatMapLatest { reading.chapters(it) }

    val state: StateFlow<State> = combine(loaded, loading, series, chapters) { loaded, loading, series, chapters ->
        if (loaded == null) return@combine State(loading = loading, available = catalog.available)
        val views = Arcs.view(loaded.issues, series, chapters, LocalDate.now())
        State(
            loading = false,
            arc = loaded.arc,
            views = views,
            progress = Arcs.progress(views, series),
            failed = loaded.failed,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), State())

    /**
     * Opens [key] once: the arc, and then its series looked for one after the other. Another arc
     * stops the one before: its search does not go on writing into this one.
     */
    fun open(key: String) {
        if (this.key == key) return
        this.key = key
        job?.cancel()
        loaded.value = null
        loading.value = true
        series.value = emptyMap()
        job = viewModelScope.launchIO {
            val arc = catalog.arc(key)
            loaded.value = arc
            loading.value = false
            if (arc != null) reading.series(arc.issues).collect { series.value = it }
        }
    }

    @Immutable
    data class State(
        val loading: Boolean = true,
        val arc: Arc? = null,
        val views: List<ArcIssueView> = emptyList(),
        val progress: ArcProgress? = null,
        /** Metron did not answer: what is shown is the arc as kept. */
        val failed: Boolean = false,
        /** Whether Metron can be asked at all (a token). */
        val available: Boolean = true,
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
