package tachiyomi.data.sync

import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.db.SqlDriver
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.data.Database
import tachiyomi.domain.sync.SyncChanges
import tachiyomi.domain.sync.SyncMerge
import tachiyomi.domain.sync.SyncRepository
import tachiyomi.domain.sync.SyncResult
import tachiyomi.domain.sync.SyncSnapshot

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class SyncRepositoryImpl(
    private val database: Database,
    private val driver: SqlDriver,
) : SyncRepository {

    private val queries get() = database.sync_stateQueries
    private val reader = SyncSnapshotReader(database)

    override suspend fun enable() {
        if (queries.control().awaitAsOne().triggers_version == SyncTriggers.VERSION) return
        database.transaction {
            dropTriggers()
            SyncTriggers.all.forEach { driver.execute(null, it.sql, 0).await() }
            queries.setTriggersVersion(SyncTriggers.VERSION)
        }
    }

    override suspend fun disable() {
        database.transaction {
            dropTriggers()
            queries.deleteAllStates()
            queries.setTriggersVersion(0)
        }
    }

    override suspend fun isEnabled(): Boolean = queries.control().awaitAsOne().triggers_version > 0

    override suspend fun snapshot(): SyncSnapshot = reader.read()

    override suspend fun merge(remote: SyncSnapshot): SyncResult = database.transactionWithResult {
        val local = reader.read()
        val merged = SyncMerge.merge(local, remote)
        queries.setApplying(1)
        val written = SyncSnapshotWriter(database).write(local, merged)
        queries.setApplying(0)
        val after = SyncSnapshot(local.records + written.keys.associateWith { merged.records.getValue(it) })
        SyncResult(
            merged = merged,
            here = SyncChanges.between(local, after),
            there = SyncChanges.between(remote, merged),
            uploadNeeded = merged != remote,
            pending = written.pending,
        )
    }

    /**
     * Counts what the merge would write as if all of it could be: a chapter this device has not
     * fetched yet shows here as a change, though the merge keeps it waiting.
     */
    override suspend fun preview(remote: SyncSnapshot): SyncResult {
        val local = reader.read()
        val merged = SyncMerge.merge(local, remote)
        return SyncResult(
            merged = merged,
            here = SyncChanges.between(local, merged),
            there = SyncChanges.between(remote, merged),
            uploadNeeded = merged != remote,
            pending = 0,
        )
    }

    /** Pins the triggers' clock, for tests that need to say which change came first. */
    internal suspend fun setClock(now: Long?) {
        queries.setFixedNow(now)
    }

    private suspend fun dropTriggers() {
        SyncTriggers.names.forEach { driver.execute(null, "DROP TRIGGER IF EXISTS $it", 0).await() }
    }
}
