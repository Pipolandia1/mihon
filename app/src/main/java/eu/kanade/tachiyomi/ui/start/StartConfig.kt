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
}
