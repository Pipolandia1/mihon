package tachiyomi.domain.sync

import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tachiyomi.domain.readingstats.model.ChapterPages
import tachiyomi.domain.readingstats.model.ChapterProgress
import tachiyomi.domain.readingstats.model.ReadingOrigin
import java.util.Base64

/**
 * A chapter's Stats progress as a sync value. Two devices that read different pages of the same
 * chapter both count: the merge is [ChapterPages.merge], the one a backup restore uses, so every
 * page either device reached stays reached and the first completion stays the first.
 */
object SyncProgress {

    @Serializable
    private data class Value(
        val pagesSeen: String? = null,
        val maxPageReached: Int,
        val totalPages: Int? = null,
        val completedAt: Long? = null,
        val completedOn: String? = null,
        val completionOrigin: Long? = null,
    )

    private val json = Json { encodeDefaults = false }

    fun encode(progress: ChapterProgress): String = json.encodeToString(
        Value.serializer(),
        Value(
            pagesSeen = progress.pagesSeen?.let { Base64.getEncoder().encodeToString(it) },
            maxPageReached = progress.maxPageReached,
            totalPages = progress.totalPages,
            completedAt = progress.completedAt,
            completedOn = progress.completedOn?.toString(),
            completionOrigin = progress.completionOrigin?.id,
        ),
    )

    /** The progress in [value], for the chapter whose local id is [chapterId]. */
    fun decode(value: String, chapterId: Long = 0): ChapterProgress {
        val decoded = json.decodeFromString(Value.serializer(), value)
        return ChapterProgress(
            chapterId = chapterId,
            pagesSeen = decoded.pagesSeen?.let { Base64.getDecoder().decode(it) },
            maxPageReached = decoded.maxPageReached,
            totalPages = decoded.totalPages,
            completedAt = decoded.completedAt,
            completedOn = decoded.completedOn?.let(LocalDate::parse),
            completionOrigin = ReadingOrigin.of(decoded.completionOrigin),
        )
    }

    /**
     * [ChapterPages.merge] keeps the first of two completions made at the same instant; taking the
     * two values in a fixed order makes the result the same whichever device merges.
     */
    fun merge(a: String, b: String): String {
        if (a == b) return a
        val (first, second) = if (a < b) a to b else b to a
        return encode(ChapterPages.merge(decode(first), decode(second)))
    }
}
