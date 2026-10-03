package tachiyomi.domain.start

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** Following an arc: its new issues told once, and the arc in "Continuar lendo" (docs/HOME_PLAN.md §13, H10). */
class ArcFollowTest {

    private val day = 24L * 60 * 60 * 1000
    private val today = LocalDate.of(2026, 10, 3)
    private val now = today.toEpochDay() * day

    private fun issue(position: Int, series: Long, name: String, number: String, storeDate: String?) = ArcIssue(
        position = position,
        key = "metron:issue:${series * 100 + position}",
        seriesKey = "metron:series:$series",
        seriesName = name,
        seriesYear = 2024,
        number = number,
        coverDate = null,
        storeDate = storeDate,
        coverUrl = null,
    )

    private val zoo = listOf(
        issue(0, 1, "Absolute Batman", "1", "2024-10-09"),
        issue(1, 1, "Absolute Batman", "2", "2026-09-30"),
        issue(2, 2, "Absolute Superman", "1", "2026-10-01"),
        issue(3, 1, "Absolute Batman", "3", "2026-10-21"),
        issue(4, 2, "Absolute Superman", "2", null),
    )
    private val arc = Arc("metron:arc:1", "The Zoo", null, null, now)
    private val follow = Follow(FollowKind.ARC, arc.key, arc.name, createdAt = now - 5 * day)

    @Test
    fun `an arc's new issues are the ones on sale since it was followed, up to today`() {
        val followedOn = LocalDate.of(2026, 9, 28)

        Alerts.arcReleases(zoo, followedOn, today).map { it.number } shouldContainExactly listOf("2", "1")
        Alerts.arcReleases(zoo, followedOn, today).first().seriesKey shouldBe "metron:series:1"
    }

    @Test
    fun `a followed arc covers its new issues, each told once whatever else covers it`() {
        val new = Alerts.arcReleases(zoo, LocalDate.of(2026, 9, 28), today)
        val items = mapOf(arc.key to new.map { it.key }.toSet())
        val series =
            Follow(FollowKind.SERIES, "metron:series:1", "Absolute Batman", "Absolute Batman", 2024, createdAt = 0)

        Alerts.covers(follow, new.first(), items) shouldBe true
        val out = Alerts.out(new + new, listOf(follow, series), items, emptySet(), today)
        out.map { it.release.number } shouldContainExactly listOf("2", "1")
        out.first().follow shouldBe follow
        Alerts.out(new, listOf(follow), items, told = new.map { it.key }.toSet(), today).shouldBeEmpty()
    }

    private val batman = ArcSeries.Found(10, "XOXO Comics", inLibrary = true)
    private val superman = ArcSeries.Found(20, "XOXO Comics", inLibrary = false)
    private val series = mapOf("metron:series:1" to batman, "metron:series:2" to superman)

    private fun work(id: Long, lastReadAt: Long?) =
        WorkFacts(id, "Work $id", true, 0, 3, 1, null, false, 1, 0, lastReadAt, emptyList())

    @Test
    fun `a followed arc continues at its next issue that can be opened, as of when it was last read in`() {
        val chapters = mapOf(
            10L to listOf(ChapterRef(101, 1.0, read = true), ChapterRef(102, 2.0)),
            20L to listOf(ChapterRef(201, 1.0)),
        )
        val works = listOf(work(10, now - 2 * day), work(20, now - day))

        val next = ArcContinue.of(arc, zoo, series, chapters, works, follow.createdAt, today).shouldNotBeNull()

        next.next.chapterId shouldBe 102
        next.progress.read shouldBe 1
        // Read in Absolute Batman; Absolute Superman, opened later, has nothing of the arc read.
        next.lastReadAt shouldBe now - 2 * day
    }

    @Test
    fun `an arc followed and not started yet is there as of the follow, and one with nothing to open is not`() {
        val unread = mapOf(10L to listOf(ChapterRef(101, 1.0)), 20L to emptyList())

        ArcContinue.of(arc, zoo, series, unread, emptyList(), follow.createdAt, today)?.lastReadAt shouldBe
            follow.createdAt
        ArcContinue.of(arc, zoo, series, emptyMap(), emptyList(), follow.createdAt, today).shouldBeNull()
    }

    private data class Row(val mangaId: Long, val chapterId: Long)

    private fun next(key: String, chapterId: Long, lastReadAt: Long) = ArcNext(
        arc = arc.copy(key = key),
        progress = ArcProgress(4, 1, 3, 25, null, 0, 2),
        next = ArcIssueView(zoo[1], ArcIssueState.AVAILABLE, 10, chapterId, "XOXO Comics"),
        lastReadAt = lastReadAt,
    )

    @Test
    fun `the arcs go among the works by when they were read, and an issue is not shown twice`() {
        val works = listOf(
            Continuing.OfWork(Row(1, 11), now - day),
            Continuing.OfWork(Row(10, 102), now - 2 * day),
            Continuing.OfWork(Row(3, 31), now - 4 * day),
        )
        val arcs = listOf(next("metron:arc:1", chapterId = 102, lastReadAt = now - 3 * day))

        val rows = ArcContinue.merge(works, { it.chapterId }, arcs, limit = 4)

        rows.map { (it as? Continuing.OfWork<Row>)?.work?.mangaId ?: -1L } shouldContainExactly listOf(1L, -1L, 3L)
    }

    @Test
    fun `an arc taking a work's row takes its place too, when the work was read more recently`() {
        val works = listOf(
            Continuing.OfWork(Row(10, 102), now),
            Continuing.OfWork(Row(1, 11), now - day),
        )
        val arcs = listOf(next("metron:arc:1", chapterId = 102, lastReadAt = now - 9 * day))

        val rows = ArcContinue.merge(works, { it.chapterId }, arcs, limit = 4)

        rows.first() shouldBe Continuing.OfArc(arcs.single().copy(lastReadAt = now))
        rows.size shouldBe 2
    }

    @Test
    fun `arcs read more recently go first, an arc older than every work goes last, and the list keeps its length`() {
        val works = listOf(Continuing.OfWork(Row(1, 11), now - 2 * day), Continuing.OfWork(Row(2, 21), now - 3 * day))
        val arcs = listOf(
            next("metron:arc:1", chapterId = 900, lastReadAt = now - 10 * day),
            next("metron:arc:2", chapterId = 901, lastReadAt = now),
        )

        val rows = ArcContinue.merge(works, { it.chapterId }, arcs, limit = 3)

        rows.map { (it as? Continuing.OfArc)?.arc?.arc?.key ?: "work" } shouldContainExactly
            listOf("metron:arc:2", "work", "work")
    }
}
