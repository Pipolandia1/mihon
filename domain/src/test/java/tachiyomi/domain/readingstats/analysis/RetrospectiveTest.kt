package tachiyomi.domain.readingstats.analysis

import io.kotest.matchers.shouldBe
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import tachiyomi.domain.readingstats.comics.ComicField
import tachiyomi.domain.readingstats.comics.ComicMetadata
import tachiyomi.domain.readingstats.comics.MetadataOrigin
import tachiyomi.domain.readingstats.comics.MetadataValue
import tachiyomi.domain.readingstats.comics.UnitCounts
import tachiyomi.domain.readingstats.metrics.ReadingPeriod
import tachiyomi.domain.readingstats.model.ReadingDayStats

class RetrospectiveTest {

    private val minute = 60_000L

    @Test
    fun `months and years, and stepping between them`() {
        val february = Retrospectives.periodOf(RetrospectiveKind.MONTH, LocalDate(2028, 2, 14))

        february shouldBe ReadingPeriod(LocalDate(2028, 2, 1), LocalDate(2028, 2, 29))
        Retrospectives.step(RetrospectiveKind.MONTH, february, -2) shouldBe
            ReadingPeriod(LocalDate(2027, 12, 1), LocalDate(2027, 12, 31))
        Retrospectives.periodOf(RetrospectiveKind.YEAR, LocalDate(2026, 9, 24)).days shouldBe 365
        val year = Retrospectives.periodOf(RetrospectiveKind.YEAR, LocalDate(2026, 1, 1))
        Retrospectives.step(RetrospectiveKind.YEAR, year, 1).from shouldBe LocalDate(2027, 1, 1)
    }

    @Test
    fun `a month told from its own days only`() {
        val period = Retrospectives.periodOf(RetrospectiveKind.MONTH, LocalDate(2026, 9, 1))
        val days = listOf(
            ReadingDayStats(LocalDate(2026, 8, 31), activeMs = 90 * minute),
            ReadingDayStats(LocalDate(2026, 9, 1), activeMs = 10 * minute, completedInReader = 1),
            ReadingDayStats(LocalDate(2026, 9, 2), activeMs = 20 * minute),
            ReadingDayStats(LocalDate(2026, 9, 3), activeMs = 5 * minute),
            ReadingDayStats(LocalDate(2026, 9, 10), activeMs = 15 * minute),
        )
        val meta = mapOf(
            1L to ComicMetadata(
                1,
                listOf(
                    MetadataValue(ComicField.UNIT, null, listOf("ISSUE"), MetadataOrigin.USER),
                    MetadataValue(ComicField.UNIT, 11, listOf("TPB"), MetadataOrigin.USER),
                    MetadataValue(ComicField.VARIANT, 12, listOf("Noir"), MetadataOrigin.USER),
                ),
                emptyList(),
            ),
        )
        val completed = listOf(
            CompletedChapter(10, 1),
            CompletedChapter(11, 1),
            CompletedChapter(12, 1),
            CompletedChapter(20, 2),
            CompletedChapter(10, 1),
        )

        val retro = Retrospectives.build(
            kind = RetrospectiveKind.MONTH,
            period = period,
            days = days,
            sessions = emptyList(),
            readings = listOf(ChapterReading(1, 10, "Batman", 50 * minute)),
            completed = completed,
            metadata = meta,
            firstDayOfWeek = DayOfWeek.SUNDAY,
            recordingStartedOn = LocalDate(2026, 8, 1),
        )

        retro.summary.activeMs shouldBe 50 * minute
        retro.longestDay?.date shouldBe LocalDate(2026, 9, 2)
        retro.longestStreak shouldBe 3
        retro.completedUnits shouldBe UnitCounts(issues = 1, collected = 1, variants = 1, unknown = 1)
        retro.series.entries.single().value shouldBe "Batman"
        retro.isEmpty shouldBe false
    }
}
