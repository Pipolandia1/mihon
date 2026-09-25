package eu.kanade.tachiyomi.ui.readingstats

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.readingstats.fieldName
import eu.kanade.presentation.readingstats.fieldValueText
import eu.kanade.presentation.readingstats.originName
import eu.kanade.presentation.readingstats.unitName
import eu.kanade.presentation.readingstats.yearsText
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.readingstats.comics.online.FallbackReason
import eu.kanade.tachiyomi.readingstats.comics.online.OnlineSearch
import eu.kanade.tachiyomi.readingstats.comics.online.SeriesCandidate
import tachiyomi.domain.readingstats.comics.ComicField
import tachiyomi.domain.readingstats.comics.ComicUnit
import tachiyomi.domain.readingstats.comics.MetadataOrigin
import tachiyomi.domain.readingstats.comics.MetadataValue
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

private val DialogListMaxHeight = 360.dp

private val SERIES_FIELDS = listOf(
    ComicField.UNIT,
    ComicField.SERIES_NAME,
    ComicField.VOLUME,
    ComicField.START_YEAR,
    ComicField.END_YEAR,
    ComicField.TOTAL_ISSUES,
    ComicField.PUBLISHER,
    ComicField.IMPRINT,
    ComicField.UNIVERSE,
    ComicField.GENRE,
    ComicField.WRITER,
    ComicField.ARTIST,
    ComicField.COLORIST,
    ComicField.LETTERER,
    ComicField.COVER_ARTIST,
    ComicField.EDITOR,
    ComicField.CHARACTER,
    ComicField.TEAM,
)

private val CHAPTER_FIELDS = listOf(
    ComicField.UNIT,
    ComicField.ISSUE_LABEL,
    ComicField.VARIANT,
    ComicField.COVER_DATE,
    ComicField.PAGE_COUNT,
    ComicField.ARC,
    ComicField.CONTAINS_ISSUES,
    ComicField.WRITER,
    ComicField.ARTIST,
    ComicField.COLORIST,
    ComicField.LETTERER,
    ComicField.COVER_ARTIST,
    ComicField.EDITOR,
    ComicField.CHARACTER,
    ComicField.TEAM,
    ComicField.UNIVERSE,
)

private val NUMBER_FIELDS = setOf(
    ComicField.VOLUME,
    ComicField.START_YEAR,
    ComicField.END_YEAR,
    ComicField.TOTAL_ISSUES,
    ComicField.PAGE_COUNT,
)

/** One entry's comic metadata: what is confirmed, what sources suggest, and each chapter's own. */
class ComicMetadataScreen(private val mangaId: Long) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = assistedMetroViewModel<ComicMetadataViewModel, ComicMetadataViewModel.Factory> {
            create(mangaId)
        }
        val state by viewModel.state.collectAsStateWithLifecycle()
        val openChapter = state.chapters.firstOrNull { it.id == state.openChapter }

        BackHandler(enabled = openChapter != null) { viewModel.openChapter(null) }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = openChapter?.name ?: stringResource(MR.strings.reading_stats_comic_metadata),
                    subtitle = state.title,
                    navigateUp = { if (openChapter != null) viewModel.openChapter(null) else navigator.pop() },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            val metadata = state.metadata
            if (state.loading || metadata == null) {
                LoadingScreen(Modifier.padding(contentPadding))
                return@Scaffold
            }
            LazyColumn(contentPadding = contentPadding) {
                if (openChapter == null) {
                    item {
                        Actions(
                            state = state,
                            onSearch = viewModel::searchOnline,
                            onReadComicInfo = viewModel::readComicInfo,
                            onAcceptAll = viewModel::acceptAll,
                        )
                    }
                    item { SectionTitle(stringResource(MR.strings.reading_stats_section_series)) }
                    items(SERIES_FIELDS) { field ->
                        FieldRow(
                            field = field,
                            confirmed = metadata.series(field),
                            suggestion = state.best(field, null),
                            inherited = null,
                            onClick = { viewModel.editField(field, null) },
                        )
                    }
                    item { References(metadata.series(ComicField.METRON_ID), metadata.series(ComicField.GCD_ID)) }
                    item { SectionTitle(stringResource(MR.strings.reading_stats_section_chapters)) }
                    items(state.chapters, key = { it.id }) { chapter ->
                        ChapterRow(chapter, state, onClick = { viewModel.openChapter(chapter.id) })
                    }
                } else {
                    items(CHAPTER_FIELDS) { field ->
                        FieldRow(
                            field = field,
                            confirmed = metadata.chapter(openChapter.id, field),
                            suggestion = state.best(field, openChapter.id),
                            inherited = metadata.series(field).takeIf { field == ComicField.UNIT },
                            onClick = { viewModel.editField(field, openChapter.id) },
                        )
                    }
                    item {
                        References(
                            metadata.chapter(openChapter.id, ComicField.METRON_ID),
                            metadata.chapter(openChapter.id, ComicField.GCD_ID),
                        )
                    }
                }
            }
        }

        when (val dialog = state.dialog) {
            is ComicMetadataViewModel.Dialog.EditField -> EditFieldDialog(
                dialog = dialog,
                onConfirm = { values, origin -> viewModel.confirm(dialog.field, dialog.chapterId, values, origin) },
                onDismissRequest = { viewModel.setDialog(null) },
            )
            null -> {}
        }
        state.fetch?.let { fetch ->
            FetchDialog(fetch = fetch, onPick = viewModel::pick, onDismissRequest = viewModel::dismissFetch)
        }
    }
}

@Composable
private fun Actions(
    state: ComicMetadataViewModel.State,
    onSearch: () -> Unit,
    onReadComicInfo: () -> Unit,
    onAcceptAll: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
            OutlinedButton(onClick = onSearch) { Text(stringResource(MR.strings.reading_stats_search_online)) }
            if (state.canReadComicInfo) {
                OutlinedButton(onClick = onReadComicInfo) {
                    Text(stringResource(MR.strings.reading_stats_read_comic_info))
                }
            }
            if (state.pending.isNotEmpty()) {
                OutlinedButton(onClick = onAcceptAll) {
                    Text(
                        pluralStringResource(
                            MR.plurals.reading_stats_accept_suggestions,
                            state.pending.size,
                            state.pending.size,
                        ),
                    )
                }
            }
        }
        Text(
            text = stringResource(MR.strings.reading_stats_metadata_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
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

/**
 * A field: its confirmed value and where that came from; else the best suggestion, set apart;
 * else, for a chapter, the series' value it follows.
 */
@Composable
private fun FieldRow(
    field: ComicField,
    confirmed: MetadataValue?,
    suggestion: MetadataValue?,
    inherited: MetadataValue?,
    onClick: () -> Unit,
) {
    val secondary = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
    ) {
        Text(text = stringResource(fieldName(field)), style = MaterialTheme.typography.labelMedium, color = secondary)
        when {
            confirmed != null -> {
                Text(text = fieldValueText(field, confirmed.values), style = MaterialTheme.typography.bodyMedium)
                if (confirmed.origin != MetadataOrigin.USER) {
                    Text(
                        text = stringResource(
                            MR.strings.reading_stats_confirmed_from,
                            stringResource(originName(confirmed.origin)),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = secondary,
                    )
                }
            }
            suggestion != null -> {
                Text(
                    text = fieldValueText(field, suggestion.values),
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = secondary,
                )
                Text(
                    text = stringResource(
                        MR.strings.reading_stats_suggested_by,
                        stringResource(originName(suggestion.origin)),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            inherited != null -> Text(
                text = stringResource(MR.strings.reading_stats_from_series, fieldValueText(field, inherited.values)),
                style = MaterialTheme.typography.bodyMedium,
                color = secondary,
            )
            else -> Text(
                text = stringResource(MR.strings.reading_stats_not_set),
                style = MaterialTheme.typography.bodyMedium,
                color = secondary,
            )
        }
    }
}

@Composable
private fun References(metron: MetadataValue?, gcd: MetadataValue?) {
    val parts = listOfNotNull(
        metron?.value?.let { "Metron #$it" },
        gcd?.value?.let { "GCD #$it" },
    )
    if (parts.isEmpty()) return
    Text(
        text = parts.joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
        modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
    )
}

/** A chapter with what it is: its format, number and variant, confirmed or suggested. */
@Composable
private fun ChapterRow(
    chapter: ComicMetadataViewModel.ChapterRow,
    state: ComicMetadataViewModel.State,
    onClick: () -> Unit,
) {
    val metadata = state.metadata ?: return
    val unitValue = metadata.chapter(chapter.id, ComicField.UNIT) ?: metadata.series(ComicField.UNIT)
    val unit = unitValue?.let { ComicUnit.of(it.value) }
    val suggestedUnit = (state.best(ComicField.UNIT, chapter.id) ?: state.best(ComicField.UNIT, null))
        ?.takeIf { unitValue == null }
    val label = metadata.chapter(chapter.id, ComicField.ISSUE_LABEL)?.value
    val variant = metadata.chapter(chapter.id, ComicField.VARIANT)?.value
    val details = buildList {
        add(
            when {
                unit != null -> stringResource(unitName(unit))
                suggestedUnit != null -> stringResource(
                    MR.strings.reading_stats_suggested_value,
                    stringResource(unitName(ComicUnit.of(suggestedUnit.value))),
                )
                else -> stringResource(MR.strings.reading_stats_unit_unknown)
            },
        )
        label?.let { add(it) }
        variant?.let { add(stringResource(MR.strings.reading_stats_variant_of, it)) }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
    ) {
        Text(
            text = chapter.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (chapter.read) FontWeight.Normal else FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = details.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
        )
    }
}

@Composable
private fun EditFieldDialog(
    dialog: ComicMetadataViewModel.Dialog.EditField,
    onConfirm: (values: List<String>, origin: MetadataOrigin) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val field = dialog.field
    val initial = (dialog.confirmed ?: dialog.alternatives.firstOrNull())?.values.orEmpty().joinToString(", ")
    var text by remember(dialog) { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(fieldName(field))) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = DialogListMaxHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                if (field == ComicField.UNIT) {
                    val current = ComicUnit.of(dialog.confirmed?.value)
                    (ComicUnit.entries + listOf(null)).forEach { unit ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = dialog.confirmed != null && unit == current,
                                    role = Role.RadioButton,
                                    onClick = { onConfirm(listOfNotNull(unit?.name), MetadataOrigin.USER) },
                                ),
                        ) {
                            RadioButton(selected = dialog.confirmed != null && unit == current, onClick = null)
                            Text(
                                text = stringResource(unitName(unit)),
                                modifier = Modifier.padding(start = MaterialTheme.padding.small),
                            )
                        }
                    }
                    if (dialog.chapterId == null) {
                        Hint(stringResource(MR.strings.reading_stats_unit_series_help))
                    }
                } else {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = !field.list,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (field in NUMBER_FIELDS) KeyboardType.Number else KeyboardType.Text,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (field.list) Hint(stringResource(MR.strings.reading_stats_list_field_help))
                }
                if (dialog.alternatives.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        text = stringResource(MR.strings.reading_stats_suggestions),
                        style = MaterialTheme.typography.labelMedium,
                    )
                    dialog.alternatives.forEach { alternative ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onConfirm(alternative.values, alternative.origin) }
                                .padding(vertical = MaterialTheme.padding.extraSmall),
                        ) {
                            Text(fieldValueText(field, alternative.values), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = stringResource(originName(alternative.origin)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                if (dialog.confirmed != null && field != ComicField.UNIT) {
                    TextButton(onClick = { onConfirm(emptyList(), MetadataOrigin.USER) }) {
                        Text(stringResource(MR.strings.reading_stats_clear_value))
                    }
                }
            }
        },
        confirmButton = {
            if (field != ComicField.UNIT) {
                TextButton(
                    onClick = {
                        val values = if (field.list) text.split(',') else listOf(text)
                        val kept = values.map(String::trim).filter(String::isNotEmpty)
                        // Saving a suggestion unchanged keeps where it came from.
                        val origin = dialog.alternatives.firstOrNull { it.values == kept }?.origin
                            ?: dialog.confirmed?.takeIf { it.values == kept }?.origin
                            ?: MetadataOrigin.USER
                        onConfirm(kept, origin)
                    },
                ) {
                    Text(stringResource(MR.strings.action_save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
    )
}

@Composable
private fun FetchDialog(
    fetch: ComicMetadataViewModel.Fetch,
    onPick: (SeriesCandidate) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val busy = fetch is ComicMetadataViewModel.Fetch.Reading ||
        fetch is ComicMetadataViewModel.Fetch.Searching ||
        fetch is ComicMetadataViewModel.Fetch.Fetching
    AlertDialog(
        onDismissRequest = { if (!busy) onDismissRequest() },
        title = {
            Text(
                stringResource(
                    if (fetch is ComicMetadataViewModel.Fetch.Found) {
                        MR.strings.reading_stats_pick_series
                    } else {
                        MR.strings.reading_stats_comic_metadata
                    },
                ),
            )
        },
        text = {
            when (fetch) {
                ComicMetadataViewModel.Fetch.Reading ->
                    Busy(stringResource(MR.strings.reading_stats_reading_comic_info))
                ComicMetadataViewModel.Fetch.Searching -> Busy(stringResource(MR.strings.reading_stats_searching))
                is ComicMetadataViewModel.Fetch.Found -> SearchResults(fetch.search, onPick)
                is ComicMetadataViewModel.Fetch.Fetching -> Column(
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                ) {
                    Text(fetch.candidate.name, style = MaterialTheme.typography.bodyMedium)
                    if (fetch.total == 0) {
                        Busy(stringResource(MR.strings.reading_stats_fetching_series))
                    } else {
                        Text(
                            stringResource(MR.strings.reading_stats_fetching_issues, fetch.done, fetch.total),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        LinearProgressIndicator(
                            progress = { fetch.done.toFloat() / fetch.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                is ComicMetadataViewModel.Fetch.Fetched -> Text(
                    if (fetch.fetched == fetch.matched) {
                        stringResource(
                            MR.strings.reading_stats_fetched,
                            stringResource(originName(fetch.origin)),
                            fetch.matched,
                        )
                    } else {
                        stringResource(
                            MR.strings.reading_stats_fetched_partly,
                            stringResource(originName(fetch.origin)),
                            fetch.fetched,
                            fetch.matched,
                        )
                    },
                )
                is ComicMetadataViewModel.Fetch.ComicInfoDone -> Text(
                    pluralStringResource(MR.plurals.reading_stats_comic_info_read, fetch.values, fetch.values),
                )
                is ComicMetadataViewModel.Fetch.Failed -> Text(
                    stringResource(
                        when (fetch.failure) {
                            ComicMetadataViewModel.Failure.NO_COMIC_INFO -> MR.strings.reading_stats_no_comic_info
                            ComicMetadataViewModel.Failure.LIMIT_REACHED -> MR.strings.reading_stats_online_limit
                            ComicMetadataViewModel.Failure.REFUSED -> MR.strings.reading_stats_online_refused
                            ComicMetadataViewModel.Failure.NETWORK -> MR.strings.reading_stats_online_network
                            ComicMetadataViewModel.Failure.OTHER -> MR.strings.reading_stats_online_failed
                        },
                    ),
                )
            }
        },
        confirmButton = {
            if (!busy && fetch !is ComicMetadataViewModel.Fetch.Found) {
                TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_ok)) }
            }
        },
        dismissButton = {
            if (busy || fetch is ComicMetadataViewModel.Fetch.Found) {
                TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
            }
        },
    )
}

@Composable
private fun Busy(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
    ) {
        CircularProgressIndicator()
        Text(text)
    }
}

@Composable
private fun SearchResults(search: OnlineSearch, onPick: (SeriesCandidate) -> Unit) {
    val secondary = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA)
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
        val origin = search.candidates.firstOrNull()?.origin ?: if (search.fallback == null) {
            MetadataOrigin.METRON
        } else {
            MetadataOrigin.GCD
        }
        search.fallback?.let { reason ->
            Text(
                text = stringResource(
                    when (reason) {
                        FallbackReason.NO_TOKEN -> MR.strings.reading_stats_fallback_no_token
                        FallbackReason.NOT_FOUND -> MR.strings.reading_stats_fallback_not_found
                        FallbackReason.REFUSED -> MR.strings.reading_stats_fallback_refused
                        FallbackReason.LIMIT_REACHED -> MR.strings.reading_stats_fallback_limit
                        FallbackReason.FAILED -> MR.strings.reading_stats_fallback_failed
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            text = if (search.year != null) {
                stringResource(MR.strings.reading_stats_search_sent_year, search.query, search.year)
            } else {
                stringResource(MR.strings.reading_stats_search_sent, search.query)
            },
            style = MaterialTheme.typography.bodySmall,
            color = secondary,
        )
        if (search.candidates.isEmpty()) {
            Text(stringResource(MR.strings.reading_stats_search_nothing, search.query))
        }
        LazyColumn(modifier = Modifier.heightIn(max = DialogListMaxHeight)) {
            items(search.candidates, key = { "${it.origin}-${it.id}" }) { candidate ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(candidate) }
                        .padding(vertical = MaterialTheme.padding.small),
                ) {
                    Text(candidate.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    val details = listOfNotNull(
                        yearsText(candidate.yearBegan, candidate.yearEnd).takeIf(String::isNotEmpty),
                        candidate.publisher,
                        candidate.format?.takeIf(String::isNotBlank),
                        candidate.issueCount?.let {
                            pluralStringResource(MR.plurals.reading_stats_count_issues, it, it)
                        },
                    )
                    Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = secondary)
                }
            }
        }
        Text(
            text = stringResource(
                if (origin == MetadataOrigin.GCD) {
                    MR.strings.reading_stats_gcd_credit
                } else {
                    MR.strings.reading_stats_metron_credit
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = secondary,
        )
    }
}
