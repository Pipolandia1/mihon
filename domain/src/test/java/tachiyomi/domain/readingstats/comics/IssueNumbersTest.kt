package tachiyomi.domain.readingstats.comics

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class IssueNumbersTest {

    @Test
    fun `plain issue numbers`() {
        IssueNumbers.parse("#163", 163.0).let {
            it.kind shouldBe IssueKind.REGULAR
            it.number shouldBe 163.0
            it.label shouldBe "#163"
            it.unit shouldBe ComicUnit.ISSUE
            it.variant.shouldBeNull()
        }
        IssueNumbers.parse("Issue #1", 1.0).let {
            it.number shouldBe 1.0
            it.label shouldBe "#1"
            it.unit shouldBe ComicUnit.ISSUE
        }
        IssueNumbers.parse("Issue 12").number shouldBe 12.0
    }

    @Test
    fun `a variant keeps the issue number and names what varies`() {
        val parsed = IssueNumbers.parse("#1 Noir Edition", 1.0)

        parsed.kind shouldBe IssueKind.REGULAR
        parsed.number shouldBe 1.0
        parsed.variant shouldBe "Noir Edition"
        parsed.label shouldBe "#1"
    }

    @Test
    fun `a story title after the number is not a variant`() {
        val parsed = IssueNumbers.parse("#12 - The Court of Owls", 12.0)

        parsed.number shouldBe 12.0
        parsed.variant.shouldBeNull()
    }

    @Test
    fun `an annual is not issue 2025`() {
        // Mihon reads "#Annual 2025" as chapter 2025.
        val parsed = IssueNumbers.parse("#Annual 2025", 2025.0)

        parsed.kind shouldBe IssueKind.ANNUAL
        parsed.number shouldBe 2025.0
        parsed.label shouldBe "Annual 2025"
    }

    @Test
    fun `collected editions are named by their unit`() {
        IssueNumbers.parse("TPB_(Part_1)", 1.0).let {
            it.kind shouldBe IssueKind.COLLECTED
            it.unit shouldBe ComicUnit.TPB
            it.part shouldBe 1
            it.label shouldBe "TPB 1"
        }
        IssueNumbers.parse("Vol. 2").let {
            it.unit shouldBe ComicUnit.TPB
            it.label shouldBe "Vol. 2"
        }
        IssueNumbers.parse("TPB 2").let {
            it.number shouldBe 2.0
            it.label shouldBe "TPB 2"
        }
        listOf("TPB 2", "TPB 1").sortedWith(compareBy(IssueNumbers.order) { IssueNumbers.parse(it) }) shouldBe
            listOf("TPB 1", "TPB 2")
        IssueNumbers.parse("HC #3").number shouldBe 3.0
        IssueNumbers.parse("Deluxe Edition Book 1").unit shouldBe ComicUnit.HARDCOVER
        IssueNumbers.parse("Omnibus").unit shouldBe ComicUnit.OMNIBUS
    }

    @Test
    fun `fractions and suffixes`() {
        IssueNumbers.parse("#1/2").let {
            it.number shouldBe 0.5
            it.label shouldBe "#1/2"
        }
        IssueNumbers.parse("#1.MU").let {
            it.number shouldBe 1.0
            it.suffix shouldBe ".MU"
            it.label shouldBe "#1.MU"
        }
    }

    @Test
    fun `specials keep their name`() {
        val parsed = IssueNumbers.parse("Free Comic Book Day 2024")

        parsed.kind shouldBe IssueKind.SPECIAL
        parsed.label shouldBe "Free Comic Book Day 2024"
    }

    @Test
    fun `a name without a number falls back to the chapter number`() {
        IssueNumbers.parse("Prologue", 3.0).number shouldBe 3.0
        IssueNumbers.parse("Prologue").number.shouldBeNull()
    }

    @Test
    fun `series order puts issues first, then annuals, then collected editions`() {
        val names = listOf("TPB_(Part_1)", "#Annual 2025", "#2", "#1 Noir Edition", "#1", "#1/2", "#10")

        val ordered = names.sortedWith(compareBy(IssueNumbers.order) { IssueNumbers.parse(it) })

        ordered shouldBe listOf("#1/2", "#1", "#1 Noir Edition", "#2", "#10", "#Annual 2025", "TPB_(Part_1)")
    }
}
