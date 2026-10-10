package tachiyomi.data.sync

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.sync.SyncEntity
import tachiyomi.domain.sync.SyncField
import tachiyomi.domain.sync.SyncFields
import tachiyomi.domain.sync.SyncKey
import tachiyomi.domain.sync.SyncRecord
import tachiyomi.domain.sync.SyncSnapshot

class SyncSnapshotCodecTest {

    private val snapshot = SyncSnapshot(
        mapOf(
            SyncKey(SyncEntity.MANGA, "-6247824327199706550", "/batman") to SyncRecord(
                mapOf(
                    SyncFields.FAVORITE to SyncField("1", 1_000),
                    // Empty values and parts are values too.
                    SyncFields.NOTES to SyncField("", 0),
                ),
            ),
            SyncKey(SyncEntity.FEEDBACK, "1", "tom king", "") to SyncRecord(
                mapOf(SyncFields.ROW to SyncField("""{"verdict":1}""", 2_000)),
            ),
            SyncKey(SyncEntity.CATEGORY, "Marvel") to SyncRecord(emptyMap(), deletedAt = 3_000),
        ),
    )

    @Test
    fun `a snapshot survives the file, tombstones and empty strings included`() {
        val decoded = SyncSnapshotCodec.decode(SyncSnapshotCodec.encode(snapshot, writtenAt = 42, device = "PC"))

        decoded.snapshot shouldBe snapshot
        decoded.writtenAt shouldBe 42
        decoded.device shouldBe "PC"
    }

    @Test
    fun `the same snapshot always makes the same bytes`() {
        val reordered = SyncSnapshot(snapshot.records.entries.reversed().associate { it.key to it.value })

        SyncSnapshotCodec.encode(reordered, 0, "PC").contentEquals(SyncSnapshotCodec.encode(snapshot, 0, "PC")) shouldBe
            true
    }
}
