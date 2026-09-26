package tachiyomi.data.start

import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.data.Database
import tachiyomi.domain.start.CatalogCache

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class CatalogCacheImpl(
    private val database: Database,
) : CatalogCache {

    private val queries get() = database.start_catalogQueries

    override suspend fun get(key: String, nowMs: Long): String? = queries.getCached(key, nowMs).awaitAsOneOrNull()

    /** Keeps [body] for [keepMs], and lets go of every answer that has expired. */
    override suspend fun put(key: String, body: String, nowMs: Long, keepMs: Long) {
        database.transaction {
            queries.deleteExpiredCache(nowMs)
            queries.putCached(key, body, nowMs, nowMs + keepMs)
        }
    }
}
