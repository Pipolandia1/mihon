package eu.kanade.presentation.start

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.readingstats.readingTimeText
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.readingstats.ContinueItem
import eu.kanade.tachiyomi.ui.start.StartConfig
import eu.kanade.tachiyomi.ui.start.StartViewModel
import kotlinx.coroutines.launch
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Search
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.readingstats.comics.ComicLibrary
import tachiyomi.domain.readingstats.comics.EntryProgress
import tachiyomi.domain.start.BacklogBlock
import tachiyomi.domain.start.BacklogShelf
import tachiyomi.domain.start.ByTimeBlock
import tachiyomi.domain.start.ContinueBlock
import tachiyomi.domain.start.FeedbackVerdict
import tachiyomi.domain.start.NearFinishBlock
import tachiyomi.domain.start.ReadNowBlock
import tachiyomi.domain.start.ReadingTime
import tachiyomi.domain.start.ShelfKind
import tachiyomi.domain.start.TimeChoice
import tachiyomi.domain.start.TimeOption
import tachiyomi.domain.start.Timed
import tachiyomi.domain.start.TimedCollection
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

/** The Início tab (docs/HOME_PLAN.md): continue, almost done, by the time you have, backlog. */
@Composable
fun StartScreen(
    state: StartViewModel.State,
    onClickSearch: () -> Unit,
    onOpenChapter: (mangaId: Long, chapterId: Long) -> Unit,
    onOpenManga: (mangaId: Long) -> Unit,
    onOpenLibrary: () -> Unit,
    onFeedback: (mangaId: Long, verdict: FeedbackVerdict) -> Unit,
    onUndoFeedback: (mangaId: Long) -> Unit,
    onOpenSources: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Said out loud, with a way back: a recommendation turned down by mistake is otherwise lost.
    val giveFeedback: (Long, String, FeedbackVerdict) -> Unit = { mangaId, title, verdict ->
        onFeedback(mangaId, verdict)
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val message = if (verdict == FeedbackVerdict.LATER) {
                context.stringResource(MR.strings.start_feedback_later_done, title, StartConfig.LATER_DAYS)
            } else {
                context.stringResource(MR.strings.start_feedback_done, title)
            }
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = context.stringResource(MR.strings.action_undo),
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) onUndoFeedback(mangaId)
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                            AppBar.OverflowAction(
                                title = stringResource(MR.strings.start_sources_title),
                                onClick = onOpenSources,
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
            val feed = state.feed
            // "Continuar lendo" is there from the first frame, before the library is read.
            val blocks = feed?.blocks ?: emptyList()
            if (blocks.none { it is ContinueBlock }) continueSection(state, onOpenChapter, onOpenLibrary)
            blocks.forEach { block ->
                when (block) {
                    is ContinueBlock -> continueSection(state, onOpenChapter, onOpenLibrary)
                    is ReadNowBlock -> {
                        item(key = "read-now-title") { SectionTitle(stringResource(MR.strings.start_read_now)) }
                        item(key = "read-now") {
                            val recommendation = block.recommendation
                            ReadNowCard(
                                recommendation = recommendation,
                                onStart = onOpenChapter,
                                onFeedback = { mangaId, verdict ->
                                    giveFeedback(mangaId, recommendation.progress.entry.title, verdict)
                                },
                            )
                        }
                    }
                    is NearFinishBlock -> nearFinishSection(block, onOpenChapter)
                    is ByTimeBlock -> item(key = "by-time") {
                        ByTimeSection(block, feed?.perIssueMs, onOpenChapter)
                    }
                    is BacklogBlock -> backlogSection(block, onOpenManga)
                }
            }
        }
    }
}

private fun LazyListScope.continueSection(
    state: StartViewModel.State,
    onOpenChapter: (Long, Long) -> Unit,
    onOpenLibrary: () -> Unit,
) {
    item(key = "continue-title") { SectionTitle(stringResource(MR.strings.reading_stats_continue_reading)) }
    val first = state.continueReading.firstOrNull()
    if (first == null) {
        item(key = "continue-empty") { NothingInProgress(onOpenLibrary) }
        return
    }
    item(key = "continue-${first.mangaId}") {
        ContinueHighlight(first, state.feed?.remaining?.get(first.mangaId), onOpenChapter)
    }
    state.continueReading.drop(1).forEach { item ->
        item(key = "continue-${item.mangaId}") { ContinueRow(item, onOpenChapter) }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(
            start = MaterialTheme.padding.medium,
            end = MaterialTheme.padding.medium,
            top = MaterialTheme.padding.medium,
        ),
    )
}

/** The work to continue: large, with where it stops and one button that opens it there. */
@Composable
private fun ContinueHighlight(item: ContinueItem, left: Timed?, onOpenChapter: (Long, Long) -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.medium),
        onClick = { onOpenChapter(item.mangaId, item.chapterId) },
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
                Secondary(detailOf(item))
                left?.let { Secondary(leftText(it)) }
                // Only with both numbers known: a bar with a guessed end would be a made-up figure.
                if (item.resumePage != null && item.totalPages != null && item.totalPages > 0) {
                    LinearProgressIndicator(
                        progress = { item.resumePage.toFloat() / item.totalPages },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Button(onClick = { onOpenChapter(item.mangaId, item.chapterId) }) {
                    Text(stringResource(MR.strings.start_continue))
                }
            }
        }
    }
}

@Composable
private fun ContinueRow(item: ContinueItem, onOpenChapter: (Long, Long) -> Unit) {
    SeriesRow(
        cover = item.cover,
        title = item.title,
        lines = listOf(detailOf(item)),
        onClick = { onOpenChapter(item.mangaId, item.chapterId) },
    )
}

@Composable
private fun NothingInProgress(onOpenLibrary: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        Secondary(stringResource(MR.strings.start_nothing_in_progress))
        OutlinedButton(onClick = onOpenLibrary) {
            Text(stringResource(MR.strings.start_open_library))
        }
    }
}

private fun LazyListScope.nearFinishSection(block: NearFinishBlock, onOpenChapter: (Long, Long) -> Unit) {
    item(key = "near-title") { SectionTitle(stringResource(MR.strings.start_near_finish)) }
    block.items.forEach { timed ->
        val progress = timed.progress
        val next = progress.next ?: return@forEach
        item(key = "near-${progress.entry.mangaId}") {
            SeriesRow(
                cover = progress.entry.cover,
                title = progress.entry.title,
                lines = listOf(leftText(timed), stringResource(MR.strings.reading_stats_next_chapter, next.name)),
                onClick = { onOpenChapter(progress.entry.mangaId, next.id) },
            )
        }
    }
}

/** Chips for the time the reader has; tapping one lists what fits. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ByTimeSection(block: ByTimeBlock, perIssueMs: Long?, onOpenChapter: (Long, Long) -> Unit) {
    var selected by rememberSaveable { mutableStateOf<TimeChoice?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
        SectionTitle(stringResource(MR.strings.start_by_time))
        FlowRow(
            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            block.options.forEach { option ->
                FilterChip(
                    selected = selected == option.choice,
                    onClick = { selected = option.choice.takeIf { it != selected } },
                    label = { Text(stringResource(labelOf(option.choice))) },
                )
            }
        }
        // Without a measured pace the time chips are simply not there; say what brings them.
        if (perIssueMs == null) {
            Secondary(
                text = stringResource(MR.strings.start_time_needs_pace, ReadingTime.MIN_ISSUES),
                modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
            )
        }
        val option = block.options.firstOrNull { it.choice == selected }
        if (option == null) {
            Secondary(
                text = stringResource(MR.strings.start_by_time_prompt),
                modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
            )
        } else {
            TimeOptionList(option, onOpenChapter)
        }
    }
}

@Composable
private fun TimeOptionList(option: TimeOption, onOpenChapter: (Long, Long) -> Unit) {
    option.series.forEach { timed ->
        val progress = timed.progress
        val next = progress.next ?: return@forEach
        val lines = if (option.choice == TimeChoice.FINISH) {
            listOf(leftText(timed))
        } else {
            listOf(issuesText(timed.issues, timed.estimateMs))
        }
        SeriesRow(
            cover = progress.entry.cover,
            title = progress.entry.title,
            lines = lines + stringResource(MR.strings.reading_stats_next_chapter, next.name),
            onClick = { onOpenChapter(progress.entry.mangaId, next.id) },
        )
    }
    option.collections.forEach { CollectionRow(it, onOpenChapter) }
}

@Composable
private fun CollectionRow(timed: TimedCollection, onOpenChapter: (Long, Long) -> Unit) {
    val collection = timed.collection
    val unread = pluralStringResource(MR.plurals.start_unread, collection.unread, collection.unread)
    val text = timed.estimateMs?.let { "$unread · ${paceText(it)}" } ?: unread
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = collection.firstUnread != null) {
                collection.firstUnread?.let { (mangaId, chapterId) -> onOpenChapter(mangaId, chapterId) }
            }
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
    ) {
        Text(
            text = collection.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Secondary(text)
    }
}

private fun LazyListScope.backlogSection(block: BacklogBlock, onOpenManga: (Long) -> Unit) {
    item(key = "backlog-title") { SectionTitle(stringResource(MR.strings.start_backlog)) }
    block.shelves.forEach { shelf ->
        item(key = "shelf-${shelf.kind}") { Shelf(shelf, onOpenManga) }
    }
}

@Composable
private fun Shelf(shelf: BacklogShelf, onOpenManga: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
        Text(
            text = stringResource(shelfTitleOf(shelf.kind)),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = MaterialTheme.padding.medium),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            items(shelf.entries, key = { it.entry.mangaId }) { progress ->
                ShelfItem(progress, shelfDetail(shelf.kind, progress), onOpenManga)
            }
        }
    }
}

@Composable
private fun ShelfItem(progress: EntryProgress, detail: String?, onOpenManga: (Long) -> Unit) {
    Column(
        modifier = Modifier
            .width(ShelfItemWidth)
            .clickable { onOpenManga(progress.entry.mangaId) },
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
    ) {
        MangaCover.Book(data = progress.entry.cover, modifier = Modifier.fillMaxWidth())
        Text(
            text = progress.entry.title,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        detail?.let { Secondary(it) }
    }
}

@Composable
private fun shelfDetail(kind: ShelfKind, progress: EntryProgress): String? =
    when (kind) {
        ShelfKind.SHORT -> progress.total?.let { pluralStringResource(MR.plurals.start_issues, it, it) }
        ShelfKind.NEVER_STARTED -> null
        ShelfKind.STALLED -> progress.entry.lastReadAt?.let { lastRead ->
            val days = ComicLibrary.daysSince(lastRead, System.currentTimeMillis()).toInt()
            pluralStringResource(MR.plurals.reading_stats_stalled_for, days, days)
        }
    }

@Composable
private fun SeriesRow(cover: Any?, title: String, lines: List<String>, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover.Book(
            modifier = Modifier.height(RowCoverHeight),
            data = cover,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = MaterialTheme.padding.medium),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            lines.forEach { Secondary(it) }
        }
    }
}

@Composable
private fun Secondary(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
        modifier = modifier,
    )
}

@Composable
private fun detailOf(item: ContinueItem): String {
    val chapter = formatChapterNumber(item.chapterNumber)
    return when {
        item.newChapter -> stringResource(MR.strings.start_new_chapter, chapter)
        item.resumePage != null && item.totalPages != null ->
            stringResource(MR.strings.reading_stats_continue_page, chapter, item.resumePage, item.totalPages)
        item.resumePage != null ->
            stringResource(MR.strings.reading_stats_continue_page_unknown_total, chapter, item.resumePage)
        else -> stringResource(MR.strings.reading_stats_continue_next, chapter)
    }
}

/** "6 de 8 · faltam 2", and "· ~35m no seu ritmo" only with a measured pace. */
@Composable
private fun leftText(timed: Timed): String {
    val progress = timed.progress
    val left = pluralStringResource(MR.plurals.reading_stats_left, timed.issues, timed.issues)
    val base = stringResource(MR.strings.reading_stats_near_finish_detail, progress.done, progress.total ?: 0, left)
    return timed.estimateMs?.let { "$base · ${paceText(it)}" } ?: base
}

@Composable
private fun issuesText(issues: Int, estimateMs: Long?): String {
    val count = pluralStringResource(MR.plurals.start_issues, issues, issues)
    return estimateMs?.let { "$count · ${paceText(it)}" } ?: count
}

@Composable
private fun paceText(estimateMs: Long): String {
    val minutes = ReadingTime.displayMinutes(estimateMs)
    return stringResource(MR.strings.start_at_your_pace, readingTimeText(LocalContext.current, minutes * MS_PER_MINUTE))
}

private fun labelOf(choice: TimeChoice) = when (choice) {
    TimeChoice.TEN_MINUTES -> MR.strings.start_time_ten
    TimeChoice.THIRTY_MINUTES -> MR.strings.start_time_thirty
    TimeChoice.DIVE -> MR.strings.start_time_dive
    TimeChoice.FINISH -> MR.strings.start_time_finish
}

private fun shelfTitleOf(kind: ShelfKind) = when (kind) {
    ShelfKind.SHORT -> MR.strings.start_shelf_short
    ShelfKind.NEVER_STARTED -> MR.strings.start_shelf_never
    ShelfKind.STALLED -> MR.strings.start_shelf_stalled
}

private const val MS_PER_MINUTE = 60_000L
private val HighlightCoverHeight = 132.dp
private val RowCoverHeight = 64.dp
private val ShelfItemWidth = 96.dp
