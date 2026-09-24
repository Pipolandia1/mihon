package tachiyomi.domain.readingstats.goals

import kotlinx.datetime.LocalDate
import tachiyomi.domain.readingstats.metrics.ReadingPeriod
import tachiyomi.domain.readingstats.model.ReadingDayStats
import kotlin.time.Duration.Companion.minutes

/** What a goal counts. Each entry's [id] is what the database stores. */
enum class GoalMetric(val id: Long) {
    /** Pages reached for the first time. */
    PAGES(0),

    /** Chapters finished in the reader; chapters marked read by hand do not count. */
    CHAPTERS(1),

    /** Whole minutes of active reading. */
    MINUTES(2),
    ;

    companion object {
        fun of(id: Long): GoalMetric? = entries.firstOrNull { it.id == id }
    }
}

enum class GoalPeriod(val id: Long) {
    DAY(0),
    WEEK(1),
    ;

    companion object {
        fun of(id: Long): GoalPeriod? = entries.firstOrNull { it.id == id }
    }
}

/** A target for one metric over one period. There is at most one goal per metric and period. */
data class ReadingGoal(
    val metric: GoalMetric,
    val period: GoalPeriod,
    val target: Int,
    /** The one goal shown first and largest. */
    val isPrimary: Boolean,
)

data class GoalProgress(
    val goal: ReadingGoal,
    val value: Long,
    /** For a daily goal: the recorded days of this week, up to today, on which it was met. */
    val daysMet: Int?,
    /** For a daily goal: the recorded days of this week up to today. */
    val daysSoFar: Int?,
) {
    val met: Boolean get() = value >= goal.target
    val remaining: Long get() = (goal.target - value).coerceAtLeast(0)
}

object GoalRules {

    /** What [metric] adds up to over [days], by the same definitions as every other card. */
    fun valueOf(metric: GoalMetric, days: Collection<ReadingDayStats>): Long = when (metric) {
        GoalMetric.PAGES -> days.sumOf { it.pagesNew.toLong() }
        GoalMetric.CHAPTERS -> days.sumOf { it.completedInReader.toLong() }
        GoalMetric.MINUTES -> days.sumOf { it.activeMs } / 1.minutes.inWholeMilliseconds
    }

    /**
     * Progress of [goal] today or this [week], from the recorded [days]. Days before
     * [recordingStartedOn] were not measured, so they count neither as met nor as missed.
     */
    fun progress(
        goal: ReadingGoal,
        today: LocalDate,
        week: ReadingPeriod,
        days: List<ReadingDayStats>,
        recordingStartedOn: LocalDate,
    ): GoalProgress {
        val byDate = days.associateBy { it.date }
        return when (goal.period) {
            GoalPeriod.DAY -> {
                val soFar = week.dates().filter { it in recordingStartedOn..today }
                GoalProgress(
                    goal = goal,
                    value = valueOf(goal.metric, listOfNotNull(byDate[today])),
                    daysMet = soFar.count { date ->
                        valueOf(goal.metric, listOfNotNull(byDate[date])) >= goal.target
                    },
                    daysSoFar = soFar.size,
                )
            }
            GoalPeriod.WEEK -> GoalProgress(
                goal = goal,
                value = valueOf(goal.metric, days.filter { it.date in week }),
                daysMet = null,
                daysSoFar = null,
            )
        }
    }

    /** The primary goal first, then daily before weekly goals, in metric order. */
    fun ordered(goals: List<ReadingGoal>): List<ReadingGoal> =
        goals.sortedWith(compareBy({ !it.isPrimary }, { it.period.id }, { it.metric.id }))
}
