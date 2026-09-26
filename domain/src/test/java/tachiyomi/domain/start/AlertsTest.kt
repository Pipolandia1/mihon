package tachiyomi.domain.start

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate

/** The alerts' rules, with the clock and the date given (docs/HOME_PLAN.md §8.3, H7). */
class AlertsTest {

    private val day = 24L * 60 * 60 * 1000
    private val wednesday = LocalDate.of(2026, 9, 23)
    private val now = wednesday.toEpochDay() * day

    private fun metron(id: Long, series: Long, name: String, number: String, date: String = "2026-09-23") =
        ReleaseItem("metron:issue:$id", "metron:series:$series", name, 2024, number, date, null)

    private val absolute24 = metron(1, 3001, "Absolute Batman", "24")
    private val absolute25 = metron(2, 3001, "Absolute Batman", "25", date = "2026-10-21")
    private val wolverine = metron(3, 4001, "Wolverine", "28")
    private val week = listOf(absolute24, absolute25, wolverine)

    private val series =
        Follow(FollowKind.SERIES, "metron:series:3001", "Absolute Batman", "Absolute Batman", 2024, createdAt = 0)
    private val issue =
        Follow(FollowKind.ISSUE, "metron:issue:3", "Wolverine #28", "Wolverine", 2024, "28", createdAt = 0)
    private val character = Follow(FollowKind.CHARACTER, "metron:character:5", "Batman", createdAt = 0)

    @Test
    fun `a follow covers its issue, its series, or what the catalogue lists for its name`() {
        Alerts.covers(issue, wolverine, emptyMap()) shouldBe true
        Alerts.covers(issue, absolute24, emptyMap()) shouldBe false
        Alerts.covers(series, absolute24, emptyMap()) shouldBe true
        Alerts.covers(series, wolverine, emptyMap()) shouldBe false
        Alerts.covers(character, absolute24, mapOf(character.key to setOf(absolute24.key))) shouldBe true
        Alerts.covers(character, wolverine, mapOf(character.key to setOf(absolute24.key))) shouldBe false
    }

    @Test
    fun `a series followed on Metron's list is covered on the GCD's, by name and year`() {
        val gcd = ReleaseItem("gcd:issue:2868581", "gcd:series:216143", "Absolute Batman", 2024, "24", null, null)

        Alerts.covers(series, gcd, emptyMap()) shouldBe true
    }

    @Test
    fun `what is out by the day is told, what comes later is not yet`() {
        // The clock of the job, given: on the Wednesday #24 is out and #25 is weeks away.
        Alerts.out(week, listOf(series, issue), emptyMap(), emptySet(), wednesday).map {
            it.release.key
        } shouldContainExactly
            listOf(absolute24.key, wolverine.key)
        Alerts.out(week, listOf(series), emptyMap(), emptySet(), wednesday.minusDays(1)).shouldBeEmpty()
        Alerts.out(week, listOf(series), emptyMap(), emptySet(), LocalDate.of(2026, 10, 21)).map {
            it.release.number
        } shouldBe
            listOf("24", "25")
    }

    @Test
    fun `nothing is told twice, however many follows cover it`() {
        val told = setOf(absolute24.key)
        val bothCover = listOf(series, character)

        Alerts.out(week, bothCover, mapOf(character.key to setOf(absolute24.key)), told, wednesday).shouldBeEmpty()
        Alerts.out(week, bothCover, mapOf(character.key to setOf(absolute24.key)), emptySet(), wednesday).single()
            .follow shouldBe series
    }

    @Test
    fun `an issue on the week's list and on a followed creator's is told once`() {
        // Seen on the emulator: Absolute Batman #24 came twice, once from Scott Snyder's issues.
        val creator = Follow(FollowKind.CREATOR, "metron:creator:7", "Scott Snyder", createdAt = 0)
        val listedTwice = week + absolute24

        Alerts.out(
            listedTwice,
            listOf(series, creator),
            mapOf(creator.key to setOf(absolute24.key)),
            emptySet(),
            wednesday,
        )
            .map { it.release.key } shouldContainExactly listOf(absolute24.key)
    }

    @Test
    fun `an issue with variant covers is told once`() {
        val regular = ReleaseItem("gcd:issue:1", "gcd:series:216143", "Absolute Batman", 2024, "24", null, null)
        val variant = regular.copy(key = "gcd:issue:2", variantOf = "gcd:issue:1")

        Alerts.out(
            listOf(regular, variant),
            listOf(series),
            emptyMap(),
            emptySet(),
            wednesday,
        ).single().release shouldBe
            regular
    }

    @Test
    fun `a release told out is looked for among the chapters for a month, until it is told available`() {
        val out = Notified(absolute24, AlertWhat.OUT, now)
        val available = Notified(wolverine, AlertWhat.AVAILABLE, now)
        val outWolverine = Notified(wolverine, AlertWhat.OUT, now)
        val old = Notified(absolute25, AlertWhat.OUT, now - 31 * day)

        Alerts.awaitingChapter(listOf(out, available, outWolverine, old), now) shouldContainExactly listOf(absolute24)
    }

    @Test
    fun `the issue is the chapter with its number`() {
        val chapters = listOf(ChapterRef(10, 23.0), ChapterRef(11, 24.0), ChapterRef(12, 2025.0))

        Alerts.chapterFor(absolute24, chapters) shouldBe ChapterRef(11, 24.0)
        Alerts.chapterFor(absolute24, chapters.take(1)).shouldBeNull()
        Alerts.chapterFor(absolute24.copy(number = "Annual 1"), chapters).shouldBeNull()
    }

    @Test
    fun `the bell shows what is followed`() {
        Alerts.followed(absolute24, listOf(series)) shouldBe true
        Alerts.followed(wolverine, listOf(series)) shouldBe false
    }
}
