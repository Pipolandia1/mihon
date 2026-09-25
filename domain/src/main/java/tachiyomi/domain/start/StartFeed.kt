package tachiyomi.domain.start

import tachiyomi.domain.readingstats.comics.ComicLibrary
import tachiyomi.domain.readingstats.comics.EntryProgress
import tachiyomi.domain.readingstats.comics.LibraryStatus
import tachiyomi.domain.readingstats.plans.CollectionKind
import kotlin.random.Random

/** The Início blocks, in the order the plan fixes (docs/HOME_PLAN.md §6). */
enum class StartBlockKind {
    CONTINUE,
    READ_NOW,
    RELEASES,
    BECAUSE_YOU_LIKED,
    NEAR_FINISH,
    BY_TIME,
    BACKLOG,
    CREATORS,
}

sealed interface StartBlock {
    val kind: StartBlockKind
}

/** The works "Continuar lendo" shows; what they look like is the app's. */
data class ContinueBlock(val mangaIds: List<Long>) : StartBlock {
    override val kind get() = StartBlockKind.CONTINUE
}

data class NearFinishBlock(val items: List<Timed>) : StartBlock {
    override val kind get() = StartBlockKind.NEAR_FINISH
}

data class ByTimeBlock(val options: List<TimeOption>) : StartBlock {
    override val kind get() = StartBlockKind.BY_TIME
}

data class BacklogBlock(val shelves: List<BacklogShelf>) : StartBlock {
    override val kind get() = StartBlockKind.BACKLOG
}

/** A series and how long what is shown of it takes; [estimateMs] null when not measured. */
data class Timed(val progress: EntryProgress, val issues: Int, val estimateMs: Long?)

enum class TimeChoice { TEN_MINUTES, THIRTY_MINUTES, DIVE, FINISH }

data class TimeOption(val choice: TimeChoice, val series: List<Timed>, val collections: List<TimedCollection>)

/** A collection of the Stats plans (an arc, event, run or reading order) with unread chapters. */
data class CollectionState(
    val id: Long,
    val name: String,
    val kind: CollectionKind,
    val total: Int,
    val unread: Int,
    /** The first unread chapter in the collection's order: (manga id, chapter id). */
    val firstUnread: Pair<Long, Long>?,
)

data class TimedCollection(val collection: CollectionState, val estimateMs: Long?)

enum class ShelfKind { SHORT, NEVER_STARTED, STALLED }

data class BacklogShelf(val kind: ShelfKind, val entries: List<EntryProgress>)

data class StartInput(
    /** The works of "Continuar lendo", in order. */
    val continueIds: List<Long>,
    /** Every library entry's progress (`ComicLibrary.progress`). */
    val progress: List<EntryProgress>,
    val collections: List<CollectionState>,
    /** `ReadingTime.perIssueMs`; null when not measured. */
    val perIssueMs: Long?,
    val nowMs: Long,
    /** The day, so the backlog's picks change daily and stay put within a day. */
    val daySeed: Long,
    /** The Stats setting that hides "stalled" reminders; the Início respects it too. */
    val hideStalled: Boolean,
)

data class StartFeedResult(
    val blocks: List<StartBlock>,
    /** For the works of "Continuar lendo" with a known total: what is left of each. */
    val remaining: Map<Long, Timed>,
    /** The measured time per issue the estimates used; null when not measured. */
    val perIssueMs: Long?,
)

/**
 * Builds the Início from what is recorded. Amendment H2; later steps add their blocks here.
 *
 * - A work appears once on the screen: a block lower down never repeats one a block above it
 *   already shows. "Escolha pelo tempo" is the exception, because its lists only appear when a chip
 *   is tapped and answer a question ("what fits in 10 minutes?") the work being read may well answer.
 * - A block with nothing in it is not shown, and at most [MAX_BLOCKS] are.
 * - Deterministic: the same data on the same day is the same screen; only the day reshuffles the
 *   backlog. So the Início does not jump around while it is open, and reading something updates it.
 * - Nothing is estimated without a measured pace ([ReadingTime]).
 */
object StartFeed {

    const val MAX_BLOCKS = 7
    const val NEAR_FINISH_SHOWN = 3
    const val SHELF_SIZE = 8
    const val PICKS_PER_OPTION = 3

    /** A series this short, with its total known, is a "short story" of the backlog. */
    const val SHORT_MAX_ISSUES = 6

    private const val TEN_MINUTES = 10
    private const val THIRTY_MINUTES = 30

    fun build(input: StartInput): StartFeedResult {
        val shown = input.continueIds.toMutableSet()
        val blocks = mutableListOf<StartBlock>()
        val byId = input.progress.associateBy { it.entry.mangaId }

        if (input.continueIds.isNotEmpty()) blocks += ContinueBlock(input.continueIds)

        val near = ComicLibrary.nearFinish(input.progress)
            .filter { it.entry.mangaId !in shown }
            .take(NEAR_FINISH_SHOWN)
            .map { timed(it, it.remaining ?: 0, input.perIssueMs) }
        if (near.isNotEmpty()) {
            blocks += NearFinishBlock(near)
            shown += near.map { it.progress.entry.mangaId }
        }

        val options = timeOptions(input)
        if (options.isNotEmpty()) blocks += ByTimeBlock(options)

        val shelves = backlogShelves(input, shown)
        if (shelves.isNotEmpty()) blocks += BacklogBlock(shelves)

        val remaining = input.continueIds.mapNotNull { byId[it] }
            .filter { (it.remaining ?: 0) > 0 }
            .associate { it.entry.mangaId to timed(it, it.remaining!!, input.perIssueMs) }
        return StartFeedResult(blocks.sortedBy { it.kind }.take(MAX_BLOCKS), remaining, input.perIssueMs)
    }

    private fun timeOptions(input: StartInput): List<TimeOption> {
        val perIssue = input.perIssueMs
        // Works with an issue to open: the ones in progress, most recently read first, then the ones
        // never started, most recently added first.
        val readable = input.progress
            .filter { it.next != null && it.status in READABLE }
            .sortedWith(
                compareBy<EntryProgress> { if (it.status == LibraryStatus.IN_PROGRESS) 0 else 1 }
                    .thenByDescending { it.entry.lastReadAt ?: it.entry.dateAdded },
            )
        val options = mutableListOf<TimeOption>()
        if (perIssue != null && ReadingTime.issuesIn(TEN_MINUTES, perIssue) >= 1) {
            val picks = readable.take(PICKS_PER_OPTION).map { timed(it, 1, perIssue) }
            if (picks.isNotEmpty()) options += TimeOption(TimeChoice.TEN_MINUTES, picks, emptyList())
        }
        val fitThirty = perIssue?.let { ReadingTime.issuesIn(THIRTY_MINUTES, it) } ?: 0
        if (perIssue != null && fitThirty >= 1) {
            val picks = readable.take(PICKS_PER_OPTION)
                .map { timed(it, minOf(fitThirty, unread(it)).coerceAtLeast(1), perIssue) }
            if (picks.isNotEmpty()) options += TimeOption(TimeChoice.THIRTY_MINUTES, picks, emptyList())
        }
        val dives = input.collections
            .filter { it.kind != CollectionKind.ENTRY && it.unread > 0 }
            // Started ones first: a dive already begun is the likeliest to be finished.
            .sortedWith(compareBy<CollectionState> { if (it.unread < it.total) 0 else 1 }.thenBy { it.name })
            .take(PICKS_PER_OPTION)
            .map { TimedCollection(it, perIssue?.let { ms -> ms * it.unread }) }
        if (dives.isNotEmpty()) options += TimeOption(TimeChoice.DIVE, emptyList(), dives)
        val finish = ComicLibrary.nearFinish(input.progress)
            .take(PICKS_PER_OPTION)
            .map { timed(it, it.remaining ?: 0, perIssue) }
        if (finish.isNotEmpty()) options += TimeOption(TimeChoice.FINISH, finish, emptyList())
        return options
    }

    private fun backlogShelves(input: StartInput, shown: MutableSet<Long>): List<BacklogShelf> {
        val shelves = mutableListOf<BacklogShelf>()
        fun shelf(kind: ShelfKind, entries: List<EntryProgress>) {
            val kept = entries.filter { it.entry.mangaId !in shown }.take(SHELF_SIZE)
            if (kept.isEmpty()) return
            shelves += BacklogShelf(kind, kept)
            shown += kept.map { it.entry.mangaId }
        }
        // Short stories first: a whole story in a sitting is the easiest thing in the backlog to start.
        shelf(
            ShelfKind.SHORT,
            input.progress
                .filter { it.next != null && it.status in READABLE }
                .filter { (it.total ?: Int.MAX_VALUE) <= SHORT_MAX_ISSUES }
                .sortedWith(compareBy({ it.remaining }, { it.entry.title })),
        )
        shelf(
            ShelfKind.NEVER_STARTED,
            input.progress
                .filter { it.status == LibraryStatus.NOT_STARTED && it.next != null }
                .sortedBy { it.entry.mangaId }
                .shuffled(Random(input.daySeed)),
        )
        if (!input.hideStalled) shelf(ShelfKind.STALLED, ComicLibrary.stalled(input.progress, input.nowMs))
        return shelves
    }

    private fun timed(progress: EntryProgress, issues: Int, perIssueMs: Long?) =
        Timed(progress, issues, perIssueMs?.let { it * issues })

    /** Units left to read, variants aside. */
    private fun unread(progress: EntryProgress): Int {
        val counts = ComicLibrary.unitCounts(progress.entry.chapters.filterNot { it.read }, progress.entry.metadata)
        return counts.total - counts.variants
    }

    private val READABLE = setOf(LibraryStatus.NOT_STARTED, LibraryStatus.IN_PROGRESS)
}
