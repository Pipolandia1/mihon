package tachiyomi.domain.start

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.readingstats.comics.ComicField
import tachiyomi.domain.readingstats.comics.ComicLibrary
import tachiyomi.domain.readingstats.comics.ComicMetadata
import tachiyomi.domain.readingstats.comics.EntryProgress
import tachiyomi.domain.readingstats.comics.LibraryChapter
import tachiyomi.domain.readingstats.comics.LibraryEntry
import tachiyomi.domain.readingstats.comics.MetadataOrigin
import tachiyomi.domain.readingstats.comics.MetadataValue
import tachiyomi.domain.readingstats.plans.CollectionKind

/** The Início's blocks from the library (docs/HOME_PLAN.md, H2). */
class StartFeedTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 400 * day
    private val minute = 60_000L

    /** A series of [issues] issues, the first [read] of them read; the total known when [total]. */
    private fun series(
        id: Long,
        issues: Int,
        read: Int = 0,
        total: Boolean = true,
        lastReadAt: Long? = if (read > 0) now - day else null,
        added: Long = id,
    ): EntryProgress {
        val chapters = (1..issues).map { LibraryChapter(id * 100 + it, it <= read, "#$it", it.toDouble(), 0) }
        val metadata = if (total) {
            listOf(MetadataValue(ComicField.TOTAL_ISSUES, null, listOf("$issues"), MetadataOrigin.USER))
        } else {
            emptyList()
        }
        val entry =
            LibraryEntry(
                id,
                "Series $id",
                null,
                false,
                added,
                lastReadAt,
                chapters,
                ComicMetadata(id, metadata, emptyList()),
            )
        return ComicLibrary.progress(entry)
    }

    private fun input(
        progress: List<EntryProgress>,
        continueIds: List<Long> = emptyList(),
        perIssueMs: Long? = null,
        collections: List<CollectionState> = emptyList(),
        daySeed: Long = 1,
        hideStalled: Boolean = false,
        works: List<WorkFacts> = emptyList(),
        feedback: List<Feedback> = emptyList(),
    ) = StartInput(continueIds, progress, collections, perIssueMs, now, daySeed, hideStalled, works, feedback)

    /** What affinity knows of [progress]'s work, written by [writer]. */
    private fun facts(progress: EntryProgress, writer: String) = WorkFacts(
        mangaId = progress.entry.mangaId,
        title = progress.entry.title,
        inLibrary = true,
        dateAdded = progress.entry.dateAdded,
        chapters = progress.entry.chapters.size,
        chaptersRead = progress.done,
        total = progress.total,
        finished = progress.entry.publicationFinished,
        sessions = progress.done,
        activeMs = 0,
        lastReadAt = progress.entry.lastReadAt,
        credits = listOf(Credit(EntityKind.WRITER, writer, MetadataOrigin.USER)),
    )

    private inline fun <reified T : StartBlock> StartFeedResult.block(): T? = blocks.filterIsInstance<T>().firstOrNull()

    private fun ids(block: BacklogBlock?, kind: ShelfKind) =
        block?.shelves?.firstOrNull { it.kind == kind }?.entries?.map { it.entry.mangaId } ?: emptyList()

    @Test
    fun `a work shows once, a block lower down never repeating one above it`() {
        val nearly = series(1, issues = 8, read = 6)
        val otherNearly = series(2, issues = 8, read = 7)
        val result = StartFeed.build(input(listOf(nearly, otherNearly), continueIds = listOf(1)))

        result.block<NearFinishBlock>()!!.items.map { it.progress.entry.mangaId } shouldContainExactly listOf(2L)
        result.blocks.map { it.kind } shouldBe
            listOf(StartBlockKind.CONTINUE, StartBlockKind.NEAR_FINISH, StartBlockKind.BY_TIME)
    }

    @Test
    fun `almost done needs a known total`() {
        val result = StartFeed.build(input(listOf(series(1, issues = 8, read = 6, total = false))))

        result.block<NearFinishBlock>().shouldBeNull()
    }

    @Test
    fun `without a measured pace nothing is estimated and the time chips are not there`() {
        val result = StartFeed.build(input(listOf(series(1, issues = 8, read = 6))))

        result.block<NearFinishBlock>()!!.items.single().estimateMs.shouldBeNull()
        result.block<ByTimeBlock>()!!.options.map { it.choice } shouldBe listOf(TimeChoice.FINISH)
        result.perIssueMs.shouldBeNull()
    }

    @Test
    fun `with a measured pace, what is left is estimated from it`() {
        val result = StartFeed.build(input(listOf(series(1, issues = 8, read = 6)), perIssueMs = 9 * minute))

        val item = result.block<NearFinishBlock>()!!.items.single()
        item.issues shouldBe 2
        item.estimateMs shouldBe 18 * minute
    }

    @Test
    fun `ten minutes only when an issue fits in ten minutes`() {
        val library = listOf(series(1, issues = 10, read = 2))

        StartFeed.build(input(library, perIssueMs = 9 * minute)).block<ByTimeBlock>()!!.options.map {
            it.choice
        } shouldBe
            listOf(TimeChoice.TEN_MINUTES, TimeChoice.THIRTY_MINUTES)
        StartFeed.build(input(library, perIssueMs = 14 * minute)).block<ByTimeBlock>()!!.options.map {
            it.choice
        } shouldBe
            listOf(TimeChoice.THIRTY_MINUTES)
        StartFeed.build(input(library, perIssueMs = 40 * minute)).block<ByTimeBlock>().shouldBeNull()
    }

    @Test
    fun `thirty minutes is as many issues as fit, but no more than are left`() {
        val result = StartFeed.build(
            input(listOf(series(1, issues = 10, read = 2), series(2, issues = 3, read = 2)), perIssueMs = 9 * minute),
        )
        val picks = result.block<ByTimeBlock>()!!.options.first { it.choice == TimeChoice.THIRTY_MINUTES }.series

        picks.associate { it.progress.entry.mangaId to it.issues } shouldBe mapOf(1L to 3, 2L to 1)
    }

    @Test
    fun `diving in is a collection with unread chapters, the started ones first, and never a single series plan`() {
        val collections = listOf(
            CollectionState(1, "Night Lanterns", CollectionKind.ARC, total = 6, unread = 6, firstUnread = 1L to 10L),
            CollectionState(2, "Court of Owls", CollectionKind.ARC, total = 11, unread = 4, firstUnread = 2L to 20L),
            CollectionState(3, "Batman", CollectionKind.ENTRY, total = 50, unread = 20, firstUnread = 3L to 30L),
            CollectionState(4, "Done", CollectionKind.EVENT, total = 5, unread = 0, firstUnread = null),
        )
        val dive = StartFeed.build(input(emptyList(), collections = collections, perIssueMs = 10 * minute))
            .block<ByTimeBlock>()!!.options.single { it.choice == TimeChoice.DIVE }

        dive.collections.map { it.collection.name } shouldBe listOf("Court of Owls", "Night Lanterns")
        dive.collections.first().estimateMs shouldBe 40 * minute
    }

    @Test
    fun `the backlog is short stories first, then never started, then stalled`() {
        val short = series(1, issues = 4)
        val long = series(2, issues = 30, total = false)
        val stalled = series(3, issues = 20, read = 5, total = false, lastReadAt = now - 60 * day)
        val result = StartFeed.build(input(listOf(short, long, stalled)))
        val backlog = result.block<BacklogBlock>()!!

        backlog.shelves.map { it.kind } shouldBe listOf(ShelfKind.SHORT, ShelfKind.NEVER_STARTED, ShelfKind.STALLED)
        ids(backlog, ShelfKind.SHORT) shouldBe listOf(1L)
        ids(backlog, ShelfKind.NEVER_STARTED) shouldBe listOf(2L)
        ids(backlog, ShelfKind.STALLED) shouldBe listOf(3L)
    }

    @Test
    fun `stalled series stay out when the Stats reminders are hidden`() {
        val stalled = series(3, issues = 20, read = 5, total = false, lastReadAt = now - 60 * day)

        StartFeed.build(input(listOf(stalled), hideStalled = true)).block<BacklogBlock>().shouldBeNull()
    }

    @Test
    fun `the never started shelf changes from day to day and stays put within a day`() {
        val library = (1L..30L).map { series(it, issues = 12, total = false) }

        val monday = ids(StartFeed.build(input(library, daySeed = 20_000)).block(), ShelfKind.NEVER_STARTED)
        val again = ids(StartFeed.build(input(library.reversed(), daySeed = 20_000)).block(), ShelfKind.NEVER_STARTED)
        val tuesday = ids(StartFeed.build(input(library, daySeed = 20_001)).block(), ShelfKind.NEVER_STARTED)

        monday.size shouldBe StartFeed.SHELF_SIZE
        again shouldBe monday
        tuesday shouldNotBe monday
    }

    @Test
    fun `what is left of a work being continued rides along with it`() {
        val result = StartFeed.build(
            input(listOf(series(1, issues = 8, read = 6)), continueIds = listOf(1), perIssueMs = 10 * minute),
        )

        result.remaining.getValue(1).issues shouldBe 2
        result.remaining.getValue(1).estimateMs shouldBe 20 * minute
        result.block<NearFinishBlock>().shouldBeNull()
    }

    @Test
    fun `read now takes its pick off the blocks below, and feedback keeps a work off the backlog`() {
        val finished = series(1, issues = 5, read = 5)
        val shortOne = series(2, issues = 6)
        val longOne = series(3, issues = 12, total = false)
        val library = listOf(finished, shortOne, longOne)
        val works = listOf(facts(finished, "Ana Torres"), facts(shortOne, "Ana Torres"), facts(longOne, "Bruno Lima"))

        val result = StartFeed.build(input(library, works = works))

        result.block<ReadNowBlock>()!!.recommendation.progress.entry.mangaId shouldBe 2
        result.blocks.map { it.kind }.first() shouldBe StartBlockKind.READ_NOW
        ids(result.block(), ShelfKind.SHORT) shouldNotContain 2L
        ids(result.block(), ShelfKind.NEVER_STARTED) shouldBe listOf(3L)

        val notInterested = listOf(Feedback(FeedbackSubject.WORK, "3", FeedbackVerdict.NOT_INTERESTED, null, now))
        val after = StartFeed.build(input(library, works = works, feedback = notInterested))
        ids(after.block(), ShelfKind.NEVER_STARTED) shouldBe emptyList()
    }

    @Test
    fun `finished and caught up works are in no block`() {
        val finished = series(1, issues = 5, read = 5)
        val result = StartFeed.build(input(listOf(finished)))

        result.blocks.shouldBeEmpty()
        result.remaining.keys shouldNotContain 1L
    }
}
