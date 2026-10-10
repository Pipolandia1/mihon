package tachiyomi.domain.sync

/** This device's side of sync: what it has, and taking in what the others have (docs/DESKTOP_PLAN.md §3.4). */
interface SyncRepository {

    /**
     * Starts recording when each synced field changes. Until then nothing is recorded and the app
     * writes exactly as it did before sync existed; what is already here counts as written at time 0.
     */
    suspend fun enable()

    /** Stops recording and forgets every recorded time. */
    suspend fun disable()

    suspend fun isEnabled(): Boolean

    /** What this device has now. */
    suspend fun snapshot(): SyncSnapshot

    /**
     * Merges [remote] with what this device has and writes the result here, in one transaction: a
     * change the user makes meanwhile is either part of the merge or comes after it, never lost.
     */
    suspend fun merge(remote: SyncSnapshot): SyncResult

    /** What [merge] would change, without writing anything: the summary shown before the first sync. */
    suspend fun preview(remote: SyncSnapshot): SyncResult
}

data class SyncResult(
    /** What every device should have now: the next file to upload. */
    val merged: SyncSnapshot,
    /** What changed on this device. */
    val here: SyncChanges,
    /** What the merge adds to the remote file. */
    val there: SyncChanges,
    /** Whether [merged] differs from the remote file at all, tombstones included. */
    val uploadNeeded: Boolean,
    /**
     * Records this device cannot take yet and keeps for later: a chapter of an entry it has not
     * refreshed, the history of such a chapter.
     */
    val pending: Int,
)
