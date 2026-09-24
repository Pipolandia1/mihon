package tachiyomi.domain.readingstats.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.readingstats.goals.GoalMetric
import tachiyomi.domain.readingstats.goals.GoalPeriod
import tachiyomi.domain.readingstats.goals.ReadingGoal
import tachiyomi.domain.readingstats.plans.CollectionItem
import tachiyomi.domain.readingstats.plans.CollectionKind
import tachiyomi.domain.readingstats.plans.PlanChapter
import tachiyomi.domain.readingstats.plans.ReadingPlan

/** Goals and reading plans. Nothing here reads or writes sessions. */
interface ReadingPlanRepository {

    fun subscribeGoals(): Flow<List<ReadingGoal>>

    /** Sets the goal for its metric and period; a primary goal makes every other one secondary. */
    suspend fun setGoal(goal: ReadingGoal, now: Long)

    /** Removes a goal; if it was the primary one, the first remaining goal becomes primary. */
    suspend fun removeGoal(metric: GoalMetric, period: GoalPeriod)

    fun subscribePlans(): Flow<List<ReadingPlan>>

    suspend fun getPlan(planId: Long): ReadingPlan?

    /** The chapters a collection covers, in its order; a chapter held twice appears twice. */
    fun subscribeCollectionChapters(collectionId: Long): Flow<List<PlanChapter>>

    suspend fun getItems(collectionId: Long): List<CollectionItem>

    /** Creates a plan over a new collection of [items]; returns the plan's id. */
    suspend fun createPlan(
        name: String,
        kind: CollectionKind,
        items: List<CollectionItem>,
        pacePerWeek: Int,
        now: Long,
        today: LocalDate,
    ): Long

    /** Changes a plan's name, pace, pause and items. Its start stays. */
    suspend fun updatePlan(plan: ReadingPlan, items: List<CollectionItem>)

    /** Deletes the plan and its collection. Reading is not touched. */
    suspend fun deletePlan(plan: ReadingPlan)

    /** Library entries whose title contains [query], for building a plan. */
    suspend fun searchLibrary(query: String): List<PlannableEntry>

    // region Backup

    /** Every plan with its items. */
    suspend fun exportPlans(): List<Pair<ReadingPlan, List<CollectionItem>>>

    /** Replaces the goals of the same metric and period. */
    suspend fun importGoals(goals: List<ReadingGoal>)

    /** Adds plans not already present; items are matched to entries and chapters by source and URL. */
    suspend fun importPlans(plans: List<Pair<ReadingPlan, List<CollectionItem>>>)

    // endregion
}

data class PlannableEntry(
    val mangaId: Long,
    val sourceId: Long,
    val url: String,
    val title: String,
    val cover: MangaCover,
)
