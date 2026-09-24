package tachiyomi.domain.readingstats.goals

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import tachiyomi.domain.readingstats.metrics.ReadingPeriod
import tachiyomi.domain.readingstats.model.ReadingDayStats

class GoalRulesTest {

    private val recording = LocalDate(2026, 1, 1)

    private val week = ReadingPeriod(LocalDate(2026, 9, 20), LocalDate(2026, 9, 26))
    private val today = LocalDate(2026, 9, 23)
    private val days = listOf(
        ReadingDayStats(LocalDate(2026, 9, 19), activeMs = 99 * 60_000L, pagesNew = 99),
        ReadingDayStats(LocalDate(2026, 9, 20), activeMs = 25 * 60_000L, pagesNew = 30, completedInReader = 1),
        ReadingDayStats(LocalDate(2026, 9, 21), activeMs = 5 * 60_000L, pagesNew = 5, completedManually = 3),
        ReadingDayStats(LocalDate(2026, 9, 23), activeMs = 19 * 60_000L + 59_999, pagesNew = 20),
    )

    @Test
    fun `a daily goal counts today, and says on which days of the week it was met`() {
        val goal = ReadingGoal(GoalMetric.PAGES, GoalPeriod.DAY, target = 20, isPrimary = true)

        val progress = GoalRules.progress(goal, today, week, days, recording)

        progress.value shouldBe 20
        progress.met shouldBe true
        progress.daysMet shouldBe 2
        progress.daysSoFar shouldBe 4
    }

    @Test
    fun `days before recording began are neither met nor missed`() {
        val goal = ReadingGoal(GoalMetric.PAGES, GoalPeriod.DAY, target = 20, isPrimary = true)

        val progress = GoalRules.progress(goal, today, week, days, recordingStartedOn = LocalDate(2026, 9, 22))

        progress.daysMet shouldBe 1
        progress.daysSoFar shouldBe 2
    }

    @Test
    fun `a weekly goal counts the week only`() {
        val goal = ReadingGoal(GoalMetric.PAGES, GoalPeriod.WEEK, target = 100, isPrimary = false)

        val progress = GoalRules.progress(goal, today, week, days, recording)

        progress.value shouldBe 55
        progress.remaining shouldBe 45
        progress.daysMet.shouldBeNull()
    }

    @Test
    fun `minutes are whole minutes of the summed time`() {
        val goal = ReadingGoal(GoalMetric.MINUTES, GoalPeriod.DAY, target = 20, isPrimary = false)

        val progress = GoalRules.progress(goal, today, week, days, recording)

        progress.value shouldBe 19
        progress.met shouldBe false
    }

    @Test
    fun `chapters marked read by hand do not count towards a goal`() {
        val goal = ReadingGoal(GoalMetric.CHAPTERS, GoalPeriod.WEEK, target = 2, isPrimary = false)

        GoalRules.progress(goal, today, week, days, recording).value shouldBe 1
    }

    @Test
    fun `the primary goal comes first, then daily before weekly`() {
        val goals = listOf(
            ReadingGoal(GoalMetric.MINUTES, GoalPeriod.WEEK, 1, isPrimary = false),
            ReadingGoal(GoalMetric.CHAPTERS, GoalPeriod.WEEK, 1, isPrimary = true),
            ReadingGoal(GoalMetric.PAGES, GoalPeriod.WEEK, 1, isPrimary = false),
            ReadingGoal(GoalMetric.MINUTES, GoalPeriod.DAY, 1, isPrimary = false),
        )

        GoalRules.ordered(goals).map { it.metric to it.period } shouldBe listOf(
            GoalMetric.CHAPTERS to GoalPeriod.WEEK,
            GoalMetric.MINUTES to GoalPeriod.DAY,
            GoalMetric.PAGES to GoalPeriod.WEEK,
            GoalMetric.MINUTES to GoalPeriod.WEEK,
        )
    }
}
