package tachiyomi.domain.readingstats.comics

import tachiyomi.domain.start.MatchQuality
import tachiyomi.domain.start.TitleMatch

/** Where automatic metadata stands for one work (docs/HOME_PLAN.md §14, H13). */
enum class AutoStatus(val id: Long) {
    /** The catalogue's series was certain: its values were confirmed where nothing was. */
    CONFIRMED(0),

    /** A likely series, not certain (a year apart): its values wait as suggestions for the reader to accept. */
    SUGGESTED(1),

    /** No series the catalogue lists is this work; asked again after [AutoMetadata.RETRY_NOT_FOUND_DAYS]. */
    NOT_FOUND(2),

    /**
     * The reader matched the work to another catalogue (the GCD) by hand, and Metron has no certain
     * series for it: left as they set it.
     */
    LEFT(3),
    ;

    companion object {
        fun of(id: Long) = entries.firstOrNull { it.id == id } ?: NOT_FOUND
    }
}

/** What was done for [mangaId], with [seriesId] the catalogue's, when it had [chapters] chapters. */
data class AutoState(
    val mangaId: Long,
    val status: AutoStatus,
    val seriesId: Long?,
    val chapters: Int,
    val checkedAt: Long,
)

/** A work in the library or read, as automatic metadata sees it. */
data class AutoWork(
    val mangaId: Long,
    val title: String,
    val chapters: Int,
    val lastReadAt: Long?,
    val dateAdded: Long,
)

/**
 * A series a catalogue's search lists, titled as it titles it: "Batman (2016)". [collected]: a trade
 * paperback, hardcover or omnibus series, which a series of single issues of the same name is preferred to.
 */
data class CatalogCandidate(val id: Long, val title: String, val yearBegan: Int?, val collected: Boolean = false)

/** The series chosen for a work, and whether its values are confirmed or only suggested. */
data class AutoPick(val seriesId: Long, val status: AutoStatus)

/**
 * Comic metadata without a tap (H13): every work in the library or read gets its series' and issues'
 * values from the catalogue on its own.
 *
 * - **Certain or nothing confirmed:** a series is confirmed when its name and year are the work's
 *   ([MatchQuality.EXACT_YEAR]), or when the work's title has no year and the series is the only one
 *   of the name ([MatchQuality.EXACT]: a title goes without its year when no other series shares the
 *   name — the reader's rule, 03/10/2026); a likely one, a year apart or a nearly equal name, is kept
 *   as suggestions, which the metadata screen offers to accept.
 * - **The reader's values stay:** only fields with nothing confirmed are filled; a work the reader
 *   matched to another catalogue by hand gets Metron's values only from a certain series.
 * - **New issues:** a work is looked at again when it has more chapters than when it was last done.
 */
object AutoMetadata {

    /** Days before a work no series was found for is searched again: catalogues grow. */
    const val RETRY_NOT_FOUND_DAYS = 7L

    /** Days before a work left with suggestions is looked at again, as the rules may now be sure of it. */
    const val RETRY_SUGGESTED_DAYS = 1L

    /** Works done per run: each is a search and a few requests, or one per new issue. */
    const val PER_RUN = 15

    /** The series that is [title]'s, and how sure that is; null when none is, or two tie. */
    fun pick(title: String, candidates: List<CatalogCandidate>): AutoPick? {
        val parsed = SeriesTitles.parse(title)
        val key = TitleMatch.key(parsed.name, parsed.startYear)
        // The single issues first: the same name and year as a collection of them is the same series.
        val ordered = candidates.sortedBy { it.collected }
        val best = TitleMatch.best(parsed.name, parsed.startYear, ordered, ::labelOf) ?: return null
        val quality = TitleMatch.quality(key, labelOf(best)) ?: return null
        val status = if (quality in CERTAIN) AutoStatus.CONFIRMED else AutoStatus.SUGGESTED
        return AutoPick(best.id, status)
    }

    /** Whether [work] needs a run now, as [state] left it. */
    fun due(work: AutoWork, state: AutoState?, nowMs: Long): Boolean = when (state?.status) {
        null -> true
        AutoStatus.NOT_FOUND -> nowMs - state.checkedAt >= RETRY_NOT_FOUND_DAYS * DAY_MS
        AutoStatus.CONFIRMED -> work.chapters > state.chapters
        AutoStatus.SUGGESTED ->
            work.chapters > state.chapters ||
                nowMs - state.checkedAt >= RETRY_SUGGESTED_DAYS * DAY_MS
        AutoStatus.LEFT -> false
    }

    /** The works to do, the one read most recently first, then the one added most recently. */
    fun todo(works: List<AutoWork>, states: Map<Long, AutoState>, nowMs: Long): List<AutoWork> =
        works.filter { due(it, states[it.mangaId], nowMs) }
            .sortedWith(
                compareByDescending<AutoWork> { it.lastReadAt ?: 0L }
                    .thenByDescending { it.dateAdded }
                    .thenBy { it.mangaId },
            )

    /** What to confirm of [metadata]: the catalogue's suggestions for fields with nothing confirmed. */
    fun toConfirm(metadata: ComicMetadata, origin: MetadataOrigin): List<MetadataValue> =
        ComicSuggestions.pending(metadata, emptyList()).filter { it.origin == origin }

    /** A catalogue's title with its year, as sources and the work title it, when it lacks one. */
    private fun labelOf(candidate: CatalogCandidate): String {
        val year = candidate.yearBegan ?: return candidate.title
        return if (TitleMatch.key(candidate.title).year != null) candidate.title else "${candidate.title} ($year)"
    }

    /** The matches a series is confirmed from: name and year, or the name alone with no year to compare. */
    private val CERTAIN = setOf(MatchQuality.EXACT_YEAR, MatchQuality.EXACT)

    private const val DAY_MS = 24L * 60 * 60 * 1000
}
