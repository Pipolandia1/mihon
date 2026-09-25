package tachiyomi.domain.readingstats.comics

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ComicInferenceTest {

    @Test
    fun `series titles with years`() {
        SeriesTitles.parse("Batman (2025-)") shouldBe ParsedTitle("Batman", 2025, null, true)
        SeriesTitles.parse("Flashpoint (2011)") shouldBe ParsedTitle("Flashpoint", 2011, 2011, false)
        SeriesTitles.parse("Batman (2016-2025)") shouldBe ParsedTitle("Batman", 2016, 2025, false)
        SeriesTitles.parse("Saga") shouldBe ParsedTitle("Saga", null, null, null)
    }

    @Test
    fun `publishers and imprints among genres`() {
        Publishers.match(listOf("Action", "DC Comics")) shouldBe ("DC Comics" to null)
        Publishers.match(listOf("vertigo")) shouldBe ("DC Comics" to "Vertigo")
        Publishers.match(listOf("Action")).shouldBeNull()
    }

    @Test
    fun `suggestions from what a comic source gives`() {
        val chapters = listOf(
            ChapterName(1, "Issue #1", 1.0),
            ChapterName(2, "Issue #2", 2.0),
            ChapterName(3, "#1 Noir Edition", 1.0),
            ChapterName(4, "#Annual 2025", 2025.0),
            ChapterName(5, "TPB_(Part_1)", 1.0),
        )

        val values = ComicInference.suggest("Batman (2025-)", "", null, listOf("DC Comics", "Superhero"), chapters)

        values shouldContain MetadataValue(ComicField.SERIES_NAME, null, listOf("Batman"), MetadataOrigin.INFERRED)
        values shouldContain MetadataValue(ComicField.START_YEAR, null, listOf("2025"), MetadataOrigin.INFERRED)
        values.filter { it.field == ComicField.END_YEAR }.shouldBeEmpty()
        values shouldContain MetadataValue(ComicField.PUBLISHER, null, listOf("DC Comics"), MetadataOrigin.INFERRED)
        values shouldContain MetadataValue(ComicField.GENRE, null, listOf("Superhero"), MetadataOrigin.SOURCE)
        values.filter { it.field == ComicField.WRITER }.shouldBeEmpty()
        values shouldContain MetadataValue(ComicField.UNIT, null, listOf("ISSUE"), MetadataOrigin.INFERRED)
        values shouldContain MetadataValue(ComicField.UNIT, 5, listOf("TPB"), MetadataOrigin.INFERRED)
        values shouldContain MetadataValue(ComicField.VARIANT, 3, listOf("Noir Edition"), MetadataOrigin.INFERRED)
        values shouldContain MetadataValue(ComicField.ISSUE_LABEL, 4, listOf("Annual 2025"), MetadataOrigin.INFERRED)
        values shouldNotContain MetadataValue(ComicField.ISSUE_LABEL, 1, listOf("#1"), MetadataOrigin.INFERRED)
    }

    @Test
    fun `credits are split and unknowns dropped`() {
        val values = ComicInference.suggest(
            title = "Saga",
            author = "Brian K. Vaughan, Unknown",
            artist = "Fiona Staples",
            genres = emptyList(),
            chapters = emptyList(),
        )

        values shouldContain MetadataValue(ComicField.WRITER, null, listOf("Brian K. Vaughan"), MetadataOrigin.SOURCE)
        values shouldContain MetadataValue(ComicField.ARTIST, null, listOf("Fiona Staples"), MetadataOrigin.SOURCE)
        values.filter { it.field == ComicField.SERIES_NAME }.shouldBeEmpty()
    }
}
