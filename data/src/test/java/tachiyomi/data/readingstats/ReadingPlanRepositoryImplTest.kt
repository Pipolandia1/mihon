package tachiyomi.data.readingstats

import app.cash.sqldelight.async.coroutines.synchronous
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.domain.readingstats.goals.GoalMetric
import tachiyomi.domain.readingstats.goals.GoalPeriod
import tachiyomi.domain.readingstats.goals.ReadingGoal
import tachiyomi.domain.readingstats.model.ChapterKey
import tachiyomi.domain.readingstats.model.ChapterProgress
import tachiyomi.domain.readingstats.model.DayTotals
import tachiyomi.domain.readingstats.model.ReadingOrigin
import tachiyomi.domain.readingstats.model.SessionSnapshot
import tachiyomi.domain.readingstats.model.SessionState
import tachiyomi.domain.readingstats.model.SessionWrite
import tachiyomi.domain.readingstats.plans.CollectionItem
import tachiyomi.domain.readingstats.plans.CollectionKind
import tachiyomi.domain.readingstats.plans.CollectionRules
import tachiyomi.domain.readingstats.plans.PlanRules

class ReadingPlanRepositoryImplTest {

    private val db = TestDatabase()
    private val plans = ReadingPlanRepositoryImpl(db.database)
    private val stats = ReadingStatsRepositoryImpl(db.database)

    private fun goal(metric: GoalMetric, period: GoalPeriod, target: Int, primary: Boolean = false) =
        ReadingGoal(metric, period, target, primary)

    @Test
    fun `the first goal is primary, a new primary replaces it, removing it promotes another`() = runTest {
        plans.setGoal(goal(GoalMetric.PAGES, GoalPeriod.DAY, 20), now = 1)
        plans.setGoal(goal(GoalMetric.MINUTES, GoalPeriod.WEEK, 120), now = 2)
        plans.subscribeGoals().first().single { it.isPrimary }.metric shouldBe GoalMetric.PAGES

        plans.setGoal(goal(GoalMetric.MINUTES, GoalPeriod.WEEK, 150, primary = true), now = 3)
        val goals = plans.subscribeGoals().first()
        goals.single { it.isPrimary }.metric shouldBe GoalMetric.MINUTES
        goals.single { it.metric == GoalMetric.MINUTES }.target shouldBe 150
        goals.size shouldBe 2

        plans.removeGoal(GoalMetric.MINUTES, GoalPeriod.WEEK)
        plans.subscribeGoals().first().single().isPrimary shouldBe true
    }

    @Test
    fun `changing a goal does not rewrite any reading`() = runTest {
        db.insertManga(1)
        db.insertChapter(10, mangaId = 1)
        stats.saveSession(session("a", chapterId = 10, activeMs = 90_000, pagesNew = 7))
        val before = stats.subscribeDays(DAY, DAY).first()

        plans.setGoal(goal(GoalMetric.PAGES, GoalPeriod.DAY, 5), now = 1)
        plans.setGoal(goal(GoalMetric.PAGES, GoalPeriod.DAY, 50), now = 2)
        plans.removeGoal(GoalMetric.PAGES, GoalPeriod.DAY)

        stats.subscribeDays(DAY, DAY).first() shouldBe before
        stats.exportSessions().single().activeMs shouldBe 90_000
    }

    @Test
    fun `a plan over two series counts each chapter once and hides hidden scanlators`() = runTest {
        db.insertManga(1)
        db.insertManga(2)
        db.insertChapter(10, mangaId = 1)
        db.insertChapter(11, mangaId = 1)
        db.insertChapter(12, mangaId = 1, scanlator = "Hidden")
        db.exec("INSERT INTO excluded_scanlators(manga_id, scanlator) VALUES (1, 'Hidden')")
        db.insertChapter(20, mangaId = 2)
        db.insertChapter(21, mangaId = 2)
        val items = listOf(
            wholeEntry(1),
            chapterItem(mangaId = 2, chapterId = 20),
            chapterItem(mangaId = 1, chapterId = 11),
        )

        val planId = plans.createPlan("Run", CollectionKind.LIST, items, pacePerWeek = 3, now = 5, today = DAY)

        val plan = plans.getPlan(planId).shouldNotBeNull()
        val chapters = plans.subscribeCollectionChapters(plan.collectionId).first()
        chapters.map { it.chapterId } shouldBe listOf(10L, 11L, 20L, 11L)
        PlanRules.distinct(chapters).map { it.chapterId } shouldBe listOf(10L, 11L, 20L)
    }

    @Test
    fun `a whole entry takes in the chapters its source adds later`() = runTest {
        db.insertManga(1)
        db.insertChapter(10, mangaId = 1)
        val planId = plans.createPlan("Series", CollectionKind.ENTRY, listOf(wholeEntry(1)), 2, now = 5, today = DAY)
        val collectionId = plans.getPlan(planId).shouldNotBeNull().collectionId

        db.insertChapter(11, mangaId = 1)

        plans.subscribeCollectionChapters(collectionId).first().map { it.chapterId } shouldBe listOf(10L, 11L)
    }

    @Test
    fun `a chapter is finished on its first completion in the reader, or when it was marked read`() = runTest {
        db.insertManga(1)
        db.insertChapter(10, mangaId = 1, read = true)
        db.insertChapter(11, mangaId = 1, read = true)
        db.insertChapter(12, mangaId = 1)
        stats.saveSession(session("a", chapterId = 10, completedOn = LocalDate(2026, 9, 20)))
        stats.markCompletedManually(listOf(11L), at = 5, on = LocalDate(2026, 9, 21))
        val planId = plans.createPlan("Series", CollectionKind.ENTRY, listOf(wholeEntry(1)), 2, now = 5, today = DAY)

        val chapters = plans.subscribeCollectionChapters(plans.getPlan(planId)!!.collectionId).first()

        chapters.map { it.finishedOn } shouldBe listOf(LocalDate(2026, 9, 20), LocalDate(2026, 9, 21), null)
        chapters.map { it.read } shouldBe listOf(true, true, false)
    }

    @Test
    fun `a plan is edited and deleted without touching reading`() = runTest {
        db.insertManga(1)
        db.insertManga(2)
        db.insertChapter(10, mangaId = 1)
        db.insertChapter(20, mangaId = 2)
        stats.saveSession(session("a", chapterId = 10))
        val planId = plans.createPlan("Run", CollectionKind.LIST, listOf(wholeEntry(1)), 3, now = 5, today = DAY)
        val plan = plans.getPlan(planId).shouldNotBeNull()

        plans.updatePlan(plan.copy(name = "Run 2", pacePerWeek = 7, paused = true), listOf(wholeEntry(2)))

        val edited = plans.getPlan(planId).shouldNotBeNull()
        edited.name shouldBe "Run 2"
        edited.pacePerWeek shouldBe 7
        edited.paused shouldBe true
        edited.startedOn shouldBe DAY
        plans.subscribeCollectionChapters(edited.collectionId).first().map { it.chapterId } shouldBe listOf(20L)

        plans.deletePlan(edited)

        plans.subscribePlans().first().shouldBeEmpty()
        plans.getItems(edited.collectionId).shouldBeEmpty()
        stats.exportSessions().size shouldBe 1
    }

    @Test
    fun `plans restore once, onto the entries and chapters of another device`() = runTest {
        db.insertManga(1)
        db.insertChapter(10, mangaId = 1)
        plans.createPlan("Run", CollectionKind.LIST, listOf(chapterItem(1, 10)), 3, now = 5, today = DAY)
        plans.setGoal(goal(GoalMetric.PAGES, GoalPeriod.WEEK, 100, primary = true), now = 1)
        val exported = plans.exportPlans()
        val goals = plans.subscribeGoals().first()

        val other = TestDatabase()
        other.insertManga(7, url = "/manga/1")
        other.insertChapter(70, mangaId = 7, url = "/chapter/10")
        val target = ReadingPlanRepositoryImpl(other.database)
        repeat(2) {
            target.importPlans(exported)
            target.importGoals(goals)
        }

        val restored = target.subscribePlans().first().single()
        restored.name shouldBe "Run"
        restored.startedOn shouldBe DAY
        target.subscribeCollectionChapters(restored.collectionId).first().single().chapterId shouldBe 70
        target.subscribeGoals().first() shouldBe goals
    }

    @Test
    fun `migrations 16 and 17 add plans, standalone collections and comic metadata`() = runTest {
        listOf("comic_metadata", "reading_plans", "reading_collection_items", "reading_collections", "reading_goals")
            .forEach { db.exec("DROP TABLE $it") }

        Database.Schema.synchronous().migrate(db.driver, 16, 18)

        plans.setGoal(goal(GoalMetric.CHAPTERS, GoalPeriod.WEEK, 5), now = 1)
        plans.subscribeGoals().first().single().target shouldBe 5
        db.insertManga(1)
        plans.createCollection("Arc", CollectionKind.ARC, listOf(wholeEntry(1)), now = 1)
        plans.subscribeCollections().first().single().name shouldBe "Arc"
    }

    @Test
    fun `within an item, annuals and collected editions come after the issues`() = runTest {
        db.insertManga(1)
        db.insertChapter(10, mangaId = 1, name = "TPB_(Part_1)", number = 1.0)
        db.insertChapter(11, mangaId = 1, name = "#Annual 2025", number = 2025.0)
        db.insertChapter(12, mangaId = 1, name = "#2", number = 2.0)
        db.insertChapter(13, mangaId = 1, name = "Issue #1", number = 1.0)
        val planId = plans.createPlan("Series", CollectionKind.ENTRY, listOf(wholeEntry(1)), 2, now = 5, today = DAY)

        val chapters = plans.subscribeCollectionChapters(plans.getPlan(planId)!!.collectionId).first()

        chapters.map { it.chapterId } shouldBe listOf(13L, 12L, 11L, 10L)
    }

    @Test
    fun `a standalone collection outlives its plans, and deleting it ends them`() = runTest {
        db.insertManga(1)
        db.insertChapter(10, mangaId = 1)
        val collectionId = plans.createCollection("Arc", CollectionKind.ARC, listOf(chapterItem(1, 10)), now = 1)
        val collection = plans.getCollection(collectionId).shouldNotBeNull()
        val planId = plans.createPlanFor(collection, pacePerWeek = 2, now = 5, today = DAY)
        val plan = plans.getPlan(planId).shouldNotBeNull()
        plan.standaloneCollection shouldBe true
        plan.name shouldBe "Arc"

        // Editing the plan leaves the collection's items alone.
        plans.updatePlan(plan.copy(pacePerWeek = 4), emptyList())
        plans.getItems(collectionId).size shouldBe 1
        plans.getPlan(planId)!!.pacePerWeek shouldBe 4

        plans.deletePlan(plan)
        plans.subscribePlans().first().shouldBeEmpty()
        plans.getCollection(collectionId).shouldNotBeNull()

        plans.createPlanFor(collection, pacePerWeek = 2, now = 6, today = DAY)
        plans.deleteCollection(collection)
        plans.subscribePlans().first().shouldBeEmpty()
        plans.subscribeCollections().first().shouldBeEmpty()
    }

    @Test
    fun `a chapter in two collections is read once`() = runTest {
        db.insertManga(1)
        db.insertChapter(10, mangaId = 1, read = true)
        stats.saveSession(session("a", chapterId = 10, completedOn = DAY))
        val first = plans.createCollection("Run", CollectionKind.RUN, listOf(chapterItem(1, 10)), now = 1)
        val second = plans.createCollection("Event", CollectionKind.EVENT, listOf(chapterItem(1, 10)), now = 2)

        listOf(first, second).forEach { id ->
            val progress = CollectionRules.progress(
                plans.getCollection(id)!!,
                plans.subscribeCollectionChapters(id).first(),
            )
            progress.done shouldBe 1
            progress.total shouldBe 1
        }
        stats.exportSessions().size shouldBe 1
    }

    @Test
    fun `standalone collections restore once`() = runTest {
        db.insertManga(1)
        db.insertChapter(10, mangaId = 1)
        plans.createCollection("Arc", CollectionKind.READING_ORDER, listOf(chapterItem(1, 10)), now = 1)
        val exported = plans.exportCollections()

        val other = TestDatabase()
        other.insertManga(7, url = "/manga/1")
        other.insertChapter(70, mangaId = 7, url = "/chapter/10")
        val target = ReadingPlanRepositoryImpl(other.database)
        repeat(2) { target.importCollections(exported) }

        val restored = target.subscribeCollections().first().single()
        restored.kind shouldBe CollectionKind.READING_ORDER
        target.subscribeCollectionChapters(restored.id).first().single().chapterId shouldBe 70
    }

    private fun wholeEntry(mangaId: Long) =
        CollectionItem(mangaId, null, 1, "/manga/$mangaId", null, "Title $mangaId")

    private fun chapterItem(mangaId: Long, chapterId: Long) =
        CollectionItem(mangaId, chapterId, 1, "/manga/$mangaId", "/chapter/$chapterId", "Chapter $chapterId")

    private fun session(
        uuid: String,
        chapterId: Long,
        activeMs: Long = 1_000,
        pagesNew: Int = 1,
        completedOn: LocalDate? = null,
    ): SessionWrite {
        val snapshot = SessionSnapshot(
            uuid = uuid,
            mangaId = 1,
            chapterId = chapterId,
            key = ChapterKey(
                1,
                "/manga/1",
                "/chapter/$chapterId",
                "Title 1",
                "Chapter $chapterId",
                chapterId.toDouble(),
            ),
            startedAt = 0,
            endedAt = activeMs,
            activeMs = activeMs,
            firstPage = 0,
            lastPage = 0,
            pagesVisited = pagesNew,
            pagesNew = pagesNew,
            pagesReread = 0,
            pageAdvances = 0,
            totalPages = 10,
            pagesBits = byteArrayOf(1),
            completedAt = completedOn?.let { 1L },
            completedOn = completedOn,
            firstCompletion = completedOn != null,
            origin = ReadingOrigin.READER,
            state = SessionState.CLOSED,
            zoneId = "UTC",
            days = listOf(DayTotals(DAY, activeMs, pagesNew, pagesNew)),
        )
        val progress = ChapterProgress(
            chapterId = chapterId,
            pagesSeen = byteArrayOf(1),
            maxPageReached = 0,
            totalPages = 10,
            completedAt = completedOn?.let { 1L },
            completedOn = completedOn,
            completionOrigin = completedOn?.let { ReadingOrigin.READER },
        )
        return SessionWrite(snapshot, progress)
    }

    companion object {
        private val DAY = LocalDate(2026, 9, 24)
    }
}
