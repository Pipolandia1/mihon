package eu.kanade.presentation.start

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.readingstats.ContinueItem
import eu.kanade.tachiyomi.ui.start.StartViewModel
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Search
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

/** The Início tab: for now, continuing where the reader left off (docs/HOME_PLAN.md, H1). */
@Composable
fun StartScreen(
    state: StartViewModel.State,
    onClickSearch: () -> Unit,
    onContinue: (mangaId: Long, chapterId: Long) -> Unit,
    onOpenLibrary: () -> Unit,
) {
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(MR.strings.label_start),
                actions = {
                    AppBarActions(
                        listOf(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_global_search),
                                icon = MaterialSymbols.Rounded.Search,
                                onClick = onClickSearch,
                            ),
                        ),
                    )
                },
                scrollBehavior = scrollBehavior,
            )
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
            item { SectionTitle(stringResource(MR.strings.reading_stats_continue_reading)) }
            val first = state.continueReading.firstOrNull()
            if (first == null) {
                item { NothingInProgress(onOpenLibrary) }
            } else {
                item { ContinueHighlight(first, onContinue) }
                state.continueReading.drop(1).forEach { item ->
                    item(key = item.mangaId) { ContinueRow(item, onContinue) }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(
            horizontal = MaterialTheme.padding.medium,
            vertical = MaterialTheme.padding.small,
        ),
    )
}

/** The work to continue: large, with where it stops and one button that opens it there. */
@Composable
private fun ContinueHighlight(item: ContinueItem, onContinue: (Long, Long) -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.medium),
        onClick = { onContinue(item.mangaId, item.chapterId) },
    ) {
        Row(
            modifier = Modifier.padding(MaterialTheme.padding.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MangaCover.Book(
                modifier = Modifier.height(HighlightCoverHeight),
                data = item.cover,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = MaterialTheme.padding.medium),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = detailOf(item),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
                )
                // Only with both numbers known: a bar with a guessed end would be a made-up figure.
                if (item.resumePage != null && item.totalPages != null && item.totalPages > 0) {
                    LinearProgressIndicator(
                        progress = { item.resumePage.toFloat() / item.totalPages },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Button(onClick = { onContinue(item.mangaId, item.chapterId) }) {
                    Text(stringResource(MR.strings.start_continue))
                }
            }
        }
    }
}

@Composable
private fun ContinueRow(item: ContinueItem, onContinue: (Long, Long) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onContinue(item.mangaId, item.chapterId) }
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover.Book(
            modifier = Modifier.height(RowCoverHeight),
            data = item.cover,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = MaterialTheme.padding.medium),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detailOf(item),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
            )
        }
    }
}

@Composable
private fun NothingInProgress(onOpenLibrary: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        Text(
            text = stringResource(MR.strings.start_nothing_in_progress),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
        )
        OutlinedButton(onClick = onOpenLibrary) {
            Text(stringResource(MR.strings.start_open_library))
        }
    }
}

@Composable
private fun detailOf(item: ContinueItem): String {
    val chapter = formatChapterNumber(item.chapterNumber)
    return when {
        item.resumePage != null && item.totalPages != null ->
            stringResource(MR.strings.reading_stats_continue_page, chapter, item.resumePage, item.totalPages)
        item.resumePage != null ->
            stringResource(MR.strings.reading_stats_continue_page_unknown_total, chapter, item.resumePage)
        else -> stringResource(MR.strings.reading_stats_continue_next, chapter)
    }
}

private val HighlightCoverHeight = 132.dp
private val RowCoverHeight = 64.dp
