package tachiyomi.domain.start

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** An arc read in order, and how much of it is read (docs/HOME_PLAN.md §13, H8). */
class ArcsTest {

    private val today = LocalDate.of(2026, 10, 3)

    private fun issue(position: Int, series: Long, name: String, number: String, storeDate: String = "2026-01-07") =
        ArcIssue(
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

    // An arc across two series, in the catalogue's order, the last issue not out yet.
    private val arc = listOf(
        issue(0, 1, "Absolute Batman", "1"),
        issue(1, 1, "Absolute Batman", "2"),
        issue(2, 2, "Absolute Superman", "1"),
        issue(3, 1, "Absolute Batman", "3"),
        issue(4, 2, "Absolute Superman", "2", storeDate = "2026-10-21"),
    )

    private val batman = ArcSeries.Found(mangaId = 10, sourceName = "Read Comics Online", inLibrary = true)
    private val superman = ArcSeries.Found(mangaId = 20, sourceName = "Read Comics Online", inLibrary = false)

    private val chapters = mapOf(
        10L to listOf(ChapterRef(101, 1.0, read = true), ChapterRef(102, 2.0, read = true), ChapterRef(103, 3.0)),
        20L to listOf(ChapterRef(201, 1.0)),
    )

    @Test
    fun `each issue's state comes from the entry its series is read in`() {
        val views = Arcs.view(arc, mapOf(batman.key(1), superman.key(2)), chapters, today)

        views.map { it.state } shouldContainExactly listOf(
            ArcIssueState.READ,
            ArcIssueState.READ,
            ArcIssueState.AVAILABLE,
            ArcIssueState.AVAILABLE,
            ArcIssueState.NOT_OUT,
        )
        views[2].chapterId shouldBe 201
        views[2].sourceName shouldBe "Read Comics Online"
    }

    @Test
    fun `the progress is of the whole arc, issues not out and not found included`() {
        val views = Arcs.view(arc, mapOf(batman.key(1), "metron:series:2" to ArcSeries.NotFound), chapters, today)

        Arcs.progress(views, emptyMap()).let {
            it.total shouldBe 5
            it.read shouldBe 2
            it.left shouldBe 3
            it.percent shouldBe 40
        }
        views.map { it.state }.count { it == ArcIssueState.NOT_FOUND } shouldBe 1
    }

    @Test
    fun `the next issue is the first not read that can be opened, in the arc's order`() {
        val views = Arcs.view(arc, mapOf(batman.key(1), superman.key(2)), chapters, today)

        Arcs.next(views)?.issue?.number shouldBe "1"
        Arcs.next(views)?.issue?.seriesName shouldBe "Absolute Superman"
        // Absolute Superman in no source: the arc goes on with Absolute Batman #3.
        val without = Arcs.view(arc, mapOf(batman.key(1), "metron:series:2" to ArcSeries.NotFound), chapters, today)
        Arcs.next(without)?.chapterId shouldBe 103
    }

    @Test
    fun `while a series is looked for, its issues say so, and are counted as being looked for`() {
        val views = Arcs.view(arc, mapOf(batman.key(1)), chapters, today)

        views[2].state shouldBe ArcIssueState.SEARCHING
        views[4].state shouldBe ArcIssueState.NOT_OUT
        Arcs.progress(views, mapOf(batman.key(1))).let {
            it.searching shouldBe 1
            it.series shouldBe 2
        }
    }

    @Test
    fun `an out issue the entry has no chapter for is not in that source yet`() {
        val noThird = mapOf(10L to chapters.getValue(10L).take(2))

        Arcs.view(arc, mapOf(batman.key(1), superman.key(2)), noThird, today)[3].state shouldBe ArcIssueState.NOT_FOUND
    }

    @Test
    fun `an issue is the chapter with its number, and one without a number is no chapter`() {
        Arcs.chapterOf(arc[1], chapters.getValue(10L))?.id shouldBe 102
        Arcs.chapterOf(arc[1].copy(number = "Annual 1"), chapters.getValue(10L)).shouldBeNull()
    }

    @Test
    fun `a one-shot's issue is the entry's only chapter, kept without a number`() {
        val oneShot = listOf(ChapterRef(301, -1.0))

        Arcs.chapterOf(arc[2], oneShot)?.id shouldBe 301
        Arcs.chapterOf(arc[3], oneShot).shouldBeNull()
        Arcs.chapterOf(arc[2], oneShot + ChapterRef(302, -1.0)).shouldBeNull()
    }

    @Test
    fun `an empty arc has no percentage, not zero`() {
        Arcs.progress(emptyList(), emptyMap()).percent.shouldBeNull()
    }

    @Test
    fun `the order is the catalogue's position, however the issues come`() {
        val views = Arcs.view(arc.reversed(), mapOf(batman.key(1), superman.key(2)), chapters, today)

        views.map { it.issue.position } shouldContainExactly listOf(0, 1, 2, 3, 4)
    }

    private fun ArcSeries.key(series: Long) = "metron:series:$series" to this
}
