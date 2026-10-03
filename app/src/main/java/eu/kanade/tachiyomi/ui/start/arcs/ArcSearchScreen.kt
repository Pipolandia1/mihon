package eu.kanade.tachiyomi.ui.start.arcs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
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
import eu.kanade.presentation.start.ArcSearchContent
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.arcs.ArcCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.start.CatalogName

/** "Arcos e eventos": an arc or event found by name, until the Início has its own block (H9). */
class ArcSearchScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = metroViewModel<ArcSearchModel>()
        val state by model.state.collectAsStateWithLifecycle()

        ArcSearchContent(
            state = state,
            navigateUp = navigator::pop,
            onQuery = model::setQuery,
            onSearch = model::search,
            onOpen = { navigator.push(ArcScreen(ArcCatalog.keyOf(it.id), it.name)) },
        )
    }
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class ArcSearchModel(
    private val catalog: ArcCatalog,
) : ViewModel() {

    private val mutableState = MutableStateFlow(State(available = catalog.available))
    val state: StateFlow<State> = mutableState.asStateFlow()

    fun setQuery(query: String) {
        mutableState.update { it.copy(query = query) }
    }

    fun search() {
        val query = state.value.query.trim()
        if (query.isEmpty() || state.value.searching) return
        mutableState.update { it.copy(searching = true, failed = false) }
        viewModelScope.launchIO {
            val results = try {
                catalog.search(query)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "StartFeed arcs: Metron did not answer a search" }
                null
            }
            mutableState.update {
                it.copy(searching = false, results = results ?: it.results, failed = results == null)
            }
        }
    }

    @Immutable
    data class State(
        val query: String = "",
        val searching: Boolean = false,
        /** Null until searched. */
        val results: List<CatalogName>? = null,
        val failed: Boolean = false,
        /** Whether Metron can be asked (a token). */
        val available: Boolean = true,
    )
}
