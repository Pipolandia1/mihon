package tachiyomi.domain.start

import java.time.LocalDate
import kotlin.math.roundToInt

/** A story arc or event of the catalogue (Metron calls both arcs). [key]: "metron:arc:123". */
data class Arc(
    val key: String,
    val name: String,
    val description: String?,
    val imageUrl: String?,
    val fetchedAt: Long,
)

/** One issue of an arc, at its place in the catalogue's order ([position], from 0). */
data class ArcIssue(
    val position: Int,
    val key: String,
    val seriesKey: String,
    val seriesName: String,
    val seriesYear: Int?,
    val number: String,
    val coverDate: String?,
    val storeDate: String?,
    val coverUrl: String?,
)

/** Where one series of an arc is read from. */
sealed interface ArcSeries {
    /** Still being looked for in the sources. */
    data object Searching : ArcSeries

    /** In none of the reader's sources, or a source failed and nothing was found. */
    data object NotFound : ArcSeries

    /** The entry its issues are read in: the library's when the reader has it, else one found (H4). */
    data class Found(val mangaId: Long, val sourceName: String, val inLibrary: Boolean) : ArcSeries
}

enum class ArcIssueState { READ, AVAILABLE, SEARCHING, NOT_FOUND, NOT_OUT }

/** An issue of an arc as the reader can take it: its state, and the chapter to open when there is one. */
data class ArcIssueView(
    val issue: ArcIssue,
    val state: ArcIssueState,
    val mangaId: Long? = null,
    val chapterId: Long? = null,
    val sourceName: String? = null,
)

/**
 * How much of the arc is read: [read] of [total], [percent] of it (null with no issues), [left] to go,
 * the [next] issue to open, and how many of its series are still being looked for.
 */
data class ArcProgress(
    val total: Int,
    val read: Int,
    val left: Int,
    val percent: Int?,
    val next: ArcIssueView?,
    val searching: Int,
    val series: Int,
)

interface ArcRepository {
    suspend fun getArc(key: String): Pair<Arc, List<ArcIssue>>?
    suspend fun saveArc(arc: Arc, issues: List<ArcIssue>)
}

/**
 * An arc read in order (docs/HOME_PLAN.md §13, H8): each issue's state from the entry its series is
 * read in, and how much of the arc that makes.
 *
 * The total is the arc's, as the catalogue lists it: an issue no source has and one not out yet count
 * in it, so the percentage is of the arc, not of what a source happens to have. An issue is read when
 * the chapter with its number is read in that entry.
 */
object Arcs {

    fun view(
        issues: List<ArcIssue>,
        series: Map<String, ArcSeries>,
        chapters: Map<Long, List<ChapterRef>>,
        today: LocalDate,
    ): List<ArcIssueView> = issues.sortedBy { it.position }.map { issue ->
        val out = !notOutBy(issue, today)
        when (val found = series[issue.seriesKey] ?: ArcSeries.Searching) {
            ArcSeries.Searching -> ArcIssueView(issue, if (out) ArcIssueState.SEARCHING else ArcIssueState.NOT_OUT)
            ArcSeries.NotFound -> ArcIssueView(issue, if (out) ArcIssueState.NOT_FOUND else ArcIssueState.NOT_OUT)
            is ArcSeries.Found -> {
                val chapter = chapterOf(issue, chapters[found.mangaId].orEmpty())
                val state = when {
                    chapter?.read == true -> ArcIssueState.READ
                    chapter != null -> ArcIssueState.AVAILABLE
                    !out -> ArcIssueState.NOT_OUT
                    // Out, and the entry has no chapter with its number: not in that source yet.
                    else -> ArcIssueState.NOT_FOUND
                }
                ArcIssueView(issue, state, found.mangaId, chapter?.id, found.sourceName)
            }
        }
    }

    fun progress(views: List<ArcIssueView>, series: Map<String, ArcSeries>): ArcProgress {
        val total = views.size
        val read = views.count { it.state == ArcIssueState.READ }
        val keys = views.map { it.issue.seriesKey }.toSet()
        return ArcProgress(
            total = total,
            read = read,
            left = total - read,
            percent = if (total == 0) null else (read * 100.0 / total).roundToInt(),
            next = next(views),
            searching = keys.count { (series[it] ?: ArcSeries.Searching) == ArcSeries.Searching },
            series = keys.size,
        )
    }

    /**
     * The issue to open next: the first one in order not read that can be opened. One that cannot be
     * opened yet (searching, in no source) does not hold the arc back.
     */
    fun next(views: List<ArcIssueView>): ArcIssueView? =
        views.firstOrNull { it.state == ArcIssueState.AVAILABLE && it.chapterId != null }

    /**
     * The chapter with the issue's number; for an issue without a number, none. A one-shot's #1 is
     * also the entry's only chapter when that one has no number, as sources keep it ("Full", -1).
     */
    fun chapterOf(issue: ArcIssue, chapters: List<ChapterRef>): ChapterRef? {
        val number = issue.number.trim().toDoubleOrNull() ?: return null
        return chapters.firstOrNull { it.number == number }
            ?: chapters.singleOrNull()?.takeIf { number == 1.0 && it.number < 0 }
    }

    private fun notOutBy(issue: ArcIssue, today: LocalDate): Boolean {
        val date = issue.storeDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return false
        return date > today
    }
}
