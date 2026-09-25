package tachiyomi.domain.readingstats.comics

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ComicLibraryTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    private fun chapter(id: Long, name: String, read: Boolean = false, fetched: Long = 0) =
        LibraryChapter(id, read, name, -1.0, fetched)

    private fun confirmed(vararg values: MetadataValue) = values.toList()

    private fun series(field: ComicField, value: String) =
        MetadataValue(field, null, listOf(value), MetadataOrigin.USER)

    private fun ofChapter(id: Long, field: ComicField, value: String) =
        MetadataValue(field, id, listOf(value), MetadataOrigin.USER)

    private fun entry(
        id: Long = 1,
        chapters: List<LibraryChapter>,
        metadata: List<MetadataValue> = emptyList(),
        finished: Boolean = false,
        lastReadAt: Long? = null,
        added: Long = 0,
        title: String = "Entry $id",
    ) = LibraryEntry(id, title, null, finished, added, lastReadAt, chapters, ComicMetadata(id, metadata, emptyList()))

    @Test
    fun `a TPB is not an issue and a variant is not counted`() {
        val metadata = confirmed(
            series(ComicField.UNIT, "ISSUE"),
            series(ComicField.TOTAL_ISSUES, "3"),
            ofChapter(3, ComicField.VARIANT, "Noir Edition"),
            ofChapter(4, ComicField.UNIT, "TPB"),
        )
        val chapters = listOf(
            chapter(1, "#1", read = true),
            chapter(2, "#2", read = true),
            chapter(3, "#1 Noir Edition", read = true),
            chapter(4, "TPB_(Part_1)", read = true),
            chapter(5, "#3"),
        )

        val progress = ComicLibrary.progress(entry(chapters = chapters, metadata = metadata))

        progress.done shouldBe 2
        progress.total shouldBe 3
        progress.remaining shouldBe 1
        progress.next?.id shouldBe 5
        progress.status shouldBe LibraryStatus.IN_PROGRESS
        ComicLibrary.unitCounts(chapters, ComicMetadata(1, metadata, emptyList())) shouldBe
            UnitCounts(issues = 3, collected = 1, variants = 1)
    }

    @Test
    fun `reading the same issue twice counts it once against the total`() {
        val metadata = confirmed(series(ComicField.TOTAL_ISSUES, "2"))
        val chapters = listOf(chapter(1, "Issue #1", read = true), chapter(2, "#1", read = true))

        ComicLibrary.progress(entry(chapters = chapters, metadata = metadata)).done shouldBe 1
    }

    @Test
    fun `an ongoing series without a total has no percentage`() {
        val chapters = listOf(chapter(1, "#1", read = true), chapter(2, "#2"))

        val progress = ComicLibrary.progress(entry(chapters = chapters))

        progress.total.shouldBeNull()
        progress.remaining.shouldBeNull()
        progress.status shouldBe LibraryStatus.IN_PROGRESS
    }

    @Test
    fun `statuses`() {
        val unread = listOf(chapter(1, "#1"), chapter(2, "#2"))
        val allRead = listOf(chapter(1, "#1", read = true), chapter(2, "#2", read = true))

        ComicLibrary.progress(entry(chapters = unread)).status shouldBe LibraryStatus.NOT_STARTED
        ComicLibrary.progress(entry(chapters = allRead)).status shouldBe LibraryStatus.CAUGHT_UP
        ComicLibrary.progress(entry(chapters = allRead, finished = true)).status shouldBe LibraryStatus.COMPLETED
    }

    @Test
    fun `next follows series order, not the chapter list`() {
        val chapters = listOf(
            chapter(1, "#Annual 2025"),
            chapter(2, "#2"),
            chapter(3, "#1", read = true),
        )

        ComicLibrary.progress(entry(chapters = chapters)).next?.id shouldBe 2
    }

    @Test
    fun `near finish needs a known total and a few left`() {
        val closeOne = entry(1, List(5) { chapter(it + 1L, "#${it + 1}", read = it < 3) }, finished = true)
        val farOne = entry(2, List(10) { chapter(it + 10L, "#${it + 1}", read = it < 1) }, finished = true)
        val unknownTotal = entry(3, listOf(chapter(30, "#1", read = true), chapter(31, "#2")))
        val progress = listOf(closeOne, farOne, unknownTotal).map(ComicLibrary::progress)

        ComicLibrary.nearFinish(progress).map { it.entry.mangaId } shouldBe listOf(1L)
    }

    @Test
    fun `stalled series are in progress and untouched for a while`() {
        val stale = entry(1, listOf(chapter(1, "#1", read = true), chapter(2, "#2")), lastReadAt = now - 40 * day)
        val recent = entry(2, listOf(chapter(3, "#1", read = true), chapter(4, "#2")), lastReadAt = now - 2 * day)
        val neverRead = entry(3, listOf(chapter(5, "#1")))
        val progress = listOf(stale, recent, neverRead).map(ComicLibrary::progress)

        ComicLibrary.stalled(progress, now).map { it.entry.mangaId } shouldBe listOf(1L)
        ComicLibrary.daysSince(now - 40 * day, now) shouldBe 40
    }

    @Test
    fun `backlog counts unread units, unknown when unclassified`() {
        val comics = entry(
            1,
            listOf(chapter(1, "#1", read = true), chapter(2, "#2"), chapter(3, "TPB")),
            confirmed(series(ComicField.UNIT, "ISSUE"), ofChapter(3, ComicField.UNIT, "TPB")),
        )
        val manga = entry(2, listOf(chapter(4, "Chapter 1"), chapter(5, "Chapter 2")))

        ComicLibrary.backlog(listOf(comics, manga)) shouldBe UnitCounts(issues = 1, collected = 1, unknown = 2)
    }

    @Test
    fun `what came in and what was finished in a period`() {
        val entries = listOf(
            entry(
                1,
                listOf(chapter(1, "#1", fetched = 5 * day), chapter(2, "#2", fetched = 20 * day)),
                added = 5 * day,
            ),
            entry(2, listOf(chapter(3, "#1", fetched = 1 * day)), added = 1 * day),
        )

        val change = ComicLibrary.change(entries, 4 * day, 10 * day, completed = setOf(1L, 3L))

        change.entriesAdded shouldBe 1
        change.chaptersAdded shouldBe UnitCounts(unknown = 1)
        change.chaptersCompleted shouldBe UnitCounts(unknown = 2)
        ComicLibrary.change(emptyList(), 0, 1, emptySet()).chaptersAdded.total shouldBe 0
        ComicLibrary.nearFinish(emptyList()).shouldBeEmpty()
    }
}
