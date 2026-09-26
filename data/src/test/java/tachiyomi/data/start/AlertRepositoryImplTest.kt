package tachiyomi.data.start

import app.cash.sqldelight.async.coroutines.synchronous
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.readingstats.TestDatabase
import tachiyomi.domain.start.AlertWhat
import tachiyomi.domain.start.Follow
import tachiyomi.domain.start.FollowKind
import tachiyomi.domain.start.Notified
import tachiyomi.domain.start.ReleaseItem

/** Follows and what was told (docs/HOME_PLAN.md §8.3, H7), on real SQLite. */
class AlertRepositoryImplTest {

    private val db = TestDatabase()
    private val alerts = AlertRepositoryImpl(db.database)

    private val series =
        Follow(FollowKind.SERIES, "metron:series:3001", "Absolute Batman", "Absolute Batman", 2024, null, 10)
    private val character = Follow(FollowKind.CHARACTER, "metron:character:5", "Batman", createdAt = 20)
    private val release =
        ReleaseItem("metron:issue:1", "metron:series:3001", "Absolute Batman", 2024, "24", "2026-09-23", null)

    @Test
    fun `follows are kept, one per kind and key, and let go`() = runTest {
        alerts.follow(series)
        alerts.follow(series.copy(label = "Absolute Batman (again)"))
        alerts.follow(character)

        alerts.subscribeFollows().first() shouldBe listOf(series.copy(label = "Absolute Batman (again)"), character)

        alerts.unfollow(FollowKind.SERIES, series.key)
        alerts.getFollows() shouldBe listOf(character)
    }

    @Test
    fun `a release is told once for each kind of alert, with what finding it again needs`() = runTest {
        alerts.markNotified(Notified(release, AlertWhat.OUT, 100))
        alerts.markNotified(Notified(release, AlertWhat.OUT, 200))
        alerts.markNotified(Notified(release, AlertWhat.AVAILABLE, 300))

        alerts.notified(since = 0).map { it.what to it.at } shouldBe
            listOf(AlertWhat.OUT to 100L, AlertWhat.AVAILABLE to 300L)
        alerts.notified(since = 0).first().release shouldBe release
        alerts.notified(since = 301).shouldBeEmpty()
    }

    @Test
    fun `migration 22 adds the alert tables`() = runTest {
        db.exec("DROP TABLE release_follows")
        db.exec("DROP TABLE release_notified")

        Database.Schema.synchronous().migrate(db.driver, 22, 23)

        alerts.follow(series)
        alerts.markNotified(Notified(release, AlertWhat.OUT, 100))
        alerts.getFollows() shouldBe listOf(series)
        alerts.notified(0).single().what shouldBe AlertWhat.OUT
    }
}
