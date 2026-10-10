package tachiyomi.domain.sync

import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import tachiyomi.domain.readingstats.model.ChapterProgress
import tachiyomi.domain.readingstats.model.PageBits
import tachiyomi.domain.readingstats.model.ReadingOrigin
import java.util.BitSet

/** The merge rules of docs/DESKTOP_PLAN.md §3.4, without a database. */
class SyncMergeTest {

    private val manga = SyncKey(SyncEntity.MANGA, "1", "/batman")
    private val chapter = SyncKey(SyncEntity.CHAPTER, "1", "/batman", "/batman/1")
    private val history = SyncKey(SyncEntity.HISTORY, "1", "/batman", "/batman/1")
    private val category = SyncKey(SyncEntity.CATEGORY, "DC")
    private val progress = SyncKey(SyncEntity.CHAPTER_PROGRESS, "1", "/batman", "/batman/1")

    private fun record(vararg fields: Pair<String, SyncField>, deletedAt: Long? = null) =
        SyncRecord(fields.toMap(), deletedAt)

    private fun at(time: Long, value: String) = SyncField(value, time)

    private fun snapshot(vararg records: Pair<SyncKey, SyncRecord>) = SyncSnapshot(records.toMap())

    @Test
    fun `the later change of a field wins, field by field`() {
        val pc = snapshot(chapter to record(SyncFields.READ to at(100, "1"), SyncFields.BOOKMARK to at(50, "0")))
        val phone = snapshot(chapter to record(SyncFields.READ to at(200, "0"), SyncFields.BOOKMARK to at(300, "1")))

        val merged = SyncMerge.merge(pc, phone).records.getValue(chapter)

        merged[SyncFields.READ] shouldBe "0"
        merged[SyncFields.BOOKMARK] shouldBe "1"
    }

    @Test
    fun `on a tie the greater value wins, numbers by value`() {
        val a = snapshot(chapter to record(SyncFields.READ to at(0, "0"), SyncFields.PAGE to at(0, "10")))
        val b = snapshot(chapter to record(SyncFields.READ to at(0, "1"), SyncFields.PAGE to at(0, "9")))

        val merged = SyncMerge.merge(a, b).records.getValue(chapter)

        merged[SyncFields.READ] shouldBe "1"
        merged[SyncFields.PAGE] shouldBe "10"
    }

    @Test
    fun `merging does not depend on the order, and a snapshot merged with itself is unchanged`() {
        val a = snapshot(
            chapter to record(SyncFields.READ to at(0, "0"), SyncFields.PAGE to at(5, "3")),
            history to record(SyncFields.LAST_READ to at(10, "10"), SyncFields.TIME_READ to at(10, "60000")),
            category to record(deletedAt = 40),
        )
        val b = snapshot(
            chapter to record(SyncFields.READ to at(0, "1"), SyncFields.PAGE to at(5, "7")),
            history to record(SyncFields.LAST_READ to at(20, "20"), SyncFields.TIME_READ to at(20, "30000")),
            category to record(SyncFields.ROW to at(30, """{"order":1}""")),
            manga to record(SyncFields.FAVORITE to at(1, "1")),
        )
        val c = snapshot(category to record(SyncFields.ROW to at(50, """{"order":2}""")))

        SyncMerge.merge(a, b) shouldBe SyncMerge.merge(b, a)
        SyncMerge.merge(a, a) shouldBe a
        SyncMerge.merge(SyncMerge.merge(a, b), c) shouldBe SyncMerge.merge(a, SyncMerge.merge(b, c))
    }

    @Test
    fun `a deletion drops what was written before it and is undone by what came after`() {
        val removedOnPc = snapshot(category to record(deletedAt = 100))
        val olderOnPhone = snapshot(category to record(SyncFields.ROW to at(90, """{"order":1}""")))
        val newerOnPhone = snapshot(category to record(SyncFields.ROW to at(110, """{"order":1}""")))

        SyncMerge.merge(removedOnPc, olderOnPhone).records.getValue(category).isAlive shouldBe false
        SyncMerge.merge(removedOnPc, newerOnPhone).records.getValue(category).isAlive shouldBe true
        // The tombstone stays in the file either way, for devices that have not synced yet.
        SyncMerge.merge(removedOnPc, newerOnPhone).records.getValue(category).deletedAt shouldBe 100
    }

    @Test
    fun `time read keeps the greater, last read the later, and removing history wins over older reading`() {
        val pc =
            snapshot(
                history to record(SyncFields.LAST_READ to at(100, "100"), SyncFields.TIME_READ to at(100, "600000")),
            )
        val phone =
            snapshot(
                history to record(SyncFields.LAST_READ to at(200, "200"), SyncFields.TIME_READ to at(200, "300000")),
            )
        val merged = SyncMerge.merge(pc, phone).records.getValue(history)
        merged[SyncFields.LAST_READ] shouldBe "200"
        merged[SyncFields.TIME_READ] shouldBe "600000"

        val removed = snapshot(history to record(SyncFields.LAST_READ to at(300, "0"), deletedAt = 300))
        SyncMerge.merge(SyncSnapshot(mapOf(history to merged)), removed).records.getValue(history).isAlive shouldBe
            false
    }

    @Test
    fun `chapter progress from two devices keeps every page and the first completion`() {
        fun bits(vararg pages: Int) = PageBits.encode(BitSet().apply { pages.forEach { set(it) } })
        val pc = ChapterProgress(
            chapterId = 0,
            pagesSeen = bits(0, 1, 2),
            maxPageReached = 2,
            totalPages = 6,
            completedAt = null,
            completedOn = null,
            completionOrigin = null,
        )
        val phone = ChapterProgress(
            chapterId = 0,
            pagesSeen = bits(3, 4, 5),
            maxPageReached = 5,
            totalPages = 6,
            completedAt = 500,
            completedOn = LocalDate(2026, 10, 9),
            completionOrigin = ReadingOrigin.READER,
        )
        val a = snapshot(progress to record(SyncFields.ROW to at(100, SyncProgress.encode(pc))))
        val b = snapshot(progress to record(SyncFields.ROW to at(200, SyncProgress.encode(phone))))

        val merged = SyncMerge.merge(a, b)
        merged shouldBe SyncMerge.merge(b, a)
        val result = SyncProgress.decode(merged.records.getValue(progress)[SyncFields.ROW]!!)
        PageBits.decode(result.pagesSeen).cardinality() shouldBe 6
        result.maxPageReached shouldBe 5
        result.completedAt shouldBe 500
        result.completionOrigin shouldBe ReadingOrigin.READER
    }

    @Test
    fun `recording started at the earlier of the two`() {
        val key = SyncKey(SyncEntity.STATS_META, "recording_started_at")
        val a = snapshot(key to record(SyncFields.VALUE to at(900, "1000")))
        val b = snapshot(key to record(SyncFields.VALUE to at(100, "2000")))

        SyncMerge.merge(a, b).records.getValue(key)[SyncFields.VALUE] shouldBe "1000"
    }

    @Test
    fun `changes count what was added, changed and removed, not tombstones of things never seen`() {
        val before = snapshot(
            chapter to record(SyncFields.READ to at(1, "0")),
            category to record(SyncFields.ROW to at(1, "{}")),
        )
        val after = snapshot(
            chapter to record(SyncFields.READ to at(2, "1")),
            category to record(deletedAt = 5),
            manga to record(SyncFields.FAVORITE to at(3, "1")),
            SyncKey(SyncEntity.CATEGORY, "Marvel") to record(deletedAt = 4),
        )

        val changes = SyncChanges.between(before, after)

        changes.counts[SyncEntity.CHAPTER] shouldBe SyncChanges.Count(changed = 1)
        changes.counts[SyncEntity.CATEGORY] shouldBe SyncChanges.Count(removed = 1)
        changes.counts[SyncEntity.MANGA] shouldBe SyncChanges.Count(added = 1)
        SyncChanges.between(after, after).isEmpty shouldBe true
    }
}
