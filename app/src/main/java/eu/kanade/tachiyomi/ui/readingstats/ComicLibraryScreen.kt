package eu.kanade.tachiyomi.ui.readingstats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.readingstats.statusName
import eu.kanade.presentation.readingstats.unitName
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.readingstats.comics.ComicField
import tachiyomi.domain.readingstats.comics.ComicLibrary
import tachiyomi.domain.readingstats.comics.ComicUnit
import tachiyomi.domain.readingstats.comics.EntryProgress
import tachiyomi.domain.readingstats.comics.LibraryStatus
import tachiyomi.domain.readingstats.repository.ComicMetadataRepository
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import kotlin.time.Duration.Companion.seconds

private val CoverHeight = 56.dp

/** The library by state, with each entry's progress; an entry opens its comic metadata. */
class ComicLibraryScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<ComicLibraryViewModel>()
        val entries by viewModel.entries.collectAsStateWithLifecycle()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.reading_stats_comic_library),
                    navigateUp = navigator::pop,
                    actions = {
                        AppBarActions(
                            listOf(
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.reading_stats_collections),
                                    onClick = { navigator.push(ReadingCollectionsScreen()) },
                                ),
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.reading_stats_online_settings),
                                    onClick = { navigator.push(ComicOnlineSettingsScreen()) },
                                ),
                            ),
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            val current = entries
            if (current == null) {
                LoadingScreen(Modifier.padding(contentPadding))
                return@Scaffold
            }
            LazyColumn(contentPadding = contentPadding) {
                item {
                    Text(
                        text = stringResource(MR.strings.reading_stats_comic_library_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
                        modifier = Modifier.padding(MaterialTheme.padding.medium),
                    )
                }
                LibraryStatus.entries.forEach { status ->
                    val group = current.filter { it.status == status }
                    if (group.isEmpty()) return@forEach
                    item(key = status) {
                        Text(
                            text = "${stringResource(statusName(status))} (${group.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(
                                start = MaterialTheme.padding.medium,
                                end = MaterialTheme.padding.medium,
                                top = MaterialTheme.padding.large,
                                bottom = MaterialTheme.padding.small,
                            ),
                        )
                    }
                    items(group, key = { it.entry.mangaId }) { progress ->
                        EntryRow(progress, onClick = { navigator.push(ComicMetadataScreen(progress.entry.mangaId)) })
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(progress: EntryProgress, onClick: () -> Unit) {
    val entry = progress.entry
    val unit = ComicUnit.of(entry.metadata.series(ComicField.UNIT)?.value)
    val total = progress.total
    val detail = listOf(
        stringResource(unitName(unit)),
        if (total != null) {
            stringResource(MR.strings.reading_stats_progress_known, progress.done, total)
        } else {
            stringResource(MR.strings.reading_stats_progress_unknown, progress.done)
        },
    ).joinToString(" · ")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
    ) {
        entry.cover?.let { MangaCover.Book(data = it, modifier = Modifier.height(CoverHeight)) }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = MaterialTheme.padding.medium),
        ) {
            Text(
                text = entry.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
            )
        }
    }
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class ComicLibraryViewModel(
    repository: ComicMetadataRepository,
) : ViewModel() {

    /** Null while loading; by title. */
    val entries: StateFlow<List<EntryProgress>?> = repository.subscribeLibrary()
        .map { entries -> entries.map(ComicLibrary::progress).sortedBy { it.entry.title.lowercase() } }
        .catch { logcat(LogPriority.ERROR, it) }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), null)
}
