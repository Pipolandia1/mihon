package eu.kanade.tachiyomi.data.backup.create.creators

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupChapterProgress
import eu.kanade.tachiyomi.data.backup.models.BackupComicMetadata
import eu.kanade.tachiyomi.data.backup.models.BackupReadingCollection
import eu.kanade.tachiyomi.data.backup.models.BackupReadingGoal
import eu.kanade.tachiyomi.data.backup.models.BackupReadingPlan
import eu.kanade.tachiyomi.data.backup.models.BackupReadingSession
import eu.kanade.tachiyomi.data.backup.models.BackupReadingStats
import eu.kanade.tachiyomi.data.backup.models.BackupStartFeedback
import kotlinx.coroutines.flow.first
import tachiyomi.domain.readingstats.repository.ComicMetadataRepository
import tachiyomi.domain.readingstats.repository.ReadingPlanRepository
import tachiyomi.domain.readingstats.repository.ReadingStatsRepository
import tachiyomi.domain.start.StartRepository

@Inject
class ReadingStatsBackupCreator(
    private val repository: ReadingStatsRepository,
    private val planRepository: ReadingPlanRepository,
    private val metadataRepository: ComicMetadataRepository,
    private val startRepository: StartRepository,
) {

    suspend operator fun invoke(): BackupReadingStats {
        val (workFeedback, nameFeedback) = startRepository.exportFeedback()
        return BackupReadingStats(
            recordingStartedAt = repository.getRecordingStartedAt(),
            sessions = repository.exportSessions().map(BackupReadingSession::of),
            chapterProgress = repository.exportProgress().map { (key, progress) ->
                BackupChapterProgress.of(key, progress)
            },
            goals = planRepository.subscribeGoals().first().map(BackupReadingGoal::of),
            plans = planRepository.exportPlans().map { (plan, items) -> BackupReadingPlan.of(plan, items) },
            comicMetadata = metadataRepository.export().map(BackupComicMetadata::of),
            collections = planRepository.exportCollections().map { (collection, items) ->
                BackupReadingCollection.of(collection, items)
            },
            startFeedback = workFeedback.map { BackupStartFeedback.of(it.feedback, it.sourceId, it.mangaUrl) } +
                nameFeedback.map { BackupStartFeedback.of(it) },
        )
    }
}
