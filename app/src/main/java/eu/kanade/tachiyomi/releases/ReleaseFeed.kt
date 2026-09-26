package eu.kanade.tachiyomi.releases

import android.os.SystemClock
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.readingstats.comics.online.gcd.GcdApi
import eu.kanade.tachiyomi.readingstats.comics.online.gcd.GcdMapping
import eu.kanade.tachiyomi.readingstats.comics.online.gcd.GcdWeeklyItem
import eu.kanade.tachiyomi.readingstats.comics.online.metron.MetronApi
import eu.kanade.tachiyomi.readingstats.comics.online.metron.MetronIssueListItem
import eu.kanade.tachiyomi.ui.start.StartConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.start.ReleaseItem
import tachiyomi.domain.start.ReleaseOrigin
import tachiyomi.domain.start.ReleaseRepository
import tachiyomi.domain.start.ReleaseWeek
import tachiyomi.domain.start.ReleaseWeekList
import tachiyomi.domain.start.Releases
import java.time.LocalDate

/**
 * Each week's releases (docs/HOME_PLAN.md §8, H6): from Metron, with the reader's token — the day
 * of each issue, weeks ahead — and from the GCD's weekly list when Metron cannot answer. The list
 * is the same for anyone who asks: nothing of the library goes out to fetch it.
 *
 * A week is kept on the device and fetched again at most once a day while it can still change
 * ([Releases.stale]); a week fetched after it ended is never fetched again.
 */
@Inject
@SingleIn(AppScope::class)
class ReleaseFeed(
    private val metron: MetronApi,
    private val gcd: GcdApi,
    private val repository: ReleaseRepository,
) {

    sealed interface Outcome {
        /** Kept and still current: nothing asked. */
        data object Kept : Outcome

        data class Fetched(val origin: ReleaseOrigin, val issues: Int) : Outcome

        /** Neither catalogue answered; [kept] when an older list is there to show. */
        data class Failed(val kept: Boolean) : Outcome
    }

    private val queue = Mutex()

    /** [week]'s list, fetched when it is not kept yet or may have changed. */
    suspend fun refresh(week: ReleaseWeek): Outcome = queue.withLock {
        val now = System.currentTimeMillis()
        val kept = repository.getWeek(week)
        if (kept != null && !Releases.stale(kept, LocalDate.now(), now)) return@withLock Outcome.Kept
        val started = SystemClock.elapsedRealtime()
        val fetched = fromMetron(week, now) ?: fromGcd(week, now)
        if (fetched == null) {
            logcat(LogPriority.WARN) { "StartFeed releases $week: neither Metron nor the GCD answered" }
            return@withLock Outcome.Failed(kept != null)
        }
        repository.replaceWeek(fetched)
        logcat(LogPriority.INFO) {
            "StartFeed releases $week from ${fetched.origin}: ${fetched.items.size} issues " +
                "in ${SystemClock.elapsedRealtime() - started}ms"
        }
        Outcome.Fetched(fetched.origin, fetched.items.size)
    }

    private suspend fun fromMetron(week: ReleaseWeek, now: Long): ReleaseWeekList? {
        if (!metron.available) return null
        return attempt("Metron") {
            val (items, count) =
                metron.weekIssues(week.monday.toString(), week.sunday.toString(), StartConfig.RELEASES_METRON_PAGES)
            logCut(week, "Metron", items.size, count)
            ReleaseWeekList(week, ReleaseOrigin.METRON, now, items.mapNotNull(::metronRelease))
        }
    }

    private suspend fun fromGcd(week: ReleaseWeek, now: Long): ReleaseWeekList? = attempt("the GCD") {
        val (items, count) = gcd.weekIssues(week.year, week.week, StartConfig.RELEASES_GCD_PAGES)
        logCut(week, "the GCD", items.size, count)
        ReleaseWeekList(week, ReleaseOrigin.GCD, now, items.mapNotNull(::gcdRelease))
    }

    private suspend fun attempt(name: String, block: suspend () -> ReleaseWeekList): ReleaseWeekList? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logcat(LogPriority.WARN, e) { "StartFeed releases: $name did not answer" }
        null
    }

    /** No silent cap: a week longer than the pages read is said. */
    private fun logCut(week: ReleaseWeek, from: String, read: Int, count: Int) {
        if (read < count) logcat(LogPriority.INFO) { "StartFeed releases $week: $read of $count issues from $from" }
    }
}

internal fun metronRelease(item: MetronIssueListItem): ReleaseItem? {
    val series = item.series ?: return null
    return ReleaseItem(
        key = "metron:issue:${item.id}",
        seriesKey = "metron:series:${series.id}",
        seriesName = series.name,
        seriesYear = series.yearBegan,
        number = item.number,
        storeDate = item.storeDate,
        coverUrl = item.image,
    )
}

/** The GCD names the series with its year, "Absolute Batman (2024 series)", and the issue with its cover. */
internal fun gcdRelease(item: GcdWeeklyItem): ReleaseItem? {
    val id = GcdMapping.idOf(item.apiUrl) ?: return null
    val seriesId = GcdMapping.idOf(item.series) ?: return null
    val named = GCD_SERIES.matchEntire(item.seriesName.trim())
    return ReleaseItem(
        key = "gcd:issue:$id",
        seriesKey = "gcd:series:$seriesId",
        seriesName = named?.groupValues?.get(1) ?: item.seriesName.trim(),
        seriesYear = named?.groupValues?.get(2)?.toInt(),
        number = Releases.gcdNumber(item.descriptor),
        storeDate = null,
        coverUrl = null,
        variantOf = GcdMapping.idOf(item.variantOf)?.let { "gcd:issue:$it" },
    )
}

private val GCD_SERIES = Regex("(.*?)\\s*\\((\\d{4}) series\\)")
