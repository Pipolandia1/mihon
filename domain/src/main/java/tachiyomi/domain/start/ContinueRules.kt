package tachiyomi.domain.start

/** A chapter as "Continuar lendo" needs it: in the series' reading order. */
data class ContinueChapter(
    val id: Long,
    val read: Boolean,
    /** When Mihon first saw the chapter, epoch ms: a new issue's arrival. */
    val dateFetch: Long,
)

/** A work of the history: the chapter last opened, when, and all its chapters in reading order. */
data class ContinueCandidate(
    val mangaId: Long,
    val lastChapterId: Long,
    val lastReadAt: Long,
    val chapters: List<ContinueChapter>,
)

data class ContinuePick(
    val mangaId: Long,
    val chapter: ContinueChapter,
    /** The chapter last opened, left unfinished: open it where it was left. */
    val resuming: Boolean,
    /** The chapter arrived after the reader last read the work: a new issue brought it back. */
    val newSinceRead: Boolean,
    /** What orders the list: the last reading, or a new issue's arrival if later. */
    val rankAt: Long,
)

/**
 * Which works "Continuar lendo" shows, and which chapter each opens. Amendment H2.
 *
 * A work is there only while something of it is left to read: the chapter last opened if it was
 * left unfinished, otherwise the first unread one after it. A work read to the end leaves the list,
 * and comes back, at the top, when a new issue arrives. Mihon's own "next chapter" is the one after
 * the last opened whether or not it was read, so a series reread or marked read by hand stayed on
 * the list with an issue already read as "next".
 */
object ContinueRules {

    fun next(candidate: ContinueCandidate): ContinuePick? {
        val chapters = candidate.chapters
        val from = chapters.indexOfFirst { it.id == candidate.lastChapterId }
        val current = chapters.getOrNull(from)
        val chapter = if (current != null && !current.read) {
            current
        } else {
            // When the last chapter opened is gone (the source removed it), from is -1 and the
            // whole series is looked at.
            chapters.drop(from + 1).firstOrNull { !it.read } ?: return null
        }
        val resuming = chapter.id == candidate.lastChapterId
        val newSinceRead = !resuming && chapter.dateFetch > candidate.lastReadAt
        return ContinuePick(
            mangaId = candidate.mangaId,
            chapter = chapter,
            resuming = resuming,
            newSinceRead = newSinceRead,
            rankAt = if (newSinceRead) chapter.dateFetch else candidate.lastReadAt,
        )
    }

    /**
     * Whether a work has an unread chapter that arrived after it was last read: one a new issue may
     * have brought back, however long ago it was read.
     */
    fun hasNewSinceRead(lastReadAt: Long?, chapters: List<ContinueChapter>): Boolean =
        lastReadAt != null && chapters.any { !it.read && it.dateFetch > lastReadAt }

    /** Up to [limit] works to continue, the most recent first. */
    fun pick(candidates: List<ContinueCandidate>, limit: Int): List<ContinuePick> =
        candidates.mapNotNull(::next)
            .sortedByDescending { it.rankAt }
            .take(limit)
}
