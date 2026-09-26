package tachiyomi.data.start

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tachiyomi.data.Database
import tachiyomi.data.Release_follows
import tachiyomi.data.subscribeToList
import tachiyomi.domain.start.AlertRepository
import tachiyomi.domain.start.AlertWhat
import tachiyomi.domain.start.Follow
import tachiyomi.domain.start.FollowKind
import tachiyomi.domain.start.Notified
import tachiyomi.domain.start.ReleaseItem

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AlertRepositoryImpl(
    private val database: Database,
) : AlertRepository {

    private val queries get() = database.start_alertsQueries

    override fun subscribeFollows(): Flow<List<Follow>> = queries.allFollows().subscribeToList().map {
        it.map(::followOf)
    }

    override suspend fun getFollows(): List<Follow> = queries.allFollows().awaitAsList().map(::followOf)

    override suspend fun follow(follow: Follow) {
        queries.upsertFollow(
            kind = follow.kind.id,
            followKey = follow.key,
            label = follow.label,
            seriesName = follow.seriesName,
            seriesYear = follow.seriesYear?.toLong(),
            number = follow.number,
            createdAt = follow.createdAt,
        )
    }

    override suspend fun unfollow(kind: FollowKind, key: String) {
        queries.deleteFollow(kind.id, key)
    }

    override suspend fun notified(since: Long): List<Notified> = queries.notifiedSince(since).awaitAsList().map { row ->
        Notified(
            release = ReleaseItem(
                key = row.release_key,
                seriesKey = row.series_key,
                seriesName = row.series_name,
                seriesYear = row.series_year?.toInt(),
                number = row.number,
                storeDate = row.store_date,
                coverUrl = null,
            ),
            what = AlertWhat.entries.first { it.id == row.what },
            at = row.notified_at,
        )
    }

    /** Once per release and kind of alert: a second time is ignored. */
    override suspend fun markNotified(notified: Notified) {
        val release = notified.release
        queries.insertNotified(
            releaseKey = release.key,
            what = notified.what.id,
            seriesKey = release.seriesKey,
            seriesName = release.seriesName,
            seriesYear = release.seriesYear?.toLong(),
            number = release.number,
            storeDate = release.storeDate,
            notifiedAt = notified.at,
        )
    }

    private fun followOf(row: Release_follows) = Follow(
        kind = FollowKind.of(row.kind),
        key = row.follow_key,
        label = row.label,
        seriesName = row.series_name,
        seriesYear = row.series_year?.toInt(),
        number = row.number,
        createdAt = row.created_at,
    )
}
