package tachiyomi.domain.start

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import tachiyomi.domain.readingstats.comics.ComicLibrary
import tachiyomi.domain.readingstats.comics.ComicMetadata
import tachiyomi.domain.readingstats.comics.EntryProgress
import tachiyomi.domain.readingstats.comics.LibraryChapter
import tachiyomi.domain.readingstats.comics.LibraryEntry
import tachiyomi.domain.readingstats.comics.MetadataOrigin

/** Discovery beyond the library, and the blocks it feeds (docs/HOME_PLAN.md §7.5, H5). */
class DiscoveryTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_000 * day

    private fun writer(name: String) = Credit(EntityKind.WRITER, name, MetadataOrigin.USER)
    private fun character(name: String) = Credit(EntityKind.CHARACTER, name, MetadataOrigin.USER)
    private fun genre(name: String) = Credit(EntityKind.GENRE, name, MetadataOrigin.USER)

    private fun work(
        id: Long,
        title: String,
        credits: List<Credit>,
        chapters: Int = 10,
        read: Int = 0,
        total: Int? = null,
        inLibrary: Boolean = true,
        lastReadAt: Long? = if (read > 0) now - day * (100 - id) else null,
    ) = WorkFacts(
        mangaId = id,
        title = title,
        inLibrary = inLibrary,
        dateAdded = now - 30 * day,
        chapters = chapters,
        chaptersRead = read,
        total = total,
        finished = total != null,
        sessions = read,
        activeMs = 0,
        lastReadAt = lastReadAt,
        credits = credits,
    )

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

    private fun key(credit: Credit) = EntityKey.of(credit)

    private fun series(id: Long, name: String, year: Int, issues: Int = 5) = CatalogSeriesRef(id, name, 1, year, issues)

    private fun outside(mangaId: Long, pick: CatalogPick) =
        OutsideWork(
            mangaId,
            "${pick.series.name} (${pick.series.yearBegan}-)",
            null,
            "Read Comics Online",
            pick,
            12,
            true,
        )

    private val king = writer("Tom King")
    private val supergirl = character("Supergirl")

    // The reader: two Tom King series finished, one Batman series in progress.
    private val mister = work(1, "Mister Miracle (2017)", listOf(king), chapters = 12, read = 12, total = 12)
    private val woman = work(2, "Supergirl: Woman of Tomorrow (2021)", listOf(king, supergirl), 8, 8, 8)
    private val batman = work(3, "Batman (2025-)", listOf(writer("Matt Fraction"), character("Batman")), 6, read = 2)
    private val works = listOf(mister, woman, batman)

    @Test
    fun `the catalogue is asked about the strongest entities read, and never a turned down one`() {
        val onlyAdded = work(4, "Heroes in Crisis (2018)", listOf(writer("Someone Added")))
        val model = Affinity.model(works + onlyAdded, now)

        val asked = Discovery.entitiesToAsk(model, emptyList(), now)

        asked.first().key shouldBe key(king)
        asked.first().name shouldBe "Tom King"
        asked.map { it.key.name } shouldNotContain "someone added"
        val turnedDown = Feedback(FeedbackSubject.CREATOR, "tom king", FeedbackVerdict.NOT_INTERESTED, null, now)
        Discovery.entitiesToAsk(model, listOf(turnedDown), now).map { it.key } shouldNotContain key(king)
    }

    @Test
    fun `genres and publishers are never asked about`() {
        val model = Affinity.model(listOf(work(1, "A", listOf(genre("Superhero")), 12, 12, 12)), now)

        Discovery.entitiesToAsk(model, emptyList(), now).shouldBeEmpty()
    }

    @Test
    fun `a name is the catalogue's only when exactly one of its results is named so`() {
        // Metron's name search matches parts of names, accents aside.
        val creators = listOf(CatalogName(11, "Tom King"), CatalogName(12, "Tom Kingsley"))
        val robins = listOf(CatalogName(21, "Robin (Dick Grayson)"), CatalogName(22, "Robin (Tim Drake)"))

        Discovery.exactId(creators, "Tom King") shouldBe 11
        Discovery.exactId(creators, "tom  king ") shouldBe 11
        Discovery.exactId(listOf(CatalogName(31, "José Luis García-López")), "Jose Luis Garcia-Lopez") shouldBe 31
        Discovery.exactId(robins, "Robin").shouldBeNull()
        Discovery.exactId(creators, "Tom").shouldBeNull()
    }

    @Test
    fun `an entity's issues become its series, the ones with most of its issues first`() {
        val issues = listOf(
            CatalogIssue(100, "Mister Miracle", 4, 2017),
            CatalogIssue(200, "The Human Target", 1, 2021),
            CatalogIssue(200, "The Human Target", 1, 2021),
            CatalogIssue(300, "Wonder Woman", 6, 2023),
            CatalogIssue(300, "Wonder Woman", 6, 2023),
            CatalogIssue(300, "Wonder Woman", 6, 2023),
        )

        Discovery.seriesOf(issues) shouldContainExactly listOf(
            CatalogSeriesRef(300, "Wonder Woman", 6, 2023, 3),
            CatalogSeriesRef(200, "The Human Target", 1, 2021, 2),
            CatalogSeriesRef(100, "Mister Miracle", 4, 2017, 1),
        )
    }

    @Test
    fun `a collected edition is not a series to recommend`() {
        Discovery.isCollected(CatalogSeriesDetail(1, seriesType = "Trade Paperback")) shouldBe true
        Discovery.isCollected(CatalogSeriesDetail(1, seriesType = "Hard Cover")) shouldBe true
        Discovery.isCollected(CatalogSeriesDetail(1, seriesType = "Omnibus")) shouldBe true
        Discovery.isCollected(CatalogSeriesDetail(1, seriesType = "Limited Series")) shouldBe false
        Discovery.isCollected(CatalogSeriesDetail(1)) shouldBe false
    }

    @Test
    fun `what the reader has or read is not picked again, by name and year`() {
        val model = Affinity.model(works, now)
        val answer = CatalogAnswer(
            key(king),
            listOf(
                series(1, "Mister Miracle", 2017),
                series(2, "Supergirl: Woman of Tomorrow", 2021),
                series(3, "The Human Target", 2021),
            ),
        )
        val batmanAnswer =
            CatalogAnswer(key(character("Batman")), listOf(series(4, "Batman", 2025), series(5, "Batman", 2016)))

        val picks = Discovery.picks(listOf(answer, batmanAnswer), model, works)

        picks.map { it.series.id } shouldContainExactly listOf(3L, 5L)
    }

    @Test
    fun `a pick carries its reasons in numbers, and a series of two liked entities comes first`() {
        val model = Affinity.model(works, now)
        val answers = listOf(
            CatalogAnswer(key(king), listOf(series(3, "The Human Target", 2021), series(6, "Wonder Woman", 2023))),
            CatalogAnswer(key(supergirl), listOf(series(6, "Wonder Woman", 2023))),
        )

        val picks = Discovery.picks(answers, model, works)

        picks.first().series.id shouldBe 6
        picks.first().keys.toSet() shouldBe setOf(key(king), key(supergirl))
        picks.first().reasons.first { it.kind == EntityKind.WRITER }.let {
            it.worksCompleted shouldBe 2
            it.fromTitles.toSet() shouldBe setOf("Mister Miracle (2017)", "Supergirl: Woman of Tomorrow (2021)")
            it.candidateOrigin shouldBe MetadataOrigin.METRON
        }
    }

    @Test
    fun `no entity takes more than its share of the searches`() {
        val model = Affinity.model(works, now)
        val many = CatalogAnswer(key(king), (10L..20L).map { series(it, "King Series $it", 2020) })

        Discovery.picks(listOf(many), model, works).size shouldBe Discovery.PER_ENTITY
    }

    /** Every work some block of the Início shelves, H5's blocks included. */
    private fun shelved(result: StartFeedResult) = result.blocks.flatMap { block ->
        when (block) {
            is BecauseYouLikedBlock -> block.picks.map { it.work.mangaId }
            is CreatorsBlock -> block.picks.map { it.work.mangaId }
            else -> emptyList()
        }
    }

    private fun ids(block: BacklogBlock?) = block?.shelves?.flatMap { shelf ->
        shelf.entries.map { it.entry.mangaId }
    }.orEmpty()

    private fun readNow(result: StartFeedResult) =
        result.blocks.filterIsInstance<ReadNowBlock>().singleOrNull()?.recommendation?.progress?.entry?.mangaId

    /** A found series led to by [keys]; only the keys matter to the blocks. */
    private fun found(mangaId: Long, vararg keys: EntityKey) =
        outside(mangaId, CatalogPick(series(mangaId, "Found $mangaId", 2022), keys.toList(), 1.0, emptyList()))

    // Two Tom King works not started: "Leia isso agora" takes one, the blocks below the other.
    private val heroes = work(5, "Heroes in Crisis (2018)", listOf(king), chapters = 9)
    private val rorschach = work(10, "Rorschach (2020)", listOf(king), chapters = 12)

    @Test
    fun `because you liked, works sharing an entity with the latest liked one, found ones included`() {
        val other = work(6, "Unrelated (2020)", listOf(writer("Someone Else")), chapters = 5)
        val all = works + heroes + rorschach + other

        val result = StartFeed.build(input(all, outside = listOf(found(900, key(king)))))

        val block = result.blocks.filterIsInstance<BecauseYouLikedBlock>().single()
        block.anchorTitle shouldBe "Supergirl: Woman of Tomorrow (2021)"
        block.picks.map { it.work.mangaId }.toSet() shouldBe (setOf(5L, 10L) - readNow(result)!!) + 900L
        block.picks.first { it.work.mangaId == 900L }.let {
            it.work.shouldBeInstanceOf<FoundWork>().work.sourceName shouldBe "Read Comics Online"
            it.link shouldBe Link(EntityKind.WRITER, "Tom King")
        }
    }

    @Test
    fun `a liked work with too little to show gives way to the one liked before it`() {
        val fraction = writer("Matt Fraction")
        // Hawkeye, liked last, shares its writer with one work not started: one work is not a shelf.
        val hawkeye = work(7, "Hawkeye (2012)", listOf(fraction), 22, read = 22, total = 22)
        val sexCriminals = work(8, "Sex Criminals (2013)", listOf(fraction), chapters = 30)
        val all = works + hawkeye + sexCriminals + heroes + rorschach

        val result = StartFeed.build(input(all, outside = listOf(found(900, key(king)))))

        result.blocks.filterIsInstance<BecauseYouLikedBlock>().single().anchorTitle shouldBe
            "Supergirl: Woman of Tomorrow (2021)"
        // The authors block then takes Matt Fraction, with that one work.
        result.blocks.filterIsInstance<CreatorsBlock>().single().let {
            it.reason.name shouldBe "Matt Fraction"
            it.picks.map { pick -> pick.work.mangaId } shouldContainExactly listOf(8L)
        }
    }

    @Test
    fun `a liked work marked read by hand, with no reading date, is weighed with the rest`() {
        // Marked read in the chapter list: no history, so no last read date.
        val byHand = work(11, "Omega Men (2015)", listOf(king), chapters = 12, read = 12, total = 12, lastReadAt = null)
        val all = works + byHand + heroes + rorschach

        val result = StartFeed.build(input(all, outside = listOf(found(900, key(king)))))

        result.blocks.filterIsInstance<BecauseYouLikedBlock>().single().anchorTitle shouldBe
            "Supergirl: Woman of Tomorrow (2021)"
    }

    @Test
    fun `authors and characters, the most read entity not leading the block above, and more of it`() {
        val fraction = writer("Matt Fraction")
        // Matt Fraction finished twice, most recently: he leads "Porque você gostou de".
        val hawkeye = work(7, "Hawkeye (2012)", listOf(fraction), chapters = 22, read = 22, total = 22)
        val casanova = work(9, "Casanova (2006)", listOf(fraction), chapters = 7, read = 7, total = 7)
        // One Tom King series being read, which no recommendation takes, and two not started.
        val begun = work(10, "Rorschach (2020)", listOf(king), chapters = 12, read = 1, lastReadAt = now - day)
        val omega = work(12, "Omega Men (2015)", listOf(king), chapters = 12)
        val all = works + hawkeye + casanova + heroes + omega + begun
        val outside = listOf(found(901, key(fraction)), found(902, key(king)), found(903, key(fraction)))

        val result = StartFeed.build(input(all, outside = outside))

        val readNow = readNow(result)!!
        setOf(5L, 12L) shouldContain readNow
        result.blocks.filterIsInstance<BecauseYouLikedBlock>().single().let {
            it.anchorTitle shouldBe "Casanova (2006)"
            it.picks.map { pick -> pick.work.mangaId }.toSet() shouldBe setOf(901L, 903L)
        }
        // Tom King reads most of the rest; Matt Fraction already leads the block above. The work
        // being read stays out, and the one not started is his block's, not the backlog's.
        val creators = result.blocks.filterIsInstance<CreatorsBlock>().single()
        creators.reason.name shouldBe "Tom King"
        creators.reason.worksCompleted shouldBe 2
        creators.picks.map { it.work.mangaId }.toSet() shouldBe (setOf(5L, 12L) - readNow) + 902L
        ids(result.blocks.filterIsInstance<BacklogBlock>().singleOrNull()) shouldNotContain
            (setOf(5L, 12L) - readNow).single()
    }

    @Test
    fun `an entity liked in one work still gets its block, for what was found of it`() {
        // One Tom King series finished and nothing of his left in the library: only the catalogue's.
        val all = listOf(mister)

        val result = StartFeed.build(input(all, outside = listOf(found(900, key(king)))))

        result.blocks.filterIsInstance<BecauseYouLikedBlock>().shouldBeEmpty() // one work is not a shelf
        result.blocks.filterIsInstance<CreatorsBlock>().single().let {
            it.reason.name shouldBe "Tom King"
            it.reason.worksRead shouldBe 1
            it.picks.map { pick -> pick.work.mangaId } shouldContainExactly listOf(900L)
        }
    }

    @Test
    fun `a creator turned down leads no block, and the works found through them go`() {
        val all = works + heroes + rorschach
        val turnedDown = Feedback(FeedbackSubject.CREATOR, "tom king", FeedbackVerdict.NOT_INTERESTED, null, now)
        val outside = listOf(found(900, key(king)))

        val result = StartFeed.build(input(all, feedback = listOf(turnedDown), outside = outside))

        result.blocks.filterIsInstance<CreatorsBlock>().map { it.reason.name } shouldNotContain "Tom King"
        shelved(result).shouldBeEmpty()
        readNow(result).shouldBeNull()
    }

    @Test
    fun `a found work the reader turned down is not shown again`() {
        val all = works + heroes + rorschach
        val outside = listOf(found(900, key(king)))
        val notNow = Feedback(FeedbackSubject.WORK, "900", FeedbackVerdict.LATER, now + day, now)

        shelved(StartFeed.build(input(all, outside = outside))) shouldContain 900L
        shelved(StartFeed.build(input(all, feedback = listOf(notNow), outside = outside))) shouldNotContain 900L
    }

    @Test
    fun `the Início says metadata would help only when most of the library has no writer`() {
        val bare = (10L..13L).map { work(it, "Bare $it", emptyList()) }

        StartFeed.missingWriters(works + bare) shouldBe 4
        StartFeed.missingWriters(works + bare.take(2)).shouldBeNull() // 2 of 5: not most, and under 3
        StartFeed.missingWriters(works).shouldBeNull()
    }

    private fun input(
        works: List<WorkFacts>,
        feedback: List<Feedback> = emptyList(),
        outside: List<OutsideWork> = emptyList(),
    ) = StartInput(
        continueIds = emptyList(),
        progress = works.filter { it.inLibrary }.map(::progress),
        collections = emptyList(),
        perIssueMs = null,
        nowMs = now,
        daySeed = 1,
        hideStalled = false,
        works = works,
        feedback = feedback,
        outside = outside,
    )
}
