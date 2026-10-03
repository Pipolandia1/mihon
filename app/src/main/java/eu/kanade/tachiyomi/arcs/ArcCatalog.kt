package eu.kanade.tachiyomi.arcs

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.readingstats.comics.online.metron.MetronApi
import eu.kanade.tachiyomi.readingstats.comics.online.metron.MetronArc
import eu.kanade.tachiyomi.readingstats.comics.online.metron.MetronIssueListItem
import eu.kanade.tachiyomi.ui.start.StartConfig
import kotlinx.coroutines.CancellationException
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.start.Arc
import tachiyomi.domain.start.ArcIssue
import tachiyomi.domain.start.ArcRepository
import tachiyomi.domain.start.CatalogName

/**
 * The catalogue's story arcs and events (docs/HOME_PLAN.md §13, H8): found by name, and each one's
 * issues in Metron's order, kept on the device for [StartConfig.ARC_DAYS].
 */
@Inject
@SingleIn(AppScope::class)
class ArcCatalog(
    private val metron: MetronApi,
    private val repository: ArcRepository,
) {

    /** An arc and its issues; [failed] when Metron did not answer and what is shown is what was kept. */
    data class Loaded(val arc: Arc, val issues: List<ArcIssue>, val failed: Boolean = false)

    val available: Boolean get() = metron.available

    /** Arcs and events named like [name], as Metron's search lists them. */
    suspend fun search(name: String): List<CatalogName> =
        withIOContext { metron.arcs(name.trim()) }.map { CatalogName(it.id, it.name) }

    /** The arc, from what is kept while it is recent, else from Metron; null when neither has it. */
    suspend fun arc(key: String): Loaded? {
        val kept = repository.getArc(key)
        val now = System.currentTimeMillis()
        if (kept != null && now - kept.first.fetchedAt < StartConfig.ARC_DAYS * DAY_MS) {
            return Loaded(kept.first, kept.second)
        }
        val id = key.removePrefix(ARC_KEY).toLongOrNull() ?: return kept?.let { Loaded(it.first, it.second) }
        return try {
            val (arc, issues) = withIOContext {
                val detail = metron.arc(id)
                val (items, count) = metron.arcIssues(id, StartConfig.ARC_PAGES)
                if (items.size < count) {
                    logcat(LogPriority.INFO) { "StartFeed arc ${detail.name}: ${items.size} of $count issues read" }
                }
                arcOf(key, detail, now) to items.mapIndexedNotNull(::arcIssue)
            }
            repository.saveArc(arc, issues)
            logcat(LogPriority.INFO) { "StartFeed arc ${arc.name}: ${issues.size} issues from Metron" }
            Loaded(arc, issues)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "StartFeed arc $key: Metron did not answer" }
            kept?.let { Loaded(it.first, it.second, failed = true) }
        }
    }

    companion object {
        const val ARC_KEY = "metron:arc:"
        private const val DAY_MS = 24L * 60 * 60 * 1000

        fun keyOf(id: Long) = "$ARC_KEY$id"
    }
}

internal fun arcOf(key: String, arc: MetronArc, now: Long) =
    Arc(key, arc.name, arc.desc?.trim()?.ifBlank { null }, arc.image, now)

/** An issue of the arc's list at its [position]; one without a series is left out. */
internal fun arcIssue(position: Int, item: MetronIssueListItem): ArcIssue? {
    val series = item.series ?: return null
    return ArcIssue(
        position = position,
        key = "metron:issue:${item.id}",
        seriesKey = "metron:series:${series.id}",
        seriesName = series.name,
        seriesYear = series.yearBegan,
        number = item.number,
        coverDate = item.coverDate,
        storeDate = item.storeDate,
        coverUrl = item.image,
    )
}
