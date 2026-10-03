package tachiyomi.data.readingstats

import app.cash.sqldelight.async.coroutines.synchronous
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.domain.readingstats.comics.AutoState
import tachiyomi.domain.readingstats.comics.AutoStatus

/** Where automatic metadata stands for each work (docs/HOME_PLAN.md §14, H13), on real SQLite. */
class AutoMetadataRepositoryImplTest {

    private val db = TestDatabase()
    private val states = AutoMetadataRepositoryImpl(db.database)

    @Test
    fun `a work's state is kept, replaced by the next, and goes with the work`() = runTest {
        db.insertManga(1)
        db.insertManga(2)
        states.put(AutoState(1, AutoStatus.NOT_FOUND, null, 3, 100))
        states.put(AutoState(1, AutoStatus.CONFIRMED, 2016, 4, 200))
        states.put(AutoState(2, AutoStatus.SUGGESTED, 7, 1, 300))

        states.all().sortedBy { it.mangaId } shouldBe listOf(
            AutoState(1, AutoStatus.CONFIRMED, 2016, 4, 200),
            AutoState(2, AutoStatus.SUGGESTED, 7, 1, 300),
        )

        db.exec("PRAGMA foreign_keys = ON")
        db.exec("DELETE FROM mangas WHERE _id = 2")
        states.all().map { it.mangaId } shouldBe listOf(1L)
    }

    @Test
    fun `migration 24 adds the table`() = runTest {
        db.exec("DROP TABLE comic_auto_metadata")

        Database.Schema.synchronous().migrate(db.driver, 24, 25)

        states.all().shouldBeEmpty()
        db.insertManga(1)
        states.put(AutoState(1, AutoStatus.LEFT, null, 0, 1))
        states.all().single().status shouldBe AutoStatus.LEFT
    }
}
