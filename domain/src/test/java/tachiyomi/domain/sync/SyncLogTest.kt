package tachiyomi.domain.sync

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class SyncLogTest {

    @Test
    fun `the newest sync comes first and only the last twenty are kept`() {
        var log = ""
        (1..25).forEach {
            log = SyncLog.add(log, SyncLogEntry(at = it.toLong(), reason = SyncReason.READING, here = it))
        }

        val entries = SyncLog.decode(log)

        entries.size shouldBe SyncLog.MAX
        entries.first().at shouldBe 25
        entries.last().at shouldBe 6
    }

    @Test
    fun `a failure keeps what went wrong, and an unreadable log reads as empty`() {
        val log = SyncLog.add("", SyncLogEntry(1, SyncReason.FIRST, error = SyncError.DRIVE, detail = "403"))

        SyncLog.decode(log).single().error shouldBe SyncError.DRIVE
        SyncLog.decode("not json").shouldBeEmpty()
    }
}
