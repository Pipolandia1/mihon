package tachiyomi.data.readingstats

import app.cash.sqldelight.async.coroutines.awaitAsList
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.data.Database
import tachiyomi.domain.readingstats.comics.AutoState
import tachiyomi.domain.readingstats.comics.AutoStatus
import tachiyomi.domain.readingstats.repository.AutoMetadataRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class AutoMetadataRepositoryImpl(
    private val database: Database,
) : AutoMetadataRepository {

    private val queries get() = database.comic_auto_metadataQueries

    override suspend fun all(): List<AutoState> = queries.allStates().awaitAsList().map {
        AutoState(it.manga_id, AutoStatus.of(it.status), it.series_id, it.chapters.toInt(), it.checked_at)
    }

    override suspend fun put(state: AutoState) {
        queries.putState(state.mangaId, state.status.id, state.seriesId, state.chapters.toLong(), state.checkedAt)
    }
}
