package tachiyomi.domain.start

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.readingstats.comics.ComicLibrary
import tachiyomi.domain.readingstats.comics.ComicMetadata
import tachiyomi.domain.readingstats.comics.LibraryEntry
import tachiyomi.domain.readingstats.comics.MetadataOrigin
import java.time.LocalDate

/** The week's releases (docs/HOME_PLAN.md §8, H6). */
class ReleasesTest {

    private val day = 24L * 60 * 60 * 1000
    private val today = LocalDate.of(2026, 9, 24) // a Thursday of ISO week 39
    private val now = today.toEpochDay() * day

    private fun work(
        id: Long,
        title: String,
        credits: List<Credit> = emptyList(),
        read: Int = 0,
        inLibrary: Boolean = true,
        catalogIds: Set<String> = emptySet(),
        lastReadAt: Long? = if (read > 0) now - id * day else null,
    ) = WorkFacts(id, title, inLibrary, now - 90 * day, 12, read, 12, true, read, 0, lastReadAt, credits, catalogIds)

    private fun metron(id: Long, series: Long, name: String, year: Int, number: String, date: String = "2026-09-23") =
        ReleaseItem("metron:issue:$id", "metron:series:$series", name, year, number, date, "https://img/$id.jpg")

    /** As the GCD's weekly list gives them: the series named with its year, variants on their own. */
    private fun gcd(id: Long, series: Long, name: String, descriptor: String, variantOf: Long? = null) = ReleaseItem(
        key = "gcd:issue:$id",
        seriesKey = "gcd:series:$series",
        seriesName = name,
        seriesYear = null,
        number = Releases.gcdNumber(descriptor),
        storeDate = null,
        coverUrl = null,
        variantOf = variantOf?.let { "gcd:issue:$it" },
    )

    // GCD, week 39 of 2026: Absolute Batman #24 with five variant covers, as its list has them.
    private val absoluteBatmanWeek = listOf(
        gcd(2868581, 216143, "Absolute Batman (2024 series)", "24"),
        gcd(2868920, 216143, "Absolute Batman (2024 series)", "24 [Clay Mann Cardstock Variant Cover]", 2868581),
        gcd(2868921, 216143, "Absolute Batman (2024 series)", "24 [Jorge Fornés Cardstock Variant Cover]", 2868581),
        gcd(2868922, 216143, "Absolute Batman (2024 series)", "24 [Blank Sketch Variant Cover]", 2868581),
        gcd(2868923, 216143, "Absolute Batman (2024 series)", "24 [1:25 Variant Cover]", 2868581),
        gcd(2868924, 216143, "Absolute Batman (2024 series)", "24 [1:50 Foil Variant Cover]", 2868581),
        gcd(2869581, 11293, "2000 AD (2001 series)", "2501"),
    )

    @Test
    fun `a week is Monday to Sunday of its ISO week, across the new year too`() {
        ReleaseWeek.of(today) shouldBe ReleaseWeek(2026, 39)
        ReleaseWeek(2026, 39).monday shouldBe LocalDate.of(2026, 9, 21)
        ReleaseWeek(2026, 39).sunday shouldBe LocalDate.of(2026, 9, 27)
        ReleaseWeek.of(LocalDate.of(2027, 1, 1)) shouldBe ReleaseWeek(2026, 53)
        ReleaseWeek(2026, 53).plus(1) shouldBe ReleaseWeek(2027, 1)
        ReleaseWeek(2027, 1).monday shouldBe LocalDate.of(2027, 1, 4)
    }

    @Test
    fun `an issue with its variant covers is one release`() {
        val grouped = Releases.grouped(absoluteBatmanWeek)

        grouped.map { it.key } shouldContainExactly listOf("gcd:issue:2868581", "gcd:issue:2869581")
        grouped.first().number shouldBe "24"
    }

    @Test
    fun `variants whose regular cover is not listed are one release, the first of them`() {
        val onlyVariants = absoluteBatmanWeek.drop(1).take(3)

        Releases.grouped(onlyVariants).map { it.key } shouldContainExactly listOf("gcd:issue:2868920")
        Releases.grouped(onlyVariants).single().number shouldBe "24"
    }

    @Test
    fun `Absolute Batman 24 comes once, because the reader reads Absolute Batman`() {
        val works = listOf(work(1, "Absolute Batman (2024-)", read = 3))

        val relevant = Releases.relevant(absoluteBatmanWeek, works, emptyList(), emptyList(), now)

        relevant.single().let {
            it.item.number shouldBe "24"
            it.reason shouldBe ReleaseReason.YourSeries(1, "Absolute Batman (2024-)")
        }
    }

    @Test
    fun `a series is the reader's by the catalogue's id first, and by name and year otherwise`() {
        val byId = work(1, "Batman Vol 4 (renamed by the source)", catalogIds = setOf("metron:series:3001"))
        val byName = work(2, "Flashpoint Beyond (2022-)")
        val otherYear = work(3, "Batman (2016-2020)")
        val items = listOf(
            metron(1, 3001, "Batman", 2025, "3"),
            metron(2, 2501, "Flashpoint Beyond", 2022, "7"),
            metron(3, 3999, "Wonder Woman", 2023, "30"),
        )

        val relevant = Releases.relevant(items, listOf(byId, byName, otherYear), emptyList(), emptyList(), now)

        relevant.map { (it.reason as ReleaseReason.YourSeries).mangaId } shouldContainExactly listOf(1L, 2L)
    }

    @Test
    fun `a series of a name the reader reads comes after theirs, with the name`() {
        val king = Credit(EntityKind.WRITER, "Tom King", MetadataOrigin.USER)
        val works = listOf(
            work(1, "Mister Miracle (2017)", listOf(king), read = 12),
            work(2, "Absolute Batman (2024-)", read = 3),
        )
        val answers =
            listOf(CatalogAnswer(EntityKey.of(king), listOf(CatalogSeriesRef(2533, "The Human Target", 1, 2021))))
        val items = listOf(
            metron(10, 2533, "The Human Target", 2021, "12"),
            metron(11, 216143, "Absolute Batman", 2024, "24"),
            metron(12, 4000, "Unrelated", 2025, "1"),
        )

        val relevant = Releases.relevant(items, works, answers, emptyList(), now)

        relevant.map { it.item.number } shouldContainExactly listOf("24", "12")
        relevant.last().reason shouldBe ReleaseReason.ByName(EntityKind.WRITER, "Tom King")
    }

    @Test
    fun `nothing turned down comes back as a release`() {
        val king = Credit(EntityKind.WRITER, "Tom King", MetadataOrigin.USER)
        val works =
            listOf(work(1, "Mister Miracle (2017)", listOf(king), read = 12), work(2, "Absolute Batman (2024-)"))
        val answers =
            listOf(CatalogAnswer(EntityKey.of(king), listOf(CatalogSeriesRef(2533, "The Human Target", 1, 2021))))
        val items =
            listOf(metron(10, 2533, "The Human Target", 2021, "12"), metron(11, 216143, "Absolute Batman", 2024, "24"))
        val feedback = listOf(
            Feedback(FeedbackSubject.CREATOR, "tom king", FeedbackVerdict.NOT_INTERESTED, null, now),
            Feedback(FeedbackSubject.WORK, "2", FeedbackVerdict.NOT_INTERESTED, null, now),
        )

        Releases.relevant(items, works, answers, feedback, now).shouldBeEmpty()
    }

    @Test
    fun `a week is fetched again while it can change, and never once it had ended`() {
        val week = ReleaseWeek(2026, 39)
        fun list(fetchedAt: Long) = ReleaseWeekList(week, ReleaseOrigin.METRON, fetchedAt, emptyList())

        Releases.stale(list(now - day / 24), today, now) shouldBe false
        Releases.stale(list(now - day - 1), today, now) shouldBe true
        // Fetched on its Thursday; on the Monday after, once more for the final list.
        Releases.stale(list(now), LocalDate.of(2026, 9, 28), now + day * 4) shouldBe true
        // Fetched after it ended: final.
        val afterEnd = LocalDate.of(2026, 9, 28).toEpochDay() * day + 1
        Releases.stale(list(afterEnd), LocalDate.of(2026, 10, 30), afterEnd + 30 * day) shouldBe false
    }

    @Test
    fun `the Início shows the week's releases that matter, and none when none do`() {
        val works = listOf(work(1, "Absolute Batman (2024-)", read = 3))
        fun build(releases: List<ReleaseItem>) = StartFeed.build(
            StartInput(
                continueIds = emptyList(),
                progress = emptyList(),
                collections = emptyList(),
                perIssueMs = null,
                nowMs = now,
                daySeed = 1,
                hideStalled = false,
                works = works,
                releases = releases,
            ),
        )

        build(absoluteBatmanWeek).blocks.filterIsInstance<ReleasesBlock>().single().picks.single().item.key shouldBe
            "gcd:issue:2868581"
        build(absoluteBatmanWeek.takeLast(1)).blocks.filterIsInstance<ReleasesBlock>().shouldBeEmpty()
    }

    @Test
    fun `with every kind of block, choosing by the time you have gives way first`() {
        val progress = ComicLibrary.progress(
            LibraryEntry(1, "A", null, false, 0, null, emptyList(), ComicMetadata.empty(1)),
        )
        val key = EntityKey(EntityKind.WRITER, "tom king")
        val reason = EntityReason(EntityKind.WRITER, "Tom King", 2, 2, 20, emptyList(), MetadataOrigin.USER, 5.0)
        val blocks = listOf(
            CreatorsBlock(key, reason, emptyList()),
            BacklogBlock(emptyList()),
            ByTimeBlock(emptyList()),
            NearFinishBlock(emptyList()),
            BecauseYouLikedBlock(1, "A", emptyList()),
            ReleasesBlock(emptyList()),
            ReadNowBlock(Recommendation(progress, 1.0, emptyList())),
            ContinueBlock(listOf(1)),
        )

        StartFeed.fit(blocks).map { it.kind } shouldContainExactly listOf(
            StartBlockKind.CONTINUE,
            StartBlockKind.READ_NOW,
            StartBlockKind.RELEASES,
            StartBlockKind.BECAUSE_YOU_LIKED,
            StartBlockKind.NEAR_FINISH,
            StartBlockKind.BACKLOG,
            StartBlockKind.CREATORS,
        )
        StartFeed.fit(blocks.filterNot { it is CreatorsBlock }).map { it.kind }
            .contains(StartBlockKind.BY_TIME) shouldBe true
    }
}
