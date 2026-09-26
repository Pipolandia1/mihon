package eu.kanade.presentation.start

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.readingstats.originName
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.MoreVert
import tachiyomi.domain.readingstats.comics.EntryProgress
import tachiyomi.domain.start.EntityKind
import tachiyomi.domain.start.EntityReason
import tachiyomi.domain.start.FeedbackVerdict
import tachiyomi.domain.start.FormatReason
import tachiyomi.domain.start.Reason
import tachiyomi.domain.start.Recommendation
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

/** How many reasons the card itself shows; "Por quê?" shows them all. */
private const val REASONS_ON_CARD = 2

/** How many of the works behind a reason are named before "and N more". */
private const val TITLES_NAMED = 3

/**
 * "Leia isso agora" (docs/HOME_PLAN.md, H3): one work, what it is, why, and one button to start it.
 * A long press, or the menu, says what the reader thinks of it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReadNowCard(
    recommendation: Recommendation,
    onStart: (mangaId: Long, chapterId: Long) -> Unit,
    onFeedback: (mangaId: Long, verdict: FeedbackVerdict) -> Unit,
) {
    val progress = recommendation.progress
    val next = progress.next ?: return
    var showWhy by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.medium)
            .combinedClickable(
                onClick = { onStart(progress.entry.mangaId, next.id) },
                onLongClick = { showMenu = true },
            ),
    ) {
        Row(modifier = Modifier.padding(MaterialTheme.padding.medium)) {
            MangaCover.Book(
                modifier = Modifier.height(ReadNowCoverHeight),
                data = progress.entry.cover,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = MaterialTheme.padding.medium),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = progress.entry.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                MaterialSymbols.Rounded.MoreVert,
                                contentDescription = stringResource(MR.strings.action_menu),
                            )
                        }
                        FeedbackMenu(
                            expanded = showMenu,
                            onDismiss = { showMenu = false },
                            onChoose = { verdict ->
                                showMenu = false
                                onFeedback(progress.entry.mangaId, verdict)
                            },
                        )
                    }
                }
                formatText(progress)?.let { SecondaryText(it) }
                recommendation.reasons.take(REASONS_ON_CARD).forEach {
                    Text(text = reasonText(it), style = MaterialTheme.typography.bodySmall)
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = { onStart(progress.entry.mangaId, next.id) }) {
                        Text(stringResource(MR.strings.start_read_now_start))
                    }
                    TextButton(onClick = { showWhy = true }) { Text(stringResource(MR.strings.start_why)) }
                }
            }
        }
    }
    if (showWhy) WhyDialog(recommendation, onDismiss = { showWhy = false })
}

@Composable
internal fun FeedbackMenu(expanded: Boolean, onDismiss: () -> Unit, onChoose: (FeedbackVerdict) -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        FeedbackVerdict.entries.forEach { verdict ->
            DropdownMenuItem(
                text = { Text(stringResource(verdictLabel(verdict))) },
                onClick = { onChoose(verdict) },
            )
        }
    }
}

/** Every reason, with the works behind it and how sure this work's own value is. */
@Composable
private fun WhyDialog(recommendation: Recommendation, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.start_why_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
            ) {
                recommendation.reasons.forEach { reason ->
                    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall)) {
                        Text(text = reasonText(reason), style = MaterialTheme.typography.bodyMedium)
                        if (reason is EntityReason) {
                            SecondaryText(fromText(reason.fromTitles))
                            SecondaryText(
                                stringResource(
                                    MR.strings.start_why_in_this_comic,
                                    stringResource(originName(reason.candidateOrigin)),
                                ),
                            )
                        }
                    }
                }
                SecondaryText(stringResource(MR.strings.start_why_footer))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_ok)) } },
    )
}

@Composable
fun reasonText(reason: Reason): String = when (reason) {
    is FormatReason -> stringResource(MR.strings.start_reason_format, reason.finished, reason.started, reason.maxIssues)
    is EntityReason -> when (reason.kind) {
        EntityKind.WRITER -> if (reason.worksCompleted > 0) {
            pluralStringResource(
                MR.plurals.start_reason_writer_finished,
                reason.worksCompleted,
                reason.worksCompleted,
                reason.name,
            )
        } else {
            pluralStringResource(MR.plurals.start_reason_writer_read, reason.worksRead, reason.worksRead, reason.name)
        }
        EntityKind.ARTIST ->
            pluralStringResource(MR.plurals.start_reason_artist, reason.worksRead, reason.worksRead, reason.name)
        EntityKind.CHARACTER, EntityKind.TEAM ->
            pluralStringResource(MR.plurals.start_reason_character, reason.issuesRead, reason.issuesRead, reason.name)
        EntityKind.PUBLISHER, EntityKind.IMPRINT, EntityKind.UNIVERSE, EntityKind.GENRE ->
            pluralStringResource(MR.plurals.start_reason_of, reason.worksRead, reason.worksRead, reason.name)
    }
}

@Composable
private fun fromText(titles: List<String>): String {
    val named = titles.take(TITLES_NAMED).joinToString(", ")
    val more = titles.size - TITLES_NAMED
    val list = if (more > 0) stringResource(MR.strings.start_why_and_more, named, more) else named
    return stringResource(MR.strings.start_why_from, list)
}

/** "8 edições · história fechada" only with the total and the ending known. */
@Composable
private fun formatText(progress: EntryProgress): String? {
    val total = progress.total
    return when {
        total != null && progress.entry.publicationFinished ->
            pluralStringResource(MR.plurals.start_format_closed, total, total)
        total != null -> pluralStringResource(MR.plurals.start_format_issues, total, total)
        progress.available > 0 -> pluralStringResource(
            MR.plurals.start_format_available,
            progress.available,
            progress.available,
        )
        else -> null
    }
}

fun verdictLabel(verdict: FeedbackVerdict) = when (verdict) {
    FeedbackVerdict.NOT_INTERESTED -> MR.strings.start_feedback_not_interested
    FeedbackVerdict.ALREADY_READ -> MR.strings.start_feedback_already_read
    FeedbackVerdict.LATER -> MR.strings.start_feedback_later
}

@Composable
private fun SecondaryText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
    )
}

private val ReadNowCoverHeight = 150.dp
