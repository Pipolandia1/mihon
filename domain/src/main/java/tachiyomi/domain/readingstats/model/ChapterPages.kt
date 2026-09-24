package tachiyomi.domain.readingstats.model

import java.util.BitSet

/** Sets of page indices, stored as the bytes of a [BitSet]. */
object PageBits {
    fun decode(bytes: ByteArray?): BitSet = if (bytes == null) BitSet() else BitSet.valueOf(bytes)

    fun encode(bits: BitSet): ByteArray = bits.toByteArray()
}

/** Which pages of a chapter count as already seen, and how two records of a chapter combine. */
object ChapterPages {

    /**
     * The pages of a chapter reached before now. Progress written by the reader says exactly;
     * progress carried over from before recording, or none at all, falls back to what Mihon knew:
     * a chapter marked read was seen whole, one left on a page was seen up to that page.
     */
    fun seenBefore(progress: ChapterProgress?, legacy: LegacyProgress?, totalPages: Int?): BitSet {
        if (progress?.pagesSeen != null) return PageBits.decode(progress.pagesSeen)
        return when {
            progress != null -> implied(
                completed = progress.completionOrigin != null,
                lastPage = progress.maxPageReached,
                totalPages = totalPages ?: progress.totalPages,
            )
            legacy != null -> implied(legacy.read, legacy.lastPageRead, totalPages)
            else -> BitSet()
        }
    }

    private fun implied(completed: Boolean, lastPage: Int, totalPages: Int?): BitSet {
        val bits = BitSet()
        when {
            completed && totalPages != null -> bits.set(0, totalPages)
            completed || lastPage > 0 -> if (lastPage >= 0) bits.set(0, lastPage + 1)
        }
        return bits
    }

    /**
     * Combines what a backup knows about a chapter with what this device knows: every page either
     * reached, the furthest page, and the earliest dated completion.
     */
    fun merge(local: ChapterProgress?, incoming: ChapterProgress): ChapterProgress {
        if (local == null) return incoming
        val totalPages = listOfNotNull(local.totalPages, incoming.totalPages).maxOrNull()
        val seen = if (local.pagesSeen == null && incoming.pagesSeen == null) {
            null
        } else {
            seenBefore(local, null, totalPages).apply { or(seenBefore(incoming, null, totalPages)) }
        }
        val completion = listOf(local, incoming)
            .filter { it.completionOrigin != null }
            .minWithOrNull(compareBy(nullsLast()) { it.completedAt })
        return ChapterProgress(
            chapterId = local.chapterId,
            pagesSeen = seen?.let(PageBits::encode),
            maxPageReached = maxOf(local.maxPageReached, incoming.maxPageReached),
            totalPages = totalPages,
            completedAt = completion?.completedAt,
            completedOn = completion?.completedOn,
            completionOrigin = completion?.completionOrigin,
        )
    }
}
