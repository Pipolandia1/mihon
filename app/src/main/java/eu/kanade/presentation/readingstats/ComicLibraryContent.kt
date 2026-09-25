package eu.kanade.presentation.readingstats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.tachiyomi.readingstats.comics.LibrarySummary
import tachiyomi.domain.readingstats.comics.ComicLibrary
import tachiyomi.domain.readingstats.comics.EntryProgress
import tachiyomi.domain.readingstats.comics.LibraryStatus
import tachiyomi.domain.readingstats.comics.PeriodChange
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

private val ReminderCoverHeight = 48.dp

/** The library by state, what is left to read, and what came in and was finished lately. */
@Composable
fun LibraryCard(
    summary: LibrarySummary,
    onOpenLibrary: () -> Unit,
    onOpenCollections: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
        val statuses = LibraryStatus.entries
            .mapNotNull { status -> summary.statusCounts[status]?.takeIf { it > 0 }?.let { status to it } }
            .map { (status, count) -> "$count ${stringResource(statusName(status)).lowercase()}" }
            .joinToString(" · ")
        Text(
            text = pluralStringResource(MR.plurals.reading_stats_count_entries, summary.entries, summary.entries),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        if (statuses.isNotEmpty()) Text(statuses, style = MaterialTheme.typography.bodyMedium)
        Text(
            text = stringResource(MR.strings.reading_stats_backlog, unitCountsText(summary.backlog)),
            style = MaterialTheme.typography.bodyMedium,
        )
        ChangeLine(stringResource(MR.strings.reading_stats_period_week), summary.week)
        ChangeLine(stringResource(MR.strings.reading_stats_period_month), summary.month)
        Footnote(stringResource(MR.strings.reading_stats_library_totals_help))
        FlowRow {
            TextButton(onClick = onOpenLibrary) { Text(stringResource(MR.strings.reading_stats_comic_library)) }
            TextButton(onClick = onOpenCollections) { Text(stringResource(MR.strings.reading_stats_collections)) }
        }
    }
}

@Composable
private fun ChangeLine(period: String, change: PeriodChange) {
    val added = buildList {
        if (change.entriesAdded > 0) {
            add(pluralStringResource(MR.plurals.reading_stats_count_entries, change.entriesAdded, change.entriesAdded))
        }
        if (change.chaptersAdded.total > 0) add(unitCountsText(change.chaptersAdded))
    }.joinToString(", ").ifEmpty { stringResource(MR.strings.reading_stats_nothing) }
    Text(
        text = stringResource(MR.strings.reading_stats_change, period, added, unitCountsText(change.chaptersCompleted)),
        style = MaterialTheme.typography.bodySmall,
    )
}

/** Series with a known total and only a few left; a row opens the next one. */
@Composable
fun NearFinishCard(entries: List<EntryProgress>, onOpen: (mangaId: Long, chapterId: Long) -> Unit) {
    Column {
        entries.forEach { progress ->
            ReminderRow(
                progress = progress,
                detail = stringResource(
                    MR.strings.reading_stats_near_finish_detail,
                    progress.done,
                    progress.total ?: 0,
                    (progress.remaining ?: 0).let { pluralStringResource(MR.plurals.reading_stats_left, it, it) },
                ),
                onOpen = onOpen,
            )
        }
    }
}

/** Series in progress and untouched for a while; the reminders can be hidden. */
@Composable
fun StalledCard(
    entries: List<EntryProgress>,
    nowMs: Long,
    onOpen: (mangaId: Long, chapterId: Long) -> Unit,
    onHide: () -> Unit,
) {
    Column {
        entries.forEach { progress ->
            val days = ComicLibrary.daysSince(progress.entry.lastReadAt ?: nowMs, nowMs).toInt()
            ReminderRow(
                progress = progress,
                detail = pluralStringResource(MR.plurals.reading_stats_stalled_for, days, days),
                onOpen = onOpen,
            )
        }
        TextButton(onClick = onHide) { Text(stringResource(MR.strings.reading_stats_hide_stalled)) }
    }
}

@Composable
private fun ReminderRow(
    progress: EntryProgress,
    detail: String,
    onOpen: (mangaId: Long, chapterId: Long) -> Unit,
) {
    val next = progress.next ?: return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(progress.entry.mangaId, next.id) }
            .padding(vertical = MaterialTheme.padding.small),
    ) {
        progress.entry.cover?.let { MangaCover.Book(data = it, modifier = Modifier.height(ReminderCoverHeight)) }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = MaterialTheme.padding.medium),
        ) {
            Text(
                text = progress.entry.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "$detail · ${stringResource(MR.strings.reading_stats_next_chapter, next.name)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Footnote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
    )
}
