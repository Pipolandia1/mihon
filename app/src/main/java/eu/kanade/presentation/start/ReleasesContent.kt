package eu.kanade.presentation.start

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.tachiyomi.ui.start.releases.ReleasesModel
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.KeyboardArrowLeft
import mihon.icons.materialsymbols.rounded.KeyboardArrowRight
import tachiyomi.domain.start.EntityKind
import tachiyomi.domain.start.ReleaseItem
import tachiyomi.domain.start.ReleaseOrigin
import tachiyomi.domain.start.ReleaseReason
import tachiyomi.domain.start.ReleaseWeek
import tachiyomi.domain.start.ReleasesBlock
import tachiyomi.domain.start.RelevantRelease
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** "Lançamentos da semana" (docs/HOME_PLAN.md §9.1, H6): what matters this week, and the calendar. */
fun LazyListScope.releasesSection(
    block: ReleasesBlock,
    onOpenRelease: (RelevantRelease) -> Unit,
    onOpenCalendar: () -> Unit,
) {
    item(key = "releases-title") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) { SectionTitle(stringResource(MR.strings.start_releases)) }
            TextButton(
                onClick = onOpenCalendar,
                modifier = Modifier.padding(top = MaterialTheme.padding.small, end = MaterialTheme.padding.small),
            ) {
                Text(stringResource(MR.strings.start_releases_all))
            }
        }
    }
    item(key = "releases-shelf") {
        LazyRow(
            contentPadding = PaddingValues(horizontal = MaterialTheme.padding.medium),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        ) {
            items(block.picks, key = { it.item.key }) { pick ->
                Column(
                    modifier = Modifier
                        .width(ShelfItemWidth)
                        .clickable { onOpenRelease(pick) },
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                ) {
                    MangaCover.Book(data = pick.item.coverUrl, modifier = Modifier.fillMaxWidth())
                    Text(
                        text = releaseTitle(pick.item),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Secondary(dayText(pick.item))
                    Secondary(reasonText(pick.reason))
                }
            }
        }
    }
}

@Composable
fun ReleasesCalendar(
    state: ReleasesModel.State,
    snackbarHostState: SnackbarHostState,
    navigateUp: () -> Unit,
    onWeek: (Long) -> Unit,
    onOnlyRelevant: (Boolean) -> Unit,
    onOpen: (ReleaseItem, ReleaseReason?) -> Unit,
) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { scrollBehavior ->
            AppBar(
                title = stringResource(MR.strings.releases_title),
                subtitle = weekRange(state.week),
                navigateUp = navigateUp,
                actions = {
                    IconButton(onClick = { onWeek(-1) }) {
                        Icon(
                            MaterialSymbols.Rounded.KeyboardArrowLeft,
                            stringResource(MR.strings.releases_previous_week),
                        )
                    }
                    IconButton(onClick = { onWeek(1) }) {
                        Icon(MaterialSymbols.Rounded.KeyboardArrowRight, stringResource(MR.strings.releases_next_week))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { contentPadding ->
        val list = state.list
        if (list == null && state.loading) {
            LoadingScreen(Modifier.padding(contentPadding))
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
        ) {
            item(key = "filters") {
                Row(
                    modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                ) {
                    FilterChip(
                        selected = state.onlyRelevant,
                        onClick = { onOnlyRelevant(!state.onlyRelevant) },
                        label = { Text(stringResource(MR.strings.releases_only_relevant)) },
                    )
                    if (!state.isThisWeek) {
                        FilterChip(
                            selected = false,
                            onClick = { onWeek(0) },
                            label = { Text(stringResource(MR.strings.releases_this_week)) },
                        )
                    }
                }
            }
            when {
                list == null -> item(key = "failed") { Notice(stringResource(MR.strings.releases_failed_none)) }
                state.failed -> item(key = "stale") {
                    Notice(stringResource(MR.strings.releases_failed_kept, fetchedText(list.fetchedAt)))
                }
            }
            if (list == null) return@LazyColumn
            item(key = "for-you") { SectionTitle(stringResource(MR.strings.releases_for_you, state.relevant.size)) }
            if (state.relevant.isEmpty()) {
                item(key = "for-you-empty") { Notice(stringResource(MR.strings.releases_none_for_you)) }
            }
            items(state.relevant, key = { "relevant-${it.item.key}" }) { pick ->
                ReleaseRow(pick.item, reasonText(pick.reason)) { onOpen(pick.item, pick.reason) }
            }
            if (!state.onlyRelevant) {
                if (list.items.isEmpty()) item(key = "empty") { Notice(stringResource(MR.strings.releases_empty)) }
                state.others.forEach { (day, items) -> dayGroup(day, items, onOpen) }
            }
            item(key = "credit") { Credit(list.origin) }
        }
    }
}

/** A day of the rest of the week, folded: its count, and its issues when opened. */
private fun LazyListScope.dayGroup(
    day: LocalDate?,
    items: List<ReleaseItem>,
    onOpen: (ReleaseItem, ReleaseReason?) -> Unit,
) {
    item(key = "day-$day") {
        var open by rememberSaveable { mutableStateOf(false) }
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { open = !open }
                    .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = day?.let { dayTitle(it) } ?: stringResource(MR.strings.releases_this_week),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Secondary(pluralStringResource(MR.plurals.releases_count, items.size, items.size))
                }
                Text(
                    text = stringResource(if (open) MR.strings.releases_hide else MR.strings.releases_show),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (open) items.forEach { item -> ReleaseRow(item, null) { onOpen(item, null) } }
        }
    }
}

@Composable
private fun ReleaseRow(item: ReleaseItem, reason: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover.Book(data = item.coverUrl, modifier = Modifier.height(RowCoverHeight))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = MaterialTheme.padding.medium),
        ) {
            Text(
                text = releaseTitle(item),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Secondary(dayText(item))
            reason?.let { Secondary(it) }
        }
    }
}

@Composable
private fun Notice(text: String) {
    Secondary(
        text,
        modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
    )
}

@Composable
private fun Credit(origin: ReleaseOrigin) {
    val text = when (origin) {
        ReleaseOrigin.METRON -> stringResource(MR.strings.releases_credit_metron)
        ReleaseOrigin.GCD -> stringResource(MR.strings.releases_credit_gcd)
    }
    Secondary(text, modifier = Modifier.padding(MaterialTheme.padding.medium))
}

/** "Absolute Batman #24". */
private fun releaseTitle(item: ReleaseItem) = "${item.seriesName} #${item.number}"

/** "qua., 23/09", or "esta semana" when the list says only the week. */
@Composable
private fun dayText(item: ReleaseItem): String {
    val date = item.storeDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: return stringResource(MR.strings.start_release_this_week)
    return DateTimeFormatter.ofPattern("EEE, dd/MM", Locale.getDefault()).format(date)
}

private fun dayTitle(date: LocalDate) = DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.getDefault()).format(date)

private fun weekRange(week: ReleaseWeek): String {
    val format = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    return "${format.format(week.monday)} – ${format.format(week.sunday)}"
}

private fun fetchedText(epochMs: Long): String = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault())
    .format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

@Composable
private fun reasonText(reason: ReleaseReason): String = when (reason) {
    is ReleaseReason.YourSeries -> stringResource(MR.strings.start_release_reason_yours, reason.title)
    is ReleaseReason.ByName -> when (reason.kind) {
        EntityKind.WRITER -> stringResource(MR.strings.start_release_reason_writer, reason.name)
        EntityKind.ARTIST -> stringResource(MR.strings.start_release_reason_artist, reason.name)
        else -> stringResource(MR.strings.start_release_reason_character, reason.name)
    }
}

private val RowCoverHeight = 72.dp
