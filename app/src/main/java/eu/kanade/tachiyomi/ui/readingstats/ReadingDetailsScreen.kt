package eu.kanade.tachiyomi.ui.readingstats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.readingstats.BarChart
import eu.kanade.presentation.readingstats.ChartBar
import eu.kanade.presentation.readingstats.ShareRow
import eu.kanade.presentation.readingstats.bucketLabel
import eu.kanade.presentation.readingstats.bucketName
import eu.kanade.presentation.readingstats.dayPartPhrase
import eu.kanade.presentation.readingstats.periodChoiceName
import eu.kanade.presentation.readingstats.periodText
import eu.kanade.presentation.readingstats.rankingDimensionName
import eu.kanade.presentation.readingstats.rankingValueText
import eu.kanade.presentation.readingstats.readingDateText
import eu.kanade.presentation.readingstats.readingShortDateText
import eu.kanade.presentation.readingstats.readingTimeText
import eu.kanade.presentation.readingstats.unitCountsText
import eu.kanade.presentation.readingstats.weekdayLongName
import eu.kanade.presentation.readingstats.weekdayNarrowName
import eu.kanade.presentation.util.Screen
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
import tachiyomi.domain.readingstats.analysis.BucketSize
import tachiyomi.domain.readingstats.analysis.DayPart
import tachiyomi.domain.readingstats.analysis.PeriodChoice
import tachiyomi.domain.readingstats.analysis.RankingDimension
import tachiyomi.domain.readingstats.analysis.Rankings
import tachiyomi.domain.readingstats.analysis.ReadingPace
import tachiyomi.domain.readingstats.analysis.RetrospectiveKind
import tachiyomi.domain.readingstats.metrics.Coverage
import tachiyomi.domain.readingstats.metrics.Trend
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.SectionCard
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import java.util.Locale
import kotlin.time.Clock
import kotlin.time.Instant

private enum class ChartMetric { TIME, PAGES, CHAPTERS }

private const val HOUR_LABEL_EVERY = 6
private val SmallChartHeight = 64.dp

/** Reading over a chosen period, with every figure explained in "how we count". */
class ReadingDetailsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<ReadingDetailsViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()
        var pickingRange by remember { mutableStateOf(false) }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.reading_stats_details),
                    navigateUp = navigator::pop,
                    actions = {
                        AppBarActions(
                            listOf(
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.reading_stats_month_summary),
                                    onClick = { navigator.push(ReadingRetrospectiveScreen(RetrospectiveKind.MONTH)) },
                                ),
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.reading_stats_year_review),
                                    onClick = { navigator.push(ReadingRetrospectiveScreen(RetrospectiveKind.YEAR)) },
                                ),
                                AppBar.OverflowAction(
                                    title = stringResource(MR.strings.reading_stats_how_counted),
                                    onClick = { navigator.push(ReadingDefinitionsScreen()) },
                                ),
                            ),
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            val current = state
            LazyColumn(
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
            ) {
                item {
                    PeriodChips(
                        selected = current?.choice,
                        onChoose = { if (it == PeriodChoice.CUSTOM) pickingRange = true else viewModel.choose(it) },
                    )
                }
                if (current == null) {
                    item { LoadingScreen() }
                    return@LazyColumn
                }
                item { PeriodHeader(current) }
                item { SectionCard(MR.strings.reading_stats_summary) { SummaryCard(current) } }
                item { SectionCard(MR.strings.reading_stats_over_period) { ChartCard(current) } }
                item { SectionCard(MR.strings.reading_stats_pace_title) { PaceCard(current) } }
                item { SectionCard(MR.strings.reading_stats_sessions_title) { SessionsCard(current) } }
                item { SectionCard(MR.strings.reading_stats_when_title) { WhenCard(current) } }
                item { SectionCard(MR.strings.reading_stats_estimates_title) { EstimatesCard(current) } }
                item {
                    SectionCard(MR.strings.reading_stats_rankings_title) {
                        RankingsCard(current, onClassify = { navigator.push(ComicLibraryScreen()) })
                    }
                }
                item {
                    TextButton(
                        onClick = { navigator.push(ReadingDefinitionsScreen()) },
                        modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                    ) {
                        Text(stringResource(MR.strings.reading_stats_how_counted))
                    }
                }
            }
        }

        if (pickingRange) {
            RangePickerDialog(
                onPick = { from, to ->
                    viewModel.chooseRange(from, to)
                    pickingRange = false
                },
                onDismissRequest = { pickingRange = false },
            )
        }
    }
}

@Composable
private fun PeriodChips(selected: PeriodChoice?, onChoose: (PeriodChoice) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
    ) {
        PeriodChoice.entries.forEach { choice ->
            FilterChip(
                selected = choice == selected,
                onClick = { onChoose(choice) },
                label = { Text(stringResource(periodChoiceName(choice))) },
            )
        }
    }
}

@Composable
private fun PeriodHeader(state: ReadingDetailsViewModel.State) {
    Column(
        modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
    ) {
        Text(
            text = periodText(state.period, state.today),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        if (state.summary.coverage != Coverage.FULL) {
            Footnote(
                stringResource(MR.strings.reading_stats_recording_since, readingDateText(state.recordingStartedOn)),
            )
        }
    }
}

@Composable
private fun SummaryCard(state: ReadingDetailsViewModel.State) {
    val context = LocalContext.current
    val summary = state.summary
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
        Figure(stringResource(MR.strings.reading_stats_time_reading), readingTimeText(context, summary.activeMs))
        Figure(stringResource(MR.strings.reading_stats_pages_new), summary.pagesNew.toString())
        Figure(
            stringResource(MR.strings.reading_stats_chapters_finished),
            (summary.completedInReader + summary.completedManually).toString(),
        )
        Figure(
            stringResource(MR.strings.reading_stats_reading_days),
            stringResource(MR.strings.reading_stats_days_of, summary.readingDays, state.pace.recordedDays),
        )
        if (state.completedUnits.total > 0) {
            Footnote(stringResource(MR.strings.reading_stats_completed_units, unitCountsText(state.completedUnits)))
        }
        if (summary.completedManually > 0) {
            Footnote(stringResource(MR.strings.reading_stats_marked_read, summary.completedManually))
        }
        if (summary.completedImported > 0) {
            Footnote(stringResource(MR.strings.reading_stats_imported, summary.completedImported))
        }
        val previous = state.previous
        if (previous != null) {
            Footnote(
                if (state.comparable) {
                    stringResource(
                        MR.strings.reading_stats_compare,
                        periodText(previous.period, state.today),
                        trendText(state.timeTrend),
                        trendText(state.pagesTrend),
                    )
                } else {
                    stringResource(MR.strings.reading_stats_compare_none, periodText(previous.period, state.today))
                },
            )
        }
    }
}

@Composable
private fun trendText(trend: Trend?): String = when {
    trend == null -> stringResource(MR.strings.reading_stats_compare_no_base)
    trend.percent > 0 -> "+${trend.percent}%"
    trend.percent < 0 -> "−${-trend.percent}%"
    else -> stringResource(MR.strings.reading_stats_compare_same)
}

@Composable
private fun ChartCard(state: ReadingDetailsViewModel.State) {
    val context = LocalContext.current
    var metric by rememberSaveable { mutableStateOf(ChartMetric.TIME) }
    val buckets = state.buckets
    fun valueOf(index: Int): Long = when (metric) {
        ChartMetric.TIME -> buckets[index].activeMs
        ChartMetric.PAGES -> buckets[index].pagesNew.toLong()
        ChartMetric.CHAPTERS -> buckets[index].completed.toLong()
    }
    fun figure(value: Long): String = when (metric) {
        ChartMetric.TIME -> readingTimeText(context, value)
        else -> value.toString()
    }
    val known = buckets.indices.filter { buckets[it].coverage != Coverage.NONE }
    val peak = known.maxByOrNull { valueOf(it) }?.takeIf { valueOf(it) > 0 }
    val unitName = stringResource(
        when (state.bucketSize) {
            BucketSize.DAY -> MR.strings.reading_stats_unit_day
            BucketSize.WEEK -> MR.strings.reading_stats_unit_week
            BucketSize.MONTH -> MR.strings.reading_stats_unit_month
        },
    )
    val summaryText = if (peak == null) {
        stringResource(MR.strings.reading_stats_chart_empty)
    } else {
        stringResource(
            MR.strings.reading_stats_chart_summary,
            figure(valueOf(peak)),
            bucketName(buckets[peak], state.bucketSize, state.today),
            figure(known.sumOf { valueOf(it) } / known.size.coerceAtLeast(1)),
            unitName,
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
            ChartMetric.entries.forEach { option ->
                FilterChip(
                    selected = option == metric,
                    onClick = { metric = option },
                    label = {
                        Text(
                            stringResource(
                                when (option) {
                                    ChartMetric.TIME -> MR.strings.reading_stats_time_reading
                                    ChartMetric.PAGES -> MR.strings.reading_stats_pages_new
                                    ChartMetric.CHAPTERS -> MR.strings.reading_stats_chapters_finished
                                },
                            ),
                        )
                    },
                )
            }
        }
        BarChart(
            bars = buckets.indices.map { index ->
                ChartBar(
                    value = valueOf(index).takeIf { buckets[index].coverage != Coverage.NONE },
                    label = bucketLabel(buckets, index, state.bucketSize),
                    highlighted = index == peak,
                )
            },
            description = summaryText,
        )
        Text(summaryText, style = MaterialTheme.typography.bodySmall)
        if (known.size < buckets.size) Footnote(stringResource(MR.strings.reading_stats_chart_unknown))
    }
}

@Composable
private fun PaceCard(state: ReadingDetailsViewModel.State) {
    val pace = state.pace
    val unknown = stringResource(MR.strings.reading_stats_unknown_value)
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
        Figure(stringResource(MR.strings.reading_stats_pages_per_hour), pace.pagesPerHour?.toString() ?: unknown)
        Figure(
            stringResource(MR.strings.reading_stats_minutes_per_day),
            pace.minutesPerReadingDay?.toString() ?: unknown,
        )
        Figure(
            stringResource(MR.strings.reading_stats_chapters_per_week_figure),
            pace.chaptersPerWeek?.let { decimal(it) } ?: unknown,
        )
        Footnote(stringResource(MR.strings.reading_stats_pace_help))
    }
}

@Composable
private fun SessionsCard(state: ReadingDetailsViewModel.State) {
    val context = LocalContext.current
    val sessions = state.sessions
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
        if (sessions.count == 0) {
            Text(stringResource(MR.strings.reading_stats_sessions_none), style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        Figure(stringResource(MR.strings.reading_stats_sessions_count), sessions.count.toString())
        Figure(stringResource(MR.strings.reading_stats_session_median), readingTimeText(context, sessions.medianMs))
        Figure(stringResource(MR.strings.reading_stats_session_average), readingTimeText(context, sessions.averageMs))
        sessions.longest?.let { longest ->
            Footnote(
                stringResource(
                    MR.strings.reading_stats_session_longest,
                    readingTimeText(context, longest.activeMs),
                    longest.title,
                    longest.chapterName,
                ),
            )
        }
        val bands = ReadingPace.LENGTH_BANDS_MINUTES
        val labels = listOf("<${bands.first()}") +
            bands.zipWithNext { a, b -> "$a–$b" } +
            listOf("${bands.last()}+")
        val description = stringResource(
            MR.strings.reading_stats_session_bands,
            labels.zip(sessions.byLength).joinToString(", ") { (label, count) -> "$label: $count" },
        )
        BarChart(
            bars = sessions.byLength.mapIndexed { index, count -> ChartBar(count.toLong(), labels[index]) },
            description = description,
            height = SmallChartHeight,
        )
        Footnote(stringResource(MR.strings.reading_stats_session_bands_help))
    }
}

@Composable
private fun WhenCard(state: ReadingDetailsViewModel.State) {
    val context = LocalContext.current
    val total = state.byWeekday.sum()
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
        if (total == 0L) {
            Text(stringResource(MR.strings.reading_stats_chart_empty), style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        // Weekdays in the order this locale's weeks run.
        val order = (0 until 7).map { DayOfWeek.entries[(state.firstDayOfWeek.isoDayNumber - 1 + it) % 7] }
        val topDay = order.maxBy { state.byWeekday[it.isoDayNumber - 1] }
        val weekdayText = stringResource(MR.strings.reading_stats_when_weekday, weekdayLongName(topDay))
        BarChart(
            bars = order.map {
                ChartBar(state.byWeekday[it.isoDayNumber - 1], weekdayNarrowName(it), highlighted = it == topDay)
            },
            description = order.joinToString(", ") {
                "${weekdayLongName(it)}: ${readingTimeText(context, state.byWeekday[it.isoDayNumber - 1])}"
            },
            height = SmallChartHeight,
        )
        Text(weekdayText, style = MaterialTheme.typography.bodySmall)
        val hoursTotal = state.byHour.sum()
        if (hoursTotal > 0) {
            val parts = ReadingPace.byDayPart(state.byHour)
            val topPart = DayPart.entries.maxBy { parts[it] ?: 0 }
            val topHour = state.byHour.indices.maxBy { state.byHour[it] }
            val partText = stringResource(
                MR.strings.reading_stats_when_day_part,
                stringResource(dayPartPhrase(topPart)),
            )
            BarChart(
                bars = state.byHour.mapIndexed { hour, ms ->
                    ChartBar(ms, if (hour % HOUR_LABEL_EVERY == 0) "${hour}h" else null, highlighted = hour == topHour)
                },
                description = partText,
                height = SmallChartHeight,
            )
            Text(partText, style = MaterialTheme.typography.bodySmall)
        }
        Footnote(stringResource(MR.strings.reading_stats_when_help))
    }
}

@Composable
private fun EstimatesCard(state: ReadingDetailsViewModel.State) {
    val rate = state.rate
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
        if (rate == null) {
            Text(
                text = stringResource(
                    MR.strings.reading_stats_estimates_unknown,
                    ReadingPace.RATE_MIN_DAYS,
                    ReadingPace.RATE_MIN_CHAPTERS,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Column
        }
        Text(
            text = stringResource(MR.strings.reading_stats_estimates_rate, decimal(rate.perWeek)),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (state.estimates.isEmpty()) {
            Footnote(stringResource(MR.strings.reading_stats_estimates_none))
        }
        state.estimates.forEach { estimate ->
            Figure(
                estimate.progress.entry.title,
                stringResource(
                    MR.strings.reading_stats_estimate_row,
                    estimate.progress.remaining ?: 0,
                    pluralStringResource(MR.plurals.reading_stats_weeks, estimate.weeks, estimate.weeks),
                ),
            )
        }
    }
}

@Composable
private fun RankingsCard(state: ReadingDetailsViewModel.State, onClassify: () -> Unit) {
    val context = LocalContext.current
    var dimension by rememberSaveable { mutableStateOf(RankingDimension.SERIES) }
    val ranking = state.rankings.getValue(dimension)
    val fieldName = stringResource(rankingDimensionName(dimension)).lowercase()
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
            RankingDimension.entries.forEach { option ->
                FilterChip(
                    selected = option == dimension,
                    onClick = { dimension = option },
                    label = { Text(stringResource(rankingDimensionName(option))) },
                )
            }
        }
        when {
            ranking.totalMs == 0L -> Text(
                stringResource(MR.strings.reading_stats_chart_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
            !ranking.shown -> {
                Text(
                    text = stringResource(
                        MR.strings.reading_stats_ranking_low_coverage,
                        percent(ranking.coverage),
                        fieldName,
                        percent(Rankings.MIN_COVERAGE),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onClassify) { Text(stringResource(MR.strings.reading_stats_classify)) }
            }
            else -> {
                val largest = ranking.entries.first().activeMs.coerceAtLeast(1)
                ranking.entries.forEach { entry ->
                    ShareRow(
                        label = rankingValueText(dimension, entry.value),
                        figure = "${readingTimeText(context, entry.activeMs)} · ${percent(entry.share)}%",
                        fraction = entry.activeMs.toFloat() / largest,
                    )
                }
                if (dimension != RankingDimension.SERIES) {
                    Footnote(stringResource(MR.strings.reading_stats_ranking_based, percent(ranking.coverage)))
                }
                if (ranking.classifiedMs < ranking.totalMs) {
                    Footnote(
                        stringResource(
                            MR.strings.reading_stats_ranking_unknown,
                            fieldName,
                            percent(1 - ranking.coverage),
                        ),
                    )
                }
                if (dimension.field?.list == true) Footnote(stringResource(MR.strings.reading_stats_ranking_multi))
            }
        }
    }
}

@Composable
private fun Figure(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = MaterialTheme.padding.small),
        )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangePickerDialog(
    onPick: (kotlinx.datetime.LocalDate, kotlinx.datetime.LocalDate) -> Unit,
    onDismissRequest: () -> Unit,
) {
    // The picker speaks in UTC midnights.
    val todayUtc = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        .atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
    val pickerState = rememberDateRangePickerState(
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= todayUtc
        },
    )
    fun dateOf(millis: Long) = Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date
    DatePickerDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                onClick = {
                    val from = pickerState.selectedStartDateMillis ?: return@TextButton
                    val to = pickerState.selectedEndDateMillis ?: from
                    onPick(dateOf(from), dateOf(to))
                },
                enabled = pickerState.selectedStartDateMillis != null,
            ) {
                Text(stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
        },
    ) {
        // The default headline wraps word by word in narrow dialogs with long translations.
        DateRangePicker(
            state = pickerState,
            modifier = Modifier.weight(1f),
            headline = {
                val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
                val picked = listOfNotNull(pickerState.selectedStartDateMillis, pickerState.selectedEndDateMillis)
                    .map { readingShortDateText(dateOf(it), today) }
                val none = stringResource(MR.strings.reading_stats_period_custom)
                Text(
                    text = picked.joinToString(" – ").ifEmpty { none },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(
                        start = MaterialTheme.padding.large,
                        end = MaterialTheme.padding.medium,
                        bottom = MaterialTheme.padding.medium,
                    ),
                )
            },
            showModeToggle = false,
        )
    }
}

private fun percent(fraction: Double): Int = (fraction * 100).toInt()

private fun decimal(value: Double): String = String.format(Locale.getDefault(), "%.1f", value)
