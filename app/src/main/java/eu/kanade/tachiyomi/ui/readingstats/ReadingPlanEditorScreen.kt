package eu.kanade.tachiyomi.ui.readingstats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.readingstats.collectionKindName
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.formatChapterNumber
import kotlinx.coroutines.flow.collectLatest
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.Done
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.readingstats.plans.ReadingCollection
import tachiyomi.domain.readingstats.repository.PlannableEntry
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

private val EntryCoverHeight = 56.dp
private val PaceFieldWidth = 96.dp
private val DialogListMaxHeight = 360.dp

/** Creates a reading plan, or edits the one with [planId]. */
class ReadingPlanEditorScreen(private val planId: Long?) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = assistedMetroViewModel<ReadingPlanEditorViewModel, ReadingPlanEditorViewModel.Factory> {
            create(planId)
        }
        val state by viewModel.state.collectAsStateWithLifecycle()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(
                        if (state.isNew) MR.strings.reading_stats_new_plan else MR.strings.reading_stats_edit_plan,
                    ),
                    navigateUp = navigator::pop,
                    actions = {
                        if (!state.isNew) {
                            IconButton(onClick = {
                                viewModel.setDialog(ReadingPlanEditorViewModel.Dialog.ConfirmDelete)
                            }) {
                                Icon(
                                    MaterialSymbols.Rounded.Delete,
                                    stringResource(MR.strings.reading_stats_delete_plan),
                                )
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = {
                if (state.canSave) {
                    ExtendedFloatingActionButton(
                        text = { Text(stringResource(MR.strings.action_save)) },
                        icon = { Icon(MaterialSymbols.Rounded.Done, contentDescription = null) },
                        onClick = viewModel::save,
                    )
                }
            },
        ) { contentPadding ->
            if (state.loading) {
                LoadingScreen(Modifier.padding(contentPadding))
                return@Scaffold
            }
            LazyColumn(
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                // A plan over a standalone collection goes by the collection's name.
                if (state.collection == null) {
                    item {
                        OutlinedTextField(
                            value = state.name,
                            onValueChange = viewModel::setName,
                            label = { Text(stringResource(MR.strings.reading_stats_plan_name)) },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = MaterialTheme.padding.medium),
                        )
                    }
                }
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                        modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                    ) {
                        OutlinedTextField(
                            value = state.pace,
                            onValueChange = viewModel::setPace,
                            label = { Text(stringResource(MR.strings.reading_stats_pace)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.width(PaceFieldWidth),
                        )
                        Text(
                            text = stringResource(MR.strings.reading_stats_chapters_per_week),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (!state.isNew) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = MaterialTheme.padding.medium),
                        ) {
                            Text(
                                text = stringResource(MR.strings.reading_stats_plan_paused),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(checked = state.paused, onCheckedChange = viewModel::setPaused)
                        }
                    }
                }
                item {
                    Text(
                        text = stringResource(MR.strings.reading_stats_plan_entries),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                    )
                }
                val collection = state.collection
                if (collection != null) {
                    // A plan over a standalone collection reads it as it is; the collection is edited on its own.
                    item {
                        Text(
                            text = stringResource(MR.strings.reading_stats_plan_reads_collection, collection.name),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                        )
                    }
                    if (state.isNew) {
                        item {
                            TextButton(
                                onClick = { viewModel.useCollection(null) },
                                modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                            ) {
                                Text(stringResource(MR.strings.reading_stats_plan_no_collection))
                            }
                        }
                    }
                } else {
                    items(state.entries, key = { it.mangaId }) { entry ->
                        EntryRow(
                            entry = entry,
                            onPickChapters = { viewModel.openChapterPicker(entry.mangaId) },
                            onRemove = { viewModel.removeEntry(entry.mangaId) },
                        )
                    }
                    item {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                        ) {
                            OutlinedButton(onClick = viewModel::openEntryPicker) {
                                Text(stringResource(MR.strings.reading_stats_add_entry))
                            }
                            if (state.isNew) {
                                OutlinedButton(onClick = viewModel::openCollectionPicker) {
                                    Text(stringResource(MR.strings.reading_stats_plan_use_collection))
                                }
                            }
                        }
                    }
                }
                item {
                    Text(
                        text = stringResource(MR.strings.reading_stats_plan_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
                        modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                    )
                }
            }
        }

        val onDismissRequest = { viewModel.setDialog(null) }
        when (val dialog = state.dialog) {
            is ReadingPlanEditorViewModel.Dialog.PickEntry -> PickEntryDialog(
                query = dialog.query,
                results = dialog.results,
                onQuery = viewModel::search,
                onPick = viewModel::addEntry,
                onDismissRequest = onDismissRequest,
            )
            is ReadingPlanEditorViewModel.Dialog.PickChapters -> {
                val entry = state.entries.firstOrNull { it.mangaId == dialog.mangaId }
                if (entry != null) {
                    PickChaptersDialog(
                        title = entry.title,
                        chapters = entry.chapters,
                        selected = dialog.selected,
                        onSelect = viewModel::setPickedChapters,
                        onConfirm = viewModel::confirmChapters,
                        onDismissRequest = onDismissRequest,
                    )
                }
            }
            is ReadingPlanEditorViewModel.Dialog.PickCollection -> PickCollectionDialog(
                collections = dialog.collections,
                onPick = viewModel::useCollection,
                onDismissRequest = onDismissRequest,
            )
            ReadingPlanEditorViewModel.Dialog.ConfirmDelete -> AlertDialog(
                onDismissRequest = onDismissRequest,
                title = { Text(stringResource(MR.strings.reading_stats_delete_plan)) },
                text = { Text(stringResource(MR.strings.reading_stats_delete_plan_confirm)) },
                confirmButton = {
                    TextButton(onClick = viewModel::delete) { Text(stringResource(MR.strings.action_delete)) }
                },
                dismissButton = {
                    TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
                },
            )
            null -> {}
        }

        LaunchedEffect(Unit) {
            viewModel.eventFlow.collectLatest { event ->
                when (event) {
                    ReadingPlanEditorViewModel.Event.Closed -> navigator.pop()
                }
            }
        }
    }
}

@Composable
private fun EntryRow(
    entry: ReadingPlanEditorViewModel.Entry,
    onPickChapters: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPickChapters)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
    ) {
        MangaCover.Book(data = entry.cover, modifier = Modifier.height(EntryCoverHeight))
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
            val selected = entry.selected
            Text(
                text = if (selected == null) {
                    stringResource(MR.strings.reading_stats_plan_all_chapters, entry.chapters.size)
                } else {
                    stringResource(MR.strings.reading_stats_plan_some_chapters, selected.size, entry.chapters.size)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
            )
        }
        IconButton(onClick = onRemove) {
            Icon(MaterialSymbols.Rounded.Close, stringResource(MR.strings.reading_stats_remove_entry))
        }
    }
}

@Composable
internal fun PickEntryDialog(
    query: String,
    results: List<PlannableEntry>,
    onQuery: (String) -> Unit,
    onPick: (PlannableEntry) -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.reading_stats_add_entry)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQuery,
                    placeholder = { Text(stringResource(MR.strings.action_search_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(modifier = Modifier.heightIn(max = DialogListMaxHeight)) {
                    items(results, key = { it.mangaId }) { entry ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(entry) }
                                .padding(vertical = MaterialTheme.padding.small),
                        ) {
                            MangaCover.Book(data = entry.cover, modifier = Modifier.height(EntryCoverHeight))
                            Text(
                                text = entry.title,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = MaterialTheme.padding.medium),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
}

@Composable
internal fun PickChaptersDialog(
    title: String,
    chapters: List<Chapter>,
    selected: Set<Long>,
    onSelect: (Set<Long>) -> Unit,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                FlowRow {
                    TextButton(onClick = { onSelect(chapters.map(Chapter::id).toSet()) }) {
                        Text(stringResource(MR.strings.action_select_all))
                    }
                    TextButton(onClick = {
                        onSelect(chapters.filterNot(Chapter::read).map(Chapter::id).toSet())
                    }) {
                        Text(stringResource(MR.strings.reading_stats_unread_only))
                    }
                    TextButton(onClick = { onSelect(emptySet()) }) {
                        Text(stringResource(MR.strings.reading_stats_select_none))
                    }
                }
                LazyColumn(modifier = Modifier.heightIn(max = DialogListMaxHeight)) {
                    items(chapters, key = { it.id }) { chapter ->
                        val checked = chapter.id in selected
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(if (checked) selected - chapter.id else selected + chapter.id) },
                        ) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Text(
                                text = chapterLabel(chapter),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = MaterialTheme.padding.small),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = selected.isNotEmpty()) {
                Text(stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
}

private fun chapterLabel(chapter: Chapter): String {
    val number = chapter.chapterNumber.takeIf { chapter.isRecognizedNumber }?.let { "#${formatChapterNumber(it)}" }
    return listOfNotNull(number, chapter.name.takeIf { it.isNotBlank() && it != number }).joinToString(" · ")
}

@Composable
private fun PickCollectionDialog(
    collections: List<ReadingCollection>,
    onPick: (ReadingCollection) -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(MR.strings.reading_stats_plan_use_collection)) },
        text = {
            if (collections.isEmpty()) {
                Text(stringResource(MR.strings.reading_stats_collections_empty))
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = DialogListMaxHeight)) {
                    items(collections, key = { it.id }) { collection ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(collection) }
                                .padding(vertical = MaterialTheme.padding.small),
                        ) {
                            Text(collection.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = stringResource(collectionKindName(collection.kind)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
}
