package tachiyomi.domain.readingstats.interactor

import dev.zacsweers.metro.Inject
import kotlinx.datetime.TimeZone
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.readingstats.metrics.ReadingCalendar
import tachiyomi.domain.readingstats.repository.ReadingStatsRepository
import tachiyomi.domain.readingstats.service.ReadingStatsPreferences
import kotlin.time.Clock

/**
 * Records chapters marked as read or unread by hand. A hand-marked chapter counts as completed on
 * the day it was marked, apart from measured reading: it carries no time and no pages.
 */
@Inject
class RecordManualReadStatus(
    private val repository: ReadingStatsRepository,
    private val preferences: ReadingStatsPreferences,
) {

    suspend fun await(read: Boolean, chapterIds: List<Long>) {
        if (chapterIds.isEmpty()) return
        try {
            if (read) {
                if (!preferences.recordingEnabled.get()) return
                val now = Clock.System.now().toEpochMilliseconds()
                val today = ReadingCalendar.dateOf(now, TimeZone.currentSystemDefault())
                repository.markCompletedManually(chapterIds, now, today)
            } else {
                // Undoing a mark is a correction, whatever the preference says.
                repository.clearManualCompletion(chapterIds)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }
}
