package eu.kanade.presentation.start

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.tachiyomi.ui.start.arcs.ArcModel
import eu.kanade.tachiyomi.ui.start.arcs.ArcSearchModel
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Notifications
import mihon.icons.materialsymbols.rounded.Search
import mihon.icons.materialsymbols.roundedfilled.Notifications
import tachiyomi.domain.start.ArcIssueState
import tachiyomi.domain.start.ArcIssueView
import tachiyomi.domain.start.ArcProgress
import tachiyomi.domain.start.CatalogName
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * An arc's page (docs/HOME_PLAN.md §13, H8): what it is, how much of it is read, the next issue, and
 * every issue in order with where it is read from.
 */
@Composable
fun ArcContent(
    state: ArcModel.State,
    name: String,
    navigateUp: () -> Unit,
    onToggleFollow: () -> Unit,
    onOpen: (ArcIssueView) -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = state.arc?.name ?: name,
                navigateUp = navigateUp,
                actions = {
                    if (state.arc != null) {
                        AppBarActions(
                            listOf(
                                AppBar.Action(
                                    title = stringResource(
                                        if (state.followed) MR.strings.arc_unfollow else MR.strings.arc_follow,
                                    ),
                                    icon = if (state.followed) {
                                        MaterialSymbols.RoundedFilled.Notifications
                                    } else {
                                        MaterialSymbols.Rounded.Notifications
                                    },
                                    onClick = onToggleFollow,
                                ),
                            ),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        val arc = state.arc
        if (arc == null) {
            if (state.loading) {
                LoadingScreen(Modifier.padding(contentPadding))
            } else {
                Text(
                    text = stringResource(
                        if (state.available) MR.strings.arc_failed_none else MR.strings.arcs_need_metron,
                    ),
                    modifier = Modifier
                        .padding(contentPadding)
                        .padding(MaterialTheme.padding.medium),
                )
            }
            return@Scaffold
        }
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
            item(key = "header") {
                Row(modifier = Modifier.padding(MaterialTheme.padding.medium)) {
                    arc.imageUrl?.let { MangaCover.Book(data = it, modifier = Modifier.height(ArcImageHeight)) }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = MaterialTheme.padding.medium),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                    ) {
                        Text(arc.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Secondary(pluralStringResource(MR.plurals.start_issues, state.views.size, state.views.size))
                        arc.description?.let { Description(it) }
                    }
                }
            }
            if (state.failed) {
                item(key = "failed") {
                    Secondary(
                        stringResource(MR.strings.arc_failed_kept),
                        Modifier.padding(horizontal = MaterialTheme.padding.medium),
                    )
                }
            }
            state.progress?.let { progress -> item(key = "progress") { Progress(progress, onOpen) } }
            items(state.views, key = { it.issue.key + it.issue.position }) { view -> IssueRow(view, onOpen) }
            item(key = "credit") {
                Secondary(
                    stringResource(MR.strings.releases_credit_metron),
                    Modifier.padding(MaterialTheme.padding.medium),
                )
            }
        }
    }
}

/** "8 de 12 · 67 % · faltam 4", the bar, the series still looked for, and the next issue. */
@Composable
private fun Progress(progress: ArcProgress, onOpen: (ArcIssueView) -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        val percent = progress.percent
        if (percent != null) {
            Text(
                text = if (progress.left == 0) {
                    stringResource(MR.strings.arc_read_all, progress.read, progress.total)
                } else {
                    stringResource(MR.strings.arc_progress, progress.read, progress.total, percent, progress.left)
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            LinearProgressIndicator(
                progress = { progress.read.toFloat() / progress.total },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (progress.searching > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Secondary(
                    stringResource(MR.strings.arc_searching, progress.series - progress.searching, progress.series),
                    Modifier.padding(start = MaterialTheme.padding.small),
                )
            }
        }
        val next = progress.next
        when {
            next != null -> Button(onClick = { onOpen(next) }) {
                Text(
                    stringResource(
                        if (progress.read == 0) MR.strings.arc_start else MR.strings.arc_continue,
                        "${next.issue.seriesName} #${next.issue.number}",
                    ),
                )
            }
            progress.left > 0 && progress.searching == 0 -> Secondary(stringResource(MR.strings.arc_nothing_available))
        }
    }
}

@Composable
private fun IssueRow(view: ArcIssueView, onOpen: (ArcIssueView) -> Unit) {
    val issue = view.issue
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = view.chapterId != null) { onOpen(view) }
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${issue.position + 1}",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(PositionWidth),
        )
        MangaCover.Book(data = issue.coverUrl, modifier = Modifier.height(RowCoverHeight))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = MaterialTheme.padding.medium),
        ) {
            Text(
                text = "${issue.seriesName} #${issue.number}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (view.state == ArcIssueState.READ) FontWeight.Normal else FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            issue.coverDate?.let { monthText(it) }?.let { Secondary(stringResource(MR.strings.arc_cover_date, it)) }
            Text(
                text = stateText(view),
                style = MaterialTheme.typography.bodySmall,
                color = if (view.state == ArcIssueState.AVAILABLE) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun stateText(view: ArcIssueView): String = when (view.state) {
    ArcIssueState.READ -> stringResource(MR.strings.arc_state_read)
    ArcIssueState.AVAILABLE -> stringResource(MR.strings.start_in_source, view.sourceName.orEmpty())
    ArcIssueState.SEARCHING -> stringResource(MR.strings.arc_state_searching)
    ArcIssueState.NOT_FOUND -> stringResource(MR.strings.arc_state_not_found)
    ArcIssueState.NOT_OUT -> stringResource(MR.strings.arc_state_not_out, dayText(view.issue.storeDate))
}

@Composable
private fun Description(text: String) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        maxLines = if (expanded) Int.MAX_VALUE else DESCRIPTION_LINES,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clickable { expanded = !expanded },
    )
}

/** "Arcos e eventos": a search by name; an arc tapped opens its page. */
@Composable
fun ArcSearchContent(
    state: ArcSearchModel.State,
    navigateUp: () -> Unit,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onOpen: (CatalogName) -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(MR.strings.arcs_title),
                navigateUp = navigateUp,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
            if (!state.available) {
                item(key = "metron") {
                    Secondary(
                        stringResource(MR.strings.arcs_need_metron),
                        Modifier.padding(MaterialTheme.padding.medium),
                    )
                }
                return@LazyColumn
            }
            item(key = "search") {
                Row(
                    modifier = Modifier.padding(MaterialTheme.padding.medium),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = onQuery,
                        label = { Text(stringResource(MR.strings.arcs_search_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                        trailingIcon = {
                            if (state.searching) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    MaterialSymbols.Rounded.Search,
                                    contentDescription = stringResource(MR.strings.arcs_search),
                                    modifier = Modifier.clickable(onClick = onSearch),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            if (state.failed) {
                item(key = "failed") {
                    Secondary(
                        stringResource(MR.strings.arcs_search_failed),
                        Modifier.padding(MaterialTheme.padding.medium),
                    )
                }
            }
            val results = state.results
            if (results != null && results.isEmpty()) {
                item(key = "none") {
                    Secondary(
                        stringResource(MR.strings.arcs_none_found),
                        Modifier.padding(MaterialTheme.padding.medium),
                    )
                }
            }
            items(results.orEmpty(), key = { it.id }) { arc ->
                Text(
                    text = arc.name,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(arc) }
                        .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
                )
            }
        }
    }
}

/** "out. 2024" from Metron's cover date. */
private fun monthText(date: String): String? = runCatching {
    DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault()).format(LocalDate.parse(date))
}.getOrNull()

private fun dayText(date: String?): String = date?.let {
    runCatching { DateTimeFormatter.ofPattern("dd/MM", Locale.getDefault()).format(LocalDate.parse(it)) }.getOrNull()
}.orEmpty()

private const val DESCRIPTION_LINES = 4
private val ArcImageHeight = 150.dp
private val RowCoverHeight = 72.dp
private val PositionWidth = 28.dp
