package tachiyomi.domain.start

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** The Início's "Arcos e eventos" (docs/HOME_PLAN.md §13, H9). */
class ArcShelfTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000
    private val today = LocalDate.of(2026, 10, 3)

    private fun work(id: Long, title: String, read: Int = 0, lastReadAt: Long? = null, inLibrary: Boolean = true) =
        WorkFacts(id, title, inLibrary, now - 90 * day, 12, read, 12, true, read, 0, lastReadAt, emptyList())

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

    private fun release(id: Long, name: String, reason: ReleaseReason) = RelevantRelease(
        ReleaseItem("metron:issue:$id", "metron:series:$id", name, 2024, "3", "2026-09-30", null),
        reason,
    )

    private fun arcFeedback(key: String, until: Long? = null) =
        Feedback(FeedbackSubject.ARC, key, FeedbackVerdict.NOT_INTERESTED, until, now - day)

    // An arc across two series, the last issue not out yet.
    private val zoo = listOf(
        issue(0, 1, "Absolute Batman", "1"),
        issue(1, 1, "Absolute Batman", "2"),
        issue(2, 2, "Absolute Superman", "1"),
        issue(3, 2, "Absolute Superman", "2", storeDate = "2026-10-21"),
    )

    private val batman = work(10, "Absolute Batman (2024-)", read = 1, lastReadAt = now - 2 * day)
    private val superman = work(20, "Absolute Superman (2024-)", read = 0, lastReadAt = now - day)

    private val chapters = mapOf(
        10L to listOf(ChapterRef(101, 1.0, read = true), ChapterRef(102, 2.0)),
        20L to listOf(ChapterRef(201, 1.0)),
    )

    @Test
    fun `the arcs the reader is in come first, then those of what they read, then the week's`() {
        val leads = ArcShelf.leads(
            started = listOf(StartedArc("metron:arc:1", now - 5 * day), StartedArc("metron:arc:2", now - day)),
            reading = listOf(
                ReadArc("metron:arc:3", 10, "Absolute Batman (2024-)", now - 3 * day),
                ReadArc("metron:arc:4", 20, "Absolute Superman (2024-)", now - day),
            ),
            releases = listOf(
                ReleaseArc("metron:arc:5", release(7, "Absolute Flash", ReleaseReason.YourSeries(30, "F"))),
            ),
            feedback = emptyList(),
            nowMs = now,
        )

        leads.map { it.arcKey } shouldContainExactly listOf(
            "metron:arc:2",
            "metron:arc:1",
            "metron:arc:4",
            "metron:arc:3",
            "metron:arc:5",
        )
    }

    @Test
    fun `an arc is offered once, for the reason that puts it highest, with its issue out this week`() {
        val out = release(7, "Absolute Batman", ReleaseReason.YourSeries(10, "Absolute Batman (2024-)"))
        val leads = ArcShelf.leads(
            started = emptyList(),
            reading = listOf(ReadArc("metron:arc:1", 10, "Absolute Batman (2024-)", now - day)),
            releases = listOf(ReleaseArc("metron:arc:1", out), ReleaseArc("metron:arc:2", out)),
            feedback = emptyList(),
            nowMs = now,
        )

        leads.map { it.arcKey } shouldContainExactly listOf("metron:arc:1", "metron:arc:2")
        leads[0].reason shouldBe ArcReason.InYourReading(10, "Absolute Batman (2024-)")
        leads[0].release shouldBe out.item
        leads[1].reason shouldBe ArcReason.OutThisWeek(out.reason)
    }

    @Test
    fun `an arc turned down is not offered, while the turning down lasts, nor one of a work turned down`() {
        val hiddenWork = Feedback(FeedbackSubject.WORK, "20", FeedbackVerdict.NOT_INTERESTED, null, now - day)
        val leads = ArcShelf.leads(
            started = listOf(StartedArc("metron:arc:1", now)),
            reading = listOf(
                ReadArc("metron:arc:2", 10, "Absolute Batman (2024-)", now),
                ReadArc("metron:arc:3", 20, "Absolute Superman (2024-)", now),
            ),
            releases = listOf(ReleaseArc("metron:arc:4", release(7, "X", ReleaseReason.YourSeries(30, "X")))),
            feedback = listOf(
                arcFeedback("metron:arc:1"),
                arcFeedback("metron:arc:4", until = now - 1),
                hiddenWork,
            ),
            nowMs = now,
        )

        leads.map { it.arcKey } shouldContainExactly listOf("metron:arc:2", "metron:arc:4")
    }

    @Test
    fun `an arc turned down is not a name turned down`() {
        Recommender.unwantedNames(listOf(arcFeedback("metron:arc:1")), now).shouldBeEmpty()
    }

    @Test
    fun `the arc's progress counts what is read in the works the reader has, the rest as not read`() {
        val owners = ArcShelf.owners(zoo, listOf(batman, superman))

        owners.mapValues { it.value.mangaId } shouldBe mapOf("metron:series:1" to 10L, "metron:series:2" to 20L)
        ArcShelf.progress(zoo, owners, chapters, today).let {
            it.total shouldBe 4
            it.read shouldBe 1
            it.left shouldBe 3
            it.next?.chapterId shouldBe 102
        }
        // Without the reader's Absolute Superman, its issues are still in the arc, not read.
        ArcShelf.progress(zoo, ArcShelf.owners(zoo, listOf(batman)), chapters, today).total shouldBe 4
    }

    @Test
    fun `an arc is one the reader is in when an issue of it is read and some are left, as of that reading`() {
        val owners = ArcShelf.owners(zoo, listOf(batman, superman))

        // Absolute Superman was opened later, but nothing of the arc was read in it.
        ArcShelf.started("metron:arc:1", zoo, owners, chapters, today) shouldBe
            StartedArc("metron:arc:1", now - 2 * day)

        val nothingRead = mapOf(10L to listOf(ChapterRef(101, 1.0)), 20L to emptyList())
        ArcShelf.started("metron:arc:1", zoo, owners, nothingRead, today).shouldBeNull()

        val allRead = mapOf(
            10L to listOf(ChapterRef(101, 1.0, read = true), ChapterRef(102, 2.0, read = true)),
            20L to listOf(ChapterRef(201, 1.0, read = true), ChapterRef(202, 2.0, read = true)),
        )
        ArcShelf.started("metron:arc:1", zoo, owners, allRead, today).shouldBeNull()
    }

    @Test
    fun `an arc read to the end, or with no issues, is not worth offering`() {
        val owners = ArcShelf.owners(zoo, listOf(batman, superman))

        ArcShelf.worthOffering(ArcShelf.progress(zoo, owners, chapters, today)) shouldBe true
        ArcShelf.worthOffering(ArcShelf.progress(emptyList(), owners, chapters, today)) shouldBe false
        val allRead = chapters + (20L to listOf(ChapterRef(201, 1.0, read = true), ChapterRef(202, 2.0, read = true))) +
            (10L to listOf(ChapterRef(101, 1.0, read = true), ChapterRef(102, 2.0, read = true)))
        ArcShelf.worthOffering(ArcShelf.progress(zoo, owners, allRead, today)) shouldBe false
    }

    @Test
    fun `the Início shows the arcs after the week's releases, and at most a shelf of them`() {
        val arc = Arc("metron:arc:1", "The Zoo", null, null, now)
        val progress = ArcShelf.progress(zoo, ArcShelf.owners(zoo, listOf(batman)), chapters, today)
        val picks = (1..ArcShelf.SHOWN + 2).map {
            ArcPick(arc.copy(key = "metron:arc:$it"), progress, ArcLead("metron:arc:$it", ArcReason.Started))
        }
        val out = ReleaseItem("metron:issue:7", "metron:series:1", "Absolute Batman", 2024, "3", "2026-09-30", null)
        val result = StartFeed.build(
            StartInput(
                continueIds = emptyList(),
                progress = emptyList(),
                collections = emptyList(),
                perIssueMs = null,
                nowMs = now,
                daySeed = 0,
                hideStalled = false,
                works = listOf(batman),
                releases = listOf(out),
                arcs = picks,
            ),
        )

        result.blocks.map { it.kind } shouldContainExactly listOf(StartBlockKind.RELEASES, StartBlockKind.ARCS)
        result.blocks.filterIsInstance<ArcsBlock>().single().picks.size shouldBe ArcShelf.SHOWN
        StartFeed.build(
            StartInput(emptyList(), emptyList(), emptyList(), null, now, 0, false),
        ).blocks.filterIsInstance<ArcsBlock>().shouldBeEmpty()
    }

    @Test
    fun `an issue's series is matched to the reader's work as a release's would be`() {
        zoo[0].asRelease().let {
            it.key shouldBe zoo[0].key
            it.seriesKey shouldBe "metron:series:1"
            it.number shouldBe "1"
        }
        ArcShelf.owners(zoo, listOf(work(30, "Batman (2016)", read = 3))).shouldNotBeNull().shouldBe(emptyMap())
    }
}
