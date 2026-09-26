package eu.kanade.tachiyomi.releases

import eu.kanade.tachiyomi.readingstats.comics.online.gcd.GcdPage
import eu.kanade.tachiyomi.readingstats.comics.online.gcd.GcdWeeklyItem
import eu.kanade.tachiyomi.readingstats.comics.online.metron.MetronIssueListItem
import eu.kanade.tachiyomi.readingstats.comics.online.metron.MetronPage
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import tachiyomi.domain.start.ReleaseItem
import tachiyomi.domain.start.ReleaseReason
import tachiyomi.domain.start.Releases
import tachiyomi.domain.start.WorkFacts

/**
 * A week's releases as the app reads them (H6): Metron's issue list filtered by sale date, and the
 * GCD's weekly list, written from their schemas (the GCD's shaped as its week 39 of 2026 is).
 */
class ReleaseMappingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private fun fixture(name: String) = javaClass.getResource("/comics/$name")!!.readText()

    /** A series in the library, three issues read. */
    private fun work(title: String) = WorkFacts(
        mangaId = 1,
        title = title,
        inLibrary = true,
        dateAdded = 0,
        chapters = 24,
        chaptersRead = 3,
        total = null,
        finished = false,
        sessions = 3,
        activeMs = 0,
        lastReadAt = 1,
        credits = emptyList(),
    )

    private val metronWeek by lazy {
        json.decodeFromString<MetronPage<MetronIssueListItem>>(
            fixture("metron_week.json"),
        ).results.mapNotNull(::metronRelease)
    }
    private val gcdWeek by lazy {
        json.decodeFromString<GcdPage<GcdWeeklyItem>>(fixture("gcd_week.json")).results.mapNotNull(::gcdRelease)
    }

    @Test
    fun `Metron gives the series by id, the day of sale and the cover, not an issue without a series`() {
        metronWeek.map { it.key } shouldContainExactly listOf("metron:issue:164221", "metron:issue:164310")
        metronWeek.first() shouldBe ReleaseItem(
            key = "metron:issue:164221",
            seriesKey = "metron:series:3001",
            seriesName = "Absolute Batman",
            seriesYear = 2024,
            number = "24",
            storeDate = "2026-09-23",
            coverUrl = "https://static.metron.cloud/media/issue/2026/09/16/absolute-batman-24.jpg",
        )
    }

    @Test
    fun `the GCD's series name gives its year, and a variant says which issue it is a cover of`() {
        gcdWeek.first() shouldBe ReleaseItem(
            key = "gcd:issue:2868581",
            seriesKey = "gcd:series:216143",
            seriesName = "Absolute Batman",
            seriesYear = 2024,
            number = "24",
            storeDate = null,
            coverUrl = null,
        )
        gcdWeek[1].number shouldBe "24"
        gcdWeek[1].variantOf shouldBe "gcd:issue:2868581"
        gcdWeek.last().seriesName shouldBe "2000 AD"
    }

    @Test
    fun `Absolute Batman 24 is one release from either list, because the reader reads Absolute Batman`() {
        val works = listOf(
            work("Absolute Batman (2024-)"),
        )

        listOf(metronWeek, gcdWeek).forEach { week ->
            val relevant = Releases.relevant(week, works, emptyList(), emptyList(), nowMs = 10)
            relevant.single().let {
                it.item.number shouldBe "24"
                it.reason shouldBe ReleaseReason.YourSeries(1, "Absolute Batman (2024-)")
            }
        }
    }

    @Test
    fun `the library's Metron id matches a release even when the titles differ`() {
        val works = listOf(
            work("Batman Absoluto").copy(catalogIds = setOf("metron:series:3001")),
        )

        Releases.relevant(metronWeek, works, emptyList(), emptyList(), nowMs = 10).single().item.key shouldBe
            "metron:issue:164221"
    }
}
