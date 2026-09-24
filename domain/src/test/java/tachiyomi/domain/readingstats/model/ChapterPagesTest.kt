package tachiyomi.domain.readingstats.model

import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import java.util.BitSet

class ChapterPagesTest {

    @Test
    fun `measured pages are taken as they are`() {
        val progress = progress(pagesSeen = bits(2, 5), maxPage = 5)

        ChapterPages.seenBefore(progress, LegacyProgress(read = true, lastPageRead = 9), 10) shouldBe bits(2, 5)
    }

    @Test
    fun `carried-over progress is seen up to where it was left, or whole if completed`() {
        ChapterPages.seenBefore(progress(maxPage = 3), null, 10) shouldBe bits(0, 1, 2, 3)
        ChapterPages.seenBefore(progress(maxPage = 3, origin = ReadingOrigin.IMPORTED), null, 5) shouldBe
            bits(0, 1, 2, 3, 4)
        ChapterPages.seenBefore(progress(maxPage = -1, origin = ReadingOrigin.MANUAL), null, null) shouldBe bits()
    }

    @Test
    fun `without progress, what Mihon knew decides`() {
        ChapterPages.seenBefore(null, LegacyProgress(read = true, lastPageRead = 0), 3) shouldBe bits(0, 1, 2)
        ChapterPages.seenBefore(null, LegacyProgress(read = false, lastPageRead = 2), 5) shouldBe bits(0, 1, 2)
        ChapterPages.seenBefore(null, LegacyProgress(read = false, lastPageRead = 0), 5) shouldBe bits()
        ChapterPages.seenBefore(null, null, 5) shouldBe bits()
    }

    @Test
    fun `merging keeps every page reached and the earliest dated completion`() {
        val local = progress(pagesSeen = bits(0, 1), maxPage = 1, completedAt = 500, origin = ReadingOrigin.READER)
        val incoming = progress(pagesSeen = bits(4), maxPage = 4, completedAt = 100, origin = ReadingOrigin.MANUAL)

        val merged = ChapterPages.merge(local, incoming)

        PageBits.decode(merged.pagesSeen) shouldBe bits(0, 1, 4)
        merged.maxPageReached shouldBe 4
        merged.completedAt shouldBe 100
        merged.completionOrigin shouldBe ReadingOrigin.MANUAL
    }

    @Test
    fun `merging prefers a dated completion to an undated one`() {
        val local = progress(maxPage = 2, origin = ReadingOrigin.IMPORTED)
        val incoming = progress(pagesSeen = bits(0), maxPage = 0, completedAt = 700, origin = ReadingOrigin.READER)

        val merged = ChapterPages.merge(local, incoming)

        merged.completedAt shouldBe 700
        merged.completionOrigin shouldBe ReadingOrigin.READER
        PageBits.decode(merged.pagesSeen) shouldBe bits(0, 1, 2)
    }

    @Test
    fun `merging into nothing keeps the incoming progress`() {
        val incoming = progress(pagesSeen = bits(1), maxPage = 1)

        ChapterPages.merge(null, incoming) shouldBe incoming
    }

    private fun progress(
        pagesSeen: BitSet? = null,
        maxPage: Int,
        completedAt: Long? = null,
        origin: ReadingOrigin? = null,
    ) = ChapterProgress(
        chapterId = 1,
        pagesSeen = pagesSeen?.let(PageBits::encode),
        maxPageReached = maxPage,
        totalPages = null,
        completedAt = completedAt,
        completedOn = completedAt?.let { LocalDate(2026, 9, 24) },
        completionOrigin = origin,
    )

    private fun bits(vararg pages: Int) = BitSet().apply { pages.forEach(::set) }
}
