package tachiyomi.domain.start

import java.time.LocalDate

/**
 * A followed arc in "Continuar lendo" (docs/HOME_PLAN.md §13, H10): the issue to open next, how much
 * of the arc is read, and when the reader was last in it.
 */
data class ArcNext(val arc: Arc, val progress: ArcProgress, val next: ArcIssueView, val lastReadAt: Long)

/** One row of "Continuar lendo": a work, or a followed arc; [readAt] orders them. */
sealed interface Continuing<out W> {
    val readAt: Long

    data class OfWork<W>(val work: W, override val readAt: Long) : Continuing<W>

    data class OfArc(val arc: ArcNext) : Continuing<Nothing> {
        override val readAt: Long get() = arc.lastReadAt
    }
}

object ArcContinue {

    /**
     * [arc] in "Continuar lendo": its next issue that can be opened, in the arc's order; null when
     * none can, or every issue is read. Last read when a work it was read in was; one not started yet
     * as of when it was [followedAt], so following an arc puts it there ("Começar o arco").
     */
    fun of(
        arc: Arc,
        issues: List<ArcIssue>,
        series: Map<String, ArcSeries>,
        chapters: Map<Long, List<ChapterRef>>,
        works: List<WorkFacts>,
        followedAt: Long,
        today: LocalDate,
    ): ArcNext? {
        val views = Arcs.view(issues, series, chapters, today)
        val progress = Arcs.progress(views, series)
        val next = progress.next ?: return null
        if (progress.left == 0) return null
        val readIn = views.filter { it.state == ArcIssueState.READ }.mapNotNull { it.mangaId }.toSet()
        val lastRead = works.filter { it.mangaId in readIn }.mapNotNull { it.lastReadAt }.maxOrNull()
        return ArcNext(arc, progress, next, lastRead ?: followedAt)
    }

    /**
     * The works and the followed arcs of "Continuar lendo", at most [limit]: the works in their own
     * order, each arc before the first work last read before it. A work whose chapter to continue is
     * an arc's next issue is the arc's row: the same issue is not shown twice, and the arc takes the
     * work's place when it was read more recently (opened from the arc, say, and not finished).
     */
    fun <W> merge(
        works: List<Continuing.OfWork<W>>,
        chapterOf: (W) -> Long,
        arcs: List<ArcNext>,
        limit: Int,
    ): List<Continuing<W>> {
        val opened = arcs.mapNotNull { it.next.chapterId }.toSet()
        val rows = works.filter { chapterOf(it.work) !in opened }.toMutableList<Continuing<W>>()
        val placed = arcs.map { arc ->
            val taken = works.filter { chapterOf(it.work) == arc.next.chapterId }.maxOfOrNull { it.readAt }
            if (taken != null && taken > arc.lastReadAt) arc.copy(lastReadAt = taken) else arc
        }
        placed.sortedByDescending { it.lastReadAt }.forEach { arc ->
            val at = rows.indexOfFirst { it is Continuing.OfWork<*> && it.readAt < arc.lastReadAt }
            val row = Continuing.OfArc(arc)
            if (at < 0) rows += row else rows.add(at, row)
        }
        return rows.take(limit)
    }
}
