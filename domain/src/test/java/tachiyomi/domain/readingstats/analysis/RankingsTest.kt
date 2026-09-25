package tachiyomi.domain.readingstats.analysis

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.readingstats.comics.ComicField
import tachiyomi.domain.readingstats.comics.ComicMetadata
import tachiyomi.domain.readingstats.comics.MetadataOrigin
import tachiyomi.domain.readingstats.comics.MetadataValue

class RankingsTest {

    private val minute = 60_000L

    private fun metadata(mangaId: Long, vararg values: MetadataValue) =
        ComicMetadata(mangaId, values.toList(), emptyList())

    private fun series(field: ComicField, vararg values: String) =
        MetadataValue(field, null, values.toList(), MetadataOrigin.USER)

    private fun chapter(id: Long, field: ComicField, vararg values: String) =
        MetadataValue(field, id, values.toList(), MetadataOrigin.USER)

    @Test
    fun `unknown never leads and too little classified time shows no ranking`() {
        val readings = listOf(
            ChapterReading(1, 10, "Batman", 30 * minute),
            ChapterReading(2, 20, "Saga", 70 * minute),
        )
        val known = mapOf(1L to metadata(1, series(ComicField.PUBLISHER, "DC Comics")))

        val ranking = Rankings.of(RankingDimension.PUBLISHER, readings, known)

        ranking.entries.map { it.value } shouldBe listOf("DC Comics")
        ranking.coverage shouldBe 0.3
        ranking.shown shouldBe false

        val more = known + (2L to metadata(2, series(ComicField.PUBLISHER, "Image Comics")))
        val full = Rankings.of(RankingDimension.PUBLISHER, readings, more)
        full.shown shouldBe true
        full.entries.map { it.value to it.share } shouldBe listOf("Image Comics" to 0.7, "DC Comics" to 0.3)
    }

    @Test
    fun `several values each count the chapter in full, and a chapter's own value wins`() {
        val readings = listOf(
            ChapterReading(1, 10, "Batman", 60 * minute),
            ChapterReading(1, 11, "Batman", 30 * minute),
        )
        val meta = metadata(
            1,
            series(ComicField.WRITER, "Tom King"),
            chapter(10, ComicField.WRITER, "Chip Zdarsky", "Tom King"),
        )

        val ranking = Rankings.of(RankingDimension.WRITER, readings, mapOf(1L to meta))

        ranking.entries.map { it.value to it.activeMs } shouldBe
            listOf("Tom King" to 90 * minute, "Chip Zdarsky" to 60 * minute)
        ranking.classifiedMs shouldBe 90 * minute
        ranking.coverage shouldBe 1.0
    }

    @Test
    fun `decades from the cover date, else the series' start`() {
        val readings = listOf(ChapterReading(1, 10, "A", 10 * minute), ChapterReading(1, 11, "A", 5 * minute))
        val meta = metadata(1, series(ComicField.START_YEAR, "1986"), chapter(10, ComicField.COVER_DATE, "2011-07"))

        Rankings.of(RankingDimension.DECADE, readings, mapOf(1L to meta)).entries.map { it.value } shouldBe
            listOf("2010", "1980")
    }

    @Test
    fun `series need no metadata, a deleted chapter counts for its entry`() {
        val readings = listOf(
            ChapterReading(null, null, "Gone", 10 * minute),
            ChapterReading(1, 10, "Saga", 5 * minute),
        )

        val ranking = Rankings.of(RankingDimension.SERIES, readings, emptyMap())

        ranking.coverage shouldBe 1.0
        ranking.entries.map { it.value } shouldBe listOf("Gone", "Saga")
        Rankings.of(RankingDimension.SERIES, emptyList(), emptyMap()).entries.shouldBeEmpty()
    }
}
