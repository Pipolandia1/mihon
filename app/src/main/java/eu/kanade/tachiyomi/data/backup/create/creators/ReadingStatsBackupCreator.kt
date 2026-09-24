package eu.kanade.tachiyomi.data.backup.create.creators

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupChapterProgress
import eu.kanade.tachiyomi.data.backup.models.BackupReadingSession
import eu.kanade.tachiyomi.data.backup.models.BackupReadingStats
import tachiyomi.domain.readingstats.repository.ReadingStatsRepository

@Inject
class ReadingStatsBackupCreator(
    private val repository: ReadingStatsRepository,
) {

    suspend operator fun invoke(): BackupReadingStats {
        return BackupReadingStats(
            recordingStartedAt = repository.getRecordingStartedAt(),
            sessions = repository.exportSessions().map(BackupReadingSession::of),
            chapterProgress = repository.exportProgress().map { (key, progress) ->
                BackupChapterProgress.of(key, progress)
            },
        )
    }
}
