package eu.kanade.presentation.start

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.tachiyomi.ui.start.catalog.CatalogDiscovery
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.MoreVert
import tachiyomi.domain.start.BecauseYouLikedBlock
import tachiyomi.domain.start.CreatorsBlock
import tachiyomi.domain.start.EntityKind
import tachiyomi.domain.start.FeedbackVerdict
import tachiyomi.domain.start.FoundWork
import tachiyomi.domain.start.LibraryWork
import tachiyomi.domain.start.Link
import tachiyomi.domain.start.ShelfPick
import tachiyomi.domain.start.StartFeed
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

/** What the shelves of H5 do with a work: open it, or say what the reader thinks of it. */
class ShelfActions(
    val onOpenManga: (Long) -> Unit,
    val onFeedback: (mangaId: Long, title: String, verdict: FeedbackVerdict) -> Unit,
)

/** "Porque você gostou de X" (docs/HOME_PLAN.md §9.1, H5). */
fun LazyListScope.becauseYouLikedSection(block: BecauseYouLikedBlock, actions: ShelfActions) {
    item(key = "liked-title") {
        SectionTitle(stringResource(MR.strings.start_because_you_liked, block.anchorTitle))
    }
    item(key = "liked-shelf") {
        PickShelf(block.picks, actions) { pick ->
            listOfNotNull(linkText(pick.link), sourceText(pick))
        }
    }
}

/** "Autores e personagens": the entity read most, why, a way to turn it down, and more of it. */
fun LazyListScope.creatorsSection(
    block: CreatorsBlock,
    actions: ShelfActions,
    onEntityFeedback: (EntityKind, String) -> Unit,
) {
    item(key = "creators-title") { SectionTitle(stringResource(MR.strings.start_creators)) }
    item(key = "creators-reason") {
        var menu by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier.padding(start = MaterialTheme.padding.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = creatorsHeadline(block),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Secondary(reasonText(block.reason))
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(MaterialSymbols.Rounded.MoreVert, contentDescription = stringResource(MR.strings.action_menu))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(MR.strings.start_entity_not_interested, block.reason.name)) },
                        onClick = {
                            menu = false
                            onEntityFeedback(block.key.kind, block.reason.name)
                        },
                    )
                }
            }
        }
    }
    item(key = "creators-shelf") {
        PickShelf(block.picks, actions) { pick -> listOfNotNull(formatText(pick), sourceText(pick)) }
    }
}

/**
 * The discreet lines at the end of the Início: what would bring recommendations from outside the
 * library (a Metron token, a primary source), and the metadata the library lacks (§5.4).
 */
fun LazyListScope.discoveryNotices(
    catalog: CatalogDiscovery.State,
    missingWriters: Int?,
    onOpenSources: () -> Unit,
    onOpenMetronSettings: () -> Unit,
    onOpenComicLibrary: () -> Unit,
) {
    when (catalog) {
        CatalogDiscovery.State.NoToken -> item(key = "notice-token") {
            Notice(
                text = stringResource(MR.strings.start_catalog_no_token),
                action = stringResource(MR.strings.start_catalog_add_token),
                onAction = onOpenMetronSettings,
            )
        }
        is CatalogDiscovery.State.NeedsPrimary -> item(key = "notice-primary") {
            Notice(
                text = pluralStringResource(MR.plurals.start_catalog_needs_primary, catalog.series, catalog.series),
                action = stringResource(MR.strings.start_catalog_choose_source),
                onAction = onOpenSources,
            )
        }
        else -> Unit
    }
    if (missingWriters != null) {
        item(key = "notice-coverage") {
            Notice(
                text = pluralStringResource(MR.plurals.start_coverage, missingWriters, missingWriters),
                action = stringResource(MR.strings.start_coverage_open),
                onAction = onOpenComicLibrary,
            )
        }
    }
}

@Composable
private fun Notice(text: String, action: String, onAction: () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium)) {
        Secondary(text)
        TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun PickShelf(picks: List<ShelfPick>, actions: ShelfActions, lines: @Composable (ShelfPick) -> List<String>) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = MaterialTheme.padding.medium),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        items(picks, key = { it.work.mangaId }) { pick -> PickItem(pick, lines(pick), actions) }
    }
}

/** A work of a shelf: tapped, its page opens; held, what the reader thinks of it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PickItem(pick: ShelfPick, lines: List<String>, actions: ShelfActions) {
    val work = pick.work
    val (title, cover) = when (work) {
        is LibraryWork -> work.progress.entry.title to work.progress.entry.cover
        is FoundWork -> work.work.title to work.work.cover
    }
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            modifier = Modifier
                .width(ShelfItemWidth)
                .combinedClickable(onClick = { actions.onOpenManga(work.mangaId) }, onLongClick = { menu = true }),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
        ) {
            MangaCover.Book(data = cover, modifier = Modifier.fillMaxWidth())
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            lines.forEach { Secondary(it) }
        }
        FeedbackMenu(
            expanded = menu,
            onDismiss = { menu = false },
            onChoose = { verdict ->
                menu = false
                actions.onFeedback(work.mangaId, title, verdict)
            },
        )
    }
}

/** "Você lê muito X" only with [StartFeed.CREATOR_MIN_WORKS] works read; with one, "Mais de X". */
@Composable
private fun creatorsHeadline(block: CreatorsBlock): String {
    val name = block.reason.name
    return when {
        block.reason.worksRead >= StartFeed.CREATOR_MIN_WORKS -> stringResource(
            MR.strings.start_creators_reads_a_lot,
            name,
        )
        block.key.kind == EntityKind.CHARACTER || block.key.kind == EntityKind.TEAM ->
            stringResource(MR.strings.start_creators_more_with, name)
        else -> stringResource(MR.strings.start_creators_more_by, name)
    }
}

@Composable
private fun linkText(link: Link): String = when (link.kind) {
    EntityKind.WRITER -> stringResource(MR.strings.start_link_writer, link.name)
    EntityKind.ARTIST -> stringResource(MR.strings.start_link_artist, link.name)
    else -> stringResource(MR.strings.start_link_character, link.name)
}

/** "em Read Comics Online", for a work found outside the library. */
@Composable
private fun sourceText(pick: ShelfPick): String? =
    (pick.work as? FoundWork)?.let { stringResource(MR.strings.start_in_source, it.work.sourceName) }

/** "12 edições · história fechada" for a found work, only as far as the catalogue knows it. */
@Composable
private fun formatText(pick: ShelfPick): String? {
    val work = (pick.work as? FoundWork)?.work ?: return null
    val total = work.total ?: return null
    return if (work.finished) {
        pluralStringResource(MR.plurals.start_format_closed, total, total)
    } else {
        pluralStringResource(MR.plurals.start_format_issues, total, total)
    }
}
