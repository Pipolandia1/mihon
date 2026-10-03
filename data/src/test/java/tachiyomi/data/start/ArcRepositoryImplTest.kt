package tachiyomi.data.start

import app.cash.sqldelight.async.coroutines.synchronous
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.readingstats.TestDatabase
import tachiyomi.domain.start.Arc
import tachiyomi.domain.start.ArcIssue

/** Arcs kept on the device (docs/HOME_PLAN.md §13, H8), on real SQLite. */
class ArcRepositoryImplTest {

    private val db = TestDatabase()
    private val arcs = ArcRepositoryImpl(db.database)

    private val arc = Arc("metron:arc:1", "The Zoo", "Bruce's first months.", "https://img/arc.jpg", 100)

    private fun issue(position: Int, number: String) = ArcIssue(
        position = position,
        key = "metron:issue:$position",
        seriesKey = "metron:series:3001",
        seriesName = "Absolute Batman",
        seriesYear = 2024,
        number = number,
        coverDate = "2024-12-01",
        storeDate = "2024-10-09",
        coverUrl = null,
    )

    @Test
    fun `an arc is kept with its issues in order, and fetched again it replaces them`() = runTest {
        arcs.getArc(arc.key).shouldBeNull()

        arcs.saveArc(arc, listOf(issue(1, "2"), issue(0, "1"), issue(2, "3")))
        arcs.getArc(arc.key).shouldNotBeNull().let { (kept, issues) ->
            kept shouldBe arc
            issues.map { it.number } shouldBe listOf("1", "2", "3")
        }

        arcs.saveArc(arc.copy(fetchedAt = 200), listOf(issue(0, "1"), issue(1, "2")))
        arcs.getArc(arc.key).shouldNotBeNull().let { (kept, issues) ->
            kept.fetchedAt shouldBe 200
            issues.size shouldBe 2
        }
    }

    @Test
    fun `every kept arc comes with its own issues, in order`() = runTest {
        arcs.saveArc(arc, listOf(issue(1, "2"), issue(0, "1")))
        arcs.saveArc(arc.copy(key = "metron:arc:2", name = "Absolute Power"), listOf(issue(0, "13")))

        arcs.keptArcs().associate { (kept, issues) -> kept.name to issues.map { it.number } } shouldBe mapOf(
            "The Zoo" to listOf("1", "2"),
            "Absolute Power" to listOf("13"),
        )
    }

    @Test
    fun `the arcs named in what was opened lately, the latest first, each once, confirmed only`() = runTest {
        db.insertManga(1, title = "Absolute Batman (2024-)")
        db.insertManga(2, title = "Green Arrow (2023)")
        (11L..14L).forEach { db.insertChapter(it, mangaId = 1) }
        db.insertChapter(21, mangaId = 2)
        fun arcOf(id: Long, manga: Long, chapter: Long, name: String, confirmed: Boolean = true) = db.exec(
            """
            INSERT INTO comic_metadata(_id, manga_id, chapter_id, field, position, value, origin, confirmed)
            VALUES ($id, $manga, $chapter, 23, 0, '$name', 1, ${if (confirmed) 1 else 0})
            """.trimIndent(),
        )
        fun opened(chapter: Long, at: Long) =
            db.exec("INSERT INTO history(chapter_id, last_read, time_read) VALUES ($chapter, $at, 0)")
        arcOf(1, 1, 11, "The Zoo")
        arcOf(2, 1, 12, "The Zoo")
        arcOf(3, 2, 21, "Absolute Power")
        arcOf(4, 1, 13, "Suggested Only", confirmed = false)
        arcOf(5, 1, 14, "Long Ago")
        opened(11, 1_000)
        opened(12, 3_000)
        opened(21, 2_000)
        opened(13, 4_000)
        opened(14, 10)

        arcs.readArcNames(since = 500, limit = 10).map { it.name to it.readAt } shouldBe listOf(
            "The Zoo" to 3_000L,
            "Absolute Power" to 2_000L,
        )
        arcs.readArcNames(since = 500, limit = 10).last().let {
            it.mangaId shouldBe 2
            it.title shouldBe "Green Arrow (2023)"
        }
        arcs.readArcNames(since = 500, limit = 1).single().name shouldBe "The Zoo"
    }

    @Test
    fun `migration 23 adds the arc tables`() = runTest {
        db.exec("DROP TABLE start_arc_issues")
        db.exec("DROP TABLE start_arcs")

        Database.Schema.synchronous().migrate(db.driver, 23, 24)

        arcs.saveArc(arc, listOf(issue(0, "1")))
        arcs.getArc(arc.key).shouldNotBeNull().second.single().number shouldBe "1"
    }
}
