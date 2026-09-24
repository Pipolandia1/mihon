package eu.kanade.tachiyomi.data.backup.models

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import org.junit.jupiter.api.Test
import tachiyomi.domain.readingstats.model.ChapterKey
import tachiyomi.domain.readingstats.model.ChapterProgress
import tachiyomi.domain.readingstats.model.DayTotals
import tachiyomi.domain.readingstats.model.ReadingOrigin
import tachiyomi.domain.readingstats.model.SessionSnapshot
import tachiyomi.domain.readingstats.model.SessionState

class BackupReadingStatsTest {

    private val manga = BackupManga(source = 1, url = "/m", title = "Title")

    @Test
    fun `reading stats survive a round trip`() {
        val stats = BackupReadingStats(
            recordingStartedAt = 42,
            sessions = listOf(BackupReadingSession.of(session())),
            chapterProgress = listOf(BackupChapterProgress.of(key(), progress())),
        )
        val backup = Backup(backupManga = listOf(manga), backupReadingStats = stats)

        val decoded = ProtoBuf.decodeFromByteArray(
            Backup.serializer(),
            ProtoBuf.encodeToByteArray(Backup.serializer(), backup),
        )

        val readingStats = decoded.backupReadingStats.shouldNotBeNull()
        readingStats.recordingStartedAt shouldBe 42
        val session = readingStats.sessions.single().toSnapshot()
        session.uuid shouldBe "uuid"
        session.key shouldBe key()
        session.activeMs shouldBe 90_000
        session.firstPage shouldBe -1
        session.totalPages.shouldBeNull()
        session.pagesBits.toList() shouldBe listOf<Byte>(7)
        session.completedOn shouldBe LocalDate(2026, 9, 24)
        session.state shouldBe SessionState.RECOVERED
        session.days shouldBe listOf(DayTotals(LocalDate(2026, 9, 24), 90_000, 3, 4))
        val (progressKey, progress) = readingStats.chapterProgress.single().toProgress()
        progressKey.chapterUrl shouldBe "/c"
        progress.copy(chapterId = 1) shouldBe progress()
    }

    @Test
    fun `a backup from before reading stats restores with none`() {
        val old = ProtoBuf.encodeToByteArray(OldBackup.serializer(), OldBackup(listOf(manga)))

        val decoded = ProtoBuf.decodeFromByteArray(Backup.serializer(), old)

        decoded.backupManga.single().title shouldBe "Title"
        decoded.backupReadingStats.shouldBeNull()
    }

    @Test
    fun `a backup with reading stats is still readable by a Mihon without them`() {
        val backup = Backup(
            backupManga = listOf(manga),
            backupReadingStats = BackupReadingStats(sessions = listOf(BackupReadingSession.of(session()))),
        )

        val decoded = ProtoBuf.decodeFromByteArray(
            OldBackup.serializer(),
            ProtoBuf.encodeToByteArray(Backup.serializer(), backup),
        )

        decoded.backupManga.single().title shouldBe "Title"
    }

    /** The backup as upstream Mihon writes it: the same fields, without reading stats. */
    @Serializable
    private class OldBackup(
        @ProtoNumber(1) val backupManga: List<BackupManga>,
        @ProtoNumber(2) val backupCategories: List<BackupCategory> = emptyList(),
        @ProtoNumber(101) val backupSources: List<BackupSource> = emptyList(),
        @ProtoNumber(104) val backupPreferences: List<BackupPreference> = emptyList(),
        @ProtoNumber(105) val backupSourcePreferences: List<BackupSourcePreferences> = emptyList(),
        @ProtoNumber(106) val backupExtensionStores: List<BackupExtensionStore> = emptyList(),
    )

    private fun key() = ChapterKey(1, "/m", "/c", "Title", "Chapter 1", 1.0)

    private fun session() = SessionSnapshot(
        uuid = "uuid",
        mangaId = 5,
        chapterId = 6,
        key = key(),
        startedAt = 1_000,
        endedAt = 91_000,
        activeMs = 90_000,
        firstPage = -1,
        lastPage = 2,
        pagesVisited = 4,
        pagesNew = 3,
        pagesReread = 1,
        pageAdvances = 2,
        totalPages = null,
        pagesBits = byteArrayOf(7),
        completedAt = 90_000,
        completedOn = LocalDate(2026, 9, 24),
        firstCompletion = true,
        origin = ReadingOrigin.READER,
        state = SessionState.RECOVERED,
        zoneId = "America/Sao_Paulo",
        days = listOf(DayTotals(LocalDate(2026, 9, 24), 90_000, 3, 4)),
    )

    private fun progress() = ChapterProgress(
        chapterId = 1,
        pagesSeen = byteArrayOf(7),
        maxPageReached = 2,
        totalPages = 20,
        completedAt = 90_000,
        completedOn = LocalDate(2026, 9, 24),
        completionOrigin = ReadingOrigin.READER,
    )
}
