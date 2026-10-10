package tachiyomi.data.sync

import tachiyomi.domain.sync.SyncMerge
import tachiyomi.domain.sync.SyncRepository
import tachiyomi.domain.sync.SyncResult
import tachiyomi.domain.sync.SyncSnapshot

/**
 * One sync with the file every device shares (docs/DESKTOP_PLAN.md §3.4): download it, merge it with
 * this device and write the merge here, then upload the merge unless it is what the file already
 * holds.
 *
 * Drive has no write-if-unchanged, so the version read with the file is checked again just before the
 * upload. If another device wrote meanwhile, everything starts over with its file: merging is
 * idempotent, so what was already written here is merged again unchanged. The window left is the
 * moment between that check and the upload, between two devices of the same reader.
 *
 * Two devices creating the file at the same time leave two copies; both are merged and the extra
 * one deleted.
 */
class DriveSync(
    private val repository: SyncRepository,
    private val files: RemoteFiles,
    private val device: String,
    private val now: () -> Long,
) {

    class Conflict : Exception("Another device kept writing the sync file")

    data class Outcome(val result: SyncResult, val uploaded: Boolean, val attempts: Int)

    data class Preview(val result: SyncResult, val fileExists: Boolean)

    /** What the first sync would do on both sides, without writing anything anywhere. */
    suspend fun preview(): Preview {
        val copies = files.list()
        return Preview(repository.preview(download(copies)), fileExists = copies.isNotEmpty())
    }

    suspend fun sync(): Outcome {
        repeat(MAX_ATTEMPTS) { attempt ->
            val copies = files.list()
            val result = repository.merge(download(copies))
            val current = copies.firstOrNull()
            val upToDate = !result.uploadNeeded && copies.size <= 1
            if (upToDate) return Outcome(result, uploaded = false, attempts = attempt + 1)

            if (current != null && files.version(current.id) != current.version) return@repeat
            val bytes = SyncSnapshotCodec.encode(result.merged, now(), device)
            if (current == null) files.create(bytes) else files.update(current.id, bytes)
            copies.drop(1).forEach { files.delete(it.id) }
            return Outcome(result, uploaded = true, attempts = attempt + 1)
        }
        throw Conflict()
    }

    private suspend fun download(copies: List<RemoteFiles.File>): SyncSnapshot =
        copies.fold(SyncSnapshot.EMPTY) { merged, file ->
            SyncMerge.merge(merged, SyncSnapshotCodec.decode(files.download(file.id)).snapshot)
        }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
