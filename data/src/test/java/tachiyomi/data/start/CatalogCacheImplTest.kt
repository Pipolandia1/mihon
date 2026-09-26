package tachiyomi.data.start

import app.cash.sqldelight.async.coroutines.synchronous
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.readingstats.TestDatabase

/** The catalogue's answers kept for the Início (docs/HOME_PLAN.md §7.5, H5), on real SQLite. */
class CatalogCacheImplTest {

    private val db = TestDatabase()
    private val cache = CatalogCacheImpl(db.database)

    @Test
    fun `an answer is kept until it expires, and a newer one replaces it`() = runTest {
        cache.put("metron:creator:tom king", "11", nowMs = 100, keepMs = 50)

        cache.get("metron:creator:tom king", nowMs = 149) shouldBe "11"
        cache.get("metron:creator:tom king", nowMs = 150).shouldBeNull()

        cache.put("metron:creator:tom king", "12", nowMs = 200, keepMs = 50)
        cache.get("metron:creator:tom king", nowMs = 210) shouldBe "12"
    }

    @Test
    fun `expired answers go when a new one is kept, even asked about as of before`() = runTest {
        cache.put("old", "1", nowMs = 0, keepMs = 10)
        cache.put("new", "2", nowMs = 20, keepMs = 10)

        cache.get("old", nowMs = 5).shouldBeNull()
        cache.get("new", nowMs = 25) shouldBe "2"
    }

    @Test
    fun `migration 20 adds the cache table`() = runTest {
        db.exec("DROP TABLE start_catalog_cache")

        Database.Schema.synchronous().migrate(db.driver, 20, 21)

        cache.put("k", "v", nowMs = 0, keepMs = 10)
        cache.get("k", nowMs = 1) shouldBe "v"
    }
}
