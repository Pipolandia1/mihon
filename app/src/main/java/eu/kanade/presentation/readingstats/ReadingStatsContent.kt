package eu.kanade.presentation.readingstats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.formatChapterNumber
import eu.kanade.tachiyomi.ui.readingstats.ReadingStatsViewModel
import kotlinx.datetime.LocalDate
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.CalendarMonth
import mihon.icons.materialsymbols.rounded.CollectionsBookmark
import mihon.icons.materialsymbols.rounded.LocalLibrary
import mihon.icons.materialsymbols.rounded.Schedule
import tachiyomi.domain.readingstats.metrics.Coverage
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.SectionCard
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

private val DayBarsHeight = 56.dp
private val FigureMinWidth = 72.dp
private val ContinueCoverHeight = 64.dp

@Composable
fun ReadingStatsContent(
    state: ReadingStatsViewModel.State,
    contentPadding: PaddingValues,
    onClickDay: (LocalDate) -> Unit,
    onClickContinue: (mangaId: Long, chapterId: Long) -> Unit,
    onOpenPlan: (planId: Long) -> Unit,
    onEditGoals: () -> Unit,
    onEditPlans: () -> Unit,
    onShowMonth: (LocalDate) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenCollections: () -> Unit,
    onHideStalled: () -> Unit,
) {
    val week = state.week
    if (week == null) {
        LoadingScreen()
        return
    }
    LazyColumn(
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        if (!state.recordingEnabled) {
            item { Notice(stringResource(MR.strings.reading_stats_paused)) }
        }
        item {
            SectionCard(MR.strings.reading_stats_this_week) {
                WeekCard(week, onClickDay)
            }
        }
        if (!week.hasAnyReading) {
            item {
                Notice(
                    stringResource(MR.strings.reading_stats_empty),
                    stringResource(MR.strings.reading_stats_empty_detail),
                )
            }
        }
        if (state.continueReading.isNotEmpty()) {
            item {
                SectionCard(MR.strings.reading_stats_continue_reading) {
                    state.continueReading.forEach { ContinueRow(it, onClickContinue) }
                }
            }
        }
        state.objectives?.let { objectives ->
            item {
                SectionCard(MR.strings.reading_stats_objectives) {
                    ReadingObjectives(objectives, onClickContinue, onOpenPlan, onEditGoals, onEditPlans)
                }
            }
        }
        state.library?.let { library ->
            if (library.nearFinish.isNotEmpty()) {
                item {
                    SectionCard(MR.strings.reading_stats_near_finish) {
                        NearFinishCard(library.nearFinish, onClickContinue)
                    }
                }
            }
            if (library.stalled.isNotEmpty() && !library.hideStalled) {
                item {
                    SectionCard(MR.strings.reading_stats_stalled) {
                        StalledCard(library.stalled, library.nowMs, onClickContinue, onHideStalled)
                    }
                }
            }
        }
        state.activity?.let { activity ->
            item {
                SectionCard(MR.strings.reading_stats_activity) {
                    ReadingActivity(activity, onClickDay, onShowMonth)
                }
            }
        }
        state.library?.takeIf { it.entries > 0 }?.let { library ->
            item {
                SectionCard(MR.strings.reading_stats_library) {
                    LibraryCard(library, onOpenLibrary, onOpenCollections)
                }
            }
        }
    }
}

@Composable
private fun WeekCard(week: ReadingStatsViewModel.WeekState, onClickDay: (LocalDate) -> Unit) {
    val context = LocalContext.current
    val summary = week.summary
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
        val figures = listOf(
            Figure(
                summary.pagesNew.toString(),
                stringResource(MR.strings.reading_stats_pages_new),
                MaterialSymbols.Rounded.LocalLibrary,
            ),
            Figure(
                summary.completedInReader.toString(),
                stringResource(MR.strings.reading_stats_chapters_finished),
                MaterialSymbols.Rounded.CollectionsBookmark,
            ),
            Figure(
                readingTimeText(context, summary.activeMs),
                stringResource(MR.strings.reading_stats_time_reading),
                MaterialSymbols.Rounded.Schedule,
            ),
            Figure(
                stringResource(MR.strings.reading_stats_days_of, summary.readingDays, summary.period.days),
                stringResource(MR.strings.reading_stats_reading_days),
                MaterialSymbols.Rounded.CalendarMonth,
            ),
        )
        // Four across while each has room for its words; two by two with large text.
        BoxWithConstraints {
            val columns = if (maxWidth / figures.size >= FigureMinWidth * LocalDensity.current.fontScale) {
                figures.size
            } else {
                2
            }
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium)) {
                figures.chunked(columns).forEach { row ->
                    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                        row.forEach { WeekFigure(it) }
                    }
                }
            }
        }
        DayBars(week, onClickDay)
        if (summary.completedManually > 0) {
            Footnote(stringResource(MR.strings.reading_stats_marked_read, summary.completedManually))
        }
        if (summary.completedImported > 0) {
            Footnote(stringResource(MR.strings.reading_stats_imported, summary.completedImported))
        }
        val trend = week.pagesTrend
        when {
            trend != null && trend.percent > 0 ->
                Footnote(stringResource(MR.strings.reading_stats_trend_more, trend.percent))
            trend != null && trend.percent < 0 ->
                Footnote(stringResource(MR.strings.reading_stats_trend_less, -trend.percent))
            trend != null -> Footnote(stringResource(MR.strings.reading_stats_trend_same))
            !week.comparable -> Footnote(stringResource(MR.strings.reading_stats_no_comparison))
        }
        if (summary.coverage != Coverage.FULL) {
            Footnote(
                stringResource(MR.strings.reading_stats_recording_since, readingDateText(week.recordingStartedOn)),
            )
        }
    }
}

/** One bar per day of the week, as tall as that day's reading time; a day opens its history. */
@Composable
private fun DayBars(week: ReadingStatsViewModel.WeekState, onClickDay: (LocalDate) -> Unit) {
    val context = LocalContext.current
    val longest = week.days.maxOf { it.activeMs }.coerceAtLeast(1)
    Row(modifier = Modifier.fillMaxWidth().padding(top = MaterialTheme.padding.small)) {
        week.days.forEach { day ->
            val future = day.date > week.today
            val recorded = day.date >= week.recordingStartedOn
            val description = if (recorded) {
                stringResource(
                    MR.strings.reading_stats_day_bar,
                    weekdayName(day.date),
                    readingTimeText(context, day.activeMs),
                    day.pagesNew,
                )
            } else {
                stringResource(MR.strings.reading_stats_day_bar_not_recorded, weekdayName(day.date))
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = !future, role = Role.Button) { onClickDay(day.date) }
                    .clearAndSetSemantics { contentDescription = description }
                    .padding(vertical = MaterialTheme.padding.extraSmall),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier.height(DayBarsHeight).fillMaxWidth(0.5f),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    val fraction = day.activeMs.toFloat() / longest
                    if (day.activeMs > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(fraction.coerceAtLeast(MIN_BAR_FRACTION))
                                .background(
                                    MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp),
                                ),
                        )
                    } else if (recorded && !future) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.dp)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = EMPTY_BAR_ALPHA)),
                        )
                    }
                }
                Spacer(Modifier.height(MaterialTheme.padding.extraSmall))
                val isToday = day.date == week.today
                Text(
                    text = weekdayInitial(day.date),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        isToday -> MaterialTheme.colorScheme.primary
                        future -> MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA)
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

/** A figure of the week: the number, what it counts, and its icon. */
private class Figure(val value: String, val label: String, val icon: ImageVector)

@Composable
private fun RowScope.WeekFigure(figure: Figure) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .padding(horizontal = MaterialTheme.padding.extraSmall),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = figure.value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Text(
            text = figure.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.weight(1f))
        Icon(imageVector = figure.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ContinueRow(
    item: ReadingStatsViewModel.ContinueItem,
    onClick: (mangaId: Long, chapterId: Long) -> Unit,
) {
    val chapter = formatChapterNumber(item.chapterNumber)
    val detail = when {
        item.resumePage != null && item.totalPages != null ->
            stringResource(MR.strings.reading_stats_continue_page, chapter, item.resumePage, item.totalPages)
        item.resumePage != null ->
            stringResource(MR.strings.reading_stats_continue_page_unknown_total, chapter, item.resumePage)
        else -> stringResource(MR.strings.reading_stats_continue_next, chapter)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(item.mangaId, item.chapterId) }
            .padding(vertical = MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover.Book(
            modifier = Modifier.height(ContinueCoverHeight),
            data = item.cover,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = MaterialTheme.padding.medium),
        ) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
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

@Composable
private fun Notice(title: String, detail: String? = null) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MaterialTheme.padding.extraLarge, vertical = MaterialTheme.padding.small),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Start,
        )
        if (detail != null) Footnote(detail)
    }
}

private const val MIN_BAR_FRACTION = 0.06f
private const val EMPTY_BAR_ALPHA = 0.2f
