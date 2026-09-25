package eu.kanade.tachiyomi.ui.start

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getLongArray

/** The Início's settings (docs/HOME_PLAN.md). Backed up with the app's settings, like the migration's order. */
@Inject
@SingleIn(AppScope::class)
class StartPreferences(
    preferenceStore: PreferenceStore,
) {

    /**
     * "Fontes das recomendações" (§7.2): where a recommended series from outside the library is
     * looked for, in order — the primary first, then the reserves. Empty until the reader chooses.
     */
    val recommendationSources: Preference<List<Long>> =
        preferenceStore.getLongArray("start_recommendation_sources", emptyList())

    /**
     * Whether the Início may search the sources on its own for recommended series (§7.4). Off, it
     * recommends only from the library; a search the reader starts by hand is not affected.
     */
    val searchSources: Preference<Boolean> = preferenceStore.getBoolean("start_search_sources", true)
}
