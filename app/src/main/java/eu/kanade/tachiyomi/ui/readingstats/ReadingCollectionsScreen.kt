package eu.kanade.tachiyomi.ui.readingstats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.readingstats.collectionKindName
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import logcat.LogPriority
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Add
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.readingstats.plans.CollectionProgress
import tachiyomi.domain.readingstats.plans.CollectionRules
import tachiyomi.domain.readingstats.repository.ReadingPlanRepository
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.time.Duration.Companion.seconds

/** Standalone collections: runs, arcs, events and reading orders, across series and sources. */
class ReadingCollectionsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<ReadingCollectionsViewModel>()
        val collections by viewModel.collections.collectAsStateWithLifecycle()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.reading_stats_collections),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    text = { Text(stringResource(MR.strings.reading_stats_new_collection)) },
                    icon = { Icon(MaterialSymbols.Rounded.Add, contentDescription = null) },
                    onClick = { navigator.push(ReadingCollectionEditorScreen(null)) },
                )
            },
        ) { contentPadding ->
            val current = collections
            LazyColumn(contentPadding = contentPadding) {
                if (current != null && current.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(MR.strings.reading_stats_collections_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(MaterialTheme.padding.extraLarge),
                        )
                    }
                }
                items(current.orEmpty(), key = { it.collection.id }) { progress ->
                    CollectionRow(progress, onClick = {
                        navigator.push(ReadingCollectionEditorScreen(progress.collection.id))
                    })
                }
            }
        }
    }
}

@Composable
private fun CollectionRow(progress: CollectionProgress, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
    ) {
        Text(progress.collection.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(
            text = stringResource(collectionKindName(progress.collection.kind)) + " · " +
                stringResource(MR.strings.reading_stats_plan_progress, progress.done, progress.total),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
        )
        if (progress.total > 0) {
            LinearProgressIndicator(
                progress = { progress.done.toFloat() / progress.total },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MaterialTheme.padding.extraSmall),
            )
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class ReadingCollectionsViewModel(
    repository: ReadingPlanRepository,
) : ViewModel() {

    /** Null while loading. A chapter any collection shares with another counts in each, read once. */
    val collections: StateFlow<List<CollectionProgress>?> = repository.subscribeCollections()
        .flatMapLatest { collections ->
            if (collections.isEmpty()) return@flatMapLatest flowOf(emptyList())
            combine(
                collections.map { collection ->
                    repository.subscribeCollectionChapters(collection.id).map {
                        CollectionRules.progress(collection, it)
                    }
                },
            ) { it.toList() }
        }
        .catch { logcat(LogPriority.ERROR, it) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), null)
}
