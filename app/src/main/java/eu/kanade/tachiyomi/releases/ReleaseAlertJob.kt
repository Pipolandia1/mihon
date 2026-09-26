package eu.kanade.tachiyomi.releases

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import eu.kanade.tachiyomi.ui.start.StartConfig
import eu.kanade.tachiyomi.util.system.workManager
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.TimeUnit

/**
 * The alerts' check once a day (docs/HOME_PLAN.md §8.3, H7), while the reader follows something:
 * scheduled with the first follow, cancelled with the last. Mihon's own jobs are left as they are.
 */
class ReleaseAlertJob(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        (applicationContext.appGraph as ReleaseAlertBindings).releaseAlerts.check()
        Result.success()
    } catch (e: Exception) {
        logcat(LogPriority.WARN, e) { "StartFeed alerts: the daily check failed" }
        Result.retry()
    }

    companion object {
        private const val NAME = "StartReleaseAlerts"

        /** Keeps the daily check scheduled; one already scheduled is left as it is. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReleaseAlertJob>(StartConfig.ALERT_CHECK_HOURS, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(NAME)
                .build()
            context.workManager.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            context.workManager.cancelUniqueWork(NAME)
        }
    }
}

/** What the job needs from the app's graph, contributed by the package instead of listed in it. */
@ContributesTo(AppScope::class)
interface ReleaseAlertBindings {
    val releaseAlerts: ReleaseAlerts
}
