package tachiyomi.domain.start

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReadingTimeTest {

    private val minute = 60_000L

    private fun times(vararg minutes: Long) = minutes.mapIndexed { i, m -> IssueTime(i.toLong(), 1, m * minute) }

    @Test
    fun `below three measured issues there is no figure`() {
        ReadingTime.perIssueMs(times(8, 9)).shouldBeNull()
        ReadingTime.perIssueMs(times(8, 9, 0)).shouldBeNull()
    }

    @Test
    fun `the time per issue is the median, so one long sitting does not swing it`() {
        ReadingTime.perIssueMs(times(8, 9, 60)) shouldBe 9 * minute
        ReadingTime.perIssueMs(times(8, 10, 12, 60)) shouldBe 11 * minute
    }

    @Test
    fun `minutes are shown whole up to ten, then to the nearest five, never zero`() {
        ReadingTime.displayMinutes(10_000) shouldBe 1
        ReadingTime.displayMinutes(7 * minute + 20_000) shouldBe 7
        ReadingTime.displayMinutes(33 * minute) shouldBe 35
        ReadingTime.displayMinutes(32 * minute) shouldBe 30
        ReadingTime.displayMinutes(81 * minute) shouldBe 80
    }

    @Test
    fun `how many issues fit in a time`() {
        ReadingTime.issuesIn(30, 9 * minute) shouldBe 3
        ReadingTime.issuesIn(10, 12 * minute) shouldBe 0
    }
}
