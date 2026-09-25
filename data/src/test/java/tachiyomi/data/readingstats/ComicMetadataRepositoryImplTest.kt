package tachiyomi.data.readingstats

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import tachiyomi.domain.readingstats.comics.ComicField
import tachiyomi.domain.readingstats.comics.ComicUnit
import tachiyomi.domain.readingstats.comics.MetadataOrigin
import tachiyomi.domain.readingstats.comics.MetadataValue

class ComicMetadataRepositoryImplTest {

    private val db = TestDatabase()
    private val metadata = ComicMetadataRepositoryImpl(db.database)
    private val stats = ReadingStatsRepositoryImpl(db.database)

    private fun value(field: ComicField, vararg values: String, chapterId: Long? = null, origin: MetadataOrigin) =
        MetadataValue(field, chapterId, values.toList(), origin)

    @Test
    fun `confirming replaces a field in its scope and keeps list order`() = runTest {
        db.insertManga(1)
        db.insertChapter(10, mangaId = 1)
        metadata.confirm(
            1,
            listOf(
                value(ComicField.WRITER, "Tom King", "Chip Zdarsky", origin = MetadataOrigin.USER),
                value(ComicField.UNIT, "ISSUE", origin = MetadataOrigin.INFERRED),
                value(ComicField.UNIT, "TPB", chapterId = 10, origin = MetadataOrigin.USER),
            ),
        )

        metadata.confirm(1, listOf(value(ComicField.WRITER, "Chip Zdarsky", "Tom King", origin = MetadataOrigin.USER)))

        val stored = metadata.get(1)
        stored.series(ComicField.WRITER)!!.values shouldBe listOf("Chip Zdarsky", "Tom King")
        stored.series(ComicField.UNIT)!!.origin shouldBe MetadataOrigin.INFERRED
        stored.unitOf(10) shouldBe ComicUnit.TPB

        metadata.confirm(1, listOf(value(ComicField.WRITER, origin = MetadataOrigin.USER)))
        metadata.remove(1, ComicField.UNIT, chapterId = 10)

        metadata.get(1).series(ComicField.WRITER).shouldBeNull()
        metadata.get(1).unitOf(10) shouldBe ComicUnit.ISSUE
    }

    @Test
    fun `suggestions are replaced per origin and never count as confirmed`() = runTest {
        db.insertManga(1)
        metadata.replaceSuggestions(
            1,
            MetadataOrigin.METRON,
            listOf(value(ComicField.PUBLISHER, "DC Comics", origin = MetadataOrigin.METRON)),
        )
        metadata.replaceSuggestions(
            1,
            MetadataOrigin.COMIC_INFO,
            listOf(value(ComicField.PUBLISHER, "DC", origin = MetadataOrigin.COMIC_INFO)),
        )
        metadata.replaceSuggestions(
            1,
            MetadataOrigin.METRON,
            listOf(value(ComicField.START_YEAR, "2025", origin = MetadataOrigin.METRON)),
        )

        val stored = metadata.subscribe(1).first()
        stored.confirmed.shouldBeEmpty()
        stored.suggested.map { it.origin to it.field }.toSet() shouldBe setOf(
            MetadataOrigin.COMIC_INFO to ComicField.PUBLISHER,
            MetadataOrigin.METRON to ComicField.START_YEAR,
        )
    }

    @Test
    fun `the library has favorites with their chapters, confirmed metadata and last reading`() = runTest {
        db.insertManga(1, status = 2, dateAdded = 50)
        db.insertManga(2)
        db.insertManga(3, favorite = false)
        db.insertChapter(10, mangaId = 1, name = "#1", read = true)
        db.insertChapter(11, mangaId = 1, name = "#2", scanlator = "Hidden")
        db.exec("INSERT INTO excluded_scanlators(manga_id, scanlator) VALUES (1, 'Hidden')")
        db.insertChapter(20, mangaId = 2)
        db.insertChapter(30, mangaId = 3)
        stats.saveSession(session(chapterId = 10, endedAt = 1_000))
        db.exec("INSERT INTO history(chapter_id, last_read, time_read) VALUES (20, 2000, 0)")
        metadata.confirm(1, listOf(value(ComicField.UNIT, "ISSUE", origin = MetadataOrigin.USER)))
        metadata.replaceSuggestions(
            1,
            MetadataOrigin.METRON,
            listOf(value(ComicField.UNIT, "TPB", origin = MetadataOrigin.METRON)),
        )

        val library = metadata.subscribeLibrary().first().associateBy { it.mangaId }

        library.keys shouldBe setOf(1L, 2L)
        library.getValue(1).let {
            it.publicationFinished shouldBe true
            it.dateAdded shouldBe 50
            it.lastReadAt shouldBe 1_000
            it.chapters.map { chapter -> chapter.id } shouldBe listOf(10L)
            it.metadata.unitOf(10) shouldBe ComicUnit.ISSUE
            it.metadata.suggested.shouldBeEmpty()
        }
        library.getValue(2).let {
            it.publicationFinished shouldBe false
            it.lastReadAt shouldBe 2_000
        }
    }

    @Test
    fun `chapters completed in a period, in the reader or by hand`() = runTest {
        db.insertManga(1)
        db.insertChapter(10, mangaId = 1)
        db.insertChapter(11, mangaId = 1)
        db.insertChapter(12, mangaId = 1)
        stats.saveSession(session(chapterId = 10, completedOn = LocalDate(2026, 9, 20)))
        stats.markCompletedManually(listOf(11L), at = 5, on = LocalDate(2026, 9, 22))
        stats.markCompletedManually(listOf(12L), at = 5, on = LocalDate(2026, 8, 1))

        metadata.completedChapterIds(LocalDate(2026, 9, 20), LocalDate(2026, 9, 26)) shouldBe setOf(10L, 11L)
    }

    @Test
    fun `an arc gathers its chapters across series by cover date`() = runTest {
        db.insertManga(1, title = "Batman")
        db.insertManga(2, title = "Nightwing")
        db.insertChapter(10, mangaId = 1, name = "#8")
        db.insertChapter(20, mangaId = 2, name = "#7")
        db.insertChapter(21, mangaId = 2, name = "#8")
        metadata.confirm(
            1,
            listOf(
                value(ComicField.ARC, "Night of the Owls", chapterId = 10, origin = MetadataOrigin.USER),
                value(ComicField.COVER_DATE, "2012-06-01", chapterId = 10, origin = MetadataOrigin.USER),
            ),
        )
        metadata.confirm(
            2,
            listOf(
                value(ComicField.ARC, "Night of the Owls", "Other", chapterId = 20, origin = MetadataOrigin.USER),
                value(ComicField.COVER_DATE, "2012-05-01", chapterId = 20, origin = MetadataOrigin.USER),
                value(ComicField.ARC, "Night of the Owls", chapterId = 21, origin = MetadataOrigin.USER),
            ),
        )

        metadata.arcs() shouldBe listOf("Night of the Owls", "Other")
        metadata.chaptersOfArc("Night of the Owls").map { it.chapterId } shouldBe listOf(20L, 10L, 21L)
        metadata.chaptersOfArc("Night of the Owls").first().label shouldBe "Nightwing · #7"
    }

    @Test
    fun `confirmed metadata restores onto another device's entries and chapters`() = runTest {
        db.insertManga(1)
        db.insertChapter(10, mangaId = 1)
        metadata.confirm(
            1,
            listOf(
                value(ComicField.PUBLISHER, "DC Comics", origin = MetadataOrigin.METRON),
                value(ComicField.CHARACTER, "Batman", "Robin", chapterId = 10, origin = MetadataOrigin.USER),
            ),
        )
        metadata.replaceSuggestions(
            1,
            MetadataOrigin.METRON,
            listOf(value(ComicField.IMPRINT, "X", origin = MetadataOrigin.METRON)),
        )
        val exported = metadata.export()

        val other = TestDatabase()
        other.insertManga(7, url = "/manga/1")
        other.insertChapter(70, mangaId = 7, url = "/chapter/10")
        val target = ComicMetadataRepositoryImpl(other.database)
        repeat(2) { target.import(exported) }

        val restored = target.get(7)
        restored.series(ComicField.PUBLISHER)!!.origin shouldBe MetadataOrigin.METRON
        restored.chapter(70, ComicField.CHARACTER)!!.values shouldBe listOf("Batman", "Robin")
        restored.confirmed.size shouldBe 2
        restored.suggested.shouldBeEmpty()
    }

    private fun session(chapterId: Long, endedAt: Long = 1_000, completedOn: LocalDate? = null) =
        ReadingStatsFixtures.session("s$chapterId", chapterId = chapterId, endedAt = endedAt, completedOn = completedOn)
}
