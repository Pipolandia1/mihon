package tachiyomi.domain.readingstats.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

@Inject
@SingleIn(AppScope::class)
class ReadingStatsPreferences(
    preferenceStore: PreferenceStore,
) {

    /** Whether reading sessions and completions are recorded. What is already recorded stays. */
    val recordingEnabled: Preference<Boolean> = preferenceStore.getBoolean("reading_stats_recording", true)
}
