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

    /** How far back an entity's issues are asked of the catalogue: recent series are the readable ones. */
    const val CATALOG_YEARS = 5

    /** Pages of 100 issues read per entity; Metron lists them by series name, not by date. */
    const val CATALOG_PAGES = 2

    /** How many of the best catalogue picks get their details asked, before the sources are searched. */
    const val CATALOG_DETAILS = 15

    /** How long an entity's series are kept before the catalogue is asked again. */
    const val CATALOG_SERIES_DAYS = 7L

    /** How long a name's id and a series' details are kept: they seldom change. */
    const val CATALOG_NAME_DAYS = 30L

    /** Pages of a week's releases read from Metron (100 each) and from the GCD (50 each). */
    const val RELEASES_METRON_PAGES = 10
    const val RELEASES_GCD_PAGES = 10

    /** Series outside the library looked for in the sources per alerts' check (H7): each is a search and a fetch. */
    const val ALERT_OUTSIDE_CHECKS = 5

    /** Characters and creators followed whose week is asked of Metron per check: one request each. */
    const val ALERT_ENTITY_FOLLOWS = 10

    /** How often the alerts' check runs on its own. */
    const val ALERT_CHECK_HOURS = 24L

    /** How long an arc's issues are kept before Metron is asked again (H8). */
    const val ARC_DAYS = 7L

    /** Pages of an arc's issues read from Metron, 100 each: an event's crossovers fit. */
    const val ARC_PAGES = 5

    /** How far back an issue opened puts its arcs on the Início (H9). */
    const val ARC_READ_DAYS = 60L

    /** Arc names of what was read asked of Metron per build of the Início's arcs: one search each, kept. */
    const val ARC_NAMES_ASKED = 5

    /** Releases of the week whose arcs are asked of Metron: one issue's details each, kept [ARC_DAYS]. */
    const val ARC_RELEASES_ASKED = 5

    /** Arcs tried beyond the ones shown, for those read to the end or that Metron does not answer for. */
    const val ARC_SPARE = 3
}
