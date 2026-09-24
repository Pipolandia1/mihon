package tachiyomi.domain.readingstats.plans

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import tachiyomi.domain.readingstats.goals.GoalMetric
import tachiyomi.domain.readingstats.goals.GoalPeriod
import tachiyomi.domain.readingstats.goals.GoalProgress
import tachiyomi.domain.readingstats.goals.ReadingGoal
import tachiyomi.domain.readingstats.metrics.ReadingPeriod

class PlanRulesTest {

    // Weeks start on Sunday: 13-19, 20-26.
    private val week = ReadingPeriod(LocalDate(2026, 9, 20), LocalDate(2026, 9, 26))
    private val today = LocalDate(2026, 9, 23)
    private val recording = LocalDate(2026, 8, 1)

    private fun plan(pace: Int = 5, startedOn: LocalDate = LocalDate(2026, 9, 13), paused: Boolean = false) =
        ReadingPlan(1, "p", 1, "c", CollectionKind.LIST, "Run", pace, 0, startedOn, paused)

    private fun chapter(id: Long, manga: Long = 1, finishedOn: LocalDate? = null, read: Boolean = finishedOn != null) =
        PlanChapter(id, manga, read, finishedOn)

    @Test
    fun `a run over two series counts each chapter once`() {
        // Series 1 whole (chapters 1-3) and chapter 2 listed again, plus two issues of series 2.
        val chapters = listOf(
            chapter(1, finishedOn = LocalDate(2026, 9, 21)),
            chapter(2),
            chapter(3),
            chapter(2),
            chapter(10, manga = 2, finishedOn = LocalDate(2026, 9, 22)),
            chapter(11, manga = 2),
        )

        val progress = PlanRules.progress(plan(), chapters, today, week, recording)

        progress.total shouldBe 5
        progress.done shouldBe 2
        progress.remaining shouldBe 3
        progress.next?.chapterId shouldBe 2
    }

    @Test
    fun `pace counts only what was finished since the plan started`() {
        val chapters = listOf(
            chapter(1, finishedOn = LocalDate(2026, 9, 1)),
            chapter(2, finishedOn = LocalDate(2026, 9, 14)),
            chapter(3, finishedOn = LocalDate(2026, 9, 21)),
        ) + (4L..20L).map { chapter(it) }

        val progress = PlanRules.progress(plan(pace = 5), chapters, today, week, recording)

        progress.doneSinceStart shouldBe 2
        progress.doneThisWeek shouldBe 1
        // 14 days to the end of this week at 5 a week: 10 expected, 2 done.
        progress.neededThisWeek shouldBe 8
        // 11 days to today: 8 expected.
        progress.aheadBy shouldBe -6
        progress.weeksAtPace shouldBe 4
    }

    @Test
    fun `a chapter read with no known date counts as done but not towards the pace`() {
        val chapters = listOf(chapter(1, read = true), chapter(2))

        val progress = PlanRules.progress(plan(), chapters, today, week, recording)

        progress.done shouldBe 1
        progress.doneSinceStart shouldBe 0
    }

    @Test
    fun `the forecast is unknown without two weeks of recording`() {
        val chapters = listOf(
            chapter(1, finishedOn = LocalDate(2026, 9, 21)),
            chapter(2, finishedOn = LocalDate(2026, 9, 22)),
            chapter(3),
        )

        PlanRules.progress(plan(), chapters, today, week, recordingStartedOn = LocalDate(2026, 9, 15))
            .forecast shouldBe Forecast.Unknown
    }

    @Test
    fun `the forecast is unknown without enough of the plan read recently`() {
        val chapters = listOf(chapter(1, finishedOn = LocalDate(2026, 9, 21)), chapter(2))

        PlanRules.progress(plan(), chapters, today, week, recording).forecast shouldBe Forecast.Unknown
    }

    @Test
    fun `the forecast follows the recent pace`() {
        // 4 chapters in the last 28 days: one a week; 3 left, so three weeks.
        val chapters = listOf(
            chapter(1, finishedOn = LocalDate(2026, 8, 30)),
            chapter(2, finishedOn = LocalDate(2026, 9, 6)),
            chapter(3, finishedOn = LocalDate(2026, 9, 13)),
            chapter(4, finishedOn = LocalDate(2026, 9, 20)),
            chapter(5),
            chapter(6),
            chapter(7),
        )

        val forecast = PlanRules.progress(plan(), chapters, today, week, recording).forecast

        forecast.shouldBeInstanceOf<Forecast.On>()
        forecast.chaptersPerWeek shouldBe 1.0
        forecast.date shouldBe LocalDate(2026, 10, 14)
    }

    @Test
    fun `a finished plan says so`() {
        val chapters = listOf(chapter(1, finishedOn = LocalDate(2026, 9, 21)))

        val progress = PlanRules.progress(plan(), chapters, today, week, recording)

        progress.forecast shouldBe Forecast.Finished
        progress.weeksAtPace.shouldBeNull()
        progress.next.shouldBeNull()
    }

    @Test
    fun `next steps suggest keeping the pace, catching up, or another pace, never blame`() {
        val many = (1L..30L).map { chapter(it) }
        val onPace = PlanRules.progress(
            plan(pace = 5, startedOn = LocalDate(2026, 9, 20)),
            many,
            today,
            week,
            recording,
        )
        val behind = PlanRules.progress(
            plan(pace = 5, startedOn = LocalDate(2026, 9, 13)),
            many,
            today,
            week,
            recording,
        )
        val farBehind = PlanRules.progress(
            plan(pace = 2, startedOn = LocalDate(2026, 8, 30)),
            many,
            today,
            week,
            recording,
        )

        NextSteps.of(emptyList(), listOf(onPace)).single().shouldBeInstanceOf<NextStep.KeepPace>()
        NextSteps.of(emptyList(), listOf(behind)).single().shouldBeInstanceOf<NextStep.CatchUp>()
        NextSteps.of(emptyList(), listOf(farBehind)).single().shouldBeInstanceOf<NextStep.AdjustPace>()
    }

    @Test
    fun `next steps mention a plan about to end and skip paused ones`() {
        val almost = listOf(chapter(1, finishedOn = LocalDate(2026, 9, 21)), chapter(2), chapter(3))

        val finishing = PlanRules.progress(plan(), almost, today, week, recording)
        val paused = PlanRules.progress(plan(paused = true), almost, today, week, recording)

        NextSteps.of(emptyList(), listOf(finishing)).single().shouldBeInstanceOf<NextStep.FinishSoon>()
        NextSteps.of(emptyList(), listOf(paused)) shouldBe emptyList()
    }

    @Test
    fun `a goal close to done comes first`() {
        val goal = ReadingGoal(GoalMetric.PAGES, GoalPeriod.DAY, target = 20, isPrimary = true)
        val close = GoalProgress(goal, value = 16, daysMet = 0, daysSoFar = 1)
        val far = GoalProgress(goal, value = 4, daysMet = 0, daysSoFar = 1)
        val started = PlanRules.progress(plan(startedOn = today), (1L..30L).map { chapter(it) }, today, week, recording)

        NextSteps.of(listOf(close), listOf(started)).first() shouldBe NextStep.GoalWithinReach(close)
        NextSteps.of(listOf(far), emptyList()) shouldBe emptyList()
    }
}
