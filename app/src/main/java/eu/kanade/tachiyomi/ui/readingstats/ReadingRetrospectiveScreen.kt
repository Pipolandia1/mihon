package eu.kanade.tachiyomi.ui.readingstats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.readingstats.ShareRow
import eu.kanade.presentation.readingstats.monthText
import eu.kanade.presentation.readingstats.periodText
import eu.kanade.presentation.readingstats.readingShortDateText
import eu.kanade.presentation.readingstats.readingTimeText
import eu.kanade.presentation.readingstats.unitCountsText
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.readingstats.RetrospectiveShare
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.KeyboardArrowLeft
import mihon.icons.materialsymbols.rounded.KeyboardArrowRight
import mihon.icons.materialsymbols.rounded.Share
import tachiyomi.domain.readingstats.analysis.Ranking
import tachiyomi.domain.readingstats.analysis.Rankings
import tachiyomi.domain.readingstats.analysis.Retrospective
import tachiyomi.domain.readingstats.analysis.RetrospectiveKind
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.SectionCard
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import java.util.Locale

/** The monthly summary or the yearly retrospective. */
class ReadingRetrospectiveScreen(private val kind: RetrospectiveKind) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val viewModel = assistedMetroViewModel<ReadingRetrospectiveViewModel, ReadingRetrospectiveViewModel.Factory> {
            create(kind)
        }
        val state by viewModel.state.collectAsStateWithLifecycle()
        var sharing by remember { mutableStateOf(false) }
        val heading = stringResource(
            if (kind == RetrospectiveKind.MONTH) {
                MR.strings.reading_stats_month_summary
            } else {
                MR.strings.reading_stats_year_review
            },
        )

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = heading,
                    navigateUp = navigator::pop,
                    actions = {
                        if (state?.retrospective?.isEmpty == false) {
                            IconButton(onClick = { sharing = true }) {
                                Icon(MaterialSymbols.Rounded.Share, stringResource(MR.strings.action_share))
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            val current = state
            if (current == null) {
                LoadingScreen(Modifier.padding(contentPadding))
                return@Scaffold
            }
            val retro = current.retrospective
            val title = titleOf(retro)
            val lines = retrospectiveLines(retro, current.today)
            LazyColumn(
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MaterialTheme.padding.small),
                    ) {
                        IconButton(onClick = { viewModel.step(-1) }, enabled = current.canGoBack) {
                            Icon(
                                MaterialSymbols.Rounded.KeyboardArrowLeft,
                                stringResource(MR.strings.reading_stats_previous),
                            )
                        }
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { viewModel.step(1) }, enabled = current.canGoForward) {
                            Icon(
                                MaterialSymbols.Rounded.KeyboardArrowRight,
                                stringResource(MR.strings.reading_stats_next),
                            )
                        }
                    }
                }
                if (current.inProgress) {
                    item { ScreenNote(stringResource(MR.strings.reading_stats_retro_in_progress)) }
                }
                if (retro.isEmpty) {
                    item { ScreenNote(stringResource(MR.strings.reading_stats_retro_empty)) }
                    return@LazyColumn
                }
                item {
                    SectionCard(MR.strings.reading_stats_summary) {
                        Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                            lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
                item {
                    SectionCard(MR.strings.reading_stats_rank_series) { RankingBlock(retro.series, null) }
                }
                item {
                    SectionCard(MR.strings.reading_stats_field_writer) {
                        RankingBlock(retro.writers, stringResource(MR.strings.reading_stats_field_writer))
                    }
                }
                item {
                    SectionCard(MR.strings.reading_stats_field_publisher) {
                        RankingBlock(retro.publishers, stringResource(MR.strings.reading_stats_field_publisher))
                    }
                }
                item { ScreenNote(stringResource(MR.strings.reading_stats_retro_privacy)) }
            }

            if (sharing) {
                AlertDialog(
                    onDismissRequest = { sharing = false },
                    title = { Text(stringResource(MR.strings.action_share)) },
                    text = { Text(stringResource(MR.strings.reading_stats_share_how)) },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                sharing = false
                                RetrospectiveShare.shareImage(context, heading, title, lines)
                            },
                        ) {
                            Text(stringResource(MR.strings.reading_stats_share_image))
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                sharing = false
                                RetrospectiveShare.shareText(context, heading, title, lines)
                            },
                        ) {
                            Text(stringResource(MR.strings.reading_stats_share_text))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun titleOf(retro: Retrospective): String = when (retro.kind) {
    RetrospectiveKind.MONTH -> monthText(retro.period.from, withYear = true)
        .replaceFirstChar { it.titlecase(Locale.getDefault()) }
    RetrospectiveKind.YEAR -> retro.period.from.year.toString()
}

/** The retrospective as lines of text, the same on screen and when shared. */
@Composable
private fun retrospectiveLines(retro: Retrospective, today: kotlinx.datetime.LocalDate): List<String> {
    val context = LocalContext.current
    val summary = retro.summary
    val completed = summary.completedInReader + summary.completedManually
    return buildList {
        add(stringResource(MR.strings.reading_stats_retro_time, readingTimeText(context, summary.activeMs)))
        add(stringResource(MR.strings.reading_stats_retro_pages, summary.pagesNew))
        add(
            if (retro.completedUnits.total > 0) {
                stringResource(
                    MR.strings.reading_stats_retro_completed_units,
                    completed,
                    unitCountsText(retro.completedUnits),
                )
            } else {
                stringResource(MR.strings.reading_stats_retro_completed, completed)
            },
        )
        add(stringResource(MR.strings.reading_stats_retro_days, summary.readingDays))
        if (retro.longestStreak > 1) {
            add(pluralStringResource(MR.plurals.reading_stats_retro_streak, retro.longestStreak, retro.longestStreak))
        }
        retro.longestDay?.let {
            add(
                stringResource(
                    MR.strings.reading_stats_retro_longest_day,
                    readingTimeText(context, it.value),
                    readingShortDateText(it.date, today),
                ),
            )
        }
        if (retro.kind == RetrospectiveKind.YEAR) {
            retro.bestWeek?.let {
                add(
                    stringResource(
                        MR.strings.reading_stats_retro_best_week,
                        readingTimeText(context, it.activeMs),
                        periodText(it.week, today),
                    ),
                )
            }
        }
        if (retro.sessions.count > 0) {
            add(
                stringResource(
                    MR.strings.reading_stats_retro_sessions,
                    retro.sessions.count,
                    readingTimeText(context, retro.sessions.medianMs),
                ),
            )
        }
        retro.series.entries.takeIf { it.isNotEmpty() }?.let { entries ->
            add(
                stringResource(
                    MR.strings.reading_stats_retro_top_series,
                    entries.take(3).joinToString(", ") { "${it.value} (${readingTimeText(context, it.activeMs)})" },
                ),
            )
        }
        if (retro.writers.shown) {
            add(
                stringResource(
                    MR.strings.reading_stats_retro_top_writers,
                    retro.writers.entries.take(3).joinToString(", ") { it.value },
                    (retro.writers.coverage * 100).toInt(),
                ),
            )
        }
    }
}

@Composable
private fun RankingBlock(ranking: Ranking, fieldName: String?) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall)) {
        when {
            ranking.totalMs == 0L -> Note(stringResource(MR.strings.reading_stats_chart_empty))
            !ranking.shown && fieldName != null -> Note(
                stringResource(
                    MR.strings.reading_stats_ranking_low_coverage,
                    (ranking.coverage * 100).toInt(),
                    fieldName.lowercase(),
                    (Rankings.MIN_COVERAGE * 100).toInt(),
                ),
            )
            else -> {
                val largest = ranking.entries.firstOrNull()?.activeMs?.coerceAtLeast(1) ?: 1
                ranking.entries.forEach {
                    ShareRow(
                        label = it.value,
                        figure = "${readingTimeText(context, it.activeMs)} · ${(it.share * 100).toInt()}%",
                        fraction = it.activeMs.toFloat() / largest,
                    )
                }
                if (fieldName != null) {
                    Note(stringResource(MR.strings.reading_stats_ranking_based, (ranking.coverage * 100).toInt()))
                }
            }
        }
    }
}

@Composable
private fun Note(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
        modifier = modifier,
    )
}

@Composable
private fun ScreenNote(text: String) = Note(text, Modifier.padding(horizontal = MaterialTheme.padding.medium))
