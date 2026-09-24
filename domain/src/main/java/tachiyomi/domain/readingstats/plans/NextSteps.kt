package tachiyomi.domain.readingstats.plans

import tachiyomi.domain.readingstats.goals.GoalProgress

/**
 * A suggestion with its numbers and where it leads. Worded as what is possible from here, never as
 * what was missed.
 */
sealed interface NextStep {
    /** The goal is not met yet and what is left is a small part of it. */
    data class GoalWithinReach(val progress: GoalProgress) : NextStep

    /** A few chapters finish the plan. */
    data class FinishSoon(val plan: PlanProgress) : NextStep

    /** This many more this week keep the plan's pace. */
    data class KeepPace(val plan: PlanProgress) : NextStep

    /** More than a week's pace is needed this week to be back on pace. */
    data class CatchUp(val plan: PlanProgress) : NextStep

    /** So far behind that another pace may suit better. */
    data class AdjustPace(val plan: PlanProgress) : NextStep

    data class Ahead(val plan: PlanProgress) : NextStep
}

object NextSteps {

    /** What counts as a few chapters left, or a small part of a goal left. */
    const val FINISH_SOON_CHAPTERS = 3
    private const val GOAL_WITHIN_REACH_SHARE = 0.25

    /** Beyond this many weeks' pace behind, suggest another pace rather than catching up. */
    private const val CATCH_UP_WEEKS = 2

    const val DEFAULT_LIMIT = 3

    fun of(goals: List<GoalProgress>, plans: List<PlanProgress>, limit: Int = DEFAULT_LIMIT): List<NextStep> {
        val steps = ArrayList<NextStep>()
        goals
            .filter { !it.met && it.value > 0 && it.remaining <= it.goal.target * GOAL_WITHIN_REACH_SHARE }
            .sortedBy { !it.goal.isPrimary }
            .mapTo(steps, NextStep::GoalWithinReach)
        val active = plans.filter { !it.plan.paused && it.remaining > 0 }
        active.filter { it.remaining <= FINISH_SOON_CHAPTERS }.mapTo(steps, NextStep::FinishSoon)
        active.filter { it.remaining > FINISH_SOON_CHAPTERS }.forEach { plan ->
            val pace = plan.plan.pacePerWeek
            when {
                plan.neededThisWeek == 0 && plan.aheadBy > 0 -> steps += NextStep.Ahead(plan)
                plan.neededThisWeek == 0 -> Unit
                plan.neededThisWeek <= pace -> steps += NextStep.KeepPace(plan)
                plan.neededThisWeek <= pace * CATCH_UP_WEEKS -> steps += NextStep.CatchUp(plan)
                else -> steps += NextStep.AdjustPace(plan)
            }
        }
        return steps.take(limit)
    }
}
