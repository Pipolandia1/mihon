package tachiyomi.data.start

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import tachiyomi.data.Database
import tachiyomi.data.Release_items
import tachiyomi.data.Release_weeks
import tachiyomi.data.subscribeToList
import tachiyomi.data.subscribeToOneOrNull
import tachiyomi.domain.start.ReleaseItem
import tachiyomi.domain.start.ReleaseOrigin
import tachiyomi.domain.start.ReleaseRepository
import tachiyomi.domain.start.ReleaseWeek
import tachiyomi.domain.start.ReleaseWeekList

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ReleaseRepositoryImpl(
    private val database: Database,
) : ReleaseRepository {

    private val queries get() = database.start_releasesQueries

    override fun subscribeWeek(week: ReleaseWeek): Flow<ReleaseWeekList?> = combine(
        queries.getWeek(week.year.toLong(), week.week.toLong()).subscribeToOneOrNull(),
        queries.weekItems(week.year.toLong(), week.week.toLong()).subscribeToList(),
    ) { fetched, items -> fetched?.let { listOf(it, items) } }

    override suspend fun getWeek(week: ReleaseWeek): ReleaseWeekList? {
        val fetched = queries.getWeek(week.year.toLong(), week.week.toLong()).awaitAsOneOrNull() ?: return null
        return listOf(fetched, queries.weekItems(week.year.toLong(), week.week.toLong()).awaitAsList())
    }

    /** The week's list as fetched now, in place of what was kept. */
    override suspend fun replaceWeek(list: ReleaseWeekList) {
        val year = list.week.year.toLong()
        val week = list.week.week.toLong()
        database.transaction {
            queries.deleteWeekItems(year, week)
            list.items.forEachIndexed { position, item ->
                queries.insertItem(
                    releaseKey = item.key,
                    year = year,
                    week = week,
                    seriesKey = item.seriesKey,
                    seriesName = item.seriesName,
                    seriesYear = item.seriesYear?.toLong(),
                    number = item.number,
                    storeDate = item.storeDate,
                    coverUrl = item.coverUrl,
                    variantOf = item.variantOf,
                    position = position.toLong(),
                )
            }
            queries.upsertWeek(year, week, list.origin.id, list.fetchedAt)
        }
    }

    private fun listOf(fetched: Release_weeks, items: List<Release_items>) = ReleaseWeekList(
        week = ReleaseWeek(fetched.iso_year.toInt(), fetched.iso_week.toInt()),
        origin = ReleaseOrigin.of(fetched.origin),
        fetchedAt = fetched.fetched_at,
        items = items.map { row ->
            ReleaseItem(
                key = row.release_key,
                seriesKey = row.series_key,
                seriesName = row.series_name,
                seriesYear = row.series_year?.toInt(),
                number = row.number,
                storeDate = row.store_date,
                coverUrl = row.cover_url,
                variantOf = row.variant_of,
            )
        },
    )
}
