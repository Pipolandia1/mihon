package eu.kanade.tachiyomi.releases

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.ui.start.sources.SourceFinder
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.start.CatalogSeries
import tachiyomi.domain.start.ReleaseItem
import tachiyomi.domain.start.ReleaseReason

/** What a tap on a release opens (H6), in the Início and the calendar alike. */
@Inject
class ReleaseOpener(
    private val finder: SourceFinder,
) {

    /**
     * The entry to open: the reader's own for a series they have; otherwise the series looked for in
     * their sources, by the tap's own search (H4). Null when no source has it.
     */
    suspend fun entryFor(item: ReleaseItem, reason: ReleaseReason?): Long? {
        if (reason is ReleaseReason.YourSeries) return reason.mangaId
        return withIOContext {
            finder.findByHand(CatalogSeries(item.seriesKey, item.seriesName, item.seriesYear))?.mangaId
        }
    }
}
