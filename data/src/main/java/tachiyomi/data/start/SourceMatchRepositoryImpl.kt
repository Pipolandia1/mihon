package tachiyomi.data.start

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.data.Database
import tachiyomi.data.Start_source_matches
import tachiyomi.domain.start.SourceMatch
import tachiyomi.domain.start.SourceMatchRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SourceMatchRepositoryImpl(
    private val database: Database,
) : SourceMatchRepository {

    private val queries get() = database.start_matchesQueries

    override suspend fun get(catalogKey: String): SourceMatch? =
        queries.getMatch(catalogKey).awaitAsOneOrNull()?.toMatch()

    /** Keeps [match], and lets go of every answer that has expired. */
    override suspend fun put(match: SourceMatch) {
        database.transaction {
            queries.deleteExpired(match.checkedAt)
            queries.upsertMatch(
                catalogKey = match.catalogKey,
                sourceId = match.sourceId,
                mangaUrl = match.mangaUrl,
                mangaId = match.mangaId,
                matchedTitle = match.matchedTitle,
                triedSources = match.triedSources.joinToString(","),
                checkedAt = match.checkedAt,
                expiresAt = match.expiresAt,
            )
        }
    }

    private fun Start_source_matches.toMatch() = SourceMatch(
        catalogKey = catalog_key,
        sourceId = source_id,
        mangaUrl = manga_url,
        mangaId = manga_id,
        matchedTitle = matched_title,
        triedSources = tried_sources.split(",").mapNotNull { it.toLongOrNull() },
        checkedAt = checked_at,
        expiresAt = expires_at,
    )
}
