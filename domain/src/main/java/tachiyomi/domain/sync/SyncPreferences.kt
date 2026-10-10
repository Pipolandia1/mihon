package tachiyomi.domain.sync

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * This device's sync settings (docs/DESKTOP_PLAN.md §3.4). All app state: none goes into a backup,
 * because each device connects on its own.
 */
@Inject
@SingleIn(AppScope::class)
class SyncPreferences(
    preferenceStore: PreferenceStore,
) {

    /** The Google account whose Drive holds the sync file; empty while this device is not connected. */
    val account: Preference<String> = preferenceStore.getString(Preference.appStateKey("sync_account"), "")

    /** Whether the first sync, the one confirmed by hand after its summary, was done: nothing syncs by itself before. */
    val firstSyncDone: Preference<Boolean> =
        preferenceStore.getBoolean(Preference.appStateKey("sync_first_done"), false)

    /** When the last sync that reached Drive ended, epoch ms; 0 before the first. */
    val lastSyncAt: Preference<Long> = preferenceStore.getLong(Preference.appStateKey("sync_last_at"), 0L)

    /** The last syncs, newest first, as [SyncLog]. */
    val log: Preference<String> = preferenceStore.getString(Preference.appStateKey("sync_log"), "")
}

/** One sync as the Sync screen lists it. */
@Serializable
data class SyncLogEntry(
    val at: Long,
    val reason: SyncReason,
    val here: Int = 0,
    val sent: Int = 0,
    val pending: Int = 0,
    val error: SyncError? = null,
    val detail: String? = null,
)

enum class SyncReason { FIRST, MANUAL, APP_OPEN, READING }

enum class SyncError { CONSENT, NETWORK, DRIVE, NEWER_FORMAT, CONFLICT, BACKUP }

/** The history kept in [SyncPreferences.log]: the newest [MAX] syncs. */
object SyncLog {
    const val MAX = 20

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(SyncLogEntry.serializer())

    fun decode(value: String): List<SyncLogEntry> =
        if (value.isBlank()) {
            emptyList()
        } else {
            runCatching {
                json.decodeFromString(serializer, value)
            }.getOrDefault(emptyList())
        }

    fun add(value: String, entry: SyncLogEntry): String =
        json.encodeToString(serializer, (listOf(entry) + decode(value)).take(MAX))
}
