package tachiyomi.data.readingstats

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import tachiyomi.data.readingstats.ReadingStatsFixtures.session
import tachiyomi.domain.readingstats.analysis.ChapterReading
import tachiyomi.domain.readingstats.analysis.CompletedChapter
import tachiyomi.domain.readingstats.comics.ComicField
import tachiyomi.domain.readingstats.comics.MetadataOrigin
import tachiyomi.domain.readingstats.comics.MetadataValue
import tachiyomi.domain.start.IssueTime

class ReadingAnalysisQueriesTest {

    private val db = TestDatabase()
    private val stats = ReadingStatsRepositoryImpl(db.database)
    private val metadata = ComicMetadataRepositoryImpl(db.database)

    private val monday = LocalDate(2026, 9, 21)
    private val tuesday = LocalDate(2026, 9, 22)

    @Test
    fun `sessions and time per chapter of a period`() = runTest {
        db.insertManga(1)
        db.insertManga(2, title = "Saga")
        db.insertChapter(10, mangaId = 1)
        db.insertChapter(11, mangaId = 1)
        db.insertChapter(20, mangaId = 2)
        stats.saveSession(session("a", chapterId = 10, activeMs = 60_000, day = monday))
        stats.saveSession(session("b", chapterId = 10, activeMs = 30_000, endedAt = 200_000, day = tuesday))
        stats.saveSession(session("c", chapterId = 11, activeMs = 5_000, day = tuesday))
        stats.saveSession(session("d", chapterId = 20, activeMs = 7_000, day = tuesday, mangaId = 2, title = "Saga"))

        stats.getSessions(tuesday, tuesday).map { it.activeMs }.sorted() shouldBe listOf(5_000L, 7_000, 30_000)
        stats.getSessions(monday, tuesday).size shouldBe 4

        stats.getReadingByChapter(monday, tuesday).sortedBy { it.chapterId } shouldBe listOf(
            ChapterReading(1, 10, "Title 1", 90_000),
            ChapterReading(1, 11, "Title 1", 5_000),
            ChapterReading(2, 20, "Saga", 7_000),
        )
        stats.getReadingByChapter(monday, monday).single().activeMs shouldBe 60_000
    }

    @Test
    fun `completed chapters of a period, in the reader or by hand, once each`() = runTest {
        db.insertManga(1)
        db.insertChapter(10, mangaId = 1)
        db.insertChapter(11, mangaId = 1)
        db.insertChapter(12, mangaId = 1)
        stats.saveSession(session("a", chapterId = 10, completedOn = monday, day = monday))
        stats.markCompletedManually(listOf(11L), at = 5, on = tuesday)
        stats.markCompletedManually(listOf(12L), at = 5, on = LocalDate(2026, 8, 1))

        stats.getCompletedChapters(monday, tuesday).sortedBy { it.chapterId } shouldBe
            listOf(CompletedChapter(10, 1), CompletedChapter(11, 1))
    }

    @Test
    fun `time per issue counts every session up to the one that finished it, reader only`() = runTest {
        db.insertManga(1)
        db.insertChapter(10, mangaId = 1)
        db.insertChapter(11, mangaId = 1)
        db.insertChapter(12, mangaId = 1)
        // #10 read over two sittings, finished in the second; then reread later.
        stats.saveSession(session("a", chapterId = 10, activeMs = 60_000, endedAt = 100_000, day = monday))
        stats.saveSession(
            session("b", chapterId = 10, activeMs = 90_000, endedAt = 300_000, completedOn = tuesday, day = tuesday),
        )
        stats.saveSession(session("c", chapterId = 10, activeMs = 45_000, endedAt = 900_000, day = tuesday))
        // #11 marked read by hand: no time, not a measured issue.
        stats.markCompletedManually(listOf(11L), at = 5, on = tuesday)
        // #12 finished in the reader before the period.
        stats.saveSession(
            session("d", chapterId = 12, activeMs = 30_000, completedOn = LocalDate(2026, 8, 1), day = monday),
        )

        stats.getReaderCompletionTimes(monday, tuesday) shouldBe listOf(IssueTime(10, 1, 150_000))
    }

    @Test
    fun `the first day with anything recorded or carried over`() = runTest {
        stats.getFirstActivityDate().shouldBeNull()
        db.insertManga(1)
        db.insertChapter(10, mangaId = 1)
        db.insertChapter(11, mangaId = 1)
        stats.saveSession(session("a", chapterId = 10, day = tuesday))
        stats.getFirstActivityDate() shouldBe tuesday

        stats.markCompletedManually(listOf(11L), at = 5, on = LocalDate(2026, 8, 1))

        stats.getFirstActivityDate() shouldBe LocalDate(2026, 8, 1)
    }

    @Test
    fun `confirmed metadata of several entries at once`() = runTest {
        db.insertManga(1)
        db.insertManga(2)
        db.insertManga(3)
        metadata.confirm(1, listOf(MetadataValue(ComicField.PUBLISHER, null, listOf("DC"), MetadataOrigin.USER)))
        metadata.replaceSuggestions(
            2,
            MetadataOrigin.GCD,
            listOf(MetadataValue(ComicField.PUBLISHER, null, listOf("Image"), MetadataOrigin.GCD)),
        )

        val found = metadata.getConfirmed(listOf(1L, 2L, 3L, 1L))

        found.keys shouldBe setOf(1L)
        found.getValue(1).series(ComicField.PUBLISHER)!!.value shouldBe "DC"
        metadata.getConfirmed(emptyList()) shouldBe emptyMap()
    }
}
