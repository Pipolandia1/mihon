package tachiyomi.domain.readingstats.comics

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Comic metadata without a tap (docs/HOME_PLAN.md §14, H13). */
class AutoMetadataTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun work(id: Long, chapters: Int = 10, lastReadAt: Long? = null, dateAdded: Long = 0) =
        AutoWork(id, "Work $id", chapters, lastReadAt, dateAdded)

    private fun state(id: Long, status: AutoStatus, chapters: Int = 10, checkedAt: Long = now - day) =
        AutoState(id, status, seriesId = 1, chapters = chapters, checkedAt = checkedAt)

    @Test
    fun `a series of the work's name and year is certain, and confirmed`() {
        val candidates = listOf(
            CatalogCandidate(1, "Batman (2011)", 2011),
            CatalogCandidate(2, "Batman (2016)", 2016),
            CatalogCandidate(3, "Batman (2025)", 2025),
        )

        AutoMetadata.pick("Batman (2016-)", candidates) shouldBe AutoPick(2, AutoStatus.CONFIRMED)
    }

    @Test
    fun `a series a year apart, or a work without a year, is only suggested`() {
        AutoMetadata.pick(
            "Justice League Unlimited (2024-)",
            listOf(CatalogCandidate(7, "Justice League Unlimited (2025)", 2025)),
        ) shouldBe
            AutoPick(7, AutoStatus.SUGGESTED)
        AutoMetadata.pick("Flashpoint", listOf(CatalogCandidate(8, "Flashpoint (2011)", 2011))) shouldBe
            AutoPick(8, AutoStatus.SUGGESTED)
    }

    @Test
    fun `no series, or two that tie, is no pick`() {
        AutoMetadata.pick("Absolute Batman (2024-)", listOf(CatalogCandidate(1, "Batman (2016)", 2016))).shouldBeNull()
        // Without a year, two series of the name cannot be told apart.
        AutoMetadata.pick(
            "Batman",
            listOf(CatalogCandidate(1, "Batman (2011)", 2011), CatalogCandidate(2, "Batman (2016)", 2016)),
        ).shouldBeNull()
    }

    @Test
    fun `the single issues are preferred to a collection of the same name and year`() {
        val candidates = listOf(
            CatalogCandidate(1, "Absolute Batman (2024)", 2024, collected = true),
            CatalogCandidate(2, "Absolute Batman (2024)", 2024),
        )

        AutoMetadata.pick("Absolute Batman (2024-)", candidates)?.seriesId shouldBe 2
    }

    @Test
    fun `a catalogue title without its year is given it`() {
        AutoMetadata.pick("Geiger (2024-)", listOf(CatalogCandidate(4, "Geiger", 2024))) shouldBe
            AutoPick(4, AutoStatus.CONFIRMED)
    }

    @Test
    fun `a work is done once, again with new chapters, and searched again a week after nothing was found`() {
        AutoMetadata.due(work(1), null, now) shouldBe true
        AutoMetadata.due(work(1), state(1, AutoStatus.CONFIRMED), now) shouldBe false
        AutoMetadata.due(work(1, chapters = 11), state(1, AutoStatus.CONFIRMED), now) shouldBe true
        AutoMetadata.due(work(1, chapters = 11), state(1, AutoStatus.SUGGESTED), now) shouldBe true
        AutoMetadata.due(work(1), state(1, AutoStatus.NOT_FOUND, checkedAt = now - 6 * day), now) shouldBe false
        AutoMetadata.due(work(1), state(1, AutoStatus.NOT_FOUND, checkedAt = now - 7 * day), now) shouldBe true
        AutoMetadata.due(work(1, chapters = 99), state(1, AutoStatus.LEFT), now) shouldBe false
    }

    @Test
    fun `what was read most recently goes first, then what was added most recently`() {
        val works = listOf(
            work(1, dateAdded = now - 3 * day),
            work(2, lastReadAt = now - day),
            work(3, dateAdded = now - day),
            work(4, lastReadAt = now - 2 * day),
            work(5),
        )
        val states = mapOf(5L to state(5, AutoStatus.CONFIRMED))

        AutoMetadata.todo(works, states, now).map { it.mangaId } shouldContainExactly listOf(2L, 4L, 3L, 1L)
    }

    @Test
    fun `only the catalogue's suggestions for fields with nothing confirmed are confirmed`() {
        val metadata = ComicMetadata(
            mangaId = 1,
            confirmed = listOf(MetadataValue(ComicField.WRITER, 10, listOf("Me"), MetadataOrigin.USER)),
            suggested = listOf(
                MetadataValue(ComicField.WRITER, 10, listOf("Tom King"), MetadataOrigin.METRON),
                MetadataValue(ComicField.ARC, 10, listOf("I Am Gotham"), MetadataOrigin.METRON),
                MetadataValue(ComicField.PAGE_COUNT, 10, listOf("32"), MetadataOrigin.COMIC_INFO),
                MetadataValue(ComicField.METRON_ID, null, listOf("2"), MetadataOrigin.METRON),
            ),
        )

        AutoMetadata.toConfirm(metadata, MetadataOrigin.METRON).map { it.field } shouldContainExactly
            listOf(ComicField.ARC, ComicField.METRON_ID)
    }
}
