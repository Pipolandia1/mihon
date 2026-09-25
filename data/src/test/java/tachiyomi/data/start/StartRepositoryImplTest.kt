package tachiyomi.data.start

import app.cash.sqldelight.async.coroutines.synchronous
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.readingstats.ComicMetadataRepositoryImpl
import tachiyomi.data.readingstats.ReadingStatsFixtures.session
import tachiyomi.data.readingstats.ReadingStatsRepositoryImpl
import tachiyomi.data.readingstats.TestDatabase
import tachiyomi.domain.readingstats.comics.ComicField
import tachiyomi.domain.readingstats.comics.MetadataOrigin
import tachiyomi.domain.readingstats.comics.MetadataValue
import tachiyomi.domain.start.Credit
import tachiyomi.domain.start.EntityKind
import tachiyomi.domain.start.Feedback
import tachiyomi.domain.start.FeedbackSubject
import tachiyomi.domain.start.FeedbackVerdict

class StartRepositoryImplTest {

    private val db = TestDatabase()
    private val start = StartRepositoryImpl(db.database)
    private val stats = ReadingStatsRepositoryImpl(db.database)
    private val metadata = ComicMetadataRepositoryImpl(db.database)

    @Test
    fun `works are the library and whatever was read, not everything ever browsed`() = runTest {
        db.insertManga(1, favorite = true)
        db.insertManga(2, favorite = false)
        db.insertManga(3, favorite = false)
        db.insertManga(4, favorite = false)
        db.insertChapter(20, mangaId = 2, read = true)
        db.insertChapter(30, mangaId = 3)
        db.insertChapter(40, mangaId = 4)
        stats.saveSession(session("a", chapterId = 30, mangaId = 3, activeMs = 90_000))

        val works = start.subscribeWorks().first().associateBy { it.mangaId }

        works.keys shouldBe setOf(1L, 2L, 3L)
        works.getValue(1).inLibrary shouldBe true
        works.getValue(2).chaptersRead shouldBe 1
        works.getValue(3).sessions shouldBe 1
        works.getValue(3).activeMs shouldBe 90_000
    }

    @Test
    fun `credits say where they came from, and a confirmed value is the reader's own`() = runTest {
        db.insertManga(1)
        db.exec("UPDATE mangas SET author = 'Ana Torres', artist = 'Rui Campos', genre = 'Teste' WHERE _id = 1")
        metadata.confirm(
            1,
            listOf(MetadataValue(ComicField.CHARACTER, null, listOf("Lanterna"), MetadataOrigin.COMIC_INFO)),
        )
        metadata.replaceSuggestions(
            1,
            MetadataOrigin.GCD,
            listOf(MetadataValue(ComicField.WRITER, null, listOf("Ana Torres"), MetadataOrigin.GCD)),
        )

        val credits = start.subscribeWorks().first().single().credits

        credits shouldContain Credit(EntityKind.CHARACTER, "Lanterna", MetadataOrigin.USER)
        credits shouldContain Credit(EntityKind.WRITER, "Ana Torres", MetadataOrigin.GCD)
        credits shouldContain Credit(EntityKind.WRITER, "Ana Torres", MetadataOrigin.SOURCE)
        credits shouldContain Credit(EntityKind.ARTIST, "Rui Campos", MetadataOrigin.SOURCE)
        credits shouldContain Credit(EntityKind.GENRE, "Teste", MetadataOrigin.SOURCE)
    }

    @Test
    fun `the total is the confirmed one, or the chapters when publication ended, or unknown`() = runTest {
        db.insertManga(1, status = 2) // completed
        db.insertManga(2, status = 1) // ongoing
        db.insertManga(3, status = 1)
        (10L..12L).forEach { db.insertChapter(it, mangaId = 1) }
        db.insertChapter(20, mangaId = 2)
        db.insertChapter(30, mangaId = 3)
        metadata.confirm(3, listOf(MetadataValue(ComicField.TOTAL_ISSUES, null, listOf("12"), MetadataOrigin.USER)))

        val works = start.subscribeWorks().first().associateBy { it.mangaId }

        works.getValue(1).total shouldBe 3
        works.getValue(2).total shouldBe null
        works.getValue(3).total shouldBe 12
    }

    @Test
    fun `feedback is kept, replaced and removed`() = runTest {
        start.setFeedback(Feedback(FeedbackSubject.WORK, "1", FeedbackVerdict.LATER, untilAt = 50, createdAt = 1))
        start.setFeedback(
            Feedback(FeedbackSubject.WORK, "1", FeedbackVerdict.NOT_INTERESTED, untilAt = null, createdAt = 2),
        )
        start.setFeedback(Feedback(FeedbackSubject.CREATOR, "tom king", FeedbackVerdict.NOT_INTERESTED, null, 3))

        start.subscribeFeedback().first().associateBy { it.key }.let {
            it.getValue("1").verdict shouldBe FeedbackVerdict.NOT_INTERESTED
            it.getValue("1").untilAt shouldBe null
            it.getValue("tom king").subject shouldBe FeedbackSubject.CREATOR
        }

        start.removeFeedback(FeedbackSubject.WORK, "1")
        start.subscribeFeedback().first().map { it.key } shouldBe listOf("tom king")
    }

    @Test
    fun `feedback travels by source and URL, and restores onto other ids once`() = runTest {
        db.insertManga(1, url = "/series/a")
        start.setFeedback(Feedback(FeedbackSubject.WORK, "1", FeedbackVerdict.ALREADY_READ, null, 5))
        start.setFeedback(Feedback(FeedbackSubject.CHARACTER, "batman", FeedbackVerdict.NOT_INTERESTED, null, 6))
        val (works, names) = start.exportFeedback()

        val other = TestDatabase()
        other.insertManga(7, url = "/series/a")
        val restored = StartRepositoryImpl(other.database)
        restored.importFeedback(works, names)
        restored.importFeedback(works, names)

        restored.subscribeFeedback().first().map { it.subject to it.key }.toSet() shouldBe
            setOf(FeedbackSubject.WORK to "7", FeedbackSubject.CHARACTER to "batman")
    }

    @Test
    fun `a work not on this device is skipped on restore`() = runTest {
        db.insertManga(1, url = "/series/a")
        start.setFeedback(Feedback(FeedbackSubject.WORK, "1", FeedbackVerdict.ALREADY_READ, null, 5))
        val (works, names) = start.exportFeedback()

        val other = StartRepositoryImpl(TestDatabase().database)
        other.importFeedback(works, names)

        other.subscribeFeedback().first().shouldBeEmpty()
    }

    @Test
    fun `migration 18 adds the feedback table`() = runTest {
        db.exec("DROP TABLE start_feedback")

        Database.Schema.synchronous().migrate(db.driver, 18, 19)

        start.setFeedback(Feedback(FeedbackSubject.WORK, "1", FeedbackVerdict.LATER, 9, 1))
        start.subscribeFeedback().first().map { it.key } shouldNotContain "2"
        start.subscribeFeedback().first().single().untilAt shouldBe 9
    }
}
