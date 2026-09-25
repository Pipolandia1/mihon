package eu.kanade.presentation.start

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.browse.components.SourceIcon
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.tachiyomi.ui.start.sources.RecommendationSourcesModel
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Add
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.DragHandle
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.domain.source.model.Source
import tachiyomi.domain.start.Attempt
import tachiyomi.domain.start.FailureKind
import tachiyomi.domain.start.Resolution
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

/** "Fontes das recomendações" (docs/HOME_PLAN.md §7.2): the order, the switch, and a test search. */
@Composable
fun RecommendationSourcesContent(
    state: RecommendationSourcesModel.State,
    navigateUp: () -> Unit,
    onAdd: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onCopyMigration: () -> Unit,
    onSearchSources: (Boolean) -> Unit,
    onTestName: (String) -> Unit,
    onTestYear: (String) -> Unit,
    onRunTest: () -> Unit,
    onOpenFound: (Attempt.Found) -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(MR.strings.start_sources_title),
                navigateUp = navigateUp,
                actions = {
                    if (state.canCopyMigration) {
                        AppBarActions(
                            listOf(
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.start_sources_copy_migration),
                                    onClick = onCopyMigration,
                                ),
                            ),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        if (state.loading) {
            LoadingScreen(Modifier.padding(contentPadding))
            return@Scaffold
        }
        val listState = rememberLazyListState()
        val reorderState = rememberReorderableLazyListState(listState, contentPadding) { from, to ->
            val fromIndex = state.chosen.indexOfFirst { chosenKey(it.id) == from.key }
            val toIndex = state.chosen.indexOfFirst { chosenKey(it.id) == to.key }
            if (fromIndex != -1 && toIndex != -1) onMove(fromIndex, toIndex)
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = contentPadding,
        ) {
            item(key = "search") {
                ListItem(
                    headlineContent = { Text(stringResource(MR.strings.start_sources_search)) },
                    supportingContent = { Text(stringResource(MR.strings.start_sources_search_summary)) },
                    trailingContent = { Switch(checked = state.searchSources, onCheckedChange = onSearchSources) },
                    modifier = Modifier.clickable { onSearchSources(!state.searchSources) },
                )
            }
            item(key = "about") { Explanation(stringResource(MR.strings.start_sources_about)) }
            item(key = "order-title") { Header(stringResource(MR.strings.start_sources_order)) }
            if (state.chosen.isEmpty()) {
                item(key = "order-empty") { Explanation(stringResource(MR.strings.start_sources_empty)) }
            }
            itemsIndexed(state.chosen, key = { _, source -> chosenKey(source.id) }) { index, source ->
                ReorderableItem(reorderState, key = chosenKey(source.id)) {
                    ListItem(
                        leadingContent = { SourceIcon(source = source) },
                        headlineContent = { Text(source.name) },
                        supportingContent = { Text(roleText(index, source)) },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { onRemove(source.id) }) {
                                    Icon(
                                        MaterialSymbols.Rounded.Close,
                                        contentDescription = stringResource(MR.strings.start_sources_remove),
                                    )
                                }
                                if (state.chosen.size > 1) {
                                    Icon(
                                        MaterialSymbols.Rounded.DragHandle,
                                        contentDescription = null,
                                        modifier = Modifier.draggableHandle(),
                                    )
                                }
                            }
                        },
                    )
                }
            }
            if (state.others.isNotEmpty()) {
                item(key = "others-title") { Header(stringResource(MR.strings.start_sources_others)) }
            }
            items(state.others, key = { "other-${it.id}" }) { source ->
                ListItem(
                    leadingContent = { SourceIcon(source = source) },
                    headlineContent = { Text(source.name) },
                    trailingContent = {
                        Icon(
                            MaterialSymbols.Rounded.Add,
                            contentDescription = stringResource(MR.strings.start_sources_add),
                        )
                    },
                    modifier = Modifier.clickable { onAdd(source.id) },
                )
            }
            item(key = "test") {
                TestSearch(
                    test = state.test,
                    canRun = state.chosen.isNotEmpty(),
                    onName = onTestName,
                    onYear = onTestYear,
                    onRun = onRunTest,
                    onOpen = onOpenFound,
                )
            }
        }
    }
}

private fun chosenKey(id: Long) = "chosen-$id"

@Composable
private fun roleText(index: Int, source: Source): String {
    val role = if (index == 0) {
        stringResource(MR.strings.start_sources_primary)
    } else {
        stringResource(MR.strings.start_sources_reserve, index)
    }
    return if (source.isStub) "$role · ${stringResource(MR.strings.start_sources_not_installed)}" else role
}

@Composable
private fun TestSearch(
    test: RecommendationSourcesModel.TestState,
    canRun: Boolean,
    onName: (String) -> Unit,
    onYear: (String) -> Unit,
    onRun: () -> Unit,
    onOpen: (Attempt.Found) -> Unit,
) {
    Column(
        modifier = Modifier.padding(bottom = MaterialTheme.padding.large),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        Header(stringResource(MR.strings.start_sources_test))
        Explanation(stringResource(MR.strings.start_sources_test_summary))
        Row(
            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            OutlinedTextField(
                value = test.name,
                onValueChange = onName,
                label = { Text(stringResource(MR.strings.start_sources_test_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                modifier = Modifier.weight(2f),
            )
            OutlinedTextField(
                value = test.year,
                onValueChange = onYear,
                label = { Text(stringResource(MR.strings.start_sources_test_year)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onRun, enabled = canRun && test.name.isNotBlank() && !test.running) {
                Text(stringResource(MR.strings.start_sources_test_run))
            }
            if (test.running) CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
        test.result?.let { TestResultRows(it, onOpen) }
    }
}

@Composable
private fun TestResultRows(result: RecommendationSourcesModel.TestResult, onOpen: (Attempt.Found) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
    ) {
        val found = result.found
        val summary = when {
            found != null -> stringResource(
                MR.strings.start_sources_result_found,
                result.foundSource.orEmpty(),
                found.hit.title,
            )
            result.status == Resolution.Status.NO_SOURCES -> stringResource(MR.strings.start_sources_result_no_sources)
            result.status == Resolution.Status.FAILED -> stringResource(MR.strings.start_sources_result_failed)
            else -> stringResource(MR.strings.start_sources_result_not_found)
        }
        Text(text = summary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        result.rows.forEach { row ->
            Text(
                text = "${row.sourceName}: ${attemptText(row.attempt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
            )
        }
        if (found != null) {
            TextButton(onClick = { onOpen(found) }) { Text(stringResource(MR.strings.start_sources_open)) }
        }
    }
}

@Composable
private fun attemptText(attempt: Attempt): String = when (attempt) {
    is Attempt.Found -> stringResource(MR.strings.start_sources_attempt_found, attempt.hit.title)
    is Attempt.NoResults -> stringResource(MR.strings.start_sources_attempt_no_results)
    is Attempt.NoMatch ->
        pluralStringResource(MR.plurals.start_sources_attempt_no_match, attempt.results, attempt.results, attempt.first)
    is Attempt.Failed -> stringResource(
        MR.strings.start_sources_attempt_failed,
        failureText(attempt.kind, attempt.detail),
    )
    is Attempt.Skipped -> stringResource(MR.strings.start_sources_attempt_skipped, failureText(attempt.kind, null))
}

@Composable
private fun failureText(kind: FailureKind, detail: String?): String = when (kind) {
    FailureKind.CLOUDFLARE -> stringResource(MR.strings.start_sources_failure_cloudflare)
    FailureKind.SITE_DOWN -> stringResource(MR.strings.start_sources_failure_site_down, detail.orEmpty())
    FailureKind.HTTP -> stringResource(MR.strings.start_sources_failure_http, detail.orEmpty())
    FailureKind.TIMEOUT -> stringResource(MR.strings.start_sources_failure_timeout)
    FailureKind.NETWORK -> stringResource(MR.strings.start_sources_failure_network)
    FailureKind.NOT_INSTALLED -> stringResource(MR.strings.start_sources_not_installed)
    FailureKind.OTHER -> stringResource(MR.strings.start_sources_failure_other, detail.orEmpty())
}

@Composable
private fun Header(text: String) {
    Text(
        text = text,
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

@Composable
private fun Explanation(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
        modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
    )
}
