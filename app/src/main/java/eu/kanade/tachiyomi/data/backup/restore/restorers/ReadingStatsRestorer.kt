package eu.kanade.tachiyomi.data.backup.restore.restorers

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupReadingStats
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.readingstats.repository.ReadingPlanRepository
import tachiyomi.domain.readingstats.repository.ReadingStatsRepository

/** Adds a backup's reading to this device's. Restoring the same backup again changes nothing. */
@Inject
class ReadingStatsRestorer(
    private val repository: ReadingStatsRepository,
    private val planRepository: ReadingPlanRepository,
) {

    /** Returns why the restore failed, or null if it did not. */
    suspend operator fun invoke(stats: BackupReadingStats): String? {
        return try {
            repository.importSessions(stats.sessions.map { it.toSnapshot() })
            repository.importProgress(stats.chapterProgress.map { it.toProgress() })
            repository.importRecordingStartedAt(stats.recordingStartedAt)
            planRepository.importGoals(stats.goals.mapNotNull { it.toGoal() })
            planRepository.importPlans(stats.plans.map { it.toPlan() })
            null
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            e.message ?: e.javaClass.simpleName
        }
    }
}
