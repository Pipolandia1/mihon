package tachiyomi.domain.start

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.readingstats.comics.MetadataOrigin

/** Who or what a value names: the entities affinity is counted over. */
enum class EntityKind {
    WRITER,
    CHARACTER,
    ARTIST,
    TEAM,
    PUBLISHER,
    IMPRINT,
    UNIVERSE,
    GENRE,
}

/** A value of a work: its writer, a character, its publisher; with where it came from. */
data class Credit(val kind: EntityKind, val name: String, val origin: MetadataOrigin)

/** What the Início knows of a work in the library or read at all: how it was read and what it is. */
data class WorkFacts(
    val mangaId: Long,
    val title: String,
    val inLibrary: Boolean,
    val dateAdded: Long,
    val chapters: Int,
    val chaptersRead: Int,
    /** A confirmed issue total, or the chapter count when the source says publication ended. */
    val total: Int?,
    val finished: Boolean,
    val sessions: Int,
    val activeMs: Long,
    /** The last reading or opening, epoch ms; null if never. */
    val lastReadAt: Long?,
    val credits: List<Credit>,
    /** The catalogues' ids of its series, from its metadata: "metron:series:10", "gcd:series:216143". */
    val catalogIds: Set<String> = emptySet(),
)

/** What the reader said of a recommendation. Amendment H3. */
enum class FeedbackVerdict(val id: Long) {
    NOT_INTERESTED(0),

    /** Read somewhere else: known, and not to be recommended. */
    ALREADY_READ(1),

    /** Not now: comes back after [Feedback.untilAt]. */
    LATER(2),
    ;

    companion object {
        fun of(id: Long): FeedbackVerdict = entries.firstOrNull { it.id == id } ?: NOT_INTERESTED
    }
}

enum class FeedbackSubject(val id: Long) {
    WORK(0),
    CREATOR(1),
    CHARACTER(2),
    ;

    companion object {
        fun of(id: Long): FeedbackSubject = entries.firstOrNull { it.id == id } ?: WORK
    }
}

/** For a work, [key] is its manga id; for a creator or character, the normalized name. */
data class Feedback(
    val subject: FeedbackSubject,
    val key: String,
    val verdict: FeedbackVerdict,
    val untilAt: Long?,
    val createdAt: Long,
) {
    /** Whether it still keeps its subject off the Início at [nowMs]. */
    fun activeAt(nowMs: Long): Boolean = untilAt == null || nowMs < untilAt
}

/** A work's feedback, named by source and URL so a backup restores onto another device. */
data class ExportedFeedback(val sourceId: Long, val mangaUrl: String, val feedback: Feedback)

interface StartRepository {

    /** Every work in the library or with anything read, kept current as reading and metadata change. */
    fun subscribeWorks(): Flow<List<WorkFacts>>

    fun subscribeFeedback(): Flow<List<Feedback>>

    suspend fun setFeedback(feedback: Feedback)

    suspend fun removeFeedback(subject: FeedbackSubject, key: String)

    /** Feedback on works, for the backup; feedback on names travels as it is. */
    suspend fun exportFeedback(): Pair<List<ExportedFeedback>, List<Feedback>>

    /** Restores feedback, matching works by source and URL; works not on this device are skipped. */
    suspend fun importFeedback(works: List<ExportedFeedback>, names: List<Feedback>)
}
