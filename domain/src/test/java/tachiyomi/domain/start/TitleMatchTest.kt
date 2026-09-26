package tachiyomi.domain.start

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Strict matching of a catalogue series with a title in a source (docs/HOME_PLAN.md §7.3, H4). */
class TitleMatchTest {

    // Titles as the emulator's sources list them (25/09/2026).
    private val sourceTitles = listOf(
        "Batman: The Long Halloween",
        "Batman (2016-2020)",
        "Batman (2025-)",
        "Absolute Batman (2024-)",
        "Flashpoint: The World of Flashpoint",
        "Flashpoint (2011)",
        "Flashpoint Beyond (2022)",
    )

    private fun best(name: String, year: Int?) = TitleMatch.best(name, year, sourceTitles) { it }

    @Test
    fun `the year in brackets is set aside and compared`() {
        TitleMatch.key("Absolute Batman (2024-)") shouldBe TitleKey("absolute batman", 2024)
        TitleMatch.key("Batman (2016-2020)") shouldBe TitleKey("batman", 2016)
        TitleMatch.key("Flashpoint [2011]") shouldBe TitleKey("flashpoint", 2011)
        TitleMatch.key("Absolute Batman (2024 series)") shouldBe TitleKey("absolute batman", 2024)
        TitleMatch.key("Batman: The Long Halloween") shouldBe TitleKey("batman the long halloween", null)
    }

    @Test
    fun `punctuation, accents, volume and a leading article do not tell series apart`() {
        TitleMatch.key("The Walking Dead").name shouldBe TitleMatch.key("Walking Dead").name
        TitleMatch.key("Batman Vol. 3 (2016)") shouldBe TitleKey("batman", 2016)
        TitleMatch.key("Batman & Robin").name shouldBe TitleMatch.key("Batman and Robin").name
        TitleMatch.key("Spider-Man: Blue").name shouldBe "spider man blue"
        TitleMatch.key("Asterix, o Gaulês").name shouldBe "asterix o gaules"
    }

    @Test
    fun `the catalogue series is found with its year among the real titles`() {
        best("Absolute Batman", 2024) shouldBe "Absolute Batman (2024-)"
        best("Batman", 2025) shouldBe "Batman (2025-)"
        best("Batman", 2016) shouldBe "Batman (2016-2020)"
        best("Flashpoint", 2011) shouldBe "Flashpoint (2011)"
    }

    @Test
    fun `a different year is a different series`() {
        best("Batman", 2011).shouldBeNull()
        best("Absolute Batman", 2022).shouldBeNull()
    }

    @Test
    fun `an equal name a year off is the series, as a first cover and a first sale date it`() {
        // Seen on the emulator (26/09/2026): Metron dates it 2025, Read Comics Online 2024.
        TitleMatch.best("Justice League Unlimited", 2025, listOf("Justice League Unlimited (2024-)")) { it } shouldBe
            "Justice League Unlimited (2024-)"
        // The year itself still wins, and a year off both ways is no answer.
        TitleMatch.best("Batman", 2025, listOf("Batman (2024)", "Batman (2025-)")) { it } shouldBe "Batman (2025-)"
        TitleMatch.best("Batman", 2025, listOf("Batman (2024)", "Batman (2026)")) { it }.shouldBeNull()
        // A nearly equal name still needs the very year.
        TitleMatch.best("Absolute Batman", 2025, listOf("Absolut Batman (2024)")) { it }.shouldBeNull()
    }

    @Test
    fun `a longer title that starts the same is not the series`() {
        val longer = listOf("Flashpoint: The World of Flashpoint", "Flashpoint Beyond (2022)")

        TitleMatch.best("Flashpoint", null, longer) { it }.shouldBeNull()
        TitleMatch.best("Batman", 2025, listOf("Batman: The Long Halloween")) { it }.shouldBeNull()
    }

    @Test
    fun `without a year, two series with the name are no answer, and one is`() {
        best("Batman", null).shouldBeNull() // 2016 and 2025
        best("Flashpoint", null) shouldBe "Flashpoint (2011)"
    }

    @Test
    fun `a nearly equal name counts only with the same year`() {
        TitleMatch.best("Absolute Batman", 2024, listOf("Absolut Batman (2024)")) { it } shouldBe
            "Absolut Batman (2024)"
        TitleMatch.best("Absolute Batman", null, listOf("Absolut Batman (2024)")) { it }.shouldBeNull()
        TitleMatch.best("Absolute Batman", 2024, listOf("Absolut Batman")) { it }.shouldBeNull()
    }

    @Test
    fun `an equal name with the year beats one without, and a near one`() {
        val titles = listOf("Absolut Batman (2024)", "Absolute Batman", "Absolute Batman (2024-)")

        TitleMatch.best("Absolute Batman", 2024, titles) { it } shouldBe "Absolute Batman (2024-)"
    }

    @Test
    fun `similarity is one minus the edits over the longer name`() {
        TitleMatch.similarity("batman", "batman") shouldBe 1.0
        TitleMatch.similarity("absolute batman", "absolut batman") shouldBe (1.0 - 1.0 / 15)
        TitleMatch.similarity("batman", "") shouldBe 0.0
    }
}
