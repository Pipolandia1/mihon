package tachiyomi.data.readingstats

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import tachiyomi.data.Database
import tachiyomi.data.GetPlans
import tachiyomi.data.subscribeToList
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.readingstats.goals.GoalMetric
import tachiyomi.domain.readingstats.goals.GoalPeriod
import tachiyomi.domain.readingstats.goals.ReadingGoal
import tachiyomi.domain.readingstats.plans.CollectionItem
import tachiyomi.domain.readingstats.plans.CollectionKind
import tachiyomi.domain.readingstats.plans.PlanChapter
import tachiyomi.domain.readingstats.plans.ReadingPlan
import tachiyomi.domain.readingstats.repository.PlannableEntry
import tachiyomi.domain.readingstats.repository.ReadingPlanRepository
import java.util.UUID

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ReadingPlanRepositoryImpl(
    private val database: Database,
) : ReadingPlanRepository {

    private val queries get() = database.reading_plansQueries

    override fun subscribeGoals(): Flow<List<ReadingGoal>> {
        return queries.getGoals().subscribeToList().map { rows ->
            rows.mapNotNull { row ->
                ReadingGoal(
                    metric = GoalMetric.of(row.metric) ?: return@mapNotNull null,
                    period = GoalPeriod.of(row.period) ?: return@mapNotNull null,
                    target = row.target.toInt(),
                    isPrimary = row.is_primary,
                )
            }
        }
    }

    override suspend fun setGoal(goal: ReadingGoal, now: Long) {
        database.transaction {
            val others = queries.getGoals().awaitAsList()
                .filterNot { it.metric == goal.metric.id && it.period == goal.period.id }
            // The first goal is the primary one; a new primary goal takes over from the old.
            val primary = goal.isPrimary || others.none { it.is_primary }
            if (primary) queries.clearPrimaryGoal()
            queries.upsertGoal(goal.metric.id, goal.period.id, goal.target.toLong(), primary, now)
        }
    }

    override suspend fun removeGoal(metric: GoalMetric, period: GoalPeriod) {
        database.transaction {
            queries.deleteGoal(metric.id, period.id)
            val rest = queries.getGoals().awaitAsList()
            val next = rest.sortedWith(compareBy({ it.period }, { it.metric })).firstOrNull()
            if (next != null && rest.none { it.is_primary }) {
                queries.upsertGoal(next.metric, next.period, next.target, true, next.updated_at)
            }
        }
    }

    override fun subscribePlans(): Flow<List<ReadingPlan>> {
        return queries.getPlans().subscribeToList().map { rows -> rows.map(::mapPlan) }
    }

    override suspend fun getPlan(planId: Long): ReadingPlan? {
        return queries.getPlans().awaitAsList().firstOrNull { it._id == planId }?.let(::mapPlan)
    }

    override fun subscribeCollectionChapters(collectionId: Long): Flow<List<PlanChapter>> {
        return queries.collectionChapters(collectionId).subscribeToList().map { rows ->
            rows.map {
                PlanChapter(
                    chapterId = it._id,
                    mangaId = it.manga_id,
                    read = it.read,
                    finishedOn = (it.read_on ?: it.marked_on)?.let(LocalDate::parse),
                )
            }
        }
    }

    override suspend fun getItems(collectionId: Long): List<CollectionItem> {
        return queries.getItemsOf(collectionId).awaitAsList().map {
            CollectionItem(
                mangaId = it.manga_id,
                chapterId = it.chapter_id,
                sourceId = it.source_id,
                mangaUrl = it.manga_url,
                chapterUrl = it.chapter_url,
                label = it.label,
            )
        }
    }

    override suspend fun createPlan(
        name: String,
        kind: CollectionKind,
        items: List<CollectionItem>,
        pacePerWeek: Int,
        now: Long,
        today: LocalDate,
    ): Long {
        return database.transactionWithResult {
            val collectionId = insertCollection(UUID.randomUUID().toString(), kind, name, now)
            insertItems(collectionId, items)
            val uuid = UUID.randomUUID().toString()
            queries.insertPlan(uuid, collectionId, pacePerWeek.toLong(), now, today.toString(), false)
            queries.getPlanId(uuid).awaitAsOne()
        }
    }

    override suspend fun updatePlan(plan: ReadingPlan, items: List<CollectionItem>) {
        database.transaction {
            queries.renameCollection(plan.name, plan.collectionId)
            queries.updatePlan(plan.pacePerWeek.toLong(), plan.paused, plan.id)
            queries.deleteItemsOf(plan.collectionId)
            insertItems(plan.collectionId, items)
        }
    }

    override suspend fun deletePlan(plan: ReadingPlan) {
        queries.deleteCollection(plan.collectionId)
    }

    override suspend fun searchLibrary(query: String): List<PlannableEntry> {
        return queries.libraryEntries(query).awaitAsList().map {
            PlannableEntry(
                mangaId = it._id,
                sourceId = it.source,
                url = it.url,
                title = it.title,
                cover = MangaCover(it._id, it.source, it.favorite, it.thumbnail_url, it.cover_last_modified),
            )
        }
    }

    override suspend fun exportPlans(): List<Pair<ReadingPlan, List<CollectionItem>>> {
        return queries.getPlans().awaitAsList().map(::mapPlan).map { it to getItems(it.collectionId) }
    }

    override suspend fun importGoals(goals: List<ReadingGoal>) {
        if (goals.isEmpty()) return
        database.transaction {
            if (goals.any { it.isPrimary }) queries.clearPrimaryGoal()
            goals.forEach { queries.upsertGoal(it.metric.id, it.period.id, it.target.toLong(), it.isPrimary, 0) }
        }
    }

    override suspend fun importPlans(plans: List<Pair<ReadingPlan, List<CollectionItem>>>) {
        database.transaction {
            plans.forEach { (plan, items) ->
                if (queries.getPlanId(plan.uuid).awaitAsOneOrNull() != null) return@forEach
                val collectionId = queries.getCollectionId(plan.collectionUuid).awaitAsOneOrNull()
                    ?: insertCollection(plan.collectionUuid, plan.kind, plan.name, plan.startedAt).also {
                        insertItems(it, items.map { item -> resolve(item) })
                    }
                queries.insertPlan(
                    uuid = plan.uuid,
                    collectionId = collectionId,
                    pacePerWeek = plan.pacePerWeek.toLong(),
                    startedAt = plan.startedAt,
                    startedOn = plan.startedOn.toString(),
                    paused = plan.paused,
                )
            }
        }
    }

    private suspend fun insertCollection(uuid: String, kind: CollectionKind, name: String, createdAt: Long): Long {
        queries.insertCollection(uuid, kind.id, name, createdAt)
        return queries.getCollectionId(uuid).awaitAsOne()
    }

    private suspend fun insertItems(collectionId: Long, items: List<CollectionItem>) {
        items.forEachIndexed { position, item ->
            queries.insertItem(
                collectionId = collectionId,
                position = position.toLong(),
                mangaId = item.mangaId,
                chapterId = item.chapterId,
                sourceId = item.sourceId,
                mangaUrl = item.mangaUrl,
                chapterUrl = item.chapterUrl,
                label = item.label,
            )
        }
    }

    /** The ids on this device of the entry and chapter an item names by source and URL. */
    private suspend fun resolve(item: CollectionItem): CollectionItem {
        val mangaId = database.mangasQueries.getMangaByUrlAndSource(item.mangaUrl, item.sourceId)
            .awaitAsOneOrNull()?._id
        val chapterUrl = item.chapterUrl
        val chapterId = if (mangaId != null && chapterUrl != null) {
            database.chaptersQueries.getChapterByUrlAndMangaId(chapterUrl, mangaId).awaitAsOneOrNull()?._id
        } else {
            null
        }
        return item.copy(mangaId = mangaId, chapterId = chapterId)
    }

    private fun mapPlan(row: GetPlans) = ReadingPlan(
        id = row._id,
        uuid = row.uuid,
        collectionId = row.collection_id,
        collectionUuid = row.collection_uuid,
        kind = CollectionKind.of(row.kind),
        name = row.name,
        pacePerWeek = row.pace_per_week.toInt(),
        startedAt = row.started_at,
        startedOn = LocalDate.parse(row.started_on),
        paused = row.paused,
    )
}
