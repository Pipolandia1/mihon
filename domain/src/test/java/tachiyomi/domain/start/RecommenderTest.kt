package tachiyomi.domain.start

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import tachiyomi.domain.readingstats.comics.ComicLibrary
import tachiyomi.domain.readingstats.comics.ComicMetadata
import tachiyomi.domain.readingstats.comics.EntryProgress
import tachiyomi.domain.readingstats.comics.LibraryChapter
import tachiyomi.domain.readingstats.comics.LibraryEntry
import tachiyomi.domain.readingstats.comics.MetadataOrigin

/** Affinity and "Leia isso agora" (docs/HOME_PLAN.md, H3). */
class RecommenderTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000 * day

    private fun writer(name: String, origin: MetadataOrigin = MetadataOrigin.USER) = Credit(
        EntityKind.WRITER,
        name,
        origin,
    )
    private fun character(name: String) = Credit(EntityKind.CHARACTER, name, MetadataOrigin.USER)

    private fun work(
        id: Long,
        credits: List<Credit>,
        chapters: Int = 10,
        read: Int = 0,
        total: Int? = null,
        finished: Boolean = false,
        sessions: Int = if (read > 0) read else 0,
        activeMs: Long = 0,
        lastReadAt: Long? = if (read > 0 || sessions > 0) now - day else null,
        inLibrary: Boolean = true,
        added: Long = now - 10 * day,
        title: String = "Work $id",
    ) = WorkFacts(id, title, inLibrary, added, chapters, read, total, finished, sessions, activeMs, lastReadAt, credits)

    /** The library view of a work: what the recommender picks from. */
    private fun progress(work: WorkFacts): EntryProgress {
        val chapters = (1..work.chapters).map {
            LibraryChapter(work.mangaId * 100 + it, it <= work.chaptersRead, "#$it", it.toDouble(), 0)
        }
        val entry = LibraryEntry(
            work.mangaId,
            work.title,
            null,
            work.finished,
            work.dateAdded,
            work.lastReadAt,
            chapters,
            ComicMetadata.empty(work.mangaId),
        )
        return ComicLibrary.progress(entry)
    }

    private fun rank(works: List<WorkFacts>, feedback: List<Feedback> = emptyList(), exclude: Set<Long> = emptySet()) =
        Recommender.rank(works.map(::progress), works, feedback, exclude, now, daySeed = 1)

    @Test
    fun `each way of reading gives its own weight`() {
        fun engagement(work: WorkFacts) = Affinity.engagement(work, now)

        engagement(work(1, emptyList(), read = 8, total = 8)) shouldBe Engagement.COMPLETED
        engagement(work(1, emptyList(), chapters = 30, read = 12)) shouldBe Engagement.MANY_ISSUES
        engagement(work(1, emptyList(), read = 3)) shouldBe Engagement.IN_PROGRESS
        engagement(work(1, emptyList(), sessions = 1, activeMs = 60_000)) shouldBe Engagement.OPENED_ONCE
        engagement(work(1, emptyList())) shouldBe Engagement.ADDED_NEVER_READ
        engagement(work(1, emptyList(), inLibrary = false)) shouldBe Engagement.NONE
        engagement(work(1, emptyList(), chapters = 20, read = 2, total = 20, lastReadAt = now - 90 * day)) shouldBe
            Engagement.ABANDONED
    }

    @Test
    fun `reading fades by half every 180 days`() {
        Affinity.recency(work(1, emptyList(), read = 1, lastReadAt = now), now) shouldBe (1.0 plusOrMinus 1e-9)
        Affinity.recency(work(1, emptyList(), read = 1, lastReadAt = now - 180 * day), now) shouldBe
            (0.5 plusOrMinus 1e-9)
        Affinity.recency(work(1, emptyList(), read = 1, lastReadAt = now - 360 * day), now) shouldBe
            (0.25 plusOrMinus 1e-9)
    }

    @Test
    fun `a confirmed credit weighs more than ComicInfo, ComicInfo more than the source, and a guess not at all`() {
        val read = listOf(
            work(1, listOf(writer("A", MetadataOrigin.USER)), read = 3),
            work(2, listOf(writer("B", MetadataOrigin.COMIC_INFO)), read = 3),
            work(3, listOf(writer("C", MetadataOrigin.SOURCE)), read = 3),
            work(4, listOf(writer("D", MetadataOrigin.INFERRED)), read = 3),
        )
        val model = Affinity.model(read, now)
        fun of(name: String) = model.affinity(EntityKey(EntityKind.WRITER, name.lowercase()))

        (of("A") > of("B")) shouldBe true
        (of("B") > of("C")) shouldBe true
        of("D") shouldBe 0.0
    }

    @Test
    fun `the candidate sharing most with what was read comes first, with its reasons in numbers`() {
        val works = listOf(
            work(
                1,
                listOf(writer("Tom King"), character("Supergirl")),
                chapters = 8,
                read = 8,
                total = 8,
                finished = true,
            ),
            work(2, listOf(writer("Tom King")), chapters = 6, read = 6, total = 6, finished = true),
            work(3, listOf(writer("Tom King"), character("Supergirl")), chapters = 12),
            work(4, listOf(writer("Someone Else")), chapters = 12),
        )

        val picks = rank(works)

        picks.map { it.progress.entry.mangaId } shouldBe listOf(3L)
        val reasons = picks.single().reasons.filterIsInstance<EntityReason>()
        reasons.first { it.kind == EntityKind.WRITER }.let {
            it.worksCompleted shouldBe 2
            it.worksRead shouldBe 2
            it.fromTitles.toSet() shouldBe setOf("Work 1", "Work 2")
        }
        reasons.first { it.kind == EntityKind.CHARACTER }.issuesRead shouldBe 8
    }

    @Test
    fun `adding to the library is not liking`() {
        // Only added, never read: shares a writer with the candidate, and still recommends nothing.
        val works = listOf(work(1, listOf(writer("Tom King"))), work(2, listOf(writer("Tom King"))))

        rank(works).shouldBeEmpty()
    }

    @Test
    fun `a work never recommends itself`() {
        // Only this work has the writer: its own reading must not make it its own reason.
        val works = listOf(work(1, listOf(writer("Tom King")), chapters = 20, read = 5))

        rank(works).shouldBeEmpty()
    }

    @Test
    fun `not interested, already read and not now take a work off, and not now brings it back`() {
        val read = work(1, listOf(writer("Tom King")), read = 5, chapters = 5, total = 5, finished = true)
        val candidate = work(2, listOf(writer("Tom King")))
        fun feedback(verdict: FeedbackVerdict, until: Long? = null) =
            listOf(Feedback(FeedbackSubject.WORK, "2", verdict, until, createdAt = now))

        rank(listOf(read, candidate)).single().progress.entry.mangaId shouldBe 2
        rank(listOf(read, candidate), feedback(FeedbackVerdict.NOT_INTERESTED)).shouldBeEmpty()
        rank(listOf(read, candidate), feedback(FeedbackVerdict.ALREADY_READ)).shouldBeEmpty()
        rank(listOf(read, candidate), feedback(FeedbackVerdict.LATER, until = now + day)).shouldBeEmpty()
        rank(listOf(read, candidate), feedback(FeedbackVerdict.LATER, until = now - 1)).single()
            .progress.entry.mangaId shouldBe 2
    }

    @Test
    fun `a creator the reader turned down keeps their works off`() {
        val read =
            work(1, listOf(writer("Tom King"), character("Batman")), read = 5, chapters = 5, total = 5, finished = true)
        val candidate = work(2, listOf(writer("Tom King"), character("Batman")))
        val turnedDown =
            listOf(Feedback(FeedbackSubject.CREATOR, "tom king", FeedbackVerdict.NOT_INTERESTED, null, now))

        rank(listOf(read, candidate), turnedDown).shouldBeEmpty()
    }

    @Test
    fun `the short series habit counts only with three started, and most finished`() {
        fun short(id: Long, read: Int) = work(id, emptyList(), chapters = 6, read = read, total = 6, finished = true)

        Recommender.formatHabit(listOf(short(1, 6), short(2, 6)), now).shouldBeNull()
        Recommender.formatHabit(listOf(short(1, 6), short(2, 6), short(3, 1)), now).shouldNotBeNull().let {
            it.finished shouldBe 2
            it.started shouldBe 3
        }
        Recommender.formatHabit(listOf(short(1, 6), short(2, 1), short(3, 1)), now).shouldBeNull()
    }

    @Test
    fun `a short closed series gets the habit as a reason`() {
        val finished = (1L..3L).map { work(it, emptyList(), chapters = 6, read = 6, total = 6, finished = true) }
        val candidate = work(9, emptyList(), chapters = 8, total = 8, finished = true)

        val pick = rank(finished + candidate).single()

        pick.progress.entry.mangaId shouldBe 9
        pick.reasons.single().shouldBeInstanceOf<FormatReason>().finished shouldBe 3
    }

    @Test
    fun `works already on the screen are not picked again`() {
        val read = work(1, listOf(writer("Tom King")), read = 5, chapters = 5, total = 5, finished = true)
        val candidate = work(2, listOf(writer("Tom King")))

        rank(listOf(read, candidate), exclude = setOf(2L)).shouldBeEmpty()
    }
}
