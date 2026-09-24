package eu.kanade.tachiyomi.data.backup.create.creators

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupChapterProgress
import eu.kanade.tachiyomi.data.backup.models.BackupReadingGoal
import eu.kanade.tachiyomi.data.backup.models.BackupReadingPlan
import eu.kanade.tachiyomi.data.backup.models.BackupReadingSession
import eu.kanade.tachiyomi.data.backup.models.BackupReadingStats
import kotlinx.coroutines.flow.first
import tachiyomi.domain.readingstats.repository.ReadingPlanRepository
import tachiyomi.domain.readingstats.repository.ReadingStatsRepository

@Inject
class ReadingStatsBackupCreator(
    private val repository: ReadingStatsRepository,
    private val planRepository: ReadingPlanRepository,
) {

    suspend operator fun invoke(): BackupReadingStats {
        return BackupReadingStats(
            recordingStartedAt = repository.getRecordingStartedAt(),
            sessions = repository.exportSessions().map(BackupReadingSession::of),
            chapterProgress = repository.exportProgress().map { (key, progress) ->
                BackupChapterProgress.of(key, progress)
            },
            goals = planRepository.subscribeGoals().first().map(BackupReadingGoal::of),
            plans = planRepository.exportPlans().map { (plan, items) -> BackupReadingPlan.of(plan, items) },
        )
    }
}
