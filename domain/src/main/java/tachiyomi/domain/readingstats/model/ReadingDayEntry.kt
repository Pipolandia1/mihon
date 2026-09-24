package tachiyomi.domain.readingstats.model

import tachiyomi.domain.manga.model.MangaCover

/** A line of one day's reading history. */
sealed interface ReadingDayEntry {
    /** Null once the entry is gone from the database; [title] still names it. */
    val mangaId: Long?
    val title: String
    val chapterName: String
    val chapterNumber: Double
    val cover: MangaCover?

    /** Ordering key: the moment the entry happened. */
    val at: Long

    /** A measured reading session; the figures are this day's share of it. */
    data class Session(
        val sessionId: Long,
        override val mangaId: Long?,
        val chapterId: Long?,
        override val title: String,
        override val chapterName: String,
        override val chapterNumber: Double,
        override val cover: MangaCover?,
        val startedAt: Long,
        val endedAt: Long,
        val dayActiveMs: Long,
        val dayPagesNew: Int,
        val dayPagesVisited: Int,
        val totalPages: Int?,
        /** The session reached the chapter's last page on this day. */
        val finishedChapter: Boolean,
        /** ...and that was the first time the chapter was finished. */
        val firstCompletion: Boolean,
        /** Ended by the app dying rather than by leaving the reader. */
        val recovered: Boolean,
    ) : ReadingDayEntry {
        override val at: Long get() = startedAt
    }

    /** A chapter marked as read by hand, or carried over from before recording. */
    data class Completion(
        override val mangaId: Long,
        val chapterId: Long,
        override val title: String,
        override val chapterName: String,
        override val chapterNumber: Double,
        override val cover: MangaCover?,
        override val at: Long,
        val origin: ReadingOrigin,
    ) : ReadingDayEntry

    /** A chapter Mihon last opened on this day, before reading was recorded: no time, no pages. */
    data class Legacy(
        override val mangaId: Long,
        val chapterId: Long,
        override val title: String,
        override val chapterName: String,
        override val chapterNumber: Double,
        override val cover: MangaCover?,
        override val at: Long,
    ) : ReadingDayEntry
}
