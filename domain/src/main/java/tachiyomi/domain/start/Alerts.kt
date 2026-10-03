package tachiyomi.domain.start

import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** What a follow is of: one issue, every issue of a series, a character's or a creator's, or an arc's (H10). */
enum class FollowKind(val id: Long) {
    ISSUE(0),
    SERIES(1),
    CHARACTER(2),
    CREATOR(3),
    ARC(4),
    ;

    companion object {
        fun of(id: Long) = entries.first { it.id == id }
    }
}

/**
 * Something the reader follows (docs/HOME_PLAN.md §8.3, H7). [key]: the issue's or the series'
 * release key ("metron:issue:1", "metron:series:10", "gcd:series:5"), or the catalogue's id of the
 * character, creator or arc ("metron:character:5", "metron:creator:7", "metron:arc:2470", H10). An
 * issue or a series keeps its name and year, and an issue its number, to be found in the library and
 * the sources.
 */
data class Follow(
    val kind: FollowKind,
    val key: String,
    val label: String,
    val seriesName: String? = null,
    val seriesYear: Int? = null,
    val number: String? = null,
    val createdAt: Long,
)

/** What the reader is told of a release: that it is out, and that it can be read. */
enum class AlertWhat(val id: Long) {
    OUT(0),
    AVAILABLE(1),
}

/** A release the reader was told of, and when; kept so nothing is told twice. */
data class Notified(val release: ReleaseItem, val what: AlertWhat, val at: Long)

data class OutAlert(val release: ReleaseItem, val follow: Follow)

/** A chapter of an entry, by its number: for "já disponível" (H7), and an arc's progress (H8). */
data class ChapterRef(val id: Long, val number: Double, val read: Boolean = false)

interface AlertRepository {
    fun subscribeFollows(): Flow<List<Follow>>
    suspend fun getFollows(): List<Follow>
    suspend fun follow(follow: Follow)
    suspend fun unfollow(kind: FollowKind, key: String)
    suspend fun notified(since: Long): List<Notified>
    suspend fun markNotified(notified: Notified)
}

/**
 * The alerts' rules (docs/HOME_PLAN.md §8.3, H7), apart from the clock and the network: what a
 * follow covers, which releases are out and not told yet, and which issue of an entry a release is.
 */
object Alerts {

    /** How long after it is out a release is still looked for in the library and the sources. */
    const val AVAILABLE_DAYS = 30L

    /**
     * Whether [follow] covers [item]. A series by its key, or by name and year when the lists differ
     * (a follow made on Metron's list, the week on the GCD's); a character, a creator or an arc by the
     * releases the catalogue lists for them ([entityItems]: follow key → release keys).
     */
    fun covers(follow: Follow, item: ReleaseItem, entityItems: Map<String, Set<String>>): Boolean = when (follow.kind) {
        FollowKind.ISSUE -> item.key == follow.key
        FollowKind.SERIES -> item.seriesKey == follow.key || sameSeries(follow, item)
        FollowKind.CHARACTER, FollowKind.CREATOR, FollowKind.ARC -> item.key in entityItems[follow.key].orEmpty()
    }

    /** Whether a follow covers [item] at all: the bell shows it. */
    fun followed(item: ReleaseItem, follows: List<Follow>, entityItems: Map<String, Set<String>> = emptyMap()) =
        follows.any { covers(it, item, entityItems) }

    /**
     * The releases on sale by [today] that something followed covers and the reader was not told of
     * yet: once each, however many follows cover it. A list that gives only the week (the GCD's)
     * counts as on sale during it.
     */
    fun out(
        releases: List<ReleaseItem>,
        follows: List<Follow>,
        entityItems: Map<String, Set<String>>,
        told: Set<String>,
        today: LocalDate,
    ): List<OutAlert> = Releases.grouped(releases)
        .filter { it.key !in told && onSaleBy(it, today) }
        .mapNotNull { item -> follows.firstOrNull { covers(it, item, entityItems) }?.let { OutAlert(item, it) } }

    /**
     * The releases told out within [AVAILABLE_DAYS] and not told available yet: the ones to look for
     * among the chapters.
     */
    fun awaitingChapter(notified: List<Notified>, nowMs: Long): List<ReleaseItem> {
        val available = notified.filter { it.what == AlertWhat.AVAILABLE }.map { it.release.key }.toSet()
        return notified
            .filter { it.what == AlertWhat.OUT && it.release.key !in available }
            .filter { nowMs - it.at <= AVAILABLE_DAYS * DAY_MS }
            .map { it.release }
            .distinctBy { it.key }
    }

    /**
     * The issues of a followed arc that are new to the reader (H10): on sale from the day it was
     * [followedOn] to [today]. The ones out before were there when the reader chose to follow it; an
     * issue without a sale date cannot be told new.
     */
    fun arcReleases(issues: List<ArcIssue>, followedOn: LocalDate, today: LocalDate): List<ReleaseItem> =
        issues.filter { issue ->
            val date = issue.storeDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@filter false
            date in followedOn..today
        }.map { it.asRelease() }

    /** The chapter that is [release]'s issue, by its number; null when none is, or it has no number. */
    fun chapterFor(release: ReleaseItem, chapters: List<ChapterRef>): ChapterRef? {
        val number = release.number.trim().toDoubleOrNull() ?: return null
        return chapters.firstOrNull { it.number == number }
    }

    private fun onSaleBy(item: ReleaseItem, today: LocalDate): Boolean {
        val date = item.storeDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return true
        return date <= today
    }

    private fun sameSeries(follow: Follow, item: ReleaseItem): Boolean {
        val name = follow.seriesName ?: return false
        val title = item.seriesYear?.let { "${item.seriesName} ($it)" } ?: item.seriesName
        return TitleMatch.quality(TitleMatch.key(name, follow.seriesYear), title) != null
    }

    private const val DAY_MS = 24L * 60 * 60 * 1000
}
