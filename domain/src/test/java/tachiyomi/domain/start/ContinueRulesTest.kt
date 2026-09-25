package tachiyomi.domain.start

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** "Continuar lendo": a work read to the end leaves, a new issue brings it back (amendment H2). */
class ContinueRulesTest {

    private fun chapters(vararg read: Boolean, fetched: Long = 0) =
        read.mapIndexed { index, isRead -> ContinueChapter(index + 1L, isRead, fetched) }

    private fun candidate(
        mangaId: Long = 1,
        lastChapterId: Long,
        lastReadAt: Long = 100,
        chapters: List<ContinueChapter>,
    ) = ContinueCandidate(mangaId, lastChapterId, lastReadAt, chapters)

    @Test
    fun `the chapter left part way is resumed`() {
        val pick = ContinueRules.next(candidate(lastChapterId = 2, chapters = chapters(true, false, false)))!!

        pick.chapter.id shouldBe 2
        pick.resuming shouldBe true
        pick.newSinceRead shouldBe false
    }

    @Test
    fun `a chapter read to the end continues with the next unread one`() {
        val pick = ContinueRules.next(candidate(lastChapterId = 2, chapters = chapters(true, true, false)))!!

        pick.chapter.id shouldBe 3
        pick.resuming shouldBe false
    }

    @Test
    fun `a work read to the end leaves the list`() {
        ContinueRules.next(candidate(lastChapterId = 3, chapters = chapters(true, true, true))).shouldBeNull()
    }

    @Test
    fun `rereading an old issue of a finished series does not bring it back with a read issue as next`() {
        // Mihon's own "next" is the chapter after the last opened, read or not: #3 here.
        ContinueRules.next(candidate(lastChapterId = 2, chapters = chapters(true, true, true, true))).shouldBeNull()
    }

    @Test
    fun `issues already read after the last opened one are skipped`() {
        val pick = ContinueRules.next(candidate(lastChapterId = 1, chapters = chapters(true, true, true, false)))!!

        pick.chapter.id shouldBe 4
    }

    @Test
    fun `a new issue brings a finished work back, to the top`() {
        val finished =
            candidate(mangaId = 1, lastChapterId = 3, lastReadAt = 100, chapters = chapters(true, true, true))
        val recent = candidate(mangaId = 2, lastChapterId = 1, lastReadAt = 500, chapters = chapters(false, false))
        ContinueRules.pick(listOf(finished, recent), limit = 4).map { it.mangaId } shouldBe listOf(2L)

        val withNewIssue = finished.copy(chapters = finished.chapters + ContinueChapter(4, false, dateFetch = 900))
        val picks = ContinueRules.pick(listOf(withNewIssue, recent), limit = 4)

        picks.map { it.mangaId } shouldBe listOf(1L, 2L)
        picks.first().chapter.id shouldBe 4
        picks.first().newSinceRead shouldBe true
    }

    @Test
    fun `an unread issue older than the last reading is not a new one`() {
        val pick = ContinueRules.next(
            candidate(lastChapterId = 1, lastReadAt = 500, chapters = chapters(true, false, fetched = 100)),
        )!!

        pick.newSinceRead shouldBe false
        pick.rankAt shouldBe 500
    }

    @Test
    fun `when the chapter last opened is gone, the first unread one is next`() {
        val pick = ContinueRules.next(candidate(lastChapterId = 99, chapters = chapters(true, false)))!!

        pick.chapter.id shouldBe 2
    }

    @Test
    fun `the list is cut at the limit, most recent first`() {
        val works = (1L..6L).map {
            candidate(mangaId = it, lastChapterId = 1, lastReadAt = it * 10, chapters = chapters(false))
        }

        ContinueRules.pick(works, limit = 4).map { it.mangaId } shouldBe listOf(6L, 5L, 4L, 3L)
    }

    @Test
    fun `a work with an issue fetched after it was read is one a new issue may have brought back`() {
        ContinueRules.hasNewSinceRead(
            100,
            listOf(ContinueChapter(1, true, 50), ContinueChapter(2, false, 150)),
        ) shouldBe
            true
        ContinueRules.hasNewSinceRead(100, listOf(ContinueChapter(1, true, 50), ContinueChapter(2, true, 150))) shouldBe
            false
        ContinueRules.hasNewSinceRead(null, listOf(ContinueChapter(1, false, 150))) shouldBe false
    }
}
