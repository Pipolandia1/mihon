package eu.kanade.presentation.readingstats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.formatChapterNumber
import kotlinx.datetime.LocalDate
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Delete
import tachiyomi.domain.readingstats.model.ReadingDayEntry
import tachiyomi.domain.readingstats.model.ReadingOrigin
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

private val EntryCoverHeight = 72.dp

/** One day of reading: what was read, for how long, and what was finished. */
@Composable
fun ReadingDayContent(
    date: LocalDate,
    entries: List<ReadingDayEntry>?,
    /** Days before this one were not measured: they show no time or page figures. */
    recordingStartedOn: LocalDate?,
    contentPadding: PaddingValues,
    onClearDay: () -> Unit,
    onClickEntry: (mangaId: Long) -> Unit,
    onDeleteSession: (sessionId: Long) -> Unit,
) {
    val context = LocalContext.current
    LazyColumn(contentPadding = contentPadding) {
        item(key = "header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = MaterialTheme.padding.medium, end = MaterialTheme.padding.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = readingDateLongText(date),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClearDay) {
                    Text(stringResource(MR.strings.reading_stats_all_dates))
                }
            }
        }
        when {
            entries == null -> item(key = "loading") { LoadingScreen() }
            entries.isEmpty() -> item(key = "empty") {
                Text(
                    text = stringResource(MR.strings.reading_stats_day_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(MaterialTheme.padding.medium),
                )
            }
            else -> {
                item(key = "summary") {
                    val sessions = entries.filterIsInstance<ReadingDayEntry.Session>()
                    val recorded = recordingStartedOn == null || date >= recordingStartedOn
                    Text(
                        text = if (recorded) {
                            stringResource(
                                MR.strings.reading_stats_day_summary,
                                readingTimeText(context, sessions.sumOf { it.dayActiveMs }),
                                sessions.sumOf { it.dayPagesNew },
                                sessions.count { it.firstCompletion },
                            )
                        } else {
                            stringResource(MR.strings.reading_stats_day_not_recorded)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
                        modifier = Modifier.padding(
                            horizontal = MaterialTheme.padding.medium,
                            vertical = MaterialTheme.padding.extraSmall,
                        ),
                    )
                }
                items(items = entries, key = ::entryKey) { entry ->
                    EntryRow(entry, onClickEntry, onDeleteSession)
                }
            }
        }
    }
}

@Composable
private fun EntryRow(
    entry: ReadingDayEntry,
    onClickEntry: (mangaId: Long) -> Unit,
    onDeleteSession: (sessionId: Long) -> Unit,
) {
    val context = LocalContext.current
    val mangaId = entry.mangaId
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = mangaId != null) { mangaId?.let(onClickEntry) }
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover.Book(
            modifier = Modifier.height(EntryCoverHeight),
            data = entry.cover,
        )
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
                text = chapterText(entry),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val detail = when (entry) {
                is ReadingDayEntry.Session -> buildList {
                    add(
                        stringResource(
                            MR.strings.reading_stats_session_line,
                            readingClockText(entry.startedAt),
                            readingClockText(entry.endedAt),
                            readingTimeText(context, entry.dayActiveMs),
                            entry.dayPagesNew,
                        ),
                    )
                    if (entry.finishedChapter) add(stringResource(MR.strings.reading_stats_finished))
                    if (entry.recovered) add(stringResource(MR.strings.reading_stats_interrupted))
                }.joinToString(" · ")
                is ReadingDayEntry.Completion -> if (entry.origin == ReadingOrigin.IMPORTED) {
                    stringResource(MR.strings.reading_stats_imported_entry)
                } else {
                    stringResource(MR.strings.reading_stats_marked_read_entry)
                }
                is ReadingDayEntry.Legacy -> stringResource(MR.strings.reading_stats_legacy_entry)
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
            )
        }
        if (entry is ReadingDayEntry.Session) {
            IconButton(onClick = { onDeleteSession(entry.sessionId) }) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.Delete,
                    contentDescription = stringResource(MR.strings.reading_stats_delete_session),
                )
            }
        }
    }
}

private fun chapterText(entry: ReadingDayEntry): String {
    val number = entry.chapterNumber.takeIf { it >= 0 }?.let(::formatChapterNumber)
    return listOfNotNull(number?.let { "#$it" }, entry.chapterName.takeIf { it.isNotBlank() })
        .distinct()
        .joinToString(" · ")
}

private fun entryKey(entry: ReadingDayEntry): String = when (entry) {
    is ReadingDayEntry.Session -> "session-${entry.sessionId}"
    is ReadingDayEntry.Completion -> "completion-${entry.chapterId}"
    is ReadingDayEntry.Legacy -> "legacy-${entry.chapterId}"
}
