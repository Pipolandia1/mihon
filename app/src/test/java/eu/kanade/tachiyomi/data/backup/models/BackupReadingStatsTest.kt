package eu.kanade.tachiyomi.data.backup.models

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import org.junit.jupiter.api.Test
import tachiyomi.domain.readingstats.comics.ComicField
import tachiyomi.domain.readingstats.comics.MetadataOrigin
import tachiyomi.domain.readingstats.comics.MetadataValue
import tachiyomi.domain.readingstats.goals.GoalMetric
import tachiyomi.domain.readingstats.goals.GoalPeriod
import tachiyomi.domain.readingstats.goals.ReadingGoal
import tachiyomi.domain.readingstats.model.ChapterKey
import tachiyomi.domain.readingstats.model.ChapterProgress
import tachiyomi.domain.readingstats.model.DayTotals
import tachiyomi.domain.readingstats.model.ReadingOrigin
import tachiyomi.domain.readingstats.model.SessionSnapshot
import tachiyomi.domain.readingstats.model.SessionState
import tachiyomi.domain.readingstats.plans.CollectionItem
import tachiyomi.domain.readingstats.plans.CollectionKind
import tachiyomi.domain.readingstats.plans.ReadingCollection
import tachiyomi.domain.readingstats.plans.ReadingPlan
import tachiyomi.domain.readingstats.repository.ExportedMetadata
import tachiyomi.domain.start.ExportedFeedback
import tachiyomi.domain.start.Feedback
import tachiyomi.domain.start.FeedbackSubject
import tachiyomi.domain.start.FeedbackVerdict
import tachiyomi.domain.start.Follow
import tachiyomi.domain.start.FollowKind

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
    fun `goals and plans survive a round trip`() {
        val goal = ReadingGoal(GoalMetric.MINUTES, GoalPeriod.WEEK, target = 120, isPrimary = true)
        val plan = ReadingPlan(
            id = 3,
            uuid = "plan",
            collectionId = 4,
            collectionUuid = "list",
            kind = CollectionKind.LIST,
            name = "Run",
            pacePerWeek = 5,
            startedAt = 99,
            startedOn = LocalDate(2026, 9, 20),
            paused = true,
        )
        val items = listOf(
            CollectionItem(7, null, 1, "/m", null, "Series"),
            CollectionItem(8, 9, 2, "/n", "/c", "Issue"),
        )
        val stats = BackupReadingStats(
            goals = listOf(BackupReadingGoal.of(goal)),
            plans = listOf(BackupReadingPlan.of(plan, items)),
        )

        val decoded = ProtoBuf.decodeFromByteArray(
            BackupReadingStats.serializer(),
            ProtoBuf.encodeToByteArray(BackupReadingStats.serializer(), stats),
        )

        decoded.goals.single().toGoal() shouldBe goal
        val (restored, restoredItems) = decoded.plans.single().toPlan()
        restored shouldBe plan.copy(id = -1, collectionId = -1)
        restoredItems shouldBe items.map { it.copy(mangaId = null, chapterId = null) }
    }

    @Test
    fun `comic metadata and collections survive a round trip`() {
        val series = ExportedMetadata(
            1,
            "/m",
            null,
            MetadataValue(ComicField.PUBLISHER, null, listOf("DC Comics"), MetadataOrigin.GCD),
        )
        val chapter = ExportedMetadata(
            1,
            "/m",
            "/c",
            MetadataValue(ComicField.CHARACTER, null, listOf("Batman", "Robin"), MetadataOrigin.USER),
        )
        val collection = ReadingCollection(5, "arc", CollectionKind.ARC, "Night of the Owls", 77)
        val items = listOf(CollectionItem(8, 9, 2, "/n", "/c", "Nightwing · #7"))
        val stats = BackupReadingStats(
            comicMetadata = listOf(BackupComicMetadata.of(series), BackupComicMetadata.of(chapter)),
            collections = listOf(BackupReadingCollection.of(collection, items)),
        )

        val decoded = ProtoBuf.decodeFromByteArray(
            BackupReadingStats.serializer(),
            ProtoBuf.encodeToByteArray(BackupReadingStats.serializer(), stats),
        )

        decoded.comicMetadata.mapNotNull { it.toExported() } shouldBe listOf(series, chapter)
        val (restored, restoredItems) = decoded.collections.single().toCollection()
        restored shouldBe collection.copy(id = -1)
        restoredItems shouldBe items.map { it.copy(mangaId = null, chapterId = null) }
    }

    @Test
    fun `a backup from before comic metadata restores with none`() {
        val old = BackupReadingStats(goals = listOf(BackupReadingGoal(GoalMetric.PAGES.id, GoalPeriod.DAY.id, 5)))

        val decoded = ProtoBuf.decodeFromByteArray(
            BackupReadingStats.serializer(),
            ProtoBuf.encodeToByteArray(BackupReadingStats.serializer(), old),
        )

        decoded.comicMetadata shouldBe emptyList()
        decoded.collections shouldBe emptyList()
    }

    @Test
    fun `what the reader said of recommendations survives a round trip`() {
        val work = Feedback(FeedbackSubject.WORK, "12", FeedbackVerdict.LATER, untilAt = 99, createdAt = 5)
        val name = Feedback(FeedbackSubject.CREATOR, "tom king", FeedbackVerdict.NOT_INTERESTED, null, 6)
        val stats = BackupReadingStats(
            startFeedback = listOf(
                BackupStartFeedback.of(work, source = 1, mangaUrl = "/series/a"),
                BackupStartFeedback.of(name),
            ),
        )

        val decoded = ProtoBuf.decodeFromByteArray(
            BackupReadingStats.serializer(),
            ProtoBuf.encodeToByteArray(BackupReadingStats.serializer(), stats),
        )

        decoded.startFeedback.mapNotNull { it.toExported() } shouldBe listOf(ExportedFeedback(1, "/series/a", work))
        decoded.startFeedback.mapNotNull { it.toNameFeedback() } shouldBe listOf(name)
        // A backup from before the Início has none.
        ProtoBuf.decodeFromByteArray(
            BackupReadingStats.serializer(),
            ProtoBuf.encodeToByteArray(BackupReadingStats.serializer(), BackupReadingStats()),
        ).startFeedback shouldBe emptyList()
    }

    @Test
    fun `the releases followed survive a round trip, as the catalogue names them`() {
        val series =
            Follow(FollowKind.SERIES, "metron:series:3001", "Absolute Batman", "Absolute Batman", 2024, null, 5)
        val creator = Follow(FollowKind.CREATOR, "metron:creator:7", "Scott Snyder", createdAt = 6)
        val stats = BackupReadingStats(releaseFollows = listOf(series, creator).map(BackupReleaseFollow::of))

        val decoded = ProtoBuf.decodeFromByteArray(
            BackupReadingStats.serializer(),
            ProtoBuf.encodeToByteArray(BackupReadingStats.serializer(), stats),
        )

        decoded.releaseFollows.map { it.toFollow() } shouldBe listOf(series, creator)
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
