package eu.kanade.tachiyomi.ui.start.sources

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
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.presentation.start.RecommendationSourcesContent
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.start.StartPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.start.Attempt
import tachiyomi.domain.start.CatalogSeries
import tachiyomi.domain.start.Resolution
import tachiyomi.source.local.LocalSource

/**
 * "Fontes das recomendações" (docs/HOME_PLAN.md §7.2, H4): the primary source and the reserves, in
 * the order a recommended series from outside the library is looked for; whether the Início may
 * look at all; and a search to test the list by hand.
 */
class RecommendationSourcesScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = metroViewModel<RecommendationSourcesModel>()
        val state by model.state.collectAsStateWithLifecycle()

        RecommendationSourcesContent(
            state = state,
            navigateUp = navigator::pop,
            onAdd = model::add,
            onRemove = model::remove,
            onMove = model::move,
            onCopyMigration = model::copyMigration,
            onSearchSources = model::setSearchSources,
            onTestName = model::setTestName,
            onTestYear = model::setTestYear,
            onRunTest = model::runTest,
            onOpenFound = { found -> model.open(found) { navigator.push(MangaScreen(it)) } },
        )
    }
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class RecommendationSourcesModel(
    private val preferences: StartPreferences,
    private val sourcePreferences: SourcePreferences,
    private val sourceManager: SourceManager,
    private val finder: SourceFinder,
) : ViewModel() {

    val state: StateFlow<State>
        field = MutableStateFlow(State())

    init {
        viewModelScope.launchIO {
            combine(
                sourceManager.sources,
                preferences.recommendationSources.changes(),
                preferences.searchSources.changes(),
                sourcePreferences.enabledLanguages.changes(),
            ) { sources, order, search, languages ->
                // Searchable online sources, as the migration lists them.
                val installed = sources.filterIsInstance<CatalogueSource>()
                    // The reader's own files: what is in them is already the reader's.
                    .filter { it.id != LocalSource.ID }
                    .associateBy { it.id }
                // A source whose extension was removed stays in the list, marked, until taken out.
                val chosen = order.map { id ->
                    val source = installed[id]
                    Source(id, source?.lang.orEmpty(), source?.name ?: id.toString(), false, isStub = source == null)
                }
                val others = installed.values
                    .filter { it.id !in order && it.lang in languages }
                    .sortedBy { it.name.lowercase() }
                    .map { Source(it.id, it.lang, it.name, supportsLatest = false, isStub = false) }
                val migration = sourcePreferences.migrationSources.get().filter { it in installed }
                state.update {
                    it.copy(
                        loading = false,
                        chosen = chosen,
                        others = others,
                        searchSources = search,
                        canCopyMigration = migration.isNotEmpty() && migration != order,
                    )
                }
            }.collect {}
        }
    }

    private fun order() = preferences.recommendationSources.get()

    fun add(sourceId: Long) {
        if (sourceId !in order()) preferences.recommendationSources.set(order() + sourceId)
    }

    fun remove(sourceId: Long) {
        preferences.recommendationSources.set(order() - sourceId)
    }

    fun move(from: Int, to: Int) {
        val order = order().toMutableList()
        if (from !in order.indices || to !in order.indices) return
        order.add(to, order.removeAt(from))
        preferences.recommendationSources.set(order)
    }

    fun copyMigration() {
        viewModelScope.launchIO {
            val installed = sourceManager.getAll().filterIsInstance<CatalogueSource>().map { it.id }.toSet()
            val migration = sourcePreferences.migrationSources.get().filter { it in installed && it != LocalSource.ID }
            if (migration.isNotEmpty()) preferences.recommendationSources.set(migration)
        }
    }

    fun setSearchSources(enabled: Boolean) {
        preferences.searchSources.set(enabled)
    }

    fun setTestName(name: String) {
        state.update { it.copy(test = it.test.copy(name = name)) }
    }

    fun setTestYear(year: String) {
        state.update { it.copy(test = it.test.copy(year = year.filter(Char::isDigit).take(YEAR_DIGITS))) }
    }

    fun runTest() {
        val test = state.value.test
        val name = test.name.trim()
        if (name.isEmpty() || test.running) return
        state.update { it.copy(test = it.test.copy(running = true, result = null)) }
        viewModelScope.launchIO {
            val year = test.year.toIntOrNull()
            val resolution = finder.test(CatalogSeries(key = "test:$name:$year", name = name, year = year))
            val rows = resolution.attempts.map { AttemptRow(finder.sourceName(it.sourceId), it) }
            val found = resolution.attempts.filterIsInstance<Attempt.Found>().firstOrNull()
            val result = TestResult(resolution.status, rows, found, found?.let { finder.sourceName(it.sourceId) })
            state.update { it.copy(test = it.test.copy(running = false, result = result)) }
        }
    }

    /** Makes the entry outside the library for what was found, and opens its page. */
    fun open(found: Attempt.Found, onOpen: (Long) -> Unit) {
        viewModelScope.launch {
            onOpen(withIOContext { finder.entryFor(found) })
        }
    }

    @Immutable
    data class State(
        val loading: Boolean = true,
        val chosen: List<Source> = emptyList(),
        val others: List<Source> = emptyList(),
        val searchSources: Boolean = true,
        val canCopyMigration: Boolean = false,
        val test: TestState = TestState(),
    )

    @Immutable
    data class TestState(
        val name: String = "",
        val year: String = "",
        val running: Boolean = false,
        val result: TestResult? = null,
    )

    @Immutable
    data class TestResult(
        val status: Resolution.Status,
        val rows: List<AttemptRow>,
        val found: Attempt.Found?,
        val foundSource: String?,
    )

    @Immutable
    data class AttemptRow(val sourceName: String, val attempt: Attempt)

    private companion object {
        const val YEAR_DIGITS = 4
    }
}
