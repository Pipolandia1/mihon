package eu.kanade.tachiyomi.ui.start.catalog

import eu.kanade.tachiyomi.readingstats.comics.online.metron.MetronIssueListItem
import eu.kanade.tachiyomi.readingstats.comics.online.metron.MetronItem
import eu.kanade.tachiyomi.readingstats.comics.online.metron.MetronNamed
import eu.kanade.tachiyomi.readingstats.comics.online.metron.MetronPage
import eu.kanade.tachiyomi.readingstats.comics.online.metron.MetronSeries
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import tachiyomi.domain.start.CatalogSeriesRef
import tachiyomi.domain.start.Discovery
import tachiyomi.domain.start.EntityKind

/**
 * Metron's answers for the Início's discovery (H5), written from its serializers (CreatorList,
 * IssueList with its series, SeriesRead), as the app decodes them.
 */
class MetronCatalogTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private fun fixture(name: String) = javaClass.getResource("/comics/$name")!!.readText()

    @Test
    fun `a name becomes the one id named exactly so`() {
        val page = json.decodeFromString<MetronPage<MetronItem>>(fixture("metron_creator_search.json"))

        Discovery.exactId(catalogNames(page.results), "Tom King") shouldBe 1437
        Discovery.exactId(catalogNames(page.results), "Tom").shouldBeNull()
    }

    @Test
    fun `a creator's issues become the series they are in, and a page cut short is told by its count`() {
        val page = json.decodeFromString<MetronPage<MetronIssueListItem>>(fixture("metron_issues_by_creator.json"))

        page.count shouldBe 212
        (page.next != null) shouldBe true
        Discovery.seriesOf(catalogIssues(page.results)) shouldContainExactly listOf(
            CatalogSeriesRef(2533, "The Human Target", 1, 2021, 2),
            CatalogSeriesRef(2911, "Batman: The Brave and the Bold", 1, 2023, 1),
            CatalogSeriesRef(3120, "Wonder Woman", 6, 2023, 1),
        )
    }

    @Test
    fun `a series' details say its length, that it ended, and its format`() {
        val series = json.decodeFromString<MetronSeries>(fixture("metron_series_detail.json"))

        val detail = seriesDetail(series)

        detail.issueCount shouldBe 12
        detail.finished shouldBe true
        Discovery.isCollected(detail) shouldBe false
        Discovery.isCollected(
            seriesDetail(series.copy(seriesType = series.seriesType?.copy(name = "Trade Paperback"))),
        ) shouldBe
            true
        seriesDetail(series.copy(status = "Ongoing")).finished shouldBe false
    }

    @Test
    fun `writers and artists are asked as creators, characters and teams as themselves, genres not at all`() {
        named(EntityKind.WRITER) shouldBe MetronNamed.CREATOR
        named(EntityKind.ARTIST) shouldBe MetronNamed.CREATOR
        named(EntityKind.CHARACTER) shouldBe MetronNamed.CHARACTER
        named(EntityKind.TEAM) shouldBe MetronNamed.TEAM
        named(EntityKind.GENRE).shouldBeNull()
        named(EntityKind.PUBLISHER).shouldBeNull()
    }
}
