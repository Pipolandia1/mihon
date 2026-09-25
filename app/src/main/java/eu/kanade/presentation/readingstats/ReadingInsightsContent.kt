package eu.kanade.presentation.readingstats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.datetime.LocalDate
import tachiyomi.domain.readingstats.analysis.Insight
import tachiyomi.domain.readingstats.analysis.InsightRules
import tachiyomi.domain.readingstats.analysis.ReadingPace
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import java.util.Locale

/**
 * A few observations, each with "why?" showing the numbers and the rule behind it, and the way
 * into the details by period.
 */
@Composable
fun InsightsCard(
    insights: List<Insight>,
    today: LocalDate,
    onOpenDetails: () -> Unit,
    onOpenRetrospective: () -> Unit,
) {
    var explained by remember { mutableStateOf<Insight?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
        if (insights.isEmpty()) {
            Text(
                text = stringResource(MR.strings.reading_stats_insights_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
            )
        }
        insights.take(InsightRules.MAX_SHOWN).forEach { insight ->
            Column {
                Text(text = insightText(insight), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { explained = insight }) {
                    Text(stringResource(MR.strings.reading_stats_insight_why))
                }
            }
        }
        FlowRow {
            TextButton(onClick = onOpenDetails) { Text(stringResource(MR.strings.reading_stats_details)) }
            TextButton(onClick = onOpenRetrospective) { Text(stringResource(MR.strings.reading_stats_month_summary)) }
        }
    }
    explained?.let { insight ->
        AlertDialog(
            onDismissRequest = { explained = null },
            confirmButton = {
                TextButton(onClick = { explained = null }) { Text(stringResource(MR.strings.action_ok)) }
            },
            title = { Text(stringResource(MR.strings.reading_stats_insight_why_title)) },
            text = {
                Text(
                    text = insightEvidence(insight, today),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
        )
    }
}

@Composable
fun insightText(insight: Insight): String = when (insight) {
    is Insight.Streak -> pluralStringResource(MR.plurals.reading_stats_insight_streak, insight.days, insight.days)
    is Insight.WeekChange -> if (insight.percent > 0) {
        stringResource(MR.strings.reading_stats_insight_week_more, insight.percent)
    } else {
        stringResource(MR.strings.reading_stats_insight_week_less, -insight.percent)
    }
    is Insight.BestWeek -> stringResource(MR.strings.reading_stats_insight_best_week)
    is Insight.FavoriteWeekday -> stringResource(
        MR.strings.reading_stats_insight_weekday,
        weekdayLongName(insight.weekday).replaceFirstChar { it.titlecase(Locale.getDefault()) },
        insight.percent,
    )
    is Insight.FavoriteDayPart -> stringResource(
        MR.strings.reading_stats_insight_day_part,
        stringResource(dayPartPhrase(insight.part)),
        insight.percent,
    )
    is Insight.TopSeries -> stringResource(MR.strings.reading_stats_insight_top_series, insight.title, insight.percent)
    is Insight.BacklogPace -> pluralStringResource(
        MR.plurals.reading_stats_insight_backlog,
        insight.weeks,
        insight.weeks,
    )
}

/** The numbers an insight was drawn from, and its rule. */
@Composable
fun insightEvidence(insight: Insight, today: LocalDate): String {
    val context = LocalContext.current
    return when (insight) {
        is Insight.Streak -> stringResource(
            MR.strings.reading_stats_why_streak,
            readingShortDateText(insight.since, today),
            insight.days,
            insight.record,
            InsightRules.STREAK_MIN_DAYS,
        )
        is Insight.WeekChange -> stringResource(
            MR.strings.reading_stats_why_week,
            periodText(insight.thisWeek, today),
            insight.pagesNow,
            periodText(insight.lastWeek, today),
            insight.pagesBefore,
            InsightRules.WEEK_CHANGE_MIN_PERCENT,
            InsightRules.WEEK_CHANGE_MIN_PAGES,
        )
        is Insight.BestWeek -> stringResource(
            MR.strings.reading_stats_why_best_week,
            readingTimeText(context, insight.activeMs),
            periodText(insight.previousBest, today),
            readingTimeText(context, insight.previousBestMs),
            insight.weeksCompared,
            InsightRules.BEST_WEEK_MIN_WEEKS,
        )
        is Insight.FavoriteWeekday -> stringResource(
            MR.strings.reading_stats_why_weekday,
            weekdayLongName(insight.weekday).replaceFirstChar { it.titlecase(Locale.getDefault()) },
            readingTimeText(context, insight.activeMs),
            readingTimeText(context, insight.totalMs),
            periodText(insight.period, today),
            InsightRules.WEEKDAY_MIN_PERCENT,
        )
        is Insight.FavoriteDayPart -> stringResource(
            MR.strings.reading_stats_why_day_part,
            readingTimeText(context, insight.activeMs),
            readingTimeText(context, insight.totalMs),
            insight.sessions,
            periodText(insight.period, today),
            InsightRules.DAY_PART_MIN_SESSIONS,
            InsightRules.DAY_PART_MIN_PERCENT,
        )
        is Insight.TopSeries -> stringResource(
            MR.strings.reading_stats_why_top_series,
            insight.title,
            readingTimeText(context, insight.activeMs),
            readingTimeText(context, insight.totalMs),
            periodText(insight.period, today),
            InsightRules.TOP_SERIES_MIN_PERCENT,
        )
        is Insight.BacklogPace -> stringResource(
            MR.strings.reading_stats_why_backlog,
            insight.backlog,
            insight.rate.windowDays,
            insight.rate.chapters,
            String.format(Locale.getDefault(), "%.1f", insight.rate.perWeek),
            ReadingPace.RATE_MIN_DAYS,
            ReadingPace.RATE_MIN_CHAPTERS,
        )
    }
}
