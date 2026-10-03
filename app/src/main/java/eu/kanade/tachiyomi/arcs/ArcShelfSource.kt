package eu.kanade.tachiyomi.arcs

import android.os.SystemClock
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.readingstats.comics.online.metron.MetronApi
import eu.kanade.tachiyomi.ui.start.StartConfig
import eu.kanade.tachiyomi.ui.start.catalog.catalogNames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.start.ArcPick
import tachiyomi.domain.start.ArcRepository
import tachiyomi.domain.start.ArcShelf
import tachiyomi.domain.start.CatalogCache
import tachiyomi.domain.start.ChapterRef
import tachiyomi.domain.start.Discovery
import tachiyomi.domain.start.Feedback
import tachiyomi.domain.start.ReadArc
import tachiyomi.domain.start.ReleaseArc
import tachiyomi.domain.start.RelevantRelease
import tachiyomi.domain.start.TitleMatch
import tachiyomi.domain.start.WorkFacts
import java.time.LocalDate

/**
 * The arcs of the Início's "Arcos e eventos" (docs/HOME_PLAN.md §13, H9): the ones kept on the device
 * the reader is in, the ones the metadata of what they opened lately names, and the ones this week's
 * releases that matter to them are in; ordered by [ArcShelf], each with its progress.
 *
 * What is kept on the device is shown first; Metron is asked after, for what is not kept yet — an arc
 * name's id (kept [StartConfig.CATALOG_NAME_DAYS]), a release's arcs and an arc's issues (kept
 * [StartConfig.ARC_DAYS]) —, a few requests at most per name, release and arc. Nothing is looked for
 * in the sources here: progress counts the works the reader has; the arc's page looks for the rest.
 */
@Inject
class ArcShelfSource(
    private val repository: ArcRepository,
    private val catalog: ArcCatalog,
    private val metron: MetronApi,
    private val cache: CatalogCache,
    private val chapters: ChapterRepository,
) {

    /** The arcs to offer, again whenever reading, what the reader said or the week's releases change. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun subscribe(
        works: Flow<List<WorkFacts>>,
        feedback: Flow<List<Feedback>>,
        relevant: Flow<List<RelevantRelease>>,
    ): Flow<List<ArcPick>> = combine(works, feedback, relevant, ::Triple)
        .transformLatest { (works, feedback, relevant) ->
            emit(build(works, feedback, relevant, ask = false))
            if (metron.available) emit(build(works, feedback, relevant, ask = true))
        }
        .distinctUntilChanged()

    /** The arcs to offer; with [ask], Metron is asked for what is not kept, else only what is is used. */
    private suspend fun build(
        works: List<WorkFacts>,
        feedback: List<Feedback>,
        relevant: List<RelevantRelease>,
        ask: Boolean,
    ): List<ArcPick> {
        val started = SystemClock.elapsedRealtime()
        val now = System.currentTimeMillis()
        val today = LocalDate.now()
        val own = works.filter { it.inLibrary || it.chaptersRead > 0 }
        val refs = HashMap<Long, List<ChapterRef>>()
        suspend fun chaptersOf(owners: Map<String, WorkFacts>) = owners.values.associate { work ->
            work.mangaId to refs.getOrPut(work.mangaId) {
                chapters.getChapterByMangaId(work.mangaId).map { ChapterRef(it.id, it.chapterNumber, it.read) }
            }
        }

        val kept = repository.keptArcs().associateBy { it.first.key }
        val inArcs = kept.values.mapNotNull { (arc, issues) ->
            val owners = ArcShelf.owners(issues, own)
            ArcShelf.started(arc.key, issues, owners, chaptersOf(owners), today)
        }
        val reading = repository.readArcNames(now - StartConfig.ARC_READ_DAYS * DAY_MS, StartConfig.ARC_NAMES_ASKED)
            .mapNotNull { read ->
                arcIdOf(read.name, now, ask)?.let {
                    ReadArc(ArcCatalog.keyOf(it), read.mangaId, read.title, read.readAt)
                }
            }
        val releases = relevant
            .filter { it.item.key.startsWith(METRON_ISSUE) }
            .take(StartConfig.ARC_RELEASES_ASKED)
            .flatMap { pick -> arcsOf(pick.item.key, now, ask).map { ReleaseArc(ArcCatalog.keyOf(it), pick) } }
        val leads = ArcShelf.leads(inArcs, reading, releases, feedback, now)

        val picks = mutableListOf<ArcPick>()
        for (lead in leads.take(ArcShelf.SHOWN + StartConfig.ARC_SPARE)) {
            if (picks.size == ArcShelf.SHOWN) break
            val (arc, issues) = if (ask) {
                catalog.arc(lead.arcKey)?.let { it.arc to it.issues }
            } else {
                kept[lead.arcKey]
            } ?: continue
            val owners = ArcShelf.owners(issues, own)
            val progress = ArcShelf.progress(issues, owners, chaptersOf(owners), today)
            if (ArcShelf.worthOffering(progress)) picks += ArcPick(arc, progress, lead)
        }
        if (ask) {
            logcat(LogPriority.INFO) {
                "StartFeed arcs: ${inArcs.size} started, ${reading.size} from reading, ${releases.size} from " +
                    "releases -> ${picks.map { it.arc.name }} in ${SystemClock.elapsedRealtime() - started}ms"
            }
        }
        return picks
    }

    /** The id of the arc Metron names exactly [name]; null when none is, or several are. */
    private suspend fun arcIdOf(name: String, now: Long, ask: Boolean): Long? {
        val key = "metron:arc:name:${TitleMatch.key(name).name}"
        cache.get(key, now)?.let { return it.toLongOrNull() }
        if (!ask) return null
        return try {
            val id = Discovery.exactId(catalogNames(metron.arcs(name.trim())), name)
            // "Not in Metron by this name" is kept too, for less long: an arc may be added.
            val keep = if (id != null) StartConfig.CATALOG_NAME_DAYS else StartConfig.CATALOG_SERIES_DAYS
            cache.put(key, id?.toString().orEmpty(), now, keep * DAY_MS)
            id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "StartFeed arcs: Metron did not answer for the arc \"$name\"" }
            null
        }
    }

    /** The ids of the arcs the release [issueKey] is in, from its details on Metron. */
    private suspend fun arcsOf(issueKey: String, now: Long, ask: Boolean): List<Long> {
        val key = "$issueKey:arcs"
        cache.get(key, now)?.let { body -> return body.split(',').mapNotNull { it.toLongOrNull() } }
        if (!ask) return emptyList()
        val id = issueKey.removePrefix(METRON_ISSUE).toLongOrNull() ?: return emptyList()
        return try {
            val ids = metron.issueDetail(id).arcs.map { it.id }
            cache.put(key, ids.joinToString(","), now, StartConfig.ARC_DAYS * DAY_MS)
            ids
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "StartFeed arcs: Metron did not answer for the release $issueKey" }
            emptyList()
        }
    }

    private companion object {
        const val METRON_ISSUE = "metron:issue:"
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
