package eu.kanade.tachiyomi.data.backup.restore.restorers

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupReadingStats
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.readingstats.repository.ComicMetadataRepository
import tachiyomi.domain.readingstats.repository.ReadingPlanRepository
import tachiyomi.domain.readingstats.repository.ReadingStatsRepository

/** Adds a backup's reading to this device's. Restoring the same backup again changes nothing. */
@Inject
class ReadingStatsRestorer(
    private val repository: ReadingStatsRepository,
    private val planRepository: ReadingPlanRepository,
    private val metadataRepository: ComicMetadataRepository,
) {

    /** Returns why the restore failed, or null if it did not. */
    suspend operator fun invoke(stats: BackupReadingStats): String? {
        return try {
            repository.importSessions(stats.sessions.map { it.toSnapshot() })
            repository.importProgress(stats.chapterProgress.map { it.toProgress() })
            repository.importRecordingStartedAt(stats.recordingStartedAt)
            planRepository.importGoals(stats.goals.mapNotNull { it.toGoal() })
            // Collections first: a plan that reads a standalone collection finds it by its uuid.
            planRepository.importCollections(stats.collections.map { it.toCollection() })
            planRepository.importPlans(stats.plans.map { it.toPlan() })
            metadataRepository.import(stats.comicMetadata.mapNotNull { it.toExported() })
            null
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            e.message ?: e.javaClass.simpleName
        }
    }
}
