package eu.kanade.tachiyomi.readingstats.comics

import eu.kanade.tachiyomi.readingstats.comics.online.IssueMatcher
import eu.kanade.tachiyomi.readingstats.comics.online.IssueRef
import eu.kanade.tachiyomi.readingstats.comics.online.gcd.GcdIssue
import eu.kanade.tachiyomi.readingstats.comics.online.gcd.GcdMapping
import eu.kanade.tachiyomi.readingstats.comics.online.gcd.GcdPage
import eu.kanade.tachiyomi.readingstats.comics.online.gcd.GcdSeries
import eu.kanade.tachiyomi.readingstats.comics.online.metron.MetronIssue
import eu.kanade.tachiyomi.readingstats.comics.online.metron.MetronIssueSummary
import eu.kanade.tachiyomi.readingstats.comics.online.metron.MetronMapping
import eu.kanade.tachiyomi.readingstats.comics.online.metron.MetronPage
import eu.kanade.tachiyomi.readingstats.comics.online.metron.MetronSeries
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import tachiyomi.domain.readingstats.comics.ChapterName
import tachiyomi.domain.readingstats.comics.ComicField
import tachiyomi.domain.readingstats.comics.ComicUnit
import tachiyomi.domain.readingstats.comics.MetadataOrigin
import tachiyomi.domain.readingstats.comics.MetadataValue

/** Responses written from the APIs' schemas (Metron via its Mokkari client, the GCD's OpenAPI). */
class OnlineMetadataMappingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private fun fixture(name: String) = javaClass.getResource("/comics/$name")!!.readText()

    private fun List<MetadataValue>.of(field: ComicField, chapterId: Long? = null) =
        single { it.field == field && it.chapterId == chapterId }.values

    // region Matching

    private val chapters = listOf(
        ChapterName(1, "Issue #1", 1.0),
        ChapterName(2, "#1 Noir Edition", 1.0),
        ChapterName(3, "#2", 2.0),
        ChapterName(4, "#Annual 2025", 2025.0),
        ChapterName(5, "TPB_(Part_1)", 1.0),
        ChapterName(6, "#1/2", 0.5),
        ChapterName(7, "#9", 9.0),
    )

    @Test
    fun `chapters pair with issues by number, annuals and collections do not`() {
        val issues = listOf(IssueRef(10, "1"), IssueRef(11, "1"), IssueRef(12, "2"), IssueRef(13, "1/2"))

        val matched = IssueMatcher.match(chapters, emptyMap(), issues)

        matched.mapValues { it.value.id } shouldBe mapOf(1L to 10L, 2L to 10L, 3L to 12L, 6L to 13L)
    }

    @Test
    fun `a confirmed label pairs a chapter its name would not`() {
        val matched = IssueMatcher.match(
            listOf(ChapterName(1, "Prologue", -1.0)),
            mapOf(1L to "#0"),
            listOf(IssueRef(20, "0")),
        )

        matched.mapValues { it.value.id } shouldBe mapOf(1L to 20L)
        IssueMatcher.match(listOf(ChapterName(1, "Prologue", -1.0)), emptyMap(), listOf(IssueRef(20, "0")))
            .shouldBeEmpty()
    }

    // endregion

    // region Metron

    @Test
    fun `a finished Metron series gives its total, publisher and unit`() {
        val series = json.decodeFromString<MetronSeries>(fixture("metron_series.json"))

        val values = MetronMapping.seriesValues(series)

        values.all { it.origin == MetadataOrigin.METRON && it.chapterId == null } shouldBe true
        values.of(ComicField.SERIES_NAME) shouldBe listOf("Flashpoint")
        values.of(ComicField.PUBLISHER) shouldBe listOf("DC Comics")
        values.of(ComicField.START_YEAR) shouldBe listOf("2011")
        values.of(ComicField.TOTAL_ISSUES) shouldBe listOf("5")
        values.of(ComicField.UNIT) shouldBe listOf("ISSUE")
        values.of(ComicField.METRON_ID) shouldBe listOf("2891")
        values.of(ComicField.GENRE) shouldBe listOf("Super-Hero")
    }

    @Test
    fun `an ongoing Metron series gives no total`() {
        val series = json.decodeFromString<MetronSeries>(fixture("metron_series.json"))
            .copy(status = "Ongoing", yearEnd = null)

        MetronMapping.seriesValues(series).filter { it.field == ComicField.TOTAL_ISSUES }.shouldBeEmpty()
    }

    @Test
    fun `Metron series types map to units`() {
        MetronMapping.unitOf("Trade Paperback") shouldBe ComicUnit.TPB
        MetronMapping.unitOf("Hard Cover") shouldBe ComicUnit.HARDCOVER
        MetronMapping.unitOf("Omnibus") shouldBe ComicUnit.OMNIBUS
        MetronMapping.unitOf("Graphic Novel") shouldBe ComicUnit.OTHER
        MetronMapping.unitOf("One-Shot") shouldBe ComicUnit.ISSUE
    }

    @Test
    fun `a Metron issue list pairs with the chapters`() {
        val list = json.decodeFromString<MetronPage<MetronIssueSummary>>(fixture("metron_issue_list.json"))

        val matched = IssueMatcher.match(chapters, emptyMap(), list.results.map { IssueRef(it.id, it.number) })

        matched.mapValues { it.value.id } shouldBe mapOf(1L to 101L, 2L to 101L, 3L to 102L, 6L to 106L)
    }

    @Test
    fun `a Metron issue's credits go to their fields, a cover role only to the cover`() {
        val issue = json.decodeFromString<MetronIssue>(fixture("metron_issue.json"))

        val values = MetronMapping.issueValues(issue, chapterId = 1)

        values.of(ComicField.ISSUE_LABEL, 1) shouldBe listOf("#1")
        values.of(ComicField.COVER_DATE, 1) shouldBe listOf("2011-07-01")
        values.of(ComicField.PAGE_COUNT, 1) shouldBe listOf("40")
        values.of(ComicField.WRITER, 1) shouldBe listOf("Geoff Johns")
        values.of(ComicField.ARTIST, 1) shouldBe listOf("Andy Kubert", "Sandra Hope")
        values.of(ComicField.COVER_ARTIST, 1) shouldBe listOf("Andy Kubert", "Ivan Reis")
        values.of(ComicField.COLORIST, 1) shouldBe listOf("Alex Sinclair")
        values.of(ComicField.LETTERER, 1) shouldBe listOf("Nick Napolitano")
        values.of(ComicField.EDITOR, 1) shouldBe listOf("Eddie Berganza")
        values.of(ComicField.ARC, 1) shouldBe listOf("Flashpoint")
        values.of(ComicField.CHARACTER, 1) shouldBe listOf("Flash", "Batman (Thomas Wayne)")
        values.filter { it.field == ComicField.TEAM }.shouldBeEmpty()
    }

    // endregion

    // region GCD

    @Test
    fun `GCD series give their issues by number, printings and variants aside`() {
        val page = json.decodeFromString<GcdPage<GcdSeries>>(fixture("gcd_series_search.json"))
        val flashpoint = page.results.single { it.name == "Flashpoint" }

        GcdMapping.idOf(flashpoint.apiUrl) shouldBe 58499
        GcdMapping.issuesOf(flashpoint).map { it.number } shouldBe listOf("1", "1", "2", "3", "4", "5")
        GcdMapping.unitOf(flashpoint) shouldBe ComicUnit.ISSUE
        GcdMapping.unitOf(page.results.single { it.name.startsWith("The Flash") }) shouldBe ComicUnit.HARDCOVER

        val values = GcdMapping.seriesValues(flashpoint, publisher = "DC Comics")
        values.all { it.origin == MetadataOrigin.GCD } shouldBe true
        values.of(ComicField.TOTAL_ISSUES) shouldBe listOf("5")
        values.of(ComicField.GCD_ID) shouldBe listOf("58499")
        values.of(ComicField.PUBLISHER) shouldBe listOf("DC Comics")

        val matched = IssueMatcher.match(chapters, emptyMap(), GcdMapping.issuesOf(flashpoint))
        matched.mapValues { it.value.id } shouldBe mapOf(1L to 833090L, 2L to 833090L, 3L to 833091L)
    }

    @Test
    fun `a GCD issue's text credits and characters are split`() {
        val issue = json.decodeFromString<GcdIssue>(fixture("gcd_issue.json"))

        val values = GcdMapping.issueValues(issue, chapterId = 1)

        values.of(ComicField.GCD_ID, 1) shouldBe listOf("833090")
        values.of(ComicField.COVER_DATE, 1) shouldBe listOf("2011-07")
        values.of(ComicField.PAGE_COUNT, 1) shouldBe listOf("44")
        values.of(ComicField.WRITER, 1) shouldBe listOf("Geoff Johns")
        values.of(ComicField.ARTIST, 1) shouldBe listOf("Andy Kubert", "Sandra Hope")
        values.of(ComicField.COVER_ARTIST, 1) shouldBe listOf("Andy Kubert", "Sandra Hope")
        values.of(ComicField.COLORIST, 1) shouldBe listOf("Alex Sinclair")
        values.of(ComicField.LETTERER, 1) shouldBe listOf("Nick Napolitano")
        values.of(ComicField.EDITOR, 1) shouldBe listOf("Adam Schlagman", "Rex Ogle", "Eddie Berganza")
        values.of(ComicField.CHARACTER, 1) shouldBe listOf(
            "Flash [Barry Allen]",
            "Nora Allen",
            "Batman [Thomas Wayne]",
            "Cyborg [Victor Stone]",
            // Other names of one character, not a team: none of them has brackets of its own.
            "Reverse-Flash [Professor Zoom; Eobard Thawne]",
        )
        values.of(ComicField.TEAM, 1) shouldBe listOf("Justice League")
        values.filter { it.field == ComicField.VARIANT }.shouldBeEmpty()
    }

    @Test
    fun `GCD dates keep only what is known`() {
        GcdMapping.dateOf("2011-07-00") shouldBe "2011-07"
        GcdMapping.dateOf("2011-00-00") shouldBe "2011"
        GcdMapping.dateOf("2011-05-11") shouldBe "2011-05-11"
        GcdMapping.dateOf("") shouldBe null
    }

    // endregion
}
