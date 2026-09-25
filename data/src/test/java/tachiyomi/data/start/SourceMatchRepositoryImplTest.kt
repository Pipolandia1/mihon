package tachiyomi.data.start

import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.synchronous
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.data.readingstats.TestDatabase
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.start.SourceMatch

/** The cache of where recommended series were found (docs/HOME_PLAN.md §7.3, H4), on real SQLite. */
class SourceMatchRepositoryImplTest {

    private val db = TestDatabase()
    private val matches = SourceMatchRepositoryImpl(db.database)

    private fun match(
        key: String = "metron:series:1",
        mangaId: Long? = null,
        checkedAt: Long = 100,
        expiresAt: Long = 200,
        sourceId: Long? = 7,
    ) = SourceMatch(
        catalogKey = key,
        sourceId = sourceId,
        mangaUrl = sourceId?.let { "/series/absolute-batman" },
        mangaId = mangaId,
        matchedTitle = sourceId?.let { "Absolute Batman (2024-)" },
        triedSources = listOf(3, 7),
        checkedAt = checkedAt,
        expiresAt = expiresAt,
    )

    @Test
    fun `a match is kept and read back, found or not`() = runTest {
        matches.put(match())
        matches.put(match(key = "metron:series:2", sourceId = null))

        matches.get("metron:series:1") shouldBe match()
        matches.get("metron:series:2") shouldBe match(key = "metron:series:2", sourceId = null)
        matches.get("metron:series:3").shouldBeNull()
    }

    @Test
    fun `a newer answer replaces the old one, and expired answers go`() = runTest {
        matches.put(match(key = "metron:series:old", expiresAt = 150))
        matches.put(match(mangaId = null))
        matches.put(match(checkedAt = 160, expiresAt = 300))

        matches.get("metron:series:1").shouldNotBeNull().expiresAt shouldBe 300
        matches.get("metron:series:old").shouldBeNull()
    }

    @Test
    fun `an entry made for a match can go without taking the match`() = runTest {
        db.insertManga(5, favorite = false)
        matches.put(match(mangaId = 5))

        db.exec("DELETE FROM mangas WHERE _id = 5")

        matches.get("metron:series:1").shouldNotBeNull().let {
            it.mangaId.shouldBeNull()
            it.mangaUrl shouldBe "/series/absolute-batman"
        }
    }

    @Test
    fun `the entry made for a found series stays out of the library, and one in it stays in it`() = runTest {
        val mangas = MangaRepositoryImpl(db.database)
        db.insertManga(1, url = "/series/in-library", favorite = true, title = "Batman (2025-)")

        val found = Manga.create().copy(source = 1, url = "/series/absolute-batman", title = "Absolute Batman (2024-)")
        val inLibrary = Manga.create().copy(source = 1, url = "/series/in-library", title = "Batman (2025-)")
        val (made, kept) = mangas.insertNetworkManga(listOf(found, inLibrary))

        made.favorite shouldBe false
        kept.id shouldBe 1
        kept.favorite shouldBe true
        db.database.mangasQueries.getFavorites().awaitAsOne().url shouldBe "/series/in-library"
    }

    @Test
    fun `migration 19 adds the match table`() = runTest {
        db.exec("DROP TABLE start_source_matches")

        Database.Schema.synchronous().migrate(db.driver, 19, 20)

        matches.put(match())
        matches.get("metron:series:1") shouldBe match()
    }
}
