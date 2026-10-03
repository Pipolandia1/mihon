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
    fun `migration 23 adds the arc tables`() = runTest {
        db.exec("DROP TABLE start_arc_issues")
        db.exec("DROP TABLE start_arcs")

        Database.Schema.synchronous().migrate(db.driver, 23, 24)

        arcs.saveArc(arc, listOf(issue(0, "1")))
        arcs.getArc(arc.key).shouldNotBeNull().second.single().number shouldBe "1"
    }
}
