package eu.kanade.tachiyomi.data.backup.models

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
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
import tachiyomi.domain.readingstats.plans.ReadingPlan

/**
 * Reading sessions, per-chapter progress, goals and plans. Entries and chapters are named by source
 * and URL, not by id, so a backup restores onto any device; a session whose chapter is not there is
 * kept.
 */
@Serializable
class BackupReadingStats(
    @ProtoNumber(1) val recordingStartedAt: Long = 0,
    @ProtoNumber(2) val sessions: List<BackupReadingSession> = emptyList(),
    @ProtoNumber(3) val chapterProgress: List<BackupChapterProgress> = emptyList(),
    @ProtoNumber(4) val goals: List<BackupReadingGoal> = emptyList(),
    @ProtoNumber(5) val plans: List<BackupReadingPlan> = emptyList(),
)

@Serializable
class BackupReadingGoal(
    @ProtoNumber(1) val metric: Long,
    @ProtoNumber(2) val period: Long,
    @ProtoNumber(3) val target: Int,
    @ProtoNumber(4) val primary: Boolean = false,
) {
    fun toGoal(): ReadingGoal? = ReadingGoal(
        metric = GoalMetric.of(metric) ?: return null,
        period = GoalPeriod.of(period) ?: return null,
        target = target,
        isPrimary = primary,
    )

    companion object {
        fun of(goal: ReadingGoal) = BackupReadingGoal(goal.metric.id, goal.period.id, goal.target, goal.isPrimary)
    }
}

@Serializable
class BackupReadingPlan(
    @ProtoNumber(1) val uuid: String,
    @ProtoNumber(2) val collectionUuid: String,
    @ProtoNumber(3) val kind: Long = CollectionKind.LIST.id,
    @ProtoNumber(4) val name: String,
    @ProtoNumber(5) val pacePerWeek: Int,
    @ProtoNumber(6) val startedAt: Long,
    @ProtoNumber(7) val startedOn: String,
    @ProtoNumber(8) val paused: Boolean = false,
    @ProtoNumber(9) val items: List<BackupCollectionItem> = emptyList(),
) {
    fun toPlan(): Pair<ReadingPlan, List<CollectionItem>> {
        val plan = ReadingPlan(
            id = -1,
            uuid = uuid,
            collectionId = -1,
            collectionUuid = collectionUuid,
            kind = CollectionKind.of(kind),
            name = name,
            pacePerWeek = pacePerWeek,
            startedAt = startedAt,
            startedOn = LocalDate.parse(startedOn),
            paused = paused,
        )
        return plan to items.map { CollectionItem(null, null, it.source, it.mangaUrl, it.chapterUrl, it.label) }
    }

    companion object {
        fun of(plan: ReadingPlan, items: List<CollectionItem>) = BackupReadingPlan(
            uuid = plan.uuid,
            collectionUuid = plan.collectionUuid,
            kind = plan.kind.id,
            name = plan.name,
            pacePerWeek = plan.pacePerWeek,
            startedAt = plan.startedAt,
            startedOn = plan.startedOn.toString(),
            paused = plan.paused,
            items = items.map { BackupCollectionItem(it.sourceId, it.mangaUrl, it.chapterUrl, it.label) },
        )
    }
}

@Serializable
class BackupCollectionItem(
    @ProtoNumber(1) val source: Long,
    @ProtoNumber(2) val mangaUrl: String,
    /** Absent for a whole entry. */
    @ProtoNumber(3) val chapterUrl: String? = null,
    @ProtoNumber(4) val label: String = "",
)

@Serializable
class BackupReadingSession(
    @ProtoNumber(1) val uuid: String,
    @ProtoNumber(2) val source: Long,
    @ProtoNumber(3) val mangaUrl: String,
    @ProtoNumber(4) val chapterUrl: String,
    @ProtoNumber(5) val mangaTitle: String,
    @ProtoNumber(6) val chapterName: String,
    @ProtoNumber(7) val chapterNumber: Double = -1.0,
    @ProtoNumber(8) val startedAt: Long,
    @ProtoNumber(9) val endedAt: Long,
    @ProtoNumber(10) val activeMs: Long = 0,
    @ProtoNumber(11) val firstPage: Int = -1,
    @ProtoNumber(12) val lastPage: Int = -1,
    @ProtoNumber(13) val pagesVisited: Int = 0,
    @ProtoNumber(14) val pagesNew: Int = 0,
    @ProtoNumber(15) val pagesReread: Int = 0,
    @ProtoNumber(16) val pageAdvances: Int = 0,
    @ProtoNumber(17) val totalPages: Int? = null,
    @ProtoNumber(18) val pagesBits: ByteArray = ByteArray(0),
    @ProtoNumber(19) val completedAt: Long? = null,
    @ProtoNumber(20) val completedOn: String? = null,
    @ProtoNumber(21) val firstCompletion: Boolean = false,
    @ProtoNumber(22) val origin: Long = ReadingOrigin.READER.id,
    @ProtoNumber(23) val state: Long = SessionState.CLOSED.id,
    @ProtoNumber(24) val zoneId: String = "",
    @ProtoNumber(25) val days: List<BackupReadingDay> = emptyList(),
) {
    fun toSnapshot() = SessionSnapshot(
        uuid = uuid,
        mangaId = null,
        chapterId = null,
        key = ChapterKey(source, mangaUrl, chapterUrl, mangaTitle, chapterName, chapterNumber),
        startedAt = startedAt,
        endedAt = endedAt,
        activeMs = activeMs,
        firstPage = firstPage,
        lastPage = lastPage,
        pagesVisited = pagesVisited,
        pagesNew = pagesNew,
        pagesReread = pagesReread,
        pageAdvances = pageAdvances,
        totalPages = totalPages,
        pagesBits = pagesBits,
        completedAt = completedAt,
        completedOn = completedOn?.let(LocalDate::parse),
        firstCompletion = firstCompletion,
        origin = ReadingOrigin.of(origin) ?: ReadingOrigin.READER,
        state = SessionState.of(state),
        zoneId = zoneId,
        days = days.map { DayTotals(LocalDate.parse(it.date), it.activeMs, it.pagesNew, it.pagesVisited) },
    )

    companion object {
        fun of(session: SessionSnapshot) = BackupReadingSession(
            uuid = session.uuid,
            source = session.key.sourceId,
            mangaUrl = session.key.mangaUrl,
            chapterUrl = session.key.chapterUrl,
            mangaTitle = session.key.mangaTitle,
            chapterName = session.key.chapterName,
            chapterNumber = session.key.chapterNumber,
            startedAt = session.startedAt,
            endedAt = session.endedAt,
            activeMs = session.activeMs,
            firstPage = session.firstPage,
            lastPage = session.lastPage,
            pagesVisited = session.pagesVisited,
            pagesNew = session.pagesNew,
            pagesReread = session.pagesReread,
            pageAdvances = session.pageAdvances,
            totalPages = session.totalPages,
            pagesBits = session.pagesBits,
            completedAt = session.completedAt,
            completedOn = session.completedOn?.toString(),
            firstCompletion = session.firstCompletion,
            origin = session.origin.id,
            state = session.state.id,
            zoneId = session.zoneId,
            days = session.days.map { BackupReadingDay(it.date.toString(), it.activeMs, it.pagesNew, it.pagesVisited) },
        )
    }
}

@Serializable
class BackupReadingDay(
    @ProtoNumber(1) val date: String,
    @ProtoNumber(2) val activeMs: Long = 0,
    @ProtoNumber(3) val pagesNew: Int = 0,
    @ProtoNumber(4) val pagesVisited: Int = 0,
)

@Serializable
class BackupChapterProgress(
    @ProtoNumber(1) val source: Long,
    @ProtoNumber(2) val mangaUrl: String,
    @ProtoNumber(3) val chapterUrl: String,
    @ProtoNumber(4) val pagesSeen: ByteArray? = null,
    @ProtoNumber(5) val maxPageReached: Int = -1,
    @ProtoNumber(6) val totalPages: Int? = null,
    @ProtoNumber(7) val completedAt: Long? = null,
    @ProtoNumber(8) val completedOn: String? = null,
    @ProtoNumber(9) val completionOrigin: Long? = null,
) {
    fun toProgress(): Pair<ChapterKey, ChapterProgress> {
        val key = ChapterKey(source, mangaUrl, chapterUrl, mangaTitle = "", chapterName = "", chapterNumber = -1.0)
        return key to ChapterProgress(
            chapterId = -1,
            pagesSeen = pagesSeen,
            maxPageReached = maxPageReached,
            totalPages = totalPages,
            completedAt = completedAt,
            completedOn = completedOn?.let(LocalDate::parse),
            completionOrigin = ReadingOrigin.of(completionOrigin),
        )
    }

    companion object {
        fun of(key: ChapterKey, progress: ChapterProgress) = BackupChapterProgress(
            source = key.sourceId,
            mangaUrl = key.mangaUrl,
            chapterUrl = key.chapterUrl,
            pagesSeen = progress.pagesSeen,
            maxPageReached = progress.maxPageReached,
            totalPages = progress.totalPages,
            completedAt = progress.completedAt,
            completedOn = progress.completedOn?.toString(),
            completionOrigin = progress.completionOrigin?.id,
        )
    }
}
