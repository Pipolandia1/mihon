package eu.kanade.tachiyomi.readingstats.comics.online

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.readingstats.service.ReadingStatsPreferences
import tachiyomi.domain.start.StartRepository
import java.util.concurrent.TimeUnit

/** A run of automatic comic metadata ([AutoMetadataFetcher], H13), with a connection. */
class AutoMetadataJob(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        (applicationContext.appGraph as AutoMetadataBindings).autoMetadata.run()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logcat(LogPriority.WARN, e) { "ComicAuto: the run failed" }
        Result.retry()
    }

    companion object {
        private const val DAILY = "ComicAutoMetadata"
        private const val SOON = "ComicAutoMetadataSoon"

        /** How often a run comes on its own: a day, for the issues the sources added. */
        private const val RUN_HOURS = 24L

        /** How long a run asked for waits, so the works added or opened together are one run. */
        private const val SOON_DELAY_S = 15L

        private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Keeps the daily run scheduled; one already scheduled is left as it is. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AutoMetadataJob>(RUN_HOURS, TimeUnit.HOURS)
                .setConstraints(connected)
                .addTag(DAILY)
                .build()
            context.workManager.enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** A run shortly; after the one running, when one is. */
        fun runSoon(context: Context) {
            val request = OneTimeWorkRequestBuilder<AutoMetadataJob>()
                .setInitialDelay(SOON_DELAY_S, TimeUnit.SECONDS)
                .setConstraints(connected)
                .addTag(SOON)
                .build()
            context.workManager.enqueueUniqueWork(SOON, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }

        fun cancel(context: Context) {
            context.workManager.cancelUniqueWork(DAILY)
            context.workManager.cancelUniqueWork(SOON)
        }
    }
}

/** What the job needs from the app's graph, contributed by the package instead of listed in it. */
@ContributesTo(AppScope::class)
interface AutoMetadataBindings {
    val autoMetadata: AutoMetadataFetcher
}

/**
 * When the runs come (H13): one when the app opens, one a day, and one whenever a work is added to the
 * library or opened for the first time; none while the switch is off. Kept by the Início, which opens
 * with the app; leaving it stops the watching, never a run.
 */
@Inject
class AutoMetadataScheduler(
    private val context: Context,
    private val preferences: ReadingStatsPreferences,
    private val startRepository: StartRepository,
) {

    suspend fun keepUp() {
        preferences.autoMetadata.changes().collectLatest { on ->
            if (!on) {
                AutoMetadataJob.cancel(context)
                logcat(LogPriority.INFO) { "ComicAuto: switched off, runs cancelled" }
                return@collectLatest
            }
            AutoMetadataJob.schedule(context)
            AutoMetadataJob.runSoon(context)
            var known: Set<Long>? = null
            startRepository.subscribeWorks()
                .map { works ->
                    works.filter { it.inLibrary || it.chaptersRead > 0 || it.sessions > 0 }.map { it.mangaId }.toSet()
                }
                .distinctUntilChanged()
                .collect { ids ->
                    val before = known
                    known = ids
                    if (before != null && (ids - before).isNotEmpty()) {
                        logcat(LogPriority.INFO) { "ComicAuto: new works ${ids - before}, a run soon" }
                        AutoMetadataJob.runSoon(context)
                    }
                }
        }
    }
}
