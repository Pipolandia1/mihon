package eu.kanade.tachiyomi.ui.readingstats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.readingstats.readingTimeText
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.readingstats.ReadingStatsConfig
import tachiyomi.domain.readingstats.analysis.InsightRules
import tachiyomi.domain.readingstats.analysis.Rankings
import tachiyomi.domain.readingstats.analysis.ReadingPace
import tachiyomi.domain.readingstats.comics.ComicLibrary
import tachiyomi.domain.readingstats.metrics.ReadingMetrics
import tachiyomi.domain.readingstats.plans.PlanRules
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * How every figure is counted, with the thresholds the code actually uses: each number here comes
 * from the same constant the cards, charts, goals and insights read.
 */
class ReadingDefinitionsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val tracker = ReadingStatsConfig.TRACKER
        val sections: List<Pair<StringResource, String>> = listOf(
            MR.strings.reading_stats_def_title_time to stringResource(
                MR.strings.reading_stats_definitions,
                readingTimeText(context, tracker.idleTimeout.inWholeMilliseconds),
                readingTimeText(context, tracker.idleGrace.inWholeMilliseconds),
                readingTimeText(context, ReadingMetrics.READING_DAY_MIN_ACTIVE.inWholeMilliseconds),
            ),
            MR.strings.reading_stats_def_title_habits to stringResource(
                MR.strings.reading_stats_definitions_habits,
                PlanRules.FORECAST_WINDOW_DAYS,
                PlanRules.FORECAST_MIN_DAYS,
                PlanRules.FORECAST_MIN_FINISHED,
            ),
            MR.strings.reading_stats_def_title_units to stringResource(MR.strings.reading_stats_def_units),
            MR.strings.reading_stats_def_title_library to stringResource(
                MR.strings.reading_stats_def_library,
                ComicLibrary.NEAR_FINISH_MAX,
                ComicLibrary.STALLED_DAYS,
            ),
            MR.strings.reading_stats_def_title_periods to stringResource(MR.strings.reading_stats_def_periods),
            MR.strings.reading_stats_def_title_pace to stringResource(
                MR.strings.reading_stats_def_pace,
                ReadingPace.MIN_ACTIVE_FOR_RATE_MS / MS_PER_MINUTE,
                ReadingPace.RATE_WINDOW_DAYS,
                ReadingPace.RATE_MIN_DAYS,
                ReadingPace.RATE_MIN_CHAPTERS,
            ),
            MR.strings.reading_stats_def_title_when to stringResource(MR.strings.reading_stats_def_when),
            MR.strings.reading_stats_def_title_rankings to stringResource(
                MR.strings.reading_stats_def_rankings,
                (Rankings.MIN_COVERAGE * 100).toInt(),
            ),
            MR.strings.reading_stats_def_title_insights to stringResource(
                MR.strings.reading_stats_def_insights,
                InsightRules.STREAK_MIN_DAYS,
                InsightRules.WEEK_CHANGE_MIN_PERCENT,
                InsightRules.WEEK_CHANGE_MIN_PAGES,
                InsightRules.BEST_WEEK_MIN_WEEKS,
                InsightRules.WEEKDAY_MIN_PERCENT,
                InsightRules.DAY_PART_MIN_SESSIONS,
                InsightRules.DAY_PART_MIN_PERCENT,
                InsightRules.TOP_SERIES_MIN_PERCENT,
                InsightRules.RECENT_DAYS,
            ),
            MR.strings.reading_stats_def_title_privacy to stringResource(MR.strings.reading_stats_def_privacy),
        )

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.reading_stats_how_counted),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            LazyColumn(
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.large),
            ) {
                items(sections) { (title, body) ->
                    Column(
                        modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                    ) {
                        Text(
                            text = stringResource(title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(text = body, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    private companion object {
        const val MS_PER_MINUTE = 60_000L
    }
}
