package eu.kanade.tachiyomi.sync

import android.os.Build
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.data.sync.DriveFiles
import tachiyomi.data.sync.DriveSync
import tachiyomi.data.sync.SyncSnapshotCodec
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.domain.sync.SyncError
import tachiyomi.domain.sync.SyncLog
import tachiyomi.domain.sync.SyncLogEntry
import tachiyomi.domain.sync.SyncPreferences
import tachiyomi.domain.sync.SyncReason
import tachiyomi.domain.sync.SyncRepository
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs the syncs of this device (docs/DESKTOP_PLAN.md §3.4), one at a time: Drive access, the first
 * sync with its backup, the later ones, and the history the Sync screen shows. Every sync is logged
 * as `Sync …` for the emulator checks.
 */
@Inject
@SingleIn(AppScope::class)
class SyncRunner(
    private val repository: SyncRepository,
    private val preferences: SyncPreferences,
    private val auth: GoogleDriveAuth,
    private val network: NetworkHelper,
    private val storageManager: StorageManager,
    private val backupCreator: BackupCreator.Factory,
) {
    private val mutex = Mutex()

    val running: StateFlow<Boolean>
        field = MutableStateFlow(false)

    sealed interface Outcome {
        val entry: SyncLogEntry

        data class Done(override val entry: SyncLogEntry) : Outcome

        data class Failed(override val entry: SyncLogEntry) : Outcome
    }

    private class NeedsConsent : Exception()

    private class BackupFailed(message: String?) : Exception(message)

    /** After the reader granted access: whose Drive it is. */
    suspend fun connect(token: String): String = withIOContext {
        val account = DriveFiles(network.client) { token }.account().orEmpty()
        preferences.account.set(account)
        logcat { "Sync connected to $account" }
        account
    }

    /** What the first sync would change here and in the Drive file; null when Google needs the reader again. */
    suspend fun preview(): DriveSync.Preview? = mutex.withLock {
        try {
            withDrive { it.preview() }
        } catch (_: NeedsConsent) {
            null
        }
    }

    /** The first sync, after the reader saw its summary: a full backup, then recording, then the sync. */
    suspend fun firstSync(): Outcome = run(SyncReason.FIRST) {
        backup()
        repository.enable()
    }.also { if (it is Outcome.Done) preferences.firstSyncDone.set(true) }

    /** A sync after the first one; null when this device is not connected. */
    suspend fun sync(reason: SyncReason): Outcome? {
        if (!preferences.firstSyncDone.get()) return null
        return run(reason)
    }

    /** Stops syncing here and forgets what was recorded. The Drive file stays for the other devices. */
    suspend fun disconnect() = mutex.withLock {
        repository.disable()
        preferences.firstSyncDone.set(false)
        preferences.account.set("")
        try {
            auth.revoke()
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Sync could not revoke Drive access" }
        }
        logcat { "Sync disconnected" }
    }

    private suspend fun run(reason: SyncReason, before: suspend () -> Unit = {}): Outcome = mutex.withLock {
        running.value = true
        val start = System.currentTimeMillis()
        try {
            before()
            val outcome = withDrive { it.sync() }
            val entry = SyncLogEntry(
                at = System.currentTimeMillis(),
                reason = reason,
                here = outcome.result.here.total,
                sent = if (outcome.uploaded) outcome.result.there.total else 0,
                pending = outcome.result.pending,
            )
            preferences.lastSyncAt.set(entry.at)
            logcat {
                "Sync $reason here=${outcome.result.here.counts} there=${outcome.result.there.counts} " +
                    "uploaded=${outcome.uploaded} attempts=${outcome.attempts} pending=${entry.pending} " +
                    "in ${entry.at - start}ms"
            }
            Outcome.Done(entry).also { log(it.entry) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val (error, detail) = when (e) {
                is NeedsConsent -> SyncError.CONSENT to null
                is BackupFailed -> SyncError.BACKUP to e.message
                is SyncSnapshotCodec.NewerFormatException -> SyncError.NEWER_FORMAT to null
                is DriveSync.Conflict -> SyncError.CONFLICT to null
                is DriveFiles.Failure -> SyncError.DRIVE to "${e.code}"
                is IOException -> SyncError.NETWORK to e.message
                else -> SyncError.DRIVE to e.message
            }
            logcat(LogPriority.ERROR, e) { "Sync $reason failed: $error" }
            Outcome.Failed(SyncLogEntry(System.currentTimeMillis(), reason, error = error, detail = detail))
                .also { log(it.entry) }
        } finally {
            running.value = false
        }
    }

    /** Runs [block] with Drive access; a token Drive refuses is dropped and a fresh one tried once. */
    private suspend fun <T> withDrive(block: suspend (DriveSync) -> T): T = withIOContext {
        var token = auth.token() ?: throw NeedsConsent()
        val drive = DriveSync(repository, DriveFiles(network.client) { token }, DEVICE, System::currentTimeMillis)
        try {
            block(drive)
        } catch (_: DriveFiles.Unauthorized) {
            auth.clear(token)
            token = auth.token() ?: throw NeedsConsent()
            block(drive)
        }
    }

    /** A full backup of this device in the automatic backups folder, under its own name so no rotation removes it. */
    private suspend fun backup() {
        val directory = storageManager.getAutomaticBackupsDirectory() ?: throw BackupFailed("no storage folder")
        val name = "mihon_before_sync_${SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ENGLISH).format(Date())}.tachibk"
        val file = directory.createFile(name) ?: throw BackupFailed("could not create $name")
        try {
            backupCreator.create(isAutoBackup = false).backup(file.uri, BackupOptions())
        } catch (e: Exception) {
            throw BackupFailed(e.message)
        }
        logcat { "Sync backup before the first sync: ${file.uri}" }
    }

    private fun log(entry: SyncLogEntry) {
        preferences.log.set(SyncLog.add(preferences.log.get(), entry))
    }

    private companion object {
        val DEVICE = "${Build.MANUFACTURER} ${Build.MODEL}"
    }
}
