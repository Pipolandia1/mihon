package eu.kanade.tachiyomi.ui.readingstats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.readingstats.collectionKindName
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.flow.collectLatest
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.ArrowDownward
import mihon.icons.materialsymbols.rounded.ArrowUpward
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.Done
import tachiyomi.domain.readingstats.plans.CollectionItem
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

private val DialogListMaxHeight = 360.dp

/** Creates a collection, or edits the one with [collectionId]. */
class ReadingCollectionEditorScreen(private val collectionId: Long?) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = assistedMetroViewModel<
            ReadingCollectionEditorViewModel,
            ReadingCollectionEditorViewModel.Factory,
            > {
            create(collectionId)
        }
        val state by viewModel.state.collectAsStateWithLifecycle()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(
                        if (state.isNew) {
                            MR.strings.reading_stats_new_collection
                        } else {
                            MR.strings.reading_stats_edit_collection
                        },
                    ),
                    navigateUp = navigator::pop,
                    actions = {
                        if (!state.isNew) {
                            IconButton(onClick = {
                                viewModel.setDialog(ReadingCollectionEditorViewModel.Dialog.ConfirmDelete)
                            }) {
                                Icon(
                                    MaterialSymbols.Rounded.Delete,
                                    stringResource(MR.strings.reading_stats_delete_collection),
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
                item {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                        modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                    ) {
                        ReadingCollectionEditorViewModel.KINDS.forEach { kind ->
                            FilterChip(
                                selected = state.kind == kind,
                                onClick = { viewModel.setKind(kind) },
                                label = { Text(stringResource(collectionKindName(kind))) },
                            )
                        }
                    }
                }
                itemsIndexed(
                    state.items,
                    key = { index, item -> "$index-${item.mangaUrl}-${item.chapterUrl}" },
                ) { index, item ->
                    ItemRow(
                        item = item,
                        position = index + 1,
                        canMoveUp = index > 0,
                        canMoveDown = index < state.items.lastIndex,
                        onMove = { viewModel.move(index, it) },
                        onRemove = { viewModel.remove(index) },
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
                        OutlinedButton(onClick = viewModel::openArcPicker) {
                            Text(stringResource(MR.strings.reading_stats_add_arc))
                        }
                        if (!state.isNew) {
                            OutlinedButton(onClick = viewModel::startPlan) {
                                Text(stringResource(MR.strings.reading_stats_start_plan))
                            }
                        }
                    }
                }
                item {
                    Text(
                        text = stringResource(MR.strings.reading_stats_collection_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
                        modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                    )
                }
            }
        }

        val onDismissRequest = { viewModel.setDialog(null) }
        when (val dialog = state.dialog) {
            is ReadingCollectionEditorViewModel.Dialog.PickEntry -> PickEntryDialog(
                query = dialog.query,
                results = dialog.results,
                onQuery = viewModel::search,
                onPick = viewModel::pickEntry,
                onDismissRequest = onDismissRequest,
            )
            is ReadingCollectionEditorViewModel.Dialog.PickChapters -> PickChaptersDialog(
                title = dialog.entry.title,
                chapters = dialog.chapters,
                selected = dialog.selected,
                onSelect = viewModel::setPickedChapters,
                onConfirm = viewModel::confirmChapters,
                onDismissRequest = onDismissRequest,
            )
            is ReadingCollectionEditorViewModel.Dialog.PickArc -> AlertDialog(
                onDismissRequest = onDismissRequest,
                title = { Text(stringResource(MR.strings.reading_stats_add_arc)) },
                text = {
                    if (dialog.arcs.isEmpty()) {
                        Text(stringResource(MR.strings.reading_stats_arcs_empty))
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = DialogListMaxHeight)) {
                            itemsIndexed(dialog.arcs) { _, arc ->
                                Text(
                                    text = arc,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.addArc(arc) }
                                        .padding(vertical = MaterialTheme.padding.small),
                                )
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
                },
            )
            ReadingCollectionEditorViewModel.Dialog.ConfirmDelete -> AlertDialog(
                onDismissRequest = onDismissRequest,
                title = { Text(stringResource(MR.strings.reading_stats_delete_collection)) },
                text = { Text(stringResource(MR.strings.reading_stats_delete_collection_confirm)) },
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
                    ReadingCollectionEditorViewModel.Event.Closed -> navigator.pop()
                    is ReadingCollectionEditorViewModel.Event.OpenPlan ->
                        navigator.replace(ReadingPlanEditorScreen(event.planId))
                }
            }
        }
    }
}

@Composable
private fun ItemRow(
    item: CollectionItem,
    position: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = MaterialTheme.padding.medium),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "$position. ${item.label}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.wholeEntry) {
                Text(
                    text = stringResource(MR.strings.reading_stats_whole_entry),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
                )
            }
        }
        IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
            Icon(MaterialSymbols.Rounded.ArrowUpward, stringResource(MR.strings.reading_stats_move_up))
        }
        IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
            Icon(MaterialSymbols.Rounded.ArrowDownward, stringResource(MR.strings.reading_stats_move_down))
        }
        IconButton(onClick = onRemove) {
            Icon(MaterialSymbols.Rounded.Close, stringResource(MR.strings.reading_stats_remove_entry))
        }
    }
}
