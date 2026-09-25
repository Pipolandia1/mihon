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

    /** The user's own Metron API token; empty while Metron is not used. Private: kept out of backups. */
    val metronToken: Preference<String> =
        preferenceStore.getString(Preference.privateKey("reading_stats_metron_token"), "")

    /**
     * The user's own Grand Comics Database login, optional: without it the GCD allows 30 requests an
     * hour, with it 2,000 a day. Private: kept out of backups.
     */
    val gcdUsername: Preference<String> =
        preferenceStore.getString(Preference.privateKey("reading_stats_gcd_username"), "")
    val gcdPassword: Preference<String> =
        preferenceStore.getString(Preference.privateKey("reading_stats_gcd_password"), "")

    /** Whether the stats leave out reminders about series left unread for a while. */
    val hideStalledReminders: Preference<Boolean> =
        preferenceStore.getBoolean("reading_stats_hide_stalled", false)
}
