package tachiyomi.data.start

import app.cash.sqldelight.async.coroutines.synchronous
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.readingstats.ComicMetadataRepositoryImpl
import tachiyomi.data.readingstats.TestDatabase
import tachiyomi.domain.readingstats.comics.ComicField
import tachiyomi.domain.readingstats.comics.MetadataOrigin
import tachiyomi.domain.readingstats.comics.MetadataValue
import tachiyomi.domain.start.ReleaseItem
import tachiyomi.domain.start.ReleaseOrigin
import tachiyomi.domain.start.ReleaseWeek
import tachiyomi.domain.start.ReleaseWeekList

/** The week's releases kept on the device (docs/HOME_PLAN.md §8, H6), on real SQLite. */
class ReleaseRepositoryImplTest {

    private val db = TestDatabase()
    private val releases = ReleaseRepositoryImpl(db.database)
    private val week = ReleaseWeek(2026, 39)

    private fun item(id: Long, number: String, variantOf: String? = null) = ReleaseItem(
        key = "metron:issue:$id",
        seriesKey = "metron:series:1",
        seriesName = "Absolute Batman",
        seriesYear = 2024,
        number = number,
        storeDate = "2026-09-23",
        coverUrl = "https://img/$id.jpg",
        variantOf = variantOf,
    )

    @Test
    fun `a week's list is kept in its order, and a new fetch replaces it`() = runTest {
        releases.getWeek(week).shouldBeNull()

        releases.replaceWeek(ReleaseWeekList(week, ReleaseOrigin.GCD, 10, listOf(item(2, "24"), item(1, "23"))))
        releases.replaceWeek(ReleaseWeekList(week, ReleaseOrigin.METRON, 20, listOf(item(3, "24"))))

        releases.subscribeWeek(week).first().shouldNotBeNull().let {
            it.origin shouldBe ReleaseOrigin.METRON
            it.fetchedAt shouldBe 20
            it.items shouldBe listOf(item(3, "24"))
        }
    }

    @Test
    fun `weeks are apart, and an empty week is still a fetched week`() = runTest {
        releases.replaceWeek(ReleaseWeekList(week, ReleaseOrigin.METRON, 10, listOf(item(1, "24"))))
        releases.replaceWeek(ReleaseWeekList(week.plus(1), ReleaseOrigin.METRON, 11, emptyList()))

        releases.getWeek(week).shouldNotBeNull().items.size shouldBe 1
        releases.getWeek(week.plus(1)).shouldNotBeNull().items shouldBe emptyList()
        releases.getWeek(week.plus(2)).shouldBeNull()
    }

    @Test
    fun `a series' own catalogue ids reach its work, and an issue's do not`() = runTest {
        db.insertManga(1)
        db.insertChapter(10, mangaId = 1)
        val metadata = ComicMetadataRepositoryImpl(db.database)
        metadata.confirm(
            1,
            listOf(
                MetadataValue(ComicField.METRON_ID, null, listOf("3001"), MetadataOrigin.METRON),
                MetadataValue(ComicField.GCD_ID, null, listOf("216143"), MetadataOrigin.GCD),
                // The issue's own id, per chapter: not the series'.
                MetadataValue(ComicField.METRON_ID, 10, listOf("98110"), MetadataOrigin.METRON),
            ),
        )

        val work = StartRepositoryImpl(db.database).subscribeWorks().first().single()

        work.catalogIds shouldBe setOf("metron:series:3001", "gcd:series:216143")
    }

    @Test
    fun `migration 21 adds the release tables`() = runTest {
        db.exec("DROP TABLE release_items")
        db.exec("DROP TABLE release_weeks")

        Database.Schema.synchronous().migrate(db.driver, 21, 22)

        releases.replaceWeek(ReleaseWeekList(week, ReleaseOrigin.METRON, 10, listOf(item(1, "24"))))
        releases.getWeek(week).shouldNotBeNull().items.single().number shouldBe "24"
    }
}
