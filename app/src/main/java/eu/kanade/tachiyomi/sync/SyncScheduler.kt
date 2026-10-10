package eu.kanade.tachiyomi.sync

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.domain.sync.SyncPreferences
import tachiyomi.domain.sync.SyncReason
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * When syncs happen by themselves (decision 3 of docs/DESKTOP_PLAN.md): when the app opens and when
 * the reader finishes reading. Requests close together become one sync, after the last of them:
 * finishing a chapter and closing the reader right after sync once, once their writes are done.
 */
@Inject
@SingleIn(AppScope::class)
class SyncScheduler(
    private val runner: SyncRunner,
    private val preferences: SyncPreferences,
) {
    // Not a screen's scope: the sync after the reader closes outlives the reader.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var waiting: Job? = null

    @Synchronized
    fun request(reason: SyncReason, after: Duration = READING_DELAY) {
        if (!preferences.firstSyncDone.get()) return
        waiting?.cancel()
        waiting = scope.launch {
            delay(after)
            // Its own job: a request arriving while the sync runs only cancels the wait, never the sync.
            scope.launch { runner.sync(reason) }
        }
    }

    companion object {
        /** Long enough for the reader to have written the chapter's read state and the session. */
        val READING_DELAY = 10.seconds
        val APP_OPEN_DELAY = 3.seconds
    }
}
