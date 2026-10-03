package tachiyomi.domain.start

import tachiyomi.domain.readingstats.comics.ComicLibrary
import tachiyomi.domain.readingstats.comics.EntryProgress
import tachiyomi.domain.readingstats.comics.LibraryStatus
import tachiyomi.domain.readingstats.comics.MetadataOrigin
import tachiyomi.domain.readingstats.plans.CollectionKind
import kotlin.random.Random

/** The Início blocks, in the order the plan fixes (docs/HOME_PLAN.md §6). */
enum class StartBlockKind {
    CONTINUE,
    READ_NOW,
    RELEASES,
    ARCS,
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

/** "Leia isso agora": the one work to start now, and why. Amendment H3. */
data class ReadNowBlock(val recommendation: Recommendation) : StartBlock {
    override val kind get() = StartBlockKind.READ_NOW
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

/** "Lançamentos da semana" (H6): this week's issues that matter to the reader, and why. */
data class ReleasesBlock(val picks: List<RelevantRelease>) : StartBlock {
    override val kind get() = StartBlockKind.RELEASES
}

/** "Arcos e eventos" (H9): the arcs the reader is in, of what they read, of the week's releases. */
data class ArcsBlock(val picks: List<ArcPick>) : StartBlock {
    override val kind get() = StartBlockKind.ARCS
}

/** "Porque você gostou de X" (H5): works that share a writer, artist, character or team with [anchorTitle]. */
data class BecauseYouLikedBlock(val anchorId: Long, val anchorTitle: String, val picks: List<ShelfPick>) : StartBlock {
    override val kind get() = StartBlockKind.BECAUSE_YOU_LIKED
}

/** "Autores e personagens" (H5): the entity the reader reads most, [reason], and more of it. */
data class CreatorsBlock(val key: EntityKey, val reason: EntityReason, val picks: List<ShelfPick>) : StartBlock {
    override val kind get() = StartBlockKind.CREATORS
}

/** A work a shelf recommends: one in the library, or one found in a source (H5). */
sealed interface ShelfWork {
    val mangaId: Long
    val score: Double
}

data class LibraryWork(val progress: EntryProgress, override val score: Double) : ShelfWork {
    override val mangaId get() = progress.entry.mangaId
}

data class FoundWork(val work: OutsideWork, override val score: Double) : ShelfWork {
    override val mangaId get() = work.mangaId
}

/** What ties a work to its block: the entity they share, named as the reader's works name it. */
data class Link(val kind: EntityKind, val name: String)

data class ShelfPick(val work: ShelfWork, val link: Link)

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
    /** Every work read or in the library, for affinity (amendment H3). */
    val works: List<WorkFacts> = emptyList(),
    /** What the reader said of recommendations. */
    val feedback: List<Feedback> = emptyList(),
    /** Catalogue series found in the reader's sources, outside the library (amendment H5). */
    val outside: List<OutsideWork> = emptyList(),
    /** This week's releases (amendment H6). */
    val releases: List<ReleaseItem> = emptyList(),
    /** What the catalogue lists for the names the reader reads (H5), for the releases' reasons. */
    val catalogAnswers: List<CatalogAnswer> = emptyList(),
    /** The arcs to offer, in order ([ArcShelf]); they arrive later, from the catalogue (H9). */
    val arcs: List<ArcPick> = emptyList(),
)

data class StartFeedResult(
    val blocks: List<StartBlock>,
    /** For the works of "Continuar lendo" with a known total: what is left of each. */
    val remaining: Map<Long, Timed>,
    /** The measured time per issue the estimates used; null when not measured. */
    val perIssueMs: Long?,
    /** Library series with no writer, when they are most of it (§5.4); null otherwise. */
    val missingWriters: Int? = null,
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

    /** "Porque você gostou de X" needs at least this many works to be worth a shelf. */
    const val MIN_LIKED_SHELF = 2

    /** Issues read of a series in progress from which it counts as liked. */
    const val LIKED_MIN_ISSUES = 3

    /** Works read with an entity before the Início says the reader reads it "a lot"; below, "more of". */
    const val CREATOR_MIN_WORKS = 2

    /** Library series without a writer from which the Início says metadata would help. */
    const val COVERAGE_MIN = 3

    /** How many of the week's releases the Início shows; the calendar has them all. */
    const val RELEASES_SHOWN = 8

    /**
     * With more than [MAX_BLOCKS] to show, the kinds that go first. "Escolha pelo tempo" is a tool
     * with nothing of its own until a chip is tapped; the others are content.
     */
    val DROPPED_FIRST = listOf(StartBlockKind.BY_TIME)

    /** A series this short, with its total known, is a "short story" of the backlog. */
    const val SHORT_MAX_ISSUES = 6

    private const val TEN_MINUTES = 10
    private const val THIRTY_MINUTES = 30

    fun build(input: StartInput): StartFeedResult {
        val shown = input.continueIds.toMutableSet()
        val blocks = mutableListOf<StartBlock>()
        val byId = input.progress.associateBy { it.entry.mangaId }

        if (input.continueIds.isNotEmpty()) blocks += ContinueBlock(input.continueIds)

        val readNow = Recommender.readNow(
            candidates = input.progress,
            works = input.works,
            feedback = input.feedback,
            exclude = shown,
            nowMs = input.nowMs,
            daySeed = input.daySeed,
        )
        if (readNow != null) {
            blocks += ReadNowBlock(readNow)
            shown += readNow.progress.entry.mangaId
        }

        // Issues, not works: a release of a series being read is news, not a repeat.
        val releases = Releases.relevant(input.releases, input.works, input.catalogAnswers, input.feedback, input.nowMs)
            .take(RELEASES_SHOWN)
        if (releases.isNotEmpty()) blocks += ReleasesBlock(releases)

        // Arcs, not works: an arc of a series shown above is a reading order, not a repeat.
        val arcs = input.arcs.take(ArcShelf.SHOWN)
        if (arcs.isNotEmpty()) blocks += ArcsBlock(arcs)

        val model = Affinity.model(input.works, input.nowMs)
        val liked = becauseYouLiked(input, model, shown)
        if (liked != null) {
            blocks += liked
            shown += liked.picks.map { it.work.mangaId }
        }

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

        // "Autores e personagens" is shown below the backlog but chooses before it: the backlog's
        // never-started shelf is a daily draw, and would otherwise take the author's works every time.
        // Not the entities of "Porque você gostou de X": one name does not lead two blocks.
        val avoid = liked?.let { block -> input.works.firstOrNull { it.mangaId == block.anchorId } }
            ?.let { Affinity.creditsOf(it).keys }
            .orEmpty()
        val creators = creators(input, model, shown, avoid)
        if (creators != null) {
            blocks += creators
            shown += creators.picks.map { it.work.mangaId }
        }

        val shelves = backlogShelves(input, shown)
        if (shelves.isNotEmpty()) blocks += BacklogBlock(shelves)

        val remaining = input.continueIds.mapNotNull { byId[it] }
            .filter { (it.remaining ?: 0) > 0 }
            .associate { it.entry.mangaId to timed(it, it.remaining!!, input.perIssueMs) }
        return StartFeedResult(
            blocks = fit(blocks),
            remaining = remaining,
            perIssueMs = input.perIssueMs,
            missingWriters = missingWriters(input.works),
        )
    }

    /** At most [MAX_BLOCKS], in the fixed order; with more, the kinds in [DROPPED_FIRST] go first. */
    fun fit(blocks: List<StartBlock>): List<StartBlock> {
        val kept = blocks.toMutableList()
        DROPPED_FIRST.forEach { kind -> if (kept.size > MAX_BLOCKS) kept.removeAll { it.kind == kind } }
        return kept.sortedBy { it.kind }.take(MAX_BLOCKS)
    }

    /**
     * The work liked most recently — finished, read in many issues, or [LIKED_MIN_ISSUES] into —
     * that has at least [MIN_LIKED_SHELF] works sharing an entity with it, and those works: from the
     * library, and found in the sources. A liked work whose kin are all shown above, or turned down,
     * gives way to the one liked before it.
     */
    private fun becauseYouLiked(input: StartInput, model: AffinityModel, shown: Set<Long>): BecauseYouLikedBlock? {
        val unwanted = Recommender.unwantedNames(input.feedback, input.nowMs)
        return input.works
            .filter { liked(it, input.nowMs) }
            .sortedWith(compareByDescending<WorkFacts> { it.lastReadAt ?: 0L }.thenByDescending { it.mangaId })
            .firstNotNullOfOrNull { anchor ->
                val credits = askable(Affinity.creditsOf(anchor)).filterKeys { it !in unwanted }
                related(input, model, shown, credits, excluding = anchor.mangaId)
                    .takeIf { it.size >= MIN_LIKED_SHELF }
                    ?.let { BecauseYouLikedBlock(anchor.mangaId, anchor.title, it) }
            }
    }

    /**
     * The entity with the most affinity that has more of it to show — strong as the catalogue asks
     * about ([Discovery.MIN_AFFINITY]), so what was found for it has a place; one whose every work is
     * shown above or turned down gives way to the next.
     */
    private fun creators(
        input: StartInput,
        model: AffinityModel,
        shown: Set<Long>,
        avoid: Set<EntityKey>,
    ): CreatorsBlock? {
        val unwanted = Recommender.unwantedNames(input.feedback, input.nowMs)
        return model.contributions.keys
            .filter { it.kind in Discovery.ASKED_KINDS && it !in unwanted && it !in avoid }
            .mapNotNull { key ->
                Recommender.reasonFor(model, key, MetadataOrigin.USER)
                    ?.takeIf { it.score >= Discovery.MIN_AFFINITY }
                    ?.let { key to it }
            }
            .sortedWith(compareByDescending<Pair<EntityKey, EntityReason>> { it.second.score }.thenBy { it.first.name })
            .firstNotNullOfOrNull { (key, reason) ->
                val credit = Credit(key.kind, reason.name, MetadataOrigin.USER)
                related(input, model, shown, mapOf(key to credit), excluding = null)
                    .ifEmpty { null }
                    ?.let { CreatorsBlock(key, reason, it) }
            }
    }

    /**
     * Works sharing an entity of [credits], not started yet, none shown above or turned
     * down: the library's and the ones found in the sources, the strongest first.
     */
    private fun related(
        input: StartInput,
        model: AffinityModel,
        shown: Set<Long>,
        credits: Map<EntityKey, Credit>,
        excluding: Long?,
    ): List<ShelfPick> {
        if (credits.isEmpty()) return emptyList()
        val hidden = Recommender.hiddenWorks(input.feedback, input.nowMs)
        val unwanted = Recommender.unwantedNames(input.feedback, input.nowMs)
        fun open(id: Long) = id != excluding && id !in shown && id !in hidden
        fun link(shared: List<EntityKey>): Link {
            val strongest = shared.maxWith(compareBy<EntityKey> { Affinity.roleWeight(it.kind) }.thenBy { it.name })
            return Link(strongest.kind, credits.getValue(strongest).name.trim())
        }
        val facts = input.works.associateBy { it.mangaId }
        val library = input.progress
            // Not started: what is being read stays in its own blocks (Continuar lendo, Quase terminando).
            .filter { it.next != null && it.status == LibraryStatus.NOT_STARTED && open(it.entry.mangaId) }
            .mapNotNull { progress ->
                val id = progress.entry.mangaId
                val own = facts[id]?.let(Affinity::creditsOf) ?: return@mapNotNull null
                if (own.keys.any { it in unwanted }) return@mapNotNull null
                val shared = own.keys.filter { it in credits }.ifEmpty { return@mapNotNull null }
                val score = shared.sumOf { model.affinity(it, id) * Affinity.originWeight(own.getValue(it).origin) }
                ShelfPick(LibraryWork(progress, score), link(shared))
            }
        val outside = input.outside
            .filter { work -> open(work.mangaId) && work.pick.keys.none { it in unwanted } }
            .mapNotNull { work ->
                val shared = work.pick.keys.filter { it in credits }.ifEmpty { return@mapNotNull null }
                val score = shared.sumOf { model.affinity(it) * Affinity.originWeight(Discovery.CATALOG_ORIGIN) }
                ShelfPick(FoundWork(work, score), link(shared))
            }
        return (library + outside)
            .sortedWith(compareByDescending<ShelfPick> { it.work.score }.thenBy { it.work.mangaId })
            .distinctBy { it.work.mangaId }
            .take(SHELF_SIZE)
    }

    private fun liked(work: WorkFacts, nowMs: Long): Boolean = when (Affinity.engagement(work, nowMs)) {
        Engagement.COMPLETED, Engagement.MANY_ISSUES -> true
        Engagement.IN_PROGRESS -> work.chaptersRead >= LIKED_MIN_ISSUES
        else -> false
    }

    private fun askable(credits: Map<EntityKey, Credit>) = credits.filterKeys { it.kind in Discovery.ASKED_KINDS }

    /** How many library series have no writer, when that is most of the library (§5.4). */
    fun missingWriters(works: List<WorkFacts>): Int? {
        val library = works.filter { it.inLibrary }
        val missing = library.count { work -> Affinity.creditsOf(work).keys.none { it.kind == EntityKind.WRITER } }
        return missing.takeIf { it >= COVERAGE_MIN && it * 2 >= library.size }
    }

    private fun timeOptions(input: StartInput): List<TimeOption> {
        val perIssue = input.perIssueMs
        val hidden = Recommender.hiddenWorks(input.feedback, input.nowMs)
        // Works with an issue to open: the ones in progress, most recently read first, then the ones
        // never started, most recently added first.
        val readable = input.progress
            .filter { it.next != null && it.status in READABLE && it.entry.mangaId !in hidden }
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
        // "Not interested", "already read" and "not now" keep a work off every suggestion, though
        // never off the reader's own progress (Continuar lendo, Quase terminando).
        val hidden = Recommender.hiddenWorks(input.feedback, input.nowMs)
        fun shelf(kind: ShelfKind, entries: List<EntryProgress>) {
            val kept = entries.filter { it.entry.mangaId !in shown && it.entry.mangaId !in hidden }.take(SHELF_SIZE)
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
