package eu.kanade.presentation.readingstats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import eu.kanade.tachiyomi.ui.readingstats.ReadingStatsViewModel
import tachiyomi.domain.readingstats.goals.GoalMetric
import tachiyomi.domain.readingstats.goals.GoalPeriod
import tachiyomi.domain.readingstats.goals.GoalProgress
import tachiyomi.domain.readingstats.plans.Forecast
import tachiyomi.domain.readingstats.plans.NextStep
import tachiyomi.domain.readingstats.plans.PlanProgress
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

/** Goals, plans and what to do next, in one card. */
@Composable
fun ReadingObjectives(
    objectives: ReadingStatsViewModel.ObjectivesState,
    onOpenChapter: (mangaId: Long, chapterId: Long) -> Unit,
    onOpenPlan: (planId: Long) -> Unit,
    onEditGoals: () -> Unit,
    onEditPlans: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium)) {
        if (objectives.goals.isEmpty() && objectives.plans.isEmpty()) {
            Text(
                text = stringResource(MR.strings.reading_stats_objectives_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        objectives.goals.forEachIndexed { index, goal ->
            GoalRow(goal, large = index == 0 && goal.goal.isPrimary)
        }
        if (objectives.goals.isNotEmpty() && objectives.plans.isNotEmpty()) HorizontalDivider()
        objectives.plans.forEach { plan -> PlanSummary(plan, onClick = { onOpenPlan(plan.plan.id) }) }
        if (objectives.nextSteps.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall)) {
                Text(
                    text = stringResource(MR.strings.reading_stats_next_steps),
                    style = MaterialTheme.typography.labelLarge,
                )
                objectives.nextSteps.forEach { step -> NextStepRow(step, onOpenChapter, onOpenPlan) }
            }
        }
        Row {
            TextButton(onClick = onEditGoals) { Text(stringResource(MR.strings.reading_stats_goals)) }
            TextButton(onClick = onEditPlans) { Text(stringResource(MR.strings.reading_stats_plans)) }
        }
    }
}

@Composable
private fun GoalRow(progress: GoalProgress, large: Boolean) {
    val goal = progress.goal
    Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall)) {
        Text(
            text = listOfNotNull(
                stringResource(MR.strings.reading_stats_primary_goal).takeIf { goal.isPrimary },
                stringResource(
                    if (goal.period ==
                        GoalPeriod.DAY
                    ) {
                        MR.strings.reading_stats_today
                    } else {
                        MR.strings.reading_stats_this_week
                    },
                ),
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
        )
        Text(
            text = goalValueText(goal.metric, progress.value, goal.target),
            style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (large) FontWeight.Bold else FontWeight.Normal,
        )
        LinearProgressIndicator(
            progress = { (progress.value.toFloat() / goal.target.coerceAtLeast(1)).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
        )
        val daysMet = progress.daysMet
        val daysSoFar = progress.daysSoFar
        if (daysMet != null && daysSoFar != null) {
            Text(
                text = stringResource(MR.strings.reading_stats_goal_days_met, daysMet, daysSoFar),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
            )
        }
    }
}

/** A plan's name, how far along it is, and how long it has left. */
@Composable
fun PlanSummary(progress: PlanProgress, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
    ) {
        Text(
            text = progress.plan.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = listOfNotNull(
                stringResource(MR.strings.reading_stats_plan_progress, progress.done, progress.total),
                stringResource(
                    MR.strings.reading_stats_plan_this_week,
                    progress.doneThisWeek,
                    progress.plan.pacePerWeek,
                ).takeIf { !progress.plan.paused && progress.remaining > 0 },
                stringResource(MR.strings.reading_stats_plan_paused).takeIf { progress.plan.paused },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
        )
        LinearProgressIndicator(
            progress = { if (progress.total == 0) 0f else progress.done.toFloat() / progress.total },
            modifier = Modifier.fillMaxWidth(),
        )
        PlanOutlook(progress)
    }
}

/** How long the plan has left: at its own pace, and at the pace actually read, when that is known. */
@Composable
fun PlanOutlook(progress: PlanProgress) {
    val lines = buildList {
        progress.weeksAtPace?.let { weeks ->
            add(
                stringResource(
                    MR.strings.reading_stats_plan_at_pace,
                    pluralStringResource(MR.plurals.reading_stats_weeks, weeks, weeks),
                ),
            )
        }
        when (val forecast = progress.forecast) {
            Forecast.Finished -> add(stringResource(MR.strings.reading_stats_plan_finished))
            Forecast.Unknown -> add(stringResource(MR.strings.reading_stats_forecast_unknown))
            is Forecast.On -> add(stringResource(MR.strings.reading_stats_forecast_on, readingDateText(forecast.date)))
        }
    }
    lines.forEach {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = SECONDARY_ALPHA),
        )
    }
}

@Composable
private fun NextStepRow(
    step: NextStep,
    onOpenChapter: (mangaId: Long, chapterId: Long) -> Unit,
    onOpenPlan: (planId: Long) -> Unit,
) {
    val (text, onClick) = when (step) {
        is NextStep.GoalWithinReach -> {
            val goal = step.progress.goal
            val amount = goalAmountText(goal.metric, step.progress.remaining)
            stringResource(
                if (goal.period ==
                    GoalPeriod.DAY
                ) {
                    MR.strings.reading_stats_step_goal_day
                } else {
                    MR.strings.reading_stats_step_goal_week
                },
                amount,
            ) to null
        }
        is NextStep.FinishSoon -> stringResource(
            MR.strings.reading_stats_step_finish,
            goalAmountText(GoalMetric.CHAPTERS, step.plan.remaining.toLong()),
            step.plan.plan.name,
        ) to step.plan.openNext(onOpenChapter)
        is NextStep.KeepPace -> stringResource(
            MR.strings.reading_stats_step_keep_pace,
            goalAmountText(GoalMetric.CHAPTERS, step.plan.neededThisWeek.toLong()),
            step.plan.plan.name,
        ) to step.plan.openNext(onOpenChapter)
        is NextStep.CatchUp -> stringResource(
            MR.strings.reading_stats_step_catch_up,
            goalAmountText(GoalMetric.CHAPTERS, step.plan.neededThisWeek.toLong()),
            step.plan.plan.name,
        ) to step.plan.openNext(onOpenChapter)
        is NextStep.AdjustPace -> stringResource(MR.strings.reading_stats_step_adjust, step.plan.plan.name) to
            { onOpenPlan(step.plan.plan.id) }
        is NextStep.Ahead -> stringResource(
            MR.strings.reading_stats_step_ahead,
            goalAmountText(GoalMetric.CHAPTERS, step.plan.aheadBy.toLong()),
            step.plan.plan.name,
        ) to null
    }
    Text(
        text = "› $text",
        style = MaterialTheme.typography.bodyMedium,
        color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = MaterialTheme.padding.extraSmall),
    )
}

private fun PlanProgress.openNext(onOpenChapter: (Long, Long) -> Unit): (() -> Unit)? =
    next?.let { chapter -> { onOpenChapter(chapter.mangaId, chapter.chapterId) } }

@Composable
fun goalValueText(metric: GoalMetric, value: Long, target: Int): String = stringResource(
    when (metric) {
        GoalMetric.PAGES -> MR.strings.reading_stats_goal_pages
        GoalMetric.CHAPTERS -> MR.strings.reading_stats_goal_chapters
        GoalMetric.MINUTES -> MR.strings.reading_stats_goal_minutes
    },
    value,
    target,
)

@Composable
fun goalAmountText(metric: GoalMetric, amount: Long): String {
    val count = amount.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    return pluralStringResource(
        when (metric) {
            GoalMetric.PAGES -> MR.plurals.reading_stats_new_pages
            GoalMetric.CHAPTERS -> MR.plurals.reading_stats_chapters
            GoalMetric.MINUTES -> MR.plurals.reading_stats_minutes
        },
        count,
        count,
    )
}
