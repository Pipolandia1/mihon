package eu.kanade.tachiyomi.arcs

import eu.kanade.tachiyomi.readingstats.comics.online.metron.MetronArc
import eu.kanade.tachiyomi.readingstats.comics.online.metron.MetronIssueListItem
import eu.kanade.tachiyomi.readingstats.comics.online.metron.MetronPage
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import tachiyomi.domain.start.Arc
import tachiyomi.domain.start.ArcIssue

/** Metron's arcs as the app reads them (H8), written from its Arc and IssueList serializers. */
class MetronArcTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private fun fixture(name: String) = javaClass.getResource("/comics/$name")!!.readText()

    @Test
    fun `an arc is its name, what it is about and its image`() {
        val arc = json.decodeFromString<MetronArc>(fixture("metron_arc.json"))

        arcOf(ArcCatalog.keyOf(arc.id), arc, now = 5) shouldBe Arc(
            key = "metron:arc:2470",
            name = "The Zoo",
            description = "Bruce Wayne's first months as the Batman of the Absolute Universe.",
            imageUrl = "https://static.metron.cloud/media/arc/2024/10/09/the-zoo.jpg",
            fetchedAt = 5,
        )
    }

    @Test
    fun `an arc's issues keep Metron's order, each with its series and dates, none without a series`() {
        val page = json.decodeFromString<MetronPage<MetronIssueListItem>>(fixture("metron_arc_issues.json"))

        val issues = page.results.mapIndexedNotNull(::arcIssue)

        issues.map { it.number } shouldContainExactly listOf("1", "2", "3")
        issues.map { it.position } shouldContainExactly listOf(0, 1, 3)
        issues.first() shouldBe ArcIssue(
            position = 0,
            key = "metron:issue:140001",
            seriesKey = "metron:series:3001",
            seriesName = "Absolute Batman",
            seriesYear = 2024,
            number = "1",
            coverDate = "2024-12-01",
            storeDate = "2024-10-09",
            coverUrl = "https://static.metron.cloud/media/issue/2024/10/02/ab-1.jpg",
        )
    }
}
