package eu.kanade.tachiyomi.data.backup.models

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
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

/**
 * Reading sessions, per-chapter progress, goals, plans, collections, confirmed comic metadata and
 * what the reader said of the Início's recommendations.
 * Entries and chapters are named by source and URL, not by id, so a backup restores onto any device;
 * a session whose chapter is not there is kept.
 */
@Serializable
class BackupReadingStats(
    @ProtoNumber(1) val recordingStartedAt: Long = 0,
    @ProtoNumber(2) val sessions: List<BackupReadingSession> = emptyList(),
    @ProtoNumber(3) val chapterProgress: List<BackupChapterProgress> = emptyList(),
    @ProtoNumber(4) val goals: List<BackupReadingGoal> = emptyList(),
    @ProtoNumber(5) val plans: List<BackupReadingPlan> = emptyList(),
    @ProtoNumber(6) val comicMetadata: List<BackupComicMetadata> = emptyList(),
    @ProtoNumber(7) val collections: List<BackupReadingCollection> = emptyList(),
    @ProtoNumber(8) val releaseFollows: List<BackupReleaseFollow> = emptyList(),
    @ProtoNumber(9) val startFeedback: List<BackupStartFeedback> = emptyList(),
)

/**
 * A release the reader follows (docs/HOME_PLAN.md, H7). Its key is the catalogue's — an issue, a
 * series, a character or a creator — so it restores as it is, on any device.
 */
@Serializable
class BackupReleaseFollow(
    @ProtoNumber(1) val kind: Long,
    @ProtoNumber(2) val key: String,
    @ProtoNumber(3) val label: String,
    @ProtoNumber(4) val seriesName: String? = null,
    @ProtoNumber(5) val seriesYear: Int? = null,
    @ProtoNumber(6) val number: String? = null,
    @ProtoNumber(7) val createdAt: Long,
) {
    fun toFollow() = Follow(FollowKind.of(kind), key, label, seriesName, seriesYear, number, createdAt)

    companion object {
        fun of(follow: Follow) = BackupReleaseFollow(
            kind = follow.kind.id,
            key = follow.key,
            label = follow.label,
            seriesName = follow.seriesName,
            seriesYear = follow.seriesYear,
            number = follow.number,
            createdAt = follow.createdAt,
        )
    }
}

/**
 * What the reader said of an Início recommendation (docs/HOME_PLAN.md, H3). A work is named by source
 * and URL; a creator or a character by its [key].
 */
@Serializable
class BackupStartFeedback(
    @ProtoNumber(1) val subject: Long,
    @ProtoNumber(2) val key: String,
    @ProtoNumber(3) val verdict: Long,
    @ProtoNumber(4) val untilAt: Long? = null,
    @ProtoNumber(5) val createdAt: Long,
    @ProtoNumber(6) val source: Long? = null,
    @ProtoNumber(7) val mangaUrl: String? = null,
) {
    private fun feedback() = Feedback(
        subject = FeedbackSubject.of(subject),
        key = key,
        verdict = FeedbackVerdict.of(verdict),
        untilAt = untilAt,
        createdAt = createdAt,
    )

    /** A work's feedback, or null for one on a name. */
    fun toExported(): ExportedFeedback? {
        if (source == null || mangaUrl == null) return null
        return ExportedFeedback(source, mangaUrl, feedback())
    }

    fun toNameFeedback(): Feedback? = feedback().takeIf { source == null && it.subject != FeedbackSubject.WORK }

    companion object {
        fun of(feedback: Feedback, source: Long? = null, mangaUrl: String? = null) = BackupStartFeedback(
            subject = feedback.subject.id,
            key = feedback.key,
            verdict = feedback.verdict.id,
            untilAt = feedback.untilAt,
            createdAt = feedback.createdAt,
            source = source,
            mangaUrl = mangaUrl,
        )
    }
}

/** A confirmed value of an entry, or of one of its chapters. Suggestions are not backed up. */
@Serializable
class BackupComicMetadata(
    @ProtoNumber(1) val source: Long,
    @ProtoNumber(2) val mangaUrl: String,
    /** Absent for the series. */
    @ProtoNumber(3) val chapterUrl: String? = null,
    @ProtoNumber(4) val field: Long,
    @ProtoNumber(5) val values: List<String> = emptyList(),
    @ProtoNumber(6) val origin: Long = MetadataOrigin.USER.id,
) {
    fun toExported(): ExportedMetadata? = ExportedMetadata(
        sourceId = source,
        mangaUrl = mangaUrl,
        chapterUrl = chapterUrl,
        value = MetadataValue(
            field = ComicField.of(field) ?: return null,
            chapterId = null,
            values = values,
            origin = MetadataOrigin.of(origin),
        ),
    )

    companion object {
        fun of(exported: ExportedMetadata) = BackupComicMetadata(
            source = exported.sourceId,
            mangaUrl = exported.mangaUrl,
            chapterUrl = exported.chapterUrl,
            field = exported.value.field.id,
            values = exported.value.values,
            origin = exported.value.origin.id,
        )
    }
}

/** A collection that stands on its own: plans may read it, and deleting them leaves it. */
@Serializable
class BackupReadingCollection(
    @ProtoNumber(1) val uuid: String,
    @ProtoNumber(2) val kind: Long = CollectionKind.READING_ORDER.id,
    @ProtoNumber(3) val name: String,
    @ProtoNumber(4) val createdAt: Long,
    @ProtoNumber(5) val items: List<BackupCollectionItem> = emptyList(),
) {
    fun toCollection(): Pair<ReadingCollection, List<CollectionItem>> =
        ReadingCollection(-1, uuid, CollectionKind.of(kind), name, createdAt) to
            items.map { CollectionItem(null, null, it.source, it.mangaUrl, it.chapterUrl, it.label) }

    companion object {
        fun of(collection: ReadingCollection, items: List<CollectionItem>) = BackupReadingCollection(
            uuid = collection.uuid,
            kind = collection.kind.id,
            name = collection.name,
            createdAt = collection.createdAt,
            items = items.map { BackupCollectionItem(it.sourceId, it.mangaUrl, it.chapterUrl, it.label) },
        )
    }
}

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
