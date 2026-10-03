package eu.kanade.presentation.start

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
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
import tachiyomi.domain.start.Arc
import tachiyomi.domain.start.ArcPick
import tachiyomi.domain.start.ArcReason
import tachiyomi.domain.start.ArcsBlock
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

/** "Arcos e eventos" on the Início (docs/HOME_PLAN.md §13, H9): a row of arcs, and the arc search. */
fun LazyListScope.arcsSection(
    block: ArcsBlock,
    onOpenArc: (Arc) -> Unit,
    onOpenArcs: () -> Unit,
    onTurnDown: (Arc) -> Unit,
    followed: (arcKey: String) -> Boolean,
    onBell: (Arc) -> Unit,
) {
    item(key = "arcs-title") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) { SectionTitle(stringResource(MR.strings.arcs_title)) }
            TextButton(
                onClick = onOpenArcs,
                modifier = Modifier.padding(top = MaterialTheme.padding.small, end = MaterialTheme.padding.small),
            ) {
                Text(stringResource(MR.strings.arcs_search))
            }
        }
    }
    item(key = "arcs-shelf") {
        LazyRow(
            contentPadding = PaddingValues(horizontal = MaterialTheme.padding.medium),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            items(block.picks, key = { it.arc.key }) { pick ->
                ArcCard(pick, followed(pick.arc.key), onOpenArc, onTurnDown, onBell)
            }
        }
    }
}

/** An arc: tapped, its page opens; held, a way to turn it down; its bell follows it (H10). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArcCard(
    pick: ArcPick,
    followed: Boolean,
    onOpenArc: (Arc) -> Unit,
    onTurnDown: (Arc) -> Unit,
    onBell: (Arc) -> Unit,
) {
    val progress = pick.progress
    var menu by remember { mutableStateOf(false) }
    Box {
        ElevatedCard(
            modifier = Modifier
                .width(ArcCardWidth)
                .combinedClickable(onClick = { onOpenArc(pick.arc) }, onLongClick = { menu = true }),
        ) {
            Row(modifier = Modifier.padding(MaterialTheme.padding.small)) {
                Box {
                    MangaCover.Book(data = pick.arc.imageUrl, modifier = Modifier.height(ArcCoverHeight))
                    FollowBell(
                        followed = followed,
                        onClick = { onBell(pick.arc) },
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = MaterialTheme.padding.small),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                ) {
                    Text(
                        text = pick.arc.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (progress.read > 0) {
                        Secondary(
                            stringResource(MR.strings.start_arc_left, progress.read, progress.total, progress.left),
                        )
                        LinearProgressIndicator(
                            progress = { progress.read.toFloat() / progress.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Secondary(pluralStringResource(MR.plurals.start_issues, progress.total, progress.total))
                    }
                    pick.lead.release?.let { Secondary(stringResource(MR.strings.start_arc_out, releaseTitle(it))) }
                    reasonText(pick.lead.reason)?.let { Secondary(it) }
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(MR.strings.start_arc_not_interested)) },
                onClick = {
                    menu = false
                    onTurnDown(pick.arc)
                },
            )
        }
    }
}

/** Why the arc is there; an arc the reader is in says so with its progress, so it needs none. */
@Composable
private fun reasonText(reason: ArcReason): String? = when (reason) {
    ArcReason.Started -> null
    is ArcReason.InYourReading -> stringResource(MR.strings.start_release_reason_yours, reason.title)
    is ArcReason.OutThisWeek -> releaseReasonText(reason.reason)
}

private val ArcCardWidth = 280.dp
private val ArcCoverHeight = 112.dp
