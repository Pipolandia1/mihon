package eu.kanade.tachiyomi.ui.start

/** The numbers of the Início tab (docs/HOME_PLAN.md). */
object StartConfig {

    /** Unique among the tabs; the four upstream tabs use 0 to 4. */
    const val TAB_INDEX: UShort = 5u

    /** How many works "Continuar lendo" shows: the one to continue and three more. */
    const val CONTINUE_READING_COUNT = 4

    /** How long "Agora não" keeps a recommendation away. */
    const val LATER_DAYS = 30L

    /** How long the tab's state outlives its last collector, so a quick tab switch keeps it. */
    const val STOP_TIMEOUT_MS = 5_000L

    /** How many recommended series may be searched in the sources in one session (§7.4). */
    const val SOURCE_SEARCHES_PER_SESSION = 10

    /**
     * How long one source may take to answer a search. Longer than the 30 seconds Cloudflare's
     * challenge is given, so a challenge that fails is said as Cloudflare, not as a timeout.
     */
    const val SOURCE_SEARCH_TIMEOUT_MS = 40_000L

    /** How long the check for Cloudflare's challenge on a refused search may take. */
    const val CHALLENGE_CHECK_TIMEOUT_MS = 10_000L
}
