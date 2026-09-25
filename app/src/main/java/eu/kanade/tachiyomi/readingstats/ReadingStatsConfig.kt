package eu.kanade.tachiyomi.readingstats

import tachiyomi.domain.readingstats.tracker.ReadingTrackerConfig
import kotlin.time.Duration.Companion.seconds

object ReadingStatsConfig {

    val TRACKER = ReadingTrackerConfig()

    /** How often the reader checks for silence and saves progress while it is in front. */
    val TICK_INTERVAL = 5.seconds

    /** How many recently read entries "Continue reading" offers. */
    const val CONTINUE_READING_COUNT = 3

    /**
     * How many of the most recently read works "continue reading" looks at (amendment H2). Each costs
     * a query of its chapters; a work further down the history is looked at only when a new issue
     * arrived for it.
     */
    const val CONTINUE_READING_SCAN = 40
}
