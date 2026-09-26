package eu.kanade.tachiyomi.releases

import android.os.SystemClock
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.readingstats.comics.online.metron.MetronApi
import eu.kanade.tachiyomi.readingstats.comics.online.metron.MetronNamed
import eu.kanade.tachiyomi.ui.start.StartConfig
import eu.kanade.tachiyomi.ui.start.sources.SourceFinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import mihon.domain.source.interactor.UpdateMangaFromRemote
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.start.AlertRepository
import tachiyomi.domain.start.AlertWhat
import tachiyomi.domain.start.Alerts
import tachiyomi.domain.start.CatalogSeries
import tachiyomi.domain.start.ChapterRef
import tachiyomi.domain.start.Follow
import tachiyomi.domain.start.FollowKind
import tachiyomi.domain.start.Notified
import tachiyomi.domain.start.ReleaseItem
import tachiyomi.domain.start.ReleaseRepository
import tachiyomi.domain.start.ReleaseWeek
import tachiyomi.domain.start.Releases
import tachiyomi.domain.start.StartRepository
import java.time.LocalDate

/**
 * The alerts' check (docs/HOME_PLAN.md §8.3, H7), run by [ReleaseAlertJob] once a day and by
 * "Verificar agora": the week's releases against what the reader follows — "saiu" once for each —
 * and the ones told out looked for among the chapters — "já disponível", once, with "Ler agora".
 *
 * A series in the library is found among the chapters Mihon's own library update brings; nothing
 * here fetches for it. One outside the library is looked for in the sources (H4) and its chapters
 * brought in as opening its page would, for at most [StartConfig.ALERT_OUTSIDE_CHECKS] a run.
 */
@Inject
@SingleIn(AppScope::class)
class ReleaseAlerts(
    private val alerts: AlertRepository,
    private val releases: ReleaseRepository,
    private val feed: ReleaseFeed,
    private val metron: MetronApi,
    private val startRepository: StartRepository,
    private val chapters: ChapterRepository,
    private val getManga: GetManga,
    private val sourceManager: SourceManager,
    private val finder: SourceFinder,
    private val updateManga: UpdateMangaFromRemote,
    private val notifier: ReleaseNotifier,
) {

    /** What a check told; [notificationsOff] when it could tell nothing and so checked nothing. */
    data class Outcome(
        val follows: Int,
        val out: List<ReleaseItem>,
        val available: List<ReleaseItem>,
        val notificationsOff: Boolean = false,
    )

    private val queue = Mutex()

    /** The check as of [today] and [nowMs]: the job's clock, given. */
    suspend fun check(today: LocalDate = LocalDate.now(), nowMs: Long = System.currentTimeMillis()): Outcome =
        queue.withLock {
            val follows = alerts.getFollows()
            if (follows.isEmpty()) return@withLock Outcome(0, emptyList(), emptyList())
            // Told nothing, marked nothing: once the reader lets it notify, the alerts are still to come.
            if (!notifier.enabled()) {
                logcat(LogPriority.INFO) { "StartFeed alerts: notifications are off, nothing checked" }
                return@withLock Outcome(follows.size, emptyList(), emptyList(), notificationsOff = true)
            }
            val started = SystemClock.elapsedRealtime()
            val week = ReleaseWeek.of(today)
            feed.refresh(week)
            val (entityItems, entityReleases) = entityReleases(follows, week)
            val items = releases.getWeek(week)?.items.orEmpty() + entityReleases
            val told = alerts.notified(nowMs - TOLD_WINDOW_MS).filter {
                it.what == AlertWhat.OUT
            }.map { it.release.key }
            val own = startRepository.subscribeWorks().first().filter { it.inLibrary || it.chaptersRead > 0 }

            val out = Alerts.out(items, follows, entityItems, told.toSet(), today)
            out.forEach { alert ->
                notifier.out(alert.release, Releases.ownerOf(alert.release, own)?.mangaId)
                alerts.markNotified(Notified(alert.release, AlertWhat.OUT, nowMs))
            }

            val available = mutableListOf<ReleaseItem>()
            var outside = 0
            Alerts.awaitingChapter(alerts.notified(nowMs - Alerts.AVAILABLE_DAYS * DAY_MS), nowMs).forEach { release ->
                val owner = Releases.ownerOf(release, own)
                val found = when {
                    owner != null -> inChapters(release, owner.mangaId)
                    outside < StartConfig.ALERT_OUTSIDE_CHECKS -> {
                        outside++
                        outsideLibrary(release)
                    }
                    else -> null
                } ?: return@forEach
                notifier.available(release, found.mangaId, found.chapter.id, found.sourceName)
                alerts.markNotified(Notified(release, AlertWhat.AVAILABLE, nowMs))
                available += release
            }
            logcat(LogPriority.INFO) {
                "StartFeed alerts $week: ${follows.size} follows, out ${out.map { title(it.release) }}, " +
                    "available ${available.map(::title)} in ${SystemClock.elapsedRealtime() - started}ms"
            }
            Outcome(follows.size, out.map { it.release }, available)
        }

    /**
     * The week's issues of each character and creator followed, from Metron by their ids: follow key
     * → release keys, and the releases themselves.
     */
    private suspend fun entityReleases(
        follows: List<Follow>,
        week: ReleaseWeek,
    ): Pair<Map<String, Set<String>>, List<ReleaseItem>> {
        if (!metron.available) return emptyMap<String, Set<String>>() to emptyList()
        val found = mutableMapOf<String, Set<String>>()
        val items = mutableListOf<ReleaseItem>()
        follows.filter { it.kind == FollowKind.CHARACTER || it.kind == FollowKind.CREATOR }
            .take(StartConfig.ALERT_ENTITY_FOLLOWS)
            .forEach { follow ->
                val named = if (follow.kind == FollowKind.CHARACTER) MetronNamed.CHARACTER else MetronNamed.CREATOR
                val id = follow.key.substringAfterLast(':').toLongOrNull() ?: return@forEach
                try {
                    val (issues, _) = metron.issuesOf(named, id, week.monday.toString(), 1, week.sunday.toString())
                    val listed = issues.mapNotNull(::metronRelease)
                    found[follow.key] = listed.map { it.key }.toSet()
                    items += listed
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "StartFeed alerts: no issues of ${follow.label} from Metron" }
                }
            }
        return found to items
    }

    private data class Found(val mangaId: Long, val chapter: ChapterRef, val sourceName: String)

    /** [release]'s issue among an entry's chapters as they are on the device. */
    private suspend fun inChapters(release: ReleaseItem, mangaId: Long): Found? {
        val refs = chapters.getChapterByMangaId(mangaId).map { ChapterRef(it.id, it.chapterNumber) }
        val chapter = Alerts.chapterFor(release, refs) ?: return null
        val manga = getManga.await(mangaId) ?: return null
        return Found(mangaId, chapter, sourceManager.getOrStub(manga.source).name)
    }

    /** A series outside the library: found in the sources, its chapters brought in, and looked through. */
    private suspend fun outsideLibrary(release: ReleaseItem): Found? = try {
        val series = CatalogSeries(release.seriesKey, release.seriesName, release.seriesYear)
        val entry = finder.findByHand(series)
        if (entry == null) {
            null
        } else {
            val manga = getManga.await(entry.mangaId)
            if (manga != null) withIOContext { updateManga(manga, fetchDetails = false, fetchChapters = true) }
            inChapters(release, entry.mangaId)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logcat(LogPriority.WARN, e) { "StartFeed alerts: could not look for ${title(release)} in the sources" }
        null
    }

    private fun title(release: ReleaseItem) = "${release.seriesName} #${release.number}"

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000

        /** How far back what was told counts: past this, its week is not listed again anyway. */
        const val TOLD_WINDOW_MS = 60 * DAY_MS
    }
}
