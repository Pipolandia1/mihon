package tachiyomi.domain.readingstats.model

import kotlinx.datetime.LocalDate

/** Where a reading record came from. Each entry's [id] is what the database stores. */
enum class ReadingOrigin(val id: Long) {
    /** Measured by the reader while the user was on the page. */
    READER(0),

    /** The user marked the chapter as read; nothing was measured. */
    MANUAL(1),

    /** Carried over from what Mihon knew before reading was recorded; approximate. */
    IMPORTED(2),
    ;

    companion object {
        fun of(id: Long?): ReadingOrigin? = entries.firstOrNull { it.id == id }
    }
}

enum class SessionState(val id: Long) {
    /** Still being written by the reader. */
    OPEN(0),
    CLOSED(1),

    /** Found open after the app died; it ends at its last saved instant. */
    RECOVERED(2),
    ;

    companion object {
        fun of(id: Long): SessionState = entries.firstOrNull { it.id == id } ?: CLOSED
    }
}

/**
 * What identifies a chapter across devices and source migrations, plus how it was named when it
 * was read, so a session still reads well after its entry is gone from the library.
 */
data class ChapterKey(
    val sourceId: Long,
    val mangaUrl: String,
    val chapterUrl: String,
    val mangaTitle: String,
    val chapterName: String,
    /** Mihon's chapter number; -1 when the source gave none. */
    val chapterNumber: Double,
)

/** A chapter open in the reader: its database ids and its [key]. */
data class ChapterRef(
    val mangaId: Long,
    val chapterId: Long,
    val key: ChapterKey,
)

/** One day's share of a session, on the calendar in force when the time was spent. */
data class DayTotals(
    val date: LocalDate,
    val activeMs: Long,
    val pagesNew: Int,
    val pagesVisited: Int,
)

/**
 * A reading session as written to the database. Every figure is absolute, so writing the same
 * snapshot twice leaves the database as it was after the first write.
 */
class SessionSnapshot(
    val uuid: String,
    /** Null once the entry or the chapter is no longer in the database. */
    val mangaId: Long?,
    val chapterId: Long?,
    val key: ChapterKey,
    val startedAt: Long,
    val endedAt: Long,
    val activeMs: Long,
    val firstPage: Int,
    val lastPage: Int,
    val pagesVisited: Int,
    val pagesNew: Int,
    val pagesReread: Int,
    val pageAdvances: Int,
    val totalPages: Int?,
    /** The pages visited in this session, as [PageBits]. */
    val pagesBits: ByteArray,
    /** When the session reached the chapter's last page; null if it did not. */
    val completedAt: Long?,
    val completedOn: LocalDate?,
    /** Whether that was the first time the chapter was completed. */
    val firstCompletion: Boolean,
    val origin: ReadingOrigin,
    val state: SessionState,
    val zoneId: String,
    val days: List<DayTotals>,
)

/**
 * What is known about a chapter across all its sessions: the pages ever reached, and whether and
 * how it was first completed.
 */
data class ChapterProgress(
    val chapterId: Long,
    /** The pages ever reached, as [PageBits]; null for progress carried over from before recording. */
    val pagesSeen: ByteArray?,
    /** The furthest page index reached; -1 when none is known. */
    val maxPageReached: Int,
    val totalPages: Int?,
    val completedAt: Long?,
    val completedOn: LocalDate?,
    val completionOrigin: ReadingOrigin?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChapterProgress) return false
        return chapterId == other.chapterId &&
            (pagesSeen contentEquals other.pagesSeen) &&
            maxPageReached == other.maxPageReached &&
            totalPages == other.totalPages &&
            completedAt == other.completedAt &&
            completedOn == other.completedOn &&
            completionOrigin == other.completionOrigin
    }

    override fun hashCode(): Int {
        var result = chapterId.hashCode()
        result = 31 * result + (pagesSeen?.contentHashCode() ?: 0)
        result = 31 * result + maxPageReached
        result = 31 * result + (totalPages ?: 0)
        result = 31 * result + completedAt.hashCode()
        result = 31 * result + completedOn.hashCode()
        result = 31 * result + completionOrigin.hashCode()
        return result
    }
}

/** A session and the chapter progress it leads to, written together. */
class SessionWrite(
    val session: SessionSnapshot,
    val progress: ChapterProgress,
)

/** What the reader knows about a chapter from Mihon itself, read before this session touched it. */
data class LegacyProgress(
    val read: Boolean,
    val lastPageRead: Int,
)

/** One calendar day of reading. */
data class ReadingDayStats(
    val date: LocalDate,
    val activeMs: Long = 0,
    val pagesNew: Int = 0,
    val pagesVisited: Int = 0,
    val sessions: Int = 0,
    /** Chapters first completed in the reader on this day. */
    val completedInReader: Int = 0,
    /** Chapters marked as read by hand on this day. */
    val completedManually: Int = 0,
    /** Chapters Mihon had as read before recording began, dated by their last read. */
    val completedImported: Int = 0,
)
